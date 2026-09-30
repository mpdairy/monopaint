package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real Android AtomicFile checks confined to a disposable cache directory. */
final class StorageChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        File scratch = new File(test.getTargetContext().getCacheDir(), "storage-check-" + System.nanoTime());
        DocumentStore store = new DocumentStore(scratch);
        try {
            StorageChecks.<Void>await(result -> store.createFolder("", "Sketches", result));
            StorageChecks.<Void>await(result -> store.createFolder("Sketches", "Animals", result));
            DrawingBook book = new DrawingBook(new ToneDocument(32, 24));
            mark(book, 70);
            StorageChecks.<Void>await(result -> store.save("Sketches/Animals/Cat", new DocumentStore.Snapshot(book), false, result));
            mark(book, 120);
            expectFailure(result -> store.save("Sketches/Animals/Cat", new DocumentStore.Snapshot(book), false, result));
            DrawingBook opened = StorageChecks.<DrawingBook>await(result -> store.openBook("Sketches/Animals/Cat", result));
            check(opened.current().tone(5, 6) == 70, "Unconfirmed replacement preserves saved drawing");
            StorageChecks.<Void>await(result -> store.save("Sketches/Animals/Cat", new DocumentStore.Snapshot(book), true, result));
            opened = StorageChecks.<DrawingBook>await(result -> store.openBook("Sketches/Animals/Cat", result));
            check(opened.current().tone(5, 6) == 120, "Ordinary save updates current destination");
            StorageChecks.<Void>await(result -> store.save("Copy", new DocumentStore.Snapshot(book), false, result));
            mark(book, 200);
            StorageChecks.<Void>await(result -> store.save("Copy", new DocumentStore.Snapshot(book), true, result));
            opened = StorageChecks.<DrawingBook>await(result -> store.openBook("Sketches/Animals/Cat", result));
            check(opened.current().tone(5, 6) == 120, "Save As leaves original intact");
            expectFailure(result -> store.save("Missing/Drawing", new DocumentStore.Snapshot(book), false, result));
            expectFailure(result -> store.save("_recovery", new DocumentStore.Snapshot(book), true, result));
            StorageChecks.<Void>await(result -> store.recoverLater(new DocumentStore.Snapshot(book, "Copy"), result));
            RecoveryCodec.Recovered recovered = StorageChecks.<RecoveryCodec.Recovered>await(result -> new DocumentStore(scratch).recover(result));
            check(recovered.path.equals("Copy") && recovered.book.current().tone(5, 6) == 200,
                    "Recreated store restores destination and pixels atomically");
            DrawingFiles.Entry[] entries = StorageChecks.<DrawingFiles.Entry[]>await(result -> store.list("", result));
            check(entries.length == 2 && entries[0].folder && entries[1].name.equals("Copy"), "Browser excludes recovery and orders folders first");
            File copy = new File(scratch, "drawings/Copy.tsm");
            check(copy.renameTo(new File(copy.getPath() + ".bak")), "Simulate interrupted atomic save");
            expectFailure(result -> store.save("Copy", new DocumentStore.Snapshot(book), false, result));
            opened = StorageChecks.<DrawingBook>await(result -> store.openBook("Copy", result));
            check(opened.current().tone(5, 6) == 200, "Backup protects existing drawing and restores on open");
            StorageChecks.<Void>await(result -> store.recoverLater(new DocumentStore.Snapshot(book, ""), result));
            recovered = StorageChecks.<RecoveryCodec.Recovered>await(result -> store.recover(result));
            check(recovered.path.isEmpty(), "New drawing recovery clears old destination");
            report.append("PASS: Android atomic saves, nested folders, collision protection, Save As copy, failed destinations, recovery identity and backup restoration.\n");
        } finally { remove(scratch); }
    }
    private static void mark(DrawingBook book, int tone) {
        ToneDocument page = book.current(); page.begin(); page.setTone(5, 6, tone); page.finish();
    }
    private interface Operation<T> { void run(DocumentStore.Result<T> result); }
    private static <T> T await(Operation<T> operation) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Object[] value = {null}; Exception[] error = {null};
        operation.run((result, failure) -> { value[0] = result; error[0] = failure; done.countDown(); });
        check(done.await(10, TimeUnit.SECONDS), "Storage operation completed");
        if (error[0] != null) throw error[0];
        @SuppressWarnings("unchecked") T result = (T) value[0]; return result;
    }
    private static void expectFailure(Operation<Void> operation) throws Exception {
        try { await(operation); throw new AssertionError("Invalid save succeeded"); } catch (java.io.IOException expected) {}
    }
    private static void remove(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new AssertionError("Could not clean up " + file);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
