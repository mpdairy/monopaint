package io.github.mpdairy.monopaint;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Surface;
import android.view.View;

/** Maps Android view pixels to the e-ink panel's fixed, natural-orientation coordinates. */
final class PanelCoordinates {
    private PanelCoordinates() { }

    static Matrix fromView(View owner) {
        Matrix result = new Matrix();
        View child = owner;
        while (child.getParent() instanceof View) {
            result.postConcat(child.getMatrix());
            View parent = (View)child.getParent();
            result.postTranslate(child.getLeft()-parent.getScrollX(),child.getTop()-parent.getScrollY());
            child = parent;
        }
        int[] origin = new int[2]; child.getLocationOnScreen(origin);
        result.postTranslate(origin[0],origin[1]);
        Point size = new Point(); owner.getDisplay().getRealSize(size);
        result.postConcat(fromScreen(owner.getDisplay().getRotation(),size.x,size.y));
        return result;
    }

    static Matrix fromScreen(int rotation,int width,int height) {
        Matrix result = new Matrix();
        if (rotation == Surface.ROTATION_90) { result.setRotate(90); result.postTranslate(height,0); }
        else if (rotation == Surface.ROTATION_180) { result.setRotate(180); result.postTranslate(width,height); }
        else if (rotation == Surface.ROTATION_270) { result.setRotate(-90); result.postTranslate(0,width); }
        return result;
    }
    static boolean fullyVisible(View owner,Rect local) {
        if (local.isEmpty() || local.left<0 || local.top<0 || local.right>owner.getWidth() || local.bottom>owner.getHeight()) return false;
        Rect global=new Rect();
        if (!owner.getGlobalVisibleRect(global)) return false;
        int[] rootOrigin=new int[2]; owner.getRootView().getLocationOnScreen(rootOrigin); global.offset(rootOrigin[0],rootOrigin[1]);
        Point size=new Point(); owner.getDisplay().getRealSize(size);
        RectF visible=new RectF(global); fromScreen(owner.getDisplay().getRotation(),size.x,size.y).mapRect(visible);
        RectF wanted=new RectF(local); fromView(owner).mapRect(wanted);
        return visible.contains(wanted);
    }
}
