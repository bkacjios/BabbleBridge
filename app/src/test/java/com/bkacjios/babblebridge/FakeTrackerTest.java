package com.bkacjios.babblebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FakeTrackerTest {

    private final FakeTracker fake = new FakeTracker();

    @Test
    public void packetHasSerialHeaderAndLength() {
        byte[] p = fake.nextPacket();
        assertEquals(FrameParser.HEADER[0], p[0]);
        assertEquals(FrameParser.HEADER[1], p[1]);
        assertEquals(FrameParser.HEADER[2], p[2]);
        assertEquals(FrameParser.HEADER[3], p[3]);
        int len = (p[4] & 0xFF) | (p[5] & 0xFF) << 8;
        assertEquals(p.length - 6, len);
    }

    @Test
    public void payloadIsADecodableJpeg() {
        byte[] p = fake.nextPacket();
        byte[] jpeg = Arrays.copyOfRange(p, 6, p.length);
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        assertNotNull(bmp);
        assertEquals(240, bmp.getWidth());
        assertEquals(240, bmp.getHeight());
    }

    @Test
    public void framesParseThroughTheRealParser() {
        List<byte[]> frames = new ArrayList<>();
        FrameParser parser = new FrameParser(frames::add);
        for (int i = 0; i < 5; i++) {
            byte[] p = fake.nextPacket();
            parser.feed(p, 0, p.length);
        }
        assertEquals(5, frames.size());
        assertEquals(0, parser.buffered());
    }

    @Test
    public void framesChangeOverTime() {
        byte[] a = fake.nextPacket();
        byte[] b = fake.nextPacket();
        assertFalse(Arrays.equals(a, b));
    }
}
