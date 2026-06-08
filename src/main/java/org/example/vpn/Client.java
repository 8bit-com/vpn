package org.example.vpn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class Client {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${vpn.server.url:http://80.240.23.72:8080}")
    private String serverUrl;

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        System.out.println("HTTP VPN CLIENT TEST START");
        System.out.println("SERVER URL = " + serverUrl);

        byte[] packet = "HELLO_FROM_CLIENT".getBytes(StandardCharsets.UTF_8);

        HttpRequest txRequest = HttpRequest.newBuilder(URI.create(serverUrl + "/tx"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(packet))
                .build();

        HttpResponse<Void> txResponse = httpClient.send(txRequest, HttpResponse.BodyHandlers.discarding());
        System.out.println("TX STATUS = " + txResponse.statusCode());

        HttpRequest rxRequest = HttpRequest.newBuilder(URI.create(serverUrl + "/rx"))
                .timeout(Duration.ofSeconds(35))
                .GET()
                .build();

        HttpResponse<byte[]> rxResponse = httpClient.send(rxRequest, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("RX STATUS = " + rxResponse.statusCode());
        System.out.println("RX BODY = " + new String(rxResponse.body(), StandardCharsets.UTF_8));

        System.out.println("HTTP VPN CLIENT TEST FINISHED");
    }
}
