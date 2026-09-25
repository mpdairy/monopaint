package dev.tilesmile.supernote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Dependency-free host checks: run with scripts/test_document.sh. */
public final class DocumentChecks {
    public static void main(String[] args) throws Exception {
        calibration(); transactions(); maskAndClipping(); clearCanvas(); persistence();
        System.out.println("PASS: calibration, coordinate anchoring, opaque logical tones, gesture undo/redo, clipping, codec and damaged-file rejection");
    }
    private static void calibration() {
        int[] accepted = {0,4,6,7,10,13,16,17,22,27,32,35,38,42,50,64};
        int previous = -1;
        for (int gray = 0; gray < 256; gray++) {
            int whites = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int pixel = DotPattern.pixel(gray, x, y);
                check(pixel == 0xffffffff || pixel == 0xff000000, "Only opaque black/white");
                if (pixel == 0xffffffff) whites++;
                check(pixel == DotPattern.pixel(gray, x + 24, y + 56), "Stable tile phase");
            }
            check(whites >= previous, "Monotonic intermediate tones"); previous = whites;
        }
        for (int i = 0; i < accepted.length; i++) check(DotPattern.whiteCount(GrayPalette.VALUES[i]) == accepted[i], "0.12 density " + i);
        boolean[] densities = new boolean[65];
        for (int position = 0; position < 256; position++) {
            int tone = DotPattern.pickerTone(position), density = DotPattern.whiteCount(tone);
            densities[density] = true;
            check(DotPattern.whiteCount(DotPattern.pickerTone(DotPattern.pickerPosition(tone))) == density, "Picker round trip");
        }
        for (boolean reachable : densities) check(reachable, "Every density reachable, including light end");
        int last=-1,distinct=0;
        for(int gray=0;gray<256;gray++) {
            int exported=DotPattern.exportGray(gray);
            check(exported>=last&&exported>=0&&exported<=255,"Smooth export is monotonic and bounded");
            check(Math.abs(exported-DotPattern.whiteCount(gray)*255f/64)<=2.5f,"Export follows calibrated coverage without density rounding");
            if(exported!=last)distinct++;last=exported;
        }
        check(distinct>65,"Smooth export avoids quantizing to 65 dot densities");
        check(DotPattern.exportGray(0)==0&&DotPattern.exportGray(255)==255,"Export preserves pure black/white");
        for(int i=0;i<accepted.length;i++)check(DotPattern.exportGray(GrayPalette.VALUES[i])==Math.round(accepted[i]*255f/64),"Export matches calibration anchor "+i);
    }
    private static void transactions() {
        ToneDocument doc = new ToneDocument(137, 91);
        doc.begin();
        for (int y = 0; y < doc.height; y++) for (int x = 0; x < doc.width; x++) doc.setTone(x, y, (x + y) & 255);
        check(doc.finish(), "Edit captured"); byte[] painted = doc.snapshot();
        check(doc.undo(), "Whole stroke undo");
        for (byte value : doc.snapshot()) check((value & 255) == 255, "Whole stroke restores paper");
        check(!doc.undo(), "One action for many writes");
        check(doc.redo() && Arrays.equals(painted, doc.snapshot()), "Exact redo including partial edge tiles");
        doc.undo(); doc.begin(); doc.setTone(0, 0, 255);
        check(!doc.finish() && doc.canRedo(), "No-op does not discard redo");
        doc.begin(); doc.setTone(0, 0, 80); doc.finish(); check(!doc.canRedo(), "Changed branch discards redo");
        check(doc.tone(0, 0) == 80, "Keep logical shade, not binary dots");
        int[] whole = new int[doc.width * doc.height], patch = new int[17 * 11];
        doc.render(whole, 0, 0, doc.width, doc.height); doc.render(patch, 3, 5, 17, 11);
        for (int y = 0; y < 11; y++) for (int x = 0; x < 17; x++)
            check(patch[y * 17 + x] == whole[(y + 5) * doc.width + x + 3], "Dirty render anchors to document");
        doc.clearDirty(); check(doc.dirty() == null, "Dirty reset");
    }
    private static void maskAndClipping() {
        ToneDocument doc = new ToneDocument(4, 4);
        doc.begin();
        doc.paintMask(new int[]{0, 0xff000000, 0xffffffff, 0}, 2, -1, -1, 2, 2, 80);
        check(!doc.finish(), "Off-canvas and transparent pixels ignored");
        doc.begin(); doc.paintMask(new int[]{0xff000000, 0, 0xff000000, 0xff000000}, 2, 0, 0, 2, 2, 80); doc.finish();
        check(doc.tone(0,0) == 80 && doc.tone(1,0) == 255, "Opaque mask");
        doc.begin(); doc.setTone(0,0,255); doc.finish();
        check(doc.tone(0,0) == 255 && doc.tone(0,1) == 80, "White overpainting retains neighbors");
    }
    private static void clearCanvas() {
        ToneDocument doc=new ToneDocument(137,95);
        doc.begin();doc.setTone(0,0,0);doc.setTone(70,55,137);doc.setTone(136,94,249);doc.finish();
        byte[] painted=doc.snapshot();check(doc.clear(),"Clear changes a marked canvas");
        for(byte tone:doc.snapshot())check((tone&255)==255,"Clear includes disconnected marks and partial tiles");
        check(doc.undo()&&Arrays.equals(painted,doc.snapshot()),"Clear is one exact undo");
        check(doc.redo(),"Clear redoes");check(!doc.clear(),"Clear on blank paper is a no-op");
        check(doc.undo()&&Arrays.equals(painted,doc.snapshot()),"No-op clear leaves history intact");
    }
    private static void persistence() throws Exception {
        byte[] tones = new byte[256 * 3];
        for (int i = 0; i < tones.length; i++) tones[i] = (byte)i;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DocumentCodec.write(output, 256, 3, tones); byte[] file = output.toByteArray();
        ToneDocument restored = DocumentCodec.read(new ByteArrayInputStream(file));
        check(restored.width == 256 && restored.height == 3 && Arrays.equals(tones, restored.snapshot()), "Every tone round trips");
        for (int length : new int[]{0, 3, 19, file.length - 3}) rejects(Arrays.copyOf(file, length));
        byte[] broken = file.clone(); broken[19] ^= 1; rejects(broken);
        broken = file.clone(); broken[4] = 0x7f; rejects(broken);
        broken = file.clone(); broken[0] = 0; rejects(broken);
    }
    private static void rejects(byte[] file) throws Exception {
        try { DocumentCodec.read(new ByteArrayInputStream(file)); throw new AssertionError("Accepted damaged drawing"); }
        catch (IOException expected) { /* required */ }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
