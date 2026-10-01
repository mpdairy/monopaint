package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.OrientationEventListener;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Target-orientation corners, timeout, shake recall and real prompt touch targets. */
final class RotationPromptChecks {
    static void run(Instrumentation test,PaintActivity app,StringBuilder report)throws Exception {
        OrientationEventListener sensor=(OrientationEventListener)get(app,"orientationSensor");
        SharedPreferences prefs=(SharedPreferences)get(app,"preferences");
        View hint=(View)get(app,"rotateButton"),frame=(View)get(app,"orientationFrame"),shade=(View)get(app,"shadePicker");
        RotationSuggestion suggestion=(RotationSuggestion)get(app,"rotationSuggestion");
        int size=Math.round(48*app.getResources().getDisplayMetrics().density),inset=Math.round(8*app.getResources().getDisplayMetrics().density);
        for(boolean nomad:new boolean[]{false,true})for(int target=0;target<4;target++)for(boolean right:new boolean[]{false,true}) {
            final int quarter=target,current=(target+3)%4;
            main(test,() -> {
                call(app,"setNomadMode",new Class<?>[]{boolean.class},nomad);
                prefs.edit().putBoolean("toolbox_right",right).apply();
                call(app,"requestQuarter",new Class<?>[]{int.class},current);
                call(app,"hideRotationSuggestion");
            });idle(test);int shadeWidth=shade.getWidth();
            main(test,() -> {
                suggestion.update(quarter*90,current,SystemClock.uptimeMillis()-RotationSuggestion.HOLD_MS);
                sensor.onOrientationChanged(quarter*90);
                check(hint.getVisibility()==View.VISIBLE,"Stable target shows prompt");
                check((Integer)get(app,"appRotation")==((4-current)%4),"Prompt does not turn app itself");
            });idle(test);
            RectF bounds=bounds(hint),area=bounds(frame);
            RectF menu=bounds((View)get(app,"menuButton"));
            float x=menu.centerX()<area.centerX()?area.right-inset-size:area.left+inset;
            float y=menu.centerY()<area.centerY()?area.bottom-inset-size:area.top+inset;
            check(Math.abs(bounds.left-x)<1&&Math.abs(bounds.top-y)<1,"Prompt nestles in the corner diagonally opposite the hamburger: "+target+" "+bounds);
            check(!RectF.intersects(bounds,menu),"Rotation prompt stays clear of the hamburger");
            check(shade.getWidth()==shadeWidth,"Prompt never steals color bar space");
            if(nomad&&target==1&&!right) {
                screenshot(test,app,"rotation-new-landscape.png");
                SystemClock.sleep(RotationSuggestion.SHOW_MS+100);idle(test);
                check(hint.getVisibility()==View.INVISIBLE,"Prompt disappears automatically");
                main(test,() -> sensor.onOrientationChanged(quarter*90));idle(test);
                check(hint.getVisibility()==View.INVISIBLE,"Steady orientation stays dismissed");
                main(test,() -> call(app,"shakeRotationSuggestion"));idle(test);
                check(hint.getVisibility()==View.VISIBLE,"Shake recalls current physical rotation");
            }
            SelectionFeedback feedback=(SelectionFeedback)get(app,"selectionFeedback");
            int submitted=feedback.submitted,previousRotation=(Integer)get(app,"appRotation");
            RectF touch=bounds(hint);long down=SystemClock.uptimeMillis();
            touch(test,MotionEvent.ACTION_DOWN,down,touch);
            main(test,() -> {
                check((Boolean)get(hint,"feedbackPressed"),"Rotation button immediately shows pressed outline: nomad="+nomad+" target="+quarter+" right="+right+" visibility="+hint.getVisibility());
                check(feedback.submitted>submitted,"Pressed outline submitted through fast path");
                check((Integer)get(app,"appRotation")==previousRotation,"Press feedback precedes orientation change");
            });
            touch(test,MotionEvent.ACTION_UP,down,touch);idle(test);
            check((Integer)get(app,"appRotation")==((4-target)%4),"Actual target-corner touch applies rotation");
            check(hint.getVisibility()==View.INVISIBLE,"Applied rotation dismisses prompt");
            check(!(Boolean)get(hint,"feedbackPressed"),"Hidden rotation prompt retains no pressed state");
            check(get(get(app,"pad"),"direct")!=null,"Canvas fast path restored after rotation");
        }
        report.append("PASS: rotation prompt occupies the corner diagonally opposite the hamburger in both modes/all directions/both hands; five-second expiry, shake recall, fast pressed outline before rotation, real tap rotation and cleared press state.\n");
    }
    private static void touch(Instrumentation test,int action,long down,RectF rect) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.centerX(),rect.centerY(),0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{check(test.getUiAutomation().injectInputEvent(event,true),"Prompt tapped");}finally{event.recycle();}
    }
    private static RectF bounds(View view){RectF rect=new RectF(0,0,view.getWidth(),view.getHeight());PanelCoordinates.fromView(view).mapRect(rect);return rect;}
    private static void idle(Instrumentation test){test.waitForIdleSync();SystemClock.sleep(180);}
    private static void screenshot(Instrumentation test,PaintActivity app,String name)throws Exception {
        android.graphics.Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getCacheDir(),name))) {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        finally{bitmap.recycle();}
    }
    private interface Work{void run()throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception {Throwable[] error={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    private static Object get(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name)throws Exception{return call(owner,name,new Class<?>[0]);}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
