package io.github.mpdairy.monopaint;

/**
 * Creates the pen stroke for a tool. Fill and Shapes are not strokes: the canvas runs
 * their drag-and-release gestures itself.
 */
final class ToolStrokes {
    private ToolStrokes() { }

    /**
     * @param wet live wet canvas for brushes, or null when dry
     * @param transparent brushes leave existing marks visible beneath the paint
     * @param erasing the tool erases with its own footprint instead of applying {@code gray}
     */
    static DrawingStroke create(ToneDocument document, ToolSettings settings, int gray,
                                WetWatercolor wet, boolean transparent, boolean erasing) {
        switch (settings.tool) {
            case BRUSH: case WATERCOLOR: case WET_WATERCOLOR: case FLAT_WASH:
                return new PressureStroke(document, settings, gray, wet, transparent, erasing);
            case ERASER:
                // A hard eraser is a solid round stamp; softness needs per-dab fading.
                if (settings.softness == 0) return new PressureStroke(document, settings, 255, wet, false, erasing);
                return new ToolStroke(document, settings, gray, erasing);
            case PENCIL: case SOFTEN:
                return new ToolStroke(document, settings, gray, erasing);
            case AIRBRUSH:
                return new AirbrushStroke(document, settings, gray, erasing);
            default:
                throw new IllegalArgumentException(settings.tool + " is not a stroke tool");
        }
    }
}
