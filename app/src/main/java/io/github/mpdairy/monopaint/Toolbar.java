package io.github.mpdairy.monopaint;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The side toolbar: built-in tools, Layers, Zoom and the palette in the user's saved
 * order, then a divider and the user's favorites. Tapping a tool selects it; tapping
 * the selected tool opens its settings. Favorites can be dragged to reorder them.
 */
final class Toolbar {
    /** Tools offered in the toolbar, in their default order. */
    static final ToolSettings.Tool[] TOOLS = {ToolSettings.Tool.BRUSH, ToolSettings.Tool.BRUSH_PEN, ToolSettings.Tool.PENCIL,
            ToolSettings.Tool.AIRBRUSH, ToolSettings.Tool.FILL, ToolSettings.Tool.SHAPES,
            ToolSettings.Tool.ERASER, ToolSettings.Tool.SOFTEN};
    /** Toolbar entries that are not tools. */
    static final String LAYERS = "LAYERS", ZOOM = "ZOOM", PALETTE = "PALETTE";

    private final PaintActivity app;
    final ToolRail toolRail;
    /** Portrait column and landscape row that scroll the rail. */
    final ScrollView toolScroll;
    final HorizontalScrollView landscapeTools;
    final PresetDragHandler presetDrag = new PresetDragHandler();
    /** Selectable buttons by key: {@code tool:<TOOL>} for built-ins, the preset ID for favorites. */
    final Map<String, ToolButton> selectionButtons = new LinkedHashMap<>();
    ToolButton layersButton, zoomButton;
    LinearLayout sidebarPalette;
    final ArrayList<PaletteSwatch> sidebarSwatches = new ArrayList<>();
    /** Progress text for long operations such as fills. */
    TextView operationStatus;

    Toolbar(PaintActivity app) {
        this.app = app;
        toolRail = new ToolRail(app); toolRail.setOrientation(LinearLayout.VERTICAL);
        toolScroll = new ScrollView(app); toolScroll.addView(toolRail);
        landscapeTools = new HorizontalScrollView(app);
        toolScroll.setOnDragListener(presetDrag); landscapeTools.setOnDragListener(presetDrag);
    }
    private int dp(float value) { return app.dp(value); }
    private boolean landscape() { return app.landscape; }
    private ToolLibrary library() { return app.library; }

    /** Moves the rail into the portrait column or the landscape row. Call {@link #rebuildTools} afterwards. */
    void orient(boolean landscape) {
        presetDrag.reset();
        Ui.detach(toolRail);
        toolRail.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (landscape) landscapeTools.addView(toolRail, new android.widget.FrameLayout.LayoutParams(-2, -1));
        else toolScroll.addView(toolRail, new android.widget.FrameLayout.LayoutParams(-1, -2));
    }

    String selectedKey() {
        return library().activeId().isEmpty() ? "tool:" + library().current().tool : library().activeId();
    }
    boolean shapeChosen() {
        return app.prefs.shapeChosen(!library().builtin(ToolSettings.Tool.SHAPES).equals(ToolSettings.defaults(ToolSettings.Tool.SHAPES)));
    }
    private boolean isTool(String key) { return !key.equals(LAYERS) && !key.equals(ZOOM) && !key.equals(PALETTE); }

