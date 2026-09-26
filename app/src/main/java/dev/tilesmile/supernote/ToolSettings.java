package dev.tilesmile.supernote;

/** Immutable settings: a running stroke and a saved preset cannot change underneath the pen. */
final class ToolSettings {
    static final int DEFAULT_STRENGTH = 35;
    static final int DEFAULT_PRESSURE_RESPONSE = 50;
    static final int DEFAULT_HEAD_THICKNESS = 10;
    // Append new tools: ordinals are part of the saved preset format.
    enum Tool { BRUSH, PENCIL, FILL, ERASER, SOFTEN, WATERCOLOR, WET_WATERCOLOR, FLAT_WASH }
    enum Head {
        ROUND("Round"), FLAT("Flat"), FILBERT("Filbert");
        final String label;
        Head(String label) { this.label=label; }
    }
    final Tool tool;
    final Head head;
    final int angle, bristles, headThickness;
    final int minimum, maximum, tip, hardness, softness, tolerance, strength, pressureResponse;
    private final double pressureExponent;
    final boolean soft, tilt;
    ToolSettings(Tool tool, int maximum, int tip, boolean soft, boolean tilt) {
        this(tool, maximum, tip, soft ? 85 : 0, tilt, 40);
    }
    ToolSettings(Tool tool, int maximum, int tip, int softness, boolean tilt, int hardness) {
        this(tool, tool == Tool.PENCIL ? 1 : 2, maximum, tip, softness, tilt, hardness);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, 0);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, DEFAULT_STRENGTH);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, DEFAULT_PRESSURE_RESPONSE);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, Head.ROUND, 0);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse, Head head, int angle) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, 0);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse, Head head, int angle, int bristles) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, DEFAULT_HEAD_THICKNESS);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse, Head head, int angle, int bristles, int headThickness) {
        if (headThickness < 0 || headThickness > 100 || bristles < 0 || bristles > 100 || tool == null || head == null || angle < 0 || angle > 180
                || (tool != Tool.BRUSH && tool != Tool.WATERCOLOR && tool != Tool.WET_WATERCOLOR && tool != Tool.FLAT_WASH && head != Head.ROUND) || maximum < 2 || maximum > 128 || minimum < 1 || minimum > maximum || tip < 1 || tip > 128
                || softness < 0 || softness > 100 || hardness < 0 || hardness > 100 || tolerance < 0 || tolerance > 100 || strength < 0 || strength > 100 || pressureResponse < 0 || pressureResponse > 100)
            throw new IllegalArgumentException("Invalid tool settings");
        this.tool = tool; this.head = head; this.angle = angle; this.bristles = bristles; this.headThickness = headThickness; this.minimum = minimum; this.maximum = maximum; this.tip = Math.max(minimum,Math.min(tip,maximum));
        this.softness = softness; this.soft = softness > 0; this.tilt = tilt; this.hardness = hardness; this.tolerance = tolerance; this.strength = strength;
        this.pressureResponse = pressureResponse;
        pressureExponent = Math.pow(2, pressureResponse / 50.0);
    }
    static ToolSettings defaults(Tool tool) { return new ToolSettings(tool, tool == Tool.SOFTEN ? 32 : 64, 3, 70, tool == Tool.PENCIL, 40); }
    ToolSettings asBrush() {
        if (!isBrush() || tool == Tool.BRUSH) return this;
        return new ToolSettings(Tool.BRUSH, minimum, maximum, tip, softness, tilt, hardness,
                tolerance, strength, pressureResponse, head, angle, bristles, headThickness);
    }
    ToolSettings size(int maximum) { return new ToolSettings(tool, Math.min(minimum,maximum), maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings minimum(int value) { return new ToolSettings(tool, value, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings options(int tip, boolean soft, boolean tilt) { return new ToolSettings(tool, minimum, maximum, tip, soft == this.soft ? softness : soft ? 85 : 0, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings hardness(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, value, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings softness(int value) { return new ToolSettings(tool, minimum, maximum, tip, value, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings tolerance(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, value, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings strength(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, value, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings pressureResponse(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, value, head, angle, bristles, headThickness); }
    ToolSettings head(Head value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, value, angle, bristles, headThickness); }
    ToolSettings angle(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, value, bristles, headThickness); }
    // Pencil uses tilt for broadening; shaped brushes use it for heading.
    ToolSettings tilt(boolean value) { return new ToolSettings(tool, minimum, maximum, tip, softness, value, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
    ToolSettings bristles(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, value, headThickness); }
    ToolSettings headThickness(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, value); }
    // Retain legacy fields in the wire format, but do not let hidden controls
    // affect a brush selected from the library (including older custom tools).
    ToolSettings automaticHead() {
        int thickness=head==Head.FILBERT?55:DEFAULT_HEAD_THICKNESS;
        if (!isBrush() || head == Head.ROUND || (tilt && angle == 0 && headThickness == thickness)) return this;
        return new ToolSettings(tool, minimum, maximum, tip, softness, true, hardness, tolerance, strength,
                pressureResponse, head, 0, bristles, thickness);
    }
    float headAspectRatio() { return head == Head.ROUND ? 1f : headThickness / 100f; }
    String description() { return isBrush() ? head.label + " " + label() : label(); }
    float diameter(float pressure) {
        if(!Float.isFinite(pressure)) pressure=0;
        float p=Math.max(0,Math.min(1,(pressure-.05f)/.40f));
        // Keep the measured .05–.45 pressure range reachable at every response.
        // Light touch is linear; 50 preserves the original square; firm is quartic.
        if (!isBrush() || pressureResponse == DEFAULT_PRESSURE_RESPONSE)
            return minimum+(maximum-minimum)*p*p;
        return minimum+(maximum-minimum)*(float)Math.pow(p, pressureExponent);
    }
    boolean isBrush() { return tool == Tool.BRUSH || tool == Tool.WATERCOLOR || tool == Tool.WET_WATERCOLOR || tool == Tool.FLAT_WASH; }
    String label() {
        switch (tool) {
            case WATERCOLOR: return "Watercolor";
            case FLAT_WASH: return "Flat wash";
            case WET_WATERCOLOR: return "Wet watercolor (experimental)";
            case PENCIL: return "Pencil";
            case FILL: return "Fill";
            case ERASER: return "Eraser";
            case SOFTEN: return "Soften";
            default: return "Brush";
        }
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ToolSettings)) return false;
        ToolSettings s = (ToolSettings)other;
        return tool == s.tool && minimum == s.minimum && maximum == s.maximum && tip == s.tip && softness == s.softness && tilt == s.tilt && hardness == s.hardness && tolerance == s.tolerance && strength == s.strength && pressureResponse == s.pressureResponse && head == s.head && angle == s.angle && bristles == s.bristles && headThickness == s.headThickness;
    }
    @Override public int hashCode() { return java.util.Objects.hash(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness); }
}
