package org.telegram.messenger.nexo;

import java.nio.ByteBuffer;

/** FORK MOD (Nexo): frame for stream channels: 4-byte length (big-endian) + sealed payload. */
public class Wire {
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    public static byte[] frame(byte[] payload) {
        return ByteBuffer.allocate(4 + payload.length).putInt(payload.length).put(payload).array();
    }
}
