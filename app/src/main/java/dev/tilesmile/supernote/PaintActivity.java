package dev.tilesmile.supernote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
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
    private ShadePreview shadePreview;
    private DocumentStore store;
    private SharedPreferences preferences;
    private DrawingPad pad;
    private Button brushButton;
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
        maximum = library.current().maximum;
        store = new DocumentStore(getFilesDir());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        palette = new LinearLayout(this);
        leftHeader = new LinearLayout(this); rightHeader = new LinearLayout(this);
        rightHeader.setGravity(android.view.Gravity.END);
        // Reserve equal side widths, including room for the menu on either side.
        palette.addView(leftHeader, new LinearLayout.LayoutParams(dp(260), dp(48)));
        LinearLayout colors = new LinearLayout(this);
        palette.addView(colors, new LinearLayout.LayoutParams(0, dp(48), 1));
        palette.addView(rightHeader, new LinearLayout.LayoutParams(dp(260), dp(48)));
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
        shadePicker = new ShadePicker(); shadePreview = new ShadePreview();
        // Balance the preview so the grayscale strip itself stays screen-centered.
        colors.addView(new View(this), new LinearLayout.LayoutParams(dp(48), dp(48)));
        colors.addView(shadePicker, new LinearLayout.LayoutParams(0, dp(48), 1));
        colors.addView(shadePreview, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout pageActions = new LinearLayout(this);
        pageActions.setPadding(0, 0, dp(8), 0);
        headerAction(pageActions, "Undo", R.drawable.ic_undo, () -> { if (pad.document.undo()) { pad.renderDirty(); pad.present(); recovery(); } });
        headerAction(pageActions, "Redo", R.drawable.ic_redo, () -> { if (pad.document.redo()) { pad.renderDirty(); pad.present(); recovery(); } });
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
                    .putString("tools", java.util.Base64.getEncoder().encodeToString(library.encode())).apply();
        } catch (java.io.IOException error) { message("Could not save tool settings: " + error.getMessage()); }
    }
    private int icon(ToolSettings.Tool tool) {
        switch (tool) {
            case PENCIL: return R.drawable.ic_pencil;
            case FILL: return R.drawable.ic_fill;
            case ERASER: return R.drawable.ic_eraser;
            case SOFTEN: return R.drawable.ic_soften;
            default: return R.drawable.ic_brush;
        }
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
        boolean marked;
        String presetId;
        private android.graphics.drawable.Drawable centerIcon;
        private android.graphics.drawable.Drawable settingsArrow;
        private final Paint markerPaint = new Paint();
        ToolButton() {
            super(PaintActivity.this);
            // Flat icon controls have no pressed elevation to animate after a tap.
            setStateListAnimator(null);setElevation(0);
        }
        @Override public boolean isSelected() {
            return settingsArrow != null ? marked : super.isSelected();
        }
        void iconOnly(int resource) {
            setText("");setCompoundDrawables(null,null,null,null);setMinWidth(0);setMinimumWidth(0);
            // Empty button text still has themed pressed colors which can invalidate it.
            setTextColor(Color.BLACK);setHintTextColor(Color.BLACK);setLinkTextColor(Color.BLACK);
            centerIcon=getDrawable(resource);invalidate();
        }
        Rect markerArea() { return new Rect(Math.max(0,getWidth()-dp(24)),0,getWidth(),Math.min(getHeight(),dp(24))); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(centerIcon!=null) {
                int half=dp(14),x=getWidth()/2,y=getHeight()/2-(presetId==null?0:dp(4));
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
            if (!marked || !selectionFeedback.enabled) return;
            float x=getWidth()-dp(12),y=dp(12);
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
            if(selectedKey().equals(key)) settings(); else select.run();
        });
        control.iconOnly(icon);control.settingsArrow=getDrawable(R.drawable.ic_chevron);
        control.setContentDescription(name+". Tap to select; tap again for settings.");
        control.setOnLongClickListener(v -> {
            if(!busy()){pad.finishStroke();if(!selectedKey().equals(key))select.run();settings();}return true;
        });
        return control;
    }
    private void rebuildTools() {
        toolRail.removeAllViews(); selectionButtons.clear();
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
            Button b = toolRow("tool:"+tool,ToolSettings.defaults(tool).label(), icon(tool), () -> {
                library.select(tool); maximum = library.current().maximum; refreshToolSelection(); preferences();
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
            Button b = toolRow(preset.id,preset.name, icon(preset.settings.tool), () -> {
                library.recall(preset.id); maximum=library.current().maximum; refreshToolSelection(); preferences();
            });
            b.setTextSize(12); b.setMaxLines(2); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ((ToolButton)b).presetId = preset.id;
            b.setContentDescription(preset.name + ", " + preset.settings.label() + ", " + preset.settings.maximum + " px. Tap to select; tap again for settings. Hold and drag to reorder.");
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
    private AlertDialog settings() {
        ToolSettings current=library.current();
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
        TextView minimumLabel=new TextView(this);content.addView(minimumLabel);
        SeekBar minimumSize=new SeekBar(this);minimumSize.setContentDescription("Minimum diameter");content.addView(minimumSize);
        TextView label = new TextView(this); content.addView(label);
        SeekBar size = new SeekBar(this); size.setContentDescription("Maximum diameter");size.setMax(126); size.setProgress(maximum - 2); content.addView(size);
        Runnable update = () -> {
            syncing[0]=true;ToolSettings s=library.current();
            minimumLabel.setText("Minimum diameter: "+s.minimum+" px");label.setText("Maximum diameter: "+s.maximum+" px");
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
        if(current.tool==ToolSettings.Tool.BRUSH) {
            settingSlider(content,"Pressure response",current.pressureResponse,value -> {
                library.edit(library.current().pressureResponse(value));preferences();
            });
            LinearLayout endpoints=new LinearLayout(this);content.addView(endpoints);
            TextView light=new TextView(this);light.setText("Light touch");light.setTag("hint");
            endpoints.addView(light,new LinearLayout.LayoutParams(0,-2,1));
            TextView firm=new TextView(this);firm.setText("Firm touch");firm.setTag("hint");endpoints.addView(firm);
            TextView note=new TextView(this);note.setText("Toward Firm: light strokes stay thin longer; press harder for broad strokes. 50% keeps the original feel.");note.setTag("hint");content.addView(note);
        }
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
        return showToolSettings(content,current.label());
    }
    private void settingSlider(LinearLayout content,String name,int initial,java.util.function.IntConsumer change) {
        TextView label=new TextView(this);label.setText(name+": "+initial+"%");content.addView(label);
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(initial);bar.setContentDescription(name);content.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider,int value,boolean user){label.setText(name+": "+value+"%");change.accept(value);}
            @Override public void onStartTrackingTouch(SeekBar slider){}
            @Override public void onStopTrackingTouch(SeekBar slider){}
        });
    }
    private AlertDialog showToolSettings(LinearLayout content,String title) {
        String presetId=library.activeId();
        boolean custom=!presetId.isEmpty();
        if(custom) title="Custom "+title;
        styleSettings(content);ScrollView scroll=new ScrollView(this);scroll.addView(content);
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
                    drawingName = value; dialog.dismiss();
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
        resumed = false;
        if (presetDrag != null) presetDrag.reset();
        if (pad != null) { pad.finishStroke(); pad.disconnectDisplay(); }
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
        selectionFeedback.update(shadePreview, new Rect(0,0,shadePreview.getWidth(),shadePreview.getHeight()), shadePreview::updatePaint);
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
    private final class ShadePreview extends View {
        private final Paint dots = new Paint(), outline = new Paint();
        ShadePreview() { super(PaintActivity.this); update(); }
        void update() { updatePaint();invalidate(); }
        void updatePaint() {
            dots.setShader(new BitmapShader(DotGray.tile(gray), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            setContentDescription("Selected shade: " + Math.round(100f * DotPattern.whiteCount(gray) / 64) + "% white coverage");
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE); int inset = dp(5), bottom = getHeight() - dp(10);
            canvas.drawRect(inset, inset, getWidth() - inset, bottom, dots);
            outline.setColor(Color.BLACK); outline.setStyle(Paint.Style.STROKE); outline.setStrokeWidth(1);
            canvas.drawRect(inset, inset, getWidth() - inset, bottom, outline);
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
                } else canvas.drawCircle(x,y,r,paint);
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
        private FloodFill fill;
        private int pointer = -1, retries;
        private long lastPresent;
        private boolean fallbackNotice;
        private int drawCount;
        private final Runnable retry = () -> flush(true);
        private final Runnable fillStep = this::advanceFill;

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
            finishStroke(); disconnectDisplay();
            document = replacement;
            if (document == null && getWidth() > 0 && getHeight() > 0)
                document = new ToneDocument(getWidth(), getHeight());
            book=document==null?null:new DrawingBook(document);updatePages();
            renderAll(); invalidate(); post(this::connectDisplay);
        }
        void showPage(ToneDocument page) {
            finishStroke();disconnectDisplay();document=page;
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
            removeCallbacks(retry);
            if (direct != null) { direct.close(); direct = null; }
            input.disable();
        }
        void present() { flush(true); }
        private void flush(boolean force) {
            if (pending.isEmpty()) return;
            if (!force && SystemClock.uptimeMillis() - lastPresent < 8) return;
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
            int action = event.getActionMasked(), index = event.getActionIndex();
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                    && pointer == -1 && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
                if (event.getX(index) < 0 || event.getY(index) < 0
                        || event.getX(index) >= Math.min(getWidth(), document.width)
                        || event.getY(index) >= Math.min(getHeight(), document.height)) return true;
                ToolSettings settings=library.current().size(maximum);
                if(settings.tool==ToolSettings.Tool.FILL) {
                    fill=new FloodFill(document,(int)event.getX(index),(int)event.getY(index),gray,settings.tolerance);
                    operationStatus.setText("Filling…"); post(fillStep); return true;
                }
                pointer = event.getPointerId(index);
                if(settings.tool==ToolSettings.Tool.BRUSH || (settings.tool==ToolSettings.Tool.ERASER && settings.softness==0))
                    stroke = new PressureStroke(document, settings, settings.tool==ToolSettings.Tool.ERASER ? 255 : gray);
                else stroke = new ToolStroke(document,settings,gray);
                getParent().requestDisallowInterceptTouchEvent(true);
                stroke.sample(event.getX(index), event.getY(index), event.getPressure(index),
                        event.getAxisValue(MotionEvent.AXIS_TILT,index),event.getOrientation(index));
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP) {
                int p = event.findPointerIndex(pointer);
                if (p >= 0 && stroke != null && (action == MotionEvent.ACTION_MOVE || event.getPointerId(index) == pointer)) {
                    for (int h = 0; h < event.getHistorySize(); h++)
                        stroke.sample(event.getHistoricalX(p,h), event.getHistoricalY(p,h), event.getHistoricalPressure(p,h),
                                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT,p,h),event.getHistoricalOrientation(p,h));
                    // Like 0.12, pen-up flushes pending pixels without a new zero-pressure dab.
                    if (action == MotionEvent.ACTION_MOVE) stroke.sample(event.getX(p), event.getY(p), event.getPressure(p),
                            event.getAxisValue(MotionEvent.AXIS_TILT,p),event.getOrientation(p));
                }
            }
            renderDirty(); flush(false);
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
            // No View invalidation or toolbar update at pen-up on the direct path.
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
        void close() { finishStroke(); disconnectDisplay(); input.close(); if (display != null) display.recycle(); }
    }
}
