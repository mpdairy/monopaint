package io.github.mpdairy.monopaint;

import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

/**
 * A bordered panel that opens beside a toolbar control, over the canvas. Only one is
 * open at a time: showing one replaces the activity's current {@code toolPicker}.
 */
class SidePanel {
    final PaintActivity app;
    final View anchor;
    /** Width in dp before fitting to the canvas. */
    final int preferredWidth;
    final LinearLayout panel;
    final ScrollView scroll;
    final PopupWindow popup;

    SidePanel(PaintActivity app, View anchor, int preferredWidth) {
        this.app = app; this.anchor = anchor; this.preferredWidth = preferredWidth;
        panel = new LinearLayout(app); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(app.dp(10), app.dp(4), app.dp(10), app.dp(10));
        panel.setBackground(Ui.outline(app.dp(1), Color.BLACK, app.dp(8)));
        scroll = new ScrollView(app); scroll.addView(panel);
        popup = Popups.create(scroll, app.appTurn(), true, Color.TRANSPARENT);
        popup.setOnDismissListener(() -> { if (app.toolPicker == popup) app.toolPicker = null; });
    }

    void show() {
        if (app.toolPicker != null) app.toolPicker.dismiss();
        app.closePaletteEditor();
        SettingsForm.style(panel, app.prefs.largeSettingsText());
        app.toolPicker = popup; position();
    }
    /** Re-fits the panel beside its anchor, for example after rows appear. */
    void position() { app.showBesideTool(popup, scroll, anchor, preferredWidth); }

    Button closeButton(String description) {
        Button close = new Button(app); close.setText("×"); close.setTag(SettingsForm.CLOSE);
        close.setContentDescription(description); close.setBackgroundColor(Color.WHITE); close.setStateListAnimator(null);
        close.setOnClickListener(v -> popup.dismiss()); return close;
    }
}
