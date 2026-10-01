package io.github.mpdairy.monopaint;

import java.io.*;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

final class LayerChecks {
    public static void main(String[] args) throws Exception {
        opacityAndClear(); composition(); glazing(); history(); persistence(); damaged();
        System.out.println("PASS: layer ordering/visibility, transparent erasing, white coverage, fill isolation, structural undo/redo, immutable saves, page eviction, legacy import and invalid-layer rejection");
    }
    private static void opacityAndClear() throws Exception {
        ToneDocument doc=new ToneDocument(8,8);paint(doc,2,2,20);doc.addLayer();paint(doc,2,2,220);
        check(doc.layerOpacity(1)==100,"New layers are opaque");
        ToneDocument.Snapshot before=doc.layerSnapshot();
        doc.setLayerOpacity(1,50);
        check(doc.compositeTone(2,2)==120&&doc.tone(2,2)==220&&doc.opacity(2,2)==255,"Opacity changes composition without changing marks");
        check(before.layers.get(1).opacity==100,"Pending saves freeze opacity");
        check(doc.undo()&&doc.layerOpacity(1)==100&&doc.redo()&&doc.layerOpacity(1)==50,"Opacity undo/redo");
        doc.setLayerOpacity(1,0);check(doc.compositeTone(2,2)==20,"Zero opacity reveals lower layer");
        paint(doc,3,3,0);doc.setLayerOpacity(1,100);check(doc.compositeTone(3,3)==0,"Painting at zero opacity retains pigment");
        doc.setLayerOpacity(1,37);doc.setLayerVisible(0,false);
        byte[] original=DrawingBook.encode(doc);
        DrawingBook book=new DrawingBook(doc);book.addPage();book.addPage();book.select(0);
        check(book.current().layerOpacity(1)==37,"Opacity survives page eviction");
        ToneDocument restored=DocumentCodec.read(new ByteArrayInputStream(original));
        check(restored.layerOpacity(1)==37,"Opacity persists");
        check(doc.clearAllLayers()&&doc.layerCount()==2&&doc.activeLayer()==1&&doc.layerOpacity(1)==37&&!doc.layerVisible(0),"Clear retains layer structure");
        for(ToneDocument.Layer layer:doc.layerSnapshot().layers)for(byte value:layer.alpha)check(value==0,"Clear includes hidden layers");
        check(!doc.clearAllLayers(),"Empty clear adds no undo");
        check(doc.undo()&&Arrays.equals(original,DrawingBook.encode(doc)),"One undo restores all layers exactly");
        check(doc.redo()&&doc.compositeTone(2,2)==255&&doc.undo(),"Clear all redoes and undoes");
        // Build a checksummed pre-opacity layered file, ensuring migration defaults to 100%.
        ByteArrayOutputStream old=new ByteArrayOutputStream();DataOutputStream header=new DataOutputStream(old);
        header.writeInt(0x54534d32);header.writeInt(1);header.writeInt(1);
        DeflaterOutputStream compressed=new DeflaterOutputStream(old);java.util.zip.CRC32 crc=new java.util.zip.CRC32();
        DataOutputStream data=new DataOutputStream(new java.util.zip.CheckedOutputStream(compressed,crc));
        data.writeInt(1);data.writeInt(0);data.writeUTF("Legacy");data.writeBoolean(true);data.writeByte(24);data.writeByte(255);data.flush();
        new DataOutputStream(compressed).writeLong(crc.getValue());compressed.finish();
        ToneDocument legacy=DocumentCodec.read(new ByteArrayInputStream(old.toByteArray()));
        check(legacy.layerOpacity(0)==100&&legacy.compositeTone(0,0)==24,"Old layers remain fully opaque");
        System.out.println("PASS: layer opacity composition, non-destructive marks, snapshot/save/page eviction, legacy layers, undo/redo and atomic clear including hidden layers");
    }
    private static void paint(ToneDocument doc,int x,int y,int tone) {
        doc.begin(); doc.paintTone(x,y,tone); doc.finish();
    }
    private static void composition() {
        ToneDocument doc=new ToneDocument(137,91);
        paint(doc,70,70,20); doc.addLayer();
        check(doc.activeLayer()==1&&doc.tone(70,70)==255&&doc.compositeTone(70,70)==20,"New layer is transparent and independently editable");
        paint(doc,70,70,200); paint(doc,71,70,255);
        check(doc.compositeTone(70,70)==200&&doc.opacity(71,70)==255,"Opaque pigment includes white");
        doc.setLayerVisible(1,false); check(doc.compositeTone(70,70)==20,"Hidden layer is excluded");
        try {doc.begin(); throw new AssertionError("Hidden layer accepted drawing");} catch(IllegalStateException expected) {}
        doc.setLayerVisible(1,true);
        doc.begin(); doc.eraseTone(70,70,.5f); doc.finish();
        int expected=(200*127+20*128+127)/255;
        check(doc.compositeTone(70,70)==expected,"Soft eraser reveals lower pixels with alpha, without white pigment");
        doc.begin(); doc.eraseTone(70,70,1); doc.finish();
        check(doc.compositeTone(70,70)==20&&doc.opacity(70,70)==0,"Hard eraser exposes lower layer");
        doc.undo(); check(doc.compositeTone(70,70)==expected,"Undo restores coverage");
        doc.redo(); doc.moveLayer(0); paint(doc,70,70,99);
        check(doc.compositeTone(70,70)==20,"Moved lower layer is covered by upper marks");
        doc.selectLayer(1); doc.clear(); check(doc.compositeTone(70,70)==99,"Clear affects only the active layer");
        doc.undo(); check(doc.compositeTone(70,70)==20,"Clear undo preserves both layers");
        doc.selectLayer(0);
        FloodFill fill=new FloodFill(doc,0,0,255); while(!fill.advance(1000)) {} fill.finish();
        check(doc.opacity(0,0)==255&&doc.compositeTone(70,70)==20,"White fill covers transparent regions on the active layer only");
        int[] pixels=new int[137*91]; doc.render(pixels,0,0,137,91);
        for(int y=0;y<91;y++) for(int x=0;x<137;x++) check(pixels[y*137+x]==DotPattern.pixel(doc.compositeTone(x,y),x,y),"Display uses composited tones");
        doc.begin(); doc.paintTone(136,90,0); doc.cancel(); check(doc.opacity(136,90)==255,"Cancel restores edge coverage");
    }
    private static void glazing() {
        ToneDocument doc=new ToneDocument(4,4); paint(doc,1,1,80); doc.addLayer();
        doc.begin(); doc.glazeTone(1,1,0); doc.glazeTone(1,1,0); doc.finish();
        check(doc.tone(1,1)==128&&doc.compositeTone(1,1)==40,"Transparent paint retains lower-layer pigment with one glaze per gesture");
        doc.undo(); check(doc.compositeTone(1,1)==80&&doc.opacity(1,1)==0,"Glaze undo restores transparency");
        WetWatercolor wet=new WetWatercolor(doc,0); doc.begin(); wet.beginStroke();
        wet.paintMask(new int[]{-1},1,1,1,1,1,255,false); wet.finishStroke();
        while(wet.isAnimating()) wet.advance(false);
        check(doc.compositeTone(1,1)==255&&doc.opacity(1,1)==255,"Opaque wet white covers lower layers");
        doc.selectLayer(0);check(doc.tone(1,1)==80,"Wet brush does not change the layer below");
        doc.addLayer(); doc.begin(); doc.pencilTone(1,1,255,.5f); doc.pencilTone(1,1,255,.25f); doc.finish();
        check(doc.opacity(1,1)==128&&doc.compositeTone(1,1)==255,"White pencil carries coverage and keeps its strongest contact");
        doc.setLayerVisible(2,false);
        check(doc.compositeTone(1,1)>80,"White pencil lightens the layer underneath");
        doc.undo(); doc.undo(); doc.begin(); doc.pencilTone(1,1,170,.5f); doc.finish();
        doc.setLayerVisible(2,false);
        check(doc.compositeTone(1,1)>80&&doc.compositeTone(1,1)<170,"Gray pencil blends its chosen pigment with lower layers");
    }
    private static void history() {
        ToneDocument doc=new ToneDocument(8,8); paint(doc,2,2,40); doc.addLayer(); doc.renameLayer("Clouds");
        paint(doc,2,2,180); doc.setLayerVisible(1,false); doc.moveLayer(0); doc.removeLayer();
        check(doc.layerCount()==1,"Delete removes selected layer");
        doc.undo(); check(doc.layerCount()==2&&doc.layerName(0).equals("Clouds")&&!doc.layerVisible(0),"Delete undo restores name and visibility");
        doc.undo(); check(doc.activeLayer()==1,"Reorder undo restores selection");
        doc.undo(); check(doc.compositeTone(2,2)==180,"Visibility undo restores image");
        doc.selectLayer(0); doc.undo(); check(doc.activeLayer()==1&&doc.compositeTone(2,2)==40,"Stroke undo selects its own layer");
        doc.undo(); check(doc.layerName(1).equals("Layer 2"),"Rename undo");
        doc.undo(); check(doc.layerCount()==1,"Add undo");
        for(int i=0;i<6;i++) check(doc.redo(),"Every layer operation redoes");
        check(doc.layerCount()==1&&doc.compositeTone(2,2)==40,"Full redo restores final state");
        while(doc.layerCount()<ToneDocument.MAX_LAYERS) doc.addLayer();
        try {doc.addLayer(); throw new AssertionError("Layer limit ignored");} catch(IllegalStateException expected) {}
        while(doc.layerCount()>1) doc.removeLayer();
        try {doc.removeLayer(); throw new AssertionError("Last layer deleted");} catch(IllegalStateException expected) {}
    }
    private static void persistence() throws Exception {
        ToneDocument doc=new ToneDocument(31,27); paint(doc,3,2,34); doc.addLayer(); doc.renameLayer("Sky ☁");
        paint(doc,3,2,255); doc.begin(); doc.eraseTone(3,2,.3f); doc.finish();
        doc.addLayer(); paint(doc,5,4,0); doc.setLayerVisible(2,false); doc.selectLayer(1);
        DrawingBook book=new DrawingBook(doc); DrawingBook.Snapshot snapshot=book.snapshot();
        paint(doc,3,2,0); doc.renameLayer("Changed");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); BookCodec.write(bytes,snapshot);
        DrawingBook loaded=BookCodec.read(new ByteArrayInputStream(bytes.toByteArray())); ToneDocument page=loaded.current();
        check(page.layerCount()==3&&page.activeLayer()==1&&!page.layerVisible(2)&&page.layerName(1).equals("Sky ☁"),"Snapshot preserves independent layer metadata");
        check(page.opacity(3,2)==178&&page.compositeTone(3,2)>34,"Snapshot freezes tones and partial alpha");
        byte[] expected=page.snapshot();
        loaded.addPage(); loaded.addPage(); loaded.addPage(); loaded.select(0);
        check(loaded.current().layerCount()==3&&Arrays.equals(expected,loaded.current().snapshot()),"Evicted pages keep all layers");
        ByteArrayOutputStream legacy=new ByteArrayOutputStream(); DocumentCodec.write(legacy,31,27,expected);
        ToneDocument imported=DocumentCodec.read(new ByteArrayInputStream(legacy.toByteArray()));
        check(imported.layerCount()==1&&Arrays.equals(expected,imported.snapshot())&&imported.opacity(0,0)==255,"Legacy flat images preserve every pixel as Layer 1");
        bytes.reset(); RecoveryCodec.write(bytes,loaded.snapshot(),"Art/Test");
        RecoveryCodec.Recovered recovery=RecoveryCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(recovery.path.equals("Art/Test")&&recovery.book.index()==0&&recovery.book.current().activeLayer()==1,"Recovery keeps drawing, page and selected layer");
    }
    private static void damaged() throws Exception {
        ToneDocument doc=new ToneDocument(9,7);doc.addLayer();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); DocumentCodec.write(out,doc.layerSnapshot()); byte[] valid=out.toByteArray();
        DocumentCodec.validate(new ByteArrayInputStream(valid),9,7);
        try { DocumentCodec.validate(new ByteArrayInputStream(valid),7,9); throw new AssertionError("Accepted wrong inactive page size"); }
        catch(IOException expected) {}
        for(int cut:new int[]{0,4,12,valid.length/2,valid.length-1}) reject(Arrays.copyOf(valid,cut));
        byte[] wrong=valid.clone(); wrong[wrong.length-6]^=32; reject(wrong);
        for(int count:new int[]{0,9,Integer.MAX_VALUE}) {
            out.reset(); DataOutputStream header=new DataOutputStream(out); header.writeInt(0x54534d32);header.writeInt(9);header.writeInt(7);
            DeflaterOutputStream zipped=new DeflaterOutputStream(out);DataOutputStream data=new DataOutputStream(zipped);data.writeInt(count);data.writeInt(0);zipped.finish();
            reject(out.toByteArray());
        }
    }
    private static void reject(byte[] data) throws Exception {
        try { DocumentCodec.read(new ByteArrayInputStream(data)); throw new AssertionError("Accepted invalid layer data"); }
        catch(IOException expected) {}
        try { DocumentCodec.validate(new ByteArrayInputStream(data),9,7); throw new AssertionError("Accepted invalid inactive page data"); }
        catch(IOException expected) {}
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
