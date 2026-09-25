package dev.tilesmile.supernote;

/** Immutable settings: a running stroke and a saved preset cannot change underneath the pen. */
final class ToolSettings {
    static final int DEFAULT_STRENGTH = 35;
    static final int DEFAULT_PRESSURE_RESPONSE = 50;
    enum Tool { BRUSH, PENCIL, FILL, ERASER, SOFTEN }
    final Tool tool;
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
        if (tool == null || maximum < 2 || maximum > 128 || minimum < 1 || minimum > maximum || tip < 1 || tip > 128
                || softness < 0 || softness > 100 || hardness < 0 || hardness > 100 || tolerance < 0 || tolerance > 100 || strength < 0 || strength > 100 || pressureResponse < 0 || pressureResponse > 100)
            throw new IllegalArgumentException("Invalid tool settings");
        this.tool = tool; this.minimum = minimum; this.maximum = maximum; this.tip = Math.max(minimum,Math.min(tip,maximum));
        this.softness = softness; this.soft = softness > 0; this.tilt = tilt; this.hardness = hardness; this.tolerance = tolerance; this.strength = strength;
        this.pressureResponse = pressureResponse;
        pressureExponent = Math.pow(2, pressureResponse / 50.0);
    }
    static ToolSettings defaults(Tool tool) { return new ToolSettings(tool, tool == Tool.SOFTEN ? 32 : 64, 3, 70, tool == Tool.PENCIL, 40); }
    ToolSettings size(int maximum) { return new ToolSettings(tool, Math.min(minimum,maximum), maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse); }
    ToolSettings minimum(int value) { return new ToolSettings(tool, value, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse); }
    ToolSettings options(int tip, boolean soft, boolean tilt) { return new ToolSettings(tool, minimum, maximum, tip, soft == this.soft ? softness : soft ? 85 : 0, tilt, hardness, tolerance, strength, pressureResponse); }
    ToolSettings hardness(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, value, tolerance, strength, pressureResponse); }
    ToolSettings softness(int value) { return new ToolSettings(tool, minimum, maximum, tip, value, tilt, hardness, tolerance, strength, pressureResponse); }
    ToolSettings tolerance(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, value, strength, pressureResponse); }
    ToolSettings strength(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, value, pressureResponse); }
    ToolSettings pressureResponse(int value) { return new ToolSettings(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, value); }
    float diameter(float pressure) {
        if(!Float.isFinite(pressure)) pressure=0;
        float p=Math.max(0,Math.min(1,(pressure-.05f)/.40f));
        // Keep the measured .05–.45 pressure range reachable at every response.
        // Light touch is linear; 50 preserves the original square; firm is quartic.
        if (tool != Tool.BRUSH || pressureResponse == DEFAULT_PRESSURE_RESPONSE)
            return minimum+(maximum-minimum)*p*p;
        return minimum+(maximum-minimum)*(float)Math.pow(p, pressureExponent);
    }
    String label() {
        switch (tool) {
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
        return tool == s.tool && minimum == s.minimum && maximum == s.maximum && tip == s.tip && softness == s.softness && tilt == s.tilt && hardness == s.hardness && tolerance == s.tolerance && strength == s.strength && pressureResponse == s.pressureResponse;
    }
    @Override public int hashCode() { return java.util.Objects.hash(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse); }
}
