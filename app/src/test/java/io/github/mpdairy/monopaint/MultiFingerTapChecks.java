package io.github.mpdairy.monopaint;
final class MultiFingerTapChecks {
    static int e(MultiFingerTap t,int action,long time,int count,float offset) {
        int[] ids=new int[count];float[] x=new float[count],y=new float[count];
        for(int i=0;i<count;i++){ids[i]=i+5;x[i]=100+i*50+offset;y[i]=200;}
        return t.event(action,time,ids,x,y,18,90);
    }
    static int chord(MultiFingerTap t,long time,int count) {
        e(t,0,time,1,0);for(int i=2;i<=count;i++)e(t,5,time+i*10,i,0);
        for(int i=count;i>1;i--)e(t,6,time+60+count-i,i,0);
        return e(t,1,time+80,1,0);
    }
    public static void main(String[] args) {
        MultiFingerTap t=new MultiFingerTap();
        check(chord(t,1000,2)==0 && chord(t,1200,2)==2,"Two-finger double tap");
        check(chord(t,2000,3)==0 && chord(t,2200,3)==0,"Three-finger double tap does nothing");
        check(chord(t,3000,2)==0 && chord(t,3200,3)==0,"Mixed counts rejected");t.reset();
        check(chord(t,4000,2)==0 && chord(t,5000,2)==0,"Distant taps rejected");t.reset();
        chord(t,6000,2);e(t,0,6200,1,0);e(t,5,6210,2,0);e(t,2,6220,2,60);e(t,6,6230,2,60);check(e(t,1,6240,1,60)==0,"Pinch/pan movement rejected");
        chord(t,7000,2);e(t,3,7100,1,0);check(chord(t,7200,2)==0,"Cancel resets double tap");t.reset();
        check(chord(t,8000,4)==0 && chord(t,8200,4)==0,"Palm-sized chords rejected");
        check(chord(t,9000,1)==0 && chord(t,9200,1)==0,"Single finger never toggles");
        System.out.println("PASS: two-finger chords, three-finger rejection, release-only activation, mixed counts, timing, pinch/pan movement, cancel, palms and single fingers");
    }
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
}
