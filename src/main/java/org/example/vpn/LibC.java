package org.example.vpn;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

public interface LibC extends Library {

    LibC INSTANCE = Native.load("c", LibC.class);

    int open(String path, int flags);

    int ioctl(int fd, long request, Structure arg);

    int read(int fd, byte[] buffer, int count);

    int write(int fd, byte[] buffer, int count);

    static int errno() {
        return Native.getLastError();
    }
}
