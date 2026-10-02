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
}
