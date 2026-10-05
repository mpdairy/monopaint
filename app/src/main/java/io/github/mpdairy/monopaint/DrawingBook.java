package io.github.mpdairy.monopaint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Ordered pages; only two recently used tone documents and their undo histories stay decoded. */
final class DrawingBook {
    static final int MAX_PAGES=100, MAX_PACKED_BYTES=16*1024*1024;
    final int width,height;
    private final ArrayList<byte[]> pages=new ArrayList<>();
    private final LinkedHashMap<Integer,ToneDocument> cache=new LinkedHashMap<>(4,.75f,true);
    private final java.util.HashMap<Integer,Long> packedRevisions=new java.util.HashMap<>();
    private int index;

    DrawingBook(ToneDocument first) {
        width=first.width;height=first.height;pages.add(null);cache.put(0,first);
    }
    DrawingBook(int width,int height,ArrayList<byte[]> pages,int index) throws IOException {
        this(width,height,pages,index,null);
    }
    DrawingBook(int width,int height,ArrayList<byte[]> pages,int index,ToneDocument decoded) throws IOException {
        ToneDocument.validateSize(width,height);this.width=width;this.height=height;
        if(pages.isEmpty()||pages.size()>MAX_PAGES||index<0||index>=pages.size())throw new IOException("Invalid page list");
        this.pages.addAll(pages);this.index=index;cache.put(index,decoded==null?decode(pages.get(index)):decoded);
        packedRevisions.put(index,current().revision());
    }
    int count(){return pages.size();}
    int index(){return index;}
    ToneDocument current(){return cache.get(index);}
    void select(int target) throws IOException {
        if(target<0||target>=count())throw new IllegalArgumentException("Invalid page");
        if(target==index)return;
        retainCurrent();
        ToneDocument next=cache.get(target);
        if(next==null) {next=decode(pages.get(target));packedRevisions.put(target,next.revision());}
        index=target;cache.put(index,next);evict();
    }
    void addPage() throws IOException {
        if(count()>=MAX_PAGES)throw new IOException("This drawing has 100 pages. Start a new drawing for more pages.");
        retainCurrent();
        ToneDocument next=new ToneDocument(width,height);
        index=pages.size();pages.add(null);cache.put(index,next);evict();
    }
    private void retainCurrent() throws IOException {
        // Browsing does not change a page. Keep its already-compressed bytes,
        // including after decoding an evicted page, instead of deflating it again.
        if(currentPacked()!=null)return;
        byte[] packed=encode(current());long size=packed.length;
        for(int i=0;i<pages.size();i++)if(i!=index&&pages.get(i)!=null)size+=pages.get(i).length;
        if(size>MAX_PACKED_BYTES)throw new IOException("This drawing is full. Save it and start a new drawing.");
        pages.set(index,packed);packedRevisions.put(index,current().revision());current().trimHistory(8*1024*1024);
    }
    private byte[] currentPacked() {
        Long revision=packedRevisions.get(index);
        return revision!=null && revision==current().revision()?pages.get(index):null;
    }
    private void evict(){
        long pixels=0; for(ToneDocument page:cache.values()) pixels+=(long)page.width*page.height*page.layerCount()*2;
        while(cache.size()>1&&(cache.size()>2||pixels>32*1024*1024)) {
            int removedIndex=cache.keySet().iterator().next();
            ToneDocument removed=cache.remove(removedIndex);packedRevisions.remove(removedIndex);
            pixels-=(long)removed.width*removed.height*removed.layerCount()*2;
        }
    }
    private ToneDocument decode(byte[] packed) throws IOException {
        ToneDocument document=DocumentCodec.read(new ByteArrayInputStream(packed));
        if(document.width!=width||document.height!=height)throw new IOException("Page size does not match drawing");
        return document;
    }
    static byte[] encode(ToneDocument document) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        DocumentCodec.write(out,document.layerSnapshot()); return out.toByteArray();
    }
    static byte[] encode(int width,int height,byte[] tones) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();DocumentCodec.write(out,width,height,tones);return out.toByteArray();
    }
    Snapshot snapshot(){return new Snapshot(this);}
    static final class Snapshot {
        final int width,height,index;
        final ToneDocument.Snapshot activePage;
        final byte[] packedActivePage;
        final ArrayList<byte[]> pages;
        Snapshot(DrawingBook book){
            width=book.width;height=book.height;index=book.index;pages=new ArrayList<>(book.pages);
            activePage=book.current().layerSnapshot();
            packedActivePage=book.currentPacked();
        }
        int count(){return pages.size();}
        /** Decodes one page without selecting it or touching the live book's undo history. */
        ToneDocument page(int i) throws IOException {
            if(i==index)return new ToneDocument(activePage.width,activePage.height,activePage.layers,activePage.active);
            return DocumentCodec.read(new ByteArrayInputStream(pages.get(i)));
        }
    }
}
