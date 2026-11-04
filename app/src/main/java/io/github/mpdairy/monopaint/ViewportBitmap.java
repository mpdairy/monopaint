package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

/** Scale logical tones first, then dither at screen resolution so zoom cannot alias shades. */
final class ViewportBitmap {
    static { System.loadLibrary("monopaint_display"); }
    /** Each tone's 8 × 8 dot pattern, as the canvas shows it unzoomed. */
    static final int[] DOTS=new int[256*64];
    static {
        for(int tone=0;tone<256;tone++) for(int y=0;y<8;y++) for(int x=0;x<8;x++)
            DOTS[tone*64+y*8+x]=DotPattern.pixel(tone,x,y);
    }
    /** Tones with at least this many white dots of 64 count as paper while moving. */
    private static final int MOVING_PAPER_WHITE_DOTS=60;
    /** White dots of 64 in the moving background, the same gray as Mosaic's pan and zoom. */
    private static final int MOVING_GRAY_WHITE_DOTS=56;
    /** Pinch and pan: solid black ink on a dotted gray, only black and white so A2 can show it. */
    static final int[] MOVING_DOTS=new int[256*64];
    static {
        for(int tone=0;tone<256;tone++) for(int y=0;y<8;y++) for(int x=0;x<8;x++) {
            boolean ink=DotPattern.whiteCount(tone)<MOVING_PAPER_WHITE_DOTS;
            MOVING_DOTS[tone*64+y*8+x]=!ink && movingGrayRank(x,y)<MOVING_GRAY_WHITE_DOTS?0xffffffff:0xff000000;
        }
    }
    private static int movingGrayRank(int x,int y) {
        int rank=0;
        for(int bit=0;bit<3;bit++) {
            int xBit=x>>bit&1,yBit=y>>bit&1;
            rank|=((xBit^yBit)*2+yBit)<<(2*(2-bit));
        }
        return rank;
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
    /** The page as a screen of that size would show it: smoothly scaled tones, then its dots. */
    static Bitmap dithered(ToneDocument document,int width,int height) {
        Bitmap page=Bitmap.createBitmap(document.width,document.height,Bitmap.Config.ARGB_8888);
        boolean same=width==document.width && height==document.height;
        compose(document,page,same);
        if(same) return page;
        Bitmap scaled=Bitmap.createScaledBitmap(page,width,height,true);
        page.recycle();
        ViewportBitmap screen=new ViewportBitmap(width,height);
        screen.update(scaled,new Matrix(),null);
        scaled.recycle();
        return screen.bitmap;
    }
    final Bitmap bitmap;
    /** Draws with {@link #MOVING_DOTS} instead of the calibrated shades. */
    boolean moving;
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
        nativeRender(tones,bitmap,values,moving?MOVING_DOTS:DOTS,area.left,area.top,area.right,area.bottom);
        return area;
    }
    void close() { bitmap.recycle(); }
}
