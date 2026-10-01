package io.github.mpdairy.monopaint;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Settings for the selected tool or favorite: variant choices, the tool's own
 * {@link ToolControls}, and a button to add it to the toolbar (or delete a favorite).
 */
final class ToolSettingsPanel extends SidePanel {
    private final ToolSettings.Tool tool;
    private final boolean brush;
    /** The favorite being edited, or empty for a built-in tool. */
    private final String presetId;

    ToolSettingsPanel(PaintActivity app, View anchor) {
        super(app, anchor, width(app));
        tool = app.library.current().tool; brush = app.library.current().isBrush(); presetId = app.library.activeId();
        render();
    }
    private static int width(PaintActivity app) {
        boolean large = app.prefs.largeSettingsText();
        return app.library.current().tool == ToolSettings.Tool.SHAPES ? (large ? 560 : 520) : (large ? 448 : 400);
    }
    private ToolLibrary library() { return app.library; }
    /** Shapes start with only the shape choices until one is picked. */
    private boolean shapeChosen() { return !presetId.isEmpty() || app.toolbar.shapeChosen(); }

    void render() {
        panel.removeAllViews();
        ToolSettings current = library().current();
        LinearLayout variants = ToolControls.variants(app, current, shapeChosen(),
                head -> { library().selectHead(head); variantChosen(); },
                type -> { library().edit(library().current().gradient(type)); variantChosen(); },
                shape -> {
                    library().edit(library().current().shape(shape));
                    if (presetId.isEmpty()) app.prefs.setShapeChosen();
                    variantChosen();
                });
        if (variants != null) panel.addView(variants);
        SettingsForm form = null;
        if (tool != ToolSettings.Tool.SHAPES || shapeChosen()) {
            form = new SettingsForm(app, app.prefs.largeSettingsText(), new SettingsForm.Editor() {
                @Override public ToolSettings current() { return library().current(); }
                @Override public void edit(ToolSettings changed) {
                    library().edit(changed); app.saveToolState(); app.toolbar.refreshToolSelection();
                }
            });
            form.resized = this::position;
            ToolControls.add(form, current, SettingsForm.textSize(app.prefs.largeSettingsText()));
        }
        boolean controls = form != null && form.content.getChildCount() > 0;
        // Unchosen Shapes keeps its rule above the actions.
        if (variants != null && (controls || tool == ToolSettings.Tool.SHAPES)) {
            View divider = new View(app); divider.setBackgroundColor(0xffcccccc);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, app.dp(1));
            line.setMargins(app.dp(2), app.dp(12), app.dp(2), 0); panel.addView(divider, line);
        }
        if (controls) panel.addView(form.content);
        panel.addView(actions());
        SettingsForm.style(panel, app.prefs.largeSettingsText());
        if (popup.isShowing()) position();
    }
    private LinearLayout actions() {
        LinearLayout actions = new LinearLayout(app); actions.setGravity(Gravity.END);
        Button save = new Button(app); save.setAllCaps(false); save.setBackgroundColor(Color.WHITE);
        if (presetId.isEmpty()) { save.setText("Add to Toolbar"); save.setContentDescription("Add to Toolbar"); }
        else {
            save.setContentDescription("Delete custom tool");
            save.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_trash, 0, 0, 0);
        }
        save.setOnClickListener(v -> {
            try {
                if (presetId.isEmpty()) library().add(); else library().remove(presetId);
                app.saveToolState(); popup.dismiss(); app.toolbar.rebuildTools();
            } catch (IllegalStateException error) { app.message(error.getMessage()); }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, app.dp(48), 1));
        String close = tool == ToolSettings.Tool.SHAPES ? "Close shapes" : brush ? "Close brush" : "Close tool settings";
        actions.addView(closeButton(close), new LinearLayout.LayoutParams(app.dp(48), app.dp(48)));
        return actions;
    }
    private void variantChosen() {
        app.saveToolState(); app.toolbar.refreshToolSelection(); render();
    }
}
