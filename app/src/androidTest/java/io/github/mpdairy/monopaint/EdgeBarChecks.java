package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Input goes through Android's dispatcher, including the firmware's global swipe listener. */
final class EdgeBarChecks {
    static void run(Instrumentation test,StringBuilder report)throws Exception {
        PaintActivity app=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<150&&(app.loading || !app.hasWindowFocus());i++)SystemClock.sleep(100);
        check(!app.loading && app.hasWindowFocus(),"App ready");
        DrawingBook original=app.book;String name=app.drawingName;int index=original.index();
        DrawingBook.Snapshot originalSnapshot=original.snapshot();
        int rotation=(4-app.appRotation)%4,gray=app.paint.gray;
        boolean hadHand=app.preferences.contains("toolbox_right"),hand=app.prefs.toolboxRight(),lock=app.navigationLocked;
        boolean hadLock=app.preferences.contains("navigation_locked"),hadZoom=app.preferences.contains("tool_visible_ZOOM");
        boolean zoomVisible=app.preferences.getBoolean("tool_visible_ZOOM",true);
        ToolLibrary library=app.library;
        try {
            main(test,() -> {
                app.rotationPrompt.orientationSensor.disable();app.pad.suspend();app.drawingName="";
                app.library=new ToolLibrary();app.navigationLocked=true;app.preferences.edit().putBoolean("tool_visible_ZOOM",true).apply();
                ToneDocument page=new ToneDocument(900,1200);page.begin();page.paintTone(200,200,30);page.finish();
                replace(app,new DrawingBook(page));app.fullscreen.setMode(0);
            });idle(test);
            byte[] artwork=DrawingBook.encode(app.pad.document);
            for(int turn=0;turn<4;turn++)for(boolean right:new boolean[]{false,true}) {
                final int quarter=turn;
                main(test,() -> {app.preferences.edit().putBoolean("toolbox_right",right).apply();app.requestQuarter(quarter);app.applyToolboxSide();});idle(test);
                float[] mark=point(app,200,200);
                // Exercise both directions of every transition among all four bar layouts.
                int[] bars={0,1,0,1,1,0,1,0};
                for(int i=0;i<bars.length;i++) {
                    int bar=bars[i],edge=bar==0?app.edgeBars.toolEdge():app.edgeBars.colorEdge();
                    boolean tools=app.fullscreen.toolsVisible(),colors=app.fullscreen.colorsVisible();
                    int before=app.fullscreen.mode;
                    // A Manta loses departing fingers up to ~53 dp inside the edge, so a
                    // still-moving lift there counts as leaving; stop clearly inside instead.
                    swipe(test,app,edge,app.dp(200),app.dp(96));idle(test);
                    check(app.fullscreen.mode==before,"Stops short never toggle");
                    swipe(test,app,edge,app.dp(160),i%3==2?app.dp(16):i%3==1?app.dp(7):1,i%3==1,i%3==2?app.dp(30):0);idle(test);
                    check(app.fullscreen.toolsVisible()==(bar==0?!tools:tools) && app.fullscreen.colorsVisible()==(bar==1?!colors:colors),"Independent bar toggle from mode "+before);
                    if(turn==0 && !right && i==0) {
                        check(app.fullscreen.dialog!=null && app.book.canvasChoice()==0,"First swipe from regular layout asks about margins");
                        screenshot(test,app,"edge-choice.png");
                        main(test,() -> FullscreenChecks.click(app.fullscreen.dialog.getWindow().getDecorView(),"Keep canvas size."));idle(test);
                    } else check(app.fullscreen.dialog==null,"Page choice does not repeat");
                    check(app.hasWindowFocus(),"System toolbar stays closed on the physical edge swipe");
                    near(mark,point(app,200,200),"Bar toggles keep artwork stationary");
                    check(fastDisplay(app),"Fast pen display resumes after toggling");
                    if(app.fullscreen.mode==1 || app.fullscreen.mode==3) {
                        FullscreenChecks.chord(test,app,2);FullscreenChecks.chord(test,app,2);idle(test);
                        check(app.fullscreen.mode==before,"Mixed layout double tap undoes last bar action");
                        swipe(test,app,edge,app.dp(160),1);idle(test);
                    }
                    check(Arrays.equals(artwork,DrawingBook.encode(app.pad.document)),"Swipes never change kept page or artwork");
                }
                check(app.fullscreen.mode==0,"All bars restored after toggle cycle");
                FullscreenChecks.chord(test,app,2);FullscreenChecks.chord(test,app,2);idle(test);
                check(app.fullscreen.mode==2,"Two-finger double tap hides both bars");
                FullscreenChecks.chord(test,app,2);FullscreenChecks.chord(test,app,2);idle(test);
                check(app.fullscreen.mode==0,"Two-finger double tap shows both bars");
                FullscreenChecks.chord(test,app,3);FullscreenChecks.chord(test,app,3);idle(test);
                check(app.fullscreen.mode==0,"Three-finger double tap has no toolbar action");
                ZoomButtonChecks.verify(test,app);
                report.append("PASS edge toggles and Zoom double tap rotation ").append(turn).append(" hand ").append(right).append("; four layouts, finger footprints, slow/fast input, OS toolbar stays closed.\n");
                android.os.Bundle progress=new android.os.Bundle();progress.putString("stream","Passed edge swipes: rotation "+turn+", toolbar right="+right+"\n");test.sendStatus(0,progress);
            }
            report.append("PASS: edge-only completion, fast exits, short-swipe rejection, stationary artwork, first-swipe margins, all bar layouts, double-tap toggles and system-menu avoidance.\n");
        } finally {
            main(test,() -> {app.edgeBars.reset();app.fullscreen.dismiss();app.fullscreen.setMode(0);});idle(test);
            main(test,() -> {
                app.pad.suspend();replace(app,original);app.drawingName=name;app.library=library;app.paint.gray=gray;app.navigationLocked=lock;
                if(hadHand)app.preferences.edit().putBoolean("toolbox_right",hand).apply();else app.preferences.edit().remove("toolbox_right").apply();
                android.content.SharedPreferences.Editor prefs=app.preferences.edit();
                if(hadLock)prefs.putBoolean("navigation_locked",lock);else prefs.remove("navigation_locked");
                if(hadZoom)prefs.putBoolean("tool_visible_ZOOM",zoomVisible);else prefs.remove("tool_visible_ZOOM");prefs.apply();
                app.requestQuarter(rotation);app.applyToolboxSide();app.refreshPaintModes();app.saveToolState();app.recovery();
            });idle(test);TestSessionSave.await(app);
            check(app.book==original&&original.index()==index&&app.drawingName.equals(name),"Original session restored");
            DrawingBook.Snapshot restored=original.snapshot();
            for(int i=0;i<original.count();i++)check(Arrays.equals(DrawingBook.encode(originalSnapshot.page(i)),DrawingBook.encode(restored.page(i))),"Original page and layers unchanged: "+i);
            report.append("RESTORED page ").append(index+1).append(" of ").append(original.count()).append("; all pages/layers verified.\n");
        }
    }
    private static void swipe(Instrumentation test,PaintActivity app,int edge,float from,float to)throws Exception {
        swipe(test,app,edge,from,to,false);
    }
    private static void swipe(Instrumentation test,PaintActivity app,int edge,float from,float to,boolean fast)throws Exception {
        swipe(test,app,edge,from,to,fast,0);
    }
    private static void swipe(Instrumentation test,PaintActivity app,int edge,float from,float to,boolean fast,float diameter)throws Exception {
        float w=app.root.getWidth(),h=app.root.getHeight();
        float along=(edge==0||edge==2)?h*.6f:w*.6f;
        long down=SystemClock.uptimeMillis();
        int steps=fast?2:8;
        for(int i=0;i<=steps;i++) {
            float d=from+(to-from)*i/steps;
            float[] p=edge==0?new float[]{d,along}:edge==1?new float[]{along,d}:edge==2?new float[]{w-d,along}:new float[]{along,h-d};
            PanelCoordinates.fromView(app.root).mapPoints(p);
            send(test,down,i==0?MotionEvent.ACTION_DOWN:i==steps?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,p[0],p[1],i==steps?0:diameter);SystemClock.sleep(fast?8:18);
        }
    }
    private static void send(Instrumentation test,long down,int action,float x,float y) {
        send(test,down,action,x,y,0);
    }
    private static void send(Instrumentation test,long down,int action,float x,float y,float diameter) {
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords p=new MotionEvent.PointerCoords();p.x=x;p.y=y;p.pressure=action==MotionEvent.ACTION_UP?0:1;p.touchMajor=p.touchMinor=diameter;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{p},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{check(test.getUiAutomation().injectInputEvent(event,true),"System input injection accepted");}finally{event.recycle();}
    }
    private static float[] point(PaintActivity app,int x,int y) {
        float[] p={x,y};app.pad.pageToView.mapPoints(p);PanelCoordinates.fromView(app.pad).mapPoints(p);return p;
    }
    private static void near(float[] a,float[] b,String message){check(Math.abs(a[0]-b[0])<2 && Math.abs(a[1]-b[1])<2,message+" "+Arrays.toString(a)+" vs "+Arrays.toString(b));}
    private static boolean fastDisplay(PaintActivity app)throws Exception {
        java.lang.reflect.Field field=DrawingPad.class.getDeclaredField("direct");field.setAccessible(true);return field.get(app.pad)!=null;
    }
    private static void screenshot(Instrumentation test,PaintActivity app,String name)throws Exception {
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private static void replace(PaintActivity app,DrawingBook book)throws Exception {Method m=PaintActivity.class.getDeclaredMethod("replaceBook",DrawingBook.class);m.setAccessible(true);m.invoke(app,book);}
    private static void idle(Instrumentation test){test.waitForIdleSync();SystemClock.sleep(160);}
    interface Work {void run()throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception {Throwable[] e={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable error){e[0]=error;}});if(e[0]!=null)throw new Exception(e[0]);}
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
}
