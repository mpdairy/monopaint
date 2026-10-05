package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** App-local replay on an isolated temporary document; restores the user's document afterward. */
final class PaintChecks {
    private static void canvasPaintRaster() {
        for (ToolSettings.Head head : ToolSettings.Head.values())
            for (ToolSettings.Tool tool : new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH, ToolSettings.Tool.WATERCOLOR,
                    ToolSettings.Tool.WET_WATERCOLOR, ToolSettings.Tool.FLAT_WASH}) {
                byte[] base = new byte[180*140]; Arrays.fill(base, (byte)180);
                ToneDocument doc = new ToneDocument(180,140,base), footprint = new ToneDocument(180,140);
                ToolSettings settings = ToolSettings.defaults(tool).head(head).size(48);
                PressureStroke paint = new PressureStroke(doc,settings,128,null,true);
                PressureStroke shape = new PressureStroke(footprint,settings.asBrush(),0);
                for (int x=10;x<170;x+=4) {
                    paint.sample(x,70,.45f); shape.sample(x,70,.45f);
                    paint.sample(x,70,.45f);
                }
                paint.finish(); shape.finish();
                for (int y=0;y<140;y++) for (int x=0;x<180;x++)
                    check(doc.tone(x,y)==(footprint.tone(x,y)==0?135:180),"All heads and legacy presets use one translucent glaze per stroke");
                check(doc.undo()&&Arrays.equals(base,doc.snapshot()),"Transparent raster undoes exactly");
                WetWatercolor wet = new WetWatercolor(doc,0);
                paint = new PressureStroke(doc,settings,255,wet,false);
                paint.sample(80,70,.45f);paint.finish();
                while(wet.isAnimating())wet.advance(false);
                check(doc.tone(80,70)==255,"Opaque white on a zero-wetness canvas covers the old tone");
            }
    }
    private static void wetWatercolorRaster() {
        for (ToolSettings.Head head : ToolSettings.Head.values()) {
            ToneDocument document = new ToneDocument(180, 140), footprint = new ToneDocument(180, 140);
            ToolSettings settings = ToolSettings.defaults(ToolSettings.Tool.WET_WATERCOLOR).head(head).size(48).angle(35);
            WetWatercolor wet = new WetWatercolor(document);
            PressureStroke wash = new PressureStroke(document, settings, 170, wet);
            PressureStroke brush = new PressureStroke(footprint, ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head).size(48).angle(35), 170);
            for (int x=20;x<=150;x+=10) {
                wash.sample(x,70,.45f); brush.sample(x,70,.45f);
            }
            wash.finish(); brush.finish();
            check(Arrays.equals(document.snapshot(), footprint.snapshot()), "Wet brush shares the pressure raster for " + head);
            byte[] first = document.snapshot();
            wash = new PressureStroke(document, settings, 30, wet); wash.sample(80,70,.45f);
            wet.advance(true);
            check(document.tone(80,70)>30, "Small strokes mix while the pen remains down");
            wash.finish();
            while(wet.isAnimating()) wet.advance(false);
            byte[] mixed = document.snapshot();
            check(document.undo() && Arrays.equals(document.snapshot(), first), "Wet raster and animation undo together");
            check(document.redo() && Arrays.equals(document.snapshot(), mixed), "Wet raster redoes exactly");
        }
    }
    static void run(Instrumentation test, StringBuilder report, boolean brushOnly) throws Exception {
        rasterBaseline(); report.append("Logical brush matches 0.12 circle rasterization for all 16 shades.\n");
        canvasPaintRaster(); report.append("Canvas paint modes share every brush head and legacy preset footprint, with one glaze per stroke and exact undo.\n");
        watercolorRaster(); report.append("Watercolor follows brush footprints and deposits only black dots over existing tones.\n");
        flatWashRaster(); report.append("Flat wash shares brush heads, pressure, tilt and clipping while retaining darker marks and exact gray tones.\n");
        wetWatercolorRaster(); report.append("Wet watercolor shares pressure/head footprints and keeps its animation in one undo step.\n");
        brushHeadRaster();report.append("Flat/filbert raster, rotated bounds, thin marks and gap-free interpolated strokes pass.\n");
        headThicknessRaster();wideFlatRaster();report.append("Flat height spans 1px to 20% through 256px width; rotated strokes stay continuous and Filbert retains its thickness.\n");
        roundedFilbertRaster(test);
        smoothEdgeRaster(test,report);
        headingOnlyRaster();report.append("Tilt rotates Flat/Filbert without changing size, shape or contact center.\n");
        flatLeanPressureRaster();report.append("Flat and Filbert respect pressure-sized widths under tilt, including continuous 1px hairlines.\n");
        brushTiltRaster();report.append("Brush tilt follows signed lean direction with angle offset, upright fallback, shortest turns and exact undo.\n");
        solidBrushRaster();report.append("All brush heads stay solid with legacy bristle settings, light/firm pressure, push/pull, fixed/tilted heads and exact undo.\n");
        rubbingChecks(report);
        stumpStrengthChecks(report);
        sizeRangeChecks(report);
        Intent intent = new Intent(test.getTargetContext(), PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PaintActivity activity = (PaintActivity)test.startActivitySync(intent);
        PaintActivity current = activity;
        awaitReady(test, current);
        Object pad = field(current, "pad");
        ToneDocument original = (ToneDocument)field(pad, "document");
        DrawingBook originalBook=(DrawingBook)field(current,"book");
        String originalName=(String)field(current,"drawingName");
        boolean originalSide=((android.content.SharedPreferences)field(current,"preferences")).getBoolean("toolbox_right",false);
        int originalGray = (Integer)field(get(current,"paint"),"gray"), originalMaximum = TestAccess.maximum(current);
        ToolLibrary originalLibrary = (ToolLibrary)field(current,"library");
        boolean originalErase = (Boolean)field(get(current,"paint"),"eraseMode");
        boolean originalWet = (Boolean)field(get(current,"paint"),"wetCanvas"), originalTransparent = (Boolean)field(get(current,"paint"),"transparentPaint");
        int originalWetness = (Integer)field(get(current,"paint"),"wetness");
        try {
            Object firstPad = pad;
            PaintActivity firstActivity = current;
            test.runOnMainSync(() -> {
                call(firstPad, "replace", new Class<?>[]{ToneDocument.class}, new ToneDocument(original.width, original.height));
                set(firstActivity, "drawingName", "");
                set(get(firstActivity,"paint"),"gray", 128); TestAccess.setMaximum(firstActivity, 64);
                set(get(firstActivity,"paint"),"eraseMode",false);set(get(firstActivity,"paint"),"wetCanvas",false); set(get(firstActivity,"paint"),"transparentPaint",false);
                call(firstActivity,"refreshPaintModes",new Class<?>[0]);
                set(firstActivity,"library",new ToolLibrary());
                call(get(firstActivity,"toolbar"),"rebuildTools",new Class<?>[0]);
            });
            test.waitForIdleSync(); SystemClock.sleep(300);
            if (brushOnly) {
                brushTiltInput(test,current,report);
                brushHeadUi(test,current,report);
                favoriteUi(test,current,report);
                adaptiveWetReplay(test,current,report);
                flowingWetReplay(test,current,report);
                longStroke(test,current,report,false);
                longStroke(test,current,report,true);
                wetWatercolorUi(test,current,report);
                return;
            }
            check(field(pad, "direct") != null, "Direct mode 7 display active");
            checkFirmwareArea(pad);
            report.append("Firmware default drawing area is replaced by an empty region.\n");
            ToneDocument doc = (ToneDocument)field(pad, "document");
            View view = (View)pad;
            long start = SystemClock.uptimeMillis();
            int drawCount = (Integer)field(pad, "drawCount");
            test.runOnMainSync(() -> {
                event(view, start, MotionEvent.ACTION_DOWN, 100, 150, .1f);
                event(view, start, MotionEvent.ACTION_MOVE, 260, 150, .45f);
                event(view, start, MotionEvent.ACTION_MOVE, 420, 150, .1f);
            });
            // Let the documented 8 ms coalescing interval elapse, then give it
            // another pen sample. A burst of replayed events can otherwise end
            // before its pending pixels are due for submission.
            SystemClock.sleep(12);
            final int[] driverPixel = new int[2];
            test.runOnMainSync(() -> {
                // Read the newly submitted patch immediately. The driver map
                // is shared with the compositor, not a retained screenshot.
                event(view,start,MotionEvent.ACTION_MOVE,428,150,.1f);
                try {
                    driverPixel[0] = ((DirectEink)field(firstPad,"direct")).readGray(428,150);
                    driverPixel[1] = (((Bitmap)field(firstPad,"display")).getPixel(428,150) >>> 16 & 255) / 16;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            check(doc.tone(260,150) == 128, "Logical gray while pen is down");
            int[] bounds = doc.dirty(); check(bounds == null, "Dirty tones rendered before pen-up");
            check(driverPixel[0] == driverPixel[1], "Driver receives derived dots: expected " + driverPixel[1] + ", actual " + driverPixel[0]);
            test.runOnMainSync(() -> event(view, start, MotionEvent.ACTION_UP, 428, 150, 0));
            test.waitForIdleSync(); SystemClock.sleep(100);
            check((Integer)field(pad, "drawCount") == drawCount, "No Android redraw during stroke or pen-up");
            check(doc.canUndo() && doc.tone(260,150) == 128, "Stroke committed");
            report.append("Gray paints live, driver pixels agree, and pen-up causes no Android redraw.\n");

            test.runOnMainSync(() -> {
                set(get(firstActivity,"paint"),"gray", 0); TestAccess.setMaximum(firstActivity, 64);
                event(view, start, MotionEvent.ACTION_DOWN, 260, 270, .45f);
                event(view, start, MotionEvent.ACTION_UP, 260, 270, 0);
                set(get(firstActivity,"paint"),"gray", 255); TestAccess.setMaximum(firstActivity, 16);
                event(view, start, MotionEvent.ACTION_DOWN, 260, 270, .45f);
                event(view, start, MotionEvent.ACTION_UP, 260, 270, 0);
            });
            check(doc.tone(260,270) == 255 && doc.tone(280,270) == 0, "White over logical black preserves surround");
            test.runOnMainSync(() -> findButton(firstActivity, "Undo").performClick());
            check(doc.tone(260,270) == 0, "Undo white stroke");
            test.runOnMainSync(() -> findButton(firstActivity, "Redo").performClick());
            check(doc.tone(260,270) == 255, "Redo white stroke");
            test.runOnMainSync(() -> { set(get(firstActivity,"paint"),"gray", 128); TestAccess.setMaximum(firstActivity, 128); });
            long largeStart = SystemClock.uptimeMillis();
            test.runOnMainSync(() -> {
                event(view, start, MotionEvent.ACTION_DOWN, 200, 450, .45f);
                for (int x = 208; x <= 1000; x += 8) event(view, start, MotionEvent.ACTION_MOVE, x, 450, .45f);
                event(view, start, MotionEvent.ACTION_UP, 1000, 450, 0);
            });
            long largeMillis = SystemClock.uptimeMillis() - largeStart;
            int diameter = 0;
            for (int y = 350; y < 550; y++) if (doc.tone(600,y) != 255) diameter++;
            check(diameter == 128, "Maximum brush diameter is 128 px");
            report.append("128 px brush stays capped; 101 controlled samples and commit took " + largeMillis + " ms (not panel latency).\n");
            checkTools(test,firstActivity,firstPad,doc,report);
            checkToolTaps(test,firstActivity,doc,report);
            checkLargeFill(test,firstActivity,firstPad,doc,report);
            checkSelectionFeedback(test,firstActivity,doc,report);
            byte[] expected = doc.snapshot();
            test.runOnMainSync(() -> {
                event(view, start, MotionEvent.ACTION_DOWN, 600, 500, 1, MotionEvent.TOOL_TYPE_FINGER);
                event(view, start, MotionEvent.ACTION_UP, 600, 500, 0, MotionEvent.TOOL_TYPE_FINGER);
            });
            check(Arrays.equals(expected, doc.snapshot()), "Palm/finger ignored");
            report.append("Black/white share the logical document; undo, redo and palm rejection pass.\n");
            checkPicker(test, current, report);
            DocumentStore store = (DocumentStore)field(current, "store");
            final Exception[] error = new Exception[1];
            store.recoverLater(new DocumentStore.Snapshot(doc), (value, failure) -> error[0] = failure);
            // Queue a read behind the coalescing writer; this is a durability barrier even if another save was pending.
            CountDownLatch loaded = new CountDownLatch(1);
            final ToneDocument[] restored = new ToneDocument[1];
            store.open("_recovery", (value, failure) -> { restored[0] = value; error[0] = failure; loaded.countDown(); });
            check(loaded.await(10, TimeUnit.SECONDS) && error[0] == null, "Recovery IO completed");
            check(Arrays.equals(expected, restored[0].snapshot()), "Atomic recovery contains logical tones");
            // Recreated stores must share the old Activity's writer queue.
            CountDownLatch nextRead = new CountDownLatch(1);
            store.recoverLater(new DocumentStore.Snapshot(doc), (value, failure) -> error[0] = failure);
            new DocumentStore(current.getFilesDir()).open("_recovery", (value, failure) -> {
                restored[0] = value; error[0] = failure; nextRead.countDown();
            });
            check(nextRead.await(10,TimeUnit.SECONDS) && error[0] == null
                    && Arrays.equals(expected,restored[0].snapshot()), "New store reads after previous store's save");
            namedSave(test,doc);
            report.append("Named local save/open and cross-Activity IO ordering pass.\n");
            byte[] expectedActive=checkPages(test,current,report);
            test.runOnMainSync(current::finish); test.waitForIdleSync();
            current = (PaintActivity)test.startActivitySync(intent); awaitReady(test, current);
            pad = field(current, "pad");
            check(Arrays.equals(expectedActive, ((ToneDocument)field(pad,"document")).snapshot()), "Activity restart recovers active page");
            DrawingBook recoveredBook=(DrawingBook)field(current,"book");
            check(recoveredBook.count()==2&&recoveredBook.index()==1,"Restart restores page count and position");
            int[] origin=new int[2];((View)pad).getLocationOnScreen(origin);
            check(origin[0]==0&&((android.content.SharedPreferences)field(current,"preferences")).getBoolean("toolbox_right",false),"Right-side toolbox survives restart");
            ToolLibrary restarted=(ToolLibrary)field(current,"library");
            check(restarted.presets().size()==1&&restarted.presets().get(0).name.equals("Renamed check preset")
                    &&restarted.presets().get(0).settings.soft&&restarted.presets().get(0).settings.maximum==9,"Edited custom settings survive Activity restart");
            report.append("Atomic recovery and Activity restart preserve exact logical tones.\n");
            customToolbarChecks(test, current, report);
            brushTiltInput(test, current, report);
            brushHeadUi(test,current,report);
            presetDragChecks(test, current, report);
            adaptiveWetReplay(test,current,report);
            wetWatercolorUi(test, current, report);
        } finally {
            Object restorePad = field(current, "pad"); PaintActivity restoreActivity = current;
            test.runOnMainSync(() -> {
                call(restoreActivity, "replaceBook", new Class<?>[]{DrawingBook.class}, originalBook);
                set(restoreActivity, "drawingName", originalName);
                try {((android.content.SharedPreferences)field(restoreActivity,"preferences")).edit().putBoolean("toolbox_right",originalSide).apply();}
                catch(Exception error){throw new IllegalStateException(error);}
                call(restoreActivity,"applyToolboxSide",new Class<?>[0]);
                set(get(restoreActivity,"paint"),"gray", originalGray); TestAccess.setMaximum(restoreActivity, originalMaximum);
                set(get(restoreActivity,"paint"),"wetCanvas",originalWet); set(get(restoreActivity,"paint"),"transparentPaint",originalTransparent);
                set(get(restoreActivity,"paint"),"wetness",originalWetness);
                call(restoreActivity,"refreshPaintModes",new Class<?>[0]);
                set(restoreActivity,"library",originalLibrary);set(get(restoreActivity,"paint"),"eraseMode",originalErase);
                call(get(restoreActivity,"toolbar"),"rebuildTools",new Class<?>[0]);
                call(restoreActivity,"saveToolState", new Class<?>[0]);
                try { ((View)field(restoreActivity, "shadePicker")).invalidate(); ((View)field(restoreActivity, "wetnessBar")).invalidate(); }
                catch (Exception error) { throw new IllegalStateException(error); }
                call(restoreActivity, "recovery", new Class<?>[0]);
            });
            CountDownLatch restored = new CountDownLatch(1);
            ((DocumentStore)field(current,"store")).open("_recovery", (d,e) -> restored.countDown());
            check(restored.await(10, TimeUnit.SECONDS), "Original document restoration completed");
            TestSessionSave.await(current);
        }
    }
    // Propagate UI assertions to the test thread so its finally block can restore
    // the original drawing instead of terminating the app's main thread.
    private static void onMain(Instrumentation test, Runnable action) {
        final Throwable[] failure = new Throwable[1];
        test.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Error) throw (Error)failure[0];
        if (failure[0] instanceof RuntimeException) throw (RuntimeException)failure[0];
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
    }
    private static void wetWatercolorUi(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        Object pad = field(activity, "pad"); View view = (View)pad;
        ToneDocument blank = new ToneDocument(view.getWidth(), view.getHeight());
        ToolLibrary library = new ToolLibrary();
        SelectionFeedback feedback = (SelectionFeedback)field(activity,"selectionFeedback");
        boolean originalFast = feedback.enabled;
        long start = SystemClock.uptimeMillis();
        onMain(test, () -> {
            call(pad, "replace", new Class<?>[]{ToneDocument.class}, blank);
            set(activity,"library",library); TestAccess.setMaximum(activity,64); set(get(activity,"paint"),"gray",180);
            set(get(activity,"paint"),"wetCanvas",false); set(get(activity,"paint"),"transparentPaint",false);
            call(activity,"refreshPaintModes",new Class<?>[0]); call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);
        });
        test.waitForIdleSync();
        View bar = (View)field(activity,"wetnessBar");
        byte[] beforeControls = blank.snapshot();
        try {
            for (boolean fast : new boolean[]{false,true}) {
                onMain(test, () -> {
                    feedback.enabled = fast;
                    check(findButton(activity,"Wet watercolor (experimental)")==null && findButton(activity,"Watercolor")==null
                            && findButton(activity,"Flat wash")==null && findButton(activity,"Dry watercolor")==null,"Redundant tools and dryer removed");
                    Button droplet=findButton(activity,"Wet canvas"); droplet.performClick();
                    check(droplet.isSelected(),"Droplet is selected in both feedback modes");
                    Bitmap selected=controlBitmap(droplet);
                    int inset=Math.round(12*activity.getResources().getDisplayMetrics().density);
                    check(selected.getPixel(inset,inset)==Color.BLACK,"Wet selection shows its dot clear of the outline");
                    int iconCenter=selected.getWidth()/2+Math.round(8*activity.getResources().getDisplayMetrics().density);
                    check(selected.getPixel(iconCenter,selected.getHeight()/2)==Color.WHITE,"Water droplet has a clear interior"); selected.recycle();
                    findButton(activity,"Transparent paint").performClick();
                    check(findButton(activity,"Transparent paint").isSelected()&&!findButton(activity,"Opaque paint").isSelected(),"Paint modes are mutually exclusive");
                    event(bar,start,MotionEvent.ACTION_DOWN,bar.getWidth()/2f,bar.getHeight(),.3f,MotionEvent.TOOL_TYPE_FINGER);
                    check(!droplet.isSelected(),"Dragging to zero switches wet mode off");
                    event(bar,start,MotionEvent.ACTION_MOVE,bar.getWidth()/2f,0,.3f,MotionEvent.TOOL_TYPE_FINGER);
                    event(bar,start,MotionEvent.ACTION_UP,bar.getWidth()/2f,0,0,MotionEvent.TOOL_TYPE_FINGER);
                    try {check((Integer)field(get(activity,"paint"),"wetness")==100,"Wetness drag reaches full strength");}
                    catch(Exception error){throw new IllegalStateException(error);}
                    droplet.performClick();
                    event(bar,start,MotionEvent.ACTION_DOWN,bar.getWidth()/2f,bar.getHeight()/2f,.3f,MotionEvent.TOOL_TYPE_FINGER);
                    event(bar,start,MotionEvent.ACTION_UP,bar.getWidth()/2f,bar.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
                    check(droplet.isSelected(),"Changing the bar enables wet mode without a droplet tap");
                    try {
                        int preferred=(Integer)field(get(activity,"paint"),"wetness");
                        droplet.performClick();
                        check(!droplet.isSelected()&&(Integer)field(get(activity,"paint"),"wetness")==preferred,"Droplet off retains preferred strength");
                        // A tap at exactly the retained value must still enable wet mode.
                        event(bar,start,MotionEvent.ACTION_DOWN,bar.getWidth()/2f,bar.getHeight()/2f,.3f,MotionEvent.TOOL_TYPE_FINGER);
                        event(bar,start,MotionEvent.ACTION_UP,bar.getWidth()/2f,bar.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
                        check(droplet.isSelected()&&(Integer)field(get(activity,"paint"),"wetness")==preferred,"Same-value slider tap enables wet mode");
                        droplet.performClick();
                        android.content.SharedPreferences prefs=(android.content.SharedPreferences)field(activity,"preferences");
                        check(!prefs.getBoolean("wet_canvas",true)&&prefs.getInt("canvas_wetness",0)==preferred,"Droplet off preserves saved wetness");
                    } catch(Exception error){throw new IllegalStateException(error);}
                    findButton(activity,"Opaque paint").performClick();
                });
            }
            check(Arrays.equals(beforeControls,blank.snapshot())&&!blank.canUndo(),"Canvas controls never enter pixels or history");
            onMain(test, () -> {
                findButton(activity,"Wet canvas").performClick();
                event(view,start,MotionEvent.ACTION_DOWN,100,100,.45f);
                event(view,start,MotionEvent.ACTION_MOVE,300,100,.45f);
                event(view,start,MotionEvent.ACTION_UP,300,100,0);
            });
            final byte[][] first = new byte[1][];
            onMain(test, () -> {
                // Freeze the reference on the document's owner thread immediately before
                // the next stroke; queued seep frames must not run between these actions.
                first[0] = blank.snapshot();
                set(get(activity,"paint"),"gray",30);event(view,start,MotionEvent.ACTION_DOWN,200,100,.45f);
                check(blank.tone(200,100)==30,"Normal brush puts fresh wet paint down immediately");
                try { check((Boolean)field(pad,"wetScheduled"),"Small strokes allow scheduled live blending"); }
                catch(Exception error){throw new IllegalStateException(error);}
            });
            // Seep work intentionally yields to input/display load. Wait for the
            // visible change on its owner thread instead of assuming 550ms is enough.
            long blendDeadline=SystemClock.uptimeMillis()+2500;
            boolean[] blended={false};
            while(!blended[0] && SystemClock.uptimeMillis()<blendDeadline) {
                SystemClock.sleep(50);
                onMain(test,() -> blended[0]=blank.tone(200,100)>30);
            }
            onMain(test, () -> {
                try {
                    check(blank.tone(200,100)>30,"Small strokes visibly blend while pen is down; slices="
                            +field(pad,"wetSliceCount")+", compute_ns="+field(pad,"lastWetComputeNanos")
                            +", render_ns="+field(pad,"lastWetRenderNanos")+", submit_ns="+field(pad,"lastWetPresentNanos")
                            +", pending="+field(pad,"pending"));
                } catch(Exception error){throw new IllegalStateException(error);}
                event(view,start,MotionEvent.ACTION_MOVE,260,100,.45f);
                check(blank.tone(260,100)==30,"Fresh tip stays crisp");
                event(view,start,MotionEvent.ACTION_UP,260,100,0);
                try { check((Boolean)field(pad,"wetScheduled"),"Pen-up resumes queued blending"); }
                catch(Exception error){throw new IllegalStateException(error);}
            });
            SystemClock.sleep(2300);
            onMain(test, () -> {
                check(blank.tone(260,100)>30,"Blending continues after pen-up");
                check(DotPattern.whiteCount(blank.tone(260,100))>DotPattern.whiteCount(30),"Settled mid-strength blending changes the visible dot density");
                event(bar,start,MotionEvent.ACTION_DOWN,bar.getWidth()/2f,bar.getHeight(),.3f,MotionEvent.TOOL_TYPE_FINGER);
                event(bar,start,MotionEvent.ACTION_UP,bar.getWidth()/2f,bar.getHeight(),0,MotionEvent.TOOL_TYPE_FINGER);
                check(!findButton(activity,"Wet canvas").isSelected(),"Zero slider clears the wet-mode dot");
            });
            byte[] dried = blank.snapshot(); SystemClock.sleep(450);
            check(field(pad,"wet")==null&&Arrays.equals(dried,blank.snapshot()),"Zero wetness stops all queued animation");
            onMain(test, () -> {
                findButton(activity,"Undo").performClick();check(Arrays.equals(first[0],blank.snapshot()),"One undo removes wet stroke and its frames");
                findButton(activity,"Redo").performClick();check(Arrays.equals(dried,blank.snapshot()),"Redo restores exact result");
                findButton(activity,"Transparent paint").performClick(); set(get(activity,"paint"),"gray",255);
                event(view,start,MotionEvent.ACTION_DOWN,200,100,.45f);event(view,start,MotionEvent.ACTION_UP,200,100,0);
                check(Arrays.equals(dried,blank.snapshot()),"Dry transparent white is clear");
                findButton(activity,"Opaque paint").performClick();
                event(view,start,MotionEvent.ACTION_DOWN,200,100,.45f);event(view,start,MotionEvent.ACTION_UP,200,100,0);
                check(blank.tone(200,100)==255,"Dry opaque white covers paint");
            });
            report.append("Outline droplet, slider activation, zero/off, remembered strength, fast selection, live adaptive blending, transparent/opaque white, drying and exact undo/redo pass.\n");
        } finally {
            onMain(test, () -> {
                feedback.enabled=originalFast;set(get(activity,"paint"),"wetCanvas",false);set(get(activity,"paint"),"transparentPaint",false);
                call(activity,"refreshPaintModes",new Class<?>[0]);
                call(pad,"replace",new Class<?>[]{ToneDocument.class},new ToneDocument(blank.width,blank.height));
            });
        }
    }
    private static void adaptiveWetReplay(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        Object pad = field(activity,"pad"); View view = (View)pad;
        for (boolean large : new boolean[]{false, true}) {
            byte[] base = new byte[768*512]; Arrays.fill(base,(byte)180);
            ToneDocument doc = new ToneDocument(768,512,base);
            WetWatercolor wet = new WetWatercolor(doc,100);
            int width = large ? 720 : 160, height = large ? 384 : 128;
            int[] mask = new int[width*height]; Arrays.fill(mask,0xff000000);
            doc.begin(); wet.beginStroke(); wet.paintMask(mask,width,16,32,width,height,120); wet.finishStroke();
            onMain(test, () -> {
                call(pad,"replace",new Class<?>[]{ToneDocument.class},doc);
                set(activity,"library",new ToolLibrary()); TestAccess.setMaximum(activity,large?128:24);
                set(get(activity,"paint"),"gray",30); set(get(activity,"paint"),"wetCanvas",true); set(get(activity,"paint"),"wetness",100);
                set(get(activity,"paint"),"transparentPaint",false); set(pad,"wet",wet);
                call(pad,"renderDirty",new Class<?>[0]); call(pad,"present",new Class<?>[0]);
                set(pad,"wetSliceCount",0); set(pad,"wetMaxSliceNanos",0L);
            });
            test.waitForIdleSync();
            long start = SystemClock.uptimeMillis(); long[] pen = new long[36];
            long[] stageMax = new long[3];
            onMain(test, () -> event(view,start,MotionEvent.ACTION_DOWN,80,160,.45f));
            for (int i=0;i<pen.length;i++) {
                final int sample = i;
                onMain(test, () -> {
                    long begin = System.nanoTime();
                    event(view,start,MotionEvent.ACTION_MOVE,80+sample*(large?16:2),160+(float)Math.sin(sample*.15)*36,.45f);
                    pen[sample] = System.nanoTime()-begin;
                    try {
                        stageMax[0] = Math.max(stageMax[0],(Long)field(pad,"lastWetComputeNanos"));
                        stageMax[1] = Math.max(stageMax[1],(Long)field(pad,"lastWetRenderNanos"));
                        stageMax[2] = Math.max(stageMax[2],(Long)field(pad,"lastWetPresentNanos"));
                    } catch(Exception error){throw new IllegalStateException(error);}
                });
                SystemClock.sleep(12);
            }
            // A stationary pen should regain live blending after a costly event.
            SystemClock.sleep(250);
            onMain(test, () -> {
                check(wet.strokePixels() > (large ? 32768 : 0),"Replay grows the active stroke");
                try {
                    check((Integer)field(pad,"wetSliceCount")>0,"Live seeping uses available time during a held stroke");
                    Arrays.sort(pen);
                    report.append(String.format(java.util.Locale.US,
                            "Adaptive wet %s: stroke_pixels=%d wet_tiles=%d pen_p95_ms=%.2f pen_max_ms=%.2f seep_slices=%d seep_max_ms=%.2f sampled_compute_max_ms=%.2f raster_max_ms=%.2f submit_max_ms=%.2f (CPU, not panel latency).%n",
                            large?"large":"small",wet.strokePixels(),wet.activeTiles(),pen[34]/1e6,pen[35]/1e6,
                            (Integer)field(pad,"wetSliceCount"),(Long)field(pad,"wetMaxSliceNanos")/1e6,
                            stageMax[0]/1e6,stageMax[1]/1e6,stageMax[2]/1e6));
                } catch(Exception error){throw new IllegalStateException(error);}
                event(view,start,MotionEvent.ACTION_CANCEL,80,160,0);
                try { check((Boolean)field(pad,"wetScheduled"),"Ending a gesture keeps remaining blending scheduled"); }
                catch(Exception error){throw new IllegalStateException(error);}
                call(pad,"dryWet",new Class<?>[0]);
            });
        }
    }
    private static void flowingWetReplay(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        DrawingPad pad = activity.pad;
        ToneDocument doc = new ToneDocument(((ToneDocument)field(pad,"document")).width, ((ToneDocument)field(pad,"document")).height);
        doc.begin();
        for (int y = 100; y < 400; y++) for (int x = 80; x < 250; x++) doc.paintTone(x, y, 40);
        doc.finish();
        byte[] before = doc.snapshot();
        onMain(test, () -> {
            pad.replace(doc);
            activity.library = new ToolLibrary();
            activity.library.select(ToolSettings.Tool.WET_BRUSH_PEN);
            activity.library.edit(ToolSettings.defaults(ToolSettings.Tool.WET_BRUSH_PEN).size(128).minimum(128));
            activity.paint.wetCanvas = false; activity.paint.eraseMode = false;
            pad.renderDirty(); pad.present();
        });
        test.waitForIdleSync(); SystemClock.sleep(100);
        check(field(pad, "direct") != null, "Wet brush pen uses the direct display");
        int draws = (Integer)field(pad, "drawCount");
        long start = SystemClock.uptimeMillis();
        onMain(test, () -> event(pad, start, MotionEvent.ACTION_DOWN, 120, 220, .8f));
        for (int i = 1; i <= 40; i++) {
            final int x = 120 + i * 10;
            onMain(test, () -> event(pad, start, MotionEvent.ACTION_MOVE, x, 220, .8f));
            SystemClock.sleep(12);
        }
        onMain(test, () -> event(pad, start, MotionEvent.ACTION_UP, 520, 220, 0));
        SystemClock.sleep(1500);
        onMain(test, () -> {
            check(pad.wet != null && pad.wet.strokePixels() > 40000, "Wide flowing pen wets its full path");
            check(!Arrays.equals(before, doc.snapshot()), "Flowing water moves the existing ink");
            check((Integer)get(pad, "drawCount") == draws && get(pad, "direct") != null,
                    "Flowing pen and its animation retain direct presentation without Android redraws");
            pad.dryWet();
            check(doc.undo() && Arrays.equals(before, doc.snapshot()), "Flowing pen and animation undo together");
        });
        report.append("Wet brush pen: real input and animation use direct e-ink with no Android redraws; ink moves and one undo restores the page.\n");
    }
    /** One long held stroke with a large brush: the pen must keep up to the end, wet canvas or not. */
    private static void longStroke(Instrumentation test, PaintActivity activity, StringBuilder report, boolean wetCanvas) throws Exception {
        Object pad = field(activity,"pad"); View view = (View)pad;
        // A full page, not the small page an earlier replay may have left.
        ToneDocument doc = new ToneDocument(view.getWidth(), view.getHeight());
        onMain(test, () -> {
            call(pad,"dryWet",new Class<?>[0]);
            call(pad,"replace",new Class<?>[]{ToneDocument.class},doc);
            set(activity,"library",new ToolLibrary()); TestAccess.setMaximum(activity,102);
            set(get(activity,"paint"),"gray",30); set(get(activity,"paint"),"wetCanvas",wetCanvas); set(get(activity,"paint"),"wetness",100);
            set(get(activity,"paint"),"transparentPaint",false); set(get(activity,"paint"),"eraseMode",false);
        });
        test.waitForIdleSync();
        // Pen events arrive on a fixed schedule whether or not the app keeps up,
        // so lag is how long after its due time each event is handled.
        int events = 2000, windows = 4, intervalMs = 4;
        long[] pen = new long[events], lag = new long[events];
        float cx = view.getWidth()/2f, cy = view.getHeight()/2f;
        long start = SystemClock.uptimeMillis();
        onMain(test, () -> event(view,start,MotionEvent.ACTION_DOWN,cx,cy,.45f));
        onMain(test, () -> check(get(pad,"stroke") != null && (get(pad,"wet") != null) == wetCanvas, "Long stroke starts on the chosen canvas"));
        onMain(test, () -> set(pad,"wetSliceCount",0));
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        long first = SystemClock.uptimeMillis() + 20;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(events);
        for (int i=0;i<events;i++) {
            final int sample = i; double t = i*.005;
            final float x = cx+(float)(Math.sin(t*1.3)*cx*.8), y = cy+(float)(Math.sin(t*.7)*cy*.8);
            final long due = first + (long)i*intervalMs;
            main.postAtTime(() -> {
                long begin = System.nanoTime();
                lag[sample] = SystemClock.uptimeMillis() - due;
                // Like real input, the event carries the time the pen made it.
                event(view,start,due,MotionEvent.ACTION_MOVE,x,y,.45f,MotionEvent.TOOL_TYPE_STYLUS);
                pen[sample] = System.nanoTime()-begin;
                done.countDown();
            }, due);
        }
        check(done.await(120, java.util.concurrent.TimeUnit.SECONDS), "Long stroke replay completes");
        StringBuilder line = new StringBuilder("Long stroke ("+events+" events every "+intervalMs+"ms, size 102, "+(wetCanvas?"wet":"normal")+" canvas):");
        for (int w=0; w<windows; w++) {
            int from = w*events/windows, to = (w+1)*events/windows;
            long[] p = Arrays.copyOfRange(pen,from,to), r = Arrays.copyOfRange(lag,from,to);
            Arrays.sort(p); Arrays.sort(r);
            line.append(String.format(java.util.Locale.US," [%d-%d pen_p95=%.2fms lag_p50=%dms lag_max=%dms]",
                    from,to,p[(int)(p.length*.95)]/1e6,r[r.length/2],r[r.length-1]));
        }
        int[] seeping = new int[1];
        onMain(test, () -> seeping[0] = (Integer)get(pad,"wetSliceCount"));
        line.append(" seep_slices_while_held=").append(seeping[0]).append(" (CPU, not panel latency).\n");
        report.append(line);
        onMain(test, () -> { event(view,start,MotionEvent.ACTION_UP,cx,cy,0); call(pad,"dryWet",new Class<?>[0]); });
        test.waitForIdleSync();
        long[] late = Arrays.copyOfRange(lag,events*(windows-1)/windows,events); Arrays.sort(late);
        check(late[late.length/2] <= 30, "The end of a long "+(wetCanvas?"wet":"normal")+" stroke keeps up with the pen: "+line);
    }
    private static void saveScreenshot(Instrumentation test, String name) throws Exception {
        Bitmap screen=test.getUiAutomation().takeScreenshot();check(screen!=null,"Can capture brush UI");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getCacheDir(),name))) {
            screen.compress(Bitmap.CompressFormat.PNG,100,out);
        } finally {screen.recycle();}
    }
    private static void favoriteUi(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary library=new ToolLibrary();library.selectHead(ToolSettings.Head.FLAT);library.edit(library.current().size(200).minimum(3));
        ToolSettings regular=library.current();String[] ids=new String[3];
        for(int i=0;i<3;i++){ids[i]=library.add().id;library.edit(library.current().size(20+i*10));}
        library.select(ToolSettings.Tool.BRUSH);
        test.runOnMainSync(() -> {set(activity,"library",library);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);});test.waitForIdleSync();
        test.runOnMainSync(() -> {
            try {
            java.util.Map<?,?> buttons=(java.util.Map<?,?>)field(get(activity,"toolbar"),"selectionButtons");
            for(int i=0;i<3;i++) {
                Button button=(Button)buttons.get(ids[i]);
                check((Integer)field(button,"presetNumber")==i+1,"Favorite button has its matching-type ordinal");
                Bitmap bitmap=Bitmap.createBitmap(button.getWidth(),button.getHeight(),Bitmap.Config.ARGB_8888);button.draw(new Canvas(bitmap));
                int y=button.getHeight()-Math.round(7*activity.getResources().getDisplayMetrics().density),runs=0;boolean previous=false;
                int inset=Math.round(8*activity.getResources().getDisplayMetrics().density);
                for(int x=inset;x<button.getWidth()-inset;x++) {boolean black=Color.red(bitmap.getPixel(x,y))<80;if(black&&!previous)runs++;previous=black;}
                bitmap.recycle();check(runs==i+1,"Favorite icon draws the actual number of separate dots");
                button.performClick();check(library.current().maximum==20+i*10,"Favorite click selects its own width");
                check(library.builtin(ToolSettings.Tool.BRUSH).equals(regular),"Favorite clicks leave regular brush settings intact");
                ((Button)buttons.get("tool:BRUSH")).performClick();check(library.current().equals(regular),"Regular toolbar brush returns to its original settings");
            }
            } catch(Exception error) {throw new IllegalStateException(error);}
        });
        report.append("Favorite toolbar draws one/two/three dots and preserves regular brush settings across real button clicks.\n");
    }
    private static void brushHeadUi(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary library=new ToolLibrary();
        android.content.SharedPreferences prefs=(android.content.SharedPreferences)field(activity,"preferences");
        Object pad=field(activity,"pad");ToneDocument doc=(ToneDocument)field(pad,"document");byte[] before=doc.snapshot();
        test.runOnMainSync(() -> {set(activity,"library",library);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);});
        for(boolean right:new boolean[]{false,true}) {
            test.runOnMainSync(() -> {
                prefs.edit().putBoolean("toolbox_right",right).apply();call(activity,"applyToolboxSide",new Class<?>[0]);
                try {((android.widget.ScrollView)field(get(activity,"toolbar"),"toolScroll")).scrollTo(0,0);}catch(Exception error){throw new IllegalStateException(error);}
            });test.waitForIdleSync();
            for(String name:new String[]{"Brush"}) {
                Button anchor=findButton(activity,name);check(anchor!=null&&anchor.isShown(),name+" is present in the toolbar");
                test.runOnMainSync(() -> {
                    anchor.performClick();
                    try {if (field(activity,"toolPicker")==null) anchor.performClick();}
                    catch(Exception error){throw new IllegalStateException(error);}
                });test.waitForIdleSync();
                android.widget.PopupWindow popup=(android.widget.PopupWindow)field(activity,"toolPicker");
                check(popup!=null&&popup.isShowing(),"Selected brush opens the side editor");
                View panel=popup.getContentView();
                android.graphics.RectF bounds=new android.graphics.RectF(0,0,panel.getWidth(),panel.getHeight());
                PanelCoordinates.fromView(panel).mapRect(bounds);
                View padView=(View)pad;android.graphics.RectF canvas=new android.graphics.RectF(0,0,padView.getWidth(),padView.getHeight());
                PanelCoordinates.fromView(padView).mapRect(canvas);
                check(canvas.contains(bounds),"Brush editor opens beside toolbar inside canvas");
                check(findDescription(panel,"Pressure response")!=null,
                        "Second tap opens current settings alongside choices");
                for(String head:new String[]{"Round","Flat","Filbert"})check(findButton(panel,head)!=null,"Picker includes "+head);
                check(findDescription(panel,"Brush settings")==null,"Head controls need no separate settings button");
                test.runOnMainSync(() -> findButton(panel,"Flat").performClick());test.waitForIdleSync();
                check(popup.isShowing()&&library.current().head==ToolSettings.Head.FLAT&&library.activeId().isEmpty(),
                        "Selecting Flat keeps the editor open with controls underneath");
                int[] tipAt=new int[2],controlAt=new int[2];findButton(panel,"Flat").getLocationOnScreen(tipAt);
                findDescription(panel,"Maximum width").getLocationOnScreen(controlAt);
                check(controlAt[1]>tipAt[1]+findButton(panel,"Flat").getHeight(),"Selected tip controls appear below the tip row");
                int expected=R.drawable.ic_brush_flat;
                check((Integer)field(findButton(activity,name),"iconResource")==expected,"Toolbar updates its enlarged tip icon immediately");
                test.runOnMainSync(() -> {
                    check(findDescription(panel,"Brush angle")==null&&findDescription(panel,"Head thickness")==null
                            &&findDescription(panel,"Follow pen tilt")==null,"Removed controls stay absent");
                    check(((android.widget.SeekBar)findDescription(panel,"Maximum width")).getMax()==254,"Flat width slider reaches 256px");
                    ((android.widget.SeekBar)findDescription(panel,"Maximum width")).setProgress(254);
                    ((android.widget.SeekBar)findDescription(panel,"Pressure response")).setProgress(81);
                    android.widget.SeekBar height=(android.widget.SeekBar)findDescription(panel,"Brush height");
                    check(height!=null&&height.getMax()==20,"Flat height slider reaches 20% width");height.setProgress(20);
                });test.waitForIdleSync();
                if(!right&&name.equals("Brush"))saveScreenshot(test,"flat-brush-settings.png");
                ToolSettings flat=library.current();
                check(flat.maximum==256&&flat.pressureResponse==81&&flat.tilt&&flat.headThickness==20,"Flat retains compact width/pressure/height controls");
                test.runOnMainSync(() -> findButton(panel,"Filbert").performClick());test.waitForIdleSync();
                check(popup.isShowing()&&library.current().head==ToolSettings.Head.FILBERT&&library.current().headThickness==55,
                        "Filbert selection stays open and uses the visibly fuller shape");
                check(findDescription(panel,"Brush height")==null,"Height slider belongs only to Flat");
                check(findButton(panel,"Filbert").isSelected()&&!findButton(panel,"Flat").isSelected(),"Selected tip highlight follows the controls");
                if(!right&&name.equals("Brush"))saveScreenshot(test,"filbert-brush-settings.png");
                test.runOnMainSync(() -> findButton(panel,"Round").performClick());test.waitForIdleSync();
                check(findDescription(panel,"Maximum diameter")!=null&&findDescription(panel,"Maximum width")==null,
                        "Round switches the controls from width to diameter in place");
                if(!right&&name.equals("Brush"))saveScreenshot(test,"brush-menu.png");
                test.runOnMainSync(() -> findButton(panel,"Flat").performClick());test.waitForIdleSync();
                check(library.current().equals(flat),"Switching tips restores each tip's own controls");
                test.runOnMainSync(() -> findButton(panel,"Add to Toolbar").performClick());test.waitForIdleSync();
                String id=library.activeId();check(!popup.isShowing()&&!id.isEmpty(),"Head setup can be saved directly from the editor");
                java.util.Map<?,?> buttons=(java.util.Map<?,?>)field(get(activity,"toolbar"),"selectionButtons");
                check((Integer)field(buttons.get(id),"iconResource")==expected,"Custom tool gets the matching enlarged tip icon");
                ToolLibrary restored=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                check(restored.current().equals(flat)&&restored.activeId().equals(id),"Tip controls and custom selection persist");
                android.widget.PopupWindow[] dialog=new android.widget.PopupWindow[1];
                test.runOnMainSync(() -> {
                    dialog[0]=(android.widget.PopupWindow)call(activity,"showToolSettings",new Class<?>[0]);
                    ((android.widget.SeekBar)findDescription(dialog[0].getContentView(),"Brush height")).setProgress(5);
                    check(library.current().headThickness==5&&library.builtin(ToolSettings.Tool.BRUSH).headThickness==20,"Favorite height changes leave regular height intact");
                    findButton(dialog[0].getContentView(),"Filbert").performClick();
                });test.waitForIdleSync();
                check(dialog[0].isShowing()&&library.activeId().equals(id)&&library.current().head==ToolSettings.Head.FILBERT,
                        "Custom tip controls also switch in place without losing preset identity");
                test.runOnMainSync(() -> dialog[0].dismiss());test.waitForIdleSync();
            }
        }
        check(Arrays.equals(before,doc.snapshot()),"Brush editors never paint on the document");
        report.append("Large brush tips, in-place controls below selection, both toolbar sides, independent tip recall and custom presets pass.\n");
    }
    private static void brushTiltInput(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary library=new ToolLibrary();
        Object pad=field(activity,"pad");ToneDocument doc=(ToneDocument)field(pad,"document");byte[] before=doc.snapshot();
        test.runOnMainSync(() -> {set(activity,"library",library);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);});
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR,ToolSettings.Tool.FLAT_WASH}) {
            ToolSettings live=ToolSettings.defaults(tool).head(ToolSettings.Head.FLAT).size(72).tilt(true);
            ToneDocument expected=new ToneDocument(doc.width,doc.height,doc.snapshot());
            PressureStroke reference=new PressureStroke(expected,live,182,null,false);
            reference.sample(300,1500,.45f,0,60);
            reference.sample(300,1600,.45f,0,60);
            reference.sample(400,1600,.45f,45,30);
            reference.sample(500,1600,.45f,60,0);reference.finish();
            test.runOnMainSync(() -> {
                library.edit(live);TestAccess.setMaximum(activity,72);set(get(activity,"paint"),"gray",182);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);
                long start=SystemClock.uptimeMillis();
                tiltEvent((View)pad,start,MotionEvent.ACTION_DOWN,300,1500,.45f,0,60);
                // Two historical samples and one current sample exercise every
                // input path. Unequal diagonal components also catch swapped axes.
                MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();
                prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
                MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();
                point.x=300;point.y=1600;point.pressure=.45f;
                point.orientation=0;point.setAxisValue(MotionEvent.AXIS_TILT,60);
                MotionEvent move=MotionEvent.obtain(start,start+10,MotionEvent.ACTION_MOVE,1,
                        new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{point},
                        0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
                try {
                    point.x=400;point.orientation=45;point.setAxisValue(MotionEvent.AXIS_TILT,30);
                    move.addBatch(start+20,new MotionEvent.PointerCoords[]{point},0);
                    point.x=500;point.orientation=60;point.setAxisValue(MotionEvent.AXIS_TILT,0);
                    move.addBatch(start+30,new MotionEvent.PointerCoords[]{point},0);
                    ((View)pad).dispatchTouchEvent(move);
                } finally {move.recycle();}
                tiltEvent((View)pad,start,MotionEvent.ACTION_UP,500,1600,0,60,0);
            });test.waitForIdleSync();
            check(Arrays.equals(expected.snapshot(),doc.snapshot()),"Firmware X=ORIENTATION/Y=TILT reaches brush down, historical and current movement samples");
            test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
            check(Arrays.equals(before,doc.snapshot()),"Live tilt stroke restores the exact drawing on undo");
        }
        report.append("Firmware tilt mapping passes for Brush/Watercolor/Flat wash pen-down, current and historical moves, with exact undo.\n");
        for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT})
            for(float tilt:new float[]{0,30,60}) {
                ToolSettings live=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head).size(64).minimum(1).automaticHead();
                ToneDocument light=contactDab(live,.05f,0,tilt),firm=contactDab(live,.9f,0,tilt);
                int[] grown=null;
                for(int y=0;y<200&&grown==null;y++)for(int x=0;x<160;x++)
                    if(light.tone(x,y)==255&&firm.tone(x,y)==0){grown=new int[]{x-80,y-70};break;}
                check(grown!=null,"Pressure exposes new contact for the actual default head");
                int dx=grown[0],dy=grown[1];
                android.graphics.Rect pending=(android.graphics.Rect)field(pad,"pending");
                DirectEink direct=(DirectEink)field(pad,"direct");
                long start=SystemClock.uptimeMillis();
                test.runOnMainSync(() -> {
                    library.edit(live);TestAccess.setMaximum(activity,64);set(get(activity,"paint"),"gray",0);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);
                    tiltEvent((View)pad,start,MotionEvent.ACTION_DOWN,700,1500,.05f,0,tilt);
                    check(doc.tone(700+dx,1500+dy)==255,"Light contact leaves room for pressure growth");
                    // Hold the coalescing window open deterministically. No new
                    // pen event follows: the scheduled presentation must flush it.
                    set(pad,"lastPresent",SystemClock.uptimeMillis()+100);
                    tiltEvent((View)pad,start,MotionEvent.ACTION_MOVE,700,1500,.9f,0,tilt);
                    check(doc.tone(700+dx,1500+dy)==0,"Pressure-only move spreads contact before pen-up");
                    check(!pending.isEmpty(),"Test exercises deferred display pixels");
                });
                SystemClock.sleep(250);
                check(field(pad,"stroke")!=null,"Pen remains down while waiting for presentation");
                try {
                    test.runOnMainSync(() -> {
                        check(pending.isEmpty(),"Deferred pressure pixels flush with no further pen events");
                        check(direct!=null&&direct.readGray(700+dx,1500+dy)==0,"Stationary pressure reaches the actual display buffer before pen-up");
                    });
                } finally {
                    test.runOnMainSync(() -> {
                        tiltEvent((View)pad,start,MotionEvent.ACTION_UP,700,1500,0,0,tilt);
                        findButton(activity,"Undo").performClick();
                    });
                }
                check(Arrays.equals(before,doc.snapshot()),"Live stationary pressure growth undoes exactly");
            }
        report.append("Flat/Filbert upright, moderate and strong tilt pressure reaches the direct display after input stops, before pen-up; undo is exact.\n");
    }
    private static void customToolbarChecks(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary tools=new ToolLibrary();
        android.content.SharedPreferences prefs=(android.content.SharedPreferences)field(activity,"preferences");
        test.runOnMainSync(() -> {set(activity,"library",tools);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);});
        for(ToolSettings.Tool tool:ToolSettings.Tool.values()) {
            test.runOnMainSync(() -> {
                tools.select(tool);TestAccess.setMaximum(activity,tools.current().maximum);
                android.widget.PopupWindow dialog=(android.widget.PopupWindow)call(activity,"showToolSettings",new Class<?>[0]);
                try {
                    Button add=findButton(dialog.getContentView(),"Add to Toolbar");
                    check(add.getText().toString().equals("Add to Toolbar"),"Built-in settings offer Add to Toolbar");
                    add.performClick();
                    check(!dialog.isShowing()&&tools.presets().size()==1&&!tools.activeId().isEmpty(),"Adding saves immediately without a naming dialog");
                } finally {dialog.dismiss();}
                String id=tools.activeId();
                dialog=(android.widget.PopupWindow)call(activity,"showToolSettings",new Class<?>[0]);
                try {
                    View root=dialog.getContentView();
                    check(findButton(root,"Manage custom preset")==null&&findButton(root,"Add to Toolbar")==null,"Custom settings have no management or add button");
                    String slider=tool==ToolSettings.Tool.FILL?"Tolerance":tool==ToolSettings.Tool.SHAPES?"Shape outline width":"Maximum diameter";
                    ((android.widget.SeekBar)findDescription(root,slider)).setProgress(41);
                    ((android.widget.SeekBar)findDescription(root,slider)).setProgress(53);
                    if(ToolSettings.defaults(tool).isBrush()) {
                        android.widget.SeekBar response=(android.widget.SeekBar)findDescription(root,"Pressure response");
                        check(response!=null && response.getProgress()==50,"Brush dialog exposes the original pressure response");
                        response.setProgress(85);
                        check(tools.current().pressureResponse==85,"Brush response slider updates settings");
                    }
                    if(tool==ToolSettings.Tool.PENCIL) ((android.widget.SeekBar)findDescription(root,"Hardness")).setProgress(81);
                    if(tool==ToolSettings.Tool.ERASER) ((android.widget.SeekBar)findDescription(root,"Softness")).setProgress(27);
                    if(tool==ToolSettings.Tool.SOFTEN) ((android.widget.SeekBar)findDescription(root,"Strength")).setProgress(62);
                    check(tools.activeId().equals(id)&&tools.presets().get(0).settings.equals(tools.current()),"Slider edits update the selected custom tool");
                    dialog.getContentView().findViewWithTag("close").performClick();
                } finally {dialog.dismiss();}
                ToolSettings edited=tools.current();tools.select(tool);tools.recall(id);
                check(tools.current().equals(edited),"Edited custom tool recalls the new values");
                try {
                    ToolLibrary saved=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                    check(saved.activeId().equals(id)&&saved.presets().get(0).settings.equals(edited),"Dialog edits persist to preferences");
                } catch(Exception error) {throw new IllegalStateException(error);}
                dialog=(android.widget.PopupWindow)call(activity,"showToolSettings",new Class<?>[0]);
                try {
                    Button delete=(Button)findDescription(dialog.getContentView(),"Delete custom tool");
                    check(delete.getText().length()==0&&delete.getCompoundDrawablesRelative()[0]!=null
                            &&"Delete custom tool".contentEquals(delete.getContentDescription()),"Delete is an accessible trash icon");
                    delete.performClick();
                    check(!dialog.isShowing()&&tools.presets().isEmpty()&&tools.activeId().isEmpty(),"Trash removes custom tool and closes settings");
                    try {
                        ToolLibrary saved=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                        check(saved.presets().isEmpty(),"Trash deletion persists to preferences");
                    } catch(Exception error) {throw new IllegalStateException(error);}
                } finally {dialog.dismiss();}
            });
            test.waitForIdleSync();
        }
        report.append("All tool dialogs add without naming, save repeated slider edits, recall saved values, and delete with an accessible trash icon. No management button remains.\n");
    }
    private static View findDescription(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=findDescription(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    private static void presetDragChecks(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary tools = new ToolLibrary();
        ToolLibrary.Preset a = tools.add("Drag A"), b = tools.add("Drag B"), c = tools.add("Drag C");
        tools.recall(b.id);
        ToneDocument document = (ToneDocument)field(get(activity,"pad"),"document");
        byte[] original = document.snapshot();
        android.widget.ScrollView scroll = (android.widget.ScrollView)field(get(activity,"toolbar"),"toolScroll");
        android.content.SharedPreferences prefs = (android.content.SharedPreferences)field(activity,"preferences");
        test.runOnMainSync(() -> {set(activity,"library",tools);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);scroll.scrollTo(0,0);});
        test.waitForIdleSync();
        for (boolean right : new boolean[]{false,true}) {
            test.runOnMainSync(() -> {prefs.edit().putBoolean("toolbox_right",right).apply();call(activity,"applyToolboxSide",new Class<?>[0]);});
            test.waitForIdleSync();SystemClock.sleep(300);
            check(activity.hasWindowFocus(),"Activity has input focus before native drag");
            checkHeader(activity);
            Button first = findButton(activity,"Drag A"), last = findButton(activity,"Drag C");
            int[] start = new int[2], end = new int[2]; first.getLocationOnScreen(start);last.getLocationOnScreen(end);
            drag(test, start[0]+first.getWidth()/2f, start[1]+first.getHeight()/2f,
                    end[0]+last.getWidth()/2f, end[1]+last.getHeight()-2);
            check(tools.presets().get(2).id.equals(a.id), "Native drag moves preset to end: right="+right+", source="+Arrays.toString(start)+", target="+Arrays.toString(end)+", order="+tools.presets().get(0).name+","+tools.presets().get(1).name+","+tools.presets().get(2).name);
            first = findButton(activity,"Drag A");last = findButton(activity,"Drag B");
            first.getLocationOnScreen(start);last.getLocationOnScreen(end);
            drag(test, start[0]+first.getWidth()/2f, start[1]+first.getHeight()/2f,
                    end[0]+last.getWidth()/2f, end[1]+2);
            check(tools.presets().get(0).id.equals(a.id) && tools.presets().get(1).id.equals(b.id), "Native drag inserts before first preset");
            first = findButton(activity,"Drag A");first.getLocationOnScreen(start);
            View canvas = (View)field(activity,"pad");canvas.getLocationOnScreen(end);
            drag(test, start[0]+first.getWidth()/2f, start[1]+first.getHeight()/2f,
                    end[0]+canvas.getWidth()/2f, end[1]+canvas.getHeight()/2f);
            check(tools.presets().get(0).id.equals(a.id) && first.getAlpha()==1, "Drop outside rail cancels and restores source appearance");
        }
        ToolLibrary saved = ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
        check(saved.presets().get(0).id.equals(a.id) && saved.presets().get(2).id.equals(c.id)
                && saved.activeId().equals(b.id), "Native drops persist order without changing selected preset: saved order="
                +saved.presets().get(0).name+","+saved.presets().get(1).name+","+saved.presets().get(2).name
                +", active="+saved.activeId()+", live="+tools.activeId()+", expected="+b.id);
        test.runOnMainSync(() -> {
            for (int i=0;i<25;i++) tools.add("Drag scroll " + i);
            tools.recall(b.id);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);scroll.scrollTo(0,0);
        });
        test.waitForIdleSync();
        Button first = findButton(activity,"Drag A");int[] start = new int[2], edge = new int[2];
        first.getLocationOnScreen(start);scroll.getLocationOnScreen(edge);
        drag(test,start[0]+first.getWidth()/2f,start[1]+first.getHeight()/2f,
                edge[0]+scroll.getWidth()/2f,edge[1]+scroll.getHeight()-10,800);
        check(scroll.getScrollY()>0 && tools.presets().indexOf(a)>2, "Holding dragged preset at bottom scrolls and drops into offscreen list");
        check(Arrays.equals(original,document.snapshot()), "Dragging presets never marks the canvas");
        report.append("Native long-press drag inserts presets upward/downward on both sides, scrolls at the edge, cancels outside the rail, saves order and preserves drawing/selection.\n");
    }
    private static void drag(Instrumentation test, float x, float y, float endX, float endY) {
        drag(test,x,y,endX,endY,0);
    }
    static void drag(Instrumentation test, float x, float y, float endX, float endY, int holdMillis) {
        long down = SystemClock.uptimeMillis();
        pointer(test, down, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+200);
        for (int step=1;step<=12;step++) {
            pointer(test, down, MotionEvent.ACTION_MOVE, x+(endX-x)*step/12, y+(endY-y)*step/12);
            SystemClock.sleep(35);
        }
        SystemClock.sleep(holdMillis);
        pointer(test, down, MotionEvent.ACTION_UP, endX, endY);
        test.waitForIdleSync(); SystemClock.sleep(150);
    }
    private static void pointer(Instrumentation test,long down,int action,float x,float y) {
        MotionEvent event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // System drag dispatch owns the gesture after long-press, so use the UI automation input path.
        check(test.getUiAutomation().injectInputEvent(event,true), "Drag pointer injected");event.recycle();
    }
    private static void checkHeader(PaintActivity activity) throws Exception {
        View picker=(View)field(activity,"shadePicker"), palette=(View)field(activity,"palette");
        int[] shade=new int[2], header=new int[2], action=new int[2], page=new int[2];
        picker.getLocationOnScreen(shade);palette.getLocationOnScreen(header);
        check(Math.abs(shade[0]+picker.getWidth()/2f-header[0]-palette.getWidth()/2f)<=1,
                "Grayscale strip stays centered on screen");
        for(String name:new String[]{"Undo","Redo","Clear layer"}) {
            Button button=findButton(activity,name);button.getLocationOnScreen(action);
            check(action[0]+button.getWidth()<=shade[0], "Page action stays left of color selector");
        }
        ((View)field(activity,"previousPage")).getLocationOnScreen(page);
        check(page[0]>=shade[0]+picker.getWidth(), "Page selector stays right of color selector");
    }
    private static byte[] checkPages(Instrumentation test,PaintActivity activity,StringBuilder report) throws Exception {
        DrawingBook book=(DrawingBook)field(activity,"book");byte[] first=book.current().snapshot();
        View add=(View)field(activity,"addPage"),previous=(View)field(activity,"previousPage"),next=(View)field(activity,"nextPage");
        test.runOnMainSync(add::performClick);test.waitForIdleSync();
        check(book.count()==2&&book.index()==1,"Add page appends and selects a new page");
        for(byte tone:book.current().snapshot())check((tone&255)==255,"New page starts white");
        Object pad=field(activity,"pad");View canvas=(View)pad;long start=SystemClock.uptimeMillis();
        ToolLibrary tools=(ToolLibrary)field(activity,"library");
        test.runOnMainSync(() -> {
            tools.select(ToolSettings.Tool.BRUSH);TestAccess.setMaximum(activity,32);set(get(activity,"paint"),"gray",0);
            event(canvas,start,MotionEvent.ACTION_DOWN,200,200,.45f);event(canvas,start,MotionEvent.ACTION_UP,200,200,0);
        });
        byte[] second=book.current().snapshot();check(book.current().tone(200,200)==0,"New page accepts drawing events");
        test.runOnMainSync(previous::performClick);test.waitForIdleSync();
        check(Arrays.equals(first,book.current().snapshot()),"Previous page retains all marks");
        test.runOnMainSync(next::performClick);test.waitForIdleSync();
        check(Arrays.equals(second,book.current().snapshot())&&book.current().canUndo(),"Next page retains marks and recent undo");
        android.content.SharedPreferences preferences=(android.content.SharedPreferences)field(activity,"preferences");
        for(boolean right:new boolean[]{false,true}) {
            test.runOnMainSync(() -> {preferences.edit().putBoolean("toolbox_right",right).apply();call(activity,"applyToolboxSide",new Class<?>[0]);});
            test.waitForIdleSync();SystemClock.sleep(100);
            int[] origin=new int[2];canvas.getLocationOnScreen(origin);
            check((origin[0]==0)==right&&field(pad,"direct")!=null,"Toolbox moves sides and reconnects direct display");
            check(Arrays.equals(second,book.current().snapshot()),"Moving controls leaves page pixels untouched");
        }
        DocumentStore store=(DocumentStore)field(activity,"store");CountDownLatch saved=new CountDownLatch(1);Exception[] error={null};
        String name="_page_checks_"+SystemClock.uptimeMillis();
        store.save(name,new DocumentStore.Snapshot(book),(unused,failure)->{error[0]=failure;saved.countDown();});
        check(saved.await(10,TimeUnit.SECONDS)&&error[0]==null,"Whole-book save completes");
        CountDownLatch opened=new CountDownLatch(1);DrawingBook[] loaded={null};
        store.openBook(name,(value,failure)->{loaded[0]=value;error[0]=failure;opened.countDown();});
        check(opened.await(10,TimeUnit.SECONDS)&&error[0]==null&&loaded[0].count()==2&&loaded[0].index()==1,"Named save/open preserves pages and position");
        check(Arrays.equals(second,loaded[0].current().snapshot()),"Saved active page is exact");loaded[0].select(0);check(Arrays.equals(first,loaded[0].current().snapshot()),"Saved earlier page is exact");
        new java.io.File(activity.getFilesDir(),"drawings/"+name+".tsm").delete();
        CountDownLatch barrier=new CountDownLatch(1);store.openBook("_recovery",(value,failure)->{error[0]=failure;barrier.countDown();});
        check(barrier.await(10,TimeUnit.SECONDS)&&error[0]==null,"Whole-book recovery finishes");
        report.append("Blank-page creation, navigation, recent-page undo, whole-book save/open and both toolbox sides pass.\n");
        return second;
    }
    private static void checkSelectionFeedback(Instrumentation test, PaintActivity activity, ToneDocument doc, StringBuilder report) throws Exception {
        SelectionFeedback feedback=(SelectionFeedback)field(activity,"selectionFeedback");
        ToolLibrary savedLibrary=(ToolLibrary)field(activity,"library");int savedMaximum=TestAccess.maximum(activity);
        boolean original=feedback.enabled;byte[] tones=doc.snapshot();boolean undo=doc.canUndo(),redo=doc.canRedo();
        Button pencil=findButton(activity,"Pencil");
        Bitmap[] baseline=new Bitmap[1],restored=new Bitmap[1];
        test.runOnMainSync(() -> {
            feedback.enabled=true;
            call(get(activity,"toolbar"),"markActive",new Class<?>[]{ToolButton.class,boolean.class},pencil,false);
            findButton(activity,"Brush").performClick();
        });
        test.waitForIdleSync();
        test.runOnMainSync(() -> baseline[0]=controlBitmap(pencil));
        int requests=feedback.submitted;
        try {
            checkFeedbackRepaint(test,activity,feedback,report);
            for(int i=0;i<4;i++) {
                final int shade=i%2==0?255:0;
                test.runOnMainSync(() -> {
                    findButton(activity,"Pencil").performClick();
                    call(activity,"selectShade",new Class<?>[]{int.class},shade);
                    findButton(activity,"Brush").performClick();
                });
                test.waitForIdleSync();SystemClock.sleep(20);
            }
            test.runOnMainSync(() -> {
                pencil.performClick();
                Bitmap selected=controlBitmap(pencil);
                android.graphics.Rect marker=(android.graphics.Rect)call(pencil,"markerArea",new Class<?>[0]);
                android.graphics.Rect changed=SelectionFeedback.difference(baseline[0],selected);
                check(!changed.isEmpty() && marker.contains(changed),"Instant tool selection stays within the toolbar button");
                check(changed.width()>pencil.getWidth()/2 && changed.height()>pencil.getHeight()/2,"Selection outlines the whole tool");
                check(pencil.isSelected(),"Instant box retains accessible selected state");
                selected.recycle();findButton(activity,"Brush").performClick();
            });
            check(feedback.submitted>requests,"Control-only direct requests accepted");
            android.graphics.Rect region=feedback.lastScreenRegion;
            View canvas=(View)field(activity,"pad");int[] canvasOrigin=new int[2];canvas.getLocationOnScreen(canvasOrigin);
            check(region.right<=canvasOrigin[0]||region.left>=canvasOrigin[0]+canvas.getWidth()||region.bottom<=canvasOrigin[1],"Selection request excludes document canvas");
            test.runOnMainSync(() -> restored[0]=controlBitmap(pencil));
            check(baseline[0].sameAs(restored[0]),"Changing tools restores exact inactive control pixels after redraw");
            check(!(Boolean)field(pencil,"marked")&&(Boolean)field(findButton(activity,"Brush"),"marked"),"Only latest tool marker is retained");
            ToolLibrary longList=ToolLibrary.decode(savedLibrary.encode());
            for(int i=0;i<30;i++)longList.add("Scroll check " + i);
            longList.select(ToolSettings.Tool.BRUSH);
            test.runOnMainSync(() -> {set(activity,"library",longList);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);});
            test.waitForIdleSync();
            View rail=(View)field(get(activity,"toolbar"),"toolRail");
            test.runOnMainSync(() -> {
                View divider=((ViewGroup)rail).getChildAt(((ViewGroup)rail).indexOfChild(findButton(activity,"Layers"))+1);
                Bitmap separator=controlBitmap(divider);
                int runs=0;boolean inDash=false,hasGap=false;
                for(int x=0;x<separator.getWidth();x++) {
                    int pixel=separator.getPixel(x,separator.getHeight()/2);
                    boolean black=Color.alpha(pixel)>0 && Color.red(pixel)==0;
                    if(black&&!inDash)runs++;
                    if(!black)hasGap=true;
                    inDash=black;
                }
                check(runs>=4 && hasGap,"Divider between built-in and custom tools renders visible separate black dashes");
                separator.recycle();
            });
            android.widget.ScrollView scroll=(android.widget.ScrollView)rail.getParent();
            test.runOnMainSync(() -> scroll.scrollTo(0,rail.getHeight()));test.waitForIdleSync();
            check(scroll.getScrollY()>findButton(activity,"Pencil").getBottom(),"Long preset list scrolls controls offscreen");
            int before=feedback.submitted;
            test.runOnMainSync(() -> findButton(activity,"Pencil").performClick());
            check(feedback.submitted==before,"Offscreen controls never issue direct writes");
            test.runOnMainSync(() -> scroll.scrollTo(0,0));
            android.app.AlertDialog[] dialog=new android.app.AlertDialog[1];
            test.runOnMainSync(() -> {dialog[0]=new android.app.AlertDialog.Builder(activity).setMessage("Selection feedback check").create();dialog[0].show();});
            test.waitForIdleSync();SystemClock.sleep(100);
            before=feedback.submitted;
            try {
                check(!rail.hasWindowFocus(),"Modal owns focus");
                test.runOnMainSync(() -> call(activity,"selectShade",new Class<?>[]{int.class},128));
                check(feedback.submitted==before,"Covered controls never issue direct writes");
            } finally {test.runOnMainSync(() -> dialog[0].dismiss());}
            test.waitForIdleSync();
            check(Arrays.equals(tones,doc.snapshot())&&undo==doc.canUndo()&&redo==doc.canRedo(),"Selection overlays do not enter document or history");
            report.append("Optional selection feedback changes only the tool dot (fixed border) and submits bounded control patches; rapid changes restore backgrounds, scrolling and modal focus suppress stale writes, and document/history remain untouched.\n");
        } finally {
            test.runOnMainSync(() -> {
                feedback.enabled=original;set(activity,"library",savedLibrary);TestAccess.setMaximum(activity,savedMaximum);
                call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);call(activity,"saveToolState",new Class<?>[0]);
            });
            baseline[0].recycle();if(restored[0]!=null)restored[0].recycle();
        }
    }
    private static void checkFeedbackRepaint(Instrumentation test, PaintActivity activity, SelectionFeedback feedback, StringBuilder report) throws Exception {
        View root=activity.getWindow().getDecorView();
        Button pencil=findButton(activity,"Pencil"),brush=findButton(activity,"Brush");
        View picker=(View)field(activity,"shadePicker");
        java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();
        android.view.ViewTreeObserver.OnDrawListener listener=() -> frames.incrementAndGet();
        test.waitForIdleSync();SystemClock.sleep(600);
        test.runOnMainSync(() -> root.getViewTreeObserver().addOnDrawListener(listener));
        try {
            int before=feedback.submitted;
            test.runOnMainSync(pencil::performClick);
            test.waitForIdleSync();SystemClock.sleep(800);
            check(feedback.submitted>before,"Tool selection reached direct display");
            check(frames.get()==0,"Direct tool selection must not queue a later Android frame: "+frames.get());
            before=feedback.submitted;
            test.runOnMainSync(() -> {
                long now=SystemClock.uptimeMillis();
                event(brush,now,MotionEvent.ACTION_DOWN,brush.getWidth()/2f,brush.getHeight()/2f,.2f);
                event(brush,now,MotionEvent.ACTION_UP,brush.getWidth()/2f,brush.getHeight()/2f,0);
            });
            test.waitForIdleSync();SystemClock.sleep(800);
            check(brush.isSelected() && feedback.submitted>before,"Stylus tap selects the tool directly");
            check(frames.get()==0,"Pressed/released tool must not animate a later repaint: "+frames.get());
            before=feedback.submitted;
            int oldGray=(Integer)field(get(activity,"paint"),"gray");float x=oldGray==0?picker.getWidth()-2:2;
            test.runOnMainSync(() -> {
                long now=SystemClock.uptimeMillis();
                event(picker,now,MotionEvent.ACTION_DOWN,x,picker.getHeight()/2f,.2f);
                event(picker,now,MotionEvent.ACTION_UP,x,picker.getHeight()/2f,0);
            });
            test.waitForIdleSync();SystemClock.sleep(800);
            check(feedback.submitted>before,"Shade marker reaches direct display");
            check(frames.get()==0,"Direct shade selection must not queue a later Android frame: "+frames.get());
            View wetness=(View)field(activity,"wetnessBar");
            before=feedback.submitted;
            test.runOnMainSync(() -> {
                findButton(activity,"Wet canvas").performClick();
                findButton(activity,"Transparent paint").performClick();
                long now=SystemClock.uptimeMillis();
                event(wetness,now,MotionEvent.ACTION_DOWN,wetness.getWidth()/2f,wetness.getHeight(),.2f);
                event(wetness,now,MotionEvent.ACTION_MOVE,wetness.getWidth()/2f,0,.2f);
                event(wetness,now,MotionEvent.ACTION_UP,wetness.getWidth()/2f,0,0);
            });
            test.waitForIdleSync();SystemClock.sleep(800);
            check(feedback.submitted>=before+4,"Wetness fill and paint-mode dots reach direct display");
            check(frames.get()==0,"Wet controls must not queue a later Android frame: "+frames.get());
            test.runOnMainSync(() -> {
                findButton(activity,"Wet canvas").performClick();findButton(activity,"Opaque paint").performClick();
            });
            // An unrelated redraw must refresh the retained Android commands too.
            test.runOnMainSync(root::invalidate);test.waitForIdleSync();SystemClock.sleep(300);
            Bitmap screen=test.getUiAutomation().takeScreenshot();
            check(screen!=null,"Capture compositor after unrelated redraw");
            try {
                int[] location=new int[2];
                brush.getLocationOnScreen(location);
                int inset=Math.round(12*activity.getResources().getDisplayMetrics().density);
                check(Color.red(screen.getPixel(location[0]+brush.getWidth()-inset,location[1]+inset))==0,"Next normal frame retains the latest tool dot");
            } finally {screen.recycle();}
            report.append("Direct tool clicks, stylus press/release and shade taps produce no Android frame for 800 ms; next unrelated redraw retains current tool marker.\n");
        } finally {
            test.runOnMainSync(() -> root.getViewTreeObserver().removeOnDrawListener(listener));
        }
    }
    private static Bitmap controlBitmap(View view) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));return bitmap;
    }
    private static void checkToolTaps(Instrumentation test,PaintActivity activity,ToneDocument doc,StringBuilder report) throws Exception {
        ToolLibrary library=(ToolLibrary)field(activity,"library");byte[] before=doc.snapshot();
        test.runOnMainSync(() -> call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]));
        for(String name:new String[]{"Pencil","Renamed check preset"}) {
            test.runOnMainSync(() -> findButton(activity,name).performClick());test.waitForIdleSync();SystemClock.sleep(80);
            check(activity.hasWindowFocus()&&findButton(activity,name).isSelected(),"First tap selects "+name+" without a dialog");
            ToolSettings selected=library.current();
            test.runOnMainSync(() -> findButton(activity,name).performClick());test.waitForIdleSync();SystemClock.sleep(80);
            check(!activity.hasWindowFocus()&&selected.equals(library.current()),"Second tap opens settings without resetting "+name);
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();SystemClock.sleep(80);
        }
        check(Arrays.equals(before,doc.snapshot()),"Selecting and opening settings never paints");
        test.runOnMainSync(() -> {check(doc.clear(),"Temporary canvas clears");check(doc.undo(),"Clear undo available");});
        check(Arrays.equals(before,doc.snapshot()),"Clear restores all disconnected marks with one undo");
        report.append("First tap recalls a tool/preset; second tap opens settings without resetting it. Clear is one undoable action.\n");
    }
    private static void checkLargeFill(Instrumentation test,PaintActivity activity,Object pad,ToneDocument restore,StringBuilder report) throws Exception {
        ToneDocument blank=new ToneDocument(restore.width,restore.height);View view=(View)pad;
        long start=SystemClock.uptimeMillis();
        CountDownLatch responsive=new CountDownLatch(1);
        test.runOnMainSync(() -> {
            call(pad,"replace",new Class<?>[]{ToneDocument.class},blank);
            if(!findButton(activity,"Fill").isSelected())findButton(activity,"Fill").performClick();set(get(activity,"paint"),"gray",128);
            event(view,start,MotionEvent.ACTION_DOWN,10,10,.45f);event(view,start,MotionEvent.ACTION_UP,10,10,0);
            view.post(responsive::countDown);
        });
        check(responsive.await(1,TimeUnit.SECONDS),"Main thread yields while filling large canvas");
        long end=SystemClock.uptimeMillis()+15000;
        while(field(pad,"fill")!=null&&SystemClock.uptimeMillis()<end)SystemClock.sleep(25);
        check(field(pad,"fill")==null&&blank.tone(blank.width-1,blank.height-1)==128,"Full canvas fill completes");
        long elapsed=SystemClock.uptimeMillis()-start;
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
        check(!blank.canUndo()&&blank.tone(0,0)==255&&blank.tone(blank.width-1,blank.height-1)==255,"Large fill is one undo");
        test.runOnMainSync(() -> {event(view,start,MotionEvent.ACTION_DOWN,10,10,.45f);event(view,start,MotionEvent.ACTION_UP,10,10,0);});
        SystemClock.sleep(25);
        test.runOnMainSync(() -> call(pad,"finishStroke",new Class<?>[0]));
        for(byte tone:blank.snapshot())check((tone&255)==255,"Interrupted fill restores all original pixels");
        test.runOnMainSync(() -> call(pad,"replace",new Class<?>[]{ToneDocument.class},restore));
        report.append("Full-canvas fill completed in "+elapsed+" ms with a responsive UI; interrupted fill rolls back.\n");
    }
    private static void checkTools(Instrumentation test, PaintActivity activity, Object pad, ToneDocument doc, StringBuilder report) throws Exception {
        View view=(View)pad; long start=SystemClock.uptimeMillis();
        ToolLibrary library=(ToolLibrary)field(activity,"library");
        test.runOnMainSync(() -> {
            library.edit(ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(128)); TestAccess.setMaximum(activity,128);set(get(activity,"paint"),"gray",0);
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);event(view,start,MotionEvent.ACTION_UP,600,800,0);
            findButton(activity,"Eraser").performClick();
            library.edit(library.current().size(32));TestAccess.setMaximum(activity,32);
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);event(view,start,MotionEvent.ACTION_UP,600,800,0);
        });
        check(doc.tone(600,800)>0&&doc.tone(600,800)<255&&doc.tone(640,800)==0,"Eraser contact lifts only part of the mark within its footprint");
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
        check(doc.tone(600,800)==0,"Eraser is one undo action");
        test.runOnMainSync(() -> {
            findButton(activity,"Brush").performClick();
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f,MotionEvent.TOOL_TYPE_ERASER);
            event(view,start,MotionEvent.ACTION_UP,600,800,0,MotionEvent.TOOL_TYPE_ERASER);
        });
        check(doc.tone(600,800)>0&&doc.tone(640,800)==0&&library.current().tool==ToolSettings.Tool.BRUSH,
                "A stylus's eraser end erases with the Eraser tool and keeps the selected tool");
        test.runOnMainSync(() -> {
            findButton(activity,"Undo").performClick();
            event(view,start,SystemClock.uptimeMillis(),MotionEvent.ACTION_DOWN,600,800,.45f,MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.BUTTON_STYLUS_PRIMARY);
            event(view,start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,600,800,0,MotionEvent.TOOL_TYPE_STYLUS,0);
        });
        check(doc.tone(600,800)>0&&doc.tone(640,800)==0&&library.current().tool==ToolSettings.Tool.BRUSH,
                "Holding the stylus side button erases with the Eraser tool and keeps the selected tool");
        test.runOnMainSync(() -> { findButton(activity,"Undo").performClick(); findButton(activity,"Eraser").performClick(); });
        test.runOnMainSync(() -> {
            library.edit(library.current().softness(0));
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);
            event(view,start,MotionEvent.ACTION_MOVE,620,800,.45f);
            event(view,start,MotionEvent.ACTION_UP,620,800,0);
        });
        check(doc.tone(600,800)==255&&doc.tone(620,800)==255&&doc.tone(640,800)==0,"Zero-softness eraser is opaque and ignores the selected black shade");
        for(int y=770;y<830;y++)for(int x=570;x<645;x++)check(doc.tone(x,y)==0||doc.tone(x,y)==255,"Hard eraser leaves no gray fringe");
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
        check(doc.tone(600,800)==0&&doc.tone(620,800)==0,"Hard eraser undoes the entire stroke once");
        test.runOnMainSync(() -> {
            library.edit(library.current().options(3,true,false));
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);event(view,start,MotionEvent.ACTION_UP,600,800,0);
        });
        check(doc.tone(600,800)>0&&doc.tone(600,800)<255&&doc.tone(640,800)==0,"Soft eraser lightens without disturbing outside pixels");
        test.runOnMainSync(() -> {
            doc.begin();for(int y=940;y<1060;y++)for(int x=940;x<1060;x++)doc.setTone(x,y,x<1000?0:255);doc.finish();
            call(pad,"renderDirty",new Class<?>[0]);call(pad,"present",new Class<?>[0]);
            findButton(activity,"Blending stump").performClick();TestAccess.setMaximum(activity,32);
            event(view,start,MotionEvent.ACTION_DOWN,1000,1000,.45f);event(view,start,MotionEvent.ACTION_UP,1000,1000,0);
        });
        check(doc.tone(999,1000)>0&&doc.tone(1000,1000)<255,"Soften blends existing boundary tones");
        test.runOnMainSync(() -> {
            findButton(activity,"Pencil").performClick();TestAccess.setMaximum(activity,64);set(get(activity,"paint"),"gray",0);
            tiltEvent(view,start,MotionEvent.ACTION_DOWN,400,1100,.45f,0,0);tiltEvent(view,start,MotionEvent.ACTION_UP,400,1100,0,0,0);
            tiltEvent(view,start,MotionEvent.ACTION_DOWN,500,1100,.45f,65,0);tiltEvent(view,start,MotionEvent.ACTION_UP,500,1100,0,65,0);
            tiltEvent(view,start,MotionEvent.ACTION_DOWN,600,1100,.45f,0,65);tiltEvent(view,start,MotionEvent.ACTION_UP,600,1100,0,0,65);
        });
        int[] upright=bounds(doc,400,1100,40),horizontal=bounds(doc,500,1100,40),vertical=bounds(doc,600,1100,40);
        check(horizontal[0]>upright[0]*3&&horizontal[0]>horizontal[1]*2,"Pencil tilt broadens directional contact");
        check(vertical[1]>vertical[0]*2&&horizontal[0]<=64&&vertical[1]<=64,"Tilt rotates pencil within selected cap");
        int colorBefore=(Integer)field(get(activity,"paint"),"gray");
        final String[] presetId=new String[1];
        test.runOnMainSync(() -> {
            library.edit(ToolSettings.defaults(ToolSettings.Tool.ERASER).size(23).options(3,true,false));
            presetId[0]=library.add("Device check preset").id;
            library.select(ToolSettings.Tool.BRUSH);call(get(activity,"toolbar"),"rebuildTools",new Class<?>[0]);
            findButton(activity,"Device check preset").performClick();
        });
        check(library.current().tool==ToolSettings.Tool.ERASER&&library.current().soft&&library.current().maximum==23,"Custom button recalls all tool settings");
        check((Integer)field(get(activity,"paint"),"gray")==colorBefore,"Preset recall keeps global shade");
        test.runOnMainSync(() -> {
            library.edit(library.current().size(9));
            check(library.presets().get(0).settings.maximum==9&&library.activeId().equals(presetId[0]),"Custom edits save and keep selection");
            library.rename(presetId[0],"Renamed check preset");call(activity,"saveToolState",new Class<?>[0]);
            doc.begin();for(int y=1200;y<=1300;y++)for(int x=800;x<=900;x++)doc.setTone(x,y,x==800||x==900||y==1200||y==1300?0:182);doc.finish();
            call(pad,"renderDirty",new Class<?>[0]);call(pad,"present",new Class<?>[0]);
            findButton(activity,"Fill").performClick();set(get(activity,"paint"),"gray",249);
            event(view,start,MotionEvent.ACTION_DOWN,850,1250,.45f);event(view,start,MotionEvent.ACTION_UP,850,1250,0);
        });
        long end=SystemClock.uptimeMillis()+15000;
        while(field(pad,"fill")!=null&&SystemClock.uptimeMillis()<end)SystemClock.sleep(25);
        check(field(pad,"fill")==null&&doc.tone(850,1250)==249&&doc.tone(800,1250)==0&&doc.tone(799,1250)==255,"Fill uses exact logical region boundaries");
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());check(doc.tone(850,1250)==182,"Fill is one undo action");
        test.runOnMainSync(() -> findButton(activity,"Redo").performClick());check(doc.tone(850,1250)==249,"Fill redo restores target");
        test.runOnMainSync(() -> {
            doc.begin();for(int y=1201;y<1300;y++)for(int x=801;x<900;x++)doc.setTone(x,y,x<830?192:x>870?205:182);doc.finish();
            call(pad,"renderDirty",new Class<?>[0]);call(pad,"present",new Class<?>[0]);
            library.edit(library.current().tolerance(8));set(get(activity,"paint"),"gray",182);
            event(view,start,MotionEvent.ACTION_DOWN,850,1250,.45f);event(view,start,MotionEvent.ACTION_UP,850,1250,0);
        });
        end=SystemClock.uptimeMillis()+15000;
        while(field(pad,"fill")!=null&&SystemClock.uptimeMillis()<end)SystemClock.sleep(25);
        check(field(pad,"fill")==null&&doc.tone(810,1250)==182&&doc.tone(880,1250)==205&&doc.tone(800,1250)==0,"Live fill honors tolerance from a same-color seed and preserves boundaries");
        test.runOnMainSync(() -> {findButton(activity,"Undo").performClick();library.edit(library.current().tolerance(0));});
        check(doc.tone(810,1250)==192&&doc.tone(850,1250)==182,"Tolerant fill undoes once");
        report.append("Live fill tolerance includes nearby shades, stops at the seed range and undoes once.\n");
        report.append("Opaque crisp zero-softness eraser, gradual soft eraser, stronger soften, directional tilt pencil, exact fill, and one-tap color-independent presets pass.\n");
    }
    private static void rubbingChecks(StringBuilder report) {
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.ERASER,ToolSettings.Tool.SOFTEN}) {
            byte[] initial=new byte[256*128];
            for(int y=0;y<128;y++)for(int x=0;x<256;x++)initial[y*256+x]=(byte)(tool==ToolSettings.Tool.SOFTEN&&x>=128?255:0);
            ToneDocument sparse=new ToneDocument(256,128,initial),dense=new ToneDocument(256,128,initial);
            ToolSettings settings=ToolSettings.defaults(tool).size(100);
            ToolStroke a=new ToolStroke(sparse,settings,0),b=new ToolStroke(dense,settings,0);
            a.sample(28,64,.45f,0,0);b.sample(28,64,.45f,0,0);
            byte[] contact=dense.snapshot();
            for(int i=0;i<100;i++)b.sample(28,64,.45f,0,0);
            check(Arrays.equals(contact,dense.snapshot()),tool+" stationary samples do not accumulate");
            for(int x=38;x<=228;x+=10)a.sample(x,64,.45f,0,0);
            for(int x=29;x<=228;x++)b.sample(x,64,.45f,0,0);
            check(Arrays.equals(sparse.snapshot(),dense.snapshot()),tool+" rubbing is independent of event density");
            int before=dense.tone(127,64);
            long edgeBefore=edgeEnergy(dense);
            b.sample(28,64,.45f,0,0);b.sample(228,64,.45f,0,0);
            check(tool==ToolSettings.Tool.ERASER?dense.tone(127,64)>before:edgeEnergy(dense)<edgeBefore,
                    tool+" repeated rubbing lightens erasure or reduces edge contrast");
            a.finish();b.finish();dense.undo();check(Arrays.equals(initial,dense.snapshot()),tool+" entire rub undoes as one gesture");
        }
        ToneDocument doc=new ToneDocument(1200,256);doc.begin();for(int y=0;y<128;y++)for(int x=0;x<1200;x++)doc.setTone(x,y,0);doc.finish();
        long start=SystemClock.uptimeMillis();
        ToolStroke stroke=new ToolStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SOFTEN).size(128),0);
        for(int x=200;x<=1000;x+=8)stroke.sample(x,128,.45f,0,0);stroke.finish();
        report.append("Rubbing tools ignore stationary samples, agree across input densities, build with repeated passes and undo once. 128 px soften model replay: "+(SystemClock.uptimeMillis()-start)+" ms.\n");
    }
    private static void stumpStrengthChecks(StringBuilder report) {
        byte[] original=new byte[256*128];for(int y=0;y<128;y++)for(int x=128;x<256;x++)original[y*256+x]=(byte)255;
        long previous=-1;
        for(int strength:new int[]{0,35,100}) {
            ToneDocument doc=new ToneDocument(256,128,original);
            ToolStroke stroke=new ToolStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SOFTEN).size(64).strength(strength),0);
            for(int x=80;x<=170;x+=2)stroke.sample(x,64,.45f,0,0);
            boolean changed=stroke.finish();long difference=0;byte[] actual=doc.snapshot();
            for(int i=0;i<actual.length;i++)difference+=Math.abs((actual[i]&255)-(original[i]&255));
            check(difference>previous,"Live stump stroke respects strength "+strength);previous=difference;
            check(changed==(strength>0),"Zero-strength stroke adds no edit");
            if(changed){doc.undo();check(Arrays.equals(original,doc.snapshot()),"Whole strength-controlled stroke undoes once");}
        }
        report.append("Stump strokes support zero effect, gentler 35% and full strength with one-step undo.\n");
    }
    private static void sizeRangeChecks(StringBuilder report) {
        ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(72).minimum(20);
        ToneDocument doc=new ToneDocument(256,128);
        PressureStroke brush=new PressureStroke(doc,settings,0);brush.sample(64,64,.05f);brush.finish();
        check(bounds(doc,64,64,40)[0]==20,"Brush light pressure uses configured minimum");
        brush=new PressureStroke(doc,settings,0);brush.sample(192,64,.45f);brush.finish();
        check(bounds(doc,192,64,40)[0]==72,"Brush firm pressure uses configured maximum");
        byte[] black=new byte[256*128];ToneDocument erased=new ToneDocument(256,128,black);
        ToolStroke eraser=new ToolStroke(erased,ToolSettings.defaults(ToolSettings.Tool.ERASER).size(72).minimum(20),0);
        eraser.sample(64,64,.05f,0,0);eraser.finish();
        check(erased.tone(70,64)>0&&erased.tone(75,64)==0,"Eraser minimum affects its actual contact");
        ToneDocument pencilDoc=new ToneDocument(256,128);
        ToolStroke pencil=new ToolStroke(pencilDoc,ToolSettings.defaults(ToolSettings.Tool.PENCIL).size(72).minimum(20).hardness(0),0);
        pencil.sample(64,64,.05f,0,0);pencil.finish();
        check(bounds(pencilDoc,64,64,40)[0]>=18&&bounds(pencilDoc,64,64,40)[0]<=20,"Pencil upright minimum respects contact range");
        report.append("Configured minimum/maximum pressure widths, gradual eraser contact and upright pencil minimum pass.\n");
    }
    private static long edgeEnergy(ToneDocument doc) {
        long sum=0;for(int x=80;x<176;x++){int difference=doc.tone(x+1,64)-doc.tone(x,64);sum+=difference*difference;}return sum;
    }
    private static int[] bounds(ToneDocument doc,int cx,int cy,int radius) {
        int l=cx+radius,r=cx-radius,t=cy+radius,b=cy-radius;
        for(int y=cy-radius;y<cy+radius;y++)for(int x=cx-radius;x<cx+radius;x++)if(doc.tone(x,y)!=255){l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);}
        return new int[]{Math.max(0,r-l+1),Math.max(0,b-t+1)};
    }
    private static void tiltEvent(View view,long start,int action,float x,float y,float pressure,float tx,float ty) {
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;properties.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=pressure;
        // Match the firmware wire format, independently of the stroke API.
        coords.orientation=tx;coords.setAxisValue(MotionEvent.AXIS_TILT,ty);
        MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
        view.dispatchTouchEvent(event);event.recycle();
    }
    private static void checkFirmwareArea(Object pad) throws Exception {
        Object controller = field(get(pad,"input"),"controller");
        Object base = field(controller,"mOurViewRecord");
        check((Boolean)field(base,"replaceByUserSet"), "Firmware whole-view default is excluded");
        Object global = field(controller,"mGlobalVirtualView");
        java.util.List<?> records = (java.util.List<?>)field(global,"mPWViewList");
        int owned = 0;
        for (Object record : records) {
            if (field(record,"pwCoreCtrl") == controller && (Boolean)field(record,"isUserSet")) {
                android.graphics.Rect bounds = (android.graphics.Rect)field(record,"winFrame");
                check(bounds.isEmpty(), "Firmware cannot write anywhere in document canvas"); owned++;
            }
        }
        check(owned == 1, "Exactly one empty firmware region across reconnects");
    }
    private static void namedSave(Instrumentation test, ToneDocument doc) throws Exception {
        java.io.File scratch = new java.io.File(test.getTargetContext().getCacheDir(), "document-check-" + System.nanoTime());
        DocumentStore store = new DocumentStore(scratch);
        CountDownLatch done = new CountDownLatch(1);
        final Exception[] error = new Exception[1]; final ToneDocument[] restored = new ToneDocument[1];
        store.save("Test drawing",new DocumentStore.Snapshot(doc),(value,failure) -> error[0]=failure);
        store.open("Test drawing",(value,failure) -> { restored[0]=value; if(failure!=null) error[0]=failure; done.countDown(); });
        check(done.await(10,TimeUnit.SECONDS) && error[0]==null && Arrays.equals(doc.snapshot(),restored[0].snapshot()), "Named save/open exact tones");
        new java.io.File(scratch,"drawings/Test drawing.tsm").delete();
        new java.io.File(scratch,"drawings").delete(); scratch.delete();
    }
    private static void awaitReady(Instrumentation test, PaintActivity activity) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (((Boolean)field(activity, "loading") || !activity.hasWindowFocus()
                || !(Boolean)field(activity,"resumed")) && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        test.waitForIdleSync(); SystemClock.sleep(300);
        check(!(Boolean)field(activity,"loading"), "Activity loaded");
        check(activity.hasWindowFocus() && (Boolean)field(activity,"resumed"), "Activity resumed with window focus");
    }
    private static void checkPicker(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        View picker = (View)field(activity,"shadePicker");
        Bitmap strip = (Bitmap)field(picker,"strip");
        int cap = (Integer)field(picker,"endpointWidth");
        check(picker.getWidth() < ((View)field(activity,"palette")).getWidth() * .7f, "Picker leaves generous side space");
        checkHeader(activity);
        boolean[] densities = new boolean[65];
        for (int x = 0; x < strip.getWidth(); x++) {
            int tone = DotPattern.pickerTone(Math.round((x-cap) * 255f / (strip.getWidth()-2*cap-1)));
            densities[DotPattern.whiteCount(tone)] = true;
            for (int y = 0; y < strip.getHeight(); y++)
                check(strip.getPixel(x,y) == DotPattern.pixel(tone,x,y), "Gradient uses shared calibrated dots");
            if (x < cap) check(tone == 0, "Compact pure black end");
            if (x >= strip.getWidth()-cap) check(tone == 255, "Compact pure white end");
        }
        for (boolean density : densities) check(density, "All densities visible in gradient");
        Object pad = field(activity,"pad");
        byte[] original = ((ToneDocument)field(pad,"document")).snapshot();
        long start = SystemClock.uptimeMillis();
        float inset = (picker.getWidth()-strip.getWidth())/2f;
        test.runOnMainSync(() -> {
            event(picker,start,MotionEvent.ACTION_DOWN,inset+cap/2f,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_UP,inset+cap/2f,picker.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
        });
        check((Integer)field(get(activity,"paint"),"gray")==0, "Tap compact black end selects pure black");
        test.runOnMainSync(() -> {
            event(picker,start,MotionEvent.ACTION_DOWN,inset+strip.getWidth()-cap/2f,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_UP,inset+strip.getWidth()-cap/2f,picker.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
        });
        check((Integer)field(get(activity,"paint"),"gray")==255, "Tap compact white end selects pure white");
        float light = inset + cap + .92f * (strip.getWidth()-2*cap-1);
        test.runOnMainSync(() -> {
            event(picker,start,MotionEvent.ACTION_DOWN,picker.getWidth()*.5f,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_MOVE,light,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_UP,light,picker.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
        });
        int gray = (Integer)field(get(activity,"paint"),"gray");
        check(DotPattern.whiteCount(gray)>50 && DotPattern.whiteCount(gray)<64, "New lighter shades selectable");
        check(Arrays.equals(original,((ToneDocument)field(pad,"document")).snapshot()), "Picker touches never paint");
        report.append("Shortened dotted picker exposes all 65 densities; compact black/white ends and intermediate shades select without painting.\n");
    }
    private static int marks(ToneDocument doc) {
        int count=0;for(byte pixel:doc.snapshot())if((pixel&255)!=255)count++;return count;
    }
    private static void solidBrushRaster() {
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR,ToolSettings.Tool.FLAT_WASH,ToolSettings.Tool.WET_WATERCOLOR})
            for(ToolSettings.Head head:ToolSettings.Head.values())for(boolean tilt:new boolean[]{false,true})
                for(float pressure:new float[]{.1f,.45f})for(boolean push:new boolean[]{false,true}) {
                    ToolSettings settings=ToolSettings.defaults(tool).head(head).size(32).minimum(32).tilt(tilt);
                    ToneDocument solid=new ToneDocument(128,160),legacy=new ToneDocument(128,160);
                    PressureStroke a=new PressureStroke(solid,settings.bristles(0),0,123);
                    PressureStroke b=new PressureStroke(legacy,settings.bristles(100),0,987);
                    for(int i=0;i<=4;i++) {
                        float y=push?120-i*20:40+i*20;
                        a.sample(64,y,pressure,0,60);b.sample(64,y,pressure,0,60);
                    }
                    a.finish();b.finish();
                    check(Arrays.equals(solid.snapshot(),legacy.snapshot()),"Saved bristle settings cannot create gaps in "+tool+" "+head);
                    check(legacy.tone(64,80)==0,"Solid brush paints the interior of its stroke");
                    byte[] painted=legacy.snapshot();
                    check(legacy.undo()&&marks(legacy)==0&&legacy.redo()&&Arrays.equals(painted,legacy.snapshot()),"Solid brush undo/redo is exact");
                }
    }
    private static void brushTiltRaster() {
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR})
            for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT}) {
                ToolSettings follow=ToolSettings.defaults(tool).head(head).size(72).tilt(true);
                ToneDocument stable=new ToneDocument(128,128);
                PressureStroke stroke=new PressureStroke(stable,follow,0);
                stroke.sample(64,64,.45f,0,60);byte[] first=stable.snapshot();
                for(float[] invalid:new float[][]{{Float.NaN,30},{91,0},{0,Float.POSITIVE_INFINITY}}) {
                    stroke.sample(64,64,.45f,invalid[0],invalid[1]);
                    check(Arrays.equals(first,stable.snapshot()),"Invalid tilt holds the last contact shape and angle");
                }
                stroke.finish();check(stable.undo()&&stable.redo()&&Arrays.equals(first,stable.snapshot()),"Tilted blot undo/redo is exact");
                ToneDocument fixedA=new ToneDocument(128,128),fixedB=new ToneDocument(128,128);
                PressureStroke a=new PressureStroke(fixedA,follow.tilt(false).angle(37),0);
                PressureStroke b=new PressureStroke(fixedB,follow.tilt(false).angle(37),0);
                a.sample(64,64,.45f,0,0);a.finish();b.sample(64,64,.45f,60,-45);b.finish();
                check(Arrays.equals(fixedA.snapshot(),fixedB.snapshot()),"Fixed-angle contact ignores tilt direction and magnitude");
                ToneDocument wrap=new ToneDocument(128,128);
                stroke=new PressureStroke(wrap,follow,0);stroke.sample(64,64,.45f,60,-1);stroke.sample(64,64,.45f,60,1);stroke.finish();
                check(wrap.tone(34,64)==255,"Direction wrap never spins a head the long way around");
                ToneDocument turning=new ToneDocument(128,128);
                stroke=new PressureStroke(turning,follow,0);stroke.sample(64,64,.45f,60,0);int firstMarks=marks(turning);
                stroke.sample(64,64,.45f,0,-60);stroke.finish();
                check(marks(turning)>firstMarks,"Turning in place paints intermediate contact instead of skipping it");
            }
    }
    private static ToneDocument contactDab(ToolSettings settings,float pressure,float tx,float ty) {
        ToneDocument doc=new ToneDocument(160,200);
        PressureStroke stroke=new PressureStroke(doc,settings,0);
        stroke.sample(80,70,pressure,tx,ty);stroke.finish();return doc;
    }
    private static void roundedFilbertRaster(Instrumentation test) throws Exception {
        Bitmap sheet=Bitmap.createBitmap(480,260,Bitmap.Config.ARGB_8888);sheet.eraseColor(Color.WHITE);
        Canvas canvas=new Canvas(sheet);Paint ink=new Paint();ink.setColor(Color.BLACK);ink.setTextSize(14);
        ToolSettings previous=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(ToolSettings.Head.FILBERT).size(64).minimum(64).tilt(true);
        ToolSettings rounded=previous.automaticHead();
        ToolSettings flat=previous.head(ToolSettings.Head.FLAT).automaticHead();
        ToneDocument[] samples={contactDab(previous,.45f,0,60),contactDab(rounded,.45f,0,60),contactDab(flat,.45f,0,60)};
        String[] labels={"Previous Filbert","Fuller Filbert","Flat"};
        int[] oldBounds=bounds(samples[0],80,95,75),newBounds=bounds(samples[1],80,95,75);
        check(newBounds[1]>=oldBounds[1]*4,"Actual default Filbert is at least four times deeper than the old thin oval");
        check(newBounds[1]>newBounds[0]*.5f&&newBounds[1]<newBounds[0]*.6f,"Filbert has a full oval silhouette, distinct from Flat and Round");
        for(int i=0;i<samples.length;i++) {
            int[] pixels=new int[160*200];samples[i].render(pixels,0,0,160,200);
            Bitmap stamp=Bitmap.createBitmap(pixels,160,200,Bitmap.Config.ARGB_8888);
            canvas.drawBitmap(stamp,i*160,25,null);stamp.recycle();canvas.drawText(labels[i],i*160+8,22,ink);
        }
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.WATERCOLOR,ToolSettings.Tool.FLAT_WASH,ToolSettings.Tool.WET_WATERCOLOR}) {
            ToolLibrary library=new ToolLibrary();library.select(tool);library.selectHead(ToolSettings.Head.FILBERT);
            check(library.current().headThickness==55&&library.current().tilt,"Every paint mode gets the fuller automatic Filbert");
            ToneDocument document=contactDab(library.current().size(64).minimum(64),.45f,0,60);
            check(Arrays.equals(document.snapshot(),samples[1].snapshot()),"Paint modes share the fuller Filbert footprint");
            byte[] painted=document.snapshot();check(document.undo()&&marks(document)==0&&document.redo()&&Arrays.equals(painted,document.snapshot()),"Fuller Filbert retains exact undo/redo");
        }
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getCacheDir(),"filbert-rounding-comparison.png"))) {
            sheet.compress(Bitmap.CompressFormat.PNG,100,out);
        }finally{sheet.recycle();}
    }
    /** A wavy pressure stroke, hard or smooth, timed in nanoseconds into {@code nanos[0]}. */
    private static ToneDocument wavyStroke(ToolSettings settings,int gray,boolean transparent,boolean smooth,long[] nanos) {
        ToneDocument doc=new ToneDocument(260,120);
        long start=System.nanoTime();
        PressureStroke stroke=new PressureStroke(doc,settings,gray,null,transparent,false,smooth);
        for(int i=0;i<=200;i++) {
            float t=i/200f;
            stroke.sample(20+220*t,60+35*(float)Math.sin(t*Math.PI*3),.1f+.35f*(float)Math.sin(t*Math.PI),0,0);
        }
        stroke.finish();nanos[0]+=System.nanoTime()-start;return doc;
    }
    /** Smooth brush edges anti-alias dry strokes; hard strokes keep whole pixels. Writes smooth-edges-comparison.png. */
    private static void smoothEdgeRaster(Instrumentation test,StringBuilder report) throws Exception {
        ToolSettings round=ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(40);
        ToolSettings[] brushes={round,ToolSettings.defaults(ToolSettings.Tool.BRUSH,ToolSettings.Head.FILBERT),
                ToolSettings.defaults(ToolSettings.Tool.BRUSH,ToolSettings.Head.FLAT),round.oilPaint(true).paintLoad(60),round};
        String[] labels={"Round","Filbert","Flat","Oil paint","Transparent"};
        int scale=2;
        Bitmap sheet=Bitmap.createBitmap(2*260*scale,brushes.length*(120*scale+20),Bitmap.Config.ARGB_8888);sheet.eraseColor(Color.WHITE);
        Canvas canvas=new Canvas(sheet);Paint ink=new Paint();ink.setColor(Color.BLACK);ink.setTextSize(16);
        long[] hardTime={0},smoothTime={0};
        for(int b=0;b<brushes.length;b++) {
            boolean transparent=b==4;
            ToneDocument hard=wavyStroke(brushes[b],0,transparent,false,hardTime),smooth=wavyStroke(brushes[b],0,transparent,true,smoothTime);
            int hardEdges=0,smoothEdges=0;long hardInk=0,smoothInk=0;
            int solid=transparent?ToneDocument.transparentTone(255,0):0;
            for(int y=0;y<120;y++)for(int x=0;x<260;x++) {
                int h=hard.tone(x,y),s=smooth.tone(x,y);
                if(h!=255&&Math.abs(h-solid)>8)hardEdges++;
                if(s!=255&&Math.abs(s-solid)>8)smoothEdges++;
                check(s>=solid-1,labels[b]+" smooth edges never darken past the paint");
                hardInk+=255-h;smoothInk+=255-s;
            }
            // Oil paint smears its own fading tone, so only solid strokes start with whole pixels.
            if(b!=3)check(hardEdges==0,labels[b]+" hard strokes lay whole pixels");
            check(smoothEdges>hardEdges+100,labels[b]+" smooth strokes shade their edges ("+smoothEdges+" vs "+hardEdges+")");
            check(Math.abs(smoothInk-hardInk)<hardInk*.04,labels[b]+" smooth strokes keep the same weight ("+smoothInk+" vs "+hardInk+")");
            byte[] painted=smooth.snapshot();
            check(smooth.undo()&&marks(smooth)==0&&smooth.redo()&&Arrays.equals(painted,smooth.snapshot()),labels[b]+" smooth strokes undo exactly");
            ToneDocument[] pair={hard,smooth};
            for(int i=0;i<2;i++) {
                Bitmap image=Bitmap.createBitmap(pair[i].exportPixels(true),260,120,Bitmap.Config.ARGB_8888);
                Bitmap big=Bitmap.createScaledBitmap(image,260*scale,120*scale,false);image.recycle();
                int top=b*(120*scale+20)+20;
                canvas.drawBitmap(big,i*260*scale,top,null);big.recycle();
                canvas.drawText(labels[b]+(i==0?" (hard)":" (smooth)"),i*260*scale+6,top-4,ink);
            }
        }
        ToneDocument doc=new ToneDocument(64,64);
        PressureStroke paint=new PressureStroke(doc,round,0,null,false,false,false);paint.sample(32,32,.45f,0,0);paint.finish();
        PressureStroke erase=new PressureStroke(doc,ToolSettings.defaults(ToolSettings.Tool.ERASER).softness(0).size(20),255,null,false,false,true);
        erase.sample(32,32,.45f,0,0);erase.finish();
        int partial=0;for(int y=0;y<64;y++)for(int x=0;x<64;x++)if(doc.opacity(x,y)>0&&doc.opacity(x,y)<255)partial++;
        check(partial>10&&doc.opacity(32,32)==0,"A smooth hard eraser leaves anti-aliased edges");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getCacheDir(),"smooth-edges-comparison.png"))) {
            sheet.compress(Bitmap.CompressFormat.PNG,100,out);
        }finally{sheet.recycle();}
        report.append("Smooth brush edges anti-alias dry strokes and the hard eraser, keep stroke weight and undo exactly; ")
                .append(brushes.length).append(" strokes took ").append(hardTime[0]/1000000).append(" ms hard, ")
                .append(smoothTime[0]/1000000).append(" ms smooth.\n");
    }
    private static void headingOnlyRaster() {
        for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT})
            for(int response:new int[]{0,50,100}) {
                ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head)
                        .size(64).minimum(1).pressureResponse(response).automaticHead();
                for(float pressure:new float[]{.05f,.1f,.2f,.45f,.9f}) {
                    ToneDocument upright=contactDab(settings,pressure,0,0);
                    for(float tilt:new float[]{10,30,60,85,-30,-60}) {
                        ToneDocument leaned=contactDab(settings,pressure,0,tilt);
                        check(Arrays.equals(upright.snapshot(),leaned.snapshot()),"Tilt magnitude and sign cannot resize or shift the head");
                    }
                    ToneDocument rotated=contactDab(settings,pressure,60,0);
                    ToneDocument reference=contactDab(settings.tilt(false).angle(90),pressure,0,0);
                    check(Arrays.equals(rotated.snapshot(),reference.snapshot()),"Tilt only rotates the pressure-sized head");
                    if(pressure>=.45f) {
                        int[] shape=bounds(upright,80,70,65);
                        check(shape[0]==64,"Firm pressure reaches maximum width for either head");
                        check(upright.tone(80,70)==0,"Contact center stays filled");
                        int cornerY=head==ToolSettings.Head.FLAT?70:85;
                        check((upright.tone(49,cornerY)==0)==(head==ToolSettings.Head.FLAT),"Flat keeps square corners and Filbert keeps rounded ends");
                    }
                }
                ToolSettings fixed=settings.minimum(64);
                check(Arrays.equals(contactDab(fixed,.05f,0,0).snapshot(),contactDab(fixed,.9f,0,60).snapshot()),
                        "Equal minimum and maximum hold footprint fixed across pressure and tilt");
                ToneDocument doc=new ToneDocument(160,200);PressureStroke stroke=new PressureStroke(doc,settings,0);
                stroke.sample(80,70,.05f,0,30);int previous=marks(doc);
                for(float pressure:new float[]{.25f,.35f,.45f}) {
                    stroke.sample(80,70,pressure,0,30);int count=marks(doc);
                    check(count>previous,"Stationary pressure grows the mark through its configured range");previous=count;
                }
                byte[] pressed=doc.snapshot();stroke.sample(80,70,.05f,0,30);stroke.finish();
                check(Arrays.equals(pressed,doc.snapshot()),"Pressure release preserves deposited paint");
                check(doc.undo()&&marks(doc)==0&&doc.redo()&&Arrays.equals(pressed,doc.snapshot()),"Stationary pressure retains exact undo/redo");
            }
    }
    private static void flatLeanPressureRaster() {
        for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT})
            for(int minimum:new int[]{1,2,64}) {
                ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head)
                        .size(64).minimum(minimum).headThickness(10).tilt(true);
                int smallest=1000,largest=0;
                for(float pressure:new float[]{.05f,.1f,.2f,.45f,.9f}) {
                    ToneDocument dab=contactDab(settings,pressure,0,60);
                    int[] shape=bounds(dab,80,100,75);
                    if(head==ToolSettings.Head.FLAT) {
                        float expected=settings.diameter(pressure);
                        check(Math.abs(shape[0]-expected)<=1&&shape[1]<=8,"Tilted Flat respects pressure width and stays thin");
                        smallest=Math.min(smallest,shape[0]);largest=Math.max(largest,shape[0]);
                    } else {
                        int band=0;for(int y=0;y<200;y++)if(dab.tone(80,y)==0)band++;
                        check(band<=8,"Filbert retains thin filled contact under firm pressure");
                    }
                }
                if(head==ToolSettings.Head.FLAT) {
                    if(minimum==64)check(largest-smallest==0,"Fixed-width Flat retains its width");
                    else check(largest-smallest>=60,"Variable-width Flat grows from minimum to maximum with pressure");
                }
            }
        for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT})
            for(int response:new int[]{0,50,100})
                for(float[] tilt:new float[][]{{0,0},{0,30},{0,60},{0,-60},{60,0},{-60,0},{60,60}}) {
                    ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head)
                            .size(128).minimum(1).pressureResponse(response).automaticHead();
                    ToneDocument doc=new ToneDocument(160,200);
                    PressureStroke stroke=new PressureStroke(doc,settings,0);
                    stroke.sample(80,70,.05f,tilt[0],tilt[1]);
                    check(marks(doc)==1&&doc.tone(80,70)==0,"Lightest touch is exactly 1px regardless of head, tilt or response");
                    stroke.sample(80,150,.05f,tilt[0],tilt[1]);stroke.finish();
                    check(marks(doc)==81,"Tilted 1px stroke stays one pixel wide");
                    for(int y=70;y<=150;y++)check(doc.tone(80,y)==0,"Tilted 1px stroke has no gaps");
                    byte[] painted=doc.snapshot();
                    check(doc.undo()&&marks(doc)==0&&doc.redo()&&Arrays.equals(painted,doc.snapshot()),"Hairline retains exact undo/redo");
                }
    }
    private static void headThicknessRaster() {
        for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT}) {
            int previous=0;
            for(int thickness:new int[]{0,10,35,70,100}) {
                ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head).size(64).minimum(64).tilt(true).headThickness(thickness);
                ToneDocument sideways=new ToneDocument(256,256),downward=new ToneDocument(256,256);
                PressureStroke side=new PressureStroke(sideways,settings,0),down=new PressureStroke(downward,settings,0);
                side.sample(40,128,.05f,0,60);side.sample(216,128,.05f,0,60);side.finish();
                down.sample(128,40,.05f,0,60);down.sample(128,216,.05f,0,60);down.finish();
                int thin=0,wide=0;
                for(int i=0;i<256;i++){if(sideways.tone(128,i)==0)thin++;if(downward.tone(i,128)==0)wide++;}
                if(head==ToolSettings.Head.FLAT)check(Math.abs(thin-settings.flatHeight(64))<=1,"Flat raster follows its selected height");
                else check(thin>0&&thin<=Math.max(1.5f,64*thickness/100f)+1,"Rounded Filbert retains the selected thin edge");
                // Measure the bristle band at its center. A swept arc's bounds
                // also depend on curvature and subpixel sampling at its ends.
                ToneDocument dab=contactDab(settings,.05f,0,60);int band=0;
                for(int y=0;y<200;y++)if(dab.tone(80,y)==0)band++;
                check(band>=previous,"Increasing thickness broadens the bristle band");previous=band;
                if(head==ToolSettings.Head.FLAT)check(wide==64,"Light contact exposes the full fixed Flat width");
                if(thickness==10)check(wide>thin*(head==ToolSettings.Head.FLAT?6:3),"Thin default preserves a broad edge and a fine sideways stroke");
                if(thickness==0) {
                    ToneDocument diagonal=new ToneDocument(256,256);
                    PressureStroke hairline=new PressureStroke(diagonal,settings.tilt(false).angle(45),0);
                    hairline.sample(30,220,.45f);hairline.sample(220,30,.45f);hairline.finish();
                    for(int i=65;i<215;i++)check(diagonal.tone(i,250-i)==0,"Hairline interpolation leaves no holes across sparse diagonal input");
                }
            }
        }
    }
    private static void wideFlatRaster() {
        for(int width:new int[]{2,64,128,192,256})for(int height:new int[]{0,5,10,20}) {
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(ToolSettings.Head.FLAT).size(width).minimum(width).headThickness(height).automaticHead();
            ToneDocument doc=new ToneDocument(512,512);PressureStroke stroke=new PressureStroke(doc,settings,0);
            stroke.sample(256,256,.45f,0,60);stroke.finish();
            int[] shape=bounds(doc,256,256,200);
            check(shape[0]==width&&Math.abs(shape[1]-Math.max(1,width*height/100f))<=1,"Flat dab matches selected width and proportional height");
            if(height==0)check(shape[1]==1,"Minimum height stays exactly 1px");
            for(int angle:new int[]{0,45,90,137}) {
                ToneDocument line=new ToneDocument(512,512);PressureStroke brush=new PressureStroke(line,settings.tilt(false).angle(angle),0);
                brush.sample(40.5f,40.5f,.45f);brush.sample(470.5f,470.5f,.45f);brush.finish();
                for(int i=41;i<470;i++)check(line.tone(i,i)==0,"Adjustable-height Flat stays continuous across sparse input and clipping: width="+width+" angle="+angle+" pixel="+i);
                byte[] painted=line.snapshot();check(line.undo()&&marks(line)==0&&line.redo()&&Arrays.equals(painted,line.snapshot()),"Wide Flat undo/redo remains exact");
            }
        }
    }
    private static void brushHeadRaster() {
        for(ToolSettings.Head head:new ToolSettings.Head[]{ToolSettings.Head.FLAT,ToolSettings.Head.FILBERT})
            for(int size:new int[]{2,3,72,128})for(int angle:new int[]{0,45,90,137,180})for(int center:new int[]{2,96}) {
                ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head).size(size).angle(angle).headThickness(head==ToolSettings.Head.FLAT?35:50);
                ToneDocument doc=new ToneDocument(192,192);
                // Preview and stroke rendering share the same rotated geometry.
                Bitmap stamp=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
                Paint ink=new Paint();ink.setColor(Color.BLACK);
                BrushStamp.draw(new Canvas(stamp),ink,center,96,size/2f,settings);
                int[] mask=new int[192*192];stamp.getPixels(mask,0,192,0,0,192,192);stamp.recycle();
                doc.begin();doc.paintMask(mask,192,0,0,192,192,0);doc.finish();
                Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                Canvas canvas=new Canvas(bitmap);Paint paint=new Paint();paint.setColor(Color.BLACK);
                float radius=size/2f,minor=head==ToolSettings.Head.FLAT?Math.max(.5f,radius*.2f):Math.max(.75f,radius*.5f);
                canvas.rotate(angle,center,96);
                if(head==ToolSettings.Head.FLAT)canvas.drawRect(center-radius,96-minor,center+radius,96+minor,paint);
                else canvas.drawOval(center-radius,96-minor,center+radius,96+minor,paint);
                int marks=0,mismatch=0;
                for(int y=0;y<192;y++)for(int x=0;x<192;x++) {
                    if(doc.tone(x,y)==0)marks++;
                    if(doc.tone(x,y)!=Color.red(bitmap.getPixel(x,y)))mismatch++;
                }
                // Transform rounding can choose an adjacent edge pixel at oblique angles.
                check(mismatch<=4,"Rotated stamp is complete: "+head+" "+size+" "+angle+" mismatch="+mismatch);
                check(marks>0,"Small and clipped brush heads remain visible: "+head+" "+size+" "+angle);bitmap.recycle();
                if(center==96&&size==72&&angle==0) {
                    check(doc.tone(96,96)==0,"Head center paints");
                    check(head==ToolSettings.Head.FLAT?doc.tone(129,96)==0&&doc.tone(96,104)==255:doc.tone(129,106)==255,"Flat has a straight edge at the height limit; Filbert has rounded corners");
                }
                byte[] painted=doc.snapshot();check(doc.undo(),"Head stroke undoes");
                check(doc.redo()&&Arrays.equals(painted,doc.snapshot()),"Head stroke redoes exactly");
            }
        for(ToolSettings.Head head:ToolSettings.Head.values()) {
            ToneDocument opaque=new ToneDocument(256,256),wash=new ToneDocument(256,256);
            ToolSettings dry=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head).size(32).angle(45);
            ToolSettings wet=ToolSettings.defaults(ToolSettings.Tool.WATERCOLOR).head(head).size(32).angle(45);
            PressureStroke a=new PressureStroke(opaque,dry,0),b=new PressureStroke(wash,wet,182);
            a.sample(20,20,.45f);a.sample(235,235,.45f);a.finish();
            b.sample(20,20,.45f);b.sample(235,235,.45f);b.finish();
            for(int i=20;i<235;i++)check(opaque.tone(i,i)==0,"Sparse input produces a continuous stroke for "+head);
            for(int y=0;y<256;y++)for(int x=0;x<256;x++)
                check(wash.tone(x,y)==(opaque.tone(x,y)==0&&DotPattern.pixel(182,x,y)==Color.BLACK?0:255),"All heads support transparent watercolor");
        }
    }
    private static void flatWashRaster() {
        byte[] base=new byte[256*128];
        for(int i=0;i<base.length;i++)base[i]=(byte)(i%256);
        for(ToolSettings.Head head:ToolSettings.Head.values()) for(int gray:new int[]{0,80,170,255}) for(int texture:new int[]{0,75}) {
            ToneDocument wash=new ToneDocument(256,128,base),footprint=new ToneDocument(256,128);
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.FLAT_WASH).head(head).size(72).minimum(5).pressureResponse(83).tilt(true).angle(35).bristles(texture);
            ToolSettings opaque=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(head).size(72).minimum(5).pressureResponse(83).tilt(true).angle(35).bristles(texture);
            PressureStroke stroke=new PressureStroke(wash,settings,gray,42),brush=new PressureStroke(footprint,opaque,0,42);
            float[][] points={{-.5f,3,.3f},{40,30,.1f},{130,64,.45f},{230,100,.25f},{255.5f,127,.4f}};
            for(float[] point:points) {
                stroke.sample(point[0],point[1],point[2],45,25);brush.sample(point[0],point[1],point[2],45,25);
            }
            boolean changed=stroke.finish();brush.finish();byte[] painted=wash.snapshot();
            for(int y=0;y<128;y++)for(int x=0;x<256;x++) {
                int previous=base[y*256+x]&255;
                check(wash.tone(x,y)==(footprint.tone(x,y)==0?Math.min(gray,previous):previous),"Flat wash matches shaped/textured footprint and keeps darker tones");
            }
            stroke=new PressureStroke(wash,settings,gray,42);
            for(float[] point:points)stroke.sample(point[0],point[1],point[2],45,25);
            check(!stroke.finish()&&Arrays.equals(painted,wash.snapshot()),"Repeating the same flat wash footprint does not darken it");
            if(changed) {
                check(wash.undo()&&Arrays.equals(base,wash.snapshot()),"Flat wash raster undoes in one step");
                check(wash.redo()&&Arrays.equals(painted,wash.snapshot()),"Flat wash raster redoes exactly");
            }
        }
    }
    private static void watercolorRaster() {
        byte[] base=new byte[256*128];
        for(int i=0;i<base.length;i++)base[i]=(byte)(i%256);
        for(int gray:GrayPalette.VALUES) {
            ToneDocument wash=new ToneDocument(256,128,base),footprint=new ToneDocument(256,128);
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.WATERCOLOR).size(72).minimum(5).pressureResponse(83);
            PressureStroke wet=new PressureStroke(wash,settings,gray);
            PressureStroke opaque=new PressureStroke(footprint,ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(72).minimum(5).pressureResponse(83),0);
            for(float[] point:new float[][]{{-.5f,3,.3f},{40,30,.1f},{130,64,.45f},{230,100,.25f},{255.5f,127,.4f}}) {
                wet.sample(point[0],point[1],point[2]);opaque.sample(point[0],point[1],point[2]);
            }
            wet.finish();opaque.finish();
            for(int y=0;y<128;y++)for(int x=0;x<256;x++) {
                boolean dot=footprint.tone(x,y)==0&&DotPattern.pixel(gray,x,y)==Color.BLACK;
                check(wash.tone(x,y)==(dot?0:base[y*256+x]&255),"Watercolor honors brush footprint, pressure, clipping and transparent gaps");
            }
        }
    }
    private static void rasterBaseline() {
        for (int gray : GrayPalette.VALUES) {
            ToneDocument doc = new ToneDocument(256, 128);
            PressureStroke stroke = new PressureStroke(doc, ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(64).minimum(2), gray);
            Bitmap expected = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888); expected.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(expected); Paint paint = new Paint(); paint.setColor(Color.rgb(gray,gray,gray));
            float[] xs = {-.5f, 20.3f, 63.9f, 97.5f, 143.1f, 200.9f, 255.5f};
            float[] ys = {5.8f, 30.1f, 50.6f, 63.5f, 62.7f, 89.2f, 125.7f};
            float[] ps = {.1f, .2f, .3f, .45f, .4f, .15f, .1f};
            float px = xs[0], py = ys[0], pr = NativePen.diameter(64,ps[0],0,0,false)/2;
            for (int n = 0; n < xs.length; n++) {
                stroke.sample(xs[n],ys[n],ps[n]);
                float r = NativePen.diameter(64,ps[n],0,0,false)/2;
                int steps = Math.max(1,(int)Math.ceil(Math.hypot(xs[n]-px,ys[n]-py)/Math.max(.5f,Math.min(r,pr)*.4f)));
                for (int s = 1; s <= steps; s++) {
                    float t = (float)s/steps;
                    canvas.drawCircle(px+(xs[n]-px)*t,py+(ys[n]-py)*t,pr+(r-pr)*t,paint);
                }
                px=xs[n]; py=ys[n]; pr=r;
            }
            stroke.finish();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 256; x++)
                check(doc.tone(x,y) == Color.red(expected.getPixel(x,y)), "0.12 circle raster parity at " + x + "," + y);
            expected.recycle();
        }
    }
    private static void event(View view, long start, int action, float x, float y, float pressure) {
        event(view,start,action,x,y,pressure,MotionEvent.TOOL_TYPE_STYLUS);
    }
    private static void event(View view, long start, int action, float x, float y, float pressure, int tool) {
        event(view,start,SystemClock.uptimeMillis(),action,x,y,pressure,tool);
    }
    private static void event(View view, long start, long time, int action, float x, float y, float pressure, int tool) {
        event(view,start,time,action,x,y,pressure,tool,0);
    }
    private static void event(View view, long start, long time, int action, float x, float y, float pressure, int tool, int buttons) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties(); properties.id=0; properties.toolType=tool;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords(); coords.x=x; coords.y=y; coords.pressure=pressure;
        MotionEvent event = MotionEvent.obtain(start,time,action,1,
                new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,buttons,1,1,0,0,
                tool != MotionEvent.TOOL_TYPE_FINGER ? InputDevice.SOURCE_STYLUS : InputDevice.SOURCE_TOUCHSCREEN,0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private static Object get(Object owner, String name) {
        try { return field(owner,name); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) {
        try { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object call(Object owner, String name, Class<?>[] signature, Object... args) {
        try { Method method=owner.getClass().getDeclaredMethod(name,signature); method.setAccessible(true); return method.invoke(owner,args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Button findButton(PaintActivity activity, String text) {
        return findButton(activity.getWindow().getDecorView(),text);
    }
    private static Button findButton(View view, String text) {
        if (view instanceof Button && (((Button)view).getText().toString().equals(text)||text.equals(view.getTag()))) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=findButton(((ViewGroup)view).getChildAt(i),text); if (found!=null) return found;
        }
        return null;
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
