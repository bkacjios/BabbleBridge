package com.bkacjios.babblebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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

    @Test
    public void partialFrameStaysBufferedUntilComplete() {
        byte[] f = jpeg(4000, 9);
        byte[] p = packet(f);
        parser.feed(p, 0, p.length - 10);
        assertEquals(0, frames.size());
        assertEquals(p.length - 10, parser.buffered());
        parser.feed(p, p.length - 10, 10);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
        assertEquals(0, parser.buffered());
    }

    @Test
    public void garbageOnlyKeepsAtMostAPartialHeader() {
        byte[] junk = new byte[10000];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i % 0x7F);
        feed(junk);
        assertEquals(0, frames.size());
        assertTrue(parser.buffered() < FrameParser.HEADER.length);
    }

    @Test
    public void partialHeaderAfterGarbageIsKept() {
        byte[] f = jpeg(700, 10);
        byte[] p = packet(f);
        feed(concat(new byte[]{5, 6, 7, 8}, Arrays.copyOf(p, 3)));
        assertEquals(0, frames.size());
        parser.feed(p, 3, p.length - 3);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void tinyLengthsAreSkipped() {
        byte[] zero = packet(new byte[0]);
        byte[] one = packet(new byte[]{0x00});
        byte[] good = jpeg(500, 11);
        feed(concat(zero, one, packet(good)));
        assertEquals(1, frames.size());
        assertArrayEquals(good, frames.get(0));
    }

    @Test
    public void bogusLengthIsRejectedWithoutWaitingForIt() {
        byte[] bogus = concat(FrameParser.HEADER, new byte[]{(byte) 0x60, (byte) 0xEA, 0x12, 0x34});
        feed(bogus);
        assertEquals(0, frames.size());
        assertTrue(parser.buffered() < FrameParser.HEADER.length);

        byte[] good = jpeg(900, 12);
        feed(packet(good));
        assertEquals(1, frames.size());
        assertArrayEquals(good, frames.get(0));
    }

    @Test
    public void waitsForTwoPayloadBytesBeforeCheckingSoi() {
        byte[] f = jpeg(600, 13);
        byte[] p = packet(f);
        // Header, length and just the first JPEG byte
        parser.feed(p, 0, 7);
        assertEquals(7, parser.buffered());
        parser.feed(p, 7, p.length - 7);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void headerBytesInsidePayloadDontSplitTheFrame() {
        byte[] f = jpeg(1000, 14);
        System.arraycopy(FrameParser.HEADER, 0, f, 100, FrameParser.HEADER.length);
        feed(packet(f));
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void largeReadsGrowTheBuffer() {
        byte[] a = jpeg(60000, 15), b = jpeg(60000, 16), c = jpeg(60000, 17);
        feed(concat(packet(a), packet(b), packet(c)));
        assertEquals(3, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
        assertArrayEquals(c, frames.get(2));
    }

    @Test
    public void maximumLengthFrame() {
        byte[] f = jpeg(0xFFFF, 18);
        feed(packet(f));
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
    }

    @Test
    public void feedHonorsOffsetAndCount() {
        byte[] f = jpeg(400, 19);
        byte[] p = packet(f);
        byte[] wrapped = concat(new byte[]{(byte) 0xFF, (byte) 0xA0, (byte) 0xFF}, p, FrameParser.HEADER);
        parser.feed(wrapped, 3, p.length);
        assertEquals(1, frames.size());
        assertArrayEquals(f, frames.get(0));
        assertEquals(0, parser.buffered());
    }

    @Test
    public void emittedFramesAreNotReusedByLaterFeeds() {
        byte[] a = jpeg(500, 20), b = jpeg(500, 21);
        feed(packet(a));
        feed(packet(b));
        assertEquals(2, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
    }
}
