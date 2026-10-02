package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class PaintLoadChecks {
    public static void main(String[] args) {
        runningOut(false); runningOut(true); loading(); wetFading();
        System.out.println("PASS: brush paint load lays solid paint first, fades along the stroke, smudges what it crosses, and undoes in one step");
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    /** Loads are whole steps, so lengths land within a step of the target. */
    private static boolean near(float length, float target) { return Math.abs(Math.sqrt(length) - Math.sqrt(target)) <= .5; }
    /** A mid-gray stroke across white paper, with or without a black block in its path. */
    private static ToneDocument stroke(boolean block) {
        ToneDocument doc = new ToneDocument(320, 40);
        if (block) { doc.begin(); for (int y = 0; y < 40; y++) for (int x = 60; x < 100; x++) doc.paintTone(x, y, 0); doc.finish(); }
        int[] mask = new int[8 * 8]; Arrays.fill(mask, 0xff000000);
        PaintLoad load = new PaintLoad(doc, 128, 150);
        doc.begin();
        for (int x = 0; x <= 300; x += 2) { if (x > 0) load.travel(2); load.dab(mask, 8, x, 16, 8, 8, false); }
        doc.finish();
        return doc;
    }
    /** Rubbing in the palette loads paint; a limited brush still sizes by pressure. */
    private static void loading() {
        ToolSettings flat = ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(ToolSettings.Head.FLAT).paintLoad(40);
        check(flat.diameter(.06f, 0, 0) < flat.diameter(.45f, 0, 0), "Pressure still sizes a brush with limited paint");
        check(near(flat.loadedBy(0).paintLength(), 50) && near(flat.loadedBy(600).paintLength(), 650) && flat.loadedBy(1e6f).limitsPaint(),
                "A tap loads a dab's worth, rubbing loads about as far as the pen rubbed, and rubbing never makes paint unlimited");
        ToolSettings round = ToolSettings.defaults(ToolSettings.Tool.BRUSH);
        check(!round.limitsPaint() && round.oilPaint(true).limitsPaint() && round.oilPaint(true).head == ToolSettings.Head.ROUND, "Any brush head can turn on oil paint");
        check(flat.minimumLoad(0).loadedBy(0).paintLength() < 1 && near(flat.minimumLoad(40).loadedBy(0).paintLength(), 400),
                "Minimum load sets what a tap loads, down to a sliver");
        check(near(flat.loadingSpeed(75).loadedBy(600).paintLength(), 1250) && near(flat.loadingSpeed(25).loadedBy(600).paintLength(), 350),
                "Loading speed doubles or halves the paint each rub loads");
        check(flat.oilPaint(true).equals(flat) && !flat.oilPaint(false).limitsPaint(), "Turning oil paint on keeps the loaded paint; off is unlimited");
    }
    /** On a wet canvas a brush running out lays less paint instead of covering at full strength. */
    private static void wetFading() {
        int full = wetTone(1), half = wetTone(.5f);
        check(full < 40 && half > full + 60 && half < 230, "Wet paint fades with the brush's paint (full " + full + ", half " + half + ")");
    }
    private static int wetTone(float strength) {
        ToneDocument doc = new ToneDocument(64, 64); WetWatercolor wet = new WetWatercolor(doc);
        int[] mask = new int[32 * 32]; Arrays.fill(mask, 0xff000000);
        doc.begin(); wet.beginStroke(); wet.paintMask(mask, 32, 16, 16, 32, 32, 0, false, strength); wet.finishStroke();
        for (int frames = 0; wet.isAnimating() && frames < 1000; frames++) wet.advance(false);
        return doc.tone(32, 32);
    }
    private static void runningOut(boolean block) {
        ToneDocument plain = stroke(false), doc = stroke(block);
        int start = doc.tone(10, 20), end = doc.tone(290, 20);
        check(start == 128, "A loaded brush lays its shade solidly, without overlapping dabs compounding (" + start + ")");
        check(end >= 250, "An empty brush only smears, and over clean paper it has picked up white (" + end + ")");
        check(doc.tone(10, 10) == 255, "Paint stays inside the brush footprint");
        int clean = plain.tone(40, 20), later = plain.tone(130, 20);
        check(clean < 170 && later > clean + 40, "The stroke fades as the paint runs out (" + clean + ", " + later + ")");
        if (block) {
            int smudged = doc.tone(115, 20);
            check(smudged + 20 < plain.tone(115, 20), "The brush drags black past the block (" + smudged + " vs " + plain.tone(115, 20) + ")");
            check(doc.tone(80, 10) == 0, "Paint beside the stroke is untouched");
            byte[] painted = doc.snapshot();
            check(doc.undo() && doc.tone(115, 20) == 255 && doc.tone(80, 20) == 0, "One undo removes the stroke");
            check(doc.redo() && Arrays.equals(painted, doc.snapshot()), "Redo restores it");
        }
    }
}
