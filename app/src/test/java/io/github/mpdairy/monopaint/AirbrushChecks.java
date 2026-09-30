package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class AirbrushChecks {
    private static final ToolSettings SETTINGS=ToolSettings.defaults(ToolSettings.Tool.AIRBRUSH).size(64);
    public static void main(String[] args) throws Exception {
        immediateMotion(); continuousSweeps(); strongFlow();
        ToneDocument light=held(.15f,16),firm=held(.45f,16),sparse=held(.45f,32);
        check(firm.tone(64,64)<light.tone(64,64),"Pressure increases strength");
        check(Arrays.equals(firm.snapshot(),sparse.snapshot()),"Stationary exposure is independent of event frequency");
        check(firm.tone(64,64)<firm.tone(80,64)&&firm.tone(80,64)<firm.tone(91,64),"Smooth center-to-edge falloff");
        check(firm.opacity(96,64)==0&&light.opacity(96,64)==0,"Fixed radius at both pressures");
        ToneDocument doc=new ToneDocument(128,128);
        AirbrushStroke spray=new AirbrushStroke(doc,SETTINGS,90);
        spray.sampleAt(64.5f,64.5f,.45f,100);
        int first=doc.tone(64,64);
        for(int t=132;t<=1060;t+=32)spray.advance(t);
        check(doc.tone(64,64)<first&&doc.tone(64,64)>=90,"Holding builds toward selected gray without overshoot");
        spray.finish();byte[] painted=doc.snapshot();spray.advance(2000);
        check(Arrays.equals(painted,doc.snapshot()),"No spray after finish");
        check(doc.undo()&&doc.opacity(64,64)==0,"Whole timed spray is one undo");
        check(doc.redo()&&Arrays.equals(painted,doc.snapshot()),"Exact redo");
        doc=new ToneDocument(128,128);doc.begin();doc.paintTone(64,64,20);doc.finish();doc.addLayer();
        spray=new AirbrushStroke(doc,SETTINGS,255);spray.sampleAt(64.5f,64.5f,.45f,0);spray.advance(32);spray.finish();
        check(doc.compositeTone(64,64)>20&&doc.compositeTone(64,64)<255,"White spray adds translucent paint over lower layer");
        doc.selectLayer(0);check(doc.tone(64,64)==20,"Lower layer remains intact");
        doc=new ToneDocument(128,128);spray=new AirbrushStroke(doc,SETTINGS.strength(0),0);
        spray.sampleAt(64,64,.45f,0);spray.advance(32);check(!spray.finish(),"Zero flow creates no undo");
        doc=new ToneDocument(128,128);spray=new AirbrushStroke(doc,SETTINGS,0);
        spray.sampleAt(Float.NaN,64,.45f,0);spray.sampleAt(64,64,0,0);spray.advance(32);
        check(!spray.finish(),"Invalid input and zero pressure cannot spray");
        doc=new ToneDocument(128,128);spray=new AirbrushStroke(doc,SETTINGS,0);
        spray.sampleAt(0,0,.45f,0);spray.sampleAt(127,127,.45f,32);spray.finish();
        check(doc.tone(60,60)<255,"Fast movement is interpolated without gaps and clips at edges");
        ToolLibrary tools=new ToolLibrary();tools.select(ToolSettings.Tool.AIRBRUSH);
        tools.edit(tools.current().size(101).strength(67));ToolSettings regular=tools.current();
        String id=tools.add().id;tools.edit(tools.current().size(28).strength(12));
        tools=ToolLibrary.decode(tools.encode());
        check(tools.current().maximum==28&&tools.current().strength==12&&tools.activeId().equals(id),"Airbrush favorite survives restart");
        tools.select(ToolSettings.Tool.AIRBRUSH);check(tools.current().equals(regular),"Independent regular settings persist");
        System.out.println("PASS: airbrush pressure, falloff, timed buildup, event frequency, clipping, layer composition, undo, stop and presets");
    }
    private static void immediateMotion() {
        ToneDocument doc=new ToneDocument(512,128);
        AirbrushStroke spray=new AirbrushStroke(doc,SETTINGS.strength(100),0);
        spray.sampleAt(32,64.5f,.45f,0);
        spray.sampleAt(160,64.5f,.45f,8);
        check(doc.tone(120,64)<255,"Motion paints during the input call without waiting for a timer or pen-up");
        spray.sampleAt(300,64.5f,.45f,16);
        check(doc.tone(260,64)<255,"Every subsequent sample is immediately visible");
        byte[] live=doc.snapshot();spray.advance(32);
        check(Arrays.equals(live,doc.snapshot()),"Hold timer does not add blobs while motion events are arriving");
        spray.finish();check(doc.tone(299,64)<255,"Pen-up completes the small spacing remainder at the tip");
    }
    private static void strongFlow() {
        ToneDocument doc=new ToneDocument(128,128);
        AirbrushStroke spray=new AirbrushStroke(doc,SETTINGS.strength(100),0);
        for(int t=0;t<=192;t+=16)spray.sampleAt(64.5f,64.5f,.45f,t);
        spray.finish();
        check(doc.tone(64,64)<=1,"Full flow reaches near-black in under 200ms");
        check(doc.tone(76,64)<10,"Full flow darkens a usable area around the center");
    }
    private static void continuousSweeps() {
        for(int size:new int[]{2,16,64,128}) {
            ToneDocument sparse=sweep(size,1,false),dense=sweep(size,16,false),timed=sweep(size,1,true);
            byte[] a=sparse.snapshot(),b=dense.snapshot(),c=timed.snapshot();
            for(int i=0;i<a.length;i++) {
                check(Math.abs((a[i]&255)-(b[i]&255))<=1,"Sparse and dense pen samples produce the same continuous sweep at size "+size);
                check(a[i]==c[i],"Hold timer cannot steal paint from delayed motion at size "+size);
            }
            int low=255,high=0;
            for(int x=96;x<416;x++) {int tone=sparse.tone(x,64);low=Math.min(low,tone);high=Math.max(high,tone);}
            check(high<255&&high-low<=1,"Fast stroke interior is uniform without sampled blobs at size "+size);
        }
        ToneDocument doc=new ToneDocument(512,128);AirbrushStroke spray=new AirbrushStroke(doc,SETTINGS.strength(100),0);
        spray.sampleAt(32,64.5f,.45f,0);spray.advance(120);
        spray.sampleAt(480,64.5f,.45f,16);spray.finish();
        for(int x=64;x<448;x++)check(doc.tone(x,64)<255,"Even input later than the hold timer cannot leave missing path segments");
        doc=new ToneDocument(512,128);spray=new AirbrushStroke(doc,SETTINGS.strength(100),0);
        spray.sampleAt(32,64.5f,.45f,0);spray.endAt(480,64.5f,32);spray.finish();
        check(doc.tone(460,64)<255,"Pen-up includes its final movement");
        doc=new ToneDocument(512,128);spray=new AirbrushStroke(doc,SETTINGS.strength(100),0);
        spray.sampleAt(32,64.5f,.45f,0);spray.sampleAt(480,64.5f,.45f,0);spray.finish();
        check(doc.tone(250,64)<255,"Equal-timestamp movement still paints a continuous path");
        // Diagonal motion with changing pressure must integrate identically when split.
        ToneDocument[] diagonal={new ToneDocument(256,256),new ToneDocument(256,256)};
        for(int pass=0;pass<2;pass++) {
            spray=new AirbrushStroke(diagonal[pass],SETTINGS.strength(100),0);
            int steps=pass==0?1:32;
            for(int i=0;i<=steps;i++){float t=(float)i/steps;spray.sampleAt(20+210*t,30+180*t,.10f+.35f*t,32*i/steps);}
            spray.finish();
        }
        byte[] a=diagonal[0].snapshot(),b=diagonal[1].snapshot();
        for(int i=0;i<a.length;i++)check(Math.abs((a[i]&255)-(b[i]&255))<=1,"Diagonal pressure ramp is independent of event segmentation");
        System.out.println("PASS: continuous fast sweeps, delayed/batched input, timer interleaving, final segment, equal timestamps and pressure ramps");
    }
    private static ToneDocument sweep(int size,int steps,boolean timer) {
        ToneDocument doc=new ToneDocument(512,128);
        AirbrushStroke spray=new AirbrushStroke(doc,SETTINGS.size(size).strength(100),0);
        spray.sampleAt(32,64.5f,.45f,0);
        if(timer)spray.advance(32);
        for(int i=1;i<=steps;i++)spray.sampleAt(32+448f*i/steps,64.5f,.45f,32*i/steps);
        spray.finish();return doc;
    }
    private static ToneDocument held(float pressure,int interval) {
        ToneDocument doc=new ToneDocument(128,128);AirbrushStroke spray=new AirbrushStroke(doc,SETTINGS,0);
        spray.sampleAt(64.5f,64.5f,pressure,0);
        for(int t=interval;t<=640;t+=interval)spray.sampleAt(64.5f,64.5f,pressure,t);
        spray.finish();return doc;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
