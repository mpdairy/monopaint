package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Disposable pages; always restores the user's original book, page and preferences. */
final class FullscreenChecks {
    static void run(Instrumentation test,StringBuilder report)throws Exception {
        PaintActivity app=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<150&&(app.loading || !app.hasWindowFocus());i++)SystemClock.sleep(100);
        check(!app.loading && app.hasWindowFocus(),"App ready");
        DrawingBook original=app.book;String name=app.drawingName;
        int originalIndex=original.index(),rotation=(4-app.appRotation)%4;
        boolean hadHand=app.preferences.contains("toolbox_right"),hand=app.prefs.toolboxRight(),lock=app.navigationLocked;
        byte[][] originalPages=new byte[original.count()][];
        DrawingBook.Snapshot saved=original.snapshot();
        for(int i=0;i<originalPages.length;i++)originalPages[i]=DrawingBook.encode(saved.page(i));
        try {
            main(test,() -> {app.rotationPrompt.orientationSensor.disable();app.pad.suspend();app.drawingName="";});
            for(int turn=0;turn<4;turn++)for(boolean right:new boolean[]{false,true}) {
                final int quarter=turn;
                main(test,() -> {
                    app.fullscreen.dismiss();app.fullscreen.setMode(0);
                });idle(test);
                main(test,() -> {
                    app.preferences.edit().putBoolean("toolbox_right",right).apply();
                    app.requestQuarter(quarter);app.applyToolboxSide();app.navigationLocked=true;
                });idle(test);
                main(test,() -> {
                    ToneDocument page=new ToneDocument(app.landscape?app.pad.getHeight():app.pad.getWidth(),app.landscape?app.pad.getWidth():app.pad.getHeight());
                    page.begin();page.paintTone(100,110,0);page.paintTone(160,200,87);page.finish();
                    replace(app,new DrawingBook(page));
                });idle(test);
                ToneDocument page=app.pad.document;int width=page.width,height=page.height;
                byte[] initial=DrawingBook.encode(page);float[] mark=point(app,160,200);
                chord(test,app,2);chord(test,app,2);idle(test);
                check(app.fullscreen.mode==2 && app.fullscreen.dialog!=null,"Two fingers hide both and offer page choice");
                check(app.paletteFrame.getVisibility()==View.GONE,"Color bar hidden");
                check((app.landscape?app.toolbar.landscapeTools:app.toolbar.toolScroll).getVisibility()==View.GONE,"Tools hidden");
                near(mark,point(app,160,200),"Hide preserves mark on physical screen");
                check(page.width==width && page.height==height,"Prompt does not resize before choosing");
                if(turn==0 && !right) screenshot(test,app,"fullscreen-choice.png");
                main(test,() -> click(app.fullscreen.dialog.getWindow().getDecorView(),"Keep canvas size."));idle(test);
                check(app.book.canvasChoice()==1,"Keep remembered on page");
                main(test,() -> app.fullscreen.setMode(1));idle(test);
                chord(test,app,2);chord(test,app,2);idle(test);
                check(app.fullscreen.mode==2,"Mixed layout undoes the last bar action");
                chord(test,app,2);chord(test,app,2);idle(test);
                check(app.fullscreen.mode==0,"Two fingers restore both controls");near(mark,point(app,160,200),"Return preserves mark");
                for(int bars:new int[]{3,0}) {
                    main(test,() -> app.fullscreen.setMode(bars));idle(test);
                    actualSize(test,app);near(mark,point(app,160,200),"100% returns a kept page to where it started, mode "+bars);
                }
                chord(test,app,2);chord(test,app,2);idle(test);
                check(app.fullscreen.mode==2 && app.fullscreen.dialog==null,"Two fingers hide both without repeat prompt");
                check(app.paletteFrame.getVisibility()==View.GONE,"Color bar hidden");
                check(Arrays.equals(initial,DrawingBook.encode(page)),"Keep never changes artwork or dimensions");
                // Expanding while zoomed out and panned must still add paper only under the bars.
                main(test,() -> {app.pad.viewport.hold(.65f,40,30);app.pad.canvasResized();});idle(test);
                float[] zoomed=point(app,160,200);
                main(test,() -> app.fullscreen.showCanvasChoice());idle(test);
                main(test,() -> click(app.fullscreen.dialog.getWindow().getDecorView(),"Expand canvas."));idle(test);
                int[] grown=findMark(page,87);near(zoomed,point(app,grown[0],grown[1]),"Expand keeps zoomed artwork stationary");
                check(Math.min(page.width,page.height)==Math.min(app.pad.getWidth(),app.pad.getHeight())
                        && Math.max(page.width,page.height)==Math.max(app.pad.getWidth(),app.pad.getHeight()),
                        "Zoomed-out expansion exactly fills the screen "+page.width+"x"+page.height);
                check(app.book.canvasChoice()==2,"Expand remembered on page");
                check(page.width>=width && page.height>=height && (page.width>width || page.height>height),"Page expanded");
                // Locate the known opaque mark after adding the margins, and compare its physical position.
                int[] moved=grown;
                check(page.canUndo(),"Expansion is undoable");
                int expandedWidth=page.width,expandedHeight=page.height;
                main(test,() -> {
                    app.fullscreen.setMode(0);
                });idle(test);
                check(page.width==expandedWidth && page.height==expandedHeight,"Showing controls never crops page");
                actualSize(test,app);
                moved=findMark(page,87);near(mark,point(app,moved[0],moved[1]),"100% keeps an expanded page's artwork where it started, under the controls");
                main(test,() -> {
                    // Invoke the real Undo and Redo buttons in the header.
                    app.menuControls.getChildAt(app.menuControls.indexOfChild(app.menuButton)==0?1:app.menuControls.getChildCount()-2).performClick();
                });idle(test);
                check(page.width==width && page.height==height && Arrays.equals(initial,DrawingBook.encode(page)),"UI Undo restores original canvas and pixels");
                main(test,() -> app.menuControls.getChildAt(app.menuControls.indexOfChild(app.menuButton)==0?2:app.menuControls.getChildCount()-3).performClick());idle(test);
                check(page.width==expandedWidth && page.height==expandedHeight,"UI Redo restores expansion");
                main(test,() -> {
                    app.book.addPage();app.pad.showPage(app.book.current());app.updatePages();app.fullscreen.setMode(1);
                });idle(test);
                check(app.fullscreen.dialog!=null && app.book.canvasChoice()==0,"Next page gets its own prompt");
                main(test,() -> click(app.fullscreen.dialog.getWindow().getDecorView(),"Keep canvas size."));idle(test);
                main(test,app::onBackPressed);idle(test);check(app.fullscreen.mode==0,"Back restores controls");
                report.append("PASS fullscreen rotation ").append(turn).append(" hand ").append(right).append("\n");
            }
            // A separately opened drawing in colors-only mode also owns its first-use choice.
            main(test,() -> app.fullscreen.setMode(1));idle(test);
            main(test,() -> replace(app,new DrawingBook(new ToneDocument(320,480))));idle(test);
            check(app.fullscreen.dialog!=null && app.book.canvasChoice()==0,"Opening another book while hidden offers its own choice");
            main(test,() -> click(app.fullscreen.dialog.getWindow().getDecorView(),"Keep canvas size."));idle(test);
            report.append("PASS: dispatched finger double taps, zoom-lock independence, illustrated per-page choices, keep/expand, stationary artwork, UI undo/redo, separate new pages and Back escape.\n");
        } finally {
            main(test,() -> {app.fullscreen.dismiss();app.fullscreen.setMode(0);});idle(test);
            main(test,() -> {
                app.pad.suspend();replace(app,original);app.drawingName=name;app.navigationLocked=lock;
                if(hadHand)app.preferences.edit().putBoolean("toolbox_right",hand).apply();else app.preferences.edit().remove("toolbox_right").apply();
                app.requestQuarter(rotation);app.applyToolboxSide();app.saveToolState();app.recovery();
            });idle(test);TestSessionSave.await(app);
            check(app.book==original && original.index()==originalIndex && name.equals(app.drawingName),"Original book and page restored");
            DrawingBook.Snapshot after=original.snapshot();
            for(int i=0;i<originalPages.length;i++)check(Arrays.equals(originalPages[i],DrawingBook.encode(after.page(i))),"Original page "+i+" and all layers retained");
            report.append("RESTORED original page ").append(originalIndex+1).append(" of ").append(original.count()).append("; all pages/layers verified.\n");
        }
    }
    /** Pan and zoom away first, so 100% must choose the position itself. */
    private static void actualSize(Instrumentation test,PaintActivity app)throws Exception {
        main(test,() -> {app.pad.viewport.hold(2.5f,-120,-160);app.pad.canvasResized();});idle(test);
        main(test,app.pad::actualSize);idle(test);
    }
    private static int[] findMark(ToneDocument page,int shade) {
        for(int y=0;y<page.height;y++)for(int x=0;x<page.width;x++)if(page.compositeTone(x,y)==shade)return new int[]{x,y};
        throw new AssertionError("Mark missing");
    }
    private static float[] point(PaintActivity app,int x,int y) {
        float[] p={x,y};app.pad.pageToView.mapPoints(p);PanelCoordinates.fromView(app.pad).mapPoints(p);return p;
    }
    private static void near(float[] a,float[] b,String message){check(Math.abs(a[0]-b[0])<2 && Math.abs(a[1]-b[1])<2,message+" "+Arrays.toString(a)+" vs "+Arrays.toString(b));}
    static void chord(Instrumentation test,PaintActivity app,int count)throws Exception {
        long down=SystemClock.uptimeMillis();
        main(test,() -> event(app,down,MotionEvent.ACTION_DOWN,1));
        for(int i=2;i<=count;i++){final int n=i;main(test,() -> event(app,down,MotionEvent.ACTION_POINTER_DOWN|((n-1)<<8),n));}
        for(int i=count;i>1;i--){final int n=i;main(test,() -> event(app,down,MotionEvent.ACTION_POINTER_UP|((n-1)<<8),n));}
        main(test,() -> event(app,down,MotionEvent.ACTION_UP,1));SystemClock.sleep(70);
    }
    private static void event(PaintActivity app,long down,int action,int count) {
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] points=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++) {
            props[i]=new MotionEvent.PointerProperties();props[i].id=i+5;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            points[i]=new MotionEvent.PointerCoords();points[i].x=app.pad.getWidth()/2f+(i-1)*app.dp(50);points[i].y=app.pad.getHeight()/2f;points[i].pressure=1;
        }
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,points,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try {app.pad.dispatchTouchEvent(e);}finally{e.recycle();}
    }
    static void click(View v,String prefix) {
        View found=find(v,prefix);check(found!=null,"Choice visible: "+prefix);found.performClick();
    }
    private static View find(View v,String prefix) {
        if(v.getContentDescription()!=null && v.getContentDescription().toString().startsWith(prefix))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),prefix);if(found!=null)return found;}return null;
    }
    private static void screenshot(Instrumentation test,PaintActivity app,String name)throws Exception {
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private static void replace(PaintActivity app,DrawingBook book)throws Exception {Method m=PaintActivity.class.getDeclaredMethod("replaceBook",DrawingBook.class);m.setAccessible(true);m.invoke(app,book);}
    private static void idle(Instrumentation test){test.waitForIdleSync();SystemClock.sleep(220);}
    interface Work {void run()throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception {
        Throwable[] error={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
