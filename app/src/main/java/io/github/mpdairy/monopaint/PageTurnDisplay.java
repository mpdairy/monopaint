package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** A one-shot page update. Preserve the previous pixels and never paint over the Pages panel. */
final class PageTurnDisplay implements AutoCloseable {
    private final DirectEink display;
    private final Rect bounds, covered;

    PageTurnDisplay(View owner,Bitmap before,Matrix bitmapToView,View overlay) {
        bounds=new Rect(0,0,before.getWidth(),before.getHeight());
        covered=new Rect();
        if(overlay!=null) {
            Matrix toScreen=new Matrix(bitmapToView);
            toScreen.postConcat(PanelCoordinates.fromView(owner));
            Matrix fromScreen=new Matrix();
            if(!toScreen.invert(fromScreen))throw new IllegalStateException("Invalid page transform");
            RectF area=new RectF(0,0,overlay.getWidth(),overlay.getHeight());
            PanelCoordinates.fromView(overlay).mapRect(area);fromScreen.mapRect(area);area.roundOut(covered);
            if(!covered.intersect(bounds))covered.setEmpty();
        }
        display=DirectEink.forView(owner,before,bitmapToView,0,7);
    }
    boolean present(Bitmap after) {
        if(covered.isEmpty())return display.present(after,bounds)>=0;
        // Each region is disjoint; a busy submission falls back to an Android
        // redraw of the latest page instead of ever retrying a stale page.
        boolean okay=present(after,0,0,bounds.right,covered.top);
        okay=present(after,0,covered.bottom,bounds.right,bounds.bottom)&&okay;
        okay=present(after,0,covered.top,covered.left,covered.bottom)&&okay;
        return present(after,covered.right,covered.top,bounds.right,covered.bottom)&&okay;
    }
    private boolean present(Bitmap bitmap,int left,int top,int right,int bottom) {
        return right<=left || bottom<=top || display.present(bitmap,new Rect(left,top,right,bottom))>=0;
    }
    @Override public void close() { display.close(); }
}
