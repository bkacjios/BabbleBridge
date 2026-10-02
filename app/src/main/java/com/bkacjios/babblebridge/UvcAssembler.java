package com.bkacjios.babblebridge;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Rebuilds MJPEG frames from UVC payload transfers. Each payload starts with a UVC header
 * whose FID bit flips on every new frame and whose EOF bit marks a frame's last payload.
 */
final class UvcAssembler {

    private static final int FID = 0x01;
    private static final int EOF = 0x02;
    private static final int ERR = 0x40;

    private final Consumer<byte[]> onFrame;
    private byte[] frame = new byte[64 * 1024];
    private int len;
    private int fid = -1;
    private boolean bad;

    UvcAssembler(Consumer<byte[]> onFrame) {
        this.onFrame = onFrame;
    }

    /** Feeds one payload transfer (header plus data) as read from the streaming endpoint. */
    void feed(byte[] buf, int n) {
        if (n < 2) return;
        int hlen = buf[0] & 0xFF;
        int info = buf[1] & 0xFF;
        // Headers are 2-12 bytes. EOH is mandatory per spec but TinyUSB (OpenIris) leaves it clear.
        if (hlen < 2 || hlen > 12 || hlen > n) {
            // Lost sync with the payload boundaries; drop the frame in progress.
            reset();
            return;
        }
        int f = info & FID;
        if (fid != -1 && f != fid && len > 0) {
            // A new frame started without the last one ending; emit it if it's whole.
            emit();
        }
        fid = f;
        if ((info & ERR) != 0) bad = true;
        append(buf, hlen, n - hlen);
        if ((info & EOF) != 0) emit();
    }

    private void append(byte[] src, int off, int n) {
        if (n <= 0) return;
        if (len + n > frame.length) frame = Arrays.copyOf(frame, Math.max(frame.length * 2, len + n));
        System.arraycopy(src, off, frame, len, n);
        len += n;
    }

    private void emit() {
        // Some encoders pad the last payload after EOI
        while (len > 0 && frame[len - 1] == 0) len--;
        if (!bad && isJpeg()) onFrame.accept(Arrays.copyOf(frame, len));
        len = 0;
        bad = false;
    }

    private void reset() {
        len = 0;
        bad = false;
        fid = -1;
    }

    /** Starts with SOI and ends with EOI, so a truncated frame never reaches clients. */
    private boolean isJpeg() {
        return len >= 4
                && (frame[0] & 0xFF) == 0xFF && (frame[1] & 0xFF) == 0xD8
                && (frame[len - 2] & 0xFF) == 0xFF && (frame[len - 1] & 0xFF) == 0xD9;
    }
}
