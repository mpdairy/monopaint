package dev.tilesmile.supernote;

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
    private final boolean transparent;
    private final int maximum, gray, side;
    private final ToolSettings settings;
    private final Bitmap mask;
    private final Canvas canvas;
    private final Paint paint = new Paint();
    private final int[] pixels;
    private final RectF footprint = new RectF();
    private float previousX, previousY, previousRadius, previousAngle;
    private float previousLean;
    private float previousContactPressure;
    private boolean started;

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
        this(document, settings, gray, wet, false, false);
    }
    PressureStroke(ToneDocument document, ToolSettings settings, int gray, WetWatercolor wet, boolean transparent) {
        this(document, settings, gray, wet, transparent, true);
    }
    private PressureStroke(ToneDocument document, ToolSettings settings, int gray, WetWatercolor wet,
                           boolean transparent, boolean canvasControls) {
        this.transparent = transparent;
        this.document = document; this.settings = canvasControls && settings.isBrush() ? settings.asBrush() : settings; this.maximum = settings.maximum; this.gray = gray;
        this.wet = canvasControls ? wet : settings.tool == ToolSettings.Tool.WET_WATERCOLOR
                ? (wet == null ? new WetWatercolor(document) : wet) : null;
        side = (int)Math.ceil(BrushStamp.extent(settings, maximum / 2f) * 2) + 4;
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
        float radius = settings.diameter(pressure) / 2;
        // Diameter reaches its legacy maximum at .45, but bristles must keep
        // compressing above that pressure, through the full input range.
        float contactPressure=Math.max(0,Math.min(1,(pressure-.05f)/.95f));
        float angle = settings.tilt && settings.head != ToolSettings.Head.ROUND
                ? BrushDirection.resolve(tiltX,tiltY,settings.angle,started?previousAngle:settings.angle) : settings.angle;
        float turn = started ? BrushDirection.delta(previousAngle,angle) : 0;
        float continuousAngle = started ? previousAngle + turn : angle;
        float lean = settings.tilt && settings.head != ToolSettings.Head.ROUND
                ? BrushDirection.contactLean(tiltX,tiltY,continuousAngle,previousLean) : 0;
        if (settings.tilt && settings.head != ToolSettings.Head.ROUND) {
            // A laid-over broad brush needs much less force to expose its belly.
            // Lean exposes the brush's width even at barely touching pressure.
            // Pressure primarily lays down more length, rather than resizing it.
            radius += (maximum/2f-radius)*Math.abs(lean);
            contactPressure = leanPressure(contactPressure,lean);
        }
        if (!started) {
            previousX = x; previousY = y; previousRadius = radius; previousAngle = angle;
            previousContactPressure = contactPressure; previousLean = lean; started = true;
        }
        double travel = Math.hypot(x - previousX, y - previousY)
                + (Math.abs(Math.toRadians(turn)) + Math.abs(lean-previousLean))
                    * BrushStamp.extent(settings,Math.max(radius,previousRadius));
        if (settings.tilt && settings.head != ToolSettings.Head.ROUND)
            travel += Math.abs(radius-previousRadius) + Math.abs(contactPressure-previousContactPressure)*2*Math.max(radius,previousRadius);
        float spacing = Math.max(.5f, Math.min(radius, previousRadius) * .4f * settings.headAspectRatio());
        int steps = Math.max(1, (int)Math.ceil(travel / spacing));
        for (int i = 1; i <= steps; i++) {
            float t = (float)i / steps;
            dab(previousX + (x - previousX) * t, previousY + (y - previousY) * t,
                    previousRadius + (radius - previousRadius) * t, previousAngle + turn * t,
                    previousLean+(lean-previousLean)*t,previousContactPressure+(contactPressure-previousContactPressure)*t);
        }
        previousLean = lean;
        previousContactPressure = contactPressure;
        // Keep the head frame continuous across the 180-degree boundary.
        previousX = x; previousY = y; previousRadius = radius; previousAngle += turn;
    }
    private void dab(float x, float y, float radius, float angle, float lean, float pressure) {
        float extent = BrushStamp.extent(settings, radius);
        int left = (int)Math.floor(x - extent) - 1, top = (int)Math.floor(y - extent) - 1;
        int w = Math.min(side, (int)Math.ceil(x + extent) + 1 - left);
        int h = Math.min(side, (int)Math.ceil(y + extent) + 1 - top);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        BrushStamp.draw(canvas, paint, x - left, y - top, radius, settings, angle, lean, pressure, footprint);
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
        mask.getPixels(pixels, 0, w, cropX, cropY, w, h);
        if (wet != null) wet.paintMask(pixels, w, left, top, w, h, gray, transparent);
        else if (transparent) document.transparentMask(pixels, w, left, top, w, h, gray);
        else if (settings.tool == ToolSettings.Tool.FLAT_WASH)
            document.flatWashMask(pixels, w, left, top, w, h, gray);
        else if (settings.tool == ToolSettings.Tool.WATERCOLOR)
            document.washMask(pixels, w, left, top, w, h, gray);
        else document.paintMask(pixels, w, left, top, w, h, gray);
    }
    private static float leanPressure(float pressure, float lean) {
        float gain = 1 + 8*lean*lean;
        return gain*pressure / (1 + (gain-1)*pressure);
    }
    @Override public boolean finish() { mask.recycle(); return wet == null ? document.finish() : wet.finishStroke(); }
}
