package io.github.mpdairy.monopaint;

/** Shared exact row intervals for the screen preview and the committed shape. */
final class ShapeGeometry {
    private final ToolSettings settings;
    private final int width,height;
    private final float startX,startY;
    private int[] next;
    ShapeGeometry(ToolSettings settings,int width,int height,float x,float y) {
        this.settings=settings;this.width=width;this.height=height;startX=x;startY=y;
    }
    void raster(float x,float y,int[] spans) {
        next=spans;java.util.Arrays.fill(next,0);
        // Input beyond the canvas remains bounded, while strokes at its edges clip naturally.
        x=Math.max(0,Math.min(width,x)); y=Math.max(0,Math.min(height,y));
        if(settings.shape==ToolSettings.Shape.SQUARE || settings.shape==ToolSettings.Shape.CIRCLE) {
            float size=Math.max(Math.abs(x-startX),Math.abs(y-startY));
            x=startX+Math.copySign(size,x-startX); y=startY+Math.copySign(size,y-startY);
        }
        raster(x,y);
    }
    private void raster(float x,float y) {
        if(settings.shape==ToolSettings.Shape.LINE) { line(x,y); return; }
        double left=Math.min(startX,x), right=Math.max(startX,x);
        double top=Math.min(startY,y), bottom=Math.max(startY,y);
        if(right-left<1 || bottom-top<1) return;
        double width=settings.outlineWidth;
        boolean oval=settings.shape==ToolSettings.Shape.OVAL || settings.shape==ToolSettings.Shape.CIRCLE;
        double cx=(left+right)/2, cy=(top+bottom)/2, rx=(right-left)/2, ry=(bottom-top)/2;
        for(int row=Math.max(0,(int)Math.ceil(top-.5)); row<Math.min(height,(int)Math.ceil(bottom-.5)); row++) {
            double outerLeft=left,outerRight=right,innerLeft=left+width,innerRight=right-width;
            boolean hollow=!settings.filled && row+.5>=top+width && row+.5<bottom-width && innerLeft<innerRight;
            if(oval) {
                double dy=row+.5-cy;
                double extent=rx*Math.sqrt(Math.max(0,1-dy*dy/(ry*ry)));
                outerLeft=cx-extent;outerRight=cx+extent;
                double irx=rx-width,iry=ry-width;
                hollow=!settings.filled && irx>0 && iry>0 && Math.abs(dy)<iry;
                if(hollow) {
                    double inner=irx*Math.sqrt(Math.max(0,1-dy*dy/(iry*iry)));
                    innerLeft=cx-inner;innerRight=cx+inner;
                }
            }
            if(hollow) { span(row,outerLeft,innerLeft); span(row,innerRight,outerRight); }
            else span(row,outerLeft,outerRight);
        }
    }
    private void span(int y,double left,double right) {
        int end=Math.min(width,(int)Math.ceil(right-.5));
        int start=Math.max(0,(int)Math.ceil(left-.5));
        if(start>=end)return;
        int offset=y*4;
        if(next[offset]<next[offset+1])offset+=2;
        next[offset]=start;next[offset+1]=end;
    }
    private void line(float endX,float endY) {
        double dx=endX-startX,dy=endY-startY,length=dx*dx+dy*dy,r=settings.outlineWidth/2.0;
        int bottom=Math.min(height-1,(int)Math.floor(Math.max(startY,endY)+r));
        for(int y=Math.max(0,(int)Math.floor(Math.min(startY,endY)-r));y<=bottom;y++) {
            double a=0,b=1;
            if(Math.abs(dy)>.0001) {
                a=Math.max(0,Math.min((y+.5-r-startY)/dy,(y+.5+r-startY)/dy));
                b=Math.min(1,Math.max((y+.5-r-startY)/dy,(y+.5+r-startY)/dy));
                if(a>b) continue;
            }
            double xa=startX+dx*a,xb=startX+dx*b;
            int right=Math.min(width-1,(int)Math.floor(Math.max(xa,xb)+r));
            int first=-1,last=-1;
            for(int x=Math.max(0,(int)Math.floor(Math.min(xa,xb)-r));x<=right;x++) {
                double t=length==0?0:Math.max(0,Math.min(1,((x+.5-startX)*dx+(y+.5-startY)*dy)/length));
                double px=x+.5-startX-t*dx,py=y+.5-startY-t*dy;
                if(px*px+py*py<=r*r) { if(first<0)first=x;last=x; }
            }
            if(first>=0) { next[y*4]=first;next[y*4+1]=last+1; }
        }
    }
}
