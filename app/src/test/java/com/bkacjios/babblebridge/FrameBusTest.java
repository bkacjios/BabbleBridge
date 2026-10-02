package com.bkacjios.babblebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Assert;
import org.junit.Test;

import java.util.Objects;

public class FrameBusTest {

    private final FrameBus bus = new FrameBus();

    @Test
    public void timesOutWithNullWhenNothingPublished() throws InterruptedException {
        long start = System.currentTimeMillis();
        assertNull(bus.awaitNext(0, 50));
        assertTrue(System.currentTimeMillis() - start >= 40);
    }

    @Test
    public void returnsPublishedFrame() throws InterruptedException {
        byte[] data = {1, 2, 3};
        bus.publish(data);
        FrameBus.Frame f = bus.awaitNext(0, 1000);
        assertNotNull(f);
        assertSame(data, f.data);
        assertEquals(1, f.seq);
    }

    @Test
    public void skipsStaleFramesAndReturnsLatest() throws InterruptedException {
        bus.publish(new byte[]{1});
        bus.publish(new byte[]{2});
        bus.publish(new byte[]{3});
        FrameBus.Frame f = bus.awaitNext(0, 1000);
        assertNotNull(f);
        assertArrayEquals(new byte[]{3}, f.data);
        assertEquals(3, f.seq);
    }

    @Test
    public void alreadySeenFrameIsNotReturnedAgain() throws InterruptedException {
        bus.publish(new byte[]{1});
        FrameBus.Frame f = bus.awaitNext(0, 1000);
        assertNotNull(f);
        assertNull(bus.awaitNext(f.seq, 50));
    }

    @Test
    public void wakesWaiterWhenFrameIsPublished() throws InterruptedException {
        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            bus.publish(new byte[]{9});
        });
        publisher.start();
        long start = System.currentTimeMillis();
        FrameBus.Frame f = bus.awaitNext(0, 5000);
        assertArrayEquals(new byte[]{9}, f.data);
        assertTrue(System.currentTimeMillis() - start < 4000);
        publisher.join();
    }

    @Test
    public void everyReaderSeesTheSameFrame() throws InterruptedException {
        bus.publish(new byte[]{4});
        assertEquals(1, Objects.requireNonNull(bus.awaitNext(0, 1000)).seq);
        assertEquals(1, Objects.requireNonNull(bus.awaitNext(0, 1000)).seq);
    }
}
