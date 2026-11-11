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
import io.github.mpdairy.fasteink.FastEink;
import io.github.mpdairy.fasteink.Mode;
import io.github.mpdairy.fasteink.PanelCoordinates;
import io.github.mpdairy.fasteink.Session;

/**
 * The canvas. It renders the current page into a page-sized raster ({@link #display}),
 * or a screen-sized one while zoomed ({@link #viewportBitmap}), and presents changed
 * pixels straight to the e-ink panel through a fasteink {@link Session} when available.
 *
 * <p>Pen input runs one of these gestures at a time:
 * <ul>
 * <li>a {@link DrawingStroke} for ordinary tools ({@link ToolStrokes});</li>
 * <li>a live {@link ShapePreview} for Shapes, committed at pen-up;</li>
 * <li>a Lasso loop, which lifts a {@link FloatingSelection}; while one floats, pen drags move,
 * scale and turn it, and it is placed when the pen lands elsewhere or another action begins;</li>
 * <li>a fill: tap for a flood fill, or drag a gradient axis and then pick its second color;</li>
 * <li>eyedropper sampling while the color picker is active.</li>
 * </ul>
 * Two fingers pinch, twist and pan when navigation is unlocked. Wet paint keeps blending in
 * idle time between input events, except under a watercolor brush, which waits for pen-up.
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
    private Session direct;
    /** Nomad's fast black/white session for moving previews; see {@link #beginFastPreview}. */
    private Session previewDirect;
    /** Pixels {@link #previewDirect} showed, re-presented through {@link #direct} when the preview ends. */
    private final Rect previewArea = new Rect();
    /** Panel area of closed controls' fast ink, presented again through {@link #direct}; see {@link #eraseFastInk}. */
    private final RectF fastInk = new RectF();
    private int fastInkRefreshes;
    private final NativePen input;
    private int pointer = -1, retries;
    private long lastPresent;
    private boolean fallbackNotice;
    /**
     * The driver can refuse an update once, e.g. "Out of memory" as the tablet is unplugged.
     * Reconnect after a moment, and draw through Android only once it keeps failing.
     */
    private static final int DISPLAY_ATTEMPTS = 3, RECONNECT_MS = 1000, STEADY_MS = 10_000;
    private int displayFailures;
    private long connectedAt;
    private final Runnable reconnect = this::connectDisplay;
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
    /** When running wet paint last reached the panel; flowing water shows itself at most every {@link #WET_SHOW_MS}. */
    private long lastWetShown;
    private static final int WET_SHOW_MS = 120;
    /** When the wet paint's current frame began, to pace flowing water. */
    private long wetFrameStarted;
    private int wetSliceCount;

    // Shapes
    private ShapePreview shapeStroke;
    private float shapeX, shapeY;
    /** This gesture's shape or flat fill is laid as a watercolor wash: see {@link WatercolorWash}. */
    private boolean washing;

    // The lasso and floating selections
    /** The lasso's loop so far, page x, y pairs, of which {@link #lassoShown} points are drawn; empty between loops. */
    private float[] lasso = new float[512];
    private int lassoCount, lassoShown;
    /** A piece floating over the page until it is placed, or null; see {@link #placeSelection}. */
    private FloatingSelection selection;
    private SelectionPreview selectionPreview;
    /** The part of {@link #selection} the pen is dragging, or {@link FloatingSelection#NONE}. */
    private int selectionHandle = FloatingSelection.NONE;
    private float grabX, grabY, dragX, dragY;

    // Moving previews: a shape, the gradient guide, the lasso or a selection, drawn once per display frame
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
    /** The gradient's direction, the lasso's loop or a selection's frame, whichever is shown. */
    private final CanvasGuide guide;
    private final Runnable gradientStep = this::advanceGradient;
    private final Runnable fillStep = this::advanceFill;

    // Finger taps and pinch/twist/pan
    private final MultiFingerTap fingerTaps = new MultiFingerTap();
    private final FingerTwist fingerTwist = new FingerTwist();
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
        setContentDescription("Painting canvas; use the pen to paint. Unlock Zoom to pinch, twist and pan with two fingers.");
        input = new NativePen(this, (bitmap, region) -> bitmap.recycle());
        guide = new CanvasGuide(dp(2), dp(5), dp(48));
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
        // A turn opens the page afresh, so wait for the canvas's turned size: deciding on the
        // old one would spend the opening and fit the page instead of showing it at 100%.
        // The orientation frame updates the viewport again once the turned layout is done.
        if (pageRotation != app.appRotation && isLayoutRequested()) return;
        if (pageRotation != app.appRotation) viewport.reset();
        pageRotation = app.appRotation;
        boolean swapped = pageRotation == Surface.ROTATION_90 || pageRotation == Surface.ROTATION_270;
        viewport.configure(getWidth(), getHeight(), swapped ? height : width, swapped ? width : height);
        // An expanded page fills the screen; open it at 100% under the bars, not shrunk beside them.
        if (viewport.opening() && viewport.fitScale < 1 && homeOnScreen()) viewport.openActualSize(app.prefs.toolboxRight());
        float scale = viewport.scale();
        place(pageToView, scale, viewport.x, viewport.y, viewport.angle);
        pageToView.invert(viewToPage);
        unscaledPage = pageRotation == Surface.ROTATION_0 && pageToView.isIdentity();
        boolean wasScaled = viewportBitmap != null;
        // Keep one screen-sized raster and presenter throughout a pinch, including
        // its fitted endpoints. Changing format on each crossing stalls the gesture.
        boolean scaled = navigating || viewport.zoom != 1 || scale != 1 || viewport.x != 0 || viewport.y != 0
                || viewport.angle != 0;
        if (viewportBitmap != null && (!scaled || viewportBitmap.bitmap.getWidth() != getWidth()
                || viewportBitmap.bitmap.getHeight() != getHeight())) { viewportBitmap.close(); viewportBitmap = null; }
        if (scaled && viewportBitmap == null) viewportBitmap = new ViewportBitmap(getWidth(), getHeight());
        if (wasScaled != scaled) renderAll();
        else if (viewportBitmap != null) { viewportBitmap.update(display, pageToView, null); redrawGuide(); }
        pending.clear(); app.toolbar.refreshZoom();
    }
    /** Page to view: the app's quarter turn, then scale, then the viewport's free turn about the page's corner, then (x, y). */
    private void place(Matrix transform, float scale, float x, float y, float angle) {
        int width = display.getWidth(), height = display.getHeight();
        transform.reset();
        // Cancel our own UI rotation for the artwork; Android stays in portrait.
        if (pageRotation == Surface.ROTATION_90) { transform.setRotate(-90); transform.postTranslate(0, width); }
        else if (pageRotation == Surface.ROTATION_180) { transform.setRotate(180); transform.postTranslate(width, height); }
        else if (pageRotation == Surface.ROTATION_270) { transform.setRotate(90); transform.postTranslate(height, 0); }
        transform.postScale(scale, scale);
        if (angle != 0) transform.postRotate(angle);
        transform.postTranslate(x, y);
    }
    /** The page at 100%, where its artwork sat when the page began; see {@link #actualSize}. */
    Matrix homeToView() {
        Matrix home = new Matrix();
        place(home, 1, viewport.homeX(app.prefs.toolboxRight()), viewport.homeY(), 0);
        return home;
    }
    /** The whole screen, bars included, in this view's coordinates. */
    RectF screenArea() {
        RectF pad = Popups.bounds(app.root, this);
        return new RectF(-pad.left, -pad.top, app.root.getWidth() - pad.left, app.root.getHeight() - pad.top);
    }
    /** Whether the page at 100% in its home placement stays on the screen. */
    private boolean homeOnScreen() {
        RectF page = new RectF(0, 0, display.getWidth(), display.getHeight()); homeToView().mapRect(page);
        RectF screen = screenArea(); screen.inset(-0.5f, -0.5f);
        return screen.contains(page);
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
                && viewport.untouched() && app.resumed && hasWindowFocus() && !isLayoutRequested()
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
            drawMask();
            document.clearDirty();
        }
        pending.clear();
        if (selectionPreview != null) selectionPreview.showAll(display, viewportBitmap != null);
        if (viewportBitmap != null) viewportBitmap.update(display, pageToView, null);
        redrawGuide();
    }
    /** Draws the masking fluid's hatching over the freshly composed page. */
    private void drawMask() {
        if (!document.hasMask()) return;
        int hatch = ToneDocument.HATCH;
        // Visit only the pixels on the lines: each row's lines start where x + y is a multiple of the spacing.
        for (int y = 0; y < document.height; y++)
            for (int start = (hatch - y % hatch) % hatch - hatch; start < document.width; start += hatch)
                for (int x = Math.max(0, start); x < Math.min(document.width, start + ToneDocument.HATCH_LINE); x++) {
                    int line = document.maskHatch(x, y);
                    if (line >= 0) display.setPixel(x, y, line == 0 ? Color.BLACK : Color.WHITE);
                }
    }
    /** Rebuild size-dependent display resources after canvas expansion or its undo. */
    void canvasResized() {
        disconnectDisplay(); ensureDisplay(); updateViewport(); renderAll(); invalidate(); post(this::connectDisplay);
    }
    boolean blocksFingerGesture() {
        return pointer!=-1 || pickPointer!=-1 || fillGesture || hasGradient()
                || SystemClock.uptimeMillis()<penGuardUntil;
    }
    /** Re-renders the document's changed area and queues it for presentation. */
    void renderDirty() {
        if (document == null || display == null) return;
        int[] bounds = document.dirty();
        if (bounds == null) return;
        // The guide's saved background must not cover the newly rendered artwork.
        boolean guided = !guide.isEmpty();
        eraseGuide();
        Rect dirty = new Rect(Math.max(0, bounds[0] - 2), Math.max(0, bounds[1] - 2),
                Math.min(document.width, bounds[2] + 2), Math.min(document.height, bounds[3] + 2));
        if (dirty.intersect(0, 0, display.getWidth(), display.getHeight())) {
            render(dirty);
            queue(dirty);
        }
        document.clearDirty();
        if (guided) drawGuide();
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
            int hatch = document.maskHatch(x, y), tone = hatch >= 0 ? hatch : document.compositeTone(x, y);
            renderPixels[i++] = Color.rgb(tone, tone, tone);
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
    /** Page points as presented: the unzoomed page raster is only rotated (never scaled) relative to the view. */
    private float[] presentedPoints(float[] page, int count) {
        float[] points = java.util.Arrays.copyOf(page, 2 * count);
        if (viewportBitmap != null) pageToView.mapPoints(points);
        return points;
    }
    /** Draws the gradient's direction line in place of whatever guide was shown. */
    private void drawGradientGuide() {
        eraseGuide();
        if (presented() == null) return;
        float[] axis = presentedPoints(new float[]{fillStartX, fillStartY, fillEndX, fillEndY}, 2);
        guide.add(presented(), axis, 2, false, axis, dp(4), this::queueView);
    }
    /** Draws the guide the current gesture shows, if any, in place of whatever was shown. */
    private void drawGuide() {
        if (showsGradientGuide()) drawGradientGuide();
        else if (lassoCount > 0) { eraseGuide(); lassoShown = 0; drawLasso(); }
        else if (selection != null) drawSelectionFrame();
        else eraseGuide();
    }
    /** Restores the pixels under the guide and queues them. */
    private void eraseGuide() { if (presented() != null) guide.erase(presented(), this::queueView); }
    /** Draws the guide again after a re-render replaced the raster under it. */
    private void redrawGuide() { guide.forget(); drawGuide(); }
    /** Removes the guide from the screen. */
    private void clearGradientGuide() { eraseGuide(); flush(true); }

    // Direct e-ink presentation

    /** Starts direct presentation and native pen input once nothing covers the canvas. */
    void connectDisplay() {
        scheduleWet();
        if (app.canvasCovered() || !app.resumed || !hasWindowFocus() || app.loading || display == null || direct != null) return;
        // Standard Android drawing leaves the firmware pen and e-ink interfaces untouched.
        if (!FastEink.enabled()) return;
        if (isLayoutRequested() || app.root.isLayoutRequested() || app.orientationFrame.isLayoutRequested()
                || app.previewFrame.isLayoutRequested()) return;
        try {
            if (!input.prepareDocumentCanvas()) throw new IllegalStateException(input.status);
            direct = viewportBitmap == null ? Session.forView(this, display, pageToView, Mode.GRAY)
                    : Session.forView(this, viewportBitmap.bitmap, new Matrix(), Mode.GRAY);
            connectedAt = SystemClock.uptimeMillis();
            refreshFastInk();
        } catch (RuntimeException | LinkageError error) {
            Log.w(ProbeActivity.TAG, "Using Android drawing presentation", error);
            displayFailed();
        }
    }
    /** Tries the fast display again shortly, unless it has failed {@link #DISPLAY_ATTEMPTS} times running. */
    private void displayFailed() {
        // A session that ran steadily before failing starts a new count.
        if (connectedAt != 0 && SystemClock.uptimeMillis() - connectedAt > STEADY_MS) displayFailures = 0;
        connectedAt = 0;
        if (++displayFailures < DISPLAY_ATTEMPTS) { postDelayed(reconnect, RECONNECT_MS); return; }
        if (!fallbackNotice) { app.message("Fast display unavailable; using standard drawing. Reopen the app to retry."); fallbackNotice = true; }
    }
    void disconnectDisplay() {
        cancelWetCallback();
        removeCallbacks(retry);
        removeCallbacks(reconnect);
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
        Session blank = null;
        try {
            Matrix toPanel = new Matrix(); toPanel.setTranslate(area.left, area.top);
            blank = Session.forPanel(white, toPanel, Mode.FAST_BINARY);
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
        if (direct.refresh(source, area) == Session.BUSY) { postDelayed(this::refreshFastInk, 8); return; }
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
        if (direct == null || previewDirect != null || !FastEink.supportsFastBinary()) return;
        flush(true);
        try { previewDirect = Session.forView(this, presented(), viewportBitmap == null ? pageToView : new Matrix(), Mode.FAST_BINARY); }
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
        Session target = previewDirect != null ? previewDirect : direct;
        try {
            while (!pending.isEmpty()) {
                int[] patch = pending.first();
                presenting.set(patch[0], patch[1], patch[2], patch[3]);
                int result = target.present(presented(), presenting);
                lastPresent = SystemClock.uptimeMillis();
                if (result == Session.BUSY) {
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
            displayFailed();
        }
    }

    // Input

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (app.paletteEditor != null || app.loading || document == null || fill != null || gradientCommit
                || !app.resumed || !hasWindowFocus()) return true;
        if (historyTap(event)) return true;
        if (navigationGesture(event)) return true;
        if (app.pickingShade || pickPointer != -1) { sampleShadeGesture(event); return true; }
        if (hasGradient()) return true;
        if (fillGesture) { continueFillGesture(event); return true; }
        if (shapeStroke != null) { continueShape(event); return true; }
        if (lassoCount > 0 || selectionHandle != FloatingSelection.NONE) { continueSelection(event); return true; }
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
        if (selection != null) {
            float[] knob = selectionKnob();
            int handle = selection.hit(samplePoint[0], samplePoint[1], knob, dp(24) / pageScale());
            if (handle != FloatingSelection.NONE) { grabSelection(event, index, handle); return true; }
            placeSelection();
        }
        if (samplePoint[0] < 0 || samplePoint[1] < 0
                || samplePoint[0] >= document.width || samplePoint[1] >= document.height) return false;
        PaintState paint = app.paint;
        // Masking fluid lies over every layer, hidden ones too.
        boolean fluid = paint.maskingFluid;
        if (!fluid && !document.layerVisible(document.activeLayer())) {
            app.message("This layer is hidden. Open Layers to show it or select another layer."); return false;
        }
        // A stylus's eraser end, or a stroke begun with its side button held, erases with
        // the Eraser tool, whatever tool is selected.
        boolean penEraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
                || (event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        ToolSettings settings = penEraser ? app.library.builtin(ToolSettings.Tool.ERASER) : app.library.current();
        if (settings.tool == ToolSettings.Tool.LASSO) { beginLasso(event, index); return true; }
        boolean erasing = paint.eraseMode && settings.supportsEraseMode();
        // Brushes paint into a wet canvas; the brush pens, and brushes, shapes and fills in
        // watercolor mode, bring their own water, which on a dry canvas wets only where it lands.
        // Masking fluid is laid dry, opaque and in full.
        boolean watercolor = !erasing && !fluid && paint.transparentPaint && paint.watercolor;
        washing = watercolor && (settings.tool == ToolSettings.Tool.SHAPES || settings.tool == ToolSettings.Tool.FILL);
        boolean wets = !erasing && !fluid && (settings.tool.water || washing
                || settings.isBrush() && (paint.wetCanvas || watercolor));
        if (!wets || wet != null && wet.coversCanvas() != paint.wetCanvas) dryWet();
        if (wets) wetPaint();
        document.paintMaskingFluid(fluid);
        pointer = event.getPointerId(index);
        getParent().requestDisallowInterceptTouchEvent(true);
        int gray = fluid ? 0 : paint.gray, shade = erasing ? ToneDocument.ERASE : gray;
        if (settings.tool == ToolSettings.Tool.SHAPES) { beginShape(settings, shade); return true; }
        if (settings.tool == ToolSettings.Tool.FILL) { beginFill(settings, shade); return true; }
        cancelWetCallback();
        // With opaque paint the wet brush pen keeps the selected ink: each stroke starts dipped in it again.
        if (settings.holdsInk() && !paint.transparentPaint && !erasing) paint.brushInk.load(gray);
        stroke = ToolStrokes.create(document, settings, gray, wet, paint.transparentPaint && !fluid, erasing, app.prefs.smoothEdges(),
                settings.tool.flowing ? (settings.holdsInk() ? paint.brushInk : null) : watercolor ? paint.brushLoad : null);
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
        if (lassoCount > 0) { lassoCount = 0; endPenPreview(); eraseGuide(); flush(true); }
        if (selectionHandle != FloatingSelection.NONE) releaseSelection();
        placeSelection();
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
        renderDirty(); flush(true); app.showBrushInk();
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
        if (washing) {
            int[] spans = shapeStroke.spans();
            renderShape(shapeStroke.cancel(display, viewportBitmap != null)); shapeStroke = null; pointer = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            wash(() -> WatercolorWash.spans(wet, spans, document.width, document.height, app.paint.gray));
            return;
        }
        boolean changed = shapeStroke.finish(); shapeStroke = null; pointer = -1;
        // The committed geometry matches the preview exactly; keep its native raster,
        // unless masking fluid kept the shape from some of it, or the shape was masking fluid.
        if (document.hasMask()) { renderDirty(); flush(true); } else document.clearDirty();
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
        else if (lassoCount > 0) drawLasso();
        else if (selectionHandle != FloatingSelection.NONE) {
            selection.drag(selectionHandle, grabX, grabY, dragX, dragY); showSelection();
        } else return;
        flush(true); previewFrameCount++;
    }
    private void renderShape(Rect dirty) {
        if (dirty.isEmpty()) return;
        // Preview pixels are already rasterized natively. Submit one complete frame.
        dirty.inset(-2, -2);
        if (!dirty.intersect(0, 0, document.width, document.height)) return;
        queue(dirty);
    }

    // The lasso and floating selections

    /** Whether a lifted piece or imported picture is floating over the page. */
    boolean hasSelection() { return selection != null; }
    /** How many view pixels a page pixel spans. */
    private float pageScale() { return pageToView.mapRadius(1); }
    /** The selection's turning knob, beside the edge facing the top of the screen, and on it. */
    private float[] selectionKnob() {
        float[] up = {0, -1};
        viewToPage.mapVectors(up);
        // The page area on screen, less room for the knob's own dot.
        RectF visible = new RectF(0, 0, getWidth(), getHeight());
        viewToPage.mapRect(visible);
        if (!visible.intersect(0, 0, document.width, document.height)) visible.set(0, 0, document.width, document.height);
        float margin = dp(14) / pageScale();
        visible.inset(Math.min(margin, visible.width() / 2), Math.min(margin, visible.height() / 2));
        return selection.knob(up[0], up[1], dp(40) / pageScale(), new float[]{visible.left, visible.top, visible.right, visible.bottom});
    }
    /** Ends the pen's hold on the canvas after a lasso loop or selection drag. */
    private void endPenPreview() {
        removeCallbacks(previewFrame); previewFrameScheduled = false;
        pointer = -1; endFastPreview(); getParent().requestDisallowInterceptTouchEvent(false);
    }
    private void beginLasso(MotionEvent event, int index) {
        // Wet paint would go on changing the layer under the piece.
        dryWet();
        pointer = event.getPointerId(index);
        getParent().requestDisallowInterceptTouchEvent(true);
        lassoCount = lassoShown = 0; addLassoPoint();
        beginFastPreview();
    }
    /** Adds {@link #samplePoint} to the loop, skipping points too close to the last to show. */
    private void addLassoPoint() {
        float x = samplePoint[0], y = samplePoint[1];
        if (lassoCount > 0 && Math.hypot(x - lasso[2*lassoCount-2], y - lasso[2*lassoCount-1]) < dp(3) / pageScale()) return;
        if (2 * lassoCount + 2 > lasso.length) lasso = java.util.Arrays.copyOf(lasso, 2 * lasso.length);
        lasso[2*lassoCount] = x; lasso[2*lassoCount+1] = y; lassoCount++;
    }
    /** Draws the loop's points not yet shown, joined to those that are. */
    private void drawLasso() {
        int from = Math.max(0, lassoShown - 1), count = lassoCount - from;
        if (count < 2 || presented() == null) return;
        float[] points = presentedPoints(java.util.Arrays.copyOfRange(lasso, 2 * from, 2 * lassoCount), count);
        guide.add(presented(), points, count, false, null, 0, this::queueView);
        lassoShown = lassoCount;
    }
    private void grabSelection(MotionEvent event, int index, int handle) {
        pointer = event.getPointerId(index);
        getParent().requestDisallowInterceptTouchEvent(true);
        selectionHandle = handle; grabX = dragX = samplePoint[0]; grabY = dragY = samplePoint[1];
        selection.grab(handle);
        beginFastPreview();
    }
    /** Follows the pen drawing a loop or dragging the selection. */
    private void continueSelection(MotionEvent event) {
        int action = event.getActionMasked(), index = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || index < 0) {
            // Keep the piece where it got to; an unfinished loop lifts nothing.
            if (lassoCount > 0) { lassoCount = 0; endPenPreview(); eraseGuide(); flush(true); }
            else releaseSelection();
            return;
        }
        boolean up = pointerUp(event, pointer);
        if (action == MotionEvent.ACTION_MOVE || up) {
            if (lassoCount > 0) for (int h = 0; h < event.getHistorySize(); h++) {
                pagePoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h)); addLassoPoint();
            }
            pagePoint(event.getX(index), event.getY(index));
            if (lassoCount > 0) addLassoPoint(); else { dragX = samplePoint[0]; dragY = samplePoint[1]; }
            if (!up) schedulePreviewFrame();
        }
        if (!up) return;
        drawPreviewFrame();
        if (selectionHandle != FloatingSelection.NONE) { releaseSelection(); return; }
        // A tap, such as one that placed the last piece, draws no loop.
        boolean loop = lassoCount >= 3;
        FloatingSelection lifted = loop ? FloatingSelection.lift(document, lasso, lassoCount) : null;
        lassoCount = 0; endPenPreview(); eraseGuide();
        if (lifted == null) { flush(true); if (loop) app.message("Nothing on this layer inside the loop."); return; }
        floatSelection(lifted);
    }
    /** Ends a selection drag, leaving the piece floating where it got to. */
    private void releaseSelection() {
        selection.release(); selectionHandle = FloatingSelection.NONE; endPenPreview();
        // A knob that would have left the screen during a turn moves inside now.
        eraseGuide(); drawSelectionFrame(); flush(true);
    }
    /** Floats {@code piece} over the page, placing any piece already floating first. */
    void floatSelection(FloatingSelection piece) {
        finishStroke(); dryWet();
        selection = piece; selectionPreview = new SelectionPreview(document, piece);
        showSelection(); flush(true);
    }
    /** Redraws the piece where it lies now, with its frame. */
    private void showSelection() {
        eraseGuide();
        Rect dirty = selectionPreview.show(display, viewportBitmap != null);
        if (!dirty.isEmpty()) queue(dirty);
        drawSelectionFrame();
    }
    /** Outlines the piece, with a dot on each corner to scale it and a knob to turn it. */
    private void drawSelectionFrame() {
        if (selection == null || presented() == null) return;
        float[] corners = presentedPoints(selection.corners(), 4), knob = selectionKnob();
        guide.add(presented(), corners, 4, true, corners, dp(5), this::queueView);
        float[] stem = presentedPoints(new float[]{knob[2], knob[3], knob[0], knob[1]}, 2);
        guide.add(presented(), stem, 2, false, new float[]{stem[2], stem[3]}, dp(8), this::queueView);
    }
    /** Lays the floating piece on the page as one undo step. */
    void placeSelection() {
        if (selection == null) return;
        FloatingSelection piece = selection; Rect area = endSelection();
        boolean changed;
        try { changed = piece.place(document); }
        catch (IllegalStateException error) { changed = false; app.message(error.getMessage()); }
        // The preview showed nearest pixels; the page now holds them blended.
        renderArea(area); renderDirty(); flush(true);
        if (changed) app.recovery();
    }
    /** Drops the floating piece, leaving the page as it was; false when none floats. */
    boolean cancelSelection() {
        if (selection == null) return false;
        if (selectionHandle != FloatingSelection.NONE) { selectionHandle = FloatingSelection.NONE; endPenPreview(); }
        renderArea(endSelection()); flush(true);
        return true;
    }
    /** Forgets the floating piece and its frame; returns the page pixels its preview covered. */
    private Rect endSelection() {
        eraseGuide();
        Rect area = selectionPreview.area();
        selection = null; selectionPreview = null;
        return area;
    }
    /** Re-renders these page pixels from the document and queues them. */
    private void renderArea(Rect area) {
        if (area.intersect(0, 0, display.getWidth(), display.getHeight())) { render(area); queue(area); }
    }

    // Fills and gradients

    private void beginFill(ToolSettings settings, int shade) {
        fillGesture = true;
        fillStartX = fillEndX = samplePoint[0]; fillStartY = fillEndY = samplePoint[1];
        // Masking fluid has one shade, so it fills flat.
        fillGradient = app.paint.maskingFluid ? ToolSettings.Gradient.FLAT : settings.gradient;
        fillOriginalGray = app.paint.gray; fillShade = shade;
        fillTolerance = fillGradient == ToolSettings.Gradient.FLAT ? 0 : settings.tolerance;
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
            fill = washing ? FloodFill.region(document, (int)fillStartX, (int)fillStartY, fillTolerance)
                    : new FloodFill(document, (int)fillStartX, (int)fillStartY, fillShade, fillTolerance);
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
        boolean changed = fill.finish();
        java.util.BitSet region = fill.region(); fill = null;
        app.toolbar.operationStatus.setText("");
        if (region != null) { wash(() -> WatercolorWash.pixels(wet, region, document.width, document.height, fillShade)); return; }
        renderDirty(); flush(true);
        if (changed) app.recovery();
    }
    /**
     * Lays a shape's or flat fill's wash as one stroke of the wet paint, shown at once as a
     * glaze, as a watercolor brush's is, and run once there is time.
     */
    private void wash(Runnable queue) {
        wetPaint(); document.begin(); wet.beginStroke();
        queue.run();
        boolean changed = wet.finishStroke();
        renderDirty(); flush(true);
        if (changed) app.recovery();
        scheduleWet();
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
            if (app.pickedShade) {
                app.setEraseMode(false); app.setMaskingFluid(false); app.setPickingShade(false); applyGradient();
                app.loadInk(app.paint.gray); app.saveToolState();
            }
        }
    }

    // Finger taps

    /** A two-finger tap undoes and a three-finger tap redoes, like the header buttons. */
    private boolean historyTap(MotionEvent event) {
        if (blocksFingerGesture() || !Ui.fingersOnly(event) || app.busy()) { fingerTaps.reset(); return false; }
        int count = event.getPointerCount();
        float[] x = new float[count], y = new float[count]; int[] ids = new int[count];
        for (int i = 0; i < count; i++) { x[i] = event.getX(i); y[i] = event.getY(i); ids[i] = event.getPointerId(i); }
        int fingers = fingerTaps.event(event.getActionMasked(), event.getEventTime(), ids, x, y, dp(18));
        if (fingers == 0) return false;
        endNavigation();
        if (fingers == 2) app.undo(); else app.history(document::redo);
        return true;
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
        if (app.navigationLocked && app.rotationLocked) { touchBlocked = true; endNavigation(); return true; }
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
            fingerTwist.begin(fingerLine(event, 0, 1));
            disconnectDisplay(); navigating = true;
            updateViewport(); connectDisplay();
            getParent().requestDisallowInterceptTouchEvent(true); return true;
        }
        int a = event.findPointerIndex(fingerA), b = event.findPointerIndex(fingerB);
        if (a < 0 || b < 0) { touchBlocked = true; endNavigation(); return true; }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = (event.getX(a)+event.getX(b))/2, y = (event.getY(a)+event.getY(b))/2;
            float span = (float)Math.hypot(event.getX(b)-event.getX(a), event.getY(b)-event.getY(a));
            float line = fingerLine(event, a, b);
            if (span < dp(24)) { fingerTwist.skip(line); return true; }
            // Each lock holds back only its own part: a locked turn ignores the twist, and
            // locked zoom and pan leave the page where it is while the fingers turn it about themselves.
            float turn = app.rotationLocked ? 0 : fingerTwist.move(line);
            if (app.navigationLocked) viewport.gesture(1, turn, fingerX, fingerY, fingerX, fingerY);
            else viewport.gesture(span/fingerSpan, turn, fingerX, fingerY, x, y);
            fingerX = x; fingerY = y; fingerSpan = span;
            if (!navigationFrameScheduled) {
                navigationFrameScheduled = true;
                postDelayed(navigationFrame, Math.max(0, 16-(SystemClock.uptimeMillis()-lastNavigationFrame)));
            }
        }
        return true;
    }
    /** The angle of the line from one finger to the other, in degrees clockwise from the screen's right. */
    private static float fingerLine(MotionEvent event, int from, int to) {
        return (float)Math.toDegrees(Math.atan2(event.getY(to)-event.getY(from), event.getX(to)-event.getX(from)));
    }
    void endNavigation() {
        fingerA = fingerB = -1;
        if (!navigating) return;
        // A twist that ends near upright or a quarter turn settles onto it, about where the fingers were.
        float settle = app.rotationLocked ? 0 : viewport.snapTurn();
        if (settle != 0) { viewport.gesture(1, settle, fingerX, fingerY, fingerX, fingerY); navigationFrameScheduled = true; }
        if (navigationFrameScheduled) drawNavigationFrame();
        navigating = false; getParent().requestDisallowInterceptTouchEvent(false);
        if (viewport.zoom == 1 && viewport.scale() == 1 && viewport.angle == 0) {
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
        // before the next idle callback. Only completed sweeps wait a frame;
        // flowing water runs ahead, except a small wash's slowing tail.
        if (wet != null && wet.framePending()) scheduleWet(1);
        else if (wet != null && wet.flowing())
            scheduleWet((int)Math.max(1, wet.flowFrameMillis() - (SystemClock.uptimeMillis() - wetFrameStarted)));
        else scheduleWet(WetWatercolor.FRAME_MS);
    }
    private void scheduleWet(int delayMillis) {
        if (wet != null && wet.isAnimating() && !wetHeld() && app.resumed && hasWindowFocus() && !app.loading && !navigating) {
            // Start reading the tilt now, so it is ready when the paint first moves.
            app.canvasGravity.listen(true);
            if (!wetScheduled) { wetScheduled = true; postDelayed(wetStep, delayMillis); }
            if (wet.running()) app.wetDroplet.start();
        }
    }
    /** The wet paint, started on the canvas the wet canvas setting asks for if there is none. */
    private void wetPaint() {
        if (wet == null) wet = app.paint.wetCanvas ? new WetWatercolor(document, app.paint.wetness) : new WetWatercolor(document);
    }
    /** The stroke being drawn keeps wet paint still until it is finished. */
    private boolean wetHeld() { return stroke != null && stroke.holdsWetPaint(); }
    /** About how much wet paint is left to move, or 0 when it isn't moving. */
    int wetWork() { return wetScheduled && wet != null ? Math.max(1, wet.remainingWork()) : 0; }
    private void cancelWetCallback() {
        removeCallbacks(wetStep); wetQueue.removeIdleHandler(wetIdle); wetScheduled = false;
    }
    private void advanceWet() {
        wetScheduled = false;
        if (wet == null || wetHeld() || !app.resumed || !hasWindowFocus() || app.loading || navigating) {
            app.canvasGravity.listen(false); return;
        }
        boolean drawing = stroke != null;
        long budget = wetBudget.nanos(drawing, wet.strokePixels(), wet.activePixels(), SystemClock.uptimeMillis());
        // Submit the pen's outstanding pixels before adding more display work.
        if (budget == 0 || !pending.isEmpty()) { scheduleWet(16); return; }
        long start = System.nanoTime();
        app.canvasGravity.apply(wet);
        int frame = wet.frame();
        long sliceStarted = SystemClock.uptimeMillis();
        wetChanged |= wet.advance(drawing, wetBudget.tiles(budget, drawing), budget / 2, drawing ? 96 : 192);
        long computed = System.nanoTime();
        if (wet.frame() != frame) wetFrameStarted = sliceStarted;
        // Presenting every slice of fast-running water would cost more than working it out.
        // While drawing, each slice still shows its own changes, or the pen's next event
        // would have to render all the water's held-back ones.
        long now = SystemClock.uptimeMillis();
        boolean show = drawing || !wet.flowing() || now - lastWetShown >= WET_SHOW_MS;
        if (show) { renderDirty(); lastWetShown = now; }
        long rendered = System.nanoTime();
        if (show) flush(true);
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
    boolean stroking() { return stroke != null; }
    /**
     * Lays in full the brush dabs so far shown only as a glaze, e.g. before the brush's ink
     * changes outside a stroke: they trade ink with it in order. False if there were none.
     */
    boolean layQueuedPaint() {
        if (wet == null || wet.queuedDabs() == 0) return false;
        wetChanged |= wet.layQueued(stroke != null);
        renderDirty(); flush(true);
        return true;
    }
    /** Stops wet blending, leaving the paint as it is. */
    void dryWet() {
        cancelWetCallback();
        boolean laid = layQueuedPaint();
        // Running water's last changes may not have reached the panel yet.
        if (!laid && wet != null && wet.flowing()) { renderDirty(); flush(true); }
        app.canvasGravity.listen(false);
        wet = null; if (app.wetDroplet != null) app.wetDroplet.dried();
        if (wetChanged) { wetChanged = false; app.recovery(); }
    }

    void close() {
        finishStroke(); dryWet(); disconnectDisplay(); input.close();
        if (display != null) display.recycle();
        if (viewportBitmap != null) { viewportBitmap.close(); viewportBitmap = null; }
        display = null; renderPixels = new int[0]; document = null;
    }
}
