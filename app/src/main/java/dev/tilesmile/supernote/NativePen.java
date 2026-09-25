package dev.tilesmile.supernote;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapted from AnimInk's MIT-licensed PW bridge. See THIRD_PARTY_NOTICES.md. */
final class NativePen {
    interface Listener {
        void captured(Bitmap ownedCopy, Rect dirty);
        default void preview(Bitmap ownedCopy, Rect dirty) { ownedCopy.recycle(); }
        default Bitmap background() { return null; }
    }
    private final View host;
    private final Listener listener;
    private Object controller;
    private Object callback;
    private Object widthCallback;
    private Object touchCallback;
    private Object canvasHandler;
    private boolean grayCanvas;
    private final Paint canvasPaint = new Paint();
    private Canvas penCanvas;
    private DirectEink directEink;
    private Bitmap directBitmap;
    private Bitmap dotTile;
    private int directMaximum, directPointer = -1;
    private boolean directTilt;
    private int directUpdates;
    private long directNanos;
    private float previousX, previousY, previousRadius;
    private boolean drawing;
    private final Rect strokeRect = new Rect();
    private final Rect pendingGray = new Rect();
    private long lastGrayRefresh;
    private final AtomicBoolean previewQueued = new AtomicBoolean();
    private Method tiltX, tiltY;
    private int grayUpdates;
    private float minTilt, maxTilt;
    private int tiltSamples;
    String inputStatus = "Tilt: no samples";
    private int widthObject = -1;
    private volatile boolean enabled;
    private volatile int generation;
    private boolean documentInputBlocked;
    String status = "Not connected";

    NativePen(View host, Listener listener) { this.host = host; this.listener = listener; }

    /** The document app owns every tone, including black; suppress the firmware overlay. */
    boolean prepareDocumentCanvas() {
        try {
            connect();
            call("setPWEnabled", new Class<?>[]{boolean.class}, false);
            enabled = false;
            call("setPWBitmapInVisible", new Class<?>[]{boolean.class}, true);
            call("setLockUIWhenWriting", new Class<?>[]{boolean.class}, false);
            // rmAllWritableRects restores the firmware's default whole-View
            // region. setPWEnabled(false) alone does not gate its raw input.
            // A nonempty list containing an empty rectangle replaces that
            // default with no writable pixels, while Android stylus events
            // still reach our logical-document brush. Register it once per
            // controller: repeatedly adding regions accumulates records.
            if (!documentInputBlocked) {
                Object accepted = call("addWritableRects", new Class<?>[]{java.util.List.class},
                        Collections.singletonList(new Rect(0, 0, 0, 0)));
                if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("Cannot exclude firmware drawing");
                documentInputBlocked = true;
            }
            View.class.getMethod("setEinkUpdateMode", int.class, int.class).invoke(host, 3, 7);
            Log.i(ProbeActivity.TAG, "Document canvas: firmware writable area is empty");
            return true;
        } catch (Exception error) { fail("Document input setup", error); disable(); return false; }
    }

    boolean configure(int type, int size, int color, boolean nativeInk) {
        return configure(type, size, color, nativeInk, false);
    }

    boolean configure(int type, int size, int color, boolean nativeInk, boolean pressureBrush) {
        return configure(type, size, color, nativeInk, pressureBrush, false, false);
    }

    boolean configure(int type, int size, int color, boolean nativeInk, boolean pressureBrush,
                      boolean tiltBrush, boolean grayExperiment) {
        return configure(type,size,color,nativeInk,pressureBrush,tiltBrush,grayExperiment,false);
    }

    boolean configure(int type, int size, int color, boolean nativeInk, boolean pressureBrush,
                      boolean tiltBrush, boolean grayExperiment, boolean directGray) {
        return configure(type,size,color,nativeInk,pressureBrush,tiltBrush,grayExperiment,directGray,0);
    }

    boolean configure(int type, int size, int color, boolean nativeInk, boolean pressureBrush,
                      boolean tiltBrush, boolean grayExperiment, boolean directGray, int requestFlags) {
        return configure(type,size,color,nativeInk,pressureBrush,tiltBrush,grayExperiment,directGray,requestFlags,false);
    }

