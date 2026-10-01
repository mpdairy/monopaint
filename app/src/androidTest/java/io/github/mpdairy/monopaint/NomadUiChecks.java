package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Runs on ShapeUiChecks' disposable book and shares its verified restoration. */
final class NomadUiChecks {
    static void run(Instrumentation test,PaintActivity app,StringBuilder report)throws Exception {
        if(test instanceof WidthInstrumentation && ((WidthInstrumentation)test).rotationPromptOnly) {
            RotationPromptChecks.run(test,app,report);return;
        }
        SharedPreferences prefs=(SharedPreferences)get(app,"preferences");
        boolean original=prefs.getBoolean("nomad_mode",false);
        View frame=(View)get(app,"orientationFrame"),pad=(View)get(app,"pad");
        View menu=(View)get(app,"menuButton");int controlWidth=menu.getWidth();
        ToneDocument doc=(ToneDocument)get(pad,"document");byte[] before=doc.snapshot();
        AlertDialog[] dialog={null};
        try {
            main(test,() -> call(app,"setNomadMode",new Class<?>[]{boolean.class},false));idle(test);
            main(test,() -> dialog[0]=(AlertDialog)call(app,"appSettings"));idle(test);
            tap(test,find(dialog[0].getWindow().getDecorView(),"Nomad Simulation Mode"));idle(test);
            check(prefs.getBoolean("nomad_mode",false)&&!dialog[0].isShowing(),"Settings toggle persists and dismisses");
            dialog[0]=null;
            for(int turn=0;turn<4;turn++)for(boolean right:new boolean[]{false,true}) {
                final int quarter=turn;
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",right).apply();
                    call(app,"requestQuarter",new Class<?>[]{int.class},quarter);call(app,"applyToolboxSide");
                });idle(test);
                main(test,() -> {
                    RectF area=bounds(frame);
                    check(area.equals(new RectF(258,344,1662,2216)),"Exact centered physical viewport: "+area);
                    check(((android.graphics.drawable.ColorDrawable)((View)get(app,"previewFrame")).getBackground()).getColor()==android.graphics.Color.BLACK,"Simulation margins are black");
                    check(menu.getWidth()==controlWidth,"Physical control size unchanged");
                    check(area.contains(bounds(pad)),"Canvas inside preview");
                    check(get(pad,"direct")!=null,"Manta fast display is connected in centered viewport");
                    check(Arrays.equals(before,doc.snapshot()),"Toggles and rotations preserve document");
                });
                // A real screen-coordinate pen replay checks the new ancestor offset.
                long down=SystemClock.uptimeMillis();
                ShapeUiChecks.pen(test,pad,MotionEvent.ACTION_DOWN,down,150,180);
                ShapeUiChecks.pen(test,pad,MotionEvent.ACTION_MOVE,down,280,320);
                ShapeUiChecks.pen(test,pad,MotionEvent.ACTION_UP,down,280,320);idle(test);
                main(test,() -> {check(doc.canUndo(),"Offset stylus reaches canvas");
                    boolean ink=false;for(int y=248;y<=252;y++)for(int x=213;x<=217;x++)ink|=doc.opacity(x,y)>0;
                    check(ink,"Offset stylus paints at expected document coordinates");check(doc.undo(),"Stroke undo");
                    check(Arrays.equals(before,doc.snapshot()),"Undo restores test page");call(pad,"renderAll");pad.invalidate();});
                tapAt(test,120,120);idle(test);
                check(Arrays.equals(before,doc.snapshot())&&!doc.canUndo(),"Blank margin does not paint");
                main(test,() -> call(app,"fileMenu",new Class<?>[]{View.class},menu));idle(test);
                PopupWindow file=(PopupWindow)get(app,"filePopup");
                check(bounds(frame).contains(bounds(file.getContentView())),"File menu inside preview");
                main(test,file::dismiss);
                main(test,() -> call(app,"showToolSettings"));idle(test);
                PopupWindow tools=(PopupWindow)get(app,"toolPicker");
                check(bounds(frame).contains(bounds(tools.getContentView())),"Tool panel inside preview");
                main(test,tools::dismiss);
                main(test,() -> dialog[0]=(AlertDialog)call(app,"appSettings"));idle(test);
                check(bounds(frame).contains(bounds(dialog[0].getWindow().getDecorView())),"Settings dialog inside preview: "+bounds(dialog[0].getWindow().getDecorView()));
                if(turn==0&&!right)screenshot(test,app,"nomad-settings.png");
                main(test,() -> dialog[0].dismiss());dialog[0]=null;idle(test);
                if(!right)screenshot(test,app,"nomad-"+turn+".png");
                report.append("PASS: centered Nomad viewport, unchanged controls, pen, margins and panels; turn=").append(turn).append(" right=").append(right).append(".\n");
            }
            main(test,() -> dialog[0]=(AlertDialog)call(app,"appSettings"));idle(test);
            tap(test,find(dialog[0].getWindow().getDecorView(),"Nomad Simulation Mode"));idle(test);dialog[0]=null;
            check(!prefs.getBoolean("nomad_mode",true)&&frame.getWidth()==1920&&frame.getHeight()==2560,"Toggle off restores full screen");
            report.append("PASS: Nomad mode can be disabled through the rotated Settings toggle.\n");
            PageNavigationChecks.run(test,app,report);
            RotationPromptChecks.run(test,app,report);
        } finally {
            main(test,() -> {
                call(app,"closePagePanel");
                if(dialog[0]!=null)dialog[0].dismiss();
                PopupWindow tool=(PopupWindow)get(app,"toolPicker");if(tool!=null)tool.dismiss();
                PopupWindow file=(PopupWindow)get(app,"filePopup");if(file!=null)file.dismiss();
                call(app,"setNomadMode",new Class<?>[]{boolean.class},original);
            });idle(test);
        }
    }
    private static RectF bounds(View view) {
        RectF rect=new RectF(0,0,view.getWidth(),view.getHeight());PanelCoordinates.fromView(view).mapRect(rect);return rect;
    }
    private static void idle(Instrumentation test){test.waitForIdleSync();SystemClock.sleep(150);}
    private static void tap(Instrumentation test,View view)throws Exception {
        check(view!=null,"Control exists");RectF rect=bounds(view);tapAt(test,rect.centerX(),rect.centerY());
    }
    private static void tapAt(Instrumentation test,float x,float y) {
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try{check(test.getUiAutomation().injectInputEvent(event,true),"Tap injected");}finally{event.recycle();}
        }
    }
    private static View find(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    private static void screenshot(Instrumentation test,PaintActivity app,String name)throws Exception {
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getCacheDir(),name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }finally{bitmap.recycle();}
    }
    private interface Work {void run()throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception {
        Throwable[] error={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    private static Object get(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name)throws Exception{return call(owner,name,new Class<?>[0]);}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
