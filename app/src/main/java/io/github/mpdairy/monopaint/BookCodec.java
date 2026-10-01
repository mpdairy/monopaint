package io.github.mpdairy.monopaint;

import java.io.*;
import java.util.ArrayList;
import java.util.zip.*;

/** One ZIP-based .tsm file: a manifest and ordered, checksummed logical-tone pages. */
final class BookCodec {
    private static final int MAGIC=0x54534231;
    private static final int MAX_PAGE_BYTES=DrawingBook.MAX_PACKED_BYTES;
    static void write(OutputStream output,DrawingBook.Snapshot snapshot) throws IOException {
        ArrayList<byte[]> pages=new ArrayList<>(snapshot.pages);
        if(snapshot.packedActivePage!=null)pages.set(snapshot.index,snapshot.packedActivePage);
        else {
            ByteArrayOutputStream active=new ByteArrayOutputStream(); DocumentCodec.write(active,snapshot.activePage);
            pages.set(snapshot.index,active.toByteArray());
        }
        long size=0;for(byte[] page:pages)size+=page.length;
        if(size>DrawingBook.MAX_PACKED_BYTES)throw new IOException("Drawing exceeds the page storage limit");
        try(ZipOutputStream zip=new ZipOutputStream(new FilterOutputStream(output) {
            @Override public void write(byte[] bytes,int offset,int length) throws IOException {out.write(bytes,offset,length);}
            @Override public void close() throws IOException {flush();}
        })) {
        ByteArrayOutputStream manifest=new ByteArrayOutputStream();DataOutputStream info=new DataOutputStream(manifest);
        info.writeInt(MAGIC);info.writeInt(snapshot.width);info.writeInt(snapshot.height);info.writeInt(pages.size());info.writeInt(snapshot.index);
        entry(zip,"manifest",manifest.toByteArray());
        for(int i=0;i<pages.size();i++)entry(zip,"pages/"+(i+1)+".tsm",pages.get(i));
        zip.finish();zip.flush();
        }
    }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws IOException {
        CRC32 crc=new CRC32();crc.update(bytes);ZipEntry entry=new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);entry.setSize(bytes.length);entry.setCompressedSize(bytes.length);entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();
    }
    static DrawingBook read(InputStream input) throws IOException {
        byte[] archive=bounded(input,DrawingBook.MAX_PACKED_BYTES+65536);
        if(archive.length>=4&&archive[0]=='T'&&archive[1]=='S'&&archive[2]=='M'&&(archive[3]=='1'||archive[3]=='2'||archive[3]=='3'))
            return new DrawingBook(DocumentCodec.read(new ByteArrayInputStream(archive)));
        // Require the complete central-directory footer, including when local entries are intact.
        int end=archive.length-22;
        if(end<0||archive[end]!=0x50||archive[end+1]!=0x4b||archive[end+2]!=5||archive[end+3]!=6
                ||archive[end+20]!=0||archive[end+21]!=0)throw new IOException("Incomplete drawing archive");
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry=zip.getNextEntry();
            if(entry==null||!entry.getName().equals("manifest"))throw new IOException("Missing page manifest");
            byte[] header=bounded(zip,20);if(header.length!=20)throw new IOException("Invalid page manifest");
            DataInputStream info=new DataInputStream(new ByteArrayInputStream(header));
            if(info.readInt()!=MAGIC)throw new IOException("Unknown drawing format");
            int width=info.readInt(),height=info.readInt(),count=info.readInt(),active=info.readInt();
            try{ToneDocument.validateSize(width,height);}catch(IllegalArgumentException e){throw new IOException("Invalid page dimensions",e);}
            if(count<1||count>DrawingBook.MAX_PAGES||active<0||active>=count)throw new IOException("Invalid page count");
            int entries=(archive[end+10]&255)|((archive[end+11]&255)<<8);
            if(entries!=count+1)throw new IOException("Incomplete page directory");
            ArrayList<byte[]> pages=new ArrayList<>();long total=0;ToneDocument selected=null;
            for(int i=0;i<count;i++) {
                entry=zip.getNextEntry();
                if(entry==null||!entry.getName().equals("pages/"+(i+1)+".tsm"))throw new IOException("Missing or unordered page");
                byte[] bytes=bounded(zip,MAX_PAGE_BYTES);total+=bytes.length;
                if(total>DrawingBook.MAX_PACKED_BYTES)throw new IOException("Drawing is too large");
                if(i==active) {
                    selected=DocumentCodec.read(new ByteArrayInputStream(bytes));
                    if(selected.width!=width||selected.height!=height)throw new IOException("Page size mismatch");
                } else DocumentCodec.validate(new ByteArrayInputStream(bytes),width,height);
                pages.add(bytes);
            }
            if(zip.getNextEntry()!=null)throw new IOException("Unexpected archive entry");
            return new DrawingBook(width,height,pages,active,selected);
        }
    }
    private static byte[] bounded(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
        while((read=input.read(buffer))!=-1) {
            if(output.size()+read>limit)throw new IOException("Drawing data exceeds size limit");output.write(buffer,0,read);
        }
        return output.toByteArray();
    }
    private BookCodec(){}
}
