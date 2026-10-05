package io.github.mpdairy.monopaint;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Shared geometry for the stroke mask and the actual-size settings preview. */
final class BrushStamp {
    /** Reused outlines; stamps are drawn on the UI thread only. */
    private static final Path crescent = new Path(), bite = new Path(), stamp = new Path();
    private static final Matrix turn = new Matrix();
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
        if (footprint != null) bounds(footprint, x, y, radius, minor, angle);
        int saved = canvas.save();
        canvas.rotate(angle, x, y);
        if (settings.head == ToolSettings.Head.FLAT) {
            canvas.drawRect(x-radius, y-minor, x+radius, y+minor, paint);
        } else if (lead != 0 && crescent(settings)) {
            canvas.drawPath(crescent(x, y, radius, minor, lead), paint);
        } else {
            // Curve the whole edge so even a thin filbert reads as a rounded
            // brush, rather than a flat head with tiny rounded corners.
            canvas.drawOval(x-radius,y-minor,x+radius,y+minor,paint);
        }
        canvas.restoreToCount(saved);
    }
    /**
     * Adds the stamp {@link #draw} paints to {@code path}, so a run of stamps can be filled
     * as one anti-aliased union: separate soft-edged stamps leave gaps where none covers a pixel fully.
     */
    static void outline(Path path, float x, float y, float radius, ToolSettings settings, float angle,
                        RectF footprint, float lead) {
        if (settings.head == ToolSettings.Head.ROUND && !settings.tool.water) {
            footprint.set(x-radius,y-radius,x+radius,y+radius);
            path.addCircle(x, y, radius, Path.Direction.CW);
            return;
        }
        float minor = minor(settings, radius);
        // An anti-aliased hairline needs no pixel center; keep it centered instead of snapping.
        minor = Math.max(.5f, minor);
        bounds(footprint, x, y, radius, minor, angle);
        turn.setRotate(angle, x, y);
        if (lead != 0 && crescent(settings)) {
            // Path.op output winds either way, which nonzero filling would cancel against its neighbors.
            crescent(x, y, radius, minor, lead).transform(turn);
            path.op(crescent, Path.Op.UNION);
            return;
        }
        stamp.reset();
        if (settings.head == ToolSettings.Head.FLAT) stamp.addRect(x-radius, y-minor, x+radius, y+minor, Path.Direction.CW);
        else stamp.addOval(x-radius, y-minor, x+radius, y+minor, Path.Direction.CW);
        path.addPath(stamp, turn);
    }
    private static void bounds(RectF footprint, float x, float y, float radius, float minor, float angle) {
        float cs=(float)Math.cos(Math.toRadians(angle)),sn=(float)Math.sin(Math.toRadians(angle));
        float dx=Math.abs(cs)*radius+Math.abs(sn)*minor;
        float dy=Math.abs(sn)*radius+Math.abs(cs)*minor;
        footprint.set(x-dx,y-dy,x+dx,y+dy);
    }
    /** An unrotated crescent: a slightly shorter oval bitten out of the leading side. */
    private static Path crescent(float x, float y, float radius, float minor, float lead) {
        // The middle keeps a quarter of the head, thicker than the dab spacing, and the tips stay full.
        crescent.reset(); bite.reset();
        crescent.addOval(x-radius,y-minor,x+radius,y+minor,Path.Direction.CW);
        float front = y + Math.signum(lead) * minor * .5f;
        bite.addOval(x-radius*.85f,front-minor,x+radius*.85f,front+minor,Path.Direction.CW);
        crescent.op(bite, Path.Op.DIFFERENCE);
        return crescent;
    }
    private BrushStamp() {}
}
