package io.github.mpdairy.monopaint;

import java.io.IOException;
import java.util.Arrays;

public final class ToolChecks {
    public static void main(String[] args) throws Exception {
        gradientSettings(); presets(); favoriteIsolation(); favoriteNumbers(); flatHeight(); wideFlat(); customEdits(); presetDragOrder(); legacyPresets(); pressureResponse(); brushHeads(); tiltDirection(); sizeRanges(); fills(); tolerantFills(); softErase(); soften(); directionalBlend(); stumpStrength(); pencil(); brushPenMigration();
        System.out.println("PASS: preset CRUD/order/recall/persistence, brush pen migration, bounded four-connected fill/cancel, soft eraser falloff, unbiased soften/edges, logical pencil texture/cap");
    }
    private static void brushPenMigration() throws Exception {
        // Ten tools before the brush pen, eleven before the wet brush pen.
        brushPenMigration(0x54535041, 10); brushPenMigration(0x54535042, 11);
        beforeOilField(0x54535045, 62); beforeOilField(0x54535046, 66);
        ToolSettings wetPen=ToolSettings.defaults(ToolSettings.Tool.WET_BRUSH_PEN);
        check(wetPen.tool.water && wetPen.tool.flowing && !ToolSettings.Tool.BRUSH_PEN.flowing && wetPen.pull(.45f)==1,"The wet brush pen lays fully fresh flowing water at full pressure");
    }
    /** A format whose records stop after {@code kept} bytes, before the newer oil paint fields. */
    private static void beforeOilField(int version, int kept) throws Exception {
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.BRUSH);library.edit(library.current().oilPaint(true).paintLoad(30));library.add("Oil");
        java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(library.encode()));
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        in.readInt();out.writeInt(version);byte[] record=new byte[kept];int skipped=70-kept;
        for(int i=0;i<25;i++){in.readFully(record);in.skipBytes(skipped);out.write(record);}
        out.writeUTF(in.readUTF());int count=in.readInt();out.writeInt(count);
        for(int i=0;i<count;i++){out.writeUTF(in.readUTF());out.writeUTF(in.readUTF());in.readFully(record);in.skipBytes(skipped);out.write(record);}
        check(in.read()==-1,"Paint load format fixture consumes every byte");
        ToolLibrary restored=ToolLibrary.decode(bytes.toByteArray());
        check(restored.current().equals(library.current()) && restored.current().paintLoad==30 && restored.current().loadingSpeed==ToolSettings.DEFAULT_LOADING_SPEED && restored.current().minimumLoad==ToolSettings.DEFAULT_MINIMUM_LOAD,"Oil brushes keep their paint and gain default loading");
    }
    private static void brushPenMigration(int version, int tools) throws Exception {
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.AIRBRUSH);library.edit(library.current().strength(72));library.add("Spray");
        java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(library.encode()));
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        // Those formats predate the carry and oil paint fields that end each record.
        in.readInt();out.writeInt(version);byte[] record=new byte[54];
        for(int i=0;i<25;i++){in.readFully(record);in.skipBytes(16);if(i<tools || i>=12)out.write(record);}
        out.writeUTF(in.readUTF());int count=in.readInt();out.writeInt(count);
        for(int i=0;i<count;i++){out.writeUTF(in.readUTF());out.writeUTF(in.readUTF());in.readFully(record);in.skipBytes(16);out.write(record);}
        check(in.read()==-1,"Previous format fixture consumes every byte");
        ToolLibrary restored=ToolLibrary.decode(bytes.toByteArray());
        check(restored.current().equals(library.current()) && restored.activeId().equals(library.activeId()),"Previous format retains the selected favorite");
        check(restored.builtin(ToolSettings.Tool.BRUSH_PEN).equals(ToolSettings.defaults(ToolSettings.Tool.BRUSH_PEN)),"Migration adds a default Brush pen");
        restored.select(ToolSettings.Tool.BRUSH_PEN);restored.edit(restored.current().size(40));
        check(ToolLibrary.decode(restored.encode()).builtin(ToolSettings.Tool.BRUSH_PEN).maximum==40,"Brush pen settings persist");
        check(restored.current().carry==0,"Earlier brush pens trade their load quickly");
        restored.edit(restored.current().carry(70));
        check(ToolLibrary.decode(restored.encode()).builtin(ToolSettings.Tool.BRUSH_PEN).carry==70,"Carry persists");
        check(!restored.current().equals(restored.current().carry(0)),"Carry participates in settings identity");
        ToolSettings brush=restored.builtin(ToolSettings.Tool.BRUSH);
        check(brush.paintLoad==ToolSettings.UNLIMITED_PAINT && !Float.isFinite(brush.paintLength()),"Earlier brushes never run out of paint");
        restored.select(ToolSettings.Tool.BRUSH);restored.edit(brush.paintLoad(40));
        check(ToolLibrary.decode(restored.encode()).builtin(ToolSettings.Tool.BRUSH).paintLoad==40 && brush.paintLoad(40).paintLength()==400,"Paint load persists");
        restored.edit(restored.current().loadingSpeed(80));
        check(brush.loadingSpeed==ToolSettings.DEFAULT_LOADING_SPEED && ToolLibrary.decode(restored.encode()).builtin(ToolSettings.Tool.BRUSH).loadingSpeed==80,"Loading speed persists");
        try {brush.paintLoad(0);throw new AssertionError("Empty paint load accepted");}catch(IllegalArgumentException expected) {}
        ToolSettings pen=ToolSettings.defaults(ToolSettings.Tool.BRUSH_PEN);
        try {pen.carry(101);throw new AssertionError("Carry above 100% accepted");}catch(IllegalArgumentException expected) {}
        check(pen.diameter(.1f,0,0)==pen.minimum && pen.diameter(.45f,0,0)==pen.minimum,"An upright brush pen stays at its minimum size at any pressure");
        check(pen.diameter(.1f,70,0)==pen.maximum && pen.diameter(.1f,30,0)>pen.minimum && pen.diameter(.1f,30,0)<pen.maximum,"Leaning widens the brush pen up to its maximum");
        ToolSettings strong=pen.strength(100);
        check(strong.pull(.45f)==1 && strong.pull(0)>0 && strong.pull(.25f)<1,"Pressure sets the brush pen's pull");
        check(pen.pull(.45f)<strong.pull(.45f) && pen.strength(30).pull(.45f)<pen.pull(.45f),"Strength sets the most the brush pen pulls");
        check(strong.pressureResponse(0).pull(.1f)>strong.pull(.1f) && strong.pull(.1f)>strong.pressureResponse(100).pull(.1f),"Lower response pulls more with a light touch");
        check(strong.pressureResponse(50).pull(.25f)==.15f+.85f*.5f,"The middle response is linear");
        check(!pen.supportsEraseMode(),"Clear water has no erase mode");
        check(pen.followsTilt() && pen.leanMinor(pen.maximum,pen.minimum)==pen.minimum+(pen.maximum-pen.minimum)*.28f && pen.leanMinor(pen.minimum,pen.minimum)==pen.minimum,
                "A leaning brush pen stretches along its lean like a pencil: wide sideways, thin along the lean, round upright");
    }
    private static void gradientSettings() throws Exception {
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.FILL);
        check(library.current().gradient==ToolSettings.Gradient.FLAT,"Fill defaults to Flat");
        library.edit(library.current().gradient(ToolSettings.Gradient.CIRCULAR).tolerance(23).size(32).minimum(4));
        ToolSettings circular=library.current();ToolLibrary.Preset favorite=library.add();
        library.edit(library.current().gradient(ToolSettings.Gradient.LINEAR).tolerance(7));
        check(library.builtin(ToolSettings.Tool.FILL).equals(circular),"Favorite type is independent of regular Fill");
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.current().gradient==ToolSettings.Gradient.LINEAR&&restored.current().tolerance==7&&restored.activeId().equals(favorite.id),"Selected favorite survives restart");
        restored.select(ToolSettings.Tool.FILL);
        check(restored.current().equals(circular),"Regular circular type and tolerance survive restart");
        check(!circular.equals(circular.gradient(ToolSettings.Gradient.LINEAR)),"Gradient participates in settings identity");
        ToolLibrary old=ToolLibrary.decode(beforeGradient(library));
        check(old.current().gradient==ToolSettings.Gradient.LINEAR&&old.builtin(ToolSettings.Tool.FILL).gradient==ToolSettings.Gradient.LINEAR,"Previous format defaults to Linear");
        check(old.activeId().equals(favorite.id)&&old.current().tolerance==7&&old.builtin(ToolSettings.Tool.FILL).tolerance==23,"Migration retains favorite identity and both tolerances");
        byte[] invalid=library.encode();invalid[4+47]=(byte)255;
        try {ToolLibrary.decode(invalid);throw new AssertionError("Unknown gradient accepted");}catch(IOException expected) {}
        System.out.println("PASS: gradient type defaults, immutable edits, regular/favorite independence, persistence and pre-gradient migration");
    }
    /** Strip the gradient byte and later fields to construct the actual previous 47-byte wire records. */
    private static byte[] beforeGradient(ToolLibrary library) throws Exception {
        java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(library.encode()));
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        in.readInt();out.writeInt(0x5453503d);byte[] record=new byte[47];
        for(int i=0;i<25;i++){in.readFully(record);if(i<8 || i>11)out.write(record);in.readUnsignedByte();in.skipBytes(22);}
        out.writeUTF(in.readUTF());int count=in.readInt();out.writeInt(count);
        for(int i=0;i<count;i++) {
            out.writeUTF(in.readUTF());out.writeUTF(in.readUTF());in.readFully(record);out.write(record);in.readUnsignedByte();in.skipBytes(22);
        }
        check(in.read()==-1,"Legacy fixture consumes every record");return bytes.toByteArray();
    }
    private static void favoriteIsolation() throws Exception {
        for(ToolSettings.Tool tool:ToolSettings.Tool.values()) {
            ToolLibrary library=new ToolLibrary();library.select(tool);
            if(library.current().isBrush())library.selectHead(ToolSettings.Head.FLAT);
            library.edit(library.current().size(93).minimum(7).pressureResponse(83));
            ToolSettings regular=library.current();ToolLibrary.Preset favorite=library.add();
            library.edit(library.current().size(23).minimum(2).pressureResponse(21));
            ToolSettings custom=library.current();
            check(library.builtin(tool).equals(regular),"Favorite edits cannot overwrite regular settings");
            library.select(tool);check(library.current().equals(regular),"Regular tool retains its own size and response");
            library.recall(favorite.id);check(library.current().equals(custom),"Favorite keeps its own settings");
            library=ToolLibrary.decode(library.encode());
            check(library.current().equals(custom)&&library.builtin(tool).equals(regular),"Both memories survive restart with favorite selected");
            if(regular.isBrush()) {
                library.selectHead(ToolSettings.Head.FILBERT);
                check(library.builtin(tool).equals(regular),"Changing a favorite head cannot change the regular head");
                library.select(tool);library.selectHead(ToolSettings.Head.ROUND);library.selectHead(ToolSettings.Head.FLAT);
                check(library.current().equals(regular),"Regular per-head memory survives favorite changes");
                library.recall(favorite.id);
            }
            library.remove(favorite.id);
            check(library.current().equals(regular),"Deleting a favorite returns to the regular settings");
        }
        System.out.println("PASS: favorites isolate regular tool/head settings through edits, recall, restart and deletion");
    }
    private static void favoriteNumbers() throws Exception {
        ToolLibrary library=new ToolLibrary();library.selectHead(ToolSettings.Head.FLAT);
        ToolLibrary.Preset first=library.add(),second=library.add();
        library.select(ToolSettings.Tool.BRUSH);library.selectHead(ToolSettings.Head.FILBERT);
        ToolLibrary.Preset rounded=library.add();
        library.select(ToolSettings.Tool.WATERCOLOR);library.selectHead(ToolSettings.Head.FLAT);
        ToolLibrary.Preset third=library.add();
        check(library.presetNumber(first.id)==1&&library.presetNumber(second.id)==2&&library.presetNumber(third.id)==3,
                "Matching flat icons count upward across legacy paint modes");
        check(library.presetNumber(rounded.id)==1,"Different head starts its own dot count");
        library.moveBefore(third.id,first.id);
        check(library.presetNumber(third.id)==1&&library.presetNumber(first.id)==2&&library.presetNumber(second.id)==3,"Reordering renumbers in toolbar order");
        library.remove(first.id);library.rename(second.id,"Any name");
        library=ToolLibrary.decode(library.encode());
        check(library.presetNumber(third.id)==1&&library.presetNumber(second.id)==2,"Delete, rename and restart preserve consecutive counts");
        library.recall(second.id);library.selectHead(ToolSettings.Head.FILBERT);
        check(library.presetNumber(second.id)==1&&library.presetNumber(rounded.id)==2,"Changing a head updates both matching groups");
        library.select(ToolSettings.Tool.SHAPES);library.edit(library.current().shape(ToolSettings.Shape.RECTANGLE));
        ToolLibrary.Preset rectangle=library.add();
        library.select(ToolSettings.Tool.SHAPES);library.edit(library.current().shape(ToolSettings.Shape.CIRCLE));
        ToolLibrary.Preset circle=library.add();
        library.select(ToolSettings.Tool.SHAPES);library.edit(library.current().shape(ToolSettings.Shape.RECTANGLE).filled(true));
        ToolLibrary.Preset rectangle2=library.add();
        check(library.presetNumber(rectangle.id)==1&&library.presetNumber(circle.id)==1&&library.presetNumber(rectangle2.id)==2,
                "Dots group matching shape icons regardless of fill mode");
        library.recall(rectangle.id);library.edit(library.current().shape(ToolSettings.Shape.CIRCLE));
        library=ToolLibrary.decode(library.encode());
        check(library.presetNumber(rectangle.id)==1&&library.presetNumber(circle.id)==2&&library.presetNumber(rectangle2.id)==1,
                "Changing saved shape regroups dots and survives restart");
        System.out.println("PASS: favorite dots count matching icons through reorder, removal, head changes and restart");
    }
    private static void flatHeight() throws Exception {
        ToolLibrary library=new ToolLibrary();library.selectHead(ToolSettings.Head.FLAT);
        check(library.current().headThickness==0&&library.current().flatHeight(256)==1,"New Flat starts at the existing fixed 1px height");
        library.edit(library.current().size(256).headThickness(20));ToolSettings regular=library.current();
        ToolLibrary.Preset favorite=library.add();library.edit(library.current().headThickness(7));
        library=ToolLibrary.decode(library.encode());
        check(library.current().headThickness==7&&library.activeId().equals(favorite.id),"Favorite height survives restart");
        check(library.builtin(ToolSettings.Tool.BRUSH).equals(regular),"Favorite height edits preserve regular brush height");
        library.select(ToolSettings.Tool.BRUSH);library.selectHead(ToolSettings.Head.FILBERT);library.selectHead(ToolSettings.Head.FLAT);
        check(library.current().equals(regular),"Regular Flat recalls its height after switching heads");
        for(int value:new int[]{0,1,5,10,20})for(int width:new int[]{1,2,32,128,256}) {
            ToolSettings settings=regular.headThickness(value);
            float height=settings.flatHeight(width);
            check(height>=1&&height<=Math.max(1,width*.2f),"Every height stays within 1px and 20% of current width");
            if(value==0)check(height==1,"Slider start stays exactly 1px at all widths");
            if(value==10)check(Math.abs(height-Math.max(1,width*.1f))<.001f,"Midpoint restores the old 10% proportion");
            if(value==20)check(Math.abs(height-Math.max(1,width*.2f))<.001f,"Slider end reaches twice the old proportion");
            check(settings.minimum(1).pressureResponse(81).asBrush().automaticHead().headThickness==value,"Other edits and automatic tilt preserve Flat height");
        }
        byte[] old=beforeGradient(library);java.nio.ByteBuffer.wrap(old).putInt(0,0x5453503c);
        ToolLibrary migrated=ToolLibrary.decode(old);
        check(migrated.current().headThickness==0&&migrated.presets().get(0).settings.headThickness==0,"Previous fixed-height build migrates regular and favorite Flat to 1px");
        System.out.println("PASS: Flat height range, independent favorite/head memory, restart and fixed-height migration");
    }
    private static void wideFlat() throws Exception {
        ToolLibrary library=new ToolLibrary();library.selectHead(ToolSettings.Head.FLAT);
        library.edit(library.current().size(256).minimum(256));
        ToolLibrary.Preset favorite=library.add();library.edit(library.current().minimum(1));
        library=ToolLibrary.decode(library.encode());
        check(library.current().maximum==256&&library.current().diameter(.05f)==1&&Math.abs(library.current().diameter(.45f)-256)<.001f,
                "Wide Flat keeps full pressure range through persistence");
        library.select(ToolSettings.Tool.BRUSH);check(library.current().minimum==256,"256px fixed-width regular Flat persists independently");
        library.recall(favorite.id);library.selectHead(ToolSettings.Head.FILBERT);
        check(library.current().maximum==128,"Changing a wide favorite to another head respects that head's limit");
        try {library.current().head(ToolSettings.Head.FLAT).size(257);throw new AssertionError("Oversized Flat accepted");}
        catch(IllegalArgumentException expected) {}
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
        library.select(ToolSettings.Tool.BRUSH); check(library.current().maximum==4,"Favorite recall preserves the last built-in settings");
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
            check(restored.activeId().isEmpty()&&restored.current().maximum==32,"Deleting the selected custom tool restores the regular tool");
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
                if(response>0) check(diameter<=original.pressureResponse(response-1).diameter(pressure)+1e-4f,"Moving toward Firm never broadens a stroke at the same pressure");
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
            check(settings.minimum(settings.maximum).diameter(.2f)==settings.maximum,"Equal minimum and maximum produce a fixed width");
        }
        ToolSettings firm=original.pressureResponse(100),light=original.pressureResponse(0);
        check(firm.diameter(.2f)<original.diameter(.2f)/2 && light.diameter(.2f)>original.diameter(.2f),"Firm meaningfully improves thin-stroke control and Light broadens sooner");
        check(firm.size(100).minimum(3).options(8,true,true).hardness(70).softness(30).tolerance(20).strength(80).pressureResponse==100,"Other edits retain the selected response");
        check(!firm.equals(original),"Pressure response participates in settings identity");
        for(int invalid:new int[]{-1,101}) {
            try { original.pressureResponse(invalid);throw new AssertionError("Invalid response accepted"); }
            catch(IllegalArgumentException expected) {}
        }
        for(ToolSettings.Tool tool:ToolSettings.Tool.values()) if(!ToolSettings.defaults(tool).isBrush()) {
            ToolSettings settings=ToolSettings.defaults(tool);
            check(settings.diameter(.2f)==settings.pressureResponse(100).diameter(.2f),"Brush response leaves other tools unchanged");
        }
        for(int response:new int[]{0,50,100})for(float pressure:new float[]{0,.1f,.2f,.3f,.45f,1})
            check(ToolSettings.defaults(ToolSettings.Tool.WATERCOLOR).size(original.maximum).minimum(original.minimum).pressureResponse(response).diameter(pressure)
                    ==original.pressureResponse(response).diameter(pressure),"Watercolor shares brush pressure response");
        ToolLibrary library=new ToolLibrary();library.edit(firm);
        ToolLibrary.Preset preset=library.add();library.edit(library.current().pressureResponse(83));
        library.select(ToolSettings.Tool.BRUSH);library.edit(light);library.recall(preset.id);
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.current().pressureResponse==83 && restored.activeId().equals(preset.id)
                && restored.presets().get(0).settings.pressureResponse==83,"Custom response edits survive switching and restart");
        restored.select(ToolSettings.Tool.BRUSH);
        check(restored.current().pressureResponse==light.pressureResponse,"Remembered built-in response survives favorite recall and restart");
        // Independently construct each old format with an active brush preset.
        for(int version=1;version<=6;version++) {
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
                if(version>=6)out.writeInt(83);
            }
            out.flush();ToolLibrary migrated=ToolLibrary.decode(bytes.toByteArray());
            ToolSettings expected=original.size(64).minimum(2).pressureResponse(version>=6?83:50);
            check(migrated.current().pressureResponse==expected.pressureResponse && migrated.current().diameter(.2f)==expected.diameter(.2f)
                    && migrated.activeId().equals(preset.id) && migrated.presets().get(0).name.equals("Sketch"),"TSP"+version+" retains brush feel and preset identity");
            check(ToolLibrary.decode(migrated.encode()).current().equals(migrated.current()),"Migrated response round trips");
            migrated.select(ToolSettings.Tool.WATERCOLOR);
            check(migrated.current().equals(ToolSettings.defaults(ToolSettings.Tool.WATERCOLOR)),"Old libraries gain default watercolor settings");
        }
        System.out.println("PASS: brush/watercolor pressure response, bounds, legacy feel, preset edits/restart and TSP1–6 migration");
    }
    private static void brushHeads() throws Exception {
        ToolLibrary library=new ToolLibrary();
        ToolSettings[][] expected=new ToolSettings[2][3];int index=0;
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR}) {
            library.select(tool);
            for(ToolSettings.Head head:ToolSettings.Head.values()) {
                library.selectHead(head);
                ToolSettings settings=library.current().size(70+head.ordinal()*10+index).minimum(5+index)
                        .pressureResponse(20+head.ordinal()*30).angle(head.ordinal()*45).headThickness(5+head.ordinal()*20+index);
                library.edit(settings);expected[index][head.ordinal()]=settings.automaticHead();
            }
            index++;
        }
        library=ToolLibrary.decode(library.encode());index=0;
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR}) {
            library.select(tool);
            check(library.current().head==ToolSettings.Head.FILBERT,"Each brush mode remembers its selected head");
            for(ToolSettings.Head head:ToolSettings.Head.values()) {
                library.selectHead(head);
                check(library.current().equals(expected[index][head.ordinal()]),"Each head retains its own settings across switching and restart");
            }
            index++;
        }
        library.selectHead(ToolSettings.Head.FLAT);ToolLibrary.Preset flat=library.add();
        check(flat.name.equals("Flat Watercolor"),"Custom tools identify head and paint mode");
        library.edit(library.current().size(111).angle(127));
        ToolSettings custom=library.current();library.select(ToolSettings.Tool.BRUSH);library.recall(flat.id);
        check(library.current().equals(custom),"Custom tool recalls head, angle and edited size");
        library.selectHead(ToolSettings.Head.FILBERT);
        check(library.activeId().equals(flat.id)&&library.presets().get(0).settings.head==ToolSettings.Head.FILBERT,"Changing a custom head edits the same preset");
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.current().equals(library.current())&&restored.activeId().equals(flat.id),"Custom head changes survive restart");
        ToolSettings original=custom;
        ToolSettings edited=custom.size(60).minimum(4).options(8,true,true).hardness(20).softness(30).tolerance(40).strength(50).pressureResponse(60);
        check(edited.head==original.head&&edited.angle==original.angle&&edited.headThickness==original.headThickness&&original.maximum==111,"All setting edits preserve immutable head, angle and thickness");
        for(int thickness:new int[]{-1,101})try{custom.headThickness(thickness);throw new AssertionError("Invalid thickness accepted");}catch(IllegalArgumentException expectedError){}
        check(!custom.equals(custom.headThickness(99)),"Thickness participates in settings identity");
        for(int angle:new int[]{-1,181})try{custom.angle(angle);throw new AssertionError("Invalid angle accepted");}catch(IllegalArgumentException expectedError){}
        try{ToolSettings.defaults(ToolSettings.Tool.PENCIL).head(ToolSettings.Head.FLAT);throw new AssertionError("Pencil accepted a brush head");}catch(IllegalArgumentException expectedError){}
        // The prior watercolor format stored six built-ins, without head memories.
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        out.writeInt(0x54535037);
        for(int tool:new int[]{0,1,2,3,4,5,5}) {
            writeTsp4(out,tool);out.writeInt(35);out.writeInt(83);
        }
        out.writeUTF("");out.writeInt(0);out.flush();
        ToolLibrary old=ToolLibrary.decode(bytes.toByteArray());ToolSettings round=old.current();
        check(round.head==ToolSettings.Head.ROUND&&round.angle==0&&round.pressureResponse==83,"TSP7 watercolor gains round head without losing settings");
        old.selectHead(ToolSettings.Head.FLAT);old.edit(old.current().size(120));old.selectHead(ToolSettings.Head.ROUND);
        check(old.current().equals(round),"Migrated round settings survive trying another head");
        // TSP11 had all current heads/tools but no thickness field. Strip only
        // that new field from each record to exercise the previous wire format.
        ToolLibrary previous=new ToolLibrary();previous.selectHead(ToolSettings.Head.FLAT);
        previous.edit(previous.current().angle(37).tilt(true).bristles(61).headThickness(80));
        ToolLibrary.Preset legacyPreset=previous.add("Legacy flat");
        byte[] legacy=beforeGradient(previous);
        java.nio.ByteBuffer records=java.nio.ByteBuffer.wrap(legacy);
        records.putInt(0,0x5453503c);
        // Simulate old saved options in built-ins, head memories, current tool,
        // and the custom preset, including values the UI no longer exposes.
        for(int offset=4;offset<4+21*47;offset+=47) {
            if(legacy[offset+34]!=0) {
                records.put(offset+13,(byte)0);records.putInt(offset+35,73);records.putInt(offset+43,80);
            }
        }
        int presetOffset=legacy.length-47;
        records.put(presetOffset+13,(byte)0);records.putInt(presetOffset+35,73);records.putInt(presetOffset+43,80);
        ToolLibrary automatic=ToolLibrary.decode(legacy);
        check(automatic.current().tilt&&automatic.current().angle==0&&automatic.current().headThickness==0,
                "Legacy hidden controls become automatic tilt, zero offset and the existing 1px height");
        check(automatic.activeId().equals(legacyPreset.id)&&automatic.presets().get(0).name.equals("Legacy flat"),
                "Migration preserves custom identity and name");
        automatic.select(ToolSettings.Tool.BRUSH);automatic.selectHead(ToolSettings.Head.FILBERT);automatic.recall(legacyPreset.id);
        check(automatic.current().equals(previous.current().headThickness(0)),"Recalling a legacy custom brush retains size and pressure with the 1px height");
        previous.remove(legacyPreset.id);
        byte[] modern=beforeGradient(previous);bytes.reset();out=new java.io.DataOutputStream(bytes);
        out.writeInt(0x5453503b);
        int cursor=4;
        for(int record=0;record<21;record++){out.write(modern,cursor,43);cursor+=47;}
        out.write(modern,cursor,modern.length-cursor);out.flush();
        ToolLibrary migrated=ToolLibrary.decode(bytes.toByteArray());
        check(migrated.current().equals(previous.current().headThickness(0)),"Old flat brush retains its 1px height while preserving other settings");
        for(ToolSettings.Tool tool:ToolSettings.Tool.values())if(ToolSettings.defaults(tool).isBrush()) {
            migrated.select(tool);
            for(ToolSettings.Head head:ToolSettings.Head.values()) {
                migrated.selectHead(head);check(migrated.current().headThickness==(head==ToolSettings.Head.FILBERT?55:head==ToolSettings.Head.FLAT?0:10),"Migrated filberts gain a full rounded footprint while Flat stays thin");
            }
        }
        System.out.println("PASS: independent brush-head memories, angle validation, custom head editing/recall/restart and TSP7 migration");
    }
    private static void tiltDirection() throws Exception {
        for(float[] point:new float[][]{{60,0,0},{0,60,90},{-60,0,0},{0,-60,90},{45,45,45},{-45,45,135},{45,-45,135},{-45,-45,45}})
            check(Math.abs(BrushDirection.delta(point[2]+90,BrushDirection.resolve(point[0],point[1],0,17)))<.001f,"Broad edge is perpendicular to all signed X/Y lean quadrants");
        check(Math.abs(BrushDirection.resolve(45,30,0,17)-120)<.001f,"Direction projects tangent components rather than raw degree ratios");
        check(BrushDirection.resolve(0,60,45,17)==45,"Angle acts as an offset from the perpendicular head");
        for(float[] point:new float[][]{{0,0},{1,-1},{3,3},{Float.NaN,30},{60,Float.POSITIVE_INFINITY},{91,0},{0,-91}})
            check(BrushDirection.resolve(point[0],point[1],0,73)==73,"Upright and invalid input preserve last direction");
        check(Float.isFinite(BrushDirection.resolve(90,-90,0,0)),"Extreme valid tilt stays finite");
        check(BrushDirection.delta(179,1)==2&&BrushDirection.delta(1,179)==-2&&BrushDirection.delta(0,180)==0,"Symmetric heads turn through shortest angle");
        for(int a=-360;a<=360;a++)for(int b=-180;b<=180;b+=15) {
            float delta=BrushDirection.delta(a,b);
            check(delta>=-90&&delta<90&&Math.abs(BrushDirection.delta(a+delta,b))<.001f,"Angle interpolation never takes the long route");
        }
        ToolLibrary library=new ToolLibrary();
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR}) {
            library.select(tool);library.selectHead(ToolSettings.Head.FLAT);
            check(library.current().tilt&&library.current().angle==0,"Flat automatically follows tilt without an offset");
            library.edit(library.current().tilt(true).angle(37));ToolSettings saved=library.current();
            ToolLibrary.Preset preset=library.add();
            library.select(tool);library.selectHead(ToolSettings.Head.FILBERT);
            check(library.current().tilt&&library.current().angle==0,"Filbert automatically follows tilt without an offset");
            library.selectHead(ToolSettings.Head.FLAT);check(library.current().equals(saved),"Head recalls tilt and offset");
            library.recall(preset.id);library=ToolLibrary.decode(library.encode());
            check(library.current().equals(saved)&&library.activeId().equals(preset.id),"Custom tool persists tilt and offset across restart");
        }
        System.out.println("PASS: signed tilt projection, upright/invalid fallback, symmetric turn interpolation and saved per-head tilt/offset");
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
        ToneDocument white=uniform(16,16,255);white.begin();ToneDabs.softErase(white,0,0,15);check(white.finish() && white.opacity(0,0)<255 && white.tone(0,0)==255,"Erasing white removes coverage while retaining its appearance on paper");
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