    /** All entries in the saved order, with any new entries appended. */
    ArrayList<String> toolbarOrder() {
        ArrayList<String> defaults = new ArrayList<>(), order = new ArrayList<>();
        for (ToolSettings.Tool tool : TOOLS) defaults.add(tool.name());
        defaults.add(LAYERS); defaults.add(ZOOM); defaults.add(PALETTE);
        for (String key : app.prefs.toolbarOrder()) if (defaults.contains(key) && !order.contains(key)) order.add(key);
        for (String key : defaults) if (!order.contains(key)) order.add(key);
        return order;
    }
    void move(String key, int direction) {
        ArrayList<String> order = toolbarOrder(); int from = order.indexOf(key), to = from+direction;
        if (from < 0 || to < 0 || to >= order.size()) return;
        java.util.Collections.swap(order, from, to);
        app.prefs.setToolbarOrder(order); rebuildTools();
    }
    /** Tools left out of {@link #TOOLS}, such as the unfinished wet brush pen, are never shown or kept selected. */
    boolean toolVisible(ToolSettings.Tool tool) {
        return java.util.Arrays.asList(TOOLS).contains(tool) && app.prefs.toolbarItemVisible(tool.name());
    }
    /** Shows or hides an entry. At least one tool stays visible; returns false if refused. */
    boolean setVisible(String key, boolean visible) {
        if (!visible && isTool(key)) {
            boolean another = false;
            for (ToolSettings.Tool candidate : TOOLS) if (!candidate.name().equals(key) && toolVisible(candidate)) another = true;
            if (!another) return false;
        }
        app.prefs.setToolbarItemVisible(key, visible);
        rebuildTools(); if (isTool(key)) app.saveToolState();
        return true;
    }

