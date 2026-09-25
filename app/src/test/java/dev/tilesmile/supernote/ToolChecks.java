package dev.tilesmile.supernote;

import java.io.IOException;
import java.util.Arrays;

public final class ToolChecks {
    public static void main(String[] args) throws Exception {
        presets(); customEdits(); presetDragOrder(); legacyPresets(); pressureResponse(); sizeRanges(); fills(); tolerantFills(); softErase(); soften(); directionalBlend(); stumpStrength(); pencil();
        System.out.println("PASS: preset CRUD/order/recall/persistence, bounded four-connected fill/cancel, soft eraser falloff, unbiased soften/edges, logical pencil texture/cap");
    }
    private static void presetDragOrder() throws Exception {
        ToolLibrary library = new ToolLibrary();
        ToolLibrary.Preset a = library.add("A"), b = library.add("B"), c = library.add("C"), d = library.add("D");
        library.recall(b.id);
        ToolSettings settings = library.current();
        library.moveBefore(a.id, d.id);
        check(presetIds(library).equals(b.id+c.id+a.id+d.id), "Dragging downward inserts without swapping other presets");
        library.moveBefore(d.id, b.id);
        check(presetIds(library).equals(d.id+b.id+c.id+a.id), "Dragging upward preserves intervening order");
        library.moveBefore(d.id, null);
        check(presetIds(library).equals(b.id+c.id+a.id+d.id), "Drop after last preset appends");
        library.moveBefore(c.id, c.id);
        check(presetIds(library).equals(b.id+c.id+a.id+d.id), "Drop on itself keeps order");
        try { library.moveBefore(a.id, "missing"); throw new AssertionError("Missing drop target accepted"); }
        catch (IllegalArgumentException expected) {}
        check(presetIds(library).equals(b.id+c.id+a.id+d.id), "Invalid target does not lose the moved preset");
        ToolLibrary restored = ToolLibrary.decode(library.encode());
        check(presetIds(restored).equals(presetIds(library)), "Dragged order survives restart");
        check(restored.activeId().equals(b.id) && restored.current().equals(settings), "Reordering retains active tool and settings");
        ToolLibrary single = new ToolLibrary(); ToolLibrary.Preset only = single.add("Only");
        single.moveBefore(only.id, null);
        check(single.presets().size()==1 && single.presets().get(0)==only, "Single preset remains intact");
    }
    private static String presetIds(ToolLibrary library) {
        StringBuilder ids = new StringBuilder();
        for (ToolLibrary.Preset preset : library.presets()) ids.append(preset.id);
        return ids.toString();
    }
    private static void presets() throws Exception {
        ToolLibrary library=new ToolLibrary(); library.edit(library.current().size(128).minimum(13).hardness(91).softness(37));
        ToolLibrary.Preset broad=library.add("Broad brush");
        library.select(ToolSettings.Tool.BRUSH);
        library.edit(library.current().size(4));
        check(library.activeId().isEmpty() && library.presets().get(0).settings.maximum==128,"Built-in edits leave custom tools unchanged");
        ToolLibrary.Preset fine=library.add("Fine brush"); library.recall(broad.id);
        check(library.current().maximum==128,"One-tap recall");
        check(library.current().hardness==91&&library.current().softness==37,"Preset recalls hardness and softness");
        check(library.current().minimum==13,"Preset recalls minimum diameter");
        library.select(ToolSettings.Tool.ERASER); library.edit(library.current().options(3,true,false).size(32));
        ToolLibrary.Preset erase=library.add("Soft erase");
        library.select(ToolSettings.Tool.BRUSH); check(library.current().maximum==128,"Each built-in retains its last settings");
        library.recall(erase.id); check(library.current().soft,"Soft setting recalled");
        library.rename(erase.id,"Feather"); library.move(erase.id,-1);
        check(library.presets().get(1).id.equals(erase.id),"Order changed");
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.presets().get(0).settings.hardness==91&&restored.presets().get(0).settings.softness==37,"New settings persist exactly");
        check(restored.presets().get(0).settings.minimum==13,"Minimum size persists exactly");
        check(restored.presets().get(1).name.equals("Feather") && restored.current().equals(library.current()) && restored.activeId().equals(erase.id),"Presets survive restart");
        restored.edit(restored.current().size(16));
        check(restored.presets().get(1).settings.maximum==16,"Custom edits save automatically");
        restored.remove(fine.id); check(restored.presets().size()==2,"Remove preset");
        byte[] data=restored.encode();
        for(int length:new int[]{0,5,data.length-1}) {
            try { ToolLibrary.decode(Arrays.copyOf(data,length)); throw new AssertionError("Truncated presets accepted"); }
            catch(IOException expected) {}
        }
    }
    private static void customEdits() throws Exception {
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
            ToolLibrary library=new ToolLibrary();library.select(tool);
            ToolLibrary.Preset first=library.add(), second=library.add();
            check(!first.id.equals(second.id),"Tools with the same automatic label have distinct identities");
            library.recall(first.id);
            ToolSettings original=library.current();
            library.edit(original.size(96).minimum(7));
            library.edit(library.current().options(11,true,false).hardness(82).softness(23).tolerance(41).strength(67));
            ToolSettings edited=library.current();
            check(library.activeId().equals(first.id),"Repeated custom edits keep the selected identity");
            check(library.presets().size()==2 && library.presets().get(0).settings.equals(edited)
                    && library.presets().get(1).settings.equals(original),"Only the selected custom tool changes, without duplication or reordering");
            check(first.settings.equals(original),"In-flight immutable settings remain unchanged");
            library.select(tool);library.edit(library.current().size(32));library.recall(first.id);
            check(library.current().equals(edited),"Custom edits survive switching to and editing a built-in");
            ToolLibrary restored=ToolLibrary.decode(library.encode());
            check(restored.activeId().equals(first.id)&&restored.current().equals(edited)
                    &&restored.presets().get(0).settings.equals(edited),"All custom settings survive restart");
            restored.remove(first.id);
            check(restored.activeId().isEmpty()&&restored.current().equals(edited),"Deleting the selected custom tool retains usable settings");
            restored.edit(restored.current().size(24));
            restored=ToolLibrary.decode(restored.encode());
            check(restored.presets().size()==1&&restored.presets().get(0).id.equals(second.id)
                    &&restored.presets().get(0).settings.equals(original),"Deletion persists without affecting another custom tool");
        }
        System.out.println("PASS: nameless custom creation, automatic edits for every tool, selection/identity, recall, restart and deletion");
    }
    private static void legacyPresets() throws Exception {
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        out.writeInt(0x54535031);
        for(int tool=0;tool<5;tool++)legacySetting(out,tool);
        legacySetting(out,3);String id="dbe9e155-8b1f-4915-bfd0-0328d098cc16";
        out.writeUTF(id);out.writeInt(1);out.writeUTF(id);out.writeUTF("My eraser");legacySetting(out,3);out.flush();
        ToolLibrary migrated=ToolLibrary.decode(bytes.toByteArray());
        check(migrated.activeId().equals(id)&&migrated.presets().get(0).name.equals("My eraser")
                &&migrated.current().maximum==47&&migrated.current().softness==70&&migrated.current().hardness==40,"Legacy preset migrates with identity, size, and gentle defaults");
        check(ToolLibrary.decode(migrated.encode()).current().equals(migrated.current()),"Migrated settings round trip");
    }
    private static void legacySetting(java.io.DataOutputStream out,int tool) throws Exception {
        out.writeByte(tool);out.writeInt(47);out.writeInt(3);out.writeBoolean(false);out.writeBoolean(tool==1);
    }
    private static void pressureResponse() throws Exception {
        ToolSettings original=ToolSettings.defaults(ToolSettings.Tool.BRUSH);
        check(original.pressureResponse==50,"Existing brush feel is the default");
        for(int response=0;response<=100;response++) {
            ToolSettings settings=original.pressureResponse(response);
            float previous=settings.minimum;
            for(int sample=0;sample<=1000;sample++) {
                float pressure=sample/1000f, diameter=settings.diameter(pressure);
                check(diameter>=previous && diameter>=settings.minimum && diameter<=settings.maximum,"Every response grows monotonically within size limits");
                if(response>0) check(diameter<=original.pressureResponse(response-1).diameter(pressure),"Moving toward Firm never broadens a stroke at the same pressure");
                if(response==50) {
                    float p=Math.max(0,Math.min(1,(pressure-.05f)/.40f));
                    check(diameter==original.minimum+(original.maximum-original.minimum)*p*p,"Default exactly preserves legacy width");
                }
                previous=diameter;
            }
            check(settings.diameter(-1)==settings.minimum && Math.abs(settings.diameter(.45f)-settings.maximum)<.0001f
                    && settings.diameter(2)==settings.maximum,"Every response retains reachable thin and full-width endpoints");
            for(float invalid:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY})
                check(settings.diameter(invalid)==settings.minimum,"Malformed pressure remains bounded");
            check(settings.minimum(64).diameter(.2f)==64,"Equal minimum and maximum produce a fixed width");
        }
        ToolSettings firm=original.pressureResponse(100),light=original.pressureResponse(0);
        check(firm.diameter(.2f)<original.diameter(.2f)/2 && light.diameter(.2f)>original.diameter(.2f),"Firm meaningfully improves thin-stroke control and Light broadens sooner");
        check(firm.size(100).minimum(3).options(8,true,true).hardness(70).softness(30).tolerance(20).strength(80).pressureResponse==100,"Other edits retain the selected response");
        check(!firm.equals(original),"Pressure response participates in settings identity");
        for(int invalid:new int[]{-1,101}) {
            try { original.pressureResponse(invalid);throw new AssertionError("Invalid response accepted"); }
            catch(IllegalArgumentException expected) {}
        }
        for(ToolSettings.Tool tool:ToolSettings.Tool.values()) if(tool!=ToolSettings.Tool.BRUSH) {
            ToolSettings settings=ToolSettings.defaults(tool);
            check(settings.diameter(.2f)==settings.pressureResponse(100).diameter(.2f),"Brush response leaves other tools unchanged");
        }
        ToolLibrary library=new ToolLibrary();library.edit(firm);
        ToolLibrary.Preset preset=library.add();library.edit(library.current().pressureResponse(83));
        library.select(ToolSettings.Tool.BRUSH);library.edit(light);library.recall(preset.id);
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.current().pressureResponse==83 && restored.activeId().equals(preset.id)
                && restored.presets().get(0).settings.pressureResponse==83,"Custom response edits survive switching and restart");
        restored.select(ToolSettings.Tool.BRUSH);
        check(restored.current().pressureResponse==83,"Remembered built-in response survives restart");
        // Independently construct each old format with an active brush preset.
        for(int version=1;version<=5;version++) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            out.writeInt(0x54535030+version);
            for(int record=0;record<7;record++) {
                if(record==6) { out.writeUTF(preset.id);out.writeInt(1);out.writeUTF(preset.id);out.writeUTF("Sketch"); }
                int tool=record<5?record:0;
                out.writeByte(tool);out.writeInt(64);out.writeInt(3);
                if(version==1) out.writeBoolean(true);else out.writeInt(70);
                out.writeBoolean(tool==1);
                if(version>=2)out.writeInt(40);
                if(version>=3)out.writeInt(2);
                if(version>=4)out.writeInt(0);
                if(version>=5)out.writeInt(35);
            }
            out.flush();ToolLibrary migrated=ToolLibrary.decode(bytes.toByteArray());
            check(migrated.current().pressureResponse==50 && migrated.current().diameter(.2f)==original.diameter(.2f)
                    && migrated.activeId().equals(preset.id) && migrated.presets().get(0).name.equals("Sketch"),"TSP"+version+" retains brush feel and preset identity");
            check(ToolLibrary.decode(migrated.encode()).current().equals(migrated.current()),"Migrated response round trips");
        }
        System.out.println("PASS: pressure response direction, bounds, legacy feel, preset edits/restart and TSP1–5 migration");
    }
    private static void sizeRanges() throws Exception {
        for(ToolSettings.Tool tool:ToolSettings.Tool.values()) {
            for(int max=2;max<=128;max++)for(int min=1;min<=max;min++) {
                ToolSettings settings=ToolSettings.defaults(tool).size(max).minimum(min);
                check(settings.diameter(0)==min&&settings.diameter(1)==max,"Pressure reaches both configured bounds");
                check(settings.diameter(.2f)>=min&&settings.diameter(.2f)<=max,"Pressure remains within range");
                ToolSettings smaller=settings.size(2);check(smaller.minimum<=2&&smaller.tip<=2,"Reducing maximum keeps minimum and tip valid");
            }
        }
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        out.writeInt(0x54535032);
        for(int tool:new int[]{0,1,2,3,4,1}) {
            out.writeByte(tool);out.writeInt(47);out.writeInt(3);out.writeInt(37);out.writeBoolean(tool==1);out.writeInt(92);
        }
        out.writeUTF("");out.writeInt(0);out.flush();
        ToolLibrary migrated=ToolLibrary.decode(bytes.toByteArray());
        check(migrated.current().minimum==1&&migrated.current().maximum==47&&migrated.current().hardness==92&&migrated.current().softness==37,"TSP2 migration preserves options with sensible minimum");
        migrated.select(ToolSettings.Tool.ERASER);check(migrated.current().minimum==2,"Existing pressure tools retain 2px minimum");
    }
    private static void fills() {
        ToneDocument doc=new ToneDocument(9,9); doc.begin();
        for(int y=2;y<=6;y++) for(int x=2;x<=6;x++) doc.setTone(x,y,x==2||x==6||y==2||y==6?0:182);
        doc.finish(); FloodFill fill=new FloodFill(doc,4,4,249);
        while(!fill.advance(2)) {} check(fill.finish(),"Fill committed");
        check(doc.tone(4,4)==249&&doc.tone(0,0)==255&&doc.tone(2,4)==0,"Fill respects logical dotted-shade boundary");
        doc.undo(); check(doc.tone(4,4)==182,"Fill one undo"); doc.redo(); check(doc.tone(4,4)==249,"Fill exact redo");
        ToneDocument diagonal=new ToneDocument(3,3); diagonal.begin();diagonal.setTone(0,0,80);diagonal.setTone(1,1,80);diagonal.finish();
        fill=new FloodFill(diagonal,0,0,0); while(!fill.advance(1)) {} fill.finish();
        check(diagonal.tone(1,1)==80,"Four-connected fill does not cross diagonal");
        ToneDocument large=new ToneDocument(1920,2560); fill=new FloodFill(large,0,0,128);
        int batches=0; while(!fill.advance(4096)) batches++; fill.finish();
        check(batches>1&&large.tone(1919,2559)==128,"Full Manta canvas fills in bounded batches");
        large.undo(); fill=new FloodFill(large,0,0,128);fill.advance(100);fill.cancel();
        check(large.tone(0,0)==255&&large.canRedo(),"Interrupted fill rolls back and preserves redo");
        java.util.Random random=new java.util.Random(413);
        for(int run=0;run<200;run++) {
            int width=1+random.nextInt(47),height=1+random.nextInt(43);
            byte[] expected=new byte[width*height];
            for(int i=0;i<expected.length;i++) expected[i]=(byte)(random.nextInt(5)==0?0:128);
            ToneDocument fragmented=new ToneDocument(width,height,expected);
            int seed=random.nextInt(expected.length),source=expected[seed]&255;
            java.util.ArrayDeque<Integer> queue=new java.util.ArrayDeque<>();queue.add(seed);expected[seed]=(byte)249;
            while(!queue.isEmpty()) {
                int pixel=queue.remove();int x=pixel%width,y=pixel/width;
                for(int next:new int[]{x>0?pixel-1:-1,x+1<width?pixel+1:-1,y>0?pixel-width:-1,y+1<height?pixel+width:-1}) {
                    if(next>=0&&(expected[next]&255)==source){expected[next]=(byte)249;queue.add(next);}
                }
            }
            fill=new FloodFill(fragmented,seed%width,seed/width,249);
            while(!fill.advance(1+random.nextInt(60))){}fill.finish();
            check(Arrays.equals(expected,fragmented.snapshot()),"Scanline matches independent four-neighbor fill in fragmented regions");
        }
    }
    private static void tolerantFills() throws Exception {
        ToolLibrary presets=new ToolLibrary();presets.select(ToolSettings.Tool.FILL);
        presets.edit(presets.current().tolerance(17));ToolLibrary.Preset preset=presets.add("Pencil fill");
        presets.select(ToolSettings.Tool.FILL);presets.edit(presets.current().tolerance(0));presets.recall(preset.id);
        ToolLibrary reopened=ToolLibrary.decode(presets.encode());
        check(reopened.current().tolerance==17&&reopened.presets().get(0).settings.tolerance==17,"Fill tolerance survives preset recall and restart");
        check(reopened.current().size(42).minimum(4).hardness(10).softness(30).options(5,true,true).tolerance==17,"Other settings preserve tolerance");
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        out.writeInt(0x54535033);
        for(int tool:new int[]{0,1,2,3,4,2}) {
            out.writeByte(tool);out.writeInt(47);out.writeInt(3);out.writeInt(37);out.writeBoolean(tool==1);out.writeInt(92);out.writeInt(2);
        }
        out.writeUTF("");out.writeInt(0);out.flush();
        ToolSettings old=ToolLibrary.decode(bytes.toByteArray()).current();
        check(old.tolerance==0&&old.minimum==2&&old.hardness==92,"TSP3 keeps tool settings and starts with exact fill");
        // A fixed seed range must not walk through a gradient one small step at a time.
        ToneDocument ramp=new ToneDocument(5,1,new byte[]{100,110,120,(byte)130,(byte)140});
        FloodFill fill=new FloodFill(ramp,0,0,100,8);while(!fill.advance(1)){}fill.finish();
        check(ramp.tone(2,0)==100&&ramp.tone(3,0)==130,"Fill crosses same-target seed but stops outside original seed range");
        java.util.Random random=new java.util.Random(915);
        for(int run=0;run<250;run++) {
            int w=1+random.nextInt(40),h=1+random.nextInt(40);byte[] original=new byte[w*h];random.nextBytes(original);
            int seed=random.nextInt(original.length),source=original[seed]&255,percent=run%101,threshold=Math.round(percent*255f/100);
            int target=run%2==0?source:random.nextInt(256);byte[] expected=original.clone();boolean[] seen=new boolean[w*h];
            java.util.ArrayDeque<Integer> queue=new java.util.ArrayDeque<>();queue.add(seed);seen[seed]=true;
            while(!queue.isEmpty()) {
                int pixel=queue.remove(),x=pixel%w,y=pixel/w;expected[pixel]=(byte)target;
                for(int next:new int[]{x>0?pixel-1:-1,x+1<w?pixel+1:-1,y>0?pixel-w:-1,y+1<h?pixel+w:-1}) {
                    if(next>=0&&!seen[next]&&Math.abs((original[next]&255)-source)<=threshold){seen[next]=true;queue.add(next);}
                }
            }
            ToneDocument doc=new ToneDocument(w,h,original);fill=new FloodFill(doc,seed%w,seed/w,target,percent);
            int passes=0;while(!fill.advance(17))check(++passes<w*h*4,"Tolerant fill always terminates");
            boolean changed=fill.finish();check(Arrays.equals(expected,doc.snapshot()),"Tolerant scanline agrees with independent fixed-seed flood fill");
            check(changed==!Arrays.equals(original,expected),"Fill change status includes same-target and no-op seeds");
            if(changed){doc.undo();check(Arrays.equals(original,doc.snapshot()),"One undo restores tolerant fill");doc.redo();check(Arrays.equals(expected,doc.snapshot()),"Tolerant fill redo");}
            fill=new FloodFill(doc,0,0,255,100);fill.advance(1);fill.cancel();
            check(Arrays.equals(expected,doc.snapshot()),"Cancelled tolerant fill rolls back");
        }
        System.out.println("PASS: fill tolerance range/connectivity, same-target seeds, termination, undo/cancel, presets and TSP3 migration");
    }
    private static ToneDocument uniform(int width,int height,int gray) {
        byte[] data=new byte[width*height]; Arrays.fill(data,(byte)gray);return new ToneDocument(width,height,data);
    }
    private static void softErase() {
        ToneDocument doc=uniform(64,64,0); doc.begin();ToneDabs.softErase(doc,32,32,20);doc.finish();
        int center=doc.tone(32,32),edge=doc.tone(49,32);
        check(center>edge&&edge>0&&doc.tone(52,32)==0,"Soft erase feathers and respects radius");
        byte[] first=doc.snapshot();doc.begin();ToneDabs.softErase(doc,32,32,20);doc.finish();
        for(int i=0;i<first.length;i++)check((doc.snapshot()[i]&255)>=(first[i]&255),"Eraser never darkens");
        check(doc.tone(32,32)>center,"Repeated erasing lightens");
        ToneDocument white=uniform(16,16,255);white.begin();ToneDabs.softErase(white,0,0,15);check(!white.finish(),"Erasing white is a no-op");
        ToneDocument crisp=uniform(64,64,0),feathered=uniform(64,64,0);
        crisp.begin();feathered.begin();ToneDabs.erase(crisp,32,32,20,0,1);ToneDabs.erase(feathered,32,32,20,100,1);crisp.finish();feathered.finish();
        check(crisp.tone(32,32)==255&&crisp.tone(49,32)==255&&crisp.tone(52,32)==0,"Zero softness erases fully within a crisp boundary");
        for(byte tone:crisp.snapshot())check((tone&255)==0||(tone&255)==255,"Zero softness leaves no partial gray pixels");
        check(feathered.tone(32,32)<255&&feathered.tone(49,32)<feathered.tone(32,32),"Positive softness retains gradual feathered rubbing");
    }
    private static void soften() {
        ToneDocument doc=uniform(80,80,255);doc.begin();for(int y=0;y<80;y++)for(int x=0;x<40;x++)doc.setTone(x,y,0);doc.finish();
        doc.begin();ToneDabs.soften(doc,40,40,20);doc.finish();
        check(doc.tone(39,40)>0&&doc.tone(40,40)<255,"Soften blends both sides");
        check(doc.tone(39,40)+doc.tone(40,40)==255,"No directional scan bias");
        check(doc.tone(37,40)>0&&doc.tone(39,40)>60,"Soften reaches wider and blends faster than the old 5x5 weak dab");
        int once=doc.tone(37,40);doc.begin();ToneDabs.soften(doc,40,40,20);doc.finish();
        check(doc.tone(37,40)>once,"Repeated passes continue softening");
        check(doc.tone(19,40)==0&&doc.tone(60,40)==255,"Outside footprint unchanged");
        ToneDocument flat=uniform(30,30,137);flat.begin();ToneDabs.soften(flat,0,0,24);check(!flat.finish(),"Canvas edge introduces no foreign tone");
        byte[] original=new byte[64*64],mirror=new byte[64*64];
        for(int y=0;y<64;y++)for(int x=0;x<64;x++){original[y*64+x]=(byte)((x*19+y*7)&255);mirror[y*64+63-x]=original[y*64+x];}
        ToneDocument a=new ToneDocument(64,64,original),b=new ToneDocument(64,64,mirror);
        a.begin();b.begin();ToneDabs.soften(a,32,32,25);ToneDabs.soften(b,32,32,25);a.finish();b.finish();
        for(int y=0;y<64;y++)for(int x=0;x<64;x++)check(a.tone(x,y)==b.tone(63-x,y),"Stable neighborhood is mirror invariant");
    }
    private static void directionalBlend() {
        byte[] boundary=new byte[96*96],mirror=new byte[96*96],rotated=new byte[96*96];
        for(int y=0;y<96;y++)for(int x=0;x<96;x++) {
            boundary[y*96+x]=(byte)(x<48?40:230);
            mirror[y*96+95-x]=boundary[y*96+x];rotated[x*96+y]=boundary[y*96+x];
        }
        ToneDocument forward=new ToneDocument(96,96,boundary),backward=new ToneDocument(96,96,boundary);
        ToneDocument flipped=new ToneDocument(96,96,mirror),vertical=new ToneDocument(96,96,rotated);
        forward.begin();backward.begin();flipped.begin();vertical.begin();
        ToneDabs.soften(forward,48,48,32,1,0,new int[0]);ToneDabs.soften(backward,48,48,32,-1,0,new int[0]);
        ToneDabs.soften(flipped,48,48,32,-1,0,new int[0]);ToneDabs.soften(vertical,48,48,32,0,1,new int[0]);
        forward.finish();backward.finish();flipped.finish();vertical.finish();
        check(forward.tone(52,48)<backward.tone(52,48),"Rightward rubbing carries darker shading into paper ahead");
        check(backward.tone(43,48)>forward.tone(43,48),"Reversing direction carries lighter paper back into shading");
        for(int y=0;y<96;y++)for(int x=0;x<96;x++) {
            check(forward.tone(x,y)==flipped.tone(95-x,y),"Mirroring stroke direction mirrors the blend");
            check(forward.tone(x,y)==vertical.tone(y,x),"Vertical and horizontal rubbing rotate consistently");
            check(forward.tone(x,y)>=40&&forward.tone(x,y)<=230,"Stump introduces no new color extremes");
            if(Math.hypot(x+.5-48,y+.5-48)>=32)check(forward.tone(x,y)==(boundary[y*96+x]&255),"Directional blend stays inside tip footprint");
        }
        for(int sx:new int[]{-1,0,1})for(int sy:new int[]{-1,0,1}) {
            ToneDocument flat=uniform(30,30,137);flat.begin();
            ToneDabs.soften(flat,0,0,64,sx*.7071f,sy*.7071f,new int[0]);
            check(!flat.finish(),"Directional edge clipping preserves flat paper");
        }
        forward.undo();check(Arrays.equals(boundary,forward.snapshot()),"Directional blend undo is exact");
        System.out.println("PASS: directional stump carries existing tones, reverses/rotates consistently, clips and undoes");
    }
    private static void stumpStrength() throws Exception {
        byte[] original=new byte[96*96];
        for(int y=0;y<96;y++)for(int x=0;x<96;x++)original[y*96+x]=(byte)(x<48?0:255);
        long previous=-1;
        for(int strength:new int[]{0,10,35,100}) {
            ToneDocument doc=new ToneDocument(96,96,original);doc.begin();
            ToneDabs.soften(doc,48,48,32,1,0,strength,new int[0]);boolean changed=doc.finish();
            long difference=0;byte[] result=doc.snapshot();
            for(int i=0;i<result.length;i++)difference+=Math.abs((result[i]&255)-(original[i]&255));
            check(difference>previous,"Increasing stump strength increases a matching dab's effect");previous=difference;
            check(changed==(strength>0),"Zero stump strength makes no edit or undo entry");
            if(strength>0){doc.undo();check(Arrays.equals(original,doc.snapshot()),"Strength-controlled blend undoes exactly");}
            if(strength==100) {
                ToneDocument legacy=new ToneDocument(96,96,original);legacy.begin();
                ToneDabs.soften(legacy,48,48,32,1,0,new int[0]);legacy.finish();
                check(Arrays.equals(result,legacy.snapshot()),"100% retains the previous full-power blend");
            }
        }
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.SOFTEN);
        check(library.current().strength==35,"Stump starts at gentler 35% strength");
        library.edit(library.current().strength(18));ToolLibrary.Preset saved=library.add("Gentle stump");
        library.select(ToolSettings.Tool.SOFTEN);library.edit(library.current().strength(77));
        check(library.presets().get(0).settings.strength==18,"Built-in strength edits do not overwrite custom tools");
        library.recall(saved.id);ToolLibrary reopened=ToolLibrary.decode(library.encode());
        check(reopened.current().strength==18&&reopened.presets().get(0).settings.strength==18,"Strength survives preset recall and restart");
        check(reopened.current().size(50).minimum(4).options(5,true,true).hardness(90).softness(10).tolerance(25).strength==18,"Other edits preserve strength");
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        out.writeInt(0x54535034);
        for(int tool:new int[]{0,1,2,3,4,4})writeTsp4(out,tool);
        out.writeUTF(saved.id);out.writeInt(1);out.writeUTF(saved.id);out.writeUTF("Old stump");writeTsp4(out,4);out.flush();
        ToolLibrary old=ToolLibrary.decode(bytes.toByteArray());
        check(old.current().strength==35&&old.current().maximum==80&&old.current().minimum==5&&old.current().tolerance==17
                &&old.activeId().equals(saved.id)&&old.presets().get(0).name.equals("Old stump"),"TSP4 gains gentler strength while keeping settings and preset identity");
        for(int invalid:new int[]{-1,101})try{old.current().strength(invalid);throw new AssertionError("Invalid strength accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: stump strength endpoints, graduated effect, undo, preset persistence and TSP4 migration");
    }
    private static void writeTsp4(java.io.DataOutputStream out,int tool) throws Exception {
        out.writeByte(tool);out.writeInt(80);out.writeInt(7);out.writeInt(37);out.writeBoolean(tool==1);out.writeInt(92);out.writeInt(5);out.writeInt(17);
    }
    private static void pencil() {
        ToneDocument doc=uniform(128,128,255);doc.begin();ToneDabs.pencil(doc,64,64,96,20,.7f,.7f,0);
        byte[] first=doc.snapshot();ToneDabs.pencil(doc,64,64,96,20,.7f,.7f,0);
        check(Arrays.equals(first,doc.snapshot()),"Duplicate pencil samples do not darken the mark");doc.finish();
        int marks=0;boolean intermediate=false;
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)if(doc.tone(x,y)!=255){
            check(Math.hypot(x+.5-64,y+.5-64)<48,"Pencil footprint never exceeds maximum diameter");
            marks++;if(doc.tone(x,y)>0)intermediate=true;
        }
        check(marks>100&&intermediate,"Pencil texture is held as logical tones");
        doc.undo(); for(byte tone:doc.snapshot())check((tone&255)==255,"Pencil stroke undoes exactly");
        ToneDocument soft=uniform(128,128,255),hard=uniform(128,128,255);
        soft.begin();hard.begin();ToneDabs.pencil(soft,64,64,96,20,.7f,.7f,0,0);ToneDabs.pencil(hard,64,64,96,20,.7f,.7f,0,100);soft.finish();hard.finish();
        long softDeposit=0,hardDeposit=0;
        for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
            check(soft.tone(x,y)<=hard.tone(x,y),"Softer pencil never deposits less at matching input");
            softDeposit+=255-soft.tone(x,y);hardDeposit+=255-hard.tone(x,y);
        }
        check(softDeposit>hardDeposit*3&&hardDeposit>0,"Soft pencil deposits substantially more graphite");
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
