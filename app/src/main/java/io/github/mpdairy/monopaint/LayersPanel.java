package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The Layers popup: list with visibility toggles, plus add, reorder, opacity, rename
 * and delete for the selected layer. Every edit is one undoable document change.
 */
final class LayersPanel {
    private final PaintActivity app;
    private final ToolButton trigger;
    private final ToneDocument document;
    final PopupWindow popup;

    LayersPanel(PaintActivity app, ToolButton trigger) {
        this.app = app; this.trigger = trigger; document = app.pad.document;
        LinearLayout rows = new LinearLayout(app); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        rows.setBackground(Ui.outline(dp(2), Color.BLACK, 0));
        TextView title = new TextView(app); title.setText("Layers · " + document.layerCount() + " / " + ToneDocument.MAX_LAYERS);
        title.setTextColor(Color.BLACK); title.setTextSize(18); title.setPadding(dp(8), dp(4), 0, dp(4));
        title.setTypeface(null, Typeface.BOLD); rows.addView(title);
        TextView hint = SettingsForm.hintText(app, "Top layers cover the ones below. Tap a name to draw on it.");
        hint.setPadding(dp(8), 0, dp(8), dp(8)); rows.addView(hint);
        LinearLayout actions = new LinearLayout(app); rows.addView(actions);
        action(actions, "Add layer", () -> { change(document::addLayer); reopen(); }, document.layerCount() < ToneDocument.MAX_LAYERS);
        action(actions, "Done", this::dismiss, true);
        ScrollView list = new ScrollView(app);
        LinearLayout items = new LinearLayout(app); items.setOrientation(LinearLayout.VERTICAL); list.addView(items);
        rows.addView(list, new LinearLayout.LayoutParams(-1, dp(document.layerCount()*56)));
        for (int i = document.layerCount()-1; i >= 0; i--) items.addView(layerRow(i), new LinearLayout.LayoutParams(-1, dp(56)));
        int active = document.activeLayer();
        TextView editing = new TextView(app); editing.setText("Editing: " + document.layerName(active));
        editing.setTextSize(14); editing.setTextColor(Color.BLACK); editing.setPadding(dp(8), dp(8), 0, 0); rows.addView(editing);
        addOpacity(rows, active);
        LinearLayout order = new LinearLayout(app); rows.addView(order);
        action(order, "Move up", () -> { change(() -> document.moveLayer(document.activeLayer()+1)); reopen(); }, active+1 < document.layerCount());
        action(order, "Move down", () -> { change(() -> document.moveLayer(document.activeLayer()-1)); reopen(); }, active > 0);
        LinearLayout manage = new LinearLayout(app); rows.addView(manage);
        action(manage, "Rename", this::rename, true);
        action(manage, "Delete", this::delete, document.layerCount() > 1);
        SettingsForm.style(rows, app.prefs.largeSettingsText());

        ScrollView scroll = new ScrollView(app); scroll.addView(rows);
        popup = Popups.create(scroll, app.appTurn());
        RectF anchor = Popups.bounds(app.root, trigger);
        int rootWidth = app.root.getWidth(), rootHeight = app.root.getHeight();
        int width = Math.min(dp(app.prefs.largeSettingsText() ? 400 : 360), rootWidth);
        int height = Math.min(Popups.measuredHeight(scroll, width, 0), rootHeight);
        float left = app.toolboxRight && !app.landscape ? anchor.left-width : anchor.right;
        float top = app.landscape ? anchor.bottom : anchor.top;
        RectF bounds = Popups.rect(Popups.clamp(left, 0, rootWidth-width), Popups.clamp(top, 0, rootHeight-height), width, height);
        app.selectionFeedback.update(trigger, trigger.markerArea(), () -> trigger.marked = true);
        popup.setOnDismissListener(() -> {
            if (app.layersPopup == popup) app.layersPopup = null;
            app.layerFeedback.close();
            app.selectionFeedback.update(trigger, trigger.markerArea(), () -> trigger.marked = false);
        });
        app.layersPopup = popup;
        Popups.show(popup, app.root, bounds);
    }
    private int dp(float value) { return app.dp(value); }
    private void dismiss() { if (app.layersPopup != null) app.layersPopup.dismiss(); }
    private void change(Runnable edit) { app.layerChange(edit); }
    /** Rebuilds the panel after a change to the layer list. */
    private void reopen() { dismiss(); new LayersPanel(app, trigger); }

