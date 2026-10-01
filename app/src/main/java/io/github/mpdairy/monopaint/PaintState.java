package io.github.mpdairy.monopaint;

/** The current color and canvas modes, which apply to whichever tool is selected. Saved with the tools. */
final class PaintState {
    /** Selected logical tone, 0 (black) to 255 (white). */
    int gray;
    /** Tools that support it erase with their own footprint instead of painting {@link #gray}. */
    boolean eraseMode;
    /** Brushes blend into still-wet paint. */
    boolean wetCanvas;
    /** How long paint stays wet, 0–100. */
    int wetness = 65;
    /** Brushes leave existing marks visible beneath the new paint. */
    boolean transparentPaint;
}
