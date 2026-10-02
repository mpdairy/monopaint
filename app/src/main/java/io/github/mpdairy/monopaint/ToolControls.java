package io.github.mpdairy.monopaint;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import java.util.function.Consumer;

/**
 * The rows each tool shows in its settings panel. A tool's panel is: its variant
 * choices (brush tip, fill type or shape), then {@link #add its controls}, then the
 * Add to Toolbar / delete row supplied by {@link ToolSettingsPanel}.
 */
final class ToolControls {
    private ToolControls() { }

    /** Adds the controls for the current tool. {@code textSize} sizes preview captions. */
    static void add(SettingsForm form, ToolSettings current, float textSize) {
        switch (current.tool) {
            case BRUSH: case WATERCOLOR: case WET_WATERCOLOR: case FLAT_WASH: brush(form, current, textSize); break;
            case PENCIL: pencil(form, textSize); break;
            case ERASER: eraser(form, textSize); break;
            case SOFTEN: soften(form, textSize); break;
            case AIRBRUSH: airbrush(form); break;
            case BRUSH_PEN: case WET_BRUSH_PEN: brushPen(form, current, textSize); break;
            case FILL: fill(form, current); break;
            case SHAPES: shapes(form, current); break;
            default: throw new IllegalArgumentException("No controls for " + current.tool);
        }
    }

    private static void brush(SettingsForm form, ToolSettings current, float textSize) {
        ToolSettings.Head head = current.head;
        form.hint(head == ToolSettings.Head.ROUND ? "Even in every direction"
                : head == ToolSettings.Head.FLAT ? "Fine, straight edge · follows tilt" : "Full, rounded edge · follows tilt");
        form.footprint(112, textSize);
        sizes(form, head == ToolSettings.Head.ROUND ? "diameter" : "width", ToolSettings.sizeLimit(head));
        if (head == ToolSettings.Head.FLAT) {
            form.slider("Brush height", s -> "Height\n" + (s.headThickness == 0 ? "1 px" : s.headThickness + "% width"),
                    s -> 0, s -> ToolSettings.MAX_FLAT_HEIGHT, s -> s.headThickness, ToolSettings::headThickness);
            form.hint("1 px minimum; up to 20% of the pressure-sized width.");
        }
        form.percent("Pressure response", "Pressure", s -> s.pressureResponse, ToolSettings::pressureResponse);
        form.check("Oil paint", ToolSettings::limitsPaint, ToolSettings::oilPaint);
        form.visibleWhen((View)form.percent("Loading speed", s -> s.loadingSpeed, ToolSettings::loadingSpeed).getParent(), ToolSettings::limitsPaint);
        form.visibleWhen((View)form.percent("Minimum load", s -> s.minimumLoad, ToolSettings::minimumLoad).getParent(), ToolSettings::limitsPaint);
        form.hint("Paint runs out along the stroke, then smudges. Rub the pen around in a color to load more.");
        if (current.tool == ToolSettings.Tool.WATERCOLOR) form.hint("Black dots; white adds no ink.");
        else if (current.tool == ToolSettings.Tool.FLAT_WASH) form.hint("Even gray; darker marks stay.");
        else if (current.tool == ToolSettings.Tool.WET_WATERCOLOR)
            form.hint("Colors mingle while wet. White adds water; use the dryer to set.");
    }

    private static void pencil(SettingsForm form, float textSize) {
        form.footprint(84, textSize);
        sizes(form, "diameter", 128);
        form.percent("Hardness", s -> s.hardness, ToolSettings::hardness);
        form.hint("Soft = darker. Hard = lighter.");
        form.check("Broaden with tilt", s -> s.tilt, (s, tilt) -> s.options(s.tip, s.soft, tilt));
        form.slider("Upright tip at full pressure", s -> "Upright tip\n" + s.tip + " px",
                s -> s.minimum, s -> s.maximum, s -> s.tip, (s, tip) -> s.options(tip, s.soft, s.tilt));
    }

    private static void eraser(SettingsForm form, float textSize) {
        form.footprint(84, textSize);
        sizes(form, "diameter", 128);
        form.percent("Softness", s -> s.softness, ToolSettings::softness);
        form.hint("0% erases cleanly. Higher values fade with rubbing.");
    }

    private static void soften(SettingsForm form, float textSize) {
        form.footprint(84, textSize);
        sizes(form, "diameter", 128);
        form.percent("Strength", s -> s.strength, ToolSettings::strength);
        form.hint("Pull shading in the direction you rub. Lower strength blends gently; repeat passes to build it up.");
    }

