package io.github.mpdairy.monopaint;

/** Immediate spatial spray, using the eraser's carried dab spacing and timed buildup. */
final class AirbrushStroke implements DrawingStroke {
    private static final int TILE=32;
    private static final int HOLD_GRACE_MS=48;
    private static final float FLOW_PER_MS=.036f;
    private static final float[] OPACITY=new float[8193];
    static {
        for(int i=0;i<OPACITY.length;i++)OPACITY[i]=(float)-Math.expm1(-i/512.0);
    }
    private final ToneDocument document;
    private final ToolSettings settings;
    private final int gray;
    private final boolean erasing;
    private final float[][] exposure;
    private final int columns;
    private Point lastInput;
    private final float spacing;
    private float distanceToDab,pendingExposure;
    private long heldThrough;
    private boolean finished;

    private static final class Point {
        final float x,y,pressure;
        final long time;
        Point(float x,float y,float pressure,long time) {
            this.x=x;this.y=y;this.pressure=pressure;this.time=time;
        }
    }
    AirbrushStroke(ToneDocument document,ToolSettings settings,int gray) {
        this(document,settings,gray,false);
    }
    AirbrushStroke(ToneDocument document,ToolSettings settings,int gray,boolean erasing) {
        this.erasing=erasing;
        this.document=document;this.settings=settings;this.gray=gray;
        spacing=Math.max(.5f,settings.maximum*.12f);distanceToDab=spacing;
        columns=(document.width+TILE-1)/TILE;
        exposure=new float[columns*((document.height+TILE-1)/TILE)][];
        document.begin();
    }
    @Override public void sample(float x,float y,float pressure,float tx,float ty) {
        sampleAt(x,y,pressure,lastInput==null?0:lastInput.time+16);
    }
    void sampleAt(float x,float y,float pressure,long now) {
        if(finished || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
        x=Math.max(-128,Math.min(document.width+128,x));
        y=Math.max(-128,Math.min(document.height+128,y));
        float p=Math.max(0,Math.min(1,(pressure-.05f)/.40f));
        if(lastInput==null) {
            lastInput=new Point(x,y,p,now);heldThrough=now;
            // A light initial mark keeps taps visible without a dark starting blob.
            dab(lastInput.x,lastInput.y,lastInput.pressure*2);
        } else {
            now=Math.max(now,lastInput.time);
            Point next=new Point(x,y,p,now);
            boolean moving=x!=lastInput.x || y!=lastInput.y;
            // Hold callbacks never consume motion's time or positions. Batched and
            // late pen input always paints the full connecting segment immediately.
            float elapsed=moving ? Math.max(1,Math.min(64,now-lastInput.time))
                    : Math.max(0,Math.min(64,now-Math.max(lastInput.time,heldThrough)));
            if(moving)move(lastInput,next,elapsed);
            else {flushPending();dab(next.x,next.y,(lastInput.pressure+next.pressure)*.5f*elapsed);}
            lastInput=next;
            heldThrough=Math.max(heldThrough,now);
        }
    }
    void endAt(float x,float y,long now) {
        if(lastInput!=null) sampleAt(x,y,.05f+.40f*lastInput.pressure,now);
    }
    void advance(long now) {
        if(finished || lastInput==null || now-lastInput.time<=HOLD_GRACE_MS)return;
        long elapsed=Math.max(0,Math.min(64,now-Math.max(lastInput.time,heldThrough)));
        flushPending();dab(lastInput.x,lastInput.y,lastInput.pressure*elapsed);heldThrough=Math.max(heldThrough,now);
    }
    private void move(Point from,Point to,float millis) {
        float dx=to.x-from.x,dy=to.y-from.y,distance=(float)Math.hypot(dx,dy);
        float used=0,remaining=distance;
        while(remaining>=distanceToDab) {
            float next=used+distanceToDab,t0=used/distance,t1=next/distance;
            pendingExposure+=millis*(t1-t0)*(from.pressure+(to.pressure-from.pressure)*(t0+t1)*.5f);
            dab(from.x+dx*t1,from.y+dy*t1,pendingExposure);pendingExposure=0;
            used=next;remaining=distance-used;distanceToDab=spacing;
        }
        float t=used/distance;
        pendingExposure+=millis*(1-t)*(from.pressure+(to.pressure-from.pressure)*(t+1)*.5f);
        distanceToDab-=remaining;
    }
    private void flushPending() {
        if(pendingExposure>0 && lastInput!=null)dab(lastInput.x,lastInput.y,pendingExposure);
        pendingExposure=0;distanceToDab=spacing;
    }
    private void dab(float cx,float cy,float pressureMillis) {
        float dose=settings.strength/100f*FLOW_PER_MS*pressureMillis;
        if(dose<=0)return;
        float radius=settings.maximum/2f,inverseRadius2=1/(radius*radius);
        int left=Math.max(0,(int)Math.floor(cx-radius)),right=Math.min(document.width,(int)Math.ceil(cx+radius));
        int top=Math.max(0,(int)Math.floor(cy-radius)),bottom=Math.min(document.height,(int)Math.ceil(cy+radius));
        for(int y=top;y<bottom;y++) {
            float dy=y+.5f-cy,rowEdge=1-dy*dy*inverseRadius2;
            for(int x=left;x<right;x++) {
                float dx=x+.5f-cx,edge=rowEdge-dx*dx*inverseRadius2;
                if(edge>0)deposit(x,y,dose*edge*edge*edge);
            }
        }
    }
    private void deposit(int x,int y,float dose) {
        int key=(y/TILE)*columns+x/TILE;
        float[] tile=exposure[key];
        if(tile==null){tile=new float[TILE*TILE];exposure[key]=tile;}
        int index=(y%TILE)*TILE+x%TILE;
        tile[index]=Math.min(16,tile[index]+dose);
        // Interpolated lookup error is below 0.00013 of one 8-bit tone step.
        float scaled=tile[index]*512;int bin=Math.min(OPACITY.length-2,(int)scaled);
        float opacity=OPACITY[bin]+(OPACITY[bin+1]-OPACITY[bin])*(scaled-bin);
        if(erasing) document.eraseFromBase(x,y,opacity);
        else document.sprayTone(x,y,gray,opacity);
    }
    @Override public boolean finish() {
        if(finished)return false;
        flushPending();finished=true;java.util.Arrays.fill(exposure,null);return document.finish();
    }
}
