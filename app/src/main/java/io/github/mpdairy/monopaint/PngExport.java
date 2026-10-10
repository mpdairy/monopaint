package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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

/**
 * Exports the current page, or every page as numbered files, to calibrated PNGs (smooth grays
 * or the screen's dots) in a chosen folder, or by default EXPORT/MonoPaint on a Supernote with
 * file access (the folder its Files app shows) and Pictures/MonoPaint otherwise.
 */
final class PngExport {
    static final String FOLDER = Environment.DIRECTORY_PICTURES + "/" + BuildConfig.LIBRARY_FOLDER, SUPERNOTE_FOLDER = "EXPORT/" + BuildConfig.LIBRARY_FOLDER;
    // One queue so overlapping exports never interleave writes to the same names.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final PaintActivity app;
    private final DrawingBook.Snapshot book;
    private final int rotation;
    /**
     * The chosen folder, else a folder written as plain files ({@link #SUPERNOTE_FOLDER}), else
     * null for {@link #FOLDER} through MediaStore; {@link #folder} is its name for messages.
     */
    private Uri tree;
    private File directory;
    private String folder;
    /** The size of dithered pages, or null for smooth grays at each page's size. */
    private ExportSize dotSize;

    private PngExport(PaintActivity app, DrawingBook.Snapshot book, int rotation) {
        this.app = app; this.book = book; this.rotation = rotation;
        String saved = app.prefs.exportFolder();
        useFolder(saved.isEmpty() ? null : Uri.parse(saved));
    }

