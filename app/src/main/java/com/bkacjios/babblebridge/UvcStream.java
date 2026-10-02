package com.bkacjios.babblebridge;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Minimal UVC client for cameras that stream over a bulk endpoint, like the OpenIris tracker
 * (MJPEG only). Bulk streaming needs no alternate setting: committing the probe starts it.
 */
final class UvcStream implements TrackerSource {

    private static final String TAG = "BabbleBridge";

    private static final int SC_VIDEOSTREAMING = 0x02;
    private static final int SET_CUR = 0x01;
    private static final int GET_CUR = 0x81;
    private static final int VS_PROBE_CONTROL = 0x01;
    private static final int VS_COMMIT_CONTROL = 0x02;
    /** Probe/commit block sizes for UVC 1.5, 1.1 and 1.0; devices reject the wrong one. */
    private static final int[] PROBE_SIZES = {48, 34, 26};
    private static final int TIMEOUT_MS = 1000;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint ep;
    private final UvcAssembler assembler;
    private byte[] buf;

    /** The video streaming interface with a bulk IN endpoint, or null if this isn't such a camera. */
    static UsbInterface findStreamingInterface(UsbDevice dev) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO
                    && intf.getInterfaceSubclass() == SC_VIDEOSTREAMING
                    && bulkIn(intf) != null) {
                return intf;
            }
        }
        return null;
    }

    private static UsbEndpoint bulkIn(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && e.getDirection() == UsbConstants.USB_DIR_IN) {
                return e;
            }
        }
        return null;
    }

    UvcStream(UsbDeviceConnection conn, UsbInterface intf, Consumer<byte[]> onFrame) {
        this.conn = conn;
        this.intf = intf;
        this.ep = bulkIn(intf);
        this.assembler = new UvcAssembler(onFrame);
    }

    /** Negotiates the first format and frame size at the camera's default rate, then starts it. */
    void start() throws IOException {
        if (!conn.claimInterface(intf, true)) throw new IOException("Couldn't claim the video interface");
        byte[] probe = null;
        for (int size : PROBE_SIZES) {
            byte[] p = new byte[size];
            // Start from the camera's current values so fields we don't set stay valid
            conn.controlTransfer(0xA1, GET_CUR, VS_PROBE_CONTROL << 8, intf.getId(), p, size, TIMEOUT_MS);
            p[0] = 1; // bmHint: keep dwFrameInterval
            p[1] = 0;
            p[2] = 1; // bFormatIndex
            p[3] = 1; // bFrameIndex
            if (le32(p, 4) == 0) putLe32(p, 4, defaultInterval());
            if (control(SET_CUR, VS_PROBE_CONTROL, p) == size) {
                probe = p;
                break;
            }
        }
        if (probe == null) throw new IOException("Camera rejected the video probe");
        control(GET_CUR, VS_PROBE_CONTROL, probe);
        if (control(SET_CUR, VS_COMMIT_CONTROL, probe) != probe.length) {
            throw new IOException("Camera rejected the video commit");
        }
        int frameSize = le32(probe, 18);
        int payload = le32(probe, 22);
        Log.i(TAG, "UVC streaming: probe " + probe.length + " bytes, interval "
                + le32(probe, 4) + ", max frame " + frameSize + ", max payload " + payload);
        // A read must hold a whole payload, or the next read would start mid-payload
        // without a header. Bound it in case the camera reports something silly.
        buf = new byte[Math.min(Math.max(payload, ep.getMaxPacketSize()), 1 << 20)];
    }

    /** Reads one payload; complete frames go to the callback. */
    @Override
    public int poll(int timeoutMs) {
        int n = conn.bulkTransfer(ep, buf, buf.length, timeoutMs);
        if (n <= 0) return 0; // timeout and errors look the same here; the caller checks liveness
        assembler.feed(buf, n);
        return n;
    }

    @Override
    public void close() {
        // Bulk streams stop when the host clears the endpoint halt
        conn.controlTransfer(0x02, 0x01, 0, ep.getAddress(), null, 0, TIMEOUT_MS);
        conn.releaseInterface(intf);
        conn.close();
    }

    private int control(int request, int selector, byte[] data) {
        int type = request == GET_CUR ? 0xA1 : 0x21;
        return conn.controlTransfer(type, request, selector << 8, intf.getId(), data, data.length, TIMEOUT_MS);
    }

    /** dwDefaultFrameInterval of the first MJPEG frame descriptor, in 100 ns units. */
    private int defaultInterval() {
        byte[] d = conn.getRawDescriptors();
        for (int i = 0; d != null && i + 2 < d.length && d[i] > 0; i += d[i] & 0xFF) {
            boolean mjpegFrame = (d[i + 1] & 0xFF) == 0x24 && d[i + 2] == 0x07;
            if (mjpegFrame && (d[i] & 0xFF) >= 25 && i + 25 <= d.length && d[i + 3] == 1) {
                return le32(d, i + 21);
            }
        }
        return 333_333; // 30 fps
    }

    private static void putLe32(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }
}
