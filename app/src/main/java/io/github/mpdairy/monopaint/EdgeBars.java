package io.github.mpdairy.monopaint;

import android.graphics.Matrix;
import android.graphics.Point;
import android.view.MotionEvent;

/** One-finger edge toggles work in every bar layout. */
final class EdgeBars {
    final PaintActivity app;
    private final EdgeSwipe[] swipes={new EdgeSwipe(),new EdgeSwipe()};
    private boolean tracking,swallowing;
    EdgeBars(PaintActivity app) {this.app=app;}
    int toolEdge() {return app.landscape?EdgeSwipe.TOP:app.toolboxRight?EdgeSwipe.RIGHT:EdgeSwipe.LEFT;}
    int colorEdge() {return !app.landscape?EdgeSwipe.TOP:app.toolboxRight?EdgeSwipe.RIGHT:EdgeSwipe.LEFT;}
    private int edge(int bar) {return bar==0?toolEdge():colorEdge();}
    boolean touch(MotionEvent e) {
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN)swallowing=false;
        if(swallowing) {
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)reset();
            return true;
        }
        if(app.pad==null || app.busy() || app.fullscreen.dialog!=null) {reset();return false;}
        boolean finger=e.getPointerCount()==1 && e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER;
        if(!finger || app.pad.blocksFullscreenGesture()) {reset();return false;}
        Matrix inverse=new Matrix();PanelCoordinates.fromView(app.root).invert(inverse);
        float[] p={e.getRawX(),e.getRawY()};inverse.mapPoints(p);
        if(action==MotionEvent.ACTION_DOWN) {
            reset();
            // Starting on controls must retain their ordinary tap/drag behavior.
            if(!Popups.bounds(app.root,app.pad).contains(p[0],p[1]))return false;
            tracking=true;
            for(int bar=0;bar<2;bar++)swipes[bar].start(edge(bar),app.root.getWidth(),app.root.getHeight(),
                    p[0],p[1],e.getEventTime(),app.dp(app.device.edgeSwipeSlopDp),app.dp(32),app.dp(EdgeSwipe.EXIT_BAND_DP));
        }
        if(!tracking)return false;
        // A real fingertip has width; injected test pointers often have none.
        // Keep the last useful contact size when it collapses at lift-off, capped
        // so an oversized/palm report never turns an interior swipe into an edge swipe.
        contact(e.getTouchMajor(),e.getTouchMinor());
        // Android batches fast motion. Inspect every sample, not only the final UP coordinate.
        for(int i=0;i<e.getHistorySize();i++) {
            float[] h={e.getHistoricalX(i)+e.getRawX()-e.getX(),e.getHistoricalY(i)+e.getRawY()-e.getY()};
            inverse.mapPoints(h);
            contact(e.getHistoricalTouchMajor(i),e.getHistoricalTouchMinor(i));
            for(int bar=0;bar<2;bar++)if(swipes[bar].event(MotionEvent.ACTION_MOVE,e.getHistoricalEventTime(i),1,true,h[0],h[1])
                    && physicalEdge(edge(bar),h[0],h[1]))return toggle(bar,e);
        }
        for(int bar=0;bar<2;bar++) {
            if(swipes[bar].event(action,e.getEventTime(),1,true,p[0],p[1]) && physicalEdge(edge(bar),p[0],p[1])) {
                return toggle(bar,e);
            }
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)reset();
        return false;
    }
    private boolean toggle(int bar,MotionEvent e) {
        MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
        app.dispatchCancelledTouch(cancel);cancel.recycle();reset();
        app.fullscreen.toggleBar(bar);
        swallowing=e.getActionMasked()!=MotionEvent.ACTION_UP && e.getActionMasked()!=MotionEvent.ACTION_CANCEL;
        return true;
    }
    void reset() {for(EdgeSwipe swipe:swipes)swipe.reset();tracking=swallowing=false;}
    private void contact(float major,float minor) {
        float diameter=minor>0?Math.min(major,minor):major;
        float radius=Math.min(app.dp(18),Math.max(0,diameter/2));
        for(EdgeSwipe swipe:swipes)swipe.contact(radius);
    }
    private boolean physicalEdge(int edge,float x,float y) {
        float w=app.root.getWidth(),h=app.root.getHeight(),corner=app.dp(28),s=app.dp(3);
        if(edge==EdgeSwipe.LEFT || edge==EdgeSwipe.RIGHT) {
            if(y<=corner || y>=h-corner)return false;
            x=edge==EdgeSwipe.LEFT?0:w;
        } else {
            if(x<=corner || x>=w-corner)return false;
            y=edge==EdgeSwipe.TOP?0:h;
        }
        // A simulated screen's interior boundary is never an edge gesture destination.
        Point screen=new Point();app.getWindowManager().getDefaultDisplay().getRealSize(screen);
        float[] p={x,y};PanelCoordinates.fromView(app.root).mapPoints(p);
        return p[0]<=s || p[1]<=s || p[0]>=screen.x-s || p[1]>=screen.y-s;
    }
}
