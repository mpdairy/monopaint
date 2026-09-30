package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.app.Instrumentation;
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

/** Exercises timed pen input and real controls on a temporary page, then restores the session. */
final class AirbrushUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<100&&(Boolean)get(activity,"loading");i++)SystemClock.sleep(100);
        test.waitForIdleSync();
        Object pad=get(activity,"pad");DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName");ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(activity,"gray"),maximum=(Integer)get(activity,"maximum"),rotation=(Integer)get(activity,"appRotation");
        ToneDocument doc=new ToneDocument(320,480);AlertDialog[] dialog={null};
        boolean originalErase=(Boolean)get(activity,"eraseMode");
        try {
            main(test,() -> {
                set(activity,"eraseMode",false);
                ((android.view.OrientationEventListener)get(activity,"orientationSensor")).disable();
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"drawingName","");set(activity,"library",new ToolLibrary());
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                call(activity,"rebuildTools");
                Map<?,?> buttons=(Map<?,?>)get(activity,"selectionButtons");
                check(buttons.containsKey("tool:ERASER"),"Standalone eraser remains available");
                ((View)buttons.get("tool:AIRBRUSH")).performClick();
                check(((ToolLibrary)get(activity,"library")).current().tool==ToolSettings.Tool.AIRBRUSH,"Toolbar selects airbrush");
                dialog[0]=(AlertDialog)call(activity,"settings");
                View content=dialog[0].getWindow().getDecorView();
                ((SeekBar)find(content,"Airbrush diameter")).setProgress(62);
                ((SeekBar)find(content,"Flow")).setProgress(55);
                ToolSettings settings=((ToolLibrary)get(activity,"library")).current();
                check(settings.maximum==64&&settings.strength==55,"Size and flow controls edit settings");
                check(find(content,"Minimum diameter")==null,"Airbrush has a single fixed size");
                dialog[0].dismiss();dialog[0]=null;set(activity,"gray",0);
            });
            test.waitForIdleSync();SystemClock.sleep(150);
            for(int quarter=0;quarter<4;quarter++) {
                final int turn=quarter;
                main(test,() -> call(activity,"requestQuarter",new Class<?>[]{int.class},turn));
                test.waitForIdleSync();SystemClock.sleep(150);
                long down=SystemClock.uptimeMillis();
                pen(test,pad,MotionEvent.ACTION_DOWN,down,160,240);
                int[] first={0};main(test,() -> first[0]=doc.tone(160,240));
                SystemClock.sleep(250);
                main(test,() -> check(doc.tone(160,240)<first[0],"Holding pen sprays without move events in rotation "+turn));
                pen(test,pad,MotionEvent.ACTION_UP,down,160,240);
                byte[][] stopped={null};main(test,() -> {check(get(pad,"stroke")==null,"Pen-up ends airbrush");stopped[0]=doc.snapshot();});
                SystemClock.sleep(100);
                main(test,() -> {
                    check(Arrays.equals(stopped[0],doc.snapshot()),"No spray after pen-up");
                    check(doc.undo()&&doc.opacity(160,240)==0,"One undo removes held spray");
                    call(pad,"renderAll");
                    fastReplay(pad,doc);
                });
            }
            long down=SystemClock.uptimeMillis();pen(test,pad,MotionEvent.ACTION_DOWN,down,160,240);
            main(test,() -> activity.onWindowFocusChanged(false));
            byte[][] stopped={null};main(test,() -> stopped[0]=doc.snapshot());SystemClock.sleep(100);
            main(test,() -> {
                check(get(pad,"stroke")==null&&Arrays.equals(stopped[0],doc.snapshot()),"Focus loss stops timed spray");
                activity.onWindowFocusChanged(true);
            });
            pen(test,pad,MotionEvent.ACTION_UP,down,160,240);
            report.append("PASS: Airbrush toolbar, retained eraser, diameter/flow settings, timed holds in four rotations, batched fast sweeps with timer interleaving, final pen-up motion, pen-up/focus cancellation and one-step undo.\n");
        } finally {
            main(test,() -> {
                if(dialog[0]!=null)dialog[0].dismiss();call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"drawingName",name);set(activity,"library",library);set(activity,"eraseMode",originalErase);set(activity,"gray",gray);set(activity,"maximum",maximum);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},rotation);call(activity,"rebuildTools");
                call(activity,"preferences");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }
    private static void fastReplay(Object pad,ToneDocument doc) throws Exception {
        long down=SystemClock.uptimeMillis()-32;
        MotionEvent event=event(pad,MotionEvent.ACTION_DOWN,down,down,32,240.5f,.45f);
        ((View)pad).onTouchEvent(event);event.recycle();
        // A timer may run before Android delivers older samples in a batch.
        ((Runnable)get(pad,"sprayStep")).run();
        event=event(pad,MotionEvent.ACTION_MOVE,down,down+8,96,240.5f,.45f);
        event.addBatch(down+16,new MotionEvent.PointerCoords[]{coords(pad,160,240.5f,.45f)},0);
        event.addBatch(down+24,new MotionEvent.PointerCoords[]{coords(pad,224,240.5f,.45f)},0);
        ((View)pad).onTouchEvent(event);event.recycle();
        check(doc.tone(190,240)<255,"Batched motion is painted before returning from the input event");
        event=event(pad,MotionEvent.ACTION_UP,down,down+32,288,240.5f,0);
        ((View)pad).onTouchEvent(event);event.recycle();
        int low=255,high=0;
        for(int x=70;x<250;x++){int tone=doc.tone(x,240);low=Math.min(low,tone);high=Math.max(high,tone);}
        check(high<255&&high-low<=1,"Batched fast pen input paints a uniform stripe without blobs");
        check(doc.tone(275,240)<255,"Final pen-up segment is retained");
        check(get(pad,"stroke")==null&&doc.undo(),"Immediate sweep ends as one undo action");
        check(doc.opacity(160,240)==0,"Fast sweep undo restores transparent paper");call(pad,"renderAll");
    }
    private static MotionEvent event(Object pad,int action,long down,long time,float x,float y,float pressure) throws Exception {
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        return MotionEvent.obtain(down,time,action,1,new MotionEvent.PointerProperties[]{prop},
                new MotionEvent.PointerCoords[]{coords(pad,x,y,pressure)},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
    }
    private static MotionEvent.PointerCoords coords(Object pad,float x,float y,float pressure) throws Exception {
        float[] point={x,y};((Matrix)get(pad,"pageToView")).mapPoints(point);
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=point[0];coords.y=point[1];coords.pressure=pressure;coords.size=.1f;return coords;
    }
    private static View find(View view,String name) {
        if(name.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),name);if(found!=null)return found;}
        return null;
    }
    private static void pen(Instrumentation test,Object pad,int action,long down,float x,float y) throws Exception {
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
