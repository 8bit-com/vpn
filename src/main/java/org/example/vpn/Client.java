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
    private static final Duration HTTP_TX_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HTTP_RX_TIMEOUT = Duration.ofSeconds(35);
    private static final long RETRY_DELAY_MS = 1000;
    private static final String WINDOWS_ON_LINK_GATEWAY = "0.0.0.0";

    private final TunDevice tunDevice;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT)
            .build();

    private final AtomicLong tunToHttpCounter = new AtomicLong();
    private final AtomicLong httpToTunCounter = new AtomicLong();
    private final AtomicLong rxCounter = new AtomicLong();

    @Value("${vpn.server.url:http://80.240.23.72:8080}")
    private String serverUrl;

    @Value("${vpn.tun.name:tun-http}")
    private String tunName;

    @Value("${vpn.tun.address:10.8.0.2/24}")
    private String tunAddress;

    @Value("${vpn.tun.gateway:10.8.0.1}")
    private String tunGateway;

    @Value("${vpn.mtu:1400}")
    private int mtu;

    @Value("${vpn.routes:1.1.1.1}")
    private String routes;

    @Value("${vpn.synthetic-test.enabled:true}")
    private boolean syntheticTestEnabled;

    public Client(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        if (syntheticTestEnabled) {
            runSyntheticIcmpHttpTest();
        }

        startTunAdapter();
        configureOperatingSystemRoutes();
        startPacketPumpThreads();
        printReadyMessage();
    }

    private void runSyntheticIcmpHttpTest() throws Exception {
        byte[] request = buildSyntheticIcmpEchoRequest(tunAddressIpOnly(), tunGateway);
        System.out.println("SYNTHETIC TX " + request.length + " bytes " + PacketInfo.info(request));
        postPacketToServer(request);

        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            byte[] reply = pollPacketFromServer();
            if (reply == null) {
                continue;
            }

            System.out.println("SYNTHETIC RX " + reply.length + " bytes " + PacketInfo.info(reply));
            if (isExpectedSyntheticReply(reply)) {
                System.out.println("SYNTHETIC TEST OK");
                return;
            }
        }

        throw new RuntimeException("SYNTHETIC TEST FAILED: no ICMP echo reply from server over HTTP");
    }

    private boolean isExpectedSyntheticReply(byte[] packet) {
        if (!isIpv4Packet(packet) || packet.length < 28) {
            return false;
        }
        int ihl = (packet[0] & 0x0f) * 4;
        return (packet[9] & 0xff) == 1
                && ip(packet, 12).equals(tunGateway)
                && ip(packet, 16).equals(tunAddressIpOnly())
                && (packet[ihl] & 0xff) == 0;
    }

    private byte[] buildSyntheticIcmpEchoRequest(String srcIp, String dstIp) {
        byte[] packet = new byte[60];

        packet[0] = 0x45;
        packet[1] = 0;
        putU16(packet, 2, packet.length);
        putU16(packet, 4, 1);
        putU16(packet, 6, 0);
        packet[8] = 64;
        packet[9] = 1;
        putIp(packet, 12, srcIp);
        putIp(packet, 16, dstIp);
        putU16(packet, 10, checksum(packet, 0, 20));

        int icmp = 20;
        packet[icmp] = 8;
        packet[icmp + 1] = 0;
        putU16(packet, icmp + 4, 0x1234);
        putU16(packet, icmp + 6, 1);
        for (int i = icmp + 8; i < packet.length; i++) {
            packet[i] = (byte) i;
        }
        putU16(packet, icmp + 2, checksum(packet, icmp, packet.length - icmp));

        return packet;
    }

    private void startTunAdapter() {
        tunDevice.open(tunName);
    }

    private void configureOperatingSystemRoutes() throws Exception {
        if (isWindows()) {
            configureWindowsNetwork();
            return;
        }

        configureLinuxNetwork();
    }

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

    private void configureWindowsNetwork() throws Exception {
        String address = tunAddressIpOnly();
        String mask = tunAddressMask();
        int ifIndex = windowsInterfaceIndex(tunName);

        runOptionalCommand("powershell", "-NoProfile", "-Command", "Disable-NetAdapterBinding -Name '" + psEscape(tunName) + "' -ComponentID ms_tcpip6 -ErrorAction SilentlyContinue");
        runOptionalCommand("netsh", "interface", "ip", "delete", "address", "name=" + tunName, "addr=" + address);
        runRequiredCommand("netsh", "interface", "ip", "set", "address", "name=" + tunName, "static", address, mask);
        runOptionalCommand("netsh", "interface", "ipv4", "set", "subinterface", tunName, "mtu=" + mtu, "store=active");
        runOptionalCommand("netsh", "interface", "ipv4", "set", "interface", tunName, "forwarding=enabled");

        for (String target : windowsRouteTargets()) {
            runOptionalCommand("route", "delete", target);
            runRequiredCommand("route", "add", target, "mask", "255.255.255.255", WINDOWS_ON_LINK_GATEWAY, "if", String.valueOf(ifIndex), "metric", "1");
            System.out.println("ROUTE " + target + " -> on-link if " + ifIndex);
        }

        printWindowsNetworkDiagnostics();
    }

    private void printWindowsNetworkDiagnostics() {
        runAndPrint("WINDOWS ADAPTER", "powershell", "-NoProfile", "-Command", "Get-NetAdapter -Name '" + psEscape(tunName) + "' | Format-List ifIndex,Name,InterfaceDescription,Status");
        runAndPrint("WINDOWS IP CONFIG", "powershell", "-NoProfile", "-Command", "Get-NetIPConfiguration -InterfaceAlias '" + psEscape(tunName) + "' | Format-List");
        runAndPrint("WINDOWS ROUTE", "powershell", "-NoProfile", "-Command", "Get-NetRoute -DestinationPrefix " + tunGateway + "/32 | Format-List ifIndex,DestinationPrefix,NextHop,RouteMetric,InterfaceMetric");
        runAndPrint("WINDOWS ROUTE PRINT", "route", "print", tunGateway);
    }

    private void startPacketPumpThreads() {
        Thread rxThread = new Thread(this::pumpHttpToTunForever, "http-to-tun");
        rxThread.start();

        Thread txThread = new Thread(this::pumpTunToHttpForever, "tun-to-http");
        txThread.start();
    }

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

    private void pumpHttpToTunForever() {
        while (true) {
            try {
                byte[] packet = pollPacketFromServer();
                if (packet == null) {
                    continue;
                }

                long value = rxCounter.incrementAndGet();
                System.out.println("HTTP RX CLIENT #" + value + " " + packet.length + " bytes " + PacketInfo.info(packet));

                if (!isIpv4Packet(packet)) {
                    System.out.println("DROP RX: not IPv4 " + packet.length + " bytes");
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

        if (response.statusCode() == 204) {
            return null;
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("bad /rx status " + response.statusCode());
        }

        byte[] packet = response.body();
        return packet.length == 0 ? null : packet;
    }

    private boolean isIpv4Packet(byte[] packet) {
        return packet.length >= 20 && ((packet[0] >> 4) & 0x0f) == 4;
    }

    private String ip(byte[] data, int offset) {
        return (data[offset] & 0xff) + "." + (data[offset + 1] & 0xff) + "." + (data[offset + 2] & 0xff) + "." + (data[offset + 3] & 0xff);
    }

    private void putIp(byte[] data, int offset, String ip) {
        String[] parts = ip.split("\\.");
        for (int i = 0; i < 4; i++) {
            data[offset + i] = (byte) Integer.parseInt(parts[i]);
        }
    }

    private void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    private int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        while (length > 1) {
            sum += ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            i += 2;
            length -= 2;
        }
        if (length > 0) {
            sum += (data[i] & 0xff) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private int windowsInterfaceIndex(String interfaceName) throws Exception {
        Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(Get-NetAdapter -Name '" + psEscape(interfaceName) + "').ifIndex")
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

    private void runAndPrint(String title, String... command) {
        System.out.println("===== " + title + " =====");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            System.out.println("DIAG FAILED: " + e.getMessage());
        }
    }

    private String psEscape(String value) {
        return value.replace("'", "''");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
