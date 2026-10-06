package io.github.mpdairy.monopaint;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Keep the immediate lock toggle on a single tap; a second quick tap restores 100%. */
final class ZoomButtonTouch implements View.OnTouchListener {
    private final PaintActivity app;
    private final ToolButton button;
    private long previousUp;
    private float downX,downY,previousX,previousY;
    private int previousTool;
    private boolean valid,second,lockAtDown,firstLock;
    ZoomButtonTouch(PaintActivity app,ToolButton button) {this.app=app;this.button=button;}
    @Override public boolean onTouch(View view,MotionEvent e) {
        int action=e.getActionMasked();
        if(app.busy() || !button.isEnabled() || e.getPointerCount()!=1 || action==MotionEvent.ACTION_CANCEL) {
            previousUp=0;valid=false;return false;
        }
        if(action==MotionEvent.ACTION_DOWN) {
            downX=e.getX();downY=e.getY();lockAtDown=app.navigationLocked;valid=true;
            second=previousUp!=0 && e.getEventTime()-previousUp<=ViewConfiguration.getDoubleTapTimeout()
                    && previousTool==e.getToolType(0)
                    && Math.hypot(downX-previousX,downY-previousY)<=app.dp(24);
        }
        if(Math.hypot(e.getX()-downX,e.getY()-downY)>ViewConfiguration.get(app).getScaledTouchSlop())valid=false;
        if(action!=MotionEvent.ACTION_UP)return false;
        if(!valid || e.getEventTime()-e.getDownTime()>250) {previousUp=0;return false;}
        if(!second) {
            previousUp=e.getEventTime();previousX=e.getX();previousY=e.getY();previousTool=e.getToolType(0);firstLock=lockAtDown;
            return false; // The ordinary button click immediately toggles the lock.
        }
        previousUp=0;
        MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
        button.onTouchEvent(cancel);cancel.recycle();button.press.releaseLater();
        if(app.navigationLocked!=firstLock)app.toggleNavigationLock();
        app.pad.actualSize();
        return true;
    }
}