    boolean configure(int type, int size, int color, boolean nativeInk, boolean pressureBrush,
                      boolean tiltBrush, boolean grayExperiment, boolean directGray, int requestFlags, boolean dotGray) {
        try {
            connect();
            call("setPWEnabled", new Class<?>[]{boolean.class}, false);
            enabled = false;
            removeWidthCallback();
            removeCanvasHandler();
            releaseDirect();
            grayCanvas = nativeInk && pressureBrush && grayExperiment && Color.red(color) != 0;
            // The built-in draw-end listener expects PW's own point list, which
            // a custom handler does not create. We capture custom strokes ourselves.
            call("setDrawEventListener", new Class<?>[]{Class.forName(
                    "android.view.EinkPWInterface$PWDrawEventWithPoint")}, grayCanvas ? null : callback);
            drawing = false; pendingGray.setEmpty();
            call("setPenType", new Class<?>[]{int.class}, type);
            Object before = call("getPenStdWidth", new Class<?>[0]);
            call("setPenStdWidth", new Class<?>[]{float.class}, (float)size);
            Object actual = call("getPenStdWidth", new Class<?>[0]);
            Log.i(ProbeActivity.TAG, "Native width before=" + before + " requested=" + size + " readback=" + actual);
            if (pressureBrush && !grayCanvas) installWidthCallback(size, tiltBrush);
            Object actualColor = call("setPenColor", new Class<?>[]{int.class}, color);
            Log.i(ProbeActivity.TAG, "Native color requested=" + Integer.toHexString(color)
                    + " readback=" + Integer.toHexString(((Number)actualColor).intValue())
                    + " grayCanvas=" + grayCanvas + " tilt=" + tiltBrush);
            call("rmAllWritableRects", new Class<?>[0]);
            call("addWritableRects", new Class<?>[]{java.util.List.class},
                    Collections.singletonList(new Rect(0, 0, host.getWidth(), host.getHeight())));
            // Gray is presented by our View. Hide the binary PW overlay so it
            // cannot cover white overpainting or overwrite the View's shades.
            call("setPWBitmapInVisible", new Class<?>[]{boolean.class}, !nativeInk || grayCanvas);
            call("setLockUIWhenWriting", new Class<?>[]{boolean.class}, false);
            if (grayCanvas) View.class.getMethod("setEinkUpdateMode", int.class, int.class)
                    .invoke(host, 3, 7); // View-local A16 data / partial gray display.
            else View.class.getMethod("resetEinkUpdateMode").invoke(host);
            // Direct output must not announce strokes to the firmware pen
            // renderer: its surface pen-down/up handling can also update E Ink.
            call("setPWEnabled", new Class<?>[]{boolean.class}, nativeInk && !(grayCanvas && directGray));
            enabled = nativeInk;
            if (grayCanvas) {
                if (directGray) {
                    if (host.getDisplay().getRotation()!=android.view.Surface.ROTATION_0)
                        throw new IllegalStateException("Direct display requires portrait rotation 0");
                    int[] position=new int[2]; host.getLocationOnScreen(position);
                    directBitmap=listener.background();
                    if (directBitmap==null) {
                        directBitmap=Bitmap.createBitmap(host.getWidth(),host.getHeight(),Bitmap.Config.ARGB_8888);
                        directBitmap.eraseColor(Color.WHITE);
                    }
                    directEink=new DirectEink(position[0],position[1],directBitmap,requestFlags,7);
                    directMaximum=size; directTilt=tiltBrush; directPointer=-1;
                    canvasPaint.setColor(color); canvasPaint.setAntiAlias(false);
                    canvasPaint.setFilterBitmap(false);
                    if (dotGray) {
                        dotTile=DotGray.tile(Color.red(color));
                        // Anchor the opaque pattern to canvas coordinates so
                        // overlapping dabs and repeated strokes cannot darken it.
                        canvasPaint.setShader(new BitmapShader(dotTile,
                                Shader.TileMode.REPEAT,Shader.TileMode.REPEAT));
                    }
                    Log.i(ProbeActivity.TAG,"Direct gray region origin="+position[0]+","+position[1]
                            +" mode=7 requestFlags="+requestFlags+" dots="+dotGray);
                }
                if (!directGray) installCanvasHandler(size, color, tiltBrush);
            }
            Object bitmap = call("getPureWriteBitmap", new Class<?>[0]);
            Log.i(ProbeActivity.TAG, "PW setup-time bitmap ready=" + (bitmap != null));
            status = pressureBrush ? "Native pressure brush: 2–" + size + " px diameter"
                    : "PW width " + actual + " px; firmware pressure curve";
            if (tiltBrush && pressureBrush) status += " + tilt broadening";
            if (grayCanvas) status += directEink!=null ? " | DIRECT GRAY TEST mode=7 flags="+requestFlags
                    +(dotGray ? " dots" : " solid") : " | gray via View";
            return true;
        } catch (Exception e) {
            fail("PW setup", e); disable(); return false;
        }
    }

