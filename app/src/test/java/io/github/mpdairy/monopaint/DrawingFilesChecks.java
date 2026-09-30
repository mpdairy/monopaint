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
            System.out.println("PASS: nested drawing folders, ordering, reserved names, unchanged named files, atomic recovery identity and legacy recovery");
        } finally { remove(scratch); }
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
