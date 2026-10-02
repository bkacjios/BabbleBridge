package com.bkacjios.babblebridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Reads the tracker over USB serial and republishes frames as MJPEG over Wi-Fi. */
public class BridgeService extends Service {

    public static final int PORT = 8080;
    /** Ignored by native-USB CDC devices; matters for CH340/CP210x bridges. */
    private static final int BAUD = 3_000_000;
    private static final String CHANNEL = "bridge";
    private static final String ACTION_USB_PERMISSION = "com.bkacjios.babblebridge.USB_PERMISSION";
    /** Reopen the port if the tracker goes silent this long (hung firmware, stale handle). Tests shorten it. */
    static volatile long stallMs = 10_000;
    private static final String PREFS = "bridge";
    private static final String TAG = "BabbleBridge";
    /** Horizon OS runtime permission for UVC devices, required on top of CAMERA. */
    static final String USB_CAMERA = "horizonos.permission.USB_CAMERA";

    enum State { STOPPED, WAITING, CONNECTED, STREAMING, ERROR }

    // Simple shared status for the activity to display.
    static volatile State state = State.STOPPED;
    static volatile boolean running;
    static volatile String status = "Stopped";
    static volatile int fps;
    static volatile int clients;
    /** Debug only: replace USB with generated frames to test the pipeline without hardware. */
    static volatile boolean fakeTracker;

    /** Static so the activity can preview frames without going through HTTP. */
    static final FrameBus bus = new FrameBus();
    private MjpegServer server;
    private Thread usbThread;
    private WifiManager.WifiLock wifiLock;
    private BroadcastReceiver usbPermissionReceiver;
    /** Which device we've already shown the permission dialog for, so we ask once per plug-in. */
    private volatile String requestedDeviceId;
    /** A permission dialog is on screen; asking again would stack a second one on top. */
    private volatile boolean permissionPending;
    private volatile boolean permissionDenied;
    /** Give the plug-in "open with" dialog (which grants access itself) a moment before asking. Tests shorten it. */
    static volatile long permissionGraceMs = 10_000;

