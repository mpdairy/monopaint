package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/** Runs inside ShapeUiChecks' temporary book and verified session restoration. */
final class ToolPickerChecks {
    static void run(Instrumentation test,PaintActivity activity,StringBuilder report)throws Exception {
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");
        ToolLibrary library=(ToolLibrary)get(activity,"library");
        View pad=(View)get(activity,"pad");ToneDocument doc=(ToneDocument)get(pad,"document");byte[] before=doc.snapshot();
        String[] extra={"PENCIL","AIRBRUSH","ERASER","SOFTEN","FILL","LAYERS","ZOOM"};
        Map<String,?> original=prefs.getAll();
        try {
            main(test,() -> {
                SharedPreferences.Editor edit=prefs.edit().putBoolean("large_settings_text",false);
                for(String tool:extra)edit.putBoolean("tool_visible_"+tool,true);
                edit.apply();call(get(activity,"toolbar"),"rebuildTools");
                if(get(activity,"toolPicker")!=null)((PopupWindow)get(activity,"toolPicker")).dismiss();
            });
        main(test,() -> {prefs.edit().putBoolean("shape_chosen",false).apply();call(get(activity,"toolbar"),"refreshToolSelection");});
        View shapes=(View)((Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get("tool:SHAPES");
        check((Integer)get(shapes,"iconResource")==R.drawable.ic_shapes,"Unchosen Shapes uses combined icon");
        tap(test,shapes);
        PopupWindow first=(PopupWindow)get(activity,"toolPicker");
        check(first!=null&&first.isShowing(),"First Shapes tap opens chooser");
        check(find(first.getContentView(),"Shape outline width")==null,"Unchosen Shapes shows choices first");
        tap(test,find(first.getContentView(),"Line shape"));
        check(prefs.getBoolean("shape_chosen",false),"Explicit first choice persists even for Line");
        tap(test,find(first.getContentView(),"Close shapes"));
        for(int rotation=0;rotation<4;rotation++)for(boolean right:new boolean[]{false,true}) {
            final int quarter=rotation;
            main(test,() -> {
                prefs.edit().putBoolean("toolbox_right",right).apply();
                call(activity,"requestQuarter",new Class<?>[]{int.class},quarter);call(activity,"applyToolboxSide");
                library.select(ToolSettings.Tool.PENCIL);call(get(activity,"toolbar"),"refreshToolSelection");
            });test.waitForIdleSync();
            report.append("Checking rotation ").append(rotation).append(" right=").append(right).append(".\n");
            for(String tool:new String[]{"SHAPES","BRUSH","PENCIL","AIRBRUSH","ERASER","SOFTEN","FILL"}) {
                View anchor=(View)((Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get("tool:"+tool);
                main(test,() -> anchor.requestRectangleOnScreen(new android.graphics.Rect(0,0,anchor.getWidth(),anchor.getHeight()),true));
                test.waitForIdleSync();tap(test,anchor);
                check(get(activity,"toolPicker")==null&&library.current().tool==ToolSettings.Tool.valueOf(tool),"First tap only selects "+tool);
                tap(test,anchor);
                PopupWindow popup=(PopupWindow)get(activity,"toolPicker");check(popup!=null&&popup.isShowing(),"Second tap opens "+tool);
                View panel=popup.getContentView();
                if(tool.equals("SHAPES"))check(find(panel,"Shape outline width")!=null,"Current shape settings open with choices");
                if(tool.equals("BRUSH"))check(find(panel,"Pressure response")!=null,"Current brush settings open with choices");
                main(test,() -> position(activity,panel,anchor));
                if(tool.equals("SHAPES")) {
                    if(rotation==0&&!right)screenshot(test,activity,"shape-choices.png");
                    // Even tapping the already-selected variant must reveal its controls.
                    tap(test,find(panel,library.current().shape.label+" shape"));
                    check(find(panel,"Shape outline width")!=null,"Current shape expands");
                    for(ToolSettings.Shape shape:ToolSettings.Shape.values()) {
                        tap(test,find(panel,shape.label+" shape"));check(library.current().shape==shape,"Shape icon touch selects "+shape);
                        check(find(panel,shape.label+" shape").isSelected(),"Selected shape is marked");
                        check((Integer)get(anchor,"iconResource")==shapeIcon(shape),"Builtin icon follows selected shape");main(test,() -> position(activity,panel,anchor));
                        if(shape==ToolSettings.Shape.LINE) {
                            check(find(panel,"Shape outline width").isShown()&&!find(panel,"Filled shape").isShown(),"Line only exposes width");
                        } else {
                            tap(test,find(panel,"Filled shape"));check(library.current().filled&&!find(panel,"Shape outline width").isShown(),"Filled hides width");
                            tap(test,find(panel,"Outline shape"));check(!library.current().filled&&find(panel,"Shape outline width").isShown(),"Outline restores width");
                        }
                        main(test,() -> ((SeekBar)find(panel,"Shape outline width")).setProgress(10+shape.ordinal()));
                        check(library.current().outlineWidth==11+shape.ordinal(),"Width follows slider");
                        main(test,() -> below(panel,find(panel,shape.label+" shape"),find(panel,"Shape outline width")));
                    }
                    if(rotation==0&&!right)screenshot(test,activity,"shape-details.png");
                    tap(test,find(panel,"Close shapes"));
                } else if(tool.equals("BRUSH")) {
                    tap(test,find(panel,library.current().head.label+" Brush"));
                    check(find(panel,"Pressure response")!=null,"Current brush tip expands");
                    for(ToolSettings.Head head:ToolSettings.Head.values()) {
                        tap(test,find(panel,head.label+" Brush"));check(library.current().head==head,"Head icon touch selects "+head);
                        check((Integer)get(anchor,"iconResource")== (head==ToolSettings.Head.FLAT?R.drawable.ic_brush_flat:head==ToolSettings.Head.FILBERT?R.drawable.ic_brush_filbert:R.drawable.ic_brush),"Brush icon follows selected head");
                        View size=find(panel,head==ToolSettings.Head.ROUND?"Maximum diameter":"Maximum width");
                        check(size!=null&&size.isShown(),"Matching head controls visible");main(test,() -> below(panel,find(panel,head.label+" Brush"),size));
                        check((find(panel,"Brush height")!=null)==(head==ToolSettings.Head.FLAT),"Height only for flat brush");
                        main(test,() -> position(activity,panel,anchor));
                        main(test,() -> ((SeekBar)size).setProgress(70+head.ordinal()));
                    }
                    tap(test,find(panel,"Flat Brush"));check(library.current().maximum==73,"Tip settings survive switching");
                    if(rotation==0&&!right)screenshot(test,activity,"brush-details.png");
                    tap(test,find(panel,"Close brush"));
                } else {
                    genericControls(test,activity,panel,tool,library);
                    if(rotation==0&&!right)screenshot(test,activity,tool.toLowerCase()+"-settings.png");
                    tap(test,find(panel,"Close tool settings"));
                }
                check(!popup.isShowing(),"Close dismisses panel");
                ToolLibrary restored=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                check(restored.current().equals(library.current()),"Panel settings persist");
                // Dismiss by touching uncovered canvas. This gesture must never start a stroke.
                tap(test,anchor);popup=(PopupWindow)get(activity,"toolPicker");
                RectF pop=bounds(popup.getContentView()),canvas=bounds(pad);
                float x=pop.centerX()<canvas.centerX()?canvas.right-12:canvas.left+12;
                float y=pop.centerY()<canvas.centerY()?canvas.bottom-12:canvas.top+12;
                tapAt(test,x,y,true);check(!popup.isShowing(),"Outside tap dismisses choices");
                check(Arrays.equals(before,doc.snapshot())&&!doc.canUndo(),"Panel interactions leave drawing unchanged");
            }
            zoomLock(test,activity,library);
        }
        fontPreference(test,activity,prefs,library);
        shapeShortcuts(test,activity,prefs,library);
        check(Arrays.equals(before,doc.snapshot())&&!doc.canUndo(),"Font and layer panels leave drawing unchanged");
        report.append("PASS: First tap selects and second tap opens options for all tools; matching builtin/custom shape icons update live; four rotations and both sides; controls, persistence, head memory, outside dismissal; Medium/Large preference updates tool, Layers and main settings fonts; Zoom lock preserves tool selection; custom presets save, edit and delete independently.\n");
        } catch(Exception | AssertionError error) {
            screenshot(test,activity,"toolbar-check-failure.png");throw error;
        } finally {
            main(test,() -> {
                PopupWindow popup=(PopupWindow)get(activity,"toolPicker");if(popup!=null)popup.dismiss();
                popup=(PopupWindow)get(activity,"layersPopup");if(popup!=null)popup.dismiss();
                SharedPreferences.Editor edit=prefs.edit();
                for(String tool:extra)restore(edit,original,"tool_visible_"+tool);
                restore(edit,original,"large_settings_text");restore(edit,original,"navigation_locked");edit.apply();
                set(activity,"navigationLocked",original.containsKey("navigation_locked")?(Boolean)original.get("navigation_locked"):true);
            });
        }
    }
    private static void restore(SharedPreferences.Editor edit,Map<String,?> original,String key) {
        if(original.containsKey(key))edit.putBoolean(key,(Boolean)original.get(key));else edit.remove(key);
    }
    private static void genericControls(Instrumentation test,PaintActivity activity,View panel,String tool,ToolLibrary library)throws Exception {
        if(tool.equals("FILL")) {
            tap(test,find(panel,"Flat fill"));
            check(find(panel,"Tolerance")==null,"Flat fill has no settings");
            main(test,() -> {
                ViewGroup choices=(ViewGroup)find(panel,"Fill choices");
                for(int i=0;i<choices.getChildCount();i++) {
                    View choice=choices.getChildAt(i);
                    check(choice.getTop()==0&&choice.getBottom()<=choices.getHeight(),"Multi-line fill choices fit without clipping");
                }
            });
            tap(test,find(panel,"Linear gradient"));
        }
        String control=tool.equals("AIRBRUSH")?"Flow":tool.equals("ERASER")?"Softness":tool.equals("SOFTEN")?"Strength":tool.equals("PENCIL")?"Hardness":"Tolerance";
        check(find(panel,control)!=null&&find(panel,control).isShown(),tool+" exposes its controls immediately");
        main(test,() -> {
            if(!tool.equals("FILL")) {
                ((SeekBar)find(panel,tool.equals("AIRBRUSH")?"Airbrush diameter":"Maximum diameter")).setProgress(53);
                check(library.current().maximum==55,"Size control retained for "+tool);
            }
            ((SeekBar)find(panel,control)).setProgress(61);
            ToolSettings current=library.current();
            int value=tool.equals("AIRBRUSH")||tool.equals("SOFTEN")?current.strength:tool.equals("ERASER")?current.softness:tool.equals("PENCIL")?current.hardness:current.tolerance;
            check(value==61,"Matching setting edited for "+tool);
        });
        if(tool.equals("PENCIL")) {
            boolean was=library.current().tilt;tap(test,find(panel,"Broaden with tilt"));check(library.current().tilt!=was,"Pencil tilt toggles");
            main(test,() -> ((SeekBar)find(panel,"Upright tip at full pressure")).setProgress(8));
            check(library.current().tip==library.current().minimum+8,"Pencil tip size retained");
        }
        if(tool.equals("FILL")) {
            tap(test,find(panel,"Circular gradient"));check(library.current().gradient==ToolSettings.Gradient.CIRCULAR,"Circular gradient selection");
            tap(test,find(panel,"Linear gradient"));check(library.current().gradient==ToolSettings.Gradient.LINEAR,"Linear gradient selection");
        }
    }
    private static void fontPreference(Instrumentation test,PaintActivity activity,SharedPreferences prefs,ToolLibrary library)throws Exception {
        main(test,() -> {call(activity,"requestQuarter",new Class<?>[]{int.class},0);prefs.edit().putBoolean("toolbox_right",false).apply();call(activity,"applyToolboxSide");});
        test.waitForIdleSync();
        for(boolean large:new boolean[]{true,false}) {
            android.app.AlertDialog[] dialog={null};
            try {
                main(test,() -> dialog[0]=(android.app.AlertDialog)call(activity,"appSettings"));test.waitForIdleSync();
                View choice=find(dialog[0].getWindow().getDecorView(),large?"Large settings text":"Medium settings text");
                main(test,() -> choice.requestRectangleOnScreen(new android.graphics.Rect(0,0,choice.getWidth(),choice.getHeight()),true));
                test.waitForIdleSync();tap(test,choice);
                check(prefs.getBoolean("large_settings_text",false)==large,"Font preference saved immediately");
                float expected=((android.widget.TextView)choice).getTextSize();
                main(test,() -> dialog[0].dismiss());test.waitForIdleSync();
                for(String tool:new String[]{"BRUSH","SHAPES","PENCIL","AIRBRUSH","ERASER","SOFTEN","FILL"}) {
                    View anchor=(View)((Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get("tool:"+tool);tap(test,anchor);
                    if(get(activity,"toolPicker")==null)tap(test,anchor);
                    PopupWindow popup=(PopupWindow)get(activity,"toolPicker");View panel=popup.getContentView();
                    if(tool.equals("BRUSH"))tap(test,find(panel,"Flat Brush"));
                    if(tool.equals("SHAPES"))tap(test,find(panel,"Rectangle shape"));
                    android.widget.TextView add=(android.widget.TextView)find(panel,"Add to Toolbar");
                    check(add.getTextSize()==expected&&add.getTypeface().isBold(),"Tool uses matching bold font size: "+tool);
                    main(test,() -> position(activity,panel,anchor));
                    if(tool.equals("PENCIL")||tool.equals("SHAPES"))screenshot(test,activity,tool.toLowerCase()+"-"+(large?"large":"medium")+".png");
                    main(test,popup::dismiss);test.waitForIdleSync();
                }
                zoomLock(test,activity,library);
                View layerAnchor=(View)get(get(activity,"toolbar"),"layersButton");tap(test,layerAnchor);
                PopupWindow layers=(PopupWindow)get(activity,"layersPopup");
                android.widget.TextView addLayer=(android.widget.TextView)find(layers.getContentView(),"Add layer");
                check(addLayer.getTextSize()==expected&&addLayer.getTypeface().isBold(),"Layers matches the chosen settings font");
                screenshot(test,activity,"layers-"+(large?"large":"medium")+".png");main(test,layers::dismiss);test.waitForIdleSync();
            } finally {if(dialog[0]!=null)main(test,dialog[0]::dismiss);}
        }
        // Saved presets use the same panel while retaining their separate settings.
        main(test,() -> {library.select(ToolSettings.Tool.PENCIL);call(get(activity,"toolbar"),"refreshToolSelection");});
        PopupWindow[] popup={null};ToolSettings regular=library.current();
        main(test,() -> {popup[0]=(PopupWindow)call(activity,"showToolSettings");find(popup[0].getContentView(),"Add to Toolbar").performClick();});
        check(!popup[0].isShowing()&&!library.activeId().isEmpty(),"Save custom tool from common panel");String id=library.activeId();
        main(test,() -> {popup[0]=(PopupWindow)call(activity,"showToolSettings");((SeekBar)find(popup[0].getContentView(),"Hardness")).setProgress(23);});
        check(library.current().hardness==23&&library.builtin(ToolSettings.Tool.PENCIL).equals(regular),"Preset editing leaves regular tool intact");
        main(test,() -> find(popup[0].getContentView(),"Delete custom tool").performClick());
        check(!popup[0].isShowing()&&library.activeId().isEmpty()&&library.presets().stream().noneMatch(p -> p.id.equals(id)),"Delete custom tool from common panel");
    }

    private static void zoomLock(Instrumentation test,PaintActivity activity,ToolLibrary library)throws Exception {
        Object pad=get(activity,"pad");ToolSettings selected=library.current();String preset=library.activeId();
        View anchor=(View)get(get(activity,"toolbar"),"zoomButton");
        main(test,() -> {call(pad,"fitPage");anchor.requestRectangleOnScreen(new android.graphics.Rect(0,0,anchor.getWidth(),anchor.getHeight()),true);});
        test.waitForIdleSync();
        boolean original=(Boolean)get(activity,"navigationLocked");
        for(int i=0;i<2;i++) {
            boolean before=(Boolean)get(activity,"navigationLocked");
            tap(test,anchor);
            check((Boolean)get(activity,"navigationLocked")!=before,"Tap toggles navigation lock");
            check(get(activity,"toolPicker")==null,"Zoom has no settings panel");
            check(((CanvasViewport)get(pad,"viewport")).zoom==1,"Lock does not change magnification");
            check(library.current().equals(selected)&&library.activeId().equals(preset),"Zoom retains selected drawing tool");
        }
        check((Boolean)get(activity,"navigationLocked")==original,"Restore navigation lock");
    }

    private static int shapeIcon(ToolSettings.Shape shape) {
        return new int[]{R.drawable.ic_shape_line,R.drawable.ic_shape_rectangle,R.drawable.ic_shape_square,R.drawable.ic_shape_oval,R.drawable.ic_shape_circle}[shape.ordinal()];
    }
    private static void shapeShortcuts(Instrumentation test,PaintActivity activity,SharedPreferences prefs,ToolLibrary library)throws Exception {
        main(test,() -> {library.select(ToolSettings.Tool.SHAPES);library.edit(library.current().shape(ToolSettings.Shape.RECTANGLE));call(get(activity,"toolbar"),"refreshToolSelection");});
        PopupWindow[] popup={null};ToolSettings regular=library.current();
        main(test,() -> {popup[0]=(PopupWindow)call(activity,"showToolSettings");find(popup[0].getContentView(),"Add to Toolbar").performClick();});
        String id=library.activeId();test.waitForIdleSync();
        View shortcut=(View)((Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get(id);
        check((Integer)get(shortcut,"iconResource")==R.drawable.ic_shape_rectangle,"Rectangle shortcut uses rectangle icon");
        View builtin=(View)((Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get("tool:SHAPES");tap(test,builtin);
        check(get(activity,"toolPicker")==null&&library.activeId().isEmpty(),"Builtin first tap selects from saved shortcut");
        main(test,() -> shortcut.requestRectangleOnScreen(new android.graphics.Rect(0,0,shortcut.getWidth(),shortcut.getHeight()),true));test.waitForIdleSync();
        tap(test,shortcut);check(get(activity,"toolPicker")==null&&library.activeId().equals(id),"Shortcut first tap selects without opening");
        tap(test,shortcut);popup[0]=(PopupWindow)get(activity,"toolPicker");check(popup[0]!=null,"Shortcut second tap opens options");
        for(ToolSettings.Shape shape:ToolSettings.Shape.values()) {
            tap(test,find(popup[0].getContentView(),shape.label+" shape"));
            check(library.current().shape==shape&&library.activeId().equals(id),"Changing shortcut keeps identity");
            check((Integer)get(shortcut,"iconResource")==shapeIcon(shape),"Saved shortcut icon updates immediately");
            check((Integer)get(builtin,"iconResource")==R.drawable.ic_shape_rectangle&&library.builtin(ToolSettings.Tool.SHAPES).equals(regular),"Regular shape icon and settings stay independent");
        }
        main(test,() -> popup[0].dismiss());test.waitForIdleSync();
        ToolLibrary decoded=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
        check(decoded.current().shape==ToolSettings.Shape.CIRCLE&&decoded.activeId().equals(id),"Saved shape and selection persist");
        main(test,() -> call(get(activity,"toolbar"),"rebuildTools"));test.waitForIdleSync();
        View rebuilt=(View)((Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get(id);
        check((Integer)get(rebuilt,"iconResource")==R.drawable.ic_shape_circle,"Rebuilt shortcut keeps selected shape icon");
        main(test,() -> {popup[0]=(PopupWindow)call(activity,"showToolSettings");find(popup[0].getContentView(),"Delete custom tool").performClick();});
    }
    private static void position(PaintActivity activity,View panel,View anchor)throws Exception {
        check(panel.isAttachedToWindow(),"Popup detached while checking "+((ToolLibrary)get(activity,"library")).current().description()+"; active popup="+get(activity,"toolPicker"));
        check(anchor.isAttachedToWindow(),"Toolbar anchor detached while checking "+anchor.getContentDescription());
        RectF popup=bounds(panel),canvas=bounds((View)get(activity,"pad")),icon=bounds(anchor);
        check(canvas.contains(popup),"Panel stays inside canvas: "+popup+" in "+canvas);
        check(!RectF.intersects(popup,icon),"Panel does not cover its toolbar icon");
        check(Math.min(Math.min(Math.abs(popup.left-icon.right),Math.abs(popup.right-icon.left)),
                Math.min(Math.abs(popup.top-icon.bottom),Math.abs(popup.bottom-icon.top)))<50,"Panel stays beside its icon");
    }
    private static RectF bounds(View view) {RectF r=new RectF(0,0,view.getWidth(),view.getHeight());PanelCoordinates.fromView(view).mapRect(r);return r;}
    private static void below(View panel,View choice,View control) {
        Matrix inverse=new Matrix();PanelCoordinates.fromView(panel).invert(inverse);
        // Popup root is physically unrotated; use the rotated panel's child coordinates.
        View logical=((ViewGroup)panel).getChildAt(0);PanelCoordinates.fromView(logical).invert(inverse);
        RectF row=bounds(choice),detail=bounds(control);inverse.mapRect(row);inverse.mapRect(detail);
        check(detail.top>=row.bottom,"Settings appear beneath icon row: "+row+" -> "+detail);
    }
    private static void screenshot(Instrumentation test,PaintActivity activity,String name)throws Exception {
        test.waitForIdleSync();SystemClock.sleep(150);
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),name))) {bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    static void tap(Instrumentation test,View view)throws Exception {
        check(view!=null,"Touch target exists");float[] point=new float[2];
        main(test,() -> {
            check(view.isShown()&&view.isAttachedToWindow()&&view.getWidth()>0&&view.getHeight()>0,"Touch target is laid out: "+view.getContentDescription());
            point[0]=view.getWidth()/2f;point[1]=view.getHeight()/2f;PanelCoordinates.fromView(view).mapPoints(point);
        });tapAt(test,point[0],point[1]);
    }
    private static void tapAt(Instrumentation test,float x,float y) {tapAt(test,x,y,false);}
    private static void tapAt(Instrumentation test,float x,float y,boolean dismissing) {
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            if(action==MotionEvent.ACTION_UP)SystemClock.sleep(40);
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try{boolean delivered=test.getUiAutomation().injectInputEvent(event,!(dismissing&&action==MotionEvent.ACTION_UP));
                // Dismissing the modal window can leave the rest of this gesture without a target.
                check(delivered||dismissing,"Touch injected: "+action+" at "+x+","+y);}finally{event.recycle();}
        }
        // Variant changes rebuild views even when the popup dimensions stay the same.
        // Idle callbacks can precede traversal; wait for the following frame before measuring.
        java.util.concurrent.CountDownLatch frame=new java.util.concurrent.CountDownLatch(1);
        test.runOnMainSync(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
                android.view.Choreographer.getInstance().postFrameCallback(second -> frame.countDown())));
        try {check(frame.await(3,java.util.concurrent.TimeUnit.SECONDS),"Settings frame presented");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        test.waitForIdleSync();
    }
    private static View find(View view,String name) {
        if(name.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),name);if(found!=null)return found;}
        return null;
    }
    private interface Work {void run()throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception {Throwable[] error={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object get(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name)throws Exception{return call(owner,name,new Class<?>[0]);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
