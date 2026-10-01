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
    private TestAccess() { }
}
