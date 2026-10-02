package com.bkacjios.babblebridge;

/**
 * Holds only the latest frame. Slow HTTP clients skip stale frames instead of
 * building up a queue, so latency stays bounded.
 */
public final class FrameBus {

    public static final class Frame {
        public final byte[] data;
        public final long seq;

        Frame(byte[] data, long seq) {
            this.data = data;
            this.seq = seq;
        }
    }

    private byte[] latest;
    private long seq;

    public synchronized void publish(byte[] frame) {
        latest = frame;
        seq++;
        notifyAll();
    }

    /** Waits for a frame newer than {@code afterSeq}; returns null on timeout. */
    public synchronized Frame awaitNext(long afterSeq, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (seq <= afterSeq) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            wait(remaining);
        }
        return new Frame(latest, seq);
    }
}
