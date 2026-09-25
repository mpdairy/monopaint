package dev.tilesmile.supernote;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single IO queue serializes recovery, named saves and opens. */
final class DocumentStore {
    interface Result<T> { void complete(T value, Exception error); }
    private final File directory;
    // Keep one process-wide queue: a recreated Activity must read after the
    // previous Activity's pending atomic save, not race it with a new worker.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private Snapshot pending;
    private boolean scheduled;

    DocumentStore(File files) { directory = new File(files, "drawings"); }
    private File file(String name) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create drawing folder");
        if (!validName(name)) throw new IOException("Use letters, numbers, spaces, - or _ in drawing names");
        return new File(directory, name + ".tsm");
    }
    static boolean validName(String name) {
        return name != null && name.length() > 0 && name.length() <= 64
                && name.matches("[\\p{L}\\p{N} _-]+") && !name.trim().isEmpty();
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
        IO.execute(() -> {
            try { write(name, snapshot); result.complete(null, null); }
            catch (Exception e) { result.complete(null, e); }
        });
    }
    void open(String name, Result<ToneDocument> result) {
        openBook(name,(book,error) -> result.complete(book==null?null:book.current(),error));
    }
    void openBook(String name, Result<DrawingBook> result) {
        IO.execute(() -> {
            try {
                AtomicFile atomic = new AtomicFile(file(name));
                if (name.equals("_recovery") && !atomic.getBaseFile().exists()
                        && !new File(atomic.getBaseFile().getPath() + ".bak").exists()) {
                    result.complete(null, null); return;
                }
                try (FileInputStream input = atomic.openRead()) { result.complete(BookCodec.read(input), null); }
            } catch (Exception e) { result.complete(null, e); }
        });
    }
    void list(Result<String[]> result) {
        IO.execute(() -> {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".tsm") && !name.equals("_recovery.tsm"));
            String[] names = files == null ? new String[0] : Arrays.stream(files)
                    .map(f -> f.getName().substring(0, f.getName().length() - 4)).sorted().toArray(String[]::new);
            result.complete(names, null);
        });
    }
    private void write(String name, Snapshot snapshot) throws IOException {
        AtomicFile atomic = new AtomicFile(file(name));
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            BookCodec.write(output, snapshot.book);
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }
    static final class Snapshot {
        final DrawingBook.Snapshot book;
        Snapshot(ToneDocument document) {
            this(new DrawingBook(document));
        }
        Snapshot(DrawingBook book){this.book=book.snapshot();}
    }
}
