package io.github.mpdairy.monopaint;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Shared geometry for the stroke mask and the actual-size settings preview. */
final class BrushStamp {
    static float extent(ToolSettings settings, float radius) {
        return settings.head == ToolSettings.Head.FLAT
                ? (float)Math.hypot(radius, settings.flatHeight(radius*2)/2) : radius;
    }
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings) {
        draw(canvas,paint,x,y,radius,settings,settings.angle,null);
    }
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings, float angle,
                     RectF footprint) {
        if (settings.head == ToolSettings.Head.ROUND) {
            if (footprint != null) footprint.set(x-radius,y-radius,x+radius,y+radius);
            canvas.drawCircle(x, y, radius, paint);
            return;
        }
        float minor = settings.head == ToolSettings.Head.FLAT ? settings.flatHeight(radius*2)/2 : Math.max(.75f, radius * settings.headAspectRatio());
        // Subpixel-thin ellipses can contain no raster pixel centers at all.
        // Keep the smallest heads visible without changing the legacy round tip.
        if (radius < 1) {
            float left=(float)Math.floor(x),top=(float)Math.floor(y);
            if (footprint != null) footprint.set(left,top,left+1,top+1);
            canvas.drawRect(left,top,left+1,top+1,paint);return;
        }
        if (footprint != null) {
            float cs=(float)Math.cos(Math.toRadians(angle)),sn=(float)Math.sin(Math.toRadians(angle));
            float dx=Math.abs(cs)*radius+Math.abs(sn)*minor;
            float dy=Math.abs(sn)*radius+Math.abs(cs)*minor;
            footprint.set(x-dx,y-dy,x+dx,y+dy);
        }
        int saved = canvas.save();
        canvas.rotate(angle, x, y);
        if (settings.head == ToolSettings.Head.FLAT) {
            canvas.drawRect(x-radius, y-minor, x+radius, y+minor, paint);
        } else {
            // Curve the whole edge so even a thin filbert reads as a rounded
            // brush, rather than a flat head with tiny rounded corners.
            canvas.drawOval(x-radius,y-minor,x+radius,y+minor,paint);
        }
        canvas.restoreToCount(saved);
    }
    private BrushStamp() {}
}
