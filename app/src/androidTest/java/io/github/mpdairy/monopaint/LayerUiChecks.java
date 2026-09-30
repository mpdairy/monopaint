package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.lang.reflect.*;

/** Layer controls on a temporary book; always restore the original session. */
final class LayerUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test,() -> !(Boolean)get(activity,"loading")&&get(activity,"book")!=null,"Recovery loaded");
        Object pad=get(activity,"pad"); DrawingBook original=(DrawingBook)get(activity,"book");
        String originalName=(String)get(activity,"drawingName");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences"); boolean right=prefs.getBoolean("toolbox_right",false);
        ToneDocument doc=new ToneDocument(original.width,original.height);
        try {
            main(test,() -> {
                ((android.view.OrientationEventListener)get(activity,"orientationSensor")).disable();
                set(activity,"drawingName",""); call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                doc.begin(); doc.paintTone(200,200,20); doc.finish(); call(pad,"renderAll");
            });
            open(test,activity); tap(test,find(popup(activity).getContentView(),"Add layer"));
            await(test,() -> doc.layerCount()==2,"Add layer responds");
            tap(test,find(popup(activity).getContentView(),"Done"));
            await(test,() -> get(pad,"direct")!=null,"Fast display reconnects");
            main(test,() -> {
                PressureStroke stroke=new PressureStroke(doc,ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(32),180);
                stroke.sample(200,200,.45f); stroke.finish();
                check(doc.compositeTone(200,200)==180,"Upper stroke covers lower pigment");
                PressureStroke erase=new PressureStroke(doc,ToolSettings.defaults(ToolSettings.Tool.ERASER).softness(0).size(16),255);
                erase.sample(200,200,.45f); erase.finish();
                check(doc.compositeTone(200,200)==20,"Hard eraser reveals lower pigment");
                doc.undo(); call(pad,"renderAll"); ((View)pad).invalidate(); call(activity,"recovery");
            });
            open(test,activity); tap(test,description(popup(activity).getContentView(),"Show Layer 2"));
            await(test,() -> !doc.layerVisible(1)&&doc.compositeTone(200,200)==20,"Visibility toggle");
            tap(test,description(popup(activity).getContentView(),"Show Layer 2"));
            await(test,() -> doc.layerVisible(1),"Visibility restored");
            tap(test,find(popup(activity).getContentView(),"Move down"));
            await(test,() -> doc.activeLayer()==0&&doc.compositeTone(200,200)==20,"Reorder updates composition");
            tap(test,find(popup(activity).getContentView(),"Move up"));
            tap(test,find(popup(activity).getContentView(),"Layer 1"));
            await(test,() -> doc.activeLayer()==0&&get(activity,"layersPopup")==null,"Selecting a layer dismisses panel");
            for(int quarter:new int[]{0,1,2,3}) for(boolean side:new boolean[]{false,true}) {
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",side).apply();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},quarter); call(activity,"applyToolboxSide");
                });
                test.waitForIdleSync(); open(test,activity);
                PopupWindow popup=popup(activity);
                main(test,() -> {
                    View root=(View)get(activity,"root"); View content=((ViewGroup)popup.getContentView()).getChildAt(0);
                    android.graphics.RectF bounds=new android.graphics.RectF(0,0,content.getWidth(),content.getHeight());
                    PanelCoordinates.fromView(content).mapRect(bounds); Matrix inverse=new Matrix();PanelCoordinates.fromView(root).invert(inverse); inverse.mapRect(bounds);
                    check(bounds.left>=-1&&bounds.top>=-1&&bounds.right<=root.getWidth()+1&&bounds.bottom<=root.getHeight()+1,"Layer panel fits rotated screen");
                });
                screenshot(test,activity,"layers-"+quarter+"-"+side+".png");
                tap(test,find(popup.getContentView(),"Layer 2"));
                await(test,() -> doc.activeLayer()==1&&get(activity,"layersPopup")==null,"Rotated layer selection hit target");
                await(test,() -> get(pad,"direct")!=null,"Rotated fast display reconnects");
            }
            main(test,() -> {
                while(doc.layerCount()<ToneDocument.MAX_LAYERS) doc.addLayer();
                call(activity,"recovery");
                PressureStroke stroke=new PressureStroke(doc,32,0); stroke.sample(300,300,.45f);stroke.finish();
                call(activity,"recovery"); call(pad,"renderAll");
            });
            // Let asynchronous compression run while all eight full-size layers and the display stay resident.
            SystemClock.sleep(2000);
            main(test,() -> check(get(activity,"saveError")==null,"Eight-layer autosave succeeds"));
            report.append("PASS: layer add/select/show/hide/reorder, opaque brush and transparent eraser, all four orientations and both hands, display reconnection, eight full-resolution layers and autosave.\n");
        } finally {
            main(test,() -> {
                PopupWindow popup=popup(activity); if(popup!=null) popup.dismiss();
                set(activity,"drawingName",originalName);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                prefs.edit().putBoolean("toolbox_right",right).apply();
                call(activity,"requestQuarter",new Class<?>[]{int.class},0);call(activity,"applyToolboxSide"); call(activity,"recovery");
            });
        }
    }
    private static void open(Instrumentation test,PaintActivity activity) throws Exception {
        main(test,() -> ((View)get(activity,"layersButton")).performClick());
        await(test,() -> popup(activity)!=null&&popup(activity).isShowing(),"Layers opened");test.waitForIdleSync();SystemClock.sleep(100);
    }
    private static PopupWindow popup(PaintActivity activity) throws Exception {return (PopupWindow)get(activity,"layersPopup");}
    private static View find(View view,String text) {
        if(view instanceof TextView&&(((TextView)view).getText().toString().equals(text)||((TextView)view).getText().toString().equals(text+"  ✓"))) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static View description(View view,String text) {
        if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {View found=description(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static void tap(Instrumentation test,View view) {
        check(view!=null,"Layer control exists");float[] point={view.getWidth()/2f,view.getHeight()/2f};PanelCoordinates.fromView(view).mapPoints(point);
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{0,1}) {
            android.view.MotionEvent e=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);check(test.getUiAutomation().injectInputEvent(e,true),"Layer tap injected");e.recycle();
        }
        test.waitForIdleSync();SystemClock.sleep(100);
    }
    private static void screenshot(Instrumentation test,PaintActivity activity,String name) throws Exception {
        test.waitForIdleSync();SystemClock.sleep(200);Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),name))) {bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private interface Work {void run() throws Exception;}
    private interface Condition {boolean ok() throws Exception;}
    private static void main(Instrumentation test,Work work) throws Exception {
        Throwable[] failure={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new Exception(failure[0]);
    }
    private static void await(Instrumentation test,Condition condition,String message) throws Exception {
        for(int i=0;i<100;i++){boolean[] ready={false};main(test,() -> ready[0]=condition.ok());if(ready[0])return;SystemClock.sleep(100);}
        throw new IllegalStateException(message+" timed out");
    }
    private static Object get(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static void call(Object owner,String name) throws Exception {call(owner,name,new Class<?>[0]);}
    private static void call(Object owner,String name,Class<?>[] types,Object... args) throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(owner,args);}
    private static void check(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
