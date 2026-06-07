package org.example.vpn;

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

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        DatagramSocket socket = new DatagramSocket();

        socket.send(
                new DatagramPacket(
                        "HELLO".getBytes(),
                        5,
                        InetAddress.getByName(HOST),
                        PORT
                )
        );

        System.out.println("REGISTERED");

        while (true) {

            DatagramPacket packet =
                    new DatagramPacket(
                            new byte[65535],
                            65535
                    );

            socket.receive(packet);

            System.out.println(
                    "SERVER -> CLIENT : " +
                            packet.getLength() +
                            " bytes"
            );

            byte[] data = Arrays.copyOf(
                    packet.getData(),
                    packet.getLength()
            );

            for (int i = 0; i < Math.min(32, data.length); i++) {
                System.out.printf("%02X ", data[i]);
            }

            System.out.println();
        }
    }
}
