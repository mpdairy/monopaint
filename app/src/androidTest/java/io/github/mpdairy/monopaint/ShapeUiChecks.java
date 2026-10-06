package io.github.mpdairy.monopaint;

import android.widget.PopupWindow;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.content.Intent;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/** Real pen gestures on a temporary book; restore and drain the original session afterward. */
final class ShapeUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception { run(test,report,false); }
    static void run(Instrumentation test,StringBuilder report,boolean perfOnly) throws Exception {
        run(test,report,perfOnly,false);
    }
    static void run(Instrumentation test,StringBuilder report,boolean perfOnly,boolean pickerOnly) throws Exception {
        run(test,report,perfOnly,pickerOnly,false);
    }
    static void run(Instrumentation test,StringBuilder report,boolean perfOnly,boolean pickerOnly,boolean nomadOnly) throws Exception {
        run(test,report,perfOnly,pickerOnly,nomadOnly,false);
    }
    static void run(Instrumentation test,StringBuilder report,boolean perfOnly,boolean pickerOnly,boolean nomadOnly,boolean pagesOnly) throws Exception {
        run(test,report,perfOnly,pickerOnly,nomadOnly,pagesOnly,false);
    }
    static void run(Instrumentation test,StringBuilder report,boolean perfOnly,boolean pickerOnly,boolean nomadOnly,boolean pagesOnly,boolean settingsOnly) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<100&&((Boolean)get(activity,"loading")||!activity.hasWindowFocus());i++)SystemClock.sleep(100);
        test.waitForIdleSync();
        Object pad=get(activity,"pad"); DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName"); ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(get(activity,"paint"),"gray"),maximum=TestAccess.maximum(activity);
        int quarter=(4-(Integer)get(activity,"appRotation"))%4;
        boolean erase=(Boolean)get(get(activity,"paint"),"eraseMode");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");
        boolean brushVisible=prefs.getBoolean("tool_visible_BRUSH",true);
        boolean right=prefs.getBoolean("toolbox_right",false),visible=prefs.getBoolean("tool_visible_SHAPES",true);
        boolean hadShapeChosen=prefs.contains("shape_chosen"),shapeChosen=prefs.getBoolean("shape_chosen",false);
        boolean nomad=prefs.getBoolean("nomad_mode",false);
        DrawingBook.Snapshot originalPages=original.snapshot();
        byte[] originalPage=DrawingBook.encode(original.current());
        PopupWindow[] dialog={null};
        try {
            main(test,() -> {
                call(pad,"finishStroke");call(pad,"dryWet");
                ((android.view.OrientationEventListener)get(get(activity,"rotationPrompt"),"orientationSensor")).disable();
                set(get(activity,"paint"),"eraseMode",false);set(activity,"drawingName","");set(activity,"library",new ToolLibrary());
                prefs.edit().putBoolean("shape_chosen",true).putBoolean("tool_visible_SHAPES",true).putBoolean("tool_visible_BRUSH",pickerOnly||brushVisible).apply();
                call(get(activity,"toolbar"),"rebuildTools");
                Map<?,?> buttons=(Map<?,?>)get(get(activity,"toolbar"),"selectionButtons");
                ((View)buttons.get("tool:SHAPES")).performClick();
                check(((ToolLibrary)get(activity,"library")).current().tool==ToolSettings.Tool.SHAPES,"Sidebar selects Shapes");
            });
            if(settingsOnly){SettingsUiChecks.run(test,activity,report);return;}
            if(pagesOnly){PageNavigationChecks.run(test,activity,report);return;}
            if(nomadOnly) {
                main(test,() -> call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(new ToneDocument(640,720))));
                NomadUiChecks.run(test,activity,report);return;
            }
            if(pickerOnly) {
                main(test,() -> call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(new ToneDocument(640,720))));
                ToolPickerChecks.run(test,activity,report);return;
            }
            if(!perfOnly) for(int rotation=0;rotation<4;rotation++) for(boolean hand:new boolean[]{false,true}) {
                final int turn=rotation;ToneDocument doc=new ToneDocument(640,720);
                main(test,() -> {
                    call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                    prefs.edit().putBoolean("toolbox_right",hand).apply();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},turn);call(activity,"applyToolboxSide");
                    set(get(activity,"paint"),"gray",70);
                });
                test.waitForIdleSync();
                main(test,() -> dialog[0]=(PopupWindow)call(activity,"showToolSettings"));
                test.waitForIdleSync();SystemClock.sleep(100);
                View decor=dialog[0].getContentView();
                tap(test,find(decor,"Rectangle shape"));tap(test,find(decor,"Filled shape"));
                main(test,() -> {
                    ToolSettings selected=((ToolLibrary)get(activity,"library")).current();
                    check(selected.shape==ToolSettings.Shape.RECTANGLE&&selected.filled,"Rotated settings touch targets");
                    check(!find(decor,"Shape outline width").isShown(),"Filled has no outline control");
                    dialog[0].dismiss();dialog[0]=null;
                });
                test.waitForIdleSync();SystemClock.sleep(100);
                long down=SystemClock.uptimeMillis();
                pen(test,pad,MotionEvent.ACTION_DOWN,down,80,80);
                pen(test,pad,MotionEvent.ACTION_MOVE,down,380,430);
                main(test,() -> {
                    call(pad,"drawPreviewFrame");
                    check(doc.opacity(300,300)==0&&!doc.canUndo(),"Held preview leaves document and undo unchanged");
                    Bitmap display=(Bitmap)get(pad,"display");
                    int expected=get(pad,"viewportBitmap")==null?DotPattern.pixel(70,300,300):android.graphics.Color.rgb(70,70,70);
                    check(display.getPixel(300,300)==expected,"Held shape appears in the display preview");
                });
                pen(test,pad,MotionEvent.ACTION_MOVE,down,200,220);
                main(test,() -> {
                    call(pad,"drawPreviewFrame");
                    check(((Bitmap)get(pad,"display")).getPixel(300,300)==android.graphics.Color.WHITE,"Shrinking removes old preview pixels");
                });
                pen(test,pad,MotionEvent.ACTION_UP,down,260,280);
                main(test,() -> {
                    check(get(pad,"shapeStroke")==null&&doc.tone(240,250)==70,"Final pen-up extent commits");
                    assertRendered(pad,doc);
                    check(doc.undo()&&doc.opacity(100,100)==0&&!doc.canUndo(),"Exactly one undo");
                    check(doc.redo()&&doc.tone(240,250)==70,"Redo shape");
                    call(pad,"renderAll");
                });
                down=SystemClock.uptimeMillis();
                pen(test,pad,MotionEvent.ACTION_DOWN,down,300,300);pen(test,pad,MotionEvent.ACTION_MOVE,down,400,400);
                main(test,() -> {
                    call(pad,"finishStroke");check(doc.opacity(350,350)==0,"Interrupted preview canceled");
                });
                pen(test,pad,MotionEvent.ACTION_UP,down,400,400);
                main(test,() -> {
                    check(doc.undo()&&!doc.canUndo(),"Interruption adds no history");call(pad,"renderAll");
                    ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.edit(tools.current().shape(ToolSettings.Shape.CIRCLE).filled(false).outlineWidth(6));
                    call(pad,"zoomBy",new Class<?>[]{float.class},2f);
                });
                test.waitForIdleSync();SystemClock.sleep(100);
                down=SystemClock.uptimeMillis();
                pen(test,pad,MotionEvent.ACTION_DOWN,down,240,280);pen(test,pad,MotionEvent.ACTION_MOVE,down,340,400);
                pen(test,pad,MotionEvent.ACTION_UP,down,340,400);
                main(test,() -> {
                    check(doc.opacity(240,280)==0&&doc.tone(394,280)==70&&doc.tone(85,280)==70
                            &&doc.tone(240,125)==70&&doc.tone(240,434)==70,"Zoomed circle uses pen-down center and Euclidean radius");
                    assertRendered(pad,doc);
                    check(doc.undo(),"Zoom shape undo");call(pad,"fitPage");
                    ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.edit(tools.current().shape(ToolSettings.Shape.LINE).filled(true).outlineWidth(1));
                    dialog[0]=(PopupWindow)call(activity,"showToolSettings");
                    check(find(dialog[0].getContentView(),"Shape outline width").isShown(),"Line always exposes width");
                    dialog[0].dismiss();dialog[0]=null;
                });
                test.waitForIdleSync();SystemClock.sleep(100);
            }
            main(test,() -> {
                call(activity,"requestQuarter",new Class<?>[]{int.class},0);
                prefs.edit().putBoolean("toolbox_right",false).apply();call(activity,"applyToolboxSide");
                ToneDocument doc=new ToneDocument(1600,2000);call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.edit(tools.current().shape(ToolSettings.Shape.RECTANGLE).filled(true));
            });
            test.waitForIdleSync();SystemClock.sleep(150);
            if(!perfOnly) {
                main(test,() -> ShapePreviewChecks.run(report));
                main(test,() -> queuedMotion(activity,pad,report));
            }
            for(ToolSettings.Shape kind:new ToolSettings.Shape[]{ToolSettings.Shape.RECTANGLE,ToolSettings.Shape.CIRCLE}) for(boolean filled:new boolean[]{false,true}) {
                main(test,() -> benchmark(activity,pad,kind,filled,report));
                test.waitForIdleSync();
            }
            continuousMotion(test,activity,pad,report);
            if(perfOnly) return;
            main(test,() -> {
                ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.edit(tools.current().shape(ToolSettings.Shape.RECTANGLE).filled(true));
            });
            long down=SystemClock.uptimeMillis();pen(test,pad,MotionEvent.ACTION_DOWN,down,100,100);
            long began=SystemClock.uptimeMillis();pen(test,pad,MotionEvent.ACTION_MOVE,down,1500,1850);
            report.append("Large filled preview input injection (not render latency): ").append(SystemClock.uptimeMillis()-began).append(" ms.\n");
            pen(test,pad,MotionEvent.ACTION_UP,down,1500,1850);
            main(test,() -> {
                ToneDocument doc=(ToneDocument)get(pad,"document");check(doc.tone(1490,1840)==70,"Full-page fill completes");
                check(doc.undo(),"Large fill undo");call(pad,"renderAll");
                long started=System.nanoTime();ShapeStroke shape=new ShapeStroke(doc,((ToolLibrary)get(activity,"library")).current(),70,100,100);
                shape.preview(1500,1850);shape.preview(1400,1750);shape.cancel();
                report.append("Two large previews + cancel CPU: ").append((System.nanoTime()-started)/1000000).append(" ms.\n");
                for(ToolSettings.Shape kind:ToolSettings.Shape.values()) {
                    int i=kind.ordinal();ToolSettings selected=ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(kind).filled(i%2==0).outlineWidth(8);
                    ShapeStroke sample=new ShapeStroke(doc,selected,70,100+i*300,250);
                    sample.preview(280+i*300,kind==ToolSettings.Shape.SQUARE || kind==ToolSettings.Shape.CIRCLE?500:600);sample.finish();
                }
                call(pad,"renderAll");((View)pad).invalidate();dialog[0]=(PopupWindow)call(activity,"showToolSettings");
            });
            test.waitForIdleSync();SystemClock.sleep(200);
            Bitmap screenshot=test.getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),"shapes-settings.png"))) { screenshot.compress(Bitmap.CompressFormat.PNG,100,out); } finally {screenshot.recycle();}
            report.append("PASS: Shapes sidebar, rotated controls in four orientations/both hands, one-color preview, shrink, final pen-up, undo/redo, interruption, zoomed circle, settings and full-page fill.\n");
        } finally {
            main(test,() -> {
                if(dialog[0]!=null)dialog[0].dismiss();
                android.app.AlertDialog overview=(android.app.AlertDialog)get(activity,"pageOverview");if(overview!=null)overview.dismiss();
                call(activity,"closePagePanel");call(activity,"setNomadMode",new Class<?>[]{boolean.class},nomad);
                android.widget.PopupWindow picker=(android.widget.PopupWindow)get(activity,"toolPicker");if(picker!=null)picker.dismiss();
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"drawingName",name);set(activity,"library",library);
                if(hadShapeChosen)prefs.edit().putBoolean("shape_chosen",shapeChosen).apply();else prefs.edit().remove("shape_chosen").apply();
                set(get(activity,"paint"),"eraseMode",erase);set(get(activity,"paint"),"gray",gray);TestAccess.setMaximum(activity,maximum);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                prefs.edit().putBoolean("toolbox_right",right).putBoolean("tool_visible_SHAPES",visible).putBoolean("tool_visible_BRUSH",brushVisible).apply();
                call(activity,"requestQuarter",new Class<?>[]{int.class},quarter);call(activity,"applyToolboxSide");call(get(activity,"toolbar"),"rebuildTools");
                call(activity,"saveToolState");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
            try(java.io.FileInputStream input=new java.io.FileInputStream(new java.io.File(DrawingStorage.library(activity),"_recovery"+DrawingFiles.EXTENSION))) {
                RecoveryCodec.Recovered recovered=RecoveryCodec.read(input);
                check(recovered.path.equals(name),"Original drawing destination restored");
                check(recovered.book.index()==originalPages.index&&recovered.book.count()==originalPages.pages.size(),"Original page restored");
                check(Arrays.equals(originalPage,DrawingBook.encode(recovered.book.current())),"Saved original page and layers unchanged");
                DrawingBook.Snapshot saved=recovered.book.snapshot();
                for(int i=0;i<saved.pages.size();i++)if(i!=saved.index)
                    check(Arrays.equals(originalPages.pages.get(i),saved.pages.get(i)),"Other saved pages unchanged");
                report.append("PASS: Restored drawing destination, every page/layer, and page ").append(saved.index+1).append(" of ").append(saved.pages.size()).append(" verified from saved recovery.\n");
            }
        }
    }
    private static void assertRendered(Object pad,ToneDocument doc)throws Exception {
        Bitmap display=(Bitmap)get(pad,"display");ViewportBitmap viewport=(ViewportBitmap)get(pad,"viewportBitmap");
        int[] row=new int[doc.width];
        for(int y=0;y<doc.height;y++) {
            display.getPixels(row,0,doc.width,0,y,doc.width,1);
            for(int x=0;x<doc.width;x++) {
                int tone=doc.compositeTone(x,y);
                int expected=viewport==null?DotPattern.pixel(tone,x,y):android.graphics.Color.rgb(tone,tone,tone);
                if(row[x]!=expected)throw new AssertionError("Sparse shape display differs from full composite at "+x+","+y);
            }
        }
        DirectEink direct=(DirectEink)get(pad,"direct");
        if(direct!=null) {
            call(pad,"present");Bitmap source=viewport==null?display:viewport.bitmap;
            // The driver's mapped plane is shared with Android; untouched regions are not
            // a framebuffer snapshot. Check the ink that this preview actually submitted.
            for(int y=7;y<source.getHeight();y+=31)for(int x=11;x<source.getWidth();x+=29) {
                int expected=((source.getPixel(x,y)>>>16)&255)/16;
                if(expected!=15 && direct.readGray(x,y)!=expected)
                    throw new AssertionError("Submitted ink differs at "+x+","+y);
            }
        }
        if(viewport!=null) {
            ViewportBitmap full=new ViewportBitmap(viewport.bitmap.getWidth(),viewport.bitmap.getHeight());
            try {
                full.update(display,(Matrix)get(pad,"pageToView"),null);
                check(full.bitmap.sameAs(viewport.bitmap),"Sparse viewport updates match full resampling");
            } finally { full.close(); }
        }
    }
    private static void benchmark(PaintActivity activity,Object pad,ToolSettings.Shape kind,boolean filled,StringBuilder report)throws Exception {
        ToolLibrary tools=(ToolLibrary)get(activity,"library");
        tools.edit(tools.current().shape(kind).filled(filled).outlineWidth(4));
        ToneDocument doc=(ToneDocument)get(pad,"document");
        check(get(pad,"direct")!=null,"Benchmark uses connected direct display");
        long down=SystemClock.uptimeMillis();
        event(pad,MotionEvent.ACTION_DOWN,down,100,100);
        event(pad,MotionEvent.ACTION_MOVE,down,1300,1600);call(pad,"drawPreviewFrame");
        long[] times=new long[16];
        for(int i=0;i<times.length;i++) {
            long start=System.nanoTime();
            event(pad,MotionEvent.ACTION_MOVE,down,1300+i*6,1600+i*5);
            call(pad,"drawPreviewFrame");call(pad,"present"); // Include complete preview and submission work.
            times[i]=System.nanoTime()-start;
        }
        java.util.Arrays.sort(times);
        report.append(filled?"Filled":"Outline").append(" ").append(kind.label).append(" input + preview + display: median ")
                .append(times[times.length/2]/1000000.0).append(" ms, max ").append(times[times.length-1]/1000000.0).append(" ms.\n");
        event(pad,MotionEvent.ACTION_UP,down,1390,1675);
        assertRendered(pad,doc);
        check(doc.undo(),"Performance stroke undo");call(pad,"renderAll");
    }
    private static void continuousMotion(Instrumentation test,PaintActivity activity,Object pad,StringBuilder report)throws Exception {
        Matrix transform=new Matrix();int[] first={0};
        main(test,() -> {
            ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.edit(tools.current().shape(ToolSettings.Shape.CIRCLE).filled(false));
            transform.set((Matrix)get(pad,"pageToView"));transform.postConcat(PanelCoordinates.fromView((View)pad));
            first[0]=(Integer)get(pad,"previewFrameCount");
        });
        long down=SystemClock.uptimeMillis(),up=down;
        for(int i=0;i<=121;i++) {
            int action=i==0?MotionEvent.ACTION_DOWN:i==121?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            float[] point=i==0?new float[]{100,100}:new float[]{400+Math.min(i,120)*6,500+Math.min(i,120)*7};transform.mapPoints(point);
            MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
            MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=point[0];coords.y=point[1];coords.pressure=.45f;
            long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_UP)up=now;
            MotionEvent event=MotionEvent.obtain(down,now,action,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
            try{check(test.getUiAutomation().injectInputEvent(event,false),"Continuous stylus event injected");}finally{event.recycle();}
            if(i<121)SystemClock.sleep(8);
        }
        test.waitForIdleSync();long tail=SystemClock.uptimeMillis()-up;
        main(test,() -> {
            check(get(pad,"shapeStroke")==null&&!(Boolean)get(pad,"previewFrameScheduled"),"Continuous pen-up fully completes");
            ToneDocument doc=(ToneDocument)get(pad,"document");assertRendered(pad,doc);
            check(doc.opacity(100,100)==0&&doc.opacity(1118,1338)>0,"Continuous circle retains its center and final radius");
            int frames=(Integer)get(pad,"previewFrameCount")-first[0];
            report.append("Continuous 125 Hz circle input: ").append(frames).append(" preview frames for 120 moves; pen-up-to-idle ").append(tail).append(" ms.\n");
            check(doc.undo(),"Continuous gesture commits once");call(pad,"renderAll");
        });
    }
    private static void queuedMotion(PaintActivity activity,Object pad,StringBuilder report)throws Exception {
        ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.edit(tools.current().shape(ToolSettings.Shape.CIRCLE).filled(false));
        long down=SystemClock.uptimeMillis();event(pad,MotionEvent.ACTION_DOWN,down,100,100);
        int first=(Integer)get(pad,"previewFrameCount");long begin=System.nanoTime();
        for(int i=0;i<120;i++)event(pad,MotionEvent.ACTION_MOVE,down,400+i*6,500+i*7);
        double millis=(System.nanoTime()-begin)/1000000.0;
        check((Integer)get(pad,"previewFrameCount")==first,"Queued motion does not render stale intermediate positions");
        call(pad,"drawPreviewFrame");check((Integer)get(pad,"previewFrameCount")==first+1,"Burst renders a single latest frame");
        event(pad,MotionEvent.ACTION_UP,down,1200,1400);
        ToneDocument doc=(ToneDocument)get(pad,"document");assertRendered(pad,doc);
        check(!(Boolean)get(pad,"previewFrameScheduled"),"Pen-up removes pending preview callbacks");
        check(doc.undo(),"Burst final shape is one undo");call(pad,"renderAll");
        report.append("PASS: 120 queued circle moves handled in ").append(millis).append(" ms; one latest preview, final pen-up exact.\n");
        down=SystemClock.uptimeMillis();event(pad,MotionEvent.ACTION_DOWN,down,100,100);
        event(pad,MotionEvent.ACTION_MOVE,down,1000,1300);event(pad,MotionEvent.ACTION_CANCEL,down,1000,1300);
        check(!(Boolean)get(pad,"previewFrameScheduled")&&get(pad,"shapeStroke")==null,"Cancel removes queued preview");
        assertRendered(pad,doc);
    }
    private static void event(Object pad,int action,long down,float x,float y)throws Exception {
        float[] point={x,y};((Matrix)get(pad,"pageToView")).mapPoints(point);
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=point[0];coords.y=point[1];coords.pressure=.45f;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{prop},
                new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
        try { ((View)pad).onTouchEvent(event); } finally { event.recycle(); }
    }
    private static void tap(Instrumentation test,View view)throws Exception {
        ToolPickerChecks.tap(test,view);
    }
    private static View find(View view,String name) {
        if(name.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),name);if(found!=null)return found;}
        return null;
    }
    static void pen(Instrumentation test,Object pad,int action,long down,float x,float y) throws Exception {
        float[] point={x,y};main(test,() -> {((Matrix)get(pad,"pageToView")).mapPoints(point);PanelCoordinates.fromView((View)pad).mapPoints(point);});
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=point[0];coords.y=point[1];coords.pressure=.45f;coords.size=.1f;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
        try{check(test.getUiAutomation().injectInputEvent(event,true),"Pen injected");}finally{event.recycle();}
    }
    private interface Work {void run() throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception{Throwable[] error={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    private static Object get(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name)throws Exception{return call(owner,name,new Class<?>[0]);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
