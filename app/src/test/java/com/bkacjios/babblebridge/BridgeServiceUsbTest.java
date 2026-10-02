package com.bkacjios.babblebridge;

import static com.bkacjios.babblebridge.UvcStreamTest.device;
import static com.bkacjios.babblebridge.UvcStreamTest.endpoint;
import static com.bkacjios.babblebridge.UvcStreamTest.iface;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PermissionInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowUsbRequest;

/** Drives the service's USB loop against mocked serial and UVC trackers. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = BridgeServiceUsbTest.LenientUsbRequest.class)
public class BridgeServiceUsbTest {

    private static final String ACTION_USB_PERMISSION = "com.bkacjios.babblebridge.USB_PERMISSION";
    private static final String NAME = "/dev/bus/usb/001/002";

    /** Swaps in a mocked UsbManager; everything else is the real service. */
    public static class TestBridgeService extends BridgeService {
        static volatile UsbManager usb;

        @Override
        public Object getSystemService(String name) {
            if (Context.USB_SERVICE.equals(name) && usb != null) return usb;
            return super.getSystemService(name);
        }
    }

    /** The stock shadow only works with Robolectric's own connections, not mocked ones. */
    @Implements(UsbRequest.class)
    public static class LenientUsbRequest extends ShadowUsbRequest {
        @Implementation
        @Override
        protected boolean initialize(UsbDeviceConnection connection, UsbEndpoint endpoint) {
            return true;
        }

        @Implementation
        @Override
        protected void close() {
        }
    }

    private Application app;
    private ServiceController<TestBridgeService> controller;
    private UsbManager um;
    private final Map<String, UsbDevice> attached = new ConcurrentHashMap<>();
    private volatile boolean permitted = true;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        resetStatics();
        BridgeService.stallMs = 1500;
        BridgeService.permissionGraceMs = 0;
        um = mock(UsbManager.class);
        when(um.getDeviceList()).thenAnswer(inv -> new HashMap<>(attached));
        when(um.hasPermission(any(UsbDevice.class))).thenAnswer(inv -> permitted);
        TestBridgeService.usb = um;
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
        TestBridgeService.usb = null;
        BridgeService.stallMs = 10_000;
        BridgeService.permissionGraceMs = 10_000;
        resetStatics();
    }

    private static void resetStatics() {
        BridgeService.state = BridgeService.State.STOPPED;
        BridgeService.status = "Stopped";
        BridgeService.running = false;
        BridgeService.fps = 0;
        BridgeService.clients = 0;
        BridgeService.fakeTracker = false;
    }

    private void start() {
        controller = Robolectric.buildService(TestBridgeService.class).create();
    }

    private void attach(UsbDevice dev) {
        when(dev.getDeviceName()).thenReturn(NAME);
        when(dev.getDeviceId()).thenReturn(1002);
        when(dev.getVendorId()).thenReturn(0x303A);
        when(dev.getProductId()).thenReturn(0x1001);
        attached.put(NAME, dev);
    }

    private static void awaitStatus(BridgeService.State state, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (!(BridgeService.state == state && status.equals(BridgeService.status))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(state, BridgeService.state);
        assertEquals(status, BridgeService.status);
    }

    private static void awaitStatusPrefix(BridgeService.State state, String prefix) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (!(BridgeService.state == state && BridgeService.status.startsWith(prefix))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(state, BridgeService.state);
        assertTrue(BridgeService.status, BridgeService.status.startsWith(prefix));
    }

    // ---- fake devices ----

    /** A CDC-ACM serial tracker: control interface with an interrupt IN, data interface with bulk IN/OUT. */
    private static UsbDevice serialDevice() {
        UsbInterface control = iface(UsbConstants.USB_CLASS_COMM, 0x02,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_IN));
        UsbInterface data = iface(UsbConstants.USB_CLASS_CDC_DATA, 0,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN),
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_OUT));
        when(control.getId()).thenReturn(0);
        when(data.getId()).thenReturn(1);
        return device(control, data);
    }

    private static UsbDevice uvcDevice() {
        return device(iface(UsbConstants.USB_CLASS_VIDEO, 0x02,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN)));
    }

    private static UsbDeviceConnection connection() {
        UsbDeviceConnection conn = mock(UsbDeviceConnection.class);
        when(conn.claimInterface(any(UsbInterface.class), anyBoolean())).thenReturn(true);
        return conn;
    }

    /** Boot chatter, then one serial packet holding a 600-byte JPEG-shaped payload. */
    private static byte[] serialChunk() {
        byte[] jpeg = new byte[600];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("boot ok\r\n\u0001".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(FrameParser.HEADER);
        out.write(jpeg.length & 0xFF);
        out.write(jpeg.length >> 8);
        out.writeBytes(jpeg);
        return out.toByteArray();
    }

    private volatile boolean sending = true;

    /** Answers reads like a tracker streaming at ~50 fps until {@link #sending} goes false. */
    private void streamSerial(UsbDeviceConnection conn) {
        byte[] chunk = serialChunk();
        when(conn.bulkTransfer(any(UsbEndpoint.class), any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(20);
            if (!sending) return -1;
            byte[] dest = inv.getArgument(1);
            System.arraycopy(chunk, 0, dest, 0, chunk.length);
            return chunk.length;
        });
    }

    // ---- no device / server ----

    @Test
    public void waitsWhenNoTrackerIsAttached() throws InterruptedException {
        start();
        awaitStatus(BridgeService.State.WAITING, "Plug the tracker into the headset");
        // Keeps polling: each pass lists devices three times, so a fourth call means a second pass
        verify(um, timeout(3000).atLeast(4)).getDeviceList();
    }

    @Test
    public void doesNotAllowBinding() {
        start();
        assertNull(controller.get().onBind(new Intent()));
    }

    @Test
    public void reportsBusyPort() throws IOException {
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress(BridgeService.PORT));
            start();
            assertEquals(BridgeService.State.ERROR, BridgeService.state);
            assertTrue(BridgeService.status, BridgeService.status.startsWith("Couldn't open port 8080"));
            assertFalse(BridgeService.running);
            assertTrue(shadowOf(controller.get()).isStoppedBySelf());
        }
    }

    // ---- permission ----

    @Test
    public void asksForPermissionOnceAndReportsDenial() throws InterruptedException {
        permitted = false;
        UsbDevice dev = serialDevice();
        attach(dev);
        start();
        awaitStatus(BridgeService.State.WAITING, "Allow USB access in the headset dialog");
        verify(um, timeout(2000)).requestPermission(eq(dev), any(PendingIntent.class));
        // Still pending, so it mustn't stack a second dialog
        verify(um, after(1200).times(1)).requestPermission(eq(dev), any(PendingIntent.class));

        app.sendBroadcast(new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName())
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                .putExtra(UsbManager.EXTRA_DEVICE, dev));
        shadowOf(Looper.getMainLooper()).idle();
        awaitStatus(BridgeService.State.ERROR,
                "USB access denied. Replug the tracker or reopen the app to ask again.");

        // Reopening the app asks again
        controller.startCommand(0, 2);
        verify(um, timeout(3000).times(2)).requestPermission(eq(dev), any(PendingIntent.class));
    }

    @Test
    public void grantedPermissionOpensTracker() throws InterruptedException {
        permitted = false;
        UsbDevice dev = serialDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        streamSerial(conn);
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        verify(um, timeout(3000)).requestPermission(eq(dev), any(PendingIntent.class));

        // A result without a device extra still counts
        app.sendBroadcast(new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName())
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true));
        shadowOf(Looper.getMainLooper()).idle();
        permitted = true;
        awaitStatus(BridgeService.State.STREAMING, "Receiving frames from the tracker");
    }

    @Test
    public void waitsForGracePeriodBeforeAsking() {
        BridgeService.permissionGraceMs = 60_000;
        permitted = false;
        attach(serialDevice());
        start();
        verify(um, after(1500).never()).requestPermission(any(UsbDevice.class), any(PendingIntent.class));
    }

    @Test
    public void uvcTrackerNeedsCameraPermissions() throws InterruptedException {
        PermissionInfo camera = new PermissionInfo();
        camera.name = Manifest.permission.CAMERA;
        shadowOf(app.getPackageManager()).addPermissionInfo(camera);
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        permitted = false;
        attach(uvcDevice());
        start();
        awaitStatus(BridgeService.State.ERROR, "Allow camera and USB camera access so the app can use the tracker");
        verify(um, never()).requestPermission(any(UsbDevice.class), any(PendingIntent.class));
    }

    @Test
    public void uvcTrackerAsksForUsbOnceCameraIsGranted() {
        // USB_CAMERA isn't defined off Horizon OS, so CAMERA alone is enough here
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        permitted = false;
        UsbDevice dev = uvcDevice();
        attach(dev);
        start();
        verify(um, timeout(3000)).requestPermission(eq(dev), any(PendingIntent.class));
    }

    @Test
    public void serialTrackerDoesntNeedCamera() {
        PermissionInfo camera = new PermissionInfo();
        camera.name = Manifest.permission.CAMERA;
        shadowOf(app.getPackageManager()).addPermissionInfo(camera);
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        permitted = false;
        UsbDevice dev = serialDevice();
        attach(dev);
        start();
        verify(um, timeout(3000)).requestPermission(eq(dev), any(PendingIntent.class));
    }

    // ---- opening ----

    @Test
    public void reportsDeviceThatWontOpen() throws InterruptedException {
        UsbDevice dev = serialDevice();
        attach(dev);
        start();
        awaitStatus(BridgeService.State.ERROR, "Couldn't open the USB device");
        // Then tries again
        verify(um, timeout(3000).times(2)).openDevice(dev);
    }

    // ---- serial ----

    @Test
    public void streamsFromSerialTracker() throws InterruptedException {
        UsbDevice dev = serialDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        streamSerial(conn);
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.STREAMING, "Receiving frames from the tracker");
        assertTrue(BridgeService.fps > 0);
        // Line coding plus DTR and RTS
        verify(conn, atLeastOnce()).controlTransfer(eq(0x21), eq(0x20), anyInt(), anyInt(), any(), anyInt(), anyInt());
        verify(conn, atLeastOnce()).controlTransfer(eq(0x21), eq(0x22), anyInt(), anyInt(), any(), anyInt(), anyInt());
    }

    @Test
    public void silentTrackerFallsBackThenReconnects() throws InterruptedException {
        UsbDevice dev = serialDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        streamSerial(conn);
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.STREAMING, "Receiving frames from the tracker");

        sending = false;
        awaitStatus(BridgeService.State.CONNECTED, "Connected, waiting for frames");
        awaitStatus(BridgeService.State.WAITING, "Tracker stopped sending, reconnecting");
        verify(conn, timeout(3000).atLeastOnce()).close();
        // Then it reopens the port
        verify(um, timeout(3000).times(2)).openDevice(dev);
    }

    @Test
    public void detectsUnplug() throws InterruptedException {
        UsbDevice dev = serialDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        streamSerial(conn);
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.STREAMING, "Receiving frames from the tracker");

        attached.clear();
        awaitStatus(BridgeService.State.WAITING, "Tracker disconnected");
        awaitStatus(BridgeService.State.WAITING, "Plug the tracker into the headset");
    }

    @Test
    public void reportsUsbErrors() throws InterruptedException {
        UsbDevice dev = serialDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        when(conn.bulkTransfer(any(UsbEndpoint.class), any(byte[].class), anyInt(), anyInt()))
                .thenThrow(new SecurityException("revoked"));
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.ERROR, "USB error: java.lang.SecurityException: revoked");
        verify(conn, timeout(2000).atLeastOnce()).close();
    }

    @Test
    public void keepsGoingWhenLineSettingsFail() throws InterruptedException {
        UsbDevice dev = serialDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        streamSerial(conn);
        // Every ACM control request fails, which some bridges do
        when(conn.controlTransfer(eq(0x21), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(-1);
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.STREAMING, "Receiving frames from the tracker");
    }

    // ---- UVC ----

    @Test
    public void streamsFromUvcTracker() throws InterruptedException {
        UsbDevice dev = uvcDevice();
        attach(dev);
        UsbDeviceConnection conn = connection();
        // Accept whatever probe and commit the stream sends
        when(conn.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(5));
        byte[] payload = {2, 0x02, (byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, (byte) 0xD9};
        when(conn.bulkTransfer(any(UsbEndpoint.class), any(byte[].class), anyInt(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(20);
            byte[] buf = inv.getArgument(1);
            System.arraycopy(payload, 0, buf, 0, payload.length);
            return payload.length;
        });
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.STREAMING, "Receiving frames from the tracker");
    }

    @Test
    public void uvcStartFailureClosesTheDevice() throws InterruptedException {
        UsbDevice dev = uvcDevice();
        attach(dev);
        UsbDeviceConnection conn = mock(UsbDeviceConnection.class); // refuses to claim
        when(um.openDevice(dev)).thenReturn(conn);
        start();
        awaitStatus(BridgeService.State.ERROR, "USB error: java.io.IOException: Couldn't claim the video interface");
        verify(conn, atLeastOnce()).close();
    }

    // ---- fake tracker ----

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void turningFakeTrackerOffReturnsToUsb() throws InterruptedException {
        BridgeService.fakeTracker = true;
        start();
        awaitStatusPrefix(BridgeService.State.CONNECTED, "Fake tracker on");
        BridgeService.fakeTracker = false;
        awaitStatus(BridgeService.State.WAITING, "Plug the tracker into the headset");
    }
}
