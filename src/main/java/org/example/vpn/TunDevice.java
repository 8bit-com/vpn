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

    public void open(String tunName) {
        fd = openTun(tunName);
        System.out.println(tunName + " opened");
    }

    public byte[] readPacket() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        int len = LibC.INSTANCE.read(fd, buffer, buffer.length);
        if (len <= 0) {
            return null;
        }
        return Arrays.copyOf(buffer, len);
    }

    public void writePacket(byte[] data) {
        int written = LibC.INSTANCE.write(fd, data, data.length);

        if (written != data.length) {
            throw new RuntimeException("tun write failed, written=" + written + ", expected=" + data.length + ", errno=" + LibC.errno());
        }
    }

    private int openTun(String tunName) {
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
