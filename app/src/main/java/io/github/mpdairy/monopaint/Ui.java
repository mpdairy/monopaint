package io.github.mpdairy.monopaint;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import java.util.function.IntConsumer;

/** Small view helpers shared by the app's hand-built controls. */
final class Ui {
    private Ui() { }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A white panel or control background with a solid outline. */
    static GradientDrawable outline(int strokeWidth, int strokeColor, float radius) {
        return outline(Color.WHITE, strokeWidth, strokeColor, radius);
    }
    static GradientDrawable outline(int fill, int strokeWidth, int strokeColor, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setStroke(strokeWidth, strokeColor); drawable.setCornerRadius(radius);
        return drawable;
    }

    /** Whether a screen event lies outside a view, including views turned with the app. */
    static boolean outside(View view, MotionEvent event) {
        float[] point = {event.getRawX(), event.getRawY()};
        Matrix inverse = new Matrix();
        PanelCoordinates.fromView(view).invert(inverse); inverse.mapPoints(point);
        return point[0] < 0 || point[1] < 0 || point[0] >= view.getWidth() || point[1] >= view.getHeight();
    }

    static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup)view.getParent()).removeView(view);
    }

    static void invalidateTree(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
        }
    }

    static void setDimmed(View view, boolean enabled, float dimmed) {
        view.setEnabled(enabled); view.setAlpha(enabled ? 1 : dimmed);
    }

    /** Reports every progress change, from the user or from code. */
    static void onProgress(SeekBar bar, IntConsumer change) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int value, boolean user) { change.accept(value); }
            @Override public void onStartTrackingTouch(SeekBar slider) { }
            @Override public void onStopTrackingTouch(SeekBar slider) { }
        });
    }
}
