package org.example.vpn;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Client {

    private static final String SERVER_HOST = "80.240.23.72";
    private static final int SERVER_PORT = 51888;
    private static final int WINTUN_RING_SIZE = 0x400000;
    private static final int UDP_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int LOG_EVERY_PACKETS = 100;
    private static final int MTU = 1400;
    private static final String ADAPTER_NAME = "MyVPN";
    private static final String CLIENT_IP = "10.0.0.123";
    private static final String SERVER_TUN_IP = "10.0.0.1";
    private static final String CLIENT_MASK = "255.255.255.0";

    private final AtomicLong udpToWintunCounter = new AtomicLong();
    private final AtomicLong wintunToUdpCounter = new AtomicLong();

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        cleanupOldAdapterConfig();

        String defaultGateway = getDefaultGateway();
        System.out.println("DEFAULT GATEWAY: " + defaultGateway);

        Pointer session = startWintun();

        configureMyVpnIp();
        configureMtu();
        configureRoutes(defaultGateway);

        DatagramSocket socket = createUdpSocket();

        register(socket);

        startWintunToUdp(socket, session);

        receiveUdpAndWriteToWintun(socket, session);
    }

    private DatagramSocket createUdpSocket() throws Exception {

        DatagramSocket socket = new DatagramSocket();

        socket.setReceiveBufferSize(UDP_BUFFER_SIZE);
        socket.setSendBufferSize(UDP_BUFFER_SIZE);

        System.out.println("UDP SOCKET READY");

        return socket;
    }

    private Pointer startWintun() {

        Pointer adapter =
                Wintun.INSTANCE.WintunCreateAdapter(
                        new WString(ADAPTER_NAME),
                        new WString("VPN"),
                        null
                );

        if (adapter == null) {
            throw new RuntimeException("WintunCreateAdapter failed");
        }

        Pointer session =
                Wintun.INSTANCE.WintunStartSession(
                        adapter,
                        WINTUN_RING_SIZE
                );

        if (session == null) {
            throw new RuntimeException("WintunStartSession failed");
        }

        System.out.println("WINTUN STARTED");

        return session;
    }

    private void cleanupOldAdapterConfig() {

        runCommandIgnoreError(
                "netsh",
                "interface",
                "ip",
                "delete",
                "address",
                "name=" + ADAPTER_NAME,
                "addr=" + CLIENT_IP
        );

        runCommandIgnoreError(
                "route",
                "delete",
                "0.0.0.0",
                "mask",
                "128.0.0.0",
                CLIENT_IP
        );

        runCommandIgnoreError(
                "route",
                "delete",
                "128.0.0.0",
                "mask",
                "128.0.0.0",
                CLIENT_IP
        );

        runCommandIgnoreError(
                "route",
                "delete",
                SERVER_HOST
        );
    }

    private void configureMyVpnIp() throws Exception {

        runCommand(
                "netsh",
                "interface",
                "ip",
                "set",
                "address",
                "name=" + ADAPTER_NAME,
                "static",
                CLIENT_IP,
                CLIENT_MASK
        );

        System.out.println("MYVPN IP CONFIGURED");
    }

    private void configureMtu() throws Exception {

        runCommand(
                "netsh",
                "interface",
                "ipv4",
                "set",
                "subinterface",
                ADAPTER_NAME,
                "mtu=" + MTU,
                "store=active"
        );

        System.out.println("MYVPN MTU CONFIGURED: " + MTU);
    }

    private void configureRoutes(String defaultGateway) throws Exception {

        runCommand(
                "route",
                "add",
                SERVER_HOST,
                "mask",
                "255.255.255.255",
                defaultGateway,
                "metric",
                "1"
        );

        runCommandIgnoreError(
                "route",
                "delete",
                "0.0.0.0",
                "mask",
                "128.0.0.0"
        );

        runCommandIgnoreError(
                "route",
                "delete",
                "128.0.0.0",
                "mask",
                "128.0.0.0"
        );

        runCommand(
                "route",
                "add",
                "0.0.0.0",
                "mask",
                "128.0.0.0",
                CLIENT_IP,
                "metric",
                "1"
        );

        runCommand(
                "route",
                "add",
                "128.0.0.0",
                "mask",
                "128.0.0.0",
                CLIENT_IP,
                "metric",
                "1"
        );

        System.out.println("MYVPN ROUTES CONFIGURED");
    }

    private String getDefaultGateway() throws Exception {

        Process process =
                new ProcessBuilder(
                        "powershell",
                        "-NoProfile",
                        "-Command",
                        "(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Where-Object { $_.NextHop -ne '0.0.0.0' -and $_.InterfaceAlias -ne '" + ADAPTER_NAME + "' } | Sort-Object RouteMetric,InterfaceMetric | Select-Object -First 1).NextHop"
                )
                        .redirectErrorStream(true)
                        .start();

        String gateway;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            gateway = reader.readLine();
        }

        int code = process.waitFor();

        if (code != 0 || gateway == null || gateway.trim().isEmpty()) {
            throw new RuntimeException("Cannot detect default gateway");
        }

        return gateway.trim();
    }

    private void register(DatagramSocket socket) throws Exception {

        byte[] data = "HELLO".getBytes();

        socket.send(
                new DatagramPacket(
                        data,
                        data.length,
                        InetAddress.getByName(SERVER_HOST),
                        SERVER_PORT
                )
        );

        System.out.println("REGISTERED");
    }

    private void receiveUdpAndWriteToWintun(
            DatagramSocket socket,
            Pointer session
    ) throws Exception {

        while (true) {

            DatagramPacket udpPacket =
                    new DatagramPacket(
                            new byte[65535],
                            65535
                    );

            socket.receive(udpPacket);

            byte[] data =
                    Arrays.copyOf(
                            udpPacket.getData(),
                            udpPacket.getLength()
                    );

            if (!isIpv4(data)) {
                continue;
            }

            writeToWintun(session, data);

            logEvery(
                    udpToWintunCounter,
                    "UDP -> WINTUN",
                    data
            );
        }
    }

    private void writeToWintun(
            Pointer session,
            byte[] data
    ) {

        Pointer packet =
                Wintun.INSTANCE.WintunAllocateSendPacket(
                        session,
                        data.length
                );

        if (packet == null) {
            throw new RuntimeException("WintunAllocateSendPacket failed");
        }

        packet.write(
                0,
                data,
                0,
                data.length
        );

        Wintun.INSTANCE.WintunSendPacket(
                session,
                packet
        );
    }

    private void startWintunToUdp(
            DatagramSocket socket,
            Pointer session
    ) {

        Thread thread =
                new Thread(
                        () -> readWintunAndSendUdp(socket, session),
                        "wintun-to-udp"
                );

        thread.setDaemon(true);
        thread.start();
    }

    private void readWintunAndSendUdp(
            DatagramSocket socket,
            Pointer session
    ) {

        while (true) {

            try {

                IntByReference size = new IntByReference();

                Pointer packet =
                        Wintun.INSTANCE.WintunReceivePacket(
                                session,
                                size
                        );

                if (packet == null) {
                    Thread.sleep(1);
                    continue;
                }

                byte[] data =
                        packet.getByteArray(
                                0,
                                size.getValue()
                        );

                Wintun.INSTANCE.WintunReleaseReceivePacket(
                        session,
                        packet
                );

                if (!isIpv4(data)) {
                    continue;
                }

                socket.send(
                        new DatagramPacket(
                                data,
                                data.length,
                                InetAddress.getByName(SERVER_HOST),
                                SERVER_PORT
                        )
                );

                logEvery(
                        wintunToUdpCounter,
                        "WINTUN -> UDP",
                        data
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void logEvery(
            AtomicLong counter,
            String direction,
            byte[] data
    ) {

        long value = counter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println(
                direction +
                        " packets=" + value +
                        " last=" + data.length +
                        " bytes " +
                        ipInfo(data)
        );
    }

    private boolean isIpv4(byte[] data) {
        return data.length >= 20 && (data[0] & 0xF0) == 0x40;
    }

    private String ipInfo(byte[] data) {

        if (!isIpv4(data)) {
            return "";
        }

        return ip(data, 12) + " -> " + ip(data, 16);
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xFF) + "." +
                (data[offset + 1] & 0xFF) + "." +
                (data[offset + 2] & 0xFF) + "." +
                (data[offset + 3] & 0xFF);
    }

    private void runCommand(String... command) throws Exception {

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

        int code = process.waitFor();

        if (code != 0) {
            throw new RuntimeException(
                    "Command failed, code=" + code + ", command=" + String.join(" ", command)
            );
        }
    }

    private void runCommandIgnoreError(String... command) {

        try {
            Process process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start();

            process.waitFor();
        } catch (Exception ignored) {
        }
    }
}
