package io.github.mpdairy.monopaint;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.*;
import java.util.HashMap;

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
    private AbsListView list;
    private DrawingFiles.Entry[] entries = new DrawingFiles.Entry[0];
    private String folder;
    private int request;
    // First-page previews by drawing path; null while loading or if unreadable.
    private final HashMap<String, Bitmap> previews = new HashMap<>();
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
        if (saving) list = new ListView(activity);
        else {
            // Opening shows first-page tiles in columns, like the page overview.
            GridView grid = new GridView(activity); grid.setNumColumns(GridView.AUTO_FIT); grid.setColumnWidth(dp(120));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setVerticalSpacing(dp(8)); list = grid;
        }
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(saving ? 260 : 480)));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (working) return;
            DrawingFiles.Entry entry = entries[position];
            if (entry.folder) navigate(DrawingFiles.child(folder, entry.name));
            else if (saving) { name.setText(entry.name); name.setSelection(name.length()); }
            else choose(DrawingFiles.child(folder, entry.name), false);
        });
        // Unsaved changes are settled before the Open browser appears.
        if (saving) {
            name = new EditText(activity); name.setSingleLine(true); name.setHint("Painting name");
            name.setContentDescription("Painting name"); name.setText(currentName); content.addView(name);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(saving ? "Save painting as…" : "Open painting").setView(content)
                .setNegativeButton("Cancel", null);
        if (saving) builder.setPositiveButton("Save", null);
        dialog = builder.create();
        dialog.setOnDismissListener(d -> { if (!chosen) cancelled.run(); });
        dialog.show();
        if (activity instanceof PaintActivity) ((PaintActivity) activity).orientDialog(dialog, saving ? 440 : 600);
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
            location.setText(folder.isEmpty() ? "Paintings" : "Paintings / " + folder.replace("/", " / "));
            String[] labels = new String[found.length];
            for (int i = 0; i < found.length; i++) labels[i] = found[i].name;
            list.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels) {
                @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, recycled, parent);
                    DrawingFiles.Entry entry = entries[position];
                    row.setTextColor(Color.BLACK); row.setMinHeight(dp(56));
                    row.setCompoundDrawablePadding(dp(saving ? 12 : 4));
                    if (saving) {
                        row.setCompoundDrawablesRelativeWithIntrinsicBounds(entry.folder ? R.drawable.ic_folder : R.drawable.ic_save, 0, 0, 0);
                    } else {
                        row.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
                        row.setMaxLines(2); row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        row.setMinHeight(dp(170)); row.setPadding(dp(4), dp(4), dp(4), dp(4));
                        Drawable icon = entry.folder ? null : preview(DrawingFiles.child(folder, entry.name));
                        if (icon == null) {
                            icon = activity.getDrawable(entry.folder ? R.drawable.ic_folder : R.drawable.ic_save);
                            icon.setBounds(0, 0, dp(48), dp(48));
                        }
                        row.setCompoundDrawablesRelative(null, icon, null, null);
                    }
                    row.setContentDescription((entries[position].folder ? "Folder " : "Painting ") + labels[position]);
                    return row;
                }
            });
            status.setText(found.length == 0 ? "This folder is empty" : ""); setWorking(false);
        }));
    }

    /** The drawing's first page as shown in the page overview, loading it on first use. */
    private Drawable preview(String path) {
        if (!previews.containsKey(path)) {
            previews.put(path, null);
            int rotation = activity instanceof PaintActivity ? ((PaintActivity) activity).appRotation : 0;
            store.firstPage(path, (page, error) -> {
                Bitmap thumbnail = page == null ? null : framed(PageOverview.turned(PageOverview.thumbnail(page), rotation));
                activity.runOnUiThread(() -> {
                    if (thumbnail == null || !alive()) return;
                    previews.put(path, thumbnail); list.invalidateViews();
                });
            });
        }
        Bitmap bitmap = previews.get(path);
        if (bitmap == null) return null;
        float scale = Math.min(dp(96) / (float) bitmap.getWidth(), dp(120) / (float) bitmap.getHeight());
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
        drawable.setBounds(0, 0, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale));
        return drawable;
    }
    private static Bitmap framed(Bitmap page) {
        if (page == null) return null;
        Bitmap framed = page.copy(Bitmap.Config.ARGB_8888, true);
        Paint line = new Paint(); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(2);
        new Canvas(framed).drawRect(1, 1, framed.getWidth() - 1, framed.getHeight() - 1, line);
        return framed;
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
                    .setMessage("A painting with this name already exists in this folder.")
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
