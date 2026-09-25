package dev.tilesmile.supernote;

import java.io.*;
import java.util.Arrays;

public final class BookChecks {
    public static void main(String[] args) throws Exception {
        ToneDocument first=new ToneDocument(137,95);mark(first,10,10,80);
        DrawingBook book=new DrawingBook(first);book.addPage();mark(book.current(),30,40,182);
        check(book.count()==2&&book.index()==1&&book.current().tone(10,10)==255,"New page is blank and selected");
        book.select(0);check(book.current().tone(10,10)==80&&book.current().canUndo(),"Recent page retains tones and undo");
        book.select(1);check(book.current().tone(30,40)==182,"Second page retained");
        book.current().clear();book.select(0);check(book.current().tone(10,10)==80,"Clear never touches other pages");
        for(int i=0;i<5;i++){book.addPage();mark(book.current(),i,i,100+i);}
        book.select(0);check(book.current().tone(10,10)==80,"Evicted page decodes correctly");
        book.select(3);byte[] active=book.current().snapshot();DrawingBook.Snapshot snapshot=book.snapshot();mark(book.current(),80,80,0);
        ByteArrayOutputStream out=new ByteArrayOutputStream();BookCodec.write(out,snapshot);byte[] zip=out.toByteArray();
        check(zip[0]=='P'&&zip[1]=='K',"All pages are in one ZIP-based file");
        int[] bulkBytes={0};
        BookCodec.write(new OutputStream() {
            @Override public void write(int value){}
            @Override public void write(byte[] bytes,int offset,int length){bulkBytes[0]+=length;}
        },snapshot);
        int packedBytes=0;
        try(java.util.zip.ZipInputStream entries=new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;while((entry=entries.getNextEntry())!=null)packedBytes+=entry.getSize();
        }
        check(bulkBytes[0]>=packedBytes,"Archive payload uses bulk writes for tablet storage");
        DrawingBook restored=BookCodec.read(new ByteArrayInputStream(zip));
        check(restored.count()==7&&restored.index()==3&&Arrays.equals(active,restored.current().snapshot()),"Immutable snapshot retains count and active page");
        restored.select(0);check(restored.current().tone(10,10)==80,"Page order and tones survive");
        for(int i=2;i<7;i++){restored.select(i);check(restored.current().tone(i-2,i-2)==100+i-2,"Every page survives save/open");}
        ByteArrayOutputStream legacy=new ByteArrayOutputStream();DocumentCodec.write(legacy,first.width,first.height,first.snapshot());
        DrawingBook old=BookCodec.read(new ByteArrayInputStream(legacy.toByteArray()));
        check(old.count()==1&&Arrays.equals(first.snapshot(),old.current().snapshot()),"Older drawings open as page one");
        for(int length:new int[]{0,10,zip.length-1,zip.length-22})reject(Arrays.copyOf(zip,length));
        byte[] broken=zip.clone();broken[50]^=1;reject(broken);
        DrawingBook many=new DrawingBook(new ToneDocument(2,2));for(int i=1;i<DrawingBook.MAX_PAGES;i++)many.addPage();
        try{many.addPage();throw new AssertionError("Page limit ignored");}catch(IOException expected){}
        check(many.count()==100,"Page-limit failure preserves existing pages");
        System.out.println("PASS: blank pages, navigation/cache, isolated clear, recent undo, immutable ZIP saves, legacy import and damaged-archive rejection");
    }
    private static void mark(ToneDocument d,int x,int y,int gray){d.begin();d.setTone(x,y,gray);d.finish();}
    private static void reject(byte[] data) throws Exception {
        try{BookCodec.read(new ByteArrayInputStream(data));throw new AssertionError("Damaged archive accepted");}catch(IOException expected){}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
