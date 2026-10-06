package io.github.mpdairy.monopaint;
final class EdgeSwipeChecks {
    public static void main(String[] args) {
        for(int edge=0;edge<4;edge++) {
            EdgeSwipe swipe=new EdgeSwipe();float[] from=p(edge,120,300),to=p(edge,2,300);
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            check(swipe.event(2,1100,1,true,to[0],to[1]),"Reaching edge toggles before lift-off");
            check(!swipe.event(1,1200,1,true,to[0],to[1]),"Release does not toggle twice");
            from=p(edge,120,300);to=p(edge,20,300);
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            check(!swipe.event(1,1200,1,true,to[0],to[1]),"Stopping short never toggles");
            to=p(edge,2,300);from=p(edge,2,300);
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            check(!swipe.event(1,1200,1,true,to[0],to[1]),"Edge tap never toggles");
            from=p(edge,120,300);
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            check(!swipe.event(1,1200,2,true,to[0],to[1]),"Two fingers remain available for double taps and navigation");
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            check(!swipe.event(1,1200,1,false,to[0],to[1]),"Pen cannot toggle");
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            check(!swipe.event(3,1200,1,true,to[0],to[1]),"Cancellation never toggles");
            swipe.start(edge,1000,1200,from[0],from[1],1000,5,40,10);
            to=p(edge,2,700);check(!swipe.event(1,1200,1,true,to[0],to[1]),"Diagonal motion is rejected");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            to=p(edge,7,300);
            check(swipe.event(1,1040,1,true,to[0],to[1]),"Fast exit tolerates a missing final edge sample");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            check(!swipe.event(1,1600,1,true,to[0],to[1]),"Slow stroke must actually reach edge");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            to=p(edge,20,300);
            check(!swipe.event(1,1040,1,true,to[0],to[1]),"Even fast flings stopping outside narrow edge band do nothing");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            to=p(edge,2,300);check(swipe.event(2,1100,1,true,to[0],to[1]),"Edge move toggles");
            to=p(edge,6,300);check(!swipe.event(1,1120,1,true,to[0],to[1]),"Lift-off rebound cannot toggle twice");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            to=p(edge,7,300);swipe.event(2,1040,1,true,to[0],to[1]);
            check(!swipe.event(1,1200,1,true,to[0],to[1]),"Pausing near edge is not a fast exit");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            swipe.contact(15);to=p(edge,14,300);
            check(swipe.event(2,1500,1,true,to[0],to[1]),"Contact at bezel toggles before lift-off");swipe.contact(0);
            check(!swipe.event(1,1540,1,true,to[0],to[1]),"Lost contact size on release cannot toggle twice");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            swipe.contact(15);to=p(edge,30,300);
            check(!swipe.event(1,1040,1,true,to[0],to[1]),"Finger footprint still must reach the edge band");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            to=p(edge,132,300);swipe.event(2,1030,1,true,to[0],to[1]);
            to=p(edge,2,300);check(swipe.event(1,1350,1,true,to[0],to[1]),"Small initial inward finger wobble does not cancel an outward edge swipe");
            swipe.start(edge,1000,1200,from[0],from[1],1000,3,40,6);
            to=p(edge,155,300);swipe.event(2,1030,1,true,to[0],to[1]);
            to=p(edge,2,300);check(!swipe.event(1,1350,1,true,to[0],to[1]),"Substantial reversal still rejects gesture");
        }
        // Upward swipes recorded on a Manta (raw fts_ts samples: ms, x, y, touch major; x=-1 is lift-off).
        // Its digitizer drops the finger 59–99 px below the top edge.
        for(int[][] stroke:MANTA_UP)check(manta(stroke,stroke.length),"Recorded Manta swipe toggles despite lost edge samples");
        for(int[][] stroke:MANTA_UP) {
            int count=1;while(stroke[count][2]>=130)count++; // Lift at the last sample ≥70 dp from the edge.
            check(!manta(stroke,count),"Lifting well inside the canvas never toggles");
        }
        for(int[][] stroke:MANTA_UP) {
            int[][] paused=java.util.Arrays.copyOf(stroke,stroke.length);int[] last=stroke[stroke.length-2];
            paused[paused.length-1]=new int[]{last[0]+120,-1,-1,0};
            check(!manta(paused,paused.length),"Pausing in the lost-sample band before lifting never toggles");
        }
        System.out.println("PASS: four exact-edge destinations, on-reach toggle, short strokes, edge taps, multi-touch, pen, cancellation, diagonal rejection and recorded Manta swipes");
    }
    /** Replays like EdgeBars on a 1920×2560, 300 dpi Manta; {@code count} truncates before lifting at the last kept sample. */
    static boolean manta(int[][] stroke,int count) {
        float dp=300/160f;EdgeSwipe swipe=new EdgeSwipe();
        swipe.start(EdgeSwipe.TOP,1920,2560,stroke[0][1],stroke[0][2],stroke[0][0],24*dp,32*dp,EdgeSwipe.EXIT_BAND_DP*dp);
        int[] at=stroke[0];
        for(int i=1;i<count;i++) {
            boolean up=i==count-1;
            if(stroke[i][1]>=0)at=stroke[i];
            swipe.contact(Math.min(18*dp,at[3]/2f));
            if(swipe.event(up?1:2,stroke[i][0],1,true,at[1],at[2]))return true;
        }
        return false;
    }
    static final int[][][] MANTA_UP={
        {{0,1104,269,112},{38,1099,236,112},{51,1099,236,128},{63,1097,197,128},{76,1098,172,144},{89,1099,141,144},{102,1102,110,144},{114,1108,82,128},{138,1116,59,128},{147,-1,-1,0}},
        {{0,1008,278,208},{13,1008,254,208},{26,1008,228,208},{38,1008,190,208},{51,1009,147,208},{64,1010,103,192},{86,1013,66,192},{96,-1,-1,0}},
        {{0,895,386,224},{26,891,358,224},{39,886,325,224},{52,878,278,224},{64,869,220,224},{77,863,157,224},{101,859,94,224},{110,-1,-1,0}},
        {{0,956,449,224},{26,959,421,224},{39,960,396,224},{52,962,365,224},{65,963,327,224},{78,964,283,224},{90,965,228,224},{103,963,166,224},{127,961,99,224},{136,-1,-1,0}},
        {{0,1209,369,208},{26,1213,345,208},{39,1211,312,208},{52,1206,265,208},{65,1200,207,208},{77,1194,148,192},{101,1190,89,192},{110,-1,-1,0}},
        {{0,916,351,176},{39,916,351,192},{52,926,320,192},{78,934,279,208},{91,937,250,208},{104,939,214,208},{116,939,171,208},{129,937,125,192},{153,935,81,192},{162,-1,-1,0}},
        {{0,904,350,144},{39,916,324,160},{65,928,303,160},{77,928,303,176},{90,938,279,192},{103,938,279,208},{116,949,246,208},{129,953,225,208},{142,957,202,208},{155,960,176,208},{168,962,149,208},{181,962,122,208},{193,961,97,192},{217,960,74,192},{225,-1,-1,0}},
        {{0,973,402,160},{13,973,402,176},{26,968,369,176},{39,963,335,176},{52,959,293,176},{65,954,244,192},{78,950,189,208},{90,947,132,192},{114,945,76,192},{123,-1,-1,0}},
        {{0,799,350,192},{26,804,321,192},{39,807,293,208},{52,811,250,208},{65,815,196,208},{77,818,135,192},{101,822,77,192},{110,-1,-1,0}}
    };
    static float[] p(int edge,float d,float along) {return edge==0?new float[]{d,along}:edge==1?new float[]{along,d}:edge==2?new float[]{1000-d,along}:new float[]{along,1200-d};}
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
}
