package io.github.mpdairy.monopaint;

final class ViewportChecks {
    public static void main(String[] args) {
        CanvasViewport v=new CanvasViewport(); v.configure(1000,1400,1000,1400);
        v.gesture(2,400,500,400,500);
        near(v.scale(),2); near(v.x,-400); near(v.y,-500);
        near((400-v.x)/v.scale(),400); near((500-v.y)/v.scale(),500);
        v.gesture(1,400,500,450,600); near(v.x,-350); near(v.y,-400);
        v.gesture(1,0,0,10000,10000); near(v.x,0); near(v.y,0);
        v.gesture(1,0,0,-10000,-10000); near(v.x,-1000); near(v.y,-1400);
        v.gesture(100,500,700,500,700); near(v.scale(),8);
        v.gesture(.001f,500,700,500,700); near(v.scale(),1); near(v.x,0); near(v.y,0);
        v.configure(500,700,1000,1400); near(v.scale(),.5f);
        if(v.percent()!=50) throw new AssertionError("Fit percentage uses document pixels");
        v.gesture(4,250,350,250,350); near(v.scale(),2); near(v.x,-750); near(v.y,-1050);
        v.gesture(Float.NaN,0,0,0,0); near(v.scale(),2);
        v.reset(); near(v.scale(),.5f); near(v.x,0); near(v.y,0);
        v.configure(1000,1400,320,480); v.gesture(2,500,700,500,700);
        near(v.x,0); near(v.y,0);
        v=new CanvasViewport();v.configure(500,700,1000,1400);
        v.hold(.5f,64,48);v.configure(700,1000,1000,1400);
        near(v.scale(),.5f);near(v.x,64);near(v.y,48);
        v.gesture(1,200,200,230,230);v.configure(700,1000,1000,1400);
        near(v.scale(),.5f);near(v.x,94);near(v.y,78);
        v.reset();v.configure(700,1000,1000,1400);near(v.scale(),.7f);
        turnChecks();
        System.out.println("PASS: zoom focus, two-finger translation, page bounds, 800% limit, actual percentage, fit reset, turn, quarter-turn snap and margin growth");
    }
    private static void turnChecks() {
        // A twist about the fingers keeps the page point under them under them, whatever the zoom.
        CanvasViewport v=new CanvasViewport(); v.configure(1000,1400,1000,1400);
        v.gesture(2,300,400,300,400);
        float[] held=toPage(v,420,560);
        v.gesture(1,30,420,560,420,560);
        near(v.angle,30);
        float[] after=toPage(v,420,560);
        near(after[0],held[0]); near(after[1],held[1]);
        // Fingers that twist and drift together carry the page point to where they end.
        float[] carried=toPage(v,500,700);
        v.gesture(1.5f,25,500,700,540,650);
        float[] landed=toPage(v,540,650);
        near(landed[0],carried[0]); near(landed[1],carried[1]);
        near(v.angle,55); near(v.scale(),3);
        // Turns accumulate clockwise and wrap into (-180, 180].
        v.gesture(1,150,500,700,500,700); near(v.angle,-155);
        v.gesture(1,-30,500,700,500,700); near(v.angle,175);
        // A turned page larger than the canvas can be panned until its bounds meet each canvas edge, no further.
        v.gesture(1,0,0,0,10000,10000);
        float[] box=bounds(v,1000,1400);
        near(box[0],0); near(box[1],0);
        v.gesture(1,0,0,0,-10000,-10000);
        box=bounds(v,1000,1400);
        near(box[2],1000); near(box[3],1400);
        // A turned page smaller than the canvas stays wholly on it.
        v=new CanvasViewport(); v.configure(1000,1400,320,480);
        v.gesture(1,45,500,700,500,700); v.gesture(1,0,0,0,-10000,-10000);
        float[][] corners={{0,0},{320,0},{0,480},{320,480}};
        for(float[] c:corners) { float[] at=toView(v,c[0],c[1]); inside(at,1000,1400); }
        v.gesture(1,0,0,0,10000,10000);
        for(float[] c:corners) { float[] at=toView(v,c[0],c[1]); inside(at,1000,1400); }
        // An unturned gesture is the old two-finger pinch and pan, exactly.
        v=new CanvasViewport(); v.configure(1000,1400,1000,1400);
        v.gesture(2,400,500,430,520); near(v.angle,0); near(v.x,-370); near(v.y,-480);
        v.gesture(1,Float.NaN,0,0,0,0); near(v.scale(),2); near(v.angle,0);
        // Quarter-turn snap.
        v=new CanvasViewport(); v.configure(1000,1400,1000,1400); v.gesture(2,0,0,0,0);
        float[] angles={0,3,-4.9f,10,45,86,-91,176,-178,180},snaps={0,-3,4.9f,0,0,4,1,4,-2,0};
        for(int i=0;i<angles.length;i++) {
            v.angle=angles[i]; near(v.snapTurn(),snaps[i]);
        }
        v.angle=7; near(v.snapTurn(),0);
        v.angle=4; v.gesture(1,v.snapTurn(),500,700,500,700); near(v.angle,0);
        // Reset and 100% stand the page upright; a held view keeps its turn.
        v.gesture(1,40,500,700,500,700); v.hold(1,10,20); near(v.angle,40);
        v.actualSize(false); near(v.angle,0);
        v.gesture(1,40,500,700,500,700); v.reset(); near(v.angle,0);
        // Growing the page above and left of the artwork leaves every mark where it was, turned or not.
        v=new CanvasViewport(); v.configure(1000,1400,1000,1400);
        v.gesture(2,0,300,400,300,400); v.gesture(1,35,300,400,330,380);
        float[] mark=toView(v,200,250);
        v.moveOrigin(60,90);
        float[] still=toView(v,260,340);
        near(still[0],mark[0]); near(still[1],mark[1]);
    }
    /** The view point where the page's own point (px, py) is shown. */
    private static float[] toView(CanvasViewport v,float px,float py) {
        double r=Math.toRadians(v.angle),cos=Math.cos(r),sin=Math.sin(r);
        float s=v.scale();
        return new float[]{(float)(v.x+s*(cos*px-sin*py)),(float)(v.y+s*(sin*px+cos*py))};
    }
    /** The page point shown at the view point (vx, vy). */
    private static float[] toPage(CanvasViewport v,float vx,float vy) {
        double r=Math.toRadians(v.angle),cos=Math.cos(r),sin=Math.sin(r);
        float s=v.scale(),dx=vx-v.x,dy=vy-v.y;
        return new float[]{(float)((cos*dx+sin*dy)/s),(float)((-sin*dx+cos*dy)/s)};
    }
    /** The view rectangle {left, top, right, bottom} that holds the whole page. */
    private static float[] bounds(CanvasViewport v,float pageWidth,float pageHeight) {
        float[][] corners={{0,0},{pageWidth,0},{0,pageHeight},{pageWidth,pageHeight}};
        float[] box={Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(float[] c:corners) {
            float[] at=toView(v,c[0],c[1]);
            box[0]=Math.min(box[0],at[0]); box[1]=Math.min(box[1],at[1]);
            box[2]=Math.max(box[2],at[0]); box[3]=Math.max(box[3],at[1]);
        }
        return box;
    }
    private static void inside(float[] view,float width,float height) {
        if(view[0]<-.5f || view[1]<-.5f || view[0]>width+.5f || view[1]>height+.5f)
            throw new AssertionError("page leaves the canvas at "+view[0]+","+view[1]);
    }
    private static void near(float actual,float wanted) {
        if(Math.abs(actual-wanted)>.01f) throw new AssertionError(actual+" != "+wanted);
    }
}
