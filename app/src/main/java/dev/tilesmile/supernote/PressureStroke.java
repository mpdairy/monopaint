package dev.tilesmile.supernote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;

/** The 0.12 opaque circles and spacing, applied to logical tones through a mask. */
final class PressureStroke implements DrawingStroke {
    private final ToneDocument document;
    private final int maximum, gray, side;
    private final ToolSettings settings;
    private final Bitmap mask;
    private final Canvas canvas;
    private final Paint paint = new Paint();
    private final int[] pixels;
    private float previousX, previousY, previousRadius;
    private boolean started;

    PressureStroke(ToneDocument document, int maximum, int gray) {
        this(document,ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(maximum),gray);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray) {
        this.document = document; this.settings = settings; this.maximum = settings.maximum; this.gray = gray;
        side = maximum + 4;
        mask = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        pixels = new int[side * side]; canvas = new Canvas(mask);
        paint.setColor(Color.BLACK); paint.setAntiAlias(false);
        document.begin();
    }
    void sample(float x, float y, float pressure) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
        // Limit interpolation work for malformed input outside the owned canvas.
        x = Math.max(-maximum, Math.min(document.width + maximum, x));
        y = Math.max(-maximum, Math.min(document.height + maximum, y));
        float radius = settings.diameter(pressure) / 2;
        if (!started) { previousX = x; previousY = y; previousRadius = radius; started = true; }
        int steps = Math.max(1, (int)Math.ceil(Math.hypot(x - previousX, y - previousY)
                / Math.max(.5f, Math.min(radius, previousRadius) * .4f)));
        for (int i = 1; i <= steps; i++) {
            float t = (float)i / steps;
            dab(previousX + (x - previousX) * t, previousY + (y - previousY) * t,
                    previousRadius + (radius - previousRadius) * t);
        }
        previousX = x; previousY = y; previousRadius = radius;
    }
    private void dab(float x, float y, float radius) {
        int left = (int)Math.floor(x - radius) - 1, top = (int)Math.floor(y - radius) - 1;
        int w = Math.min(side, (int)Math.ceil(x + radius) + 1 - left);
        int h = Math.min(side, (int)Math.ceil(y + radius) + 1 - top);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawCircle(x - left, y - top, radius, paint);
        mask.getPixels(pixels, 0, w, 0, 0, w, h);
        document.paintMask(pixels, w, left, top, w, h, gray);
    }
    @Override public void sample(float x, float y, float pressure, float tiltX, float tiltY) { sample(x,y,pressure); }
    @Override public boolean finish() { mask.recycle(); return document.finish(); }
}
