package io.github.mpdairy.monopaint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.function.Consumer;
import io.github.mpdairy.fasteink.PanelCoordinates;
import io.github.mpdairy.fasteink.FastEink;

/**
 * The painting screen. It owns the document and current selection, builds the layout,
 * turns it for app-only rotation, and coordinates the panels.
 *
 * <p>Layout, from the outside in: {@link PanelPreviewLayout} (optional smaller-panel
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
    /** The tablet itself; {@link #layoutDevice} is the one the controls are laid out for. */
    Device device;
    ToolLibrary library;
    final PaintState paint = new PaintState();
    /** Whether fingers may not zoom and pan the page, and whether they may not turn it. */
    boolean navigationLocked = true, rotationLocked = true;
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
    PanelPreviewLayout previewFrame;
    DrawingPad pad;
    FullscreenController fullscreen;
    EdgeBars edgeBars;
    Toolbar toolbar;
    RotationPrompt rotationPrompt;
    CanvasGravity canvasGravity;
    ShadePicker shadePicker;
    WetnessBar wetnessBar;
    /** The droplet and wetness slider, kept upright together. */
    private QuarterTurnLayout wetControls;
    WetDroplet wetDroplet;
    ToolButton maskButton, watercolorButton, opaqueButton, transparentButton, eyedropperButton, eraseButton;
    ImageButton menuButton, previousPage, nextPage, pageMenuButton;
    PageActionButton rotateButton, pagesButton;
    TextView pageNumber;

    // Panels. At most one popup is open at a time.
    PopupWindow toolPicker, filePopup, layersPopup;
    PaletteEditor paletteEditor;
    AlertDialog pageOverview, pageConfirmation;
    QuarterTurnLayout pagePanel;
    private TextView popupPageNumber;
    private PageActionButton popupPrevious, popupNext;
    private final Rect pagePanelBounds = new Rect();
    private boolean pagePanelByFinger;
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
        device = Device.current(this);
        preferences = getSharedPreferences(PaintPreferences.FILE, MODE_PRIVATE);
        prefs = new PaintPreferences(preferences);
        FastEink.setEnabled(!prefs.androidDrawing());
        noticeUntestedFirmware();
        navigationLocked = prefs.navigationLocked(); rotationLocked = prefs.rotationLocked();
        prefs.loadPaint(paint);
        library = prefs.toolLibrary(() -> message("Could not load presets. A recovery copy has been kept."));
        if (library.activeId().isEmpty() && library.current().isBrush()) library.edit(library.current().asBrush());
        paint.eraseMode &= library.current().supportsEraseMode();
        // Start in private storage; loadDrawing() moves the library to shared storage when allowed.
        store = new DocumentStore(DrawingStorage.appPrivate(this), DrawingStorage.recovery(this));

        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        buildHeader();
        paletteFrame = new QuarterTurnLayout(this); paletteFrame.addView(palette);
        root.addView(paletteFrame);
        body = new LinearLayout(this);
        toolbar = new Toolbar(this);
        body.addView(toolbar.toolScroll, new LinearLayout.LayoutParams(dp(64), -1));
        toolbar.rebuildTools();
        fullscreen = new FullscreenController(this);
        edgeBars = new EdgeBars(this);
        pad = new DrawingPad(this); body.addView(pad, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        orientationFrame = new QuarterTurnLayout(this);
        orientationFrame.addView(root);
        orientationFrame.afterLayout = () -> { pad.disconnectDisplay(); pad.updateViewport(); pad.post(pad::connectDisplay); };
        previewFrame = new PanelPreviewLayout(this);
        previewFrame.addView(orientationFrame);
        previewFrame.setRotationHint(rotateButton, menuButton, dp(48), dp(8));
        previewFrame.afterLayout = () -> pad.post(pad::connectDisplay);
        previewFrame.setSimulated(simulating() ? device.simulates : null);
        setContentView(previewFrame);
        // Come back turned the way the person last left it.
        turnTo(prefs.appQuarter());
        applyToolboxSide(); updatePages();
        rotationPrompt = new RotationPrompt(this, rotateButton);
        canvasGravity = new CanvasGravity(this);
        pad.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) { pad.disconnectDisplay(); pad.post(pad::connectDisplay); }
        });
        if (DrawingStorage.sharedAllowed(this)) loadDrawing();
        else root.post(this::askForSharedStorage);
    }

    /** Moves the library to shared storage when allowed, then restores the working drawing. */
    private void loadDrawing() {
        if (destroyed) return;
        if (DrawingStorage.sharedAllowed(this)) store.relocate(DrawingStorage.shared(), (unused, error) -> {
            if (error != null) runOnUiThread(() -> message("Could not move paintings to "
                    + DrawingStorage.SHARED_NAME + ": " + error.getMessage()));
        });
        store.recover((recovered, error) -> runOnUiThread(() -> {
            if (destroyed) return;
            loading = false;
            if (error != null) {
                // Keep the failed recovery on disk until the user explicitly chooses a new drawing.
                loading = true;
                showDialog(new AlertDialog.Builder(this).setTitle("Could not recover painting")
                        .setMessage(error.getMessage() + "\nYou can open a saved painting or start a new one.")
                        .setPositiveButton("Open", (d, w) -> openDrawing())
                        .setNegativeButton("New", (d, w) -> startNewDrawing())
                        .setCancelable(false));
            } else {
                drawingName = recovered == null ? "" : recovered.path;
                replaceBook(recovered == null ? null : recovered.book);
                scheduleAutosave();
            }
        }));
    }

    /** Builds the header strip: menu, undo/redo, the color bar and the page controls. */
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
        headerAction("Undo", R.drawable.ic_undo, this::undo);
        headerAction("Redo", R.drawable.ic_redo, () -> history(pad.document::redo));
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
        pageMenuButton = headerPageButton("Page actions", R.drawable.ic_page, this::showPageMenu);
        pagesButton = new PageActionButton(this, this, true); pagesButton.setImageResource(R.drawable.ic_pages);
        pagesButton.setContentDescription("Pages");
        pagesButton.setOnClickListener(v -> { if (!busy()) showPagePanel(); });

        maskButton = colorBarButton(colors, "Masking fluid", R.drawable.ic_masking_fluid, 32, this::toggleMaskingFluid);
        maskButton.setOnTouchListener(new MaskPeelTouch(this, maskButton));
        maskButton.markerBelow = true;
        // The droplet and its wetness slider make one square, turned upright in landscape
        // like an icon, so the slider always fills from the bottom.
        LinearLayout wetPair = new LinearLayout(this);
        wetControls = new QuarterTurnLayout(this); wetControls.addView(wetPair);
        colors.addView(wetControls, new LinearLayout.LayoutParams(dp(48), dp(48)));
        watercolorButton = colorBarButton(wetPair, "Watercolor mode", 0, 32, this::tapDroplet);
        watercolorButton.headerIcon = false;
        // No icon resource: the droplet is solid while Watercolor mode is on, and dashed while it is off.
        watercolorButton.iconOnly(new DropletIcon(false), new DropletIcon(true));
        watercolorButton.setContentDescription("Watercolor mode. Transparent brushes, shapes and flat fills paint watercolor. While wet paint is moving, tap to dry it.");
        watercolorButton.iconHalf = 18;
        wetDroplet = new WetDroplet(this, watercolorButton);
        wetnessBar = new WetnessBar(this);
        wetPair.addView(wetnessBar, new LinearLayout.LayoutParams(dp(16), dp(48)));
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
        refreshPaintModes(); setMaskingFluid(paint.maskingFluid);
        rightHeader.addView(headerControls);
    }

    int dp(float value) { return Ui.dp(this, value); }
    int appTurn() { return appRotation == Surface.ROTATION_270 ? -90 : appRotation*90; }

    // ControlHost
    @Override public boolean busy() { return loading || saving || pad == null || pad.document == null || pad.fill != null || pad.gradientCommit; }
    @Override public boolean pageActionPending() { return pageActionPending; }
    @Override public int toolbarTurn() { return toolbarTurn; }
    @Override public boolean colorMarkersOnTop() { return landscape && !toolboxRight; }
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
        return headerButton(parent, title, icon, 48, action);
    }
    /**
     * A header control {@code width} dp along the strip. A narrow icon takes a narrow button,
     * leaving the room to the shade strip; it still turns upright in place in landscape.
     */
    private ToolButton headerButton(LinearLayout parent, String title, int icon, int width, Runnable action) {
        ToolButton button = iconButton(title, icon, action);
        button.headerIcon = true; button.setBackgroundColor(Color.WHITE);
        parent.addView(button, new LinearLayout.LayoutParams(dp(width), dp(48)));
        return button;
    }
    private ToolButton headerAction(String title, int icon, Runnable action) { return headerButton(menuControls, title, icon, action); }
    /** A color bar toggle that shows its state with a dot. */
    private ToolButton colorBarButton(LinearLayout parent, String title, int icon, Runnable action) {
        return colorBarButton(parent, title, icon, 48, action);
    }
    private ToolButton colorBarButton(LinearLayout parent, String title, int icon, int width, Runnable action) {
        ToolButton button = headerButton(parent, title, icon, width, action);
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
                try {
                    // End the press before page counters, disabled arrows or a
                    // new panel request an Android frame. Retaining the box for
                    // 200 ms lets those redraws replay (and dim) its fast pixels.
                    button.press.set(false);
                    if (resumed && !destroyed && button.isAttachedToWindow()) { setPickingShade(false); action.run(); }
                } finally { pageActionPending = false; }
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
        updatePageRow(pageNumber, previousPage, nextPage, index, count);
        if (pagesButton != null) pagesButton.setContentDescription("Pages. Page " + (index+1) + " of " + count);
        if (popupPageNumber != null) updatePageRow(popupPageNumber, popupPrevious, popupNext, index, count);
    }
    private void updatePageRow(TextView label, View previous, View next, int index, int count) {
        label.setText((index+1) + " / " + count); label.setContentDescription("Page " + (index+1) + " of " + count + ". Show all pages");
        Ui.setDimmed(previous, book != null && index > 0, .3f);
        Ui.setDimmed(next, book != null && index+1 < count, .3f);
    }
    private void replaceBook(DrawingBook replacement) {
        if (replacement == null) { pad.replace(null); return; }
        pad.finishStroke(); book = replacement; pad.showPage(book.current()); updatePages(); pad.post(fullscreen::pageChanged);
    }
    private void changePage(int index) {
        if (busy() || index < 0 || index >= book.count()) return;
        pad.finishStroke(); pad.dryWet();
        try { book.select(index); pad.showPage(book.current()); updatePages(); recovery(); fullscreen.pageChanged(); }
        catch (java.io.IOException error) { message("Could not open page: " + error.getMessage()); }
    }
    private void addPage() { pageEdit("add", () -> book.addPage()); }
    /** Inserts a copy of the current page after it and turns to the copy. */
    private void duplicatePage() { pageEdit("duplicate", () -> book.duplicatePage()); }
    private void deletePage() { pageEdit("delete", () -> book.deletePage()); }
    private void clearPage() { layerChange(() -> pad.document.clearAllLayers()); }
    private void clearLayer() { layerChange(() -> pad.document.clear()); }
    private interface BookEdit { void run() throws java.io.IOException; }
    /** Changes the pages with {@code edit}, which selects the page to show, and shows it. */
    private void pageEdit(String verb, BookEdit edit) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        try { edit.run(); pad.showPage(book.current()); updatePages(); recovery(); fullscreen.pageChanged(); }
        catch (java.io.IOException error) { message("Could not " + verb + " page: " + error.getMessage()); }
    }
    /** The compact layout's Pages box: the page row with Add page, above the other page actions. */
    private void showPagePanel() {
        if (!beginPagePanel()) return;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_HORIZONTAL);
        popupPrevious = pageAction("Previous page", R.drawable.ic_previous, () -> changePage(book.index()-1), false);
        popupNext = pageAction("Next page", R.drawable.ic_chevron, () -> changePage(book.index()+1), false);
        popupPageNumber = new TextView(this); popupPageNumber.setTextSize(15); popupPageNumber.setTextColor(Color.BLACK);
        popupPageNumber.setOnClickListener(v -> showPageOverview());
        popupPageNumber.setTypeface(null, Typeface.BOLD); popupPageNumber.setGravity(Gravity.CENTER);
        row.addView(popupPrevious, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(popupPageNumber, new LinearLayout.LayoutParams(dp(76), dp(48)));
        row.addView(popupNext, new LinearLayout.LayoutParams(dp(48), dp(48)));
        addPageItem(row);
        updatePageRow(popupPageNumber, popupPrevious, popupNext, book.index(), book.count());
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_HORIZONTAL); addPageChanges(actions);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
        box.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
        showPagePanel(pagesButton, box, 3*dp(48)+dp(76), 2*dp(48));
    }
    /**
     * The page actions in a column below the header's page button; compact layouts show them in
     * the Pages box instead.
     */
    private void showPageMenu() {
        if (compactLayout()) { showPagePanel(); return; }
        if (!beginPagePanel()) return;
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        addPageItem(column); addPageChanges(column);
        showPagePanel(pageMenuButton, column, dp(48), 5*dp(48));
    }
    private void addPageItem(LinearLayout menu) {
        pageMenuItem(menu, "Add page", R.drawable.ic_new, this::addPage, book.count() < DrawingBook.MAX_PAGES);
    }
    /** Adds the actions that change the current page; deleting it, which can't be undone, asks first. */
    private void addPageChanges(LinearLayout menu) {
        pageMenuItem(menu, "Duplicate page", R.drawable.ic_pages, this::duplicatePage, book.count() < DrawingBook.MAX_PAGES);
        pageMenuItem(menu, "Delete page", R.drawable.ic_page_delete, () -> confirmPageAction(
                "Delete page " + (book.index()+1) + "?", "You can't undo this.",
                "Delete", this::deletePage), book.count() > 1);
        pageMenuItem(menu, "Clear page", R.drawable.ic_page_clear, this::clearPage, true);
        pageMenuItem(menu, "Clear current layer", R.drawable.ic_layer_clear, this::clearLayer, true);
    }
    private View pageMenuItem(LinearLayout menu, String name, int icon, Runnable action, boolean enabled) {
        // Like the page row, act while the panel is open so page artwork reaches the panel at once.
        View item = pageAction(name, icon, () -> { action.run(); closePagePanel(); }, false);
        Ui.setDimmed(item, enabled, .3f);
        menu.addView(item, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return item;
    }
    private void confirmPageAction(String title, String text, String verb, Runnable action) {
        closePagePanel();
        AlertDialog dialog = showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(text)
                .setPositiveButton(verb, (unused, which) -> { if (!busy()) action.run(); })
                .setNegativeButton("Cancel", null));
        pageConfirmation = dialog;
        dialog.setOnDismissListener(unused -> { if (pageConfirmation == dialog) pageConfirmation = null; });
    }
    /** Closes an open page panel, or prepares the canvas for a new one; false when none should open. */
    private boolean beginPagePanel() {
        if (pagePanel != null) { closePagePanel(); return false; }
        if (busy()) return false;
        dismissPanels(); hideGradientHint();
        pad.suspend();
        return true;
    }
    /**
     * Shows {@code content} ({@code width} × {@code height} pixels, without its border) in an
     * outlined panel beside {@code button}, drawn through the same fast path as toolbar selection.
     */
    private void showPagePanel(View button, View content, int width, int height) {
        content.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.setBackground(Ui.outline(dp(1), Color.BLACK, 0));
        QuarterTurnLayout panel = new QuarterTurnLayout(this); panel.setTurn(appTurn()); panel.addView(content);
        RectF anchor = Popups.bounds(root, button);
        // Sum rounded child pixels: at density 300, dp(56) is one pixel shorter
        // than dp(48)+2*dp(4), which clips buttons and disables direct feedback.
        width += 2*dp(4); height += 2*dp(4);
        float left = landscape ? (toolboxRight ? anchor.left-width : anchor.right) : anchor.right-width;
        float top = landscape ? anchor.bottom-height : anchor.bottom;
        RectF bounds = Popups.rect(Popups.clamp(left, 0, root.getWidth()-width), Popups.clamp(top, 0, root.getHeight()-height), width, height);
        // The panel is a child of the preview frame, not a popup window.
        PanelCoordinates.fromView(root).mapRect(bounds);
        Matrix inverse = new Matrix(); PanelCoordinates.fromView(previewFrame).invert(inverse);
        inverse.mapRect(bounds); bounds.roundOut(pagePanelBounds);
        pagePanel = panel; pagePanelByFinger = Ui.fingerPress();
        // Lay out the real touch targets synchronously, then submit only their
        // bounded pixels through the same fast path as toolbar selection.
        selectionFeedback.update(previewFrame, new Rect(pagePanelBounds), () -> previewFrame.showPanel(panel, pagePanelBounds));
    }
    void closePagePanel() {
        if (pagePanel == null) return;
        Rect bounds = new Rect(pagePanelBounds); pagePanel = null;
        selectionFeedback.update(previewFrame, bounds, previewFrame::hidePanel);
        pagePanelBounds.setEmpty(); popupPageNumber = null; popupPrevious = null; popupNext = null;
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
        edgeBars.reset();
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
        paletteFrame.setTurn(toolbarTurn); wetControls.setTurn(-toolbarTurn);
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
        fullscreen.applyVisibility();
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
        // A narrower header replaces the page row with a Pages button.
        if (compactLayout()) pageSide.addView(pagesButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        else pageSide.addView(headerControls);
    }
    /** Turns the app to a quarter turn from the tablet's natural portrait. */
    void requestQuarter(int quarter) {
        if (busy()) return;
        pad.suspend();
        dismiss(toolPicker);
        toolbar.presetDrag.reset(); rotationPrompt.hide();
        turnTo(quarter); prefs.setAppQuarter(quarter);
        applyToolboxSide();
    }
    private void turnTo(int quarter) {
        appRotation = (4-quarter)%4;
        landscape = appRotation == Surface.ROTATION_90 || appRotation == Surface.ROTATION_270;
        orientationFrame.setTurn(appTurn());
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        rotationPrompt.hide();
        dismiss(toolPicker);
        applyToolboxSide();
    }
    boolean canSimulate() { return device.simulates != null; }
    /** Previewing a smaller tablet's panel and controls (Settings' simulation mode). */
    boolean simulating() { return canSimulate() && prefs.simulateSmallerPanel(); }
    /**
     * The tablet the controls are laid out for: the simulated one while simulating. Input
     * and display quirks always come from {@link #device}, the hardware actually in use.
     */
    Device layoutDevice() { return simulating() ? device.simulates : device; }
    boolean compactLayout() { return layoutDevice().compactControls; }
    void setSimulating(boolean enabled) {
        if (busy()) return;
        enabled = enabled && canSimulate();
        dismissPanels(); hideGradientHint();
        pad.suspend(); pad.viewport.reset();
        prefs.setSimulateSmallerPanel(enabled);
        previewFrame.setSimulated(enabled ? device.simulates : null);
        applyToolboxSide();
        saveToolState(); recovery();
    }

    /**
     * Fast e-ink is a guess on firmware the device checks have not run on with this tablet.
     * Say so once per build, so a misbehaving screen has an obvious way out.
     */
    private void noticeUntestedFirmware() {
        if (!Device.supernote() || device.firmwareTested() || prefs.androidDrawing()
                || Device.firmware().equals(prefs.untestedFirmwareNoticed())) return;
        prefs.setUntestedFirmwareNoticed(Device.firmware());
        message("MonoPaint hasn't been tested on this firmware. If the screen misbehaves, "
                + "choose Standard Android drawing in Settings.");
    }
    /** Switches between fast e-ink and Android drawing (Settings' Screen drawing). */
    void setAndroidDrawing(boolean enabled) {
        pad.suspend();
        prefs.setAndroidDrawing(enabled);
        FastEink.setEnabled(!enabled);
        root.invalidate(); pad.connectDisplay();
    }

    void dispatchCancelledTouch(MotionEvent event) { super.dispatchTouchEvent(event); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        Ui.notePress(event);
        if (edgeBars != null && edgeBars.touch(event)) return true;
        boolean down = event.getActionMasked() == MotionEvent.ACTION_DOWN;
        if (down && rotateButton != null && rotateButton.getVisibility() == View.VISIBLE && Ui.outside(rotateButton, event))
            rotationPrompt.dismiss();
        if (dismissingTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                dismissingTouch = false;
            return true;
        }
        // A touch outside the page row or palette editor only closes it, or is a resting hand to ignore.
        if (down && pagePanel != null && Ui.outside(pagePanel, event)) {
            if (Ui.closesPanel(pagePanelByFinger, event)) closePagePanel();
            dismissingTouch = true; return true;
        }
        if (down && paletteEditor != null && Ui.outside(shadePicker, event)) {
            if (Ui.closesPanel(paletteEditor.openedByFinger, event)) closePaletteEditor();
            dismissingTouch = true; return true;
        }
        // Android treats ORIENTATION as radians when it transforms a View's events.
        // Retain the firmware's original signed-degree tilt axes before that happens.
        MotionEvent previous = physicalPenEvent; physicalPenEvent = event;
        try { return super.dispatchTouchEvent(event); }
        finally { physicalPenEvent = previous; }
    }

    // Panels

    private static void dismiss(PopupWindow popup) { if (popup != null) popup.dismiss(); }
    /** Closes every panel and popup and leaves the eyedropper. */
    void dismissPanels() {
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
        String[] names = {"New painting", "Open painting", "Save painting as…", "Import image…", "Export PNG…", "Fullscreen…", "Settings"};
        int[] icons = {R.drawable.ic_new, R.drawable.ic_open, R.drawable.ic_save, R.drawable.ic_import, R.drawable.ic_export,
                R.drawable.ic_zoom, R.drawable.ic_settings};
        Runnable[] actions = {() -> settleChanges("starting a new painting", this::startNewDrawing),
                () -> settleChanges("opening another painting", this::openDrawing), this::saveDrawingAs, this::importImage,
                this::exportPng, () -> fullscreen.showModes(), this::appSettings};
        menu(anchor, names, icons, actions);
    }
    /** A list of labeled rows below the hamburger; tapping one closes the list and runs its action. */
    private void menu(View anchor, String[] names, int[] icons, Runnable[] actions) {
        closePagePanel();
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
    // Document edits

    /** Applies a layer edit as one undoable change and presents it. */
    void layerChange(Runnable action) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        try { action.run(); pad.renderDirty(); pad.present(); recovery(); }
        catch (IllegalStateException | IllegalArgumentException error) { message(error.getMessage()); }
    }
    interface HistoryStep { boolean apply(); }
    /** Undoes the last change; a floating selection is dropped instead, putting back what it lifted. */
    void undo() {
        if (pad.cancelSelection()) return;
        history(pad.document::undo);
    }
    void history(HistoryStep step) {
        pad.finishStroke(); pad.dryWet();
        int width=pad.document.width, height=pad.document.height;
        if (step.apply()) {
            if(width!=pad.document.width || height!=pad.document.height) { pad.viewport.reset(); pad.canvasResized(); }
            else { pad.renderDirty(); pad.present(); }
            recovery();
        }
    }

    // Color and paint modes

    /** Changes the color bar's state through the fast display path. */
    void updateColorBar(Runnable change) { selectionFeedback.update(shadePicker, shadePicker.markerArea(), change); }
    boolean shadeMarkerVisible() { return !(((paint.eraseMode || paint.maskingFluid) && !pickingShade) || (pickingShade && !pickedShade)); }

    void selectShade(int value) {
        if (busy()) return;
        setPickingShade(false);
        if (!pad.hasGradient()) pad.finishStroke();
        setEraseMode(false); setMaskingFluid(false);
        // A brush pen holding no ink lays no color: choosing one goes back to the brush last in use.
        if (library.current().tool.water && !library.current().holdsInk()) toolbar.returnToBrush();
        if (paint.gray != value) updateColorBar(() -> paint.gray = value);
        loadInk(value);
        if (pad.hasGradient()) pad.previewGradient(value);
        if (paletteEditor != null) paletteEditor.colorChanged(value);
    }
    /**
     * Any shade chosen, the same one too, resets a watercolor brush's ink to it, after the brush
     * has laid the paint it is still showing; with the wet brush pen, it dips the pen in that ink.
     */
    void loadInk(int value) {
        pad.layQueuedPaint();
        paint.brushLoad.tint(value);
        if (holdsInk()) paint.brushInk.load(value);
    }
    /**
     * With the wet brush pen, the color bar's marker slides to the ink it holds now, after a stroke;
     * with opaque paint it stays, since the next stroke starts dipped in the selected ink again.
     */
    void showBrushInk() {
        if (!holdsInk() || !paint.transparentPaint || paint.eraseMode || paint.maskingFluid || pickingShade) return;
        int shade = 255 - Math.round(paint.brushInk.fullness() * 255), from = paint.gray;
        if (shade == from) return;
        // The marker still shows the old shade until it slides.
        paint.gray = shade; shadePicker.slideMarker(from);
    }
    /** Loads a brush with limited paint by as far as the pen rubbed in the color bar or palette. */
    void loadPaint(float travel) {
        ToolSettings current = library.current();
        if (busy()) return;
        if (!current.limitsPaint()) return;
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
    /** The wet brush pen holds the ink it is dipped in, or soaks up, between strokes; white cleans it. */
    private boolean holdsInk() { return library.current().holdsInk(); }
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
    // Masking fluid

    /** Masking fluid is chosen like a color; the eraser then rubs it off. */
    private void toggleMaskingFluid() {
        if (busy()) return;
        pad.dryWet();
        // The brush pens lay no color: masking fluid goes on with the brush last in use.
        if (!paint.maskingFluid && library.current().tool.water) toolbar.returnToBrush();
        setMaskingFluid(!paint.maskingFluid); saveToolState();
    }
    void setMaskingFluid(boolean value) {
        if (paint.maskingFluid != value) updateColorBar(() -> paint.maskingFluid = value);
        if (maskButton == null) return;
        maskButton.mark(value);
        maskButton.setContentDescription(value ? "Masking fluid. Erase to rub it off; hold to peel it all off."
                : "Masking fluid. Hold to peel it all off.");
    }
    /** Opens the Peel button below the masking fluid; null when no panel opened. */
    View showPeel() {
        if (!beginPagePanel()) return null;
        LinearLayout column = new LinearLayout(this);
        View peel = pageMenuItem(column, "Peel off masking fluid", R.drawable.ic_peel, this::peelMask, pad.document.hasMask());
        showPagePanel(maskButton, column, dp(48), dp(48));
        return peel;
    }
    private void peelMask() { layerChange(() -> pad.document.peelMask()); }

    void refreshPaintModes() {
        watercolorButton.mark(paint.watercolor);
        transparentButton.mark(paint.transparentPaint);
        opaqueButton.mark(!paint.transparentPaint);
    }
    /** Transparent paint from brushes, shapes and flat fills becomes watercolor, or stops being it. */
    private void setWatercolor(boolean value) {
        if (busy()) return;
        pad.finishStroke(); paint.watercolor = value;
        refreshPaintModes(); saveToolState();
    }
    /** While wet paint is moving, the droplet dries it; otherwise it toggles watercolor mode. */
    private void tapDroplet() {
        if (wetDroplet.moving()) blowDry();
        else setWatercolor(!paint.watercolor);
    }
    /**
     * The blow dryer: wet paint stops moving and stays as it is, and the droplet blows a little
     * air. The modes stay as they are, so on a wet canvas the next stroke wets the paint it reaches again.
     */
    void blowDry() {
        if (busy()) return;
        setPickingShade(false);
        pad.finishStroke(); pad.dryWet();
        wetDroplet.rest(); BlowDryer.blow(this, watercolorButton);
    }
    private void setTransparentPaint(boolean value) {
        if (busy()) return;
        pad.finishStroke(); paint.transparentPaint = value;
        refreshPaintModes(); saveToolState();
    }
    void toggleNavigationLock() {
        flipLock(toolbar.zoomButton, () -> navigationLocked = !navigationLocked);
        prefs.setNavigationLocked(navigationLocked);
    }
    void toggleRotationLock() {
        flipLock(toolbar.turnButton, () -> rotationLocked = !rotationLocked);
        prefs.setRotationLocked(rotationLocked);
    }
    private void flipLock(ToolButton button, Runnable flip) {
        // Discard any fingers already down; unlocking requires a fresh gesture.
        pad.touchBlocked = true; pad.endNavigation();
        selectionFeedback.update(button, button.markerArea(), () -> { flip.run(); toolbar.describeNavigation(); });
    }
    /** Saves the current color, modes and tools. */
    void saveToolState() {
        // The Nomad's side refresh can reuse Android's last surface without an
        // app redraw. Commit the final marker once rapid color taps have settled.
        shadePicker.finishUpdate();
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
    void compactDialog(AlertDialog dialog, int width) {
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
        Ui.notePresses(window);
        boolean openedByFinger = Ui.fingerPress();
        window.getDecorView().setOnTouchListener((panel, event) -> Ui.restingOutside(panel, event, openedByFinger));
        int width = Math.min(dp(desiredWidth), root.getWidth()-dp(32));
        boolean compact = compactLayout();
        if (appRotation == Surface.ROTATION_0 && !compact) { window.setLayout(width, -2); return; }
        ViewGroup content = window.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() != 1 || content.getChildAt(0) instanceof QuarterTurnLayout) return;
        View panel = content.getChildAt(0); content.removeView(panel);
        QuarterTurnLayout frame = new QuarterTurnLayout(this); frame.setTurn(appTurn()); frame.addView(panel);
        if (compact) frame.setMaximumSize(Math.max(1, orientationFrame.getWidth()-dp(32)), Math.max(1, orientationFrame.getHeight()-dp(32)));
        content.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        window.setLayout(landscape ? -2 : width, landscape ? width : -2);
    }
    void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    // Files

    /** Queues an autosave of the whole book to the recovery file, and to the painting's own file soon after. */
    void recovery() {
        if (loading || pad.document == null) return;
        store.recoverLater(new DocumentStore.Snapshot(book, drawingName), (unused, error) -> {
            if (error != null) {
                Log.e(ProbeActivity.TAG, "Drawing recovery save failed", error);
                runOnUiThread(() -> saveError = error.getMessage());
            }
        });
        scheduleAutosave();
    }
    /**
     * How long a named painting's own file may lag its recovery file while drawing. Supernote
     * Cloud syncs the library, and rewriting a file there every few seconds broke the sync.
     */
    private static final long AUTOSAVE_MS = 60_000;
    private final Runnable autosave = this::autosave;
    private boolean autosaveScheduled;
    private void scheduleAutosave() {
        if (autosaveScheduled || drawingName.isEmpty() || book == null || !book.unsaved()) return;
        autosaveScheduled = true; pad.postDelayed(autosave, AUTOSAVE_MS);
    }
    private void autosaveNow() { pad.removeCallbacks(autosave); autosave(); }
    /** Writes a named painting to its own file without interrupting drawing. */
    private void autosave() {
        autosaveScheduled = false;
        if (destroyed || drawingName.isEmpty() || book == null || !book.unsaved()) return;
        if (busy() || pad.stroking()) { autosaveScheduled = true; pad.postDelayed(autosave, 1000); return; }
        DrawingBook savedBook = book; String path = drawingName;
        DocumentStore.Snapshot snapshot = new DocumentStore.Snapshot(book);
        store.save(path, snapshot, (unused, error) -> runOnUiThread(() -> {
            if (destroyed) return;
            if (error != null) {
                Log.e(ProbeActivity.TAG, "Drawing autosave failed", error);
                saveError = error.getMessage(); showSaveError(); return;
            }
            if (path.equals(drawingName)) saved(savedBook, snapshot, path);
        }));
    }
    private void showSaveError() {
        if (saveError != null) { message("Autosave failed: " + saveError); saveError = null; }
    }
    private static final int SHARED_STORAGE = 2;
    /** Asked on every launch until allowed, before the working drawing loads. */
    private void askForSharedStorage() {
        store.drawingCount((count, error) -> runOnUiThread(() -> askForSharedStorage(count == null ? 0 : count)));
    }
    private void askForSharedStorage(int drawings) {
        if (destroyed) return;
        String risk = drawings == 0 ? "Right now MonoPaint keeps paintings inside the app, where uninstalling or reinstalling it erases them."
                : (drawings == 1 ? "1 saved painting is" : drawings + " saved paintings are")
                        + " stored inside MonoPaint, where uninstalling or reinstalling it erases them.";
        String move = drawings == 0 ? "so paintings are kept in " + DrawingStorage.SHARED_NAME + " instead, where Files shows them. "
                        + "Paintings from an earlier install there will open again."
                : "to move them, and the painting you're working on, to " + DrawingStorage.SHARED_NAME + ", where they're safe and Files shows them.";
        showDialog(new AlertDialog.Builder(this).setTitle("Keep your paintings safe")
                .setMessage(risk + " Allow access to all files " + move)
                .setPositiveButton("Allow", (d, w) -> {
                    if (!DrawingStorage.request(this, SHARED_STORAGE)) { message("This tablet cannot grant file access"); loadDrawing(); }
                })
                .setNegativeButton("Not now", (d, w) -> loadDrawing())
                .setCancelable(false));
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == SHARED_STORAGE) loadDrawing();
    }
    private void startNewDrawing() { loading = false; pad.replace(null); drawingName = ""; recovery(); }
    /**
     * Runs {@code then}, which replaces the open drawing, once its changes are saved: a named
     * painting saves to its own file, and an unnamed one, kept only in the recovery file, asks
     * for a name or to drop it.
     */
    private void settleChanges(String replacing, Runnable then) {
        if (loading || book == null || !book.unsaved()) { then.run(); return; }
        if (!drawingName.isEmpty()) { saveDrawingTo(drawingName, true, then); return; }
        showDialog(new AlertDialog.Builder(this).setTitle("Save this painting?")
                .setMessage("This painting doesn't have a name yet. Save it before " + replacing + "?")
                .setPositiveButton("Save as…", (d, w) -> saveDrawingAs(then))
                .setNeutralButton("Don't save", (d, w) -> then.run())
                .setNegativeButton("Cancel", null));
    }
    private void saveDrawingAs() { saveDrawingAs(() -> { }); }
    private void saveDrawingAs(Runnable saved) {
        showSaveError();
        new DrawingBrowser(this, store, true, drawingName, (path, replace) -> saveDrawingTo(path, replace, saved), () -> { });
    }
    private void saveDrawingTo(String path, boolean replace, Runnable saved) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        DrawingBook savedBook = book;
        saving = true;
        DocumentStore.Snapshot snapshot = new DocumentStore.Snapshot(book);
        store.save(path, snapshot, replace, (unused, error) -> runOnUiThread(() -> {
            saving = false;
            if (destroyed) return;
            if (error != null) { message("Save failed: " + error.getMessage()); return; }
            saved(savedBook, snapshot, path);
            message("Saved " + path);
            if (book == savedBook) saved.run();
        }));
    }
    /** Records that {@code snapshot} of {@code savedBook} reached {@code path}, which becomes its file if it is still open. */
    private void saved(DrawingBook savedBook, DocumentStore.Snapshot snapshot, String path) {
        // A failed save must never change the destination of subsequent saves.
        if (book == savedBook) { savedBook.markSaved(snapshot.book); drawingName = path; recovery(); }
    }
    private static final int PICK_FOLDER = 1, PICK_IMAGE = 3;
    private Consumer<Uri> folderPicked;
    /** Opens Android's folder picker at Supernote's EXPORT folder; keeps access to the chosen folder. */
    void pickFolder(Consumer<Uri> picked) {
        folderPicked = picked;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:EXPORT"));
        try { startActivityForResult(intent, PICK_FOLDER); }
        catch (android.content.ActivityNotFoundException error) { folderPicked = null; message("No folder picker on this tablet"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SHARED_STORAGE) { loadDrawing(); return; }
        if (request == PICK_IMAGE) {
            if (result == RESULT_OK && data != null && data.getData() != null && !busy()) ImageImport.ask(this, data.getData());
            return;
        }
        if (request != PICK_FOLDER) return;
        Consumer<Uri> picked = folderPicked; folderPicked = null;
        if (picked == null || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        picked.accept(tree);
    }
    private void importImage() {
        if (ImageImport.browse(this)) return;
        try { startActivityForResult(ImageImport.picker(), PICK_IMAGE); }
        catch (android.content.ActivityNotFoundException error) { message("No file picker on this tablet"); }
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
        showDialog(new AlertDialog.Builder(this).setTitle("Start a new painting?")
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
        if (edgeBars != null) edgeBars.reset();
        if (fullscreen != null) fullscreen.dismiss();
        if (pageOverview != null) pageOverview.dismiss();
        dismissPanels();
        resumed = false;
        rotationPrompt.disable();
        rotationPrompt.hide();
        toolbar.presetDrag.reset();
        if (pad != null) pad.suspend();
        if (!loading && book != null) { saveToolState(); recovery(); autosaveNow(); }
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (pad == null) return;
        if (focus) { pad.post(pad::connectDisplay); showSaveError(); }
        else { rotationPrompt.dismiss(); pad.finishStroke(); pad.disconnectDisplay(); selectionFeedback.closeSession(); }
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
        if (fullscreen != null && fullscreen.mode != 0) { fullscreen.setMode(0); return; }
        if (pad != null && (pad.hasGradient() || pad.fillGesture)) { pad.finishStroke(); return; }
        super.onBackPressed();
    }
}
