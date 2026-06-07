package org.example.vpn;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

@Service
public class Client {
    private static final String HOST = "80.240.23.72";
    private static final int PORT = 51888;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        DatagramSocket socket = new DatagramSocket();

        String message = "PING";

        byte[] data =
                message.getBytes(StandardCharsets.UTF_8);

        DatagramPacket request =
                new DatagramPacket(
                        data,
                        data.length,
                        InetAddress.getByName(HOST),
                        PORT
                );

        socket.send(request);

        System.out.println("PING SENT");

        DatagramPacket response =
                new DatagramPacket(
                        new byte[65535],
                        65535
                );

        socket.receive(response);

        String text =
                new String(
                        response.getData(),
                        0,
                        response.getLength(),
                        StandardCharsets.UTF_8
                );

        System.out.println("RESPONSE = " + text);

        socket.close();
    }
}
