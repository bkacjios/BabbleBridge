package com.bkacjios.babblebridge;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal multipart/x-mixed-replace (MJPEG) server; any path returns the stream. */
public final class MjpegServer {

    private static final String BOUNDARY = "babbleframe";
    private static final byte[] CRLF = {'\r', '\n'};

    private final int port;
    private final FrameBus bus;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    public MjpegServer(int port, FrameBus bus) {
        this.port = port;
        this.bus = bus;
    }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        Thread t = new Thread(this::acceptLoop, "mjpeg-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        ServerSocket s = server;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        for (Socket c : clients) {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    public int clientCount() {
        return clients.size();
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                Thread t = new Thread(() -> serve(c), "mjpeg-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (s.isClosed()) return;
            }
        }
    }

    private void serve(Socket socket) {
        clients.add(socket);
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            skipRequestHead(s);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-cache, no-store\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            long seq = 0;
            while (!s.isClosed()) {
                FrameBus.Frame f = bus.awaitNext(seq, 2000);
                if (f == null) continue;
                seq = f.seq;
                out.write(("--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + f.data.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(f.data);
                out.write(CRLF);
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // client went away
        } finally {
            clients.remove(socket);
        }
    }

    /** Reads (and ignores) the HTTP request up to the blank line. */
    private static void skipRequestHead(Socket s) throws IOException {
        s.setSoTimeout(2000);
        InputStream in = s.getInputStream();
        int matched = 0;
        int total = 0;
        try {
            while (matched < 4 && total < 16 * 1024) {
                int b = in.read();
                if (b < 0) break;
                total++;
                boolean expectCr = (matched % 2 == 0);
                if ((expectCr && b == '\r') || (!expectCr && b == '\n')) {
                    matched++;
                } else {
                    matched = (b == '\r') ? 1 : 0;
                }
            }
        } catch (SocketTimeoutException ignored) {
            // No request head; stream anyway.
        }
        s.setSoTimeout(0);
    }
}