    private LinearLayout layerRow(int index) {
        boolean selected = index == document.activeLayer();
        LinearLayout row = new LinearLayout(app); row.setGravity(Gravity.CENTER_VERTICAL);
        ToolButton name = textButton(document.layerName(index) + (selected ? "  ✓" : ""));
        name.setTextSize(15); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        app.toolbar.markActive(name, selected);
        name.setOnClickListener(v -> { change(() -> document.selectLayer(index)); dismiss(); });
        row.addView(name, new LinearLayout.LayoutParams(0, dp(52), 1));
        ToolButton visible = new ToolButton(app, app); visible.press.feedback = app.layerFeedback; visible.iconHalf = 16;
        visible.setBackgroundColor(Color.WHITE); visible.setPadding(dp(10), dp(10), dp(10), dp(10));
        Runnable describe = () -> {
            boolean shown = document.layerVisible(index);
            visible.iconOnly(shown ? R.drawable.ic_layer_visible : R.drawable.ic_layer_hidden);
            visible.setContentDescription((shown ? "Hide " : "Show ") + document.layerName(index));
            visible.setTooltipText(visible.getContentDescription());
            name.setContentDescription(document.layerName(index) + (selected ? ", selected" : "") + ", " + (shown ? "visible" : "hidden"));
        };
        describe.run();
        visible.setOnClickListener(view -> {
            change(() -> document.setLayerVisible(index, !document.layerVisible(index)));
            app.layerFeedback.update(visible, describe);
        });
        row.addView(visible, new LinearLayout.LayoutParams(dp(52), dp(52)));
        return row;
    }
    private void addOpacity(LinearLayout rows, int active) {
        TextView label = new TextView(app); label.setText("Opacity\n" + document.layerOpacity(active) + "%");
        SeekBar opacity = new SeekBar(app); opacity.setContentDescription("Layer opacity");
        opacity.setMax(100); opacity.setProgress(document.layerOpacity(active));
        SettingsForm.sliderRow(rows, label, opacity, app.prefs.largeSettingsText());
        // One undo step per drag: apply the opacity when the pen lifts.
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { label.setText("Opacity\n" + value + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                change(() -> document.setLayerOpacity(document.activeLayer(), bar.getProgress()));
            }
        });
    }
    private ToolButton textButton(String label) {
        ToolButton button = new ToolButton(app, app); button.press.feedback = app.layerFeedback;
        button.setText(label); button.setAllCaps(false);
        return button;
    }
    private void action(LinearLayout row, String label, Runnable action, boolean enabled) {
        ToolButton button = textButton(label);
        button.setTextSize(14); button.setPadding(dp(4), 0, dp(4), 0);
        button.setContentDescription(label); button.setEnabled(enabled);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    private void rename() {
        dismiss();
        EditText name = new EditText(app); name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        name.setText(document.layerName(document.activeLayer())); name.selectAll();
        app.showDialog(new AlertDialog.Builder(app).setTitle("Layer name").setView(name)
                .setPositiveButton("Save", (dialog, which) -> change(() -> document.renameLayer(name.getText().toString())))
                .setNegativeButton("Cancel", null));
    }
    private void delete() {
        dismiss();
        app.showDialog(new AlertDialog.Builder(app).setTitle("Delete " + document.layerName(document.activeLayer()) + "?")
                .setMessage("Other layers stay as they are. You can undo this.")
                .setPositiveButton("Delete", (dialog, which) -> change(document::removeLayer))
                .setNegativeButton("Cancel", null));
    }
}
