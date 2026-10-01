package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/** Mode controls and real pen routing on a disposable book; restores the user's session. */
final class EraseUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<100&&(Boolean)get(activity,"loading");i++)SystemClock.sleep(100);
        test.waitForIdleSync();
        Object pad=get(activity,"pad");DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName");ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(activity,"gray"),maximum=(Integer)get(activity,"maximum"),rotation=(Integer)get(activity,"appRotation");
        boolean wet=(Boolean)get(activity,"wetCanvas"),transparent=(Boolean)get(activity,"transparentPaint"),erase=(Boolean)get(activity,"eraseMode");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");boolean right=prefs.getBoolean("toolbox_right",false);
        Map<String,?> originalPreferences=prefs.getAll();
        String[] requiredTools={"PENCIL","AIRBRUSH","SOFTEN","ERASER"};
        ToneDocument doc=new ToneDocument(320,480);
        doc.begin();for(int y=0;y<480;y++)for(int x=0;x<320;x++)doc.paintTone(x,y,24);doc.finish();
        doc.addLayer();doc.begin();for(int y=0;y<480;y++)for(int x=0;x<320;x++)doc.paintTone(x,y,160);doc.finish();
        byte[] originalPixels=doc.snapshot();
        try {
            main(test,() -> {
                ((android.view.OrientationEventListener)get(activity,"orientationSensor")).disable();
                call(pad,"finishStroke");call(pad,"dryWet");set(activity,"drawingName","");set(activity,"library",new ToolLibrary());
                set(activity,"eraseMode",false);set(activity,"wetCanvas",false);set(activity,"gray",0);
                for(String tool:requiredTools)prefs.edit().putBoolean("tool_visible_"+tool,true).commit();
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));call(activity,"rebuildTools");
            });
            for(int quarter=0;quarter<4;quarter++)for(boolean hand:new boolean[]{false,true}) {
                final int turn=quarter;
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",hand).commit();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},turn);call(activity,"applyToolboxSide");
                    call(activity,"selectShade",new Class<?>[]{int.class},0);
                });
                test.waitForIdleSync();SystemClock.sleep(100);
                View button=(View)get(activity,"eraseButton"),shade=(View)get(activity,"shadePicker");
                Bitmap before=render(test,shade);
                tap(test,button);
                main(test,() -> {
                    check((Boolean)get(activity,"eraseMode")&&button.isSelected(),"Eraser color selected in every rotation and hand");
                    ViewGroup colors=(ViewGroup)shade.getParent();
                    check(colors.indexOfChild(button)==colors.indexOfChild(shade)+((Boolean)get(activity,"landscape")?-1:1),"Eraser next to white end");
                    check(shade.getWidth()>60,"Shade bar retains usable width");
                    check(((ToolLibrary)get(activity,"library")).current().tool==ToolSettings.Tool.BRUSH,"Mode retains selected brush");
                });
                Bitmap after=render(test,shade);check(!before.sameAs(after),"Color marker leaves the bar");before.recycle();after.recycle();
                long down=SystemClock.uptimeMillis();pen(test,pad,MotionEvent.ACTION_DOWN,down,160,240);pen(test,pad,MotionEvent.ACTION_UP,down,160,240);
                main(test,() -> {
                    check(doc.opacity(160,240)==0&&doc.compositeTone(160,240)==24,"Real pen erases selected layer");
                    check(doc.undo()&&Arrays.equals(originalPixels,doc.snapshot()),"One undo restores original layers");call(pad,"renderAll");
                });
                if(quarter==0&&!hand) {
                    main(test,() -> {((View)get(activity,"root")).invalidate();});test.waitForIdleSync();SystemClock.sleep(100);
                    Bitmap shot=test.getUiAutomation().takeScreenshot();
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),"erase-mode.png"))) {shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}
                }
            }
            main(test,() -> {
                ToolLibrary tools=(ToolLibrary)get(activity,"library");
                for(ToolSettings.Head head:ToolSettings.Head.values()) {
                    tools.selectHead(head);tools.edit(tools.current().size(64));set(activity,"maximum",64);call(activity,"refreshToolSelection");
                    ToolSettings settings=tools.current();
                    call(activity,"setWetCanvas",new Class<?>[]{boolean.class},true);
                    call(activity,"setTransparentPaint",new Class<?>[]{boolean.class},true);
                    call(activity,"selectShade",new Class<?>[]{int.class},0);
                    ((View)get(activity,"eraseButton")).performClick();
                    replay(pad,160,240);
                    check(doc.opacity(160,240)==0&&get(pad,"wet")==null,"Erase bypasses wet/transparent paint for "+head);
                    ToneDocument mask=new ToneDocument(320,480);
                    PressureStroke brush=new PressureStroke(mask,settings,0);brush.sample(160,240,.45f,0,0);brush.finish();
                    for(int y=195;y<285;y++)for(int x=115;x<205;x++)check(doc.opacity(x,y)==255-mask.opacity(x,y),"Erase footprint matches "+head);
                    check(doc.undo()&&Arrays.equals(originalPixels,doc.snapshot()),"Head undo");
                }
                Map<?,?> buttons=(Map<?,?>)get(activity,"selectionButtons");
                for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.PENCIL,ToolSettings.Tool.AIRBRUSH}) {
                    ((View)buttons.get("tool:"+tool)).performClick();
                    check((Boolean)get(activity,"eraseMode"),"Mode follows supported tools");
                    replay(pad,160,240);
                    check(doc.opacity(160,240)<255,"Tool erases: "+tool);check(doc.undo(),"Tool undo");
                }
                tools.select(ToolSettings.Tool.AIRBRUSH);String preset=tools.add().id;call(activity,"rebuildTools");
                call(activity,"selectShade",new Class<?>[]{int.class},0);
                ((View)get(activity,"eraseButton")).performClick();
                check(tools.activeId().equals(preset),"Erase mode preserves favorite identity");
                call(activity,"preferences");check(prefs.getBoolean("erase_mode",false),"Erase selection saved");
                call(activity,"selectShade",new Class<?>[]{int.class},(Integer)get(activity,"gray"));
                check(!(Boolean)get(activity,"eraseMode"),"Reselecting same shade exits erase");
                ((View)get(activity,"eraseButton")).performClick();
                ((View)get(activity,"eyedropperButton")).performClick();
                check(!((View)get(activity,"eraseButton")).isSelected(),"Eyedropper takes selection dot");
                MotionEvent event=event(pad,MotionEvent.ACTION_DOWN,0,0,160,240,.45f);((View)pad).onTouchEvent(event);event.recycle();
                event=event(pad,MotionEvent.ACTION_CANCEL,0,1,160,240,.45f);((View)pad).onTouchEvent(event);event.recycle();
                check((Boolean)get(activity,"eraseMode"),"Canceled sampling restores erase mode");
                ((View)get(activity,"eyedropperButton")).performClick();replay(pad,160,240);
                check(!(Boolean)get(activity,"eraseMode")&&(Integer)get(activity,"gray")==160,"Picked color returns to painting");
                erasingShapesAndFills(activity,pad,doc);
                ShapePreviewChecks.run(report);
                buttons=(Map<?,?>)get(activity,"selectionButtons");
                for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.SOFTEN,ToolSettings.Tool.ERASER}) {
                    tools.select(ToolSettings.Tool.BRUSH);call(activity,"refreshToolSelection");((View)get(activity,"eraseButton")).performClick();
                    ((View)buttons.get("tool:"+tool)).performClick();
                    check(!(Boolean)get(activity,"eraseMode")&&!((View)get(activity,"eraseButton")).isEnabled(),"Non-brush tool uses normal behavior: "+tool);
                }
                replay(pad,160,240);check(doc.opacity(160,240)<255,"Standalone eraser still erases");check(doc.undo(),"Standalone undo");
                doc.selectLayer(0);check(doc.tone(160,240)==24,"Lower layer intact");doc.selectLayer(1);
            });
            report.append("PASS: Eraser color touch targets and marker in four rotations/both hands; exact Round/Flat/Filbert footprints; real pen layer reveal, pencil and airbrush, one-step undo, wet/transparent modes, presets, saved selection, same-color exit, eyedropper accept/cancel, filled shape erase, solid fill erase, both gradient types with either transparent endpoint, exact native shape previews, cancellation, unsupported tools and retained standalone eraser.\n");
        } finally {
            main(test,() -> {
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"drawingName",name);set(activity,"library",library);set(activity,"gray",gray);set(activity,"maximum",maximum);
                set(activity,"wetCanvas",wet);set(activity,"transparentPaint",transparent);set(activity,"eraseMode",erase);
                prefs.edit().putBoolean("toolbox_right",right).commit();
                for(String tool:requiredTools) {
                    String key="tool_visible_"+tool;
                    if(originalPreferences.containsKey(key))prefs.edit().putBoolean(key,(Boolean)originalPreferences.get(key)).commit();
                    else prefs.edit().remove(key).commit();
                }
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},rotation);call(activity,"applyToolboxSide");call(activity,"refreshPaintModes");
                call(activity,"preferences");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }
    private static void erasingShapesAndFills(PaintActivity activity,Object pad,ToneDocument doc) throws Exception {
        ToolLibrary tools=(ToolLibrary)get(activity,"library");
        byte[] original=DrawingBook.encode(doc);
        tools.select(ToolSettings.Tool.SHAPES);call(activity,"refreshToolSelection");
        ((View)get(activity,"eraseButton")).performClick();
        check((Boolean)get(activity,"eraseMode"),"Shapes accepts eraser color");
        tools.edit(tools.current().shape(ToolSettings.Shape.RECTANGLE).filled(true));
        drag(pad,80,100,240,300);
        check(doc.opacity(160,240)==0&&doc.compositeTone(160,240)==24&&doc.opacity(40,40)==255,"Shape erases its interior only");
        check(doc.undo()&&Arrays.equals(original,DrawingBook.encode(doc)),"Erase shape restores all layers");call(pad,"renderAll");
        tools.select(ToolSettings.Tool.FILL);call(activity,"refreshToolSelection");
        check((Boolean)get(activity,"eraseMode")&&((View)get(activity,"eraseButton")).isEnabled(),"Fill retains eraser color");
        replay(pad,160,240);while(get(pad,"fill")!=null)call(pad,"advanceFill");
        check(doc.opacity(10,10)==0&&doc.compositeTone(160,240)==24,"Pen tap erase fill");
        check(doc.undo()&&Arrays.equals(original,DrawingBook.encode(doc)),"Erase fill undo");call(pad,"renderAll");
        for(ToolSettings.Gradient type:ToolSettings.Gradient.values()) {
            tools.edit(tools.current().gradient(type));
            // Start transparent, then choose a paint endpoint.
            drag(pad,80,240,240,240);
            call(activity,"selectShade",new Class<?>[]{int.class},80);call(pad,"applyGradient");
            while(get(pad,"gradientFill")!=null)call(pad,"advanceGradient");
            check(doc.opacity(80,240)==0&&doc.opacity(160,240)==128&&doc.tone(240,240)==80,"Erase-to-color pen gradient: "+type);
            check(doc.undo()&&Arrays.equals(original,DrawingBook.encode(doc)),"Erase-to-color undo");call(pad,"renderAll");
            // Start in paint; the actual erase button must preserve the pending axis.
            drag(pad,80,240,240,240);
            ((View)get(activity,"eraseButton")).performClick();
            while(get(pad,"gradientFill")!=null)call(pad,"advanceGradient");
            check(doc.tone(80,240)==80&&doc.opacity(160,240)==128&&doc.opacity(240,240)==0,"Color-to-erase button gradient: "+type);
            check(doc.undo()&&Arrays.equals(original,DrawingBook.encode(doc)),"Color-to-erase undo");call(pad,"renderAll");
            drag(pad,80,240,240,240);call(activity,"selectShade",new Class<?>[]{int.class},200);
            call(pad,"finishStroke");
            check((Boolean)get(activity,"eraseMode")&&Arrays.equals(original,DrawingBook.encode(doc)),"Canceled gradient restores eraser and drawing");
        }
        call(activity,"selectShade",new Class<?>[]{int.class},160);
    }
    private static void drag(Object pad,float x,float y,float endX,float endY) throws Exception {
        long now=SystemClock.uptimeMillis();
        MotionEvent event=event(pad,MotionEvent.ACTION_DOWN,now,now,x,y,.45f);((View)pad).onTouchEvent(event);event.recycle();
        event=event(pad,MotionEvent.ACTION_UP,now,now+1,endX,endY,.45f);((View)pad).onTouchEvent(event);event.recycle();
    }
    private static void replay(Object pad,float x,float y) throws Exception {
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=event(pad,action,now,now+1,x,y,.45f);((View)pad).onTouchEvent(event);event.recycle();
        }
    }
    private static Bitmap render(Instrumentation test,View view) throws Exception {
        Bitmap[] bitmap={null};main(test,() -> {bitmap[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap[0]));});return bitmap[0];
    }
    private static void tap(Instrumentation test,View view) throws Exception {
        float[] point={view.getWidth()/2f,view.getHeight()/2f};main(test,() -> PanelCoordinates.fromView(view).mapPoints(point));
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(now,now+1,action,point[0],point[1],0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try{check(test.getUiAutomation().injectInputEvent(event,true),"Tap injected");}finally{event.recycle();}
        }
        test.waitForIdleSync();
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
