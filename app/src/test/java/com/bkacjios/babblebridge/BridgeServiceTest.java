package com.bkacjios.babblebridge;

import static com.bkacjios.babblebridge.UvcStreamTest.device;
import static com.bkacjios.babblebridge.UvcStreamTest.endpoint;
import static com.bkacjios.babblebridge.UvcStreamTest.iface;
import static com.bkacjios.babblebridge.UvcStreamTest.videoStreaming;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import androidx.test.core.app.ApplicationProvider;

import java.util.HashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
public class BridgeServiceTest {

    private Application app;
    private ServiceController<BridgeService> controller;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        resetStatics();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.destroy();
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

    private static UsbManager usbManager(UsbDevice... devices) {
        HashMap<String, UsbDevice> list = new HashMap<>();
        for (int i = 0; i < devices.length; i++) {
            when(devices[i].getDeviceName()).thenReturn("/dev/bus/usb/001/00" + i);
            list.put("/dev/bus/usb/001/00" + i, devices[i]);
        }
        UsbManager um = mock(UsbManager.class);
        when(um.getDeviceList()).thenReturn(list);
        return um;
    }

    private static void awaitState(BridgeService.State want) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (BridgeService.state != want && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(BridgeService.status, want, BridgeService.state);
    }

    // ---- enabled preference ----

    @Test
    public void disabledByDefault() {
        assertFalse(BridgeService.isEnabled(app));
    }

    @Test
    public void enabledFlagPersists() {
        BridgeService.setEnabled(app, true);
        assertTrue(BridgeService.isEnabled(app));
        BridgeService.setEnabled(app, false);
        assertFalse(BridgeService.isEnabled(app));
    }

    // ---- findTracker ----

    @Test
    public void noDevicesMeansNoTracker() {
        assertNull(BridgeService.findTracker(usbManager()));
    }

    @Test
    public void prefersUvcCameraOverSerialDevice() {
        UsbDevice cdc = device(iface(UsbConstants.USB_CLASS_COMM, 0x02));
        UsbDevice camera = device(videoStreaming());
        assertSame(camera, BridgeService.findTracker(usbManager(cdc, camera)));
    }

    @Test
    public void fallsBackToCdcDevice() {
        UsbDevice other = device(iface(UsbConstants.USB_CLASS_HID, 0));
        UsbDevice cdc = device(iface(UsbConstants.USB_CLASS_CDC_DATA, 0,
                endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN)));
        assertSame(cdc, BridgeService.findTracker(usbManager(other, cdc)));
    }

    @Test
    public void ignoresUnrelatedDevices() {
        UsbDevice keyboard = device(iface(UsbConstants.USB_CLASS_HID, 0));
        UsbDevice storage = device(iface(UsbConstants.USB_CLASS_MASS_STORAGE, 0));
        assertNull(BridgeService.findTracker(usbManager(keyboard, storage)));
    }

    // ---- lifecycle ----

    @Test
    public void startsInForegroundAndWaitsForTracker() throws InterruptedException {
        controller = Robolectric.buildService(BridgeService.class).create();
        Notification n = shadowOf(controller.get()).getLastForegroundNotification();
        assertEquals("Babble Bridge running", shadowOf(n).getContentTitle().toString());
        assertTrue(BridgeService.running);
        awaitState(BridgeService.State.WAITING);
        assertEquals("Plug the tracker into the headset", BridgeService.status);
    }

    @Test
    public void startCommandIsSticky() {
        controller = Robolectric.buildService(BridgeService.class).create();
        assertEquals(Service.START_STICKY, controller.get().onStartCommand(null, 0, 1));
    }

    @Test
    public void destroyStopsTheBridge() throws InterruptedException {
        controller = Robolectric.buildService(BridgeService.class).create();
        awaitState(BridgeService.State.WAITING);
        BridgeService.fps = 30;
        BridgeService.clients = 2;
        controller.destroy();
        controller = null;
        assertFalse(BridgeService.running);
        assertEquals(BridgeService.State.STOPPED, BridgeService.state);
        assertEquals("Bridge is off", BridgeService.status);
        assertEquals(0, BridgeService.fps);
        assertEquals(0, BridgeService.clients);
    }

    @Test
    public void destroyKeepsErrorVisible() {
        controller = Robolectric.buildService(BridgeService.class).create();
        BridgeService.running = false; // stop the USB loop from overwriting the state
        BridgeService.state = BridgeService.State.ERROR;
        BridgeService.status = "USB error: boom";
        controller.destroy();
        controller = null;
        assertEquals(BridgeService.State.ERROR, BridgeService.state);
        assertEquals("USB error: boom", BridgeService.status);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void fakeTrackerStreamsFrames() throws InterruptedException {
        BridgeService.fakeTracker = true;
        long seq = 0;
        FrameBus.Frame before = BridgeService.bus.awaitNext(0, 0);
        if (before != null) seq = before.seq;
        controller = Robolectric.buildService(BridgeService.class).create();
        FrameBus.Frame f = BridgeService.bus.awaitNext(seq, 5000);
        assertNotNull("no fake frame published", f);
        assertEquals((byte) 0xFF, f.data[0]);
        assertEquals((byte) 0xD8, f.data[1]);
        awaitState(BridgeService.State.STREAMING);
        assertEquals("Receiving fake frames (debug)", BridgeService.status);
    }
}
