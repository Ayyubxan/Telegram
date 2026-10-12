package org.telegram.messenger.nexo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FORK MOD (Nexo): reassembles {@link Wire} frames from a byte stream. Keeps one growing buffer so that
 * small BLE chunks collected into a large frame do not copy the whole buffer every time.
 */
public class FrameParser {
    private byte[] buffer = new byte[4096];
    private int size = 0;

    /** @throws IllegalStateException on a corrupt frame header */
    public List<byte[]> push(byte[] data) {
        if (size + data.length > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + data.length));
        System.arraycopy(data, 0, buffer, size, data.length);
        size += data.length;
        ArrayList<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (size - offset >= 4) {
            int len = ByteBuffer.wrap(buffer, offset, 4).getInt();
            if (len <= 0 || len > Wire.MAX_FRAME) throw new IllegalStateException("bad frame");
            if (size - offset - 4 < len) break;
            frames.add(Arrays.copyOfRange(buffer, offset + 4, offset + 4 + len));
            offset += 4 + len;
        }
        if (offset > 0) {
            System.arraycopy(buffer, offset, buffer, 0, size - offset);
            size -= offset;
        }
        return frames;
    }
}
