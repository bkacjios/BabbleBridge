package com.bkacjios.babblebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class UvcStreamTest {

    private static final int SET_CUR = 0x01;
    private static final int GET_CUR = 0x81;
    private static final int PROBE = 0x01 << 8;
    private static final int COMMIT = 0x02 << 8;
    private static final int INTF_ID = 1;
    private static final int EP_ADDR = 0x81;

    private final List<byte[]> frames = new ArrayList<>();
    /** Every SET_CUR probe block the stream sent, in order. */
    private final List<byte[]> probesSent = new ArrayList<>();
    private byte[] committed;

    // Fake camera behavior
    private int acceptedProbeSize = 34;
    private boolean rejectCommit;
    /** What the camera reports for GET_CUR before negotiation. */
    private final byte[] cameraState = new byte[48];

    private UsbDeviceConnection conn;
    private UsbInterface intf;
    private UsbEndpoint ep;

    static UsbEndpoint endpoint(int type, int direction) {
        UsbEndpoint e = mock(UsbEndpoint.class);
        when(e.getType()).thenReturn(type);
        when(e.getDirection()).thenReturn(direction);
        when(e.getMaxPacketSize()).thenReturn(512);
        when(e.getAddress()).thenReturn(EP_ADDR);
        return e;
    }

    static UsbInterface iface(int cls, int subclass, UsbEndpoint... eps) {
        UsbInterface i = mock(UsbInterface.class);
        when(i.getId()).thenReturn(INTF_ID);
        when(i.getInterfaceClass()).thenReturn(cls);
        when(i.getInterfaceSubclass()).thenReturn(subclass);
        when(i.getEndpointCount()).thenReturn(eps.length);
        for (int n = 0; n < eps.length; n++) when(i.getEndpoint(n)).thenReturn(eps[n]);
        return i;
    }

    static UsbDevice device(UsbInterface... intfs) {
        UsbDevice d = mock(UsbDevice.class);
        when(d.getInterfaceCount()).thenReturn(intfs.length);
        for (int n = 0; n < intfs.length; n++) when(d.getInterface(n)).thenReturn(intfs[n]);
        return d;
    }

    static UsbInterface videoStreaming() {
        return iface(UsbConstants.USB_CLASS_VIDEO, 0x02,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN));
    }

    static void putLe32(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    /** A VS_FRAME_MJPEG descriptor with the given frame index and default interval. */
    static byte[] mjpegFrameDescriptor(int frameIndex, int defaultInterval) {
        byte[] d = new byte[30];
        d[0] = 30;
        d[1] = 0x24;
        d[2] = 0x07;
        d[3] = (byte) frameIndex;
        putLe32(d, 21, defaultInterval);
        return d;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @Before
    public void setUp() {
        ep = endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN);
        intf = iface(UsbConstants.USB_CLASS_VIDEO, 0x02, ep);
        conn = mock(UsbDeviceConnection.class);
        when(conn.claimInterface(intf, true)).thenReturn(true);

        putLe32(cameraState, 4, 333_333);   // dwFrameInterval
        putLe32(cameraState, 18, 100_000);  // dwMaxVideoFrameSize
        putLe32(cameraState, 22, 3072);     // dwMaxPayloadTransferSize

        when(conn.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    int type = inv.getArgument(0);
                    int request = inv.getArgument(1);
                    int value = inv.getArgument(2);
                    byte[] data = inv.getArgument(4);
                    int length = inv.getArgument(5);
                    if (type == 0xA1 && request == GET_CUR && value == PROBE) {
                        int n = Math.min(length, cameraState.length);
                        System.arraycopy(cameraState, 0, data, 0, n);
                        return n;
                    }
                    if (type == 0x21 && request == SET_CUR && value == PROBE) {
                        probesSent.add(Arrays.copyOf(data, length));
                        if (length != acceptedProbeSize) return -1;
                        // The camera echoes what it accepted on the next GET_CUR
                        System.arraycopy(data, 0, cameraState, 0, length);
                        return length;
                    }
                    if (type == 0x21 && request == SET_CUR && value == COMMIT) {
                        if (rejectCommit) return -1;
                        committed = Arrays.copyOf(data, length);
                        return length;
                    }
                    return 0;
                });
    }

    private UvcStream stream() {
        return new UvcStream(conn, intf, frames::add);
    }

    /** Negotiates and closes again, for tests that only check what was committed. */
    private void startAndClose() throws IOException {
        try (UvcStream s = stream()) {
            s.start();
        }
    }

    // ---- findStreamingInterface ----

    @Test
    public void findsVideoStreamingInterfaceWithBulkIn() {
        UsbInterface audio = iface(UsbConstants.USB_CLASS_AUDIO, 0x02,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN));
        UsbInterface control = iface(UsbConstants.USB_CLASS_VIDEO, 0x01,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_IN));
        UsbInterface streaming = videoStreaming();
        assertSame(streaming, UvcStream.findStreamingInterface(device(audio, control, streaming)));
    }

    @Test
    public void ignoresIsochronousStreamingInterface() {
        UsbInterface iso = iface(UsbConstants.USB_CLASS_VIDEO, 0x02,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_ISOC, UsbConstants.USB_DIR_IN));
        assertNull(UvcStream.findStreamingInterface(device(iso)));
    }

    @Test
    public void ignoresBulkOutOnlyStreamingInterface() {
        UsbInterface out = iface(UsbConstants.USB_CLASS_VIDEO, 0x02,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_OUT));
        assertNull(UvcStream.findStreamingInterface(device(out)));
    }

    @Test
    public void noInterfacesMeansNoCamera() {
        assertNull(UvcStream.findStreamingInterface(device()));
    }

    // ---- start ----

    @Test
    public void triesProbeSizesUntilCameraAcceptsOne() throws IOException {
        acceptedProbeSize = 26;
        startAndClose();
        assertEquals(3, probesSent.size());
        assertEquals(48, probesSent.get(0).length);
        assertEquals(34, probesSent.get(1).length);
        assertEquals(26, probesSent.get(2).length);
        assertEquals(26, committed.length);
    }

    @Test
    public void commitsFirstFormatAndFrameKeepingInterval() throws IOException {
        startAndClose();
        assertEquals(34, committed.length);
        assertEquals(1, committed[0]); // bmHint: keep dwFrameInterval
        assertEquals(0, committed[1]);
        assertEquals(1, committed[2]); // bFormatIndex
        assertEquals(1, committed[3]); // bFrameIndex
        assertEquals(333_333, le32(committed, 4));
    }

    @Test
    public void preservesCameraFieldsItDoesntSet() throws IOException {
        cameraState[8] = 0x42; // wKeyFrameRate
        startAndClose();
        assertEquals(0x42, committed[8]);
    }

    @Test
    public void fillsMissingIntervalFromMjpegFrameDescriptor() throws IOException {
        putLe32(cameraState, 4, 0);
        byte[] deviceDescriptor = new byte[18];
        deviceDescriptor[0] = 18;
        deviceDescriptor[1] = 0x01;
        when(conn.getRawDescriptors()).thenReturn(concat(deviceDescriptor,
                mjpegFrameDescriptor(2, 666_666), mjpegFrameDescriptor(1, 166_666)));
        startAndClose();
        assertEquals(166_666, le32(committed, 4));
    }

    @Test
    public void defaultsTo30FpsWithoutDescriptors() throws IOException {
        putLe32(cameraState, 4, 0);
        when(conn.getRawDescriptors()).thenReturn(null);
        startAndClose();
        assertEquals(333_333, le32(committed, 4));
    }

    @Test
    public void ignoresTruncatedFrameDescriptor() throws IOException {
        putLe32(cameraState, 4, 0);
        byte[] d = mjpegFrameDescriptor(1, 166_666);
        when(conn.getRawDescriptors()).thenReturn(Arrays.copyOf(d, 20));
        startAndClose();
        assertEquals(333_333, le32(committed, 4));
    }

    @Test
    public void failsWhenInterfaceCantBeClaimed() {
        when(conn.claimInterface(intf, true)).thenReturn(false);
        IOException e = assertThrows(IOException.class, this::startAndClose);
        assertEquals("Couldn't claim the video interface", e.getMessage());
    }

    @Test
    public void failsWhenEveryProbeIsRejected() {
        acceptedProbeSize = 99;
        IOException e = assertThrows(IOException.class, this::startAndClose);
        assertEquals("Camera rejected the video probe", e.getMessage());
        assertNull(committed);
    }

    @Test
    public void failsWhenCommitIsRejected() {
        rejectCommit = true;
        IOException e = assertThrows(IOException.class, this::startAndClose);
        assertEquals("Camera rejected the video commit", e.getMessage());
    }

    // ---- poll ----

    private int readSizeAfterStart() throws IOException {
        try (UvcStream s = stream()) {
            s.start();
            when(conn.bulkTransfer(eq(ep), any(byte[].class), anyInt(), anyInt())).thenReturn(0);
            s.poll(100);
        }
        ArgumentCaptor<Integer> len = ArgumentCaptor.forClass(Integer.class);
        verify(conn).bulkTransfer(eq(ep), any(byte[].class), len.capture(), eq(100));
        return len.getValue();
    }

    @Test
    public void readsWholePayloads() throws IOException {
        assertEquals(3072, readSizeAfterStart());
    }

    @Test
    public void readsAtLeastOnePacket() throws IOException {
        putLe32(cameraState, 22, 16);
        assertEquals(512, readSizeAfterStart());
    }

    @Test
    public void capsAbsurdPayloadSize() throws IOException {
        putLe32(cameraState, 22, 0x7FFF_FFFF);
        assertEquals(1 << 20, readSizeAfterStart());
    }

    @Test
    public void pollDeliversAssembledFrames() throws IOException {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        try (UvcStream s = stream()) {
            s.start();
            when(conn.bulkTransfer(eq(ep), any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> {
                byte[] buf = inv.getArgument(1);
                buf[0] = 2;
                buf[1] = 0x02; // EOF
                System.arraycopy(jpeg, 0, buf, 2, jpeg.length);
                return 2 + jpeg.length;
            });
            assertEquals(2 + jpeg.length, s.poll(100));
        }
        assertEquals(1, frames.size());
        assertArrayEquals(jpeg, frames.get(0));
    }

    @Test
    public void pollReturnsZeroOnTimeoutOrError() throws IOException {
        try (UvcStream s = stream()) {
            s.start();
            when(conn.bulkTransfer(eq(ep), any(byte[].class), anyInt(), anyInt())).thenReturn(-1);
            assertEquals(0, s.poll(100));
        }
        assertEquals(0, frames.size());
    }

    // ---- close ----

    @Test
    public void closeClearsHaltAndReleasesDevice() throws IOException {
        startAndClose();
        // CLEAR_FEATURE(ENDPOINT_HALT) on the streaming endpoint
        verify(conn).controlTransfer(eq(0x02), eq(0x01), eq(0), eq(EP_ADDR), isNull(), eq(0), anyInt());
        verify(conn).releaseInterface(intf);
        verify(conn).close();
    }
}
