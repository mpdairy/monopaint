package dev.tilesmile.supernote;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

public final class BristleChecks {
    public static void main(String[] args) throws Exception {
        direction(); masks(); tiltDirection(); settings();
        System.out.println("PASS: signed push/pull gating, seeded bristle gaps, patch stability, preset persistence and TSP8 migration");
    }
    private static void direction() {
        check(BrushDirection.resolve(0,60,0,17)==0,"Downward lean gives a horizontal broad edge");
        check(BrushDirection.resolve(60,0,0,17)==90,"Rightward lean gives a vertical broad edge");
        check(BrushDirection.againstLean(0,60,0,-10)==1,"Upward motion against downward lean splays bristles");
        check(BrushDirection.againstLean(0,60,0,10)==0,"Pulling with lean does not splay");
        check(BrushDirection.againstLean(0,60,10,0)==0,"Sideways motion does not splay");
        check(BrushDirection.againstLean(0,-60,0,10)==1,"Opposite lean reverses push direction");
        check(BrushDirection.againstLean(-60,0,10,0)==1,"Horizontal signed lean is preserved");
        float diagonal=BrushDirection.againstLean(0,60,10,-10);
        check(diagonal>.7f&&diagonal<.71f,"Diagonal pushes blend smoothly");
        for(float[] v:new float[][]{{0,0,0,-1},{1,-1,0,1},{0,60,0,0},{91,0,1,0},{Float.NaN,60,1,0},{0,60,Float.NaN,0}})
            check(BrushDirection.againstLean(v[0],v[1],v[2],v[3])==0,"Upright, stationary or malformed samples cannot invent a push");
    }
    private static void masks() {
        int[] base=new int[128*128];Arrays.fill(base,0xff000000);
        for(int i=0;i<base.length;i+=11)base[i]=0;
        int[] none=base.clone();BristleTexture.apply(none,128,-7,-3,128,128,48,0,0,123);
        check(Arrays.equals(base,none),"Zero texture is byte-exact clean mask");
        int[] rough=base.clone();BristleTexture.apply(rough,128,-7,-3,128,128,48,0,1,123);
        int gaps=0,marks=0;
        for(int i=0;i<rough.length;i++) {
            check(rough[i]==0||rough[i]==base[i],"Texture only removes mask coverage; it never paints white or expands bounds");
            if(base[i]!=0&&rough[i]==0)gaps++;
            if(rough[i]!=0)marks++;
        }
        check(gaps>2000&&marks>2000,"Rough texture has substantial gaps and substantial tufts");
        int[] repeat=rough.clone();BristleTexture.apply(repeat,128,-7,-3,128,128,48,0,1,123);
        check(Arrays.equals(rough,repeat),"Repeated stamps cannot fill or change the seeded gaps");
        int[] different=base.clone();BristleTexture.apply(different,128,-7,-3,128,128,48,0,1,456);
        check(!Arrays.equals(rough,different),"Different strokes can have different bristle patterns");
        int[] patch=new int[19*17];
        for(int y=0;y<17;y++)for(int x=0;x<19;x++)patch[y*19+x]=base[(y+23)*128+x+41];
        BristleTexture.apply(patch,19,34,20,19,17,48,0,1,123);
        for(int y=0;y<17;y++)for(int x=0;x<19;x++)check(patch[y*19+x]==rough[(y+23)*128+x+41],"Separate raster patches agree at the same paper coordinates");
    }
    private static void tiltDirection() {
        for(int angle:new int[]{0,90}) {
            int[] mask=new int[192*192];Arrays.fill(mask,0xff000000);
            BristleTexture.apply(mask,192,0,0,192,192,48,angle,.8f,321);
            int horizontal=0,vertical=0;
            for(int y=1;y<191;y++)for(int x=1;x<191;x++) {
                if(mask[y*192+x]!=mask[y*192+x+1])horizontal++;
                if(mask[y*192+x]!=mask[(y+1)*192+x])vertical++;
            }
            check(angle==0?horizontal>vertical*1.5:vertical>horizontal*1.5,"Bristle streaks rotate with the tilt-controlled head");
        }
    }
    private static void settings() throws Exception {
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.WATERCOLOR);library.selectHead(ToolSettings.Head.FILBERT);
        library.edit(library.current().bristles(78).tilt(true).angle(23));ToolSettings original=library.current();
        ToolLibrary.Preset preset=library.add();
        library.selectHead(ToolSettings.Head.FLAT);check(library.current().bristles==0,"Other heads stay clean by default");
        library.selectHead(ToolSettings.Head.FILBERT);check(library.current().equals(original),"Returning to a head recalls its texture");
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.current().equals(original)&&restored.activeId().equals(preset.id),"Custom texture survives restart");
        ToolSettings edited=original.size(100).minimum(7).options(11,true,false).hardness(20).softness(30).tolerance(40).strength(50)
                .pressureResponse(60).angle(75).head(ToolSettings.Head.FLAT).tilt(true);
        check(edited.bristles==78&&original.maximum==64,"Every immutable settings edit retains texture");
        for(int invalid:new int[]{-1,101})try{original.bristles(invalid);throw new AssertionError("Invalid texture accepted");}catch(IllegalArgumentException expected){}

        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(0x54535038);
        for(int tool=0;tool<6;tool++)writeOld(out,ToolSettings.defaults(ToolSettings.Tool.values()[tool]).angle(23));
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR})
            for(ToolSettings.Head head:ToolSettings.Head.values())writeOld(out,ToolSettings.defaults(tool).head(head).angle(23));
        ToolSettings old=ToolSettings.defaults(ToolSettings.Tool.WATERCOLOR).head(ToolSettings.Head.FLAT).tilt(true).size(91).angle(23);
        writeOld(out,old);out.writeUTF(preset.id);out.writeInt(1);out.writeUTF(preset.id);out.writeUTF("Old flat");writeOld(out,old);out.flush();
        ToolLibrary migrated=ToolLibrary.decode(bytes.toByteArray());
        check(migrated.current().equals(old.automaticHead())&&migrated.current().bristles==0&&migrated.activeId().equals(preset.id),"TSP8 preserves shape/custom identity with automatic tilt and no angle offset");
        check(ToolLibrary.decode(migrated.encode()).current().equals(old.automaticHead()),"Migrated texture defaults survive another save");
    }
    private static void writeOld(DataOutputStream out,ToolSettings s) throws Exception {
        out.writeByte(s.tool.ordinal());out.writeInt(s.maximum);out.writeInt(s.tip);out.writeInt(s.softness);out.writeBoolean(s.tilt);
        out.writeInt(s.hardness);out.writeInt(s.minimum);out.writeInt(s.tolerance);out.writeInt(s.strength);out.writeInt(s.pressureResponse);
        out.writeByte(s.head.ordinal());out.writeInt(s.angle);
    }
    private static void check(boolean pass,String message) { if(!pass)throw new AssertionError(message); }
}
