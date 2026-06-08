package org.example.vpn;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class TunDevice {

    private static final int O_RDWR = 2;
    private static final short IFF_TUN = 0x0001;
    private static final short IFF_NO_PI = 0x1000;
    private static final long TUNSETIFF = 0x400454caL;
    private static final int MAX_PACKET_SIZE = 65535;
    private static final int WINTUN_SESSION_CAPACITY = 0x400000;

    private int fd;
    private boolean windows;
    private Pointer adapter;
    private Pointer session;
    private int interfaceIndex;

    public void open(String tunName) {
        windows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (windows) {
            openWindowsTun(tunName);
            return;
        }

        fd = openLinuxTun(tunName);
        System.out.println(tunName + " opened");
    }

    public int interfaceIndex() {
        return interfaceIndex;
    }

    public byte[] readPacket() {
        if (windows) {
            return readWindowsPacket();
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

            IntByReference indexRef = new IntByReference();
            Wintun.INSTANCE.WintunGetAdapterLUID(adapter, indexRef);
            interfaceIndex = indexRef.getValue();

            session = Wintun.INSTANCE.WintunStartSession(adapter, WINTUN_SESSION_CAPACITY);

            if (session == null || Pointer.nativeValue(session) == 0) {
                throw new RuntimeException("WintunStartSession failed, lastError=" + Native.getLastError());
            }

            System.out.println(tunName + " opened by wintun.dll, interface=" + interfaceIndex);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("wintun.dll not found. Put wintun.dll near java.exe working directory or add it to PATH", e);
        }
    }

    private byte[] readWindowsPacket() {
        IntByReference packetSizeRef = new IntByReference();
        Pointer packet = Wintun.INSTANCE.WintunReceivePacket(session, packetSizeRef);

        if (packet == null || Pointer.nativeValue(packet) == 0) {
            return null;
        }

        int packetSize = packetSizeRef.getValue();
        byte[] result = packet.getByteArray(0, packetSize);
        Wintun.INSTANCE.WintunReleaseReceivePacket(session, packet);
        return result;
    }

    private void writeWindowsPacket(byte[] data) {
        Pointer packet = Wintun.INSTANCE.WintunAllocateSendPacket(session, data.length);

        if (packet == null || Pointer.nativeValue(packet) == 0) {
            throw new RuntimeException("WintunAllocateSendPacket failed, lastError=" + Native.getLastError());
        }

        packet.write(0, data, 0, data.length);
        Wintun.INSTANCE.WintunSendPacket(session, packet);
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

        void WintunGetAdapterLUID(Pointer adapter, IntByReference luid);

        Pointer WintunStartSession(Pointer adapter, int capacity);

        void WintunEndSession(Pointer session);

        Pointer WintunReceivePacket(Pointer session, IntByReference packetSize);

        void WintunReleaseReceivePacket(Pointer session, Pointer packet);

        Pointer WintunAllocateSendPacket(Pointer session, int packetSize);

        void WintunSendPacket(Pointer session, Pointer packet);
    }
}
