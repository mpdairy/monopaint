package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises app-only rotation on a temporary page, then restores the original book. */
final class OrientationChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity = (PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test,() -> !(Boolean)get(activity,"loading") && get(get(activity,"pad"),"document") != null,"Recovered document");
        Object pad = get(activity,"pad");
        DrawingBook original = (DrawingBook)get(activity,"book");
        String name = (String)get(activity,"drawingName");
        SharedPreferences preferences = (SharedPreferences)get(activity,"preferences");
        boolean right = preferences.getBoolean("toolbox_right",false);
        ToolLibrary library = (ToolLibrary)get(activity,"library");
        int gray = (Integer)get(get(activity,"paint"),"gray"), maximum = TestAccess.maximum(activity);
        boolean wet = (Boolean)get(get(activity,"paint"),"wetCanvas"), transparent = (Boolean)get(get(activity,"paint"),"transparentPaint");
        OrientationEventListener sensor = (OrientationEventListener)get(get(activity,"rotationPrompt"),"orientationSensor");
        // Pen/display assertions use full-tablet coordinates, regardless of the
        // dimensions of a previously opened drawing in this test install.
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        float density = activity.getResources().getDisplayMetrics().density;
        ToneDocument doc = new ToneDocument(metrics.widthPixels-Math.round(64*density),
                metrics.heightPixels-Math.round(48*density));
        boolean originalErase=(Boolean)get(get(activity,"paint"),"eraseMode");
        try {
            main(test,() -> {
                set(get(activity,"paint"),"eraseMode",false);
                sensor.disable();
                check(sensor.canDetectOrientation(),"Accelerometer is exposed");
                call(get(activity,"rotationPrompt"),"hide");
                set(activity,"drawingName",""); set(get(activity,"paint"),"gray",0); TestAccess.setMaximum(activity,20);
                set(get(activity,"paint"),"wetCanvas",false); set(get(activity,"paint"),"transparentPaint",false);
                set(activity,"library",new ToolLibrary());
                call(pad,"replace",new Class<?>[]{ToneDocument.class},doc);
                preferences.edit().putBoolean("toolbox_right",false).apply(); call(activity,"applyToolboxSide");
            });
            await(test,() -> get(pad,"direct") != null,"Portrait direct display");
            portraitHands(test,activity,preferences);
            main(test,() -> {
                stroke(activity,pad,140,180,280,180);
                check(doc.tone(200,180)==0,"Portrait pen coordinates");
                // Exercise the actual listener, including its delayed check with NO second sample.
                sensor.onOrientationChanged(270);
                check(((View)get(activity,"rotateButton")).getVisibility()==View.INVISIBLE,"Suggestion does not appear immediately");
            });
            await(test,() -> ((View)get(activity,"rotateButton")).getVisibility()==View.VISIBLE,"Stable suggestion appears without a second event");
            check(activity.getWindowManager().getDefaultDisplay().getRotation()==Surface.ROTATION_0,"Sensor alone never rotates");
            byte[] portrait = doc.snapshot();
            main(test,() -> ((View)get(activity,"rotateButton")).performClick());
            awaitRotation(test,activity,Surface.ROTATION_90,true);
            main(test,() -> {
                check(get(activity,"pad")==pad && get(pad,"document")==doc,"Rotation preserves Activity, pad and document identity");
                check(Arrays.equals(portrait,doc.snapshot()),"Rotation preserves all tones");
                check(doc.undo(),"Rotation preserves undo history");
                check(doc.tone(200,180)==255,"Undo after rotation targets original stroke");
                check(doc.redo(),"Redo survives rotation");
                call(pad,"renderAll");
                layout(activity,false);
                check(get(pad,"direct")!=null,"Landscape uses direct e-ink presentation");
                // The page stays in the panel's portrait frame. Only a buffer scanned in another frame needs a rotated copy.
                check((get(get(pad,"direct"),"buffer")==null)==DirectEink.bufferFromPanel().isIdentity(),"Landscape page reaches the panel without a rotated bitmap copy when the driver buffer is portrait");
                stroke(activity,pad,320,340,480,340);
                check(doc.tone(400,340)==0,"Landscape pen coordinates match displayed page");
                // Header touches travel through the rotated View hierarchy.
                View shade = (View)get(activity,"shadePicker");
                tapLocal(activity,shade,1,shade.getHeight()/2f);
                check((Integer)get(get(activity,"paint"),"gray")==255,"Top of landscape shade strip selects white");
                tapLocal(activity,shade,shade.getWidth()-1,shade.getHeight()/2f);
                check((Integer)get(get(activity,"paint"),"gray")==0,"Bottom of landscape shade strip selects black");
                set(get(activity,"paint"),"gray",0);
                check(((View)get(activity,"rotateButton")).getVisibility()==View.INVISIBLE,"Accepted suggestion disappears");
            });
            report.append("Landscape suggestion waits for a stable sensor angle and a tap; document, undo, pen and rotated palette hit testing pass.\n");
            screenshot(test,activity,"orientation-right.png");
            fileMenuChecks(test,activity);
            fastStrokeAndRefresh(test,activity,doc,report);
            dialogChecks(test,activity);
            horizontalDrag(test,activity,doc);
            Matrix rightHandPage = new Matrix((Matrix)get(pad,"pageToView"));
            main(test,() -> { preferences.edit().putBoolean("toolbox_right",true).apply(); call(activity,"applyToolboxSide"); });
            test.waitForIdleSync();
            main(test,() -> {
                layout(activity,true);
                check(rightHandPage.equals(get(pad,"pageToView")),"Changing drawing hand never turns or moves the page within its canvas");
                stroke(activity,pad,540,540,700,540);
                check(doc.tone(620,540)==0,"Left-handed landscape pen coordinates");
            });
            screenshot(test,activity,"orientation-left.png");
            fileMenuChecks(test,activity);
            main(test,() -> call(activity,"requestQuarter",new Class<?>[]{int.class},1));
            awaitRotation(test,activity,Surface.ROTATION_270,true);
            main(test,() -> {
                layout(activity,true);
                stroke(activity,pad,720,740,880,740);
                check(doc.tone(800,740)==0,"Reverse landscape pen coordinates");
            });
            screenshot(test,activity,"orientation-left-right-edge-down.png");
            fileMenuChecks(test,activity);
            main(test,() -> {
                preferences.edit().putBoolean("toolbox_right",false).apply(); call(activity,"applyToolboxSide");
            });
            test.waitForIdleSync();
            main(test,() -> layout(activity,false));
            screenshot(test,activity,"orientation-right-edge-down.png");
            fileMenuChecks(test,activity);
            fastStrokeAndRefresh(test,activity,doc,report);
            dialogChecks(test,activity);
            horizontalDrag(test,activity,doc);
            main(test,() -> {
                call(activity,"requestQuarter",new Class<?>[]{int.class},2);
            });
            awaitRotation(test,activity,Surface.ROTATION_180,false);
            portraitHands(test,activity,preferences);
            main(test,() -> {
                check(get(pad,"document")==doc,"Upside-down portrait retains the page");
                sensor.onOrientationChanged(0);
            });
            await(test,() -> ((View)get(activity,"rotateButton")).getVisibility()==View.VISIBLE,"Portrait return suggestion");
            screenshot(test,activity,"orientation-suggestion.png");
            main(test,() -> {
                View rotate = (View)get(activity,"rotateButton");
                tapLocal(activity,rotate,rotate.getWidth()/2f,rotate.getHeight()/2f);
            });
            awaitRotation(test,activity,Surface.ROTATION_0,false);
            await(test,() -> get(pad,"direct") != null,"Portrait direct display reconnects");
            main(test,() -> {
                check(get(pad,"document")==doc,"Same document after round trip");
                check(doc.tone(200,180)==0 && doc.tone(400,340)==0 && doc.tone(620,540)==0,"All rotated strokes retained");
                sensor.onOrientationChanged(-1);
                check(((View)get(activity,"rotateButton")).getVisibility()==View.INVISIBLE,"Flat tablet has no suggestion");
            });
            report.append("All four app orientations keep Android in portrait and artwork fixed to the tablet; direct e-ink, tool placement, both preset rails, black-bottom slider, portrait return and refresh pass.\n");
        } finally {
            main(test,() -> {
                call(activity,"requestQuarter",new Class<?>[]{int.class},0);
                set(activity,"library",library);set(get(activity,"paint"),"eraseMode",originalErase); set(get(activity,"paint"),"gray",gray); TestAccess.setMaximum(activity,maximum);
                set(get(activity,"paint"),"wetCanvas",wet); set(get(activity,"paint"),"transparentPaint",transparent);
                preferences.edit().putBoolean("toolbox_right",right).apply();
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                set(activity,"drawingName",name); call(activity,"applyToolboxSide");
                call(activity,"refreshPaintModes"); call(activity,"saveToolState"); call(activity,"recovery");
            });
            CountDownLatch saved = new CountDownLatch(1);
            ((DocumentStore)get(activity,"store")).recover((value,error) -> saved.countDown());
            check(saved.await(15,TimeUnit.SECONDS),"Original recovery persisted");
            main(test,activity::finish);
        }
    }
    private static void horizontalDrag(Instrumentation test,PaintActivity activity,ToneDocument document) throws Exception {
        ToolLibrary saved=(ToolLibrary)get(activity,"library"), tools=new ToolLibrary();
        ToolLibrary.Preset first=tools.add("Landscape A"), middle=tools.add("Landscape B"), last=tools.add("Landscape C");
        tools.recall(middle.id);
        byte[] tones=document.snapshot();
        main(test,() -> { set(activity,"library",tools); call(get(activity,"toolbar"),"rebuildTools"); });
        test.waitForIdleSync();
        try {
            View source=preset(activity,first.id), target=preset(activity,last.id);
            // A narrower panel scrolls the rail; bring the adjacent presets into view as a person would.
            final View from=source, to=target;
            main(test,() -> {
                to.requestRectangleOnScreen(new android.graphics.Rect(0,0,to.getWidth(),to.getHeight()),true);
                from.requestRectangleOnScreen(new android.graphics.Rect(0,0,from.getWidth(),from.getHeight()),true);
            });
            test.waitForIdleSync();
            main(test,() -> check(PanelCoordinates.fullyVisible(from,new android.graphics.Rect(0,0,from.getWidth(),from.getHeight()))
                    &&PanelCoordinates.fullyVisible(to,new android.graphics.Rect(0,0,to.getWidth(),to.getHeight())),"Dragged presets are on screen"));
            float[] start=physicalPoint(source,source.getWidth()/2f,source.getHeight()/2f);
            float[] end=physicalPoint(target,target.getWidth()-2,target.getHeight()/2f);
            PaintChecks.drag(test,start[0],start[1],end[0],end[1],0);
            check(tools.presets().get(2).id.equals(first.id),"Horizontal drag reorders presets");
            main(test,() -> { for(int i=0;i<30;i++)tools.add("Landscape scroll "+i); tools.recall(middle.id); call(get(activity,"toolbar"),"rebuildTools"); });
            test.waitForIdleSync();
            source=preset(activity,middle.id); View scroll=(View)get(get(activity,"toolbar"),"landscapeTools");
            start=physicalPoint(source,source.getWidth()/2f,source.getHeight()/2f);
            end=physicalPoint(scroll,scroll.getWidth()-8,scroll.getHeight()/2f);
            PaintChecks.drag(test,start[0],start[1],end[0],end[1],800);
            check(scroll.getScrollX()>0,"Horizontal drag scrolls at right edge");
            check(Arrays.equals(tones,document.snapshot()),"Horizontal drag never paints");
        } finally {
            main(test,() -> { set(activity,"library",saved); call(get(activity,"toolbar"),"rebuildTools"); ((View)get(get(activity,"toolbar"),"landscapeTools")).scrollTo(0,0); });
        }
    }
    private static View preset(PaintActivity activity,String id) throws Exception {
        return (View)((java.util.Map<?,?>)get(get(activity,"toolbar"),"selectionButtons")).get(id);
    }
    private static void fastStrokeAndRefresh(Instrumentation test,PaintActivity activity,ToneDocument document,StringBuilder report) throws Exception {
        Object pad=get(activity,"pad"); View view=(View)pad;
        ToolLibrary saved=(ToolLibrary)get(activity,"library"); int maximum=TestAccess.maximum(activity);
        ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).head(ToolSettings.Head.FLAT).size(48).automaticHead();
        int y=(Integer)get(activity,"appRotation")==Surface.ROTATION_90 ? 1100 : 1400;
        ToneDocument reference=new ToneDocument(document.width,document.height,document.snapshot());
        PressureStroke expected=new PressureStroke(reference,settings,0);
        float[][] tilts={TestAccess.panelTilt(activity,25,-55),TestAccess.panelTilt(activity,-40,30),TestAccess.panelTilt(activity,50,-20)};
        expected.sample(900,y,.2f,tilts[0][0],tilts[0][1]); expected.sample(1020,y,.45f,tilts[1][0],tilts[1][1]); expected.sample(1140,y,.35f,tilts[2][0],tilts[2][1]); expected.finish();
        // The flat head's footprint follows the tablet's tilt frame; sample a pixel it actually painted.
        int[] found=null;
        for(int dy=-24;dy<=24&&found==null;dy++)for(int dx=-24;dx<=24;dx++)
            if(reference.tone(1140+dx,y+dy)==0){found=new int[]{1140+dx,y+dy};break;}
        check(found!=null,"Reference stroke paints black near its end");
        final int[] black=found;
        test.waitForIdleSync(); SystemClock.sleep(150);
        int draws=(Integer)get(pad,"drawCount"); long[] elapsed={0};
        try {
            main(test,() -> {
                ToolLibrary tools=new ToolLibrary(); tools.edit(settings); set(activity,"library",tools); TestAccess.setMaximum(activity,48);
                float[] points={900,y,1020,y,1140,y};
                ((Matrix)get(pad,"pageToView")).mapPoints(points); PanelCoordinates.fromView(view).mapPoints(points);
                long now=SystemClock.uptimeMillis(), started=System.nanoTime();
                MotionEvent down=stylus(now,now,MotionEvent.ACTION_DOWN,points[0],points[1],.2f,25,-55);
                activity.dispatchTouchEvent(down); down.recycle();
                MotionEvent move=stylus(now,now+1,MotionEvent.ACTION_MOVE,points[2],points[3],.45f,-40,30);
                MotionEvent.PointerCoords last=coords(points[4],points[5],.35f,50,-20);
                move.addBatch(now+2,new MotionEvent.PointerCoords[]{last},0);
                activity.dispatchTouchEvent(move); move.recycle();
                MotionEvent up=stylus(now,now+3,MotionEvent.ACTION_UP,points[4],points[5],0,50,-20);
                activity.dispatchTouchEvent(up); up.recycle();
                elapsed[0]=System.nanoTime()-started;
                check(Arrays.equals(reference.snapshot(),document.snapshot()),"Physical pen positions and original signed tilt degrees survive app rotation, including history");
                check(get(pad,"direct")!=null && (get(get(pad,"direct"),"buffer")==null)==DirectEink.bufferFromPanel().isIdentity(),"Direct page submits its original bitmap to a portrait driver buffer");
            });
            await(test,() -> ((DirtyRegions)get(pad,"pending")).isEmpty(),"Direct pen pixels submitted");
            test.waitForIdleSync(); SystemClock.sleep(200);
            main(test,() -> {
                check((Integer)get(pad,"drawCount")==draws,"Landscape pen and pen-up do not request Android canvas frames");
                DirectEink direct=(DirectEink)get(pad,"direct"); Bitmap bitmap=(Bitmap)get(pad,"display");
                check(direct.readGray(1140,y)==((bitmap.getPixel(1140,y)>>>16)&255)/16,"Landscape pen pixels reach native panel buffer");
                check(direct.readGray(black[0],black[1])==0,"Tilted black brush appears in the panel buffer");
            });
            selection(test,activity,pad);
            // A full compositor redraw must reproduce the same artwork, rather than stale pre-pen pixels.
            main(test,() -> { view.invalidate(); activity.getWindow().getDecorView().invalidate(); });
            test.waitForIdleSync(); SystemClock.sleep(200);
            Bitmap refreshed=test.getUiAutomation().takeScreenshot();
            float[] point={black[0],black[1]}; ((Matrix)get(pad,"pageToView")).mapPoints(point); PanelCoordinates.fromView(view).mapPoints(point);
            check((refreshed.getPixel(Math.round(point[0]),Math.round(point[1]))&0xffffff)==0,"Full screen redraw retains fast landscape ink");
            refreshed.recycle();
            report.append("App rotation ").append(get(activity,"appRotation")).append(": direct pen + batched signed tilt, no Android pen frames, control patches and full refresh pass; replay CPU ")
                    .append(String.format(java.util.Locale.US,"%.2f",elapsed[0]/1_000_000.0)).append(" ms (not panel latency).\n");
        } finally { main(test,() -> { set(activity,"library",saved); TestAccess.setMaximum(activity,maximum); }); }
    }
    private static MotionEvent.PointerCoords coords(float x,float y,float pressure,float tiltX,float tiltY) {
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords(); c.x=x;c.y=y;c.pressure=pressure;
        c.setAxisValue(MotionEvent.AXIS_ORIENTATION,tiltX);c.setAxisValue(MotionEvent.AXIS_TILT,tiltY); return c;
    }
    private static void dialogChecks(Instrumentation test,PaintActivity activity) throws Exception {
        android.widget.PopupWindow[] dialog={null};
        main(test,() -> {
            Method method=PaintActivity.class.getDeclaredMethod("showToolSettings"); method.setAccessible(true);
            dialog[0]=(android.widget.PopupWindow)method.invoke(activity);
        });
        test.waitForIdleSync(); SystemClock.sleep(200);
        try {
            main(test,() -> {
                View frame=dialog[0].getContentView();
                check(frame instanceof QuarterTurnLayout,"Tool panel rotates inside its portrait window");
                check(frame.getWidth()>0 && frame.getHeight()>0,"Rotated settings has a measured content area");
                android.graphics.Rect visible=new android.graphics.Rect();
                check(frame.getGlobalVisibleRect(visible) && visible.width()==frame.getWidth() && visible.height()==frame.getHeight(),"Rotated settings fits its window");
                check(activity.getWindowManager().getDefaultDisplay().getRotation()==Surface.ROTATION_0,"Opening settings never turns the desktop");
            });
            View done=dialog[0].getContentView().findViewWithTag("close");
            float[] point=physicalPoint(done,done.getWidth()/2f,done.getHeight()/2f);
            long now=SystemClock.uptimeMillis();
            for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
                MotionEvent e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,point[0],point[1],0);
                e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                check(test.getUiAutomation().injectInputEvent(e,true),"Rotated Done tap injected");e.recycle();
            }
            await(test,() -> !dialog[0].isShowing(),"Rotated dialog Done hit target");
        } finally {main(test,dialog[0]::dismiss);}
        await(test,() -> get(get(activity,"pad"),"direct")!=null,"Direct display reconnects after rotated settings");
    }
    private static void fileMenuChecks(Instrumentation test,PaintActivity activity) throws Exception {
        byte[] tones=((ToneDocument)get(get(activity,"pad"),"document")).snapshot();
        main(test,() -> {
            View button=(View)get(activity,"menuButton");
            tapLocal(activity,button,button.getWidth()/2f,button.getHeight()/2f);
        });
        test.waitForIdleSync();
        android.widget.PopupWindow popup=(android.widget.PopupWindow)get(activity,"filePopup");
        check(popup!=null && popup.isShowing(),"Hamburger opens an anchored popup");
        try {
            main(test,() -> {
                View root=(View)get(activity,"root"), button=(View)get(activity,"menuButton");
                View scroll=((android.view.ViewGroup)popup.getContentView()).getChildAt(0);
                android.graphics.RectF menu=boundsInRoot(root,scroll), anchor=boundsInRoot(root,button);
                boolean right=((SharedPreferences)get(activity,"preferences")).getBoolean("toolbox_right",false);
                check(Math.abs(menu.top-anchor.bottom)<2,"File menu opens immediately below the hamburger");
                check(Math.abs(right ? menu.right-anchor.right : menu.left-anchor.left)<2,"File menu aligns with the hamburger's outer edge");
                check(menu.left>=-1 && menu.top>=-1 && menu.right<=root.getWidth()+1 && menu.bottom<=root.getHeight()+1,"File menu fits on screen");
            });
            screenshot(test,activity,"orientation-menu-"+get(activity,"appRotation")+"-"+get(activity,"toolboxRight")+".png");
            android.view.ViewGroup rows=(android.view.ViewGroup)((android.view.ViewGroup)((android.view.ViewGroup)popup.getContentView()).getChildAt(0)).getChildAt(0);
            View settings=null;
            for(int i=0;i<rows.getChildCount();i++)
                if(rows.getChildAt(i) instanceof android.widget.TextView&&"Settings".contentEquals(((android.widget.TextView)rows.getChildAt(i)).getText()))settings=rows.getChildAt(i);
            check(settings!=null,"File menu has a Settings item");
            // Input reaches a popup only once its window has focus, which can lag behind main-thread idle.
            await(test,() -> popup.getContentView().hasWindowFocus(),"File menu receives input");
            injectTap(test,physicalPoint(settings,settings.getWidth()/2f,settings.getHeight()/2f));
            await(test,() -> !popup.isShowing(),"Rotated menu item responds to its displayed touch target");
            test.waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo window=test.getUiAutomation().getRootInActiveWindow();
            check(window!=null && !window.findAccessibilityNodeInfosByText("Drawing hand").isEmpty(),"Settings menu item opens app settings");
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            test.waitForIdleSync();
            main(test,() -> ((View)get(activity,"menuButton")).performClick());
            test.waitForIdleSync();
            android.widget.PopupWindow reopened=(android.widget.PopupWindow)get(activity,"filePopup");
            View pad=(View)get(activity,"pad");
            await(test,() -> reopened.getContentView().hasWindowFocus(),"Reopened file menu receives input");
            injectTap(test,physicalPoint(pad,pad.getWidth()/2f,pad.getHeight()/2f),true);
            await(test,() -> !reopened.isShowing(),"Tap outside dismisses the anchored menu");
            check(Arrays.equals(tones,((ToneDocument)get(get(activity,"pad"),"document")).snapshot()),"Opening and dismissing the menu preserves drawing pixels");
        } finally {
            main(test,() -> { android.widget.PopupWindow open=(android.widget.PopupWindow)get(activity,"filePopup"); if(open!=null)open.dismiss(); });
        }
        await(test,() -> get(get(activity,"pad"),"direct")!=null,"Direct display reconnects after file menu");
    }
    private static android.graphics.RectF boundsInRoot(View root,View view) {
        android.graphics.RectF bounds=new android.graphics.RectF(0,0,view.getWidth(),view.getHeight());
        PanelCoordinates.fromView(view).mapRect(bounds);
        Matrix inverse=new Matrix();PanelCoordinates.fromView(root).invert(inverse);inverse.mapRect(bounds);
        return bounds;
    }
    private static void injectTap(Instrumentation test,float[] point) {injectTap(test,point,false);}
    private static void injectTap(Instrumentation test,float[] point,boolean dismissing) {
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean delivered=test.getUiAutomation().injectInputEvent(event,true);event.recycle();
            // A touch outside a modal popup dismisses it on down, which can leave the lift without a target.
            check(delivered || (dismissing && action==MotionEvent.ACTION_UP),"Menu touch injected");
        }
    }
    private static MotionEvent stylus(long down,long time,int action,float x,float y,float pressure,float tiltX,float tiltY) {
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        return MotionEvent.obtain(down,time,action,1,new MotionEvent.PointerProperties[]{p},
                new MotionEvent.PointerCoords[]{coords(x,y,pressure,tiltX,tiltY)},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
    }
    private static void selection(Instrumentation test,PaintActivity activity,Object pad) throws Exception {
        SelectionFeedback feedback=(SelectionFeedback)get(activity,"selectionFeedback"); boolean enabled=feedback.enabled;
        int submitted=feedback.submitted;
        main(test,() -> {
            feedback.enabled=true;
            View shade=(View)get(activity,"shadePicker");
            tapLocal(activity,shade,1,shade.getHeight()/2f);
        });
        try {
            check(feedback.submitted>submitted,"Rotated color selection reaches direct display");
            DirectEink direct=(DirectEink)get(pad,"direct"); Bitmap bitmap=(Bitmap)get(pad,"display");
            android.graphics.Rect page=direct.bufferRegion(new android.graphics.Rect(0,0,bitmap.getWidth(),bitmap.getHeight()));
            check(!android.graphics.Rect.intersects(page,feedback.lastBufferRegion),"Color update stays outside the physical artwork");
        } finally {
            main(test,() -> {
                View shade=(View)get(activity,"shadePicker");tapLocal(activity,shade,shade.getWidth()-1,shade.getHeight()/2f);
                feedback.enabled=enabled;
            });
        }
    }
    private static void layout(PaintActivity activity,boolean right) throws Exception {
        View palette=(View)get(activity,"paletteFrame"), pad=(View)get(activity,"pad"), tools=(View)get(get(activity,"toolbar"),"landscapeTools");
        LinearLayout root=(LinearLayout)get(activity,"root");
        check(root.indexOfChild(palette)==(right ? 1 : 0),"Header is on the non-drawing-hand side");
        check(tools.getBottom()<=pad.getTop(),"Tools stay at the top for both hands and both landscape directions");
        menuCorner(activity,right);
        shadeMarker(activity);
        int menuY=layoutY(root,(View)get(activity,"menuButton"));
        int undoY=layoutY(root,tagged((View)get(activity,"palette"),"Undo"));
        int shadeY=layoutY(root,(View)get(activity,"shadePicker"));
        // Compact layouts show a Pages button in place of the page row.
        int pageY=layoutY(root,(View)get(activity,activity.compactLayout()?"pagesButton":"previousPage"));
        check(menuY<undoY && undoY<shadeY && shadeY<pageY,"Side column reads menu, undo, colors, then pages from top to bottom");
        check(((LinearLayout)get(get(activity,"toolbar"),"toolRail")).getOrientation()==LinearLayout.HORIZONTAL,"Tool rail runs horizontally");
        Bitmap bitmap=(Bitmap)get(pad,"display");
        float[] bounds={0,0,bitmap.getWidth(),0,0,bitmap.getHeight(),bitmap.getWidth(),bitmap.getHeight()};
        ((Matrix)get(pad,"pageToView")).mapPoints(bounds);
        for(int i=0;i<bounds.length;i+=2)
            check(bounds[i]>=-.1f && bounds[i]<=pad.getWidth()+.1f && bounds[i+1]>=-.1f && bounds[i+1]<=pad.getHeight()+.1f,"Every page corner remains visible");
    }
    private static void portraitHands(Instrumentation test,PaintActivity activity,SharedPreferences preferences) throws Exception {
        for (boolean right : new boolean[]{true,false}) {
            main(test,() -> { preferences.edit().putBoolean("toolbox_right",right).apply(); call(activity,"applyToolboxSide"); });
            test.waitForIdleSync();
            main(test,() -> menuCorner(activity,right));
            fileMenuChecks(test,activity);
        }
    }
    private static void menuCorner(PaintActivity activity,boolean right) throws Exception {
        View root=(View)get(activity,"root"), menu=(View)get(activity,"menuButton"), rotate=(View)get(activity,"rotateButton");
        float[] center=layoutPoint(root,menu,menu.getWidth()/2f,menu.getHeight()/2f);
        float half=menu.getWidth()/2f;
        check(Math.abs(center[0]-(right ? root.getWidth()-half : half))<1
                && Math.abs(center[1]-half)<1,"Hamburger occupies the upper outer corner for each drawing hand");
        check(rotate.getParent()!=get(activity,"menuControls"),"Rotation suggestion occupies no header space");
    }
    private static void shadeMarker(PaintActivity activity) throws Exception {
        View shade=(View)get(activity,"shadePicker"), root=(View)get(activity,"root"), pad=(View)get(activity,"pad");
        int gray=(Integer)get(get(activity,"paint"),"gray");
        Bitmap rendered=Bitmap.createBitmap(shade.getWidth(),shade.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            tapLocal(activity,shade,1,shade.getHeight()/2f);
            check((Integer)get(get(activity,"paint"),"gray")==255,"Landscape picker keeps white at the top for both hands");
            tapLocal(activity,shade,shade.getWidth()-1,shade.getHeight()/2f);
            check((Integer)get(get(activity,"paint"),"gray")==0,"Landscape picker keeps black at the bottom for both hands");
            shade.draw(new android.graphics.Canvas(rendered));
            int inset=Math.round(3*activity.getResources().getDisplayMetrics().density);
            float[] middle=layoutPoint(root,shade,shade.getWidth()/2f,shade.getHeight()/2f);
            float[] canvasMiddle=layoutPoint(root,pad,pad.getWidth()/2f,pad.getHeight()/2f);
            int marks=0;
            for (int y : new int[]{inset,shade.getHeight()-inset}) {
                for (int x=0;x<shade.getWidth();x++) {
                    if ((rendered.getPixel(x,y)&0xffffff)!=0) continue;
                    float[] mark=layoutPoint(root,shade,x,y);
                    check(Math.abs(mark[0]-canvasMiddle[0])<Math.abs(middle[0]-canvasMiddle[0]),"Black selection marker faces the canvas");
                    check(mark[1]>middle[1],"Black selection marker stays at the bottom");
                    marks++;
                }
            }
            check(marks>0,"Black selection marker is visible beside the gradient");
        } finally { rendered.recycle(); set(get(activity,"paint"),"gray",gray); shade.invalidate(); }
    }
    private static int layoutY(View root,View view) {
        return Math.round(layoutPoint(root,view,0,0)[1]);
    }
    private static float[] layoutPoint(View root,View view,float x,float y) {
        float[] point={x,y};
        while(view!=root) {
            view.getMatrix().mapPoints(point);
            View parent=(View)view.getParent();
            point[0]+=view.getLeft()-parent.getScrollX(); point[1]+=view.getTop()-parent.getScrollY(); view=parent;
        }
        return point;
    }
    private static float[] physicalPoint(View view,float x,float y) {
        float[] point={x,y}; PanelCoordinates.fromView(view).mapPoints(point); return point;
    }
    private static View tagged(View view,String tag) {
        if(tag.equals(view.getTag()))return view;
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                View found=tagged(group.getChildAt(i),tag); if(found!=null)return found;
            }
        }
        return null;
    }
    private static void stroke(PaintActivity activity,Object pad,float x1,float y1,float x2,float y2) throws Exception {
        float[] points={x1,y1,x2,y2}; ((Matrix)get(pad,"pageToView")).mapPoints(points);
        View view=(View)pad;
        long time=SystemClock.uptimeMillis();
        event(view,time,MotionEvent.ACTION_DOWN,points[0],points[1],MotionEvent.TOOL_TYPE_STYLUS);
        event(view,time,MotionEvent.ACTION_MOVE,points[2],points[3],MotionEvent.TOOL_TYPE_STYLUS);
        event(view,time,MotionEvent.ACTION_UP,points[2],points[3],MotionEvent.TOOL_TYPE_STYLUS);
    }
    private static void event(View view,long time,int action,float x,float y,int tool) {
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties(); p.id=0; p.toolType=tool;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords(); c.x=x; c.y=y; c.pressure=.45f;
        MotionEvent e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},
                new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
        view.dispatchTouchEvent(e); e.recycle();
    }
    private static void tapLocal(PaintActivity activity,View view,float x,float y) {
        float[] point={x,y}; View current=view;
        while (current.getParent() instanceof View) {
            current.getMatrix().mapPoints(point);
            View parent=(View)current.getParent();
            point[0]+=current.getLeft()-parent.getScrollX(); point[1]+=current.getTop()-parent.getScrollY();
            current=parent;
        }
        long now=SystemClock.uptimeMillis();
        event(current,now,MotionEvent.ACTION_DOWN,point[0],point[1],MotionEvent.TOOL_TYPE_FINGER);
        event(current,now,MotionEvent.ACTION_UP,point[0],point[1],MotionEvent.TOOL_TYPE_FINGER);
    }
    private static void screenshot(Instrumentation test,PaintActivity activity,String name) throws Exception {
        test.waitForIdleSync();
        // UI-thread idleness can precede the compositor's new frame on this tablet.
        SystemClock.sleep(200);
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        check(bitmap!=null,"Screenshot available");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        } finally {bitmap.recycle();}
    }
    private static void awaitRotation(Instrumentation test,PaintActivity activity,int rotation,boolean landscape) throws Exception {
        await(test,() -> (Integer)get(activity,"appRotation")==rotation
                && (Boolean)get(activity,"landscape")==landscape && ((View)get(activity,"pad")).getWidth()>0,"Requested orientation");
        test.waitForIdleSync(); SystemClock.sleep(300);
        main(test,() -> {
            check(activity.getWindowManager().getDefaultDisplay().getRotation()==Surface.ROTATION_0,"Android display remains in portrait");
            check(activity.getRequestedOrientation()==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,"Activity remains portrait-locked");
            check(get(get(activity,"pad"),"direct")!=null,"Direct e-ink remains active in app rotation "+rotation);
            float[] direction={1,0};
            ((Matrix)get(get(activity,"pad"),"pageToView")).mapVectors(direction);
            float[] expectedX={1,0,-1,0}, expectedY={0,-1,0,1};
            float length=(float)Math.hypot(direction[0],direction[1]);
            check(length>0 && Math.abs(direction[0]/length-expectedX[rotation])<.001
                    && Math.abs(direction[1]/length-expectedY[rotation])<.001,
                    "Artwork remains fixed to tablet at display rotation "+rotation);
        });
    }
    private interface Work { void run() throws Exception; }
    private interface Condition { boolean ok() throws Exception; }
    private static void main(Instrumentation test,Work work) throws Exception {
        Exception[] error={null};
        test.runOnMainSync(() -> { try {work.run();} catch(Exception e) {error[0]=e;} });
        if(error[0]!=null)throw error[0];
    }
    private static void await(Instrumentation test,Condition condition,String message) throws Exception {
        for(int i=0;i<100;i++) {
            boolean[] ready={false}; main(test,() -> ready[0]=condition.ok());
            if(ready[0])return; SystemClock.sleep(100);
        }
        throw new IllegalStateException(message+" timed out");
    }
    private static Object get(Object owner,String name) throws Exception { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void set(Object owner,String name,Object value) throws Exception { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value); }
    private static void call(Object owner,String name) throws Exception { call(owner,name,new Class<?>[0]); }
    private static void call(Object owner,String name,Class<?>[] types,Object... args) throws Exception { Method method=owner.getClass().getDeclaredMethod(name,types); method.setAccessible(true); method.invoke(owner,args); }
    private static void check(boolean value,String message) { if(!value)throw new IllegalStateException(message); }
}
