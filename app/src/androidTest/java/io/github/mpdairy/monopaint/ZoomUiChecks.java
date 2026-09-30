package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Real view-hierarchy gestures and e-ink checks on a disposable book. */
final class ZoomUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int i=0;i<100&&(Boolean)get(activity,"loading");i++) SystemClock.sleep(100);
        test.waitForIdleSync();
        Object pad=get(activity,"pad"); View view=(View)pad;
        DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName"); ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(activity,"gray"), maximum=(Integer)get(activity,"maximum"), rotation=(Integer)get(activity,"appRotation");
        boolean wet=(Boolean)get(activity,"wetCanvas"), transparent=(Boolean)get(activity,"transparentPaint"), erase=(Boolean)get(activity,"eraseMode");
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences"); boolean right=prefs.getBoolean("toolbox_right",false);
        boolean originalLock=(Boolean)get(activity,"navigationLocked"), hadLock=prefs.contains("navigation_locked");
        boolean zoomVisible=prefs.getBoolean("tool_visible_ZOOM",true), hadZoomVisible=prefs.contains("tool_visible_ZOOM");
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics(); activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        float density=activity.getResources().getDisplayMetrics().density;
        ToneDocument doc=new ToneDocument(metrics.widthPixels-Math.round(64*density),metrics.heightPixels-Math.round(48*density));
        try {
            main(test,() -> {
                rasterChecks();
                check(originalLock==prefs.getBoolean("navigation_locked",true),"Navigation defaults to locked and restores saved choice");
                prefs.edit().putBoolean("tool_visible_ZOOM",true).apply();
                ((android.view.OrientationEventListener)get(activity,"orientationSensor")).disable();
                call(pad,"finishStroke"); call(pad,"dryWet");
                set(activity,"drawingName",""); set(activity,"gray",0); set(activity,"maximum",16);
                set(activity,"wetCanvas",false);set(activity,"transparentPaint",false);set(activity,"eraseMode",false);
                set(activity,"library",new ToolLibrary());
                doc.begin();
                for(int y=doc.height/2-180;y<doc.height/2+180;y++) for(int x=doc.width/2-180;x<doc.width/2+180;x++) doc.setTone(x,y,170);
                doc.finish();
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc)); call(activity,"rebuildTools");
            });
            for(int turn=0;turn<4;turn++) for(boolean hand:new boolean[]{false,true}) {
                final int quarter=turn;
                main(test,() -> {
                    call(activity,"requestQuarter",new Class<?>[]{int.class},quarter);
                    prefs.edit().putBoolean("toolbox_right",hand).apply();call(activity,"applyToolboxSide");
                });
                test.waitForIdleSync(); SystemClock.sleep(300);
                main(test,() -> {
                    call(pad,"fitPage");set(pad,"penGuardUntil",0L);
                    View zoom=(View)get(activity,"zoomButton");
                    zoom.requestRectangleOnScreen(new Rect(0,0,zoom.getWidth(),zoom.getHeight()),true);
                });
                test.waitForIdleSync();SystemClock.sleep(100);
                main(test,() -> {
                    View zoom=(View)get(activity,"zoomButton");
                    if(!(Boolean)get(activity,"navigationLocked"))zoom.performClick();
                    Matrix locked=new Matrix((Matrix)get(pad,"pageToView"));
                    pinch(activity,pad,view.getWidth()/2f,view.getHeight()/2f);
                    check(locked.equals(get(pad,"pageToView")),"Locked pinch and pan cannot move canvas");
                    SelectionFeedback feedback=(SelectionFeedback)get(activity,"selectionFeedback");
                    int submitted=feedback.submitted;
                    zoom.performClick();
                    check(feedback.submitted>submitted,"Lock icon updates through direct e-ink feedback");
                    check(!prefs.getBoolean("navigation_locked",true),"Unlock is saved");
                    check(get(activity,"toolPicker")==null,"Zoom tap opens no settings");
                    float x=view.getWidth()/2f,y=view.getHeight()/2f;
                    if(quarter==0&&!hand) {
                        long began=System.nanoTime();
                        touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
                        touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
                        long ready=System.nanoTime();
                        for(int i=0;i<24;i++) {
                            float span=110+i*4;
                            touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-span+i,y,x+span+i,y});
                        }
                        long moved=System.nanoTime();
                        touch(activity,pad,MotionEvent.ACTION_UP,new int[]{7},new int[]{1},new float[]{x-200,y});
                        report.append("Gesture CPU: setup ").append((ready-began)/1000000).append(" ms, 24 queued moves ")
                                .append((moved-ready)/1000000).append(" ms, release ").append((System.nanoTime()-moved)/1000000).append(" ms.\n");
                        call(pad,"fitPage");
                    }
                    byte[] before=doc.snapshot();
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
                    touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7},new int[]{1},new float[]{x-50,y+100});
                    touch(activity,pad,MotionEvent.ACTION_UP,new int[]{7},new int[]{1},new float[]{x-50,y+100});
                    check(((CanvasViewport)get(pad,"viewport")).zoom==1,"Single finger cannot move the canvas");
                    pinch(activity,pad,x,y);
                    CanvasViewport viewport=(CanvasViewport)get(pad,"viewport");
                    check(Math.abs(viewport.zoom-2)<.01,"Two fingers zoom to 200% in rotation "+quarter);
                    check(((View)get(activity,"zoomButton")).getContentDescription().toString().contains(viewport.percent()+"%"),"Toolbar percentage updates during pinch");
                    check(Arrays.equals(before,doc.snapshot()),"Pinch and pan leave all artwork unchanged");
                    check(get(pad,"viewportBitmap")!=null,"Zoom uses a clipped screen raster");
                    Matrix inverse=(Matrix)get(pad,"viewToPage");float[] page={x,y};inverse.mapPoints(page);
                    set(pad,"penGuardUntil",0L);
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{0},new int[]{2},new float[]{x,y});
                    Matrix during=new Matrix((Matrix)get(pad,"pageToView"));
                    touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{0,7},new int[]{2,1},new float[]{x,y,x+100,y+100});
                    touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(2<<8),new int[]{0,7,12},new int[]{2,1,1},new float[]{x,y,x+100,y+100,x+300,y+100});
                    touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{0,7,12},new int[]{2,1,1},new float[]{x+40,y,x+50,y+200,x+350,y+200});
                    check(during.equals(get(pad,"pageToView")),"Palm touches never transform an active pen stroke");
                    touch(activity,pad,MotionEvent.ACTION_CANCEL,new int[]{0},new int[]{2},new float[]{x+40,y});
                    check(doc.tone((int)page[0],(int)page[1])<170,"Zoomed pen lands on the displayed document pixel");
                    check(doc.undo()&&Arrays.equals(before,doc.snapshot()),"One undo removes the complete zoomed stroke");
                    call(pad,"renderDirty");call(pad,"present");
                    set(pad,"penGuardUntil",0L);
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
                    touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
                    touch(activity,pad,MotionEvent.ACTION_CANCEL,new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
                    check(!(Boolean)get(pad,"navigating"),"Cancellation releases navigation");
                    ViewportBitmap raster=(ViewportBitmap)get(pad,"viewportBitmap");
                    for(int row=(int)y-16;row<(int)y+16;row++) for(int col=(int)x-16;col<(int)x+16;col++) {
                        float[] p={col+.5f,row+.5f};inverse.mapPoints(p);
                        check(raster.bitmap.getPixel(col,row)==DotPattern.pixel(doc.compositeTone((int)p[0],(int)p[1]),col,row),"Zoom preserves shade density at screen resolution");
                    }
                });
                test.waitForIdleSync();SystemClock.sleep(200);
                main(test,() -> {
                    DirectEink direct=(DirectEink)get(pad,"direct");check(direct!=null,"Fast e-ink reconnects after gesture");
                    Rect patch=direct.panelRegion(new Rect(0,0,view.getWidth(),view.getHeight()));
                    check(patch.left>=0&&patch.top>=0&&patch.right<=metrics.widthPixels&&patch.bottom<=metrics.heightPixels,"Zoomed e-ink patch stays on screen");
                    ViewportBitmap raster=(ViewportBitmap)get(pad,"viewportBitmap");int x=view.getWidth()/2,y=view.getHeight()/2;
                    if(quarter==0 && !hand) {
                        long start=System.nanoTime();
                        for(int i=0;i<5;i++) raster.update((Bitmap)get(pad,"display"),(Matrix)get(pad,"pageToView"),null);
                        report.append("Full-screen zoom raster average: "+((System.nanoTime()-start)/5_000_000)+" ms.\n");
                    }
                    int frames=(Integer)get(pad,"drawCount");
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{0},new int[]{2},new float[]{x,y});
                    touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{0},new int[]{2},new float[]{x+30,y});
                    touch(activity,pad,MotionEvent.ACTION_UP,new int[]{0},new int[]{2},new float[]{x+30,y});
                    check((Integer)get(pad,"drawCount")==frames,"Zoomed strokes stay on the direct display path");
                    call(pad,"present");
                    // The shared driver plane is not a screenshot: inspect the ink this
                    // stroke submitted, not untouched background after Android redraws.
                    for(int dx=10;dx<18;dx++) {
                        check(raster.bitmap.getPixel(x+dx,y)==android.graphics.Color.BLACK,"Stroke covers the native sample");
                        check(direct.readGray(x+dx,y)==0,"Zoomed pen ink reaches native panel in rotation "+quarter);
                    }
                    check(doc.undo(),"Native zoomed stroke can undo");call(pad,"renderDirty");call(pad,"present");
                    View zoom=(View)get(activity,"zoomButton");
                    Matrix locked=new Matrix((Matrix)get(pad,"pageToView"));
                    zoom.performClick();set(pad,"penGuardUntil",0L);
                    pinch(activity,pad,x,y);
                    check(locked.equals(get(pad,"pageToView")),"Lock preserves zoomed position and blocks both pinch and pan");
                    check(prefs.getBoolean("navigation_locked",false),"Lock is saved");
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{0},new int[]{2},new float[]{x,y});
                    touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{0},new int[]{2},new float[]{x+30,y});
                    touch(activity,pad,MotionEvent.ACTION_UP,new int[]{0},new int[]{2},new float[]{x+30,y});
                    check(doc.undo(),"Pen still draws while navigation is locked");call(pad,"renderDirty");call(pad,"present");
                    zoom.performLongClick();
                    check(locked.equals(get(pad,"pageToView")),"Holding Zoom does not reset view");
                    zoom.performClick();set(pad,"penGuardUntil",0L);
                    // Pinching inward replaces the old fit-page action.
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-200,y});
                    touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-200,y,x+200,y});
                    touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-30,y,x+30,y});
                    touch(activity,pad,MotionEvent.ACTION_UP,new int[]{7},new int[]{1},new float[]{x-30,y});
                    check(((CanvasViewport)get(pad,"viewport")).zoom==1,"Pinch inward fits the whole page");
                });
                previewChecks(test,activity,pad,quarter,report);
            }
            main(test,() -> {
                call(pad,"zoomBy",new Class<?>[]{float.class},100f);
                check(((CanvasViewport)get(pad,"viewport")).percent()==800,"Zoom caps at 800%");
                call(activity,"addPage");
                check(((CanvasViewport)get(pad,"viewport")).zoom==1,"New page starts fitted");
            });
            report.append("PASS: default/saved navigation lock, fast corner feedback, locked pinch/pan rejection and pen drawing, no zoom settings or hold reset, pinch-to-fit, zoom percentage, pinch focus/pan, one-finger and active-pen palm rejection, cancellation, pen mapping/undo, calibrated shades, bounded native e-ink, direct strokes and fit reset in all four rotations and both hands; 800% limit and page reset.\n");
        } finally {
            main(test,() -> {
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"drawingName",name);set(activity,"library",library);set(activity,"gray",gray);set(activity,"maximum",maximum);
                set(activity,"wetCanvas",wet);set(activity,"transparentPaint",transparent);set(activity,"eraseMode",erase);
                SharedPreferences.Editor restore=prefs.edit().putBoolean("toolbox_right",right);
                if(hadLock)restore.putBoolean("navigation_locked",originalLock);else restore.remove("navigation_locked");
                if(hadZoomVisible)restore.putBoolean("tool_visible_ZOOM",zoomVisible);else restore.remove("tool_visible_ZOOM");
                restore.apply();set(activity,"navigationLocked",originalLock);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},rotation);call(activity,"applyToolboxSide");
                call(activity,"refreshPaintModes");call(activity,"preferences");call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }
    private static void pinch(PaintActivity activity,Object pad,float x,float y) throws Exception {
        touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
        touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
        // Swap pointer indices while preserving IDs, then translate the pair.
        touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{12,7},new int[]{1,1},new float[]{x+200,y,x-200,y});
        touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-170,y+30,x+230,y+30});
        touch(activity,pad,MotionEvent.ACTION_POINTER_UP|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-170,y+30,x+230,y+30});
        touch(activity,pad,MotionEvent.ACTION_UP,new int[]{7},new int[]{1},new float[]{x-170,y+30});
    }
    private static void previewChecks(Instrumentation test,PaintActivity activity,Object pad,int quarter,StringBuilder report)throws Exception {
        View view=(View)pad;float x=view.getWidth()/2f,y=view.getHeight()/2f;
        int[] frames={0},draws={0};Object[] presenter={null};
        main(test,() -> {
            set(pad,"penGuardUntil",0L);
            touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
            touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
            presenter[0]=get(pad,"direct");check(presenter[0]!=null,"Gesture owns a direct presenter");
        });
        test.waitForIdleSync();
        main(test,() -> {
            frames[0]=(Integer)get(pad,"navigationFrameCount");draws[0]=(Integer)get(pad,"drawCount");
            for(int i=0;i<100;i++) {
                float span=110+i;
                touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-span+20,y+15,x+span+20,y+15});
            }
            check((Integer)get(pad,"navigationFrameCount")==frames[0],"Queued moves do not render obsolete frames");
            check((Boolean)get(pad,"navigationFrameScheduled"),"Latest preview is scheduled");
        });
        test.waitForIdleSync();SystemClock.sleep(80);
        main(test,() -> {
            check((Integer)get(pad,"navigationFrameCount")==frames[0]+1,"One frame consumes the complete queued burst");
            check((Integer)get(pad,"drawCount")==draws[0],"Live navigation avoids Android canvas redraws");
            check(get(pad,"direct")==presenter[0],"Gesture reuses its direct presenter");
            check(Math.abs(((CanvasViewport)get(pad,"viewport")).zoom-2.09f)<.01,"Latest finger position wins");
            ViewportBitmap raster=(ViewportBitmap)get(pad,"viewportBitmap");
            ViewportBitmap expected=new ViewportBitmap(view.getWidth(),view.getHeight());
            try {
                expected.update((Bitmap)get(pad,"display"),(Matrix)get(pad,"pageToView"),null);
                check(expected.bitmap.sameAs(raster.bitmap),"Preview preserves every calibrated pixel");
            } finally {expected.close();}
            // An external redraw must retain the newest preview, not the old fitted page.
            Bitmap androidFrame=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
            androidFrame.setHasAlpha(false);
            try {view.draw(new android.graphics.Canvas(androidFrame));check(androidFrame.sameAs(raster.bitmap),"Android redraw retains navigation preview");}
            finally {androidFrame.recycle();}
            report.append("Navigation preview rotation ").append(quarter).append(": raster ")
                    .append((Long)get(pad,"lastNavigationRasterNanos")/1000000).append(" ms, submit ")
                    .append((Long)get(pad,"lastNavigationPresentNanos")/1000000).append(" ms.\n");
            touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-160,y,x+240,y});
            touch(activity,pad,MotionEvent.ACTION_CANCEL,new int[]{7,12},new int[]{1,1},new float[]{x-160,y,x+240,y});
            check(!(Boolean)get(pad,"navigationFrameScheduled")&&!(Boolean)get(pad,"navigating"),"Cancellation drains the final preview");
            check(Math.abs(((CanvasViewport)get(pad,"viewport")).zoom-2)<.01,"Cancellation keeps latest view");
            // A pen landing before the scheduled preview must use the newest transform.
            ToneDocument document=(ToneDocument)get(pad,"document");byte[] before=document.snapshot();
            touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
            touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
            touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-120,y,x+120,y});
            touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(2<<8),new int[]{7,12,0},new int[]{1,1,2},new float[]{x-120,y,x+120,y,x,y});
            check(!(Boolean)get(pad,"navigationFrameScheduled")&&!(Boolean)get(pad,"navigating"),"Pen interruption drains the preview");
            float[] point={x,y};((Matrix)get(pad,"viewToPage")).mapPoints(point);
            check(document.tone((int)point[0],(int)point[1])<170,"Interrupting pen lands on latest view");
            touch(activity,pad,MotionEvent.ACTION_CANCEL,new int[]{0},new int[]{2},new float[]{x,y});
            check(document.undo()&&Arrays.equals(before,document.snapshot()),"Pen interruption is one undoable stroke");
            call(pad,"renderDirty");call(pad,"present");set(pad,"penGuardUntil",0L);
            touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{7},new int[]{1},new float[]{x-100,y});
            touch(activity,pad,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{7,12},new int[]{1,1},new float[]{x-100,y,x+100,y});
            touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{7,12},new int[]{1,1},new float[]{x-120,y,x+120,y});
            ((View)get(activity,"zoomButton")).performClick();
            check((Boolean)get(activity,"navigationLocked")&&!(Boolean)get(pad,"navigationFrameScheduled")&&!(Boolean)get(pad,"navigating"),"Lock interruption drains the preview");
            ((View)get(activity,"zoomButton")).performClick();
            call(pad,"fitPage");
        });
        test.waitForIdleSync();
    }
    private static void compositionChecks() {
        int width=31,height=19,count=width*height;
        java.util.ArrayList<ToneDocument.Layer> layers=new java.util.ArrayList<>();
        for(int i=0;i<8;i++) {
            byte[] tones=new byte[count],alpha=new byte[count];
            for(int j=0;j<count;j++){tones[j]=(byte)(j*31+i*7);alpha[j]=(byte)(j*13+i*19);}
            layers.add(new ToneDocument.Layer("Layer "+i,i%3!=0,tones,alpha));
        }
        ToneDocument doc=new ToneDocument(width,height,layers,0);
        Bitmap target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try {
            for(int pass=0;pass<2;pass++) {
                ViewportBitmap.compose(doc,target);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++)
                    check(target.getPixel(x,y)==(0xff000000|doc.compositeTone(x,y)*0x010101),"Native navigation composite matches all layer alpha and visibility");
                for(int layer=0;layer<8;layer++)doc.setLayerVisible(layer,false);
            }
        } finally {target.recycle();}
    }
    private static void rotationChecks() throws Exception {
        Bitmap source=Bitmap.createBitmap(31,19,Bitmap.Config.ARGB_8888);source.setHasAlpha(false);
        try {
            for(int y=0;y<19;y++)for(int x=0;x<31;x++)source.setPixel(x,y,0xff000000|((x*71+y*31)*977&0xffffff));
            Method rotate=DirectEink.class.getDeclaredMethod("nativeRotate",Bitmap.class,Bitmap.class,int.class,int.class,int.class,int.class,int.class);
            rotate.setAccessible(true);
            for(int turn=0;turn<4;turn++) {
                int width=turn%2==0?31:19,height=turn%2==0?19:31;
                Bitmap target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);target.eraseColor(0xffff00ff);
                try {
                    rotate.invoke(null,source,target,turn,2,3,width-1,height-2);
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                        int sx=turn==1?y:turn==2?30-x:turn==3?30-y:x;
                        int sy=turn==1?18-x:turn==2?18-y:turn==3?x:y;
                        int expected=x>=2&&x<width-1&&y>=3&&y<height-2?source.getPixel(sx,sy):0xffff00ff;
                        check(target.getPixel(x,y)==expected,"Native panel rotation preserves pixels and clip borders");
                    }
                } finally {target.recycle();}
            }
        } finally {source.recycle();}
    }
    private static void rasterChecks() throws Exception {
        rotationChecks();compositionChecks();
        Bitmap source=Bitmap.createBitmap(31,19,Bitmap.Config.ARGB_8888);
        ViewportBitmap target=new ViewportBitmap(128,128);
        try {
            for(int y=0;y<19;y++) for(int x=0;x<31;x++) {
                int tone=(y*31+x)%256;source.setPixel(x,y,0xff000000|tone*0x010101);
            }
            for(int turn=0;turn<4;turn++)for(float scale:new float[]{.7f,1,1.25f,2.13f,8}) {
                Matrix matrix=new Matrix();matrix.setRotate(turn*90);matrix.postScale(scale,scale);
                matrix.postTranslate(turn==1||turn==2?95.35f:-2.35f,turn>=2?105.75f:3.75f);
                Matrix inverse=new Matrix();matrix.invert(inverse);target.update(source,matrix,null);
                for(int y=0;y<128;y++) for(int x=0;x<128;x++) {
                    float[] p={x+.5f,y+.5f};inverse.mapPoints(p);
                    int tone=p[0]>=0&&p[0]<31&&p[1]>=0&&p[1]<19?(source.getPixel((int)p[0],(int)p[1])&255):255;
                    check(target.bitmap.getPixel(x,y)==DotPattern.pixel(tone,x,y),"Fractional zoom raster turn="+turn+" scale="+scale+" at "+x+","+y+" maps to "+p[0]+","+p[1]+" actual="+Integer.toHexString(target.bitmap.getPixel(x,y))+" expected="+Integer.toHexString(DotPattern.pixel(tone,x,y)));
                }
            }
        } finally {source.recycle();target.close();}
    }
    private static void touch(PaintActivity activity,Object pad,int action,int[] ids,int[] types,float[] positions) {
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[ids.length];
        PanelCoordinates.fromView((View)pad).mapPoints(positions);
        for(int i=0;i<ids.length;i++) {
            props[i]=new MotionEvent.PointerProperties();props[i].id=ids[i];props[i].toolType=types[i];
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=positions[i*2];coords[i].y=positions[i*2+1];coords[i].pressure=.6f;coords[i].size=.1f;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,action,ids.length,props,coords,0,0,1,1,0,0,types[0]==2?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
        try {activity.dispatchTouchEvent(event);} finally {event.recycle();}
    }
    private interface Work {void run() throws Exception;}
    private static void main(Instrumentation test,Work work)throws Exception{Throwable[] error={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    private static Object get(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name)throws Exception{return call(owner,name,new Class<?>[0]);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
