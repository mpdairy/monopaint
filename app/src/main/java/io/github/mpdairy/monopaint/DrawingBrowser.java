package io.github.mpdairy.monopaint;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.View;
import android.widget.*;

/** A folder picker for the app's private drawing library. */
final class DrawingBrowser {
    interface Selection { void choose(String path, boolean replace); }
    private final Activity activity;
    private final DocumentStore store;
    private final boolean saving;
    private final Selection selection;
    private final Runnable cancelled;
    private AlertDialog dialog;
    private TextView location, status;
    private Button up, newFolder;
    private EditText name;
    private ListView list;
    private DrawingFiles.Entry[] entries = new DrawingFiles.Entry[0];
    private String folder;
    private int request;
    private boolean chosen, working;

    DrawingBrowser(Activity activity, DocumentStore store, boolean saving, String current,
                   Selection selection, Runnable cancelled) {
        this.activity = activity; this.store = store; this.saving = saving;
        this.selection = selection; this.cancelled = cancelled;
        folder = DrawingFiles.parent(current);
        build(DrawingFiles.name(current));
    }

    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
    private boolean alive() { return !activity.isDestroyed() && dialog.isShowing(); }

    private void build(String currentName) {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        location = new TextView(activity); location.setTextSize(18); content.addView(location);
        LinearLayout actions = new LinearLayout(activity);
        up = new Button(activity); up.setText("Up"); up.setAllCaps(false);
        up.setOnClickListener(v -> navigate(DrawingFiles.parent(folder)));
        actions.addView(up, new LinearLayout.LayoutParams(0, dp(52), 1));
        newFolder = new Button(activity); newFolder.setText("New folder"); newFolder.setAllCaps(false);
        newFolder.setOnClickListener(v -> createFolder());
        actions.addView(newFolder, new LinearLayout.LayoutParams(0, dp(52), 1)); content.addView(actions);
        status = new TextView(activity); content.addView(status);
        list = new ListView(activity); content.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (working) return;
            DrawingFiles.Entry entry = entries[position];
            if (entry.folder) navigate(DrawingFiles.child(folder, entry.name));
            else if (saving) { name.setText(entry.name); name.setSelection(name.length()); }
            else choose(DrawingFiles.child(folder, entry.name), false);
        });
        if (saving) {
            name = new EditText(activity); name.setSingleLine(true); name.setHint("Drawing name");
            name.setContentDescription("Drawing name"); name.setText(currentName); content.addView(name);
        } else {
            TextView hint = new TextView(activity);
            hint.setText("Opening a drawing replaces the current canvas. Save first to keep your changes.");
            content.addView(hint);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(saving ? "Save drawing as…" : "Open drawing").setView(content)
                .setNegativeButton("Cancel", null);
        if (saving) builder.setPositiveButton("Save", null);
        dialog = builder.create();
        dialog.setOnDismissListener(d -> { if (!chosen) cancelled.run(); });
        dialog.show();
        orient(dialog);
        if (saving) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save());
        navigate(folder);
    }

    private void setWorking(boolean value) {
        working = value; list.setEnabled(!value); up.setEnabled(!value && !folder.isEmpty());
        newFolder.setEnabled(!value);
        if (saving) { name.setEnabled(!value); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!value); }
    }

    private void navigate(String destination) {
        int token = ++request;
        setWorking(true); status.setText("Loading…");
        store.list(destination, (found, error) -> activity.runOnUiThread(() -> {
            if (!alive() || token != request) return;
            if (error != null) {
                status.setText("Could not open folder: " + error.getMessage()); setWorking(false); return;
            }
            folder = destination; entries = found;
            location.setText(folder.isEmpty() ? "Drawings" : "Drawings / " + folder.replace("/", " / "));
            String[] labels = new String[found.length];
            for (int i = 0; i < found.length; i++) labels[i] = found[i].name;
            list.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels) {
                @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, recycled, parent);
                    row.setTextColor(Color.BLACK); row.setMinHeight(dp(56));
                    row.setCompoundDrawablesRelativeWithIntrinsicBounds(entries[position].folder ? R.drawable.ic_folder : R.drawable.ic_save, 0, 0, 0);
                    row.setCompoundDrawablePadding(dp(12));
                    row.setContentDescription((entries[position].folder ? "Folder " : "Drawing ") + labels[position]);
                    return row;
                }
            });
            status.setText(found.length == 0 ? "This folder is empty" : ""); setWorking(false);
        }));
    }

    private void save() {
        String value = name.getText().toString().trim();
        if (!DrawingFiles.validName(value)) {
            name.setError("Use 1–64 letters, numbers, spaces, - or _; _recovery is reserved"); return;
        }
        String path = DrawingFiles.child(folder, value);
        setWorking(true);
        store.exists(path, (exists, error) -> activity.runOnUiThread(() -> {
            if (!alive()) return;
            if (error != null) { status.setText(error.getMessage()); setWorking(false); return; }
            if (!exists) { choose(path, false); return; }
            AlertDialog confirmation = new AlertDialog.Builder(activity).setTitle("Replace “" + value + "”?")
                    .setMessage("A drawing with this name already exists in this folder.")
                    .setPositiveButton("Replace", (d, w) -> { if (alive()) choose(path, true); })
                    .setNegativeButton("Cancel", null).create();
            confirmation.setOnDismissListener(d -> { if (alive()) setWorking(false); }); confirmation.show(); orient(confirmation);
        }));
    }

    private void choose(String path, boolean replace) {
        chosen = true; dialog.dismiss(); selection.choose(path, replace);
    }

    private void createFolder() {
        EditText input = new EditText(activity); input.setSingleLine(true); input.setHint("Folder name");
        AlertDialog prompt = new AlertDialog.Builder(activity).setTitle("New folder").setView(input)
                .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create();
        prompt.show();
        orient(prompt);
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!DrawingFiles.validName(value)) {
                input.setError("Use 1–64 letters, numbers, spaces, - or _; _recovery is reserved"); return;
            }
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            store.createFolder(folder, value, (unused, error) -> activity.runOnUiThread(() -> {
                if (!alive() || !prompt.isShowing()) return;
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (error != null) { input.setError(error.getMessage()); return; }
                prompt.dismiss(); navigate(DrawingFiles.child(folder, value));
            }));
        });
    }
    private void orient(AlertDialog window) {
        if (activity instanceof PaintActivity) ((PaintActivity)activity).orientDialog(window);
    }
}
