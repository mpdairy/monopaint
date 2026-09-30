package io.github.mpdairy.monopaint;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Measures and turns one app-owned view while Android's display remains in portrait. */
final class QuarterTurnLayout extends ViewGroup {
    private int turn;
    private int laidOutTurn;
    Runnable afterLayout;

    QuarterTurnLayout(Context context) { super(context); }

    void setTurn(int degrees) {
        if (turn == degrees) return;
        turn = degrees;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        View child = getChildAt(0);
        boolean swap = Math.abs(turn) == 90;
        child.measure(swap ? heightSpec : widthSpec, swap ? widthSpec : heightSpec);
        setMeasuredDimension(swap ? child.getMeasuredHeight() : child.getMeasuredWidth(),
                swap ? child.getMeasuredWidth() : child.getMeasuredHeight());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean transformChanged = changed || laidOutTurn != turn;
        View child = getChildAt(0);
        child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
        child.setPivotX(0); child.setPivotY(0); child.setRotation(turn);
        child.setTranslationX(turn == 90 || turn == 180 ? getWidth() : 0);
        child.setTranslationY(turn == -90 || turn == 180 ? getHeight() : 0);
        laidOutTurn = turn;
        // A toolbar rebuild may request layout without moving the canvas. Keep
        // its live e-ink session and any deferred pen presentation in that case.
        if (transformChanged && afterLayout != null) afterLayout.run();
    }
}
