package io.github.mpdairy.monopaint;

import java.util.BitSet;

/**
 * The paint a brush carries through one stroke, in two parts: the chosen shade, which it
 * lays solidly at first and runs out of along the stroke, and the paint it picks up from
 * the canvas and drags along. As the shade runs out the brush lays more of what it picked
 * up; once empty it only smudges, so over clean paper it leaves nothing.
 */
final class PaintLoad {
    /** How strongly an empty brush still smears what it picked up. */
    private static final float SMUDGE = .45f;
    /** Travel, in pixels, over which the picked-up paint trades for what the brush now covers. */
    private static final float PICKUP_LENGTH = 40;
    private final ToneDocument document;
    private final int gray;
    private final float length;
    /** Each pixel takes the brush's first full contact, so overlapping dabs do not compound. */
    private final BitSet touched = new BitSet();
    private float picked, travelled, pickedAt, amount = 1;
    private boolean started;

    PaintLoad(ToneDocument document, int gray, float length) {
        this.document = document; this.gray = gray; this.length = length;
    }
    /** The shade the brush now lays: its own paint, mixed toward what it picked up as it runs out. */
    int tone() { return Math.round(picked + (gray - picked) * amount); }
    /** Paint left on the brush falls from 1 to 0 over its length; it holds, then fades. */
    void travel(float distance) {
        travelled += distance;
        float t = Math.min(1, travelled / length);
        amount = 1 - t * t * (3 - 2 * t);
    }
    /** How far each new pixel moves toward the brush's tone: fully while loaded, a smear once empty. */
    float deposit() { return SMUDGE + (1 - SMUDGE) * amount; }

    /** One dry dab: lays the load opaquely, or as a transparent glaze, after picking up what it covers. */
    void dab(int[] mask, int stride, int x, int y, int w, int h, boolean transparent) { cover(mask, stride, x, y, w, h, true, transparent); }
    /** Wet paint mingles by itself; the brush only picks up what it covers. */
    void pickUp(int[] mask, int stride, int x, int y, int w, int h) { cover(mask, stride, x, y, w, h, false, false); }
    private void cover(int[] mask, int stride, int x, int y, int w, int h, boolean lay, boolean transparent) {
        float under = 0; int count = 0;
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if (edge(mask, stride, col, row, px, py) != 0) { under += document.strokeBaseTone(px, py); count++; }
        }
        float distance = travelled - pickedAt; pickedAt = travelled;
        if (count == 0) return;
        under /= count;
        if (!started) { picked = under; started = true; }
        else picked += (under - picked) * (1 - (float)Math.exp(-distance / PICKUP_LENGTH));
        if (!lay) return;
        int tone = tone(); float deposit = deposit();
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row, index = py * document.width + px, edge = edge(mask, stride, col, row, px, py);
            if (edge == 0 || touched.get(index)) continue;
            // An anti-aliased edge covers only partly, so a later dab can still reach it fully.
            if (edge == 255) touched.set(index);
            if (transparent) document.glazeTone(px, py, Math.round(255 - (255 - tone) * deposit), edge);
            else document.mixTone(px, py, tone, deposit, edge);
        }
    }
    /** The mask's coverage of an on-page pixel, out of 255, or 0 off the page. */
    private int edge(int[] mask, int stride, int col, int row, int px, int py) {
        return px >= 0 && py >= 0 && px < document.width && py < document.height ? mask[row * stride + col] >>> 24 : 0;
    }
}
