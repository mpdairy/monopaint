package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class GradientFillChecks {
    public static void main(String[] args) {
        for(ToolSettings.Gradient type:ToolSettings.Gradient.values()) {
        for(int[] axis:new int[][]{{2,3,8,3},{3,2,3,8},{2,2,8,8},{8,8,2,2},{5,5,5,5}}) {
            ToneDocument doc=new ToneDocument(12,12);
            FloodFill fill=new FloodFill(doc,axis[0],axis[1],20,0,axis[0],axis[1],axis[2],axis[3],220,type);
            complete(fill);
            for(int y=0;y<12;y++) for(int x=0;x<12;x++) {
                double dx=axis[2]-axis[0],dy=axis[3]-axis[1],length=dx*dx+dy*dy;
                double t=length==0?0:Math.max(0,Math.min(1,type==ToolSettings.Gradient.CIRCULAR
                        ? Math.hypot(x-axis[0],y-axis[1])/Math.sqrt(length) : ((x-axis[0])*dx+(y-axis[1])*dy)/length));
                check(doc.tone(x,y)==Math.round(20+200*t),"Projected or radial clamped shade");
            }
            fill.secondShade(80); complete(fill); fill.secondShade(240); complete(fill);
            byte[] finalPixels=doc.snapshot(); check(fill.finish(),"Gradient changes pixels");
            check(doc.undo()&&!doc.canUndo(),"One undo for all previews");
            check(doc.opacity(5,5)==0,"Undo restores transparency");
            check(doc.redo()&&Arrays.equals(finalPixels,doc.snapshot()),"Exact redo");
        }
        ToneDocument bounded=new ToneDocument(9,5);
        bounded.begin();
        for(int y=0;y<5;y++) bounded.paintTone(4,y,0);
        bounded.finish(); bounded.addLayer();
        bounded.begin();
        for(int y=0;y<5;y++) for(int x=0;x<9;x++) bounded.paintTone(x,y,x==4?100:200);
        bounded.finish(); byte[] original=bounded.snapshot();
        FloodFill fill=new FloodFill(bounded,1,2,0,0,1,2,3,2,100,type);
        complete(fill); fill.secondShade(200); complete(fill);
        check(bounded.tone(4,2)==100&&bounded.tone(7,2)==200,"Preview never crosses original boundary");
        fill.cancel(); check(Arrays.equals(original,bounded.snapshot()),"Cancel restores original");
        bounded.selectLayer(0);check(bounded.tone(4,2)==0&&bounded.opacity(1,2)==0,"Other layers untouched");

        byte[] ramp={(byte)100,(byte)110,(byte)120,(byte)130,(byte)140};
        ToneDocument tolerance=new ToneDocument(5,1,ramp);
        fill=new FloodFill(tolerance,0,0,255,8,0,0,4,0,0,type); complete(fill); fill.finish();
        check(tolerance.tone(2,0)==128&&tolerance.tone(3,0)==130,"Tolerance stays relative to seed");

        ToneDocument interrupted=new ToneDocument(300,400);
        interrupted.begin();interrupted.paintTone(0,0,75);interrupted.finish();interrupted.undo();
        fill=new FloodFill(interrupted,2,2,0,0,2,2,250,250,255,type);
        fill.advance(1);fill.secondShade(120);fill.advance(1);fill.cancel();
        check(interrupted.opacity(2,2)==0&&interrupted.canRedo(),"Interrupted preview restores alpha and redo");
        fill=new FloodFill(interrupted,2,2,255,0,2,2,250,250,255,type);
        complete(fill);fill.secondShade(0);fill.advance(1);fill.cancel();
        check(interrupted.opacity(250,250)==0&&interrupted.canRedo(),"Cancel partial recoloring");
        }
        System.out.println("PASS: linear and circular gradients, endpoints, zero length, stable regions, tolerance, layers, previews, undo/redo, cancellation");
    }
    private static void complete(FloodFill fill) { while(!fill.advance(31)) {} }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
