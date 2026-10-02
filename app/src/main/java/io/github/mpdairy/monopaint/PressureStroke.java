package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;

/** Pressure-sized brush stamps for opaque paint and black-dot washes. */
final class PressureStroke implements DrawingStroke {
    private final ToneDocument document;
    private final WetWatercolor wet;
    private final boolean transparent, batchFlat, erasing;
    private final int maximum, gray, side;
    private final ToolSettings settings;
    private final Bitmap mask;
    private final Canvas canvas;
    private final Paint paint = new Paint();
    private final int[] pixels;
    private final RectF footprint = new RectF();
    /** The brush's running-out paint, or null when it never runs out. */
    private final PaintLoad load;
    private float previousX, previousY, previousRadius, previousAngle, previousPull;
    /** Which side of a crescent head leads the stroke: +1, -1, or 0 until the pen first moves. */
    private float lead;
    private boolean started, dabbed;

    PressureStroke(ToneDocument document, int maximum, int gray) {
        this(document,ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(maximum),gray);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray) {
        this(document,settings,gray,0,null);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray, WetWatercolor wet) {
        this(document,settings,gray,0,wet);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray, int textureSeed) {
        this(document,settings,gray,textureSeed,null);
    }
    private PressureStroke(ToneDocument document, ToolSettings settings, int gray, int textureSeed, WetWatercolor wet) {
        this(document, settings, gray, wet, false, false, false);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray, WetWatercolor wet, boolean transparent) {
        this(document, settings, gray, wet, transparent, true, false);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray, WetWatercolor wet, boolean transparent, boolean erasing) {
        this(document, settings, gray, erasing ? null : wet, transparent, true, erasing);
    }
    private PressureStroke(ToneDocument document, ToolSettings settings, int gray, WetWatercolor wet,
                           boolean transparent, boolean canvasControls, boolean erasing) {
        this.erasing = erasing;
        this.transparent = transparent;
        this.document = document; this.settings = canvasControls && settings.isBrush() ? settings.asBrush() : settings; this.maximum = settings.maximum; this.gray = gray;
        this.wet = canvasControls ? wet : settings.tool == ToolSettings.Tool.WET_WATERCOLOR
                ? (wet == null ? new WetWatercolor(document) : wet) : null;
        load = canvasControls && !erasing && settings.limitsPaint()
                ? new PaintLoad(document, gray, settings.paintLength()) : null;
        // Running-out paint changes with every dab, so dabs cannot be unioned.
        batchFlat=this.wet==null && load==null && settings.head==ToolSettings.Head.FLAT;
        side = (int)Math.ceil(BrushStamp.extent(settings, maximum / 2f) * 2) + 4 + (batchFlat?66:0);
        mask = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        pixels = new int[side * side]; canvas = new Canvas(mask);
        paint.setColor(Color.BLACK); paint.setAntiAlias(false);
        document.begin();
        if (this.wet != null) this.wet.beginStroke();
    }
    void sample(float x, float y, float pressure) {
        sample(x,y,pressure,0,0);
    }
    @Override public void sample(float x, float y, float pressure, float tiltX, float tiltY) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
        // Limit interpolation work for malformed input outside the owned canvas.
        x = Math.max(-maximum, Math.min(document.width + maximum, x));
        y = Math.max(-maximum, Math.min(document.height + maximum, y));
        float radius = settings.diameter(pressure, tiltX, tiltY) / 2, pull = settings.pull(pressure);
        // A flat edge lies across the lean; a brush pen's long side runs along it, like a pencil's.
        float angle = settings.followsTilt()
                ? BrushDirection.resolve(tiltX,tiltY,settings.tool.water ? -90 : settings.angle,started?previousAngle:settings.angle) : settings.angle;
        float turn = started ? BrushDirection.delta(previousAngle,angle) : 0;
        // Tilt supplies heading only; pressure alone selects the head size.
        if (!started) {
            previousX = x; previousY = y; previousRadius = radius; previousAngle = angle; previousPull = pull;
            started = true;
        }
        double travel = Math.hypot(x - previousX, y - previousY)
                + Math.abs(Math.toRadians(turn)) * BrushStamp.extent(settings,Math.max(radius,previousRadius));
        if (settings.followsTilt())
            travel += Math.abs(radius-previousRadius);
        float spacing = settings.head == ToolSettings.Head.FLAT ? Math.max(.5f,settings.flatHeight(Math.min(radius,previousRadius)*2)*.2f)
                : Math.max(.5f, BrushStamp.minor(settings, Math.min(radius, previousRadius)) * .4f);
        int steps = Math.max(1, (int)Math.ceil(travel / spacing));
        float moved = (float)Math.hypot(x - previousX, y - previousY), stepDistance = moved / steps;
        if (BrushStamp.crescent(settings) && moved >= .5f) {
            // The head's short axis, rotated into the page; the crescent's hollow faces the way the pen moves.
            double heading = Math.toRadians(previousAngle + turn / 2);
            lead = -Math.sin(heading) * (x - previousX) + Math.cos(heading) * (y - previousY) >= 0 ? 1 : -1;
        }
        if (BrushStamp.crescent(settings) && lead == 0) {
            // A crescent waits for a direction instead of stamping a whole oval where the pen lands.
        } else if(batchFlat && steps>1) batch(x,y,radius,turn,steps);
        else for (int i = 1; i <= steps; i++) {
            float t = (float)i / steps;
            // A narrower, lighter stroke spends its paint more slowly.
            if (load != null) load.travel(stepDistance * (previousRadius + (radius - previousRadius) * t) * 2 / maximum);
            dab(previousX + (x - previousX) * t, previousY + (y - previousY) * t,
                    previousRadius + (radius - previousRadius) * t, previousAngle + turn * t, previousPull + (pull - previousPull) * t);
        }
        // Keep the head frame continuous across the 180-degree boundary.
        previousX = x; previousY = y; previousRadius = radius; previousAngle += turn; previousPull = pull;
    }
    /** Dry flat stamps are idempotent: union them before copying pixels into the document. */
    private void batch(float x,float y,float radius,float turn,int steps) {
        float distance=(float)Math.hypot(x-previousX,y-previousY);
        int chunk=Math.max(1,(int)Math.floor(64*steps/Math.max(1,distance)));
        for(int first=1;first<=steps;first+=chunk) {
            int last=Math.min(steps,first+chunk-1);
            float a=(float)first/steps,b=(float)last/steps;
            float x0=previousX+(x-previousX)*a,y0=previousY+(y-previousY)*a;
            float x1=previousX+(x-previousX)*b,y1=previousY+(y-previousY)*b;
            float extent=BrushStamp.extent(settings,Math.max(previousRadius+(radius-previousRadius)*a,
                    previousRadius+(radius-previousRadius)*b));
            int left=(int)Math.floor(Math.min(x0,x1)-extent)-1,top=(int)Math.floor(Math.min(y0,y1)-extent)-1;
            canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            float l=side,t=side,r=0,bottom=0;
            for(int i=first;i<=last;i++) {
                float f=(float)i/steps,cx=previousX+(x-previousX)*f,cy=previousY+(y-previousY)*f;
                float size=previousRadius+(radius-previousRadius)*f,e=BrushStamp.extent(settings,size);
                // Keep the individual stamp's local origin to retain Android's
                // subpixel and rotated-edge rounding from the original raster.
                int stampX=(int)Math.floor(cx-e)-1,stampY=(int)Math.floor(cy-e)-1;
                int saved=canvas.save();canvas.translate(stampX-left,stampY-top);
                BrushStamp.draw(canvas,paint,cx-stampX,cy-stampY,size,settings,previousAngle+turn*f,footprint,0);
                canvas.restoreToCount(saved);footprint.offset(stampX-left,stampY-top);
                l=Math.min(l,footprint.left);t=Math.min(t,footprint.top);
                r=Math.max(r,footprint.right);bottom=Math.max(bottom,footprint.bottom);
            }
            int cropX=Math.max(0,(int)Math.floor(l)-1),cropY=Math.max(0,(int)Math.floor(t)-1);
            int w=Math.min(side,(int)Math.ceil(r)+1)-cropX,h=Math.min(side,(int)Math.ceil(bottom)+1)-cropY;
            if(w>0 && h>0)applyMask(left+cropX,top+cropY,cropX,cropY,w,h,1);
        }
    }
    private void dab(float x, float y, float radius, float angle, float pull) {
        float extent = BrushStamp.extent(settings, radius);
        int left = (int)Math.floor(x - extent) - 1, top = (int)Math.floor(y - extent) - 1;
        int w = Math.min(side, (int)Math.ceil(x + extent) + 1 - left);
        int h = Math.min(side, (int)Math.ceil(y + extent) + 1 - top);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        BrushStamp.draw(canvas, paint, x - left, y - top, radius, settings, angle, footprint, lead);
        dabbed = true;
        int cropX=0,cropY=0;
        if (wet == null) {
            // Preserve the rasterizer's original origin, but copy and process
            // only the occupied rectangle instead of a large transparent square.
            // Wet paint keeps its original neighborhood wake-up bounds.
            cropX=Math.max(0,(int)Math.floor(footprint.left)-1);
            cropY=Math.max(0,(int)Math.floor(footprint.top)-1);
            int right=Math.min(w,(int)Math.ceil(footprint.right)+1);
            int bottom=Math.min(h,(int)Math.ceil(footprint.bottom)+1);
            w=right-cropX;h=bottom-cropY;
            if(w<=0||h<=0)return;
            left+=cropX;top+=cropY;
        }
        applyMask(left,top,cropX,cropY,w,h,pull);
    }
    private void applyMask(int left,int top,int cropX,int cropY,int w,int h,float pull) {
        mask.getPixels(pixels, 0, w, cropX, cropY, w, h);
        if(erasing || settings.tool==ToolSettings.Tool.ERASER) document.eraseMask(pixels,w,left,top,w,h);
        else if (wet != null && settings.tool.water) wet.waterMask(pixels, w, left, top, w, h, pull, settings.tool.flowing, settings.carry / 100f);
        else if (wet != null) {
            if (load == null) wet.paintMask(pixels, w, left, top, w, h, gray, transparent);
            else {
                load.pickUp(pixels, w, left, top, w, h);
                wet.paintMask(pixels, w, left, top, w, h, load.tone(), transparent, load.deposit());
            }
        }
        else if (load != null) load.dab(pixels, w, left, top, w, h, transparent);
        else if (transparent) document.transparentMask(pixels, w, left, top, w, h, gray);
        else if (settings.tool == ToolSettings.Tool.FLAT_WASH)
            document.flatWashMask(pixels, w, left, top, w, h, gray);
        else if (settings.tool == ToolSettings.Tool.WATERCOLOR)
            document.washMask(pixels, w, left, top, w, h, gray);
        else document.paintMask(pixels, w, left, top, w, h, gray);
    }
    @Override public boolean finish() {
        // A crescent tapped without moving still leaves its sliver.
        if (started && !dabbed) { lead = 1; dab(previousX, previousY, previousRadius, previousAngle, previousPull); }
        mask.recycle(); return wet == null ? document.finish() : wet.finishStroke(); }
}
