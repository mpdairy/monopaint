package io.github.mpdairy.monopaint;

/** Immutable settings: a running stroke and a saved preset cannot change underneath the pen. */
final class ToolSettings {
    static final int DEFAULT_STRENGTH = 35;
    static final int DEFAULT_PRESSURE_RESPONSE = 50;
    static final int DEFAULT_HEAD_THICKNESS = 10;
    static final int MAX_FLAT_WIDTH = 256;
    static final int MAX_FLAT_HEIGHT = 20;
    static int sizeLimit(Head head) { return head == Head.FLAT ? MAX_FLAT_WIDTH : 128; }

    /**
     * Every drawing tool. To add one:
     * <ol>
     * <li>Append a constant here. Ordinals are part of the saved preset format, so never
     *     reorder them, and add a new format version in {@link ToolLibrary}.</li>
     * <li>Give it an icon in {@code ToolIcons}, settings rows in {@code ToolControls}
     *     and a pen stroke in {@code ToolStrokes}.</li>
     * <li>List it in {@code Toolbar.TOOLS} to show it in the side toolbar.</li>
     * </ol>
     */
    enum Tool {
        BRUSH("Brush", Kind.BRUSH),
        PENCIL("Pencil", Kind.COLORED),
        FILL("Fill", Kind.COLORED),
        ERASER("Eraser", Kind.COLORLESS),
        SOFTEN("Blending stump", Kind.COLORLESS),
        WATERCOLOR("Watercolor", Kind.BRUSH),
        WET_WATERCOLOR("Wet watercolor (experimental)", Kind.BRUSH),
        FLAT_WASH("Flat wash", Kind.BRUSH),
        AIRBRUSH("Airbrush", Kind.COLORED),
        SHAPES("Shapes", Kind.COLORED),
        BRUSH_PEN("Brush pen", Kind.WATER),
        WET_BRUSH_PEN("Wet brush pen", Kind.FLOWING_WATER);

        final String label;
        /** Brushes share brush heads, wet canvas and transparent paint; they display as one Brush tool. */
        final boolean brush;
        /** Tools that apply the selected color can erase with their own footprint instead. */
        final boolean erasable;
        /** Lays down its own clear water, wet canvas or not. Tilt sets its size; pressure its pull. */
        final boolean water;
        /** Its water stays wet and carries ink like ink in water, faster where the water is fresher. */
        final boolean flowing;
        Tool(String label, Kind kind) {
            this.label = label; brush = kind == Kind.BRUSH; erasable = kind == Kind.BRUSH || kind == Kind.COLORED;
            flowing = kind == Kind.FLOWING_WATER; water = kind == Kind.WATER || flowing;
        }
        private enum Kind { BRUSH, COLORED, COLORLESS, WATER, FLOWING_WATER }
    }
    enum Head {
        ROUND("Round"), FLAT("Flat"), FILBERT("Filbert");
        final String label;
        Head(String label) { this.label=label; }
    }
    enum Gradient {
        LINEAR("Linear"), CIRCULAR("Circular"), FLAT("Flat fill");
        final String label;
        Gradient(String label) { this.label=label; }
    }
    enum Shape {
        LINE("Line"), RECTANGLE("Rectangle"), SQUARE("Square"), OVAL("Oval"), CIRCLE("Circle");
        final String label;
        Shape(String label) { this.label=label; }
    }
    final Shape shape;
    final boolean filled;
    final int outlineWidth;
    final Gradient gradient;
    final Tool tool;
    final Head head;
    final int angle, bristles, headThickness;
    final int minimum, maximum, tip, hardness, softness, tolerance, strength, pressureResponse;
    private final double pressureExponent;
    final boolean soft, tilt;

