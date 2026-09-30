package io.github.mpdairy.monopaint;

/** Bounded page magnification and translation in view pixels. */
final class CanvasViewport {
    float zoom = 1, fitScale = 1, x, y;
    private float width, height, pageWidth, pageHeight;

    void configure(float width, float height, float pageWidth, float pageHeight) {
        this.width=width; this.height=height;
        this.pageWidth=pageWidth; this.pageHeight=pageHeight;
        fitScale=Math.min(1,Math.min(width/pageWidth,height/pageHeight));
        zoom=Math.max(1,Math.min(8/fitScale,zoom));
        clamp();
    }
    float scale() { return fitScale*zoom; }
    int percent() { return Math.round(scale()*100); }
    void reset() { zoom=1; x=y=0; }
    void gesture(float factor, float oldX, float oldY, float newX, float newY) {
        if(!Float.isFinite(factor) || factor<=0) return;
        float next=Math.max(1,Math.min(8/fitScale,zoom*factor));
        float ratio=next/zoom;
        x=newX-(oldX-x)*ratio; y=newY-(oldY-y)*ratio; zoom=next;
        clamp();
    }
    private void clamp() {
        x=Math.max(Math.min(0,width-pageWidth*scale()),Math.min(0,x));
        y=Math.max(Math.min(0,height-pageHeight*scale()),Math.min(0,y));
    }
}
