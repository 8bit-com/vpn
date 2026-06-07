package org.example.vpn;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

public interface Wintun extends Library {

    Wintun INSTANCE =
            Native.load(
                    "wintun",
                    Wintun.class
            );

    Pointer WintunCreateAdapter(
            WString name,
            WString tunnelType,
            Pointer requestedGuid
    );

    int WintunDeleteAdapter(
            Pointer adapter
    );

    Pointer WintunStartSession(
            Pointer adapter,
            int capacity
    );

    Pointer WintunAllocateSendPacket(
            Pointer session,
            int size
    );

    void WintunSendPacket(
            Pointer session,
            Pointer packet
    );

    Pointer WintunReceivePacket(
            Pointer session,
            IntByReference packetSize
    );

    void WintunReleaseReceivePacket(
            Pointer session,
            Pointer packet
    );
}