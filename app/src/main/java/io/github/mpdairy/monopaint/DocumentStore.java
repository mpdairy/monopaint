package io.github.mpdairy.monopaint;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single IO queue serializes recovery, named saves and opens. */
final class DocumentStore {
    interface Result<T> { void complete(T value, Exception error); }
    /** Read and replaced only on {@link #IO}. */
    private DrawingFiles files;
    /**
     * The working drawing's recovery file. It stays in private storage wherever the library is:
     * Supernote Cloud syncs Document/, and syncing a file rewritten every few seconds left
     * conflict copies and broken temporary files there until autosave failed.
     */
    private final File recovery;
    // Keep one process-wide queue: a recreated Activity must read after the
    // previous Activity's pending atomic save, not race it with a new worker.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private Snapshot pending;
    private boolean scheduled;

    /** A store for the drawing library in {@code library}, autosaving to {@code recovery} (see {@link DrawingStorage}). */
    DocumentStore(File library, File recovery) {
        files = new DrawingFiles(library); this.recovery = recovery;
        IO.execute(this::upgradeLegacyNames);
    }
    /** Runs on {@link #IO} before anything else reads the library; a failure leaves the old names readable by retrying next time. */
    private void upgradeLegacyNames() {
        try { files.upgradeLegacyNames(); }
        catch (IOException e) { android.util.Log.e(ProbeActivity.TAG, "Could not rename .tsm drawings", e); }
    }
    private File file(String name) throws IOException {
        return name.equals("_recovery") ? recovery : files.drawing(name);
    }
    static boolean validName(String name) {
        return DrawingFiles.validName(name);
    }
    synchronized void recoverLater(Snapshot snapshot, Result<Void> result) {
        pending = snapshot;
        if (scheduled) return;
        scheduled = true;
        IO.execute(() -> {
            while (true) {
                Snapshot next;
                synchronized (DocumentStore.this) {
                    next = pending; pending = null;
                    if (next == null) { scheduled = false; return; }
                }
                try { write("_recovery", next); result.complete(null, null); }
                catch (Exception e) { result.complete(null, e); }
            }
        });
    }
    void save(String name, Snapshot snapshot, Result<Void> result) {
        save(name, snapshot, true, result);
    }
    void save(String name, Snapshot snapshot, boolean replace, Result<Void> result) {
        IO.execute(() -> {
            try {
                File target = files.drawing(name);
                if (!replace && exists(target)) throw new IOException("A painting with that name already exists");
                write(name, snapshot); result.complete(null, null);
            }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    private static boolean exists(File target) { return DrawingFiles.exists(target); }
    void exists(String name, Result<Boolean> result) {
        IO.execute(() -> {
            try { result.complete(exists(files.drawing(name)), null); }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    void open(String name, Result<ToneDocument> result) {
        openBook(name,(book,error) -> result.complete(book==null?null:book.current(),error));
    }
    void openBook(String name, Result<DrawingBook> result) {
        if (name.equals("_recovery")) {
            recover((recovered, error) -> result.complete(recovered == null ? null : recovered.book, error));
            return;
        }
        IO.execute(() -> {
            try {
                AtomicFile atomic = new AtomicFile(file(name));
                try (FileInputStream input = atomic.openRead()) { result.complete(BookCodec.read(input), null); }
            } catch (Exception e) { result.complete(null, e); }
        });
    }
    /** The first page of a saved drawing, for browsing; never loads the whole book. */
    void firstPage(String name, Result<ToneDocument> result) {
        IO.execute(() -> {
            try (FileInputStream input = new AtomicFile(files.drawing(name)).openRead()) {
                result.complete(BookCodec.readFirstPage(input), null);
            } catch (Exception e) { result.complete(null, e); }
        });
    }
    void recover(Result<RecoveryCodec.Recovered> result) {
        IO.execute(() -> {
            try {
                AtomicFile atomic = new AtomicFile(recovery);
                if (!exists(atomic.getBaseFile())) { result.complete(null, null); return; }
                try (FileInputStream input = atomic.openRead()) { result.complete(RecoveryCodec.read(input), null); }
            } catch (Exception e) { result.complete(null, e); }
        });
    }
    void list(String folder, Result<DrawingFiles.Entry[]> result) {
        IO.execute(() -> {
            try { result.complete(files.list(folder), null); }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    /** Named drawings in the library, not counting the working drawing's recovery file. */
    void drawingCount(Result<Integer> result) {
        IO.execute(() -> {
            try { result.complete(files.drawingCount(), null); }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    void createFolder(String folder, String name, Result<Void> result) {
        IO.execute(() -> {
            try { files.createFolder(folder, name); result.complete(null, null); }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    /**
     * Moves the library's drawings into {@code library} and uses it from then on; queued saves
     * land wherever the library is when they run. The recovery file stays put. On failure the
     * store stays where it was, and moving again finishes the job.
     */
    void relocate(File library, Result<Void> result) {
        IO.execute(() -> {
            try {
                DrawingFiles target = new DrawingFiles(library);
                if (!target.sameLibrary(files)) { target.folder(""); move("", target); }
                files = target; upgradeLegacyNames(); adoptLibraryRecovery(); result.complete(null, null);
            } catch (Exception e) { result.complete(null, e); }
        });
    }
    /**
     * Before 0.97 the recovery file lived in the library, where cloud sync copied it as
     * "_recovery_CONFLICT_…" drawings and left "_recovery.mpaint.new" folders behind. Takes
     * that recovery over when there is no private one, keeps it as a named drawing when it
     * has unsaved changes of its own, and removes it and the sync's leftovers.
     */
    private void adoptLibraryRecovery() throws IOException {
        File old = files.recovery();
        if (old.getCanonicalPath().equals(recovery.getCanonicalPath())) return;
        if (exists(old)) {
            AtomicFile source = new AtomicFile(old);
            if (!exists(recovery)) {
                byte[] bytes = source.readFully();
                write(new AtomicFile(recovery), output -> output.write(bytes));
                if (!Arrays.equals(bytes, new AtomicFile(recovery).readFully()))
                    throw new IOException("The private copy of the recovery file did not match; the original was kept");
            } else keepUnsavedRecovery(files, source);
            source.delete();
        }
        File[] entries = files.folder("").listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            // A directory is removed only when empty; delete() refuses otherwise.
            if (name.equals(old.getName() + ".new") || name.startsWith("_recovery_CONFLICT_") && name.endsWith(DrawingFiles.EXTENSION))
                entry.delete();
        }
    }
    private static void keepUnsavedRecovery(DrawingFiles target, AtomicFile recovery) throws IOException {
        RecoveryCodec.Recovered recovered;
        try (FileInputStream input = recovery.openRead()) { recovered = RecoveryCodec.read(input); }
        if (!recovered.book.unsaved()) return;
        String folder = recovered.path.isEmpty() ? "" : DrawingFiles.parent(recovered.path);
        String name = recovered.path.isEmpty() ? "Recovered painting" : DrawingFiles.name(recovered.path) + " recovered";
        try { target.folder(folder); } catch (IOException missing) { folder = ""; }
        DrawingBook.Snapshot book = recovered.book.snapshot();
        write(new AtomicFile(target.drawing(target.unusedName(folder, name))), output -> BookCodec.write(output, book));
    }
    /** Moves one folder level; each source file goes only after its copy is complete. */
    private void move(String path, DrawingFiles target) throws IOException {
        File[] entries = files.folder(path).listFiles();
        if (entries == null) throw new IOException("Could not read painting folder");
        LinkedHashSet<String> drawings = new LinkedHashSet<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (entry.isDirectory() && DrawingFiles.validName(name)) {
                if (!new File(target.folder(path), name).isDirectory()) target.createFolder(path, name);
                move(DrawingFiles.child(path, name), target);
                entry.delete();
            } else if (name.endsWith(DrawingFiles.EXTENSION) || name.endsWith(DrawingFiles.EXTENSION + ".bak")) {
                drawings.add(name.substring(0, name.lastIndexOf(DrawingFiles.EXTENSION)));
            }
        }
        for (String name : drawings) {
            if (!DrawingFiles.validName(name)) continue;
            AtomicFile source = new AtomicFile(files.drawing(DrawingFiles.child(path, name)));
            byte[] bytes = source.readFully();
            File destination = target.drawing(DrawingFiles.child(path, name));
            if (exists(destination)) {
                if (Arrays.equals(bytes, new AtomicFile(destination).readFully())) { source.delete(); continue; }
                destination = target.drawing(target.unusedName(path, name));
            }
            write(new AtomicFile(destination), output -> output.write(bytes));
            if (!Arrays.equals(bytes, new AtomicFile(destination).readFully()))
                throw new IOException("The moved copy of " + DrawingFiles.child(path, name) + " did not match; the original was kept");
            source.delete();
        }
    }
    private void write(String name, Snapshot snapshot) throws IOException {
        write(new AtomicFile(file(name)), output -> {
            if (name.equals("_recovery")) RecoveryCodec.write(output, snapshot.book, snapshot.path);
            else BookCodec.write(output, snapshot.book);
        });
    }
    private interface Contents { void writeTo(FileOutputStream output) throws IOException; }
    private static void write(AtomicFile atomic, Contents contents) throws IOException {
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            contents.writeTo(output);
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }
    static final class Snapshot {
        final DrawingBook.Snapshot book;
        final String path;
        Snapshot(ToneDocument document) {
            this(new DrawingBook(document));
        }
        Snapshot(DrawingBook book){this(book, "");}
        Snapshot(DrawingBook book, String path){this.book=book.snapshot();this.path=path;}
    }
}
