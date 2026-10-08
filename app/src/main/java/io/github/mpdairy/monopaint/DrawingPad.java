package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

/**
 * The canvas. It renders the current page into a page-sized raster ({@link #display}),
 * or a screen-sized one while zoomed ({@link #viewportBitmap}), and presents changed
 * pixels straight to the e-ink panel through {@link DirectEink} when available.
 *
 * <p>Pen input runs one of these gestures at a time:
 * <ul>
 * <li>a {@link DrawingStroke} for ordinary tools ({@link ToolStrokes});</li>
 * <li>a live {@link ShapePreview} for Shapes, committed at pen-up;</li>
 * <li>a fill: tap for a flood fill, or drag a gradient axis and then pick its second color;</li>
 * <li>eyedropper sampling while the color picker is active.</li>
 * </ul>
 * Two fingers pinch and pan when navigation is unlocked. Wet paint keeps blending in
 * idle time between input events.
 */
@SuppressLint("ViewConstructor")
final class DrawingPad extends View {
    private final PaintActivity app;
    /** See {@link Device#landscapeTilt}. */
    private final boolean landscapeTilt;
    ToneDocument document;
    private Bitmap display;
    private ViewportBitmap viewportBitmap;
    final CanvasViewport viewport = new CanvasViewport();
    final Matrix pageToView = new Matrix();
    private final Matrix viewToPage = new Matrix();
    private final float[] samplePoint = new float[2];
    private boolean unscaledPage = true;
    private int pageRotation = Surface.ROTATION_0;
    private int[] renderPixels = new int[0];
    /** Changed presented pixels awaiting presentation, as separate patches. */
    private final DirtyRegions pending = new DirtyRegions(32);
    private final Rect presenting = new Rect();
    private DirectEink direct;
    /** Nomad's fast black/white session for moving previews; see {@link #beginFastPreview}. */
    private DirectEink previewDirect;
    /** Pixels {@link #previewDirect} showed, re-presented through {@link #direct} when the preview ends. */
    private final Rect previewArea = new Rect();
    /** Panel area of closed controls' fast ink, presented again through {@link #direct}; see {@link #eraseFastInk}. */
    private final RectF fastInk = new RectF();
    private int fastInkRefreshes;
    private final NativePen input;
    private int pointer = -1, retries;
    private long lastPresent;
    private boolean fallbackNotice;
    private int drawCount;
    private int pagePresentCount;
    private final Runnable retry = () -> flush(true);

    // Strokes
    private DrawingStroke stroke;

    // Wet paint
    WetWatercolor wet;
    private boolean wetChanged, wetScheduled;
    private final WetWorkBudget wetBudget = new WetWorkBudget();
    private final android.os.MessageQueue wetQueue = android.os.Looper.getMainLooper().getQueue();
    private final android.os.MessageQueue.IdleHandler wetIdle = () -> { advanceWet(); return false; };
    private final Runnable wetStep = () -> wetQueue.addIdleHandler(wetIdle);
    private long lastWetComputeNanos, lastWetRenderNanos, lastWetPresentNanos, wetMaxSliceNanos;
    private int wetSliceCount;

    // Shapes
    private ShapePreview shapeStroke;
    private float shapeX, shapeY;

    // Moving previews: a shape or the gradient guide, drawn once per display frame
    private boolean previewFrameScheduled;
    private long lastPreviewFrame;
    private int previewFrameCount;
    private final Runnable previewFrame = this::drawPreviewFrame;

    // Fills, gradients and the eyedropper
    FloodFill fill;
    private FloodFill gradientFill;
    boolean fillGesture, gradientWaiting, gradientCommit;
    private boolean gradientReady;
    private int gradientShade, gradientPassShade, pickPointer = -1;
    /** The composite before a gradient preview, so the eyedropper never samples the preview itself. */
    private byte[] gradientSample;
    private float fillStartX, fillStartY, fillEndX, fillEndY;
    private int fillShade, fillOriginalGray, fillTolerance;
    private ToolSettings.Gradient fillGradient = ToolSettings.Gradient.LINEAR;
    private final Paint gradientGuide = new Paint();
    /** Presented pixels the gradient guide is drawn over; empty when it is not shown. */
    private final java.util.ArrayList<Rect> guideTiles = new java.util.ArrayList<>();
    /** The presented pixels under {@link #guideTiles}, in order, restored to erase the guide. */
    private int[] guideBackground = new int[0];
    private final Runnable gradientStep = this::advanceGradient;
    private final Runnable fillStep = this::advanceFill;

    // Pinch and pan
    private int fingerA = -1, fingerB = -1;
    boolean navigating, touchBlocked;
    private boolean navigationFrameScheduled;
    private long lastNavigationFrame, lastNavigationRasterNanos, lastNavigationPresentNanos;
    private int navigationFrameCount;
    private final Runnable navigationFrame = this::drawNavigationFrame;
    private float fingerX, fingerY, fingerSpan;
    /** Fingers are ignored until this time after the pen was last near the screen. */
    private long penGuardUntil;

