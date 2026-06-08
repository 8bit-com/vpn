package org.example.vpn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Client {

    private static final int LOG_EVERY_PACKETS = 100;
    private static final int MAX_PACKET_SIZE = 65535;

    private final TunDevice tunDevice;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicLong tunToHttpCounter = new AtomicLong();
    private final AtomicLong httpToTunCounter = new AtomicLong();

    @Value("${vpn.server.url:http://80.240.23.72:8080}")
    private String serverUrl;

    @Value("${vpn.tun.name:tun-http}")
    private String tunName;

    @Value("${vpn.tun.address:10.8.0.2/24}")
    private String tunAddress;

    @Value("${vpn.mtu:1400}")
    private int mtu;

    @Value("${vpn.routes:1.1.1.1}")
    private String routes;

    public Client(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {

        runCommandIgnoreError("ip", "link", "delete", tunName);
        tunDevice.open(tunName);
        configureLinuxNetwork();

        Thread rxThread = new Thread(this::readHttpAndWriteTun, "http-to-tun");
        rxThread.start();

        Thread txThread = new Thread(this::readTunAndPostHttp, "tun-to-http");
        txThread.start();

        System.out.println("HTTP VPN CLIENT READY");
        System.out.println("CHECK INTERNAL: ping 10.8.0.1");
        for (String route : routeList()) {
            System.out.println("CHECK EXTERNAL: ping " + route);
        }
    }

    private void configureLinuxNetwork() throws Exception {
        runCommand("ip", "addr", "flush", "dev", tunName);
        runCommand("ip", "addr", "add", tunAddress, "dev", tunName);
        runCommand("ip", "link", "set", "dev", tunName, "mtu", String.valueOf(mtu));
        runCommand("ip", "link", "set", tunName, "up");

        for (String route : routeList()) {
            String target = route.contains("/") ? route : route + "/32";
            runCommand("ip", "route", "replace", target, "dev", tunName);
            System.out.println("ROUTE " + target + " -> " + tunName);
        }
    }

    private void readTunAndPostHttp() {
        while (true) {
            try {
                byte[] packet = tunDevice.readPacket();
                if (packet == null || packet.length > MAX_PACKET_SIZE) {
                    continue;
                }

                HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/tx"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(packet))
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 204) {
                    throw new RuntimeException("bad /tx status " + response.statusCode());
                }

                logEvery(tunToHttpCounter, "tun -> http", packet);

            } catch (Exception e) {
                System.out.println("tun -> http retry: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                sleep();
            }
        }
    }

    private void readHttpAndWriteTun() {
        while (true) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/rx"))
                        .timeout(Duration.ofSeconds(35))
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 204) {
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new RuntimeException("bad /rx status " + response.statusCode());
                }

                byte[] packet = response.body();
                if (packet.length > 0) {
                    tunDevice.writePacket(packet);
                    logEvery(httpToTunCounter, "http -> tun", packet);
                }

            } catch (Exception e) {
                System.out.println("http -> tun retry: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                sleep();
            }
        }
    }

    private void logEvery(AtomicLong counter, String direction, byte[] data) {

        long value = counter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println(direction + " packets=" + value + " last=" + data.length + " bytes " + PacketInfo.info(data));
    }

    private String[] routeList() {
        return Arrays.stream(routes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
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

    private void sleep() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
