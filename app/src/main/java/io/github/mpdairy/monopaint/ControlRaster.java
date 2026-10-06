package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.util.function.Consumer;

/** Same binary control pixels in Android redraws and direct Nomad feedback. */
final class ControlRaster {
    private Bitmap bitmap;
    private Canvas canvas;
    private int[] pixels;

    void draw(Canvas target,int width,int height,Consumer<Canvas> draw) {
        if (!DirectEink.fastBinaryControls() || width==0 || height==0) { draw.accept(target); return; }
        if (bitmap==null || bitmap.getWidth()!=width || bitmap.getHeight()!=height) {
            close();
            bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            canvas=new Canvas(bitmap);pixels=new int[width*height];
        }
        // VectorDrawable caches antialiased pixels internally; a Canvas filter
        // cannot remove those gray edges. Retain their coverage as binary dots.
        canvas.drawColor(Color.WHITE);
        int save=canvas.save();
        try { draw.accept(canvas); } finally { canvas.restoreToCount(save); }
        bitmap.getPixels(pixels,0,width,0,0,width,height);
        for (int y=0,i=0;y<height;y++) for (int x=0;x<width;x++,i++) {
            int pixel=pixels[i];
            if (pixel!=Color.BLACK && pixel!=Color.WHITE) pixels[i]=DotPattern.pixel(Color.red(pixel),x,y);
        }
        bitmap.setPixels(pixels,0,width,0,0,width,height);
        target.drawBitmap(bitmap,0,0,null);
    }
    void close() {
        if(bitmap!=null)bitmap.recycle();
        bitmap=null;canvas=null;pixels=null;
    }
}
