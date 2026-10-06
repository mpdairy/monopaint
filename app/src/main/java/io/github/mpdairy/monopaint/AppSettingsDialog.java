package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.util.ArrayList;

/** The Settings dialog: drawing hand, rotation, sizes, wet gravity, smooth edges, tablet simulation and toolbar contents. */
final class AppSettingsDialog {
    private final PaintActivity app;
    private final PaintPreferences prefs;
    private final LinearLayout content;
    AlertDialog dialog;

    AppSettingsDialog(PaintActivity app) {
        this.app = app; prefs = app.prefs;
        content = new LinearLayout(app); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        label("Drawing hand");
        content.addView(SettingsForm.pair(app, "Right-handed", "Left-handed", prefs.toolboxRight(),
                left -> { prefs.setToolboxRight(left); app.applyToolboxSide(); }));
        Button manualRotation = new Button(app);
        manualRotation.setText(app.landscape ? "Turn to portrait" : "Turn to landscape");
        content.addView(manualRotation);
        label("Settings text size");
        RadioGroup textSizes = SettingsForm.pair(app, "Medium", "Large", prefs.largeSettingsText(), large -> {
            prefs.setLargeSettingsText(large);
            SettingsForm.style(dialog.getWindow().getDecorView(), large); content.requestLayout();
        });
        textSizes.getChildAt(0).setContentDescription("Medium settings text");
        textSizes.getChildAt(1).setContentDescription("Large settings text");
        content.addView(textSizes);
        CheckBox gravity = new CheckBox(app);
        gravity.setText("Wet canvas uses gravity"); gravity.setContentDescription("Wet canvas uses gravity");
        gravity.setChecked(prefs.wetGravity());
        gravity.setOnCheckedChangeListener((button, checked) -> prefs.setWetGravity(checked));
        content.addView(gravity);
        CheckBox smooth = new CheckBox(app);
        smooth.setText("Smooth brush edges (better for PNG exports; adds a tiny bit of lag)"); smooth.setContentDescription("Smooth brush edges");
        smooth.setChecked(prefs.smoothEdges());
        smooth.setOnCheckedChangeListener((button, checked) -> prefs.setSmoothEdges(checked));
        content.addView(smooth);
        CheckBox simulate = new CheckBox(app);
        if (app.canSimulate()) {
            String label = app.device.simulates.name + " Simulation Mode";
            simulate.setText(label); simulate.setContentDescription(label);
            simulate.setChecked(app.simulating());
            content.addView(simulate);
        }
        label("Toolbar");
        LinearLayout toolsList = new LinearLayout(app); toolsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(toolsList); renderToolbarSettings(toolsList);
        SettingsForm.style(content, prefs.largeSettingsText());
        SettingsScroller scroll = new SettingsScroller(app, content);
        dialog = new AlertDialog.Builder(app).setTitle("Settings").setView(scroll).setPositiveButton("Done", null).create();
        dialog.show(); app.compactDialog(dialog);
        simulate.setOnCheckedChangeListener((button, checked) -> { dialog.dismiss(); app.setSimulating(checked); });
        manualRotation.setOnClickListener(v -> { dialog.dismiss(); app.requestQuarter(app.landscape ? 0 : 3); });
    }
    private int dp(float value) { return app.dp(value); }
    private void label(String text) {
        TextView label = new TextView(app); label.setText(text); content.addView(label);
    }

    /** One row per toolbar entry: visibility checkbox and move up/down buttons. */
    private void renderToolbarSettings(LinearLayout list) {
        list.removeAllViews();
        ArrayList<String> order = app.toolbar.toolbarOrder();
        for (int index = 0; index < order.size(); index++) {
            String key = order.get(index);
            LinearLayout row = new LinearLayout(app); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4)); list.addView(row, new LinearLayout.LayoutParams(-1, dp(56)));
            String name = entryName(key);
            CheckBox visible = new CheckBox(app); visible.setText(name); visible.setTextColor(Color.BLACK); visible.setTextSize(15);
            Drawable icon = app.getDrawable(entryIcon(key));
            icon.setBounds(0, 0, dp(28), dp(28));
            visible.setCompoundDrawables(icon, null, null, null); visible.setCompoundDrawablePadding(dp(12));
            visible.setPadding(dp(4), 0, dp(8), 0);
            visible.setChecked(prefs.toolbarItemVisible(key));
            row.addView(visible, new LinearLayout.LayoutParams(0, -1, 1));
            visible.setOnCheckedChangeListener((button, checked) -> {
                if (!app.toolbar.setVisible(key, checked)) {
                    button.setChecked(true); app.message("Keep at least one regular tool visible.");
                }
            });
            for (int direction : new int[]{-1, 1}) {
                ImageButton move = new ImageButton(app);
                move.setImageResource(direction < 0 ? R.drawable.ic_move_up : R.drawable.ic_move_down);
                move.setContentDescription("Move " + name + (direction < 0 ? " up" : " down"));
                move.setBackgroundColor(Color.WHITE); move.setPadding(dp(12), dp(12), dp(12), dp(12));
                Ui.setDimmed(move, index+direction >= 0 && index+direction < order.size(), .25f);
                row.addView(move, new LinearLayout.LayoutParams(dp(48), dp(48)));
                move.setOnClickListener(v -> { app.toolbar.move(key, direction); renderToolbarSettings(list); });
            }
            if (index+1 < order.size()) {
                View divider = new View(app); divider.setBackgroundColor(0xffdddddd);
                list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        SettingsForm.style(list, prefs.largeSettingsText());
    }
    private String entryName(String key) {
        switch (key) {
            case Toolbar.LAYERS: return "Layers";
            case Toolbar.ZOOM: return "Zoom";
            case Toolbar.PALETTE: return "Palette";
            default: return app.library.builtin(ToolSettings.Tool.valueOf(key)).label();
        }
    }
    private int entryIcon(String key) {
        switch (key) {
            case Toolbar.LAYERS: return R.drawable.ic_layers;
            case Toolbar.ZOOM: return R.drawable.ic_zoom;
            case Toolbar.PALETTE: return R.drawable.ic_palette;
            default:
                ToolSettings settings = app.library.builtin(ToolSettings.Tool.valueOf(key));
                return settings.tool == ToolSettings.Tool.SHAPES ? R.drawable.ic_shapes : ToolIcons.of(settings);
        }
    }
}
