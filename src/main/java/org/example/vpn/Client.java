package org.example.vpn;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

@Service
public class Client {

    private static final String HOST = "80.240.23.72";
    private static final int PORT = 51888;
    private static final int WINTUN_RING_SIZE = 0x400000;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        DatagramSocket socket = new DatagramSocket();

        Pointer session = createSession();

        register(socket);

        startWintunReader(
                socket,
                session
        );

        receiveLoop(
                socket,
                session
        );
    }

    private Pointer createSession() {

        Pointer adapter =
                Wintun.INSTANCE.WintunCreateAdapter(
                        new WString("MyVPN"),
                        new WString("VPN"),
                        null
                );

        System.out.println(adapter);

        Pointer session =
                Wintun.INSTANCE.WintunStartSession(
                        adapter,
                        WINTUN_RING_SIZE
                );

        System.out.println(session);

        return session;
    }

    private void register(
            DatagramSocket socket
    ) throws Exception {

        socket.send(
                new DatagramPacket(
                        "HELLO".getBytes(),
                        5,
                        InetAddress.getByName(HOST),
                        PORT
                )
        );

        System.out.println("REGISTERED");
    }

    private void receiveLoop(
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

            byte[] data = Arrays.copyOf(
                    udpPacket.getData(),
                    udpPacket.getLength()
            );

            writeToWintun(
                    session,
                    data
            );

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

        Pointer wintunPacket =
                Wintun.INSTANCE.WintunAllocateSendPacket(
                        session,
                        data.length
                );

        wintunPacket.write(
                0,
                data,
                0,
                data.length
        );

        Wintun.INSTANCE.WintunSendPacket(
                session,
                wintunPacket
        );
    }

    private void startWintunReader(
            DatagramSocket socket,
            Pointer session
    ) {

        new Thread(
                () -> readWintun(
                        socket,
                        session
                ),
                "wintun-reader"
        ).start();
    }

    private void readWintun(
            DatagramSocket socket,
            Pointer session
    ) {

        while (true) {

            try {

                IntByReference size =
                        new IntByReference();

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

                socket.send(
                        new DatagramPacket(
                                data,
                                data.length,
                                InetAddress.getByName(HOST),
                                PORT
                        )
                );

                System.out.println(
                        "WINTUN -> UDP : " +
                                data.length +
                                " bytes"
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}