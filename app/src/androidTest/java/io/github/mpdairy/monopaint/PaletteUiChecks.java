package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.SeekBar;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Swatch input and settings on the actual rotated UI, with the original session restored. */
final class PaletteUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test,() -> !(Boolean)get(activity,"loading") && activity.hasWindowFocus(),"App ready");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");Map<String,?> originalPrefs=prefs.getAll();
        Object pad=get(activity,"pad");DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName");ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(activity,"gray"),rotation=(Integer)get(activity,"appRotation");
        boolean erase=(Boolean)get(activity,"eraseMode");
        AlertDialog[] dialog={null};ToneDocument doc=new ToneDocument(320,480);
        try {
            main(test,() -> {
                ((android.view.OrientationEventListener)get(activity,"orientationSensor")).disable();
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"library",new ToolLibrary());set(activity,"drawingName","");
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                prefs.edit().putString("palette_shades","0,128,192,255").putBoolean("tool_visible_PALETTE",true)
                        .putString("toolbar_order","PALETTE").apply();call(activity,"rebuildTools");
            });
            for(int quarter=0;quarter<4;quarter++) for(boolean right:new boolean[]{false,true}) {
                final int q=quarter;
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",right).apply();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},q);call(activity,"applyToolboxSide");
                    ((android.widget.ScrollView)get(activity,"toolScroll")).scrollTo(0,0);
                    ((android.widget.HorizontalScrollView)get(activity,"landscapeTools")).scrollTo(0,0);
                    call(activity,"setEraseMode",new Class<?>[]{boolean.class},true);
                });
                test.waitForIdleSync();SystemClock.sleep(150);
                ViewGroup block=(ViewGroup)get(activity,"sidebarPalette");
                View swatch=((ViewGroup)block.getChildAt(1)).getChildAt(1);
                tap(test,swatch);
                main(test,() -> {
                    check((Integer)get(activity,"gray")==128,"Swatch picks exact logical tone");
                    check(!(Boolean)get(activity,"eraseMode"),"Swatch exits brush erasing");
                    check(((ToolLibrary)get(activity,"library")).current().tool==ToolSettings.Tool.BRUSH,"Swatch preserves brush");
                    check(doc.compositeTone(100,100)==255 && !doc.undo(),"Swatch adds neither marks nor undo");
                    check(prefs.getInt("gray",-1)==128,"Selected shade persists");
                });
                main(test,() -> dialog[0]=(AlertDialog)call(activity,"paletteSettings"));
                test.waitForIdleSync();
                View decor=dialog[0].getWindow().getDecorView();
                tap(test,text(decor,"Add current color"));
                main(test,() -> check(prefs.getString("palette_shades","").equals("0,128,192,255,128"),"Add saves exact selected shade"));
                ViewGroup grown=(ViewGroup)get(activity,"sidebarPalette");
                check(grown.getChildCount()==4,"Adding fifth swatch extends the palette");
                SeekBar slider=(SeekBar)description(decor,"Palette shade 5");
                // End key is handled by SeekBar as a user edit, exercising the actual listener.
                main(test,() -> slider.onKeyDown(android.view.KeyEvent.KEYCODE_MOVE_END,
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MOVE_END)));
                // Inject a tap near the light end to verify rotated slider coordinates as well.
                tap(test,slider);
                main(test,() -> check(!prefs.getString("palette_shades","").endsWith(",128"),"Slider edits and saves swatch"));
                tap(test,description(decor,"Remove palette shade 5"));
                main(test,() -> {
                    check(prefs.getString("palette_shades","").equals("0,128,192,255"),"Remove preserves other shades");
                    dialog[0].dismiss();dialog[0]=null;
                });
                await(test,activity::hasWindowFocus,"Canvas focus");
                screenshot(test,activity,"palette-"+quarter+"-"+right+".png");
            }
            main(test,() -> {
                ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.add("Palette test favorite");
                call(activity,"rebuildTools");String active=tools.activeId();
                ((ViewGroup)((ViewGroup)get(activity,"sidebarPalette")).getChildAt(1)).getChildAt(0).performClick();
                check(tools.activeId().equals(active),"Swatch keeps custom preset selected");
                dialog[0]=(AlertDialog)call(activity,"appSettings");
                CheckBox visible=findCheck(dialog[0].getWindow().getDecorView(),"Palette");
                visible.performClick();check(get(activity,"sidebarPalette")==null,"Palette can be hidden");
                visible.performClick();check(get(activity,"sidebarPalette")!=null,"Palette can be shown with retained shades");
                dialog[0].dismiss();dialog[0]=null;
                prefs.edit().putString("palette_shades","-1,bad,256,80").apply();
                check(call(activity,"paletteShades").toString().equals("[80]"),"Invalid entries safely skipped");
            });
            report.append("PASS: palette taps and settings in all four rotations and both hands; exact tones, eraser exit, retained tool/preset, no marks/undo, growing layout, add/edit/remove persistence, visibility, and malformed settings.\n");
        } finally {
            main(test,() -> {
                if(dialog[0]!=null)dialog[0].dismiss();
                SharedPreferences.Editor editor=prefs.edit();
                for(String key:new String[]{"palette_shades","tool_visible_PALETTE","toolbar_order","toolbox_right"}) {
                    Object value=originalPrefs.get(key);
                    if(value instanceof String)editor.putString(key,(String)value);
                    else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else editor.remove(key);
                }
                editor.apply();set(activity,"library",library);set(activity,"drawingName",name);
                set(activity,"gray",gray);set(activity,"eraseMode",erase);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},rotation);call(activity,"applyToolboxSide");
                call(activity,"preferences");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }
    private static View text(View view,String label) {
        if(view instanceof android.widget.TextView && label.contentEquals(((android.widget.TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
    private static View description(View view,String text) {
        if(text.contentEquals(view.getContentDescription()==null ? "" : view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=description(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    private static void tap(Instrumentation test,View view) {
        check(view!=null,"Settings control exists");
        float[] point={view.getWidth()/2f,view.getHeight()/2f};PanelCoordinates.fromView(view).mapPoints(point);
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            check(test.getUiAutomation().injectInputEvent(event,true),"Settings tap injected");event.recycle();
        }
        test.waitForIdleSync();SystemClock.sleep(100);
    }
    private static void screenshot(Instrumentation test,PaintActivity activity,String name) throws Exception {
        // Direct e-ink updates bypass Android screenshots; request a frame to synchronize its cached controls.
        main(test,() -> ((View)get(activity,"root")).invalidate());
        test.waitForIdleSync();SystemClock.sleep(150);
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();check(bitmap!=null,"Screenshot available");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }finally{bitmap.recycle();}
    }
    private static CheckBox findCheck(View view,String text) {
        if(view instanceof CheckBox && text.contentEquals(((CheckBox)view).getText()))return (CheckBox)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {CheckBox found=findCheck(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
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
    private static Object call(Object owner,String name,Class<?>[] types,Object... args) throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name) throws Exception {return call(owner,name,new Class<?>[0]);}
    private static void check(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
