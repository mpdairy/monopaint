package io.github.mpdairy.monopaint;

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
    private final ToolSettings[][] heads = new ToolSettings[ToolSettings.Tool.values().length][ToolSettings.Head.values().length];
    private final ArrayList<Preset> presets = new ArrayList<>();
    private ToolSettings current;
    private String activeId = "";
    ToolLibrary() {
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
            builtins[tool.ordinal()] = ToolSettings.defaults(tool);
            if (builtins[tool.ordinal()].isBrush()) for (ToolSettings.Head head : ToolSettings.Head.values())
                heads[tool.ordinal()][head.ordinal()] = ToolSettings.defaults(tool).head(head).automaticHead();
        }
        current = builtins[0];
    }
    ToolSettings current() { return current; }
    ToolSettings builtin(ToolSettings.Tool tool) { return builtins[tool.ordinal()]; }
    String activeId() { return activeId; }
    List<Preset> presets() { return Collections.unmodifiableList(presets); }
    void select(ToolSettings.Tool tool) { current = builtins[tool.ordinal()]; activeId = ""; }
    void edit(ToolSettings settings) {
        settings = settings.automaticHead();
        if (settings.tool != current.tool) activeId = "";
        current = settings;
        if (activeId.isEmpty()) remember(settings);
        else update(activeId);
    }
    private void remember(ToolSettings settings) {
        builtins[settings.tool.ordinal()] = settings;
        if (settings.isBrush()) heads[settings.tool.ordinal()][settings.head.ordinal()] = settings;
    }
    void selectHead(ToolSettings.Head head) {
        if (!current.isBrush() || head == null) throw new IllegalArgumentException("Select a brush first");
        edit(activeId.isEmpty() ? heads[current.tool.ordinal()][head.ordinal()] : current.head(head));
    }
    void recall(String id) {
        Preset preset = find(id); current = preset.settings;
        activeId = id;
    }
    // Keep the legacy name field for compatible storage and accessible tool labels.
    Preset add() { return add(current.description()); }
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
    void remove(String id) { presets.remove(find(id)); if (activeId.equals(id)) select(current.tool); }
    int presetNumber(String id) {
        ToolSettings type = find(id).settings.asBrush();
        int number = 0;
        for (Preset preset : presets) {
            ToolSettings candidate = preset.settings.asBrush();
            if (candidate.tool == type.tool && candidate.head == type.head
                    && (type.tool != ToolSettings.Tool.SHAPES || candidate.shape == type.shape)
                    && (type.tool != ToolSettings.Tool.FILL || candidate.gradient == type.gradient)) number++;
            if (preset.id.equals(id)) return number;
        }
        throw new IllegalArgumentException("Preset no longer exists");
    }
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
    /** Current preset format. When appending a tool, bump this and extend {@link #toolCount}. */
    private static final int FORMAT = 0x54535043;
    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(FORMAT);
        for (ToolSettings settings : builtins) write(out, settings);
        for (ToolSettings settings : builtins) if (settings.isBrush())
            for (ToolSettings remembered : heads[settings.tool.ordinal()]) write(out, remembered);
        write(out, current); out.writeUTF(activeId); out.writeInt(presets.size());
        for (Preset preset : presets) { out.writeUTF(preset.id); out.writeUTF(preset.name); write(out, preset.settings); }
        out.flush(); return bytes.toByteArray();
    }
    static ToolLibrary decode(byte[] bytes) throws IOException {
        if (bytes.length > 100000) throw new IOException("Preset data is too large");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version < 0x54535031 || version > FORMAT) throw new IOException("Unknown preset format");
            ToolLibrary library = new ToolLibrary();
            int builtinCount = toolCount(version);
            for (int index = 0; index < builtinCount; index++) {
                ToolSettings.Tool tool = ToolSettings.Tool.values()[index];
                ToolSettings settings = read(in, version);
                if (settings.tool != tool) throw new IOException("Invalid built-in settings");
                library.remember(settings);
            }
            if (version >= 0x54535038) for (ToolSettings builtin : library.builtins) if (builtin.isBrush() && builtin.tool.ordinal() < builtinCount) {
                for (ToolSettings.Head head : ToolSettings.Head.values()) {
                    ToolSettings saved = read(in, version);
                    if (saved.tool != builtin.tool || saved.head != head) throw new IOException("Invalid brush head settings");
                    library.heads[builtin.tool.ordinal()][head.ordinal()] = saved;
                }
                if (!builtin.equals(library.heads[builtin.tool.ordinal()][builtin.head.ordinal()]))
                    throw new IOException("Inconsistent brush head settings");
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
    /** Tools are appended over time; each format version knows how many it stores. */
    private static int toolCount(int version) {
        return version >= 0x54535043 ? 12 : version >= 0x54535042 ? 11 : version >= 0x54535040 ? 10 : version >= 0x5453503f ? 9 : version >= 0x5453503b ? 8
                : version >= 0x5453503a ? 7 : version >= 0x54535037 ? 6 : 5;
    }
    private static void write(DataOutputStream out, ToolSettings settings) throws IOException {
        out.writeByte(settings.tool.ordinal()); out.writeInt(settings.maximum); out.writeInt(settings.tip);
        out.writeInt(settings.softness); out.writeBoolean(settings.tilt); out.writeInt(settings.hardness);
        out.writeInt(settings.minimum); out.writeInt(settings.tolerance); out.writeInt(settings.strength); out.writeInt(settings.pressureResponse);
        out.writeByte(settings.head.ordinal()); out.writeInt(settings.angle);
        out.writeInt(settings.bristles); out.writeInt(settings.headThickness); out.writeByte(settings.gradient.ordinal());
        out.writeByte(settings.shape.ordinal()); out.writeBoolean(settings.filled); out.writeInt(settings.outlineWidth);
    }
    private static ToolSettings read(DataInputStream in, int version) throws IOException {
        int tool = in.readUnsignedByte();
        if (tool >= toolCount(version)) throw new IOException("Unknown tool");
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
        int head=version>=0x54535038?in.readUnsignedByte():0;
        int angle=version>=0x54535038?in.readInt():0;
        int bristles=version>=0x54535039?in.readInt():0;
        int headThickness=version>=0x5453503c?in.readInt():ToolSettings.DEFAULT_HEAD_THICKNESS;
        int gradient=version>=0x5453503e?in.readUnsignedByte():0;
        int shape=version>=0x54535040?in.readUnsignedByte():0;
        boolean filled=version>=0x54535040 && in.readBoolean();
        int outlineWidth=version>=0x54535040?in.readInt():3;
        if(shape>=ToolSettings.Shape.values().length) throw new IOException("Unknown shape");
        if(gradient>=ToolSettings.Gradient.values().length) throw new IOException("Unknown gradient type");
        if (head >= ToolSettings.Head.values().length) throw new IOException("Unknown brush head");
        // Earlier builds ignored Flat's stored thickness and always drew 1px.
        // Preserve that appearance until the user changes the new height slider.
        if (version < 0x5453503d && head == ToolSettings.Head.FLAT.ordinal()) headThickness=0;
        return new ToolSettings(ToolSettings.Tool.values()[tool], minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, ToolSettings.Head.values()[head], angle, bristles, headThickness, ToolSettings.Gradient.values()[gradient], ToolSettings.Shape.values()[shape], filled, outlineWidth).automaticHead();
    }
    static final class Preset {
        final String id, name; final ToolSettings settings;
        Preset(String id, String name, ToolSettings settings) { this.id = id; this.name = name; this.settings = settings; }
    }
}
