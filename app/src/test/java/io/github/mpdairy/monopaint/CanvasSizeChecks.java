package io.github.mpdairy.monopaint;
import java.io.*;
import java.util.*;
import java.util.zip.*;

final class CanvasSizeChecks {
    public static void main(String[] args)throws Exception {
        ToneDocument page=new ToneDocument(73,91);
        page.begin();page.paintTone(8,9,37,123);page.finish();
        page.addLayer();page.begin();page.paintTone(20,30,82,201);page.finish();
        page.setLayerOpacity(1,47);page.setLayerVisible(1,false);
        byte[] original=DrawingBook.encode(page);
        DrawingBook book=new DrawingBook(page);
        book.setCanvasChoice(2);DrawingBook.Snapshot before=book.snapshot();
        page.expand(13,17,19,23);
        check(page.width==105 && page.height==131,"New dimensions");
        for(int i=0;i<page.layerCount();i++) {
            ToneDocument old=DocumentCodec.read(new ByteArrayInputStream(original)); old.selectLayer(i);page.selectLayer(i);
            for(int y=0;y<91;y++)for(int x=0;x<73;x++)check(page.tone(x+13,y+17)==old.tone(x,y) && page.opacity(x+13,y+17)==old.opacity(x,y),"Every pixel and alpha retained");
            check(page.opacity(0,0)==0 && page.opacity(104,130)==0,"Margins transparent on every layer");
        }
        page.selectLayer(1);
        byte[] expanded=DrawingBook.encode(page);
        check(page.undo() && Arrays.equals(original,DrawingBook.encode(page)),"Undo restores dimensions and all layers");
        check(page.undo() && page.layerVisible(1),"Older layer edit remains undoable");
        page.redo();page.redo();check(Arrays.equals(expanded,DrawingBook.encode(page)),"Redo re-expands exactly");
        page.setLayerVisible(1,true);page.begin();page.paintTone(0,0,0);page.finish();
        page.undo();page.undo();page.undo();page.redo();page.redo();page.redo();
        check(page.tone(0,0)==0,"Painting new margins survives history travel");
        ByteArrayOutputStream out=new ByteArrayOutputStream();BookCodec.write(out,before);
        check(BookCodec.read(new ByteArrayInputStream(out.toByteArray())).current().width==73,"Old snapshot immutable");
        book.addPage();check(book.canvasChoice()==0 && book.current().width==73,"New page independent size and choice");book.setCanvasChoice(1);
        book.addPage();book.addPage();book.select(0);
        check(book.current().width==105 && book.canvasChoice()==2,"Evicted expanded page and choice retained");
        out.reset();BookCodec.write(out,book.snapshot());DrawingBook restored=BookCodec.read(new ByteArrayInputStream(out.toByteArray()));
        check(restored.canvasChoice()==2 && restored.current().width==105,"Expanded page restored");
        restored.select(1);check(restored.canvasChoice()==1 && restored.current().width==73,"Keep choice restored independently");
        restored.select(2);check(restored.canvasChoice()==0,"Unvisited page still asks");
        check(restored.snapshot().page(0).width==105,"Preview and export use page dimensions");
        legacyBook();
        try {page.expand(-1,0,0,0);throw new AssertionError("Negative margin accepted");}catch(IllegalArgumentException expected){}
        try {page.expand(4096,0,0,0);throw new AssertionError("Excessive size accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: per-page sizes/choices, exact multilayer expansion, nested undo/redo, immutable snapshots, cache eviction, new pages, previews/export and legacy book compatibility");
    }
    private static void legacyBook()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest"));DataOutputStream d=new DataOutputStream(zip);
            d.writeInt(0x54534231);d.writeInt(41);d.writeInt(53);d.writeInt(2);d.writeInt(1);zip.closeEntry();
            for(int i=1;i<=2;i++) {zip.putNextEntry(new ZipEntry("pages/"+i+".tsm"));zip.write(DrawingBook.encode(new ToneDocument(41,53)));zip.closeEntry();}
        }
        DrawingBook old=BookCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(old.count()==2 && old.index()==1 && old.canvasChoice()==0,"Old multipage books still open");old.select(0);check(old.current().height==53,"Old inactive pages still open");
    }
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
}
