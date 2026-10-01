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
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Swatch input and settings on the actual rotated UI, with the original session restored. */
final class PaletteUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {run(test,report,false);}
    static void run(Instrumentation test,StringBuilder report,boolean performanceOnly) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test,() -> !(Boolean)get(activity,"loading") && activity.hasWindowFocus(),"App ready");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");Map<String,?> originalPrefs=prefs.getAll();
        Object pad=get(activity,"pad");DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName");ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(get(activity,"paint"),"gray"),rotation=(Integer)get(activity,"appRotation");
        boolean erase=(Boolean)get(get(activity,"paint"),"eraseMode");
        AlertDialog[] dialog={null};ToneDocument doc=new ToneDocument(320,480);
        try {
            main(test,() -> {
                ((android.view.OrientationEventListener)get(get(activity,"rotationPrompt"),"orientationSensor")).disable();
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"library",new ToolLibrary());set(activity,"drawingName","");
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                prefs.edit().putString("palette_shades","0,128,192,255").putBoolean("tool_visible_PALETTE",true)
                        .putString("toolbar_order","BRUSH,AIRBRUSH,FILL,PALETTE").apply();call(get(activity,"toolbar"),"rebuildTools");
            });
            for(int quarter=0;quarter<4;quarter++) for(boolean right:new boolean[]{false,true}) {
                final int q=quarter;
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",right).apply();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},q);call(activity,"applyToolboxSide");
                    ((android.widget.ScrollView)get(get(activity,"toolbar"),"toolScroll")).scrollTo(0,0);
                    ((android.widget.HorizontalScrollView)get(get(activity,"toolbar"),"landscapeTools")).scrollTo(0,0);
                    call(activity,"setEraseMode",new Class<?>[]{boolean.class},true);
                });
                test.waitForIdleSync();SystemClock.sleep(150);
                ViewGroup block=(ViewGroup)get(get(activity,"toolbar"),"sidebarPalette");
                View swatch=((ViewGroup)block.getChildAt(1)).getChildAt(1);
                tap(test,swatch);
                main(test,() -> {
                    check((Integer)get(get(activity,"paint"),"gray")==128,"Swatch picks exact logical tone");
                    check(!(Boolean)get(get(activity,"paint"),"eraseMode"),"Swatch exits brush erasing");
                    check(((ToolLibrary)get(activity,"library")).current().tool==ToolSettings.Tool.BRUSH,"Swatch preserves brush");
                    check(doc.compositeTone(100,100)==255 && !doc.undo(),"Swatch adds neither marks nor undo");
                    check(prefs.getInt("gray",-1)==128,"Selected shade persists");
                });
                tap(test,block.getChildAt(0));
                Object editor=get(activity,"paletteEditor");check(editor!=null,"Palette icon opens editor");
                View content=(View)get(editor,"content");
                if(!performanceOnly)main(test,() -> checkAnchor(activity,editor));
                check(text(content,"Add current color")==null,"No Add current color button");
                tap(test,cell(editor,1));
                main(test,() -> check((Integer)get(get(activity,"paint"),"gray")==128,"Existing swatch moves the main color marker"));
                if(performanceOnly) {measureColorUpdates(test,activity,report);return;}
                Object stableButton=((ViewGroup)get(get(activity,"toolbar"),"toolRail")).getChildAt(0);
                int feedbackBefore=((SelectionFeedback)get(editor,"swatchFeedback")).submitted;
                chooseOnRelease(test,activity,editor,.72f);
                main(test,() -> {
                    check(get(activity,"paletteEditor")==editor,"Using the main strip keeps editor open");
                    check(shades(editor).get(1).equals(get(get(activity,"paint"),"gray")),"Main strip edits selected swatch");
                    check(shades(editor).size()==4,"Editing does not add a swatch");
                    check(((ViewGroup)get(get(activity,"toolbar"),"toolRail")).getChildAt(0)==stableButton,"Color changes retain existing toolbar views");
                    check(((SelectionFeedback)get(editor,"swatchFeedback")).submitted>feedbackBefore,"Popup swatch uses immediate e-ink feedback");
                    check(call(get(activity,"prefs"),"paletteShades").equals(shades(editor)),"Color gesture release persists edited shades");
                });
                tap(test,cell(editor,4));
                check(shades(editor).size()==4,"Selecting an empty cell does not add the current shade");
                if(q==0 && !right) {
                    check(((android.widget.TextView)get(editor,"hint")).getText().length()==0,"Empty hint is delayed");
                    SystemClock.sleep(3200);
                    main(test,() -> check(((android.widget.TextView)get(editor,"hint")).getText().toString().equals("Select a color"),"Empty hint appears after three seconds"));
                }
                chooseOnRelease(test,activity,editor,.25f);
                check(shades(editor).size()==5,"Main strip fills the empty cell");
                check(shades(editor).get(4).equals(get(get(activity,"paint"),"gray")),"Added shade exactly matches main strip");
                check(((android.widget.TextView)get(editor,"hint")).getText().length()==0,"Color selection clears hint");
                ArrayList<Integer> expected=new ArrayList<>(shades(editor));
                int moved=expected.remove(4);expected.add(1,moved);
                drag(test,cell(editor,4),cell(editor,1),true);
                check(shades(editor).equals(expected),"Drag inserts before target and shifts later swatches");
                check((Integer)get(editor,"selected")==1,"Selection follows moved swatch");
                moved=expected.remove(0);expected.add(moved);
                drag(test,cell(editor,0),cell(editor,5));
                check(shades(editor).equals(expected),"Dropping on empty cell appends without duplicating");
                tap(test,(View)get(editor,"trash"));
                check(shades(editor).equals(expected),"Tapping trash does not delete");
                expected.remove(2);drag(test,cell(editor,2),(View)get(editor,"trash"));
                check(shades(editor).equals(expected),"Drag to trash deletes and closes gaps");
                drag(test,cell(editor,0),(View)pad);
                check(shades(editor).equals(expected),"Drop outside editor cancels without deleting");
                main(test,() -> {
                    check(doc.compositeTone(100,100)==255 && !doc.undo(),"Editor and drag gestures add no marks or undo");
                    check(call(get(activity,"prefs"),"paletteShades").equals(expected),"Edited order and deletion persist");
                });
                if(q==0 && !right) {
                    while(!shades(editor).isEmpty())drag(test,cell(editor,0),(View)get(editor,"trash"));
                    check(call(get(activity,"prefs"),"paletteShades").toString().equals("[]"),"Deleting the last swatch saves an empty palette");
                    check(((java.util.List<?>)get(editor,"cells")).size()==1,"Empty palette has one add box");
                    tap(test,cell(editor,0));
                    // Same-shade input must still fill an empty box.
                    main(test,() -> {call(activity,"selectShade",new Class<?>[]{int.class},(Integer)get(get(activity,"paint"),"gray"));call(editor,"commitColors");});
                    check(shades(editor).size()==1,"Same current shade can fill an empty box");
                }
                screenshot(test,activity,"palette-editor-"+quarter+"-"+right+".png");
                tap(test,description(content,"Close palette"));
                check(get(activity,"paletteEditor")==null,"X closes editor");
                main(test,() -> {
                    prefs.edit().putString("palette_shades","0,128,192,255").apply();call(get(activity,"toolbar"),"rebuildTools");
                });
                await(test,activity::hasWindowFocus,"Canvas focus");
                screenshot(test,activity,"palette-"+quarter+"-"+right+".png");
            }
            main(test,() -> {
                ToolLibrary tools=(ToolLibrary)get(activity,"library");tools.add("Palette test favorite");
                call(get(activity,"toolbar"),"rebuildTools");String active=tools.activeId();
                ((ViewGroup)((ViewGroup)get(get(activity,"toolbar"),"sidebarPalette")).getChildAt(1)).getChildAt(0).performClick();
                check(tools.activeId().equals(active),"Swatch keeps custom preset selected");
                dialog[0]=(AlertDialog)call(activity,"appSettings");
                CheckBox visible=findCheck(dialog[0].getWindow().getDecorView(),"Palette");
                visible.performClick();check(get(get(activity,"toolbar"),"sidebarPalette")==null,"Palette can be hidden");
                visible.performClick();check(get(get(activity,"toolbar"),"sidebarPalette")!=null,"Palette can be shown with retained shades");
                dialog[0].dismiss();dialog[0]=null;
                prefs.edit().putString("palette_shades","-1,bad,256,80").apply();
                check(call(get(activity,"prefs"),"paletteShades").toString().equals("[80]"),"Invalid entries safely skipped");
            });
            report.append("PASS: pen and finger palette gestures, grid and live main color selector in all four rotations and both hands; delayed empty hint, add/edit, insert-before reorder, append, drag-only trash deletion, outside-drop cancellation, last-swatch deletion, same-shade addition, saved order, exact tones, retained tool/preset, no marks/undo, visibility, and malformed settings.\n");
        } finally {
            main(test,() -> {
                call(activity,"closePaletteEditor");
                if(dialog[0]!=null)dialog[0].dismiss();
                SharedPreferences.Editor editor=prefs.edit();
                for(String key:new String[]{"palette_shades","tool_visible_PALETTE","toolbar_order","toolbox_right"}) {
                    Object value=originalPrefs.get(key);
                    if(value instanceof String)editor.putString(key,(String)value);
                    else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else editor.remove(key);
                }
                editor.apply();set(activity,"library",library);set(activity,"drawingName",name);
                set(get(activity,"paint"),"gray",gray);set(get(activity,"paint"),"eraseMode",erase);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},rotation);call(activity,"applyToolboxSide");
                call(activity,"saveToolState");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }
    private static void chooseOnRelease(Instrumentation test,PaintActivity activity,Object editor,float fraction) throws Exception {
        View picker=(View)get(activity,"shadePicker");
        ArrayList<Integer> before=new ArrayList<>(shades(editor));
        int selected=(Integer)get(editor,"selected");View cell=cell(editor,selected);
        int originalTone=(Integer)get(cell,"tone");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");String saved=prefs.getString("palette_shades","");
        int feedback=((SelectionFeedback)get(editor,"swatchFeedback")).submitted;
        long now=SystemClock.uptimeMillis();
        for(int step=0;step<3;step++) {
            int action=step==0?MotionEvent.ACTION_DOWN:step==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP;
            float[] point={picker.getWidth()*(step==0?1-fraction:fraction),picker.getHeight()*.5f};PanelCoordinates.fromView(picker).mapPoints(point);
            MotionEvent event=inputEvent(now,action,point[0],point[1],true);
            check(test.getUiAutomation().injectInputEvent(event,true),"Color gesture injected");event.recycle();
            test.waitForIdleSync();
            if(step<2)main(test,() -> {
                check(shades(editor).equals(before) && (Integer)get(cell,"tone")==originalTone,"Swatches stay unchanged while pen is down");
                check(((SelectionFeedback)get(editor,"swatchFeedback")).submitted==feedback,"No popup presentation while selecting color");
                check(prefs.getString("palette_shades","").equals(saved),"No palette write before release");
            });
        }
        main(test,() -> check(shades(editor).get(selected).equals(get(get(activity,"paint"),"gray")),"Pen-up commits the final selected color"));
    }
    private static android.graphics.RectF boundsInRoot(PaintActivity activity,View view) throws Exception {
        android.graphics.RectF rect=new android.graphics.RectF(0,0,view.getWidth(),view.getHeight());
        PanelCoordinates.fromView(view).mapRect(rect);
        android.graphics.Matrix inverse=new android.graphics.Matrix();PanelCoordinates.fromView((View)get(activity,"root")).invert(inverse);
        inverse.mapRect(rect);return rect;
    }
    private static void checkAnchor(PaintActivity activity,Object editor) throws Exception {
        View anchor=((ViewGroup)get(get(activity,"toolbar"),"sidebarPalette")).getChildAt(0);
        android.graphics.RectF icon=boundsInRoot(activity,anchor),canvas=boundsInRoot(activity,(View)get(activity,"pad"));
        View window=((android.widget.PopupWindow)get(editor,"popup")).getContentView();
        android.graphics.RectF panel=boundsInRoot(activity,window),strip=boundsInRoot(activity,(View)get(activity,"shadePicker"));
        int gap=(Integer)call(activity,"dp",new Class<?>[]{float.class},8f);canvas.inset(gap,gap);
        check(!android.graphics.RectF.intersects(panel,strip),"Popup leaves color selector exposed");
        float expected=(Boolean)get(activity,"landscape")?Math.max(canvas.left,Math.min(icon.left,canvas.right-panel.width()))
                :Math.max(canvas.top,Math.min(icon.top,canvas.bottom-panel.height()));
        float actual=(Boolean)get(activity,"landscape")?panel.left:panel.top;
        check(Math.abs(expected-actual)<3,"Popup aligns to palette icon, not canvas corner: "+panel+" icon "+icon);
        float distance=(Boolean)get(activity,"landscape")?Math.min(Math.abs(panel.top-icon.bottom),Math.abs(icon.top-panel.bottom))
                :Math.min(Math.abs(panel.left-icon.right),Math.abs(icon.left-panel.right));
        check(distance<=gap+4,"Popup sits beside the icon");
    }
    private static void measureColorUpdates(Instrumentation test,PaintActivity activity,StringBuilder report) throws Exception {
        long[] samples=new long[17];int[] layouts={0};
        View root=(View)get(activity,"root");
        android.view.ViewTreeObserver.OnGlobalLayoutListener listener=() -> layouts[0]++;
        main(test,() -> root.getViewTreeObserver().addOnGlobalLayoutListener(listener));
        Object first=((ViewGroup)get(get(activity,"toolbar"),"toolRail")).getChildAt(1);
        for(int i=0;i<samples.length;i++) {
            final int index=i,shade=DotPattern.pickerTone(i*15);
            main(test,() -> {
                long start=System.nanoTime();call(activity,"selectShade",new Class<?>[]{int.class},shade);
                samples[index]=System.nanoTime()-start;
            });
            test.waitForIdleSync();SystemClock.sleep(30);
        }
        main(test,() -> root.getViewTreeObserver().removeOnGlobalLayoutListener(listener));
        java.util.Arrays.sort(samples);
        report.append(String.format(java.util.Locale.US,"Palette color handler median %.2f ms, worst %.2f ms; main-window layouts %d; regular toolbar button retained %s. These are CPU-side timings, not panel latency.\n",
                samples[8]/1e6,samples[16]/1e6,layouts[0],first==((ViewGroup)get(get(activity,"toolbar"),"toolRail")).getChildAt(1)));
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
    @SuppressWarnings("unchecked")
    private static ArrayList<Integer> shades(Object editor) throws Exception {return (ArrayList<Integer>)get(editor,"shades");}
    private static View cell(Object editor,int index) throws Exception {return (View)((java.util.List<?>)get(editor,"cells")).get(index);}
    private static void tap(Instrumentation test,View view) {tapAt(test,view,.5f,.5f);}
    private static void tapAt(Instrumentation test,View view,float x,float y) {
        check(view!=null,"Settings control exists");
        float[] point={view.getWidth()*x,view.getHeight()*y};PanelCoordinates.fromView(view).mapPoints(point);
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=inputEvent(now,action,point[0],point[1],view.getClass().getSimpleName().equals("ShadePicker"));
            check(test.getUiAutomation().injectInputEvent(event,true),"Settings tap injected");event.recycle();
        }
        test.waitForIdleSync();SystemClock.sleep(100);
    }
    private static void drag(Instrumentation test,View from,View to) {drag(test,from,to,false);}
    private static void drag(Instrumentation test,View from,View to,boolean stylus) {
        float[] a={from.getWidth()/2f,from.getHeight()/2f},b={to.getWidth()/2f,to.getHeight()/2f};
        PanelCoordinates.fromView(from).mapPoints(a);PanelCoordinates.fromView(to).mapPoints(b);
        long now=SystemClock.uptimeMillis();
        for(int step=0;step<=12;step++) {
            int action=step==0?MotionEvent.ACTION_DOWN:step==12?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            float f=Math.min(1,step/10f);
            MotionEvent event=inputEvent(now,action,a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f,stylus);
            check(test.getUiAutomation().injectInputEvent(event,true),"Drag injected");event.recycle();SystemClock.sleep(30);
        }
        test.waitForIdleSync();SystemClock.sleep(200);
    }
    private static MotionEvent inputEvent(long down,int action,float x,float y,boolean stylus) {
        MotionEvent.PointerProperties property=new MotionEvent.PointerProperties();property.id=0;
        property.toolType=stylus?MotionEvent.TOOL_TYPE_STYLUS:MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=.5f;coords.size=.1f;
        return MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,stylus?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
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
