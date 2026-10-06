package io.github.mpdairy.monopaint;

/** Bounded page magnification and translation in view pixels. */
final class CanvasViewport {
    float zoom = 1, fitScale = 1, x, y;
    private boolean heldLayout;
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
    void reset() { heldLayout=false; zoom=1; x=y=0; }
    /**
     * 100% placement where the artwork began. A page starts filling the canvas beside
     * both bars, and expansion only adds paper under bars, so the far edges stay on the
     * canvas edges away from the bars: right or left (by hand) and bottom.
     */
    float homeX(boolean leftHanded) { return leftHanded?0:width-pageWidth; }
    float homeY() { return height-pageHeight; }
    void actualSize(boolean leftHanded) { hold(1,homeX(leftHanded),homeY()); }
    /** Retain the exact view while chrome changes around it. */
    void hold(float scale,float x,float y) { heldLayout=true; zoom=scale/fitScale; this.x=x; this.y=y; }
    void gesture(float factor, float oldX, float oldY, float newX, float newY) {
        if(!Float.isFinite(factor) || factor<=0) return;
        // A hidden bar may leave a previously fitted page below the new fit scale.
        // Panning must not unexpectedly magnify it; pinch out or reset to adopt the new fit.
        float next=Math.max(Math.min(1,zoom),Math.min(8/fitScale,zoom*factor));
        heldLayout=next<1;
        float ratio=next/zoom;
        x=newX-(oldX-x)*ratio; y=newY-(oldY-y)*ratio; zoom=next;
        clamp();
    }
    private void clamp() {
        x=Math.max(Math.min(0,width-pageWidth*scale()),Math.min(Math.max(0,width-pageWidth*scale()),x));
        y=Math.max(Math.min(0,height-pageHeight*scale()),Math.min(Math.max(0,height-pageHeight*scale()),y));
    }
}