    /** Asks which pages and what name, then exports the book as it is now. */
    static void show(PaintActivity app, DrawingBook.Snapshot book, String drawingName) {
        new PngExport(app, book, app.appRotation).ask(drawingName.isEmpty() ? "painting" : DrawingFiles.name(drawingName));
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
        // One page of several is named for its page number unless the name is edited.
        String pageName = book.count() > 1 ? pageBase(defaultName, book.index) : defaultName;
        EditText name = new EditText(app); name.setSingleLine(true); name.setText(pageName);
        name.setHint("File name"); name.setContentDescription("File name"); name.setSelection(name.length());
        content.addView(name);
        CheckBox dots = new CheckBox(app); dots.setText("Dithered, as on screen");
        dots.setChecked(app.prefs.exportDithered()); content.addView(dots);
        // Dots look right only shown one to one, so dithered pages are sized for a screen.
        int[] page = upright(book.activePage.width, book.activePage.height);
        RadioGroup sizes = new RadioGroup(app);
        RadioButton pageSize = sizeChoice(sizes, "page", "Page size", page);
        for (Device device : Device.SUPERNOTES)
            if (!java.util.Arrays.equals(preset(device.name).of(page[0], page[1]), page))
                sizeChoice(sizes, device.name, device.name + " screen", page);
        RadioButton custom = new RadioButton(app); custom.setText("Custom size");
        custom.setId(View.generateViewId()); sizes.addView(custom);
        content.addView(sizes);
        LinearLayout customRow = new LinearLayout(app);
        EditText wide = sizeField("Width"), high = sizeField("Height");
        TextView by = new TextView(app); by.setText(" × ");
        customRow.addView(wide); customRow.addView(by); customRow.addView(high);
        content.addView(customRow);
        String saved = app.prefs.exportSize();
        String customWidth = saved.startsWith("custom:") ? saved.substring(7) : Integer.toString(page[0]);
        sizes.check(saved.startsWith("custom:") ? custom.getId() : pageSize.getId());
        for (int i = 0; i < sizes.getChildCount(); i++)
            if (saved.equals(sizes.getChildAt(i).getTag())) sizes.check(sizes.getChildAt(i).getId());
        // Either side follows the other so the page keeps its proportions.
        boolean[] syncing = {false};
        linkSides(wide, high, syncing, typed -> ExportSize.width(typed, page[0]).of(page[0], page[1])[1]);
        linkSides(high, wide, syncing, typed -> ExportSize.height(typed, page[1]).of(page[0], page[1])[0]);
        wide.setText(customWidth);
        Runnable showSizes = () -> {
            sizes.setVisibility(dots.isChecked() ? View.VISIBLE : View.GONE);
            customRow.setVisibility(dots.isChecked() && custom.isChecked() ? View.VISIBLE : View.GONE);
        };
        showSizes.run();
        dots.setOnCheckedChangeListener((box, checked) -> showSizes.run());
        sizes.setOnCheckedChangeListener((group, id) -> showSizes.run());
        TextView where = new TextView(app); content.addView(where);
        Button change = new Button(app); change.setText("Change folder…"); content.addView(change);
        Runnable describe = () -> {
            String base = base(name.getText().toString());
            where.setText("Saves to " + folder + "/" + (all.isChecked()
                    ? numbered(base, 0) + " … " + numbered(base, book.count() - 1) : base + ".png"));
        };
        scope.setOnCheckedChangeListener((group, id) -> {
            String unedited = all.isChecked() ? pageName : defaultName;
            if (name.getText().toString().equals(unedited)) {
                name.setText(all.isChecked() ? defaultName : pageName); name.setSelection(name.length());
            }
            describe.run();
        });
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { describe.run(); }
        });
        describe.run();
        change.setOnClickListener(v -> app.pickFolder(picked -> {
            app.prefs.setExportFolder(picked.toString()); useFolder(picked); describe.run();
        }));
        AlertDialog dialog = app.showDialog(new AlertDialog.Builder(app).setTitle("Export PNG").setView(content)
                .setPositiveButton("Export", null).setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String base = base(name.getText().toString());
            if (!validName(base)) { name.setError("Use 1–100 characters without / or \\ or a leading ."); return; }
            String sizeKey = "page";
            dotSize = null;
            if (dots.isChecked() && custom.isChecked()) {
                int width = number(wide);
                dotSize = width > 0 ? ExportSize.width(width, page[0]) : null;
                int[] size = dotSize == null ? null : dotSize.of(page[0], page[1]);
                if (size == null || !ToneDocument.validSize(size[0], size[1])) {
                    wide.setError("Use 1 to 4096 pixels a side, at most " + ToneDocument.MAX_PIXELS / 1000000 + " million in all");
                    return;
                }
                sizeKey = "custom:" + width;
            } else if (dots.isChecked()) {
                View chosen = sizes.findViewById(sizes.getCheckedRadioButtonId());
                sizeKey = (String)chosen.getTag(); dotSize = preset(sizeKey);
            }
            dialog.dismiss();
            app.prefs.setExportDithered(dots.isChecked());
            if (dots.isChecked()) app.prefs.setExportSize(sizeKey);
            boolean every = all.isChecked();
            int first = every ? 0 : book.index, last = every ? book.count() - 1 : book.index;
            String[] files = new String[last - first + 1];
            for (int i = first; i <= last; i++) files[i - first] = every ? numbered(base, i) : base + ".png";
            confirm(files, first);
        });
    }

    /** The page's own size, or filling the screen of the Supernote with that name. */
    private static ExportSize preset(String key) {
        for (Device device : Device.SUPERNOTES)
            if (device.name.equals(key)) return ExportSize.screen(device.panelWidth, device.panelHeight);
        return ExportSize.PAGE;
    }
    /** A preset option labeled with the current page's size, tagged with its saved key. */
    private RadioButton sizeChoice(RadioGroup group, String key, String label, int[] page) {
        int[] pixels = preset(key).of(page[0], page[1]);
        RadioButton choice = new RadioButton(app); choice.setId(View.generateViewId());
        choice.setText(label + ", " + pixels[0] + " × " + pixels[1]);
        choice.setTag(key);
        group.addView(choice);
        return choice;
    }
    private EditText sizeField(String hint) {
        EditText field = new EditText(app); field.setSingleLine(true); field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint(hint); field.setContentDescription(hint); field.setEms(4);
        return field;
    }
    /** Typing in {@code from} sets {@code to} to the matching side. */
    private static void linkSides(EditText from, EditText to, boolean[] syncing, java.util.function.IntUnaryOperator other) {
        from.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                int typed = number(from);
                if (syncing[0] || typed <= 0) return;
                syncing[0] = true; to.setText(Integer.toString(other.applyAsInt(typed))); to.setError(null); syncing[0] = false;
            }
        });
    }
    /** The field's whole number, or 0 when it has none. */
    private static int number(EditText field) {
        try { return Integer.parseInt(field.getText().toString().trim()); } catch (NumberFormatException none) { return 0; }
    }
    /** A page's size as exported, turned like the app. */
    private int[] upright(int width, int height) { return rotation % 2 == 0 ? new int[]{width, height} : new int[]{height, width}; }

    static String base(String name) {
        String value = name.trim();
        return value.toLowerCase(Locale.ROOT).endsWith(".png") ? value.substring(0, value.length() - 4).trim() : value;
    }
    static boolean validName(String base) {
        return !base.isEmpty() && base.length() <= 100 && !base.startsWith(".")
                && base.indexOf('/') < 0 && base.indexOf('\\') < 0 && base.indexOf('\0') < 0;
    }
    /** Page numbers count from 1: base001.png, base002.png, … */
    static String numbered(String base, int page) { return pageBase(base, page) + ".png"; }
    static String pageBase(String base, int page) { return String.format(Locale.ROOT, "%s%03d", base, page + 1); }

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
                                + " already exist" + (count == 1 ? "s" : "") + " in " + folder + ".")
                        .setPositiveButton("Replace", (d, w) -> export(files, first)).setNegativeButton("Cancel", null));
            });
        });
    }

    private void export(String[] files, int first) {
        app.message(files.length == 1 ? "Exporting " + files[0] + "…" : "Exporting " + files.length + " pages…");
        WORKER.execute(() -> {
            try {
                for (int i = 0; i < files.length; i++) write(files[i], first + i);
                finish(files.length == 1 ? "Exported " + folder + "/" + files[0]
                        : "Exported " + files.length + " pages to " + folder);
            } catch (RuntimeException | IOException error) {
                android.util.Log.e(ProbeActivity.TAG, "PNG export failed", error);
                finish("Export failed: " + error.getMessage());
            }
        });
    }

    private void finish(String text) { app.runOnUiThread(() -> { if (!app.isDestroyed()) app.message(text); }); }

    private void write(String file, int index) throws IOException {
        ToneDocument page = book.page(index);
        Bitmap image;
        if (dotSize != null) {
            int[] upright = upright(page.width, page.height), turned = dotSize.of(upright[0], upright[1]);
            int[] size = upright(turned[0], turned[1]); // back to the page's own orientation
            if (!ToneDocument.validSize(size[0], size[1])) throw new IOException("Page " + (index + 1) + " is too large at that size");
            image = ViewportBitmap.dithered(page, size[0], size[1]);
        } else image = Bitmap.createBitmap(page.exportPixels(true), page.width, page.height, Bitmap.Config.ARGB_8888);
        Bitmap upright = PageOverview.turned(image, rotation);
        if (upright != image) image.recycle();
        try {
            if (tree != null) writeTree(upright, file);
            else if (directory != null || Build.VERSION.SDK_INT < 29) {
                try (OutputStream output = new FileOutputStream(plainFile(file))) { encode(upright, output, file); }
            } else writeShared(upright, file);
        } finally { upright.recycle(); }
    }
    private static void encode(Bitmap image, OutputStream output, String file) throws IOException {
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Could not encode " + file);
    }
    /** Replaces the content at {@code uri}. */
    private static void encode(Bitmap image, ContentResolver resolver, Uri uri, String file) throws IOException {
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Cannot write " + file);
            encode(image, output, file);
        }
    }

    // Storage: a chosen folder, Supernote's EXPORT folder as plain files, shared Pictures through
    // MediaStore, or this app's pictures folder before Android 10.

    /** Uses a chosen folder while the app still has access to it; otherwise the default folder. */
    private void useFolder(Uri chosen) {
        tree = null;
        boolean supernote = Device.supernote() && DrawingStorage.sharedAllowed(app);
        directory = supernote ? new File(Environment.getExternalStorageDirectory(), SUPERNOTE_FOLDER) : null;
        folder = supernote ? SUPERNOTE_FOLDER : FOLDER;
        if (chosen == null) return;
        for (UriPermission permission : app.getContentResolver().getPersistedUriPermissions()) {
            if (!permission.getUri().equals(chosen) || !permission.isWritePermission()) continue;
            Uri root = DocumentsContract.buildDocumentUriUsingTree(chosen, DocumentsContract.getTreeDocumentId(chosen));
            try (Cursor cursor = app.getContentResolver().query(root,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return;
                tree = chosen; directory = null; folder = cursor.getString(0);
            } catch (RuntimeException missing) { return; }
        }
    }

    private File plainFile(String file) throws IOException {
        File folder = directory != null ? directory : new File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MonoPaint");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create export folder");
        return new File(folder, file);
    }
    private boolean exists(String file) throws IOException {
        if (tree != null) return treeFile(file) != null;
        return directory != null || Build.VERSION.SDK_INT < 29 ? plainFile(file).exists() : shared(file) != null;
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
        try { encode(image, resolver, uri, file); }
        catch (IOException | RuntimeException error) {
            if (created) resolver.delete(uri, null, null);
            throw error;
        }
        if (created) {
            // Hidden from other apps until complete.
            ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }

    /** The chosen folder's file with that name, if any. */
    private Uri treeFile(String file) {
        String parent = DocumentsContract.getTreeDocumentId(tree);
        try (Cursor cursor = app.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            while (cursor != null && cursor.moveToNext())
                if (file.equals(cursor.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
        }
        return null;
    }
    private void writeTree(Bitmap image, String file) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        Uri uri = treeFile(file);
        boolean created = uri == null;
        if (created) {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            uri = DocumentsContract.createDocument(resolver, parent, "image/png", file);
            if (uri == null) throw new IOException("Cannot create " + file);
        }
        try { encode(image, resolver, uri, file); }
        catch (IOException | RuntimeException error) {
            if (created) try { DocumentsContract.deleteDocument(resolver, uri); } catch (IOException | RuntimeException ignored) { }
            throw error;
        }
    }
}
