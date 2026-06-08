package org.example.vpn;

import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class TunDevice {

    private static final int O_RDWR = 2;
    private static final short IFF_TUN = 0x0001;
    private static final short IFF_NO_PI = 0x1000;
    private static final long TUNSETIFF = 0x400454caL;
    private static final int MAX_PACKET_SIZE = 65535;

    private int fd;
    private boolean windows;

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
            throw new UnsupportedOperationException("Windows TUN is not wired yet. Install Wintun and add JNA bindings next.");
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
            throw new UnsupportedOperationException("Windows TUN is not wired yet. Install Wintun and add JNA bindings next.");
        }

        int written = LibC.INSTANCE.write(fd, data, data.length);

        if (written != data.length) {
            throw new RuntimeException("tun write failed, written=" + written + ", expected=" + data.length + ", errno=" + LibC.errno());
        }
    }

    private void openWindowsTun(String tunName) {
        System.out.println("Windows detected. Linux /dev/net/tun is disabled for client.");
        System.out.println("Next required step: put wintun.dll near the application and wire Wintun session API.");
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
}
