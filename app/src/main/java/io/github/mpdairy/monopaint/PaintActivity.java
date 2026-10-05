package io.github.mpdairy.monopaint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * The painting screen. It owns the document and current selection, builds the layout,
 * turns it for app-only rotation, and coordinates the panels.
 *
 * <p>Layout, from the outside in: {@link NomadPreviewLayout} (optional smaller-panel
 * simulation) → {@link QuarterTurnLayout} turning the whole UI → {@link #root}, holding
 * the header/color bar ({@link #palette}) and the {@link #body} with the {@link Toolbar}
 * and the {@link DrawingPad} canvas. Android itself always stays in portrait.
 */
public final class PaintActivity extends Activity implements ControlHost {
    // Document
    DocumentStore store;
    DrawingBook book;
    /** Saved path of the open drawing, or empty if never saved. */
    String drawingName = "";
    String saveError;
    boolean resumed, loading = true, destroyed, saving;

    // Settings and the current selection
    SharedPreferences preferences;
    PaintPreferences prefs;
    ToolLibrary library;
    final PaintState paint = new PaintState();
    boolean navigationLocked = true;
    /** The eyedropper is active; {@link #pickedShade} once it has sampled the canvas. */
    boolean pickingShade, pickedShade;
    /** Shade to restore if the eyedropper is cancelled. */
    int pickOriginalShade;

    // App-only rotation: Android and the e-ink driver always remain in portrait.
    int appRotation = Surface.ROTATION_0;
    boolean landscape, toolboxRight;
    /** Degrees the header strip is turned; 90 in landscape. */
    int toolbarTurn;
    /** The untransformed event, whose pen tilt axes Android would otherwise rotate. */
    MotionEvent physicalPenEvent;

    // Layout
    LinearLayout root, body, palette, headerControls, leftHeader, rightHeader, menuControls;
    QuarterTurnLayout paletteFrame, orientationFrame;
    NomadPreviewLayout previewFrame;
    DrawingPad pad;
    Toolbar toolbar;
    RotationPrompt rotationPrompt;
    CanvasGravity canvasGravity;
    ShadePicker shadePicker;
    WetnessBar wetnessBar;
    ToolButton wetButton, opaqueButton, transparentButton, eyedropperButton, eraseButton;
    ImageButton menuButton, previousPage, nextPage, addPage;
    PageActionButton rotateButton, pagesButton;
    TextView pageNumber;
    View clearButton;

    // Panels. At most one popup is open at a time.
    PopupWindow toolPicker, filePopup, layersPopup;
    PaletteEditor paletteEditor;
    AlertDialog pageOverview;
    QuarterTurnLayout pagePanel;
    private TextView popupPageNumber;
    private PageActionButton popupPrevious, popupNext, popupAdd;
    private final Rect pagePanelBounds = new Rect();
    TextView gradientHint;
    private final Runnable gradientHintTask = this::showGradientHint;
    /** Swallows the rest of a touch that dismissed a panel. */
    private boolean dismissingTouch;
    boolean pageActionPending;
    final SelectionFeedback selectionFeedback = new SelectionFeedback();
    /** Rubbing in the color bar or palette loads a brush with limited paint. */
    final PaintRub paintRub = new PaintRub(this::loadPaint);
    final SelectionFeedback layerFeedback = new SelectionFeedback();

    @Override protected void attachBaseContext(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        // Increase all app text, including native dialogs, while respecting the user's font scale.
        configuration.fontScale *= 1.2f;
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences(PaintPreferences.FILE, MODE_PRIVATE);
        prefs = new PaintPreferences(preferences);
        navigationLocked = prefs.navigationLocked();
        prefs.loadPaint(paint);
        library = prefs.toolLibrary(() -> message("Could not load presets. A recovery copy has been kept."));
        if (library.activeId().isEmpty() && library.current().isBrush()) library.edit(library.current().asBrush());
        paint.eraseMode &= library.current().supportsEraseMode();
        store = new DocumentStore(getFilesDir());

        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        buildHeader();
        paletteFrame = new QuarterTurnLayout(this); paletteFrame.addView(palette);
        root.addView(paletteFrame);
        body = new LinearLayout(this);
        toolbar = new Toolbar(this);
        body.addView(toolbar.toolScroll, new LinearLayout.LayoutParams(dp(64), -1));
        toolbar.rebuildTools();
        pad = new DrawingPad(this); body.addView(pad, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        orientationFrame = new QuarterTurnLayout(this);
        orientationFrame.addView(root);
        orientationFrame.afterLayout = () -> { pad.disconnectDisplay(); pad.updateViewport(); pad.post(pad::connectDisplay); };
        previewFrame = new NomadPreviewLayout(this);
        previewFrame.addView(orientationFrame);
        previewFrame.setRotationHint(rotateButton, menuButton, dp(48), dp(8));
        previewFrame.afterLayout = () -> pad.post(pad::connectDisplay);
        previewFrame.setEnabledPreview(nomadMode());
        setContentView(previewFrame);
        applyToolboxSide(); updatePages();
        rotationPrompt = new RotationPrompt(this, rotateButton);
        canvasGravity = new CanvasGravity(this);
        pad.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) { pad.disconnectDisplay(); pad.post(pad::connectDisplay); }
        });
        store.recover((recovered, error) -> runOnUiThread(() -> {
            if (destroyed) return;
            loading = false;
            if (error != null) {
                // Keep the failed recovery on disk until the user explicitly chooses a new drawing.
                loading = true;
                showDialog(new AlertDialog.Builder(this).setTitle("Could not recover drawing")
                        .setMessage(error.getMessage() + "\nYou can open a saved drawing or start a new one.")
                        .setPositiveButton("Open", (d, w) -> openDrawing())
                        .setNegativeButton("New", (d, w) -> startNewDrawing())
                        .setCancelable(false));
            } else {
                drawingName = recovered == null ? "" : recovered.path;
                replaceBook(recovered == null ? null : recovered.book);
            }
        }));
    }

    /** Builds the header strip: menu, undo/redo/clear, the color bar and the page controls. */
    private void buildHeader() {
        palette = new LinearLayout(this);
        leftHeader = new LinearLayout(this); rightHeader = new LinearLayout(this);
        rightHeader.setGravity(Gravity.END);
        // Size each control group to its contents; the shade strip gets all spare space.
        palette.addView(leftHeader, new LinearLayout.LayoutParams(-2, dp(48)));
        LinearLayout colors = new LinearLayout(this);
        palette.addView(colors, new LinearLayout.LayoutParams(0, dp(48), 1));
        palette.addView(rightHeader, new LinearLayout.LayoutParams(-2, dp(48)));

        menuButton = new PageActionButton(this, this, true);
        menuButton.setImageResource(R.drawable.ic_menu); menuButton.setContentDescription("File menu");
        menuButton.setOnClickListener(v -> {
            if (busy()) return;
            pad.finishStroke(); setPickingShade(false);
            fileMenu(menuButton);
        });
        menuControls = new LinearLayout(this);
        menuControls.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        headerAction("Undo", R.drawable.ic_undo, () -> history(pad.document::undo));
        headerAction("Redo", R.drawable.ic_redo, () -> history(pad.document::redo));
        clearButton = headerAction("Clear", R.drawable.ic_clear, this::clearDrawing);
        leftHeader.addView(menuControls);

        rotateButton = pageAction("Rotate", R.drawable.ic_rotate, () -> rotationPrompt.accept(), false);
        rotateButton.setBackground(Ui.outline(dp(2), Color.BLACK, dp(8))); rotateButton.setStateListAnimator(null);
        rotateButton.setVisibility(View.INVISIBLE);

        headerControls = new LinearLayout(this);
        previousPage = headerPageButton("Previous page", R.drawable.ic_previous, () -> changePage(book.index()-1));
        pageNumber = new PageLabel(this, this); pageNumber.setOnClickListener(v -> showPageOverview());
        pageNumber.setTextSize(15); pageNumber.setTypeface(null, Typeface.BOLD);
        pageNumber.setGravity(Gravity.CENTER); headerControls.addView(pageNumber, new LinearLayout.LayoutParams(dp(76), dp(48)));
        nextPage = headerPageButton("Next page", R.drawable.ic_chevron, () -> changePage(book.index()+1));
        addPage = headerPageButton("Add page", R.drawable.ic_new, this::addPage);
        pagesButton = new PageActionButton(this, this, true); pagesButton.setImageResource(R.drawable.ic_pages);
        pagesButton.setContentDescription("Pages");
        pagesButton.setOnClickListener(v -> { if (!busy()) showPagePanel(); });

        wetButton = colorBarButton(colors, "Wet canvas", R.drawable.ic_water_drop, () -> setWetCanvas(!paint.wetCanvas));
        wetButton.iconHalf = 18; wetButton.iconOffset = 8; wetButton.markerLeft = true;
        wetnessBar = new WetnessBar(this);
        colors.addView(wetnessBar, new LinearLayout.LayoutParams(dp(24), dp(48)));
        eyedropperButton = colorBarButton(colors, "Pick color from canvas", R.drawable.ic_eyedropper, () -> {
            setPickingShade(!pickingShade);
            if (pickingShade) pad.dryWet();
        });
        eyedropperButton.markerBelow = true;
        eyedropperButton.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) hideGradientHint();
            return false;
        });
        shadePicker = new ShadePicker(this);
        colors.addView(shadePicker, new LinearLayout.LayoutParams(0, dp(48), 1));
        eraseButton = colorBarButton(colors, "Erase with current tool", R.drawable.ic_eraser, this::toggleEraseMode);
        eraseButton.markerBelow = true;
        transparentButton = colorBarButton(colors, "Transparent paint", R.drawable.ic_transparent, () -> setTransparentPaint(true));
        opaqueButton = colorBarButton(colors, "Opaque paint", R.drawable.ic_opaque, () -> setTransparentPaint(false));
        refreshPaintModes();
        rightHeader.addView(headerControls);
    }

    int dp(float value) { return Ui.dp(this, value); }
    int appTurn() { return appRotation == Surface.ROTATION_270 ? -90 : appRotation*90; }

    // ControlHost
    @Override public boolean busy() { return loading || saving || pad == null || pad.document == null || pad.fill != null || pad.gradientCommit; }
    @Override public boolean pageActionPending() { return pageActionPending; }
    @Override public int toolbarTurn() { return toolbarTurn; }
    @Override public SelectionFeedback selectionFeedback() { return selectionFeedback; }

    // Controls

    /**
     * An icon control. Pressing it first ends the current pen gesture and leaves the
     * eyedropper, except that color controls may act on a pending gradient.
     */
    ToolButton iconButton(String title, int icon, Runnable action) {
        ToolButton button = new ToolButton(this, this);
        button.setTag(title); button.setContentDescription(title);
        button.setPadding(dp(10), 0, dp(8), 0);
        button.iconOnly(icon);
        button.setOnClickListener(v -> {
            if (busy()) return;
            boolean colorControl = button == eyedropperButton || button == eraseButton;
            if (button != eyedropperButton) setPickingShade(false);
            if (!colorControl || !pad.hasGradient()) pad.finishStroke();
            action.run();
        });
        return button;
    }
    private ToolButton headerButton(LinearLayout parent, String title, int icon, Runnable action) {
        ToolButton button = iconButton(title, icon, action);
        button.headerIcon = true; button.setBackgroundColor(Color.WHITE);
        parent.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }
    private ToolButton headerAction(String title, int icon, Runnable action) { return headerButton(menuControls, title, icon, action); }
    /** A color bar toggle that shows its state with a dot. */
    private ToolButton colorBarButton(LinearLayout parent, String title, int icon, Runnable action) {
        ToolButton button = headerButton(parent, title, icon, action);
        button.alwaysDot = true;
        return button;
    }
    /** A button whose action runs after its pressed outline reaches the panel. */
    private PageActionButton pageAction(String name, int icon, Runnable action, boolean header) {
        PageActionButton button = new PageActionButton(this, this, header);
        button.setImageResource(icon); button.setContentDescription(name);
        button.setOnClickListener(v -> {
            if (busy() || pageActionPending) return;
            button.press.set(true); pageActionPending = true;
            // Submit the press before page work or the full orientation change.
            button.post(() -> {
                try { if (resumed && !destroyed && button.isAttachedToWindow()) { setPickingShade(false); action.run(); } }
                finally { pageActionPending = false; button.press.releaseLater(); }
            });
        });
        return button;
    }
    private ImageButton headerPageButton(String name, int icon, Runnable action) {
        PageActionButton button = pageAction(name, icon, action, true);
        headerControls.addView(button, new LinearLayout.LayoutParams(dp(40), dp(48))); return button;
    }
    /** A drag shadow that matches the app's turned UI. */
    View.DragShadowBuilder turnedShadow(View view) {
        return new View.DragShadowBuilder(view) {
            @Override public void onDrawShadow(Canvas canvas) {
                canvas.save(); canvas.rotate(appTurn(), view.getWidth()/2f, view.getHeight()/2f);
                super.onDrawShadow(canvas); canvas.restore();
            }
        };
    }

    // Pages

    void updatePages() {
        if (pageNumber == null) return;
        int count = book == null ? 1 : book.count(), index = book == null ? 0 : book.index();
        // Text and enabled states use Android's next frame. Artwork is already
        // submitted by showPage; updating the counter must not add an e-ink flash.
        updatePageRow(pageNumber, previousPage, nextPage, addPage, index, count);
        if (pagesButton != null) pagesButton.setContentDescription("Pages. Page " + (index+1) + " of " + count);
        if (pagePanel != null) updatePageRow(popupPageNumber, popupPrevious, popupNext, popupAdd, index, count);
    }
    private void updatePageRow(TextView label, View previous, View next, View add, int index, int count) {
        label.setText((index+1) + " / " + count); label.setContentDescription("Page " + (index+1) + " of " + count + ". Show all pages");
        Ui.setDimmed(previous, book != null && index > 0, .3f);
        Ui.setDimmed(next, book != null && index+1 < count, .3f);
        Ui.setDimmed(add, book != null && count < DrawingBook.MAX_PAGES, .3f);
    }
    private void replaceBook(DrawingBook replacement) {
        if (replacement == null) { pad.replace(null); return; }
        pad.finishStroke(); book = replacement; pad.showPage(book.current()); updatePages();
    }
    private void changePage(int index) {
        if (busy() || index < 0 || index >= book.count()) return;
        pad.finishStroke(); pad.dryWet();
        try { book.select(index); pad.showPage(book.current()); updatePages(); recovery(); }
        catch (java.io.IOException error) { message("Could not open page: " + error.getMessage()); }
    }
    private void addPage() {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        try { book.addPage(); pad.showPage(book.current()); updatePages(); recovery(); }
        catch (java.io.IOException error) { message("Could not add page: " + error.getMessage()); }
    }
    /** The compact page row shown from the Pages button in Nomad layout. */
    private void showPagePanel() {
        if (pagePanel != null) { closePagePanel(); return; }
        if (busy()) return;
        dismissPanels(); hideGradientHint();
        pad.suspend();
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.setBackground(Ui.outline(dp(1), Color.BLACK, 0));
        popupPrevious = pageAction("Previous page", R.drawable.ic_previous, () -> changePage(book.index()-1), false);
        popupNext = pageAction("Next page", R.drawable.ic_chevron, () -> changePage(book.index()+1), false);
        popupAdd = pageAction("Add page", R.drawable.ic_new, this::addPage, false);
        popupPageNumber = new TextView(this); popupPageNumber.setTextSize(15); popupPageNumber.setTextColor(Color.BLACK);
        popupPageNumber.setOnClickListener(v -> showPageOverview());
        popupPageNumber.setTypeface(null, Typeface.BOLD); popupPageNumber.setGravity(Gravity.CENTER);
        row.addView(popupPrevious, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(popupPageNumber, new LinearLayout.LayoutParams(dp(76), dp(48)));
        row.addView(popupNext, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(popupAdd, new LinearLayout.LayoutParams(dp(48), dp(48)));
        updatePageRow(popupPageNumber, popupPrevious, popupNext, popupAdd, book.index(), book.count());
        QuarterTurnLayout panel = new QuarterTurnLayout(this); panel.setTurn(appTurn()); panel.addView(row);
        RectF anchor = Popups.bounds(root, pagesButton);
        // Sum rounded child pixels: at density 300, dp(56) is one pixel shorter
        // than dp(48)+2*dp(4), which clips buttons and disables direct feedback.
        int width = 3*dp(48)+dp(76)+2*dp(4), height = dp(48)+2*dp(4);
        float left = landscape ? (toolboxRight ? anchor.left-width : anchor.right) : anchor.right-width;
        float top = landscape ? anchor.bottom-height : anchor.bottom;
        RectF bounds = Popups.rect(Popups.clamp(left, 0, root.getWidth()-width), Popups.clamp(top, 0, root.getHeight()-height), width, height);
        // The panel is a child of the preview frame, not a popup window.
        PanelCoordinates.fromView(root).mapRect(bounds);
        Matrix inverse = new Matrix(); PanelCoordinates.fromView(previewFrame).invert(inverse);
        inverse.mapRect(bounds); bounds.roundOut(pagePanelBounds);
        pagePanel = panel;
        // Lay out the real touch targets synchronously, then submit only their
        // bounded pixels through the same fast path as toolbar selection.
        selectionFeedback.update(previewFrame, new Rect(pagePanelBounds), () -> previewFrame.showPanel(panel, pagePanelBounds));
    }
    void closePagePanel() {
        if (pagePanel == null) return;
        Rect bounds = new Rect(pagePanelBounds); pagePanel = null;
        selectionFeedback.update(previewFrame, bounds, previewFrame::hidePanel);
        pagePanelBounds.setEmpty(); popupPageNumber = null; popupPrevious = null; popupNext = null; popupAdd = null;
        pad.post(pad::connectDisplay);
    }
    /** The thumbnail grid of every page. */
    private void showPageOverview() {
        if (busy() || pageOverview != null) return;
        dismissPanels(); hideGradientHint();
        pad.suspend();
        DrawingBook source = book;
        PageOverview grid = new PageOverview(this, source.snapshot(), appRotation);
        // Bound the scrolling content in app coordinates, including rotated Nomad mode.
        grid.setLayoutParams(new FrameLayout.LayoutParams(-1, Math.max(dp(120), Math.min(dp(540), root.getHeight()-dp(180)))));
        FrameLayout content = new FrameLayout(this); content.addView(grid);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Pages").setView(content)
                .setNegativeButton("Close", null).create();
        pageOverview = dialog;
        grid.setOnItemClickListener((parent, view, index, id) -> {
            dialog.dismiss();
            if (book == source && index != book.index()) changePage(index);
        });
        dialog.setOnDismissListener(unused -> {
            grid.close(); if (pageOverview == dialog) pageOverview = null; pad.post(pad::connectDisplay);
        });
        dialog.show(); compactDialog(dialog, 600); grid.setSelection(source.index());
    }

    // Orientation and layout

    /** Lays the screen out for the current rotation and drawing hand. */
    void applyToolboxSide() {
        closePagePanel(); closePaletteEditor();
        dismiss(filePopup); dismiss(layersPopup);
        boolean right = prefs.toolboxRight();
        toolboxRight = right;
        if (pad != null) { pad.finishStroke(); pad.disconnectDisplay(); }
        // Read the side column from menu/undo at the top to page controls at the bottom.
        toolbarTurn = landscape ? 90 : 0;
        shadePicker.setContentDescription(!landscape
                ? "Gray gradient. Tap or drag from black on the left to white on the right."
                : "Gray gradient. Tap or drag from black at the bottom to white at the top.");
        // Follow the shade endpoints in portrait and landscape.
        LinearLayout colors = (LinearLayout)shadePicker.getParent();
        colors.removeView(eraseButton); colors.removeView(eyedropperButton);
        colors.addView(landscape ? eraseButton : eyedropperButton, colors.indexOfChild(shadePicker));
        colors.addView(landscape ? eyedropperButton : eraseButton, colors.indexOfChild(shadePicker) + 1);
        paletteFrame.setTurn(toolbarTurn);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.removeView(paletteFrame);
        root.addView(paletteFrame, landscape && right ? root.getChildCount() : 0,
                new LinearLayout.LayoutParams(landscape ? dp(48) : -1, landscape ? -1 : dp(48)));
        body.setLayoutParams(new LinearLayout.LayoutParams(landscape ? 0 : -1, landscape ? -1 : 0, 1));
        body.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.removeView(toolbar.toolScroll); body.removeView(toolbar.landscapeTools);
        toolbar.orient(landscape);
        // Both drawing hands keep tools above the canvas in either landscape direction.
        if (landscape) body.addView(toolbar.landscapeTools, 0, new LinearLayout.LayoutParams(-1, dp(64)));
        else body.addView(toolbar.toolScroll, right ? body.getChildCount() : 0, new LinearLayout.LayoutParams(dp(64), -1));
        pad.setLayoutParams(new LinearLayout.LayoutParams(landscape ? -1 : 0, landscape ? 0 : -1, 1));
        arrangeHeader(right);
        toolbar.rebuildTools();
        Ui.invalidateTree(palette);
        pad.updateViewport(); pad.invalidate(); pad.post(pad::connectDisplay);
    }
    /** Keeps menu/undo/redo in the outer corner and page controls at the other end. */
    private void arrangeHeader(boolean right) {
        Ui.detach(menuControls);
        boolean menuAtEnd = !landscape && right;
        // Keep Undo/Redo beside the outer-corner menu for either drawing hand.
        if ((menuControls.indexOfChild(menuButton) == 0) == menuAtEnd) {
            ArrayList<View> controls = new ArrayList<>();
            for (int i = menuControls.getChildCount()-1; i >= 0; i--) controls.add(menuControls.getChildAt(i));
            menuControls.removeAllViews();
            for (View control : controls) menuControls.addView(control);
        }
        Ui.detach(headerControls); Ui.detach(pagesButton);
        LinearLayout menuSide = menuAtEnd ? rightHeader : leftHeader;
        LinearLayout pageSide = menuAtEnd ? leftHeader : rightHeader;
        menuSide.addView(menuControls, new LinearLayout.LayoutParams(-2, dp(48)));
        // Nomad's narrower header replaces the page row with a Pages button.
        if (nomadMode()) pageSide.addView(pagesButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        else pageSide.addView(headerControls);
    }
    /** Turns the app to a quarter turn from the tablet's natural portrait. */
    void requestQuarter(int quarter) {
        if (busy()) return;
        pad.suspend();
        dismiss(toolPicker);
        toolbar.presetDrag.reset(); rotationPrompt.hide();
        appRotation = (4-quarter)%4;
        landscape = appRotation == Surface.ROTATION_90 || appRotation == Surface.ROTATION_270;
        orientationFrame.setTurn(appTurn());
        applyToolboxSide();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        rotationPrompt.hide();
        dismiss(toolPicker);
        applyToolboxSide();
    }
    boolean supportsNomadSimulation() {
        // The Manta also reports "Supernote Nomad" as its model. Use the physical panel,
        // independent of app rotation, window size, density, or the simulation itself.
        android.view.Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
        return "Supernote".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                && Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()) == 1920
                && Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()) == 2560;
    }
    boolean nomadMode() { return supportsNomadSimulation() && prefs.nomadMode(); }
    void setNomadMode(boolean enabled) {
        if (busy()) return;
        enabled = enabled && supportsNomadSimulation();
        dismissPanels(); hideGradientHint();
        pad.suspend(); pad.viewport.reset();
        prefs.setNomadMode(enabled);
        previewFrame.setEnabledPreview(enabled);
        applyToolboxSide();
        saveToolState(); recovery();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        boolean down = event.getActionMasked() == MotionEvent.ACTION_DOWN;
        if (down && rotateButton != null && rotateButton.getVisibility() == View.VISIBLE && Ui.outside(rotateButton, event))
            rotationPrompt.dismiss();
        if (dismissingTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                dismissingTouch = false;
            return true;
        }
        // A touch outside the page row or palette editor only closes it.
        if (down && pagePanel != null && Ui.outside(pagePanel, event)) { closePagePanel(); dismissingTouch = true; return true; }
        if (down && paletteEditor != null && Ui.outside(shadePicker, event)) { closePaletteEditor(); dismissingTouch = true; return true; }
        // Android treats ORIENTATION as radians when it transforms a View's events.
        // Retain the firmware's original signed-degree tilt axes before that happens.
        MotionEvent previous = physicalPenEvent; physicalPenEvent = event;
        try { return super.dispatchTouchEvent(event); }
        finally { physicalPenEvent = previous; }
    }

    // Panels

    private static void dismiss(PopupWindow popup) { if (popup != null) popup.dismiss(); }
    /** Closes every panel and popup and leaves the eyedropper. */
    private void dismissPanels() {
        closePagePanel(); closePaletteEditor(); setPickingShade(false);
        dismiss(filePopup); dismiss(layersPopup); dismiss(toolPicker);
    }
    /** Whether a panel or prompt covers the canvas, so direct presentation must pause. */
    boolean canvasCovered() {
        return rotateButton.getVisibility() == View.VISIBLE || pagePanel != null || pageOverview != null || paletteEditor != null;
    }
    /** Shows a popup beside a toolbar control, fitted inside the canvas area. */
    void showBesideTool(PopupWindow popup, View content, View anchor, int preferredWidthDp) {
        RectF area = Popups.bounds(root, pad); area.inset(dp(8), dp(8));
        int width = Math.min(dp(preferredWidthDp), Math.round(area.width()));
        int height = Math.min(Popups.measuredHeight(content, width, Math.round(area.height())), Math.round(area.height()));
        Popups.show(popup, root, Popups.besideAnchor(Popups.bounds(root, anchor), area, width, height, landscape, dp(8)));
    }
    /** Opens settings for the selected tool beside its toolbar button. */
    PopupWindow showToolSettings() {
        closePagePanel(); dismiss(toolPicker); closePaletteEditor();
        View anchor = toolbar.selectionButtons.get(toolbar.selectedKey());
        ToolSettingsPanel panel = new ToolSettingsPanel(this, anchor == null ? toolbar.toolRail : anchor);
        panel.show(); return panel.popup;
    }
    void showLayers(ToolButton anchor) {
        closePagePanel();
        if (busy()) return;
        if (layersPopup != null) { layersPopup.dismiss(); return; }
        pad.finishStroke(); pad.dryWet();
        new LayersPanel(this, anchor);
    }
    void paletteSettings() {
        closePagePanel();
        if (paletteEditor != null) { closePaletteEditor(); return; }
        if (busy()) return;
        pad.suspend();
        dismissPanels();
        paletteEditor = new PaletteEditor(this); paletteEditor.show();
    }
    void closePaletteEditor() { if (paletteEditor != null) paletteEditor.popup.dismiss(); }
    void savePalette(List<Integer> shades) { prefs.setPaletteShades(shades); toolbar.showPaletteShades(shades); }
    AlertDialog appSettings() { closePagePanel(); return new AppSettingsDialog(this).dialog; }

    private void fileMenu(View anchor) {
        closePagePanel();
        String[] names = {"New drawing", "Open drawing", "Save drawing", "Save drawing as…", "Export PNG…", "Settings"};
        int[] icons = {R.drawable.ic_new, R.drawable.ic_open, R.drawable.ic_save, R.drawable.ic_save, R.drawable.ic_export, R.drawable.ic_settings};
        Runnable[] actions = {this::newDrawing, this::openDrawing, this::saveDrawing, this::saveDrawingAs, this::exportPng, this::appSettings};
        dismiss(filePopup); dismiss(layersPopup);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this); scroll.addView(rows);
        PopupWindow menu = Popups.create(scroll, appTurn());
        for (int i = 0; i < names.length; i++) {
            Runnable action = actions[i];
            TextView row = new TextView(this); row.setText(names[i]); row.setTextColor(Color.BLACK);
            row.setBackground(Ui.outline(dp(1), Color.BLACK, 0));
            row.setTextSize(16); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12)); row.setMinHeight(dp(52));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[i], 0, 0, 0); row.setCompoundDrawablePadding(dp(12));
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                menu.dismiss();
                if (busy()) return;
                pad.finishStroke(); action.run();
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        SettingsForm.style(rows, prefs.largeSettingsText());
        // Place the menu below the hamburger in the user's orientation.
        RectF anchorBounds = Popups.bounds(root, anchor);
        int width = Math.min(dp(prefs.largeSettingsText() ? 256 : 240), root.getWidth());
        int height = Math.min(Popups.measuredHeight(scroll, width, 0), Math.max(1, root.getHeight()-Math.round(anchorBounds.bottom)));
        float left = Popups.clamp(toolboxRight ? anchorBounds.right-width : anchorBounds.left, 0, root.getWidth()-width);
        menu.setOnDismissListener(() -> { if (filePopup == menu) filePopup = null; });
        filePopup = menu;
        Popups.show(menu, root, Popups.rect(left, anchorBounds.bottom, width, height));
    }
    private void clearDrawing() {
        closePagePanel();
        SidePanel menu = new SidePanel(this, clearButton, 300);
        for (boolean all : new boolean[]{false, true}) {
            Button option = new Button(this); option.setAllCaps(false);
            String label = all ? "Clear all layers" : "Clear current layer";
            option.setText(label); option.setContentDescription(label);
            option.setOnClickListener(v -> {
                menu.popup.dismiss();
                layerChange(() -> { if (all) pad.document.clearAllLayers(); else pad.document.clear(); });
            });
            menu.panel.addView(option, new LinearLayout.LayoutParams(-1, dp(56)));
        }
        menu.show();
    }

    // Document edits

    /** Applies a layer edit as one undoable change and presents it. */
    void layerChange(Runnable action) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        try { action.run(); pad.renderDirty(); pad.present(); recovery(); }
        catch (IllegalStateException | IllegalArgumentException error) { message(error.getMessage()); }
    }
    private interface HistoryStep { boolean apply(); }
    private void history(HistoryStep step) {
        pad.dryWet();
        if (step.apply()) { pad.renderDirty(); pad.present(); recovery(); }
    }

    // Color and paint modes

    /** Changes the color bar's state through the fast display path. */
    void updateColorBar(Runnable change) { selectionFeedback.update(shadePicker, change); }
    boolean shadeMarkerVisible() { return !((paint.eraseMode && !pickingShade) || (pickingShade && !pickedShade)); }

    void selectShade(int value) {
        if (busy()) return;
        setPickingShade(false);
        if (!pad.hasGradient()) pad.finishStroke();
        setEraseMode(false);
        if (paint.gray != value) updateColorBar(() -> paint.gray = value);
        if (pad.hasGradient()) pad.previewGradient(value);
        if (paletteEditor != null) paletteEditor.colorChanged(value);
    }
    /** Loads a brush with limited paint by as far as the pen rubbed in the color bar or palette. */
    void loadPaint(float travel) {
        ToolSettings current = library.current();
        if (busy() || !current.limitsPaint()) return;
        library.edit(current.loadedBy(travel)); saveToolState(); toolbar.refreshToolSelection();
    }
    /** The pen or key left the shade strip: commit or cancel what it previewed. */
    void endShadeGesture(boolean committed) {
        if (committed) pad.applyGradient(); else pad.cancelGradient();
        if (paletteEditor != null) paletteEditor.commitColors();
    }
    private void toggleEraseMode() {
        if (busy() || !library.current().supportsEraseMode()) return;
        // A pending gradient fades to transparent instead of waiting for a color.
        boolean gradient = pad.hasGradient();
        if (!gradient) pad.finishStroke();
        setPickingShade(false); pad.dryWet();
        setEraseMode(gradient || !paint.eraseMode);
        if (gradient) { pad.previewGradient(ToneDocument.ERASE); pad.applyGradient(); }
        saveToolState();
    }
    void setEraseMode(boolean value) {
        if (paint.eraseMode != value) updateColorBar(() -> paint.eraseMode = value);
        refreshEraseControl();
    }
    void refreshEraseControl() {
        if (eraseButton == null) return;
        boolean supported = library.current().supportsEraseMode();
        if (!supported && paint.eraseMode) { setEraseMode(false); return; }
        eraseButton.setEnabled(supported);
        eraseButton.setAlpha(supported ? 1f : .3f);
        eraseButton.setContentDescription(supported ? "Erase with current tool" : "This tool does not use a color");
        eraseButton.mark(paint.eraseMode && !pickingShade);
    }
    void setPickingShade(boolean picking) {
        if (pickingShade == picking) return;
        if (picking) { hideGradientHint(); pickOriginalShade = paint.gray; pickedShade = false; }
        updateColorBar(() -> pickingShade = picking);
        refreshEraseControl();
        if (eyedropperButton != null) {
            eyedropperButton.mark(picking);
            eyedropperButton.setContentDescription(picking ? "Cancel picking color" : "Pick color from canvas");
        }
    }
    void refreshPaintModes() {
        wetButton.mark(paint.wetCanvas);
        transparentButton.mark(paint.transparentPaint);
        opaqueButton.mark(!paint.transparentPaint);
    }
    private void setWetCanvas(boolean value) {
        if (busy()) return;
        pad.finishStroke();
        if (value && paint.wetness == 0) wetnessBar.select(65);
        paint.wetCanvas = value;
        if (!value) pad.dryWet();
        refreshPaintModes(); saveToolState();
    }
    private void setTransparentPaint(boolean value) {
        if (busy()) return;
        pad.finishStroke(); paint.transparentPaint = value;
        refreshPaintModes(); saveToolState();
    }
    void toggleNavigationLock() {
        // Discard any fingers already down; unlocking requires a fresh gesture.
        pad.touchBlocked = true; pad.endNavigation();
        ToolButton zoom = toolbar.zoomButton;
        selectionFeedback.update(zoom, zoom.markerArea(), () -> {
            navigationLocked = !navigationLocked;
            toolbar.describeNavigation();
        });
        prefs.setNavigationLocked(navigationLocked);
    }
    /** Saves the current color, modes and tools. */
    void saveToolState() {
        try { prefs.savePaint(paint, library); }
        catch (java.io.IOException error) { message("Could not save tool settings: " + error.getMessage()); }
    }

    // Gradient hint

    /** Prompts for a gradient's second color if none is chosen soon. */
    void scheduleGradientHint() { hideGradientHint(); shadePicker.postDelayed(gradientHintTask, 3000); }
    private void showGradientHint() {
        if (pad == null || !pad.gradientWaiting || pickingShade || !resumed || !hasWindowFocus()) return;
        TextView hint = new TextView(this); hint.setText("Select a second color");
        hint.setTextSize(14); hint.setTextColor(Color.BLACK); hint.setBackgroundColor(Color.WHITE);
        hint.setPadding(dp(8), dp(4), dp(8), dp(4));
        hint.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, root.getWidth()-dp(16)), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        RectF anchor = Popups.bounds(root, shadePicker);
        int width = hint.getMeasuredWidth(), height = hint.getMeasuredHeight();
        float x = landscape ? (toolboxRight ? anchor.left-width-dp(4) : anchor.right+dp(4)) : anchor.centerX()-width/2f;
        int left = Math.round(Popups.clamp(x, 0, root.getWidth()-width));
        int top = Math.round(Popups.clamp(anchor.bottom+dp(4), 0, root.getHeight()-height));
        hint.layout(left, top, left+width, top+height);
        gradientHint = hint; root.getOverlay().add(hint);
    }
    void hideGradientHint() {
        if (shadePicker != null) shadePicker.removeCallbacks(gradientHintTask);
        if (gradientHint != null) { root.getOverlay().remove(gradientHint); gradientHint = null; }
    }

    // Dialogs

    void compactDialog(AlertDialog dialog) { compactDialog(dialog, 440); }
    private void compactDialog(AlertDialog dialog, int width) {
        orientDialog(dialog, width);
        SettingsForm.style(dialog.getWindow().getDecorView(), prefs.largeSettingsText());
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = dialog.getButton(which); if (b != null) { b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD); }
        }
    }
    AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create(); dialog.show(); compactDialog(dialog); return dialog;
    }
    void orientDialog(AlertDialog dialog) { orientDialog(dialog, 440); }
    /** Turns a dialog's content with the app; Android places it in portrait. */
    void orientDialog(AlertDialog dialog, int desiredWidth) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        int width = Math.min(dp(desiredWidth), root.getWidth()-dp(32));
        boolean nomad = nomadMode();
        if (appRotation == Surface.ROTATION_0 && !nomad) { window.setLayout(width, -2); return; }
        ViewGroup content = window.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() != 1 || content.getChildAt(0) instanceof QuarterTurnLayout) return;
        View panel = content.getChildAt(0); content.removeView(panel);
        QuarterTurnLayout frame = new QuarterTurnLayout(this); frame.setTurn(appTurn()); frame.addView(panel);
        if (nomad) frame.setMaximumSize(Math.max(1, orientationFrame.getWidth()-dp(32)), Math.max(1, orientationFrame.getHeight()-dp(32)));
        content.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        window.setLayout(landscape ? -2 : width, landscape ? width : -2);
    }
    void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    // Files

    /** Queues an autosave of the whole book to the recovery file. */
    void recovery() {
        if (loading || pad.document == null) return;
        store.recoverLater(new DocumentStore.Snapshot(book, drawingName), (unused, error) -> {
            if (error != null) {
                Log.e(ProbeActivity.TAG, "Drawing recovery save failed", error);
                runOnUiThread(() -> saveError = error.getMessage());
            }
        });
    }
    private void showSaveError() {
        if (saveError != null) { message("Autosave failed: " + saveError); saveError = null; }
    }
    private void startNewDrawing() { loading = false; pad.replace(null); drawingName = ""; recovery(); }
    private void newDrawing() {
        showDialog(new AlertDialog.Builder(this).setTitle("New drawing?")
                .setMessage("Start a new drawing with one blank page. Save first to keep all pages of this drawing.")
                .setPositiveButton("New", (d, w) -> { pad.replace(null); drawingName = ""; recovery(); })
                .setNegativeButton("Cancel", null));
    }
    private void saveDrawing() {
        showSaveError();
        if (drawingName.isEmpty()) saveDrawingAs();
        else saveDrawingTo(drawingName, true);
    }
    private void saveDrawingAs() {
        showSaveError();
        new DrawingBrowser(this, store, true, drawingName, this::saveDrawingTo, () -> { });
    }
    private void saveDrawingTo(String path, boolean replace) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        DrawingBook savedBook = book;
        saving = true;
        store.save(path, new DocumentStore.Snapshot(book), replace, (unused, error) -> runOnUiThread(() -> {
            saving = false;
            if (destroyed) return;
            if (error != null) { message("Save failed: " + error.getMessage()); return; }
            // A failed save must never change the destination of subsequent saves.
            if (book == savedBook) { drawingName = path; recovery(); }
            message("Saved " + path);
        }));
    }
    private void exportPng() {
        pad.dryWet();
        PngExport.show(this, book.snapshot(), drawingName);
    }
    private void openDrawing() {
        showSaveError();
        new DrawingBrowser(this, store, false, drawingName, (path, replace) -> {
            boolean recovering = loading;
            loading = true;
            store.openBook(path, (document, error) -> runOnUiThread(() -> {
                if (destroyed) return;
                loading = false;
                if (error != null) {
                    message("Open failed: " + error.getMessage());
                    if (recovering) { loading = true; openRecoveryChoice(); }
                    else pad.post(pad::connectDisplay);
                    return;
                }
                replaceBook(document); drawingName = path; recovery();
            }));
        }, () -> { if (!destroyed && loading) openRecoveryChoice(); });
    }
    private void openRecoveryChoice() {
        showDialog(new AlertDialog.Builder(this).setTitle("Start a new drawing?")
                .setMessage("The previous recovery file could not be read.")
                .setPositiveButton("New", (d, w) -> startNewDrawing())
                .setNegativeButton("Open", (d, w) -> openDrawing()).setCancelable(false));
    }

    // Lifecycle

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        rotationPrompt.enable();
        if (pad != null) { pad.updateViewport(); pad.post(pad::connectDisplay); }
    }
    @Override protected void onPause() {
        if (pageOverview != null) pageOverview.dismiss();
        dismissPanels();
        resumed = false;
        rotationPrompt.disable();
        rotationPrompt.hide();
        toolbar.presetDrag.reset();
        if (pad != null) pad.suspend();
        if (!loading && book != null) { saveToolState(); recovery(); }
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (pad == null) return;
        if (focus) { pad.post(pad::connectDisplay); showSaveError(); }
        else { rotationPrompt.dismiss(); pad.finishStroke(); pad.disconnectDisplay(); }
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (rotationPrompt != null) rotationPrompt.orientationSensor.disable();
        selectionFeedback.close();
        layerFeedback.close();
        if (pad != null) pad.close();
        book = null;
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (pagePanel != null) { closePagePanel(); return; }
        if (paletteEditor != null) { closePaletteEditor(); return; }
        if (pad != null && (pad.hasGradient() || pad.fillGesture)) { pad.finishStroke(); return; }
        super.onBackPressed();
    }
}
