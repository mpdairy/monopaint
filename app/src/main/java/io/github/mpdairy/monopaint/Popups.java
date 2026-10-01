package io.github.mpdairy.monopaint;

import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;

/**
 * Popups placed in the app's own upright coordinates.
 *
 * <p>Android and the e-ink driver always stay in portrait; the app turns its view tree
 * instead. A popup is therefore laid out in "app" coordinates (relative to the turned
 * root view), its content is turned to match, and its rectangle is mapped back to
 * Android's portrait window when shown.
 */
final class Popups {
    private Popups() { }

    /** A flat, unanimated popup whose content is turned by {@code turn} degrees. */
    static PopupWindow create(View content, int turn, boolean focusable, int background) {
        QuarterTurnLayout rotated = new QuarterTurnLayout(content.getContext());
        rotated.setTurn(turn); rotated.addView(content);
        PopupWindow popup = new PopupWindow(rotated, 0, 0, focusable);
        popup.setBackgroundDrawable(new ColorDrawable(background));
        popup.setOutsideTouchable(focusable); popup.setElevation(0); popup.setAnimationStyle(0);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        return popup;
    }
    static PopupWindow create(View content, int turn) { return create(content, turn, true, Color.WHITE); }

    /** A view's bounds in the root's app coordinates. */
    static RectF bounds(View root, View view) {
        Matrix toRoot = new Matrix(); PanelCoordinates.fromView(root).invert(toRoot);
        RectF bounds = new RectF(0, 0, view.getWidth(), view.getHeight());
        PanelCoordinates.fromView(view).mapRect(bounds); toRoot.mapRect(bounds);
        return bounds;
    }

    /** Height a view wants at {@code width}, optionally capped at {@code maxHeight} (0 = no cap). */
    static int measuredHeight(View view, int width, int maxHeight) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), maxHeight > 0
                ? View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    /** Shows, or moves, the popup over {@code bounds} given in the root's app coordinates. */
    static void show(PopupWindow popup, View root, RectF bounds) {
        RectF window = new RectF(bounds); PanelCoordinates.fromView(root).mapRect(window);
        int left = Math.round(window.left), top = Math.round(window.top);
        int width = Math.round(window.width()), height = Math.round(window.height());
        if (popup.isShowing()) { popup.update(left, top, width, height); return; }
        popup.setWidth(width); popup.setHeight(height);
        popup.showAtLocation(root, Gravity.TOP | Gravity.LEFT, left, top);
    }

    static RectF rect(float left, float top, float width, float height) {
        return new RectF(left, top, left+width, top+height);
    }
    static float clamp(float value, float low, float high) { return Math.max(low, Math.min(value, high)); }

    /**
     * Places a panel of the given size beside its toolbar anchor, within {@code area}.
     * In portrait it opens toward the canvas beside the icon; in landscape, below or
     * above the icon, whichever side faces the canvas.
     */
    static RectF besideAnchor(RectF anchor, RectF area, int width, int height, boolean landscape, int gap) {
        float left = landscape ? anchor.left
                : anchor.centerX() < area.centerX() ? anchor.right+gap : anchor.left-width-gap;
        float top = !landscape ? anchor.top
                : anchor.centerY() < area.centerY() ? anchor.bottom+gap : anchor.top-height-gap;
        return rect(clamp(left, area.left, area.right-width), clamp(top, area.top, area.bottom-height), width, height);
    }
}
