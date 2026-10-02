package com.bkacjios.babblebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class UvcAssemblerTest {

    private static final int FID = 0x01;
    private static final int EOF = 0x02;
    private static final int ERR = 0x40;

    private final List<byte[]> frames = new ArrayList<>();
    private UvcAssembler assembler;

    @Before
    public void setUp() {
        frames.clear();
        assembler = new UvcAssembler(frames::add);
    }

    /** A JPEG-shaped blob: SOI, filler without 0xFF, EOI. */
    static byte[] jpeg(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 2; i < size - 2; i++) {
            b[i] = (byte) (seed + i * 7);
            if (b[i] == (byte) 0xFF || b[i] == 0) b[i] = 0x11;
        }
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[size - 2] = (byte) 0xFF;
        b[size - 1] = (byte) 0xD9;
        return b;
    }

    /** Splits a frame into 64-byte payloads with 2-byte headers, like the OpenIris tracker. */
    private void send(byte[] frame, int fid, boolean eof) {
        sendWithHeader(frame, fid, eof, 2, 64);
    }

    private void sendWithHeader(byte[] frame, int fid, boolean eof, int hlen, int payload) {
        int chunk = payload - hlen;
        for (int off = 0; off < frame.length; off += chunk) {
            int n = Math.min(chunk, frame.length - off);
            boolean last = off + n >= frame.length;
            byte[] p = new byte[hlen + n];
            p[0] = (byte) hlen;
            p[1] = (byte) (fid | (last && eof ? EOF : 0));
            System.arraycopy(frame, off, p, hlen, n);
            assembler.feed(p, p.length);
        }
    }

    @Test
    public void assemblesFrameAcrossPayloads() {
        byte[] f = jpeg(1000, 1);
        send(f, 0, true);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void fidToggleEndsFrameWithoutEof() {
        byte[] a = jpeg(300, 1);
        byte[] b = jpeg(400, 2);
        send(a, 0, false);
        send(b, FID, true);
        assertEquals(2, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
    }

    @Test
    public void dropsFrameJoinedMidway() {
        byte[] a = jpeg(500, 1);
        byte[] b = jpeg(500, 2);
        send(Arrays.copyOfRange(a, 200, a.length), 0, true);
        send(b, FID, true);
        assertEquals(1, frames.size());
        assertArrayEquals(b, frames.get(0));
    }

    @Test
    public void dropsFrameWithErrorBit() {
        byte[] a = jpeg(100, 1);
        byte[] p = new byte[a.length + 2];
        p[0] = 2;
        p[1] = (byte) (EOF | ERR);
        System.arraycopy(a, 0, p, 2, a.length);
        assembler.feed(p, p.length);
        assertEquals(0, frames.size());

        send(a, FID, true);
        assertEquals(1, frames.size());
    }

    @Test
    public void handlesLongHeaders() {
        byte[] f = jpeg(2000, 3);
        sendWithHeader(f, 0, true, 12, 512);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void trimsPaddingAfterEoi() {
        byte[] f = jpeg(200, 4);
        byte[] padded = Arrays.copyOf(f, f.length + 30);
        send(padded, 0, true);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void resyncsAfterGarbageHeader() {
        assembler.feed(new byte[]{(byte) 0x80, 0, 1, 2}, 4);
        byte[] f = jpeg(300, 5);
        send(f, 0, true);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    /** One payload with a 2-byte header carrying the given info bits. */
    private void payload(int info, byte[] data, int off, int n) {
        byte[] p = new byte[2 + n];
        p[0] = 2;
        p[1] = (byte) info;
        System.arraycopy(data, off, p, 2, n);
        assembler.feed(p, p.length);
    }

    @Test
    public void ignoresRuntTransfersMidFrame() {
        byte[] f = jpeg(600, 6);
        send(Arrays.copyOfRange(f, 0, 300), 0, false);
        assembler.feed(new byte[]{2}, 1);
        assembler.feed(new byte[0], 0);
        send(Arrays.copyOfRange(f, 300, f.length), 0, true);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void errorBitMidFrameDropsWholeFrame() {
        byte[] f = jpeg(300, 7);
        payload(0, f, 0, 100);
        payload(ERR, f, 100, 100);
        payload(EOF, f, 200, 100);
        assertEquals(0, frames.size());
    }

    @Test
    public void headerLongerThanTransferDropsFrameInProgress() {
        byte[] a = jpeg(400, 8);
        send(Arrays.copyOfRange(a, 0, 200), 0, false);
        assembler.feed(new byte[]{10, 0, 0, 0}, 4);
        send(Arrays.copyOfRange(a, 200, a.length), 0, true);
        assertEquals(0, frames.size());

        byte[] b = jpeg(400, 9);
        send(b, FID, true);
        assertEquals(1, frames.size());
        assertArrayEquals(b, frames.get(0));
    }

    @Test
    public void headerLengthAboveTwelveIsRejected() {
        byte[] f = jpeg(300, 10);
        sendWithHeader(f, 0, true, 13, 64);
        assertEquals(0, frames.size());
        send(f, FID, true);
        assertEquals(1, frames.size());
    }

    @Test
    public void truncatedFrameIsDroppedOnFidToggle() {
        byte[] a = jpeg(500, 11);
        byte[] b = jpeg(500, 12);
        send(Arrays.copyOfRange(a, 0, 250), 0, false);
        send(b, FID, true);
        assertEquals(1, frames.size());
        assertArrayEquals(b, frames.get(0));
    }

    @Test
    public void frameMissingEoiIsDroppedEvenWithEofBit() {
        byte[] a = jpeg(500, 13);
        send(Arrays.copyOfRange(a, 0, a.length - 1), 0, true);
        assertEquals(0, frames.size());
    }

    @Test
    public void headerOnlyPayloadCanCarryEof() {
        byte[] f = jpeg(500, 14);
        send(f, 0, false);
        payload(0, new byte[0], 0, 0);
        assertEquals(0, frames.size());
        payload(EOF, new byte[0], 0, 0);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void eofSeparatesFramesWithSameFid() {
        byte[] a = jpeg(300, 15);
        byte[] b = jpeg(350, 16);
        send(a, 0, true);
        send(b, 0, true);
        assertEquals(2, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
    }

    @Test
    public void growsForFramesLargerThanInitialBuffer() {
        byte[] f = jpeg(200_000, 17);
        sendWithHeader(f, 0, true, 12, 16 * 1024);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void readsOnlyTheTransferredBytes() {
        byte[] f = jpeg(50, 18);
        byte[] p = new byte[2 + f.length + 20];
        p[0] = 2;
        p[1] = EOF;
        System.arraycopy(f, 0, p, 2, f.length);
        Arrays.fill(p, 2 + f.length, p.length, (byte) 0x55);
        assembler.feed(p, 2 + f.length);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void emittedFramesAreCopies() {
        byte[] a = jpeg(300, 19);
        byte[] b = jpeg(300, 20);
        send(a, 0, true);
        send(b, FID, true);
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
    }
}
