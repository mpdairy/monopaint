package dev.tilesmile.supernote;

/** Drawing-thread feedback controller; all timings include raster/display work. */
final class WetWorkBudget {
    private long busyUntil;
    private double nanosPerTile = 1_000_000;
    private double inputNanos;

    void input(long elapsedNanos, long eventAgeMillis, long nowMillis) {
        inputNanos = Math.max(elapsedNanos, inputNanos * .75);
        if (inputNanos >= 8_000_000 || eventAgeMillis >= 24) busyUntil = nowMillis + 100;
    }

    long nanos(boolean drawing, int strokePixels, int activePixels, long nowMillis) {
        if (!drawing) return 5_000_000;
        if (nowMillis < busyUntil) return 0;
        // Area predicts pressure before the first expensive animation sample.
        double load = 1 + strokePixels / 32768.0 + activePixels / 131072.0;
        return (long)Math.max(500_000, 2_000_000 / load);
    }

    int tiles(long budgetNanos, boolean drawing) {
        if (budgetNanos <= 0) return 0;
        return Math.max(1, Math.min(drawing ? 8 : 16, (int)(budgetNanos / nanosPerTile)));
    }

    void completed(long elapsedNanos, int tiles, boolean drawing, long nowMillis) {
        if (tiles <= 0) return;
        // React immediately to a slow slice, recover gradually as costs fall.
        nanosPerTile = Math.max(elapsedNanos / (double)tiles, nanosPerTile * .85);
        if (drawing && elapsedNanos > 4_000_000) busyUntil = nowMillis + 100;
    }
}
