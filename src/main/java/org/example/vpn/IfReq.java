package org.example.vpn;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public class IfReq extends Structure {

    public byte[] ifr_name = new byte[16];

    public short ifr_flags;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(
                "ifr_name",
                "ifr_flags"
        );
    }
}
