package io.github.mpdairy.monopaint;

import java.io.*;

/** Atomically keep the working drawing and its save destination together. */
final class RecoveryCodec {
    private static final int MAGIC = 0x54535231; // TSR1; named .tsm books stay unchanged.

    static void write(OutputStream output, DrawingBook.Snapshot book, String path) throws IOException {
        if (!path.isEmpty() && !DrawingFiles.validPath(path)) throw new IOException("Invalid recovery destination");
        DataOutputStream header = new DataOutputStream(output);
        header.writeInt(MAGIC);
        header.writeUTF(path);
        BookCodec.write(output, book);
    }

    static Recovered read(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(4);
        DataInputStream header = new DataInputStream(buffered);
        int magic = header.readInt();
        if (magic != MAGIC) {
            buffered.reset();
            return new Recovered(BookCodec.read(buffered), "");
        }
        String path = header.readUTF();
        if (!path.isEmpty() && !DrawingFiles.validPath(path)) throw new IOException("Invalid recovery destination");
        return new Recovered(BookCodec.read(buffered), path);
    }

    static final class Recovered {
        final DrawingBook book;
        final String path;
        Recovered(DrawingBook book, String path) { this.book = book; this.path = path; }
    }
    private RecoveryCodec() {}
}
