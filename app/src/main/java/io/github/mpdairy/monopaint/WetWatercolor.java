package io.github.mpdairy.monopaint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

/** Transient water and pigment, separate from the dry drawing beneath it.
 * All calls run on the document owner thread. Only painted tiles allocate storage.
 * Drying drops this state; the composited tones remain ordinary saved pixels. */
final class WetWatercolor {
    static final int FRAME_MS = 150;
    private static final int SIDE = 16, PIXELS = SIDE * SIDE, FRAMES = 20;
    private static final int TILES_PER_FRAME = 96;
    /** Brush pen water stays wet longer and evens out quickly with the wet paint around it. */
    private static final int WATER_FRAMES = 40;
    /**
     * Flowing water dries by this factor each frame. Once this damp it barely moves ink
     * (mobility under 3%), so it rests and stops holding its tile open.
     */
    private static final float DRYING = .985f, DAMP = .3f;
    /** Dry paint that flowing water lifts holds this many times its own shade in pigment, and dissolves this fast when fresh. */
    private static final float DEPTH = 2, DISSOLVE = .8f;
    /** How readily water of this freshness lets ink through: drying water quickly becomes sluggish. */
    private static float mobility(float fresh) { return fresh * fresh * fresh; }
    /** Share of the ink difference that tiles joined by a full edge of fresh flowing water trade each step; at most 1/4 stays stable. */
    private static final float SPREAD = .25f;
    /** Tile-scale steps per frame, so ink runs quickly through fresh water without evening it all out. */
    private static final int SPREAD_STEPS = 8;
    private final ArrayList<Tile> spreading = new ArrayList<>();
    private float[] spreadInk = new float[16], spreadChange = new float[16], links = new float[16];
    private int[] linked = new int[32];
    // Spread planning shares the animation deadline, including the first frame.
    // Snapshot queue membership because pen input may wake more tiles between slices.
    private Tile[] spreadCandidates;
    private int spreadPhase, spreadCursor, spreadPairs, spreadStep;
    /** How much of its load the brush pen leaves in fresh water at full pull, and picks up from each dab. */
    private static final float CARRY = .5f, PICKUP = .3f;
    private final ToneDocument document;
    private final int columns;
    private final Tile[] tiles;
    private final ArrayDeque<Tile> active = new ArrayDeque<>();
    private final ArrayList<Tile> batch = new ArrayList<>();
    private boolean canContinue;
    private final boolean wholeCanvas;
    private float wetness = 1;
    /** Downhill direction in page axes, length 0 (flat) to 1 (upright). */
    private float gravityX, gravityY;
    private int strokePixels, frameRemaining, advancedTiles;
    /** Pigment the brush pen carries along the current stroke. */
    private float load;
    private boolean loaded, hasFlowing;

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
    /** True when existing paint everywhere joins in; false when only painted pixels are wet. */
    boolean coversCanvas() { return wholeCanvas; }
    void setWetness(int amount) { wetness = Math.max(0, Math.min(100, amount)) / 100f; }
    void setGravity(float x, float y) { gravityX = x; gravityY = y; }
    /** Wet reach beyond a stamp: paint can run further downhill before it dries. */
    private int margin(float downhill) { return 8 + Math.round(24 * Math.max(0, downhill)); }
    void beginStroke() {
        canContinue = false;
        strokePixels = 0;
        loaded = false;
        for (Tile tile : tiles) if (tile != null) tile.touched.clear();
    }
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        paintMask(mask, stride, x, y, w, h, gray, false);
    }
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray, boolean transparent) {
        paintMask(mask, stride, x, y, w, h, gray, transparent, 1);
    }
    /** {@code strength}, from 0 to 1, moves each pixel only that far toward the paint, as a brush running out of it does. */
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray, boolean transparent, float strength) {
        // Rehydrate the local neighborhood lazily: existing drawing is pigment too.
        // The whole canvas is eligible, without scanning/allocating it on a toggle.
        int marginLeft = margin(-gravityX), marginTop = margin(-gravityY);
        int marginRight = margin(gravityX), marginBottom = margin(gravityY);
        if (wholeCanvas) hydrate(x - marginLeft, y - marginTop, x + w + marginRight, y + h + marginBottom);
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if ((mask[row * stride + col] >>> 24) == 0 || px < 0 || py < 0
                    || px >= document.width || py >= document.height) continue;
            Tile tile = tileAt(px, py);
            int index = (py % SIDE) * SIDE + px % SIDE;
            // Interpolated stamps must not repeatedly replace pigment or the moving trail.
            if (tile.touched.get(index)) continue;
            tile.touched.set(index);
            strokePixels++;
            float pigment = (255 - gray) * strength;
            if (wholeCanvas) {
                float old = tile.pigment[index];
                if (transparent) pigment = old + (255 - old) * (255 - gray) * strength / 510f;
                else pigment = old + (255 - gray - old) * strength;
                // Opaque paint starts crisp, then fades into the pigment underneath.
                // Clear water introduces no white pigment; it only wakes diffusion.
                tile.target[index] = old * (.6f * wetness) + pigment * (1 - .6f * wetness);
                tile.pigment[index] = pigment;
                clearWater(tile, index);
                if(transparent) document.glazeTone(px,py,Math.round(255 - (255 - gray) * strength));
                else document.paintTone(px,py,Math.round(255 - pigment));
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
            clearWater(tile, index);
            composite(tile, index, px, py);
            wake(tile);
        }
        wakeAround(x - marginLeft, y - marginTop, x + w + marginRight, y + h + marginBottom);
    }
    /**
     * Brush pen clear water. It wets only the pixels it touches, so on a dry canvas its
     * edges stay sharp, and lifts the paint already there. Inside the water pigment flows
     * freely, so nearby paint seeps into it, thinning as it spreads. The pen also drags
     * a little of the pigment it passes over. {@code pull}, from 0 to 1, scales both.
     * <p>
     * {@code flowing} water instead behaves like ink in water: it does not drag, and
     * neighboring water pixels trade pigment at the rate of the less fresh one, so ink
     * evens out quickly through fresh water and seeps slowly into water that has begun
     * to dry. There {@code pull} is the water's freshness, which fades each frame. Dry
     * paint it lifts is a hidden reserve that keeps dissolving into the water while it is
     * fresh, but never makes that water darker than the paint was.
     */
    void waterMask(int[] mask, int stride, int x, int y, int w, int h, float pull, boolean flowing) {
        waterMask(mask, stride, x, y, w, h, pull, flowing, 0);
    }
    /**
     * {@code carry}, from 0 to 1, slows how fast the dragging pen trades its load for the
     * paint under it, so it pushes what it picked up, white too, further along the stroke.
     * The slowing is squared so the middle of the range already carries a long way;
     * at 1 the pen keeps its first load for the whole stroke.
     */
    void waterMask(int[] mask, int stride, int x, int y, int w, int h, float pull, boolean flowing, float carry) {
        int marginLeft = margin(-gravityX), marginTop = margin(-gravityY);
        int marginRight = margin(gravityX), marginBottom = margin(gravityY);
        if (wholeCanvas) hydrate(x - marginLeft, y - marginTop, x + w + marginRight, y + h + marginBottom);
        // Only the dragging pen needs a first pass to measure its pigment load.
        // Flowing water lifts each new pixel in the application pass below.
        float under = 0; int count = 0;
        if (!flowing) {
            for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                int px = x + col, py = y + row;
                if ((mask[row * stride + col] >>> 24) == 0 || px < 0 || py < 0
                        || px >= document.width || py >= document.height) continue;
                Tile tile = tileAt(px, py);
                int index = (py % SIDE) * SIDE + px % SIDE;
                if (!tile.wet.get(index)) lift(tile, index, px, py);
                under += tile.pigment[index]; count++;
            }
            if (count == 0) return;
            under /= count;
            if (!loaded) { load = under; loaded = true; }
        }
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if ((mask[row * stride + col] >>> 24) == 0 || px < 0 || py < 0
                    || px >= document.width || py >= document.height) continue;
            Tile tile = tileAt(px, py);
            int index = (py % SIDE) * SIDE + px % SIDE;
            // Each dab adds water only where the pen is newly arriving.
            if (tile.touched.get(index)) continue;
            if (!tile.wet.get(index)) lift(tile, index, px, py);
            tile.touched.set(index);
            strokePixels++;
            boolean newWater = !tile.water.get(index);
            if (!flowing) tile.pigment[index] += (load - tile.pigment[index]) * CARRY * pull;
            // Water ignores its target; flowing water keeps the lifted paint's shade there.
            if (newWater) tile.target[index] = tile.pigment[index];
            if (tile.pull == null) { tile.pull = new float[PIXELS]; tile.mobility = new float[PIXELS]; }
            // More water over a wet spot never makes it pull less. Full pull evens each
            // water pixel with its neighbors every frame, the most that stays stable.
            tile.pull[index] = newWater ? pull : Math.max(tile.pull[index], pull);
            tile.mobility[index] = mobility(tile.pull[index]);
            if (flowing && newWater && tile.pigment[index] > 0) {
                if (tile.reserve == null) tile.reserve = new float[PIXELS];
                tile.reserve[index] = tile.pigment[index] * (DEPTH - 1);
            }
            tile.water.set(index);
            if (flowing) { tile.flowing.set(index); hasFlowing = true; } else tile.flowing.clear(index);
            composite(tile, index, px, py);
            wake(tile, WATER_FRAMES);
        }
        if (!flowing) load += (under - load) * PICKUP * (1 - carry) * (1 - carry);
        wakeAround(x - marginLeft, y - marginTop, x + w + marginRight, y + h + marginBottom);
    }
    private Tile tileAt(int x, int y) {
        int key = (y / SIDE) * columns + x / SIDE;
        Tile tile = tiles[key];
        if (tile == null) tiles[key] = tile = new Tile(x / SIDE * SIDE, y / SIDE * SIDE);
        return tile;
    }
    /** Adjacent old washes participate even when the new stamp only touches their edge. */
    private void wakeAround(int left, int top, int right, int bottom) {
        left = Math.max(0, left) / SIDE; top = Math.max(0, top) / SIDE;
        right = Math.min(document.width - 1, right) / SIDE;
        bottom = Math.min(document.height - 1, bottom) / SIDE;
        for (int ty = top; ty <= bottom; ty++) for (int tx = left; tx <= right; tx++) {
            Tile tile = tiles[ty * columns + tx];
            if (tile != null) wake(tile);
        }
    }
    private void hydrate(int left, int top, int right, int bottom) {
        left = Math.max(0, left); top = Math.max(0, top);
        right = Math.min(document.width, right); bottom = Math.min(document.height, bottom);
        if (left >= right || top >= bottom) return;
        // Successive stamps overlap almost entirely. Skip tiles that are already
        // fully wet instead of revisiting each of their pixels on every stamp.
        for (int ty = top / SIDE; ty <= (bottom - 1) / SIDE; ty++)
            for (int tx = left / SIDE; tx <= (right - 1) / SIDE; tx++) {
                Tile tile = tiles[ty * columns + tx];
                if (tile == null) tiles[ty * columns + tx] = tile = new Tile(tx * SIDE, ty * SIDE);
                else if (tile.wetPixels == PIXELS) continue;
                int x0 = Math.max(left, tile.x), x1 = Math.min(right, tile.x + SIDE);
                int y0 = Math.max(top, tile.y), y1 = Math.min(bottom, tile.y + SIDE);
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                    int i = (y - tile.y) * SIDE + x - tile.x;
                    if (!tile.wet.get(i)) lift(tile, i, x, y);
                }
            }
    }
    /** Wets a dry pixel, taking its drawn tone up as pigment over white. */
    private void lift(Tile tile, int index, int x, int y) {
        markWet(tile, index); tile.base[index] = (byte)255;
        tile.pigment[index] = tile.target[index] = 255 - document.tone(x, y);
    }
    private void wake(Tile tile) { wake(tile, FRAMES); }
    private void wake(Tile tile, int frames) {
        if (tile.frames == 0) active.addLast(tile);
        tile.frames = Math.max(tile.frames, frames);
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
        if (frameRemaining == 0) {
            frameRemaining = active.size();
            spreadCandidates = hasFlowing ? active.toArray(new Tile[0]) : null;
            spreading.clear();
            spreadPhase = spreadCursor = spreadPairs = spreadStep = 0;
        }
        if (!planSpread(start, budgetNanos)) return false;
        if (!strokeActive) document.begin();
        batch.clear();
        int count = Math.min(maxTiles, frameRemaining);
        int left = 0, top = 0, right = 0, bottom = 0;
        // Each pixel draws more from uphill neighbors than downhill ones, so pigment
        // drifts with gravity. The weights average 1, keeping total flow unchanged.
        float fromLeft = 1 + gravityX, fromRight = 1 - gravityX;
        float fromAbove = 1 + gravityY, fromBelow = 1 - gravityY;
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
            // This frame's tile-scale ink change: fresher water takes more of a gain, and a
            // loss comes from where the ink is, so no pixel goes below clear water.
            boolean gaining = tile.spread > 0;
            float spread = tile.spread / Math.max(gaining ? DAMP : 1, gaining ? tile.meanFresh : tile.meanInk);
            tile.spread = 0;
            for (int i = tile.wet.nextSetBit(0); i >= 0; i = tile.wet.nextSetBit(i + 1)) {
                float value = tile.pigment[i];
                if (tile.flowing.get(i)) {
                    if (tile.pull[i] <= DAMP) { tile.next[i] = value; continue; }
                    float fresh = tile.mobility[i];
                    tile.next[i] = value + (fromLeft * exchange(tile, i, -1, 0, value, fresh) + fromRight * exchange(tile, i, 1, 0, value, fresh)
                            + fromAbove * exchange(tile, i, 0, -1, value, fresh) + fromBelow * exchange(tile, i, 0, 1, value, fresh)) / 12 + spread * (gaining ? tile.pull[i] : value);
                    continue;
                }
                float neighbors = fromLeft * neighbor(tile, i, -1, 0, value) + fromRight * neighbor(tile, i, 1, 0, value)
                        + fromAbove * neighbor(tile, i, 0, -1, value) + fromBelow * neighbor(tile, i, 0, 1, value);
                // Water has no settled pigment to return to: it only evens out.
                if (tile.water.get(i)) { tile.next[i] = value + (neighbors / 12 - value) * tile.pull[i]; continue; }
                float flow = wholeCanvas ? wetness : 1;
                tile.next[i] = value * (1 - .55f * flow) + neighbors * (flow / 30f) + tile.target[i] * (.15f * flow);
            }
        }
        for (Tile tile : batch) {
            for (int i = tile.wet.nextSetBit(0); i >= 0; i = tile.wet.nextSetBit(i + 1)) {
                tile.pigment[i] = tile.next[i];
                composite(tile, i, tile.x + i % SIDE, tile.y + i / SIDE);
            }
            // Flowing water dissolves its reserve and keeps its tile moving until it has nearly dried.
            boolean damp = false;
            for (int i = tile.flowing.nextSetBit(0); i >= 0; i = tile.flowing.nextSetBit(i + 1)) {
                if (tile.reserve != null && tile.reserve[i] > 0) {
                    float dissolved = Math.min(tile.reserve[i], Math.max(0, tile.target[i] - tile.pigment[i]) * tile.pull[i] * DISSOLVE);
                    tile.reserve[i] -= dissolved; tile.pigment[i] += dissolved;
                }
                damp |= (tile.pull[i] *= DRYING) > DAMP;
                tile.mobility[i] = mobility(tile.pull[i]);
            }
            if (damp) tile.frames = Math.max(tile.frames, 2);
            if (--tile.frames > 0) active.addLast(tile);
        }
        advancedTiles = batch.size();
        frameRemaining -= advancedTiles;
        if (strokeActive) return false;
        boolean changed = canContinue ? document.finishContinuation() : document.finish();
        canContinue |= changed;
        return changed;
    }
    /**
     * Pixel exchange alone spreads ink only a few pixels a frame. Once per frame, tiles
     * joined by flowing water also trade their average ink over several quick steps, so
     * ink runs through the whole wet area while the water is fresh. Each pair moves the
     * same amount either way, conserving ink. The result waits in {@link Tile#spread}.
     */
    private boolean planSpread(long start, long budgetNanos) {
        if (spreadCandidates == null) return true;
        do {
            int count = spreading.size();
            if (spreadPhase == 0) {
                if (spreadCursor < spreadCandidates.length) {
                    Tile tile = spreadCandidates[spreadCursor++];
                    if (!tile.flowing.isEmpty()) {
                        measure(tile);
                        if (tile.meanFresh > DAMP) { tile.spreadSlot = spreading.size(); spreading.add(tile); }
                    }
                } else {
                    if (spreadInk.length < count) {
                        spreadInk = new float[count * 2]; spreadChange = new float[count * 2];
                    }
                    spreadPhase = 1; spreadCursor = 0;
                }
            } else if (spreadPhase == 1) {
                if (spreadCursor < count) {
                    int a = spreadCursor++;
                    Tile tile = spreading.get(a);
                    spreadInk[a] = tile.meanInk;
                    // Right and lower neighbors only, so each pair is linked once.
                    for (int side = 0; side < 2; side++) {
                        int x = tile.x + (side == 0 ? SIDE : 0), y = tile.y + (side == 1 ? SIDE : 0);
                        if (x >= document.width || y >= document.height) continue;
                        Tile other = tiles[y / SIDE * columns + x / SIDE];
                        if (other == null || other.spreadSlot < 0) continue;
                        int joined = 0;
                        for (int k = 0; k < SIDE; k++)
                            if (side == 0 ? tile.flowing.get(k * SIDE + SIDE - 1) && other.flowing.get(k * SIDE)
                                    : tile.flowing.get(PIXELS - SIDE + k) && other.flowing.get(k)) joined++;
                        if (joined == 0) continue;
                        if (links.length <= spreadPairs) {
                            links = Arrays.copyOf(links, spreadPairs * 2);
                            linked = Arrays.copyOf(linked, spreadPairs * 4);
                        }
                        links[spreadPairs] = SPREAD * joined / SIDE * mobility(Math.min(tile.meanFresh, other.meanFresh))
                                * Math.min(tile.flowingPixels, other.flowingPixels);
                        linked[spreadPairs * 2] = a; linked[spreadPairs * 2 + 1] = other.spreadSlot; spreadPairs++;
                    }
                } else { spreadPhase = spreadPairs == 0 ? 5 : 2; spreadCursor = 0; }
            } else if (spreadPhase == 2) {
                int end = Math.min(count, spreadCursor + 64);
                Arrays.fill(spreadChange, spreadCursor, end, 0);
                spreadCursor = end;
                if (end == count) { spreadPhase = 3; spreadCursor = 0; }
            } else if (spreadPhase == 3) {
                int end = Math.min(spreadPairs, spreadCursor + 64);
                for (; spreadCursor < end; spreadCursor++) {
                    int a = linked[spreadCursor * 2], b = linked[spreadCursor * 2 + 1];
                    float moved = links[spreadCursor] * (spreadInk[b] - spreadInk[a]);
                    spreadChange[a] += moved; spreadChange[b] -= moved;
                }
                if (end == spreadPairs) { spreadPhase = 4; spreadCursor = 0; }
            } else if (spreadPhase == 4) {
                int end = Math.min(count, spreadCursor + 64);
                for (; spreadCursor < end; spreadCursor++)
                    spreadInk[spreadCursor] += spreadChange[spreadCursor] / spreading.get(spreadCursor).flowingPixels;
                if (end == count) { spreadPhase = ++spreadStep == SPREAD_STEPS ? 5 : 2; spreadCursor = 0; }
            } else {
                if (spreadCursor < count) {
                    Tile tile = spreading.get(spreadCursor);
                    tile.spread = spreadInk[spreadCursor++] - tile.meanInk; tile.spreadSlot = -1;
                } else { spreadCandidates = null; return true; }
            }
        } while (System.nanoTime() - start < budgetNanos);
        return false;
    }
    /** Updates the mean ink, mean freshness and count of a tile's flowing pixels. */
    private static void measure(Tile tile) {
        float ink = 0, fresh = 0; int count = 0;
        for (int i = tile.flowing.nextSetBit(0); i >= 0; i = tile.flowing.nextSetBit(i + 1)) {
            ink += tile.pigment[i]; fresh += tile.pull[i]; count++;
        }
        tile.meanInk = ink / count; tile.meanFresh = fresh / count; tile.flowingPixels = count;
    }
    // Each pixel samples its wet neighbors 1, 4 and 8 px away in each direction.
    // A ray ends at the first dry pixel, so water never jumps a gap.

    /** Pixels from {@code index} to its tile's edge in direction dx,dy, counting itself. */
    private static int edge(int index, int dx, int dy) {
        return dx < 0 ? index % SIDE + 1 : dx > 0 ? SIDE - index % SIDE : dy < 0 ? index / SIDE + 1 : SIDE - index / SIDE;
    }
    /** The tile a ray enters within its 8 px reach, or null. */
    private Tile beside(Tile tile, int edge, int dx, int dy) {
        if (edge > 8) return null;
        int x = tile.x + dx * SIDE, y = tile.y + dy * SIDE;
        return x >= 0 && y >= 0 && x < document.width && y < document.height ? tiles[y / SIDE * columns + x / SIDE] : null;
    }
    /** Sum of the three wet samples' pigment along a ray; a sample cut off by dry paper reads {@code fallback}. */
    private float neighbor(Tile tile, int index, int dx, int dy, float fallback) {
        int edge = edge(index, dx, dy), step = dx + dy * SIDE, wrap = dx * SIDE + dy * PIXELS;
        Tile next = beside(tile, edge, dx, dy);
        int reach = reach(tile, next, index, dx, dy, edge);
        float close = reach < 1 ? fallback : edge > 1 ? tile.pigment[index + step] : next.pigment[index + step - wrap];
        float near = reach < 4 ? fallback : edge > 4 ? tile.pigment[index + 4 * step] : next.pigment[index + 4 * step - wrap];
        float far = reach < 8 ? fallback : edge > 8 ? tile.pigment[index + 8 * step] : next.pigment[index + 8 * step - wrap];
        return close + near + far;
    }
    /** Pigment flowing water gains along a ray, trading with each wet sample at the mobility of the less fresh of the two. */
    private float exchange(Tile tile, int index, int dx, int dy, float value, float fresh) {
        int edge = edge(index, dx, dy), step = dx + dy * SIDE, wrap = dx * SIDE + dy * PIXELS;
        Tile next = beside(tile, edge, dx, dy);
        int reach = reach(tile, next, index, dx, dy, edge);
        float gained = 0;
        if (reach >= 1) gained += edge > 1 ? trade(tile, index + step, value, fresh) : trade(next, index + step - wrap, value, fresh);
        if (reach >= 4) gained += edge > 4 ? trade(tile, index + 4 * step, value, fresh) : trade(next, index + 4 * step - wrap, value, fresh);
        if (reach >= 8) gained += edge > 8 ? trade(tile, index + 8 * step, value, fresh) : trade(next, index + 8 * step - wrap, value, fresh);
        return gained;
    }
    /** Only water trades with flowing water; other wet paint holds its pigment. The trade is symmetric, so ink is conserved. */
    private static float trade(Tile source, int index, float value, float fresh) {
        // Pixels that are not water have no mobility.
        return source.mobility == null ? 0 : Math.min(fresh, source.mobility[index]) * (source.pigment[index] - value);
    }
    /** Paint over water makes it ordinary wet paint again. */
    private static void clearWater(Tile tile, int index) {
        tile.water.clear(index); tile.flowing.clear(index);
        if (tile.mobility != null) tile.mobility[index] = 0;
    }
    /** How many pixels in a row are wet beyond {@code index} along the ray, up to the 8 it samples. */
    private int reach(Tile tile, Tile next, int index, int dx, int dy, int edge) {
        // Fully hydrated tiles have no dry gaps to inspect.
        if (tile.wetPixels == PIXELS && (edge > 8 || next != null && next.wetPixels == PIXELS)) return 8;
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
        return Integer.numberOfTrailingZeros(~connected);
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
        final BitSet wet = new BitSet(PIXELS), touched = new BitSet(PIXELS), water = new BitSet(PIXELS), flowing = new BitSet(PIXELS);
        final int[] wetRows = new int[SIDE], wetColumns = new int[SIDE];
        /** Brush pen pull of each water pixel, or freshness of flowing water; allocated once a pen reaches this tile. */
        float[] pull;
        /** Cached cubic freshness, shared by the twelve exchanges per flowing pixel. */
        float[] mobility;
        /** Lifted paint not yet dissolved into flowing water, allocated when there is some. */
        float[] reserve;
        int frames, wetPixels, flowingPixels;
        float meanInk, meanFresh;
        /** Planned change to this tile's mean flowing ink this frame. */
        float spread;
        int spreadSlot = -1;
        Tile(int x, int y) { this.x = x; this.y = y; }
    }
}
