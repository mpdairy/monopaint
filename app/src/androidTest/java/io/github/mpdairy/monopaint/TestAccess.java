package io.github.mpdairy.monopaint;

import java.lang.reflect.Field;

/** Shared reflective access to the painting screen's state for device checks. */
final class TestAccess {
    private static ToolLibrary library(Object activity) throws Exception {
        Field field = PaintActivity.class.getDeclaredField("library"); field.setAccessible(true);
        return (ToolLibrary)field.get(activity);
    }
    /** The selected tool's maximum size. */
    static int maximum(Object activity) {
        try { return library(activity).current().maximum; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    /** Resizes the selected tool. */
    static void setMaximum(Object activity, int maximum) {
        try { ToolLibrary library = library(activity); library.edit(library.current().size(maximum)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    /**
     * The panel-frame tilt a brush receives for firmware ORIENTATION/TILT degrees. Written out
     * independently of DrawingPad so a wrong turn fails: a landscape digitizer frame is turned
     * a quarter to the portrait panel.
     */
    static float[] panelTilt(PaintActivity activity, float orientation, float tilt) {
        return activity.device.landscapeTilt ? new float[]{-tilt, orientation} : new float[]{orientation, tilt};
    }
    /** The firmware ORIENTATION/TILT that {@link #panelTilt} turns into the given panel tilt. */
    static float[] firmwareTilt(PaintActivity activity, float x, float y) {
        return activity.device.landscapeTilt ? new float[]{y, -x} : new float[]{x, y};
    }
    private TestAccess() { }
}
