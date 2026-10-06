package io.github.mpdairy.monopaint;

/** Two matching, stationary two-finger chords; decisions occur only after every finger lifts. */
final class MultiFingerTap {
    private final float[] startX=new float[32],startY=new float[32];
    private final boolean[] seen=new boolean[32];
    private long down,previousUp;
    private int peak,previousCount;
    private float centerX,centerY,previousX,previousY;
    private boolean rejected,lifting;
    void reset() { rejected=true;previousCount=0;peak=0; }
    // Android action constants: down=0, up=1, move=2, cancel=3, pointer down=5, pointer up=6.
    int event(int action,long time,int[] ids,float[] xs,float[] ys,float slop,float repeatSlop) {
        if(action==0) {
            down=time;peak=0;rejected=false;lifting=false;java.util.Arrays.fill(seen,false);
        }
        if(action==3) {reset();return 0;}
        if(ids.length>2 || time-down>280)rejected=true;
        if(action==5 && lifting)rejected=true;
        for(int i=0;i<ids.length;i++) {
            int id=ids[i]; if(id<0 || id>=32) { rejected=true;continue; }
            if(!seen[id]) { seen[id]=true; startX[id]=xs[i];startY[id]=ys[i]; }
            if(Math.hypot(xs[i]-startX[id],ys[i]-startY[id])>slop)rejected=true;
        }
        if(ids.length>peak) {
            peak=ids.length;centerX=centerY=0;
            for(int i=0;i<ids.length;i++) {centerX+=xs[i]/ids.length;centerY+=ys[i]/ids.length;}
        }
        if(action==6)lifting=true;
        if(action!=1)return 0;
        if(rejected || peak!=2) {previousCount=0;return 0;}
        if(previousCount==peak && time-down<=280 && down-previousUp<=360 && down>=previousUp
                && Math.hypot(centerX-previousX,centerY-previousY)<=repeatSlop) {
            int result=peak;reset();return result;
        }
        previousCount=peak;previousUp=time;previousX=centerX;previousY=centerY;return 0;
    }
}
