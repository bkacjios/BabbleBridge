package com.bkacjios.babblebridge;

import java.io.Closeable;
import java.io.IOException;

/** An open tracker connection (serial or UVC) that delivers JPEG frames as it's polled. */
interface TrackerSource extends Closeable {
    /** Reads whatever is available, waiting up to the timeout. Returns bytes read, 0 if none. */
    int poll(int timeoutMs) throws IOException;
}
