package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Checks request routing and input handling, not the physical panel's response time. */
final class ColorBarChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        run(test,report,false);
    }
    static void run(Instrumentation test, StringBuilder report,boolean pagesOnly) throws Exception {
        PaintActivity app=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline=SystemClock.uptimeMillis()+10000;
        while ((app.loading || !app.hasWindowFocus()) && SystemClock.uptimeMillis()<deadline)
            SystemClock.sleep(50);
        check(!app.loading && app.hasWindowFocus(),"App ready");
        Field field=PaintActivity.class.getDeclaredField("preferences");field.setAccessible(true);
        SharedPreferences prefs=(SharedPreferences)field.get(app);
        boolean hadHand=prefs.contains("toolbox_right"),hand=prefs.getBoolean("toolbox_right",false);
        int rotation=(4-app.appRotation)%4,gray=app.paint.gray;
        boolean hadNomad=prefs.contains("nomad_mode"),nomad=prefs.getBoolean("nomad_mode",false);
        boolean erase=app.paint.eraseMode;
        int wetness=app.paint.wetness;boolean wetCanvas=app.paint.wetCanvas;
        DrawingBook book=app.book;String name=app.drawingName;
        ToolLibrary library=app.library;
        ToneDocument blank=new ToneDocument(320,480);
        byte[] blankPixels=blank.snapshot();
        try {
            main(test,() -> {
                app.rotationPrompt.orientationSensor.disable();
                app.pad.finishStroke();app.pad.dryWet();app.drawingName="";app.library=new ToolLibrary();
                replaceBook(app,new DrawingBook(blank));app.paint.eraseMode=false;
            });
            if(pagesOnly) {PageNavigationChecks.runFeedback(test,app,report);return;}
            for(int turn=0;turn<4;turn++) for(boolean right:new boolean[]{false,true}) {
                final int quarter=turn;
                main(test,() -> {
                    prefs.edit().putBoolean("toolbox_right",right).apply();
                    app.requestQuarter(quarter);app.applyToolboxSide();
                });
                test.waitForIdleSync();SystemClock.sleep(200);
                ControlFeedbackChecks.wetness(test,app);
                for(int tool:new int[]{MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER}) {
                    long start=SystemClock.uptimeMillis();
                    // Include reversals, black/white endpoints and an immediate release.
                    float[] positions={.5f,.1f,.9f,.3f,.7f,0,1,.45f,.45f};
                    for(int i=0;i<positions.length;i++) {
                        final int action=i==0?MotionEvent.ACTION_DOWN:i==positions.length-1?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
                        final float fraction=positions[i];
                        main(test,() -> {
                            View picker=app.shadePicker;
                            Bitmap before=capture(picker);int submitted=app.selectionFeedback.submitted;
                            MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=tool;
                            MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();
                            point.x=fraction*picker.getWidth();point.y=picker.getHeight()/2f;point.pressure=action==MotionEvent.ACTION_UP?0:.5f;
                            MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,1,
                                    new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},
                                    0,0,1,1,0,0,tool==MotionEvent.TOOL_TYPE_STYLUS?android.view.InputDevice.SOURCE_STYLUS:android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
                            try {picker.dispatchTouchEvent(event);} finally {event.recycle();}
                            Bitmap after=capture(picker);Rect dirty=SelectionFeedback.difference(before,after);
                            if(!dirty.isEmpty()) {
                                check(app.selectionFeedback.submitted>submitted,"Every changed marker is submitted immediately");
                                int mode=DirectEink.fastBinaryControls()?9:7;
                                check(app.selectionFeedback.lastDisplayMode==mode,"Correct device-specific request");
                                DirectEink display=DirectEink.forView(picker,after,new Matrix(),mode==9?1:0,mode);
                                try {
                                    check(display.panelRegion(dirty).equals(app.selectionFeedback.lastScreenRegion),"Request covers old and new marker in driver coordinates");
                                    check(display.panelRegion(new Rect(0,0,picker.getWidth(),picker.getHeight())).contains(app.selectionFeedback.lastScreenRegion),"Request stays within color bar");
                                    // These planes are shared scratch: firmware/compositor may
                                    // rewrite them after ioctl. A later read is not a snapshot
                                    // of what our request submitted or what the panel displays.
                                    check(display.present(after,dirty)==0,"Unchanged pixels submit no request");
                                    if(mode==9) {
                                        Bitmap invalid=after.copy(Bitmap.Config.ARGB_8888,true);
                                        invalid.setPixel(dirty.left,dirty.top,Color.GRAY);
                                        boolean rejected=false;
                                        try {display.present(invalid,dirty);} catch(IllegalStateException expected) {rejected=true;}
                                        invalid.recycle();check(rejected,"Gray pixels rejected by binary path");
                                    }
                                } finally {display.close();}
                            }
                            before.recycle();after.recycle();
                        });
                        SystemClock.sleep(8);
                    }
                }
            }
            ControlFeedbackChecks.controls(test,app,report);
            main(test,() -> check(Arrays.equals(blankPixels,blank.snapshot())&&!blank.canUndo(),"Color gestures never paint or add undo"));
            report.append("PASS: color bar pen/finger drags and reversals, immediate final position, four rotations/both hands, bounded old/new marker requests, no-op suppression, binary format validation, and no canvas changes. mode=")
                    .append(DirectEink.fastBinaryControls()?9:7).append(". Physical trailing requires visual confirmation.\n");
        } finally {
            main(test,() -> {
                app.closePagePanel();
                if(pagesOnly) {
                    app.setSimulating(nomad);
                    if(!hadNomad)prefs.edit().remove("nomad_mode").apply();
                }
                if(hadHand)prefs.edit().putBoolean("toolbox_right",hand).apply();else prefs.edit().remove("toolbox_right").apply();
                app.paint.gray=gray;app.paint.eraseMode=erase;app.drawingName=name;app.library=library;
                app.paint.wetness=wetness;app.paint.wetCanvas=wetCanvas;
                replaceBook(app,book);app.requestQuarter(rotation);app.applyToolboxSide();
                app.saveToolState();app.recovery();
            });
            TestSessionSave.await(app);
        }
    }
    private static Bitmap capture(View view) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));return bitmap;
    }
    private static void replaceBook(PaintActivity app,DrawingBook book) throws Exception {
        java.lang.reflect.Method method=PaintActivity.class.getDeclaredMethod("replaceBook",DrawingBook.class);
        method.setAccessible(true);method.invoke(app,book);
    }
    private interface Work {void run() throws Exception;}
    private static void main(Instrumentation test,Work work) throws Exception {
        Throwable[] failure={null};test.runOnMainSync(() -> {try {work.run();} catch(Throwable error) {failure[0]=error;}});
        if(failure[0]!=null)throw new Exception(failure[0]);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
