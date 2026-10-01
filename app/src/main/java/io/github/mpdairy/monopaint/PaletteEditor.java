package io.github.mpdairy.monopaint;

import android.graphics.Color;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * Edits the saved palette beside the toolbar. Select a cell, then pick a shade on the
 * main color bar; the last cell adds a shade. Drag cells to reorder or onto the trash.
 * The panel is not focusable, so the color bar stays usable while it is open.
 */
final class PaletteEditor {
    private static final int HINT_DELAY_MS = 3000;
    /** Drop target index for the trash. */
    private static final int TRASH = -2;
    private final PaintActivity app;
    final ArrayList<Integer> shades;
    final ArrayList<PaletteSwatch> cells = new ArrayList<>();
    final LinearLayout content, grid;
    final TextView hint;
    final ImageButton trash;
    final PopupWindow popup;
    final ScrollView scroll;
    final SelectionFeedback swatchFeedback = new SelectionFeedback();
    private boolean colorsDirty;
    private int pendingTone;
    int selected = -1, dropTarget = -1;
    private Drag dragging;
    private final Runnable delayedHint;

    /** Identifies this editor's own drags. */
    private static final class Drag {
        final PaletteEditor owner; final int index;
        Drag(PaletteEditor owner, int index) { this.owner = owner; this.index = index; }
    }