    private static void brushPen(SettingsForm form, ToolSettings current, float textSize) {
        form.footprint(84, textSize);
        sizes(form, "diameter", 128);
        form.percent("Strength", s -> s.strength, ToolSettings::strength);
        form.percent("Pressure response", "Pressure", s -> s.pressureResponse, ToolSettings::pressureResponse);
        if (!current.tool.flowing) form.percent("Carry original paint", s -> s.carry, ToolSettings::carry);
    }

    private static void airbrush(SettingsForm form) {
        form.slider("Airbrush diameter", s -> "Diameter\n" + s.maximum + " px", s -> 2, s -> 128, s -> s.maximum, ToolSettings::size);
        form.percent("Flow", s -> s.strength, ToolSettings::strength);
        form.hint("Press harder for stronger spray. Hold or move slowly to build color. Size stays fixed.");
    }

    private static void fill(SettingsForm form, ToolSettings current) {
        if (current.gradient == ToolSettings.Gradient.FLAT) return;
        form.percent("Tolerance", s -> s.tolerance, ToolSettings::tolerance);
        form.hint(current.gradient == ToolSettings.Gradient.CIRCULAR
                ? "Drag from center to edge, then choose a second color."
                : "Drag along the gradient, then choose a second color.");
    }

    private static void shapes(SettingsForm form, ToolSettings current) {
        boolean line = current.shape == ToolSettings.Shape.LINE;
        form.visibleWhen(form.radio(filled -> filled ? "Filled" : "Outline", filled -> filled ? "Filled shape" : "Outline shape",
                s -> s.filled, ToolSettings::filled, false, true), s -> !line);
        String caption = line ? "Line width" : "Outline width";
        SeekBar width = form.slider("Shape outline width", s -> caption + "\n" + s.outlineWidth + " px",
                s -> 1, s -> 128, s -> s.outlineWidth, ToolSettings::outlineWidth);
        form.visibleWhen((View)width.getParent(), s -> line || !s.filled);
        form.hint(current.shape == ToolSettings.Shape.CIRCLE
                ? "Start at the center · drag to set the radius · lift to finish" : "Drag to size · lift to finish");
    }

    /** Linked minimum/maximum size sliders. */
    private static void sizes(SettingsForm form, String dimension, int limit) {
        form.slider("Minimum " + dimension, s -> "Min " + dimension + "\n" + s.minimum + " px",
                s -> 1, s -> s.maximum, s -> s.minimum, ToolSettings::minimum);
        form.slider("Maximum " + dimension, s -> "Max " + dimension + "\n" + s.maximum + " px",
                s -> 2, s -> limit, s -> s.maximum, ToolSettings::size);
    }

    /** The variant row above a tool's controls, or null for tools without variants. */
    static LinearLayout variants(Context context, ToolSettings current, boolean shapeChosen, Consumer<ToolSettings.Head> head,
                                 Consumer<ToolSettings.Gradient> fill, Consumer<ToolSettings.Shape> shape) {
        LinearLayout row = new LinearLayout(context);
        if (current.isBrush()) {
            row.setContentDescription("Brush tips");
            for (ToolSettings.Head choice : ToolSettings.Head.values()) {
                Button button = SettingsForm.choice(context, choice.label, ToolIcons.tip(choice), current.head == choice, () -> head.accept(choice));
                button.setContentDescription(choice.label + " " + current.label()); SettingsForm.addChoice(row, button, 84);
            }
        } else if (current.tool == ToolSettings.Tool.FILL) {
            row.setBaselineAligned(false); row.setContentDescription("Fill choices");
            for (ToolSettings.Gradient type : new ToolSettings.Gradient[]{ToolSettings.Gradient.FLAT, ToolSettings.Gradient.LINEAR, ToolSettings.Gradient.CIRCULAR}) {
                String label = type == ToolSettings.Gradient.FLAT ? "Flat fill" : type.label + " gradient";
                Button button = SettingsForm.choice(context, label, ToolIcons.fill(type), current.gradient == type, () -> fill.accept(type));
                button.setContentDescription(label); SettingsForm.addChoice(row, button, 112);
            }
        } else if (current.tool == ToolSettings.Tool.SHAPES) {
            row.setContentDescription("Shape choices");
            for (ToolSettings.Shape choice : ToolSettings.Shape.values()) {
                Button button = SettingsForm.choice(context, choice.label, ToolIcons.shape(choice),
                        current.shape == choice && shapeChosen, () -> shape.accept(choice));
                button.setContentDescription(choice.label + " shape"); SettingsForm.addChoice(row, button, 84);
            }
        } else return null;
        return row;
    }
}
