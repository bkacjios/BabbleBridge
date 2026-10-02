package com.bkacjios.babblebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MjpegServerTest {

    private final FrameBus bus = new FrameBus();
    private MjpegServer server;
    private int port;

    @Before
    public void setUp() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new MjpegServer(port, bus);
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5000);
        OutputStream out = s.getOutputStream();
        out.write("GET /stream HTTP/1.1\r\nHost: test\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return s;
    }

    /** Reads up to and including the next blank line. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("Stream ended mid-head: " + head);
            head.write(b);
            boolean expectCr = (matched % 2 == 0);
            if ((expectCr && b == '\r') || (!expectCr && b == '\n')) {
                matched++;
            } else {
                matched = (b == '\r') ? 1 : 0;
            }
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    /** Reads one multipart section and returns its JPEG body. */
    private static byte[] readPart(InputStream in) throws IOException {
        String head = readHead(in);
        assertTrue(head, head.startsWith("--babbleframe\r\n"));
        assertTrue(head, head.contains("Content-Type: image/jpeg\r\n"));
        Matcher m = Pattern.compile("Content-Length: (\\d+)\r\n").matcher(head);
        assertTrue(head, m.find());
        byte[] body = in.readNBytes(Integer.parseInt(Objects.requireNonNull(m.group(1))));
        assertEquals('\r', in.read());
        assertEquals('\n', in.read());
        return body;
    }

    private void awaitClientCount(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (server.clientCount() != n && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(n, server.clientCount());
    }

    @Test
    public void sendsMultipartResponseHeaders() throws IOException {
        try (Socket s = connect()) {
            String head = readHead(s.getInputStream());
            assertTrue(head, head.startsWith("HTTP/1.1 200 OK\r\n"));
            assertTrue(head, head.contains("Content-Type: multipart/x-mixed-replace; boundary=babbleframe\r\n"));
            assertTrue(head, head.contains("Cache-Control: no-cache, no-store\r\n"));
        }
    }

    @Test
    public void streamsPublishedFramesInOrder() throws IOException {
        try (Socket s = connect()) {
            InputStream in = s.getInputStream();
            readHead(in);
            byte[] a = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
            bus.publish(a);
            assertArrayEquals(a, readPart(in));
            byte[] b = {(byte) 0xFF, (byte) 0xD8, 4, 5, (byte) 0xFF, (byte) 0xD9};
            bus.publish(b);
            assertArrayEquals(b, readPart(in));
        }
    }

    @Test
    public void newClientGetsLatestFrameImmediately() throws IOException {
        byte[] latest = {1, 2, 3, 4};
        bus.publish(new byte[]{9});
        bus.publish(latest);
        try (Socket s = connect()) {
            InputStream in = s.getInputStream();
            readHead(in);
            assertArrayEquals(latest, readPart(in));
        }
    }

    @Test
    public void servesMultipleClients() throws IOException, InterruptedException {
        try (Socket s1 = connect(); Socket s2 = connect()) {
            readHead(s1.getInputStream());
            readHead(s2.getInputStream());
            awaitClientCount(2);
            byte[] f = {7, 7, 7};
            bus.publish(f);
            assertArrayEquals(f, readPart(s1.getInputStream()));
            assertArrayEquals(f, readPart(s2.getInputStream()));
        }
    }

    @Test
    public void clientCountDropsWhenClientLeaves() throws IOException, InterruptedException {
        assertEquals(0, server.clientCount());
        Socket s = connect();
        readHead(s.getInputStream());
        awaitClientCount(1);
        s.close();
        // The server only notices once a write fails, so keep frames flowing
        long deadline = System.currentTimeMillis() + 5000;
        for (byte i = 0; server.clientCount() != 0 && System.currentTimeMillis() < deadline; i++) {
            bus.publish(new byte[]{i});
            Thread.sleep(20);
        }
        assertEquals(0, server.clientCount());
    }

    @Test
    public void stopDisconnectsClients() throws IOException, InterruptedException {
        try (Socket s = connect()) {
            InputStream in = s.getInputStream();
            readHead(in);
            awaitClientCount(1);
            server.stop();
            int r;
            try {
                r = in.read();
            } catch (IOException e) {
                r = -1; // A reset is a disconnect too
            }
            assertEquals(-1, r);
        }
    }
}
