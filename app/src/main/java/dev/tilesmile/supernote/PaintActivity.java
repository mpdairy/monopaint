package dev.tilesmile.supernote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;

/** Single-layer painting app with logical tools, saved presets and calibrated presentation. */
public final class PaintActivity extends Activity {
    private ShadePicker shadePicker;
    private ToolButton wetButton, opaqueButton, transparentButton;
    private WetnessBar wetnessBar;
    private boolean wetCanvas, transparentPaint;
    private int wetness = 65;
    private DocumentStore store;
    private SharedPreferences preferences;
    private DrawingPad pad;
    private Button brushButton;
    private android.widget.PopupWindow brushPicker;
    private ToolRail toolRail;
    private PresetDragHandler presetDrag;
    private TextView operationStatus;
    private ToolLibrary library;
    private DrawingBook book;
    private LinearLayout body, palette, headerControls, leftHeader, rightHeader;
    private ScrollView toolScroll;
    private android.widget.ImageButton menuButton, previousPage, nextPage, addPage;
    private TextView pageNumber;
    private final SelectionFeedback selectionFeedback = new SelectionFeedback();
    private final java.util.Map<String, ToolButton> selectionButtons = new java.util.LinkedHashMap<>();
    private int gray, maximum;
    private boolean resumed, loading = true, destroyed;
    private String drawingName = "";
    private String saveError;

