package io.github.mpdairy.monopaint;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Controlled app-local stylus replay. Measures firmware bitmap widths, never physical latency. */
public final class WidthInstrumentation extends Instrumentation {
    private boolean displayProbeOnly, paletteOnly, palettePerfOnly;
    private boolean edgeBarsOnly, fullscreenOnly;
    private boolean colorBarOnly;
    private boolean pageFeedbackOnly;
    private boolean paintOnly, shapesOnly, shapesPerfOnly;
    private boolean settingsOnly, brushOnly, pickerOnly, nomadOnly, pagesOnly;
    boolean rotationPromptOnly;
    private boolean brushPerfOnly;
    private boolean storageOnly;
    private boolean orientationOnly, layersOnly, toolbarOnly, gradientOnly, airbrushOnly, eraseOnly, zoomOnly;
    private NativePen pen;
    private volatile CountDownLatch complete;
    private volatile double measured;
    private boolean pressureCurve;
    private boolean gradient;
    private boolean tiltBrush;
    private boolean tiltGradient;
    private boolean grayExperiment;
    private float leanX = 65, leanY;
    private int color = Color.BLACK;
    // White pixels in each 300-pixel Atelier screenshot sample. White is the
    // defined endpoint, invisible on the white background. See GRAY_DENSITY.md.
    private static final int[] REFERENCE_WHITE = {
            0,17,29,33,46,61,73,82,104,125,152,165,179,196,236,300
    };
    private volatile int centerColor;
    private volatile double[] segments;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        displayProbeOnly = args != null && "true".equals(args.getString("displayProbeOnly"));
        edgeBarsOnly = args != null && "true".equals(args.getString("edgeBarsOnly"));
        fullscreenOnly = args != null && "true".equals(args.getString("fullscreenOnly"));
        colorBarOnly = args != null && "true".equals(args.getString("colorBarOnly"));
        pageFeedbackOnly = args != null && "true".equals(args.getString("pageFeedbackOnly"));
        colorBarOnly |= pageFeedbackOnly;
        settingsOnly = args != null && "true".equals(args.getString("settingsOnly"));
        pickerOnly = args != null && "true".equals(args.getString("pickerOnly"));
        nomadOnly = args != null && "true".equals(args.getString("nomadOnly"));
        rotationPromptOnly = args != null && "true".equals(args.getString("rotationPromptOnly"));
        nomadOnly |= rotationPromptOnly;
        pagesOnly = args != null && "true".equals(args.getString("pagesOnly"));
        shapesPerfOnly = args != null && "true".equals(args.getString("shapesPerfOnly"));
        shapesOnly = args != null && "true".equals(args.getString("shapesOnly"));
        paintOnly = args != null && "true".equals(args.getString("paintOnly"));
        brushOnly = args != null && "true".equals(args.getString("brushOnly"));
        brushPerfOnly = args != null && "true".equals(args.getString("brushPerfOnly"));
        storageOnly = args != null && "true".equals(args.getString("storageOnly"));
        layersOnly = args != null && "true".equals(args.getString("layersOnly"));
        toolbarOnly = args != null && "true".equals(args.getString("toolbarOnly"));
        eraseOnly = args != null && "true".equals(args.getString("eraseOnly"));
        zoomOnly = args != null && "true".equals(args.getString("zoomOnly"));
        airbrushOnly = args != null && "true".equals(args.getString("airbrushOnly"));
        gradientOnly = args != null && "true".equals(args.getString("gradientOnly"));
        orientationOnly = args != null && "true".equals(args.getString("orientationOnly"));
        paletteOnly = args != null && "true".equals(args.getString("paletteOnly"));
        palettePerfOnly = args != null && "true".equals(args.getString("palettePerfOnly"));
        start();
    }
    /**
     * Without file access the app opens on its "Keep your paintings safe" prompt, which would
     * take window focus from every check. An isolated checks build keeps its own shared folder.
     */
    private void allowSharedStorage() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return;
        android.os.ParcelFileDescriptor output = getUiAutomation().executeShellCommand(
                "appops set --uid " + getTargetContext().getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
            while (in.read() != -1) { }
        } catch (java.io.IOException ignored) { }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        allowSharedStorage();
        if (edgeBarsOnly || fullscreenOnly || colorBarOnly || settingsOnly || pagesOnly || nomadOnly || pickerOnly || shapesPerfOnly || shapesOnly || palettePerfOnly || paletteOnly || paintOnly || brushOnly || brushPerfOnly || storageOnly || orientationOnly || layersOnly || toolbarOnly || gradientOnly || airbrushOnly || eraseOnly || zoomOnly) {
            try {
                if (edgeBarsOnly) EdgeBarChecks.run(this,report);
                else if (fullscreenOnly) FullscreenChecks.run(this,report);
                else if (colorBarOnly) ColorBarChecks.run(this,report,pageFeedbackOnly);
                else if (settingsOnly) ShapeUiChecks.run(this,report,false,false,false,false,true);
                else if (pagesOnly) ShapeUiChecks.run(this,report,false,false,false,true);
                else if (nomadOnly) ShapeUiChecks.run(this,report,false,false,true);
                else if (pickerOnly) ShapeUiChecks.run(this,report,false,true);
                else if (shapesPerfOnly) ShapeUiChecks.run(this,report,true);
                else if (shapesOnly) ShapeUiChecks.run(this,report);
                else if (palettePerfOnly) PaletteUiChecks.run(this,report,true);
                else if (paletteOnly) PaletteUiChecks.run(this,report);
                else if (zoomOnly) ZoomUiChecks.run(this,report);
                else if (eraseOnly) EraseUiChecks.run(this,report);
                else if (airbrushOnly) AirbrushUiChecks.run(this,report);
                else if (gradientOnly) GradientUiChecks.run(this,report);
                else if (toolbarOnly) ToolbarChecks.run(this,report);
                else if (layersOnly) LayerUiChecks.run(this,report);
                else if (orientationOnly) OrientationChecks.run(this,report);
                else if (storageOnly) StorageChecks.run(this, report);
                else if (brushPerfOnly) BrushPerformanceChecks.run(report);
                else PaintChecks.run(this, report, brushOnly);
                result.putString("stream", report.toString());
                finish(Activity.RESULT_OK, result);
            } catch (Throwable error) {
                result.putString("stream", report + "FAILED: " + android.util.Log.getStackTraceString(error));
                finish(Activity.RESULT_CANCELED, result);
            }
            return;
        }
        if (displayProbeOnly) {
            result.putString("stream", DirectEink.probe() + "\n");
            finish(Activity.RESULT_OK,result);
            return;
        }
        Activity activity = null;
        try {
            Intent intent = new Intent(getTargetContext(), ProbeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            waitForIdleSync(); SystemClock.sleep(500);
            Field ink = ProbeActivity.class.getDeclaredField("ink"); ink.setAccessible(true);
            Field pad = ProbeActivity.class.getDeclaredField("pad"); pad.setAccessible(true);
            NativePen original = (NativePen)ink.get(activity);
            View host = (View)pad.get(activity);
            verifyPresentation(activity, host, original, report);
            verifyDirectPresentation(activity, host, original, report);
            clickRequestSwitch(activity,"Update: bitmap");
            verifyDirectPresentation(activity, host, original, report);
            clickRequestSwitch(activity,"Update: previous");
            report.append("Both observed request flags passed the same direct drawing regressions.\n");
            verifyDotPresentation(activity, host, original, report);
            runOnMainSync(() -> {
                original.close();
                pen = new NativePen(host, (bitmap, dirty) -> {
                    measured = measure(bitmap, dirty);
                    centerColor = dirty.contains(700,500) ? bitmap.getPixel(700-dirty.left,500-dirty.top) : 0;
                    segments = new double[]{measureAt(bitmap,dirty,450), measureAt(bitmap,dirty,720), measureAt(bitmap,dirty,990)};
                    bitmap.recycle();
                    if (complete != null) complete.countDown();
                });
            });
            for (int type : new int[]{2, 4}) {
                double light = stroke(type, 32, .10f);
                double heavy = stroke(type, 32, .40f);
                double higher = stroke(type, 32, .80f);
                report.append(String.format(Locale.US,
                        "type=%d stdWidth=32: pressure .10=%.2fpx .40=%.2fpx .80=%.2fpx\n",
                        type, light, heavy, higher));
            }
            report.append(String.format(Locale.US, "type=4 pressure=.40 stdWidth4=%.2fpx stdWidth64=%.2fpx\n",
                    stroke(4, 4, .40f), stroke(4, 64, .40f)));
            pressureCurve = true;
            gradient = true;
            stroke(2,64,.1f);
            report.append(String.format(Locale.US,"Custom native brush, one stroke light/heavy/light: %.2f / %.2f / %.2f px\n",
                    segments[0],segments[1],segments[2]));
            if (segments[1] < segments[0]*3 || segments[1] < segments[2]*3)
                throw new IllegalStateException("Native within-stroke pressure response missing");
            gradient = false; tiltGradient = true; tiltBrush = true;
            stroke(2,64,.15f);
            report.append(String.format(Locale.US,"Native tilt brush, native degree axes upright/65deg/upright: %.2f / %.2f / %.2f px\n",
                    segments[0],segments[1],segments[2]));
            if (segments[1] < segments[0]*2 || segments[1] < segments[2]*2)
                throw new IllegalStateException("Native tilt response missing");
            leanX = 0; leanY = -65;
            stroke(2,64,.15f);
            if (segments[1] < segments[0]*2 || segments[1] < segments[2]*2)
                throw new IllegalStateException("Negative Y-axis native tilt response missing");
            report.append("Negative Y-axis tilt broadens too.\n");
            tiltBrush = false;
            stroke(2,64,.15f);
            if (Math.abs(segments[1]-segments[0]) > 2 || Math.abs(segments[1]-segments[2]) > 2)
                throw new IllegalStateException("Tilt-off changed the pressure-only brush");
            report.append("Tilt-off preserves pressure-only width.\n");
            tiltGradient = false; grayExperiment = true;
            for (int gray : GrayPalette.VALUES) {
                color = Color.rgb(gray,gray,gray);
                stroke(2,64,.4f);
                if (centerColor != color)
                    throw new IllegalStateException("Native gray " + gray + " produced " + Integer.toHexString(centerColor));
            }
            tiltGradient = true; tiltBrush = true; color = Color.rgb(170,170,170);
            stroke(2,64,.15f);
            if (segments[1] < segments[0]*2 || segments[1] < segments[2]*2)
                throw new IllegalStateException("Native canvas tilt response missing");
            tiltGradient = false; tiltBrush = false; color = Color.BLACK;
            stroke(2,64,.4f);
            if (centerColor != Color.BLACK) throw new IllegalStateException("Original black path not restored");
            report.append("Native canvas tilt and switching back to original black passed.\n");
            report.append("All 16 Atelier grays survive in opaque native bitmap pixels; physical live shades/latency unverified.\n");
            result.putString("stream", report.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", report + "FAILED: " + error);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (pen != null) runOnMainSync(() -> pen.close());
            if (activity != null) { Activity a = activity; runOnMainSync(a::finish); }
        }
    }
    private void verifyPresentation(Activity activity, View host, NativePen original,
                                    StringBuilder report) throws Exception {
        Field tilt = ProbeActivity.class.getDeclaredField("tiltBrush"); tilt.setAccessible(true);
        if (tilt.getBoolean(activity)) throw new IllegalStateException("Paint brush tilt should default off");
        setDirect(activity,false);
        // Use the actual Activity listener and palette, before replacing the
        // adapter for width measurements. No Show bitmap or swatch tap at UP.
        replayOnPad(host, 600, .4f, -1);
        selectSwatch(activity, 136);
        assertScreenPixel(host, 700,600,Color.BLACK,"black retained before overpainting");
        replayOnPad(host,500,.4f,136);
        assertScreenPixel(host,1000,500,Color.rgb(136,136,136),"gray after UP without palette tap");
        replayOnPad(host,700,.4f,136);
        assertScreenPixel(host,1000,700,Color.rgb(136,136,136),"second gray stroke without reconfigure");
        selectSwatch(activity,255);
        assertScreenPixel(host,700,600,Color.BLACK,"black background for white test");
        replayOnPad(host,600,.2f,255);
        assertScreenPixel(host,1000,600,Color.WHITE,"white over black after UP");
        assertScreenPixel(host,700,618,Color.BLACK,"white leaves surrounding black intact");
        assertScreenPixel(host,700,500,Color.rgb(136,136,136),"gray retained after white");
        if (original.status.contains("failed")) throw new IllegalStateException(original.status);
        report.append("Activity/display screenshots: gray during stroke and after UP, repeated gray stroke, white over black live/after UP, neighboring black preserved; default tilt off.\n");
        // Return the real Activity to black before the isolated bridge checks.
        selectSwatch(activity,0);
    }
    private void setDirect(Activity activity, boolean direct) throws Exception {
        Field field=ProbeActivity.class.getDeclaredField("directGray"); field.setAccessible(true);
        runOnMainSync(() -> {
            try { field.setBoolean(activity,direct); }
            catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        });
    }
    private void clickRequestSwitch(Activity activity, String label) {
        runOnMainSync(() -> {
            Button button=findButton(activity.getWindow().getDecorView(),label);
            if (button==null) throw new IllegalStateException("Missing request comparison switch: "+label);
            button.performClick();
        });
        waitForIdleSync(); SystemClock.sleep(300);
    }
    private static Button findButton(View view,String label) {
        if (view instanceof Button && ((Button)view).getText().toString().equals(label)) return (Button)view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                Button result=findButton(group.getChildAt(i),label);
                if (result!=null) return result;
            }
        }
        return null;
    }
    private void verifyDirectPresentation(Activity activity, View host, NativePen original,
                                          StringBuilder report) throws Exception {
        setDirect(activity,true);
        selectSwatch(activity,0);
        replayOnPad(host,1100,.4f,-1);
        for (int gray : new int[]{80,136,225,255}) {
            selectSwatch(activity,gray);
            Field field=NativePen.class.getDeclaredField("directEink"); field.setAccessible(true);
            DirectEink display=(DirectEink)field.get(original);
            if (display==null) throw new IllegalStateException("Direct presenter not configured: "+original.status);
            int y=gray==255 ? 1100 : 900;
            Field draws=host.getClass().getDeclaredField("drawCount"); draws.setAccessible(true);
            Field posts=NativePen.class.getDeclaredField("directUpdates"); posts.setAccessible(true);
            int beforeDraws=draws.getInt(host);
            replayOnPad(host,y,.2f,-1,() -> {
                int actual=display.readGray(700,y);
                if (actual!=(gray>>4)) throw new IllegalStateException("Driver buffer gray "+gray+" got "+actual);
                try {
                    if (posts.getInt(original)==0) throw new IllegalStateException("No direct update accepted before UP");
                    if (draws.getInt(host)!=beforeDraws)
                        throw new IllegalStateException("Android redrew during the direct stroke");
                    Field controllerField=NativePen.class.getDeclaredField("controller"); controllerField.setAccessible(true);
                    Object controller=controllerField.get(original);
                    if (Boolean.TRUE.equals(controller.getClass().getMethod("isCurrentWriting").invoke(controller)))
                        throw new IllegalStateException("Direct stroke entered the firmware writing path");
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            });
            if (draws.getInt(host)!=beforeDraws) throw new IllegalStateException("Android redrew at direct pen-up");
            assertRetainedPixel(host,1000,y,Color.rgb(gray,gray,gray));
            // Repeating the identical opaque stroke must not refresh pixels
            // that already have the requested physical gray level.
            replayOnPad(host,y,.2f,-1);
            if (posts.getInt(original)!=0) throw new IllegalStateException("Unchanged stroke refreshed display");
            if (draws.getInt(host)!=beforeDraws) throw new IllegalStateException("Unchanged stroke redrew Android");
            if (original.status.contains("failed")) throw new IllegalStateException(original.status);
            Bitmap tiny=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
            try {
                display.present(tiny,new Rect(-1,0,4,4));
                throw new IllegalStateException("Out-of-canvas region was accepted");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("outside app canvas")) throw expected;
            } finally { tiny.recycle(); }
        }
        assertRetainedPixel(host,700,1118,Color.BLACK);
        selectSwatch(activity,0);
        assertScreenPixel(host,1000,1100,Color.WHITE,"quiet white commit survives later UI redraw");
        assertScreenPixel(host,1000,900,Color.rgb(225,225,225),"quiet gray commit survives later UI redraw");
        verifyDirectHistory(activity,host,report);
        verifyDirectCurves(activity,host,original,report);
        selectSwatch(activity,0);
        report.append("Direct display: gray/white driver pixels before UP; firmware writing stays off; zero Android redraw during stroke AND pen-up; identical overpainting submits no updates; retained gray/white survive later UI redraw; out-of-canvas updates rejected. Physical artifacts still need checking.\n");
    }
    private void assertMappedCanvas(View host, DirectEink display, Rect area, int step) throws Exception {
        Field field=host.getClass().getDeclaredField("backing"); field.setAccessible(true);
        Bitmap backing=(Bitmap)field.get(host);
        for (int y=area.top;y<area.bottom;y+=step)
            for (int x=area.left;x<area.right;x+=step)
                if (display.readGray(x,y)!=(Color.red(backing.getPixel(x,y))>>4))
                    throw new IllegalStateException("Mapped canvas mismatch at "+x+","+y
                            +" actual="+display.readGray(x,y)+" expected="+(Color.red(backing.getPixel(x,y))>>4));
    }
    private void verifyDotPresentation(Activity activity, View host, NativePen original,
                                       StringBuilder report) throws Exception {
        int previousWhite = -1;
        for (int grade=0;grade<GrayPalette.VALUES.length;grade++) {
            int gray=GrayPalette.VALUES[grade];
            Bitmap tile = DotGray.tile(gray);
            int white = 0;
            try {
                for (int y=0;y<8;y++) for (int x=0;x<8;x++) {
                    int pixel=tile.getPixel(x,y);
                    if (pixel==Color.WHITE) white++;
                    else if (pixel!=Color.BLACK) throw new IllegalStateException("Nonbinary/transparent dot");
                }
                if (white<=previousWhite || Math.abs(white/64.0-REFERENCE_WHITE[grade]/300.0)>.01)
                    throw new IllegalStateException("Dot palette collapsed or incorrect density at "+gray);
                previousWhite=white;
            } finally { tile.recycle(); }
        }
        int lastCount=-1;
        for (int gray=0;gray<=255;gray++) {
            int count=DotGray.whiteCount(gray);
            if (count<lastCount || count<0 || count>64)
                throw new IllegalStateException("Dot interpolation not monotonic at "+gray);
            lastCount=count;
        }
        clickRequestSwitch(activity,"Shade: solid");
        selectSwatch(activity,0);
        replayOnPad(host,1500,.4f,-1);
        Field field=NativePen.class.getDeclaredField("directEink"); field.setAccessible(true);
        Field draws=host.getClass().getDeclaredField("drawCount"); draws.setAccessible(true);
        Field posts=NativePen.class.getDeclaredField("directUpdates"); posts.setAccessible(true);
        for (int grade=0;grade<GrayPalette.VALUES.length;grade++) {
            int gray=GrayPalette.VALUES[grade];
            if (gray==0) continue;
            double expectedWhite=REFERENCE_WHITE[grade]/300.0;
            selectSwatch(activity,gray);
            DirectEink display=(DirectEink)field.get(original);
            if (display==null) throw new IllegalStateException("Dot presenter missing: "+original.status);
            int beforeDraws=draws.getInt(host);
            replayOnPad(host,1500,.3f,-1,() -> {
                int white=0;
                for (int y=1496;y<1504;y++) for (int x=696;x<704;x++) {
                    int pixel=display.readGray(x,y);
                    if (pixel==15) white++;
                    else if (pixel!=0) throw new IllegalStateException("Intermediate gray in live dot stroke");
                }
                if (Math.abs(white/64.0-expectedWhite)>.01)
                    throw new IllegalStateException("Incorrect live dot density for "+gray);
            });
            if (draws.getInt(host)!=beforeDraws)
                throw new IllegalStateException("Dot stroke/pen-up redrew Android");
            assertMappedCanvas(host,display,new Rect(680,1486,720,1514),1);
            assertRetainedPixel(host,700,1518,Color.BLACK);
            replayOnPad(host,1500,.3f,-1);
            if (posts.getInt(original)!=0) throw new IllegalStateException("Repeated dot stroke refreshed/darkened");
        }
        assertRetainedPixel(host,700,1500,Color.WHITE);
        // A fresh dark dot stroke over white must retain the same opaque pattern
        // in both app bitmaps and on the next ordinary Android redraw.
        selectSwatch(activity,136);
        replayOnPad(host,1500,.3f,-1);
        Bitmap tile=DotGray.tile(136);
        try {
            for (int y=1496;y<1504;y++) for (int x=696;x<704;x++)
                assertRetainedPixel(host,x,y,tile.getPixel(x%8,y%8));
            selectSwatch(activity,0);
            int[] location=new int[2];
            runOnMainSync(() -> host.getLocationOnScreen(location));
            Bitmap screen=getUiAutomation().takeScreenshot();
            if (screen==null) throw new IllegalStateException("No dot retention screenshot");
            try {
                for (int y=1496;y<1504;y++) for (int x=696;x<704;x++)
                    if (screen.getPixel(location[0]+x,location[1]+y)!=tile.getPixel(x%8,y%8))
                        throw new IllegalStateException("Dot pattern changed on UI redraw");
            } finally { screen.recycle(); }
        } finally { tile.recycle(); }
        verifyDirectCurves(activity,host,original,report);
        clickRequestSwitch(activity,"Shade: dots");
        selectSwatch(activity,136);
        replayOnPad(host,1500,.3f,-1);
        assertRetainedPixel(host,700,1500,Color.rgb(136,136,136));
        setDirect(activity,false);
        selectSwatch(activity,136);
        replayOnPad(host,1500,.3f,136);
        setDirect(activity,true);
        selectSwatch(activity,0);
        report.append("Dots: all 16 palette densities distinct, opaque and within 1 percentage point of sampled Atelier coverage; monotonic interpolation; live mapped pixels binary; opaque overpainting/no-op replay; neighboring black and curve borders preserved; zero Android stroke/UP redraws; identical retained/shown/redrawn pattern; solid and View fallback restored. Physical shade/texture matching unverified.\n");
    }
    private void verifyDirectCurves(Activity activity, View host, NativePen original,
                                    StringBuilder report) throws Exception {
        for (int gray : new int[]{136,255}) {
            selectSwatch(activity,gray);
            long down=SystemClock.uptimeMillis();
            for (int i=0;i<=100;i++) {
                int sample=i;
                runOnMainSync(() -> {
                    double angle=Math.min(sample,99)*Math.PI*2/99;
                    float x=1420+(float)Math.cos(angle)*180;
                    float y=1150+(float)Math.sin(angle*2)*220;
                    float pressure=.1f+.3f*(float)Math.pow(Math.sin(angle),2);
                    int action=sample==0 ? MotionEvent.ACTION_DOWN
                            : sample==100 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                    MotionEvent event=stylusEvent(down,SystemClock.uptimeMillis(),action,x,y,pressure);
                    host.onTouchEvent(event); event.recycle();
                });
                SystemClock.sleep(10);
            }
            waitForIdleSync();
            Field field=NativePen.class.getDeclaredField("directEink"); field.setAccessible(true);
            assertMappedCanvas(host,(DirectEink)field.get(original),new Rect(1200,890,1640,1410),1);
        }
        report.append("Every mapped pixel around crossing gray/white pressure curves matches retained bitmap, including unpainted borders. This does not measure physical panel artifacts.\n");
    }
    private void verifyDirectHistory(Activity activity, View host, StringBuilder report) throws Exception {
        selectSwatch(activity,80);
        long down=SystemClock.uptimeMillis();
        runOnMainSync(() -> {
            MotionEvent start=stylusEvent(down,down,MotionEvent.ACTION_DOWN,300,1300,.1f);
            host.onTouchEvent(start); start.recycle();
            MotionEvent move=stylusEvent(down,down+10,MotionEvent.ACTION_MOVE,450,1300,.1f);
            MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();
            coords.x=700; coords.y=1300; coords.pressure=.4f; coords.size=1;
            move.addBatch(down+20,new MotionEvent.PointerCoords[]{coords},0);
            coords.x=990; coords.pressure=.1f;
            move.addBatch(down+30,new MotionEvent.PointerCoords[]{coords},0);
            host.onTouchEvent(move); move.recycle();
            MotionEvent end=stylusEvent(down,down+40,MotionEvent.ACTION_UP,990,1300,.1f);
            host.onTouchEvent(end); end.recycle();
        });
        waitForIdleSync();
        Field field=host.getClass().getDeclaredField("backing"); field.setAccessible(true);
        Bitmap section=Bitmap.createBitmap((Bitmap)field.get(host),300,1260,720,80);
        try {
            Rect dirty=new Rect(300,1260,1020,1340);
            double light=measureAt(section,dirty,450), heavy=measureAt(section,dirty,700);
            if (heavy<light*3 || heavy<40) throw new IllegalStateException("Direct history lost pressure variation");
            report.append(String.format(Locale.US,"Direct batched pen history retains light/heavy pressure: %.2f / %.2f px.\n",light,heavy));
        } finally { section.recycle(); }
    }
    private static MotionEvent stylusEvent(long down,long time,int action,float x,float y,float pressure) {
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();
        prop.id=0; prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();
        point.x=x; point.y=y; point.pressure=pressure; point.size=1;
        return MotionEvent.obtain(down,time,action,1,new MotionEvent.PointerProperties[]{prop},
                new MotionEvent.PointerCoords[]{point},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
    }
    private void assertRetainedPixel(View host, int x, int y, int expected) throws Exception {
        for (String name : new String[]{"backing","shown"}) {
            Field bitmapField=host.getClass().getDeclaredField(name); bitmapField.setAccessible(true);
            Bitmap bitmap=(Bitmap)bitmapField.get(host);
            if (bitmap.getPixel(x,y)!=expected) throw new IllegalStateException(name+" lost quiet commit at "+x+","+y);
        }
    }
    private void selectSwatch(Activity activity, int gray) {
        runOnMainSync(() -> {
            Button button = findSwatch(activity.getWindow().getDecorView(),gray);
            if (button == null) throw new IllegalStateException("Missing swatch " + gray);
            button.performClick();
        });
        waitForIdleSync(); SystemClock.sleep(300);
    }
    private static Button findSwatch(View view, int gray) {
        if (view instanceof Button && ((Button)view).getText().toString().replace("•", "").equals(Integer.toString(gray)))
            return (Button)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) {
                Button found = findSwatch(group.getChildAt(i),gray);
                if (found != null) return found;
            }
        }
        return null;
    }
    private void replayOnPad(View host, int y, float pressure, int liveGray) {
        replayOnPad(host,y,pressure,liveGray,null);
    }
    private void replayOnPad(View host, int y, float pressure, int liveGray, Runnable liveCheck) {
        long down = SystemClock.uptimeMillis();
        for (int step=0; step<=81; step++) {
            int index = step;
            runOnMainSync(() -> {
                MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
                prop.id=0; prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
                MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
                point.x=300+Math.min(index,80)*10; point.y=y; point.pressure=pressure; point.size=1;
                int action=index==0 ? MotionEvent.ACTION_DOWN : index==81 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,
                        new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{point},
                        0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
                host.onTouchEvent(event); event.recycle();
            });
            SystemClock.sleep(10);
            if (step==50 && liveCheck!=null) liveCheck.run();
            if (step==50 && liveGray>=0) {
                SystemClock.sleep(150);
                assertScreenPixel(host,700,y,Color.rgb(liveGray,liveGray,liveGray),"live gray " + liveGray + " before UP");
            }
        }
        waitForIdleSync(); SystemClock.sleep(200);
    }
    private void assertScreenPixel(View host, int x, int y, int expected, String label) {
        int[] location = new int[2];
        runOnMainSync(() -> host.getLocationOnScreen(location));
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot == null) throw new IllegalStateException("No screenshot: " + label);
        try {
            int actual=screenshot.getPixel(location[0]+x,location[1]+y);
            if (actual != expected) throw new IllegalStateException(label + ": expected "
                    + Integer.toHexString(expected) + " got " + Integer.toHexString(actual));
        } finally { screenshot.recycle(); }
    }
    private double stroke(int type, int width, float pressure) throws Exception {
        complete = new CountDownLatch(1); measured = -1;
        runOnMainSync(() -> {
            pen.clear();
            if (!pen.configure(type, width, color, true, pressureCurve, tiltBrush, grayExperiment)) throw new IllegalStateException(pen.status);
        });
        SystemClock.sleep(250);
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 81; i++) {
            final int step = i;
            runOnMainSync(() -> {
                MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
                prop.id = 0; prop.toolType = MotionEvent.TOOL_TYPE_STYLUS;
                MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
                point.x = 300 + Math.min(step,80)*10; point.y = 500;
                point.pressure = gradient ? (step >= 27 && step < 54 ? .4f : .1f) : pressure; point.size = 1;
                point.setAxisValue(MotionEvent.AXIS_TILT,
                        tiltGradient && step >= 27 && step < 54 ? leanY : 0);
                point.orientation = tiltGradient && step >= 27 && step < 54 ? leanX : 0;
                int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 81 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                MotionEvent event = MotionEvent.obtain(down, down + step*10, action, 1,
                        new MotionEvent.PointerProperties[]{prop}, new MotionEvent.PointerCoords[]{point},
                        0, 0, 1, 1, 0, 0, InputDevice.SOURCE_STYLUS, 0);
                pen.forward(event); event.recycle();
            });
            SystemClock.sleep(10);
        }
        if (!complete.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("No callback for type " + type);
        if (Color.red(color) < 128 && measured <= 0) throw new IllegalStateException("Empty middle of native stroke");
        if (pen.status.contains("failed")) throw new IllegalStateException(pen.status);
        return measured;
    }
    private static double measure(Bitmap b, Rect dirty) {
        return measureRange(b,dirty,600,800);
    }
    private static double measureAt(Bitmap b, Rect dirty, int center) {
        return measureRange(b,dirty,center-20,center+20);
    }
    private static double measureRange(Bitmap b, Rect dirty, int start, int end) {
        int count = 0, columns = 0;
        for (int x = Math.max(start,dirty.left); x < Math.min(end,dirty.right); x++) {
            columns++;
            for (int y = 0; y < b.getHeight(); y++) {
                int pixel = b.getPixel(x-dirty.left,y);
                if (Color.alpha(pixel) > 127 && Color.red(pixel) < 255) count++;
            }
        }
        return columns == 0 ? 0 : (double)count/columns;
    }
}
