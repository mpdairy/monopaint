package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;

/** The side toolbar's strip of buttons, with the drop line shown while reordering favorites. */
final class ToolRail extends LinearLayout {
    /** Position of the drop line along the rail, or -1 when hidden. */
    float dropLine = -1;
    private final Paint linePaint = new Paint();
    ToolRail(Context context) { super(context); }
    private boolean horizontal() { return getOrientation() == HORIZONTAL; }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (dropLine < 0) return;
        linePaint.setColor(Color.BLACK); linePaint.setStrokeWidth(dp(2));
        if (horizontal()) canvas.drawLine(dropLine, dp(4), dropLine, getHeight()-dp(4), linePaint);
        else canvas.drawLine(dp(4), dropLine, getWidth()-dp(4), dropLine, linePaint);
    }

    /** The dashed rule between built-in tools and favorites. */
    @SuppressLint("ViewConstructor")
    static final class Divider extends View {
        private final Paint dashPaint = new Paint();
        private final boolean vertical;
        Divider(Context context, boolean vertical) {
            super(context); this.vertical = vertical; dashPaint.setColor(Color.BLACK);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private int dp(float value) { return Ui.dp(getContext(), value); }
        @Override protected void onDraw(Canvas canvas) {
            // Filled rectangles stay visible even when a stroked drawable's bounds are inset.
            if (vertical) {
                for (int y = 0; y < getHeight(); y += dp(7))
                    canvas.drawRect((getWidth()-dp(2))/2f, y, (getWidth()+dp(2))/2f, Math.min(y+dp(4), getHeight()), dashPaint);
                return;
            }
            int dash = dp(4), gap = dp(3), top = (getHeight()-dp(2))/2;
            for (int x = 0; x < getWidth(); x += dash+gap)
                canvas.drawRect(x, top, Math.min(x+dash, getWidth()), top+dp(2), dashPaint);
        }
    }
}
