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
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics(); activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        float density=activity.getResources().getDisplayMetrics().density;
        ToneDocument doc=new ToneDocument(metrics.widthPixels-Math.round(64*density),metrics.heightPixels-Math.round(48*density));
        try {
            main(test,() -> {
                rasterChecks();
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
                    float x=view.getWidth()/2f,y=view.getHeight()/2f;
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
                    for(int dy=0;dy<8;dy++) for(int dx=0;dx<8;dx++)
                        check(direct.readGray(x+dx,y+dy)==((raster.bitmap.getPixel(x+dx,y+dy)&255)>>4),"Native panel matches zoomed raster");
                    int frames=(Integer)get(pad,"drawCount");
                    touch(activity,pad,MotionEvent.ACTION_DOWN,new int[]{0},new int[]{2},new float[]{x,y});
                    touch(activity,pad,MotionEvent.ACTION_MOVE,new int[]{0},new int[]{2},new float[]{x+30,y});
                    touch(activity,pad,MotionEvent.ACTION_UP,new int[]{0},new int[]{2},new float[]{x+30,y});
                    check((Integer)get(pad,"drawCount")==frames,"Zoomed strokes stay on the direct display path");
                    check(doc.undo(),"Native zoomed stroke can undo");call(pad,"renderDirty");call(pad,"present");
                    ((View)get(activity,"zoomButton")).performLongClick();
                    check(((CanvasViewport)get(pad,"viewport")).zoom==1,"Holding toolbar zoom fits the whole page");
                });
            }
            main(test,() -> {
                call(pad,"zoomBy",new Class<?>[]{float.class},100f);
                check(((CanvasViewport)get(pad,"viewport")).percent()==800,"Zoom caps at 800%");
                call(activity,"addPage");
                check(((CanvasViewport)get(pad,"viewport")).zoom==1,"New page starts fitted");
            });
            report.append("PASS: zoom percentage, pinch focus/pan, one-finger and active-pen palm rejection, cancellation, pen mapping/undo, calibrated shades, bounded native e-ink, direct strokes and fit reset in all four rotations and both hands; 800% limit and page reset.\n");
        } finally {
            main(test,() -> {
                call(pad,"finishStroke");call(pad,"dryWet");
                set(activity,"drawingName",name);set(activity,"library",library);set(activity,"gray",gray);set(activity,"maximum",maximum);
                set(activity,"wetCanvas",wet);set(activity,"transparentPaint",transparent);set(activity,"eraseMode",erase);
                prefs.edit().putBoolean("toolbox_right",right).apply();
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
    private static void rasterChecks() {
        Bitmap source=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        ViewportBitmap target=new ViewportBitmap(128,128);
        try {
            for(int y=0;y<16;y++) for(int x=0;x<16;x++) {
                int tone=y*16+x;source.setPixel(x,y,0xff000000|tone*0x010101);
            }
            for(float scale:new float[]{.7f,1,1.25f,2.13f,8}) {
                Matrix matrix=new Matrix();matrix.setScale(scale,scale);matrix.postTranslate(-2.35f,3.75f);
                Matrix inverse=new Matrix();matrix.invert(inverse);target.update(source,matrix,null);
                for(int y=0;y<128;y++) for(int x=0;x<128;x++) {
                    float[] p={x+.5f,y+.5f};inverse.mapPoints(p);
                    int tone=p[0]>=0&&p[0]<16&&p[1]>=0&&p[1]<16?(source.getPixel((int)p[0],(int)p[1])&255):255;
                    check(target.bitmap.getPixel(x,y)==DotPattern.pixel(tone,x,y),"Fractional zoom raster matches logical shade at every output pixel");
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
