package io.github.mpdairy.monopaint;

/** Shared, coordinate-anchored 0.12 calibration for the document and palette. */
final class DotPattern {
    private static final int[] WHITE_COUNTS = {
            0,4,6,7,10,13,16,17,22,27,32,35,38,42,50,64
    };
    private static final int[] ORDER = {
            0,32,8,40,2,34,10,42, 48,16,56,24,50,18,58,26,
            12,44,4,36,14,46,6,38, 60,28,52,20,62,30,54,22,
            3,35,11,43,1,33,9,41, 51,19,59,27,49,17,57,25,
            15,47,7,39,13,45,5,37, 63,31,55,23,61,29,53,21
    };
    private static final int[] COUNTS = new int[256];
    static {
        for (int gray = 0; gray < 256; gray++) COUNTS[gray] = interpolate(gray);
    }
    static int whiteCount(int gray) {
        if (gray < 0 || gray > 255) throw new IllegalArgumentException("Invalid gray");
        return COUNTS[gray];
    }
    static int pixel(int gray, int x, int y) {
        return ORDER[(y & 7) * 8 + (x & 7)] < COUNTS[gray] ? 0xffffffff : 0xff000000;
    }
    /** Smooth PNG tone using the display coverage curve, before its 65-density quantization.
     * This bakes coverage into RGB intensity; it is not a measured e-ink color profile. */
    static int exportGray(int gray) {
        if(gray<0||gray>255)throw new IllegalArgumentException("Invalid gray");
        for(int i=1;i<GrayPalette.VALUES.length;i++) {
            if(gray<=GrayPalette.VALUES[i]) {
                int start=GrayPalette.VALUES[i-1],span=GrayPalette.VALUES[i]-start;
                int coverage=WHITE_COUNTS[i-1]*span+(gray-start)*(WHITE_COUNTS[i]-WHITE_COUNTS[i-1]);
                return (coverage*255+32*span)/(64*span);
            }
        }
        throw new IllegalStateException("Palette must end at white");
    }
    /** Even density spacing across the picker, including the intermediate light shades. */
    static int pickerTone(int position) {
        int count = (Math.max(0, Math.min(255, position)) * 64 + 127) / 255;
        if (count == 0) return 0;
        for (int i = 1; i < WHITE_COUNTS.length; i++) {
            if (count <= WHITE_COUNTS[i]) {
                int span = WHITE_COUNTS[i] - WHITE_COUNTS[i-1];
                return GrayPalette.VALUES[i-1] + ((count - WHITE_COUNTS[i-1])
                        * (GrayPalette.VALUES[i] - GrayPalette.VALUES[i-1]) + span/2) / span;
            }
        }
        return 255;
    }
    static int pickerPosition(int gray) { return (whiteCount(gray) * 255 + 32) / 64; }
    private static int interpolate(int gray) {
        if (gray == 0) return 0;
        for (int i = 1; i < GrayPalette.VALUES.length; i++) {
            if (gray <= GrayPalette.VALUES[i]) {
                int start = GrayPalette.VALUES[i-1], span = GrayPalette.VALUES[i]-start;
                return WHITE_COUNTS[i-1]
                        + ((gray-start)*(WHITE_COUNTS[i]-WHITE_COUNTS[i-1])+span/2)/span;
            }
        }
        throw new IllegalStateException("Palette must end at white");
    }
    private DotPattern() {}
}
