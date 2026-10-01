package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * The gray strip in the color bar, drawn with the panel's real dot patterns. Tap or drag
 * to choose a shade; a held gesture previews a pending gradient's second color.
 */
@SuppressLint("ViewConstructor")
final class ShadePicker extends View {
    private final PaintActivity app;
    private Bitmap strip;
    /** Width of the solid black and white end zones. */
    private int endpointWidth;
    private final Paint marker = new Paint();
    private int activePointer = -1;

    ShadePicker(PaintActivity app) {
        super(app); this.app = app; setFocusable(true); setClickable(true);
        setContentDescription("Gray gradient. Tap or drag from black on the left to white on the right.");
    }
    private int dp(float value) { return app.dp(value); }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (strip != null) strip.recycle();
        int width = w - dp(10), height = h - dp(15);
        if (width <= 0 || height <= 0) { strip = null; return; }
        endpointWidth = Math.min(dp(12), width / 16);
        int[] pixels = new int[width * height];
        for (int x = 0; x < width; x++) {
            int tone = toneAt(x, width);
            for (int y = 0; y < height; y++) pixels[y * width + x] = DotPattern.pixel(tone, x, y);
        }
        strip = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        if (strip == null) return;
        int saved = canvas.save();
        // Black stays at the bottom; the black marker faces the canvas
        // whether the landscape palette is on the left or the right.
        if (app.landscape) canvas.scale(-1, app.toolboxRight ? 1 : -1, getWidth()/2f, getHeight()/2f);
        int inset = dp(5);
        canvas.drawBitmap(strip, inset, inset, null);
        marker.setColor(Color.BLACK); marker.setStyle(Paint.Style.STROKE); marker.setStrokeWidth(1);
        canvas.drawRect(inset, inset, inset + strip.getWidth(), inset + strip.getHeight(), marker);
        if (app.shadeMarkerVisible()) drawMarker(canvas, inset);
        canvas.restoreToCount(saved);
    }
    private void drawMarker(Canvas canvas, int inset) {
        int gray = app.paint.gray, density = DotPattern.whiteCount(gray), width = strip.getWidth();
        float x = inset + (density == 0 ? endpointWidth / 2f : density == 64 ? width - endpointWidth / 2f
                : endpointWidth + DotPattern.pickerPosition(gray) * (width - 2 * endpointWidth - 1) / 255f);
        marker.setStyle(Paint.Style.FILL);
        canvas.drawRect(x - dp(3), getHeight() - dp(7), x + dp(3), getHeight() - dp(1), marker);
        marker.setColor(Color.WHITE); marker.setStrokeWidth(dp(3));
        canvas.drawLine(x, inset, x, inset + dp(7), marker);
        marker.setColor(Color.BLACK); marker.setStrokeWidth(1);
        canvas.drawLine(x, inset, x, inset + dp(7), marker);
    }
    private void selectX(float x) {
        if (strip == null || !Float.isFinite(x)) return;
        if (app.landscape) x = getWidth()-x;
        app.selectShade(toneAt(x - dp(5), strip.getWidth()));
    }
    private int toneAt(float x, int width) {
        int position = Math.round((x - endpointWidth) * 255f / Math.max(1, width - 2 * endpointWidth - 1));
        return DotPattern.pickerTone(position);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (app.busy()) return true;
        int action = event.getActionMasked();
        boolean up = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(event.getActionIndex()) == activePointer;
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0);
            app.hideGradientHint();
            if (!app.pad.hasGradient()) app.pad.finishStroke();
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        int index = event.findPointerIndex(activePointer);
        if (index >= 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || up))
            selectX(event.getX(index));
        if (up || action == MotionEvent.ACTION_CANCEL) {
            app.endShadeGesture(up);
            activePointer = -1; app.saveToolState(); getParent().requestDisallowInterceptTouchEvent(false);
            if (up) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            app.selectShade(DotPattern.pickerTone(DotPattern.pickerPosition(app.paint.gray) + direction * 4));
            app.saveToolState(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            app.endShadeGesture(true); return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
