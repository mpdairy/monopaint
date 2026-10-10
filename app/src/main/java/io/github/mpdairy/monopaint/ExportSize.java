package io.github.mpdairy.monopaint;

/**
 * The pixel size of a dithered PNG export. Dots only look right shown one to one on a screen, so
 * a page painted on one tablet is dithered for another at the size that tablet would show it.
 * Sizes keep each page's proportions and are upright, as exported.
 */
final class ExportSize {
    /** Each page at its own size. */
    static final ExportSize PAGE = new ExportSize(1, 0, 0);
    /** Export pixels per page pixel, when not filling a screen. */
    private final double scale;
    /** A screen's shorter and longer side, which pages fill in either orientation; 0 for none. */
    private final int screenShort, screenLong;

    private ExportSize(double scale, int screenShort, int screenLong) {
        this.scale = scale; this.screenShort = screenShort; this.screenLong = screenLong;
    }
    /** As large as fits a screen of that panel, turned to each page's orientation. */
    static ExportSize screen(int panelWidth, int panelHeight) {
        return new ExportSize(0, Math.min(panelWidth, panelHeight), Math.max(panelWidth, panelHeight));
    }
    /** A custom width for a page that is {@code pageWidth} wide; other pages scale alike. */
    static ExportSize width(int width, int pageWidth) { return new ExportSize((double)width / pageWidth, 0, 0); }
    static ExportSize height(int height, int pageHeight) { return new ExportSize((double)height / pageHeight, 0, 0); }

    /** {width, height} for an upright page of that size. */
    int[] of(int width, int height) {
        double factor = scale;
        if (screenLong > 0) {
            boolean wide = width > height;
            factor = Math.min((double)(wide ? screenLong : screenShort) / width, (double)(wide ? screenShort : screenLong) / height);
        }
        return new int[]{Math.max(1, (int)Math.round(width * factor)), Math.max(1, (int)Math.round(height * factor))};
    }
}
