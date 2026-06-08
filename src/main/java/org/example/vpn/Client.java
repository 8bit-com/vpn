package org.example.vpn;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Client {

    private static final String SERVER_HOST = "80.240.23.72";
    private static final int SERVER_PORT = 443;

    private static final String ADAPTER_NAME = "MyVPN";
    private static final String CLIENT_IP = "10.0.0.123";
    private static final String CLIENT_MASK = "255.255.255.0";
    private static final String SERVER_TUN_IP = "10.0.0.1";

    private static final List<String> TEST_EXTERNAL_IPS = List.of(
            "1.1.1.1",
            "8.8.8.8",
            "93.184.216.34",
            "142.250.185.14"
    );

    private static final int WINTUN_RING_SIZE = 0x400000;
    private static final int MTU = 1200;
    private static final int LOG_EVERY_PACKETS = 1;
    private static final int MAX_PACKET_SIZE = 65535;

    private final AtomicLong tcpToWintunCounter = new AtomicLong();
    private final AtomicLong wintunToTcpCounter = new AtomicLong();
    private final AtomicLong wintunRawCounter = new AtomicLong();
    private final AtomicLong wintunDropCounter = new AtomicLong();

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        cleanupAdapterConfig();
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupAdapterConfig, "myvpn-cleanup"));

        Pointer session = startWintun();

        configureMyVpnIp();
        configureMtu();
        configureTestRoutes();

        Socket socket = createTcpSocket();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        startWintunToTcp(out, session);

        System.out.println("TEST ROUTES MODE READY");
        System.out.println("CHECK INTERNAL: ping " + SERVER_TUN_IP);
        for (String ip : TEST_EXTERNAL_IPS) {
            System.out.println("CHECK EXTERNAL: ping " + ip);
        }
        System.out.println("CHECK CURL: curl http://93.184.216.34");

        receiveTcpAndWriteToWintun(in, session);
    }

    private Pointer startWintun() {

        Pointer adapter = Wintun.INSTANCE.WintunCreateAdapter(new WString(ADAPTER_NAME), new WString("VPN"), null);

        if (adapter == null) {
            throw new RuntimeException("WintunCreateAdapter failed");
        }

        Pointer session = Wintun.INSTANCE.WintunStartSession(adapter, WINTUN_RING_SIZE);

        if (session == null) {
            throw new RuntimeException("WintunStartSession failed");
        }

        System.out.println("WINTUN STARTED");

        return session;
    }

    private Socket createTcpSocket() throws Exception {

        Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);

        System.out.println("TCP SOCKET CONNECTED TO " + SERVER_HOST + ":" + SERVER_PORT);

        return socket;
    }

    private void configureMyVpnIp() throws Exception {

        runCommand("netsh", "interface", "ip", "set", "address", "name=" + ADAPTER_NAME, "static", CLIENT_IP, CLIENT_MASK);

        System.out.println("MYVPN IP CONFIGURED: " + CLIENT_IP + "/24");
    }

    private void configureMtu() throws Exception {

        runCommand("netsh", "interface", "ipv4", "set", "subinterface", ADAPTER_NAME, "mtu=" + MTU, "store=active");

        System.out.println("MYVPN MTU CONFIGURED: " + MTU);
    }

    private void configureTestRoutes() throws Exception {

        String interfaceIndex = getMyVpnInterfaceIndex();

        for (String ip : TEST_EXTERNAL_IPS) {
            runCommandIgnoreError("route", "delete", ip);
            runCommand("route", "add", ip, "mask", "255.255.255.255", "0.0.0.0", "metric", "1", "if", interfaceIndex);
            System.out.println("MYVPN TEST ROUTE CONFIGURED: " + ip + " ON-LINK IF " + interfaceIndex);
        }
    }

    private String getMyVpnInterfaceIndex() throws Exception {

        Process process = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-Command",
                "(Get-NetIPAddress -IPAddress '" + CLIENT_IP + "' -AddressFamily IPv4 -ErrorAction Stop).InterfaceIndex"
        ).redirectErrorStream(true).start();

        String interfaceIndex;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            interfaceIndex = reader.readLine();
        }

        int code = process.waitFor();

        if (code != 0 || interfaceIndex == null || interfaceIndex.trim().isEmpty()) {
            throw new RuntimeException("Cannot detect MyVPN interface index");
        }

        return interfaceIndex.trim();
    }

    private void cleanupAdapterConfig() {

        for (String ip : TEST_EXTERNAL_IPS) {
            runCommandIgnoreError("route", "delete", ip);
        }

        runCommandIgnoreError("netsh", "interface", "ip", "delete", "address", "name=" + ADAPTER_NAME, "addr=" + CLIENT_IP);

        System.out.println("MYVPN CLEANUP DONE");
    }

    private void startWintunToTcp(DataOutputStream out, Pointer session) {

        Thread thread = new Thread(() -> readWintunAndSendTcp(out, session), "wintun-to-tcp");
        thread.setDaemon(true);
        thread.start();
    }

    private void readWintunAndSendTcp(DataOutputStream out, Pointer session) {

        while (true) {

            try {
                IntByReference size = new IntByReference();

                Pointer packet = Wintun.INSTANCE.WintunReceivePacket(session, size);

                if (packet == null) {
                    Thread.sleep(1);
                    continue;
                }

                byte[] data = packet.getByteArray(0, size.getValue());

                Wintun.INSTANCE.WintunReleaseReceivePacket(session, packet);

                logEvery(wintunRawCounter, "WINTUN RAW", data);

                if (!shouldSendToServer(data)) {
                    logEvery(wintunDropCounter, "WINTUN DROP", data);
                    continue;
                }

                synchronized (out) {
                    out.writeInt(data.length);
                    out.write(data);
                    out.flush();
                }

                logEvery(wintunToTcpCounter, "WINTUN -> TCP", data);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void receiveTcpAndWriteToWintun(DataInputStream in, Pointer session) {

        while (true) {

            try {
                int len = in.readInt();

                if (len <= 0 || len > MAX_PACKET_SIZE) {
                    throw new RuntimeException("bad tcp frame length: " + len);
                }

                byte[] data = in.readNBytes(len);

                if (data.length != len) {
                    throw new RuntimeException("tcp frame truncated: " + data.length + "/" + len);
                }

                writeToWintun(session, data);

                logEvery(tcpToWintunCounter, "TCP -> WINTUN", data);

            } catch (Exception e) {
                e.printStackTrace();
                sleep(1000);
            }
        }
    }

    private void writeToWintun(Pointer session, byte[] data) {

        Pointer packet = Wintun.INSTANCE.WintunAllocateSendPacket(session, data.length);

        if (packet == null) {
            throw new RuntimeException("WintunAllocateSendPacket failed");
        }

        packet.write(0, data, 0, data.length);

        Wintun.INSTANCE.WintunSendPacket(session, packet);
    }

    private boolean shouldSendToServer(byte[] data) {

        if (!isIpv4(data)) {
            return false;
        }

        String src = ip(data, 12);
        String dst = ip(data, 16);

        if (!src.equals(CLIENT_IP)) {
            return false;
        }

        if (dst.equals(SERVER_TUN_IP)) {
            return true;
        }

        return TEST_EXTERNAL_IPS.contains(dst);
    }

    private boolean isIpv4(byte[] data) {
        return data.length >= 20 && (data[0] & 0xF0) == 0x40;
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xFF) + "." +
                (data[offset + 1] & 0xFF) + "." +
                (data[offset + 2] & 0xFF) + "." +
                (data[offset + 3] & 0xFF);
    }

    private void logEvery(AtomicLong counter, String direction, byte[] data) {

        long value = counter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println(direction + " packets=" + value + " last=" + data.length + " bytes " + PacketInfo.info(data));
    }

    private void runCommand(String... command) throws Exception {

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        int code = process.waitFor();

        if (code != 0) {
            throw new RuntimeException("Command failed, code=" + code + ", command=" + String.join(" ", command));
        }
    }

    private void runCommandIgnoreError(String... command) {

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.waitFor();
        } catch (Exception ignored) {
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
