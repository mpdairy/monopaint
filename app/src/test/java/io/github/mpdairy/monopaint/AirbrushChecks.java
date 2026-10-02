package io.github.mpdairy.monopaint;

import java.util.Arrays;

public final class AirbrushChecks {
    private static final ToolSettings SETTINGS=ToolSettings.defaults(ToolSettings.Tool.AIRBRUSH).size(64);
    public static void main(String[] args) throws Exception {
        ToneDocument light=new ToneDocument(256,128),firm=new ToneDocument(256,128);
        pass(light,SETTINGS,0,.15f);pass(firm,SETTINGS,0,.45f);
        check(firm.opacity(128,64+20)>0&&light.opacity(128,64+20)==0,"Pressure sets the size");
        check(firm.opacity(128,64+33)==0,"Size stays within the maximum diameter");
        check(firm.tone(128,64)>0,"One pass is translucent");
        check(firm.tone(128,64)<firm.tone(128,64+24),"Soft edge is lighter than the center");
        ToneDocument hard=new ToneDocument(256,128);pass(hard,SETTINGS.softness(0),0,.45f);
        check(hard.tone(128,64+28)<firm.tone(128,64+28)-20,"Zero softness keeps a hard edge");
        int once=firm.tone(128,64);pass(firm,SETTINGS,0,.45f);
        check(firm.tone(128,64)<once,"Another pass builds color");
        ToneDocument weak=new ToneDocument(256,128);pass(weak,SETTINGS.strength(10),0,.45f);
        check(weak.tone(128,64)>once,"Flow sets how much each pass adds");
        ToneDocument doc=new ToneDocument(128,128);
        ToolStroke spray=new ToolStroke(doc,SETTINGS,90);
        spray.sample(64.5f,64.5f,.45f,0,0);
        byte[] first=doc.snapshot();
        for(int i=0;i<30;i++)spray.sample(64.5f,64.5f,.45f,0,0);
        check(Arrays.equals(first,doc.snapshot()),"Holding still adds nothing");
        spray.finish();
        for(int i=0;i<40;i++)pass(doc,SETTINGS,90,.45f,64);
        check(doc.tone(64,64)>=90&&doc.tone(64,64)<=92,"Passes build toward the selected gray without overshoot");
        doc=new ToneDocument(128,128);spray=new ToolStroke(doc,SETTINGS,0);
        for(int x=20;x<=100;x+=8)spray.sample(x,64.5f,.45f,0,0);
        spray.finish();byte[] painted=doc.snapshot();
        check(doc.undo()&&doc.opacity(64,64)==0,"Whole spray is one undo");
        check(doc.redo()&&Arrays.equals(painted,doc.snapshot()),"Exact redo");
        doc=new ToneDocument(256,128);doc.begin();doc.paintTone(128,64,20);doc.finish();doc.addLayer();
        pass(doc,SETTINGS,255,.45f);
        check(doc.compositeTone(128,64)>20&&doc.compositeTone(128,64)<255,"White spray adds translucent paint over lower layer");
        doc.selectLayer(0);check(doc.tone(128,64)==20,"Lower layer remains intact");
        doc=new ToneDocument(128,128);spray=new ToolStroke(doc,SETTINGS.strength(0),0);
        spray.sample(64,64,.45f,0,0);spray.sample(90,64,.45f,0,0);check(!spray.finish(),"Zero flow creates no undo");
        doc=new ToneDocument(128,128);spray=new ToolStroke(doc,SETTINGS,0);
        spray.sample(Float.NaN,64,.45f,0,0);check(!spray.finish(),"Invalid input cannot spray");
        doc=new ToneDocument(128,128);spray=new ToolStroke(doc,SETTINGS,0);
        spray.sample(0,0,.45f,0,0);spray.sample(127,127,.45f,0,0);spray.finish();
        check(doc.tone(60,60)<255,"Fast movement is interpolated without gaps and clips at edges");
        ToolLibrary tools=new ToolLibrary();tools.select(ToolSettings.Tool.AIRBRUSH);
        tools.edit(tools.current().size(101).strength(67));ToolSettings regular=tools.current();
        String id=tools.add().id;tools.edit(tools.current().size(28).strength(12));
        tools=ToolLibrary.decode(tools.encode());
        check(tools.current().maximum==28&&tools.current().strength==12&&tools.activeId().equals(id),"Airbrush favorite survives restart");
        tools.select(ToolSettings.Tool.AIRBRUSH);check(tools.current().equals(regular),"Independent regular settings persist");
        System.out.println("PASS: airbrush pressure size, softness, flow per pass, no buildup while held, layer composition, undo, clipping and presets");
    }
    private static void pass(ToneDocument doc,ToolSettings settings,int gray,float pressure) {pass(doc,settings,gray,pressure,128);}
    /** One horizontal stroke centered on {@code center}, at y 64.5. */
    private static void pass(ToneDocument doc,ToolSettings settings,int gray,float pressure,int center) {
        ToolStroke spray=new ToolStroke(doc,settings,gray);
        for(int x=center-96;x<=center+96;x+=8)spray.sample(x,64.5f,pressure,0,0);
        spray.finish();
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
