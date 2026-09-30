package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Display-only preview. The document and its undo history stay untouched until pen-up. */
final class ShapePreview {
    static { System.loadLibrary("monopaint_display"); }
    private static final int[] DOTS=new int[256*64];
    static {
        for(int tone=0;tone<256;tone++)for(int y=0;y<8;y++)for(int x=0;x<8;x++)
            DOTS[tone*64+y*8+x]=DotPattern.pixel(tone,x,y);
    }
    private static native void nativeApply(Bitmap display,byte[][] tones,byte[][] alpha,int[] visible,
            int active,int shade,int[] previous,int[] next,int[] dots,boolean logical,int[] dirty);
    private final ToneDocument document;
    private final ShapeGeometry geometry;
    private final byte[][] tones,alpha;
    private final int[] visible;
    private final int active,shade;
    private int[] previous,next;
    private final int[] dirty=new int[4];
    private boolean ended;
    ShapePreview(ToneDocument document,ToolSettings settings,int shade,float x,float y) {
        this.document=document;this.shade=shade;
        geometry=new ShapeGeometry(settings,document.width,document.height,x,y);
        ToneDocument.Snapshot source=document.layerSnapshot();active=source.active;
        tones=new byte[source.layers.size()][];alpha=new byte[tones.length][];visible=new int[tones.length];
        for(int i=0;i<tones.length;i++) {
            ToneDocument.Layer layer=source.layers.get(i);tones[i]=layer.tones;alpha[i]=layer.alpha;visible[i]=layer.visible?1:0;
        }
        previous=new int[document.height*4];next=new int[previous.length];
    }
    Rect preview(float x,float y,Bitmap display,boolean logical) {
        if(ended || !Float.isFinite(x) || !Float.isFinite(y))return new Rect();
        geometry.raster(x,y,next);
        return apply(display,logical);
    }
    private Rect apply(Bitmap display,boolean logical) {
        nativeApply(display,tones,alpha,visible,active,shade,previous,next,DOTS,logical,dirty);
        int[] swap=previous;previous=next;next=swap;
        return new Rect(dirty[0],dirty[1],dirty[2],dirty[3]);
    }
    Rect cancel(Bitmap display,boolean logical) {
        if(ended)return new Rect();ended=true;java.util.Arrays.fill(next,0);
        return apply(display,logical);
    }
    boolean finish() {
        if(ended)return false;ended=true;
        document.begin();
        for(int y=0;y<document.height;y++)for(int part=0;part<4;part+=2) {
            int at=y*4+part;document.paintSpan(previous[at],previous[at+1],y,shade);
        }
        return document.finish();
    }
}
