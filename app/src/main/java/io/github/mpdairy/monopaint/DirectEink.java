package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** Manta-only region presenter, using ordinary app permissions. */
final class DirectEink {
    static { System.loadLibrary("monopaint_display"); }
    static native String probe();
    private static native long nativeOpen(Bitmap background, int x, int y, int requestFlags, int displayMode);
    private static native int nativePresent(long handle, Bitmap bitmap, int left, int top,
                                            int right, int bottom, int x, int y);
    private static native void nativeClose(long handle);
    private static native int nativeReadGray(long handle, int x, int y);
    private static native void nativeRotate(Bitmap source,Bitmap target,int quarter,int left,int top,int right,int bottom);
    private long handle;
    private final int x, y;
    private Matrix sourceToBuffer;
    private Bitmap buffer;
    private Canvas bufferCanvas;
    private final RectF mapped = new RectF();
    private final Rect bufferDirty = new Rect();
    private int sourceWidth, sourceHeight;
    private int bufferTurn=-1;
    DirectEink(int x, int y, Bitmap background, int requestFlags, int displayMode) {
        this.x=x; this.y=y;
        handle=nativeOpen(background,x,y,requestFlags,displayMode);
    }
    static DirectEink forView(View owner, Bitmap background, Matrix bitmapToView, int requestFlags, int displayMode) {
        Matrix toPanel = new Matrix(bitmapToView);
        toPanel.postConcat(PanelCoordinates.fromView(owner));
        return new DirectEink(background,toPanel,requestFlags,displayMode);
    }
    private DirectEink(Bitmap background, Matrix toPanel, int requestFlags, int displayMode) {
        float[] values = new float[9]; toPanel.getValues(values);
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalStateException("Invalid panel transform");
        // A rotated control must still own a rectangular patch, never surrounding UI pixels.
        if (!toPanel.rectStaysRect()) throw new IllegalStateException("Non-rectangular panel transform");
        RectF bounds = new RectF(0,0,background.getWidth(),background.getHeight());
        toPanel.mapRect(bounds);
        Rect pixels = new Rect(); bounds.roundOut(pixels);
        if (pixels.isEmpty() || pixels.width()>1920 || pixels.height()>2560)
            throw new IllegalStateException("Panel patch outside supported display");
        x=pixels.left; y=pixels.top;
        sourceWidth=background.getWidth(); sourceHeight=background.getHeight();
        sourceToBuffer=new Matrix(toPanel); sourceToBuffer.postTranslate(-x,-y);
        // Page and panel orientation cancel. Normal landscape pages take this zero-copy path.
        if (sourceToBuffer.isIdentity()) {
            sourceToBuffer=null;
            handle=nativeOpen(background,x,y,requestFlags,displayMode);
            return;
        }
        buffer=Bitmap.createBitmap(pixels.width(),pixels.height(),Bitmap.Config.ARGB_8888);
        bufferCanvas=new Canvas(buffer);
        bufferCanvas.drawColor(android.graphics.Color.WHITE);
        bufferTurn=quarterTurn(sourceToBuffer,sourceWidth,sourceHeight);
        copyToBuffer(background,new Rect(0,0,buffer.getWidth(),buffer.getHeight()));
        try { handle=nativeOpen(buffer,x,y,requestFlags,displayMode); }
        catch (RuntimeException | LinkageError error) { buffer.recycle(); throw error; }
    }
    synchronized int present(Bitmap bitmap, Rect dirty) {
        if (handle==0) return -1;
        if (buffer != null) {
            if (bitmap.getWidth()!=sourceWidth || bitmap.getHeight()!=sourceHeight)
                throw new IllegalStateException("Display source dimensions changed");
            mapped.set(dirty); sourceToBuffer.mapRect(mapped); mapped.roundOut(bufferDirty);
            if (!bufferDirty.intersect(0,0,buffer.getWidth(),buffer.getHeight())) return 0;
            copyToBuffer(bitmap,bufferDirty);
            return nativePresent(handle,buffer,bufferDirty.left,bufferDirty.top,bufferDirty.right,bufferDirty.bottom,x,y);
        }
        return nativePresent(handle,bitmap,dirty.left,dirty.top,dirty.right,dirty.bottom,x,y);
    }
    private void copyToBuffer(Bitmap source,Rect dirty) {
        // Control captures can have transparent corners; retain Canvas's blending
        // for those. Document/viewport rasters explicitly contain opaque pixels.
        if(bufferTurn>=0 && !source.hasAlpha())nativeRotate(source,buffer,bufferTurn,dirty.left,dirty.top,dirty.right,dirty.bottom);
        else {
            int save=bufferCanvas.save();bufferCanvas.clipRect(dirty);
            bufferCanvas.drawBitmap(source,sourceToBuffer,null);bufferCanvas.restoreToCount(save);
        }
    }
    private static int quarterTurn(Matrix transform,int width,int height) {
        float[] actual=new float[9],expected=new float[9];transform.getValues(actual);
        for(int turn=0;turn<4;turn++) {
            Matrix rotation=new Matrix();rotation.setRotate(turn*90);
            if(turn==1)rotation.postTranslate(height,0);
            else if(turn==2)rotation.postTranslate(width,height);
            else if(turn==3)rotation.postTranslate(0,width);
            rotation.getValues(expected);boolean matches=true;
            for(int i=0;i<9;i++)if(Math.abs(actual[i]-expected[i])>.00001f)matches=false;
            if(matches)return turn;
        }
        return -1;
    }
    synchronized void close() {
        if (handle==0) return;
        nativeClose(handle); handle=0;
        if (buffer != null) { buffer.recycle(); buffer=null; bufferCanvas=null; }
    }
    synchronized int readGray(int localX, int localY) {
        if (sourceToBuffer != null) {
            float[] point={localX+.5f,localY+.5f}; sourceToBuffer.mapPoints(point);
            return nativeReadGray(handle,x+(int)Math.floor(point[0]),y+(int)Math.floor(point[1]));
        }
        return nativeReadGray(handle,x+localX,y+localY);
    }
    Rect panelRegion(Rect source) {
        RectF area=new RectF(source);
        if (sourceToBuffer!=null) sourceToBuffer.mapRect(area);
        Rect result=new Rect(); area.roundOut(result); result.offset(x,y); return result;
    }
}
