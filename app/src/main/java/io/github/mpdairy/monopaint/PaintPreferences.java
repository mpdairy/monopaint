package io.github.mpdairy.monopaint;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed access to the app's saved settings. Every read goes to {@link SharedPreferences},
 * so values written elsewhere are always current. Key names are part of the installed
 * app's saved state; keep them unchanged.
 */
final class PaintPreferences {
    static final String FILE = "painting";
    final SharedPreferences store;
    PaintPreferences(SharedPreferences store) { this.store = store; }

    private boolean flag(String key, boolean fallback) { return store.getBoolean(key, fallback); }
    private void setFlag(String key, boolean value) { store.edit().putBoolean(key, value).apply(); }

    /** Left-handed layout: the toolbar sits on the right. */
    boolean toolboxRight() { return flag("toolbox_right", false); }
    void setToolboxRight(boolean value) { setFlag("toolbox_right", value); }
    boolean largeSettingsText() { return flag("large_settings_text", false); }
    void setLargeSettingsText(boolean value) { setFlag("large_settings_text", value); }
    boolean nomadMode() { return flag("nomad_mode", false); }
    void setNomadMode(boolean value) { setFlag("nomad_mode", value); }
    /** Wet paint runs toward whichever page edge the tablet tilts down. */
    boolean wetGravity() { return flag("wet_gravity", false); }
    void setWetGravity(boolean value) { setFlag("wet_gravity", value); }
    /** Dry brush strokes anti-alias their edges, for cleaner exports. */
    boolean smoothEdges() { return flag("smooth_edges", false); }
    void setSmoothEdges(boolean value) { setFlag("smooth_edges", value); }
    /** Folder chosen for PNG exports, as a document-tree URI; empty means Pictures/MonoPaint. */
    String exportFolder() { return store.getString("export_folder", ""); }
    void setExportFolder(String uri) { store.edit().putString("export_folder", uri).apply(); }
    boolean navigationLocked() { return flag("navigation_locked", true); }
    void setNavigationLocked(boolean value) { setFlag("navigation_locked", value); }
    /** Whether the user has picked a shape, so Shapes shows that shape's icon. */
    boolean shapeChosen(boolean fallback) { return flag("shape_chosen", fallback); }
    void setShapeChosen() { setFlag("shape_chosen", true); }

    /** Toolbar entries are tool names plus {@code LAYERS}, {@code ZOOM} and {@code PALETTE}. */
    boolean toolbarItemVisible(String key) { return flag("tool_visible_" + key, true); }
    void setToolbarItemVisible(String key, boolean visible) { setFlag("tool_visible_" + key, visible); }
    /** Saved toolbar order, possibly missing newer entries or containing removed ones. */
    String[] toolbarOrder() { return store.getString("toolbar_order", "").split(","); }
    void setToolbarOrder(List<String> keys) { store.edit().putString("toolbar_order", String.join(",", keys)).apply(); }

    static final int MAX_PALETTE_SHADES = 16;
    /** Saved palette shades; never empty unless the user deleted every shade. */
    ArrayList<Integer> paletteShades() {
        ArrayList<Integer> shades = new ArrayList<>();
        String saved = store.getString("palette_shades", "0,162,225,255");
        if (saved.isEmpty()) return shades;
        for (String item : saved.split(",")) {
            try {
                int value = Integer.parseInt(item);
                if (value >= 0 && value <= 255 && shades.size() < MAX_PALETTE_SHADES) shades.add(value);
            } catch (NumberFormatException ignored) { }
        }
        if (shades.isEmpty()) shades.add(0);
        return shades;
    }
    void setPaletteShades(List<Integer> shades) {
        StringBuilder value = new StringBuilder();
        for (int shade : shades) { if (value.length() > 0) value.append(','); value.append(shade); }
        store.edit().putString("palette_shades", value.toString()).apply();
    }

    /** Restores the saved color and canvas modes. */
    void loadPaint(PaintState state) {
        state.wetCanvas = flag("wet_canvas", false);
        state.transparentPaint = flag("transparent_paint", false);
        state.eraseMode = flag("erase_mode", false);
        state.wetness = Math.max(0, Math.min(100, store.getInt("canvas_wetness", 65)));
        if (state.wetness == 0) state.wetCanvas = false;
        state.gray = Math.max(0, Math.min(255, store.getInt("gray", 0)));
    }

    /** Saved tools and favorites, or a fresh library. Unreadable data is kept in a backup key. */
    ToolLibrary toolLibrary(Runnable unreadable) {
        ToolLibrary library = new ToolLibrary();
        library.edit(library.current().size(Math.max(2, Math.min(128, store.getInt("diameter", 64)))));
        String saved = store.getString("tools", null);
        if (saved == null) return library;
        try { return ToolLibrary.decode(java.util.Base64.getDecoder().decode(saved)); }
        catch (Exception error) {
            store.edit().putString("tools_unreadable_backup", saved).apply();
            unreadable.run();
            return library;
        }
    }

    void savePaint(PaintState state, ToolLibrary library) throws java.io.IOException {
        // "diameter" also seeds the brush size if the tool library is ever missing.
        store.edit().putInt("gray", state.gray).putInt("diameter", library.current().maximum)
                .putBoolean("wet_canvas", state.wetCanvas).putBoolean("transparent_paint", state.transparentPaint)
                .putBoolean("erase_mode", state.eraseMode).putInt("canvas_wetness", state.wetness)
                .putString("tools", java.util.Base64.getEncoder().encodeToString(library.encode())).apply();
    }
}
