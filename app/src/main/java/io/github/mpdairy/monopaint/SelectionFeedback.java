package io.github.mpdairy.monopaint;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.Map;
import java.util.WeakHashMap;

/** Synchronous control-only presentation, with Android redraw only as a fallback. */
final class SelectionFeedback {
    boolean enabled = true;
    int submitted;
    int lastDisplayMode;
    Rect lastScreenRegion;
    private boolean loggedFailure;
    private final Map<View, Rect> pending = new WeakHashMap<>();
    private ViewTreeObserver observer;
    // Refresh cached Android drawing commands when another UI action needs a frame.
    // Registering this listener does not request a frame or delay a display write.
    private final ViewTreeObserver.OnPreDrawListener syncBeforeDraw = () -> {
        for (Map.Entry<View, Rect> entry : pending.entrySet())
            entry.getKey().invalidate(entry.getValue());
        pending.clear();
        return true;
    };

    void retainForNextDraw(View owner, Rect area) {
        ViewTreeObserver next = owner.getRootView().getViewTreeObserver();
        if (observer != next) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(syncBeforeDraw);
            observer = next; observer.addOnPreDrawListener(syncBeforeDraw);
        }
        Rect old = pending.get(owner);
        if (old == null) pending.put(owner, new Rect(area)); else old.union(area);
    }
    /** Commit a finished interaction to Android's surface as well as the panel. */
    void finishUpdate(View owner) {
        Rect area = pending.remove(owner);
        if (area != null) owner.invalidate(area);
    }
    /** One settled gray-mode pass clears fast-ink residue without slowing live movement. */
    void clean(View owner, Rect area) {
        if (!enabled || area.isEmpty() || !owner.hasWindowFocus() || !owner.isShown()
                || owner.getDisplay() == null || !PanelCoordinates.fullyVisible(owner, area)) return;
        Bitmap pixels = capture(owner, area);
        DirectEink display = null;
        try {
            android.graphics.Matrix bitmapToView = new android.graphics.Matrix();
            bitmapToView.setTranslate(area.left, area.top);
            display = DirectEink.forView(owner, pixels, bitmapToView, 0, 7);
            // Android's unchanged white pixels cannot describe residual physical ink.
            if (display.refresh(pixels, new Rect(0, 0, pixels.getWidth(), pixels.getHeight())) < 0)
                owner.invalidate(area);
        } catch (RuntimeException error) {
            owner.invalidate(area);
        } finally {
            if (display != null) display.close();
            pixels.recycle();
        }
    }
    void close() {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(syncBeforeDraw);
        observer = null; pending.clear();
    }

    /** Updates the whole control. */
    void update(View owner, Runnable change) {
        update(owner,new Rect(0,0,owner.getWidth(),owner.getHeight()),change);
    }
    void update(View owner, Rect area, Runnable change) {
        update(owner,area,change,owner.hasWindowFocus());
    }
    // Non-focusable palettes share the activity's active window while keeping
    // their own feedback session and redraw observer for their popup surface.
    void update(View owner, Rect area, Runnable change, boolean windowFocused) {
        boolean direct = enabled && windowFocused && owner.isShown()
                && owner.getDisplay() != null
                && PanelCoordinates.fullyVisible(owner,area);
        Bitmap before = direct ? capture(owner, area) : null;
        change.run();
        if (before == null) { owner.invalidate(area); return; }
        present(owner, area, before);
    }
    /** Presents the control's current pixels over {@code before}, which it recycles. */
    private void present(View owner, Rect area, Bitmap before) {
        Bitmap after = capture(owner, area);
        DirectEink display = null;
        boolean presented = false;
        try {
            Rect dirty = difference(before, after);
            if (dirty.isEmpty()) { presented = true; return; }
            android.graphics.Matrix bitmapToView = new android.graphics.Matrix();
            bitmapToView.setTranslate(area.left,area.top);
            // Each session owns only this control patch. The canvas is never included.
            boolean fastBinary = DirectEink.fastBinaryControls() && opaqueBinary(after, dirty);
            int mode = fastBinary ? 9 : 7;
            display = DirectEink.forView(owner,before,bitmapToView,fastBinary ? 1 : 0,mode);
            if (display.present(after, dirty) > 0) {
                presented = true;
                retainForNextDraw(owner, area);
                submitted++;
                lastDisplayMode = mode;
                lastScreenRegion = display.panelRegion(dirty);
            }
            // Busy queues fall back to the normal redraw. Never replay stale UI pixels.
        } catch (RuntimeException error) {
            if (!loggedFailure) Log.w(ProbeActivity.TAG, "Selection feedback uses normal redraw", error);
            loggedFailure = true;
        } finally {
            if (display != null) display.close();
            before.recycle();
            after.recycle();
            if (!presented) owner.invalidate(area);
        }
    }

    /** Decide from the submitted patch, including its border, without flattening grays. */
    static boolean opaqueBinary(Bitmap bitmap, Rect area) {
        int[] row = new int[area.width()];
        for (int y = area.top; y < area.bottom; y++) {
            bitmap.getPixels(row, 0, row.length, area.left, y, row.length, 1);
            for (int pixel : row)
                if (pixel != android.graphics.Color.BLACK && pixel != android.graphics.Color.WHITE) return false;
        }
        return true;
    }

    private static Bitmap capture(View owner, Rect area) {
        Bitmap bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.translate(-area.left, -area.top);
        owner.draw(canvas); return bitmap;
    }
    static Rect difference(Bitmap before, Bitmap after) {
        int width = before.getWidth(), height = before.getHeight();
        int[] a = new int[width*height], b = new int[a.length];
        before.getPixels(a,0,width,0,0,width,height); after.getPixels(b,0,width,0,0,width,height);
        int left=width,top=height,right=0,bottom=0;
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            if (a[y*width+x] == b[y*width+x]) continue;
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);
        }
        if (right==0) return new Rect();
        return new Rect(Math.max(0,left-2),Math.max(0,top-2),Math.min(width,right+2),Math.min(height,bottom+2));
    }
}
