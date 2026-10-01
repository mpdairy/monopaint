package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real pen and palette gestures on a temporary book, with session restoration. */
final class GradientUiChecks {
    static void run(Instrumentation test,StringBuilder report) throws Exception {
        PaintActivity activity=(PaintActivity)test.startActivitySync(new Intent(test.getTargetContext(),PaintActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test,() -> !(Boolean)get(activity,"loading")&&activity.hasWindowFocus(),"App ready");
        Object pad=get(activity,"pad");
        DrawingBook original=(DrawingBook)get(activity,"book");
        String name=(String)get(activity,"drawingName");
        ToolLibrary library=(ToolLibrary)get(activity,"library");
        int gray=(Integer)get(get(activity,"paint"),"gray"),maximum=TestAccess.maximum(activity);
        int quarter=(4-(Integer)get(activity,"appRotation"))%4;
        SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");
        boolean right=prefs.getBoolean("toolbox_right",false);
        boolean originalErase=(Boolean)get(get(activity,"paint"),"eraseMode");
        try {
            main(test,() -> {
                set(get(activity,"paint"),"eraseMode",false);
                ((android.view.OrientationEventListener)get(get(activity,"rotationPrompt"),"orientationSensor")).disable();
                ToolLibrary tools=new ToolLibrary();tools.select(ToolSettings.Tool.FILL);tools.edit(tools.current().gradient(ToolSettings.Gradient.LINEAR));
                set(activity,"library",tools);set(activity,"drawingName","");
            });
            for(int rotation:new int[]{0,1,2,3}) for(boolean hand:new boolean[]{false,true}) {
                ToneDocument doc=new ToneDocument(640,720);
                main(test,() -> {
                    doc.begin();doc.paintTone(50,50,80);doc.finish();doc.addLayer();
                    doc.begin();for(int y=0;y<720;y++)doc.paintTone(300,y,100);doc.finish();
                    call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));
                    prefs.edit().putBoolean("toolbox_right",hand).apply();
                    call(activity,"requestQuarter",new Class<?>[]{int.class},rotation);call(activity,"applyToolboxSide");
                    set(get(activity,"paint"),"gray",0);
                });
                test.waitForIdleSync();SystemClock.sleep(150);
                byte[] before=encoded(doc);
                pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
                long waitingSince=SystemClock.uptimeMillis();
                main(test,() -> {
                    check((Boolean)get(pad,"gradientWaiting")&&get(pad,"gradientFill")==null,"Direction line waits without a fill transaction");
                    check(Arrays.equals(before,encoded(doc)),"Waiting leaves every layer unchanged");
                    check(get(activity,"gradientHint")==null,"No immediate hint");
                    check(((android.widget.TextView)get(get(activity,"toolbar"),"operationStatus")).getText().length()==0,"No tool-rail instruction");
                    guide(pad);
                });
                if(rotation==0&&!hand) {
                    SystemClock.sleep(2000);
                    main(test,() -> check(get(activity,"gradientHint")==null,"Hint stays hidden during the first two seconds"));
                }
                await(test,() -> get(activity,"gradientHint")!=null,"Delayed hint appears");
                check(SystemClock.uptimeMillis()-waitingSince>=2800,"Hint waits about three seconds");
                main(test,() -> {
                    android.widget.TextView hint=(android.widget.TextView)get(activity,"gradientHint");
                    check("Select a second color".contentEquals(hint.getText()),"Short color hint");
                    check(hint.getCurrentTextColor()==android.graphics.Color.BLACK,"Black hint text");
                    check(((android.graphics.drawable.ColorDrawable)hint.getBackground()).getColor()==android.graphics.Color.WHITE,"White hint background");
                    View root=(View)get(activity,"root");
                    check(hint.getLeft()>=0&&hint.getRight()<=root.getWidth()&&hint.getTop()>=0&&hint.getBottom()<=root.getHeight(),"Hint stays within upright layout");
                    Matrix toRoot=new Matrix();PanelCoordinates.fromView(root).invert(toRoot);
                    View picker=(View)get(activity,"shadePicker");
                    android.graphics.RectF anchor=new android.graphics.RectF(0,0,picker.getWidth(),picker.getHeight());
                    PanelCoordinates.fromView(picker).mapRect(anchor);toRoot.mapRect(anchor);
                    check(hint.getTop()>=anchor.bottom,"Hint sits below the color selector");
                    check(Arrays.equals(before,encoded(doc)),"Hint does not modify artwork");guide(pad);
                });
                screenshot(test,activity,"gradient-hint-"+rotation+"-"+hand+".png");
                // Even choosing the starting shade must begin the preview.
                shadeEvent(test,activity,false,MotionEvent.ACTION_DOWN);
                main(test,() -> check(get(activity,"gradientHint")==null&&!(Boolean)get(pad,"gradientWaiting"),"First palette contact hides hint and removes guide"));
                await(test,() -> (Boolean)get(pad,"gradientReady")&&doc.tone(240,240)==0,"Same-shade selection starts preview");
                shadeEvent(test,activity,true,MotionEvent.ACTION_MOVE);
                await(test,() -> (Boolean)get(pad,"gradientReady")&&doc.tone(240,240)==255,"White end shade while held");
                shadeEvent(test,activity,false,MotionEvent.ACTION_MOVE);
                await(test,() -> (Boolean)get(pad,"gradientReady")&&doc.tone(240,240)==0,"Black end shade while held");
                shadeEvent(test,activity,true,MotionEvent.ACTION_MOVE);
                await(test,() -> (Boolean)get(pad,"gradientReady")&&doc.tone(240,240)==255,"White preview restored while held");
                main(test,() -> {
                    check(doc.tone(60,60)<=1&&Math.abs(doc.tone(150,150)-128)<=1,"Diagonal projection in rotation "+rotation+", hand "+hand);
                    check(doc.tone(300,150)==100&&doc.opacity(400,150)==0,"Selected-layer boundary retained");
                    check(doc.compositeTone(50,50)==0,"Gradient covers selected layer over underlying marks");
                });
                screenshot(test,activity,"gradient-"+rotation+"-"+hand+".png");
                check(get(pad,"gradientFill")!=null,"Holding the selector does not commit");
                shadeEvent(test,activity,true,MotionEvent.ACTION_UP);
                await(test,() -> get(pad,"gradientFill")==null,"Palette release commits");
                main(test,() -> {
                    check(get(pad,"gradientFill")==null,"Palette release commits gradient");
                    check(doc.undo()&&Arrays.equals(before,encoded(doc)),"One undo restores all layer data");
                    check(doc.redo()&&doc.tone(240,240)==255,"Redo keeps chosen gradient");doc.undo();
                    set(get(activity,"paint"),"gray",0);
                });
                pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
                shadeEvent(test,activity,true,MotionEvent.ACTION_DOWN);
                await(test,() -> (Boolean)get(pad,"gradientReady"),"Cancel preview ready");
                shadeEvent(test,activity,true,MotionEvent.ACTION_CANCEL);
                main(test,() -> {
                    check(get(pad,"gradientFill")==null&&Arrays.equals(before,encoded(doc)),"Interrupted selector restores all layers");
                    check(doc.canRedo()&&(Integer)get(get(activity,"paint"),"gray")==0,"Interrupted selector preserves redo and starting shade");
                });
                // Pick from underneath the preview, then move to a different shade.
                pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
                tap(test,(View)get(activity,"eyedropperButton"));
                main(test,() -> {
                    check(get(activity,"gradientHint")==null&&(Boolean)get(pad,"gradientWaiting"),"Eyedropper hides hint but keeps the direction guide");
                    check(Arrays.equals(before,encoded(doc)),"Eyedropper activation alone does not preview");
                });
                if(rotation==0&&!hand) {
                    SystemClock.sleep(3100);
                    main(test,() -> check(get(activity,"gradientHint")==null,"Eyedropper cancels pending hint timer"));
                }
                penEvent(test,pad,MotionEvent.ACTION_DOWN,50,50);
                await(test,() -> (Integer)get(get(activity,"paint"),"gray")==80&&(Boolean)get(pad,"gradientReady"),"Dropper samples original layer composite");
                main(test,() -> check((Boolean)get(activity,"pickingShade")&&get(pad,"gradientFill")!=null,"Dropper holds preview open"));
                penEvent(test,pad,MotionEvent.ACTION_MOVE,300,150);
                await(test,() -> (Integer)get(get(activity,"paint"),"gray")==100&&(Boolean)get(pad,"gradientReady"),"Moving dropper updates shade");
                penEvent(test,pad,MotionEvent.ACTION_UP,50,50);
                await(test,() -> get(pad,"gradientFill")==null,"Dropper release commits");
                main(test,() -> {
                    check(doc.tone(240,240)==80&&!(Boolean)get(activity,"pickingShade"),"Final release position supplies gradient shade");
                    check(doc.undo()&&Arrays.equals(before,encoded(doc)),"Dropper gradient is one undo");
                });
                // The regular eyedropper also follows a held pen without painting.
                tap(test,(View)get(activity,"eyedropperButton"));
                penEvent(test,pad,MotionEvent.ACTION_DOWN,50,50);
                main(test,() -> check((Integer)get(get(activity,"paint"),"gray")==80&&(Boolean)get(activity,"pickingShade"),"Regular dropper stays armed on down"));
                penEvent(test,pad,MotionEvent.ACTION_MOVE,300,150);
                main(test,() -> check((Integer)get(get(activity,"paint"),"gray")==100&&(Boolean)get(activity,"pickedShade"),"Regular drag updates visible marker"));
                penEvent(test,pad,MotionEvent.ACTION_UP,400,150);
                main(test,() -> {
                    check((Integer)get(get(activity,"paint"),"gray")==255&&!(Boolean)get(activity,"pickingShade"),"Regular dropper accepts release shade");
                    check(Arrays.equals(before,encoded(doc))&&doc.canRedo(),"Regular sampling leaves artwork and history untouched");
                });
                tap(test,(View)get(activity,"eyedropperButton"));
                penEvent(test,pad,MotionEvent.ACTION_DOWN,50,50);
                penEvent(test,pad,MotionEvent.ACTION_CANCEL,50,50);
                main(test,() -> check((Integer)get(get(activity,"paint"),"gray")==255&&!(Boolean)get(activity,"pickingShade"),"Canceled dropper restores prior color"));
                chooseGradient(test,activity,ToolSettings.Gradient.CIRCULAR);
                chooseGradient(test,activity,ToolSettings.Gradient.LINEAR);
                chooseGradient(test,activity,ToolSettings.Gradient.CIRCULAR);
                main(test,() -> {
                    set(get(activity,"paint"),"gray",0);
                    // Direct document undo above bypasses the app's Undo button refresh.
                    call(pad,"renderDirty");call(pad,"present");
                });
                pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
                main(test,() -> {
                    check((Boolean)get(pad,"gradientWaiting")&&Arrays.equals(before,encoded(doc)),"Circular guide waits without changing pixels");guide(pad);
                });
                shadeEvent(test,activity,true,MotionEvent.ACTION_DOWN);
                await(test,() -> (Boolean)get(pad,"gradientReady"),"Circular preview ready");
                main(test,() -> {
                    check(doc.tone(60,60)<=1&&Math.abs(doc.tone(150,60)-90)<=1,"Circular shade depends on radius, not projection");
                    check(Math.abs(doc.tone(150,60)-doc.tone(60,150))<=1,"Equal radii have equal shades");
                    check(doc.tone(240,240)==255&&doc.tone(60,500)==255,"Circular radius and beyond use end color");
                    check(doc.tone(300,150)==100&&doc.opacity(400,150)==0,"Circular fill respects layer boundaries");
                });
                screenshot(test,activity,"gradient-circular-"+rotation+"-"+hand+".png");
                shadeEvent(test,activity,true,MotionEvent.ACTION_UP);
                await(test,() -> get(pad,"gradientFill")==null,"Circular release commits");
                main(test,() -> check(doc.undo()&&Arrays.equals(before,encoded(doc)),"Circular fill is one exact undo"));
                chooseGradient(test,activity,ToolSettings.Gradient.LINEAR);

            }
            // Solid taps, interrupted drags, hidden layers, and leaving a preview.
            ToneDocument doc=new ToneDocument(640,720);
            main(test,() -> {
                call(activity,"requestQuarter",new Class<?>[]{int.class},0);
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(doc));set(get(activity,"paint"),"gray",80);
            });test.waitForIdleSync();
            chooseGradient(test,activity,ToolSettings.Gradient.FLAT);
            pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
            await(test,() -> get(pad,"fill")==null&&doc.tone(639,719)==80,"Flat fill drag completes without a gradient");
            main(test,() -> {check(!(Boolean)get(pad,"gradientWaiting")&&doc.undo(),"Flat fill has no second color and one undo");call(pad,"renderAll");});
            chooseGradient(test,activity,ToolSettings.Gradient.LINEAR);
            pen(test,pad,60,60,60,60,MotionEvent.ACTION_UP);
            await(test,() -> get(pad,"fill")==null&&doc.tone(639,719)==80,"Solid tap completes");
            main(test,() -> check(doc.undo()&&!doc.canUndo(),"Solid tap is one undo"));
            pen(test,pad,60,60,240,240,MotionEvent.ACTION_CANCEL);
            main(test,() -> check(doc.opacity(60,60)==0&&get(pad,"gradientFill")==null,"Canceled drag leaves no mark"));
            pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
            main(test,() -> {
                call(pad,"finishStroke");
                check(!(Boolean)get(pad,"gradientWaiting")&&get(activity,"gradientHint")==null,"Leaving waiting gradient removes guide and hint");
                check(doc.opacity(60,60)==0&&doc.canRedo(),"Abandoned guide preserves artwork and redo");
            });
            pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
            shadeEvent(test,activity,true,MotionEvent.ACTION_DOWN);
            shadeEvent(test,activity,true,MotionEvent.ACTION_CANCEL);
            main(test,() -> {call(pad,"finishStroke");check(doc.opacity(60,60)==0&&doc.canRedo(),"Leaving partial preview restores data and history");doc.setLayerVisible(0,false);});
            pen(test,pad,60,60,240,240,MotionEvent.ACTION_UP);
            main(test,() -> check(get(pad,"gradientFill")==null&&get(pad,"fill")==null,"Hidden layer rejects fill"));
            chooseGradient(test,activity,ToolSettings.Gradient.CIRCULAR);
            ToneDocument large=new ToneDocument(1800,2470);
            main(test,() -> {
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},new DrawingBook(large));set(get(activity,"paint"),"gray",0);
            });test.waitForIdleSync();
            pen(test,pad,60,60,1200,1700,MotionEvent.ACTION_UP);
            shadeEvent(test,activity,true,MotionEvent.ACTION_DOWN);
            await(test,() -> (Boolean)get(pad,"gradientReady")&&large.tone(1799,2469)==255,"Full-page held preview");
            shadeEvent(test,activity,false,MotionEvent.ACTION_MOVE);
            shadeEvent(test,activity,true,MotionEvent.ACTION_UP);
            await(test,() -> get(pad,"gradientFill")==null,"Full-page release commits latest shade");
            main(test,() -> {
                check(large.tone(60,60)==0&&large.tone(1799,2469)==255,"Full-page final shade survives queued previews");
                check(large.undo()&&!large.canUndo()&&large.opacity(1799,2469)==0,"Full-page fill is one undo");
            });
            pen(test,pad,60,60,1200,1700,MotionEvent.ACTION_UP);
            main(test,() -> {
                call(activity,"selectShade",new Class<?>[]{int.class},200);
                call(pad,"applyGradient");
                call(pad,"finishStroke");
                check(get(pad,"gradientFill")==null&&large.tone(1799,2469)==200,"Lifecycle completion retains already accepted shade");
            });
            report.append("PASS: selectable Linear/Circular controls and saved type in all rotations/hands, line-only circular guide, symmetric radial colors, boundary clamping and full-page circular fill; unmodified artwork and retained direction guide until color contact, three-second black-on-white hint below palette, immediate hint dismissal and timer cancellation, same-shade preview, held palette preview and release-to-commit, gradient eyedropper samples original artwork while moving, regular eyedropper drags and release shade, canceled color gestures, all rotations and hands, layers, undo/redo, solid taps and hidden layers.\n");
        } finally {
            main(test,() -> {
                call(pad,"finishStroke");
                set(activity,"library",library);set(get(activity,"paint"),"eraseMode",originalErase);set(activity,"drawingName",name);
                set(get(activity,"paint"),"gray",gray);TestAccess.setMaximum(activity,maximum);
                prefs.edit().putBoolean("toolbox_right",right).apply();
                call(activity,"replaceBook",new Class<?>[]{DrawingBook.class},original);
                call(activity,"requestQuarter",new Class<?>[]{int.class},quarter);call(activity,"applyToolboxSide");
                call(activity,"saveToolState");call(activity,"recovery");
            });
            CountDownLatch saved=new CountDownLatch(1);
            ((DocumentStore)get(activity,"store")).recover((value,error) -> saved.countDown());
            check(saved.await(15,TimeUnit.SECONDS),"Restoration saved");
        }
    }
    private static void chooseGradient(Instrumentation test,PaintActivity activity,ToolSettings.Gradient type) throws Exception {
        android.widget.PopupWindow[] dialog={null};
        try {
            main(test,() -> dialog[0]=(android.widget.PopupWindow)call(activity,"showToolSettings"));test.waitForIdleSync();
            tap(test,description(dialog[0].getContentView(),type==ToolSettings.Gradient.FLAT?"Flat fill":type.label+" gradient"));
            main(test,() -> {
                check(((ToolLibrary)get(activity,"library")).current().gradient==type,"Gradient control selects "+type);
                SharedPreferences prefs=(SharedPreferences)get(activity,"preferences");
                check(ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools",""))).current().gradient==type,"Gradient type saved immediately");
            });
            if(type==ToolSettings.Gradient.CIRCULAR)
                screenshot(test,activity,"gradient-settings-"+get(activity,"appRotation")+"-"+get(activity,"toolboxRight")+".png");
        } finally {if(dialog[0]!=null)main(test,() -> dialog[0].dismiss());}
        await(test,activity::hasWindowFocus,"Canvas focus after gradient settings");
    }
    private static View description(View view,String text) {
        if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {View found=description(group.getChildAt(i),text);if(found!=null)return found;}
        }
        return null;
    }
    private static void guide(Object pad) throws Exception {
        View view=(View)pad;
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            view.draw(new android.graphics.Canvas(bitmap));
            float[] point={150,150};((Matrix)get(pad,"pageToView")).mapPoints(point);
            check(bitmap.getPixel(Math.round(point[0]),Math.round(point[1]))==android.graphics.Color.BLACK,"Direction guide is visible over original white artwork");
            check(((Bitmap)get(pad,"display")).getPixel(150,150)==android.graphics.Color.WHITE,"Guide is excluded from the document display bitmap");
            if(get(pad,"fillGradient")==ToolSettings.Gradient.CIRCULAR) {
                float[] edge={60,60+(float)Math.hypot(180,180)};((Matrix)get(pad,"pageToView")).mapPoints(edge);
                check(bitmap.getPixel(Math.round(edge[0]),Math.round(edge[1]))==android.graphics.Color.WHITE,"Circular guide leaves the radius outline undrawn");
            }
        } finally {bitmap.recycle();}
    }
    private static byte[] encoded(ToneDocument doc) throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();DocumentCodec.write(out,doc.layerSnapshot());return out.toByteArray();
    }
    private static long penDown;
    private static void penEvent(Instrumentation test,Object pad,int action,float x,float y) throws Exception {
        float[] point={x,y};
        main(test,() -> {((Matrix)get(pad,"pageToView")).mapPoints(point);PanelCoordinates.fromView((View)pad).mapPoints(point);});
        stylus(test,action,point);
    }
    private static void stylus(Instrumentation test,int action,float[] point) {
        if(action==MotionEvent.ACTION_DOWN) penDown=SystemClock.uptimeMillis();
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.pressure=action==MotionEvent.ACTION_UP?0:.6f;coords.size=.1f;
        coords.x=point[0];coords.y=point[1];
        MotionEvent event=MotionEvent.obtain(penDown,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{prop},
                new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
        check(test.getUiAutomation().injectInputEvent(event,true),"Pen injected");event.recycle();test.waitForIdleSync();
    }
    private static void pen(Instrumentation test,Object pad,float x,float y,float endX,float endY,int finalAction) throws Exception {
        penEvent(test,pad,MotionEvent.ACTION_DOWN,x,y);
        penEvent(test,pad,MotionEvent.ACTION_MOVE,endX,endY);
        penEvent(test,pad,finalAction,endX,endY);
    }
    private static void shadeEvent(Instrumentation test,PaintActivity activity,boolean white,int action) throws Exception {
        View view=(View)get(activity,"shadePicker");
        boolean flipped=(Boolean)get(activity,"landscape");
        float[] point={view.getWidth()*((white!=flipped)?.995f:.005f),view.getHeight()/2f};
        PanelCoordinates.fromView(view).mapPoints(point);stylus(test,action,point);
    }
    private static void tap(Instrumentation test,View view) {tapAt(test,view,view.getWidth()/2f,view.getHeight()/2f);}
    private static void tapAt(Instrumentation test,View view,float x,float y) {
        float[] point={x,y};PanelCoordinates.fromView(view).mapPoints(point);
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            check(test.getUiAutomation().injectInputEvent(event,true),"Control touch injected");event.recycle();
        }
        test.waitForIdleSync();SystemClock.sleep(100);
    }
    private static void screenshot(Instrumentation test,PaintActivity activity,String name) throws Exception {
        main(test,() -> ((View)get(activity,"pad")).invalidate());test.waitForIdleSync();SystemClock.sleep(100);
        Bitmap bitmap=test.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(),name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        } finally {bitmap.recycle();}
    }
    private interface Work {void run() throws Exception;}
    private interface Condition {boolean ok() throws Exception;}
    private static void main(Instrumentation test,Work work) throws Exception {
        Throwable[] failure={null};test.runOnMainSync(() -> {try{work.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new Exception(failure[0]);
    }
    private static void await(Instrumentation test,Condition condition,String message) throws Exception {
        for(int i=0;i<100;i++){boolean[] ready={false};main(test,() -> ready[0]=condition.ok());if(ready[0])return;SystemClock.sleep(100);}
        throw new IllegalStateException(message+" timed out");
    }
    private static Object get(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args) throws Exception {Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}
    private static Object call(Object owner,String name) throws Exception {return call(owner,name,new Class<?>[0]);}
    private static void check(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
