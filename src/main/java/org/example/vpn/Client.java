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

    private static final int MAX_PACKET_SIZE = 65535;
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_TX_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HTTP_RX_TIMEOUT = Duration.ofSeconds(35);
    private static final long RETRY_DELAY_MS = 1000;

    private final TunDevice tunDevice;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    private final AtomicLong pingFromWindowsCounter = new AtomicLong();

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
    @Value("${vpn.synthetic-test.enabled:false}")
    private boolean syntheticTestEnabled;

    public Client(TunDevice tunDevice) {
        this.tunDevice = tunDevice;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        if (syntheticTestEnabled) {
            runSyntheticIcmpHttpTest();
        }
        tunDevice.open(tunName);
        configureOperatingSystemRoutes();
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupWindowsRoutes));
        startPacketPumpThreads();
        System.out.println("HTTP VPN CLIENT READY");
        System.out.println("NOW CHECK ONLY THIS: ping " + tunGateway);
    }

    private void runSyntheticIcmpHttpTest() throws Exception {
        byte[] request = buildSyntheticIcmpEchoRequest(tunAddressIpOnly(), tunGateway);
        postPacketToServer(request);
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            byte[] reply = pollPacketFromServer();
            if (reply != null && isExpectedSyntheticReply(reply)) {
                System.out.println("SYNTHETIC TEST OK");
                return;
            }
        }
        throw new RuntimeException("SYNTHETIC TEST FAILED");
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
        }
    }

    private void configureWindowsNetwork() throws Exception {
        String address = tunAddressIpOnly();
        String mask = tunAddressMask();
        runOptionalCommand("powershell", "-NoProfile", "-Command", "Disable-NetAdapterBinding -Name '" + psEscape(tunName) + "' -ComponentID ms_tcpip6 -ErrorAction SilentlyContinue");
        runOptionalCommand("netsh", "interface", "ip", "delete", "address", "name=" + tunName, "addr=" + address);
        runRequiredCommand("netsh", "interface", "ip", "set", "address", "name=" + tunName, "static", address, mask);
        runOptionalCommand("netsh", "interface", "ipv4", "set", "subinterface", tunName, "mtu=" + mtu, "store=active");
        cleanupWindowsRoutes();
        for (String target : windowsRouteTargets()) {
            addWindowsRoute(target);
        }
    }

    private void addWindowsRoute(String target) throws Exception {
        String command = "New-NetRoute -DestinationPrefix '" + target + "/32' -InterfaceAlias '" + psEscape(tunName) + "' -NextHop '0.0.0.0' -RouteMetric 1 -PolicyStore ActiveStore";
        runRequiredCommand("powershell", "-NoProfile", "-Command", command);
    }

    private void cleanupWindowsRoutes() {
        if (!isWindows()) {
            return;
        }
        for (String target : windowsRouteTargets()) {
            String command = "Get-NetRoute -DestinationPrefix '" + target + "/32' -ErrorAction SilentlyContinue | "
                    + "Where-Object { $_.InterfaceAlias -eq '" + psEscape(tunName) + "' } | "
                    + "Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue";
            runOptionalCommand("powershell", "-NoProfile", "-Command", command);
            runOptionalCommand("route", "delete", target);
        }
    }

    private void startPacketPumpThreads() {
        new Thread(this::pumpHttpToTunForever, "http-to-tun").start();
        new Thread(this::pumpTunToHttpForever, "tun-to-http").start();
    }

    private void pumpTunToHttpForever() {
        while (true) {
            try {
                byte[] packet = readPacketFromTun();
                if (packet == null) {
                    continue;
                }
                logOnlyWindowsPingRequest(packet);
                postPacketToServer(packet);
            } catch (Exception e) {
                sleepBeforeRetry();
            }
        }
    }

    private void pumpHttpToTunForever() {
        while (true) {
            try {
                byte[] packet = pollPacketFromServer();
                if (packet == null || !isIpv4Packet(packet)) {
                    continue;
                }
                tunDevice.writePacket(packet);
            } catch (Exception e) {
                sleepBeforeRetry();
            }
        }
    }

    private byte[] readPacketFromTun() {
        byte[] packet = tunDevice.readPacket();
        if (packet == null || packet.length == 0 || packet.length > MAX_PACKET_SIZE) {
            return null;
        }
        return packet;
    }

    private void logOnlyWindowsPingRequest(byte[] packet) {
        if (!isIpv4Packet(packet)) {
            return;
        }
        int ihl = (packet[0] & 0x0f) * 4;
        if (packet.length < ihl + 8) {
            return;
        }
        int protocol = packet[9] & 0xff;
        int icmpType = packet[ihl] & 0xff;
        String src = ip(packet, 12);
        String dst = ip(packet, 16);
        if (protocol == 1 && icmpType == 8 && dst.equals(tunGateway)) {
            long count = pingFromWindowsCounter.incrementAndGet();
            System.out.println("WINDOWS PING SEEN #" + count + " " + src + " -> " + dst);
        }
    }

    private void postPacketToServer(byte[] packet) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/tx")).timeout(HTTP_TX_TIMEOUT).header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(packet)).build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 204) {
            throw new RuntimeException("bad /tx status " + response.statusCode());
        }
    }

    private byte[] pollPacketFromServer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/rx")).timeout(HTTP_RX_TIMEOUT).GET().build();
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

    private boolean isExpectedSyntheticReply(byte[] packet) {
        if (!isIpv4Packet(packet) || packet.length < 28) {
            return false;
        }
        int ihl = (packet[0] & 0x0f) * 4;
        return (packet[9] & 0xff) == 1 && ip(packet, 12).equals(tunGateway) && ip(packet, 16).equals(tunAddressIpOnly()) && (packet[ihl] & 0xff) == 0;
    }

    private byte[] buildSyntheticIcmpEchoRequest(String srcIp, String dstIp) {
        byte[] packet = new byte[60];
        packet[0] = 0x45;
        putU16(packet, 2, packet.length);
        putU16(packet, 4, 1);
        packet[8] = 64;
        packet[9] = 1;
        putIp(packet, 12, srcIp);
        putIp(packet, 16, dstIp);
        putU16(packet, 10, checksum(packet, 0, 20));
        int icmp = 20;
        packet[icmp] = 8;
        putU16(packet, icmp + 4, 0x1234);
        putU16(packet, icmp + 6, 1);
        for (int i = icmp + 8; i < packet.length; i++) {
            packet[i] = (byte) i;
        }
        putU16(packet, icmp + 2, checksum(packet, icmp, packet.length - icmp));
        return packet;
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

    private Set<String> windowsRouteTargets() {
        Set<String> result = new LinkedHashSet<>();
        result.add(tunGateway);
        result.addAll(Arrays.asList(externalRouteTargets()));
        return result;
    }

    private String[] externalRouteTargets() {
        return Arrays.stream(routes.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(value -> value.contains("/") ? value.substring(0, value.indexOf('/')) : value).toArray(String[]::new);
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