    @Override protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration configuration=new android.content.res.Configuration(base.getResources().getConfiguration());
        // Increase all app text, including native dialogs, while respecting the user's font scale.
        configuration.fontScale*=1.2f;
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("painting", MODE_PRIVATE);
        selectionFeedback.enabled = preferences.getBoolean("fast_selection", false);
        wetCanvas = preferences.getBoolean("wet_canvas", false);
        transparentPaint = preferences.getBoolean("transparent_paint", false);
        wetness = Math.max(0, Math.min(100, preferences.getInt("canvas_wetness", 65)));
        if (wetness == 0) wetCanvas = false;
        gray = Math.max(0, Math.min(255, preferences.getInt("gray", 0)));
        maximum = Math.max(2, Math.min(128, preferences.getInt("diameter", 64)));
        library = new ToolLibrary(); library.edit(library.current().size(maximum));
        String savedTools = preferences.getString("tools", null);
        if (savedTools != null) {
            try { library = ToolLibrary.decode(java.util.Base64.getDecoder().decode(savedTools)); }
            catch (Exception error) {
                preferences.edit().putString("tools_unreadable_backup", savedTools).apply();
                message("Could not load presets. A recovery copy has been kept.");
            }
        }
        if (library.activeId().isEmpty() && library.current().isBrush()) library.edit(library.current().asBrush());
        maximum = library.current().maximum;
        store = new DocumentStore(getFilesDir());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        palette = new LinearLayout(this);
        leftHeader = new LinearLayout(this); rightHeader = new LinearLayout(this);
        rightHeader.setGravity(android.view.Gravity.END);
        // Reserve equal side widths, including room for the menu on either side.
        palette.addView(leftHeader, new LinearLayout.LayoutParams(dp(272), dp(48)));
        LinearLayout colors = new LinearLayout(this);
        palette.addView(colors, new LinearLayout.LayoutParams(0, dp(48), 1));
        palette.addView(rightHeader, new LinearLayout.LayoutParams(dp(272), dp(48)));
        android.widget.ImageButton files=new android.widget.ImageButton(this);menuButton=files;
        files.setImageResource(R.drawable.ic_menu);files.setBackgroundColor(Color.WHITE);
        files.setContentDescription("File menu");files.setTooltipText("File menu");
        files.setOnClickListener(v -> {
            if(busy())return;pad.finishStroke();
            fileMenu(files);
        });
        leftHeader.addView(files,new LinearLayout.LayoutParams(dp(64),dp(48)));
        leftHeader.addView(new View(this), new LinearLayout.LayoutParams(0, dp(48), 1));
        headerControls=new LinearLayout(this);
        previousPage=pageButton("Previous page",R.drawable.ic_previous,() -> changePage(book.index()-1));
        pageNumber=new TextView(this);pageNumber.setTextSize(15);pageNumber.setTypeface(null,android.graphics.Typeface.BOLD);
        pageNumber.setGravity(android.view.Gravity.CENTER);headerControls.addView(pageNumber,new LinearLayout.LayoutParams(dp(76),dp(48)));
        nextPage=pageButton("Next page",R.drawable.ic_chevron,() -> changePage(book.index()+1));
        addPage=pageButton("Add page",R.drawable.ic_new,this::addPage);
        wetButton = paintModeButton(colors, "Wet canvas", R.drawable.ic_water_drop, () -> setWetCanvas(!wetCanvas));
        wetButton.iconHalf = 18;
        wetButton.iconOffset = 8; wetButton.markerLeft = true;
        wetnessBar = new WetnessBar();
        colors.addView(wetnessBar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        shadePicker = new ShadePicker();
        colors.addView(shadePicker, new LinearLayout.LayoutParams(0, dp(48), 1));
        transparentButton = paintModeButton(colors, "Transparent paint", R.drawable.ic_transparent, () -> setTransparentPaint(true));
        opaqueButton = paintModeButton(colors, "Opaque paint", R.drawable.ic_opaque, () -> setTransparentPaint(false));
        refreshPaintModes();
        LinearLayout pageActions = new LinearLayout(this);
        pageActions.setPadding(0, 0, dp(8), 0);
        headerAction(pageActions, "Undo", R.drawable.ic_undo, () -> { pad.dryWet(); if (pad.document.undo()) { pad.renderDirty(); pad.present(); recovery(); } });
        headerAction(pageActions, "Redo", R.drawable.ic_redo, () -> { pad.dryWet(); if (pad.document.redo()) { pad.renderDirty(); pad.present(); recovery(); } });
        headerAction(pageActions, "Clear canvas", R.drawable.ic_clear, this::clearDrawing);
        leftHeader.addView(pageActions);
        rightHeader.addView(headerControls);
        root.addView(palette);
        body = new LinearLayout(this);
        ScrollView scroll = new ScrollView(this);toolScroll=scroll;
        toolRail = new ToolRail(); toolRail.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(toolRail); body.addView(scroll, new LinearLayout.LayoutParams(dp(64), -1));
        presetDrag = new PresetDragHandler();
        scroll.setOnDragListener(presetDrag);
        rebuildTools();
        pad = new DrawingPad(); body.addView(pad, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        applyToolboxSide();updatePages();
        pad.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (l != ol || t != ot || r != or || b != ob) {pad.disconnectDisplay();pad.post(pad::connectDisplay);}
        });
        store.openBook("_recovery", (document, error) -> runOnUiThread(() -> {
            if (destroyed) return;
            loading = false;
            if (error != null) {
                // Keep the failed recovery on disk until the user explicitly chooses a new drawing.
                loading = true;
                new AlertDialog.Builder(this).setTitle("Could not recover drawing")
                        .setMessage(error.getMessage() + "\nYou can open a saved drawing or start a new one.")
                        .setPositiveButton("Open", (d,w) -> openDrawing())
                        .setNegativeButton("New", (d,w) -> { loading = false; pad.replace(null); recovery(); })
                        .setCancelable(false).show();
            } else replaceBook(document);
        }));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void headerAction(LinearLayout parent, String name, int icon, Runnable action) {
        ToolButton control = (ToolButton) button(parent, name, icon, action);
        control.iconOnly(icon);
        control.setBackgroundColor(Color.WHITE);
        control.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    private android.widget.ImageButton pageButton(String name,int icon,Runnable action) {
        android.widget.ImageButton button=new android.widget.ImageButton(this);button.setImageResource(icon);
        button.setBackgroundColor(Color.WHITE);button.setContentDescription(name);button.setTooltipText(name);
        button.setOnClickListener(v -> {if(!busy())action.run();});headerControls.addView(button,new LinearLayout.LayoutParams(dp(40),dp(48)));return button;
    }
    private void updatePages() {
        if(pageNumber==null)return;int count=book==null?1:book.count(),index=book==null?0:book.index();
        pageNumber.setText((index+1)+" / "+count);pageNumber.setContentDescription("Page "+(index+1)+" of "+count);
        previousPage.setEnabled(book!=null&&index>0);nextPage.setEnabled(book!=null&&index+1<count);
        addPage.setEnabled(book!=null&&count<DrawingBook.MAX_PAGES);
        previousPage.setAlpha(previousPage.isEnabled()?1:.3f);nextPage.setAlpha(nextPage.isEnabled()?1:.3f);
    }
    private void replaceBook(DrawingBook replacement) {
        if(replacement==null){pad.replace(null);return;}
        pad.finishStroke();book=replacement;pad.showPage(book.current());updatePages();
    }
    private void changePage(int index) {
        if(busy()||index<0||index>=book.count())return;pad.finishStroke();
        try {book.select(index);pad.showPage(book.current());updatePages();recovery();}
        catch(java.io.IOException error){message("Could not open page: "+error.getMessage());}
    }
    private void addPage() {
        if(busy())return;pad.finishStroke();
        try {book.addPage();pad.showPage(book.current());updatePages();recovery();}
        catch(java.io.IOException error){message("Could not add page: "+error.getMessage());}
    }
    private void applyToolboxSide() {
        boolean right=preferences.getBoolean("toolbox_right",false);
        if(pad!=null){pad.finishStroke();pad.disconnectDisplay();}
        body.removeView(toolScroll);body.addView(toolScroll,right?body.getChildCount():0,new LinearLayout.LayoutParams(dp(64),-1));
        ((android.view.ViewGroup)menuButton.getParent()).removeView(menuButton);
        LinearLayout menuSide = right ? rightHeader : leftHeader;
        menuSide.addView(menuButton,right?menuSide.getChildCount():0,new LinearLayout.LayoutParams(dp(64),dp(48)));
        if(pad!=null)pad.post(pad::connectDisplay);
    }
    private void appSettings() {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView label=new TextView(this);label.setText("Toolbox side");content.addView(label);
        android.widget.RadioGroup sides=new android.widget.RadioGroup(this);sides.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton left=new android.widget.RadioButton(this),right=new android.widget.RadioButton(this);
        left.setId(View.generateViewId());right.setId(View.generateViewId());left.setText("Left");right.setText("Right");
        sides.addView(left,new LinearLayout.LayoutParams(0,dp(48),1));sides.addView(right,new LinearLayout.LayoutParams(0,dp(48),1));
        sides.check(preferences.getBoolean("toolbox_right",false)?right.getId():left.getId());content.addView(sides);
        sides.setOnCheckedChangeListener((group,id) -> {preferences.edit().putBoolean("toolbox_right",id==right.getId()).apply();applyToolboxSide();});
        feedbackSetting(content);styleSettings(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Settings").setView(content).setPositiveButton("Done",null).create();dialog.show();compactDialog(dialog);
    }
    private void fileMenu(View anchor) {
        String[] names={"New drawing","Open drawing","Save drawing","Settings"};
        int[] icons={R.drawable.ic_new,R.drawable.ic_open,R.drawable.ic_save,R.drawable.ic_settings};
        android.widget.ListPopupWindow menu=new android.widget.ListPopupWindow(this);
        menu.setAnchorView(anchor);menu.setModal(true);menu.setWidth(dp(220));
        menu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        menu.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount(){return names.length;}
            @Override public Object getItem(int position){return names[position];}
            @Override public long getItemId(int position){return position;}
            @Override public View getView(int position,View recycled,android.view.ViewGroup parent) {
                TextView row=new TextView(PaintActivity.this);row.setText(names[position]);row.setTextColor(Color.BLACK);
                android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
                border.setColor(Color.WHITE);border.setStroke(dp(1),Color.BLACK);row.setBackground(border);
                row.setTextSize(16);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinHeight(dp(52));
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[position],0,0,0);row.setCompoundDrawablePadding(dp(12));
                return row;
            }
        });
        menu.setOnItemClickListener((parent,view,position,id) -> {
            menu.dismiss();if(busy())return;pad.finishStroke();
            if(position==0)newDrawing();else if(position==1)openDrawing();else if(position==2)saveDrawing();else appSettings();
        });menu.show();
    }
    private Button button(LinearLayout parent, String title, int icon, Runnable action) {
        Button button = new ToolButton(); button.setText(title); button.setAllCaps(false);
        button.setTag(title); button.setContentDescription(title); button.setTooltipText(title);
        button.setTextSize(14); button.setPadding(dp(10), 0, dp(8), 0);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(4));
        button.setOnClickListener(v -> {
            if (busy()) return;
            pad.finishStroke(); action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(60));
        params.gravity=android.view.Gravity.CENTER_HORIZONTAL;
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        parent.addView(button, params); return button;
    }
    private boolean busy() { return loading || pad == null || pad.document == null || pad.fill != null; }
    private void preferences() {
        try {
            preferences.edit().putInt("gray", gray).putInt("diameter", maximum)
                    .putBoolean("wet_canvas", wetCanvas).putBoolean("transparent_paint", transparentPaint)
                    .putInt("canvas_wetness", wetness)
                    .putString("tools", java.util.Base64.getEncoder().encodeToString(library.encode())).apply();
        } catch (java.io.IOException error) { message("Could not save tool settings: " + error.getMessage()); }
    }
    private int icon(ToolSettings.Tool tool) {
        switch (tool) {
            case WATERCOLOR: return R.drawable.ic_watercolor;
            case FLAT_WASH: return R.drawable.ic_flat_wash;
            case WET_WATERCOLOR: return R.drawable.ic_wet_watercolor;
            case PENCIL: return R.drawable.ic_pencil;
            case FILL: return R.drawable.ic_fill;
            case ERASER: return R.drawable.ic_eraser;
            case SOFTEN: return R.drawable.ic_soften;
            default: return R.drawable.ic_brush;
        }
    }
    private int icon(ToolSettings settings) {
        settings = settings.asBrush();
        if (settings.isBrush() && settings.head != ToolSettings.Head.ROUND) {
            return settings.head == ToolSettings.Head.FLAT ? R.drawable.ic_brush_flat : R.drawable.ic_brush_filbert;
        }
        return icon(settings.tool);
    }
    private void markActive(Button button, boolean selected) {
        android.graphics.drawable.GradientDrawable active = new android.graphics.drawable.GradientDrawable();
        boolean outline=selected && !selectionFeedback.enabled;
        active.setColor(Color.WHITE); active.setStroke(outline ? dp(2) : 1, outline ? Color.BLACK : 0xffaaaaaa); active.setCornerRadius(dp(4));
        button.setBackground(active); button.setSelected(selected);
    }
    private String selectedKey() {
        return library.activeId().isEmpty() ? "tool:" + library.current().tool : library.activeId();
    }
    private void refreshToolSelection() {
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
            ToolButton button = selectionButtons.get("tool:" + tool);
            if (button != null) {
                ToolSettings settings = library.builtin(tool);
                button.iconOnly(icon(settings));
                button.setContentDescription(toolDescription(settings));
                button.setTooltipText(settings.description());
            }
        }
        String selected = selectedKey();
        for (java.util.Map.Entry<String, ToolButton> entry : selectionButtons.entrySet()) {
            ToolButton button = entry.getValue(); boolean active = entry.getKey().equals(selected);
            if (button.marked != active) {
                selectionFeedback.update(button, button.markerArea(), () -> {
                    button.marked = active;
                    // Instant mode keeps the border fixed; only the corner dot changes.
                    if(selectionFeedback.enabled)
                        button.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                    else markActive(button,active);
                });
            }
        }
    }
    private final class ToolButton extends Button {
        boolean marked, alwaysDot, markerLeft;
        int iconHalf = 14, iconOffset;
        String presetId;
        private android.graphics.drawable.Drawable centerIcon;
        private int iconResource;
        private android.graphics.drawable.Drawable settingsArrow;
        private final Paint markerPaint = new Paint();
        ToolButton() {
            super(PaintActivity.this);
            // Flat icon controls have no pressed elevation to animate after a tap.
            setStateListAnimator(null);setElevation(0);
        }
        @Override public boolean isSelected() {
            return settingsArrow != null || alwaysDot ? marked : super.isSelected();
        }
        void iconOnly(int resource) {
            if (iconResource == resource) return;
            iconResource = resource;
            setText("");setCompoundDrawables(null,null,null,null);setMinWidth(0);setMinimumWidth(0);
            // Empty button text still has themed pressed colors which can invalidate it.
            setTextColor(Color.BLACK);setHintTextColor(Color.BLACK);setLinkTextColor(Color.BLACK);
            centerIcon=getDrawable(resource);invalidate();
        }
        Rect markerArea() {
            return new Rect(markerLeft ? 0 : Math.max(0,getWidth()-dp(24)), 0,
                    markerLeft ? Math.min(getWidth(),dp(24)) : getWidth(), Math.min(getHeight(),dp(24)));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(centerIcon!=null) {
                int half=dp(iconHalf),x=getWidth()/2+dp(iconOffset),y=getHeight()/2-(presetId==null?0:dp(4));
                centerIcon.setBounds(x-half,y-half,x+half,y+half);centerIcon.draw(canvas);
            }
            if (presetId != null) {
                markerPaint.setColor(Color.BLACK);
                canvas.drawCircle(getWidth()/2f, getHeight()-dp(9), dp(2), markerPaint);
            }
            if(settingsArrow!=null) {
                int x=getWidth()-dp(10),y=getHeight()/2;
                settingsArrow.setBounds(x-dp(8),y-dp(10),x+dp(8),y+dp(10));settingsArrow.draw(canvas);
            }
            if (!marked || (!alwaysDot && !selectionFeedback.enabled)) return;
            float x=markerLeft ? dp(12) : getWidth()-dp(12),y=dp(12);
            markerPaint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(6),markerPaint);
            markerPaint.setColor(Color.BLACK);canvas.drawCircle(x,y,dp(4),markerPaint);
        }
    }
    private final class ToolDivider extends View {
        private final Paint dashPaint = new Paint();
        ToolDivider() {
            super(PaintActivity.this);dashPaint.setColor(Color.BLACK);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            // Filled rectangles stay visible even when a stroked drawable's bounds are inset.
            int dash=dp(4),gap=dp(3),top=(getHeight()-dp(2))/2;
            for(int x=0;x<getWidth();x+=dash+gap)
                canvas.drawRect(x,top,Math.min(x+dash,getWidth()),top+dp(2),dashPaint);
        }
    }
    private final class ToolRail extends LinearLayout {
        float dropLine = -1;
        private final Paint linePaint = new Paint();
        ToolRail() { super(PaintActivity.this); }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (dropLine >= 0) {
                linePaint.setColor(Color.BLACK); linePaint.setStrokeWidth(dp(2));
                canvas.drawLine(dp(4), dropLine, getWidth()-dp(4), dropLine, linePaint);
            }
        }
    }
    private final class PresetDragHandler implements View.OnDragListener, Runnable {
        private ToolButton source;
        private String beforeId;
        private float pointerY;
        private boolean validDrop;
        private int scrollStep;
        @Override public boolean onDrag(View view, android.view.DragEvent event) {
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_STARTED) {
                if (busy() || !(event.getLocalState() instanceof ToolButton)) return false;
                ToolButton button = (ToolButton) event.getLocalState();
                if (button.presetId == null || button.getParent() != toolRail) return false;
                source = button; source.setAlpha(.4f); return true;
            }
            if (source == null || event.getLocalState() != source) return false;
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    pointerY = event.getY(); updateTarget();
                    toolScroll.removeCallbacks(this);
                    scrollStep = pointerY < dp(48) ? -dp(12)
                            : pointerY > toolScroll.getHeight()-dp(48) ? dp(12) : 0;
                    if (scrollStep != 0) toolScroll.postDelayed(this, 60);
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                    clearTarget(); return true;
                case android.view.DragEvent.ACTION_DROP:
                    pointerY = event.getY(); updateTarget();
                    if (!validDrop || busy()) return false;
                    library.moveBefore(source.presetId, beforeId);
                    preferences(); rebuildTools(); return true;
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    reset(); return true;
                default: return true;
            }
        }
        private void updateTarget() {
            float y = pointerY + toolScroll.getScrollY() - toolRail.getTop();
            beforeId = null; validDrop = false; toolRail.dropLine = -1;
            if (!library.presets().isEmpty()) {
                ToolButton first = selectionButtons.get(library.presets().get(0).id);
                validDrop = first != null && y >= first.getTop()-dp(4);
            }
            if (validDrop) {
                for (ToolLibrary.Preset preset : library.presets()) {
                    ToolButton button = selectionButtons.get(preset.id);
                    if (button == null || button == source) continue;
                    if (y < (button.getTop()+button.getBottom())/2f) {
                        beforeId = preset.id; toolRail.dropLine = button.getTop()-dp(2); break;
                    }
                    toolRail.dropLine = button.getBottom()+dp(2);
                }
                if (toolRail.dropLine < 0) toolRail.dropLine = source.getBottom()+dp(2);
            }
            toolRail.invalidate();
        }
        @Override public void run() {
            if (source == null || scrollStep == 0) return;
            int previous = toolScroll.getScrollY();
            toolScroll.scrollBy(0, scrollStep); updateTarget();
            if (toolScroll.getScrollY() != previous) toolScroll.postDelayed(this, 60);
        }
        private void clearTarget() {
            toolScroll.removeCallbacks(this); scrollStep = 0; validDrop = false;
            toolRail.dropLine = -1; toolRail.invalidate();
        }
        void reset() {
            clearTarget();
            if (source != null) source.setAlpha(1);
            source = null;
        }
    }
    private Button toolRow(String key,String name,int icon,Runnable select) {
        ToolButton control=(ToolButton)button(toolRail,name,icon,() -> {
            if(selectedKey().equals(key)) {
                if (library.current().isBrush() && library.activeId().isEmpty())
                    showBrushPicker(library.current().tool);
                else settings();
            } else select.run();
        });
        control.iconOnly(icon);control.settingsArrow=getDrawable(R.drawable.ic_chevron);
        control.setContentDescription(name+". Tap to select; tap again for settings.");
        control.setOnLongClickListener(v -> {
            if(!busy()){pad.finishStroke();if(!selectedKey().equals(key))select.run();settings();}return true;
        });
        return control;
    }
    private void rebuildTools() {
        if (library.activeId().isEmpty() && library.current().isBrush()) library.edit(library.current().asBrush());
        toolRail.removeAllViews(); selectionButtons.clear();
        for (ToolSettings.Tool tool : new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,
                ToolSettings.Tool.PENCIL, ToolSettings.Tool.FILL, ToolSettings.Tool.ERASER, ToolSettings.Tool.SOFTEN}) {
            ToolSettings remembered = library.builtin(tool);
            Button b = toolRow("tool:"+tool,remembered.label(), icon(remembered), () -> {
                library.select(tool); maximum = library.current().maximum; refreshToolSelection(); preferences();
            });
            b.setContentDescription(toolDescription(remembered));b.setTooltipText(remembered.description());
            if (remembered.isBrush()) b.setOnLongClickListener(v -> {
                if (!busy()) { pad.finishStroke(); showBrushPicker(tool); }
                return true;
            });
            if (tool == ToolSettings.Tool.BRUSH) brushButton = b;
            selectionButtons.put("tool:"+tool,(ToolButton)b);
            ((ToolButton)b).marked = selectedKey().equals("tool:"+tool); markActive(b,((ToolButton)b).marked);
        }
        View divider = new ToolDivider();
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(36), dp(6));
        dividerParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        dividerParams.setMargins(0, dp(10), 0, dp(10));
        toolRail.addView(divider, dividerParams);
        for (ToolLibrary.Preset preset : library.presets()) {
            Button b = toolRow(preset.id,preset.name, icon(preset.settings), () -> {
                library.recall(preset.id); maximum=library.current().maximum; refreshToolSelection(); preferences();
            });
            b.setTextSize(12); b.setMaxLines(2); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ((ToolButton)b).presetId = preset.id;
            b.setContentDescription(preset.name + ", " + preset.settings.description() + ", " + preset.settings.maximum + " px. Tap to select; tap again for settings. Hold and drag to reorder.");
            selectionButtons.put(preset.id,(ToolButton)b);
            ((ToolButton)b).marked = selectedKey().equals(preset.id); markActive(b,((ToolButton)b).marked);
            b.setOnLongClickListener(v -> {
                if (!busy()) {
                    pad.finishStroke();
                    v.startDragAndDrop(null, new View.DragShadowBuilder(v), v, 0);
                }
                return true;
            });
        }
        operationStatus = new TextView(this); operationStatus.setPadding(dp(8),dp(4),dp(4),dp(4)); toolRail.addView(operationStatus);
    }
    private String toolDescription(ToolSettings settings) {
        settings = settings.asBrush();
        return settings.description()+(settings.isBrush()
                ? ". Tap to select; tap again or hold for brush heads and settings."
                : ". Tap to select; tap again for settings.");
    }
    private int tipIcon(ToolSettings.Head head) {
        return head==ToolSettings.Head.FLAT?R.drawable.ic_tip_flat
                :head==ToolSettings.Head.FILBERT?R.drawable.ic_tip_filbert:R.drawable.ic_tip_round;
    }
    private LinearLayout headChoices(ToolSettings settings, java.util.function.Consumer<ToolSettings.Head> choose) {
        LinearLayout row = new LinearLayout(this);
        row.setContentDescription("Brush tips");
        for (ToolSettings.Head head : ToolSettings.Head.values()) {
            Button choice = new Button(this);choice.setText(head.label);choice.setTag(head.label);
            choice.setAllCaps(false);choice.setTextSize(13);choice.setTextColor(Color.BLACK);
            choice.setGravity(android.view.Gravity.CENTER);
            choice.setPadding(dp(4),dp(8),dp(4),dp(6));choice.setMinWidth(0);choice.setMinimumWidth(0);
            choice.setStateListAnimator(null);
            android.graphics.drawable.Drawable tip=getDrawable(tipIcon(head));
            tip.setBounds(0,0,dp(44),dp(44));
            choice.setCompoundDrawables(null,tip,null,null);choice.setCompoundDrawablePadding(dp(4));
            choice.setContentDescription(head.label+" "+settings.label());
            android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
            border.setColor(Color.WHITE);border.setStroke(dp(settings.head==head?2:1),settings.head==head?Color.BLACK:0xffbbbbbb);
            border.setCornerRadius(dp(14));
            choice.setBackground(border);choice.setSelected(settings.head==head);
            choice.setOnClickListener(v -> choose.accept(head));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(88),1);
            params.setMargins(dp(3),0,dp(3),0);row.addView(choice,params);
        }
        return row;
    }
    private LinearLayout brushEditor() {
        LinearLayout editor=new LinearLayout(this);editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(12),dp(12),dp(12),dp(4));
        Runnable[] render=new Runnable[1];
        render[0]=() -> {
            editor.removeAllViews();ToolSettings current=library.current();
            editor.addView(headChoices(current,head -> {
                if (busy() || head==library.current().head) return;
                library.selectHead(head);maximum=library.current().maximum;preferences();refreshToolSelection();
                render[0].run();
            }));
            View divider=new View(this);divider.setBackgroundColor(0xffcccccc);
            LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));
            line.setMargins(0,dp(14),0,dp(10));editor.addView(divider,line);
            TextView title=new TextView(this);title.setText(current.head.label);title.setTextSize(16);
            title.setTextColor(Color.BLACK);title.setTypeface(null,android.graphics.Typeface.BOLD);editor.addView(title);
            TextView hint=new TextView(this);hint.setTextSize(12);hint.setTextColor(Color.DKGRAY);
            hint.setText(current.head==ToolSettings.Head.ROUND?"Even in every direction"
                    :current.head==ToolSettings.Head.FLAT?"Fine, straight edge · follows tilt":"Full, rounded edge · follows tilt");editor.addView(hint);
            Footprint preview=new Footprint();editor.addView(preview,new LinearLayout.LayoutParams(-1,dp(112)));
            TextView low=new TextView(this),high=new TextView(this);
            SeekBar minimumSize=new SeekBar(this),size=new SeekBar(this);
            String dimension=current.head==ToolSettings.Head.ROUND?"diameter":"width";
            minimumSize.setContentDescription("Minimum "+dimension);size.setContentDescription("Maximum "+dimension);
            sliderRow(editor,low,minimumSize,true);sliderRow(editor,high,size,true);
            boolean[] syncing={false};
            Runnable update=() -> {
                syncing[0]=true;ToolSettings settings=library.current();
                low.setText("Min "+dimension+"\n"+settings.minimum+" px");high.setText("Max "+dimension+"\n"+settings.maximum+" px");
                minimumSize.setMax(settings.maximum-1);minimumSize.setProgress(settings.minimum-1);
                size.setMax(126);size.setProgress(settings.maximum-2);syncing[0]=false;preview.invalidate();
            };
            minimumSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                    if(syncing[0])return;library.edit(library.current().minimum(value+1));update.run();preferences();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                    if(syncing[0])return;maximum=value+2;library.edit(library.current().size(maximum));update.run();preferences();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            settingSlider(editor,"Pressure response",current.pressureResponse,true,value -> {
                library.edit(library.current().pressureResponse(value));preferences();
            });
            if(current.tool!=ToolSettings.Tool.BRUSH) {
                TextView mode=new TextView(this);mode.setTextSize(12);mode.setTextColor(Color.DKGRAY);mode.setPadding(0,dp(6),0,0);
                mode.setText(current.tool==ToolSettings.Tool.WATERCOLOR?"Black dots; white adds no ink."
                        :current.tool==ToolSettings.Tool.FLAT_WASH?"Even gray; darker marks stay."
                        :"Colors mingle while wet. White adds water; use the dryer to set.");editor.addView(mode);
            }
            update.run();
        };
        render[0].run();return editor;
    }
    private void showBrushPicker(ToolSettings.Tool tool) {
        if (brushPicker != null) brushPicker.dismiss();
        library.select(tool);maximum=library.current().maximum;preferences();refreshToolSelection();
        LinearLayout panel = new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(2),dp(2),dp(2),dp(2));
        TextView title=new TextView(this);title.setText(library.current().label());title.setTextSize(17);
        title.setTextColor(Color.BLACK);title.setTypeface(null,android.graphics.Typeface.BOLD);
        title.setPadding(dp(16),dp(14),dp(16),0);panel.addView(title);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.addView(brushEditor());
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(android.view.Gravity.END);
        Button add=new Button(this);add.setText("Add to Toolbar");add.setAllCaps(false);add.setTextSize(12);
        add.setOnClickListener(v -> {
            try {library.add();preferences();brushPicker.dismiss();rebuildTools();}
            catch(IllegalStateException error){message(error.getMessage());}
        });
        actions.addView(add,new LinearLayout.LayoutParams(0,dp(48),1));
        Button done=new Button(this);done.setText("Done");done.setAllCaps(false);done.setTextSize(12);
        done.setOnClickListener(v -> brushPicker.dismiss());actions.addView(done,new LinearLayout.LayoutParams(dp(76),dp(48)));
        for(Button action:new Button[]{add,done}) {
            action.setBackgroundColor(Color.WHITE);action.setTextColor(Color.BLACK);
            action.setStateListAnimator(null);action.setTypeface(null,android.graphics.Typeface.BOLD);
        }
        View divider=new View(this);divider.setBackgroundColor(0xffcccccc);panel.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        panel.addView(actions);
        View root=getWindow().getDecorView();
        int width=Math.min(dp(336),root.getWidth()-dp(24));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(panel.getMeasuredHeight()+dp(2),root.getHeight()-dp(24));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
        android.widget.PopupWindow popup=new android.widget.PopupWindow(panel,width,height,true);
        android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
        border.setColor(Color.WHITE);border.setStroke(dp(1),Color.BLACK);border.setCornerRadius(dp(6));
        popup.setBackgroundDrawable(border);popup.setOutsideTouchable(true);popup.setElevation(0);popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> { if (brushPicker==popup) brushPicker=null; });brushPicker=popup;
        popup.showAtLocation(root,android.view.Gravity.CENTER,0,0);
    }
    private AlertDialog settings() {
        ToolSettings current=library.current();
        if (current.isBrush()) return showToolSettings(brushEditor(),current.label());
        if(current.tool==ToolSettings.Tool.FILL) {
            LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(8),dp(16),dp(8));
            settingSlider(content,"Tolerance",current.tolerance,value -> {
                library.edit(library.current().tolerance(value));preferences();
            });
            TextView note=new TextView(this);note.setText("0% fills only the exact shade. Higher values include connected shades close to where you tap. Undo reverses the whole fill.");note.setTag("hint");content.addView(note);
            return showToolSettings(content,"Flood fill");
        }
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        Footprint preview = new Footprint(); content.addView(preview, new LinearLayout.LayoutParams(-1, dp(84)));
        final SeekBar[] tipControl = new SeekBar[1];
        final TextView[] tipLabel = new TextView[1];
        final boolean[] syncing = {false};
        String dimension="diameter";
        TextView minimumLabel=new TextView(this);
        SeekBar minimumSize=new SeekBar(this);minimumSize.setContentDescription("Minimum "+dimension);
        sliderRow(content,minimumLabel,minimumSize,false);
        TextView label = new TextView(this);
        SeekBar size = new SeekBar(this); size.setContentDescription("Maximum "+dimension);size.setMax(126); size.setProgress(maximum - 2);
        sliderRow(content,label,size,false);
        Runnable update = () -> {
            syncing[0]=true;ToolSettings s=library.current();
            minimumLabel.setText(("Minimum "+dimension+": ")+s.minimum+" px");
            label.setText(("Maximum "+dimension+": ")+s.maximum+" px");
            minimumSize.setMax(s.maximum-1);minimumSize.setProgress(s.minimum-1);size.setProgress(s.maximum-2);
            if(tipControl[0]!=null) {
                tipControl[0].setMax(s.maximum-s.minimum);tipControl[0].setProgress(s.tip-s.minimum);
                tipLabel[0].setText("Upright tip at full pressure: "+s.tip+" px");
            }
            syncing[0]=false;preview.invalidate();
        };
        minimumSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                if(syncing[0])return;library.edit(library.current().minimum(value+1));update.run();preferences();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if(syncing[0])return;
                maximum = value + 2; library.edit(library.current().size(maximum));
                update.run(); preferences();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        if(current.tool==ToolSettings.Tool.ERASER) {
            settingSlider(content,"Softness",current.softness,value -> {
                library.edit(library.current().softness(value));preferences();preview.invalidate();
            });
            TextView note=new TextView(this);note.setText("0% erases cleanly. Higher values fade with rubbing.");note.setTag("hint");content.addView(note);
        }
        if(current.tool==ToolSettings.Tool.PENCIL) {
            settingSlider(content,"Hardness",current.hardness,value -> {
                library.edit(library.current().hardness(value));preferences();preview.invalidate();
            });
            TextView note=new TextView(this);note.setText("Soft = darker. Hard = lighter.");note.setTag("hint");content.addView(note);
            CheckBox tilt=new CheckBox(this); tilt.setText("Broaden with tilt"); tilt.setChecked(current.tilt); content.addView(tilt);
            tilt.setOnCheckedChangeListener((b,checked) -> { ToolSettings s=library.current(); library.edit(s.options(s.tip,s.soft,checked)); preferences(); preview.invalidate(); });
            tipLabel[0]=new TextView(this);content.addView(tipLabel[0]);
            SeekBar tip=new SeekBar(this);tip.setContentDescription("Upright tip at full pressure");tipControl[0]=tip;content.addView(tip);
            tip.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                    if(syncing[0])return;
                    ToolSettings s=library.current(); library.edit(s.options(value+s.minimum,s.soft,s.tilt));
                    update.run();preferences();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
        }
        if(current.tool==ToolSettings.Tool.SOFTEN) {
            settingSlider(content,"Strength",current.strength,value -> {
                library.edit(library.current().strength(value));preferences();
            });
            TextView note=new TextView(this); note.setText("Pull shading in the direction you rub. Lower strength blends gently; repeat passes to build it up.");note.setTag("hint");content.addView(note);
        }
        update.run();
        return showToolSettings(content,current.description());
    }

    private void sliderRow(LinearLayout content,TextView label,SeekBar bar,boolean compact) {
        if (!compact) { content.addView(label);content.addView(bar);return; }
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        label.setTextSize(13);label.setTextColor(Color.BLACK);
        row.addView(label,new LinearLayout.LayoutParams(dp(112),-2));
        row.addView(bar,new LinearLayout.LayoutParams(0,dp(48),1));
        content.addView(row);
    }
    private void settingSlider(LinearLayout content,String name,int initial,java.util.function.IntConsumer change) {
        settingSlider(content,name,initial,false,change);
    }
    private void settingSlider(LinearLayout content,String name,int initial,boolean compact,java.util.function.IntConsumer change) {
        String caption=compact?"Pressure\n":name+": ";
        TextView label=new TextView(this);label.setText(caption+initial+"%");
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(initial);bar.setContentDescription(name);
        sliderRow(content,label,bar,compact);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider,int value,boolean user){label.setText(caption+value+"%");change.accept(value);}
            @Override public void onStartTrackingTouch(SeekBar slider){}
            @Override public void onStopTrackingTouch(SeekBar slider){}
        });
    }
    private AlertDialog showToolSettings(LinearLayout content,String title) {
        String presetId=library.activeId();
        boolean custom=!presetId.isEmpty();
        if(custom) title="Custom "+title;
        if(!library.current().isBrush())styleSettings(content);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Done",null)
                .setNeutralButton(custom?"Delete custom tool":"Add to Toolbar",null).create();
        dialog.setOnDismissListener(d -> rebuildTools()); dialog.show();compactDialog(dialog);
        Button action=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if(custom) {
            action.setText("");action.setContentDescription("Delete custom tool");action.setTooltipText("Delete custom tool");
            action.setMinWidth(dp(48));action.setMinimumWidth(dp(48));action.setMinHeight(dp(48));
            action.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_trash,0,0,0);
        }
        action.setOnClickListener(v -> {
            try {
                if(custom) library.remove(presetId); else library.add();
                preferences();dialog.dismiss();
            } catch(IllegalStateException error) { message(error.getMessage()); }
        });
        return dialog;
    }
    private void compactDialog(AlertDialog dialog) {
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(440),getResources().getDisplayMetrics().widthPixels-dp(32)),-2);
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
            Button b=dialog.getButton(which);if(b!=null){b.setAllCaps(false);b.setTypeface(null,android.graphics.Typeface.BOLD);}
        }
    }
    private void styleSettings(LinearLayout content) {
        for(int i=0;i<content.getChildCount();i++) {
            View v=content.getChildAt(i);
            if(v instanceof SeekBar) {
                v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(40)));
            } else if(v instanceof TextView) {
                TextView text=(TextView)v;text.setTextColor(Color.BLACK);
                boolean hint="hint".equals(text.getTag());text.setTextSize(hint?14:16);
                text.setTypeface(null,hint?android.graphics.Typeface.NORMAL:android.graphics.Typeface.BOLD);
                if(!(v instanceof Button))text.setPadding(0,dp(5),0,dp(2));
            }
        }
    }
    private void feedbackSetting(LinearLayout content) {
        CheckBox feedback=new CheckBox(this);feedback.setText("Instant selection dots (experimental)");
        feedback.setChecked(selectionFeedback.enabled);content.addView(feedback);
        feedback.setOnCheckedChangeListener((button,checked) -> {
            selectionFeedback.enabled=checked; preferences.edit().putBoolean("fast_selection",checked).apply();
            for(ToolButton control:selectionButtons.values()) { markActive(control,control.marked);control.invalidate(); }
        });
    }
    private void recovery() {
        if (loading || pad.document == null) return;
        store.recoverLater(new DocumentStore.Snapshot(book), (unused, error) -> {
            if (error != null) {
                Log.e(ProbeActivity.TAG, "Drawing recovery save failed", error);
                runOnUiThread(() -> saveError = error.getMessage());
            }
        });
    }
    private void showSaveError() {
        if (saveError != null) { message("Autosave failed: " + saveError); saveError = null; }
    }
    private void newDrawing() {
        new AlertDialog.Builder(this).setTitle("New drawing?")
                .setMessage("Start a new drawing with one blank page. Save first to keep all pages of this drawing.")
                .setPositiveButton("New", (d,w) -> { drawingName = ""; pad.replace(null); recovery(); })
                .setNegativeButton("Cancel", null).show();
    }
    private void clearDrawing() {
        new AlertDialog.Builder(this).setTitle("Clear this page?")
                .setMessage("Remove every mark on this page. Other pages stay as they are. You can undo this.")
                .setPositiveButton("Clear",(d,w) -> {
                    pad.dryWet();
                    if(pad.document.clear()) {pad.renderDirty();pad.present();recovery();}
                }).setNegativeButton("Cancel",null).show();
    }
    private void saveDrawing() {
        showSaveError();
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Drawing name"); name.setText(drawingName);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save drawing").setView(name)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (!DocumentStore.validName(value) || value.equals("_recovery")) {
                name.setError("Use 1–64 letters, numbers, spaces, - or _"); return;
            }
            store.list((names, error) -> runOnUiThread(() -> {
                if (destroyed) return;
                Runnable save = () -> {
                    pad.dryWet(); drawingName = value; dialog.dismiss();
                    store.save(value, new DocumentStore.Snapshot(book), (ignored, failure) ->
                            runOnUiThread(() -> { if (!destroyed) message(failure == null ? "Saved " + value : "Save failed: " + failure.getMessage()); }));
                };
                if (java.util.Arrays.asList(names).contains(value))
                    new AlertDialog.Builder(this).setTitle("Replace “" + value + "”?")
                            .setPositiveButton("Replace", (d,w) -> save.run()).setNegativeButton("Cancel", null).show();
                else save.run();
            }));
        }));
        dialog.show();
    }
    private void openDrawing() {
        showSaveError();
        store.list((names, error) -> runOnUiThread(() -> {
            if (destroyed) return;
            if (names.length == 0) { message("No named drawings yet"); if (loading) openRecoveryChoice(); return; }
            new AlertDialog.Builder(this).setTitle("Open drawing (replaces current canvas)")
                    .setItems(names, (d,index) -> {
                        loading = true;
                        store.openBook(names[index], (document, failure) -> runOnUiThread(() -> {
                            if (destroyed) return;
                            loading = false;
                            if (failure != null) {
                                message("Open failed: " + failure.getMessage());
                                if (pad.document == null) { loading = true; openRecoveryChoice(); }
                                return;
                            }
                            drawingName = names[index]; replaceBook(document); recovery();
                        }));
                    }).setNegativeButton("Cancel", (d,w) -> { if (loading) openRecoveryChoice(); }).show();
        }));
    }
    private void openRecoveryChoice() {
        new AlertDialog.Builder(this).setTitle("Start a new drawing?")
                .setMessage("The previous recovery file could not be read.")
                .setPositiveButton("New", (d,w) -> { loading = false; pad.replace(null); recovery(); })
                .setNegativeButton("Open", (d,w) -> openDrawing()).setCancelable(false).show();
    }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); resumed = true; if (pad != null) pad.post(pad::connectDisplay); }
    @Override protected void onPause() {
        if (brushPicker != null) brushPicker.dismiss();
        resumed = false;
        if (presetDrag != null) presetDrag.reset();
        if (pad != null) { pad.finishStroke(); pad.dryWet(); pad.disconnectDisplay(); }
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (pad == null) return;
        if (focus) { pad.post(pad::connectDisplay); showSaveError(); }
        else { pad.finishStroke(); pad.disconnectDisplay(); }
    }
    @Override protected void onDestroy() {
        destroyed = true;
        selectionFeedback.close();
        if (pad != null) pad.close();
        super.onDestroy();
    }

    private void selectShade(int value) {
        if (busy()) return;
        pad.finishStroke();
        if (gray == value) return;
        selectionFeedback.update(shadePicker, new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()), () -> gray = value);
    }
    private ToolButton paintModeButton(LinearLayout parent, String name, int icon, Runnable action) {
        ToolButton control = (ToolButton)button(parent, name, icon, action);
        control.iconOnly(icon); control.alwaysDot = true;
        control.setBackgroundColor(Color.WHITE);
        control.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return control;
    }
    private void markPaintMode(ToolButton control, boolean selected) {
        if (control.marked == selected) return;
        selectionFeedback.update(control, control.markerArea(), () -> {
            control.marked = selected;
            control.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        });
    }
    private void refreshPaintModes() {
        markPaintMode(wetButton, wetCanvas);
        markPaintMode(transparentButton, transparentPaint);
        markPaintMode(opaqueButton, !transparentPaint);
    }
    private void setWetCanvas(boolean value) {
        if (busy()) return;
        pad.finishStroke();
        if (value && wetness == 0) wetnessBar.select(65);
        wetCanvas = value;
        if (!value) pad.dryWet();
        refreshPaintModes(); preferences();
    }
    private void setTransparentPaint(boolean value) {
        if (busy()) return;
        pad.finishStroke(); transparentPaint = value;
        refreshPaintModes(); preferences();
    }
    private final class WetnessBar extends View {
        private final Paint ink = new Paint();
        private int activePointer = -1;
        WetnessBar() {
            super(PaintActivity.this); setFocusable(true); setClickable(true);
            describe();
        }
        private void describe() { setContentDescription("Canvas wetness: " + wetness + "%. Slide above zero to enable blending; zero dries the canvas."); }
        private void select(int amount) {
            amount = Math.max(0, Math.min(100, amount));
            if (busy() || (wetness == amount && wetCanvas == (amount > 0))) return;
            pad.finishStroke();
            final int selected = amount;
            selectionFeedback.update(this, new Rect(0, 0, getWidth(), getHeight()), () -> {
                wetness = selected; describe();
            });
            wetCanvas = amount > 0;
            if (pad.wet != null) pad.wet.setWetness(wetness);
            if (!wetCanvas) pad.dryWet();
            refreshPaintModes();
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            // Keep the drawn bar next to its droplet, retaining the full touch target.
            float left = dp(4), right = dp(16);
            float top = dp(6), bottom = getHeight() - dp(6);
            ink.setColor(Color.BLACK); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(dp(1));
            canvas.drawRect(left, top, right, bottom, ink);
            ink.setStyle(Paint.Style.FILL);
            canvas.drawRect(left, bottom - (bottom-top)*wetness/100f, right, bottom, ink);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (busy()) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0); pad.finishStroke();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            int index = event.findPointerIndex(activePointer);
            if (index >= 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
                float y = event.getY(index);
                if (Float.isFinite(y)) select(Math.round(100 * (getHeight()-dp(6)-y) / Math.max(1, getHeight()-dp(12))));
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePointer = -1; preferences(); getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                select(wetness + (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ? 5 : -5)); preferences(); return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(SeekBar.class.getName());
            info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                    android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 100, wetness));
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == android.R.id.accessibilityActionSetProgress && args != null) {
                float value = args.getFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                if (!Float.isFinite(value)) return false;
                select(Math.round(value)); preferences(); return true;
            }
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                select(wetness + (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 5 : -5));
                preferences(); return true;
            }
            return super.performAccessibilityAction(action, args);
        }
    }
    private final class ShadePicker extends View {
        private Bitmap strip;
        private int endpointWidth;
        private final Paint marker = new Paint();
        private int activePointer = -1;
        ShadePicker() {
            super(PaintActivity.this); setFocusable(true); setClickable(true);
            setContentDescription("Gray gradient. Tap or drag from black on the left to white on the right.");
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (strip != null) strip.recycle();
            int width = w - dp(10), height = h - dp(15);
            if (width <= 0 || height <= 0) { strip = null; return; }
            endpointWidth = Math.min(dp(48), width / 4);
            int[] pixels = new int[width * height];
            for (int x = 0; x < width; x++) {
                int tone = toneAt(x, width);
                for (int y = 0; y < height; y++) pixels[y * width + x] = DotPattern.pixel(tone, x, y);
            }
            strip = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            if (strip == null) return;
            int inset = dp(5);
            canvas.drawBitmap(strip, inset, inset, null);
            marker.setColor(Color.BLACK); marker.setStyle(Paint.Style.STROKE); marker.setStrokeWidth(1);
            canvas.drawRect(inset, inset, inset + strip.getWidth(), inset + strip.getHeight(), marker);
            int density = DotPattern.whiteCount(gray), width = strip.getWidth();
            float x = inset + (density == 0 ? endpointWidth / 2f : density == 64 ? width - endpointWidth / 2f
                    : endpointWidth + DotPattern.pickerPosition(gray) * (width - 2 * endpointWidth - 1) / 255f);
            marker.setStyle(Paint.Style.FILL);
            canvas.drawRect(x - dp(3), getHeight() - dp(7), x + dp(3), getHeight() - dp(1), marker);
            marker.setColor(Color.WHITE); marker.setStrokeWidth(dp(3));
            canvas.drawLine(x, inset, x, inset + dp(7), marker);
            marker.setColor(Color.BLACK); marker.setStrokeWidth(1);
            canvas.drawLine(x, inset, x, inset + dp(7), marker);
        }
        private void selectX(float x) {
            if (strip == null || !Float.isFinite(x)) return;
            selectShade(toneAt(x - dp(5), strip.getWidth()));
        }
        private int toneAt(float x, int width) {
            int position = Math.round((x - endpointWidth) * 255f / Math.max(1, width - 2 * endpointWidth - 1));
            return DotPattern.pickerTone(position);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (busy()) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0); pad.finishStroke();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            int index = event.findPointerIndex(activePointer);
            if (index >= 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP))
                selectX(event.getX(index));
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePointer = -1; preferences(); getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                int direction = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                selectShade(DotPattern.pickerTone(DotPattern.pickerPosition(gray) + direction * 4)); preferences(); return true;
            }
            return super.onKeyDown(keyCode, event);
        }
    }
    private final class Footprint extends View {
        private final Paint paint = new Paint();
        Footprint() { super(PaintActivity.this); setContentDescription("Minimum and maximum footprints side by side at actual canvas pixel size"); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE); paint.setColor(Color.BLACK);
            float y=getHeight()/2f-dp(10);
            ToolSettings settings=library.current();
            for(int i=0;i<2;i++) {
                float x=getWidth()*(i==0?.3f:.7f);
                int diameter=i==0?settings.minimum:settings.tool==ToolSettings.Tool.PENCIL&&!settings.tilt?settings.tip:settings.maximum;
                float r=diameter/2f;
                if(settings.tool==ToolSettings.Tool.SOFTEN || (settings.tool==ToolSettings.Tool.ERASER && settings.softness>0)) {
                    float edge=settings.tool==ToolSettings.Tool.ERASER?settings.softness/100f:1;
                    paint.setShader(new android.graphics.RadialGradient(x,y,r,new int[]{Color.BLACK,Color.BLACK,Color.WHITE},new float[]{0,Math.max(.001f,1-edge),1},Shader.TileMode.CLAMP));
                }
                if(i==1&&settings.tool==ToolSettings.Tool.PENCIL&&settings.tilt) {
                    float minor=settings.tip+(settings.maximum-settings.tip)*.28f;
                    canvas.drawOval(x-r,y-minor/2,x+r,y+minor/2,paint);
                } else BrushStamp.draw(canvas,paint,x,y,r,settings);
                paint.setShader(null);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);paint.setTextAlign(Paint.Align.CENTER);
                String prefix=i==0?"Min ":settings.tool==ToolSettings.Tool.PENCIL?(settings.tilt?"Max tilted ":"Max upright "):"Max ";
                canvas.drawText(prefix+diameter+" px",x,getHeight()-dp(8),paint);
            }
        }
    }

    private final class DrawingPad extends View {
        private ToneDocument document;
        private Bitmap display;
        private int[] renderPixels = new int[0];
        private final Rect pending = new Rect();
        private DirectEink direct;
        private final NativePen input;
        private DrawingStroke stroke;
        private WetWatercolor wet;
        private boolean wetChanged, wetScheduled;
        private final WetWorkBudget wetBudget = new WetWorkBudget();
        private final android.os.MessageQueue wetQueue = android.os.Looper.getMainLooper().getQueue();
        private final android.os.MessageQueue.IdleHandler wetIdle = () -> { advanceWet(); return false; };
        private long lastWetComputeNanos, lastWetRenderNanos, lastWetPresentNanos, wetMaxSliceNanos;
        private int wetSliceCount;
        private FloodFill fill;
        private int pointer = -1, retries;
        private long lastPresent;
        private boolean fallbackNotice;
        private int drawCount;
        private final Runnable retry = () -> flush(true);
        private final Runnable fillStep = this::advanceFill;
        private final Runnable wetStep = () -> wetQueue.addIdleHandler(wetIdle);

        DrawingPad() {
            super(PaintActivity.this);
            setContentDescription("Drawing canvas; use the pen to paint");
            input = new NativePen(this, (bitmap, region) -> bitmap.recycle());
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            finishStroke(); disconnectDisplay();
            if (w <= 0 || h <= 0) return;
            if (display != null) display.recycle();
            display = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (!loading && document == null) {document = new ToneDocument(w, h);book=new DrawingBook(document);updatePages();}
            renderAll();
        }
        void replace(ToneDocument replacement) {
            finishStroke(); dryWet(); disconnectDisplay();
            document = replacement;
            if (document == null && getWidth() > 0 && getHeight() > 0)
                document = new ToneDocument(getWidth(), getHeight());
            book=document==null?null:new DrawingBook(document);updatePages();
            renderAll(); invalidate(); post(this::connectDisplay);
        }
        void showPage(ToneDocument page) {
            finishStroke();dryWet();disconnectDisplay();document=page;
            renderAll();invalidate();post(this::connectDisplay);
        }
        private void renderAll() {
            if (display == null) return;
            display.eraseColor(Color.WHITE);
            if (document != null) {
                render(new Rect(0, 0, Math.min(document.width, display.getWidth()), Math.min(document.height, display.getHeight())));
                document.clearDirty();
            }
            pending.setEmpty();
        }
        void renderDirty() {
            if (document == null || display == null) return;
            int[] bounds = document.dirty();
            if (bounds == null) return;
            Rect dirty = new Rect(Math.max(0, bounds[0] - 2), Math.max(0, bounds[1] - 2),
                    Math.min(document.width, bounds[2] + 2), Math.min(document.height, bounds[3] + 2));
            if (dirty.intersect(0, 0, display.getWidth(), display.getHeight())) { render(dirty); pending.union(dirty); }
            document.clearDirty();
        }
        private void render(Rect dirty) {
            int count = dirty.width() * dirty.height();
            if (renderPixels.length < count) renderPixels = new int[count];
            document.render(renderPixels, dirty.left, dirty.top, dirty.width(), dirty.height());
            display.setPixels(renderPixels, 0, dirty.width(), dirty.left, dirty.top, dirty.width(), dirty.height());
        }
        @Override protected void onDraw(Canvas canvas) {
            drawCount++;
            canvas.drawColor(Color.WHITE);
            if (display != null) canvas.drawBitmap(display, 0, 0, null);
        }
        void connectDisplay() {
            scheduleWet();
            if (!resumed || !hasWindowFocus() || loading || display == null || direct != null) return;
            try {
                if (!input.prepareDocumentCanvas()) throw new IllegalStateException(input.status);
                if (getDisplay().getRotation() != Surface.ROTATION_0) throw new IllegalStateException("Portrait display required");
                int[] origin = new int[2]; getLocationOnScreen(origin);
                direct = new DirectEink(origin[0], origin[1], display, 0, 7);
            } catch (RuntimeException | LinkageError error) {
                Log.w(ProbeActivity.TAG, "Using Android drawing presentation", error);
                if (!fallbackNotice) { message("Fast display unavailable; using standard drawing"); fallbackNotice = true; }
            }
        }
        void disconnectDisplay() {
            cancelWetCallback();
            removeCallbacks(retry);
            if (direct != null) { direct.close(); direct = null; }
            input.disable();
        }
        void present() { flush(true); }
        private void flush(boolean force) {
            if (pending.isEmpty()) return;
            long elapsed = SystemClock.uptimeMillis() - lastPresent;
            if (!force && elapsed < 8) {
                // A pressure-only event may be the last event for a while.
                // Flush its pixels when the coalescing window ends.
                removeCallbacks(retry);
                postDelayed(retry, 8 - elapsed);
                return;
            }
            removeCallbacks(retry);
            if (direct == null) { invalidate(pending); pending.setEmpty(); return; }
            try {
                int result = direct.present(display, pending);
                lastPresent = SystemClock.uptimeMillis();
                if (result >= 0) { pending.setEmpty(); retries = 0; }
                else if (++retries < 120) postDelayed(retry, 8);
                else throw new IllegalStateException("Display remained busy");
            } catch (RuntimeException error) {
                Log.e(ProbeActivity.TAG, "Direct display failed", error);
                disconnectDisplay(); invalidate(pending); pending.setEmpty();
                saveError = "Fast display stopped; drawing is retained. Reopen the app to retry.";
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (loading || document == null || fill != null || !resumed || !hasWindowFocus()) return true;
            long inputStart = System.nanoTime();
            long eventAge = Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
            boolean drawingInput = false;
            int action = event.getActionMasked(), index = event.getActionIndex();
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                    && pointer == -1 && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.getX(index) < 0 || event.getY(index) < 0
                        || event.getX(index) >= Math.min(getWidth(), document.width)
                        || event.getY(index) >= Math.min(getHeight(), document.height)) return true;
                ToolSettings settings=library.current().size(maximum);
                if (!settings.isBrush() || !wetCanvas) dryWet();
                else if (wet == null) wet = new WetWatercolor(document, wetness);
                if(settings.tool==ToolSettings.Tool.FILL) {
                    fill=new FloodFill(document,(int)event.getX(index),(int)event.getY(index),gray,settings.tolerance);
                    operationStatus.setText("Filling…"); post(fillStep); return true;
                }
                pointer = event.getPointerId(index);
                cancelWetCallback();
                drawingInput = true;
                if(settings.isBrush() || (settings.tool==ToolSettings.Tool.ERASER && settings.softness==0))
                    stroke = new PressureStroke(document, settings, settings.tool==ToolSettings.Tool.ERASER ? 255 : gray, wet, settings.isBrush() && transparentPaint);
                else stroke = new ToolStroke(document,settings,gray);
                getParent().requestDisallowInterceptTouchEvent(true);
                // Supernote encodes signed X degrees in ORIENTATION, Y in TILT.
                stroke.sample(event.getX(index), event.getY(index), event.getPressure(index),
                        event.getOrientation(index),event.getAxisValue(MotionEvent.AXIS_TILT,index));
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP) {
                int p = event.findPointerIndex(pointer);
                if (p >= 0 && stroke != null && (action == MotionEvent.ACTION_MOVE || event.getPointerId(index) == pointer)) {
                    drawingInput = true;
                    for (int h = 0; h < event.getHistorySize(); h++)
                        stroke.sample(event.getHistoricalX(p,h), event.getHistoricalY(p,h), event.getHistoricalPressure(p,h),
                                event.getHistoricalOrientation(p,h),event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,p,h));
                    // Like 0.12, pen-up flushes pending pixels without a new zero-pressure dab.
                    if (action == MotionEvent.ACTION_MOVE) stroke.sample(event.getX(p), event.getY(p), event.getPressure(p),
                            event.getOrientation(p),event.getAxisValue(MotionEvent.AXIS_TILT,p));
                }
            }
            renderDirty(); flush(false);
            if (drawingInput) wetBudget.input(System.nanoTime() - inputStart, eventAge, SystemClock.uptimeMillis());
            scheduleWet();
            if (action == MotionEvent.ACTION_CANCEL || ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(index) == pointer)) finishStroke();
            return true;
        }
        void finishStroke() {
            if(fill != null) {
                removeCallbacks(fillStep); fill.cancel(); fill=null;
                renderDirty(); flush(true); operationStatus.setText("");
            }
            if (stroke == null) return;
            boolean changed = stroke.finish(); stroke = null; pointer = -1;
            renderDirty(); flush(true);
            getParent().requestDisallowInterceptTouchEvent(false);
            if (changed) recovery();
            scheduleWet();
            // No View invalidation or toolbar update at pen-up on the direct path.
        }
        private void scheduleWet() {
            // Yield between slices; input queued during the last slice runs
            // before the next idle callback. Only completed sweeps wait a frame.
            scheduleWet(wet != null && wet.framePending() ? 1 : WetWatercolor.FRAME_MS);
        }
        private void scheduleWet(int delayMillis) {
            if (wet != null && wet.isAnimating() && resumed && hasWindowFocus() && !loading) {
                if (!wetScheduled) { wetScheduled = true; postDelayed(wetStep, delayMillis); }
            }
        }
        private void cancelWetCallback() {
            removeCallbacks(wetStep); wetQueue.removeIdleHandler(wetIdle); wetScheduled = false;
        }
        private void advanceWet() {
            wetScheduled = false;
            if (wet == null || !resumed || !hasWindowFocus() || loading) return;
            boolean drawing = stroke != null;
            long budget = wetBudget.nanos(drawing, wet.strokePixels(), wet.activePixels(), SystemClock.uptimeMillis());
            // Submit the pen's outstanding pixels before adding more display work.
            if (budget == 0 || !pending.isEmpty()) { scheduleWet(16); return; }
            long start = System.nanoTime();
            wetChanged |= wet.advance(drawing, wetBudget.tiles(budget, drawing), budget / 2, drawing ? 96 : 192);
            long computed = System.nanoTime();
            renderDirty();
            long rendered = System.nanoTime();
            flush(true);
            long finished = System.nanoTime();
            lastWetComputeNanos = computed - start;
            lastWetRenderNanos = rendered - computed;
            lastWetPresentNanos = finished - rendered;
            wetMaxSliceNanos = Math.max(wetMaxSliceNanos, finished - start);
            wetSliceCount++;
            wetBudget.completed(finished - start, wet.advancedTiles(), drawing, SystemClock.uptimeMillis());
            if (wet.isAnimating()) scheduleWet();
            else if (wetChanged && stroke == null) { wetChanged = false; recovery(); }
        }
        void dryWet() {
            cancelWetCallback();
            wet = null;
            if (wetChanged) { wetChanged = false; recovery(); }
        }
        private void advanceFill() {
            if(fill==null) return;
            long deadline=System.nanoTime()+5_000_000L;
            boolean done;
            do { done=fill.advance(1024); } while(!done && System.nanoTime()<deadline);
            if(!done) { postDelayed(fillStep,1); return; }
            boolean changed=fill.finish(); fill=null;
            renderDirty(); flush(true); operationStatus.setText("");
            if(changed) recovery();
        }
        void close() { finishStroke(); dryWet(); disconnectDisplay(); input.close(); if (display != null) display.recycle(); }
    }
}