    ToolSettings(Tool tool, int maximum, int tip, int softness, boolean tilt, int hardness) {
        this(tool, tool == Tool.PENCIL ? 1 : 2, maximum, tip, softness, tilt, hardness, 0, DEFAULT_STRENGTH,
                DEFAULT_PRESSURE_RESPONSE, Head.ROUND, 0, 0, DEFAULT_HEAD_THICKNESS, Gradient.LINEAR, Shape.LINE, false, 3);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse, Head head, int angle, int bristles, int headThickness, Gradient gradient, Shape shape, boolean filled, int outlineWidth) {
        if(shape==null || outlineWidth<1 || outlineWidth>128) throw new IllegalArgumentException("Invalid shape settings");
        this.shape=shape; this.filled=filled; this.outlineWidth=outlineWidth;
        if (gradient == null || headThickness < 0 || headThickness > 100 || bristles < 0 || bristles > 100 || tool == null || head == null || angle < 0 || angle > 180
                || (!tool.brush && head != Head.ROUND) || maximum < 2 || maximum > sizeLimit(head) || minimum < 1 || minimum > maximum || tip < 1 || tip > sizeLimit(head)
                || softness < 0 || softness > 100 || hardness < 0 || hardness > 100 || tolerance < 0 || tolerance > 100 || strength < 0 || strength > 100 || pressureResponse < 0 || pressureResponse > 100)
            throw new IllegalArgumentException("Invalid tool settings");
        this.gradient=gradient; this.tool = tool; this.head = head; this.angle = angle; this.bristles = bristles; this.headThickness = headThickness; this.minimum = minimum; this.maximum = maximum; this.tip = Math.max(minimum,Math.min(tip,maximum));
        this.softness = softness; this.soft = softness > 0; this.tilt = tilt; this.hardness = hardness; this.tolerance = tolerance; this.strength = strength;
        this.pressureResponse = pressureResponse;
        // Brushes span linear (0) to quartic (100). The brush pen's pull centers on
        // linear instead, so low settings respond to a light touch (square root at 25).
        pressureExponent = tool.water ? Math.pow(2, (pressureResponse - 50) / 25.0) : Math.pow(2, pressureResponse / 50.0);
    }
    private ToolSettings(Values v) {
        this(v.tool, v.minimum, v.maximum, v.tip, v.softness, v.tilt, v.hardness, v.tolerance, v.strength, v.pressureResponse,
                v.head, v.angle, v.bristles, v.headThickness, v.gradient, v.shape, v.filled, v.outlineWidth);
    }
    static ToolSettings defaults(Tool tool) {
        ToolSettings settings=new ToolSettings(tool, tool == Tool.SOFTEN ? 32 : tool.water ? 48 : 64, 3, 70, tool == Tool.PENCIL, 40);
        // A light touch should already move paint: the pen's curve starts gentle.
        if (tool.water) return settings.minimum(8).pressureResponse(25).strength(tool.flowing ? 100 : 85);
        return tool==Tool.FILL?settings.gradient(Gradient.FLAT):settings;
    }

    /** Mutable copy used to derive a changed, validated settings object. */
    private static final class Values {
        Tool tool; Head head; Gradient gradient; Shape shape;
        int minimum, maximum, tip, softness, hardness, tolerance, strength, pressureResponse, angle, bristles, headThickness, outlineWidth;
        boolean tilt, filled;
    }
    private interface Change { void apply(Values values); }
    private ToolSettings with(Change change) {
        Values v = new Values();
        v.tool = tool; v.head = head; v.gradient = gradient; v.shape = shape;
        v.minimum = minimum; v.maximum = maximum; v.tip = tip; v.softness = softness; v.hardness = hardness;
        v.tolerance = tolerance; v.strength = strength; v.pressureResponse = pressureResponse; v.angle = angle;
        v.bristles = bristles; v.headThickness = headThickness; v.outlineWidth = outlineWidth;
        v.tilt = tilt; v.filled = filled;
        change.apply(v);
        return new ToolSettings(v);
    }

