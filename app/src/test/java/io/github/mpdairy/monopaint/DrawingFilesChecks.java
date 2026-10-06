package io.github.mpdairy.monopaint;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

public final class DrawingFilesChecks {
    public static void main(String[] args) throws Exception {
        File scratch = Files.createTempDirectory("monopaint-files-").toFile();
        try {
            DrawingFiles files = new DrawingFiles(scratch);
            check(files.list("").length == 0, "Fresh library is empty");
            files.createFolder("", "Sketches"); files.createFolder("Sketches", "Animals");
            files.createFolder("", "Studies");
            ToneDocument page = new ToneDocument(13, 9);
            page.begin(); page.setTone(4, 5, 72); page.finish();
            DrawingBook book = new DrawingBook(page);
            write(files.drawing("Old drawing"), book);
            write(files.drawing("Sketches/Animals/Cat"), book);
            write(files.drawing("Studies/Cat"), book);
            write(files.recovery(), book);
            DrawingFiles.Entry[] root = files.list("");
            check(root.length == 3 && root[0].folder && root[0].name.equals("Sketches")
                    && root[1].folder && root[2].name.equals("Old drawing"), "Folders first; old root drawings retained; recovery hidden");
            DrawingFiles.Entry[] nested = files.list("Sketches/Animals");
            check(nested.length == 1 && nested[0].name.equals("Cat") && !nested[0].folder, "Nested drawing listing");
            check(!files.drawing("Sketches/Animals/Cat").equals(files.drawing("Studies/Cat")), "Same title in different folders");
            try (InputStream input = new FileInputStream(files.drawing("Old drawing"))) {
                check(BookCodec.read(input).current().tone(4, 5) == 72, "Old named format unchanged");
            }
            for (String path : new String[]{"", "../escape", "/outside", "Sketches//Cat", "Sketches/", "a/../b", "a\\b", "_recovery", "Sketches/_recovery"}) {
                check(!DrawingFiles.validPath(path), "Reject invalid destination " + path);
                reject(() -> files.drawing(path));
            }
            check(DrawingFiles.validPath("Études/猫 2"), "Unicode names remain supported");
            reject(() -> files.createFolder("", "Sketches"));
            reject(() -> files.createFolder("", "_recovery"));
            reject(() -> files.drawing("Missing/Cat"));
            check(DrawingFiles.parent("Sketches/Animals/Cat").equals("Sketches/Animals")
                    && DrawingFiles.parent("Sketches").isEmpty()
                    && DrawingFiles.name("Sketches/Animals/Cat").equals("Cat"), "Browser parent and filename");

            book.addPage(); book.current().begin(); book.current().setTone(8, 2, 120); book.current().finish();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RecoveryCodec.write(bytes, book.snapshot(), "Sketches/Animals/Cat");
            RecoveryCodec.Recovered recovered = RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
            check(recovered.path.equals("Sketches/Animals/Cat") && recovered.book.count() == 2
                    && recovered.book.index() == 1 && recovered.book.current().tone(8, 2) == 120, "Recovery keeps destination, pages and active page together");
            recovered.book.select(0); check(recovered.book.current().tone(4, 5) == 72, "Recovery retains earlier pages");
            byte[] recovery = bytes.toByteArray();
            reject(() -> RecoveryCodec.read(new ByteArrayInputStream(Arrays.copyOf(recovery, recovery.length - 1))));
            bytes.reset(); BookCodec.write(bytes, book.snapshot());
            recovered = RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
            check(recovered.path.isEmpty() && recovered.book.count() == 2, "Legacy book recovery opens unnamed");
            bytes.reset(); DocumentCodec.write(bytes, page.width, page.height, page.snapshot());
            recovered = RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
            check(recovered.path.isEmpty() && recovered.book.current().tone(4, 5) == 72, "Legacy single-page recovery opens unnamed");
            bytes.reset(); RecoveryCodec.write(bytes, book.snapshot(), "");
            check(RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray())).path.isEmpty(), "New drawing clears previous destination");
            check(files.unusedName("Sketches/Animals", "Cat").equals("Sketches/Animals/Cat 2")
                    && files.unusedName("", "Fresh").equals("Fresh"), "Unused names number clashes");
            check(files.drawingCount() == 3, "Count drawings in every folder, not the recovery file");
            check(new DrawingFiles(new File(scratch, "missing")).drawingCount() == 0, "A library that was never created is empty");
            unsavedChanges();
            legacyNames(new File(scratch, "legacy"));
            System.out.println("PASS: nested drawing folders, ordering, reserved names, unchanged named files, atomic recovery identity, legacy recovery and unsaved changes");
        } finally { remove(scratch); }
    }
    /** Drawings saved before 0.96 as .tsm are renamed to .mpaint in place, never lost. */
    private static void legacyNames(File scratch) throws IOException {
        DrawingFiles files = new DrawingFiles(scratch);
        files.createFolder("", "Sketches");
        DrawingBook book = new DrawingBook(new ToneDocument(13, 9));
        paint(book, 10); write(new File(scratch, "Old.tsm"), book);
        paint(book, 20); write(new File(scratch, "Sketches/Cat.tsm.bak"), book);
        paint(book, 30); write(new File(scratch, "Taken.tsm"), book);
        paint(book, 40); write(files.drawing("Taken"), book);
        write(new File(scratch, "_recovery.tsm"), book);
        files.upgradeLegacyNames();
        check(tone(files.drawing("Old")) == 10, "Legacy drawing renamed");
        check(tone(new File(files.drawing("Sketches/Cat").getPath() + ".bak")) == 20, "Interrupted save's backup keeps its role");
        check(tone(files.drawing("Taken")) == 40 && tone(files.drawing("Taken 2")) == 30, "Clashing legacy name keeps both drawings");
        check(files.recovery().exists() && !new File(scratch, "_recovery.tsm").exists(), "Legacy recovery renamed");
        // Sketches/Cat has only its backup, which the browser does not list.
        check(!new File(scratch, "Old.tsm").exists() && files.drawingCount() == 3, "Drawings listed under the new extension");
        files.upgradeLegacyNames();
        check(files.drawingCount() == 3, "Renaming again changes nothing");
    }
    private static int tone(File file) throws IOException {
        if (!file.exists()) throw new AssertionError("Missing " + file);
        try (InputStream input = new FileInputStream(file)) { return BookCodec.read(input).current().tone(2, 3); }
    }
    /** New and Open ask to save only when the book really has changes since its last save or load. */
    private static void unsavedChanges() throws IOException {
        DrawingBook book = new DrawingBook(new ToneDocument(13, 9));
        check(!book.unsaved(), "A blank new drawing has nothing to save");
        paint(book, 30);
        check(book.unsaved(), "Painting is an unsaved change");
        DrawingBook.Snapshot saved = book.snapshot();
        paint(book, 60);
        book.markSaved(saved);
        check(book.unsaved(), "Painting after the save began stays unsaved");
        book.markSaved(book.snapshot());
        check(!book.unsaved(), "Saving clears unsaved changes");
        book.addPage(); book.addPage();
        book.markSaved(book.snapshot());
        book.select(0); paint(book, 90); book.select(1); book.select(2);
        check(book.unsaved(), "An edited page stays unsaved after it leaves memory");
        book.markSaved(book.snapshot());
        book.select(0); book.select(2);
        check(!book.unsaved(), "Browsing pages is not a change");
        book.setCanvasChoice(1);
        check(book.unsaved(), "Choosing a canvas size is a change");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BookCodec.write(bytes, book.snapshot());
        check(!BookCodec.read(new ByteArrayInputStream(bytes.toByteArray())).unsaved(), "An opened drawing starts saved");
        for (boolean unsaved : new boolean[]{true, false}) {
            if (unsaved) paint(book, 120); else book.markSaved(book.snapshot());
            bytes.reset(); RecoveryCodec.write(bytes, book.snapshot(), "Cat");
            check(RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray())).book.unsaved() == unsaved,
                    "Recovery keeps whether the drawing had unsaved changes");
        }
        bytes.reset(); new DataOutputStream(bytes).writeInt(0x54535231); new DataOutputStream(bytes).writeUTF("Cat");
        BookCodec.write(bytes, book.snapshot());
        RecoveryCodec.Recovered legacy = RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(legacy.path.equals("Cat") && legacy.book.unsaved(), "Older recoveries count as unsaved");
    }
    private static void paint(DrawingBook book, int tone) {
        ToneDocument page = book.current(); page.begin(); page.setTone(2, 3, tone); page.finish();
    }
    private static void write(File file, DrawingBook book) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { BookCodec.write(out, book.snapshot()); }
    }
    private static void remove(File file) throws IOException {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        Files.delete(file.toPath());
    }
    private interface Operation { void run() throws IOException; }
    private static void reject(Operation operation) throws IOException {
        try { operation.run(); throw new AssertionError("Invalid operation accepted"); } catch (IOException expected) {}
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
