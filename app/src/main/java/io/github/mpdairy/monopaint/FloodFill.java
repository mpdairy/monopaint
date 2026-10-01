package io.github.mpdairy.monopaint;

/** Four-connected scanline fill. Only logical tones are inspected, never display dots. */
final class FloodFill {
    private final ToneDocument document;
    private final int source, tolerance, first;
    private int target, repaint = -1;
    private final boolean gradient;
    private final ToolSettings.Gradient type;
    private final float startX, startY, dx, dy, lengthSquared;
    private final java.util.BitSet visited;
    private long[] spans;
    private int size;
    FloodFill(ToneDocument document, int x, int y, int target) {
        this(document,x,y,target,0);
    }
    FloodFill(ToneDocument document, int x, int y, int target, int tolerancePercent) {
        this(document,x,y,target,tolerancePercent,false,x,y,x,y,target,ToolSettings.Gradient.LINEAR);
    }
    FloodFill(ToneDocument document, int x, int y, int first, int tolerancePercent,
              float startX, float startY, float endX, float endY, int second) {
        this(document,x,y,first,tolerancePercent,startX,startY,endX,endY,second,ToolSettings.Gradient.LINEAR);
    }
    FloodFill(ToneDocument document, int x, int y, int first, int tolerancePercent,
              float startX, float startY, float endX, float endY, int second, ToolSettings.Gradient type) {
        this(document,x,y,first,tolerancePercent,true,startX,startY,endX,endY,second,type);
    }
    private FloodFill(ToneDocument document, int x, int y, int first, int tolerancePercent,
                      boolean gradient, float startX, float startY, float endX, float endY, int second, ToolSettings.Gradient type) {
        if(type==null) throw new IllegalArgumentException("Invalid gradient type");
        if(first<ToneDocument.ERASE || first>255 || second<ToneDocument.ERASE || second>255)
            throw new IllegalArgumentException("Invalid fill color");
        this.type=type;
        if(tolerancePercent<0||tolerancePercent>100)throw new IllegalArgumentException("Invalid fill tolerance");
        this.document = document; this.target = second; this.first = first; this.gradient = gradient;
        this.startX=startX; this.startY=startY; dx=endX-startX; dy=endY-startY;
        lengthSquared=dx*dx+dy*dy;
        tolerance=Math.round(tolerancePercent*255f/100);
        visited=new java.util.BitSet(document.width*document.height);
        source = document.tone(x,y); document.begin();
        spans = new long[Math.min(256, document.width * document.height)];
        if (gradient || target==ToneDocument.ERASE || source != target || tolerance>0 || document.opacity(x,y)<255) schedule(x,y);
    }
    boolean advance(int budget) {
        int work = 0;
        while (size > 0 && work < budget) {
            long span = spans[--size];
            int start = (int)(span >>> 32), right = (int)span;
            int y = start / document.width, left = start % document.width;
            for (int ny = y-1; ny <= y+1; ny += 2) {
                if (ny < 0 || ny >= document.height) continue;
                int x = left;
                while (x < right) {
                    if (matches(x,ny)) {
                        int end = schedule(x,ny); work += end-x; x = end;
                    } else { x++; work++; }
                }
            }
        }
        // Recolor only the original region, even if a preview now matches its boundary.
        while(size==0 && repaint>=0 && work<budget) {
            int pixel=visited.nextSetBit(repaint);
            if(pixel<0) { repaint=-1; break; }
            int x=pixel%document.width, y=pixel/document.width;
            paintAt(x,y); repaint=pixel+1; work++;
        }
        return size == 0 && repaint < 0;
    }
    void secondShade(int tone) {
        if(!gradient) throw new IllegalStateException("Not a gradient");
        if(tone<ToneDocument.ERASE||tone>255) throw new IllegalArgumentException("Invalid tone");
        if(target!=tone) { target=tone; repaint=0; }
    }
    private void paintAt(int x,int y) {
        if(!gradient || first==target) {
            document.paintTone(x,y,target==ToneDocument.ERASE?255:target,target==ToneDocument.ERASE?0:255);
            return;
        }
        float offsetX=x-startX, offsetY=y-startY;
        float amount=lengthSquared==0?0:type==ToolSettings.Gradient.CIRCULAR
                ? (float)Math.sqrt((offsetX*offsetX+offsetY*offsetY)/lengthSquared)
                : (offsetX*dx+offsetY*dy)/lengthSquared;
        amount=Math.max(0,Math.min(1,amount));
        if(first==ToneDocument.ERASE || target==ToneDocument.ERASE) {
            int pigment=first==ToneDocument.ERASE?target:first;
            int coverage=Math.round(255*(first==ToneDocument.ERASE?amount:1-amount));
            document.paintTone(x,y,pigment,coverage);
        } else document.paintTone(x,y,Math.round(first+(target-first)*amount));
    }
    private boolean matches(int x,int y) {
        return (visited==null || !visited.get(y*document.width+x))
                && Math.abs(document.tone(x,y)-source)<=tolerance;
    }
    private int schedule(int x, int y) {
        int left = x, right = x+1;
        while (left > 0 && matches(left-1,y)) left--;
        while (right < document.width && matches(right,y)) right++;
        if(visited!=null)visited.set(y*document.width+left,y*document.width+right);
        for (int column = left; column < right; column++) paintAt(column,y);
        if (size == spans.length) spans = java.util.Arrays.copyOf(spans,
                Math.min(document.width * document.height, spans.length * 2));
        spans[size++] = ((long)(y * document.width + left) << 32) | (right & 0xffffffffL);
        return right;
    }
    boolean finish() { if (size != 0 || repaint >= 0) throw new IllegalStateException("Fill incomplete"); return document.finish(); }
    void cancel() { document.cancel(); size = 0; repaint = -1; }
}
