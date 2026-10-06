package io.github.mpdairy.monopaint;

import java.util.ArrayList;
import java.util.List;

/** Small damage patches: do not turn two moving rectangle edges into a full-page repaint. */
final class DirtyRegions {
    private final ArrayList<int[]> regions=new ArrayList<>();
    private final int limit;
    DirtyRegions(int limit) { this.limit=limit; }
    boolean isEmpty() { return regions.isEmpty(); }
    void clear() { regions.clear(); }
    int[] first() { return regions.get(0); }
    void removeFirst() { regions.remove(0); }
    List<int[]> drain() { ArrayList<int[]> result=new ArrayList<>(regions);clear();return result; }
    void add(android.graphics.Rect r) { add(r.left,r.top,r.right,r.bottom); }
    void add(int left,int top,int right,int bottom) {
        if(left>=right || top>=bottom) return;
        long addedArea=(long)(right-left)*(bottom-top);
        for(int i=0;i<regions.size();i++) {
            int[] old=regions.get(i);
            // Nearby scanlines coalesce; perpendicular strips keep their empty interior out.
            long combined=(long)(Math.max(old[2],right)-Math.min(old[0],left))
                    *(Math.max(old[3],bottom)-Math.min(old[1],top));
            if(combined*4 <= (area(old)+addedArea)*5) {
                // Extend the existing patch without allocating an object for every scanline.
                old[0]=Math.min(old[0],left);old[1]=Math.min(old[1],top);
                old[2]=Math.max(old[2],right);old[3]=Math.max(old[3],bottom);return;
            }
        }
        regions.add(new int[]{left,top,right,bottom});
        if(regions.size()>limit) {
            int a=0,b=1;long least=Long.MAX_VALUE;
            for(int i=0;i<regions.size();i++)for(int j=i+1;j<regions.size();j++) {
                long extra=unionArea(regions.get(i),regions.get(j))-area(regions.get(i))-area(regions.get(j));
                if(extra<least) { least=extra;a=i;b=j; }
            }
            union(regions.get(a),regions.remove(b));
        }
    }
    private static long area(int[] r) { return (long)(r[2]-r[0])*(r[3]-r[1]); }
    private static long unionArea(int[] a,int[] b) {
        return (long)(Math.max(a[2],b[2])-Math.min(a[0],b[0]))*(Math.max(a[3],b[3])-Math.min(a[1],b[1]));
    }
    private static void union(int[] a,int[] b) {
        a[0]=Math.min(a[0],b[0]);a[1]=Math.min(a[1],b[1]);a[2]=Math.max(a[2],b[2]);a[3]=Math.max(a[3],b[3]);
    }
}
