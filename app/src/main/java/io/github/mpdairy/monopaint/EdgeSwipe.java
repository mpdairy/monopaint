package io.github.mpdairy.monopaint;

/** One finger must travel toward an edge; activate on reaching it, before lift-off is lost. */
final class EdgeSwipe {
    static final int LEFT=0, TOP=1, RIGHT=2, BOTTOM=3;
    /** Beyond the touch radius, where a departing finger may vanish (Manta: up to ~53 dp). */
    static final int EXIT_BAND_DP=40;
    private int edge;
    private float width,height,startDistance,startAlong,slop,travel,band;
    private long down,lastTime,lastMotion;
    private float lastDistance,closest,velocity,contactRadius;
    private boolean active,rejected,reached;
    /** {@code band}: how far inside the edge the digitizer may stop reporting a finger that is still leaving. */
    void start(int edge,float width,float height,float x,float y,long time,float slop,float travel,float band) {
        this.edge=edge;this.width=width;this.height=height;this.down=time;
        this.slop=slop;this.travel=travel;this.band=band;
        startDistance=distance(x,y);startAlong=along(x,y);
        lastDistance=closest=startDistance;lastTime=lastMotion=time;velocity=contactRadius=0;reached=false;
        // Begin inside the canvas, well clear of the OS's bezel gesture zone.
        active=true;rejected=startDistance<travel+slop;
    }
    void reset() {active=false;}
    /** Finger centers can disappear before reaching the bezel; retain their contact footprint. */
    void contact(float radius) {if(Float.isFinite(radius))contactRadius=Math.max(contactRadius,Math.max(0,radius));}
    boolean event(int action,long time,int pointers,boolean finger,float x,float y) {
        if(!active)return false;
        if(!finger || pointers!=1 || action==3 || action==5) {reset();return false;}
        float distance=distance(x,y),across=Math.abs(along(x,y)-startAlong);
        float progress=startDistance-distance;
        // Finger contact settles and wobbles. Edge precision must not also be the
        // movement tolerance, or a few inward pixels can reject an entire swipe.
        float motionSlop=Math.max(1,travel/2);
        if(time-down>1800 || progress < -motionSlop || across>Math.max(travel,Math.abs(progress)*.65f))rejected=true;
        if(time>lastTime && Math.abs(distance-lastDistance)>.01f) {
            velocity=(lastDistance-distance)/(time-lastTime);lastMotion=time;
        }
        closest=Math.min(closest,distance);
        if(distance-closest>motionSlop)rejected=true;
        float reach=Math.max(slop,contactRadius);
        if(distance<=reach && distance>=-slop && progress>=travel)reached=true;
        lastDistance=distance;lastTime=time;
        if(action==2 && reached && !rejected) {active=false;return true;}
        if(action!=1)return false;
        active=false;
        // Digitizers lose a finger before it reaches the bezel (the Manta's last
        // sample lands 30–55 dp inside). Within that band, accept a finger lifted
        // while still moving outward; a deliberate stop pauses first.
        boolean exiting=velocity>=travel/120 && time-lastMotion<=32 && distance<=reach+band;
        return !rejected && progress>=travel && distance>=-slop && (reached || exiting);
    }
    private float distance(float x,float y) {return edge==LEFT?x:edge==TOP?y:edge==RIGHT?width-x:height-y;}
    private float along(float x,float y) {return edge==LEFT || edge==RIGHT?y:x;}
}
