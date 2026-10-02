package com.bkacjios.babblebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class FrameParserTest {

    private final List<byte[]> frames = new ArrayList<>();
    private FrameParser parser;

    @Before
    public void setUp() {
        frames.clear();
        parser = new FrameParser(frames::add);
    }

    static byte[] jpeg(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (seed + i * 7);
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        // Avoid accidental header sequences inside the payload.
        for (int i = 2; i < size; i++) if (b[i] == (byte) 0xFF) b[i] = 0x00;
        return b;
    }

    static byte[] packet(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(FrameParser.HEADER);
        out.write(payload.length & 0xFF);
        out.write((payload.length >> 8) & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private void feed(byte[] b) {
        parser.feed(b, 0, b.length);
    }

    @Test
    public void singleFrame() {
        byte[] f = jpeg(5000, 1);
        feed(packet(f));
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
        assertEquals(0, parser.buffered());
    }

    @Test
    public void frameSplitOneByteAtATime() {
        byte[] f = jpeg(3000, 2);
        byte[] p = packet(f);
        for (byte b : p) parser.feed(new byte[]{b}, 0, 1);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void garbageBeforeHeaderIsSkipped() {
        byte[] f = jpeg(1000, 3);
        feed(concat(new byte[]{1, 2, 3, (byte) 0xFF, (byte) 0xA0, 9}, packet(f)));
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void multipleFramesInOneRead() {
        byte[] a = jpeg(800, 4), b = jpeg(1200, 5), c = jpeg(60000, 6);
        feed(concat(packet(a), packet(b), packet(c)));
        assertEquals(3, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
        assertArrayEquals(c, frames.get(2));
    }

    @Test
    public void nonJpegPayloadIsRejectedAndNextFrameStillParses() {
        byte[] bogus = packet(new byte[]{0x12, 0x34, 0x56, 0x78});
        // Corrupt length too: claims 60000 bytes but isn't a JPEG.
        bogus[4] = (byte) 0x60;
        bogus[5] = (byte) 0xEA;
        byte[] good = jpeg(2000, 7);
        feed(concat(bogus, packet(good)));
        assertEquals(1, frames.size());
        assertArrayEquals(good, frames.get(0));
    }

    @Test
    public void headerSplitAcrossReads() {
        byte[] f = jpeg(1500, 8);
        byte[] p = packet(f);
        parser.feed(p, 0, 3);
        assertEquals(0, frames.size());
        parser.feed(p, 3, p.length - 3);
        assertEquals(1, frames.size());
    }
}
