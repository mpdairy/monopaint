package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

/** Scale logical tones first, then dither at screen resolution so zoom cannot alias shades. */
final class ViewportBitmap {
    static { System.loadLibrary("monopaint_display"); }
    private static final int[] DOTS=new int[256*64];
    static {
        for(int tone=0;tone<256;tone++) for(int y=0;y<8;y++) for(int x=0;x<8;x++)
            DOTS[tone*64+y*8+x]=DotPattern.pixel(tone,x,y);
    }
    private static native void nativeRender(Bitmap source,Bitmap target,float[] inverse,int[] dots,
                                             int left,int top,int right,int bottom);
    private static native void nativeCompose(Bitmap target,byte[][] tones,byte[][] alpha,int[] opacity,int[] dots);
    /** One logical composite when navigation starts; never derive shades from display dots. */
    static void compose(ToneDocument document,Bitmap target) {
        compose(document,target,false);
    }
    static void compose(ToneDocument document,Bitmap target,boolean dither) {
        ToneDocument.Snapshot snapshot=document.layerSnapshot();
        java.util.ArrayList<byte[]> tones=new java.util.ArrayList<>(),alpha=new java.util.ArrayList<>();
        int[] opacity=new int[snapshot.layers.size()];
        for(ToneDocument.Layer layer:snapshot.layers)if(layer.visible) {
            opacity[tones.size()]=layer.opacity;
            tones.add(layer.tones);alpha.add(layer.alpha);
        }
        nativeCompose(target,tones.toArray(new byte[0][]),alpha.toArray(new byte[0][]),java.util.Arrays.copyOf(opacity,tones.size()),dither?DOTS:null);
    }
    final Bitmap bitmap;
    private final Matrix inverse=new Matrix();
    private final float[] values=new float[9];
    ViewportBitmap(int width,int height) {
        bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        bitmap.setHasAlpha(false);
    }
    Rect update(Bitmap tones, Matrix pageToView, Rect dirty) {
        Rect area;
        if(dirty==null) area=new Rect(0,0,bitmap.getWidth(),bitmap.getHeight());
        else {
            RectF mapped=new RectF(dirty); pageToView.mapRect(mapped);
            area=new Rect(); mapped.roundOut(area); area.inset(-1,-1);
            if(!area.intersect(0,0,bitmap.getWidth(),bitmap.getHeight())) return new Rect();
        }
        if(!pageToView.invert(inverse)) throw new IllegalStateException("Invalid zoom transform");
        inverse.getValues(values);
        nativeRender(tones,bitmap,values,DOTS,area.left,area.top,area.right,area.bottom);
        return area;
    }
    void close() { bitmap.recycle(); }
}
