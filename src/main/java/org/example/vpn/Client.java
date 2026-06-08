package org.example.vpn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class Client {

    private static final int LOG_EVERY_PACKETS = 100;
    private static final int MAX_PACKET_SIZE = 65535;
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_TX_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_RX_TIMEOUT = Duration.ofSeconds(35);
    private static final long RETRY_DELAY_MS = 1000;

    private final TunDevice tunDevice;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
            .build();

    private final AtomicLong tunToHttpCounter = new AtomicLong();
    private final AtomicLong httpToTunCounter = new AtomicLong();

    // HTTP endpoint of the server. The client sends packets to /tx and polls packets from /rx.
    @Value("${vpn.server.url:http://80.240.23.72:8080}")
    private String serverUrl;

    // Local TUN adapter name. On Windows this must match the Wintun adapter name.
    @Value("${vpn.tun.name:tun-http}")
    private String tunName;

    // Local client-side VPN address. Example: 10.8.0.2/24.
    @Value("${vpn.tun.address:10.8.0.2/24}")
    private String tunAddress;

    // Server-side VPN address. Internal ping target: ping 10.8.0.1.
    @Value("${vpn.tun.gateway:10.8.0.1}")
    private String tunGateway;

    @Value("${vpn.mtu:1400}")
    private int mtu;

    // Comma-separated list of external routes that should go into the tunnel.
    // Example: vpn.routes=1.1.1.1,8.8.8.8
    @Value("${vpn.routes:1.1.1.1}")
    private String routes;

    public Client(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        startTunAdapter();
        configureOperatingSystemRoutes();
        startPacketPumpThreads();
        printReadyMessage();
    }

    /**
     * Opens the local virtual network adapter.
     *
     * Windows: TunDevice uses wintun.dll.
     * Linux:   TunDevice uses /dev/net/tun through libc.
     */
    private void startTunAdapter() {
        tunDevice.open(tunName);
    }

    /**
     * Configures IP address, MTU and routes in the operating system.
     *
     * The Java program reads packets from the TUN adapter only after the OS routing table sends
     * traffic into this adapter. Without these routes, ping will bypass our program completely.
     */
    private void configureOperatingSystemRoutes() throws Exception {
        if (isWindows()) {
            configureWindowsNetwork();
            return;
        }

        configureLinuxNetwork();
    }

    /**
     * Linux client configuration.
     *
     * Assigns 10.8.0.2/24 to the TUN interface and routes selected targets to that interface.
     */
    private void configureLinuxNetwork() throws Exception {
        runRequiredCommand("ip", "addr", "flush", "dev", tunName);
        runRequiredCommand("ip", "addr", "add", tunAddress, "dev", tunName);
        runRequiredCommand("ip", "link", "set", "dev", tunName, "mtu", String.valueOf(mtu));
        runRequiredCommand("ip", "link", "set", tunName, "up");

        for (String route : externalRouteTargets()) {
            String target = route.contains("/") ? route : route + "/32";
            runRequiredCommand("ip", "route", "replace", target, "dev", tunName);
            System.out.println("ROUTE " + target + " -> " + tunName);
        }
    }

    /**
     * Windows client configuration.
     *
     * Important detail:
     * Wintun returns a LUID, but Windows route.exe needs the real interface index. Therefore we
     * resolve the index through PowerShell Get-NetAdapter instead of using WintunGetAdapterLUID.
     */
    private void configureWindowsNetwork() throws Exception {
        String address = tunAddressIpOnly();
        String mask = tunAddressMask();
        int ifIndex = windowsInterfaceIndex(tunName);

        // Give the Wintun adapter our client-side VPN IP.
        runOptionalCommand("netsh", "interface", "ip", "delete", "address", "name=" + tunName, "addr=" + address);
        runRequiredCommand("netsh", "interface", "ip", "set", "address", "name=" + tunName, "static", address, mask);

        // MTU is optional here. Some Windows versions reject this command depending on adapter state.
        runOptionalCommand("netsh", "interface", "ipv4", "set", "subinterface", tunName, "mtu=" + mtu, "store=active");

        // Route both the internal gateway and configured external targets into the Wintun adapter.
        for (String target : windowsRouteTargets()) {
            runOptionalCommand("route", "delete", target);
            runRequiredCommand("route", "add", target, "mask", "255.255.255.255", address, "if", String.valueOf(ifIndex), "metric", "1");
            System.out.println("ROUTE " + target + " -> " + address + " if " + ifIndex);
        }
    }

    /**
     * Starts two permanent loops:
     *
     * 1. tun-to-http:
     *    reads raw IP packets from Wintun/TUN and sends them to the server with POST /tx.
     *
     * 2. http-to-tun:
     *    polls the server with GET /rx and writes returned raw IP packets back to Wintun/TUN.
     */
    private void startPacketPumpThreads() {
        Thread rxThread = new Thread(this::pumpHttpToTunForever, "http-to-tun");
        rxThread.start();

        Thread txThread = new Thread(this::pumpTunToHttpForever, "tun-to-http");
        txThread.start();
    }

    /**
     * Direction: local OS -> TUN adapter -> Java client -> HTTP POST /tx -> server.
     */
    private void pumpTunToHttpForever() {
        while (true) {
            try {
                byte[] packet = readPacketFromTun();
                if (packet == null) {
                    continue;
                }

                postPacketToServer(packet);
                logEvery(tunToHttpCounter, "tun -> http", packet);

            } catch (Exception e) {
                System.out.println("tun -> http retry: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    /**
     * Direction: server -> HTTP GET /rx -> Java client -> TUN adapter -> local OS.
     */
    private void pumpHttpToTunForever() {
        while (true) {
            try {
                byte[] packet = pollPacketFromServer();
                if (packet == null) {
                    continue;
                }

                tunDevice.writePacket(packet);
                logEvery(httpToTunCounter, "http -> tun", packet);

            } catch (Exception e) {
                System.out.println("http -> tun retry: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    private byte[] readPacketFromTun() {
        byte[] packet = tunDevice.readPacket();

        if (packet == null || packet.length == 0) {
            return null;
        }

        if (packet.length > MAX_PACKET_SIZE) {
            System.out.println("DROP TUN PACKET: too large " + packet.length + " bytes");
            return null;
        }

        return packet;
    }

    private void postPacketToServer(byte[] packet) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/tx"))
                .timeout(HTTP_TX_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(packet))
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) {
            throw new RuntimeException("bad /tx status " + response.statusCode());
        }
    }

    private byte[] pollPacketFromServer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/rx"))
                .timeout(HTTP_RX_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        // 204 means that the server currently has no packet for this client.
        // This is normal for long polling, not an error.
        if (response.statusCode() == 204) {
            return null;
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("bad /rx status " + response.statusCode());
        }

        byte[] packet = response.body();
        return packet.length == 0 ? null : packet;
    }

    private int windowsInterfaceIndex(String interfaceName) throws Exception {
        Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(Get-NetAdapter -Name '" + interfaceName.replace("'", "''") + "').ifIndex")
                .redirectErrorStream(true)
                .start();

        String line;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            line = reader.readLine();
        }

        int code = process.waitFor();
        if (code != 0 || line == null || line.trim().isEmpty()) {
            throw new RuntimeException("Cannot detect Windows interface index for " + interfaceName);
        }

        return Integer.parseInt(line.trim());
    }

    private Set<String> windowsRouteTargets() {
        Set<String> result = new LinkedHashSet<>();

        // This route is required for: ping 10.8.0.1
        result.add(tunGateway);

        result.addAll(Arrays.asList(externalRouteTargets()));
        return result;
    }

    private String[] externalRouteTargets() {
        return Arrays.stream(routes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.contains("/") ? value.substring(0, value.indexOf('/')) : value)
                .toArray(String[]::new);
    }

    private String tunAddressIpOnly() {
        return tunAddress.contains("/") ? tunAddress.substring(0, tunAddress.indexOf('/')) : tunAddress;
    }

    private String tunAddressMask() {
        int prefix = tunAddress.contains("/") ? Integer.parseInt(tunAddress.substring(tunAddress.indexOf('/') + 1)) : 24;
        return prefixToMask(prefix);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String prefixToMask(int prefix) {
        int mask = 0xffffffff << (32 - prefix);
        return ((mask >>> 24) & 0xff) + "." + ((mask >>> 16) & 0xff) + "." + ((mask >>> 8) & 0xff) + "." + (mask & 0xff);
    }

    private void logEvery(AtomicLong counter, String direction, byte[] data) {
        long value = counter.incrementAndGet();

        if (value % LOG_EVERY_PACKETS != 0) {
            return;
        }

        System.out.println(direction + " packets=" + value + " last=" + data.length + " bytes " + PacketInfo.info(data));
    }

    private void printReadyMessage() {
        System.out.println("HTTP VPN CLIENT READY");
        System.out.println("CHECK INTERNAL: ping " + tunGateway);
        for (String route : externalRouteTargets()) {
            System.out.println("CHECK EXTERNAL: ping " + route);
        }
    }

    private void runRequiredCommand(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        int code = process.waitFor();

        if (code != 0) {
            throw new RuntimeException("Command failed, code=" + code + ", command=" + String.join(" ", command));
        }
    }

    private void runOptionalCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.waitFor();
        } catch (Exception ignored) {
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
