package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;

/** Opaque ordered dots, with density calibrated to the user's Atelier gradient. */
final class DotGray {
    static Bitmap tile(int gray) {
        whiteCount(gray); // Validate before indexing the shared lookup table.
        int[] pixels = new int[64];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = DotPattern.pixel(gray, i % 8, i / 8);
        return Bitmap.createBitmap(pixels, 8, 8, Bitmap.Config.ARGB_8888);
    }

    static int whiteCount(int gray) {
        return DotPattern.whiteCount(gray);
    }

    private DotGray() {}
}
