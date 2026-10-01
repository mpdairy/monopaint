package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import java.util.function.Supplier;

/** Minimum and maximum tool footprints side by side, at actual canvas pixel size. */
@SuppressLint("ViewConstructor")
final class Footprint extends View {
    private final Paint paint = new Paint();
    private final Supplier<ToolSettings> settings;
    /** Caption size in sp. */
    private final float textSize;

    Footprint(Context context, Supplier<ToolSettings> settings, float textSize) {
        super(context); this.settings = settings; this.textSize = textSize;
        setContentDescription("Minimum and maximum footprints side by side at actual canvas pixel size");
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE); paint.setColor(Color.BLACK);
        float y = getHeight()/2f - Ui.dp(getContext(), 10);
        ToolSettings s = settings.get();
        boolean pencil = s.tool == ToolSettings.Tool.PENCIL;
        for (int i = 0; i < 2; i++) {
            float x = getWidth()*(i == 0 ? .3f : .7f);
            int diameter = i == 0 ? s.minimum : pencil && !s.tilt ? s.tip : s.maximum;
            float r = diameter/2f;
            if (s.tool == ToolSettings.Tool.SOFTEN || (s.tool == ToolSettings.Tool.ERASER && s.softness > 0)) {
                float edge = s.tool == ToolSettings.Tool.ERASER ? s.softness/100f : 1;
                paint.setShader(new RadialGradient(x, y, r, new int[]{Color.BLACK, Color.BLACK, Color.WHITE},
                        new float[]{0, Math.max(.001f, 1-edge), 1}, Shader.TileMode.CLAMP));
            }
            if (i == 1 && pencil && s.tilt) {
                float minor = s.tip + (s.maximum-s.tip)*.28f;
                canvas.drawOval(x-r, y-minor/2, x+r, y+minor/2, paint);
            } else BrushStamp.draw(canvas, paint, x, y, r, s);
            paint.setShader(null); paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize((textSize-1)*getResources().getDisplayMetrics().scaledDensity); paint.setTextAlign(Paint.Align.CENTER);
            String prefix = i == 0 ? "Min " : pencil ? (s.tilt ? "Max tilted " : "Max upright ") : "Max ";
            canvas.drawText(prefix + diameter + " px", x, getHeight() - Ui.dp(getContext(), 8), paint);
        }
    }
}
