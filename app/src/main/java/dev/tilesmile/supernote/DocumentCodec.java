package dev.tilesmile.supernote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Versioned, bounded, checksummed logical document; never stores display dots. */
final class DocumentCodec {
    private static final int MAGIC = 0x54534d31; // TSM1
    static void write(OutputStream stream, int width, int height, byte[] tones) throws IOException {
        ToneDocument.validateSize(width, height);
        if (tones.length != width * height) throw new IOException("Wrong tone count");
        DataOutputStream header = new DataOutputStream(stream);
        CRC32 crc = new CRC32(); crc.update(tones);
        header.writeInt(MAGIC); header.writeInt(width); header.writeInt(height); header.writeLong(crc.getValue());
        java.util.zip.Deflater deflater=new java.util.zip.Deflater();
        try {
            DeflaterOutputStream compressed = new DeflaterOutputStream(stream,deflater);
            compressed.write(tones); compressed.finish(); compressed.flush();
        } finally {deflater.end();}
    }
    static ToneDocument read(InputStream stream) throws IOException {
        DataInputStream header = new DataInputStream(stream);
        if (header.readInt() != MAGIC) throw new IOException("Unknown drawing format");
        int width = header.readInt(), height = header.readInt(); long checksum = header.readLong();
        try { ToneDocument.validateSize(width, height); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid drawing dimensions", invalid); }
        byte[] tones = new byte[width * height];
        java.util.zip.Inflater inflater=new java.util.zip.Inflater();
        try {
            DataInputStream compressed = new DataInputStream(new InflaterInputStream(stream,inflater));
            compressed.readFully(tones);
            if (compressed.read() != -1) throw new IOException("Extra drawing data");
        } finally {inflater.end();}
        CRC32 crc = new CRC32(); crc.update(tones);
        if (crc.getValue() != checksum) throw new IOException("Drawing checksum mismatch");
        return new ToneDocument(width, height, tones);
    }
    private DocumentCodec() {}
}
