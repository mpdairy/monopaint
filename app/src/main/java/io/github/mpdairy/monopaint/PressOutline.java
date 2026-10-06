package io.github.mpdairy.monopaint;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * The black box a control shows while pressed. It is submitted straight to the e-ink
 * panel on pen-down and released shortly after the click, so presses feel immediate.
 */
final class PressOutline {
    /** The box sits this far inside the control, centered on its stroke. */
    static final int BOX_INSET_DP = 3;
    private static final int RELEASE_MS = 200;
    private final View owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable release = () -> set(false);
    /** Controls without a feedback session draw no press outline. */
    SelectionFeedback feedback;
    boolean pressed;

    PressOutline(View owner, SelectionFeedback feedback) {
        this.owner = owner; this.feedback = feedback;
        paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(owner.getContext(), 3));
    }

    void set(boolean value) {
        owner.removeCallbacks(release);
        if (feedback == null || pressed == value) return;
        feedback.update(owner, () -> pressed = value);
    }
    void releaseLater() {
        owner.removeCallbacks(release);
        if (feedback != null && owner.isAttachedToWindow()) owner.postDelayed(release, RELEASE_MS);
    }
    /** Call before the control's own touch handling. {@code accepting} is false for disabled or blocked controls. */
    void onTouch(MotionEvent event, boolean accepting) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { if (accepting) set(true); }
        else if (action == MotionEvent.ACTION_CANCEL || (action == MotionEvent.ACTION_MOVE && (event.getX() < 0
                || event.getY() < 0 || event.getX() >= owner.getWidth() || event.getY() >= owner.getHeight()))) set(false);
    }
    void draw(Canvas canvas) { if (pressed) drawBox(canvas); }
    void drawBox(Canvas canvas) {
        int inset = Ui.dp(owner.getContext(), BOX_INSET_DP), radius = Ui.dp(owner.getContext(), 4);
        canvas.drawRoundRect(inset, inset, owner.getWidth()-inset, owner.getHeight()-inset, radius, radius, paint);
    }
    void detached() { owner.removeCallbacks(release); pressed = false; }
}
