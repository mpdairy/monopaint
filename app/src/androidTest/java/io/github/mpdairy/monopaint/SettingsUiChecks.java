package io.github.mpdairy.monopaint;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The settings scroll rail must remain reachable at both simulated screen sizes. */
final class SettingsUiChecks {
    static void run(Instrumentation test,PaintActivity app,StringBuilder report)throws Exception {
        SharedPreferences prefs=(SharedPreferences)get(app,"preferences");
        boolean large=prefs.getBoolean("large_settings_text",false),hadLarge=prefs.contains("large_settings_text");
        boolean nomad=prefs.getBoolean("nomad_mode",false);
        AlertDialog[] dialog={null};
        boolean canSimulate=(Boolean)call(app,"canSimulate");
        try {
            for(boolean preview:canSimulate?new boolean[]{false,true}:new boolean[]{false})for(boolean big:new boolean[]{false,true})for(int quarter=0;quarter<4;quarter++) {
                final int turn=quarter;
                main(test,() -> {
                    prefs.edit().putBoolean("large_settings_text",big).putBoolean("toolbox_right",turn%2!=0).apply();
                    call(app,"setSimulating",new Class<?>[]{boolean.class},preview);
                    call(app,"requestQuarter",new Class<?>[]{int.class},turn);
                    ToolLibrary tools=(ToolLibrary)get(app,"library");tools.select(ToolSettings.Tool.SHAPES);tools.edit(tools.current().shape(ToolSettings.Shape.CIRCLE));
                });idle(test);
                main(test,() -> dialog[0]=(AlertDialog)call(app,"appSettings"));idle(test);
                View decor=dialog[0].getWindow().getDecorView();SettingsScroller scroller=(SettingsScroller)findType(decor,SettingsScroller.class);
                check(scroller!=null&&scroller.handle.getHeight()==scroller.getHeight(),"Scroll handle fills viewport");
                View simulation=find(decor,"Nomad Simulation Mode"),toolbar=find(decor,"Toolbar");
                if(canSimulate) {
                    check(simulation!=null&&simulation.getParent()==toolbar.getParent(),"Simulation toggle in settings list");
                    ViewGroup rows=(ViewGroup)toolbar.getParent();
                    check(rows.indexOfChild(toolbar)==rows.indexOfChild(simulation)+1,"Simulation toggle immediately precedes Toolbar");
                } else check(simulation==null,"No simulation toggle on a tablet that cannot simulate");
                check(find(decor,"Tools sit opposite your drawing hand. Left-handed landscape tools stay at the top.")==null,"Hand description removed");
                TextView shapes=(TextView)find(decor,"Shapes");
                main(test,() -> {
                    Drawable actual=shapes.getCompoundDrawables()[0],expected=app.getDrawable(R.drawable.ic_shapes);
                    int width=actual.getBounds().width(),height=actual.getBounds().height();
                    Bitmap a=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    try {actual.draw(new Canvas(a));expected.setBounds(0,0,width,height);expected.draw(new Canvas(b));check(a.sameAs(b),"Settings uses combined Shapes icon despite selected Circle");}
                    finally {a.recycle();b.recycle();}
                });
                if(scroller.range()>0) {
                    int originalRange=scroller.range();
                    float edge=28*app.getResources().getDisplayMetrics().density;
                    float track=scroller.handle.getHeight()-2*edge;
                    float thumb=Math.min(track,Math.max(48*app.getResources().getDisplayMetrics().density,track*scroller.scroll.getHeight()/scroller.scroll.getChildAt(0).getHeight()));
                    drag(test,scroller.handle,edge+thumb/2,scroller.handle.getHeight()-edge,turn%2==0);idle(test);
                    check(scroller.scroll.getScrollY()>=originalRange-2,"Handle drag reaches final toolbar rows");
                    check(!scroller.scroll.canScrollVertically(1)&&scroller.scroll.canScrollVertically(-1),"Bottom shows only upward scroll available");
                    drag(test,scroller.handle,scroller.handle.getHeight()-edge-thumb/2,edge,turn%2!=0);idle(test);
                    check(scroller.scroll.getScrollY()==0,"Handle returns to top");
                    tap(test,scroller.handle,scroller.handle.getHeight()-edge/2);idle(test);
                    check(scroller.scroll.getScrollY()>0,"Down arrow scrolls settings");
                    main(test,() -> scroller.scroll.scrollTo(0,0));idle(test);
                } else check(!scroller.scroll.canScrollVertically(1),"No scroll cue needed when everything fits");
                if((preview||!canSimulate)&&big&&turn==0) {
                    Bitmap bitmap=test.getUiAutomation().takeScreenshot();
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getCacheDir(),"settings-scroll.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
                }
                main(test,() -> dialog[0].dismiss());dialog[0]=null;
                report.append("PASS: settings layout/scroll; simulation=").append(preview).append(" large=").append(big).append(" turn=").append(turn).append(".\n");
            }
            // Verify the moved control still changes the saved setting through actual input.
            if(canSimulate) {
                main(test,() -> dialog[0]=(AlertDialog)call(app,"appSettings"));idle(test);
                View control=find(dialog[0].getWindow().getDecorView(),"Nomad Simulation Mode");
                main(test,() -> control.requestRectangleOnScreen(new android.graphics.Rect(0,0,control.getWidth(),control.getHeight()),true));idle(test);
                ToolPickerChecks.tap(test,control);dialog[0]=null;
                check(!prefs.getBoolean("nomad_mode",true),"Moved toggle changes simulation mode");
            }
            screenDrawing(test,app,prefs);
            report.append("PASS: Screen drawing switches between fast e-ink and Standard Android, which leaves the driver alone.\n");
        } finally {
            main(test,() -> {
                if(dialog[0]!=null)dialog[0].dismiss();
                if(hadLarge)prefs.edit().putBoolean("large_settings_text",large).apply();else prefs.edit().remove("large_settings_text").apply();
                call(app,"setSimulating",new Class<?>[]{boolean.class},nomad);
            });
        }
    }
    /** Standard Android drawing must leave no direct session and report no driver; fast e-ink must come back. */
    private static void screenDrawing(Instrumentation test,PaintActivity app,SharedPreferences prefs)throws Exception {
        View[] choice={null};AlertDialog[] dialog={null};boolean original=prefs.getBoolean("android_drawing",false);
        try {
            for(String label:new String[]{"Standard Android drawing","Fast e-ink drawing"}) {
                main(test,() -> dialog[0]=(AlertDialog)call(app,"appSettings"));idle(test);
                View decor=dialog[0].getWindow().getDecorView();choice[0]=find(decor,label);
                check(choice[0]!=null,label+" offered in Settings");
                check(find(decor,"Firmware "+Device.firmware()+": tested on this tablet")!=null
                        ||find(decor,"Firmware "+Device.firmware()+": not yet tested on this tablet. If the screen misbehaves, try Standard Android.")!=null,
                        "Settings names the firmware build");
                main(test,() -> choice[0].requestRectangleOnScreen(new android.graphics.Rect(0,0,choice[0].getWidth(),choice[0].getHeight()),true));idle(test);
                ToolPickerChecks.tap(test,choice[0]);idle(test);
                main(test,() -> {dialog[0].dismiss();dialog[0]=null;});
                Object pad=get(app,"pad");
                for(int i=0;i<40&&(get(pad,"direct")==null)!=label.startsWith("Standard");i++){SystemClock.sleep(50);test.waitForIdleSync();}
                if(label.startsWith("Standard")) {
                    check(prefs.getBoolean("android_drawing",false),"Standard Android drawing is saved");
                    check(DirectEink.androidDrawing()&&DirectEink.layout()[0]==0&&!DirectEink.fastBinaryControls(),"Standard Android reports no e-ink driver");
                    check(get(pad,"direct")==null,"Canvas uses Android drawing");
                } else {
                    check(!prefs.getBoolean("android_drawing",true)&&!DirectEink.androidDrawing(),"Fast e-ink drawing is saved");
                    check(get(pad,"direct")!=null,"Canvas fast path returns");
                }
            }
        } finally {
            main(test,() -> {if(dialog[0]!=null)dialog[0].dismiss();call(app,"setAndroidDrawing",new Class<?>[]{boolean.class},original);});
        }
    }
    private static void drag(Instrumentation test,View view,float from,float to,boolean stylus) {
        long down=SystemClock.uptimeMillis();
        for(int i=0;i<=12;i++)event(test,view,i==0?0:i==12?1:2,down,from+(to-from)*i/12,stylus);
    }
    private static void tap(Instrumentation test,View view,float y) {long down=SystemClock.uptimeMillis();event(test,view,0,down,y,false);event(test,view,1,down,y,false);}
    private static void event(Instrumentation test,View view,int action,long down,float y,boolean stylus) {
        float[] point={view.getWidth()/2f,y};PanelCoordinates.fromView(view).mapPoints(point);
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=stylus?MotionEvent.TOOL_TYPE_STYLUS:MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=point[0];c.y=point[1];c.pressure=1;
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,stylus?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
        try{check(test.getUiAutomation().injectInputEvent(e,true),"Scroll input delivered");}finally{e.recycle();}
    }
    private static View find(View v,String label) {
        if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;
    }
    private static View findType(View v,Class<?> type) {
        if(type.isInstance(v))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findType(((ViewGroup)v).getChildAt(i),type);if(found!=null)return found;}return null;
    }
    private static void idle(Instrumentation test){test.waitForIdleSync();SystemClock.sleep(150);}
    private interface Work{void run()throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception{Throwable[] failure={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);}
    private static Object get(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object call(Object owner,String name)throws Exception{return call(owner,name,new Class<?>[0]);}
    private static Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
