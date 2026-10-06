package io.github.mpdairy.monopaint;

import java.io.*;

/** Atomically keep the working drawing, its save destination and whether it has unsaved changes together. */
final class RecoveryCodec {
    // TSR2 adds the unsaved flag; TSR1 and older recoveries count as unsaved. Named books stay unchanged.
    private static final int MAGIC = 0x54535232, MAGIC_TSR1 = 0x54535231;

    static void write(OutputStream output, DrawingBook.Snapshot book, String path) throws IOException {
        if (!path.isEmpty() && !DrawingFiles.validPath(path)) throw new IOException("Invalid recovery destination");
        DataOutputStream header = new DataOutputStream(output);
        header.writeInt(MAGIC);
        header.writeUTF(path);
        header.writeBoolean(book.unsaved);
        BookCodec.write(output, book);
    }

    static Recovered read(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(4);
        DataInputStream header = new DataInputStream(buffered);
        int magic = header.readInt();
        if (magic != MAGIC && magic != MAGIC_TSR1) {
            buffered.reset();
            return new Recovered(BookCodec.read(buffered), "", true);
        }
        String path = header.readUTF();
        if (!path.isEmpty() && !DrawingFiles.validPath(path)) throw new IOException("Invalid recovery destination");
        boolean unsaved = magic == MAGIC_TSR1 || header.readBoolean();
        return new Recovered(BookCodec.read(buffered), path, unsaved);
    }

    static final class Recovered {
        final DrawingBook book;
        final String path;
        Recovered(DrawingBook book, String path, boolean unsaved) {
            this.book = book; this.path = path;
            if (unsaved) book.markUnsaved();
        }
    }
    private RecoveryCodec() {}
}
