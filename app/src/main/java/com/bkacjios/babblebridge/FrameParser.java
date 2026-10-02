package com.bkacjios.babblebridge;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Pulls JPEG frames out of the tracker's serial byte stream.
 *
 * Wire format (shared by Babble / EyeTrackVR serial firmware):
 *   FF A0 FF A1 | uint16 little-endian length | JPEG bytes
 *
 * Bytes can arrive split at any boundary, so this buffers until a full packet
 * is present. A packet is only accepted if its payload starts with the JPEG
 * SOI marker (FF D8); anything else is treated as noise and skipped.
 */
public final class FrameParser {

    static final byte[] HEADER = {(byte) 0xFF, (byte) 0xA0, (byte) 0xFF, (byte) 0xA1};
    private static final int PREFIX = HEADER.length + 2;

    private final Consumer<byte[]> onFrame;
    private byte[] buf = new byte[128 * 1024];
    private int len = 0;

    public FrameParser(Consumer<byte[]> onFrame) {
        this.onFrame = onFrame;
    }

    public void feed(byte[] data, int off, int count) {
        ensureCapacity(len + count);
        System.arraycopy(data, off, buf, len, count);
        len += count;
        parse();
    }

    /** Bytes currently buffered while waiting for the rest of a packet. */
    int buffered() {
        return len;
    }

    private void parse() {
        int pos = 0;
        while (true) {
            int h = indexOfHeader(pos);
            if (h < 0) {
                // Keep a possible partial header at the tail.
                pos = Math.max(pos, len - (HEADER.length - 1));
                break;
            }
            if (len - h < PREFIX) {
                pos = h;
                break;
            }
            int size = (buf[h + 4] & 0xFF) | ((buf[h + 5] & 0xFF) << 8);
            int start = h + PREFIX;
            if (size < 2) {
                pos = h + 1;
                continue;
            }
            // Reject early if the payload clearly isn't a JPEG, rather than
            // waiting up to 64 KB for a bogus length to fill.
            if (len - start >= 2 && !isSoi(start)) {
                pos = h + 1;
                continue;
            }
            if (len - start < size) {
                pos = h;
                break;
            }
            onFrame.accept(Arrays.copyOfRange(buf, start, start + size));
            pos = start + size;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }

    private boolean isSoi(int i) {
        return (buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xFF) == 0xD8;
    }

    private int indexOfHeader(int from) {
        for (int i = from; i <= len - HEADER.length; i++) {
            if (buf[i] == HEADER[0] && buf[i + 1] == HEADER[1]
                    && buf[i + 2] == HEADER[2] && buf[i + 3] == HEADER[3]) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int needed) {
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, needed));
        }
    }
}
