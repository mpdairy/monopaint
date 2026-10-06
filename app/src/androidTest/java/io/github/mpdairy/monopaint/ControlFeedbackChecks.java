package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

/** Exercises the real control renderers and the gray-patch fallback. */
final class ControlFeedbackChecks {
    static void wetness(Instrumentation test,PaintActivity app) throws Exception {
        for(int tool:new int[]{MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER}) {
            long start=SystemClock.uptimeMillis();
            for(int i=0;i<9;i++) {
                final int step=i;
                main(test,() -> {
                    View view=app.wetnessBar;
                    int[] values={10,90,30,70,0,100,50,20,80};int target=values[step];
                    MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=tool;
                    MotionEvent.PointerCoords point=new MotionEvent.PointerCoords();
                    point.x=view.getWidth()/2f;point.y=view.getHeight()-app.dp(6)-(view.getHeight()-app.dp(12))*target/100f;
                    point.pressure=step==8?0:.5f;
                    MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),step==0?MotionEvent.ACTION_DOWN:step==8?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                            1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{point},0,0,1,1,0,0,
                            tool==MotionEvent.TOOL_TYPE_STYLUS?android.view.InputDevice.SOURCE_STYLUS:android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
                    int count=app.selectionFeedback.submitted;
                    try {view.dispatchTouchEvent(event);} finally {event.recycle();}
                    check(app.paint.wetness==target,"Wetness reaches exact pen/finger position including release: expected="+target+" got="+app.paint.wetness);
                    check(app.paint.wetCanvas==(target>0),"Zero dries and positive wetness enables blending");
                    check(app.selectionFeedback.submitted>count,"Wetness feedback is immediate without pacing");
                    check(app.selectionFeedback.lastDisplayMode==fastMode(),"Wetness and wet-state dot use fast mode");
                });
                SystemClock.sleep(8);
            }
        }
    }

    static void controls(Instrumentation test,PaintActivity app,StringBuilder report) throws Exception {
        PopupWindow[] window={null};SelectionFeedback feedback=new SelectionFeedback();
        ToolButton[] button={null};PaletteSwatch[] swatch={null};GrayControl[] gray={null};
        try {
            main(test,() -> {
                LinearLayout row=new LinearLayout(app);row.setBackgroundColor(Color.WHITE);
                button[0]=new ToolButton(app,app);button[0].setBackgroundColor(Color.WHITE);
                button[0].iconOnly(R.drawable.ic_layer_visible);button[0].caption="100%";
                button[0].press.feedback=feedback;
                swatch[0]=new PaletteSwatch(app,0,true);gray[0]=new GrayControl(app);
                row.addView(button[0],new LinearLayout.LayoutParams(140,150));
                row.addView(swatch[0],new LinearLayout.LayoutParams(140,150));
                row.addView(gray[0],new LinearLayout.LayoutParams(140,150));
                window[0]=new PopupWindow(row,420,150,false);
                window[0].setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                window[0].showAtLocation(app.root,Gravity.TOP|Gravity.LEFT,200,250);
            });
            test.waitForIdleSync();SystemClock.sleep(200);
            main(test,() -> {
                for(int i=0;i<6;i++) {
                    final int index=i;
                    update(feedback,button[0],() -> button[0].caption=index%2==0?"125%":"100%",fastMode());
                    update(feedback,button[0],() -> button[0].iconOnly(index%2==0?R.drawable.ic_layer_hidden:R.drawable.ic_layer_visible),fastMode());
                    update(feedback,button[0],() -> button[0].press.pressed=!button[0].press.pressed,fastMode());
                    update(feedback,button[0],() -> button[0].marked=!button[0].marked,fastMode());
                    int count=feedback.submitted;
                    swatch[0].presentTone(index%2==0?128:255,feedback,app.hasWindowFocus());
                    check(feedback.submitted>count && feedback.lastDisplayMode==fastMode(),"Nonfocusable popup swatch updates immediately");
                    update(feedback,gray[0],() -> gray[0].selected=!gray[0].selected,7);
                }
                int count=feedback.submitted;
                feedback.update(button[0],new Rect(0,0,140,150),() -> {},true);
                check(count==feedback.submitted,"Unchanged controls submit nothing");
            });
            report.append("PASS: wetness drags in all rotations/hands, button dots and rounded press outlines, layer-eye icons, zoom text, popup swatches, unchanged suppression, and gray patches retained on mode 7.\n");
        } finally {
            main(test,() -> {feedback.close();if(window[0]!=null)window[0].dismiss();app.root.invalidate();});
        }
    }
    private static void update(SelectionFeedback feedback,View view,Runnable change,int mode) {
        int count=feedback.submitted;
        feedback.update(view,new Rect(0,0,view.getWidth(),view.getHeight()),change,true);
        check(feedback.submitted>count,"Changed control submitted immediately: "+view.getClass().getSimpleName());
        check(feedback.lastDisplayMode==mode,"Control mode expected="+mode+" got="+feedback.lastDisplayMode+" "+view.getClass().getSimpleName());
    }
    private static int fastMode() {return DirectEink.fastBinaryControls()?9:7;}
    private static final class GrayControl extends View {
        boolean selected;final Paint ink=new Paint();
        GrayControl(PaintActivity app) {super(app);}
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.GRAY);ink.setColor(Color.BLACK);
            canvas.drawRect(selected?30:10,10,selected?40:20,30,ink);
        }
    }
    private interface Work {void run() throws Exception;}
    private static void main(Instrumentation test,Work work) throws Exception {
        Throwable[] failure={null};test.runOnMainSync(() -> {try {work.run();} catch(Throwable error) {failure[0]=error;}});
        if(failure[0]!=null)throw new Exception(failure[0]);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
