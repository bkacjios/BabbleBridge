package com.bkacjios.babblebridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;

/**
 * Debug stand-in for the tracker: renders a moving mouth and wraps each JPEG
 * in the same serial packet format the real firmware sends.
 */
final class FakeTracker {

    private static final int SIZE = 240;

    private final Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bmp);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    private int frame;

    /** Returns one packet: FF A0 FF A1 | uint16 LE length | JPEG. */
    byte[] nextPacket() {
        draw();
        jpeg.reset();
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
        byte[] img = jpeg.toByteArray();

        byte[] packet = new byte[FrameParser.HEADER.length + 2 + img.length];
        System.arraycopy(FrameParser.HEADER, 0, packet, 0, FrameParser.HEADER.length);
        packet[4] = (byte) img.length;
        packet[5] = (byte) (img.length >> 8);
        System.arraycopy(img, 0, packet, 6, img.length);
        frame++;
        return packet;
    }

    private void draw() {
        double t = frame / 30.0;
        // IR-style gray skin
        canvas.drawColor(Color.rgb(110, 110, 110));

        // Mouth stays centered while it opens, closes and smiles
        float open = (float) (0.5 + 0.5 * Math.sin(t * 2.4));   // 0 closed .. 1 open
        float smile = (float) (8 * Math.sin(t * 0.7));
        float cx = SIZE / 2f;
        float halfW = 62 + smile;
        float gap = 4 + 34 * open;
        float cy = (SIZE - gap) / 2f;   // top lip edge; keeps the opening centered

        // Lips
        paint.setColor(Color.rgb(80, 80, 80));
        rect.set(cx - halfW - 6, cy - 22, cx + halfW + 6, cy + gap + 22);
        canvas.drawOval(rect, paint);

        // Mouth opening, teeth, tongue
        paint.setColor(Color.rgb(15, 15, 15));
        rect.set(cx - halfW, cy - 4, cx + halfW, cy + gap);
        canvas.drawOval(rect, paint);
        if (gap > 14) {
            paint.setColor(Color.rgb(210, 210, 210));
            rect.set(cx - halfW * 0.6f, cy - 2, cx + halfW * 0.6f, cy + 6);
            canvas.drawRect(rect, paint);
            paint.setColor(Color.rgb(60, 60, 60));
            rect.set(cx - halfW * 0.5f, cy + gap * 0.55f, cx + halfW * 0.5f, cy + gap + 2);
            canvas.drawOval(rect, paint);
        }

        paint.setColor(Color.rgb(200, 200, 200));
        paint.setTextSize(16);
        canvas.drawText("FAKE " + frame, 8, SIZE - 10, paint);
    }
}
