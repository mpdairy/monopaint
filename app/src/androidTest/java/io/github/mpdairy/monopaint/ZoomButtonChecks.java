package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.Arrays;

/** Called inside ZoomUiChecks' disposable drawing and restoration boundary. */
final class ZoomButtonChecks {
    static void verify(Instrumentation test,PaintActivity app)throws Exception {
        byte[] artwork=DrawingBook.encode(app.pad.document);
        ToolButton button=app.toolbar.zoomButton;
        for(boolean locked:new boolean[]{false,true})for(float scale:new float[]{.65f,2.5f}) {
            test.runOnMainSync(() -> {
                app.pad.endNavigation();app.pad.viewport.hold(scale,-120,-160);app.pad.canvasResized();
                app.navigationLocked=locked;app.prefs.setNavigationLocked(locked);app.toolbar.describeNavigation();
                button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);
            });idle(test,360);
            int tool=locked?MotionEvent.TOOL_TYPE_STYLUS:MotionEvent.TOOL_TYPE_FINGER;
            tap(test,button,tool);idle(test,30);
            check(app.navigationLocked!=locked,"Single Zoom tap still toggles lock immediately");
            check(Math.abs(app.pad.viewport.scale()-scale)<.001,"Single Zoom tap does not change scale");
            tap(test,button,tool);idle(test,180);
            check(app.pad.viewport.percent()==100 && Math.abs(app.pad.viewport.scale()-1)<.001,"Double Zoom tap sets actual 100%, including from below fit");
            check(app.navigationLocked==locked && app.prefs.navigationLocked()==locked,"Double Zoom tap preserves original lock state");
            check(button.getContentDescription().toString().contains("100%"),"Zoom label reports 100%");
            check(Arrays.equals(artwork,DrawingBook.encode(app.pad.document)),"Zoom reset never edits artwork");
        }
    }
    private static void tap(Instrumentation test,ToolButton button,int tool) {
        test.runOnMainSync(() -> {
            long down=SystemClock.uptimeMillis();
            MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=tool;
            MotionEvent.PointerCoords p=new MotionEvent.PointerCoords();p.x=button.getWidth()/2f;p.y=button.getHeight()/2f;p.pressure=1;
            MotionEvent e=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{p},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
            button.dispatchTouchEvent(e);e.setAction(MotionEvent.ACTION_UP);button.dispatchTouchEvent(e);e.recycle();
        });
    }
    private static void idle(Instrumentation test,int delay){test.waitForIdleSync();SystemClock.sleep(delay);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