    private void installWidthCallback(int maximumDiameter, boolean tiltBrush) throws Exception {
        Class<?> callbackType = Class.forName("android.view.EinkPWInterface$OnUpdatePointWidthCallBack");
        Method pressure = Class.forName("android.view.PWInputPoint").getMethod("getPress");
        widthCallback = Proxy.newProxyInstance(host.getClass().getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return "TileSmilePressureWidth";
                    }
                    if (method.getName().equals("getPointWidth")) {
                        float p = ((Number)pressure.invoke(args[2])).floatValue();
                        float tx = ((Number)tiltX.invoke(args[2])).floatValue();
                        float ty = ((Number)tiltY.invoke(args[2])).floatValue();
                        return diameter(maximumDiameter, p, tx, ty, tiltBrush);
                    }
                    throw new UnsupportedOperationException(method.toString());
                });
        widthObject = ((Number)call("getDrawObjectType", new Class<?>[0])).intValue();
        call("registerPointWidthCallBack", new Class<?>[]{int.class, callbackType}, widthObject, widthCallback);
        Log.i(ProbeActivity.TAG, "Native pressure width callback registered for object=" + widthObject);
    }

    private void removeWidthCallback() throws Exception {
        if (widthObject < 0) return;
        call("unregisterPointWidthCallBack", new Class<?>[]{int.class}, widthObject);
        widthObject = -1; widthCallback = null;
    }

    private void connect() throws Exception {
        if (controller != null) return;
        Object candidate = View.class.getMethod("getPWInterFace").invoke(host);
        if (candidate == null) throw new IllegalStateException("No PW controller");
        controller = candidate;
        try {
            Class<?> event = Class.forName("android.view.EinkPWInterface$PWDrawEventWithPoint");
            callback = Proxy.newProxyInstance(host.getClass().getClassLoader(), new Class<?>[]{event},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                            return "TileSmilePWCallback";
                        }
                        if (canvasHandler == null && method.getName().equals("onTouchDrawEnd") && args != null
                                && args.length >= 2 && args[0] instanceof Bitmap && args[1] instanceof Rect) {
                            Bitmap source = (Bitmap)args[0];
                            Rect dirty = new Rect((Rect)args[1]);
                            int epoch = generation;
                            if (!source.isRecycled() && dirty.intersect(0, 0, source.getWidth(), source.getHeight())) {
                                // Copy before returning: never retain a firmware-owned mutable bitmap.
                                Bitmap copy = Bitmap.createBitmap(dirty.width(), dirty.height(), Bitmap.Config.ARGB_8888);
                                new android.graphics.Canvas(copy).drawBitmap(source, dirty,
                                        new Rect(0, 0, copy.getWidth(), copy.getHeight()), null);
                                Log.i(ProbeActivity.TAG, "PW callback dirty=" + dirty + " source="
                                        + source.getWidth() + "x" + source.getHeight());
                                host.post(() -> {
                                    if (epoch == generation) listener.captured(copy, dirty);
                                    else copy.recycle();
                                });
                            }
                        }
                        return null;
                    });
            call("setDrawEventListener", new Class<?>[]{event}, callback);
            installTouchObserver();
            call("setDrawObjectPaintAntiAlias", new Class<?>[]{int.class}, 0);
            call("setOneWordDelayMs", new Class<?>[]{int.class}, 0);
            call("disablePenErase", new Class<?>[]{boolean.class}, false);
            call("enableTouchDispatch", new Class<?>[]{int.class}, 1);
            Log.i(ProbeActivity.TAG, "PW controller=" + controller.getClass().getName());
        } catch (Exception e) {
            disable(); controller = null; callback = null; throw e;
        }
    }

    private void installTouchObserver() throws Exception {
        Class<?> listenerType = Class.forName("android.view.EinkPWInterface$PWTouchEventListener");
        Class<?> pointType = Class.forName("android.view.PWInputPoint");
        tiltX = pointType.getMethod("getTiltX"); tiltY = pointType.getMethod("getTiltY");
        touchCallback = Proxy.newProxyInstance(host.getClass().getClassLoader(), new Class<?>[]{listenerType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return "TileSmileNativeInput";
                    }
                    if (method.getName().equals("onPwTouchEvent") && args[0] instanceof MotionEvent) {
                        MotionEvent e = (MotionEvent)args[0];
                        observeTilt(e.getActionMasked(), e.getAxisValue(MotionEvent.AXIS_TILT), e.getOrientation());
                    }
                    return null;
                });
        call("setPWTouchEventListener", new Class<?>[]{listenerType}, touchCallback);
    }

    // This Supernote firmware supplies signed tilt-X/tilt-Y DEGREES in
    // AXIS_TILT/ORIENTATION, including in native PWInputPoint. It does not
    // follow stock Android's radians + azimuth encoding (physical log 0.3).
    static float inclination(float tx, float ty) {
        if (!Float.isFinite(tx) || !Float.isFinite(ty) || Math.abs(tx) > 90 || Math.abs(ty) > 90) return 0;
        double x = Math.tan(Math.toRadians(Math.min(89.9, Math.abs(tx))));
        double y = Math.tan(Math.toRadians(Math.min(89.9, Math.abs(ty))));
        return (float)Math.toDegrees(Math.atan(Math.hypot(x,y)));
    }

    static float diameter(int maximum, float pressure, float tx, float ty, boolean tilt) {
        if (!Float.isFinite(pressure)) pressure = 0;
        float p = Math.max(0, Math.min(1, (pressure - .05f) / .40f));
        float d = 2 + (maximum - 2) * p * p;
        if (tilt) d += (maximum - d) * .65f * Math.max(0, Math.min(1, (inclination(tx,ty)-20) / 45));
        return d;
    }

    private void observeTilt(int action, float tx, float ty) {
        if (action == MotionEvent.ACTION_DOWN) {
            minTilt = Float.POSITIVE_INFINITY; maxTilt = 0; tiltSamples = 0; grayUpdates = 0;
            Log.i(ProbeActivity.TAG, "Native input tiltXY degrees=" + tx + "," + ty + " canvas=" + grayCanvas);
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float angle = inclination(tx,ty);
            minTilt = Math.min(minTilt,angle); maxTilt = Math.max(maxTilt,angle); tiltSamples++;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            inputStatus = String.format(java.util.Locale.US, "Lean %.0f–%.0f° (%d native events), gray updates %d",
                    tiltSamples == 0 ? 0 : minTilt, maxTilt, tiltSamples, grayUpdates);
            Log.i(ProbeActivity.TAG, inputStatus);
        }
    }

    private void installCanvasHandler(int maximum, int color, boolean tilt) throws Exception {
        Class<?> handlerType = Class.forName("android.view.EinkPWInterface$PwEventHandler");
        canvasPaint.setColor(color); canvasPaint.setAntiAlias(false);
        canvasHandler = Proxy.newProxyInstance(host.getClass().getClassLoader(), new Class<?>[]{handlerType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        return "TileSmileGrayCanvas";
                    }
                    if (method.getName().equals("onTouchEvent")) {
                        try { drawCanvasPoint(args, maximum, tilt); }
                        catch (Exception error) { drawing = false; fail("Native canvas drawing", error); }
                    }
                    return null;
                });
        call("registerPwEventHandler", new Class<?>[]{handlerType}, canvasHandler);
    }

    private void drawCanvasPoint(Object[] args, int maximum, boolean tilt) throws Exception {
        if ((Integer)args[1]!=MotionEvent.TOOL_TYPE_STYLUS) return;
        drawCanvasPoint((Integer)args[2],((Number)args[3]).floatValue(),((Number)args[4]).floatValue(),
                ((Number)args[6]).floatValue(),((Number)args[7]).floatValue(),((Number)args[8]).floatValue(),
                (Boolean)args[9],maximum,tilt);
    }

    private synchronized void drawCanvasPoint(int action, float x, float y, float pressure,
            float tx, float ty, boolean stopped, int maximum, boolean tilt) throws Exception {
        if (!enabled) return;
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        observeTilt(action, tx, ty);
        if (action == MotionEvent.ACTION_CANCEL || stopped) {
            if (drawing) { drawing = false; captureCanvas(strokeRect, false); }
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            penCanvas = directBitmap!=null ? new Canvas(directBitmap) : (Canvas)call("getPwCanvas", new Class<?>[0]);
            if (penCanvas == null) throw new IllegalStateException("Native canvas unavailable");
            drawing = true; strokeRect.setEmpty(); pendingGray.setEmpty(); lastGrayRefresh = 0;
            directUpdates=0; directNanos=0;
            previousX = x; previousY = y;
            previousRadius = diameter(maximum,pressure,tx,ty,tilt) / 2;
        }
        if (!drawing) return;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float radius = diameter(maximum,pressure,tx,ty,tilt) / 2;
            int steps = Math.max(1, (int)Math.ceil(Math.hypot(x-previousX,y-previousY)
                    / Math.max(.5f, Math.min(radius,previousRadius)*.4f)));
            for (int i=1; i<=steps; i++) {
                float t=(float)i/steps;
                penCanvas.drawCircle(previousX+(x-previousX)*t, previousY+(y-previousY)*t,
                        previousRadius+(radius-previousRadius)*t, canvasPaint);
            }
            int margin = (int)Math.ceil(Math.max(radius,previousRadius))+2;
            pendingGray.union((int)Math.floor(Math.min(x,previousX))-margin,
                    (int)Math.floor(Math.min(y,previousY))-margin,
                    (int)Math.ceil(Math.max(x,previousX))+margin,
                    (int)Math.ceil(Math.max(y,previousY))+margin);
            strokeRect.union(pendingGray);
            previousX=x; previousY=y; previousRadius=radius;
            long now = SystemClock.uptimeMillis();
            if (directEink!=null && now-lastGrayRefresh>=8) {
                presentDirect(); lastGrayRefresh=now;
            } else if (directEink==null && now-lastGrayRefresh >= 32 && !previewQueued.get()) {
                // Copy only changed pixels, with at most one preview queued.
                // No full-canvas copy or Android rasterization per pen sample.
                captureCanvas(pendingGray, true);
                pendingGray.setEmpty(); lastGrayRefresh=now; grayUpdates++;
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            drawing = false;
            if (directEink!=null) {
                presentDirect();
                Log.i(ProbeActivity.TAG,"Direct gray posts="+directUpdates+" total submit ms="+directNanos/1_000_000.0);
            }
            captureCanvas(strokeRect, false);
        }
    }

    private void captureCanvas(Rect region, boolean preview) throws Exception {
        Bitmap source = directBitmap!=null ? directBitmap : (Bitmap)call("getPureWriteBitmap", new Class<?>[0]);
        if (source == null) throw new IllegalStateException("Native canvas bitmap unavailable");
        Rect dirty = new Rect(region);
        if (!dirty.intersect(0,0,source.getWidth(),source.getHeight())) return;
        Bitmap copy = Bitmap.createBitmap(dirty.width(),dirty.height(),Bitmap.Config.ARGB_8888);
        new Canvas(copy).drawBitmap(source,dirty,new Rect(0,0,copy.getWidth(),copy.getHeight()),null);
        int epoch = generation;
        if (preview) previewQueued.set(true);
        boolean posted = host.post(() -> {
            try {
                if (epoch != generation) copy.recycle();
                else if (preview) listener.preview(copy,dirty);
                else listener.captured(copy,dirty);
            } finally { if (preview) previewQueued.set(false); }
        });
        if (!posted) { copy.recycle(); if (preview) previewQueued.set(false); }
    }

    boolean usesGrayCanvas() { return grayCanvas; }
    boolean usesDirectDisplay() { return directEink!=null; }

    private void presentDirect() {
        if (!pendingGray.intersect(0,0,directBitmap.getWidth(),directBitmap.getHeight())) return;
        long start=System.nanoTime();
        int result=directEink.present(directBitmap,pendingGray);
        directNanos+=System.nanoTime()-start;
        if (result>=0) pendingGray.setEmpty();
        if (result>0) { directUpdates++; grayUpdates++; }
    }

    private synchronized void releaseDirect() {
        directPointer=-1; drawing=false;
        canvasPaint.setShader(null);
        if (dotTile!=null) { dotTile.recycle(); dotTile=null; }
        if (directEink!=null) { directEink.close(); directEink=null; }
        if (directBitmap!=null) { directBitmap.recycle(); directBitmap=null; }
    }

    private void removeCanvasHandler() throws Exception {
        if (canvasHandler == null) return;
        call("unRegisterPwEventHandler", new Class<?>[]{Class.forName(
                "android.view.EinkPWInterface$PwEventHandler")}, canvasHandler);
        canvasHandler = null; penCanvas = null; drawing = false;
    }

    void forward(MotionEvent event) {
        if (!enabled) return;
        try {
            if (usesDirectDisplay()) forwardDirect(event);
            else call("sendBackEvent", new Class<?>[]{MotionEvent.class, boolean.class}, event, false);
        }
        catch (Exception e) { fail("PW event forwarding", e); disable(); }
    }

    private void forwardDirect(MotionEvent event) throws Exception {
        int action=event.getActionMasked(), index=event.getActionIndex();
        if (action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
            if (directPointer!=-1 || event.getToolType(index)!=MotionEvent.TOOL_TYPE_STYLUS) return;
            directPointer=event.getPointerId(index); action=MotionEvent.ACTION_DOWN;
        } else if (action==MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerId(index)!=directPointer) return;
            action=MotionEvent.ACTION_UP;
        }
        int p=event.findPointerIndex(directPointer);
        if (p<0) return;
        if (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
            for (int h=0;h<event.getHistorySize();h++)
                drawCanvasPoint(MotionEvent.ACTION_MOVE,event.getHistoricalX(p,h),event.getHistoricalY(p,h),
                        event.getHistoricalPressure(p,h),event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,p,h),
                        event.getHistoricalOrientation(p,h),false,directMaximum,directTilt);
        }
        drawCanvasPoint(action,event.getX(p),event.getY(p),event.getPressure(p),
                event.getAxisValue(MotionEvent.AXIS_TILT,p),event.getOrientation(p),false,directMaximum,directTilt);
        if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) directPointer=-1;
    }
    boolean isWriting() {
        if (usesDirectDisplay()) return drawing;
        try { return enabled && Boolean.TRUE.equals(call("isCurrentWriting", new Class<?>[0])); }
        catch (Exception e) { fail("PW writing state", e); return false; }
    }
    void clear() {
        generation++;
        if (controller == null) return;
        try { call("clearContent", new Class<?>[]{Rect.class, boolean.class, boolean.class}, null, true, true); }
        catch (Exception e) { fail("PW clear", e); }
    }
    void disable() {
        enabled = false;
        releaseDirect();
        if (controller == null) return;
        try {
            call("setPWEnabled", new Class<?>[]{boolean.class}, false);
            call("setPWBitmapInVisible", new Class<?>[]{boolean.class}, true);
            View.class.getMethod("resetEinkUpdateMode").invoke(host);
        } catch (Exception e) { fail("PW disable", e); }
    }
    void close() {
        generation++; disable();
        if (controller == null) return;
        try {
            call("setPWTouchEventListener", new Class<?>[]{Class.forName(
                    "android.view.EinkPWInterface$PWTouchEventListener")}, (Object)null);
            touchCallback = null;
        } catch (Exception e) { fail("PW input observer release", e); }
        try { removeCanvasHandler(); }
        catch (Exception e) { fail("PW canvas handler release", e); }
        try { removeWidthCallback(); }
        catch (Exception e) { fail("PW width callback release", e); }
        try { call("setDrawEventListener", new Class<?>[]{Class.forName(
                "android.view.EinkPWInterface$PWDrawEventWithPoint")}, (Object)null); }
        catch (Exception e) { fail("PW release", e); }
        controller = null; callback = null; documentInputBlocked = false;
    }
    private Object call(String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = controller.getClass().getMethod(name, signature);
        return method.invoke(controller, args);
    }
    private void fail(String operation, Exception e) {
        status = operation + " failed: " + e;
        Log.e(ProbeActivity.TAG, status, e);
    }
}
