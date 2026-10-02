package com.bkacjios.babblebridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    // Dark palette that sits comfortably next to Horizon OS panels.
    private static final int BG = 0xFF101114;
    private static final int CARD = 0xFF1C1E23;
    private static final int TEXT = 0xFFECEEF2;
    private static final int MUTED = 0xFF9AA0AA;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFF5B83D;
    private static final int RED = 0xFFFF5A5F;
    private static final int GREY = 0xFF6B7078;
    private static final int BLUE = 0xFF4C8DFF;

    /** Window width from which the preview sits beside the status instead of above it. */
    private static final int WIDE_DP = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private GradientDrawable dot;
    private TextView stateLabel, detail, url, fpsValue, clientsValue;
    private Button toggle;
    private GradientDrawable toggleShape;
    private Button fakeToggle;
    private GradientDrawable fakeShape;
    private ImageView preview;
    private TextView previewHint;
    private Thread previewThread;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        // Camera is needed because the tracker has a UVC interface (see BridgeService.needsCamera)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.CAMERA, BridgeService.USB_CAMERA}, 0);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA, BridgeService.USB_CAMERA}, 0);
        }
        startBridge();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        startBridge(); // tracker re-plugged
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
        previewThread = new Thread(this::previewLoop, "preview");
        previewThread.start();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        previewThread.interrupt();
        previewThread = null;
        super.onPause();
    }

    /** Decodes the latest frame off the UI thread while the panel is visible. */
    private void previewLoop() {
        long seq = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                FrameBus.Frame f = BridgeService.bus.awaitNext(seq, 1000);
                if (f == null) continue;
                seq = f.seq;
                Bitmap bmp = BitmapFactory.decodeByteArray(f.data, 0, f.data.length);
                if (bmp != null) handler.post(() -> preview.setImageBitmap(bmp));
            }
        } catch (InterruptedException ignored) { }
    }

    // ---- UI ----

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView title = text("Babble Bridge", 30, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);

        // Wide windows (the Quest panel, phones in landscape) put the preview beside the
        // status; narrow ones stack it on top. Resizing recreates the activity, so this re-runs.
        boolean wide = getResources().getConfiguration().screenWidthDp >= WIDE_DP;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(24), dp(28), dp(24));
        card.setBackground(rounded(CARD, 24));
        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Live preview, letterboxed to keep the tracker's aspect ratio
        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackground(rounded(BG, 16));
        previewBox.setClipToOutline(true);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        previewHint = text("No video", 16, MUTED);
        previewBox.addView(previewHint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        LinearLayout.LayoutParams previewLp;
        if (wide) {
            // Fills the card's height, which the status column sets
            previewLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            previewLp.setMarginEnd(dp(28));
            previewBox.setMinimumHeight(dp(220));
        } else {
            previewLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
            previewLp.bottomMargin = dp(22);
        }
        card.addView(previewBox, previewLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(info, wide
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Status row: colored dot + state name
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        View dotView = new View(this);
        dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dotView.setBackground(dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        dotLp.setMarginEnd(dp(14));
        statusRow.addView(dotView, dotLp);
        stateLabel = text("", 24, TEXT);
        stateLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusRow.addView(stateLabel);
        info.addView(statusRow);

        detail = text("", 18, MUTED);
        detail.setPadding(0, dp(6), 0, dp(22));
        info.addView(detail);

        info.addView(label("BABBLE APP ADDRESS"));
        url = text("", 28, TEXT);
        url.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        url.setPadding(0, dp(4), 0, dp(22));
        info.addView(url);

        LinearLayout stats = new LinearLayout(this);
        fpsValue = text("0", 30, TEXT);
        clientsValue = text("0", 30, TEXT);
        stats.addView(stat("TRACKER FPS", fpsValue), weight());
        stats.addView(stat("CLIENTS", clientsValue), weight());
        info.addView(stats);

        toggleShape = rounded(BLUE, 36);
        toggle = pillButton(toggleShape);
        toggle.setOnClickListener(v -> {
            if (BridgeService.running) {
                BridgeService.setEnabled(this, false);
                stopService(new Intent(this, BridgeService.class));
            } else {
                startBridge();
            }
            render();
        });
        root.addView(toggle, pillLp());

        // Debug builds only: stand-in tracker for testing without hardware
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            fakeShape = rounded(BLUE, 36);
            fakeToggle = pillButton(fakeShape);
            fakeToggle.setOnClickListener(v -> {
                // Only the UI thread writes this flag; the service just reads it.
                boolean fake = !BridgeService.fakeTracker;
                BridgeService.fakeTracker = fake;
                if (fake && !BridgeService.running) startBridge();
                render();
            });
            root.addView(fakeToggle, pillLp());
        }

        // Scrolls on short screens (phones, small panels); stays centered when it fits.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        // Android 15+ draws apps edge to edge; keep content clear of the status/nav bars and cutouts.
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
            scroll.setClipToPadding(false);
        }
        return scroll;
    }

    private void render() {
        int color;
        String name = switch (BridgeService.state) {
            case STREAMING -> {
                color = GREEN;
                yield "Streaming";
            }
            case CONNECTED -> {
                color = AMBER;
                yield "Connected";
            }
            case WAITING -> {
                color = AMBER;
                yield "Waiting for tracker";
            }
            case ERROR -> {
                color = RED;
                yield "Problem";
            }
            default -> {
                color = GREY;
                yield "Stopped";
            }
        };
        dot.setColor(color);
        stateLabel.setText(name);
        detail.setText(BridgeService.status);

        String ip = wifiIp();
        url.setText(ip == null ? "No Wi-Fi connection" : ip + ":" + BridgeService.PORT);

        // Hide the last frame once it goes stale so the preview never looks live when it isn't.
        boolean live = BridgeService.state == BridgeService.State.STREAMING;
        preview.setVisibility(live ? View.VISIBLE : View.INVISIBLE);
        previewHint.setVisibility(live ? View.GONE : View.VISIBLE);

        fpsValue.setText(String.valueOf(BridgeService.fps));
        clientsValue.setText(String.valueOf(BridgeService.clients));

        boolean on = BridgeService.running;
        toggle.setText(on ? "Stop bridge" : "Start bridge");
        toggleShape.setColor(on ? 0xFF3A3D44 : BLUE);
        if (fakeToggle != null) {
            boolean fake = BridgeService.fakeTracker;
            fakeToggle.setText(fake ? "Stop fake tracker" : "Start fake tracker");
            fakeShape.setColor(fake ? 0xFF3A3D44 : BLUE);
        }
    }

    /** Large target for the controller pointer / hand tracking. */
    private Button pillButton(GradientDrawable shape) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(22);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, null));
        return b;
    }

    private LinearLayout.LayoutParams pillLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72));
        lp.topMargin = dp(24);
        return lp;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, MUTED);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private LinearLayout stat(String name, TextView value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(name));
        value.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(value);
        return col;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---- Bridge control ----

    private void startBridge() {
        BridgeService.setEnabled(this, true);
        startForegroundService(new Intent(this, BridgeService.class));
    }

    private static String wifiIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
