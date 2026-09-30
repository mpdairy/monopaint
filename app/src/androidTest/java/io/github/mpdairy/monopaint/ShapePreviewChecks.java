package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Arrays;

/** Native previews must exactly match the committed raster, including translucent upper layers. */
final class ShapePreviewChecks {
    static void run(StringBuilder report) throws Exception {
        ArrayList<ToneDocument.Layer> layers=new ArrayList<>();int width=137,height=113;
        for(int layer=0;layer<4;layer++) {
            byte[] tones=new byte[width*height],alpha=new byte[tones.length];
            for(int i=0;i<tones.length;i++){tones[i]=(byte)((i*13+layer*70)%256);alpha[i]=(byte)((i+layer)%5==0?0:layer==0?255:91);}
            layers.add(new ToneDocument.Layer("Layer "+layer,layer!=3,tones,alpha));
        }
        ToneDocument base=new ToneDocument(width,height,layers,1);
        float[][] points={{120,105},{90,80},{10,20},{160,-40},{-20,130},{67.25f,54.75f},{103,12}};
        for(ToolSettings.Shape kind:ToolSettings.Shape.values())for(boolean filled:new boolean[]{false,true})
                for(boolean logical:new boolean[]{false,true})for(int shade:new int[]{0,70,255}) {
            ToneDocument doc=copy(base);
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(kind).filled(filled).outlineWidth(7);
            Bitmap display=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {
                int[] pixels=pixels(doc,logical);display.setPixels(pixels,0,width,0,0,width,height);
                ShapePreview preview=new ShapePreview(doc,settings,shade,67.25f,54.75f);
                byte[] original=DrawingBook.encode(doc);
                for(float[] point:points) {
                    preview.preview(point[0],point[1],display,logical);
                    ToneDocument reference=copy(base);
                    ShapeStroke shape=new ShapeStroke(reference,settings,shade,67.25f,54.75f);
                    shape.preview(point[0],point[1]);shape.finish();
                    display.getPixels(pixels,0,width,0,0,width,height);
                    if(!Arrays.equals(pixels,pixels(reference,logical)))throw new AssertionError("Native preview differs: "+kind+" filled="+filled+" logical="+logical+" shade="+shade);
                    if(!Arrays.equals(original,DrawingBook.encode(doc)))throw new AssertionError("Preview changed drawing");
                }
                preview.cancel(display,logical);display.getPixels(pixels,0,width,0,0,width,height);
                if(!Arrays.equals(pixels,pixels(base,logical)))throw new AssertionError("Cancel did not restore original display");
                preview=new ShapePreview(doc,settings,shade,67.25f,54.75f);preview.preview(120,100,display,logical);preview.finish();
                display.getPixels(pixels,0,width,0,0,width,height);
                if(!Arrays.equals(pixels,pixels(doc,logical)))throw new AssertionError("Committed raster differs from preview");
                if(!doc.undo() || !Arrays.equals(original,DrawingBook.encode(doc)))throw new AssertionError("Commit must undo exactly once");
            } finally {display.recycle();}
        }
        report.append("PASS: Native preview/commit/cancel matches all five shapes, both modes, black/gray/white, raw/dithered rasters, clipped reversals and hidden/translucent upper layers; document unchanged while held.\n");
    }
    private static ToneDocument copy(ToneDocument source) {
        ToneDocument.Snapshot snapshot=source.layerSnapshot();ArrayList<ToneDocument.Layer> layers=new ArrayList<>();
        for(ToneDocument.Layer layer:snapshot.layers)layers.add(new ToneDocument.Layer(layer.name,layer.visible,layer.tones.clone(),layer.alpha.clone()));
        return new ToneDocument(source.width,source.height,layers,snapshot.active);
    }
    private static int[] pixels(ToneDocument doc,boolean logical) {
        int[] pixels=new int[doc.width*doc.height];
        for(int y=0;y<doc.height;y++)for(int x=0;x<doc.width;x++) {
            int tone=doc.compositeTone(x,y);pixels[y*doc.width+x]=logical?Color.rgb(tone,tone,tone):DotPattern.pixel(tone,x,y);
        }
        return pixels;
    }
}
