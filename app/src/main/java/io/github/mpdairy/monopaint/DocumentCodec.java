package io.github.mpdairy.monopaint;

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
    private static final int LAYER_MAGIC = 0x54534d32; // TSM2
    private static final int OPACITY_MAGIC = 0x54534d33; // TSM3
    static void write(OutputStream stream, ToneDocument.Snapshot snapshot) throws IOException {
        DataOutputStream header=new DataOutputStream(stream);
        header.writeInt(OPACITY_MAGIC); header.writeInt(snapshot.width); header.writeInt(snapshot.height);
        java.util.zip.Deflater deflater=new java.util.zip.Deflater();
        try {
            DeflaterOutputStream compressed=new DeflaterOutputStream(stream,deflater);
            CRC32 crc=new CRC32();
            DataOutputStream data=new DataOutputStream(new java.util.zip.CheckedOutputStream(compressed,crc));
            data.writeInt(snapshot.layers.size()); data.writeInt(snapshot.active);
            for(ToneDocument.Layer layer:snapshot.layers) {
                data.writeUTF(layer.name); data.writeBoolean(layer.visible); data.writeByte(layer.opacity);
                data.write(layer.tones); data.write(layer.alpha);
            }
            data.flush(); new DataOutputStream(compressed).writeLong(crc.getValue());
            compressed.finish(); compressed.flush();
        } finally { deflater.end(); }
    }
    private static ToneDocument readLayers(InputStream stream,int width,int height,boolean retain,boolean hasOpacity) throws IOException {
        java.util.zip.Inflater inflater=new java.util.zip.Inflater();
        try {
            InflaterInputStream compressed=new InflaterInputStream(stream,inflater);
            CRC32 crc=new CRC32();
            DataInputStream data=new DataInputStream(new java.util.zip.CheckedInputStream(compressed,crc));
            int count=data.readInt(),active=data.readInt();
            if(count<1||count>ToneDocument.MAX_LAYERS||active<0||active>=count) throw new IOException("Invalid layers");
            java.util.ArrayList<ToneDocument.Layer> layers=new java.util.ArrayList<>();
            for(int i=0;i<count;i++) {
                String name=data.readUTF(); int visible=data.readUnsignedByte();
                int opacity=hasOpacity?data.readUnsignedByte():100;
                if(opacity>100)throw new IOException("Invalid layer opacity");
                if(name.trim().isEmpty()||name.length()>40||visible>1) throw new IOException("Invalid layer information");
                if(retain) {
                    byte[] tones=new byte[width*height],alpha=new byte[width*height];
                    data.readFully(tones); data.readFully(alpha);
                    ToneDocument.Layer layer=new ToneDocument.Layer(name,visible==1,tones,alpha);
                    layer.opacity=opacity; layers.add(layer);
                } else consume(data,width*height*2);
            }
            long checksum=crc.getValue();
            if(new DataInputStream(compressed).readLong()!=checksum) throw new IOException("Drawing checksum mismatch");
            if(compressed.read()!=-1) throw new IOException("Extra drawing data");
            return retain?new ToneDocument(width,height,layers,active):null;
        } finally { inflater.end(); }
    }
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
    static ToneDocument read(InputStream stream) throws IOException { return read(stream,true,0,0); }
    static void validate(InputStream stream,int width,int height) throws IOException { read(stream,false,width,height); }
    private static void consume(DataInputStream input,int count) throws IOException {
        byte[] buffer=new byte[Math.min(count,8192)];
        while(count>0) { int chunk=Math.min(count,buffer.length); input.readFully(buffer,0,chunk); count-=chunk; }
    }
    private static ToneDocument read(InputStream stream,boolean retain,int expectedWidth,int expectedHeight) throws IOException {
        DataInputStream header = new DataInputStream(stream);
        int magic=header.readInt();
        if (magic != MAGIC && magic != LAYER_MAGIC && magic != OPACITY_MAGIC) throw new IOException("Unknown drawing format");
        int width = header.readInt(), height = header.readInt();
        try { ToneDocument.validateSize(width, height); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid drawing dimensions", invalid); }
        if(expectedWidth!=0&&(width!=expectedWidth||height!=expectedHeight)) throw new IOException("Page size mismatch");
        if(magic==LAYER_MAGIC || magic==OPACITY_MAGIC) return readLayers(stream,width,height,retain,magic==OPACITY_MAGIC);
        long checksum=header.readLong();
        byte[] tones = retain?new byte[width * height]:null;
        CRC32 crc = new CRC32();
        java.util.zip.Inflater inflater=new java.util.zip.Inflater();
        try {
            DataInputStream compressed = new DataInputStream(new java.util.zip.CheckedInputStream(new InflaterInputStream(stream,inflater),crc));
            if(retain) compressed.readFully(tones); else consume(compressed,width*height);
            if (compressed.read() != -1) throw new IOException("Extra drawing data");
        } finally {inflater.end();}
        if (crc.getValue() != checksum) throw new IOException("Drawing checksum mismatch");
        if(!retain) return null;
        byte[] alpha=new byte[width*height]; java.util.Arrays.fill(alpha,(byte)255);
        java.util.ArrayList<ToneDocument.Layer> layers=new java.util.ArrayList<>();
        layers.add(new ToneDocument.Layer("Layer 1",true,tones,alpha));
        return new ToneDocument(width,height,layers,0);
    }
    private DocumentCodec() {}
}
