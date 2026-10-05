package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exports the current page, or every page as numbered files, to calibrated PNGs in Pictures/MonoPaint. */
final class PngExport {
    static final String FOLDER = Environment.DIRECTORY_PICTURES + "/MonoPaint";
    // One queue so overlapping exports never interleave writes to the same names.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final PaintActivity app;
    private final DrawingBook.Snapshot book;
    private final int rotation;

    private PngExport(PaintActivity app, DrawingBook.Snapshot book, int rotation) {
        this.app = app; this.book = book; this.rotation = rotation;
    }

    /** Asks which pages and what name, then exports the book as it is now. */
    static void show(PaintActivity app, DrawingBook.Snapshot book, String drawingName) {
        new PngExport(app, book, app.appRotation).ask(drawingName.isEmpty() ? "drawing" : DrawingFiles.name(drawingName));
    }

    private void ask(String defaultName) {
        int pad = Ui.dp(app, 20);
        LinearLayout content = new LinearLayout(app); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Ui.dp(app, 8), pad, 0);
        RadioGroup scope = new RadioGroup(app);
        RadioButton current = new RadioButton(app); current.setText("This page (" + (book.index + 1) + ")");
        RadioButton all = new RadioButton(app); all.setText("All " + book.count() + " pages");
        current.setId(View.generateViewId()); all.setId(View.generateViewId());
        scope.addView(current); scope.addView(all); scope.check(current.getId());
        content.addView(scope);
        EditText name = new EditText(app); name.setSingleLine(true); name.setText(defaultName);
        name.setHint("File name"); name.setContentDescription("File name"); name.setSelection(name.length());
        content.addView(name);
        TextView where = new TextView(app); content.addView(where);
        Runnable describe = () -> {
            String base = base(name.getText().toString());
            where.setText("Saves to " + FOLDER + "/" + (all.isChecked()
                    ? numbered(base, 0) + " … " + numbered(base, book.count() - 1) : base + ".png"));
        };
        scope.setOnCheckedChangeListener((group, id) -> describe.run());
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { describe.run(); }
        });
        describe.run();
        AlertDialog dialog = app.showDialog(new AlertDialog.Builder(app).setTitle("Export PNG").setView(content)
                .setPositiveButton("Export", null).setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String base = base(name.getText().toString());
            if (!validName(base)) { name.setError("Use 1–100 characters without / or \\ or a leading ."); return; }
            dialog.dismiss();
            boolean every = all.isChecked();
            int first = every ? 0 : book.index, last = every ? book.count() - 1 : book.index;
            String[] files = new String[last - first + 1];
            for (int i = first; i <= last; i++) files[i - first] = every ? numbered(base, i) : base + ".png";
            confirm(files, first);
        });
    }

    static String base(String name) {
        String value = name.trim();
        return value.toLowerCase(Locale.ROOT).endsWith(".png") ? value.substring(0, value.length() - 4).trim() : value;
    }
    static boolean validName(String base) {
        return !base.isEmpty() && base.length() <= 100 && !base.startsWith(".")
                && base.indexOf('/') < 0 && base.indexOf('\\') < 0 && base.indexOf('\0') < 0;
    }
    /** Page numbers count from 1: base001.png, base002.png, … */
    static String numbered(String base, int page) { return String.format(Locale.ROOT, "%s%03d.png", base, page + 1); }

    /** Confirms replacing any existing file before writing. */
    private void confirm(String[] files, int first) {
        WORKER.execute(() -> {
            int existing = 0;
            try { for (String file : files) if (exists(file)) existing++; }
            catch (RuntimeException | IOException error) { finish("Export failed: " + error.getMessage()); return; }
            int count = existing;
            app.runOnUiThread(() -> {
                if (app.isDestroyed()) return;
                if (count == 0) { export(files, first); return; }
                app.showDialog(new AlertDialog.Builder(app)
                        .setTitle(count == 1 && files.length == 1 ? "Replace “" + files[0] + "”?" : "Replace " + count + " files?")
                        .setMessage((count == 1 ? "A file" : "Files") + " with " + (count == 1 ? "this name" : "these names")
                                + " already exist" + (count == 1 ? "s" : "") + " in " + FOLDER + ".")
                        .setPositiveButton("Replace", (d, w) -> export(files, first)).setNegativeButton("Cancel", null));
            });
        });
    }

    private void export(String[] files, int first) {
        app.message(files.length == 1 ? "Exporting " + files[0] + "…" : "Exporting " + files.length + " pages…");
        WORKER.execute(() -> {
            try {
                for (int i = 0; i < files.length; i++) write(files[i], first + i);
                finish(files.length == 1 ? "Exported " + FOLDER + "/" + files[0]
                        : "Exported " + files.length + " pages to " + FOLDER);
            } catch (RuntimeException | IOException error) {
                android.util.Log.e(ProbeActivity.TAG, "PNG export failed", error);
                finish("Export failed: " + error.getMessage());
            }
        });
    }

    private void finish(String text) { app.runOnUiThread(() -> { if (!app.isDestroyed()) app.message(text); }); }

    private void write(String file, int index) throws IOException {
        ToneDocument page = book.page(index);
        Bitmap image = Bitmap.createBitmap(page.exportPixels(true), page.width, page.height, Bitmap.Config.ARGB_8888);
        Bitmap upright = PageOverview.turned(image, rotation);
        if (upright != image) image.recycle();
        try {
            if (Build.VERSION.SDK_INT < 29) {
                try (OutputStream output = new FileOutputStream(legacyFile(file))) { encode(upright, output, file); }
            } else writeShared(upright, file);
        } finally { upright.recycle(); }
    }
    private static void encode(Bitmap image, OutputStream output, String file) throws IOException {
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Could not encode " + file);
    }

    // Storage: shared Pictures through MediaStore, or this app's pictures folder before Android 10.

    private File legacyFile(String file) throws IOException {
        File folder = new File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MonoPaint");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create export folder");
        return new File(folder, file);
    }
    private boolean exists(String file) throws IOException {
        return Build.VERSION.SDK_INT < 29 ? legacyFile(file).exists() : shared(file) != null;
    }
    /** This app's earlier export with that name; other apps' images are not visible without a read permission. */
    private Uri shared(String file) {
        try (Cursor cursor = app.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.RELATIVE_PATH + "=? AND " + MediaStore.Images.Media.DISPLAY_NAME + "=?",
                new String[]{FOLDER + "/", file}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.toString(cursor.getLong(0)));
        }
    }
    private void writeShared(Bitmap image, String file) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        Uri uri = shared(file);
        boolean created = uri == null;
        if (created) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, file);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER + "/");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot create " + file);
        }
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Cannot write " + file);
            encode(image, output, file);
        } catch (IOException | RuntimeException error) {
            if (created) resolver.delete(uri, null, null);
            throw error;
        }
        if (created) {
            // Hidden from other apps until complete.
            ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }
}