    DrawingPad(PaintActivity app) {
        super(app); this.app = app; landscapeTilt = app.device.landscapeTilt;
        setContentDescription("Painting canvas; use the pen to paint. Unlock Zoom to pinch and pan with two fingers.");
        input = new NativePen(this, (bitmap, region) -> bitmap.recycle());
    }
    private int dp(float value) { return app.dp(value); }
    private static boolean pointerUp(MotionEvent event, int pointer) {
        int action = event.getActionMasked();
        return (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(event.getActionIndex()) == pointer;
    }
    /** Page size for a new blank page that fills this view in the current orientation. */
    private ToneDocument blankPage() {
        return new ToneDocument(app.landscape ? getHeight() : getWidth(), app.landscape ? getWidth() : getHeight());
    }

    // Pages and rasters

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        finishStroke(); disconnectDisplay();
        if (w <= 0 || h <= 0) return;
        if (!app.loading && document == null) {
            document = blankPage();
            app.book = new DrawingBook(document); app.updatePages();
        }
        ensureDisplay(); updateViewport();
        renderAll();
    }
    private void ensureDisplay() {
        int width = document != null ? document.width : app.landscape ? getHeight() : getWidth();
        int height = document != null ? document.height : app.landscape ? getWidth() : getHeight();
        if (width <= 0 || height <= 0) return;
        if (display != null && display.getWidth() == width && display.getHeight() == height) return;
        if (display != null) display.recycle();
        display = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        display.setHasAlpha(false);
    }
    /** Recomputes the page-to-view transform for the app rotation and zoom. */
    void updateViewport() {
        if (display == null || getWidth() <= 0 || getHeight() <= 0) return;
        int width = display.getWidth(), height = display.getHeight();
        if (pageRotation != app.appRotation) viewport.reset();
        pageRotation = app.appRotation;
        boolean swapped = pageRotation == Surface.ROTATION_90 || pageRotation == Surface.ROTATION_270;
        viewport.configure(getWidth(), getHeight(), swapped ? height : width, swapped ? width : height);
        float scale = viewport.scale();
        place(pageToView, scale, viewport.x, viewport.y);
        pageToView.invert(viewToPage);
        unscaledPage = pageRotation == Surface.ROTATION_0 && pageToView.isIdentity();
        boolean wasScaled = viewportBitmap != null;
        // Keep one screen-sized raster and presenter throughout a pinch, including
        // its fitted endpoints. Changing format on each crossing stalls the gesture.
        boolean scaled = navigating || viewport.zoom != 1 || scale != 1 || viewport.x != 0 || viewport.y != 0;
        if (viewportBitmap != null && (!scaled || viewportBitmap.bitmap.getWidth() != getWidth()
                || viewportBitmap.bitmap.getHeight() != getHeight())) { viewportBitmap.close(); viewportBitmap = null; }
        if (scaled && viewportBitmap == null) viewportBitmap = new ViewportBitmap(getWidth(), getHeight());
        if (wasScaled != scaled) renderAll();
        else if (viewportBitmap != null) { viewportBitmap.update(display, pageToView, null); redrawGradientGuide(); }
        pending.clear(); app.toolbar.refreshZoom();
    }
    private void place(Matrix transform, float scale, float x, float y) {
        int width = display.getWidth(), height = display.getHeight();
        transform.reset();
        // Cancel our own UI rotation for the artwork; Android stays in portrait.
        if (pageRotation == Surface.ROTATION_90) { transform.setRotate(-90); transform.postTranslate(0, width); }
        else if (pageRotation == Surface.ROTATION_180) { transform.setRotate(180); transform.postTranslate(width, height); }
        else if (pageRotation == Surface.ROTATION_270) { transform.setRotate(90); transform.postTranslate(height, 0); }
        transform.postScale(scale, scale);
        transform.postTranslate(x, y);
    }
    /** The page at 100%, where its artwork sat when the page began; see {@link #actualSize}. */
    Matrix homeToView() {
        Matrix home = new Matrix();
        place(home, 1, viewport.homeX(app.prefs.toolboxRight()), viewport.homeY());
        return home;
    }
    private void viewportChanged() {
        disconnectDisplay(); updateViewport(); invalidate();
        if (!navigating) post(this::connectDisplay);
    }
    void zoomBy(float factor) {
        viewport.gesture(factor, getWidth()/2f, getHeight()/2f, getWidth()/2f, getHeight()/2f);
        viewportChanged();
    }
    void fitPage() { endNavigation(); viewport.reset(); viewportChanged(); }
    /** Reset scale and pan together, returning the artwork to where the page began. */
    void actualSize() { endNavigation(); viewport.actualSize(app.prefs.toolboxRight()); viewportChanged(); }
    /** Starts a new book from this page, or from a blank page when null. */
    void replace(ToneDocument replacement) {
        suspend();
        viewport.reset();
        document = replacement;
        if (document == null && getWidth() > 0 && getHeight() > 0) document = blankPage();
        app.book = document == null ? null : new DrawingBook(document); app.updatePages();
        ensureDisplay(); updateViewport();
        renderAll(); invalidate(); post(this::connectDisplay); post(app.fullscreen::pageChanged);
    }
    /** Shows another page of the current book, presenting it directly when possible. */
    void showPage(ToneDocument page) {
        suspend();
        PageTurnDisplay turn = null;
        // Same-sized fitted pages share a transform. Snapshot the old panel
        // background before rendering the next page, even with Pages open.
        if (document != null && display != null && page.width == document.width && page.height == document.height
                && viewport.zoom == 1 && app.resumed && hasWindowFocus() && !isLayoutRequested()
                && !app.root.isLayoutRequested() && !app.previewFrame.isLayoutRequested()
                && app.rotateButton.getVisibility() != View.VISIBLE) {
            try { turn = new PageTurnDisplay(this, presented(), viewportBitmap == null ? pageToView : new Matrix(), app.pagePanel); }
            catch (RuntimeException | LinkageError error) { Log.w(ProbeActivity.TAG, "Page update uses Android display", error); }
        }
        boolean presented = false;
        try {
            document = page; viewport.reset(); ensureDisplay(); updateViewport(); renderAll();
            if (turn != null) {
                try { presented = turn.present(presented()); }
                catch (RuntimeException error) { Log.w(ProbeActivity.TAG, "Page update uses Android display", error); }
            }
        } finally { if (turn != null) turn.close(); }
        if (presented) {
            pagePresentCount++;
            app.selectionFeedback.retainForNextDraw(this, new Rect(0, 0, getWidth(), getHeight()));
        } else invalidate();
        post(this::connectDisplay);
    }
    /** The raster shown on screen: the page itself, or the zoomed view of it. */
    private Bitmap presented() { return viewportBitmap == null ? display : viewportBitmap.bitmap; }
    private void renderAll() {
        if (display == null) return;
        display.eraseColor(Color.WHITE);
        if (document != null) {
            ViewportBitmap.compose(document, display, viewportBitmap == null);
            document.clearDirty();
        }
        pending.clear();
        if (viewportBitmap != null) viewportBitmap.update(display, pageToView, null);
        redrawGradientGuide();
    }
    /** Rebuild size-dependent display resources after canvas expansion or its undo. */
    void canvasResized() {
        disconnectDisplay(); ensureDisplay(); updateViewport(); renderAll(); invalidate(); post(this::connectDisplay);
    }
    boolean blocksFullscreenGesture() {
        return pointer!=-1 || pickPointer!=-1 || fillGesture || hasGradient()
                || SystemClock.uptimeMillis()<penGuardUntil;
    }
    /** Re-renders the document's changed area and queues it for presentation. */
    void renderDirty() {
        if (document == null || display == null) return;
        int[] bounds = document.dirty();
        if (bounds == null) return;
        // The guide's saved background must not cover the newly rendered artwork.
        boolean guide = !guideTiles.isEmpty();
        eraseGradientGuide();
        Rect dirty = new Rect(Math.max(0, bounds[0] - 2), Math.max(0, bounds[1] - 2),
                Math.min(document.width, bounds[2] + 2), Math.min(document.height, bounds[3] + 2));
        if (dirty.intersect(0, 0, display.getWidth(), display.getHeight())) {
            render(dirty);
            queue(dirty);
        }
        document.clearDirty();
        if (guide) drawGradientGuide();
    }
    /** Queues page pixels that are already up to date in {@link #display}. */
    private void queue(Rect pageArea) {
        queueView(viewportBitmap == null ? pageArea : viewportBitmap.update(display, pageToView, pageArea));
    }
    private void render(Rect dirty) {
        int count = dirty.width() * dirty.height();
        if (renderPixels.length < count) renderPixels = new int[count];
        if (viewportBitmap == null) document.render(renderPixels, dirty.left, dirty.top, dirty.width(), dirty.height());
        else for (int y = dirty.top, i = 0; y < dirty.bottom; y++) for (int x = dirty.left; x < dirty.right; x++) {
            int tone = document.compositeTone(x, y); renderPixels[i++] = Color.rgb(tone, tone, tone);
        }
        display.setPixels(renderPixels, 0, dirty.width(), dirty.left, dirty.top, dirty.width(), dirty.height());
    }
    @Override protected void onDraw(Canvas canvas) {
        drawCount++;
        canvas.drawColor(Color.WHITE);
        if (viewportBitmap != null) canvas.drawBitmap(viewportBitmap.bitmap, 0, 0, null);
        else if (display != null) canvas.drawBitmap(display, pageToView, null);
    }
    private boolean showsGradientGuide() { return (fillGesture && fillGradient != ToolSettings.Gradient.FLAT) || gradientWaiting; }
    /**
     * Draws the gradient guide into the presented raster, replacing the previous one, so it
     * reaches the panel like any canvas change. Without antialiasing its pixels stay
     * black/white for {@link #beginFastPreview}.
     */
    private void drawGradientGuide() {
        eraseGradientGuide();
        Bitmap target = presented();
        if (target == null) return;
        float[] axis = {fillStartX, fillStartY, fillEndX, fillEndY};
        // The unzoomed page raster is only rotated (never scaled) relative to the view.
        if (viewportBitmap != null) pageToView.mapPoints(axis);
        // Short segments keep a long diagonal guide's damage to a band along the line.
        int segments = Math.max(1, (int)Math.ceil(Math.hypot(axis[2]-axis[0], axis[3]-axis[1]) / dp(48)));
        int saved = 0;
        RectF bounds = new RectF();
        for (int i = 0; i < segments; i++) {
            float a = (float)i / segments, b = (float)(i+1) / segments;
            float x0 = axis[0]+(axis[2]-axis[0])*a, y0 = axis[1]+(axis[3]-axis[1])*a;
            float x1 = axis[0]+(axis[2]-axis[0])*b, y1 = axis[1]+(axis[3]-axis[1])*b;
            bounds.set(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
            bounds.inset(-dp(6), -dp(6));
            Rect tile = new Rect(); bounds.roundOut(tile);
            if (!tile.intersect(0, 0, target.getWidth(), target.getHeight())) continue;
            guideTiles.add(tile); saved += tile.width() * tile.height();
        }
        if (guideBackground.length < saved) guideBackground = new int[saved];
        // Save every tile before drawing; overlapping tiles are restored in reverse.
        int offset = 0;
        for (Rect tile : guideTiles) {
            target.getPixels(guideBackground, offset, tile.width(), tile.left, tile.top, tile.width(), tile.height());
            offset += tile.width() * tile.height();
        }
        Canvas canvas = new Canvas(target);
        gradientGuide.setStyle(Paint.Style.STROKE);
        gradientGuide.setColor(Color.WHITE); gradientGuide.setStrokeWidth(dp(5));
        canvas.drawLine(axis[0], axis[1], axis[2], axis[3], gradientGuide);
        gradientGuide.setColor(Color.BLACK); gradientGuide.setStrokeWidth(dp(2));
        canvas.drawLine(axis[0], axis[1], axis[2], axis[3], gradientGuide);
        gradientGuide.setStyle(Paint.Style.FILL);
        canvas.drawCircle(axis[0], axis[1], dp(4), gradientGuide);
        canvas.drawCircle(axis[2], axis[3], dp(4), gradientGuide);
        for (Rect tile : guideTiles) queueView(tile);
    }
    /** Restores the pixels under the gradient guide and queues them. */
    private void eraseGradientGuide() {
        if (guideTiles.isEmpty()) return;
        Bitmap target = presented();
        int offset = 0;
        for (Rect tile : guideTiles) offset += tile.width() * tile.height();
        for (int i = guideTiles.size()-1; i >= 0; i--) {
            Rect tile = guideTiles.get(i);
            offset -= tile.width() * tile.height();
            target.setPixels(guideBackground, offset, tile.width(), tile.left, tile.top, tile.width(), tile.height());
            queueView(tile);
        }
        guideTiles.clear();
    }
    /** Draws the guide again after a re-render replaced the raster under it. */
    private void redrawGradientGuide() {
        guideTiles.clear();
        if (showsGradientGuide()) drawGradientGuide();
    }
    /** Removes the gradient guide from the screen. */
    private void clearGradientGuide() { eraseGradientGuide(); flush(true); }

    // Direct e-ink presentation

    /** Starts direct presentation and native pen input once nothing covers the canvas. */
    void connectDisplay() {
        scheduleWet();
        if (app.canvasCovered() || !app.resumed || !hasWindowFocus() || app.loading || display == null || direct != null) return;
        if (isLayoutRequested() || app.root.isLayoutRequested() || app.orientationFrame.isLayoutRequested()
                || app.previewFrame.isLayoutRequested()) return;
        try {
            if (!input.prepareDocumentCanvas()) throw new IllegalStateException(input.status);
            direct = viewportBitmap == null ? DirectEink.forView(this, display, pageToView, 0, 7)
                    : DirectEink.forView(this, viewportBitmap.bitmap, new Matrix(), 0, 7);
            refreshFastInk();
        } catch (RuntimeException | LinkageError error) {
            Log.w(ProbeActivity.TAG, "Using Android drawing presentation", error);
            if (!fallbackNotice) { app.message("Fast display unavailable; using standard drawing"); fallbackNotice = true; }
        }
    }
    void disconnectDisplay() {
        cancelWetCallback();
        removeCallbacks(retry);
        if (previewDirect != null) { previewDirect.close(); previewDirect = null; previewArea.setEmpty(); }
        if (direct != null) { direct.close(); direct = null; }
        input.disable();
    }
    void present() { flush(true); }
    /**
     * Removes fast ink a closed popup left over the canvas. The Nomad keeps pen-plane
     * (mode 9) pixels through Android redraws and panel refreshes, so blank that plane
     * here and show the canvas again through the gray session once it is connected.
     */
    void eraseFastInk(RectF panelArea) {
        if (panelArea.isEmpty()) return;
        Rect area = new Rect(); panelArea.roundOut(area);
        Bitmap white = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
        white.eraseColor(Color.WHITE);
        DirectEink blank = null;
        try {
            Matrix toPanel = new Matrix(); toPanel.setTranslate(area.left, area.top);
            blank = DirectEink.forPanel(white, toPanel, 1, 9);
            blank.refresh(white, new Rect(0, 0, area.width(), area.height()));
        } catch (RuntimeException | LinkageError error) {
            Log.w(ProbeActivity.TAG, "Fast ink stays until the canvas redraws", error);
        } finally {
            if (blank != null) blank.close();
            white.recycle();
        }
        fastInk.union(panelArea);
        refreshFastInk();
    }
    private void refreshFastInk() {
        if (fastInk.isEmpty() || direct == null) return;
        Matrix toPanel = new Matrix(viewportBitmap == null ? pageToView : new Matrix());
        toPanel.postConcat(PanelCoordinates.fromView(this));
        Matrix fromPanel = new Matrix();
        if (!toPanel.invert(fromPanel)) return;
        RectF mapped = new RectF(fastInk); fromPanel.mapRect(mapped);
        Rect area = new Rect(); mapped.roundOut(area);
        Bitmap source = presented();
        if (!area.intersect(0, 0, source.getWidth(), source.getHeight())) { fastInk.setEmpty(); return; }
        flush(true);
        // Forced: the software pixels are unchanged, only the panel's are stale.
        if (direct.refresh(source, area) < 0) { postDelayed(this::refreshFastInk, 8); return; }
        fastInk.setEmpty(); fastInkRefreshes++;
    }
    /**
     * Presents a moving preview (a shape or the gradient guide) through the Nomad's fast
     * black/white pen path, like the color bar. Gray-mode frames queue on the Nomad, so a
     * preview there trails the pen and leaves copies behind. The canvas raster is binary
     * dots throughout. Fast pixels do not survive a panel refresh, so
     * {@link #endFastPreview} presents the final pixels through {@link #direct}.
     */
    private void beginFastPreview() {
        if (direct == null || previewDirect != null || !DirectEink.fastBinaryControls()) return;
        flush(true);
        try { previewDirect = DirectEink.forView(this, presented(), viewportBitmap == null ? pageToView : new Matrix(), 1, 9); }
        catch (RuntimeException | LinkageError error) { Log.w(ProbeActivity.TAG, "Previews use gray display", error); }
    }
    private void endFastPreview() {
        if (previewDirect == null) return;
        flush(true); closeFastPreview(); flush(true);
    }
    private void queueView(Rect r) { pending.add(r.left, r.top, r.right, r.bottom); }
    /** Returns to the gray session, queueing everything the fast one showed. */
    private void closeFastPreview() {
        previewDirect.close(); previewDirect = null;
        queueView(previewArea); previewArea.setEmpty();
    }
    /** Finishes the gesture, sets wet paint and releases the panel, e.g. before a panel covers the canvas. */
    void suspend() { finishStroke(); dryWet(); disconnectDisplay(); }
    private void flush(boolean force) {
        if (pending.isEmpty()) return;
        long elapsed = SystemClock.uptimeMillis() - lastPresent;
        if (!force && elapsed < 8) {
            // A pressure-only event may be the last event for a while.
            // Flush its pixels when the coalescing window ends.
            removeCallbacks(retry);
            postDelayed(retry, 8 - elapsed);
            return;
        }
        removeCallbacks(retry);
        if (direct == null) { invalidate(); pending.clear(); return; }
        DirectEink target = previewDirect != null ? previewDirect : direct;
        try {
            while (!pending.isEmpty()) {
                int[] patch = pending.first();
                presenting.set(patch[0], patch[1], patch[2], patch[3]);
                int result = target.present(presented(), presenting);
                lastPresent = SystemClock.uptimeMillis();
                if (result < 0) {
                    if (++retries < 120) { postDelayed(retry, 8); return; }
                    throw new IllegalStateException("Display remained busy");
                }
                if (target == previewDirect) previewArea.union(presenting);
                pending.removeFirst(); retries = 0;
            }
        } catch (RuntimeException error) {
            if (target == previewDirect) {
                // Keep the canvas on the gray session; only the preview loses its speed.
                Log.w(ProbeActivity.TAG, "Fast preview failed", error);
                closeFastPreview(); retries = 0; flush(true); return;
            }
            Log.e(ProbeActivity.TAG, "Direct display failed", error);
            disconnectDisplay(); invalidate(); pending.clear();
            app.saveError = "Fast display stopped; painting is kept. Reopen the app to retry.";
        }
    }

    // Input

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (app.paletteEditor != null || app.loading || document == null || fill != null || gradientCommit
                || !app.resumed || !hasWindowFocus()) return true;
        if (app.fullscreen.gesture(event, blocksFullscreenGesture())) { endNavigation(); return true; }
        if (navigationGesture(event)) return true;
        if (app.pickingShade || pickPointer != -1) { sampleShadeGesture(event); return true; }
        if (hasGradient()) return true;
        if (fillGesture) { continueFillGesture(event); return true; }
        if (shapeStroke != null) { continueShape(event); return true; }
        long inputStart = System.nanoTime();
        long eventAge = Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
        boolean drawingInput = false;
        int action = event.getActionMasked(), index = event.getActionIndex();
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                && pointer == -1 && isPen(event, index)) {
            if (!penDown(event, index)) return true;
            drawingInput = stroke != null;
            if (!drawingInput) return true;
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP) {
            int p = event.findPointerIndex(pointer);
            if (p >= 0 && stroke != null && (action == MotionEvent.ACTION_MOVE || event.getPointerId(index) == pointer)) {
                drawingInput = true;
                for (int h = 0; h < event.getHistorySize(); h++) sampleEvent(event, p, h);
                if (action == MotionEvent.ACTION_MOVE) sampleEvent(event, p, -1);
            }
        }
        renderDirty(); flush(false);
        if (drawingInput) wetBudget.input(System.nanoTime() - inputStart, eventAge, SystemClock.uptimeMillis());
        scheduleWet();
        if (action == MotionEvent.ACTION_CANCEL || pointerUp(event, pointer)) finishStroke();
        return true;
    }
    /**
     * Starts the selected tool's gesture at a pen-down inside the page.
     * @return false if the pen-down was refused
     */
    private boolean penDown(MotionEvent event, int index) {
        pagePoint(event.getX(index), event.getY(index));
        if (samplePoint[0] < 0 || samplePoint[1] < 0
                || samplePoint[0] >= document.width || samplePoint[1] >= document.height) return false;
        if (!document.layerVisible(document.activeLayer())) {
            app.message("This layer is hidden. Open Layers to show it or select another layer."); return false;
        }
        PaintState paint = app.paint;
        // A stylus's eraser end, or a stroke begun with its side button held, erases with
        // the Eraser tool, whatever tool is selected.
        boolean penEraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
                || (event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        ToolSettings settings = penEraser ? app.library.builtin(ToolSettings.Tool.ERASER) : app.library.current();
        boolean erasing = paint.eraseMode && settings.supportsEraseMode();
        // Brushes paint into a wet canvas; the brush pen brings its own water, which on a
        // dry canvas wets only where it lands.
        boolean wets = !erasing && (settings.tool.water || settings.isBrush() && paint.wetCanvas);
        if (!wets || wet != null && wet.coversCanvas() != paint.wetCanvas) dryWet();
        if (wets && wet == null) wet = paint.wetCanvas ? new WetWatercolor(document, paint.wetness) : new WetWatercolor(document);
        pointer = event.getPointerId(index);
        getParent().requestDisallowInterceptTouchEvent(true);
        int shade = erasing ? ToneDocument.ERASE : paint.gray;
        if (settings.tool == ToolSettings.Tool.SHAPES) { beginShape(settings, shade); return true; }
        if (settings.tool == ToolSettings.Tool.FILL) { beginFill(settings, shade); return true; }
        cancelWetCallback();
        stroke = ToolStrokes.create(document, settings, paint.gray, wet, paint.transparentPaint, erasing, app.prefs.smoothEdges());
        // Supernote encodes signed X degrees in ORIENTATION, Y in TILT.
        sampleEvent(event, index, -1);
        return true;
    }
    /** The pointer is a stylus tip or eraser end. */
    private static boolean isPen(MotionEvent event, int index) {
        int type = event.getToolType(index);
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
    }
    /** A pen stroke, shape or fill drag is in progress. */
    boolean penActive() { return pointer != -1; }
    private void pagePoint(float x, float y) {
        samplePoint[0] = x; samplePoint[1] = y;
        if (!unscaledPage) viewToPage.mapPoints(samplePoint);
    }
    @Override public boolean onHoverEvent(MotionEvent event) {
        if (isPen(event, 0)) {
            penGuardUntil = SystemClock.uptimeMillis()+250;
            if (navigating) { touchBlocked = true; endNavigation(); }
        }
        return true;
    }
    private void sampleEvent(MotionEvent event, int pointerIndex, int history) {
        MotionEvent physical = app.physicalPenEvent == null ? event : app.physicalPenEvent;
        int source = physical.findPointerIndex(event.getPointerId(pointerIndex));
        if (source < 0 || history >= physical.getHistorySize()) { physical = event; source = pointerIndex; }
        boolean current = history < 0;
        float x = current ? event.getX(pointerIndex) : event.getHistoricalX(pointerIndex, history);
        float y = current ? event.getY(pointerIndex) : event.getHistoricalY(pointerIndex, history);
        float pressure = current ? event.getPressure(pointerIndex) : event.getHistoricalPressure(pointerIndex, history);
        float tiltX = current ? physical.getOrientation(source) : physical.getHistoricalOrientation(source, history);
        float tiltY = current ? physical.getAxisValue(MotionEvent.AXIS_TILT, source) : physical.getHistoricalAxisValue(MotionEvent.AXIS_TILT, source, history);
        pagePoint(x, y);
        // The page stays device-relative, as do Supernote's signed tilt-degree axes.
        // Transform positions only; treating ORIENTATION as Android azimuth corrupts tilt.
        if (landscapeTilt) { float panelX = tiltX; tiltX = -tiltY; tiltY = panelX; }
        stroke.sample(samplePoint[0], samplePoint[1], pressure, tiltX, tiltY);
    }

    /** Ends or cancels whatever gesture is running, committing finished work. */
    void finishStroke() {
        if (navigating) touchBlocked = true;
        endNavigation();
        if (shapeStroke != null) {
            removeCallbacks(previewFrame); previewFrameScheduled = false;
            renderShape(shapeStroke.cancel(display, viewportBitmap != null)); shapeStroke = null; pointer = -1;
            endFastPreview(); getParent().requestDisallowInterceptTouchEvent(false);
        }
        if (pickPointer != -1) {
            pickPointer = -1; getParent().requestDisallowInterceptTouchEvent(false);
            int original = app.pickOriginalShade;
            app.updateColorBar(() -> app.paint.gray = original);
            app.setPickingShade(false);
        }
        // Pen-up already accepted this fill. Complete it before a lifecycle save.
        if (gradientCommit && gradientFill != null) {
            removeCallbacks(gradientStep);
            gradientFill.secondShade(gradientShade);
            while (!gradientFill.advance(8192)) { }
            renderDirty(); flush(true); gradientReady = true; applyGradient();
        }
        if (fillGesture) {
            removeCallbacks(previewFrame); previewFrameScheduled = false;
            fillGesture = false; pointer = -1; clearGradientGuide(); endFastPreview();
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        cancelGradient();
        if (fill != null) {
            removeCallbacks(fillStep); fill.cancel(); fill = null;
            renderDirty(); flush(true); app.toolbar.operationStatus.setText("");
        }
        if (stroke == null) return;
        boolean changed = stroke.finish(); stroke = null; pointer = -1;
        renderDirty(); flush(true);
        getParent().requestDisallowInterceptTouchEvent(false);
        if (changed) app.recovery();
        scheduleWet();
        // No View invalidation or toolbar update at pen-up on the direct path.
    }

    // Shapes

    private void beginShape(ToolSettings settings, int shade) {
        renderDirty();
        shapeX = samplePoint[0]; shapeY = samplePoint[1];
        shapeStroke = new ShapePreview(document, settings, shade, shapeX, shapeY);
        beginFastPreview(); drawPreviewFrame();
    }
    private void continueShape(MotionEvent event) {
        int action = event.getActionMasked(), index = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || index < 0) { finishStroke(); return; }
        boolean up = pointerUp(event, pointer);
        if (action == MotionEvent.ACTION_MOVE || up) {
            pagePoint(event.getX(index), event.getY(index));
            shapeX = samplePoint[0]; shapeY = samplePoint[1];
            if (!up) schedulePreviewFrame();
        }
        if (!up) return;
        drawPreviewFrame(); endFastPreview();
        boolean changed = shapeStroke.finish(); shapeStroke = null; pointer = -1;
        // The committed geometry matches the preview exactly; keep its native raster.
        document.clearDirty();
        getParent().requestDisallowInterceptTouchEvent(false);
        if (changed) app.recovery();
    }
    /** Coalesces queued moves into one preview frame per display frame. */
    private void schedulePreviewFrame() {
        if (previewFrameScheduled) return;
        previewFrameScheduled = true;
        postDelayed(previewFrame, Math.max(0, 16-(SystemClock.uptimeMillis()-lastPreviewFrame)));
    }
    private void drawPreviewFrame() {
        removeCallbacks(previewFrame); previewFrameScheduled = false;
        // Budget from frame start; rendering time must not add another full-frame delay.
        lastPreviewFrame = SystemClock.uptimeMillis();
        if (shapeStroke != null) renderShape(shapeStroke.preview(shapeX, shapeY, display, viewportBitmap != null));
        else if (showsGradientGuide()) drawGradientGuide();
        else return;
        flush(true); previewFrameCount++;
    }
    private void renderShape(Rect dirty) {
        if (dirty.isEmpty()) return;
        // Preview pixels are already rasterized natively. Submit one complete frame.
        dirty.inset(-2, -2);
        if (!dirty.intersect(0, 0, document.width, document.height)) return;
        queue(dirty);
    }

    // Fills and gradients

    private void beginFill(ToolSettings settings, int shade) {
        fillGesture = true;
        fillStartX = fillEndX = samplePoint[0]; fillStartY = fillEndY = samplePoint[1];
        fillOriginalGray = app.paint.gray; fillShade = shade; fillGradient = settings.gradient;
        fillTolerance = settings.gradient == ToolSettings.Gradient.FLAT ? 0 : settings.tolerance;
        if (!showsGradientGuide()) return;
        beginFastPreview(); drawPreviewFrame();
    }
    private void continueFillGesture(MotionEvent event) {
        int action = event.getActionMasked(), p = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL) { finishStroke(); return; }
        if (p < 0) return;
        boolean up = pointerUp(event, pointer);
        if (action != MotionEvent.ACTION_MOVE && !up) return;
        pagePoint(event.getX(p), event.getY(p));
        fillEndX = samplePoint[0]; fillEndY = samplePoint[1];
        if (!up) { schedulePreviewFrame(); return; }
        drawPreviewFrame();
        fillGesture = false; pointer = -1; getParent().requestDisallowInterceptTouchEvent(false);
        float[] axis = {fillStartX, fillStartY, fillEndX, fillEndY}; pageToView.mapPoints(axis);
        if (fillGradient == ToolSettings.Gradient.FLAT || Math.hypot(axis[2]-axis[0], axis[3]-axis[1]) < dp(8)) {
            if (fillGradient != ToolSettings.Gradient.FLAT) clearGradientGuide();
            fill = new FloodFill(document, (int)fillStartX, (int)fillStartY, fillShade, fillTolerance);
            app.toolbar.operationStatus.setText("Filling…"); post(fillStep);
        } else {
            // Keep only the direction guide until the second color gesture begins.
            gradientWaiting = true; gradientReady = false; gradientCommit = false;
            app.scheduleGradientHint();
        }
        // A waiting guide must outlast a panel refresh; a cleared one is already gone.
        endFastPreview();
    }
    private void advanceFill() {
        if (fill == null) return;
        if (!advanceFor5ms(fill)) { postDelayed(fillStep, 1); return; }
        boolean changed = fill.finish(); fill = null;
        renderDirty(); flush(true); app.toolbar.operationStatus.setText("");
        if (changed) app.recovery();
    }
    /** Runs a fill for one 5 ms slice; true when it has finished. */
    private static boolean advanceFor5ms(FloodFill fill) {
        long deadline = System.nanoTime()+5_000_000L;
        boolean done;
        do { done = fill.advance(1024); } while (!done && System.nanoTime() < deadline);
        return done;
    }
    /** A gradient axis is drawn and waiting for, or previewing, its second color. */
    boolean hasGradient() { return gradientWaiting || gradientFill != null; }
    /** Previews the pending gradient with {@code shade} as its second color. */
    void previewGradient(int shade) {
        if (!hasGradient()) return;
        app.hideGradientHint();
        if (gradientWaiting) {
            gradientSample = document.snapshot();
            gradientShade = gradientPassShade = shade;
            gradientFill = new FloodFill(document, (int)fillStartX, (int)fillStartY, fillShade, fillTolerance,
                    fillStartX, fillStartY, fillEndX, fillEndY, shade, fillGradient);
            gradientWaiting = false; gradientReady = false;
            clearGradientGuide(); post(gradientStep); return;
        }
        gradientShade = shade;
        // Finish the current preview pass before starting the latest shade. Rapid
        // moves must not continually restart a large fill and starve its display.
        if (gradientReady && gradientPassShade != shade) {
            gradientFill.secondShade(shade); gradientPassShade = shade; gradientReady = false;
            post(gradientStep);
        }
    }
    private void advanceGradient() {
        if (gradientFill == null) return;
        if (!advanceFor5ms(gradientFill)) { postDelayed(gradientStep, 1); return; }
        renderDirty(); flush(true); gradientReady = true;
        if (gradientPassShade != gradientShade) previewGradient(gradientShade);
        else if (gradientCommit) applyGradient();
    }
    /** Commits the previewed gradient, once its latest pass is complete. */
    void applyGradient() {
        if (gradientFill == null) return;
        gradientCommit = true;
        if (!gradientReady) return;
        boolean changed = gradientFill.finish(); gradientFill = null; gradientSample = null; gradientCommit = false;
        app.toolbar.operationStatus.setText("");
        if (changed) app.recovery();
    }
    /** Discards a pending gradient and restores the color chosen before it. */
    void cancelGradient() {
        app.hideGradientHint(); removeCallbacks(gradientStep);
        if (!hasGradient()) return;
        if (gradientFill != null) gradientFill.cancel();
        if (gradientWaiting) { gradientWaiting = false; clearGradientGuide(); }
        gradientFill = null; gradientSample = null; gradientReady = false; gradientCommit = false;
        app.toolbar.operationStatus.setText(""); renderDirty(); flush(true);
        int original = fillOriginalGray;
        app.updateColorBar(() -> app.paint.gray = original);
        app.setEraseMode(fillShade == ToneDocument.ERASE);
        app.saveToolState();
    }

    // Eyedropper

    private void sampleShadeGesture(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) { finishStroke(); return; }
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        if (down && pickPointer == -1 && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
            pickPointer = event.getPointerId(index);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        int p = event.findPointerIndex(pickPointer);
        if (p < 0) return;
        boolean up = pointerUp(event, pickPointer);
        if (down || action == MotionEvent.ACTION_MOVE || up) {
            pagePoint(event.getX(p), event.getY(p));
            if (samplePoint[0] >= 0 && samplePoint[1] >= 0 && samplePoint[0] < document.width && samplePoint[1] < document.height) {
                int x = (int)samplePoint[0], y = (int)samplePoint[1];
                // The unmodified composite prevents sampling the gradient's own preview.
                int shade = gradientSample == null ? document.compositeTone(x, y) : gradientSample[y*document.width+x]&255;
                if (app.paint.gray != shade || !app.pickedShade)
                    app.updateColorBar(() -> { app.paint.gray = shade; app.pickedShade = true; });
                previewGradient(shade);
            }
        }
        if (up) {
            pickPointer = -1; getParent().requestDisallowInterceptTouchEvent(false);
            if (app.pickedShade) { app.setEraseMode(false); app.setPickingShade(false); applyGradient(); app.saveToolState(); }
        }
    }

    // Pinch and pan

    /** Handles finger navigation; returns true if the event was consumed as navigation. */
    private boolean navigationGesture(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) { touchBlocked = false; endNavigation(); }
        boolean pen = false;
        for (int i = 0; i < event.getPointerCount(); i++)
            if (isPen(event, i)) pen = true;
        if (pen || pointer != -1 || pickPointer != -1 || fillGesture || hasGradient()
                || SystemClock.uptimeMillis() < penGuardUntil) {
            touchBlocked = true; endNavigation();
            if (pen) penGuardUntil = SystemClock.uptimeMillis()+250;
            return false;
        }
        if (app.navigationLocked) { touchBlocked = true; endNavigation(); return true; }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) { endNavigation(); return true; }
        if (touchBlocked) return true;
        if (action == MotionEvent.ACTION_POINTER_UP) {
            // Require a fresh two-finger gesture after either tracked finger lifts.
            if (event.getPointerId(index) == fingerA || event.getPointerId(index) == fingerB) {
                touchBlocked = true; endNavigation();
            }
            return true;
        }
        if (!navigating) {
            if (action != MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 2
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                    || event.getToolType(1) != MotionEvent.TOOL_TYPE_FINGER) return true;
            fingerA = event.getPointerId(0); fingerB = event.getPointerId(1);
            fingerX = (event.getX(0)+event.getX(1))/2; fingerY = (event.getY(0)+event.getY(1))/2;
            fingerSpan = (float)Math.hypot(event.getX(1)-event.getX(0), event.getY(1)-event.getY(0));
            if (fingerSpan < dp(24)) { touchBlocked = true; fingerA = fingerB = -1; return true; }
            disconnectDisplay(); navigating = true;
            updateViewport(); connectDisplay();
            getParent().requestDisallowInterceptTouchEvent(true); return true;
        }
        int a = event.findPointerIndex(fingerA), b = event.findPointerIndex(fingerB);
        if (a < 0 || b < 0) { touchBlocked = true; endNavigation(); return true; }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = (event.getX(a)+event.getX(b))/2, y = (event.getY(a)+event.getY(b))/2;
            float span = (float)Math.hypot(event.getX(b)-event.getX(a), event.getY(b)-event.getY(a));
            if (span < dp(24)) return true;
            viewport.gesture(span/fingerSpan, fingerX, fingerY, x, y);
            fingerX = x; fingerY = y; fingerSpan = span;
            if (!navigationFrameScheduled) {
                navigationFrameScheduled = true;
                postDelayed(navigationFrame, Math.max(0, 16-(SystemClock.uptimeMillis()-lastNavigationFrame)));
            }
        }
        return true;
    }
    void endNavigation() {
        fingerA = fingerB = -1;
        if (!navigating) return;
        if (navigationFrameScheduled) drawNavigationFrame();
        navigating = false; getParent().requestDisallowInterceptTouchEvent(false);
        if (viewport.zoom == 1 && viewport.scale() == 1) {
            disconnectDisplay(); updateViewport(); invalidate();
        }
        if (direct == null) post(this::connectDisplay); else scheduleWet();
    }
    private void drawNavigationFrame() {
        removeCallbacks(navigationFrame); navigationFrameScheduled = false;
        if (!navigating) return;
        lastNavigationFrame = SystemClock.uptimeMillis();
        long began = System.nanoTime();
        updateViewport();
        long rendered = System.nanoTime();
        pending.clear(); pending.add(0, 0, getWidth(), getHeight()); flush(true);
        // Keep Android's retained drawing commands current without requesting a
        // compositor frame for every finger movement on the direct display path.
        if (direct != null) app.selectionFeedback.retainForNextDraw(this, new Rect(0, 0, getWidth(), getHeight()));
        lastNavigationRasterNanos = rendered-began;
        lastNavigationPresentNanos = System.nanoTime()-rendered;
        navigationFrameCount++;
    }

    // Wet paint

    private void scheduleWet() {
        // Yield between slices; input queued during the last slice runs
        // before the next idle callback. Only completed sweeps wait a frame.
        scheduleWet(wet != null && wet.framePending() ? 1 : WetWatercolor.FRAME_MS);
    }
    private void scheduleWet(int delayMillis) {
        if (wet != null && wet.isAnimating() && app.resumed && hasWindowFocus() && !app.loading && !navigating) {
            // Start reading the tilt now, so it is ready when the paint first moves.
            app.canvasGravity.listen(true);
            if (!wetScheduled) { wetScheduled = true; postDelayed(wetStep, delayMillis); }
        }
    }
    private void cancelWetCallback() {
        removeCallbacks(wetStep); wetQueue.removeIdleHandler(wetIdle); wetScheduled = false;
    }
    private void advanceWet() {
        wetScheduled = false;
        if (wet == null || !app.resumed || !hasWindowFocus() || app.loading || navigating) {
            app.canvasGravity.listen(false); return;
        }
        boolean drawing = stroke != null;
        long budget = wetBudget.nanos(drawing, wet.strokePixels(), wet.activePixels(), SystemClock.uptimeMillis());
        // Submit the pen's outstanding pixels before adding more display work.
        if (budget == 0 || !pending.isEmpty()) { scheduleWet(16); return; }
        long start = System.nanoTime();
        app.canvasGravity.apply(wet);
        wetChanged |= wet.advance(drawing, wetBudget.tiles(budget, drawing), budget / 2, drawing ? 96 : 192);
        long computed = System.nanoTime();
        renderDirty();
        long rendered = System.nanoTime();
        flush(true);
        long finished = System.nanoTime();
        lastWetComputeNanos = computed - start;
        lastWetRenderNanos = rendered - computed;
        lastWetPresentNanos = finished - rendered;
        wetMaxSliceNanos = Math.max(wetMaxSliceNanos, finished - start);
        wetSliceCount++;
        wetBudget.completed(finished - start, wet.advancedTiles(), drawing, SystemClock.uptimeMillis());
        if (wet.isAnimating()) scheduleWet();
        else {
            app.canvasGravity.listen(false);
            if (wetChanged && stroke == null) { wetChanged = false; app.recovery(); }
        }
    }
    /** Stops wet blending, leaving the paint as it is. */
    void dryWet() {
        cancelWetCallback();
        app.canvasGravity.listen(false);
        wet = null;
        if (wetChanged) { wetChanged = false; app.recovery(); }
    }

    void close() {
        finishStroke(); dryWet(); disconnectDisplay(); input.close();
        if (display != null) display.recycle();
        if (viewportBitmap != null) { viewportBitmap.close(); viewportBitmap = null; }
        display = null; renderPixels = new int[0]; document = null;
    }
}
