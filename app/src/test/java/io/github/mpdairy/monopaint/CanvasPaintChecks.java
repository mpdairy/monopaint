package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class CanvasPaintChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static int[] mask(int width, int height) {
        int[] result = new int[width * height]; Arrays.fill(result, 0xff000000); return result;
    }
    private static ToneDocument paper(int gray) {
        byte[] base = new byte[96 * 64]; Arrays.fill(base, (byte)gray);
        return new ToneDocument(96, 64, base);
    }
    private static void settle(WetWatercolor wet) {
        int frames = 0;
        while (wet.isAnimating()) { wet.advance(false); check(++frames < 1000, "Bounded animation"); }
    }
    private static void stroke(ToneDocument doc, WetWatercolor wet, int gray, boolean transparent) {
        doc.begin(); wet.beginStroke();
        wet.paintMask(mask(48, 32), 48, 24, 16, 48, 32, gray, transparent);
        wet.finishStroke();
    }
    private static void transparent() {
        for (int base : new int[]{0, 60, 128, 255}) for (int shade : new int[]{0, 64, 128, 230, 255}) {
            ToneDocument doc = paper(base); byte[] before = doc.snapshot();
            int[] footprint = mask(52, 36); footprint[12 * 52 + 12] = 0;
            doc.begin();
            for (int repeat = 0; repeat < 5; repeat++) doc.transparentMask(footprint, 52, -4, -4, 52, 36, shade);
            boolean changed = doc.finish();
            int expected = Math.round(base * (.5f + shade / 510f));
            check(doc.tone(20, 20) == expected && expected <= base, "Multiply once per gesture, including clipped masks");
            check(doc.tone(8, 8) == base && doc.tone(60, 40) == base, "Mask holes and untouched paper are preserved");
            byte[] once = doc.snapshot();
            if (changed) {
                check(doc.undo() && Arrays.equals(before, doc.snapshot()), "Transparent gesture undo");
                check(doc.redo() && Arrays.equals(once, doc.snapshot()), "Transparent gesture redo");
            }
            doc.begin(); doc.transparentMask(footprint, 52, -4, -4, 52, 36, shade); doc.finish();
            check(doc.tone(20, 20) == Math.round(expected * (.5f + shade / 510f)), "Separate strokes build density");
        }
    }
    private static void wetnessAndOpaque() {
        int previous = -1;
        for (int amount : new int[]{0, 25, 65, 100}) {
            ToneDocument doc = paper(180); byte[] before = doc.snapshot();
            WetWatercolor wet = new WetWatercolor(doc, amount);
            stroke(doc, wet, 20, false);
            check(doc.tone(48, 32) == 20, "Normal opaque brush appears immediately on old paint");
            settle(wet); int tone = doc.tone(48, 32);
            check(tone >= 20 && tone < 180 && tone > previous, "More water means more blending with existing canvas");
            if (amount == 0) check(tone == 20, "Zero wetness stays opaque");
            previous = tone;
            byte[] after = doc.snapshot();
            check(doc.undo() && Arrays.equals(before, doc.snapshot()), "Stroke and all wet frames undo together");
            check(doc.redo() && Arrays.equals(after, doc.snapshot()), "Wet result redoes exactly");
        }
        ToneDocument doc = paper(30); WetWatercolor wet = new WetWatercolor(doc, 100);
        stroke(doc, wet, 255, false);
        check(doc.tone(48, 32) == 255, "Opaque white deposits white pigment");
        settle(wet);
        check(doc.tone(48, 32) > 30 && doc.tone(48, 32) < 255, "Opaque white blends with underlying dark paint");
    }
    private static void waterAndHistory() {
        ToneDocument doc = paper(100); WetWatercolor wet = new WetWatercolor(doc, 100);
        stroke(doc, wet, 255, true); settle(wet);
        check(doc.tone(48, 32) == 100 && !doc.canUndo(), "Clear water adds no pigment to uniform paint");
        stroke(doc, wet, 128, true);
        check(doc.tone(48, 32) == 75, "Wet transparent paint adds a translucent multiply glaze");
        settle(wet);
        // Simulate older saved artwork, including an unrelated edit in history.
        doc = paper(255); doc.begin(); doc.paintMask(mask(48, 64), 48, 0, 0, 48, 64, 0); doc.finish();
        byte[] old = doc.snapshot(); wet = new WetWatercolor(doc, 100);
        stroke(doc, wet, 255, true);
        check(Arrays.equals(old, doc.snapshot()), "Clear water has no immediate white deposit");
        settle(wet);
        check(doc.tone(47, 32) > 0 && doc.tone(48, 32) < 255, "Water alone mobilizes existing light/dark boundary");
        check(doc.undo() && Arrays.equals(old, doc.snapshot()), "Initially invisible water owns its animation undo step");
        check(doc.undo() && doc.tone(20, 20) == 255, "Water never merges into unrelated older history");
        doc = paper(180); wet = new WetWatercolor(doc, 100);
        stroke(doc, wet, 20, false); byte[] fresh = doc.snapshot(); wet.setWetness(0); settle(wet);
        check(Arrays.equals(fresh, doc.snapshot()), "Sliding to zero stops ongoing blending");
    }
    public static void main(String[] args) {
        transparent(); wetnessAndOpaque(); waterAndHistory();
        System.out.println("PASS: transparent multiply/build-up, stamp independence, global wetness, opaque white, clear water, clipping and exact animated undo/redo");
    }
}