    ToolSettings asBrush() {
        if (!isBrush() || tool == Tool.BRUSH) return this;
        return with(v -> v.tool = Tool.BRUSH);
    }
    ToolSettings size(int value) { return with(v -> { v.maximum = value; v.minimum = Math.min(minimum, value); }); }
    ToolSettings minimum(int value) { return with(v -> v.minimum = value); }
    ToolSettings options(int tip, boolean soft, boolean tilt) {
        return with(v -> { v.tip = tip; v.softness = soft == this.soft ? softness : soft ? 85 : 0; v.tilt = tilt; });
    }
    ToolSettings hardness(int value) { return with(v -> v.hardness = value); }
    ToolSettings softness(int value) { return with(v -> v.softness = value); }
    ToolSettings tolerance(int value) { return with(v -> v.tolerance = value); }
    ToolSettings strength(int value) { return with(v -> v.strength = value); }
    ToolSettings pressureResponse(int value) { return with(v -> v.pressureResponse = value); }
    ToolSettings head(Head value) {
        int size = Math.min(maximum, sizeLimit(value));
        return with(v -> {
            v.head = value; v.maximum = size; v.minimum = Math.min(minimum, size); v.tip = Math.min(tip, size);
            if (value == Head.FLAT && head != Head.FLAT) v.headThickness = 0;
        });
    }
    ToolSettings angle(int value) { return with(v -> v.angle = value); }
    // Pencil uses tilt for broadening; shaped brushes use it for heading.
    ToolSettings tilt(boolean value) { return with(v -> v.tilt = value); }
    ToolSettings bristles(int value) { return with(v -> v.bristles = value); }
    ToolSettings headThickness(int value) { return with(v -> v.headThickness = value); }
    // Flat exposes height; the other legacy shape controls remain automatic.
    ToolSettings automaticHead() {
        int thickness=head==Head.FILBERT?55:Math.min(MAX_FLAT_HEIGHT,headThickness);
        if (!isBrush() || head == Head.ROUND || (tilt && angle == 0 && headThickness == thickness)) return this;
        return with(v -> { v.tilt = true; v.angle = 0; v.headThickness = thickness; });
    }
    ToolSettings gradient(Gradient value) { return with(v -> v.gradient = value); }
    ToolSettings shape(Shape value) { return with(v -> v.shape = value); }
    ToolSettings filled(boolean value) { return with(v -> v.filled = value); }
    ToolSettings outlineWidth(int value) { return with(v -> v.outlineWidth = value); }

    float headAspectRatio() { return head == Head.ROUND ? 1f : headThickness / 100f; }
    float flatHeight(float width) { return Math.max(1, width*Math.min(MAX_FLAT_HEIGHT,headThickness)/100f); }
    String description() {
        if (tool == Tool.SHAPES) return shape.label + (shape == Shape.LINE ? "" : filled ? " filled" : " outline");
        if (isBrush()) return head.label + " " + label();
        if (tool == Tool.FILL) return gradient == Gradient.FLAT ? "Flat fill" : gradient.label + " gradient";
        return label();
    }
    /** The measured .05–.45 pen pressure range as 0–1. */
    private static float travel(float pressure) {
        if(!Float.isFinite(pressure)) pressure=0;
        return Math.max(0,Math.min(1,(pressure-.05f)/.40f));
    }
    float diameter(float pressure) {
        float p=travel(pressure);
        // Keep the measured .05–.45 pressure range reachable at every response.
        // Light touch is linear; 50 preserves the original square; firm is quartic.
        if (!isBrush() || pressureResponse == DEFAULT_PRESSURE_RESPONSE)
            return minimum+(maximum-minimum)*p*p;
        return minimum+(maximum-minimum)*(float)Math.pow(p, pressureExponent);
    }
    /** Whether the head turns with the pen's tilt: shaped brushes that follow tilt, and the brush pens. */
    boolean followsTilt() { return tool.water || tilt && head != Head.ROUND; }
    /**
     * A leaning tip stretches along its lean, like a tilted pencil lead: the footprint's
     * short side when its long side is {@code major} and the upright tip is {@code upright}.
     */
    float leanMinor(float major, float upright) { return Math.max(minimum, upright + (major - upright) * .28f); }
    /** Stroke size for a pen sample; for the brush pens, the long side, which grows as the pen leans whatever the pressure. */
    float diameter(float pressure, float tiltX, float tiltY) {
        return tool.water ? minimum + (maximum - minimum) * BrushDirection.lean(tiltX, tiltY) : diameter(pressure);
    }
    /** How strongly brush pen water pulls paint along, 0–1: pressure through the response curve, scaled by strength. */
    float pull(float pressure) {
        return strength / 100f * (.15f + .85f * (float)Math.pow(travel(pressure), pressureExponent));
    }
    boolean supportsEraseMode() { return tool.erasable; }
    boolean isBrush() { return tool.brush; }
    String label() { return tool.label; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ToolSettings)) return false;
        ToolSettings s = (ToolSettings)other;
        return tool == s.tool && minimum == s.minimum && maximum == s.maximum && tip == s.tip && softness == s.softness && tilt == s.tilt && hardness == s.hardness && tolerance == s.tolerance && strength == s.strength && pressureResponse == s.pressureResponse && head == s.head && angle == s.angle && bristles == s.bristles && headThickness == s.headThickness && gradient == s.gradient && shape == s.shape && filled == s.filled && outlineWidth == s.outlineWidth;
    }
    @Override public int hashCode() { return java.util.Objects.hash(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness, gradient, shape, filled, outlineWidth); }
}
