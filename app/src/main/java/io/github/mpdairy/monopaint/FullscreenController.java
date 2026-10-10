package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Screen furniture is session state; canvas size and the first-use choice belong to each page. */
final class FullscreenController {
    final PaintActivity app;
    int mode; // 0: all controls; 1: colors only; 2: canvas only; 3: tools only
    private int previousMode;
    AlertDialog dialog;
    private boolean changing;
    private final MultiFingerTap taps=new MultiFingerTap();
    FullscreenController(PaintActivity app) { this.app=app; }

    boolean gesture(MotionEvent event,boolean blocked) {
        int count=event.getPointerCount();
        boolean fingers=true;
        for(int i=0;i<count;i++)fingers &= event.getToolType(i)==MotionEvent.TOOL_TYPE_FINGER;
        if(blocked || !fingers || app.busy() || changing) { taps.reset(); return false; }
        float[] x=new float[count],y=new float[count]; int[] ids=new int[count];
        for(int i=0;i<count;i++) { x[i]=event.getX(i); y[i]=event.getY(i); ids[i]=event.getPointerId(i); }
        int result=taps.event(event.getActionMasked(),event.getEventTime(),ids,x,y,app.dp(18),app.dp(90));
        if(result==0)return false;
        setMode(mode==0?2:mode==2?0:previousMode);
        return true;
    }
    boolean toolsVisible() { return mode==0 || mode==3; }
    boolean colorsVisible() { return mode==0 || mode==1; }
    void toggleBar(int bar) {
        setMode(bar==0 ? (mode==0?1:mode==1?0:mode==2?3:2)
                : (mode==0?3:mode==3?0:mode==2?1:2));
    }
    void applyVisibility() {
        app.toolbar.toolScroll.setVisibility(toolsVisible()?View.VISIBLE:View.GONE);
        app.toolbar.landscapeTools.setVisibility(toolsVisible()?View.VISIBLE:View.GONE);
        app.paletteFrame.setVisibility(colorsVisible()?View.VISIBLE:View.GONE);
    }
    void setMode(int next) {
        if(next<0 || next>3 || mode==next || changing || app.busy())return;
        app.edgeBars.reset();
        dismiss(); app.dismissPanels(); app.hideGradientHint();
        app.pad.suspend();
        final DrawingBook source=app.book;
        final ToneDocument page=app.pad.document;
        RectF old=Popups.bounds(app.root,app.pad);
        float scale=app.pad.viewport.scale(),x=app.pad.viewport.x,y=app.pad.viewport.y;
        previousMode=mode; mode=next; changing=true; taps.reset();
        app.pad.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob) {
                app.pad.removeOnLayoutChangeListener(this);
                RectF now=Popups.bounds(app.root,app.pad);
                app.pad.viewport.hold(scale,x+old.left-now.left,y+old.top-now.top);
                app.pad.canvasResized(); changing=false;
                app.pad.post(() -> { if(app.book==source && app.pad.document==page)pageChanged(); });
            }
        });
        applyVisibility(); app.root.requestLayout();
    }
    void pageChanged() {
        if(mode==0 || changing || app.busy() || !app.resumed || dialog!=null)return;
        if(app.book.canvasChoice()==0)showCanvasChoice();
        else if(app.book.canvasChoice()==2)expand(false);
    }
    void dismiss() { if(dialog!=null) { AlertDialog old=dialog; dialog=null; old.dismiss(); } }
    void showModes() {
        if(app.busy())return;
        dismiss(); app.pad.suspend();
        LinearLayout panel=panel();
        addChoice(panel,0,"All controls","Show tools and colors",() -> setMode(0));
        addChoice(panel,1,"Colors only","Hide the tools",() -> setMode(1));
        addChoice(panel,5,"Tools only","Hide the color bar",() -> setMode(3));
        addChoice(panel,2,"Canvas only","Two-finger double tap",() -> setMode(2));
        show("Fullscreen",panel);
    }
    void showCanvasChoice() {
        if(app.busy() || dialog!=null)return;
        app.pad.suspend();
        DrawingBook source=app.book; ToneDocument page=app.pad.document; int index=source.index();
        LinearLayout panel=panel();
        TextView note=new TextView(app); note.setText("Just this page. Other pages stay the same.");
        note.setTextColor(Color.BLACK); note.setTextSize(16); note.setPadding(app.dp(8),0,app.dp(8),app.dp(12)); panel.addView(note);
        addChoice(panel,3,"Keep canvas size","More room to pan and zoom",() -> {
            if(app.book!=source || source.index()!=index || app.pad.document!=page)return;
            source.setCanvasChoice(1); app.recovery();
        });
        addChoice(panel,4,"Expand canvas","Add blank margins. Keep artwork the same size.",() -> {
            if(app.book!=source || source.index()!=index || app.pad.document!=page)return;
            if(expand(true)) { source.setCanvasChoice(2); app.recovery(); }
        });
        show("Page "+(index+1)+" — use the extra space?",panel);
    }
    private boolean expand(boolean explain) {
        app.pad.finishStroke(); app.pad.dryWet();
        // Include all hidden margins in the page's own coordinates, in every app rotation.
        // Measure from the 100% home placement, not the current pan and zoom, so paper is
        // added only under the bars and 100% still returns the artwork to where it began.
        Matrix inverse=new Matrix();
        app.pad.homeToView().invert(inverse);
        RectF area=mode==0?app.pad.screenArea():new RectF(0,0,app.pad.getWidth(),app.pad.getHeight());
        inverse.mapRect(area);
        ToneDocument page=app.pad.document;
        int left=Math.max(0,(int)Math.ceil(-area.left)),top=Math.max(0,(int)Math.ceil(-area.top));
        int right=Math.max(0,(int)Math.ceil(area.right-page.width)),bottom=Math.max(0,(int)Math.ceil(area.bottom-page.height));
        try {
            page.expand(left,top,right,bottom);
            if(left+top+right+bottom>0) {
                // New page origin includes the added margins; keep every old mark stationary.
                // Rotation translations also change when page dimensions grow.
                float oldX=app.pad.viewport.x,oldY=app.pad.viewport.y,scale=app.pad.viewport.scale();
                int turn=app.appRotation;
                float dx=turn==1?top:turn==2?right:turn==3?bottom:left;
                float dy=turn==1?right:turn==2?bottom:turn==3?left:top;
                app.pad.viewport.hold(scale,oldX-dx*scale,oldY-dy*scale);
                app.pad.canvasResized(); app.recovery();
            } else if(explain)app.message("This page already fills the available space.");
            return true;
        } catch(IllegalArgumentException error) {
            if(explain)app.message("This page is too large to expand further. You can keep its size and pan or zoom.");
            return false;
        }
    }
    private LinearLayout panel() {
        LinearLayout panel=new LinearLayout(app); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(app.dp(12),app.dp(8),app.dp(12),app.dp(8));return panel;
    }
    private void show(String title,LinearLayout panel) {
        ScrollView scroll=new ScrollView(app); scroll.addView(panel);
        AlertDialog shown=app.showDialog(new AlertDialog.Builder(app).setTitle(title).setView(scroll).setNegativeButton("Cancel",null));
        dialog=shown;
        shown.setOnDismissListener(d -> { if(dialog==shown)dialog=null; app.pad.post(app.pad::connectDisplay); });
    }
    private void addChoice(LinearLayout panel,int picture,String title,String detail,Runnable action) {
        LinearLayout row=new LinearLayout(app); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(app.dp(8),app.dp(12),app.dp(8),app.dp(12));
        row.setBackground(Ui.outline(app.dp(1),Color.BLACK,0));
        row.addView(new ChoicePicture(picture),new LinearLayout.LayoutParams(app.dp(112),app.dp(104)));
        LinearLayout words=new LinearLayout(app); words.setOrientation(LinearLayout.VERTICAL);
        TextView heading=new TextView(app);heading.setText(title);heading.setTextSize(19);heading.setTextColor(Color.BLACK);
        heading.setTypeface(null,android.graphics.Typeface.BOLD);words.addView(heading);
        TextView caption=new TextView(app);caption.setText(detail);caption.setTextSize(15);caption.setTextColor(Color.BLACK);words.addView(caption);
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        row.setFocusable(true);row.setContentDescription(title+". "+detail);
        row.setOnClickListener(v -> { dismiss(); action.run(); });
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.bottomMargin=app.dp(8);panel.addView(row,size);
    }
    /** Large monochrome diagrams: the same ink mark, with or without extra paper around it. */
    private final class ChoicePicture extends View {
        final int kind; final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        ChoicePicture(int kind) { super(app);this.kind=kind;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) {
            c.save();c.scale(getWidth()/112f,getHeight()/104f);
            ink.setColor(Color.BLACK);ink.setStrokeWidth(2);ink.setStyle(Paint.Style.STROKE);
            c.drawRect(8,8,100,96,ink);
            if(kind<=2 || kind==5) {
                if(kind<2) { ink.setStyle(Paint.Style.FILL);c.drawRect(8,8,100,20,ink); }
                if(kind==0 || kind==5) { ink.setStyle(Paint.Style.FILL);c.drawRect(8,20,23,96,ink); }
            } else {
                if(kind==4) {
                    ink.setStrokeWidth(1);
                    for(int x=10;x<30;x+=5)c.drawLine(x,10,x,94,ink);
                    for(int y=12;y<29;y+=5)c.drawLine(30,y,98,y,ink);
                }
                ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(2);
                if(kind==3)c.drawRect(30,29,98,94,ink);
                else { ink.setPathEffect(new android.graphics.DashPathEffect(new float[]{4,4},0));c.drawRect(30,29,98,94,ink);ink.setPathEffect(null); }
            }
            ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(3);
            Path mark=new Path();mark.moveTo(45,77);mark.cubicTo(33,46,63,40,73,57);mark.cubicTo(94,77,60,89,45,77);c.drawPath(mark,ink);
            c.restore();
        }
    }
}