    PaletteEditor(PaintActivity app) {
        this.app = app;
        shades = app.prefs.paletteShades();
        content = new LinearLayout(app); grid = new LinearLayout(app);
        hint = new TextView(app); trash = new ImageButton(app); scroll = new ScrollView(app);
        delayedHint = () -> {
            if (app.paletteEditor == this && selected == shades.size() && selected < PaintPreferences.MAX_PALETTE_SHADES && dragging == null)
                hint.setText("Select a color");
        };
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.setBackground(Ui.outline(dp(1), Color.BLACK, dp(4)));
        LinearLayout heading = new LinearLayout(app); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(app); title.setText("Palette"); title.setTextSize(18); title.setTextColor(Color.BLACK);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button close = new Button(app); close.setText("×"); close.setTextSize(24); close.setContentDescription("Close palette");
        close.setBackgroundColor(Color.WHITE); close.setOnClickListener(v -> app.closePaletteEditor());
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); content.addView(heading);
        LinearLayout tools = new LinearLayout(app); tools.setGravity(Gravity.TOP);
        grid.setOrientation(LinearLayout.VERTICAL); tools.addView(grid, new LinearLayout.LayoutParams(dp(112), -2));
        trash.setImageResource(R.drawable.ic_trash); trash.setContentDescription("Drag a palette swatch here to delete");
        trash.setBackgroundColor(Color.WHITE); trash.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams bin = new LinearLayout.LayoutParams(dp(48), dp(48)); bin.leftMargin = dp(16);
        tools.addView(trash, bin); trash.setClickable(false);
        trash.setOnDragListener((v, event) -> dragEvent(TRASH, event)); content.addView(tools);
        hint.setTextSize(14); hint.setTextColor(Color.BLACK); hint.setMinHeight(dp(44)); hint.setPadding(0, dp(8), 0, 0); content.addView(hint);
        scroll.addView(content);
        // A non-focusable panel leaves the original color strip touchable.
        popup = Popups.create(scroll, app.appTurn(), false, Color.WHITE);
        popup.setOnDismissListener(() -> {
            hint.removeCallbacks(delayedHint); dragging = null;
            commitColors(); swatchFeedback.close();
            if (app.paletteEditor == this) app.paletteEditor = null;
            app.saveToolState(); app.pad.invalidate(); app.pad.post(app.pad::connectDisplay);
        });
        render();
    }
    private int dp(float value) { return app.dp(value); }

    void show() { position(); }
    void position() { app.showBesideTool(popup, scroll, app.toolbar.paletteAnchor(), 224); }

    void render() {
        grid.removeAllViews(); cells.clear();
        int count = Math.min(PaintPreferences.MAX_PALETTE_SHADES, shades.size()+1);
        LinearLayout row = null;
        for (int i = 0; i < count; i++) {
            final int index = i;
            if (i % 2 == 0) { row = new LinearLayout(app); grid.addView(row); }
            PaletteSwatch cell = new PaletteSwatch(app, i < shades.size() ? shades.get(i) : -1, true);
            cell.setContentDescription(i < shades.size() ? "Edit palette shade " + (i+1) : "Add palette shade");
            row.addView(cell, new LinearLayout.LayoutParams(dp(56), dp(56))); cells.add(cell);
            cell.setOnClickListener(v -> select(index));
            cell.setOnDragListener((v, event) -> dragEvent(index, event));
            if (i < shades.size()) startDragOnMove(cell, index);
        }
        SettingsForm.style(content, app.prefs.largeSettingsText()); refreshSelection();
        if (popup != null && popup.isShowing()) position();
    }
    /** Starts a drag once the pen moves past touch slop, or on long press. */
    private void startDragOnMove(PaletteSwatch cell, int index) {
        float[] down = new float[2]; boolean[] started = {false};
        cell.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                down[0] = event.getX(); down[1] = event.getY(); started[0] = false;
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_MOVE && !started[0]) {
                float dx = event.getX()-down[0], dy = event.getY()-down[1];
                int slop = android.view.ViewConfiguration.get(app).getScaledTouchSlop();
                if (dx*dx+dy*dy > slop*slop) started[0] = startDrag(cell, index);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                v.getParent().requestDisallowInterceptTouchEvent(false);
            return started[0];
        });
        cell.setOnLongClickListener(v -> startDrag(cell, index));
    }
    void refreshSelection() {
        for (int i = 0; i < cells.size(); i++) {
            PaletteSwatch cell = cells.get(i); cell.chosen = i == selected; cell.dropHere = i == dropTarget; cell.invalidate();
        }
        trash.setAlpha(selected >= 0 && selected < shades.size() || dragging != null ? 1f : .35f);
        trash.setBackgroundColor(dropTarget == TRASH ? 0xffdddddd : Color.WHITE);
    }
    void select(int index) {
        commitColors();
        hint.removeCallbacks(delayedHint); hint.setText(""); selected = -1;
        if (index < shades.size()) app.selectShade(shades.get(index));
        selected = index; refreshSelection(); app.saveToolState();
        if (index == shades.size()) hint.postDelayed(delayedHint, HINT_DELAY_MS);
    }
    /** The color bar picked a shade for the selected cell. */
    void colorChanged(int tone) {
        if (selected < 0 || selected > shades.size() || dragging != null) return;
        hint.removeCallbacks(delayedHint);
        // The main strip stays on its normal path throughout the gesture.
        // Keep the grid and sidebar unchanged until the pen/finger lifts.
        pendingTone = tone; colorsDirty = true;
    }
    /** Saves the shade picked since the last commit, at pen-up. */
    void commitColors() {
        if (!colorsDirty) return;
        colorsDirty = false;
        if (selected < 0 || selected > shades.size()) return;
        if (hint.length() > 0) hint.setText("");
        boolean added = selected == shades.size();
        if (added) {
            if (shades.size() == PaintPreferences.MAX_PALETTE_SHADES) return;
            shades.add(pendingTone);
        } else {
            if (shades.get(selected) == pendingTone) return;
            shades.set(selected, pendingTone);
            cells.get(selected).presentTone(pendingTone, swatchFeedback, app.hasWindowFocus());
        }
        app.savePalette(shades);
        if (added) render();
    }
    private boolean startDrag(PaletteSwatch cell, int index) {
        commitColors();
        if (dragging != null || index >= shades.size()) return false;
        hint.removeCallbacks(delayedHint); hint.setText("");
        Drag token = new Drag(this, index);
        boolean started = cell.startDragAndDrop(null, app.turnedShadow(cell), token, 0);
        if (started) { dragging = token; cell.setPressed(false); refreshSelection(); }
        return started;
    }
    private boolean dragEvent(int target, DragEvent event) {
        if (!(event.getLocalState() instanceof Drag) || ((Drag)event.getLocalState()).owner != this) return false;
        Drag token = (Drag)event.getLocalState();
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return true;
            case DragEvent.ACTION_DRAG_ENTERED: dropTarget = target; refreshSelection(); return true;
            case DragEvent.ACTION_DRAG_EXITED: dropTarget = -1; refreshSelection(); return true;
            case DragEvent.ACTION_DROP:
                dragging = null; dropTarget = -1;
                if (target == TRASH) delete(token.index); else move(token.index, target);
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                dragging = null; dropTarget = -1; refreshSelection();
                if (selected == shades.size()) hint.postDelayed(delayedHint, HINT_DELAY_MS);
                return true;
            default: return true;
        }
    }
    private void move(int from, int before) {
        if (from < 0 || from >= shades.size() || before < 0 || before > shades.size()) return;
        int to = before-(from < before ? 1 : 0);
        if (from == to) return;
        int tone = shades.remove(from); shades.add(to, tone);
        if (selected == from) selected = to;
        else if (selected >= 0 && selected < shades.size()) {
            if (selected > from) selected--;
            if (selected >= to) selected++;
        }
        app.savePalette(shades); render();
    }
    private void delete(int index) {
        if (index < 0 || index >= shades.size()) return;
        hint.removeCallbacks(delayedHint); shades.remove(index); app.savePalette(shades);
        selected = -1; render(); select(Math.min(index, shades.size()));
    }
}
