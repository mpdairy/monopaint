package io.github.mpdairy.monopaint;

/** Textured/soft tools share sampling, but each has its own logical pixel operation. */
final class ToolStroke implements DrawingStroke {
    private final ToneDocument document;
    private final ToolSettings settings;
    private final int gray;
    private final boolean erasing;
    private boolean started;
    private float px,py,previousDiameter;
    private float distanceToDab;
    private int[] neighborhood = new int[0];
    ToolStroke(ToneDocument document, ToolSettings settings, int gray) {
        this(document,settings,gray,false);
    }
    ToolStroke(ToneDocument document, ToolSettings settings, int gray, boolean erasing) {
        this.erasing=erasing;
        this.document=document; this.settings=settings; this.gray=gray; document.begin();
    }
    @Override public void sample(float x,float y,float pressure,float tx,float ty) {
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(pressure)) return;
        x=Math.max(-128,Math.min(document.width+128,x)); y=Math.max(-128,Math.min(document.height+128,y));
        float p=Math.max(0,Math.min(1,(pressure-.05f)/.40f));
        float diameter=settings.diameter(pressure), minor=diameter,angle=0;
        if(settings.tool==ToolSettings.Tool.PENCIL) {
            float lean=settings.tilt?BrushDirection.lean(tx,ty):0;
            float tip=settings.minimum+(settings.tip-settings.minimum)*p;
            diameter=Math.max(settings.minimum,tip+(settings.maximum-tip)*lean*(.25f+.75f*p));
            minor=settings.leanMinor(diameter,tip);
            if(Float.isFinite(tx)&&Float.isFinite(ty)&&Math.abs(tx)<=90&&Math.abs(ty)<=90)
                angle=(float)Math.atan2(Math.tan(Math.toRadians(ty)),Math.tan(Math.toRadians(tx)));
        }
        if(settings.tool==ToolSettings.Tool.ERASER || settings.tool==ToolSettings.Tool.SOFTEN || settings.tool==ToolSettings.Tool.AIRBRUSH) {
            float spacing=Math.max(.5f,diameter*.12f);
            if(!started) {
                started=true;dab(x,y,diameter,p,0,0);distanceToDab=spacing;
            } else {
                float distance=(float)Math.hypot(x-px,y-py),travel=distanceToDab;
                while(travel<=distance && distance>0) {
                    float t=travel/distance,size=previousDiameter+(diameter-previousDiameter)*t;
                    dab(px+(x-px)*t,py+(y-py)*t,size,p,(x-px)/distance,(y-py)/distance);
                    travel+=Math.max(.5f,size*.12f);
                }
                distanceToDab=travel-distance;
            }
            px=x;py=y;previousDiameter=diameter;return;
        }
        if(!started) { px=x;py=y;previousDiameter=diameter;started=true; }
        float spacing=Math.max(.5f,Math.min(minor,previousDiameter)*.2f);
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(x-px,y-py)/spacing));
        for(int i=1;i<=steps;i++) {
            float t=(float)i/steps,cx=px+(x-px)*t,cy=py+(y-py)*t;
            float size=previousDiameter+(diameter-previousDiameter)*t;
            ToneDabs.pencil(document,cx,cy,size,Math.min(size,minor),angle,p,gray,settings.hardness,erasing);
        }
        px=x;py=y;previousDiameter=diameter;
    }
    private void dab(float x,float y,float diameter,float pressure,float dx,float dy) {
        if(settings.tool==ToolSettings.Tool.ERASER) ToneDabs.erase(document,x,y,diameter/2,settings.softness,pressure);
        // Flow 50% matches the eraser's strength.
        else if(settings.tool==ToolSettings.Tool.AIRBRUSH)
            ToneDabs.soft(document,x,y,diameter/2,settings.softness,pressure,settings.strength/50f,erasing?ToneDocument.ERASE:gray);
        else neighborhood=ToneDabs.soften(document,x,y,diameter/2,dx,dy,settings.strength,neighborhood);
    }
    @Override public boolean finish() { return document.finish(); }
}
