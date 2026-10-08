package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real Android AtomicFile checks confined to a disposable cache directory. */
final class StorageChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        File scratch = new File(test.getTargetContext().getCacheDir(), "storage-check-" + System.nanoTime());
        DocumentStore store = store(scratch);
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
            RecoveryCodec.Recovered recovered = StorageChecks.<RecoveryCodec.Recovered>await(result -> store(scratch).recover(result));
            check(recovered.path.equals("Copy") && recovered.book.current().tone(5, 6) == 200,
                    "Recreated store restores destination and pixels atomically");
            DrawingFiles.Entry[] entries = StorageChecks.<DrawingFiles.Entry[]>await(result -> store.list("", result));
            check(entries.length == 2 && entries[0].folder && entries[1].name.equals("Copy"), "Browser excludes recovery and orders folders first");
            File copy = new File(scratch, "Copy" + DrawingFiles.EXTENSION);
            check(copy.renameTo(new File(copy.getPath() + ".bak")), "Simulate interrupted atomic save");
            expectFailure(result -> store.save("Copy", new DocumentStore.Snapshot(book), false, result));
            opened = StorageChecks.<DrawingBook>await(result -> store.openBook("Copy", result));
            check(opened.current().tone(5, 6) == 200, "Backup protects existing drawing and restores on open");
            StorageChecks.<Void>await(result -> store.recoverLater(new DocumentStore.Snapshot(book, ""), result));
            recovered = StorageChecks.<RecoveryCodec.Recovered>await(result -> store.recover(result));
            check(recovered.path.isEmpty(), "New drawing recovery clears old destination");
            relocation(scratch);
            report.append("PASS: Android atomic saves, nested folders, collision protection, Save As copy, failed destinations, recovery identity, backup restoration, library relocation, private recovery and sync leftover cleanup.\n");
        } finally { remove(scratch); }
    }
    /** A store whose recovery file is the private one, beside a private library. */
    private static DocumentStore store(File library) { return new DocumentStore(library, new File(library, "_recovery" + DrawingFiles.EXTENSION)); }
    /** Moving the private library into shared storage merges it without losing anything. */
    private static void relocation(File scratch) throws Exception {
        File from = new File(scratch, "private"), to = new File(scratch, "shared");
        File privateRecovery = new File(from, "_recovery" + DrawingFiles.EXTENSION);
        // The target store autosaves where 0.96 did, inside the shared library.
        DocumentStore source = store(from), target = store(to);
        DrawingBook book = new DrawingBook(new ToneDocument(32, 24));
        mark(book, 40);
        StorageChecks.<Void>await(result -> target.save("Same", new DocumentStore.Snapshot(book), true, result));
        StorageChecks.<Void>await(result -> target.recoverLater(new DocumentStore.Snapshot(book, ""), result));
        StorageChecks.<Void>await(result -> source.createFolder("", "Sketches", result));
        StorageChecks.<Void>await(result -> source.save("Same", new DocumentStore.Snapshot(book), true, result));
        StorageChecks.<Void>await(result -> source.save("Sketches/Cat", new DocumentStore.Snapshot(book), true, result));
        mark(book, 90);
        StorageChecks.<Void>await(result -> source.save("Differs", new DocumentStore.Snapshot(book), true, result));
        mark(book, 130);
        StorageChecks.<Void>await(result -> target.save("Differs", new DocumentStore.Snapshot(book), true, result));
        mark(book, 160);
        StorageChecks.<Void>await(result -> source.recoverLater(new DocumentStore.Snapshot(book, "Sketches/Cat"), result));
        File interrupted = new File(from, "Sketches/Cat" + DrawingFiles.EXTENSION);
        check(interrupted.renameTo(new File(interrupted.getPath() + ".bak")), "Simulate interrupted save before moving");
        File conflict = new File(to, "_recovery_CONFLICT_20261007203455095" + DrawingFiles.EXTENSION), unfinished = new File(to, "_recovery" + DrawingFiles.EXTENSION + ".new");
        check(new File(to, "_recovery" + DrawingFiles.EXTENSION).exists(), "Old shared recovery exists");
        java.nio.file.Files.copy(new File(to, "Same" + DrawingFiles.EXTENSION).toPath(), conflict.toPath());
        check(unfinished.mkdir(), "Simulate sync's leftover temporary folder");
        StorageChecks.<Void>await(result -> source.relocate(to, result));
        DrawingFiles.Entry[] left = new DrawingFiles(from).list("");
        check(left.length == 0 && privateRecovery.exists(), "Only the private recovery is left in the old library");
        check(!new File(to, "_recovery" + DrawingFiles.EXTENSION).exists() && !conflict.exists() && !unfinished.exists(),
                "Shared recovery and cloud sync leftovers are removed");
        DrawingFiles.Entry[] root = StorageChecks.<DrawingFiles.Entry[]>await(result -> source.list("", result));
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (DrawingFiles.Entry entry : root) names.add(entry.name);
        check(names.equals(java.util.Arrays.asList("Sketches", "Differs", "Differs 2", "Recovered painting", "Same")),
                "Merged library keeps both versions of a clashing name and the old unsaved recovery: " + names);
        check(StorageChecks.<DrawingBook>await(result -> source.openBook("Differs", result)).current().tone(5, 6) == 130
                && StorageChecks.<DrawingBook>await(result -> source.openBook("Differs 2", result)).current().tone(5, 6) == 90,
                "Existing shared drawing kept; moved one renamed");
        check(StorageChecks.<DrawingBook>await(result -> source.openBook("Sketches/Cat", result)).current().tone(5, 6) == 40,
                "Interrupted save's backup moved as the drawing");
        RecoveryCodec.Recovered recovered = StorageChecks.<RecoveryCodec.Recovered>await(result -> source.recover(result));
        check(recovered.path.equals("Sketches/Cat") && recovered.book.current().tone(5, 6) == 160 && recovered.book.unsaved(),
                "Working drawing's recovery stays private with its unsaved state");
        StorageChecks.<Void>await(result -> source.recoverLater(new DocumentStore.Snapshot(book, ""), result));
        check(!new File(to, "_recovery" + DrawingFiles.EXTENSION).exists() && privateRecovery.exists(), "Later autosaves stay private");
        // Upgrading from 0.96: the only recovery is the shared one, and it becomes the private one.
        File upgraded = new File(scratch, "upgraded"), upgradedRecovery = new File(scratch, "upgraded-private/_recovery" + DrawingFiles.EXTENSION);
        StorageChecks.<Void>await(result -> store(upgraded).recoverLater(new DocumentStore.Snapshot(book, "Same"), result));
        DocumentStore upgrading = new DocumentStore(new File(scratch, "upgraded-private"), upgradedRecovery);
        StorageChecks.<Void>await(result -> upgrading.relocate(upgraded, result));
        recovered = StorageChecks.<RecoveryCodec.Recovered>await(result -> upgrading.recover(result));
        check(recovered.path.equals("Same") && recovered.book.current().tone(5, 6) == 160 && upgradedRecovery.exists()
                && !new File(upgraded, "_recovery" + DrawingFiles.EXTENSION).exists(), "0.96 shared recovery moves into private storage");
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
