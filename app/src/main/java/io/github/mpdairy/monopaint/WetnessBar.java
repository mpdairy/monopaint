package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/** The narrow vertical slider beside the wet-canvas droplet. Above zero enables blending; zero dries the canvas. */
@SuppressLint("ViewConstructor")
final class WetnessBar extends View {
    private final PaintActivity app;
    private final Paint ink = new Paint();
    private int activePointer = -1;

    WetnessBar(PaintActivity app) {
        super(app); this.app = app; setFocusable(true); setClickable(true);
        describe();
    }
    private int dp(float value) { return app.dp(value); }
    private int wetness() { return app.paint.wetness; }
    private void describe() {
        setContentDescription("Canvas wetness: " + wetness() + "%. Slide above zero to enable blending; zero dries the canvas.");
    }
    void select(int amount) {
        amount = Math.max(0, Math.min(100, amount));
        PaintState paint = app.paint;
        if (app.busy() || (paint.wetness == amount && paint.wetCanvas == (amount > 0))) return;
        app.pad.finishStroke();
        final int selected = amount;
        app.selectionFeedback.update(this, () -> { paint.wetness = selected; describe(); });
        paint.wetCanvas = amount > 0;
        if (app.pad.wet != null) app.pad.wet.setWetness(paint.wetness);
        if (!paint.wetCanvas) app.pad.dryWet();
        app.refreshPaintModes();
    }
    private void step(int direction) { select(wetness() + 5*direction); app.saveToolState(); }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        // Keep the narrow slider next to its droplet with room to touch either side.
        float left = dp(4), right = dp(16);
        float top = dp(6), bottom = getHeight() - dp(6);
        ink.setColor(Color.BLACK); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(dp(1));
        canvas.drawRect(left, top, right, bottom, ink);
        ink.setStyle(Paint.Style.FILL);
        canvas.drawRect(left, bottom - (bottom-top)*wetness()/100f, right, bottom, ink);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (app.busy()) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0); app.pad.finishStroke();
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        int index = event.findPointerIndex(activePointer);
        if (index >= 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            float y = event.getY(index);
            if (Float.isFinite(y)) select(Math.round(100 * (getHeight()-dp(6)-y) / Math.max(1, getHeight()-dp(12))));
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointer = -1; app.saveToolState(); getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            step(keyCode == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1); return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 100, wetness()));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == android.R.id.accessibilityActionSetProgress && args != null) {
            float value = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
            if (!Float.isFinite(value)) return false;
            select(Math.round(value)); app.saveToolState(); return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            step(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1); return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
