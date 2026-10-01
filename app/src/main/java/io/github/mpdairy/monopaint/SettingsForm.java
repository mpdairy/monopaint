package io.github.mpdairy.monopaint;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Builds the rows of a settings panel. Tool rows are bound to the current tool settings:
 * each change is applied through {@link Editor}, then every row refreshes from the result,
 * so linked limits (such as minimum ≤ maximum) always stay consistent.
 */
final class SettingsForm {
    /** Reads and replaces the settings being edited. */
    interface Editor {
        ToolSettings current();
        void edit(ToolSettings changed);
    }
    /** Text tag for secondary text, styled lighter and smaller. */
    static final String HINT = "hint";
    /** Text tag for "×" close buttons. */
    static final String CLOSE = "close";

    final Context context;
    final LinearLayout content;
    private final boolean largeText;
    private final Editor editor;
    private final ArrayList<Runnable> refreshers = new ArrayList<>();
    private boolean syncing;
    /** Runs after a row appears or disappears, so the popup can resize. */
    Runnable resized = () -> { };

    SettingsForm(Context context, boolean largeText, Editor editor) {
        this.context = context; this.largeText = largeText; this.editor = editor;
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(4));
    }
    private int dp(float value) { return Ui.dp(context, value); }

    /** Re-reads every bound row from the current settings. */
    void refresh() {
        syncing = true;
        try { for (Runnable refresher : refreshers) refresher.run(); }
        finally { syncing = false; }
    }
    private void change(ToolSettings changed) {
        if (syncing) return;
        editor.edit(changed); refresh();
    }

    TextView hint(String text) {
        TextView hint = hintText(context, text); content.addView(hint); return hint;
    }
    static TextView hintText(Context context, String text) {
        TextView hint = new TextView(context); hint.setTag(HINT); hint.setTextColor(Color.BLACK); hint.setText(text);
        return hint;
    }

    /** Minimum and maximum footprints at actual canvas size. */
    Footprint footprint(int heightDp, float textSize) {
        Footprint preview = new Footprint(context, editor::current, textSize);
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(heightDp)));
        refreshers.add(preview::invalidate);
        return preview;
    }

    /**
     * A slider over {@code min..max} (inclusive), labelled by {@code label}.
     * @param description accessible name of the slider
     */
    SeekBar slider(String description, Function<ToolSettings, String> label,
                   ToIntFunction<ToolSettings> min, ToIntFunction<ToolSettings> max,
                   ToIntFunction<ToolSettings> value, BiFunction<ToolSettings, Integer, ToolSettings> apply) {
        TextView caption = new TextView(context);
        SeekBar bar = new SeekBar(context); bar.setContentDescription(description);
        sliderRow(content, caption, bar, largeText);
        Runnable refresh = () -> {
            ToolSettings s = editor.current();
            int low = min.applyAsInt(s);
            caption.setText(label.apply(s));
            bar.setMax(max.applyAsInt(s) - low); bar.setProgress(value.applyAsInt(s) - low);
        };
        refreshers.add(refresh); refresh.run();
        Ui.onProgress(bar, progress -> {
            if (syncing) return;
            ToolSettings s = editor.current();
            change(apply.apply(s, min.applyAsInt(s) + progress));
        });
        return bar;
    }
    /** A 0–100% slider captioned "{caption}\n{value}%". */
    SeekBar percent(String description, String caption, ToIntFunction<ToolSettings> value,
                    BiFunction<ToolSettings, Integer, ToolSettings> apply) {
        return slider(description, s -> caption + "\n" + value.applyAsInt(s) + "%", s -> 0, s -> 100, value, apply);
    }
    SeekBar percent(String name, ToIntFunction<ToolSettings> value, BiFunction<ToolSettings, Integer, ToolSettings> apply) {
        return percent(name, name, value, apply);
    }

    CheckBox check(String text, Predicate<ToolSettings> checked, BiFunction<ToolSettings, Boolean, ToolSettings> apply) {
        CheckBox box = new CheckBox(context); box.setText(text); box.setContentDescription(text);
        box.setChecked(checked.test(editor.current())); content.addView(box);
        refreshers.add(() -> box.setChecked(checked.test(editor.current())));
        box.setOnCheckedChangeListener((button, value) -> change(apply.apply(editor.current(), value)));
        return box;
    }

    /** A row of radio buttons, one per value. */
    @SafeVarargs
    final <T> RadioGroup radio(Function<T, String> label, Function<T, String> description,
                               Function<ToolSettings, T> selected, BiFunction<ToolSettings, T, ToolSettings> apply, T... values) {
        RadioGroup group = new RadioGroup(context); group.setOrientation(LinearLayout.HORIZONTAL);
        for (T value : values) {
            RadioButton choice = new RadioButton(context);
            choice.setId(View.generateViewId()); choice.setText(label.apply(value)); choice.setTextColor(Color.BLACK);
            choice.setContentDescription(description.apply(value)); choice.setTag(value);
            group.addView(choice, new LinearLayout.LayoutParams(0, dp(56), 1));
            choice.setChecked(value.equals(selected.apply(editor.current())));
        }
        content.addView(group);
        group.setOnCheckedChangeListener((g, id) -> {
            View choice = g.findViewById(id);
            @SuppressWarnings("unchecked") T value = choice == null ? null : (T)choice.getTag();
            if (value != null) change(apply.apply(editor.current(), value));
        });
        return group;
    }

    /** Shows {@code row} only while {@code shown} holds for the current settings. */
    void visibleWhen(View row, Predicate<ToolSettings> shown) {
        Runnable refresh = () -> {
            int visibility = shown.test(editor.current()) ? View.VISIBLE : View.GONE;
            if (row.getVisibility() == visibility) return;
            row.setVisibility(visibility); resized.run();
        };
        row.setVisibility(shown.test(editor.current()) ? View.VISIBLE : View.GONE);
        refreshers.add(refresh);
    }

    /** Adds a captioned slider row: caption on the left, slider filling the rest. */
    static void sliderRow(LinearLayout parent, TextView label, SeekBar bar, boolean largeText) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        label.setTextColor(Color.BLACK);
        row.addView(label, new LinearLayout.LayoutParams(Ui.dp(context, largeText ? 156 : 140), -2));
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(context, 56), 1));
        parent.addView(row);
    }

    /** A large icon-over-label choice, as used for brush tips, fills and shapes. */
    static Button choice(Context context, String label, int icon, boolean selected, Runnable choose) {
        Button choice = new Button(context); choice.setText(label); choice.setTag(label);
        choice.setAllCaps(false); choice.setTextColor(Color.BLACK);
        choice.setGravity(Gravity.CENTER); choice.setPadding(Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 2), Ui.dp(context, 4));
        choice.setMinWidth(0); choice.setMinimumWidth(0); choice.setStateListAnimator(null);
        android.graphics.drawable.Drawable glyph = context.getDrawable(icon); glyph.setBounds(0, 0, Ui.dp(context, 32), Ui.dp(context, 32));
        choice.setCompoundDrawables(null, glyph, null, null); choice.setCompoundDrawablePadding(Ui.dp(context, 6));
        choice.setBackground(Ui.outline(selected ? 0xffeeeeee : Color.WHITE, Ui.dp(context, selected ? 2 : 1),
                selected ? Color.BLACK : 0xffbbbbbb, Ui.dp(context, 8)));
        choice.setSelected(selected);
        choice.setOnClickListener(v -> choose.run());
        return choice;
    }
    static void addChoice(LinearLayout row, Button choice, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(row.getContext(), heightDp), 1);
        params.setMargins(Ui.dp(row.getContext(), 2), 0, Ui.dp(row.getContext(), 2), 0); row.addView(choice, params);
    }

    /** Two side-by-side radio buttons; the listener receives whether the second is chosen. */
    static RadioGroup pair(Context context, String first, String second, boolean secondChosen,
                           java.util.function.Consumer<Boolean> chosen) {
        RadioGroup group = new RadioGroup(context); group.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton a = new RadioButton(context), b = new RadioButton(context);
        a.setId(View.generateViewId()); b.setId(View.generateViewId()); a.setText(first); b.setText(second);
        group.addView(a, new LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1));
        group.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1));
        group.check(secondChosen ? b.getId() : a.getId());
        group.setOnCheckedChangeListener((g, id) -> chosen.accept(id == b.getId()));
        return group;
    }

    static float textSize(boolean largeText) { return largeText ? 18 : 16; }

    /** Applies the panel typography to every text view in a tree, honoring {@link #HINT} and {@link #CLOSE} tags. */
    static void style(View view, boolean largeText) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            boolean hint = HINT.equals(text.getTag());
            float size = textSize(largeText);
            text.setTextSize(CLOSE.equals(text.getTag()) ? 22 : hint ? size-1 : size);
            text.setTextColor(Color.BLACK);
            text.setTypeface(null, hint || text instanceof EditText ? Typeface.NORMAL : Typeface.BOLD);
            if (text instanceof Button) ((Button)text).setAllCaps(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) style(group.getChildAt(i), largeText);
        }
    }
}
