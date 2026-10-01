package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/** Real toolbar controls and pen input, on a temporary book with session restoration. */
final class ToolbarChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test,() -> !(Boolean)get(activity,"loading") && activity.hasWindowFocus(),"App ready");
        check(((SelectionFeedback)get(activity,"selectionFeedback")).enabled,"Instant selection is enabled on launch");
        Object pad=get(activity,"pad");
        DrawingBook original=(DrawingBook)get(activity,"book");
        String originalName=(String)get(activity,"drawingName");
        ToolLibrary originalTools=(ToolLibrary)get(activity,"library");
        int originalGray=(Integer)get(get(activity,"paint"),"gray"), originalMaximum=TestAccess.maximum(activity);
        int originalRotation=(Integer)get(activity,"appRotation");
        boolean originalWet=(Boolean)get(get(activity,"paint"),"wetCanvas"), originalTransparent=(Boolean)get(get(activity,"paint"),"transparentPaint");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");
        Map<String,?> originalPrefs=prefs.getAll();
        ToneDocument doc=new ToneDocument(320,480);
        AlertDialog[] dialog={null};
        boolean originalErase=(Boolean)get(get(activity,"paint"),"eraseMode");
        try {
            main(test,() -> {
                set(get(activity,"paint"),"eraseMode",false);
                ((android.view.OrientationEventListener)get(get(activity,"rotationPrompt"),"orientationSensor")).disable();
                call(pad,"finishStroke");call(pad,"dryWet");
                set(get(activity,"paint"),"wetCanvas",false);set(get(activity,"paint"),"transparentPaint",false);call(activity,"refreshPaintModes");
                for(ToolSettings.Tool tool:ToolSettings.Tool.values()) prefs.edit().remove("tool_visible_"+tool.name()).apply();
                prefs.edit().remove("tool_visible_LAYERS").remove("tool_visible_ZOOM").remove("toolbar_order")
                        .remove("large_toolbar_icons").apply();
                set(activity,"library",new ToolLibrary());set(activity,"drawingName","");
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                doc.begin();
                for(int y=100;y<120;y++) for(int x=100;x<120;x++) doc.paintTone(x,y,40);
                doc.finish();doc.addLayer();doc.begin();
                for(int y=100;y<120;y++) for(int x=100;x<120;x++) {doc.paintTone(x,y,180);doc.eraseTone(x,y,.5f);}
                doc.finish();call(pad,"renderAll");
                ToolLibrary library=(ToolLibrary)get(activity,"library");
                library.select(ToolSettings.Tool.PENCIL);library.edit(library.current().size(37));
                call(get(activity,"toolbar"),"rebuildTools");
                dialog[0]=(AlertDialog)call(activity,"appSettings");
            });
            test.waitForIdleSync();
            main(test,() -> {
                View settings=dialog[0].getWindow().getDecorView();
                check((Boolean)call(activity,"supportsNomadSimulation") && findCheck(settings,"Nomad Simulation Mode")!=null,
                        "Connected Manta exposes simulation despite its misleading Nomad model name");
                check(findCheck(settings,"Instant selection dots (experimental)")==null,"No optional instant selection setting");
                check(findCheck(settings,"Blending stump")!=null && findCheck(settings,"Soften")==null,"Blending stump has its proper name");
                for(String name:new String[]{"Brush","Pencil","Airbrush","Fill","Eraser","Blending stump","Layers","Zoom","Palette"})
                    check(findCheck(settings,name).getCompoundDrawables()[0]!=null,"Settings shows the actual icon for "+name);
                check(!description(settings,"Move Brush up").isEnabled(),"First item cannot move up");
                check(!description(settings,"Move Palette down").isEnabled(),"Last item cannot move down");
                int zoomIndex=((java.util.List<?>)call(get(activity,"toolbar"),"toolbarOrder")).indexOf("ZOOM");
                description(settings,"Move Zoom up").performClick();
                check(((java.util.List<?>)call(get(activity,"toolbar"),"toolbarOrder")).get(zoomIndex-1).equals("ZOOM"),"Zoom can move in saved order");
                check(((ViewGroup)get(get(activity,"toolbar"),"toolRail")).getChildAt(zoomIndex-1)==get(get(activity,"toolbar"),"zoomButton"),"Zoom uses chosen position");
                description(settings,"Move Zoom down").performClick();
                CheckBox zoom=findCheck(settings,"Zoom");zoom.performClick();
                check(get(get(activity,"toolbar"),"zoomButton")==null && !prefs.getBoolean("tool_visible_ZOOM",true),"Zoom can be hidden and saves");
                call(get(activity,"toolbar"),"refreshZoom");
                zoom.performClick();
                check(get(get(activity,"toolbar"),"zoomButton")!=null,"Zoom can return to toolbar");
                int layerIndex=((java.util.List<?>)call(get(activity,"toolbar"),"toolbarOrder")).indexOf("LAYERS");
                description(settings,"Move Layers up").performClick();
                description(settings,"Move Layers down").performClick();
                check(((java.util.List<?>)call(get(activity,"toolbar"),"toolbarOrder")).get(layerIndex).equals("LAYERS"),"Down control reverses an upward move");
                for(int i=0;i<layerIndex;i++)description(settings,"Move Layers up").performClick();
                check(prefs.getString("toolbar_order","").startsWith("LAYERS,"),"Toolbar order persists");
                ViewGroup rail=(ViewGroup)get(get(activity,"toolbar"),"toolRail");
                check(rail.getChildAt(0)==get(get(activity,"toolbar"),"layersButton"),"Toolbar uses chosen layer position");
                CheckBox layers=findCheck(settings,"Layers");layers.performClick();
                check(get(get(activity,"toolbar"),"layersButton")==null && !prefs.getBoolean("tool_visible_LAYERS",true),"Layers can be hidden and preference saves");
                layers.performClick();
                check(rail.getChildAt(0)==get(get(activity,"toolbar"),"layersButton"),"Layers returns to chosen position");
                CheckBox pencil=findCheck(dialog[0].getWindow().getDecorView(),"Pencil");
                check(pencil!=null && pencil.isChecked(),"Pencil visible by default");pencil.performClick();
                ToolLibrary library=(ToolLibrary)get(activity,"library");
                check(!buttons(activity).containsKey("tool:PENCIL"),"Hiding removes the tool button");
                check(library.current().tool==ToolSettings.Tool.BRUSH,"Hiding selected tool selects a visible tool");
                check(library.builtin(ToolSettings.Tool.PENCIL).maximum==37,"Hidden settings preserved");
                check(!prefs.getBoolean("tool_visible_PENCIL",true),"Visibility saved");
                pencil.performClick();
                check(buttons(activity).containsKey("tool:PENCIL"),"Tool can be re-enabled");
                library.select(ToolSettings.Tool.PENCIL);String id=library.add("Test pencil").id;
                call(get(activity,"toolbar"),"rebuildTools");pencil.performClick();
                check(buttons(activity).containsKey(id) && library.activeId().equals(id),"Hiding base tool preserves selected favorite");
                for(String name:new String[]{"Airbrush","Fill","Shapes","Eraser","Blending stump"}) findCheck(dialog[0].getWindow().getDecorView(),name).performClick();
                CheckBox brush=findCheck(dialog[0].getWindow().getDecorView(),"Brush");brush.performClick();
                check(brush.isChecked() && buttons(activity).containsKey("tool:BRUSH"),"Cannot hide last regular tool");
                call(activity,"saveToolState");
                ToolLibrary decoded=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                check(decoded.activeId().equals(id) && decoded.builtin(ToolSettings.Tool.PENCIL).maximum==37,"Saved favorite and hidden settings survive decoding");
                dialog[0].dismiss();dialog[0]=null;
            });
            await(test,activity::hasWindowFocus,"Canvas focus");
            byte[] before=encoded(doc);
            String preset=((ToolLibrary)get(activity,"library")).activeId();
            int expected=doc.compositeTone(110,110);
            check(expected>40 && expected<180,"Fixture includes partial transparency");
            for(int quarter:new int[]{0,1,2,3}) for(boolean right:new boolean[]{false,true}) {
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",right).apply();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},quarter);call(activity,"applyToolboxSide");
                    set(get(activity,"paint"),"gray",0);
                });
                test.waitForIdleSync();SystemClock.sleep(150);
                main(test,() -> {
                    check(((ViewGroup)get(get(activity,"toolbar"),"toolRail")).getChildAt(0)==get(get(activity,"toolbar"),"layersButton"),"Toolbar order survives orientation and hand changes");
                    dialog[0]=(AlertDialog)call(activity,"appSettings");
                });
                test.waitForIdleSync();
                for(boolean large:new boolean[]{true,false}) {
                    tap(test,findRadio(dialog[0].getWindow().getDecorView(),large?"Large":"Medium"));
                    main(test,() -> {
                        check(prefs.getBoolean("large_toolbar_icons",true)==large,"Icon size persists");
                        for(Object button:buttons(activity).values())
                            check((Integer)get(button,"iconHalf")== (large?18:14),"Size applies to regular and favorite icons");
                        check((Integer)get(get(get(activity,"toolbar"),"layersButton"),"iconHalf")== (large?18:14),"Layers icon follows size");
                        check((Integer)get(get(get(activity,"toolbar"),"zoomButton"),"iconHalf")== (large?16:12),"Zoom icon follows size");
                        check((Integer)get(get(activity,"eyedropperButton"),"iconHalf")==14,"Header icon size stays unchanged");
                    });
                    if(large)screenshot(test,activity,"toolbar-large-"+quarter+"-"+right+".png");
                }
                // Exercise the move targets through the rotated dialog, not only performClick.
                tap(test,description(dialog[0].getWindow().getDecorView(),"Move Layers down"));
                tap(test,description(dialog[0].getWindow().getDecorView(),"Move Layers up"));
                main(test,() -> check(((java.util.List<?>)call(get(activity,"toolbar"),"toolbarOrder")).get(0).equals("LAYERS"),"Rotated Settings move targets respond"));
                screenshot(test,activity,"toolbar-settings-"+quarter+"-"+right+".png");
                main(test,() -> {dialog[0].dismiss();dialog[0]=null;});
                await(test,activity::hasWindowFocus,"Canvas focus after Settings");
                main(test,() -> markerChecks(activity));
                main(test,() -> ((View)get(activity,"eyedropperButton")).performClick());
                screenshot(test,activity,"eyedropper-"+quarter+"-"+right+".png");
                pen(test,pad,110,110);
                main(test,() -> {
                    check((Integer)get(get(activity,"paint"),"gray")==expected,"Eyedropper samples logical composite in rotation "+quarter+", hand "+right);
                    check(!(Boolean)get(activity,"pickingShade"),"Eyedropper returns after one sample");
                    check(((ToolLibrary)get(activity,"library")).activeId().equals(preset),"Previous favorite retained");
                    check(Arrays.equals(before,encoded(doc)),"Sample gesture leaves all layers unchanged");
                    check(get(pad,"stroke")==null && get(pad,"fill")==null,"Sample creates no drawing gesture");
                    check(prefs.getInt("gray",-1)==expected,"Picked shade persisted");
                    check(((View)get(activity,"shadePicker")).getWidth()>100,"Palette remains usable");
                });
            }
            main(test,() -> {doc.setLayerVisible(1,false);call(pad,"renderAll");((View)get(activity,"eyedropperButton")).performClick();});
            pen(test,pad,110,110);
            main(test,() -> {
                check((Integer)get(get(activity,"paint"),"gray")==40,"Hidden selected layer does not prevent picking visible layer");
                ((View)get(activity,"eyedropperButton")).performClick();
                ((View)get(activity,"eyedropperButton")).performClick();
                check(!(Boolean)get(activity,"pickingShade"),"Second eyedropper tap cancels");
                ((View)get(activity,"eyedropperButton")).performClick();
                call(activity,"selectShade",new Class<?>[]{int.class},0);
                check(!(Boolean)get(activity,"pickingShade"),"Selecting a shade cancels");
                ((View)get(activity,"eyedropperButton")).performClick();
                ((View)buttons(activity).get("tool:BRUSH")).performClick();
                check(!(Boolean)get(activity,"pickingShade"),"Selecting a tool cancels");
            });
            // A picked color must be used by the next normal pen stroke.
            main(test,() -> {doc.setLayerVisible(1,true);((View)get(activity,"eyedropperButton")).performClick();});
            pen(test,pad,110,110);pen(test,pad,200,200);
            main(test,() -> {
                check(doc.compositeTone(200,200)==expected,"Next brush stroke uses picked shade");
                check(doc.undo() && doc.compositeTone(200,200)==255,"Sampling adds no undo action");
            });
            report.append("PASS: toolbar icons, Layers visibility, saved order, rotated move targets, always-on instant selection, last-tool guard, hidden settings and favorites; eyedropper layered shade, all rotations and hands, hidden selected layer, cancellation, no marks, next stroke and undo; marker transfer below upright icon, hidden color indicators, exact cancel restoration, normal and instant feedback.\n");
        } finally {
            main(test,() -> {
                if(dialog[0]!=null)dialog[0].dismiss();
                call(activity,"setPickingShade",new Class<?>[]{boolean.class},false);
                set(activity,"drawingName",originalName);set(activity,"library",originalTools);set(get(activity,"paint"),"eraseMode",originalErase);
                set(get(activity,"paint"),"gray",originalGray);TestAccess.setMaximum(activity,originalMaximum);
                set(get(activity,"paint"),"wetCanvas",originalWet);set(get(activity,"paint"),"transparentPaint",originalTransparent);call(activity,"refreshPaintModes");
                SharedPreferences.Editor editor=prefs.edit();
                for(ToolSettings.Tool tool:ToolSettings.Tool.values()) {
                    String key="tool_visible_"+tool.name();
                    if(originalPrefs.containsKey(key))editor.putBoolean(key,(Boolean)originalPrefs.get(key));else editor.remove(key);
                }
                for(String key:new String[]{"tool_visible_LAYERS","tool_visible_ZOOM","toolbar_order","large_toolbar_icons"}) {
                    Object value=originalPrefs.get(key);
                    if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                    else if(value instanceof String)editor.putString(key,(String)value);else editor.remove(key);
                }
                editor.putBoolean("toolbox_right",Boolean.TRUE.equals(originalPrefs.get("toolbox_right"))).apply();
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},originalRotation);call(activity,"applyToolboxSide");
                call(activity,"saveToolState");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }
    private static void markerChecks(PaintActivity activity) throws Exception {
        View shade=(View)get(activity,"shadePicker"), eye=(View)get(activity,"eyedropperButton"), root=(View)get(activity,"root");
        SelectionFeedback feedback=(SelectionFeedback)get(activity,"selectionFeedback");
        View menu=(View)get(activity,"menuButton");
        int menuSubmissions=feedback.submitted;
        long down=SystemClock.uptimeMillis();
        MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,menu.getWidth()/2f,menu.getHeight()/2f,0);
        try {menu.dispatchTouchEvent(press);}finally{press.recycle();}
        check((Boolean)get(get(menu,"press"),"pressed") && feedback.submitted>menuSubmissions,"Hamburger shows immediate fast-path press outline");
        check(get(activity,"filePopup")==null,"Menu press feedback precedes opening the menu");
        MotionEvent cancel=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,0,0,0);
        try {menu.dispatchTouchEvent(cancel);}finally{cancel.recycle();}
        check(!(Boolean)get(get(menu,"press"),"pressed") && get(activity,"filePopup")==null,"Cancelled hamburger press clears its outline without opening the menu");
        boolean oldFeedback=feedback.enabled;
        int oldGray=(Integer)get(get(activity,"paint"),"gray");
        try {
            for(boolean instant:new boolean[]{false,true}) {
                feedback.enabled=instant;
                View selected=(View)buttons(activity).get(call(get(activity,"toolbar"),"selectedKey"));
                View brush=(View)buttons(activity).get("tool:BRUSH");
                Bitmap inactive=render(brush);
                int toolSubmissions=feedback.submitted;
                brush.performClick();
                Bitmap active=render(brush);
                Rect changed=SelectionFeedback.difference(inactive,active);
                check(changed.width()>brush.getWidth()/2 && changed.height()>brush.getHeight()/2,"Selected tool has a box around the whole button");
                float inset=6*activity.getResources().getDisplayMetrics().density;
                for(int y=(int)inset;y<brush.getHeight()-inset;y++)for(int x=(int)inset;x<brush.getWidth()-inset;x++)
                    check(inactive.getPixel(x,y)==active.getPixel(x,y),"Selection box preserves the tool icon and favorite marks");
                check(brush.isSelected()&&!selected.isSelected(),"Only the active drawing tool is selected");
                if(instant)check(feedback.submitted>toolSubmissions,"Toolbar selection box reaches direct e-ink feedback");
                selected.performClick();
                Bitmap restored=render(brush);
                check(inactive.sameAs(restored),"Deselecting restores exact toolbar pixels");
                inactive.recycle();active.recycle();restored.recycle();
                set(get(activity,"paint"),"gray",128);
                Bitmap shadeBefore=render(shade), eyeBefore=render(eye);
                Bitmap shadeArmed=null, eyeArmed=null, shadeOther=null, shadeAfter=null, eyeAfter=null;
                try {
                    int submitted=feedback.submitted;
                    eye.performClick();
                    check((Boolean)get(activity,"pickingShade") && (Integer)get(get(activity,"paint"),"gray")==128,"Arming retains previous shade");
                    shadeArmed=render(shade);eyeArmed=render(eye);
                    check(!shadeBefore.sameAs(shadeArmed),"Color indicators disappear while picking");
                    // No indicator may reveal the remembered shade while the eyedropper is armed.
                    set(get(activity,"paint"),"gray",220);shadeOther=render(shade);
                    check(shadeArmed.sameAs(shadeOther),"Neither color indicator is drawn while picking");
                    set(get(activity,"paint"),"gray",128);
                    Rect declared=(Rect)call(eye,"markerArea");
                    Matrix panelToRoot=new Matrix();PanelCoordinates.fromView(root).invert(panelToRoot);
                    Matrix eyeToRoot=PanelCoordinates.fromView(eye);eyeToRoot.postConcat(panelToRoot);
                    float[] center={eye.getWidth()/2f,eye.getHeight()/2f};eyeToRoot.mapPoints(center);
                    int changes=0;
                    float density=activity.getResources().getDisplayMetrics().density;
                    for(int y=0;y<eye.getHeight();y++) for(int x=0;x<eye.getWidth();x++) {
                        if(eyeBefore.getPixel(x,y)==eyeArmed.getPixel(x,y))continue;
                        check((eyeArmed.getPixel(x,y)&0xffffff)==0,"Eyedropper adds only a black square");
                        check(declared.contains(x,y),"Instant feedback patch contains every marker pixel");
                        float[] point={x+.5f,y+.5f};eyeToRoot.mapPoints(point);
                        check(Math.abs(point[0]-center[0])<=4*density && point[1]>center[1]+15*density,
                                "Marker sits below the upright icon in the current orientation and hand");
                        changes++;
                    }
                    check(changes>=25*density*density && changes<=49*density*density,"Small square marker has expected size");
                    if(instant)check(feedback.submitted>submitted,"Marker transfer reaches direct e-ink feedback");
                    eye.performClick();
                    check(!(Boolean)get(activity,"pickingShade") && (Integer)get(get(activity,"paint"),"gray")==128,"Cancel restores remembered color");
                    shadeAfter=render(shade);eyeAfter=render(eye);
                    check(shadeBefore.sameAs(shadeAfter) && eyeBefore.sameAs(eyeAfter),"Cancel restores both controls pixel for pixel");
                } finally {
                    for(Bitmap bitmap:new Bitmap[]{shadeBefore,eyeBefore,shadeArmed,eyeArmed,shadeOther,shadeAfter,eyeAfter})
                        if(bitmap!=null)bitmap.recycle();
                }
            }
        } finally {
            call(activity,"setPickingShade",new Class<?>[]{boolean.class},false);
            feedback.enabled=oldFeedback;set(get(activity,"paint"),"gray",oldGray);shade.invalidate();eye.invalidate();
        }
    }
    private static Bitmap render(View view) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));return bitmap;
    }
    private static byte[] encoded(ToneDocument doc) throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();DocumentCodec.write(out,doc.layerSnapshot());return out.toByteArray();
    }
    private static Map<?,?> buttons(PaintActivity activity) throws Exception {return (Map<?,?>)get(get(activity,"toolbar"),"selectionButtons");}
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
    private static android.widget.RadioButton findRadio(View view,String text) {
        if(view instanceof android.widget.RadioButton && ((android.widget.RadioButton)view).getText().toString().equals(text))return (android.widget.RadioButton)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            android.widget.RadioButton found=findRadio(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    private static CheckBox findCheck(View view,String text) {
        if(view instanceof CheckBox && text.contentEquals(((CheckBox)view).getText()))return (CheckBox)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {CheckBox found=findCheck(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static void pen(Instrumentation test,Object pad,float x,float y) throws Exception {
        float[] point={x,y};
        main(test,() -> {((Matrix)get(pad,"pageToView")).mapPoints(point);PanelCoordinates.fromView((View)pad).mapPoints(point);});
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=point[0];coords.y=point[1];coords.pressure=.6f;coords.size=.1f;
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{prop},
                    new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
            check(test.getUiAutomation().injectInputEvent(event,true),"Pen injected");event.recycle();
        }
        test.waitForIdleSync();
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
