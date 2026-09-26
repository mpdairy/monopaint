package dev.tilesmile.supernote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class FlatWashChecks {
    public static void main(String[] args) throws Exception {
        tonesAndHistory(); presetsAndMigration();
        System.out.println("PASS: flat wash gray coverage, clipping, transparent gaps, repeated passes, undo/redo, saved tones and preset migration");
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    private static void tonesAndHistory() throws Exception {
        int width=19, height=17, stride=25;
        byte[] original=new byte[width*height];
        for(int i=0;i<original.length;i++) original[i]=(byte)(i%256);
        int[] mask=new int[stride*21];
        for(int i=0;i<mask.length;i++) mask[i]=i%5==0?0:0xff000000;
        for(int gray=0;gray<256;gray++) {
            ToneDocument doc=new ToneDocument(width,height,original);
            doc.begin();doc.flatWashMask(mask,stride,-3,-2,23,21,gray);
            boolean changed=doc.finish(); byte[] painted=doc.snapshot();
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                int base=original[y*width+x]&255;
                int expected=mask[(y+2)*stride+x+3]==0?base:Math.min(gray,base);
                check(doc.tone(x,y)==expected,"Flat wash retains true grays and darker marks within the clipped mask");
            }
            check(changed==(gray<255),"White is a no-op");
            doc.begin();doc.flatWashMask(mask,stride,-3,-2,23,21,gray);
            check(!doc.finish()&&Arrays.equals(painted,doc.snapshot()),"A new stroke at the same shade does not deepen the wash");
            doc.begin();doc.flatWashMask(mask,stride,-3,-2,23,21,Math.min(255,gray+30));
            check(!doc.finish(),"A lighter wash cannot lighten existing marks");
            if(changed) {
                check(doc.undo()&&Arrays.equals(original,doc.snapshot()),"One undo restores all underlying tones");
                check(!doc.canUndo(),"Repeated passes add no undo entries");
                check(doc.redo()&&Arrays.equals(painted,doc.snapshot()),"Redo restores exact gray tones");
            }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DocumentCodec.write(bytes,doc.width,doc.height,painted);
            check(Arrays.equals(painted,DocumentCodec.read(new ByteArrayInputStream(bytes.toByteArray())).snapshot()),"Saving preserves gray wash tones");
        }
        ToneDocument paper=new ToneDocument(8,8);int[] solid=new int[64];Arrays.fill(solid,0xff000000);
        paper.begin();paper.flatWashMask(solid,8,0,0,8,8,170);paper.finish();
        for(byte tone:paper.snapshot()) check((tone&255)==170,"Wash on paper stores a uniform intermediate gray");
        int[] display=new int[64];paper.render(display,0,0,8,8);
        for(int y=0;y<8;y++) for(int x=0;x<8;x++) check(display[y*8+x]==DotPattern.pixel(170,x,y),"Screen dots are derived from stored gray");
        paper.begin();paper.flatWashMask(solid,8,0,0,8,8,80);paper.finish();
        for(byte tone:paper.snapshot()) check((tone&255)==80,"Choosing a darker wash deepens the tone");
    }
    private static void presetsAndMigration() throws Exception {
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.FLAT_WASH);
        for(ToolSettings.Head head:ToolSettings.Head.values()) {
            library.selectHead(head);library.edit(library.current().size(91).minimum(12).angle(43).bristles(67).tilt(true).pressureResponse(81));
            ToolSettings settings=library.current();ToolLibrary.Preset preset=library.add();
            library=ToolLibrary.decode(library.encode());
            check(library.current().equals(settings)&&library.activeId().equals(preset.id),"Flat wash head and custom settings round trip");
            check(library.builtin(ToolSettings.Tool.WATERCOLOR).equals(ToolSettings.defaults(ToolSettings.Tool.WATERCOLOR)),"Watercolor settings stay independent");
        }
        // The preceding seven-tool format must remain readable, with all head memories intact.
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x5453503a);
        for(int i=0;i<7;i++) writeOld(out,ToolSettings.defaults(ToolSettings.Tool.values()[i]));
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR,ToolSettings.Tool.WET_WATERCOLOR})
            for(ToolSettings.Head head:ToolSettings.Head.values()) writeOld(out,ToolSettings.defaults(tool).head(head));
        ToolSettings old=ToolSettings.defaults(ToolSettings.Tool.WET_WATERCOLOR).head(ToolSettings.Head.FILBERT).bristles(82).size(91);
        String id="dbe9e155-8b1f-4915-bfd0-0328d098cc16";
        writeOld(out,old);out.writeUTF(id);out.writeInt(1);out.writeUTF(id);out.writeUTF("Existing wet brush");writeOld(out,old);out.flush();
        library=ToolLibrary.decode(bytes.toByteArray());
        check(library.current().equals(old.automaticHead())&&library.activeId().equals(id),"Previous format retains selected custom wet brush");
        check(library.builtin(ToolSettings.Tool.FLAT_WASH).equals(ToolSettings.defaults(ToolSettings.Tool.FLAT_WASH)),"Previous format gains a separate default flat wash");
        library=ToolLibrary.decode(library.encode());
        check(library.current().equals(old.automaticHead())&&library.presets().get(0).id.equals(id),"Migrated settings survive saving in the new format");
    }
    private static void writeOld(DataOutputStream out, ToolSettings s) throws IOException {
        out.writeByte(s.tool.ordinal());out.writeInt(s.maximum);out.writeInt(s.tip);out.writeInt(s.softness);out.writeBoolean(s.tilt);
        out.writeInt(s.hardness);out.writeInt(s.minimum);out.writeInt(s.tolerance);out.writeInt(s.strength);out.writeInt(s.pressureResponse);
        out.writeByte(s.head.ordinal());out.writeInt(s.angle);out.writeInt(s.bristles);
    }
}
