package dev.tilesmile.supernote;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/** Native black ink plus an experimental View presentation path for gray paint. */
public final class ProbeActivity extends Activity {
    static final String TAG = "SupernoteTileSmile";
    private NativePen ink;
    private Pad pad;
    private TextView status;
    private boolean nativeMode = true;
    private boolean ready;
    private boolean resumed;
    private int penType = 2;
    private boolean pressureBrush = true;
    private boolean tiltBrush = false;
    private boolean grayExperiment = true;
    private boolean directGray = true;
    private int directRequestFlags;
    private boolean dotGray;
    private int width = 64;
    private int gray;
    private int nativeCallbacks;
    private boolean forwarding;
    private String lastStroke = "No pen samples yet";
    private final ArrayList<Button> swatches = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        status = new TextView(this);
        status.setTextSize(14);
        root.addView(status);
        LinearLayout controls = row(root);
        button(controls, "Native only", v -> {
            nativeMode = !nativeMode;
            ((Button)v).setText(nativeMode ? "Native only" : "Bitmap live");
            pad.present(); configure();
        });
        button(controls, "Paint brush", v -> {
            if (pressureBrush) { pressureBrush = false; penType = 2; }
            else if (penType == 2) penType = 1;
            else { pressureBrush = true; penType = 2; }
            ((Button)v).setText(pressureBrush ? "Paint brush" : penType == 1 ? "Pencil" : "Ink");
            configure();
        });
        button(controls, "Width 64", v -> {
            width = width == 4 ? 16 : width == 16 ? 64 : 4;
            ((Button)v).setText("Width " + width); configure();
        });
        LinearLayout actions = row(root);
        button(actions, "Show bitmap / stats", v -> { pad.present(); report(); });
        button(actions, "Clear", v -> { pad.erase(); configure(); });
        button(actions, "Retry", v -> configure());
        LinearLayout experiments = row(root);
        button(experiments, "Tilt: off", v -> {
            tiltBrush = !tiltBrush;
            ((Button)v).setText(tiltBrush ? "Tilt: broad" : "Tilt: off"); configure();
        });
        button(experiments, "Gray: direct test", v -> {
            directGray = !directGray;
            ((Button)v).setText(directGray ? "Gray: direct test" : "Gray: View fallback"); configure();
        });
        button(experiments, "Update: bitmap", v -> {
            directRequestFlags = directRequestFlags == 0 ? 1 : 0;
            ((Button)v).setText(directRequestFlags == 0 ? "Update: bitmap" : "Update: previous");
            configure();
        });
        button(experiments, "Shade: solid", v -> {
            dotGray = !dotGray;
            ((Button)v).setText(dotGray ? "Shade: dots" : "Shade: solid");
            configure();
        });
        for (int r = 0; r < 2; r++) {
            LinearLayout palette = row(root);
            for (int c = 0; c < 8; c++) {
                final int value = GrayPalette.VALUES[r * 8 + c];
                Button b = button(palette, Integer.toString(value), v -> select(value));
                b.setTextSize(12);
                b.setPadding(0, 0, 0, 0);
                b.setMinWidth(0); b.setMinimumWidth(0);
                b.setBackgroundColor(Color.rgb(value, value, value));
                b.setTextColor(value < 128 ? Color.WHITE : Color.BLACK);
                swatches.add(b);
            }
        }
        pad = new Pad();
        ink = new NativePen(pad, new NativePen.Listener() {
            @Override public Bitmap background() {
                return pad.backing.copy(Bitmap.Config.ARGB_8888,true);
            }
            @Override public void captured(Bitmap copy, Rect dirty) {
                try {
                    if (!nativeMode || pad.raster == null) return;
                    pad.raster.drawBitmap(copy, dirty.left, dirty.top, null);
                    nativeCallbacks++;
                    // Direct pixels are already on the panel. Keep the retained
                    // bitmap current without issuing a second display refresh.
                    if (ink.usesGrayCanvas()) pad.showPatch(copy, dirty, !ink.usesDirectDisplay());
                    Log.i(TAG, "Captured native stroke #" + nativeCallbacks + " dirty=" + dirty);
                } finally { copy.recycle(); }
            }
            @Override public void preview(Bitmap copy, Rect dirty) {
                try {
                    if (!nativeMode || pad.raster == null) return;
                    pad.raster.drawBitmap(copy, dirty.left, dirty.top, null);
                    pad.showPatch(copy, dirty);
                } finally { copy.recycle(); }
            }
        });
        root.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        pad.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (l != ol || t != ot || r != or || b != ob) configure();
        });
        Log.i(TAG, "device=" + android.os.Build.MODEL + " fingerprint=" + android.os.Build.FINGERPRINT);
        report();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        parent.addView(row); return row;
    }
    private Button button(LinearLayout row, String label, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setOnClickListener(action);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); return b;
    }
    private void select(int value) {
        gray = value;
        for (int i = 0; i < swatches.size(); i++) {
            swatches.get(i).setText((GrayPalette.VALUES[i] == value ? "•" : "") + GrayPalette.VALUES[i]);
        }
        configure();
    }
    private void configure() {
        if (pad == null || !resumed || !hasWindowFocus() || pad.getWidth() == 0) return;
        pad.present();
        ready = ink.configure(penType, width, Color.rgb(gray, gray, gray), nativeMode, pressureBrush,
                tiltBrush, grayExperiment,directGray,directRequestFlags,dotGray);
        Log.i(TAG, "mode=" + (nativeMode ? "native-only" : "bitmap") + " pen=" + penType
                + " widthPx=" + width + " gray=" + gray + " pressureBrush=" + pressureBrush);
        report();
    }
    private void report() {
        status.setText((nativeMode ? "NATIVE ONLY" : "ANDROID BITMAP") + " | gray " + gray
                + " | native callbacks " + nativeCallbacks + "\n" + ink.status + "\n" + lastStroke + " | " + ink.inputStatus);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (pad != null) pad.post(this::configure); }
    @Override protected void onPause() {
        resumed = false; ready = false;
        if (pad != null) { pad.finish(true); pad.present(); }
        ink.disable(); super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (ink == null) return;
        if (focus) pad.post(this::configure);
        else { ready = false; pad.finish(true); ink.disable(); }
    }
    @Override protected void onDestroy() { ink.close(); super.onDestroy(); }

    private final class Pad extends View {
        private Bitmap backing;
        private Bitmap shown;
        private int drawCount;
        private Canvas raster;
        private final Paint brush = new Paint();
        private int pointer = -1;
        private float previousX, previousY, previousRadius;
        private int samples;
        private float minPressure, maxPressure;
        private long firstTime, lastTime;
        Pad() { super(ProbeActivity.this); }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            if (w <= 0 || h <= 0) return;
            Bitmap old = backing;
            backing = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            backing.eraseColor(Color.WHITE); raster = new Canvas(backing);
            if (old != null) { raster.drawBitmap(old, 0, 0, null); old.recycle(); }
            if (shown != null) shown.recycle();
            shown = backing.copy(Bitmap.Config.ARGB_8888, true);
        }
        @Override protected void onDraw(Canvas c) {
            drawCount++;
            super.onDraw(c);
            Bitmap display = nativeMode ? shown : backing;
            if (display != null) c.drawBitmap(display, 0, 0, null);
        }
        void present() {
            if (ink != null) ink.clear();
            if (shown != null) new Canvas(shown).drawBitmap(backing, 0, 0, null);
            invalidate();
        }
        void showPatch(Bitmap copy, Rect dirty) {
            showPatch(copy,dirty,true);
        }
        void showPatch(Bitmap copy, Rect dirty, boolean redraw) {
            if (shown == null) return;
            new Canvas(shown).drawBitmap(copy, dirty.left, dirty.top, null);
            if (redraw) invalidate(dirty);
        }
        void erase() {
            finish(true);
            if (backing != null) backing.eraseColor(Color.WHITE);
            lastStroke = "Canvas cleared"; present();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (backing == null) return true;
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                    && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS && pointer == -1) {
                pointer = event.getPointerId(index); samples = 0;
                minPressure = Float.POSITIVE_INFINITY; maxPressure = 0;
                brush.setColor(Color.rgb(gray, gray, gray));
                forwarding = nativeMode && ready && !ink.isWriting();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            int p = event.findPointerIndex(pointer);
            if (p >= 0 && nativeMode && ready && forwarding) ink.forward(event);
            if (p >= 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP)) {
                for (int h = 0; h < event.getHistorySize(); h++) sample(event.getHistoricalX(p,h),
                        event.getHistoricalY(p,h), event.getHistoricalPressure(p,h), event.getHistoricalEventTime(h));
                sample(event.getX(p), event.getY(p), event.getPressure(p), event.getEventTime());
                if (!nativeMode) {
                    postInvalidateOnAnimation();
                }
            }
            if (action == MotionEvent.ACTION_CANCEL) finish(true);
            else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(index) == pointer) finish(false);
            return true;
        }
        private void sample(float x, float y, float pressure, long time) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
            minPressure = Math.min(minPressure, pressure); maxPressure = Math.max(maxPressure, pressure);
            float radius = (1 + (width - 1)
                    * (float)Math.pow(Math.max(0, Math.min(1, pressure)), 1.4)) / 2;
            if (samples == 0) { firstTime = time; previousX = x; previousY = y; previousRadius = radius; }
            int steps = Math.max(1, (int)Math.ceil(Math.hypot(x-previousX, y-previousY)
                    / Math.max(.5f, Math.min(radius, previousRadius) * .4f)));
            for (int s = 1; !nativeMode && s <= steps; s++) {
                float t = (float)s / steps;
                raster.drawCircle(previousX + (x-previousX)*t, previousY + (y-previousY)*t,
                        previousRadius + (radius-previousRadius)*t, brush);
            }
            previousX = x; previousY = y; previousRadius = radius; samples++; lastTime = time;
        }
        void finish(boolean cancelled) {
            if (pointer == -1) return;
            pointer = -1;
            lastStroke = String.format(Locale.US, "%s%d samples, pressure %.3f–%.3f, %d ms",
                    cancelled ? "Interrupted: " : "", samples, minPressure, maxPressure, lastTime-firstTime);
            Log.i(TAG, lastStroke + " nativeMode=" + nativeMode + " transportReady=" + ready);
            // No UI update: even a stats label can disturb the native E Ink overlay.
        }
    }
}
