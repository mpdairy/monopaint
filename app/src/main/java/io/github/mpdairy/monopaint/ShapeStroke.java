package io.github.mpdairy.monopaint;

/** A replaceable preview on the selected layer, committed as a single undo edit. */
final class ShapeStroke {
    private final ToneDocument document;
    private final int shade;
    private final ShapeGeometry geometry;
    private float lastX=Float.NaN, lastY=Float.NaN;
    private boolean ended;
    // Each row of these convex shapes has at most two painted intervals.
    private int[] previous, next;
    private final DirtyRegions dirty=new DirtyRegions(24);
    java.util.List<int[]> drainDirty() { return dirty.drain(); }

    ShapeStroke(ToneDocument document, ToolSettings settings, int shade, float x, float y) {
        this.document=document; this.shade=shade; geometry=new ShapeGeometry(settings,document.width,document.height,x,y);
        previous=new int[document.height*4];next=new int[previous.length];
        document.begin();
    }
    void preview(float x, float y) {
        if(ended || !Float.isFinite(x) || !Float.isFinite(y)) return;
        if(x==lastX && y==lastY) return;
        lastX=x; lastY=y;
        geometry.raster(x,y,next);
        for(int row=0;row<document.height;row++) {
            difference(previous,next,row,false);
            difference(next,previous,row,true);
        }
        int[] swap=previous;previous=next;next=swap;
    }
    private void difference(int[] source,int[] subtract,int row,boolean paint) {
        int offset=row*4;
        for(int part=0;part<4;part+=2) {
            int left=source[offset+part],right=source[offset+part+1];
            if(left>=right)continue;
            for(int cut=0;cut<4 && left<right;cut+=2) {
                int a=subtract[offset+cut],b=subtract[offset+cut+1];
                if(a>=b || b<=left || a>=right)continue;
                if(a>left)change(row,left,a,paint);
                left=Math.max(left,b);
            }
            if(left<right)change(row,left,right,paint);
        }
    }
    private void change(int row,int left,int right,boolean paint) {
        if(paint)document.paintSpan(left,right,row,shade);
        else document.restoreSpan(left,right,row);
        dirty.add(left,row,right,row+1);
    }
    boolean finish() { if(ended)return false; ended=true; return document.finish(); }
    void cancel() {
        if(ended)return;
        document.cancel();ended=true;
        for(int row=0;row<document.height;row++)for(int part=0;part<4;part+=2) {
            int at=row*4+part;dirty.add(previous[at],row,previous[at+1],row+1);
        }
    }
}