    /** Recreates every toolbar button from the library and preferences. */
    void rebuildTools() {
        ToolLibrary library = library();
        if (library.activeId().isEmpty() && library.current().isBrush()) library.edit(library.current().asBrush());
        if (library.activeId().isEmpty() && !toolVisible(library.current().tool)) {
            for (String key : toolbarOrder()) {
                if (!isTool(key) || !toolVisible(ToolSettings.Tool.valueOf(key))) continue;
                library.select(ToolSettings.Tool.valueOf(key)); break;
            }
        }
        layersButton = null; zoomButton = null;
        sidebarPalette = null; sidebarSwatches.clear();
        toolRail.removeAllViews(); selectionButtons.clear();
        for (String key : toolbarOrder()) {
            if (!app.prefs.toolbarItemVisible(key)) continue;
            if (key.equals(PALETTE)) addSidebarPalette();
            else if (key.equals(ZOOM)) addZoom();
            else if (key.equals(LAYERS)) addLayers();
            else addTool(ToolSettings.Tool.valueOf(key));
        }
        boolean landscape = landscape();
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(landscape ? 6 : 36), dp(landscape ? 36 : 6));
        dividerParams.gravity = landscape ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL;
        dividerParams.setMargins(dp(landscape ? 10 : 0), dp(landscape ? 0 : 10), dp(landscape ? 10 : 0), dp(landscape ? 0 : 10));
        toolRail.addView(new ToolRail.Divider(app, landscape), dividerParams);
        for (ToolLibrary.Preset preset : library.presets()) addPreset(preset);
        app.refreshEraseControl();
        operationStatus = new TextView(app); operationStatus.setPadding(dp(8), dp(4), dp(4), dp(4)); toolRail.addView(operationStatus);
    }
    private ToolButton railButton(String title, int icon, Runnable action) {
        ToolButton button = app.iconButton(title, icon, action);
        button.iconHalf = 18;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(60));
        params.gravity = Gravity.CENTER_HORIZONTAL; params.setMargins(dp(2), dp(2), dp(2), dp(2));
        toolRail.addView(button, params);
        return button;
    }
    private void addLayers() {
        layersButton = railButton("Layers", R.drawable.ic_layers, () -> app.showLayers(layersButton));
        layersButton.selectionOutline = true; layersButton.press.feedback = app.selectionFeedback;
        markActive(layersButton, false);
    }
    private void addZoom() {
        zoomButton = railButton("Zoom", R.drawable.ic_zoom, app::toggleNavigationLock);
        zoomButton.iconHalf = 16;
        zoomButton.overlay = (canvas, button) -> drawLock(canvas, button, app.navigationLocked);
        markActive(zoomButton, false); refreshZoom();
    }
    /** A selectable tool or favorite: tap selects, tapping again opens settings. */
    private ToolButton selectable(String key, String name, int icon, Runnable select) {
        ToolButton control = railButton(name, icon, () -> {
            if (key.equals("tool:SHAPES") && !shapeChosen()) { select.run(); app.showToolSettings(); }
            else if (selectedKey().equals(key)) app.showToolSettings();
            else select.run();
        });
        control.settingsArrow = app.getDrawable(R.drawable.ic_chevron); control.selectionOutline = true;
        control.iconOffset = -4;
        control.setContentDescription(name + ". Tap to select; tap again for settings.");
        selectionButtons.put(key, control);
        control.marked = selectedKey().equals(key); markActive(control, control.marked);
        return control;
    }
    private void addTool(ToolSettings.Tool tool) {
        String key = "tool:" + tool;
        ToolSettings remembered = library().builtin(tool);
        ToolButton button = selectable(key, remembered.label(), ToolIcons.of(remembered), () -> {
            library().select(tool); refreshToolSelection(); app.saveToolState();
        });
        presentTool(button, remembered, null);
        button.setOnLongClickListener(v -> {
            if (!app.busy()) {
                app.setPickingShade(false); app.pad.finishStroke();
                if (!selectedKey().equals(key)) { library().select(tool); refreshToolSelection(); app.saveToolState(); }
                app.showToolSettings();
            }
            return true;
        });
    }
    private void addPreset(ToolLibrary.Preset preset) {
        ToolButton button = selectable(preset.id, preset.name, ToolIcons.of(preset.settings), () -> {
            library().recall(preset.id); refreshToolSelection(); app.saveToolState();
        });
        button.presetId = preset.id;
        presentTool(button, preset.settings, preset);
        button.setOnLongClickListener(v -> {
            if (!app.busy()) {
                app.setPickingShade(false); app.pad.finishStroke();
                v.startDragAndDrop(null, app.turnedShadow(v), v, 0);
            }
            return true;
        });
    }

    /** Updates icons, descriptions and the selection marker after a tool or setting change. */
    void refreshToolSelection() {
        app.refreshEraseControl();
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
            ToolButton button = selectionButtons.get("tool:" + tool);
            if (button != null) presentTool(button, library().builtin(tool), null);
        }
        for (ToolLibrary.Preset preset : library().presets()) {
            ToolButton button = selectionButtons.get(preset.id);
            if (button != null) presentTool(button, preset.settings, preset);
        }
        String selected = selectedKey();
        for (Map.Entry<String, ToolButton> entry : selectionButtons.entrySet())
            entry.getValue().mark(entry.getKey().equals(selected));
    }
    private void presentTool(ToolButton button, ToolSettings settings, ToolLibrary.Preset preset) {
        boolean unchosenShapes = preset == null && settings.tool == ToolSettings.Tool.SHAPES && !shapeChosen();
        button.iconOnly(unchosenShapes ? R.drawable.ic_shapes : ToolIcons.of(settings));
        if (preset == null) {
            button.setContentDescription(unchosenShapes ? "Shapes. Tap to choose a shape."
                    : settings.asBrush().description() + ". Tap to select; tap again for settings.");
            return;
        }
        int number = library().presetNumber(preset.id);
        if (button.presetNumber != number) { button.presetNumber = number; button.invalidate(); }
        button.setContentDescription(preset.name + ", " + settings.description() + ", favorite " + number
                + ". Tap to select; tap again for settings. Hold and drag to reorder.");
    }
    /** Static background for a toolbar control; the outline shows selection only without fast feedback. */
    void markActive(ToolButton button, boolean selected) {
        boolean outline = selected && !app.selectionFeedback.enabled && !button.selectionOutline;
        button.setBackground(Ui.outline(outline ? dp(2) : 1, outline ? Color.BLACK : 0xffaaaaaa, dp(4)));
        button.setSelected(selected);
    }

    void refreshZoom() {
        if (zoomButton == null) return;
        String caption = (app.pad == null ? 100 : app.pad.viewport.percent()) + "%";
        if (caption.equals(zoomButton.caption)) return;
        Runnable change = () -> { zoomButton.caption = caption; describeNavigation(); };
        if (app.pad != null && app.pad.navigating && zoomButton.getWidth() > 0) app.selectionFeedback.update(zoomButton, change);
        else { change.run(); zoomButton.invalidate(); }
    }
    void describeNavigation() {
        if (zoomButton == null) return;
        zoomButton.setContentDescription("Zoom " + zoomButton.caption + ". Zoom and pan "
                + (app.navigationLocked ? "locked. Tap to unlock." : "unlocked. Tap to lock."));
    }
    /** The padlock in the Zoom button's corner; open when pinch and pan are allowed. */
    private static void drawLock(Canvas canvas, ToolButton button, boolean locked) {
        // This corner is also captured by SelectionFeedback for immediate e-ink updates.
        Paint paint = new Paint();
        int right = button.getWidth()-dp(button, 4), top = dp(button, 4);
        paint.setColor(Color.WHITE);
        canvas.drawRect(right-dp(button, 17), top-dp(button, 1), right+dp(button, 1), top+dp(button, 19), paint);
        paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(button, 2)); paint.setAntiAlias(true);
        float left = right-dp(button, 12);
        canvas.save();
        if (!locked) canvas.rotate(-35, left+dp(button, 2), top+dp(button, 8));
        canvas.drawArc(left+dp(button, 2), top, right-dp(button, 2), top+dp(button, 12), 180, 180, false, paint);
        canvas.drawLine(left+dp(button, 2), top+dp(button, 6), left+dp(button, 2), top+dp(button, 9), paint);
        canvas.drawLine(right-dp(button, 2), top+dp(button, 6), right-dp(button, 2), top+dp(button, 9), paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(left, top+dp(button, 8), right, top+dp(button, 17), dp(button, 2), dp(button, 2), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(left+dp(button, 6), top+dp(button, 12), dp(button, 1), paint);
    }
    private static int dp(View view, float value) { return Ui.dp(view.getContext(), value); }

    // Palette strip

    private void addSidebarPalette() {
        boolean landscape = landscape();
        sidebarPalette = new LinearLayout(app);
        sidebarPalette.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        sidebarPalette.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams block = new LinearLayout.LayoutParams(landscape ? -2 : dp(60), landscape ? dp(60) : -2);
        block.setMargins(dp(2), dp(2), dp(2), dp(2));
        toolRail.addView(sidebarPalette, block);
        ToolButton settings = app.iconButton("Palette settings", R.drawable.ic_palette, app::paletteSettings);
        settings.settingsArrow = app.getDrawable(R.drawable.ic_chevron);
        markActive(settings, false);
        sidebarPalette.addView(settings, new LinearLayout.LayoutParams(dp(landscape ? 44 : 60), dp(landscape ? 60 : 44)));
        addSidebarSwatches(app.prefs.paletteShades());
    }
    /** The palette icon, which anchors the palette editor. */
    View paletteAnchor() { return sidebarPalette.getChildAt(0); }
    private void addSidebarSwatches(List<Integer> shades) {
        boolean landscape = landscape();
        sidebarSwatches.clear();
        for (int i = 0; i < shades.size(); i += 2) {
            LinearLayout pair = new LinearLayout(app);
            pair.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            sidebarPalette.addView(pair, new LinearLayout.LayoutParams(dp(landscape ? 40 : 60), dp(landscape ? 60 : 40)));
            for (int j = i; j < Math.min(i+2, shades.size()); j++) {
                PaletteSwatch swatch = new PaletteSwatch(app, shades.get(j), false);
                sidebarSwatches.add(swatch); describe(swatch, j);
                swatch.setOnTouchListener((v, event) -> { app.paintRub.track(event); return false; });
                swatch.setOnClickListener(v -> {
                    if (app.busy()) return;
                    app.hideGradientHint(); app.selectShade(swatch.tone); app.pad.applyGradient(); app.saveToolState();
                });
                pair.addView(swatch, new LinearLayout.LayoutParams(dp(landscape ? 40 : 30), dp(landscape ? 30 : 40)));
            }
        }
    }
    /** Shows changed palette shades without rebuilding the toolbar when only tones changed. */
    void showPaletteShades(List<Integer> shades) {
        if (sidebarPalette == null) return;
        if (sidebarSwatches.size() != shades.size()) {
            while (sidebarPalette.getChildCount() > 1) sidebarPalette.removeViewAt(1);
            addSidebarSwatches(shades);
            return;
        }
        for (int i = 0; i < shades.size(); i++) {
            PaletteSwatch swatch = sidebarSwatches.get(i);
            swatch.presentTone(shades.get(i), app.selectionFeedback, app.hasWindowFocus());
            describe(swatch, i);
        }
    }
    private static void describe(PaletteSwatch swatch, int index) {
        swatch.setContentDescription("Palette shade " + (index+1) + ": " + Math.round(swatch.tone*100f/255) + "% brightness");
    }

    /** Reorders favorites by drag and drop, auto-scrolling near the rail's ends. */
    final class PresetDragHandler implements View.OnDragListener, Runnable {
        private ToolButton source;
        private String beforeId;
        private float pointerY;
        private boolean validDrop;
        private int scrollStep;
        @Override public boolean onDrag(View view, DragEvent event) {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                if (app.busy() || !(event.getLocalState() instanceof ToolButton)) return false;
                ToolButton button = (ToolButton)event.getLocalState();
                if (button.presetId == null || button.getParent() != toolRail) return false;
                source = button; source.setAlpha(.4f); return true;
            }
            if (source == null || event.getLocalState() != source) return false;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                    pointerY = landscape() ? event.getX() : event.getY(); updateTarget();
                    toolRail.removeCallbacks(this);
                    scrollStep = pointerY < dp(48) ? -dp(12)
                            : pointerY > (landscape() ? landscapeTools.getWidth() : toolScroll.getHeight())-dp(48) ? dp(12) : 0;
                    if (scrollStep != 0) toolRail.postDelayed(this, 60);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    clearTarget(); return true;
                case DragEvent.ACTION_DROP:
                    pointerY = landscape() ? event.getX() : event.getY(); updateTarget();
                    if (!validDrop || app.busy()) return false;
                    library().moveBefore(source.presetId, beforeId);
                    app.saveToolState(); rebuildTools(); return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    reset(); return true;
                default: return true;
            }
        }
        private void updateTarget() {
            float y = pointerY + (landscape() ? landscapeTools.getScrollX()-toolRail.getLeft() : toolScroll.getScrollY()-toolRail.getTop());
            beforeId = null; validDrop = false; toolRail.dropLine = -1;
            List<ToolLibrary.Preset> presets = library().presets();
            if (!presets.isEmpty()) {
                ToolButton first = selectionButtons.get(presets.get(0).id);
                validDrop = first != null && y >= start(first)-dp(4);
            }
            if (validDrop) {
                for (ToolLibrary.Preset preset : presets) {
                    ToolButton button = selectionButtons.get(preset.id);
                    if (button == null || button == source) continue;
                    if (y < (start(button)+end(button))/2f) {
                        beforeId = preset.id; toolRail.dropLine = start(button)-dp(2); break;
                    }
                    toolRail.dropLine = end(button)+dp(2);
                }
                if (toolRail.dropLine < 0) toolRail.dropLine = end(source)+dp(2);
            }
            toolRail.invalidate();
        }
        @Override public void run() {
            if (source == null || scrollStep == 0) return;
            int previous = landscape() ? landscapeTools.getScrollX() : toolScroll.getScrollY();
            if (landscape()) landscapeTools.scrollBy(scrollStep, 0); else toolScroll.scrollBy(0, scrollStep);
            updateTarget();
            if ((landscape() ? landscapeTools.getScrollX() : toolScroll.getScrollY()) != previous) toolRail.postDelayed(this, 60);
        }
        private int start(View view) { return landscape() ? view.getLeft() : view.getTop(); }
        private int end(View view) { return landscape() ? view.getRight() : view.getBottom(); }
        private void clearTarget() {
            toolRail.removeCallbacks(this); scrollStep = 0; validDrop = false;
            toolRail.dropLine = -1; toolRail.invalidate();
        }
        void reset() {
            clearTarget();
            if (source != null) source.setAlpha(1);
            source = null;
        }
    }
}