    private int framesThisSecond;
    private long secondStart = System.currentTimeMillis();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Bridge", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Babble Bridge running")
                .setContentText("Streaming tracker on port " + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        usbPermissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                // Only record the answer; the USB loop owns the status, so a late or
                // duplicate denial can't overwrite a connection that's already up.
                permissionPending = false;
                permissionDenied = !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice d = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
                Log.i(TAG, "Permission result for " + describe(d) + ": "
                        + (permissionDenied ? "denied" : "granted"));
            }
        };
        ContextCompat.registerReceiver(this, usbPermissionReceiver,
                new IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);

        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "babblebridge");
        wifiLock.acquire();

        server = new MjpegServer(PORT, bus);
        try {
            server.start();
        } catch (IOException e) {
            state = State.ERROR;
            status = "Couldn't open port " + PORT + ": " + e.getMessage();
            stopSelf();
            return;
        }

        running = true;
        usbThread = new Thread(this::usbLoop, "usb-reader");
        usbThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Reopening the app (e.g. from the Library) should re-ask after a denial, but not
        // while a dialog is still up: plugging in relaunches the app, and re-asking then
        // would stack a second dialog that steals focus from the first.
        Log.i(TAG, "onStartCommand: pending=" + permissionPending + " denied=" + permissionDenied
                + " intent=" + intent);
        if (permissionDenied) {
            permissionDenied = false;
            requestedDeviceId = null;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (usbThread != null) usbThread.interrupt();
        if (server != null) server.stop();
        if (usbPermissionReceiver != null) unregisterReceiver(usbPermissionReceiver);
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (state != State.ERROR) {
            state = State.STOPPED;
            status = "Bridge is off";
        }
        fps = 0;
        clients = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Whether the user wants the bridge on; used to restart it after a reboot. */
    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
    }

    private void usbLoop() {
        UsbManager um = getSystemService(UsbManager.class);
        long deviceSeenAt = 0;

        while (running) {
            TrackerSource source = null;
            try {
                if (fakeTracker) {
                    fakeLoop();
                    setIdle(State.WAITING, "Fake tracker off");
                    continue;
                }
                UsbDevice dev = findTracker(um);
                if (dev == null) {
                    if (deviceSeenAt != 0) Log.i(TAG, "Tracker gone from device list");
                    // Android drops USB permission on detach, so ask again once per plug-in.
                    requestedDeviceId = null;
                    permissionPending = false;
                    permissionDenied = false;
                    deviceSeenAt = 0;
                    setIdle(State.WAITING, "Plug the tracker into the headset");
                    Thread.sleep(1000);
                    continue;
                }
                UsbInterface video = UvcStream.findStreamingInterface(dev);
                String kind = video != null ? "UVC camera" : "serial";
                if (!um.hasPermission(dev)) {
                    long now = System.currentTimeMillis();
                    if (deviceSeenAt == 0) {
                        deviceSeenAt = now;
                        Log.i(TAG, "Found " + describe(dev) + " (" + kind + ") without permission");
                    }
                    String id = String.valueOf(dev.getDeviceId());
                    if (needsCamera(dev)) {
                        // Android would deny the USB request instantly without showing a dialog
                        setIdle(State.ERROR, "Allow camera and USB camera access so the app can use the tracker");
                    } else if (permissionDenied) {
                        setIdle(State.ERROR, "USB access denied. Replug the tracker or reopen the app to ask again.");
                    } else if (!permissionPending && !id.equals(requestedDeviceId)
                            && now - deviceSeenAt >= permissionGraceMs) {
                        requestedDeviceId = id;
                        permissionPending = true;
                        setIdle(State.WAITING, "Allow USB access in the headset dialog");
                        Log.i(TAG, "Requesting permission for " + describe(dev));
                        requestPermission(um, dev);
                    }
                    Thread.sleep(500);
                    continue;
                }
                permissionPending = false;
                permissionDenied = false;
                Log.i(TAG, "Opening " + describe(dev) + " as " + kind);
                UsbDeviceConnection conn = um.openDevice(dev);
                if (conn == null) {
                    Log.w(TAG, "openDevice returned null");
                    setIdle(State.ERROR, "Couldn't open the USB device");
                    Thread.sleep(1000);
                    continue;
                }
                source = video != null ? openUvc(conn, video) : openSerial(dev, conn);
                Log.i(TAG, "Tracker open, waiting for data");

                state = State.CONNECTED;
                status = "Connected, waiting for frames";
                long lastData = System.currentTimeMillis();
                long lastCheck = lastData;
                boolean gotData = false;
                while (running && !fakeTracker) {
                    int n = source.poll(200);
                    long now = System.currentTimeMillis();
                    if (n > 0) {
                        if (!gotData) Log.i(TAG, "First data: " + n + " bytes");
                        gotData = true;
                        lastData = now;
                    }
                    if (now - lastCheck >= 1000) {
                        lastCheck = now;
                        // read() can time out quietly on a dead handle, so check explicitly.
                        if (!um.getDeviceList().containsKey(dev.getDeviceName())) {
                            throw new TrackerLost("Tracker disconnected");
                        }
                        if (now - lastData >= stallMs) {
                            throw new TrackerLost("Tracker stopped sending, reconnecting");
                        }
                    }
                    tickStats();
                }
            } catch (InterruptedException e) {
                break;
            } catch (TrackerLost e) {
                Log.w(TAG, e.getMessage());
                setIdle(State.WAITING, e.getMessage());
            } catch (IOException | RuntimeException e) {
                // Includes SecurityException when Android revokes USB access mid-read.
                Log.e(TAG, "USB error", e);
                setIdle(State.ERROR, "USB error: " + e);
            } finally {
                if (source != null) {
                    try { source.close(); } catch (IOException ignored) { }
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Feeds generated packets through the real parser at about 30 fps. */
    private void fakeLoop() throws InterruptedException {
        state = State.CONNECTED;
        status = "Fake tracker on, waiting for frames";
        FrameParser parser = new FrameParser(this::onFrame);
        FakeTracker fake = new FakeTracker();
        Random rnd = new Random();
        while (running && fakeTracker) {
            byte[] packet = fake.nextPacket();
            // Split at random points, like USB reads, to exercise the parser's buffering
            for (int off = 0; off < packet.length; ) {
                int n = Math.min(packet.length - off, 1 + rnd.nextInt(4096));
                parser.feed(packet, off, n);
                off += n;
            }
            tickStats();
            Thread.sleep(33);
        }
    }

    private void onFrame(byte[] jpeg) {
        bus.publish(jpeg);
        framesThisSecond++;
    }

    private void tickStats() {
        long now = System.currentTimeMillis();
        if (now - secondStart >= 1000) {
            fps = framesThisSecond;
            framesThisSecond = 0;
            secondStart = now;
            clients = server.clientCount();
            if (fps > 0) {
                state = State.STREAMING;
                status = fakeTracker ? "Receiving fake frames (debug)" : "Receiving frames from the tracker";
            } else if (state == State.STREAMING) {
                state = State.CONNECTED;
                status = "Connected, waiting for frames";
            }
        }
    }

    private void requestPermission(UsbManager um, UsbDevice dev) {
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        um.requestPermission(dev, pi);
    }

    /** Debug view of raw serial data: text as-is, everything else as \xNN. */
    private static String printable(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, 512); i++) {
            int c = b[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(String.format(Locale.ROOT, "\\x%02x", c));
        }
        if (n > 512) sb.append("...");
        return sb.toString();
    }

    private static String describe(UsbDevice d) {
        if (d == null) return "(no device)";
        return String.format(Locale.ROOT, "%s id=%d vid=%04x pid=%04x", d.getDeviceName(), d.getDeviceId(),
                d.getVendorId(), d.getProductId());
    }

    private void setIdle(State st, String s) {
        state = st;
        status = s;
        fps = 0;
        clients = server.clientCount();
    }

    private TrackerSource openUvc(UsbDeviceConnection conn, UsbInterface video) throws IOException {
        UvcStream stream = new UvcStream(conn, video, this::onFrame);
        try {
            stream.start();
        } catch (IOException | RuntimeException e) {
            stream.close();
            throw e;
        }
        return stream;
    }

    private TrackerSource openSerial(UsbDevice dev, UsbDeviceConnection conn) throws IOException {
        UsbSerialPort port = serialDriverFor(dev).getPorts().get(0);
        port.open(conn);
        try {
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (UnsupportedOperationException | IOException e) {
            Log.w(TAG, "setParameters failed", e);
        }
        try {
            // Same line state pyserial uses by default (what the Babble App does).
            port.setDTR(true);
            port.setRTS(true);
        } catch (UnsupportedOperationException | IOException e) {
            Log.w(TAG, "Setting DTR/RTS failed", e);
        }
        FrameParser parser = new FrameParser(this::onFrame);
        byte[] buf = new byte[16 * 1024];
        return new TrackerSource() {
            private int dumped;

            @Override
            public int poll(int timeoutMs) throws IOException {
                int n = port.read(buf, timeoutMs);
                if (n > 0) {
                    if (dumped < 4096) {
                        Log.d(TAG, "rx " + n + ": " + printable(buf, n));
                        dumped += n;
                    }
                    parser.feed(buf, 0, n);
                }
                return n;
            }

            @Override
            public void close() throws IOException {
                port.close();
            }
        };
    }

    /** A bulk-streaming UVC camera if one is attached (it sends JPEGs directly), else a serial tracker. */
    static UsbDevice findTracker(UsbManager um) {
        for (UsbDevice dev : um.getDeviceList().values()) {
            if (UvcStream.findStreamingInterface(dev) != null) return dev;
        }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um);
        if (!drivers.isEmpty()) return drivers.get(0).getDevice();
        // Fallback for native-USB CDC devices the default prober doesn't list.
        for (UsbDevice dev : um.getDeviceList().values()) {
            if (hasCdcInterface(dev)) return dev;
        }
        return null;
    }

    private static UsbSerialDriver serialDriverFor(UsbDevice dev) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        return driver != null ? driver : new CdcAcmSerialDriver(dev);
    }

    /**
     * Android refuses USB access to video-class devices unless the app holds CAMERA, and
     * Horizon OS also requires USB_CAMERA. Without both the request is denied with no dialog.
     */
    private boolean needsCamera(UsbDevice dev) {
        if (hasPermission(Manifest.permission.CAMERA) && hasPermission(USB_CAMERA)) return false;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            if (dev.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    /** True if granted, or if this OS doesn't define the permission (USB_CAMERA off Horizon OS). */
    private boolean hasPermission(String perm) {
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return true;
        try {
            getPackageManager().getPermissionInfo(perm, 0);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private static boolean hasCdcInterface(UsbDevice dev) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            int c = intf.getInterfaceClass();
            if (c == UsbConstants.USB_CLASS_COMM || c == UsbConstants.USB_CLASS_CDC_DATA) return true;
        }
        return false;
    }

    /** Raised by the read loop's own checks; the message is shown as the status. */
    private static class TrackerLost extends IOException {
        TrackerLost(String msg) { super(msg); }
    }
}
