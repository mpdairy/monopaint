package io.github.mpdairy.monopaint;

/** Bounded page magnification, turn and translation in view pixels. */
final class CanvasViewport {
    /** A free turn this close (in degrees) to a quarter turn settles onto it when a gesture ends. */
    static final float SNAP = 5;
    float zoom = 1, fitScale = 1, x, y;
    /**
     * Free turn in degrees, clockwise, within (-180, 180]. It comes on top of the app's quarter-turn
     * orientation and spins the page about its own corner, which sits at (x, y) in the view.
     */
    float angle;
    private boolean heldLayout, opening, untouched;
    private float width, height, pageWidth, pageHeight;

    void configure(float width, float height, float pageWidth, float pageHeight) {
        float heldScale=scale();
        this.width=width; this.height=height;
        this.pageWidth=pageWidth; this.pageHeight=pageHeight;
        fitScale=Math.min(1,Math.min(width/pageWidth,height/pageHeight));
        zoom=heldLayout ? heldScale/fitScale : Math.max(1,Math.min(8/fitScale,zoom));
        if(!heldLayout)clamp();
    }
    float scale() { return fitScale*zoom; }
    int percent() { return Math.round(scale()*100); }
    void reset() { heldLayout=false; zoom=1; x=y=0; angle=0; opening=untouched=true; }
    /** True once after each reset, for the first layout to choose where the page opens. */
    boolean opening() { boolean first=opening; opening=false; return first; }
    /** Still where the page opened: no pan, zoom, turn or chrome change since the reset. */
    boolean untouched() { return untouched; }
    /** Open at 100% rather than fitted, for a page that fits the screen under the bars. */
    void openActualSize(boolean leftHanded) { actualSize(leftHanded); untouched=true; }
    /**
     * 100% placement where the artwork began. A page starts filling the canvas beside
     * both bars, and expansion only adds paper under bars, so the far edges stay on the
     * canvas edges away from the bars: right or left (by hand) and bottom.
     */
    float homeX(boolean leftHanded) { return leftHanded?0:width-pageWidth; }
    float homeY() { return height-pageHeight; }
    /** Upright again, at 100%, where the artwork began. */
    void actualSize(boolean leftHanded) { angle=0; hold(1,homeX(leftHanded),homeY()); }
    /** Retain the exact view, turn included, while chrome changes around it. */
    void hold(float scale,float x,float y) { heldLayout=true; untouched=false; zoom=scale/fitScale; this.x=x; this.y=y; }
    /**
     * Retain the exact view when the page's own origin moves by (dx, dy) page pixels, as when margins
     * are added above or left of the artwork: the origin slides the other way, through the turn.
     */
    void moveOrigin(float dx,float dy) {
        float s=scale(),cos=cos(),sin=sin();
        hold(s,x-s*(cos*dx-sin*dy),y-s*(sin*dx+cos*dy));
    }
    void gesture(float factor, float oldX, float oldY, float newX, float newY) {
        gesture(factor,0,oldX,oldY,newX,newY);
    }
    /**
     * Zooms by {@code factor} and turns by {@code turn} degrees clockwise about the point that was
     * under {@code (oldX, oldY)}, which ends under {@code (newX, newY)}: two fingers' pinch, twist and pan.
     */
    void gesture(float factor, float turn, float oldX, float oldY, float newX, float newY) {
        if(!Float.isFinite(factor) || factor<=0 || !Float.isFinite(turn)) return;
        // A hidden bar may leave a previously fitted page below the new fit scale.
        // Panning must not unexpectedly magnify it; pinch out or reset to adopt the new fit.
        float next=Math.max(Math.min(1,zoom),Math.min(8/fitScale,zoom*factor));
        heldLayout=next<1; untouched=false;
        float ratio=next/zoom;
        double radians=Math.toRadians(turn),cos=Math.cos(radians),sin=Math.sin(radians);
        float dx=oldX-x, dy=oldY-y;
        x=(float)(newX-ratio*(cos*dx-sin*dy)); y=(float)(newY-ratio*(sin*dx+cos*dy)); zoom=next;
        angle+=turn; angle-=360*Math.round(angle/360);
        clamp();
    }
    /** The turn that settles the view onto the nearest quarter turn, or 0 when it is not within {@link #SNAP} of one. */
    float snapTurn() {
        float off=Math.round(angle/90)*90-angle;
        return Math.abs(off)<=SNAP?off:0;
    }
    private float cos() { return (float)Math.cos(Math.toRadians(angle)); }
    private float sin() { return (float)Math.sin(Math.toRadians(angle)); }
    /** Keeps the turned page covering the canvas, or lying wholly inside it when it is smaller. */
    private void clamp() {
        float s=scale(),cos=cos(),sin=sin(),w=pageWidth*s,h=pageHeight*s;
        // The page's corners relative to its own corner at (x, y).
        float[] cornersX={0,w*cos,-h*sin,w*cos-h*sin},cornersY={0,w*sin,h*cos,w*sin+h*cos};
        float left=0,right=0,top=0,bottom=0;
        for(int i=1;i<4;i++) {
            left=Math.min(left,cornersX[i]); right=Math.max(right,cornersX[i]);
            top=Math.min(top,cornersY[i]); bottom=Math.max(bottom,cornersY[i]);
        }
        float spanX=right-left,spanY=bottom-top;
        x=Math.max(Math.min(0,width-spanX),Math.min(Math.max(0,width-spanX),x+left))-left;
        y=Math.max(Math.min(0,height-spanY),Math.min(Math.max(0,height-spanY),y+top))-top;
    }
}
