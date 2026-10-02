package com.bkacjios.babblebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {

    private Application app;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        resetStatics();
    }

    @After
    public void tearDown() {
        // Pausing stops the preview thread
        if (controller != null) controller.pause().stop().destroy();
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

    private void launch(boolean debuggable) {
        ApplicationInfo info = app.getApplicationInfo();
        if (debuggable) info.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        else info.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        refresh();
    }

    /** Lets the half-second refresh run once more. */
    private static void refresh() {
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private List<TextView> textViews() {
        List<TextView> out = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), out);
        return out;
    }

    private static void collect(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private TextView find(String text) {
        for (TextView t : textViews()) if (text.contentEquals(t.getText())) return t;
        return null;
    }

    private TextView findStartingWith(String prefix) {
        for (TextView t : textViews()) if (t.getText().toString().startsWith(prefix)) return t;
        return null;
    }

    private TextView valueBelow(String label) {
        TextView l = find(label);
        assertNotNull(label, l);
        ViewGroup col = (ViewGroup) l.getParent();
        return (TextView) col.getChildAt(col.indexOfChild(l) + 1);
    }

    private ImageView preview() {
        List<ImageView> images = new ArrayList<>();
        collectImages(activity.getWindow().getDecorView(), images);
        assertEquals(1, images.size());
        return images.get(0);
    }

    private static void collectImages(View v, List<ImageView> out) {
        if (v instanceof ImageView) out.add((ImageView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectImages(g.getChildAt(i), out);
        }
    }

    private Intent nextStartedService() {
        return shadowOf(app).getNextStartedService();
    }

    // ---- startup ----

    @Test
    public void startsAndEnablesBridgeOnLaunch() {
        launch(false);
        assertTrue(BridgeService.isEnabled(app));
        Intent started = nextStartedService();
        assertNotNull(started);
        assertEquals(BridgeService.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void requestsCameraPermissions() {
        launch(false);
        ShadowActivity.PermissionsRequest req = shadowOf(activity).getLastRequestedPermission();
        List<String> perms = Arrays.asList(req.requestedPermissions);
        assertTrue(perms.contains(Manifest.permission.CAMERA));
        assertTrue(perms.contains(BridgeService.USB_CAMERA));
        assertTrue(perms.contains(Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test
    public void newIntentStartsBridgeAgain() {
        launch(false);
        while (nextStartedService() != null) { }
        controller.newIntent(new Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"));
        assertNotNull(nextStartedService());
    }

    // ---- status rendering ----

    @Test
    public void showsStoppedState() {
        launch(false);
        assertNotNull(find("Stopped"));
        assertNotNull(find("Start bridge"));
        assertEquals("0", valueBelow("TRACKER FPS").getText().toString());
        assertEquals("0", valueBelow("CLIENTS").getText().toString());
    }

    @Test
    public void showsStreamingStats() {
        launch(false);
        BridgeService.running = true;
        BridgeService.state = BridgeService.State.STREAMING;
        BridgeService.status = "Receiving frames from the tracker";
        BridgeService.fps = 24;
        BridgeService.clients = 3;
        refresh();
        assertNotNull(find("Streaming"));
        assertNotNull(find("Receiving frames from the tracker"));
        assertEquals("24", valueBelow("TRACKER FPS").getText().toString());
        assertEquals("3", valueBelow("CLIENTS").getText().toString());
        assertNotNull(find("Stop bridge"));
    }

    @Test
    public void mapsEveryStateToALabel() {
        launch(false);
        String[][] cases = {
                {"WAITING", "Waiting for tracker"},
                {"CONNECTED", "Connected"},
                {"ERROR", "Problem"},
                {"STOPPED", "Stopped"},
        };
        for (String[] c : cases) {
            BridgeService.state = BridgeService.State.valueOf(c[0]);
            refresh();
            assertNotNull(c[0], find(c[1]));
        }
    }

    @Test
    public void showsStreamAddressOrNoWifi() {
        launch(false);
        TextView url = findStartingWith("No Wi-Fi");
        if (url == null) {
            boolean found = false;
            for (TextView t : textViews()) {
                if (t.getText().toString().matches("\\d+\\.\\d+\\.\\d+\\.\\d+:" + BridgeService.PORT)) found = true;
            }
            assertTrue("no address shown", found);
        }
    }

    @Test
    public void previewOnlyVisibleWhileStreaming() {
        launch(false);
        assertEquals(View.INVISIBLE, preview().getVisibility());
        assertEquals(View.VISIBLE, find("No video").getVisibility());

        BridgeService.state = BridgeService.State.STREAMING;
        refresh();
        assertEquals(View.VISIBLE, preview().getVisibility());
        assertEquals(View.GONE, find("No video").getVisibility());
    }

    @Test
    public void previewShowsPublishedFrame() throws InterruptedException {
        launch(false);
        BridgeService.state = BridgeService.State.STREAMING;
        Bitmap src = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
        src.eraseColor(Color.RED);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        src.compress(Bitmap.CompressFormat.JPEG, 90, jpeg);
        BridgeService.bus.publish(jpeg.toByteArray());

        // The bus is static, so an older frame from another test may show first
        long deadline = System.currentTimeMillis() + 5000;
        while (previewWidth() != 32 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(32, previewWidth());
        assertEquals(16, ((BitmapDrawable) preview().getDrawable()).getBitmap().getHeight());
    }

    private int previewWidth() {
        BitmapDrawable d = (BitmapDrawable) preview().getDrawable();
        return d == null ? -1 : d.getBitmap().getWidth();
    }

    // ---- buttons ----

    @Test
    public void stopButtonDisablesAndStopsBridge() {
        launch(false);
        BridgeService.running = true;
        refresh();
        find("Stop bridge").performClick();
        assertFalse(BridgeService.isEnabled(app));
        Intent stopped = shadowOf(app).getNextStoppedService();
        assertEquals(BridgeService.class.getName(), stopped.getComponent().getClassName());
    }

    @Test
    public void startButtonStartsBridge() {
        launch(false);
        BridgeService.setEnabled(app, false);
        while (nextStartedService() != null) { }
        find("Start bridge").performClick();
        assertTrue(BridgeService.isEnabled(app));
        assertNotNull(nextStartedService());
    }

    @Test
    public void fakeTrackerButtonHiddenInReleaseBuilds() {
        launch(false);
        assertNull(find("Start fake tracker"));
    }

    @Test
    public void fakeTrackerButtonTogglesFakeTracker() {
        launch(true);
        Button b = (Button) find("Start fake tracker");
        assertNotNull(b);
        while (nextStartedService() != null) { }

        b.performClick();
        assertTrue(BridgeService.fakeTracker);
        assertEquals("Stop fake tracker", b.getText().toString());
        assertNotNull("bridge should start for the fake tracker", nextStartedService());

        b.performClick();
        assertFalse(BridgeService.fakeTracker);
        assertEquals("Start fake tracker", b.getText().toString());
    }

    @Test
    public void fakeTrackerDoesntRestartRunningBridge() {
        launch(true);
        BridgeService.running = true;
        while (nextStartedService() != null) { }
        find("Start fake tracker").performClick();
        assertTrue(BridgeService.fakeTracker);
        assertNull(nextStartedService());
    }

    // ---- platform and layout variants ----

    @Test
    @Config(sdk = 32)
    public void olderAndroidDoesntAskForNotifications() {
        launch(false);
        List<String> perms = Arrays.asList(shadowOf(activity).getLastRequestedPermission().requestedPermissions);
        assertTrue(perms.contains(Manifest.permission.CAMERA));
        assertTrue(perms.contains(BridgeService.USB_CAMERA));
        assertFalse(perms.contains(Manifest.permission.POST_NOTIFICATIONS));
    }

    /** The card holding the preview box and the status column. */
    private LinearLayout card() {
        View previewBox = (View) preview().getParent();
        return (LinearLayout) previewBox.getParent();
    }

    @Test
    public void narrowWindowStacksPreviewAboveStatus() {
        launch(false);
        assertEquals(LinearLayout.VERTICAL, card().getOrientation());
    }

    @Test
    @Config(qualifiers = "w800dp-h600dp")
    public void wideWindowPutsPreviewBesideStatus() {
        launch(false);
        assertEquals(LinearLayout.HORIZONTAL, card().getOrientation());
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) ((View) preview().getParent()).getLayoutParams();
        assertEquals(0, lp.width);
        assertEquals(1f, lp.weight, 0f);
    }

    // ---- address ----

    private static NetworkInterface nic(boolean up, boolean loopback, InetAddress... addrs) throws SocketException {
        NetworkInterface ni = mock(NetworkInterface.class);
        when(ni.isUp()).thenReturn(up);
        when(ni.isLoopback()).thenReturn(loopback);
        when(ni.getInetAddresses()).thenAnswer(inv -> Collections.enumeration(Arrays.asList(addrs)));
        return ni;
    }

    @Test
    public void showsFirstIpv4AddressOfAnActiveInterface() throws Exception {
        NetworkInterface down = nic(false, false, InetAddress.getByAddress(new byte[]{10, 9, 9, 9}));
        NetworkInterface lo = nic(true, true, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        NetworkInterface wifi = nic(true, false,
                InetAddress.getByAddress(new byte[]{(byte) 0xFE, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}),
                InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 42}));
        try (MockedStatic<NetworkInterface> nis = mockStatic(NetworkInterface.class)) {
            nis.when(NetworkInterface::getNetworkInterfaces)
                    .thenAnswer(inv -> Collections.enumeration(Arrays.asList(down, lo, wifi)));
            launch(false);
            assertNotNull(find("192.168.1.42:" + BridgeService.PORT));
        }
    }

    @Test
    public void showsNoWifiWhenOnlyLoopbackIsUp() throws Exception {
        NetworkInterface lo = nic(true, true, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        try (MockedStatic<NetworkInterface> nis = mockStatic(NetworkInterface.class)) {
            nis.when(NetworkInterface::getNetworkInterfaces)
                    .thenAnswer(inv -> Collections.enumeration(List.of(lo)));
            launch(false);
            assertNotNull(find("No Wi-Fi connection"));
        }
    }

    @Test
    public void showsNoWifiWhenInterfacesCantBeRead() {
        try (MockedStatic<NetworkInterface> nis = mockStatic(NetworkInterface.class)) {
            nis.when(NetworkInterface::getNetworkInterfaces).thenThrow(new SocketException("nope"));
            launch(false);
            assertNotNull(find("No Wi-Fi connection"));
        }
    }
}
