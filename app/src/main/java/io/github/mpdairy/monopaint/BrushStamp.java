package io.github.mpdairy.monopaint;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Shared geometry for the stroke mask and the actual-size settings preview. */
final class BrushStamp {
    /** Reused crescent outlines; stamps are drawn on the UI thread only. */
    private static final Path crescent = new Path(), bite = new Path();
    /**
     * Oil paint filberts are crescents hollowed on the side they move toward, so the first
     * touch of a stroke is a thin sliver with the filbert's rounded edge, while the dragged
     * head still sweeps the full filbert.
     */
    static boolean crescent(ToolSettings settings) {
        return settings.head == ToolSettings.Head.FILBERT && settings.limitsPaint() && !settings.tool.water;
    }
    static float extent(ToolSettings settings, float radius) {
        return settings.head == ToolSettings.Head.FLAT
                ? (float)Math.hypot(radius, settings.flatHeight(radius*2)/2) : radius;
    }
    /** Half the head's short side for a head {@code radius} long. Brush pens stretch along their lean like a pencil. */
    static float minor(ToolSettings settings, float radius) {
        if (settings.tool.water) return settings.leanMinor(radius * 2, settings.minimum) / 2;
        if (settings.head == ToolSettings.Head.ROUND) return radius;
        return settings.head == ToolSettings.Head.FLAT ? settings.flatHeight(radius*2)/2 : Math.max(.75f, radius * settings.headAspectRatio());
    }
    /** The preview: a crescent heads downward, the way the head's short side faces. */
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings) {
        draw(canvas,paint,x,y,radius,settings,settings.angle,null,1);
    }
    /**
     * @param lead for a {@link #crescent} head, +1 or -1 for the side of its short axis that
     *             leads the stroke; 0 before the stroke has a direction draws the whole oval
     */
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings, float angle,
                     RectF footprint, float lead) {
        if (settings.head == ToolSettings.Head.ROUND && !settings.tool.water) {
            if (footprint != null) footprint.set(x-radius,y-radius,x+radius,y+radius);
            canvas.drawCircle(x, y, radius, paint);
            return;
        }
        float minor = minor(settings, radius);
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
        } else if (lead != 0 && crescent(settings)) {
            // Bite a slightly shorter oval out of the leading side. The middle keeps a
            // quarter of the head, thicker than the dab spacing, and the tips stay full.
            crescent.reset(); bite.reset();
            crescent.addOval(x-radius,y-minor,x+radius,y+minor,Path.Direction.CW);
            float front = y + Math.signum(lead) * minor * .5f;
            bite.addOval(x-radius*.85f,front-minor,x+radius*.85f,front+minor,Path.Direction.CW);
            crescent.op(bite, Path.Op.DIFFERENCE);
            canvas.drawPath(crescent, paint);
        } else {
            // Curve the whole edge so even a thin filbert reads as a rounded
            // brush, rather than a flat head with tiny rounded corners.
            canvas.drawOval(x-radius,y-minor,x+radius,y+minor,paint);
        }
        canvas.restoreToCount(saved);
    }
    private BrushStamp() {}
}
