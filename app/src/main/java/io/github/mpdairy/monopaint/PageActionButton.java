package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.MotionEvent;
import android.widget.ImageButton;

/**
 * An icon button whose action runs on the next frame, after its pressed outline has
 * reached the panel. Used for page turns, the menu and the rotation prompt.
 */
@SuppressLint("ViewConstructor")
final class PageActionButton extends ImageButton {
    private final ControlHost host;
    /** Header buttons keep their icon upright when the header strip turns. */
    private final boolean header;
    final PressOutline press;
    private final ControlRaster controlRaster = new ControlRaster();

    PageActionButton(Context context, ControlHost host, boolean header) {
        super(context);
        this.host = host; this.header = header; press = new PressOutline(this, host.selectionFeedback());
        setBackgroundColor(Color.WHITE); setStateListAnimator(null); setElevation(0);
    }
    private boolean blocked() { return host.busy() || host.pageActionPending(); }
    @Override public void draw(Canvas canvas) {
        controlRaster.draw(canvas,getWidth(),getHeight(),super::draw);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        press.onTouch(event, isEnabled() && !blocked());
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) press.releaseLater();
        return handled;
    }
    @Override public boolean performClick() {
        if (!isEnabled() || blocked()) return false;
        press.set(true); boolean handled = super.performClick(); press.releaseLater(); return handled;
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.save(); if (header) canvas.rotate(-host.toolbarTurn(), getWidth()/2f, getHeight()/2f);
        super.onDraw(canvas); canvas.restore();
        press.draw(canvas);
    }
    @Override protected void onDetachedFromWindow() { press.detached(); controlRaster.close(); super.onDetachedFromWindow(); }
}
