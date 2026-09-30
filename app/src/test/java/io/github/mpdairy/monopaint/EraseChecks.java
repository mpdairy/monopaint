package io.github.mpdairy.monopaint;

import java.util.Arrays;

final class EraseChecks {
    public static void main(String[] args) {
        ToneDocument doc=layered();
        byte[] original=doc.snapshot();
        ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.AIRBRUSH).size(64).strength(70);
        AirbrushStroke erase=new AirbrushStroke(doc,settings,0,true);
        erase.sampleAt(64.5f,64.5f,.45f,0);
        int first=doc.opacity(64,64);
        for(int t=64;t<=256;t+=32)erase.advance(t);
        check(doc.opacity(64,64)<first,"Held airbrush removes more coverage");
        check(doc.opacity(64,64)<doc.opacity(82,64),"Soft edge retains more coverage");
        erase.finish();byte[] erased=doc.snapshot();
        check(doc.compositeTone(64,64)<160,"Erasing reveals darker lower layer, never white paint");
        check(doc.undo()&&Arrays.equals(original,doc.snapshot()),"Erase undo restores exact composite");
        check(doc.redo()&&Arrays.equals(erased,doc.snapshot()),"Erase redo restores exact composite");
        doc.selectLayer(0);check(doc.tone(64,64)==24&&doc.opacity(64,64)==255,"Lower layer unchanged");
        doc.selectLayer(1);doc.undo();
        doc.begin();
        ToneDabs.pencil(doc,64.5f,64.5f,48,24,.4f,.8f,0,40,true);
        int alpha=doc.opacity(65,64);
        for(int i=0;i<10;i++)ToneDabs.pencil(doc,64.5f,64.5f,48,24,.4f,.8f,0,40,true);
        check(doc.opacity(65,64)==alpha,"Repeated pencil contact keeps strongest contact, not repeated bleaching");
        doc.finish();check(!Arrays.equals(original,doc.snapshot()),"Pencil removes pigment with its grain");
        check(doc.undo()&&Arrays.equals(original,doc.snapshot()),"Pencil undo restores layer");
        ToneDocument empty=new ToneDocument(128,128);
        erase=new AirbrushStroke(empty,settings,255,true);erase.sampleAt(64,64,.45f,0);
        check(!erase.finish()&&empty.opacity(64,64)==0,"Erasing empty layer adds no paint or undo");
        ToneDocument painted=new ToneDocument(128,128),removed=layered();
        AirbrushStroke paint=new AirbrushStroke(painted,settings,0),rub=new AirbrushStroke(removed,settings,255,true);
        for(int t=0;t<=160;t+=16) {paint.sampleAt(32+t*.4f,64.5f,.3f,t);rub.sampleAt(32+t*.4f,64.5f,.3f,t);}
        paint.finish();rub.finish();
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)
            check(Math.abs(255-painted.opacity(x,y)-removed.opacity(x,y))<=1,"Airbrush erase matches paint exposure");
        System.out.println("PASS: brush-mode soft erasing, layer reveal, pencil grain, cumulative exposure, empty layers and exact undo/redo");
    }
    private static ToneDocument layered() {
        byte[] base=new byte[128*128];Arrays.fill(base,(byte)24);
        ToneDocument doc=new ToneDocument(128,128,base);doc.addLayer();doc.begin();
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)doc.paintTone(x,y,160);
        doc.finish();return doc;
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
