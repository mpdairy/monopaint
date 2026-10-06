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
        System.out.println("PASS: zoom focus, two-finger translation, page bounds, 800% limit, actual percentage and fit reset");
    }
    private static void near(float actual,float wanted) {
        if(Math.abs(actual-wanted)>.01f) throw new AssertionError(actual+" != "+wanted);
    }
}
