package io.github.mpdairy.monopaint;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single IO queue serializes recovery, named saves and opens. */
final class DocumentStore {
    interface Result<T> { void complete(T value, Exception error); }
    private final DrawingFiles files;
    // Keep one process-wide queue: a recreated Activity must read after the
    // previous Activity's pending atomic save, not race it with a new worker.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private Snapshot pending;
    private boolean scheduled;

    DocumentStore(File directory) { files = new DrawingFiles(directory); }
    private File file(String name) throws IOException {
        return name.equals("_recovery") ? files.recovery() : files.drawing(name);
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
                if (!replace && exists(target)) throw new IOException("A drawing with that name already exists");
                write(name, snapshot); result.complete(null, null);
            }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    private static boolean exists(File target) {
        return target.exists() || new File(target.getPath() + ".bak").exists();
    }
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
                AtomicFile atomic = new AtomicFile(files.recovery());
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
    void createFolder(String folder, String name, Result<Void> result) {
        IO.execute(() -> {
            try { files.createFolder(folder, name); result.complete(null, null); }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    private void write(String name, Snapshot snapshot) throws IOException {
        AtomicFile atomic = new AtomicFile(file(name));
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            if (name.equals("_recovery")) RecoveryCodec.write(output, snapshot.book, snapshot.path);
            else BookCodec.write(output, snapshot.book);
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
