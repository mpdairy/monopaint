package io.github.mpdairy.monopaint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scrollable page previews. Decoding never selects a page or disturbs its undo history. */
final class PageOverview extends GridView implements AutoCloseable {
    private final DrawingBook.Snapshot snapshot;
    private final int rotation;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<Integer,Bitmap> thumbnails=new android.util.LruCache<>(24);
    private final HashSet<Integer> pending=new HashSet<>(),failed=new HashSet<>();
    private volatile boolean closed;
    private final BaseAdapter adapter=new BaseAdapter() {
        @Override public int getCount(){return snapshot.pages.size();}
        @Override public Object getItem(int position){return position;}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            Tile tile=recycled instanceof Tile?(Tile)recycled:new Tile();
            tile.index=position;
            tile.setContentDescription("Page "+(position+1)+(position==snapshot.index?", current page":""));
            tile.setActivated(position==snapshot.index);tile.invalidate();request(position);return tile;
        }
    };

    PageOverview(Context context,DrawingBook.Snapshot snapshot,int rotation) {
        super(context);this.snapshot=snapshot;this.rotation=rotation;
        setNumColumns(AUTO_FIT);setColumnWidth(dp(140));setStretchMode(STRETCH_COLUMN_WIDTH);
        setHorizontalSpacing(dp(12));setVerticalSpacing(dp(12));setPadding(dp(16),dp(12),dp(16),dp(12));
        setClipToPadding(false);setBackgroundColor(Color.WHITE);setAdapter(adapter);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void request(int index) {
        if(closed || thumbnails.get(index)!=null || failed.contains(index) || !pending.add(index))return;
        worker.execute(() -> {
            Bitmap result=null;
            try {
                if(closed)return;
                ToneDocument document=snapshot.page(index);
                if(!closed)result=thumbnail(document);
            } catch(java.io.IOException | RuntimeException error) {
                android.util.Log.w(ProbeActivity.TAG,"Could not preview page "+(index+1),error);
            }
            final Bitmap ready=result;
            post(() -> {
                pending.remove(index);
                if(closed){if(ready!=null)ready.recycle();return;}
                if(ready==null)failed.add(index);else thumbnails.put(index,ready);
                // Repaint attached tiles without resetting scroll or keyboard selection.
                invalidateViews();
            });
        });
    }
    static Bitmap thumbnail(ToneDocument document) {
        float scale=Math.min(1,240f/Math.max(document.width,document.height));
        int width=Math.max(1,Math.round(document.width*scale)),height=Math.max(1,Math.round(document.height*scale));
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++) {
            if(Thread.currentThread().isInterrupted())return null;
            for(int x=0;x<width;x++) {
                // Average a small sample grid so fine strokes survive reduction without
                // allocating or dithering a full-resolution display bitmap for every page.
                int sum=0;
                for(int sy=0;sy<4;sy++)for(int sx=0;sx<4;sx++) {
                    int px=Math.min(document.width-1,(int)((x+(sx+.5f)/4)*document.width/width));
                    int py=Math.min(document.height-1,(int)((y+(sy+.5f)/4)*document.height/height));
                    sum+=document.compositeTone(px,py);
                }
                int tone=(sum+8)/16;pixels[y*width+x]=Color.rgb(tone,tone,tone);
            }
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }
    /** A copy turned by {@code -90*rotation} degrees, matching how the app shows a page. */
    static Bitmap turned(Bitmap bitmap,int rotation) {
        if(rotation%4==0)return bitmap;
        android.graphics.Matrix turn=new android.graphics.Matrix();turn.setRotate(-90*rotation);
        return Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),turn,false);
    }
    @Override public void close(){closed=true;worker.shutdownNow();thumbnails.evictAll();}

    private final class Tile extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private int index;
        Tile(){super(PageOverview.this.getContext());}
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width=MeasureSpec.getSize(widthSpec);
            float ratio=rotation%2==0?(float)snapshot.height/snapshot.width:(float)snapshot.width/snapshot.height;
            setMeasuredDimension(width,Math.round((width-dp(12))*Math.min(1.5f,ratio))+dp(44));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean current=index==snapshot.index;
            float bottom=getHeight()-dp(32),inset=dp(4);
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);canvas.drawRect(0,0,getWidth(),getHeight(),paint);
            Bitmap bitmap=thumbnails.get(index);
            if(bitmap!=null) {
                float width=rotation%2==0?bitmap.getWidth():bitmap.getHeight();
                float height=rotation%2==0?bitmap.getHeight():bitmap.getWidth();
                float scale=Math.min((getWidth()-2*inset)/width,(bottom-2*inset)/height);
                canvas.save();canvas.translate(getWidth()/2f,bottom/2);canvas.rotate(-90*rotation);canvas.scale(scale,scale);
                canvas.drawBitmap(bitmap,-bitmap.getWidth()/2f,-bitmap.getHeight()/2f,paint);canvas.restore();
            } else {
                paint.setColor(Color.DKGRAY);paint.setTextSize(dp(13));paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(failed.contains(index)?"Preview unavailable":"Loading…",getWidth()/2f,bottom/2,paint);
            }
            paint.setColor(Color.BLACK);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(current?3:1));
            canvas.drawRect(inset/2,inset/2,getWidth()-inset/2,bottom,paint);paint.setStyle(Paint.Style.FILL);
            if(current)canvas.drawRect(inset/2,bottom,getWidth()-inset/2,getHeight(),paint);
            paint.setColor(current?Color.WHITE:Color.BLACK);paint.setTextSize(dp(16));paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(index+1),getWidth()/2f,bottom+dp(16)-(paint.ascent()+paint.descent())/2,paint);
        }
    }
}
