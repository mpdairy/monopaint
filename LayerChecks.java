package io.github.mpdairy.monopaint;

import java.io.*;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

final class LayerChecks {
    public static void main(String[] args) throws Exception {
        opacityAndClear(); composition(); glazing(); history(); merging(); mergedLooksTheSame(); mergeRefusals(); limit(); persistence(); damaged();
        System.out.println("PASS: layer ordering/visibility, transparent erasing, white coverage, fill isolation, structural undo/redo, merge down with opacity, partial coverage, refusals and history, the ten-layer limit, immutable saves, page eviction, legacy import and invalid-layer rejection");
    }
    private static int[] composite(ToneDocument doc) {
        int[] result=new int[doc.width*doc.height];
        for(int y=0;y<doc.height;y++) for(int x=0;x<doc.width;x++) result[y*doc.width+x]=doc.compositeTone(x,y);
        return result;
    }
    /** Checks that merging is refused, returning the message the Layers panel would show. */
    private static String refuse(ToneDocument doc,String why) {
        try { doc.mergeDown(); } catch(IllegalStateException expected) { return expected.getMessage(); }
        throw new AssertionError("Merged when "+why);
    }
    private static void merging() throws Exception {
        // Opaque marks on the upper layer cover the lower layer's, exactly as they did.
        ToneDocument doc=new ToneDocument(8,8); paint(doc,2,2,20); paint(doc,5,5,90);
        doc.addLayer(); doc.renameLayer("Ink"); paint(doc,2,2,200); paint(doc,6,1,0);
        byte[] before=DrawingBook.encode(doc); int[] shown=composite(doc);
        doc.mergeDown();
        check(doc.layerCount()==1&&doc.activeLayer()==0&&doc.layerName(0).equals("Layer 1")&&doc.layerOpacity(0)==100,"Merge leaves one layer, named for the lower and fully opaque");
        check(Arrays.equals(shown,composite(doc)),"A plain merge looks exactly as before");
        check(doc.tone(2,2)==200&&doc.tone(5,5)==90&&doc.tone(6,1)==0&&doc.opacity(2,2)==255&&doc.opacity(0,0)==0,"The merged layer holds both layers' marks and stays transparent elsewhere");
        check(doc.undo()&&doc.layerCount()==2&&doc.activeLayer()==1&&doc.layerName(1).equals("Ink"),"Undo brings back both layers and reselects the upper one");
        check(Arrays.equals(before,DrawingBook.encode(doc)),"One undo restores both layers exactly");
        check(doc.redo()&&doc.layerCount()==1&&Arrays.equals(shown,composite(doc)),"Redo merges again");
        check(!doc.redo()&&doc.undo()&&Arrays.equals(before,DrawingBook.encode(doc)),"Merge is a single history step");
        // An empty upper layer changes nothing but bakes in the lower layer's opacity; an opaque one replaces it.
        ToneDocument faded=new ToneDocument(8,8); paint(faded,1,1,40); faded.setLayerOpacity(0,50); faded.addLayer();
        int look=faded.compositeTone(1,1); faded.mergeDown();
        check(faded.layerOpacity(0)==100&&faded.compositeTone(1,1)==look&&faded.opacity(1,1)==128,"Opacity is baked into coverage, not lost");
        ToneDocument covered=new ToneDocument(8,8); paint(covered,1,1,40); covered.addLayer(); paint(covered,1,1,230); covered.mergeDown();
        check(covered.tone(1,1)==230&&covered.opacity(1,1)==255,"An opaque upper mark replaces the lower one");
        // Exact arithmetic for two partly covered pixels, worked by hand: the upper's 100 of 255 leaves 155, of which the
        // lower keeps 100/255 (60.8, so 61), for 161 in all; the pigment is weighted by those shares: 5,720,000 / (161 * 255) = 139.3
        // for an upper of 200, and 5,745,500 / (161 * 255) = 139.9, which rounds up to 140, for an upper of 201.
        ToneDocument pair=new ToneDocument(4,4); pair.begin(); pair.paintTone(1,1,40,100); pair.paintTone(2,1,40,100); pair.finish();
        pair.addLayer(); pair.begin(); pair.paintTone(1,1,200,100); pair.paintTone(2,1,201,100); pair.finish(); pair.mergeDown();
        byte[] pairTones=pair.layerSnapshot().layers.get(0).tones;
        check(pair.opacity(1,1)==161&&pair.opacity(2,1)==161&&(pairTones[5]&255)==139&&(pairTones[6]&255)==140,"Partly covered pixels combine to coverage 161 and pigment 139 and 140");
        // Laying a sheet that covers nothing over a pixel nothing covers changes nothing, not even an unrecorded tone.
        java.util.ArrayList<ToneDocument.Layer> raw=new java.util.ArrayList<>();
        raw.add(new ToneDocument.Layer("Raw",true,new byte[64],new byte[64]));
        ToneDocument bare=new ToneDocument(8,8,raw,0);
        bare.begin(); bare.overTone(1,1,200,0); bare.overTone(2,2,200,255); bare.finish();
        check(bare.layerSnapshot().layers.get(0).tones[9]==0&&bare.tone(2,2)==200&&bare.opacity(2,2)==255,"An empty sheet leaves a bare pixel alone and an opaque one lands");
        bare.undo(); check(!bare.canUndo()&&bare.opacity(2,2)==0,"Only the opaque mark made an undo step");
        // After a merge the document edits like any other: layers move, rename, draw and erase.
        covered.addLayer(); covered.renameLayer("Top"); covered.moveLayer(0); covered.selectLayer(1); paint(covered,3,3,70);
        check(covered.layerCount()==2&&covered.layerName(0).equals("Top")&&covered.tone(3,3)==70&&covered.compositeTone(1,1)==230,"A merged layer edits normally");
        // History: a stroke after the merge undoes first, then the merge, to the exact start.
        ToneDocument h=new ToneDocument(8,8); paint(h,3,3,60); h.addLayer(); paint(h,3,3,180); byte[] start=DrawingBook.encode(h);
        h.mergeDown(); paint(h,6,6,10);
        check(h.undo()&&h.layerCount()==1&&h.tone(6,6)==255,"Undo first removes the later stroke");
        check(h.undo()&&h.layerCount()==2&&Arrays.equals(start,DrawingBook.encode(h)),"Undo then splits the merge exactly");
        check(h.redo()&&h.redo()&&h.layerCount()==1&&h.tone(6,6)==10&&h.tone(3,3)==180,"Redo replays the merge and the stroke");
        // A save taken before the merge keeps the layers as they were, even though the live ones are replaced.
        ToneDocument saving=new ToneDocument(8,8); paint(saving,2,2,30); saving.addLayer(); paint(saving,2,2,220);
        ToneDocument.Snapshot snapshot=saving.layerSnapshot(); byte[] unmerged=DrawingBook.encode(saving);
        saving.mergeDown(); paint(saving,4,4,0);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DocumentCodec.write(bytes,snapshot);
        check(Arrays.equals(unmerged,bytes.toByteArray())&&snapshot.layers.size()==2,"A pending save keeps the pre-merge layers");
        ToneDocument reloaded=DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(saving)));
        check(reloaded.layerCount()==1&&Arrays.equals(composite(saving),composite(reloaded)),"A merged page saves and loads whole");
        // Masking fluid is not artwork: merging neither joins it to a layer nor removes it.
        ToneDocument fluid=new ToneDocument(8,8); paint(fluid,1,1,50); fluid.addLayer(); paint(fluid,1,1,150);
        fluid.paintMaskingFluid(true); fluid.begin(); fluid.paintTone(4,4,0); fluid.finish(); fluid.paintMaskingFluid(false);
        byte[] fluidTones=fluid.layerSnapshot().mask.tones.clone(),fluidAlpha=fluid.layerSnapshot().mask.alpha.clone();
        fluid.mergeDown();
        check(fluid.hasMask()&&Arrays.equals(fluidTones,fluid.layerSnapshot().mask.tones)&&Arrays.equals(fluidAlpha,fluid.layerSnapshot().mask.alpha),"Masking fluid is left as it was");
        check(fluid.layerCount()==1&&fluid.tone(1,1)==150&&fluid.opacity(4,4)==0,"The fluid is not merged into the layer");
        check(fluid.undo()&&fluid.hasMask()&&fluid.layerCount()==2,"Undo keeps the fluid too");
    }
    /** Layers with partly covered pixels and different opacities look the same merged, to within rounding. */
    private static void mergedLooksTheSame() throws Exception {
        java.util.Random random=new java.util.Random(7);
        ToneDocument doc=new ToneDocument(40,30); int[] opacities={90,70,45,60};
        for(int n=0;n<4;n++) {
            if(n>0) doc.addLayer();
            doc.begin();
            for(int y=0;y<30;y++) for(int x=0;x<40;x++) {
                int roll=random.nextInt(4);
                if(roll!=0) doc.paintTone(x,y,random.nextInt(256),roll==1?1+random.nextInt(254):255);
            }
            doc.finish(); doc.setLayerOpacity(n,opacities[n]);
        }
        // Merge the third layer down into the second: the first and fourth keep their order, marks and opacity.
        doc.selectLayer(2);
        byte[] original=DrawingBook.encode(doc); int[] shown=composite(doc);
        ToneDocument.Snapshot before=doc.layerSnapshot();
        doc.mergeDown();
        check(doc.layerCount()==3&&doc.activeLayer()==1,"Merging a middle layer joins just its two");
        check(doc.layerOpacity(0)==90&&doc.layerOpacity(1)==100&&doc.layerOpacity(2)==60,"Neighbouring layers keep their opacity");
        ToneDocument.Snapshot after=doc.layerSnapshot();
        check(Arrays.equals(before.layers.get(0).tones,after.layers.get(0).tones)&&Arrays.equals(before.layers.get(0).alpha,after.layers.get(0).alpha)
                &&Arrays.equals(before.layers.get(3).tones,after.layers.get(2).tones)&&Arrays.equals(before.layers.get(3).alpha,after.layers.get(2).alpha),"Neighbouring layers' marks are untouched");
        check(doc.layerName(0).equals(before.layers.get(0).name)&&doc.layerName(1).equals(before.layers.get(1).name)&&doc.layerName(2).equals(before.layers.get(3).name),"Names and order follow");
        near(shown,composite(doc),2,"One merge");
        // Keep merging down to a single layer; the picture holds still, and every step undoes.
        doc.mergeDown(); near(shown,composite(doc),3,"Two merges");
        check(doc.layerCount()==2&&doc.activeLayer()==0,"The merged layer is the bottom one now");
        doc.selectLayer(1); doc.mergeDown(); near(shown,composite(doc),3,"Three merges");
        check(doc.layerCount()==1&&doc.layerOpacity(0)==100,"Everything merged into one layer");
        for(int i=0;i<3;i++) check(doc.undo(),"Each merge undoes");
        check(Arrays.equals(original,DrawingBook.encode(doc)),"Undoing every merge restores all four layers exactly");
    }
    private static void near(int[] wanted,int[] actual,int allowed,String what) {
        int worst=0;
        for(int i=0;i<wanted.length;i++) worst=Math.max(worst,Math.abs(wanted[i]-actual[i]));
        check(worst<=allowed,what+" changed the picture by "+worst+" levels, more than "+allowed);
    }
    private static void mergeRefusals() throws Exception {
        ToneDocument doc=new ToneDocument(8,8); paint(doc,2,2,20); doc.addLayer(); paint(doc,2,2,200); doc.addLayer(); paint(doc,3,3,90);
        check(doc.mergeBlocker()==null,"A visible layer over a visible layer can merge");
        doc.selectLayer(0); byte[] state=DrawingBook.encode(doc);
        check("There is no layer below to merge into.".equals(doc.mergeBlocker()),"The panel is told why the bottom layer cannot merge");
        check(refuse(doc,"nothing lay below").equals("There is no layer below to merge into."),"Bottom layer message");
        check(Arrays.equals(state,DrawingBook.encode(doc)),"The bottom layer is left alone");
        doc.selectLayer(2); doc.setLayerVisible(2,false);
        check("Show both layers before merging them.".equals(doc.mergeBlocker()),"The panel is told to show a hidden layer first");
        check(refuse(doc,"the upper layer was hidden").equals("Show both layers before merging them."),"Hidden layer message");
        check(doc.layerCount()==3&&!doc.layerVisible(2),"A hidden upper layer stays put");
        check(doc.undo()&&doc.layerVisible(2),"A refused merge adds nothing to undo");
        doc.setLayerVisible(1,false);
        refuse(doc,"the lower layer was hidden"); check(doc.layerCount()==3&&!doc.layerVisible(1),"A hidden lower layer stays put");
        check(doc.undo()&&doc.layerVisible(1),"A refused merge adds nothing to undo");
        doc.begin(); refuse(doc,"a stroke was in progress"); doc.cancel();
        check(doc.layerCount()==3,"A stroke in progress blocks the merge");
    }
    private static void limit() throws Exception {
        check(ToneDocument.MAX_LAYERS==10,"A page holds up to ten layers");
        ToneDocument doc=new ToneDocument(16,4);
        while(doc.layerCount()<10) { doc.addLayer(); paint(doc,doc.layerCount()-1,0,doc.layerCount()*20); }
        check(doc.layerCount()==10&&doc.activeLayer()==9,"Ten layers fit");
        try {doc.addLayer(); throw new AssertionError("Eleventh layer accepted");} catch(IllegalStateException error) { check(error.getMessage().contains("10"),"The limit message names ten"); }
        byte[] full=DrawingBook.encode(doc);
        ToneDocument loaded=DocumentCodec.read(new ByteArrayInputStream(full));
        check(loaded.layerCount()==10&&Arrays.equals(full,DrawingBook.encode(loaded)),"A ten-layer page saves and loads whole");
        DocumentCodec.validate(new ByteArrayInputStream(full),16,4);
        // The constructor behind decoding enforces the same limit.
        java.util.ArrayList<ToneDocument.Layer> many=new java.util.ArrayList<>();
        for(int n=0;n<11;n++) many.add(new ToneDocument.Layer("L"+n,true,new byte[64],new byte[64]));
        try { new ToneDocument(8,8,many,0); throw new AssertionError("Eleven layers accepted"); } catch(IllegalArgumentException expected) {}
        many.remove(10); check(new ToneDocument(8,8,many,0).layerCount()==10,"Ten layers load");
        // Merging frees a slot, and undoing it takes the slot back.
        doc.mergeDown(); check(doc.layerCount()==9,"Merging frees a slot");
        doc.addLayer(); check(doc.layerCount()==10,"The slot can be used again");
        for(int i=0;i<2;i++) check(doc.undo(),"Undo steps back");
        check(doc.layerCount()==10&&Arrays.equals(full,DrawingBook.encode(doc)),"Undoing the merge restores all ten layers");
        try {doc.addLayer(); throw new AssertionError("Eleventh layer accepted after undo");} catch(IllegalStateException expected) {}
        // A page of ten layers survives the page cache like any other.
        DrawingBook book=new DrawingBook(doc); byte[] expected=doc.snapshot(); book.addPage(); book.addPage(); book.addPage(); book.select(0);
        check(book.current().layerCount()==10&&Arrays.equals(expected,book.current().snapshot()),"Evicted ten-layer pages keep all layers");
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
        for(int count:new int[]{0,ToneDocument.MAX_LAYERS+1,Integer.MAX_VALUE}) {
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
