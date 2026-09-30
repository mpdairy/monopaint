package io.github.mpdairy.monopaint;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;

/** Layered painting app with logical tools, saved presets and calibrated presentation. */
public final class PaintActivity extends Activity {
    private ShadePicker shadePicker;
    private ToolButton wetButton, opaqueButton, transparentButton, eyedropperButton, eraseButton;
    private boolean pickingShade, pickedShade;
    private int pickOriginalShade;
    private static final ToolSettings.Tool[] TOOLBAR_TOOLS = {ToolSettings.Tool.BRUSH,
            ToolSettings.Tool.PENCIL, ToolSettings.Tool.AIRBRUSH, ToolSettings.Tool.FILL, ToolSettings.Tool.SHAPES, ToolSettings.Tool.ERASER, ToolSettings.Tool.SOFTEN};
    private WetnessBar wetnessBar;
    private boolean wetCanvas, transparentPaint, eraseMode;
    private int wetness = 65;
    private DocumentStore store;
    private SharedPreferences preferences;
    private DrawingPad pad;
    private Button brushButton;
    private android.widget.PopupWindow toolPicker;
    private android.widget.PopupWindow filePopup, layersPopup;
    private Button layersButton;
    private ToolButton zoomButton;
    private boolean navigationLocked = true;
    private LinearLayout sidebarPalette;
    private final java.util.ArrayList<PaletteSwatch> sidebarSwatches=new java.util.ArrayList<>();
    private PaletteEditor paletteEditor;
    private boolean dismissingPaletteTouch;
    private ToolRail toolRail;
    private PresetDragHandler presetDrag;
    private TextView operationStatus, gradientHint;
    private final Runnable gradientHintTask = this::showGradientHint;
    private ToolLibrary library;
    private DrawingBook book;
    private LinearLayout root, body, palette, headerControls, leftHeader, rightHeader, menuControls;
    private QuarterTurnLayout paletteFrame, orientationFrame;
    private ScrollView toolScroll;
    private HorizontalScrollView landscapeTools;
    private OrientationEventListener orientationSensor;
    // Android and the e-ink driver always remain in portrait. Only our View tree turns.
    private int appRotation = Surface.ROTATION_0;
    private MotionEvent physicalPenEvent;
    private final RotationSuggestion rotationSuggestion = new RotationSuggestion();
    private int suggestedQuarter = RotationSuggestion.NONE;
    private int lastSensorDegrees = OrientationEventListener.ORIENTATION_UNKNOWN;
    private final Runnable rotationCheck = () -> suggestRotation(lastSensorDegrees);
    private android.widget.ImageButton rotateButton;
    private boolean landscape;
    private boolean toolboxRight;
    private int toolbarTurn;
    private android.widget.ImageButton menuButton, previousPage, nextPage, addPage;
    private TextView pageNumber;
    private final SelectionFeedback selectionFeedback = new SelectionFeedback();
    private final java.util.Map<String, ToolButton> selectionButtons = new java.util.LinkedHashMap<>();
    private int gray, maximum;
    private boolean resumed, loading = true, destroyed, saving;
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
        navigationLocked = preferences.getBoolean("navigation_locked", true);
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
        eraseMode = preferences.getBoolean("erase_mode", false) && library.current().supportsEraseMode();
        store = new DocumentStore(getFilesDir());
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        palette = new LinearLayout(this);
        leftHeader = new LinearLayout(this); rightHeader = new LinearLayout(this);
        rightHeader.setGravity(android.view.Gravity.END);
        // Balance the color controls with the spare space beside page navigation.
        palette.addView(leftHeader, new LinearLayout.LayoutParams(dp(240), dp(48)));
        LinearLayout colors = new LinearLayout(this);
        palette.addView(colors, new LinearLayout.LayoutParams(0, dp(48), 1));
        palette.addView(rightHeader, new LinearLayout.LayoutParams(dp(240), dp(48)));
        android.widget.ImageButton files=new HeaderImageButton();menuButton=files;
        files.setImageResource(R.drawable.ic_menu);files.setBackgroundColor(Color.WHITE);
        files.setContentDescription("File menu");
        files.setOnClickListener(v -> {
            if(busy())return;pad.finishStroke();setPickingShade(false);
            fileMenu(files);
        });
        menuControls = new LinearLayout(this);
        menuControls.addView(files,new LinearLayout.LayoutParams(dp(48),dp(48)));
        headerAction(menuControls, "Undo", R.drawable.ic_undo, () -> { pad.dryWet(); if (pad.document.undo()) { pad.renderDirty(); pad.present(); recovery(); } });
        headerAction(menuControls, "Redo", R.drawable.ic_redo, () -> { pad.dryWet(); if (pad.document.redo()) { pad.renderDirty(); pad.present(); recovery(); } });
        headerAction(menuControls, "Clear layer", R.drawable.ic_clear, this::clearDrawing);
        rotateButton = new HeaderImageButton();
        rotateButton.setImageResource(R.drawable.ic_rotate); rotateButton.setBackgroundColor(Color.WHITE);
        rotateButton.setVisibility(View.INVISIBLE);
        rotateButton.setOnClickListener(v -> acceptRotation());
        menuControls.addView(rotateButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        leftHeader.addView(menuControls);
        headerControls=new LinearLayout(this);
        previousPage=pageButton("Previous page",R.drawable.ic_previous,() -> changePage(book.index()-1));
        pageNumber=new PageLabel();pageNumber.setTextSize(15);pageNumber.setTypeface(null,android.graphics.Typeface.BOLD);
        pageNumber.setGravity(android.view.Gravity.CENTER);headerControls.addView(pageNumber,new LinearLayout.LayoutParams(dp(76),dp(48)));
        nextPage=pageButton("Next page",R.drawable.ic_chevron,() -> changePage(book.index()+1));
        addPage=pageButton("Add page",R.drawable.ic_new,this::addPage);
        wetButton = paintModeButton(colors, "Wet canvas", R.drawable.ic_water_drop, () -> setWetCanvas(!wetCanvas));
        wetButton.iconHalf = 18;
        wetButton.iconOffset = 8; wetButton.markerLeft = true;
        wetnessBar = new WetnessBar();
        colors.addView(wetnessBar, new LinearLayout.LayoutParams(dp(24), dp(48)));
        eyedropperButton = paintModeButton(colors, "Pick color from canvas", R.drawable.ic_eyedropper, () -> {
            setPickingShade(!pickingShade);
            if (pickingShade) pad.dryWet();
        });
        eyedropperButton.markerBelow = true;
        eyedropperButton.setOnTouchListener((view,event) -> {
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN) hideGradientHint();
            return false;
        });
        shadePicker = new ShadePicker();
        colors.addView(shadePicker, new LinearLayout.LayoutParams(0, dp(48), 1));
        eraseButton = paintModeButton(colors, "Erase with current tool", R.drawable.ic_eraser, () -> {
            if (busy() || !library.current().supportsEraseMode()) return;
            pad.finishStroke(); setPickingShade(false); pad.dryWet();
            setEraseMode(!eraseMode); preferences();
        });
        eraseButton.markerBelow = true;
        transparentButton = paintModeButton(colors, "Transparent paint", R.drawable.ic_transparent, () -> setTransparentPaint(true));
        opaqueButton = paintModeButton(colors, "Opaque paint", R.drawable.ic_opaque, () -> setTransparentPaint(false));
        refreshPaintModes();
        rightHeader.addView(headerControls);
        paletteFrame = new QuarterTurnLayout(this); paletteFrame.addView(palette);
        root.addView(paletteFrame);
        body = new LinearLayout(this);
        ScrollView scroll = new ScrollView(this);toolScroll=scroll;
        landscapeTools = new HorizontalScrollView(this);
        toolRail = new ToolRail(); toolRail.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(toolRail); body.addView(scroll, new LinearLayout.LayoutParams(dp(64), -1));
        presetDrag = new PresetDragHandler();
        scroll.setOnDragListener(presetDrag);
        landscapeTools.setOnDragListener(presetDrag);
        rebuildTools();
        pad = new DrawingPad(); body.addView(pad, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        orientationFrame = new QuarterTurnLayout(this);
        orientationFrame.addView(root);
        orientationFrame.afterLayout = () -> {
            pad.disconnectDisplay(); pad.updateViewport(); pad.post(pad::connectDisplay);
        };
        setContentView(orientationFrame);
        applyToolboxSide();updatePages();
        orientationSensor = new OrientationEventListener(this, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) {
            @Override public void onOrientationChanged(int degrees) {
                lastSensorDegrees = degrees;
                suggestRotation(degrees);
                // The listener need not emit another event once a device stops moving.
                rotateButton.removeCallbacks(rotationCheck);
                if (degrees >= 0 && suggestedQuarter == RotationSuggestion.NONE)
                    rotateButton.postDelayed(rotationCheck,RotationSuggestion.HOLD_MS);
            }
        };
        pad.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (l != ol || t != ot || r != or || b != ob) {pad.disconnectDisplay();pad.post(pad::connectDisplay);}
        });
        store.recover((recovered, error) -> runOnUiThread(() -> {
            if (destroyed) return;
            loading = false;
            if (error != null) {
                // Keep the failed recovery on disk until the user explicitly chooses a new drawing.
                loading = true;
                showDialog(new AlertDialog.Builder(this).setTitle("Could not recover drawing")
                        .setMessage(error.getMessage() + "\nYou can open a saved drawing or start a new one.")
                        .setPositiveButton("Open", (d,w) -> openDrawing())
                        .setNegativeButton("New", (d,w) -> { loading = false; pad.replace(null); drawingName = ""; recovery(); })
                        .setCancelable(false));
            } else {
                drawingName = recovered == null ? "" : recovered.path;
                replaceBook(recovered == null ? null : recovered.book);
            }
        }));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void headerAction(LinearLayout parent, String name, int icon, Runnable action) {
        ToolButton control = (ToolButton) button(parent, name, icon, action);
        control.headerIcon = true;
        control.iconOnly(icon);
        control.setBackgroundColor(Color.WHITE);
        control.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    private android.widget.ImageButton pageButton(String name,int icon,Runnable action) {
        android.widget.ImageButton button=new HeaderImageButton();button.setImageResource(icon);
        button.setBackgroundColor(Color.WHITE);button.setContentDescription(name);
        button.setOnClickListener(v -> {if(!busy()){setPickingShade(false);action.run();}});headerControls.addView(button,new LinearLayout.LayoutParams(dp(40),dp(48)));return button;
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
        closePaletteEditor();
        if (filePopup != null) filePopup.dismiss();
        if (layersPopup != null) layersPopup.dismiss();
        boolean right=preferences.getBoolean("toolbox_right",false);
        toolboxRight = right;
        if(pad!=null){pad.finishStroke();pad.disconnectDisplay();}
        if (presetDrag != null) presetDrag.reset();
        // Read the side column from menu/undo at the top to page controls at the bottom.
        toolbarTurn = landscape ? 90 : 0;
        shadePicker.setContentDescription(!landscape
                ? "Gray gradient. Tap or drag from black on the left to white on the right."
                : "Gray gradient. Tap or drag from black at the bottom to white at the top.");
        // Follow the shade endpoints in portrait and landscape.
        LinearLayout colors = (LinearLayout)shadePicker.getParent();
        colors.removeView(eraseButton); colors.removeView(eyedropperButton);
        colors.addView(landscape ? eraseButton : eyedropperButton, colors.indexOfChild(shadePicker));
        colors.addView(landscape ? eyedropperButton : eraseButton, colors.indexOfChild(shadePicker) + 1);
        paletteFrame.setTurn(toolbarTurn);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.removeView(paletteFrame);
        root.addView(paletteFrame,landscape && right ? root.getChildCount() : 0,
                new LinearLayout.LayoutParams(landscape ? dp(48) : -1,landscape ? -1 : dp(48)));
        body.setLayoutParams(new LinearLayout.LayoutParams(landscape ? 0 : -1,landscape ? -1 : 0,1));
        body.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.removeView(toolScroll); body.removeView(landscapeTools);
        ((android.view.ViewGroup)toolRail.getParent()).removeView(toolRail);
        toolRail.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (landscape) {
            landscapeTools.addView(toolRail,new android.widget.FrameLayout.LayoutParams(-2,-1));
            // Left-handed tools stay at the top in either landscape direction.
            boolean toolsAtTop = right || appRotation == Surface.ROTATION_270;
            body.addView(landscapeTools,toolsAtTop ? 0 : body.getChildCount(),new LinearLayout.LayoutParams(-1,dp(64)));
        } else {
            toolScroll.addView(toolRail,new android.widget.FrameLayout.LayoutParams(-1,-2));
            body.addView(toolScroll,right ? body.getChildCount() : 0,new LinearLayout.LayoutParams(dp(64),-1));
        }
        pad.setLayoutParams(new LinearLayout.LayoutParams(landscape ? -1 : 0,landscape ? 0 : -1,1));
        ((android.view.ViewGroup)menuControls.getParent()).removeView(menuControls);
        boolean menuAtEnd = !landscape && right;
        // Reclaim the page-side gap as well as the wetness slider's blank tail.
        // Keep the full menu width when it occupies the right-hand corner.
        rightHeader.setLayoutParams(new LinearLayout.LayoutParams(dp(menuAtEnd ? 240 : 216), dp(48)));
        // Keep Undo/Redo beside the outer-corner menu for either drawing hand.
        if ((menuControls.indexOfChild(menuButton) == 0) == menuAtEnd) {
            java.util.ArrayList<View> controls = new java.util.ArrayList<>();
            for (int i=menuControls.getChildCount()-1;i>=0;i--) controls.add(menuControls.getChildAt(i));
            menuControls.removeAllViews();
            for (View control : controls) menuControls.addView(control);
        }
        ((android.view.ViewGroup)headerControls.getParent()).removeView(headerControls);
        LinearLayout menuSide = menuAtEnd ? rightHeader : leftHeader;
        LinearLayout pageSide = menuAtEnd ? leftHeader : rightHeader;
        menuSide.addView(menuControls,new LinearLayout.LayoutParams(dp(240),dp(48)));
        pageSide.addView(headerControls);
        rebuildTools();
        invalidateHeader(palette);
        pad.updateViewport(); pad.invalidate(); pad.post(pad::connectDisplay);
    }
    private void invalidateHeader(View view) {
        view.invalidate();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) invalidateHeader(group.getChildAt(i));
        }
    }
    private int currentQuarter() {
        return (4 - appRotation) % 4;
    }
    private void suggestRotation(int degrees) {
        if (!resumed || !hasWindowFocus()) { hideRotationSuggestion(); return; }
        int next = rotationSuggestion.update(degrees,currentQuarter(),SystemClock.uptimeMillis());
        if (next == suggestedQuarter) return;
        suggestedQuarter = next;
        String label = next % 2 == 1 ? "Rotate to landscape" : "Rotate to portrait";
        rotateButton.setContentDescription(label);
        rotateButton.setVisibility(next == RotationSuggestion.NONE ? View.INVISIBLE : View.VISIBLE);
    }
    private void hideRotationSuggestion() {
        rotationSuggestion.reset(); suggestedQuarter = RotationSuggestion.NONE;
        if (rotateButton != null) {
            rotateButton.removeCallbacks(rotationCheck);
            rotateButton.setVisibility(View.INVISIBLE);
        }
    }
    private void acceptRotation() {
        if (suggestedQuarter == RotationSuggestion.NONE || busy()) return;
        requestQuarter(suggestedQuarter);
    }
    private void requestQuarter(int quarter) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet(); pad.disconnectDisplay();
        if (toolPicker != null) toolPicker.dismiss();
        presetDrag.reset(); hideRotationSuggestion();
        appRotation = (4-quarter)%4;
        landscape = appRotation == Surface.ROTATION_90 || appRotation == Surface.ROTATION_270;
        orientationFrame.setTurn(appTurn());
        applyToolboxSide();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        hideRotationSuggestion();
        if (toolPicker != null) toolPicker.dismiss();
        applyToolboxSide();
    }
    private int appTurn() { return appRotation == Surface.ROTATION_270 ? -90 : appRotation*90; }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(dismissingPaletteTouch) {
            if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL)
                dismissingPaletteTouch=false;
            return true;
        }
        if(paletteEditor!=null && event.getActionMasked()==MotionEvent.ACTION_DOWN) {
            float[] point={event.getRawX(),event.getRawY()};Matrix inverse=new Matrix();
            PanelCoordinates.fromView(shadePicker).invert(inverse);inverse.mapPoints(point);
            if(point[0]<0 || point[1]<0 || point[0]>=shadePicker.getWidth() || point[1]>=shadePicker.getHeight()) {
                closePaletteEditor();dismissingPaletteTouch=true;return true;
            }
        }
        // Android treats ORIENTATION as radians when it transforms a View's events.
        // Retain the firmware's original signed-degree tilt axes before that happens.
        MotionEvent previous=physicalPenEvent; physicalPenEvent=event;
        try { return super.dispatchTouchEvent(event); }
        finally { physicalPenEvent=previous; }
    }
    private final class HeaderImageButton extends android.widget.ImageButton {
        HeaderImageButton() { super(PaintActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.rotate(-toolbarTurn,getWidth()/2f,getHeight()/2f);
            super.onDraw(canvas); canvas.restore();
        }
    }
    private final class PageLabel extends TextView {
        PageLabel() { super(PaintActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            if (!landscape) { super.onDraw(canvas); return; }
            canvas.save(); canvas.rotate(-toolbarTurn,getWidth()/2f,getHeight()/2f);
            Paint paint = getPaint(); paint.setColor(Color.BLACK); paint.setTextAlign(Paint.Align.CENTER);
            String[] lines = getText().toString().split(" / ");
            float middle = getHeight()/2f - (paint.ascent()+paint.descent())/2f;
            float lineHeight = paint.getFontSpacing();
            canvas.drawText(lines[0],getWidth()/2f,middle-lineHeight,paint);
            canvas.drawText("/",getWidth()/2f,middle,paint);
            canvas.drawText(lines.length > 1 ? lines[1] : "",getWidth()/2f,middle+lineHeight,paint);
            paint.setTextAlign(Paint.Align.LEFT); canvas.restore();
        }
    }
    private AlertDialog appSettings() {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView label=new TextView(this);label.setText("Drawing hand");content.addView(label);
        android.widget.RadioGroup sides=new android.widget.RadioGroup(this);sides.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton left=new android.widget.RadioButton(this),right=new android.widget.RadioButton(this);
        left.setId(View.generateViewId());right.setId(View.generateViewId());left.setText("Right-handed");right.setText("Left-handed");
        sides.addView(left,new LinearLayout.LayoutParams(0,dp(48),1));sides.addView(right,new LinearLayout.LayoutParams(0,dp(48),1));
        sides.check(preferences.getBoolean("toolbox_right",false)?right.getId():left.getId());content.addView(sides);
        sides.setOnCheckedChangeListener((group,id) -> {preferences.edit().putBoolean("toolbox_right",id==right.getId()).apply();applyToolboxSide();});
        TextView placement = new TextView(this);
        placement.setTag("hint");placement.setText("Tools sit opposite your drawing hand. Left-handed landscape tools stay at the top.");
        content.addView(placement);
        Button manualRotation = new Button(this);
        manualRotation.setText(landscape ? "Turn to portrait" : "Turn to landscape");
        content.addView(manualRotation);
        TextView sizeLabel=new TextView(this);sizeLabel.setText("Side toolbar icon size");content.addView(sizeLabel);
        android.widget.RadioGroup sizes=new android.widget.RadioGroup(this);sizes.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton medium=new android.widget.RadioButton(this),large=new android.widget.RadioButton(this);
        medium.setId(View.generateViewId());large.setId(View.generateViewId());medium.setText("Medium");large.setText("Large");
        sizes.addView(medium,new LinearLayout.LayoutParams(0,dp(48),1));sizes.addView(large,new LinearLayout.LayoutParams(0,dp(48),1));
        sizes.check(largeToolbarIcons()?large.getId():medium.getId());content.addView(sizes);
        sizes.setOnCheckedChangeListener((group,id) -> {
            preferences.edit().putBoolean("large_toolbar_icons",id==large.getId()).apply();rebuildTools();
        });
        TextView textLabel=new TextView(this);textLabel.setText("Settings text size");content.addView(textLabel);
        android.widget.RadioGroup textSizes=new android.widget.RadioGroup(this);textSizes.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton mediumText=new android.widget.RadioButton(this),largeText=new android.widget.RadioButton(this);
        mediumText.setId(View.generateViewId());largeText.setId(View.generateViewId());mediumText.setText("Medium");largeText.setText("Large");
        mediumText.setContentDescription("Medium settings text");largeText.setContentDescription("Large settings text");
        textSizes.addView(mediumText,new LinearLayout.LayoutParams(0,dp(48),1));textSizes.addView(largeText,new LinearLayout.LayoutParams(0,dp(48),1));
        textSizes.check(largeSettingsText()?largeText.getId():mediumText.getId());content.addView(textSizes);
        TextView toolsLabel=new TextView(this);toolsLabel.setText("Toolbar");content.addView(toolsLabel);
        LinearLayout toolsList=new LinearLayout(this);toolsList.setOrientation(LinearLayout.VERTICAL);
        content.addView(toolsList);renderToolbarSettings(toolsList);
        styleSettings(content);
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Settings").setView(scroll).setPositiveButton("Done",null).create();dialog.show();compactDialog(dialog);
        textSizes.setOnCheckedChangeListener((group,id) -> {
            preferences.edit().putBoolean("large_settings_text",id==largeText.getId()).apply();
            stylePanelText(dialog.getWindow().getDecorView());content.requestLayout();
        });
        manualRotation.setOnClickListener(v -> { dialog.dismiss(); requestQuarter(landscape ? 0 : 3); });
        return dialog;
    }
    private boolean largeToolbarIcons() { return preferences.getBoolean("large_toolbar_icons",true); }
    private java.util.ArrayList<String> toolbarOrder() {
        java.util.ArrayList<String> defaults=new java.util.ArrayList<>(), order=new java.util.ArrayList<>();
        for(ToolSettings.Tool tool:TOOLBAR_TOOLS)defaults.add(tool.name());
        defaults.add("LAYERS");
        defaults.add("ZOOM");
        defaults.add("PALETTE");
        for(String key:preferences.getString("toolbar_order","").split(","))
            if(defaults.contains(key) && !order.contains(key))order.add(key);
        for(String key:defaults)if(!order.contains(key))order.add(key);
        return order;
    }
    private void renderToolbarSettings(LinearLayout list) {
        list.removeAllViews();
        java.util.ArrayList<String> order=toolbarOrder();
        for(int index=0;index<order.size();index++) {
            String key=order.get(index);
            boolean layers=key.equals("LAYERS"),zoom=key.equals("ZOOM"),swatches=key.equals("PALETTE");
            ToolSettings settings=layers || zoom || swatches ? null : library.builtin(ToolSettings.Tool.valueOf(key));
            String name=layers ? "Layers" : zoom ? "Zoom" : swatches ? "Palette" : settings.label();
            LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0,dp(4),0,dp(4));list.addView(row,new LinearLayout.LayoutParams(-1,dp(56)));
            CheckBox visible=new CheckBox(this);visible.setText(name);visible.setTextColor(Color.BLACK);visible.setTextSize(15);
            android.graphics.drawable.Drawable toolIcon=getDrawable(layers ? R.drawable.ic_layers : zoom ? R.drawable.ic_zoom : swatches ? R.drawable.ic_palette : icon(settings));
            toolIcon.setBounds(0,0,dp(28),dp(28));
            visible.setCompoundDrawables(toolIcon,null,null,null);visible.setCompoundDrawablePadding(dp(12));
            visible.setPadding(dp(4),0,dp(8),0);
            visible.setChecked(preferences.getBoolean("tool_visible_"+key,true));
            row.addView(visible,new LinearLayout.LayoutParams(0,-1,1));
            visible.setOnCheckedChangeListener((button,checked) -> {
                if(layers || zoom || swatches) {preferences.edit().putBoolean("tool_visible_"+key,checked).apply();rebuildTools();}
                else if(!setToolVisible(settings.tool,checked)) {
                    button.setChecked(true);message("Keep at least one regular tool visible.");
                }
            });
            for(int direction:new int[]{-1,1}) {
                android.widget.ImageButton move=new android.widget.ImageButton(this);
                move.setImageResource(direction<0 ? R.drawable.ic_move_up : R.drawable.ic_move_down);
                move.setContentDescription("Move "+name+(direction<0 ? " up" : " down"));
                move.setBackgroundColor(Color.WHITE);move.setPadding(dp(12),dp(12),dp(12),dp(12));
                boolean enabled=index+direction>=0 && index+direction<order.size();
                move.setEnabled(enabled);move.setAlpha(enabled ? 1 : .25f);
                row.addView(move,new LinearLayout.LayoutParams(dp(48),dp(48)));
                move.setOnClickListener(v -> {
                    java.util.ArrayList<String> next=toolbarOrder();int from=next.indexOf(key),to=from+direction;
                    if(from<0 || to<0 || to>=next.size())return;
                    java.util.Collections.swap(next,from,to);
                    preferences.edit().putString("toolbar_order",String.join(",",next)).apply();
                    rebuildTools();renderToolbarSettings(list);
                });
            }
            if(index+1<order.size()) {
                View divider=new View(this);divider.setBackgroundColor(0xffdddddd);
                list.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
            }
        }
        stylePanelText(list);
    }
    private void fileMenu(View anchor) {
        String[] names={"New drawing","Open drawing","Save drawing","Save drawing as…","Settings"};
        int[] icons={R.drawable.ic_new,R.drawable.ic_open,R.drawable.ic_save,R.drawable.ic_save,R.drawable.ic_settings};
        if (filePopup != null) filePopup.dismiss();
        if (layersPopup != null) layersPopup.dismiss();
        LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.addView(rows);
        QuarterTurnLayout content=new QuarterTurnLayout(this);content.setTurn(appTurn());content.addView(scroll);
        android.widget.PopupWindow menu=new android.widget.PopupWindow(content,0,0,true);
        for (int i=0;i<names.length;i++) {
            final int position=i;
            TextView row=new TextView(this);row.setText(names[i]);row.setTextColor(Color.BLACK);
            android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
            border.setColor(Color.WHITE);border.setStroke(dp(1),Color.BLACK);row.setBackground(border);
            row.setTextSize(16);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinHeight(dp(52));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[i],0,0,0);row.setCompoundDrawablePadding(dp(12));
            row.setFocusable(true);
            row.setOnClickListener(v -> { menu.dismiss(); fileAction(position); });
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        stylePanelText(rows);
        // Place the menu below the hamburger in the user's orientation, then
        // map its rectangle into Android's portrait window for the popup.
        Matrix toRoot=new Matrix();PanelCoordinates.fromView(root).invert(toRoot);
        android.graphics.RectF anchorBounds=new android.graphics.RectF(0,0,anchor.getWidth(),anchor.getHeight());
        PanelCoordinates.fromView(anchor).mapRect(anchorBounds);toRoot.mapRect(anchorBounds);
        int width=Math.min(dp(largeSettingsText()?256:240),root.getWidth());
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(scroll.getMeasuredHeight(),Math.max(1,root.getHeight()-Math.round(anchorBounds.bottom)));
        float left=toolboxRight ? anchorBounds.right-width : anchorBounds.left;
        left=Math.max(0,Math.min(left,root.getWidth()-width));
        android.graphics.RectF bounds=new android.graphics.RectF(left,anchorBounds.bottom,left+width,anchorBounds.bottom+height);
        PanelCoordinates.fromView(root).mapRect(bounds);
        menu.setWidth(Math.round(bounds.width()));menu.setHeight(Math.round(bounds.height()));
        menu.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        menu.setOutsideTouchable(true);menu.setElevation(0);menu.setAnimationStyle(0);
        menu.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
        menu.setOnDismissListener(() -> { if (filePopup==menu) filePopup=null; });filePopup=menu;
        menu.showAtLocation(root,android.view.Gravity.TOP|android.view.Gravity.LEFT,Math.round(bounds.left),Math.round(bounds.top));
    }
    private void layerChange(Runnable action) {
        if(busy()) return;
        pad.finishStroke(); pad.dryWet();
        try { action.run(); pad.renderDirty(); pad.present(); recovery(); }
        catch(IllegalStateException | IllegalArgumentException error) { message(error.getMessage()); }
    }
    private Button layerAction(LinearLayout row,String label,Runnable action,boolean enabled) {
        Button button=new Button(this); button.setText(label); button.setAllCaps(false);
        button.setTextSize(14); button.setPadding(dp(4),0,dp(4),0);
        button.setContentDescription(label); button.setEnabled(enabled);
        button.setOnClickListener(v -> action.run());
        row.addView(button,new LinearLayout.LayoutParams(0,dp(48),1)); return button;
    }
    private void showLayers(View anchor) {
        if(busy()) return;
        if(layersPopup!=null) { layersPopup.dismiss(); return; }
        pad.finishStroke(); pad.dryWet();
        ToneDocument document=pad.document;
        LinearLayout rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8),dp(8),dp(8),dp(8));
        android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
        border.setColor(Color.WHITE); border.setStroke(dp(2),Color.BLACK); rows.setBackground(border);
        TextView title=new TextView(this); title.setText("Layers · "+document.layerCount()+" / "+ToneDocument.MAX_LAYERS);
        title.setTextColor(Color.BLACK); title.setTextSize(18); title.setPadding(dp(8),dp(4),0,dp(4));
        title.setTypeface(null,android.graphics.Typeface.BOLD); rows.addView(title);
        TextView hint=new TextView(this); hint.setText("Top layers cover the ones below. Tap a name to draw on it.");
        hint.setTag("hint"); hint.setTextColor(Color.BLACK); hint.setPadding(dp(8),0,dp(8),dp(8)); rows.addView(hint);
        Runnable refresh=() -> { if(layersPopup!=null) layersPopup.dismiss(); showLayers(anchor); };
        LinearLayout actions=new LinearLayout(this); rows.addView(actions);
        layerAction(actions,"Add layer",() -> {layerChange(document::addLayer);refresh.run();},document.layerCount()<ToneDocument.MAX_LAYERS);
        layerAction(actions,"Done",() -> {if(layersPopup!=null) layersPopup.dismiss();},true);
        ScrollView list=new ScrollView(this);
        LinearLayout items=new LinearLayout(this); items.setOrientation(LinearLayout.VERTICAL); list.addView(items);
        rows.addView(list,new LinearLayout.LayoutParams(-1,dp(document.layerCount()*56)));
        for(int i=document.layerCount()-1;i>=0;i--) {
            final int index=i; boolean selected=i==document.activeLayer();
            LinearLayout row=new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            Button name=new Button(this); name.setAllCaps(false); name.setText(document.layerName(i)+(selected?"  ✓":""));
            name.setTextSize(15); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setContentDescription(document.layerName(i)+(selected?", selected":"")+", "+(document.layerVisible(i)?"visible":"hidden"));
            name.setTypeface(null,selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
            markActive(name,selected);
            name.setOnClickListener(v -> { layerChange(() -> document.selectLayer(index)); if(layersPopup!=null) layersPopup.dismiss(); });
            row.addView(name,new LinearLayout.LayoutParams(0,dp(52),1));
            CheckBox visible=new CheckBox(this); visible.setText("Show"); visible.setTextSize(13);
            visible.setChecked(document.layerVisible(i)); visible.setContentDescription("Show "+document.layerName(i));
            visible.setOnCheckedChangeListener((view,checked) -> { layerChange(() -> document.setLayerVisible(index,checked)); refresh.run(); });
            row.addView(visible,new LinearLayout.LayoutParams(dp(88),dp(52)));
            items.addView(row,new LinearLayout.LayoutParams(-1,dp(56)));
        }
        TextView active=new TextView(this); active.setText("Editing: "+document.layerName(document.activeLayer()));
        active.setTextSize(14); active.setTextColor(Color.BLACK); active.setPadding(dp(8),dp(8),0,0); rows.addView(active);
        LinearLayout order=new LinearLayout(this); rows.addView(order);
        layerAction(order,"Move up",() -> {layerChange(() -> document.moveLayer(document.activeLayer()+1));refresh.run();},document.activeLayer()+1<document.layerCount());
        layerAction(order,"Move down",() -> {layerChange(() -> document.moveLayer(document.activeLayer()-1));refresh.run();},document.activeLayer()>0);
        LinearLayout manage=new LinearLayout(this); rows.addView(manage);
        layerAction(manage,"Rename",() -> {
            if(layersPopup!=null) layersPopup.dismiss();
            android.widget.EditText name=new android.widget.EditText(this); name.setSingleLine(true);
            name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
            name.setText(document.layerName(document.activeLayer())); name.selectAll();
            showDialog(new AlertDialog.Builder(this).setTitle("Layer name").setView(name)
                    .setPositiveButton("Save",(dialog,which) -> layerChange(() -> document.renameLayer(name.getText().toString())))
                    .setNegativeButton("Cancel",null));
        },true);
        layerAction(manage,"Delete",() -> {
            if(layersPopup!=null) layersPopup.dismiss();
            showDialog(new AlertDialog.Builder(this).setTitle("Delete "+document.layerName(document.activeLayer())+"?")
                    .setMessage("Other layers stay as they are. You can undo this.")
                    .setPositiveButton("Delete",(dialog,which) -> layerChange(document::removeLayer))
                    .setNegativeButton("Cancel",null));
        },document.layerCount()>1);
        stylePanelText(rows);
        ScrollView scroll=new ScrollView(this); scroll.addView(rows);
        QuarterTurnLayout content=new QuarterTurnLayout(this); content.setTurn(appTurn()); content.addView(scroll);
        android.widget.PopupWindow popup=new android.widget.PopupWindow(content,0,0,true);
        Matrix toRoot=new Matrix(); PanelCoordinates.fromView(root).invert(toRoot);
        android.graphics.RectF anchorBounds=new android.graphics.RectF(0,0,anchor.getWidth(),anchor.getHeight());
        PanelCoordinates.fromView(anchor).mapRect(anchorBounds); toRoot.mapRect(anchorBounds);
        int width=Math.min(dp(largeSettingsText()?400:360),root.getWidth());
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(scroll.getMeasuredHeight(),root.getHeight());
        float left=toolboxRight&&!landscape?anchorBounds.left-width:anchorBounds.right;
        float top=landscape?anchorBounds.bottom:anchorBounds.top;
        left=Math.max(0,Math.min(left,root.getWidth()-width)); top=Math.max(0,Math.min(top,root.getHeight()-height));
        android.graphics.RectF bounds=new android.graphics.RectF(left,top,left+width,top+height);
        PanelCoordinates.fromView(root).mapRect(bounds);
        popup.setWidth(Math.round(bounds.width())); popup.setHeight(Math.round(bounds.height()));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        popup.setOutsideTouchable(true); popup.setElevation(0); popup.setAnimationStyle(0);
        popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {if(layersPopup==popup) layersPopup=null;}); layersPopup=popup;
        popup.showAtLocation(root,android.view.Gravity.TOP|android.view.Gravity.LEFT,Math.round(bounds.left),Math.round(bounds.top));
    }
    private void fileAction(int position) {
        if(busy())return;pad.finishStroke();
        if(position==0)newDrawing();else if(position==1)openDrawing();else if(position==2)saveDrawing();else if(position==3)saveDrawingAs();else appSettings();
    }
    private Button button(LinearLayout parent, String title, int icon, Runnable action) {
        ToolButton button = new ToolButton(); button.setText(title); button.setAllCaps(false);
        if(parent==toolRail && largeToolbarIcons())button.iconHalf=18;
        button.setTag(title); button.setContentDescription(title);
        button.setTextSize(14); button.setPadding(dp(10), 0, dp(8), 0);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(4));
        button.setOnClickListener(v -> {
            if (busy()) return;
            if (button != eyedropperButton) setPickingShade(false);
            if(button != eyedropperButton || !pad.hasGradient()) pad.finishStroke();
            action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(60));
        params.gravity=android.view.Gravity.CENTER_HORIZONTAL;
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        parent.addView(button, params); return button;
    }
    private boolean busy() { return loading || saving || pad == null || pad.document == null || pad.fill != null || pad.gradientCommit; }
    private void preferences() {
        try {
            preferences.edit().putInt("gray", gray).putInt("diameter", maximum)
                    .putBoolean("wet_canvas", wetCanvas).putBoolean("transparent_paint", transparentPaint)
                    .putBoolean("erase_mode", eraseMode)
                    .putInt("canvas_wetness", wetness)
                    .putString("tools", java.util.Base64.getEncoder().encodeToString(library.encode())).apply();
        } catch (java.io.IOException error) { message("Could not save tool settings: " + error.getMessage()); }
    }
    private int icon(ToolSettings.Tool tool) {
        switch (tool) {
            case WATERCOLOR: return R.drawable.ic_watercolor;
            case FLAT_WASH: return R.drawable.ic_flat_wash;
            case WET_WATERCOLOR: return R.drawable.ic_wet_watercolor;
            case AIRBRUSH: return R.drawable.ic_airbrush;
            case SHAPES: return R.drawable.ic_shapes;
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
        if(settings.tool==ToolSettings.Tool.SHAPES)return shapeIcon(settings.shape);
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
    private void presentTool(ToolButton button,ToolSettings settings,ToolLibrary.Preset preset) {
        button.iconOnly(icon(settings));
        if(preset==null)button.setContentDescription(toolDescription(settings));
        else {
            int number=library.presetNumber(preset.id);
            if(button.presetNumber!=number) {button.presetNumber=number;button.invalidate();}
            button.setContentDescription(preset.name+", "+settings.description()+", favorite "+number
                    +". Tap to select; tap again for settings. Hold and drag to reorder.");
        }
    }
    private void refreshToolSelection() {
        refreshEraseControl();
        for (ToolSettings.Tool tool : ToolSettings.Tool.values()) {
            ToolButton button = selectionButtons.get("tool:" + tool);
            if (button != null) {
                presentTool(button,library.builtin(tool),null);
            }
        }
        for(ToolLibrary.Preset preset:library.presets()) {
            ToolButton button=selectionButtons.get(preset.id);
            if(button!=null)presentTool(button,preset.settings,preset);
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
        boolean marked, alwaysDot, markerLeft, markerBelow, headerIcon;
        boolean navigationControl;
        int iconHalf = 14, iconOffset, presetNumber;
        String presetId;
        String caption;
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
            // Changing the selected variant only changes the drawable; keep the
            // toolbar's measured geometry stable while its settings panel is open.
            if(centerIcon==null) {
                setText("");setCompoundDrawables(null,null,null,null);setMinWidth(0);setMinimumWidth(0);
                setTextColor(Color.BLACK);setHintTextColor(Color.BLACK);setLinkTextColor(Color.BLACK);
            }
            iconResource = resource;
            centerIcon=getDrawable(resource);invalidate();
        }
        Rect markerArea() {
            if (markerBelow) {
                android.graphics.RectF area = new android.graphics.RectF(getWidth()/2f-dp(3), getHeight()-dp(7),
                        getWidth()/2f+dp(3), getHeight()-dp(1));
                Matrix rotation = new Matrix();
                rotation.setRotate(-toolbarTurn,getWidth()/2f,getHeight()/2f);
                rotation.mapRect(area);
                Rect bounds = new Rect(); area.roundOut(bounds); return bounds;
            }
            return new Rect(markerLeft ? 0 : Math.max(0,getWidth()-dp(24)), 0,
                    markerLeft ? Math.min(getWidth(),dp(24)) : getWidth(), Math.min(getHeight(),dp(24)));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(centerIcon!=null) {
                int half=dp(iconHalf),x=getWidth()/2+dp(iconOffset),y=getHeight()/2-(caption!=null?dp(9):presetId==null?0:dp(4));
                canvas.save();
                if (headerIcon) canvas.rotate(-toolbarTurn,x,y);
                centerIcon.setBounds(x-half,y-half,x+half,y+half);centerIcon.draw(canvas);
                canvas.restore();
            }
            if(caption!=null) {
                markerPaint.setColor(Color.BLACK); markerPaint.setTextAlign(Paint.Align.CENTER);
                markerPaint.setTextSize(dp(12)); markerPaint.setAntiAlias(true);
                canvas.drawText(caption,getWidth()/2f,getHeight()-dp(7),markerPaint);
                markerPaint.setAntiAlias(false);
            }
            if(navigationControl) {
                // This corner is also captured by SelectionFeedback for immediate e-ink updates.
                int right=getWidth()-dp(4),top=dp(4);
                markerPaint.setColor(Color.WHITE);
                canvas.drawRect(right-dp(17),top-dp(1),right+dp(1),top+dp(19),markerPaint);
                markerPaint.setColor(Color.BLACK);markerPaint.setStyle(Paint.Style.STROKE);
                markerPaint.setStrokeWidth(dp(2));markerPaint.setAntiAlias(true);
                float left=right-dp(12);
                canvas.save();
                if(!navigationLocked)canvas.rotate(-35,left+dp(2),top+dp(8));
                canvas.drawArc(left+dp(2),top,right-dp(2),top+dp(12),180,180,false,markerPaint);
                canvas.drawLine(left+dp(2),top+dp(6),left+dp(2),top+dp(9),markerPaint);
                canvas.drawLine(right-dp(2),top+dp(6),right-dp(2),top+dp(9),markerPaint);
                canvas.restore();
                markerPaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(left,top+dp(8),right,top+dp(17),dp(2),dp(2),markerPaint);
                markerPaint.setColor(Color.WHITE);
                canvas.drawCircle(left+dp(6),top+dp(12),dp(1),markerPaint);
                markerPaint.setAntiAlias(false);
            }
            if (presetId != null) {
                markerPaint.setColor(Color.BLACK);
                int columns=Math.max(1,Math.min(10,(getWidth()-dp(12))/dp(6)));
                int rows=(presetNumber+columns-1)/columns;
                float spacing=Math.min(dp(6),dp(18)/(float)Math.max(1,rows));
                float radius=Math.min(dp(2),spacing/3);
                for(int i=0;i<presetNumber;i++) {
                    int row=i/columns,count=Math.min(columns,presetNumber-row*columns);
                    float x=getWidth()/2f+(i%columns-(count-1)/2f)*dp(6);
                    canvas.drawCircle(x,getHeight()-dp(7)-(rows-1-row)*spacing,radius,markerPaint);
                }
            }
            if(settingsArrow!=null) {
                int x=getWidth()-dp(10),y=getHeight()/2;
                settingsArrow.setBounds(x-dp(8),y-dp(10),x+dp(8),y+dp(10));settingsArrow.draw(canvas);
            }
            if (!marked || (!alwaysDot && !selectionFeedback.enabled)) return;
            if (markerBelow) {
                // Turn with the upright icon, so the square stays below it for either hand.
                canvas.save();canvas.rotate(-toolbarTurn,getWidth()/2f,getHeight()/2f);
                markerPaint.setColor(Color.BLACK);
                canvas.drawRect(getWidth()/2f-dp(3),getHeight()-dp(7),
                        getWidth()/2f+dp(3),getHeight()-dp(1),markerPaint);
                canvas.restore();return;
            }
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
            if (landscape) {
                for(int y=0;y<getHeight();y+=dp(7))
                    canvas.drawRect((getWidth()-dp(2))/2f,y,(getWidth()+dp(2))/2f,Math.min(y+dp(4),getHeight()),dashPaint);
                return;
            }
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
                if (landscape) canvas.drawLine(dropLine,dp(4),dropLine,getHeight()-dp(4),linePaint);
                else canvas.drawLine(dp(4), dropLine, getWidth()-dp(4), dropLine, linePaint);
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
                    pointerY = landscape ? event.getX() : event.getY(); updateTarget();
                    toolRail.removeCallbacks(this);
                    scrollStep = pointerY < dp(48) ? -dp(12)
                            : pointerY > (landscape ? landscapeTools.getWidth() : toolScroll.getHeight())-dp(48) ? dp(12) : 0;
                    if (scrollStep != 0) toolRail.postDelayed(this, 60);
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                    clearTarget(); return true;
                case android.view.DragEvent.ACTION_DROP:
                    pointerY = landscape ? event.getX() : event.getY(); updateTarget();
                    if (!validDrop || busy()) return false;
                    library.moveBefore(source.presetId, beforeId);
                    preferences(); rebuildTools(); return true;
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    reset(); return true;
                default: return true;
            }
        }
        private void updateTarget() {
            float y = pointerY + (landscape ? landscapeTools.getScrollX()-toolRail.getLeft() : toolScroll.getScrollY()-toolRail.getTop());
            beforeId = null; validDrop = false; toolRail.dropLine = -1;
            if (!library.presets().isEmpty()) {
                ToolButton first = selectionButtons.get(library.presets().get(0).id);
                validDrop = first != null && y >= start(first)-dp(4);
            }
            if (validDrop) {
                for (ToolLibrary.Preset preset : library.presets()) {
                    ToolButton button = selectionButtons.get(preset.id);
                    if (button == null || button == source) continue;
                    if (y < (start(button)+end(button))/2f) {
                        beforeId = preset.id; toolRail.dropLine = start(button)-dp(2); break;
                    }
                    toolRail.dropLine = end(button)+dp(2);
                }
                if (toolRail.dropLine < 0) toolRail.dropLine = end(source)+dp(2);
            }
            toolRail.invalidate();
        }
        @Override public void run() {
            if (source == null || scrollStep == 0) return;
            int previous = landscape ? landscapeTools.getScrollX() : toolScroll.getScrollY();
            if (landscape) landscapeTools.scrollBy(scrollStep,0); else toolScroll.scrollBy(0, scrollStep);
            updateTarget();
            if ((landscape ? landscapeTools.getScrollX() : toolScroll.getScrollY()) != previous) toolRail.postDelayed(this, 60);
        }
        private int start(View view) { return landscape ? view.getLeft() : view.getTop(); }
        private int end(View view) { return landscape ? view.getRight() : view.getBottom(); }
        private void clearTarget() {
            toolRail.removeCallbacks(this); scrollStep = 0; validDrop = false;
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
            if(selectedKey().equals(key)) settings();
            else select.run();
        });
        control.iconOnly(icon);control.settingsArrow=getDrawable(R.drawable.ic_chevron);
        if(largeToolbarIcons())control.iconOffset=-4;
        control.setContentDescription(name+". Tap to select; tap again for settings.");
        control.setOnLongClickListener(v -> {
            if(!busy()){setPickingShade(false);pad.finishStroke();if(!selectedKey().equals(key))select.run();
                settings();}return true;
        });
        return control;
    }
    private java.util.ArrayList<Integer> paletteShades() {
        java.util.ArrayList<Integer> shades=new java.util.ArrayList<>();
        String saved=preferences.getString("palette_shades","0,128,192,255");
        if(saved.isEmpty())return shades;
        for(String item:saved.split(",")) {
            try {
                int value=Integer.parseInt(item);
                if(value>=0 && value<=255 && shades.size()<16)shades.add(value);
            } catch(NumberFormatException ignored) { }
        }
        if(shades.isEmpty())shades.add(0);
        return shades;
    }
    private void savePalette(java.util.List<Integer> shades) {
        StringBuilder value=new StringBuilder();
        for(int shade:shades) { if(value.length()>0)value.append(',');value.append(shade); }
        preferences.edit().putString("palette_shades",value.toString()).apply();
        if(sidebarPalette==null)return;
        if(sidebarSwatches.size()!=shades.size()) {
            while(sidebarPalette.getChildCount()>1)sidebarPalette.removeViewAt(1);
            addSidebarSwatches(shades);
        } else for(int i=0;i<shades.size();i++) {
            PaletteSwatch swatch=sidebarSwatches.get(i);swatch.presentTone(shades.get(i),selectionFeedback,hasWindowFocus());
            describePaletteSwatch(swatch,i);
        }
    }
    private void describePaletteSwatch(PaletteSwatch swatch,int index) {
        swatch.setContentDescription("Palette shade "+(index+1)+": "+Math.round(swatch.tone*100f/255)+"% brightness");
    }
    private void addSidebarPalette() {
        sidebarPalette=new LinearLayout(this);
        sidebarPalette.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        sidebarPalette.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams block=new LinearLayout.LayoutParams(landscape ? -2 : dp(60),landscape ? dp(60) : -2);
        block.setMargins(dp(2),dp(2),dp(2),dp(2));
        toolRail.addView(sidebarPalette,block);
        ToolButton settings=(ToolButton)button(sidebarPalette,"Palette settings",R.drawable.ic_palette,this::paletteSettings);
        settings.iconOnly(R.drawable.ic_palette);settings.settingsArrow=getDrawable(R.drawable.ic_chevron);
        markActive(settings,false);
        settings.setLayoutParams(new LinearLayout.LayoutParams(dp(landscape ? 44 : 60),dp(landscape ? 60 : 44)));
        addSidebarSwatches(paletteShades());
    }
    private void addSidebarSwatches(java.util.List<Integer> shades) {
        sidebarSwatches.clear();
        for(int i=0;i<shades.size();i+=2) {
            LinearLayout pair=new LinearLayout(this);
            pair.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            sidebarPalette.addView(pair,new LinearLayout.LayoutParams(dp(landscape ? 40 : 60),dp(landscape ? 60 : 40)));
            for(int j=i;j<Math.min(i+2,shades.size());j++) {
                int tone=shades.get(j);
                PaletteSwatch swatch=new PaletteSwatch(tone);
                sidebarSwatches.add(swatch);describePaletteSwatch(swatch,j);
                swatch.setOnClickListener(v -> {
                    if(busy())return;
                    hideGradientHint();selectShade(swatch.tone);pad.applyGradient();preferences();
                });
                pair.addView(swatch,new LinearLayout.LayoutParams(dp(landscape ? 40 : 30),dp(landscape ? 30 : 40)));
            }
        }
    }
    private void paletteSettings() {
        if(paletteEditor!=null) {closePaletteEditor();return;}
        if(busy())return;
        setPickingShade(false);pad.finishStroke();pad.dryWet();pad.disconnectDisplay();
        if(filePopup!=null)filePopup.dismiss();
        if(layersPopup!=null)layersPopup.dismiss();
        if(toolPicker!=null)toolPicker.dismiss();
        paletteEditor=new PaletteEditor();paletteEditor.show();
    }
    private void closePaletteEditor() {
        if(paletteEditor!=null)paletteEditor.popup.dismiss();
    }
    private final class PaletteEditor {
        final java.util.ArrayList<Integer> shades=paletteShades();
        final java.util.ArrayList<PaletteSwatch> cells=new java.util.ArrayList<>();
        final LinearLayout content=new LinearLayout(PaintActivity.this), grid=new LinearLayout(PaintActivity.this);
        final TextView hint=new TextView(PaintActivity.this);
        final android.widget.ImageButton trash=new android.widget.ImageButton(PaintActivity.this);
        final android.widget.PopupWindow popup;
        final ScrollView scroll=new ScrollView(PaintActivity.this);
        final SelectionFeedback swatchFeedback=new SelectionFeedback();
        boolean colorsDirty;
        int pendingTone;
        int selected=-1, dropTarget=-1;
        PaletteDrag dragging;
        final Runnable delayedHint=() -> {
            if(paletteEditor==this && selected==shades.size() && selected<16 && dragging==null)
                hint.setText("Select a color");
        };
        PaletteEditor() {
            content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(8),dp(12),dp(8));
            android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
            border.setColor(Color.WHITE);border.setStroke(dp(1),Color.BLACK);border.setCornerRadius(dp(4));content.setBackground(border);
            LinearLayout heading=new LinearLayout(PaintActivity.this);heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title=new TextView(PaintActivity.this);title.setText("Palette");title.setTextSize(18);title.setTextColor(Color.BLACK);
            heading.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
            Button close=new Button(PaintActivity.this);close.setText("×");close.setTextSize(24);close.setContentDescription("Close palette");
            close.setBackgroundColor(Color.WHITE);close.setOnClickListener(v -> closePaletteEditor());
            heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(heading);
            LinearLayout tools=new LinearLayout(PaintActivity.this);tools.setGravity(android.view.Gravity.TOP);
            grid.setOrientation(LinearLayout.VERTICAL);tools.addView(grid,new LinearLayout.LayoutParams(dp(112),-2));
            trash.setImageResource(R.drawable.ic_trash);trash.setContentDescription("Drag a palette swatch here to delete");
            trash.setBackgroundColor(Color.WHITE);trash.setPadding(dp(12),dp(12),dp(12),dp(12));
            LinearLayout.LayoutParams bin=new LinearLayout.LayoutParams(dp(48),dp(48));bin.leftMargin=dp(16);
            tools.addView(trash,bin);trash.setClickable(false);
            trash.setOnDragListener((v,event) -> dragEvent(-2,event));content.addView(tools);
            hint.setTextSize(14);hint.setTextColor(Color.BLACK);hint.setMinHeight(dp(44));hint.setPadding(0,dp(8),0,0);content.addView(hint);
            scroll.addView(content);
            QuarterTurnLayout rotated=new QuarterTurnLayout(PaintActivity.this);rotated.setTurn(appTurn());rotated.addView(scroll);
            // A non-focusable panel leaves the original color strip touchable.
            popup=new android.widget.PopupWindow(rotated,0,0,false);
            popup.setOutsideTouchable(false);popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            popup.setElevation(0);popup.setAnimationStyle(0);popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
            popup.setOnDismissListener(() -> {
                hint.removeCallbacks(delayedHint);dragging=null;
                commitColors();swatchFeedback.close();
                if(paletteEditor==this)paletteEditor=null;
                preferences();pad.invalidate();pad.post(pad::connectDisplay);
            });
            render();
        }
        void show() { position(); }
        void position() {
            Matrix inverse=new Matrix();PanelCoordinates.fromView(root).invert(inverse);
            android.graphics.RectF area=new android.graphics.RectF(0,0,pad.getWidth(),pad.getHeight());
            PanelCoordinates.fromView(pad).mapRect(area);inverse.mapRect(area);area.inset(dp(8),dp(8));
            int width=Math.min(dp(224),Math.round(area.width()));
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(area.height()),View.MeasureSpec.AT_MOST));
            int height=Math.min(scroll.getMeasuredHeight(),Math.round(area.height()));
            View anchor=sidebarPalette.getChildAt(0);
            android.graphics.RectF icon=new android.graphics.RectF(0,0,anchor.getWidth(),anchor.getHeight());
            PanelCoordinates.fromView(anchor).mapRect(icon);inverse.mapRect(icon);
            float left=landscape ? icon.left : icon.centerX()<area.centerX() ? icon.right+dp(8) : icon.left-width-dp(8);
            float top=!landscape ? icon.top : icon.centerY()<area.centerY() ? icon.bottom+dp(8) : icon.top-height-dp(8);
            left=Math.max(area.left,Math.min(left,area.right-width));
            top=Math.max(area.top,Math.min(top,area.bottom-height));
            android.graphics.RectF bounds=new android.graphics.RectF(left,top,left+width,top+height);
            PanelCoordinates.fromView(root).mapRect(bounds);
            if(popup.isShowing())popup.update(Math.round(bounds.left),Math.round(bounds.top),Math.round(bounds.width()),Math.round(bounds.height()));
            else {
                popup.setWidth(Math.round(bounds.width()));popup.setHeight(Math.round(bounds.height()));
                popup.showAtLocation(root,android.view.Gravity.TOP|android.view.Gravity.LEFT,Math.round(bounds.left),Math.round(bounds.top));
            }
        }
        void render() {
            grid.removeAllViews();cells.clear();
            int count=Math.min(16,shades.size()+1);
            LinearLayout row=null;
            for(int i=0;i<count;i++) {
                final int index=i;
                if(i%2==0) {row=new LinearLayout(PaintActivity.this);grid.addView(row);}
                PaletteSwatch cell=new PaletteSwatch(i<shades.size()?shades.get(i):-1);cell.editorCell=true;
                cell.setContentDescription(i<shades.size()?"Edit palette shade "+(i+1):"Add palette shade");
                row.addView(cell,new LinearLayout.LayoutParams(dp(56),dp(56)));cells.add(cell);
                cell.setOnClickListener(v -> select(index));
                cell.setOnDragListener((v,event) -> dragEvent(index,event));
                if(i<shades.size()) {
                    float[] down=new float[2];boolean[] started={false};
                    cell.setOnTouchListener((v,event) -> {
                        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) {
                            down[0]=event.getX();down[1]=event.getY();started[0]=false;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        } else if(event.getActionMasked()==MotionEvent.ACTION_MOVE && !started[0]) {
                            float dx=event.getX()-down[0],dy=event.getY()-down[1];
                            int slop=android.view.ViewConfiguration.get(PaintActivity.this).getScaledTouchSlop();
                            if(dx*dx+dy*dy>slop*slop)started[0]=startDrag(cell,index);
                        } else if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL)
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        return started[0];
                    });
                    cell.setOnLongClickListener(v -> startDrag(cell,index));
                }
            }
            stylePanelText(content);refreshSelection();if(popup!=null && popup.isShowing())position();
        }
        void refreshSelection() {
            for(int i=0;i<cells.size();i++) {
                PaletteSwatch cell=cells.get(i);cell.chosen=i==selected;cell.dropHere=i==dropTarget;cell.invalidate();
            }
            trash.setAlpha(selected>=0 && selected<shades.size() || dragging!=null ? 1f : .35f);
            trash.setBackgroundColor(dropTarget==-2 ? 0xffdddddd : Color.WHITE);
        }
        void select(int index) {
            commitColors();
            hint.removeCallbacks(delayedHint);hint.setText("");selected=-1;
            if(index<shades.size())selectShade(shades.get(index));
            selected=index;refreshSelection();preferences();
            if(index==shades.size())hint.postDelayed(delayedHint,3000);
        }
        void colorChanged(int tone) {
            if(selected<0 || selected>shades.size() || dragging!=null)return;
            hint.removeCallbacks(delayedHint);
            // The main strip stays on its normal path throughout the gesture.
            // Keep the grid and sidebar unchanged until the pen/finger lifts.
            pendingTone=tone;colorsDirty=true;
        }
        void commitColors() {
            if(!colorsDirty)return;
            colorsDirty=false;
            if(selected<0 || selected>shades.size())return;
            if(hint.length()>0)hint.setText("");
            boolean added=selected==shades.size();
            if(added) {
                if(shades.size()==16)return;
                shades.add(pendingTone);
            } else {
                if(shades.get(selected)==pendingTone)return;
                shades.set(selected,pendingTone);
                cells.get(selected).presentTone(pendingTone,swatchFeedback,hasWindowFocus());
            }
            savePalette(shades);
            if(added)render();
        }
        boolean startDrag(PaletteSwatch cell,int index) {
            commitColors();
            if(dragging!=null || index>=shades.size())return false;
            hint.removeCallbacks(delayedHint);hint.setText("");
            PaletteDrag token=new PaletteDrag(this,index);
            boolean started=cell.startDragAndDrop(null,new View.DragShadowBuilder(cell) {
                @Override public void onDrawShadow(Canvas canvas) {
                    canvas.save();canvas.rotate(appTurn(),cell.getWidth()/2f,cell.getHeight()/2f);
                    super.onDrawShadow(canvas);canvas.restore();
                }
            },token,0);
            if(started) {dragging=token;cell.setPressed(false);refreshSelection();}
            return started;
        }
        boolean dragEvent(int target,android.view.DragEvent event) {
            if(!(event.getLocalState() instanceof PaletteDrag) || ((PaletteDrag)event.getLocalState()).owner!=this)return false;
            PaletteDrag token=(PaletteDrag)event.getLocalState();
            switch(event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:return true;
                case android.view.DragEvent.ACTION_DRAG_ENTERED:dropTarget=target;refreshSelection();return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:dropTarget=-1;refreshSelection();return true;
                case android.view.DragEvent.ACTION_DROP:
                    dragging=null;dropTarget=-1;
                    if(target==-2)delete(token.index);else move(token.index,target);
                    return true;
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    dragging=null;dropTarget=-1;refreshSelection();
                    if(selected==shades.size())hint.postDelayed(delayedHint,3000);
                    return true;
                default:return true;
            }
        }
        void move(int from,int before) {
            if(from<0 || from>=shades.size() || before<0 || before>shades.size())return;
            int to=before-(from<before?1:0);
            if(from==to)return;
            int tone=shades.remove(from);shades.add(to,tone);
            if(selected==from)selected=to;
            else if(selected>=0 && selected<shades.size()) {
                if(selected>from)selected--;if(selected>=to)selected++;
            }
            savePalette(shades);render();
        }
        void delete(int index) {
            if(index<0 || index>=shades.size())return;
            hint.removeCallbacks(delayedHint);shades.remove(index);savePalette(shades);
            selected=-1;render();select(Math.min(index,shades.size()));
        }
    }
    private final class PaletteDrag {
        final PaletteEditor owner;final int index;
        PaletteDrag(PaletteEditor owner,int index) {this.owner=owner;this.index=index;}
    }
    private final class PaletteSwatch extends View {
        private final Paint ink=new Paint();
        private final Paint border=new Paint();
        private Bitmap tile;
        boolean editorCell, chosen, dropHere, empty;
        int tone=-2;
        private final android.graphics.DashPathEffect dashed=new android.graphics.DashPathEffect(new float[]{dp(4),dp(3)},0);
        PaletteSwatch(int tone) { super(PaintActivity.this);setFocusable(true);setTone(tone); }
        void setTone(int tone) {replaceTone(tone);invalidate();}
        void presentTone(int value,SelectionFeedback feedback,boolean windowFocused) {
            if(tone==value)return;
            feedback.update(this,new Rect(0,0,getWidth(),getHeight()),() -> replaceTone(value),windowFocused);
        }
        private void replaceTone(int tone) {
            this.tone=tone;
            Bitmap previous=tile;empty=tone<0;tile=empty?null:DotGray.tile(tone);
            ink.setShader(empty?null:new BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT));
            if(previous!=null)previous.recycle();
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            int size=dp(editorCell?40:22),left=(getWidth()-size)/2,top=(getHeight()-size)/2;
            ink.setStyle(Paint.Style.FILL);if(!empty)canvas.drawRect(left,top,left+size,top+size,ink);
            border.setColor(Color.BLACK);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(chosen || dropHere?3:1));
            border.setPathEffect(empty && !chosen && !dropHere?dashed:null);
            canvas.drawRect(left,top,left+size,top+size,border);
            if(chosen || dropHere) {border.setPathEffect(null);canvas.drawRect(left-dp(4),top-dp(4),left+size+dp(4),top+size+dp(4),border);}
        }
    }
    private boolean toolVisible(ToolSettings.Tool tool) {
        return preferences.getBoolean("tool_visible_" + tool.name(), true);
    }
    private boolean setToolVisible(ToolSettings.Tool tool, boolean visible) {
        if (!visible) {
            boolean another=false;
            for (ToolSettings.Tool candidate : TOOLBAR_TOOLS)
                if (candidate != tool && toolVisible(candidate)) another=true;
            if (!another) return false;
        }
        preferences.edit().putBoolean("tool_visible_" + tool.name(),visible).apply();
        rebuildTools();preferences();return true;
    }
    private void rebuildTools() {
        if (library.activeId().isEmpty() && library.current().isBrush()) library.edit(library.current().asBrush());
        if (library.activeId().isEmpty() && !toolVisible(library.current().tool)) {
            for (String key : toolbarOrder()) {
                if(key.equals("LAYERS") || key.equals("ZOOM") || key.equals("PALETTE"))continue;
                ToolSettings.Tool tool=ToolSettings.Tool.valueOf(key);
                if (!toolVisible(tool))continue;
                library.select(tool);maximum=library.current().maximum;break;
            }
        }
        brushButton = null;
        layersButton = null;
        zoomButton = null;
        sidebarPalette = null;sidebarSwatches.clear();
        toolRail.removeAllViews(); selectionButtons.clear();
        for (String key : toolbarOrder()) {
            if(key.equals("PALETTE")) {
                if(preferences.getBoolean("tool_visible_PALETTE",true)) addSidebarPalette();
                continue;
            }
            if(key.equals("ZOOM")) {
                if(preferences.getBoolean("tool_visible_ZOOM",true)) {
                    zoomButton=(ToolButton)button(toolRail,"Zoom",R.drawable.ic_zoom,this::toggleNavigationLock);
                    zoomButton.iconOnly(R.drawable.ic_zoom);zoomButton.iconHalf=largeToolbarIcons()?16:12;
                    zoomButton.navigationControl=true;
                    markActive(zoomButton,false);refreshZoom();
                }
                continue;
            }
            if(key.equals("LAYERS")) {
                if(preferences.getBoolean("tool_visible_LAYERS",true)) {
                    layersButton=button(toolRail,"Layers",R.drawable.ic_layers,() -> showLayers(layersButton));
                    ((ToolButton)layersButton).iconOnly(R.drawable.ic_layers);markActive(layersButton,false);
                }
                continue;
            }
            ToolSettings.Tool tool=ToolSettings.Tool.valueOf(key);
            if (!toolVisible(tool)) continue;
            ToolSettings remembered = library.builtin(tool);
            Button b = toolRow("tool:"+tool,remembered.label(), icon(remembered), () -> {
                library.select(tool); maximum = library.current().maximum; refreshToolSelection(); preferences();
            });
            presentTool((ToolButton)b,remembered,null);
            if (tool == ToolSettings.Tool.BRUSH) brushButton = b;
            selectionButtons.put("tool:"+tool,(ToolButton)b);
            ((ToolButton)b).marked = selectedKey().equals("tool:"+tool); markActive(b,((ToolButton)b).marked);
        }
        View divider = new ToolDivider();
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(landscape ? 6 : 36), dp(landscape ? 36 : 6));
        dividerParams.gravity = landscape ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.CENTER_HORIZONTAL;
        dividerParams.setMargins(dp(landscape ? 10 : 0), dp(landscape ? 0 : 10), dp(landscape ? 10 : 0), dp(landscape ? 0 : 10));
        toolRail.addView(divider, dividerParams);
        for (ToolLibrary.Preset preset : library.presets()) {
            Button b = toolRow(preset.id,preset.name, icon(preset.settings), () -> {
                library.recall(preset.id); maximum=library.current().maximum; refreshToolSelection(); preferences();
            });
            b.setTextSize(12); b.setMaxLines(2); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ((ToolButton)b).presetId = preset.id;
            presentTool((ToolButton)b,preset.settings,preset);
            selectionButtons.put(preset.id,(ToolButton)b);
            ((ToolButton)b).marked = selectedKey().equals(preset.id); markActive(b,((ToolButton)b).marked);
            b.setOnLongClickListener(v -> {
                if (!busy()) {
                    setPickingShade(false);
                    pad.finishStroke();
                    v.startDragAndDrop(null, new View.DragShadowBuilder(v) {
                        @Override public void onDrawShadow(Canvas canvas) {
                            canvas.save(); canvas.rotate(appTurn(),v.getWidth()/2f,v.getHeight()/2f);
                            super.onDrawShadow(canvas); canvas.restore();
                        }
                    }, v, 0);
                }
                return true;
            });
        }
        refreshEraseControl();
        operationStatus = new TextView(this); operationStatus.setPadding(dp(8),dp(4),dp(4),dp(4)); toolRail.addView(operationStatus);
    }
    private String toolDescription(ToolSettings settings) {
        settings = settings.asBrush();
        return settings.description()+". Tap to select; tap again for settings.";
    }
    private void refreshZoom() {
        if(zoomButton==null) return;
        String caption=(pad==null?100:pad.viewport.percent())+"%";
        if(caption.equals(zoomButton.caption)) return;
        Runnable change=() -> {zoomButton.caption=caption;describeNavigation();};
        if(pad!=null && pad.navigating && zoomButton.getWidth()>0)
            selectionFeedback.update(zoomButton,new Rect(0,0,zoomButton.getWidth(),zoomButton.getHeight()),change);
        else {change.run();zoomButton.invalidate();}
    }
    private void describeNavigation() {
        zoomButton.setContentDescription("Zoom "+zoomButton.caption+". Zoom and pan "
                +(navigationLocked?"locked. Tap to unlock.":"unlocked. Tap to lock."));
    }
    private void toggleNavigationLock() {
        // Discard any fingers already down; unlocking requires a fresh gesture.
        pad.touchBlocked=true;pad.endNavigation();
        selectionFeedback.update(zoomButton,zoomButton.markerArea(),() -> {
            navigationLocked=!navigationLocked;
            describeNavigation();
        });
        preferences.edit().putBoolean("navigation_locked",navigationLocked).apply();
    }
    private int shapeIcon(ToolSettings.Shape shape) {
        switch(shape) {
            case LINE:return R.drawable.ic_shape_line;
            case RECTANGLE:return R.drawable.ic_shape_rectangle;
            case SQUARE:return R.drawable.ic_shape_square;
            case OVAL:return R.drawable.ic_shape_oval;
            case CIRCLE:return R.drawable.ic_shape_circle;
            default:throw new IllegalArgumentException("Unknown shape: "+shape);
        }
    }
    private int tipIcon(ToolSettings.Head head) {
        return head==ToolSettings.Head.FLAT?R.drawable.ic_tip_flat
                :head==ToolSettings.Head.FILBERT?R.drawable.ic_tip_filbert:R.drawable.ic_tip_round;
    }
    private Button variantChoice(String label,int icon,boolean selected,Runnable choose) {
        Button choice=new Button(this);choice.setText(label);choice.setTag(label);
        choice.setAllCaps(false);choice.setTextColor(Color.BLACK);
        choice.setGravity(android.view.Gravity.CENTER);choice.setPadding(dp(2),dp(8),dp(2),dp(4));
        choice.setMinWidth(0);choice.setMinimumWidth(0);choice.setStateListAnimator(null);
        android.graphics.drawable.Drawable glyph=getDrawable(icon);glyph.setBounds(0,0,dp(32),dp(32));
        choice.setCompoundDrawables(null,glyph,null,null);choice.setCompoundDrawablePadding(dp(6));
        android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
        border.setColor(selected?0xffeeeeee:Color.WHITE);border.setStroke(dp(selected?2:1),selected?Color.BLACK:0xffbbbbbb);
        border.setCornerRadius(dp(8));choice.setBackground(border);choice.setSelected(selected);
        choice.setOnClickListener(v -> choose.run());return choice;
    }
    private void addVariant(LinearLayout row,Button choice) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(84),1);
        params.setMargins(dp(2),0,dp(2),0);row.addView(choice,params);
    }
    private LinearLayout headChoices(ToolSettings settings, java.util.function.Consumer<ToolSettings.Head> choose) {
        LinearLayout row=new LinearLayout(this);row.setContentDescription("Brush tips");
        for(ToolSettings.Head head:ToolSettings.Head.values()) {
            Button choice=variantChoice(head.label,tipIcon(head),settings.head==head,() -> choose.accept(head));
            choice.setContentDescription(head.label+" "+settings.label());addVariant(row,choice);
        }
        return row;
    }
    private LinearLayout shapeChoices(ToolSettings settings,java.util.function.Consumer<ToolSettings.Shape> choose) {
        LinearLayout row=new LinearLayout(this);row.setContentDescription("Shape choices");
        for(ToolSettings.Shape shape:ToolSettings.Shape.values()) {
            Button choice=variantChoice(shape.label,shapeIcon(shape),settings.shape==shape,() -> choose.accept(shape));
            choice.setContentDescription(shape.label+" shape");addVariant(row,choice);
        }
        return row;
    }
    private LinearLayout brushEditor() {
        LinearLayout editor=new LinearLayout(this);editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(12),dp(12),dp(12),dp(4));
        ToolSettings current=library.current();
        TextView hint=new TextView(this);hint.setTag("hint");hint.setTextColor(Color.BLACK);
        hint.setText(current.head==ToolSettings.Head.ROUND?"Even in every direction"
                :current.head==ToolSettings.Head.FLAT?"Fine, straight edge · follows tilt":"Full, rounded edge · follows tilt");editor.addView(hint);
        Footprint preview=new Footprint();editor.addView(preview,new LinearLayout.LayoutParams(-1,dp(112)));
        TextView low=new TextView(this),high=new TextView(this);
        SeekBar minimumSize=new SeekBar(this),size=new SeekBar(this);
        String dimension=current.head==ToolSettings.Head.ROUND?"diameter":"width";
        minimumSize.setContentDescription("Minimum "+dimension);size.setContentDescription("Maximum "+dimension);
        sliderRow(editor,low,minimumSize,true);sliderRow(editor,high,size,true);
        TextView heightLabel=current.head==ToolSettings.Head.FLAT?new TextView(this):null;
        SeekBar height=current.head==ToolSettings.Head.FLAT?new SeekBar(this):null;
        if(height!=null) {
            height.setContentDescription("Brush height");height.setMax(ToolSettings.MAX_FLAT_HEIGHT);
            sliderRow(editor,heightLabel,height,true);
            TextView heightHint=new TextView(this);heightHint.setTag("hint");heightHint.setTextColor(Color.BLACK);
            heightHint.setText("1 px minimum; up to 20% of the pressure-sized width.");editor.addView(heightHint);
        }
        boolean[] syncing={false};
        Runnable update=() -> {
            syncing[0]=true;ToolSettings settings=library.current();
            low.setText("Min "+dimension+"\n"+settings.minimum+" px");high.setText("Max "+dimension+"\n"+settings.maximum+" px");
            minimumSize.setMax(settings.maximum-1);minimumSize.setProgress(settings.minimum-1);
            size.setMax(ToolSettings.sizeLimit(settings.head)-2);size.setProgress(settings.maximum-2);
            if(height!=null) {
                heightLabel.setText("Height\n"+(settings.headThickness==0?"1 px":settings.headThickness+"% width"));
                height.setProgress(settings.headThickness);
            }
            syncing[0]=false;preview.invalidate();
        };
        if(height!=null)height.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                if(syncing[0])return;library.edit(library.current().headThickness(value));update.run();preferences();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
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
            TextView mode=new TextView(this);mode.setTag("hint");mode.setTextColor(Color.BLACK);mode.setPadding(0,dp(6),0,0);
            mode.setText(current.tool==ToolSettings.Tool.WATERCOLOR?"Black dots; white adds no ink."
                    :current.tool==ToolSettings.Tool.FLAT_WASH?"Even gray; darker marks stay."
                    :"Colors mingle while wet. White adds water; use the dryer to set.");editor.addView(mode);
        }
        update.run();
        return editor;
    }
    private android.widget.PopupWindow showToolPanel() {
        if(toolPicker!=null)toolPicker.dismiss();
        closePaletteEditor();
        View anchor=selectionButtons.get(selectedKey());
        if(anchor==null)anchor=toolRail;
        ToolSettingsPanel panel=new ToolSettingsPanel(anchor);panel.show();return panel.popup;
    }
    /** Common sizing, rotation, typography and dismissal for sidebar settings panels. */
    private class SettingsPanel {
        final View anchor;
        final int preferredWidth;
        final LinearLayout panel=new LinearLayout(PaintActivity.this);
        final ScrollView scroll=new ScrollView(PaintActivity.this);
        final android.widget.PopupWindow popup;
        SettingsPanel(View anchor,int width) {
            this.anchor=anchor;preferredWidth=width;
            panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(10),dp(4),dp(10),dp(10));
            android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
            border.setColor(Color.WHITE);border.setStroke(dp(1),Color.BLACK);border.setCornerRadius(dp(8));panel.setBackground(border);
            scroll.addView(panel);
            QuarterTurnLayout rotated=new QuarterTurnLayout(PaintActivity.this);rotated.setTurn(appTurn());rotated.addView(scroll);
            popup=new android.widget.PopupWindow(rotated,0,0,true);
            popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);popup.setElevation(0);popup.setAnimationStyle(0);
            popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
            popup.setOnDismissListener(() -> {if(toolPicker==popup)toolPicker=null;});
        }
        void show() {
            if(toolPicker!=null)toolPicker.dismiss();
            closePaletteEditor();stylePanelText(panel);toolPicker=popup;position();
        }
        Button closeButton(String description) {
            Button close=new Button(PaintActivity.this);close.setText("×");close.setTag("close");
            close.setContentDescription(description);close.setBackgroundColor(Color.WHITE);close.setStateListAnimator(null);
            close.setOnClickListener(v -> popup.dismiss());return close;
        }
        void position() {
            Matrix inverse=new Matrix();PanelCoordinates.fromView(root).invert(inverse);
            android.graphics.RectF area=new android.graphics.RectF(0,0,pad.getWidth(),pad.getHeight());
            PanelCoordinates.fromView(pad).mapRect(area);inverse.mapRect(area);area.inset(dp(8),dp(8));
            int width=Math.min(dp(preferredWidth),Math.round(area.width()));
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(area.height()),View.MeasureSpec.AT_MOST));
            int height=Math.min(scroll.getMeasuredHeight(),Math.round(area.height()));
            android.graphics.RectF icon=new android.graphics.RectF(0,0,anchor.getWidth(),anchor.getHeight());
            PanelCoordinates.fromView(anchor).mapRect(icon);inverse.mapRect(icon);
            float left=landscape?icon.left:icon.centerX()<area.centerX()?icon.right+dp(8):icon.left-width-dp(8);
            float top=!landscape?icon.top:icon.centerY()<area.centerY()?icon.bottom+dp(8):icon.top-height-dp(8);
            left=Math.max(area.left,Math.min(left,area.right-width));top=Math.max(area.top,Math.min(top,area.bottom-height));
            android.graphics.RectF bounds=new android.graphics.RectF(left,top,left+width,top+height);PanelCoordinates.fromView(root).mapRect(bounds);
            if(popup.isShowing())popup.update(Math.round(bounds.left),Math.round(bounds.top),Math.round(bounds.width()),Math.round(bounds.height()));
            else {popup.setWidth(Math.round(bounds.width()));popup.setHeight(Math.round(bounds.height()));
                popup.showAtLocation(root,android.view.Gravity.TOP|android.view.Gravity.LEFT,Math.round(bounds.left),Math.round(bounds.top));}
        }
    }
    private final class ToolSettingsPanel extends SettingsPanel {
        final boolean shapes=library.current().tool==ToolSettings.Tool.SHAPES;
        final boolean brush=library.current().isBrush();
        final String presetId=library.activeId();
        ToolSettingsPanel(View anchor) {
            super(anchor,library.current().tool==ToolSettings.Tool.SHAPES?(largeSettingsText()?560:520):(largeSettingsText()?448:400));
            render();
        }
        void render() {
            panel.removeAllViews();
            ToolSettings current=library.current();
            if(shapes || brush) panel.addView(shapes?shapeChoices(current,shape -> {
                library.edit(library.current().shape(shape));selected();
            }):headChoices(current,head -> {library.selectHead(head);selected();}));
            if(shapes || brush) {
                View divider=new View(PaintActivity.this);divider.setBackgroundColor(0xffcccccc);
                LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.setMargins(dp(2),dp(12),dp(2),0);panel.addView(divider,line);
            }
            panel.addView(shapes?shapeEditor(this::position):brush?brushEditor():toolEditor());
            LinearLayout actions=new LinearLayout(PaintActivity.this);actions.setGravity(android.view.Gravity.END);
            Button save=new Button(PaintActivity.this);save.setAllCaps(false);save.setBackgroundColor(Color.WHITE);
            if(presetId.isEmpty()) {save.setText("Add to Toolbar");save.setContentDescription("Add to Toolbar");}
            else {
                save.setContentDescription("Delete custom tool");
                save.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_trash,0,0,0);
            }
            save.setOnClickListener(v -> {
                try {
                    if(presetId.isEmpty())library.add();else library.remove(presetId);
                    maximum=library.current().maximum;preferences();popup.dismiss();rebuildTools();
                } catch(IllegalStateException error){message(error.getMessage());}
            });
            actions.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));
            actions.addView(closeButton(shapes?"Close shapes":brush?"Close brush":"Close tool settings"),new LinearLayout.LayoutParams(dp(48),dp(48)));
            panel.addView(actions);stylePanelText(panel);
            if(popup.isShowing())position();
        }
        void selected() {
            maximum=library.current().maximum;preferences();refreshToolSelection();render();
        }
    }
    private LinearLayout shapeEditor(Runnable resized) {
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12),dp(12),dp(12),dp(4));
        ToolSettings current=library.current();
        android.widget.RadioGroup modes=new android.widget.RadioGroup(this);modes.setOrientation(LinearLayout.HORIZONTAL);
        for(boolean filled:new boolean[]{false,true}) {
            android.widget.RadioButton choice=new android.widget.RadioButton(this);
            choice.setId(View.generateViewId());choice.setText(filled?"Filled":"Outline");choice.setTextColor(Color.BLACK);
            choice.setContentDescription(filled?"Filled shape":"Outline shape");choice.setTag(filled);
            modes.addView(choice,new LinearLayout.LayoutParams(0,dp(56),1));choice.setChecked(current.filled==filled);
        }
        content.addView(modes);modes.setVisibility(current.shape==ToolSettings.Shape.LINE?View.GONE:View.VISIBLE);
        LinearLayout widthControls=new LinearLayout(this);widthControls.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(this);String caption=current.shape==ToolSettings.Shape.LINE?"Line width":"Outline width";
        label.setText(caption+"\n"+current.outlineWidth+" px");
        SeekBar width=new SeekBar(this);width.setContentDescription("Shape outline width");
        width.setMax(127);width.setProgress(current.outlineWidth-1);sliderRow(widthControls,label,width,true);content.addView(widthControls);
        widthControls.setVisibility(current.shape==ToolSettings.Shape.LINE || !current.filled?View.VISIBLE:View.GONE);
        modes.setOnCheckedChangeListener((group,id) -> {
            View choice=group.findViewById(id);if(choice==null)return;
            library.edit(library.current().filled((Boolean)choice.getTag()));preferences();refreshToolSelection();
            widthControls.setVisibility(library.current().filled?View.GONE:View.VISIBLE);resized.run();
        });
        width.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                library.edit(library.current().outlineWidth(value+1));label.setText(caption+"\n"+(value+1)+" px");preferences();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        TextView note=new TextView(this);note.setText("Drag to size · lift to finish");note.setTextColor(Color.BLACK);note.setTag("hint");
        note.setPadding(0,dp(8),0,dp(4));content.addView(note);
        return content;
    }
    private android.widget.PopupWindow settings() {return showToolPanel();}
    private LinearLayout toolEditor() {
        ToolSettings current=library.current();
        if(current.tool==ToolSettings.Tool.AIRBRUSH) {
            LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16),dp(8),dp(16),dp(8));
            TextView label=new TextView(this);label.setText("Diameter\n"+current.maximum+" px");
            SeekBar size=new SeekBar(this);size.setContentDescription("Airbrush diameter");
            size.setMax(126);size.setProgress(current.maximum-2);sliderRow(content,label,size,true);
            size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar,int value,boolean user) {
                    maximum=value+2;library.edit(library.current().size(maximum));
                    label.setText("Diameter\n"+maximum+" px");preferences();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            settingSlider(content,"Flow",current.strength,value -> {
                library.edit(library.current().strength(value));preferences();
            });
            TextView note=new TextView(this);note.setTag("hint");
            note.setText("Press harder for stronger spray. Hold or move slowly to build color. Size stays fixed.");
            content.addView(note);return content;
        }
        if(current.tool==ToolSettings.Tool.FILL) {
            LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(16),dp(8),dp(16),dp(8));
            TextView note=new TextView(this);note.setTag("hint");
            Runnable hint=() -> note.setText("• Tap for solid fill\n• "+(library.current().gradient==ToolSettings.Gradient.CIRCULAR
                    ? "Drag center to edge, then choose a second color" : "Drag a line for gradient, then choose a second color"));
            TextView title=new TextView(this);title.setText("Gradient type");content.addView(title);
            android.widget.RadioGroup types=new android.widget.RadioGroup(this);types.setOrientation(LinearLayout.HORIZONTAL);
            for(ToolSettings.Gradient type:ToolSettings.Gradient.values()) {
                android.widget.RadioButton choice=new android.widget.RadioButton(this);
                choice.setId(View.generateViewId());choice.setText(type.label);choice.setTextColor(Color.BLACK);
                choice.setContentDescription(type.label+" gradient");choice.setTag(type);
                types.addView(choice,new LinearLayout.LayoutParams(0,dp(48),1));
                choice.setChecked(current.gradient==type);
            }
            types.setOnCheckedChangeListener((group,id) -> {
                View choice=group.findViewById(id);if(choice==null)return;
                library.edit(library.current().gradient((ToolSettings.Gradient)choice.getTag()));
                preferences();refreshToolSelection();hint.run();
            });
            content.addView(types);
            settingSlider(content,"Tolerance",current.tolerance,value -> {
                library.edit(library.current().tolerance(value));preferences();
            });
            hint.run();content.addView(note);
            return content;
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
        sliderRow(content,minimumLabel,minimumSize,true);
        TextView label = new TextView(this);
        SeekBar size = new SeekBar(this); size.setContentDescription("Maximum "+dimension);size.setMax(126); size.setProgress(maximum - 2);
        sliderRow(content,label,size,true);
        Runnable update = () -> {
            syncing[0]=true;ToolSettings s=library.current();
            minimumLabel.setText(("Min "+dimension+"\n")+s.minimum+" px");
            label.setText(("Max "+dimension+"\n")+s.maximum+" px");
            minimumSize.setMax(s.maximum-1);minimumSize.setProgress(s.minimum-1);size.setProgress(s.maximum-2);
            if(tipControl[0]!=null) {
                tipControl[0].setMax(s.maximum-s.minimum);tipControl[0].setProgress(s.tip-s.minimum);
                tipLabel[0].setText("Upright tip\n"+s.tip+" px");
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
            CheckBox tilt=new CheckBox(this); tilt.setText("Broaden with tilt");tilt.setContentDescription("Broaden with tilt"); tilt.setChecked(current.tilt); content.addView(tilt);
            tilt.setOnCheckedChangeListener((b,checked) -> { ToolSettings s=library.current(); library.edit(s.options(s.tip,s.soft,checked)); preferences(); preview.invalidate(); });
            tipLabel[0]=new TextView(this);
            SeekBar tip=new SeekBar(this);tip.setContentDescription("Upright tip at full pressure");tipControl[0]=tip;sliderRow(content,tipLabel[0],tip,true);
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
        return content;
    }

    private void sliderRow(LinearLayout content,TextView label,SeekBar bar,boolean compact) {
        if (!compact) { content.addView(label);content.addView(bar);return; }
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        label.setTextColor(Color.BLACK);
        row.addView(label,new LinearLayout.LayoutParams(dp(largeSettingsText()?156:140),-2));
        row.addView(bar,new LinearLayout.LayoutParams(0,dp(56),1));
        content.addView(row);
    }
    private void settingSlider(LinearLayout content,String name,int initial,java.util.function.IntConsumer change) {
        settingSlider(content,name,initial,true,change);
    }
    private void settingSlider(LinearLayout content,String name,int initial,boolean compact,java.util.function.IntConsumer change) {
        String caption=compact?(name.equals("Pressure response")?"Pressure":name)+"\n":name+": ";
        TextView label=new TextView(this);label.setText(caption+initial+"%");
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(initial);bar.setContentDescription(name);
        sliderRow(content,label,bar,compact);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider,int value,boolean user){label.setText(caption+value+"%");change.accept(value);}
            @Override public void onStartTrackingTouch(SeekBar slider){}
            @Override public void onStopTrackingTouch(SeekBar slider){}
        });
    }
    private void compactDialog(AlertDialog dialog) {compactDialog(dialog,440);}
    private void compactDialog(AlertDialog dialog,int width) {
        orientDialog(dialog,width);
        stylePanelText(dialog.getWindow().getDecorView());
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
            Button b=dialog.getButton(which);if(b!=null){b.setAllCaps(false);b.setTypeface(null,android.graphics.Typeface.BOLD);}
        }
    }
    private AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog=builder.create(); dialog.show(); compactDialog(dialog); return dialog;
    }
    void orientDialog(AlertDialog dialog) {orientDialog(dialog,440);}
    private void orientDialog(AlertDialog dialog,int desiredWidth) {
        android.view.Window window=dialog.getWindow();
        if (window==null) return;
        int width=Math.min(dp(desiredWidth),root.getWidth()-dp(32));
        if (appRotation == Surface.ROTATION_0) { window.setLayout(width,-2); return; }
        android.view.ViewGroup content=window.findViewById(android.R.id.content);
        if (content==null || content.getChildCount()!=1 || content.getChildAt(0) instanceof QuarterTurnLayout) return;
        View panel=content.getChildAt(0); content.removeView(panel);
        QuarterTurnLayout frame=new QuarterTurnLayout(this); frame.setTurn(appTurn()); frame.addView(panel);
        content.addView(frame,new android.widget.FrameLayout.LayoutParams(-1,-1));
        window.setLayout(landscape ? -2 : width,landscape ? width : -2);
    }
    private boolean largeSettingsText() {return preferences.getBoolean("large_settings_text",false);}
    private float settingsTextSize() {return largeSettingsText()?18:16;}
    private void stylePanelText(View view) {
        if(view instanceof TextView) {
            TextView text=(TextView)view;
            boolean hint="hint".equals(text.getTag());
            text.setTextSize("close".equals(text.getTag())?22:hint?settingsTextSize()-1:settingsTextSize());
            text.setTextColor(Color.BLACK);
            text.setTypeface(null,hint || text instanceof android.widget.EditText?android.graphics.Typeface.NORMAL:android.graphics.Typeface.BOLD);
            if(text instanceof Button)((Button)text).setAllCaps(false);
        }
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)stylePanelText(group.getChildAt(i));
        }
    }
    private void styleSettings(LinearLayout content) {stylePanelText(content);}
    private void recovery() {
        if (loading || pad.document == null) return;
        store.recoverLater(new DocumentStore.Snapshot(book, drawingName), (unused, error) -> {
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
        showDialog(new AlertDialog.Builder(this).setTitle("New drawing?")
                .setMessage("Start a new drawing with one blank page. Save first to keep all pages of this drawing.")
                .setPositiveButton("New", (d,w) -> { pad.replace(null); drawingName = ""; recovery(); })
                .setNegativeButton("Cancel", null));
    }
    private void clearDrawing() {
        showDialog(new AlertDialog.Builder(this).setTitle("Clear this layer?")
                .setMessage("Remove every mark on "+pad.document.layerName(pad.document.activeLayer())+". Other layers and pages stay as they are. You can undo this.")
                .setPositiveButton("Clear",(d,w) -> {
                    pad.dryWet();
                    if(!pad.document.layerVisible(pad.document.activeLayer())) { message("Show this layer before clearing it."); return; }
                    if(pad.document.clear()) {pad.renderDirty();pad.present();recovery();}
                }).setNegativeButton("Cancel",null));
    }
    private void saveDrawing() {
        showSaveError();
        if (drawingName.isEmpty()) saveDrawingAs();
        else saveDrawingTo(drawingName, true);
    }
    private void saveDrawingAs() {
        showSaveError();
        new DrawingBrowser(this, store, true, drawingName, this::saveDrawingTo, () -> {});
    }
    private void saveDrawingTo(String path, boolean replace) {
        if (busy()) return;
        pad.finishStroke(); pad.dryWet();
        DrawingBook savedBook = book;
        saving = true;
        store.save(path, new DocumentStore.Snapshot(book), replace, (unused, error) -> runOnUiThread(() -> {
            saving = false;
            if (destroyed) return;
            if (error != null) { message("Save failed: " + error.getMessage()); return; }
            // A failed save must never change the destination of subsequent saves.
            if (book == savedBook) { drawingName = path; recovery(); }
            message("Saved " + path);
        }));
    }
    private void openDrawing() {
        showSaveError();
        new DrawingBrowser(this, store, false, drawingName, (path, replace) -> {
            boolean recovering = loading;
            loading = true;
            store.openBook(path, (document, error) -> runOnUiThread(() -> {
                if (destroyed) return;
                loading = false;
                if (error != null) {
                    message("Open failed: " + error.getMessage());
                    if (recovering) { loading = true; openRecoveryChoice(); }
                    else pad.post(pad::connectDisplay);
                    return;
                }
                replaceBook(document); drawingName = path; recovery();
            }));
        }, () -> { if (!destroyed && loading) openRecoveryChoice(); });
    }
    private void openRecoveryChoice() {
        showDialog(new AlertDialog.Builder(this).setTitle("Start a new drawing?")
                .setMessage("The previous recovery file could not be read.")
                .setPositiveButton("New", (d,w) -> { loading = false; pad.replace(null); drawingName = ""; recovery(); })
                .setNegativeButton("Open", (d,w) -> openDrawing()).setCancelable(false));
    }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (orientationSensor != null && orientationSensor.canDetectOrientation()) orientationSensor.enable();
        if (pad != null) { pad.updateViewport(); pad.post(pad::connectDisplay); }
    }
    @Override protected void onPause() {
        closePaletteEditor();
        setPickingShade(false);
        if (filePopup != null) filePopup.dismiss();
        if (layersPopup != null) layersPopup.dismiss();
        if (toolPicker != null) toolPicker.dismiss();
        resumed = false;
        if (orientationSensor != null) orientationSensor.disable();
        hideRotationSuggestion();
        if (presetDrag != null) presetDrag.reset();
        if (pad != null) { pad.finishStroke(); pad.dryWet(); pad.disconnectDisplay(); }
        if (!loading && book != null) { preferences(); recovery(); }
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (pad == null) return;
        if (focus) { pad.post(pad::connectDisplay); showSaveError(); }
        else { hideRotationSuggestion(); pad.finishStroke(); pad.disconnectDisplay(); }
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (orientationSensor != null) orientationSensor.disable();
        selectionFeedback.close();
        if (pad != null) pad.close();
        book=null;
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if(paletteEditor!=null) {closePaletteEditor();return;}
        if(pad!=null && (pad.hasGradient() || pad.fillGesture)) { pad.finishStroke(); return; }
        super.onBackPressed();
    }

    private void showGradientHint() {
        if(pad==null || !pad.gradientWaiting || pickingShade || !resumed || !hasWindowFocus()) return;
        TextView hint=new TextView(this); hint.setText("Select a second color");
        hint.setTextSize(14); hint.setTextColor(Color.BLACK); hint.setBackgroundColor(Color.WHITE);
        hint.setPadding(dp(8),dp(4),dp(8),dp(4));
        hint.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,root.getWidth()-dp(16)),View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        Matrix toRoot=new Matrix(); PanelCoordinates.fromView(root).invert(toRoot);
        android.graphics.RectF anchor=new android.graphics.RectF(0,0,shadePicker.getWidth(),shadePicker.getHeight());
        PanelCoordinates.fromView(shadePicker).mapRect(anchor); toRoot.mapRect(anchor);
        int width=hint.getMeasuredWidth(), height=hint.getMeasuredHeight();
        float x=landscape ? (toolboxRight?anchor.left-width-dp(4):anchor.right+dp(4)) : anchor.centerX()-width/2f;
        int left=Math.round(Math.max(0,Math.min(x,root.getWidth()-width)));
        int top=Math.round(Math.max(0,Math.min(anchor.bottom+dp(4),root.getHeight()-height)));
        hint.layout(left,top,left+width,top+height);
        gradientHint=hint; root.getOverlay().add(hint);
    }
    private void hideGradientHint() {
        if(shadePicker!=null) shadePicker.removeCallbacks(gradientHintTask);
        if(gradientHint!=null) { root.getOverlay().remove(gradientHint); gradientHint=null; }
    }

    private void selectShade(int value) {
        if (busy()) return;
        setPickingShade(false);
        if(!pad.hasGradient()) pad.finishStroke();
        setEraseMode(false);
        if(gray!=value)
            selectionFeedback.update(shadePicker, new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()), () -> gray = value);
        if(pad.hasGradient()) pad.previewGradient(value);
        if(paletteEditor!=null)paletteEditor.colorChanged(value);
    }
    private void setEraseMode(boolean value) {
        if (eraseMode != value)
            selectionFeedback.update(shadePicker, new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()),
                    () -> eraseMode = value);
        refreshEraseControl();
    }
    private void refreshEraseControl() {
        if (eraseButton == null) return;
        boolean supported = library.current().supportsEraseMode();
        if (!supported && eraseMode) { setEraseMode(false); return; }
        eraseButton.setEnabled(supported);
        eraseButton.setAlpha(supported ? 1f : .3f);
        eraseButton.setContentDescription(supported ? "Erase with current tool" : "Erasing requires a brush, pencil, or airbrush");
        markPaintMode(eraseButton, eraseMode && !pickingShade);
    }
    private void setPickingShade(boolean picking) {
        if (pickingShade == picking) return;
        if(picking) { hideGradientHint(); pickOriginalShade=gray; pickedShade=false; }
        selectionFeedback.update(shadePicker, new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()),
                () -> pickingShade = picking);
        refreshEraseControl();
        if (eyedropperButton != null) {
            markPaintMode(eyedropperButton,picking);
            eyedropperButton.setContentDescription(picking ? "Cancel picking color" : "Pick color from canvas");
        }
    }
    private ToolButton paintModeButton(LinearLayout parent, String name, int icon, Runnable action) {
        ToolButton control = (ToolButton)button(parent, name, icon, action);
        control.headerIcon = true;
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
            // Keep the narrow slider next to its droplet with room to touch either side.
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
            endpointWidth = Math.min(dp(12), width / 16);
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
            int saved=canvas.save();
            // Black stays at the bottom; the black marker faces the canvas
            // whether the landscape palette is on the left or the right.
            if (landscape) canvas.scale(-1,toolboxRight ? 1 : -1,getWidth()/2f,getHeight()/2f);
            int inset = dp(5);
            canvas.drawBitmap(strip, inset, inset, null);
            marker.setColor(Color.BLACK); marker.setStyle(Paint.Style.STROKE); marker.setStrokeWidth(1);
            canvas.drawRect(inset, inset, inset + strip.getWidth(), inset + strip.getHeight(), marker);
            if ((eraseMode && !pickingShade) || (pickingShade && !pickedShade)) { canvas.restoreToCount(saved); return; }
            int density = DotPattern.whiteCount(gray), width = strip.getWidth();
            float x = inset + (density == 0 ? endpointWidth / 2f : density == 64 ? width - endpointWidth / 2f
                    : endpointWidth + DotPattern.pickerPosition(gray) * (width - 2 * endpointWidth - 1) / 255f);
            marker.setStyle(Paint.Style.FILL);
            canvas.drawRect(x - dp(3), getHeight() - dp(7), x + dp(3), getHeight() - dp(1), marker);
            marker.setColor(Color.WHITE); marker.setStrokeWidth(dp(3));
            canvas.drawLine(x, inset, x, inset + dp(7), marker);
            marker.setColor(Color.BLACK); marker.setStrokeWidth(1);
            canvas.drawLine(x, inset, x, inset + dp(7), marker);
            canvas.restoreToCount(saved);
        }
        private void selectX(float x) {
            if (strip == null || !Float.isFinite(x)) return;
            if (landscape) x=getWidth()-x;
            selectShade(toneAt(x - dp(5), strip.getWidth()));
        }
        private int toneAt(float x, int width) {
            int position = Math.round((x - endpointWidth) * 255f / Math.max(1, width - 2 * endpointWidth - 1));
            return DotPattern.pickerTone(position);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (busy()) return true;
            int action = event.getActionMasked();
            boolean up=(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(event.getActionIndex())==activePointer;
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0);
                hideGradientHint();
                if(!pad.hasGradient()) pad.finishStroke();
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            int index = event.findPointerIndex(activePointer);
            if (index >= 0 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || up))
                selectX(event.getX(index));
            if (up || action == MotionEvent.ACTION_CANCEL) {
                if(up) pad.applyGradient(); else pad.cancelGradient();
                if(paletteEditor!=null)paletteEditor.commitColors();
                activePointer = -1; preferences(); getParent().requestDisallowInterceptTouchEvent(false);
                if (up) performClick();
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
        @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
            if(keyCode==android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode==android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                pad.applyGradient();if(paletteEditor!=null)paletteEditor.commitColors();return true;
            }
            return super.onKeyUp(keyCode,event);
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
                paint.setShader(null);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextSize((settingsTextSize()-1)*getResources().getDisplayMetrics().scaledDensity);paint.setTextAlign(Paint.Align.CENTER);
                String prefix=i==0?"Min ":settings.tool==ToolSettings.Tool.PENCIL?(settings.tilt?"Max tilted ":"Max upright "):"Max ";
                canvas.drawText(prefix+diameter+" px",x,getHeight()-dp(8),paint);
            }
        }
    }

    private final class DrawingPad extends View {
        private ToneDocument document;
        private Bitmap display;
        private ViewportBitmap viewportBitmap;
        private final CanvasViewport viewport = new CanvasViewport();
        private final Matrix pageToView = new Matrix(), viewToPage = new Matrix();
        private final float[] samplePoint = new float[2];
        private boolean unscaledPage = true;
        private int pageRotation = Surface.ROTATION_0;
        private int[] renderPixels = new int[0];
        private final Rect pending = new Rect();
        private DirectEink direct;
        private final NativePen input;
        private DrawingStroke stroke;
        private final Runnable sprayStep = new Runnable() {
            @Override public void run() {
                if (!(stroke instanceof AirbrushStroke) || !resumed || !hasWindowFocus()) return;
                ((AirbrushStroke)stroke).advance(SystemClock.uptimeMillis());
                renderDirty();flush(false);postDelayed(this,32);
            }
        };
        private WetWatercolor wet;
        private boolean wetChanged, wetScheduled;
        private final WetWorkBudget wetBudget = new WetWorkBudget();
        private final android.os.MessageQueue wetQueue = android.os.Looper.getMainLooper().getQueue();
        private final android.os.MessageQueue.IdleHandler wetIdle = () -> { advanceWet(); return false; };
        private long lastWetComputeNanos, lastWetRenderNanos, lastWetPresentNanos, wetMaxSliceNanos;
        private int wetSliceCount;
        private ShapePreview shapeStroke;
        private float shapeX,shapeY;
        private boolean shapeFrameScheduled;
        private long lastShapeFrame;
        private int shapeFrameCount;
        private final Runnable shapeFrame=this::drawShapeFrame;
        private FloodFill fill;
        private FloodFill gradientFill;
        private boolean fillGesture, gradientWaiting, gradientReady, gradientCommit;
        private int gradientShade, gradientPassShade, pickPointer = -1;
        private byte[] gradientSample;
        private float fillStartX, fillStartY, fillEndX, fillEndY;
        private int fillShade, fillTolerance;
        private ToolSettings.Gradient fillGradient=ToolSettings.Gradient.LINEAR;
        private final Paint gradientGuide = new Paint();
        private final Runnable gradientStep = this::advanceGradient;
        private int pointer = -1, retries;
        private long lastPresent;
        private boolean fallbackNotice;
        private int drawCount;
        private final Runnable retry = () -> flush(true);
        private final Runnable fillStep = this::advanceFill;
        private final Runnable wetStep = () -> wetQueue.addIdleHandler(wetIdle);
        private int fingerA=-1, fingerB=-1;
        private boolean navigating, touchBlocked;
        private boolean navigationFrameScheduled;
        private long lastNavigationFrame, lastNavigationRasterNanos, lastNavigationPresentNanos;
        private int navigationFrameCount;
        private final Runnable navigationFrame=this::drawNavigationFrame;
        private float fingerX, fingerY, fingerSpan;
        private long penGuardUntil;

        DrawingPad() {
            super(PaintActivity.this);
            setContentDescription("Drawing canvas; use the pen to paint. Unlock Zoom to pinch and pan with two fingers.");
            input = new NativePen(this, (bitmap, region) -> bitmap.recycle());
        }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            finishStroke(); disconnectDisplay();
            if (w <= 0 || h <= 0) return;
            if (!loading && document == null) {
                document = new ToneDocument(landscape ? h : w,landscape ? w : h);
                book=new DrawingBook(document);updatePages();
            }
            ensureDisplay(); updateViewport();
            renderAll();
        }
        private void ensureDisplay() {
            int width = document != null ? document.width : landscape ? getHeight() : getWidth();
            int height = document != null ? document.height : landscape ? getWidth() : getHeight();
            if (width <= 0 || height <= 0) return;
            if (display != null && display.getWidth() == width && display.getHeight() == height) return;
            if (display != null) display.recycle();
            display = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            display.setHasAlpha(false);
        }
        void updateViewport() {
            if (display == null || getWidth() <= 0 || getHeight() <= 0) return;
            int width = display.getWidth(), height = display.getHeight();
            pageToView.reset();
            // Cancel our own UI rotation for the artwork; Android stays in portrait.
            if(pageRotation!=appRotation) viewport.reset();
            pageRotation = appRotation;
            if (pageRotation == Surface.ROTATION_90) { pageToView.setRotate(-90); pageToView.postTranslate(0,width); }
            else if (pageRotation == Surface.ROTATION_180) { pageToView.setRotate(180); pageToView.postTranslate(width,height); }
            else if (pageRotation == Surface.ROTATION_270) { pageToView.setRotate(90); pageToView.postTranslate(height,0); }
            boolean swapped = pageRotation == Surface.ROTATION_90 || pageRotation == Surface.ROTATION_270;
            viewport.configure(getWidth(),getHeight(),swapped?height:width,swapped?width:height);
            float scale=viewport.scale();
            pageToView.postScale(scale,scale);
            pageToView.postTranslate(viewport.x,viewport.y);
            pageToView.invert(viewToPage);
            unscaledPage = pageRotation == Surface.ROTATION_0 && pageToView.isIdentity();
            boolean wasScaled=viewportBitmap!=null;
            // Keep one screen-sized raster and presenter throughout a pinch, including
            // its fitted endpoints. Changing format on each crossing stalls the gesture.
            boolean scaled=navigating || viewport.zoom!=1 || scale!=1;
            if(viewportBitmap!=null && (!scaled || viewportBitmap.bitmap.getWidth()!=getWidth()
                    || viewportBitmap.bitmap.getHeight()!=getHeight())) { viewportBitmap.close(); viewportBitmap=null; }
            if(scaled && viewportBitmap==null) viewportBitmap=new ViewportBitmap(getWidth(),getHeight());
            if(wasScaled!=scaled) renderAll();
            else if(viewportBitmap!=null) viewportBitmap.update(display,pageToView,null);
            pending.setEmpty(); refreshZoom();
        }
        private void viewportChanged() {
            disconnectDisplay(); updateViewport(); invalidate();
            if(!navigating) post(this::connectDisplay);
        }
        void zoomBy(float factor) {
            viewport.gesture(factor,getWidth()/2f,getHeight()/2f,getWidth()/2f,getHeight()/2f);
            viewportChanged();
        }
        void fitPage() { endNavigation(); viewport.reset(); viewportChanged(); }
        void replace(ToneDocument replacement) {
            finishStroke(); dryWet(); disconnectDisplay();
            viewport.reset();
            document = replacement;
            if (document == null && getWidth() > 0 && getHeight() > 0)
                document = new ToneDocument(landscape ? getHeight() : getWidth(),landscape ? getWidth() : getHeight());
            book=document==null?null:new DrawingBook(document);updatePages();
            ensureDisplay(); updateViewport();
            renderAll(); invalidate(); post(this::connectDisplay);
        }
        void showPage(ToneDocument page) {
            finishStroke();dryWet();disconnectDisplay();document=page;
            viewport.reset();
            ensureDisplay(); updateViewport();
            renderAll();invalidate();post(this::connectDisplay);
        }
        private void renderAll() {
            if (display == null) return;
            display.eraseColor(Color.WHITE);
            if (document != null) {
                if(viewportBitmap!=null)ViewportBitmap.compose(document,display);
                else render(new Rect(0, 0, Math.min(document.width, display.getWidth()), Math.min(document.height, display.getHeight())));
                document.clearDirty();
            }
            pending.setEmpty();
            if(viewportBitmap!=null) viewportBitmap.update(display,pageToView,null);
        }
        void renderDirty() {
            if (document == null || display == null) return;
            int[] bounds = document.dirty();
            if (bounds == null) return;
            Rect dirty = new Rect(Math.max(0, bounds[0] - 2), Math.max(0, bounds[1] - 2),
                    Math.min(document.width, bounds[2] + 2), Math.min(document.height, bounds[3] + 2));
            if (dirty.intersect(0, 0, display.getWidth(), display.getHeight())) {
                render(dirty);
                pending.union(viewportBitmap==null?dirty:viewportBitmap.update(display,pageToView,dirty));
            }
            document.clearDirty();
        }
        private void renderShape(Rect dirty) {
            if(dirty.isEmpty())return;
            // Preview pixels are already rasterized natively. Submit one complete frame.
            dirty.inset(-2,-2);
            if(!dirty.intersect(0,0,document.width,document.height))return;
            pending.union(viewportBitmap==null?dirty:viewportBitmap.update(display,pageToView,dirty));
        }
        private void drawShapeFrame() {
            removeCallbacks(shapeFrame);shapeFrameScheduled=false;
            if(shapeStroke==null)return;
            // Budget from frame start; rendering time must not add another full-frame delay.
            lastShapeFrame=SystemClock.uptimeMillis();
            renderShape(shapeStroke.preview(shapeX,shapeY,display,viewportBitmap!=null));
            flush(true);shapeFrameCount++;
        }
        private void render(Rect dirty) {
            int count = dirty.width() * dirty.height();
            if (renderPixels.length < count) renderPixels = new int[count];
            if(viewportBitmap==null) document.render(renderPixels, dirty.left, dirty.top, dirty.width(), dirty.height());
            else for(int y=dirty.top,i=0;y<dirty.bottom;y++) for(int x=dirty.left;x<dirty.right;x++) {
                int tone=document.compositeTone(x,y); renderPixels[i++]=Color.rgb(tone,tone,tone);
            }
            display.setPixels(renderPixels, 0, dirty.width(), dirty.left, dirty.top, dirty.width(), dirty.height());
        }
        @Override protected void onDraw(Canvas canvas) {
            drawCount++;
            canvas.drawColor(Color.WHITE);
            if(viewportBitmap!=null) canvas.drawBitmap(viewportBitmap.bitmap,0,0,null);
            else if (display != null) canvas.drawBitmap(display,pageToView,null);
            if(fillGesture || gradientWaiting) {
                float[] axis={fillStartX,fillStartY,fillEndX,fillEndY}; pageToView.mapPoints(axis);
                gradientGuide.setStyle(Paint.Style.STROKE);
                gradientGuide.setColor(Color.WHITE); gradientGuide.setStrokeWidth(dp(5));
                canvas.drawLine(axis[0],axis[1],axis[2],axis[3],gradientGuide);
                gradientGuide.setColor(Color.BLACK); gradientGuide.setStrokeWidth(dp(2));
                canvas.drawLine(axis[0],axis[1],axis[2],axis[3],gradientGuide);
                gradientGuide.setStyle(Paint.Style.FILL);
                canvas.drawCircle(axis[0],axis[1],dp(4),gradientGuide);
                canvas.drawCircle(axis[2],axis[3],dp(4),gradientGuide);
            }
        }
        void connectDisplay() {
            scheduleWet();
            if (paletteEditor!=null || !resumed || !hasWindowFocus() || loading || display == null || direct != null) return;
            if (isLayoutRequested() || root.isLayoutRequested() || orientationFrame.isLayoutRequested()) return;
            try {
                if (!input.prepareDocumentCanvas()) throw new IllegalStateException(input.status);
                direct = viewportBitmap==null ? DirectEink.forView(this,display,pageToView,0,7)
                        : DirectEink.forView(this,viewportBitmap.bitmap,new Matrix(),0,7);
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
            if (direct == null) { invalidate(); pending.setEmpty(); return; }
            try {
                int result=direct.present(viewportBitmap==null?display:viewportBitmap.bitmap,pending);
                lastPresent = SystemClock.uptimeMillis();
                if (result >= 0) { pending.setEmpty();retries=0; }
                else if (++retries < 120) postDelayed(retry, 8);
                else throw new IllegalStateException("Display remained busy");
            } catch (RuntimeException error) {
                Log.e(ProbeActivity.TAG, "Direct display failed", error);
                disconnectDisplay(); invalidate(); pending.setEmpty();
                saveError = "Fast display stopped; drawing is retained. Reopen the app to retry.";
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (paletteEditor!=null || loading || document == null || fill != null || gradientCommit || !resumed || !hasWindowFocus()) return true;
            if(navigationGesture(event)) return true;
            if(pickingShade || pickPointer!=-1) { sampleShadeGesture(event); return true; }
            if(hasGradient()) return true;
            if(fillGesture) { continueFillGesture(event); return true; }
            if(shapeStroke!=null) { continueShape(event); return true; }
            long inputStart = System.nanoTime();
            long eventAge = Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
            boolean drawingInput = false;
            int action = event.getActionMasked(), index = event.getActionIndex();
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                    && pointer == -1 && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
                pagePoint(event.getX(index),event.getY(index));
                if (samplePoint[0] < 0 || samplePoint[1] < 0
                        || samplePoint[0] >= document.width || samplePoint[1] >= document.height) return true;
                if(!document.layerVisible(document.activeLayer())) {
                    message("This layer is hidden. Open Layers to show it or select another layer."); return true;
                }
                ToolSettings settings=library.current().size(maximum);
                boolean erasing = eraseMode && settings.supportsEraseMode();
                if (erasing || !settings.isBrush() || !wetCanvas) dryWet();
                else if (wet == null) wet = new WetWatercolor(document, wetness);
                if(settings.tool==ToolSettings.Tool.SHAPES) {
                    renderDirty();
                    pointer=event.getPointerId(index);
                    shapeX=samplePoint[0];shapeY=samplePoint[1];
                    shapeStroke=new ShapePreview(document,settings,gray,shapeX,shapeY);
                    getParent().requestDisallowInterceptTouchEvent(true);drawShapeFrame();return true;
                }
                if(settings.tool==ToolSettings.Tool.FILL) {
                    fillGesture=true; pointer=event.getPointerId(index);
                    fillStartX=fillEndX=samplePoint[0]; fillStartY=fillEndY=samplePoint[1];
                    fillShade=gray; fillTolerance=settings.tolerance; fillGradient=settings.gradient;
                    getParent().requestDisallowInterceptTouchEvent(true); invalidate(); return true;
                }
                pointer = event.getPointerId(index);
                cancelWetCallback();
                drawingInput = true;
                if(settings.isBrush() || (settings.tool==ToolSettings.Tool.ERASER && settings.softness==0))
                    stroke = new PressureStroke(document, settings, settings.tool==ToolSettings.Tool.ERASER ? 255 : gray, wet, settings.isBrush() && transparentPaint, erasing);
                else if(settings.tool==ToolSettings.Tool.AIRBRUSH) stroke=new AirbrushStroke(document,settings,gray,erasing);
                else stroke = new ToolStroke(document,settings,gray,erasing);
                getParent().requestDisallowInterceptTouchEvent(true);
                // Supernote encodes signed X degrees in ORIENTATION, Y in TILT.
                sampleEvent(event,index,-1);
                if(stroke instanceof AirbrushStroke) postDelayed(sprayStep,32);
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP) {
                int p = event.findPointerIndex(pointer);
                if (p >= 0 && stroke != null && (action == MotionEvent.ACTION_MOVE || event.getPointerId(index) == pointer)) {
                    drawingInput = true;
                    for (int h = 0; h < event.getHistorySize(); h++)
                        sampleEvent(event,p,h);
                    // Keep the airbrush's final motion segment; other tools retain the 0.12 pen-up behavior.
                    if (action == MotionEvent.ACTION_MOVE || stroke instanceof AirbrushStroke) sampleEvent(event,p,-1);
                }
            }
            renderDirty(); flush(false);
            if (drawingInput) wetBudget.input(System.nanoTime() - inputStart, eventAge, SystemClock.uptimeMillis());
            scheduleWet();
            if (action == MotionEvent.ACTION_CANCEL || ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(index) == pointer)) finishStroke();
            return true;
        }
        private void continueShape(MotionEvent event) {
            int action=event.getActionMasked(), index=event.findPointerIndex(pointer);
            if(action==MotionEvent.ACTION_CANCEL || index<0) { finishStroke();return; }
            boolean up=(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(event.getActionIndex())==pointer;
            if(action==MotionEvent.ACTION_MOVE || up) {
                pagePoint(event.getX(index),event.getY(index));
                shapeX=samplePoint[0];shapeY=samplePoint[1];
                if(!up && !shapeFrameScheduled) {
                    shapeFrameScheduled=true;
                    postDelayed(shapeFrame,Math.max(0,16-(SystemClock.uptimeMillis()-lastShapeFrame)));
                }
            }
            if(up) {
                drawShapeFrame();
                boolean changed=shapeStroke.finish();shapeStroke=null;pointer=-1;
                // The committed geometry matches the preview exactly; keep its native raster.
                document.clearDirty();
                getParent().requestDisallowInterceptTouchEvent(false);
                if(changed) recovery();
            }
        }
        private void pagePoint(float x, float y) {
            samplePoint[0] = x; samplePoint[1] = y;
            if (!unscaledPage) viewToPage.mapPoints(samplePoint);
        }
        @Override public boolean onHoverEvent(MotionEvent event) {
            if(event.getToolType(0)==MotionEvent.TOOL_TYPE_STYLUS) {
                penGuardUntil=SystemClock.uptimeMillis()+250;
                if(navigating) { touchBlocked=true; endNavigation(); }
            }
            return true;
        }
        private void endNavigation() {
            fingerA=fingerB=-1;
            if(!navigating) return;
            if(navigationFrameScheduled)drawNavigationFrame();
            navigating=false; getParent().requestDisallowInterceptTouchEvent(false);
            if(viewport.zoom==1 && viewport.scale()==1) {
                disconnectDisplay();updateViewport();invalidate();
            }
            if(direct==null)post(this::connectDisplay);else scheduleWet();
        }
        private void drawNavigationFrame() {
            removeCallbacks(navigationFrame);navigationFrameScheduled=false;
            if(!navigating)return;
            lastNavigationFrame=SystemClock.uptimeMillis();
            long began=System.nanoTime();
            updateViewport();
            long rendered=System.nanoTime();
            pending.set(0,0,getWidth(),getHeight());flush(true);
            // Keep Android's retained drawing commands current without requesting a
            // compositor frame for every finger movement on the direct display path.
            if(direct!=null)selectionFeedback.retainForNextDraw(this,new Rect(0,0,getWidth(),getHeight()));
            lastNavigationRasterNanos=rendered-began;
            lastNavigationPresentNanos=System.nanoTime()-rendered;
            navigationFrameCount++;
        }
        private boolean navigationGesture(MotionEvent event) {
            int action=event.getActionMasked(), index=event.getActionIndex();
            if(action==MotionEvent.ACTION_DOWN) { touchBlocked=false; endNavigation(); }
            boolean pen=false;
            for(int i=0;i<event.getPointerCount();i++)
                if(event.getToolType(i)==MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(i)==MotionEvent.TOOL_TYPE_ERASER) pen=true;
            if(pen || pointer!=-1 || pickPointer!=-1 || fillGesture || hasGradient()
                    || SystemClock.uptimeMillis()<penGuardUntil) {
                touchBlocked=true; endNavigation();
                if(pen) penGuardUntil=SystemClock.uptimeMillis()+250;
                return false;
            }
            if(navigationLocked) { touchBlocked=true;endNavigation();return true; }
            if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP) { endNavigation(); return true; }
            if(touchBlocked) return true;
            if(action==MotionEvent.ACTION_POINTER_UP) {
                // Require a fresh two-finger gesture after either tracked finger lifts.
                if(event.getPointerId(index)==fingerA || event.getPointerId(index)==fingerB) {
                    touchBlocked=true; endNavigation();
                }
                return true;
            }
            if(!navigating) {
                if(action!=MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount()!=2
                        || event.getToolType(0)!=MotionEvent.TOOL_TYPE_FINGER
                        || event.getToolType(1)!=MotionEvent.TOOL_TYPE_FINGER) return true;
                fingerA=event.getPointerId(0); fingerB=event.getPointerId(1);
                fingerX=(event.getX(0)+event.getX(1))/2; fingerY=(event.getY(0)+event.getY(1))/2;
                fingerSpan=(float)Math.hypot(event.getX(1)-event.getX(0),event.getY(1)-event.getY(0));
                if(fingerSpan<dp(24)) { touchBlocked=true; fingerA=fingerB=-1; return true; }
                disconnectDisplay();navigating=true;
                updateViewport();connectDisplay();
                getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            int a=event.findPointerIndex(fingerA), b=event.findPointerIndex(fingerB);
            if(a<0 || b<0) { touchBlocked=true; endNavigation(); return true; }
            if(action==MotionEvent.ACTION_MOVE) {
                float x=(event.getX(a)+event.getX(b))/2, y=(event.getY(a)+event.getY(b))/2;
                float span=(float)Math.hypot(event.getX(b)-event.getX(a),event.getY(b)-event.getY(a));
                if(span<dp(24)) return true;
                viewport.gesture(span/fingerSpan,fingerX,fingerY,x,y);
                fingerX=x; fingerY=y; fingerSpan=span;
                if(!navigationFrameScheduled) {
                    navigationFrameScheduled=true;
                    postDelayed(navigationFrame,Math.max(0,16-(SystemClock.uptimeMillis()-lastNavigationFrame)));
                }
            }
            return true;
        }
        private void continueFillGesture(MotionEvent event) {
            int action=event.getActionMasked(), p=event.findPointerIndex(pointer);
            if(action==MotionEvent.ACTION_CANCEL) { finishStroke(); return; }
            if(p<0) return;
            boolean up=(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(event.getActionIndex())==pointer;
            if(action!=MotionEvent.ACTION_MOVE && !up) return;
            pagePoint(event.getX(p),event.getY(p));
            fillEndX=samplePoint[0]; fillEndY=samplePoint[1]; invalidate();
            if(!up) return;
            fillGesture=false; pointer=-1; getParent().requestDisallowInterceptTouchEvent(false);
            float[] axis={fillStartX,fillStartY,fillEndX,fillEndY}; pageToView.mapPoints(axis);
            if(Math.hypot(axis[2]-axis[0],axis[3]-axis[1])<dp(8)) {
                fill=new FloodFill(document,(int)fillStartX,(int)fillStartY,fillShade,fillTolerance);
                operationStatus.setText("Filling…"); post(fillStep);
            } else {
                // Keep only the direction guide until the second color gesture begins.
                gradientWaiting=true; gradientReady=false; gradientCommit=false;
                hideGradientHint(); shadePicker.postDelayed(gradientHintTask,3000);
            }
        }
        private void sampleShadeGesture(MotionEvent event) {
            int action=event.getActionMasked(), index=event.getActionIndex();
            if(action==MotionEvent.ACTION_CANCEL) { finishStroke(); return; }
            if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN)
                    && pickPointer==-1 && event.getToolType(index)==MotionEvent.TOOL_TYPE_STYLUS) {
                pickPointer=event.getPointerId(index);
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            int p=event.findPointerIndex(pickPointer);
            if(p<0) return;
            boolean up=(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)
                    && event.getPointerId(index)==pickPointer;
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN
                    || action==MotionEvent.ACTION_MOVE || up) {
                pagePoint(event.getX(p),event.getY(p));
                if(samplePoint[0]>=0 && samplePoint[1]>=0 && samplePoint[0]<document.width && samplePoint[1]<document.height) {
                    int x=(int)samplePoint[0],y=(int)samplePoint[1];
                    // The unmodified composite prevents sampling the gradient's own preview.
                    int shade=gradientSample==null?document.compositeTone(x,y):gradientSample[y*document.width+x]&255;
                    if(gray!=shade || !pickedShade)
                        selectionFeedback.update(shadePicker,new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()),
                                () -> {gray=shade; pickedShade=true;});
                    previewGradient(shade);
                }
            }
            if(up) {
                pickPointer=-1;getParent().requestDisallowInterceptTouchEvent(false);
                if(pickedShade) { setEraseMode(false); setPickingShade(false); applyGradient(); preferences(); }
            }
        }
        private boolean hasGradient() { return gradientWaiting || gradientFill!=null; }
        private void previewGradient(int shade) {
            if(!hasGradient()) return;
            hideGradientHint();
            if(gradientWaiting) {
                gradientSample=document.snapshot();
                gradientShade=gradientPassShade=shade;
                gradientFill=new FloodFill(document,(int)fillStartX,(int)fillStartY,fillShade,fillTolerance,
                        fillStartX,fillStartY,fillEndX,fillEndY,shade,fillGradient);
                gradientWaiting=false; gradientReady=false;
                invalidate(); post(gradientStep); return;
            }
            gradientShade=shade;
            // Finish the current preview pass before starting the latest shade. Rapid
            // moves must not continually restart a large fill and starve its display.
            if(gradientReady && gradientPassShade!=shade) {
                gradientFill.secondShade(shade); gradientPassShade=shade; gradientReady=false;
                post(gradientStep);
            }
        }
        private void advanceGradient() {
            if(gradientFill==null) return;
            long deadline=System.nanoTime()+5_000_000L;
            boolean done;
            do { done=gradientFill.advance(1024); } while(!done && System.nanoTime()<deadline);
            if(!done) { postDelayed(gradientStep,1); return; }
            renderDirty(); flush(true); gradientReady=true;
            if(gradientPassShade!=gradientShade) previewGradient(gradientShade);
            else if(gradientCommit) applyGradient();
        }
        private void applyGradient() {
            if(gradientFill==null) return;
            gradientCommit=true;
            if(!gradientReady) return;
            boolean changed=gradientFill.finish(); gradientFill=null; gradientSample=null; gradientCommit=false;
            operationStatus.setText("");
            if(changed) recovery();
        }
        private void cancelGradient() {
            hideGradientHint(); removeCallbacks(gradientStep);
            if(!hasGradient()) return;
            if(gradientFill!=null) gradientFill.cancel();
            if(gradientWaiting) { gradientWaiting=false; invalidate(); }
            gradientFill=null; gradientSample=null; gradientReady=false; gradientCommit=false;
            operationStatus.setText(""); renderDirty(); flush(true);
            selectionFeedback.update(shadePicker,new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()),() -> gray=fillShade);
            preferences();
        }
        private void sampleEvent(MotionEvent event,int pointerIndex,int history) {
            MotionEvent physical=physicalPenEvent == null ? event : physicalPenEvent;
            int source=physical.findPointerIndex(event.getPointerId(pointerIndex));
            if (source<0 || history>=physical.getHistorySize()) { physical=event; source=pointerIndex; }
            boolean current=history<0;
            float x=current ? event.getX(pointerIndex) : event.getHistoricalX(pointerIndex,history);
            float y=current ? event.getY(pointerIndex) : event.getHistoricalY(pointerIndex,history);
            float pressure=current ? event.getPressure(pointerIndex) : event.getHistoricalPressure(pointerIndex,history);
            float tiltX=current ? physical.getOrientation(source) : physical.getHistoricalOrientation(source,history);
            float tiltY=current ? physical.getAxisValue(MotionEvent.AXIS_TILT,source) : physical.getHistoricalAxisValue(MotionEvent.AXIS_TILT,source,history);
            pagePoint(x,y);
            // The page stays device-relative, as do Supernote's signed tilt-degree axes.
            // Transform positions only; treating ORIENTATION as Android azimuth corrupts tilt.
            if(stroke instanceof AirbrushStroke) {
                boolean up=current && (event.getActionMasked()==MotionEvent.ACTION_UP
                        || event.getActionMasked()==MotionEvent.ACTION_POINTER_UP);
                if(up) ((AirbrushStroke)stroke).endAt(samplePoint[0],samplePoint[1],event.getEventTime());
                else ((AirbrushStroke)stroke).sampleAt(samplePoint[0],samplePoint[1],pressure,
                        current?event.getEventTime():event.getHistoricalEventTime(history));
            } else stroke.sample(samplePoint[0],samplePoint[1],pressure,tiltX,tiltY);
        }
        void finishStroke() {
            if(navigating) touchBlocked=true;
            endNavigation();
            removeCallbacks(sprayStep);
            if(shapeStroke!=null) {
                removeCallbacks(shapeFrame);shapeFrameScheduled=false;
                renderShape(shapeStroke.cancel(display,viewportBitmap!=null));shapeStroke=null;pointer=-1;
                flush(true);getParent().requestDisallowInterceptTouchEvent(false);
            }
            if(pickPointer!=-1) {
                pickPointer=-1; getParent().requestDisallowInterceptTouchEvent(false);
                selectionFeedback.update(shadePicker,new Rect(0,0,shadePicker.getWidth(),shadePicker.getHeight()),() -> gray=pickOriginalShade);
                setPickingShade(false);
            }
            // Pen-up already accepted this fill. Complete it before a lifecycle save.
            if(gradientCommit && gradientFill!=null) {
                removeCallbacks(gradientStep);
                gradientFill.secondShade(gradientShade);
                while(!gradientFill.advance(8192)) {}
                renderDirty(); flush(true); gradientReady=true; applyGradient();
            }
            if(fillGesture) {
                fillGesture=false; pointer=-1; invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            cancelGradient();
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
            if (wet != null && wet.isAnimating() && resumed && hasWindowFocus() && !loading && !navigating) {
                if (!wetScheduled) { wetScheduled = true; postDelayed(wetStep, delayMillis); }
            }
        }
        private void cancelWetCallback() {
            removeCallbacks(wetStep); wetQueue.removeIdleHandler(wetIdle); wetScheduled = false;
        }
        private void advanceWet() {
            wetScheduled = false;
            if (wet == null || !resumed || !hasWindowFocus() || loading || navigating) return;
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
        void close() {
            finishStroke(); dryWet(); disconnectDisplay(); input.close();
            if (display != null) display.recycle();
            if(viewportBitmap!=null) {viewportBitmap.close();viewportBitmap=null;}
            display=null; renderPixels=new int[0]; document=null;
        }
    }
}
