package io.github.mpdairy.monopaint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;

/** Transient water and pigment, separate from the dry drawing beneath it.
 * All calls run on the document owner thread. Only painted tiles allocate storage.
 * Drying drops this state; the composited tones remain ordinary saved pixels. */
final class WetWatercolor {
    static final int FRAME_MS = 150;
    private static final int SIDE = 16, PIXELS = SIDE * SIDE, FRAMES = 20;
    private static final int TILES_PER_FRAME = 96;
    private final ToneDocument document;
    private final int columns;
    private final Tile[] tiles;
    private final ArrayDeque<Tile> active = new ArrayDeque<>();
    private final ArrayList<Tile> batch = new ArrayList<>();
    private boolean canContinue;
    private final boolean wholeCanvas;
    private float wetness = 1;
    private int strokePixels, frameRemaining, advancedTiles;

    WetWatercolor(ToneDocument document) {
        this(document, false, 100);
    }
    WetWatercolor(ToneDocument document, int amount) {
        this(document, true, amount);
    }
    private WetWatercolor(ToneDocument document, boolean wholeCanvas, int amount) {
        this.wholeCanvas = wholeCanvas;
        setWetness(amount);
        this.document = document;
        columns = (document.width + SIDE - 1) / SIDE;
        tiles = new Tile[columns * ((document.height + SIDE - 1) / SIDE)];
    }
    void setWetness(int amount) { wetness = Math.max(0, Math.min(100, amount)) / 100f; }
    void beginStroke() {
        canContinue = false;
        strokePixels = 0;
        for (Tile tile : tiles) if (tile != null) tile.touched.clear();
    }
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        paintMask(mask, stride, x, y, w, h, gray, false);
    }
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray, boolean transparent) {
        // Rehydrate the local neighborhood lazily: existing drawing is pigment too.
        // The whole canvas is eligible, without scanning/allocating it on a toggle.
        if (wholeCanvas) hydrate(x - 8, y - 8, x + w + 8, y + h + 8);
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if ((mask[row * stride + col] >>> 24) == 0 || px < 0 || py < 0
                    || px >= document.width || py >= document.height) continue;
            int key = (py / SIDE) * columns + px / SIDE, index = (py % SIDE) * SIDE + px % SIDE;
            Tile tile = tiles[key];
            if (tile == null) tiles[key] = tile = new Tile(px / SIDE * SIDE, py / SIDE * SIDE);
            // Interpolated stamps must not repeatedly replace pigment or the moving trail.
            if (tile.touched.get(index)) continue;
            tile.touched.set(index);
            strokePixels++;
            float pigment = 255 - gray;
            if (wholeCanvas) {
                float old = tile.pigment[index];
                if (transparent) pigment = old + (255 - old) * (255 - gray) / 510f;
                // Opaque paint starts crisp, then fades into the pigment underneath.
                // Clear water introduces no white pigment; it only wakes diffusion.
                tile.target[index] = old * (.6f * wetness) + pigment * (1 - .6f * wetness);
                tile.pigment[index] = pigment;
                if(transparent) document.glazeTone(px,py,gray);
                else document.paintTone(px,py,gray);
                composite(tile, index, px, py);
                wake(tile);
                continue;
            }
            if (!tile.wet.get(index)) {
                tile.base[index] = (byte)document.tone(px, py);
                tile.target[index] = pigment;
                markWet(tile, index);
            } else tile.target[index] = tile.pigment[index] * .45f + pigment * .55f;
            tile.pigment[index] = pigment;
            composite(tile, index, px, py);
            wake(tile);
        }
        // Adjacent old washes participate even when the new stamp only touches their edge.
        int left = Math.max(0, x - 8) / SIDE, top = Math.max(0, y - 8) / SIDE;
        int right = Math.min(document.width - 1, x + w + 8) / SIDE;
        int bottom = Math.min(document.height - 1, y + h + 8) / SIDE;
        for (int ty = top; ty <= bottom; ty++) for (int tx = left; tx <= right; tx++) {
            Tile tile = tiles[ty * columns + tx];
            if (tile != null) wake(tile);
        }
    }
    private void hydrate(int left, int top, int right, int bottom) {
        for (int y = Math.max(0, top); y < Math.min(document.height, bottom); y++)
            for (int x = Math.max(0, left); x < Math.min(document.width, right); x++) {
                int key = y / SIDE * columns + x / SIDE, i = y % SIDE * SIDE + x % SIDE;
                Tile tile = tiles[key];
                if (tile == null) tiles[key] = tile = new Tile(x / SIDE * SIDE, y / SIDE * SIDE);
                if (!tile.wet.get(i)) {
                    markWet(tile, i); tile.base[i] = (byte)255;
                    tile.pigment[i] = tile.target[i] = 255 - document.tone(x, y);
                }
            }
    }
    private void wake(Tile tile) {
        if (tile.frames == 0) active.addLast(tile);
        tile.frames = FRAMES;
    }
    private static void markWet(Tile tile, int index) {
        tile.wet.set(index); tile.wetPixels++;
        int row = index / SIDE, col = index % SIDE;
        tile.wetRows[row] |= 1 << col;
        tile.wetColumns[col] |= 1 << row;
    }
    boolean finishStroke() {
        boolean changed = document.finish();
        canContinue |= changed;
        return changed;
    }
    boolean isAnimating() { return !active.isEmpty(); }
    int activeTiles() { return active.size(); }
    int activePixels() { return active.size() * PIXELS; }
    int strokePixels() { return strokePixels; }
    int advancedTiles() { return advancedTiles; }
    boolean framePending() { return frameRemaining > 0; }
    /** Unpaced advancement for model checks. The UI supplies a small work budget. */
    boolean advance(boolean strokeActive) {
        return advance(strokeActive, TILES_PER_FRAME, Long.MAX_VALUE, Integer.MAX_VALUE);
    }
    /** One spatially bounded slice; never leave a partial edit between callbacks.
     * The deadline is checked between tiles, so a single tile may exceed it. */
    boolean advance(boolean strokeActive, int maxTiles, long budgetNanos, int maxSpan) {
        advancedTiles = 0;
        if (active.isEmpty() || maxTiles <= 0 || budgetNanos <= 0) return false;
        long start = System.nanoTime();
        if (frameRemaining == 0) frameRemaining = active.size();
        if (!strokeActive) document.begin();
        batch.clear();
        int count = Math.min(maxTiles, frameRemaining);
        int left = 0, top = 0, right = 0, bottom = 0;
        for (int t = 0; t < count; t++) {
            Tile tile = active.peekFirst();
            int l = t == 0 ? tile.x : Math.min(left, tile.x);
            int u = t == 0 ? tile.y : Math.min(top, tile.y);
            int r = t == 0 ? tile.x + SIDE : Math.max(right, tile.x + SIDE);
            int b = t == 0 ? tile.y + SIDE : Math.max(bottom, tile.y + SIDE);
            // A distant tile waits for the next slice instead of expanding the
            // display's dirty rectangle across otherwise unchanged artwork.
            if (t > 0 && (r - l > maxSpan || b - u > maxSpan || System.nanoTime() - start >= budgetNanos)) break;
            left = l; top = u; right = r; bottom = b;
            batch.add(active.removeFirst());
            for (int i = tile.wet.nextSetBit(0); i >= 0; i = tile.wet.nextSetBit(i + 1)) {
                float value = tile.pigment[i];
                float neighbors = neighbor(tile, i, -1, 0, value) + neighbor(tile, i, 1, 0, value)
                        + neighbor(tile, i, 0, -1, value) + neighbor(tile, i, 0, 1, value);
                float flow = wholeCanvas ? wetness : 1;
                tile.next[i] = value * (1 - .55f * flow) + neighbors * (flow / 30f) + tile.target[i] * (.15f * flow);
            }
        }
        for (Tile tile : batch) {
            for (int i = tile.wet.nextSetBit(0); i >= 0; i = tile.wet.nextSetBit(i + 1)) {
                tile.pigment[i] = tile.next[i];
                composite(tile, i, tile.x + i % SIDE, tile.y + i / SIDE);
            }
            if (--tile.frames > 0) active.addLast(tile);
        }
        advancedTiles = batch.size();
        frameRemaining -= advancedTiles;
        if (strokeActive) return false;
        boolean changed = canContinue ? document.finishContinuation() : document.finish();
        canContinue |= changed;
        return changed;
    }
    private float neighbor(Tile tile, int index, int dx, int dy, float fallback) {
        int edge = dx < 0 ? index % SIDE + 1 : dx > 0 ? SIDE - index % SIDE
                : dy < 0 ? index / SIDE + 1 : SIDE - index / SIDE;
        int step = dx + dy * SIDE, wrap = dx * SIDE + dy * PIXELS;
        Tile next = null;
        if (edge <= 8) {
            int x = tile.x + dx * SIDE, y = tile.y + dy * SIDE;
            if (x >= 0 && y >= 0 && x < document.width && y < document.height)
                next = tiles[y / SIDE * columns + x / SIDE];
        }
        // Fully hydrated tiles have no dry gaps to inspect. Read the three
        // stencil samples directly, avoiding eight coordinate lookups per ray.
        if (tile.wetPixels == PIXELS && (edge > 8 || next != null && next.wetPixels == PIXELS)) {
            float close = edge > 1 ? tile.pigment[index + step] : next.pigment[index + step - wrap];
            float near = edge > 4 ? tile.pigment[index + 4 * step] : next.pigment[index + 4 * step - wrap];
            float far = edge > 8 ? tile.pigment[index + 8 * step] : next.pigment[index + 8 * step - wrap];
            return close + near + far;
        }
        int position = dx != 0 ? index % SIDE : index / SIDE;
        int line = dx != 0 ? index / SIDE : index % SIDE;
        int bits = dx != 0 ? tile.wetRows[line] : tile.wetColumns[line];
        int nextBits = next == null ? 0 : dx != 0 ? next.wetRows[line] : next.wetColumns[line];
        if (dx + dy < 0) {
            bits = Integer.reverse(bits) >>> (32 - SIDE);
            nextBits = Integer.reverse(nextBits) >>> (32 - SIDE);
            position = SIDE - 1 - position;
        }
        int connected = bits >>> (position + 1);
        if (edge <= 8) connected |= nextBits << (edge - 1);
        // The first dry bit cuts the ray off, including across tile boundaries.
        // Water still cannot jump a gap; no per-pixel coordinate walk is needed.
        int reach = Integer.numberOfTrailingZeros(~connected);
        float close = reach < 1 ? fallback : edge > 1 ? tile.pigment[index + step] : next.pigment[index + step - wrap];
        float near = reach < 4 ? fallback : edge > 4 ? tile.pigment[index + 4 * step] : next.pigment[index + 4 * step - wrap];
        float far = reach < 8 ? fallback : edge > 8 ? tile.pigment[index + 8 * step] : next.pigment[index + 8 * step - wrap];
        return close + near + far;
    }
    private void composite(Tile tile, int index, int x, int y) {
        // Legacy washes retain a dry base. Canvas mode imports old paint as pigment
        // over white, so it participates in the same diffusion as each new stroke.
        int tone = Math.round((tile.base[index] & 255) * (1 - tile.pigment[index] / 255f));
        document.setTone(x, y, Math.max(0, Math.min(255, tone)));
    }
    private static final class Tile {
        final int x, y;
        final byte[] base = new byte[PIXELS];
        final float[] pigment = new float[PIXELS], target = new float[PIXELS], next = new float[PIXELS];
        final BitSet wet = new BitSet(PIXELS), touched = new BitSet(PIXELS);
        final int[] wetRows = new int[SIDE], wetColumns = new int[SIDE];
        int frames, wetPixels;
        Tile(int x, int y) { this.x = x; this.y = y; }
    }
}
