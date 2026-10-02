package io.github.mpdairy.monopaint;

import android.view.MotionEvent;
import java.util.function.Consumer;

/**
 * Measures how far one pen gesture rubs around in the color bar or palette. A brush with
 * limited paint loads as much paint as the pen rubbed, the way a brush is worked in a palette.
 */
final class PaintRub {
    private final Consumer<Float> loaded;
    private float x, y, travel;
    private int pointer = -1;

    /** @param loaded receives the gesture's travel in pixels when the pen lifts */
    PaintRub(Consumer<Float> loaded) { this.loaded = loaded; }

    void track(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointer = event.getPointerId(0); x = event.getX(); y = event.getY(); travel = 0; return;
        }
        int index = event.findPointerIndex(pointer);
        if (index < 0) return;
        for (int i = 0; i < event.getHistorySize(); i++) to(event.getHistoricalX(index, i), event.getHistoricalY(index, i));
        to(event.getX(index), event.getY(index));
        boolean up = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(event.getActionIndex()) == pointer;
        if (up) loaded.accept(travel);
        if (up || action == MotionEvent.ACTION_CANCEL) pointer = -1;
    }
    private void to(float nextX, float nextY) {
        travel += (float)Math.hypot(nextX - x, nextY - y); x = nextX; y = nextY;
    }
}
