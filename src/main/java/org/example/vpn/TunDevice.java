package org.example.vpn;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TunDevice {

    private static final int O_RDWR = 2;
    private static final short IFF_TUN = 0x0001;
    private static final short IFF_NO_PI = 0x1000;
    private static final long TUNSETIFF = 0x400454caL;
    private static final int MAX_PACKET_SIZE = 65535;
    private static final int WINTUN_SESSION_CAPACITY = 0x400000;
    private static final int INFINITE = -1;
    private static final int ERROR_NO_MORE_ITEMS = 259;

    private final AtomicLong windowsReadCounter = new AtomicLong();
    private final AtomicLong windowsWriteCounter = new AtomicLong();

    private int fd;
    private boolean windows;
    private Pointer adapter;
    private Pointer session;
    private Pointer readWaitEvent;

    public void open(String tunName) {
        windows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (windows) {
            openWindowsTun(tunName);
            return;
        }

        fd = openLinuxTun(tunName);
        System.out.println(tunName + " opened");
    }

    public byte[] readPacket() {
        if (windows) {
            return readWindowsPacketBlocking();
        }

        byte[] buffer = new byte[MAX_PACKET_SIZE];
        int len = LibC.INSTANCE.read(fd, buffer, buffer.length);
        if (len <= 0) {
            return null;
        }
        return Arrays.copyOf(buffer, len);
    }

    public void writePacket(byte[] data) {
        if (windows) {
            writeWindowsPacket(data);
            return;
        }

        int written = LibC.INSTANCE.write(fd, data, data.length);

        if (written != data.length) {
            throw new RuntimeException("tun write failed, written=" + written + ", expected=" + data.length + ", errno=" + LibC.errno());
        }
    }

    private void openWindowsTun(String tunName) {
        try {
            adapter = Wintun.INSTANCE.WintunOpenAdapter(new WString(tunName));

            if (adapter == null || Pointer.nativeValue(adapter) == 0) {
                adapter = Wintun.INSTANCE.WintunCreateAdapter(new WString(tunName), new WString("HTTPVPN"), null);
            }

            if (adapter == null || Pointer.nativeValue(adapter) == 0) {
                throw new RuntimeException("WintunCreateAdapter failed, lastError=" + Native.getLastError());
            }

            session = Wintun.INSTANCE.WintunStartSession(adapter, WINTUN_SESSION_CAPACITY);

            if (session == null || Pointer.nativeValue(session) == 0) {
                throw new RuntimeException("WintunStartSession failed, lastError=" + Native.getLastError());
            }

            readWaitEvent = Wintun.INSTANCE.WintunGetReadWaitEvent(session);
            if (readWaitEvent == null || Pointer.nativeValue(readWaitEvent) == 0) {
                throw new RuntimeException("WintunGetReadWaitEvent failed, lastError=" + Native.getLastError());
            }

            System.out.println(tunName + " opened by wintun.dll");
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("wintun.dll not found. Put wintun.dll near java.exe working directory or add it to PATH", e);
        }
    }

    private byte[] readWindowsPacketBlocking() {
        while (true) {
            IntByReference packetSizeRef = new IntByReference();
            Pointer packet = Wintun.INSTANCE.WintunReceivePacket(session, packetSizeRef);

            if (packet != null && Pointer.nativeValue(packet) != 0) {
                int packetSize = packetSizeRef.getValue();
                byte[] raw = packet.getByteArray(0, packetSize);
                Wintun.INSTANCE.WintunReleaseReceivePacket(session, packet);

                byte[] normalized = normalizeToIpv4(raw);
                logWindowsRead(raw, normalized);
                return normalized != null ? normalized : raw;
            }

            int lastError = Native.getLastError();
            if (lastError == ERROR_NO_MORE_ITEMS) {
                Kernel32.INSTANCE.WaitForSingleObject(readWaitEvent, INFINITE);
                continue;
            }

            throw new RuntimeException("WintunReceivePacket failed, lastError=" + lastError);
        }
    }

    private byte[] normalizeToIpv4(byte[] raw) {
        if (isValidIpv4Packet(raw, 0)) {
            return raw;
        }

        for (int offset = 1; offset <= Math.min(64, raw.length - 20); offset++) {
            if (isValidIpv4Packet(raw, offset)) {
                byte[] result = Arrays.copyOfRange(raw, offset, raw.length);
                System.out.println("wintun normalized IPv4 offset=" + offset + " raw=" + raw.length + " normalized=" + result.length);
                return result;
            }
        }

        return null;
    }

    private boolean isValidIpv4Packet(byte[] data, int offset) {
        if (data.length - offset < 20) {
            return false;
        }

        int version = (data[offset] >> 4) & 0x0f;
        if (version != 4) {
            return false;
        }

        int ihl = (data[offset] & 0x0f) * 4;
        if (ihl < 20 || data.length - offset < ihl) {
            return false;
        }

        int totalLength = ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
        if (totalLength < ihl || totalLength > data.length - offset) {
            return false;
        }

        return true;
    }

    private void writeWindowsPacket(byte[] data) {
        logWindowsWrite(data);

        Pointer packet = Wintun.INSTANCE.WintunAllocateSendPacket(session, data.length);

        if (packet == null || Pointer.nativeValue(packet) == 0) {
            throw new RuntimeException("WintunAllocateSendPacket failed, lastError=" + Native.getLastError());
        }

        packet.write(0, data, 0, data.length);
        Wintun.INSTANCE.WintunSendPacket(session, packet);
    }

    private void logWindowsRead(byte[] raw, byte[] normalized) {
        long count = windowsReadCounter.incrementAndGet();
        if (count <= 30 || count % 100 == 0) {
            if (normalized != null && normalized != raw) {
                System.out.println("wintun read #" + count + " raw=" + raw.length + " normalized=" + normalized.length + " bytes " + PacketInfo.info(normalized));
            } else {
                System.out.println("wintun read #" + count + " " + raw.length + " bytes " + PacketInfo.info(raw));
            }
        }
    }

    private void logWindowsWrite(byte[] packet) {
        long count = windowsWriteCounter.incrementAndGet();
        if (count <= 30 || count % 100 == 0) {
            System.out.println("wintun write #" + count + " " + packet.length + " bytes " + PacketInfo.info(packet));
        }
    }

    private int openLinuxTun(String tunName) {
        int fd = LibC.INSTANCE.open("/dev/net/tun", O_RDWR);

        if (fd < 0) {
            throw new RuntimeException("open /dev/net/tun failed, errno=" + LibC.errno());
        }

        IfReq ifr = new IfReq();

        byte[] nameBytes = tunName.getBytes();
        System.arraycopy(nameBytes, 0, ifr.ifr_name, 0, Math.min(nameBytes.length, ifr.ifr_name.length));

        ifr.ifr_flags = (short) (IFF_TUN | IFF_NO_PI);

        ifr.write();

        int result = LibC.INSTANCE.ioctl(fd, TUNSETIFF, ifr);

        if (result < 0) {
            throw new RuntimeException("ioctl TUNSETIFF failed, errno=" + LibC.errno());
        }

        return fd;
    }

    private interface Wintun extends Library {

        Wintun INSTANCE = Native.load("wintun", Wintun.class);

        Pointer WintunCreateAdapter(WString name, WString tunnelType, Pointer requestedGuid);

        Pointer WintunOpenAdapter(WString name);

        void WintunCloseAdapter(Pointer adapter);

        boolean WintunDeleteAdapter(Pointer adapter, boolean forceCloseSessions);

        Pointer WintunStartSession(Pointer adapter, int capacity);

        void WintunEndSession(Pointer session);

        Pointer WintunGetReadWaitEvent(Pointer session);

        Pointer WintunReceivePacket(Pointer session, IntByReference packetSize);

        void WintunReleaseReceivePacket(Pointer session, Pointer packet);

        Pointer WintunAllocateSendPacket(Pointer session, int packetSize);

        void WintunSendPacket(Pointer session, Pointer packet);
    }

    private interface Kernel32 extends Library {

        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int WaitForSingleObject(Pointer handle, int milliseconds);
    }
}
