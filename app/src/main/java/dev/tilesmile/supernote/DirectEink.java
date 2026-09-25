package dev.tilesmile.supernote;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Manta-only region presenter, using ordinary app permissions. */
final class DirectEink {
    static { System.loadLibrary("tilesmile_display"); }
    static native String probe();
    private static native long nativeOpen(Bitmap background, int x, int y, int requestFlags, int displayMode);
    private static native int nativePresent(long handle, Bitmap bitmap, int left, int top,
                                            int right, int bottom, int x, int y);
    private static native void nativeClose(long handle);
    private static native int nativeReadGray(long handle, int x, int y);
    private long handle;
    private final int x, y;
    DirectEink(int x, int y, Bitmap background, int requestFlags, int displayMode) {
        this.x=x; this.y=y;
        handle=nativeOpen(background,x,y,requestFlags,displayMode);
    }
    synchronized int present(Bitmap bitmap, Rect dirty) {
        if (handle==0) return -1;
        return nativePresent(handle,bitmap,dirty.left,dirty.top,dirty.right,dirty.bottom,x,y);
    }
    synchronized void close() {
        if (handle==0) return;
        nativeClose(handle); handle=0;
    }
    synchronized int readGray(int localX, int localY) {
        return nativeReadGray(handle,x+localX,y+localY);
    }
}
