package dev.tilesmile.supernote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Presets exclude color; edits save back to the selected custom tool. */
final class ToolLibrary {
    private final ToolSettings[] builtins = new ToolSettings[ToolSettings.Tool.values().length];
    private final ArrayList<Preset> presets = new ArrayList<>();
    private ToolSettings current;
    private String activeId = "";
    ToolLibrary() {
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) builtins[tool.ordinal()] = ToolSettings.defaults(tool);
        current = builtins[0];
    }
    ToolSettings current() { return current; }
    String activeId() { return activeId; }
    List<Preset> presets() { return Collections.unmodifiableList(presets); }
    void select(ToolSettings.Tool tool) { current = builtins[tool.ordinal()]; activeId = ""; }
    void edit(ToolSettings settings) {
        if (settings.tool != current.tool) activeId = "";
        current = settings; builtins[settings.tool.ordinal()] = settings;
        if (!activeId.isEmpty()) update(activeId);
    }
    void recall(String id) {
        Preset preset = find(id); current = preset.settings;
        builtins[current.tool.ordinal()] = current; activeId = id;
    }
    // Keep the legacy name field for compatible storage and accessible tool labels.
    Preset add() { return add(current.label()); }
    Preset add(String name) {
        if (presets.size() >= 100) throw new IllegalStateException("The Custom list is full (100 presets)");
        Preset preset = new Preset(UUID.randomUUID().toString(), validName(name), current);
        presets.add(preset); activeId = preset.id; return preset;
    }
    void rename(String id, String name) {
        Preset old = find(id); presets.set(presets.indexOf(old), new Preset(id, validName(name), old.settings));
    }
    void update(String id) {
        Preset old = find(id); presets.set(presets.indexOf(old), new Preset(id, old.name, current)); activeId = id;
    }
    void remove(String id) { presets.remove(find(id)); if (activeId.equals(id)) activeId = ""; }
    void move(String id, int direction) {
        int from = presets.indexOf(find(id)), to = Math.max(0, Math.min(presets.size()-1, from+direction));
        if (from != to) Collections.swap(presets, from, to);
    }
    void moveBefore(String id, String beforeId) {
        Preset moved = find(id), before = beforeId == null ? null : find(beforeId);
        if (moved == before) return;
        presets.remove(moved);
        presets.add(before == null ? presets.size() : presets.indexOf(before), moved);
    }
    private Preset find(String id) {
        for (Preset preset : presets) if (preset.id.equals(id)) return preset;
        throw new IllegalArgumentException("Preset no longer exists");
    }
    static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) throw new IllegalArgumentException("Use a name of 1–40 characters");
        return trimmed;
    }
    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x54535036);
        for (ToolSettings settings : builtins) write(out, settings);
        write(out, current); out.writeUTF(activeId); out.writeInt(presets.size());
        for (Preset preset : presets) { out.writeUTF(preset.id); out.writeUTF(preset.name); write(out, preset.settings); }
        out.flush(); return bytes.toByteArray();
    }
    static ToolLibrary decode(byte[] bytes) throws IOException {
        if (bytes.length > 100000) throw new IOException("Preset data is too large");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != 0x54535031 && version != 0x54535032 && version != 0x54535033 && version != 0x54535034 && version != 0x54535035 && version != 0x54535036) throw new IOException("Unknown preset format");
            ToolLibrary library = new ToolLibrary();
            for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
                ToolSettings settings = read(in, version);
                if (settings.tool != tool) throw new IOException("Invalid built-in settings");
                library.builtins[tool.ordinal()] = settings;
            }
            library.current = read(in, version); String active = in.readUTF(); int count = in.readInt();
            if (count < 0 || count > 100) throw new IOException("Invalid preset count");
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), name = validName(in.readUTF());
                if (id.length() != 36 || !ids.add(id)) throw new IOException("Invalid preset ID");
                library.presets.add(new Preset(id, name, read(in, version)));
            }
            if (!active.isEmpty() && !library.find(active).settings.equals(library.current))
                throw new IOException("Invalid active preset");
            library.activeId = active;
            if (in.read() != -1) throw new IOException("Extra preset data");
            return library;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid tool settings", invalid); }
    }
    private static void write(DataOutputStream out, ToolSettings settings) throws IOException {
        out.writeByte(settings.tool.ordinal()); out.writeInt(settings.maximum); out.writeInt(settings.tip);
        out.writeInt(settings.softness); out.writeBoolean(settings.tilt); out.writeInt(settings.hardness);
        out.writeInt(settings.minimum); out.writeInt(settings.tolerance); out.writeInt(settings.strength); out.writeInt(settings.pressureResponse);
    }
    private static ToolSettings read(DataInputStream in, int version) throws IOException {
        int tool = in.readUnsignedByte();
        if (tool >= ToolSettings.Tool.values().length) throw new IOException("Unknown tool");
        int maximum = in.readInt(), tip = in.readInt();
        // Keep old preset identities/order/sizes; old hard erasers now start gently feathered.
        if (version == 0x54535031) {
            boolean soft = in.readBoolean(), tilt = in.readBoolean();
            return new ToolSettings(ToolSettings.Tool.values()[tool], maximum, tip, soft ? 85 : 70, tilt, 40);
        }
        int softness=in.readInt();boolean tilt=in.readBoolean();int hardness=in.readInt();
        int minimum=version>=0x54535033?in.readInt():tool==ToolSettings.Tool.PENCIL.ordinal()?1:2;
        int tolerance=version>=0x54535034?in.readInt():0;
        int strength=version>=0x54535035?in.readInt():ToolSettings.DEFAULT_STRENGTH;
        int pressureResponse=version>=0x54535036?in.readInt():ToolSettings.DEFAULT_PRESSURE_RESPONSE;
        return new ToolSettings(ToolSettings.Tool.values()[tool], minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse);
    }
    static final class Preset {
        final String id, name; final ToolSettings settings;
        Preset(String id, String name, ToolSettings settings) { this.id = id; this.name = name; this.settings = settings; }
    }
}
