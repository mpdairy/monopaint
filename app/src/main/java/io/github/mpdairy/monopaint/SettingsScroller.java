package io.github.mpdairy.monopaint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Persistent, touchable scroll rail for the settings sheet, including rotated layouts. */
final class SettingsScroller extends LinearLayout {
    final ScrollView scroll;
    final View handle;
    SettingsScroller(Context context,View content) {
        super(context);setOrientation(HORIZONTAL);
        scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);
        addView(scroll,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        handle=new Handle(context);addView(handle,new LayoutParams(dp(36),LayoutParams.MATCH_PARENT));
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY) -> handle.invalidate());
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    int range(){return Math.max(0,scroll.getChildAt(0).getHeight()-scroll.getHeight());}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,r,b);handle.invalidate();
    }
    private final class Handle extends View {
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean dragging;
        float grab;
        Handle(Context context) {
            super(context);setContentDescription("Scroll settings");setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        float trackTop(){return dp(28);}
        float trackHeight(){return Math.max(1,getHeight()-2*trackTop());}
        float thumbHeight(){return Math.min(trackHeight(),Math.max(dp(48),trackHeight()*scroll.getHeight()/Math.max(1,scroll.getChildAt(0).getHeight())));}
        float travel(){return Math.max(0,trackHeight()-thumbHeight());}
        float thumbTop(){return trackTop()+travel()*scroll.getScrollY()/Math.max(1,range());}
        @Override protected void onDraw(Canvas canvas) {
            if(range()==0)return;
            float cx=getWidth()/2f,top=thumbTop(),bottom=top+thumbHeight();
            ink.setStrokeWidth(dp(2));ink.setStyle(Paint.Style.STROKE);ink.setColor(Color.BLACK);
            canvas.drawLine(cx,trackTop(),cx,getHeight()-trackTop(),ink);
            if(scroll.canScrollVertically(-1))arrow(canvas,cx,dp(12),-1);
            if(scroll.canScrollVertically(1))arrow(canvas,cx,getHeight()-dp(12),1);
            ink.setStyle(Paint.Style.FILL);ink.setColor(Color.WHITE);
            canvas.drawRoundRect(dp(5),top,getWidth()-dp(5),bottom,dp(5),dp(5),ink);
            ink.setStyle(Paint.Style.STROKE);ink.setColor(Color.BLACK);
            canvas.drawRoundRect(dp(5),top,getWidth()-dp(5),bottom,dp(5),dp(5),ink);
            float middle=(top+bottom)/2;
            for(int offset:new int[]{-5,0,5})canvas.drawLine(dp(11),middle+dp(offset),getWidth()-dp(11),middle+dp(offset),ink);
        }
        private void arrow(Canvas canvas,float x,float y,int direction) {
            canvas.drawLine(x-dp(6),y-direction*dp(4),x,y+direction*dp(4),ink);
            canvas.drawLine(x,y+direction*dp(4),x+dp(6),y-direction*dp(4),ink);
        }
        private void move(float y) {
            float position=Math.max(0,Math.min(1,(y-grab-trackTop())/Math.max(1,travel())));
            scroll.scrollTo(0,Math.round(range()*position));
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(range()==0)return false;
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if(event.getY()<trackTop() || event.getY()>getHeight()-trackTop()) {
                        scroll.scrollBy(0,(event.getY()<trackTop()?-1:1)*Math.max(1,scroll.getHeight()*3/4));
                        dragging=false;
                    } else {
                        float top=thumbTop();
                        grab=event.getY()>=top&&event.getY()<=top+thumbHeight()?event.getY()-top:thumbHeight()/2;
                        dragging=true;move(event.getY());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:if(dragging)move(event.getY());return true;
                case MotionEvent.ACTION_UP:
                    if(dragging)move(event.getY());dragging=false;
                    getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging=false;getParent().requestDisallowInterceptTouchEvent(false);return true;
                default:return true;
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.SeekBar");
            info.setScrollable(range()>0);
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0,100,100f*scroll.getScrollY()/Math.max(1,range())));
            if(range()>0)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            if(scroll.canScrollVertically(1))info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if(scroll.canScrollVertically(-1))info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action,Bundle args) {
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                scroll.scrollBy(0,(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1)*scroll.getHeight()*3/4);return true;
            }
            if(action==android.R.id.accessibilityActionSetProgress && args!=null) {
                float value=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                if(!Float.isFinite(value))return false;
                scroll.scrollTo(0,Math.round(range()*Math.max(0,Math.min(100,value))/100));return true;
            }
            return super.performAccessibilityAction(action,args);
        }
    }
}
