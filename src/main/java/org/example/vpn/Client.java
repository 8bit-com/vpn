package org.example.vpn;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

@Service
public class Client {

    private static final String SERVER_HOST = "80.240.23.72";
    private static final int SERVER_PORT = 51888;
    private static final int WINTUN_RING_SIZE = 0x400000;
    private static final String ADAPTER_NAME = "MyVPN";
    private static final String CLIENT_IP = "10.0.0.123";
    private static final String CLIENT_MASK = "255.255.255.0";

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        cleanupOldAdapterConfig();

        Pointer session = startWintun();

        configureMyVpnIp();

        DatagramSocket socket = new DatagramSocket();

        register(socket);

        receiveUdpAndWriteToWintun(socket, session);
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

            writeToWintun(session, data);

            System.out.println(
                    "UDP -> WINTUN : " +
                            data.length +
                            " bytes"
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
