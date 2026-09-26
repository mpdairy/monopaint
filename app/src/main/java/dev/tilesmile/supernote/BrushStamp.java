package dev.tilesmile.supernote;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Shared geometry for the stroke mask and the actual-size settings preview. */
final class BrushStamp {
    static float extent(ToolSettings settings, float radius) {
        if (settings.tilt && settings.head != ToolSettings.Head.ROUND)
            return (float)Math.hypot(radius*(settings.head==ToolSettings.Head.FLAT?1.025f:1),
                    2 * fullyPressedMinor(settings, radius));
        return settings.head == ToolSettings.Head.FLAT
                ? (float)Math.hypot(radius, Math.max(.75f, radius * settings.headAspectRatio())) : radius;
    }
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings) {
        // Center the preview's leaned footprint; live marks stay anchored at the
        // pen tip and extend forward using the overload below.
        if (settings.tilt && settings.head != ToolSettings.Head.ROUND) {
            float offset = fullyPressedMinor(settings, radius);
            x += (float)Math.sin(Math.toRadians(settings.angle))*offset;
            y -= (float)Math.cos(Math.toRadians(settings.angle))*offset;
        }
        draw(canvas,paint,x,y,radius,settings,settings.angle);
    }
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings, float angle) {
        draw(canvas,paint,x,y,radius,settings,angle,settings.tilt?1:0,1);
    }
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings, float angle,
                     float signedLean, float pressure) {
        draw(canvas,paint,x,y,radius,settings,angle,signedLean,pressure,null);
    }
    static void draw(Canvas canvas, Paint paint, float x, float y, float radius, ToolSettings settings, float angle,
                     float signedLean, float pressure, RectF footprint) {
        if (settings.head == ToolSettings.Head.ROUND) {
            if (footprint != null) footprint.set(x-radius,y-radius,x+radius,y+radius);
            canvas.drawCircle(x, y, radius, paint);
            return;
        }
        float minor = Math.max(.75f, radius * settings.headAspectRatio());
        float lean = settings.tilt ? Math.abs(signedLean) : 0;
        float compression = pressure * (2-pressure);
        float offset = 0;
        float tipSplay = 0, tipDepth = 0;
        float bandMinor = minor;
        if (settings.tilt) {
            float brushRadius = radius;
            float upright = Math.max(.75f, minor * .5f);
            // Near upright, pressure exposes the width of the rounded end.
            // A leaning tip starts wider, but still spreads as bristles compress.
            float contact = lean + (1-lean)*compression;
            radius = upright + (brushRadius-upright)*contact;
            // Flat keeps its fine edge; Filbert uses its fuller rounded band.
            float uprightDepth = upright + (bandMinor*1.2f-upright)*compression;
            float leanedDepth = bandMinor*(1+.2f*compression);
            minor = uprightDepth + (leanedDepth-uprightDepth)*lean;
            offset = minor * signedLean;
            if (settings.head == ToolSettings.Head.FLAT) {
                tipSplay = brushRadius*.025f*compression*lean;
                tipDepth = Math.min(2*minor,Math.max(1,brushRadius*.12f));
            }
        }
        // Subpixel-thin ellipses can contain no raster pixel centers at all.
        // Keep the smallest heads visible without changing the legacy round tip.
        if (radius < 1) {
            float left=(float)Math.floor(x),top=(float)Math.floor(y);
            if (footprint != null) footprint.set(left,top,left+1,top+1);
            canvas.drawRect(left,top,left+1,top+1,paint);return;
        }
        if (footprint != null) {
            float cs=(float)Math.cos(Math.toRadians(angle)),sn=(float)Math.sin(Math.toRadians(angle));
            float cx=x-sn*offset,cy=y+cs*offset;
            float dx=Math.abs(cs)*(radius+tipSplay)+Math.abs(sn)*minor;
            float dy=Math.abs(sn)*(radius+tipSplay)+Math.abs(cs)*minor;
            footprint.set(cx-dx,cy-dy,cx+dx,cy+dy);
        }
        int saved = canvas.save();
        canvas.rotate(angle, x, y);
        y += offset;
        if (settings.head == ToolSettings.Head.FLAT) {
            canvas.drawRect(x-radius, y-minor, x+radius, y+minor, paint);
            if (tipSplay > 0) {
                // Only the original bristle tips flare sideways. The belly
                // laid down behind them retains the brush's body width.
                float front=y-Math.copySign(minor,signedLean);
                float back=front+Math.copySign(tipDepth,signedLean);
                Path tip=new Path();
                tip.moveTo(x-radius-tipSplay,front);tip.lineTo(x+radius+tipSplay,front);
                tip.lineTo(x+radius,back);tip.lineTo(x-radius,back);tip.close();
                canvas.drawPath(tip,paint);
            }
        } else {
            // Curve the whole edge so even a thin filbert reads as a rounded
            // brush, rather than a flat head with tiny rounded corners.
            canvas.drawOval(x-radius,y-minor,x+radius,y+minor,paint);
        }
        canvas.restoreToCount(saved);
    }
    private static float fullyPressedMinor(ToolSettings settings, float radius) {
        return Math.max(.75f,radius*settings.headAspectRatio())*1.2f;
    }
    private BrushStamp() {}
}
