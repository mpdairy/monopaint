package dev.tilesmile.supernote;

/** Four-connected scanline fill. Only logical tones are inspected, never display dots. */
final class FloodFill {
    private final ToneDocument document;
    private final int source, target, tolerance;
    private final java.util.BitSet visited;
    private long[] spans;
    private int size;
    FloodFill(ToneDocument document, int x, int y, int target) {
        this(document,x,y,target,0);
    }
    FloodFill(ToneDocument document, int x, int y, int target, int tolerancePercent) {
        if(tolerancePercent<0||tolerancePercent>100)throw new IllegalArgumentException("Invalid fill tolerance");
        this.document = document; this.target = target;
        tolerance=Math.round(tolerancePercent*255f/100);
        visited=tolerance==0?null:new java.util.BitSet(document.width*document.height);
        source = document.tone(x,y); document.begin();
        spans = new long[Math.min(256, document.width * document.height)];
        if (source != target || tolerance>0) schedule(x,y);
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
        return size == 0;
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
        for (int column = left; column < right; column++) document.setTone(column,y,target);
        if (size == spans.length) spans = java.util.Arrays.copyOf(spans,
                Math.min(document.width * document.height, spans.length * 2));
        spans[size++] = ((long)(y * document.width + left) << 32) | (right & 0xffffffffL);
        return right;
    }
    boolean finish() { if (size != 0) throw new IllegalStateException("Fill incomplete"); return document.finish(); }
    void cancel() { document.cancel(); size = 0; }
}
