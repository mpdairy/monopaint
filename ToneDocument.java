package io.github.mpdairy.monopaint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoritative 8-bit tones, including black stipples deposited by watercolor.
 * Display dithering is derived from these tones. Confined to its owner thread. */
final class ToneDocument {
    static final int MAX_LAYERS = 10;
    static final int MAX_PIXELS = 1920 * 2560;
    private static final int TILE = 64;
    private static final int HISTORY_BYTES = 32 * 1024 * 1024;
    int width, height;
    private byte[] tones, alpha;
    /** The last tile {@link #baseTile} looked up, so a dab's pixels skip the map; reset with {@link #before}. */
    private byte[] lastBaseTile;
    private int lastBaseKey=-1;
    /**
     * Pixels this gesture's {@link #transparentMask} has fully glazed with {@link #glazedGray},
     * one bit each, a row of a tile per long. Overlapping dabs would only lay the same glaze again.
     */
    private final HashMap<Integer,long[]> glazed=new HashMap<>();
    private int glazedGray=-1;
    private final ArrayList<Layer> layers = new ArrayList<>();
    private int active;
    /**
     * Masking fluid over the whole page, or null: its darkness shields every layer from paint,
     * erasing and wet paint seeping in. It is never part of the artwork.
     */
    private Layer mask;
    /** Gestures paint the masking fluid instead of the active layer; see {@link #paintMaskingFluid}. */
    private boolean maskTarget;
    /** The layer {@link #tones} and {@link #alpha} belong to: the active one, or the mask during a masking gesture. */
    private Layer bound;
    private long revision;
    long revision() { return revision; }
    static final class Layer {
        String name;
        boolean visible;
        int opacity=100;
        byte[] tones, alpha;
        boolean shared;
        Layer(String name, boolean visible, byte[] tones, byte[] alpha) {
            this.name=name; this.visible=visible; this.tones=tones; this.alpha=alpha;
        }
        // Saves own immutable array references. Only a subsequently edited layer is copied.
        Layer copy() { shared=true; Layer copy=new Layer(name,visible,tones,alpha); copy.opacity=opacity; return copy; }
    }
    static final class Snapshot {
        final int width, height, active;
        final ArrayList<Layer> layers = new ArrayList<>();
        /** The masking fluid, or null when the page has none. */
        final Layer mask;
        Snapshot(ToneDocument document) {
            width=document.width; height=document.height; active=document.active;
            for(Layer layer:document.layers) layers.add(layer.copy());
            mask=document.hasMask()?document.mask.copy():null;
        }
    }
    Snapshot layerSnapshot() { return new Snapshot(this); }
    int layerCount() { return layers.size(); }
    int activeLayer() { return active; }
    String layerName(int index) { return layers.get(index).name; }
    boolean layerVisible(int index) { return layers.get(index).visible; }
    int layerOpacity(int index) { return layers.get(index).opacity; }
    void setLayerOpacity(int index,int value) {
        idle(); if(value<0||value>100) throw new IllegalArgumentException("Invalid layer opacity");
        if(layers.get(index).opacity==value)return;
        StackState old=new StackState(this); layers.get(index).opacity=value; stackEdit(old);
    }
    private void writable() {
        revision++;
        if(bound.shared) {
            bound.tones=bound.tones.clone(); bound.alpha=bound.alpha.clone(); bound.shared=false;
            bind(bound);
        }
    }
    private void bind(Layer layer) { bound=layer; tones=layer.tones; alpha=layer.alpha; }
    private void idle() { if(editing) throw new IllegalStateException("Finish the current gesture first"); }
    void selectLayer(int index) {
        idle(); Layer layer=layers.get(index); if(active!=index)revision++;
        active=index; bind(layer);
    }
    /** Gestures from now on lay or, erasing, rub off masking fluid instead of painting the active layer. */
    void paintMaskingFluid(boolean value) { idle(); maskTarget=value; }
    /** Whether any masking fluid is on the page. */
    boolean hasMask() {
        if(mask==null) return false;
        for(byte a:mask.alpha) if(a!=0) return true;
        return false;
    }
    /** How strongly masking fluid shields this pixel, 0 to 255: the fluid's darkness. */
    private int shield(int i) { return mask==null?0:255-onWhite(mask.tones[i]&255,mask.alpha[i]&255); }
    /** Peel all the masking fluid off the page as one undoable step; false when there is none. */
    boolean peelMask() {
        idle(); if(!hasMask()) { mask=null; return false; }
        StackState old=new StackState(this); mask=null; stackEdit(old); return true;
    }
    /** Spacing of {@link #maskHatch}'s diagonal lines, and how many pixels across each is, thick enough to read as lines at the panel's resolution. */
    static final int HATCH=12, HATCH_LINE=4;
    /**
     * Diagonal hatching that shows the masking fluid over the painting: black over light
     * paint and white over dark, or -1 where the pixel shows the painting.
     */
    int maskHatch(int x,int y) {
        if(mask==null||(x+y)%HATCH>=HATCH_LINE||shield(y*width+x)<32) return -1;
        return compositeTone(x,y)<128?255:0;
    }
    void addLayer() { addLayer(paper(width,height),new byte[width*height]); }
    /** Adds a layer holding these raw tones and coverage above the active one, and selects it. */
    void addLayer(byte[] layerTones,byte[] layerAlpha) {
        idle(); if(layers.size()>=MAX_LAYERS) throw new IllegalStateException("Each page can have up to "+MAX_LAYERS+" layers.");
        if(layerTones.length!=width*height||layerAlpha.length!=width*height) throw new IllegalArgumentException("Invalid layer pixels");
        StackState old=new StackState(this);
        int number=1;
        while(hasName("Layer "+number)) number++;
        layers.add(active+1,new Layer("Layer "+number,true,layerTones,layerAlpha));
        selectLayer(active+1); stackEdit(old);
    }
    /** Copies the active layer's raw tones and coverage in this rectangle, which must lie on the page. */
    void copyLayer(int x,int y,int w,int h,byte[] layerTones,byte[] layerAlpha) {
        Layer layer=layers.get(active);
        for(int row=0;row<h;row++) {
            System.arraycopy(layer.tones,(y+row)*width+x,layerTones,row*w,w);
            System.arraycopy(layer.alpha,(y+row)*width+x,layerAlpha,row*w,w);
        }
    }
    private boolean hasName(String name) { for(Layer layer:layers) if(layer.name.equals(name)) return true; return false; }
    void removeLayer() {
        idle(); if(layers.size()==1) throw new IllegalStateException("Keep at least one layer.");
        StackState old=new StackState(this); layers.remove(active); selectLayer(Math.min(active,layers.size()-1)); stackEdit(old);
    }
    void moveLayer(int target) {
        idle(); if(target<0||target>=layers.size()||target==active) return;
        StackState old=new StackState(this); Layer layer=layers.remove(active); layers.add(target,layer); selectLayer(target); stackEdit(old);
    }
    void setLayerVisible(int index,boolean visible) {
        idle(); if(layers.get(index).visible==visible) return;
        StackState old=new StackState(this); layers.get(index).visible=visible; stackEdit(old);
    }
    void renameLayer(String name) {
        idle(); name=name.trim(); if(name.isEmpty()||name.length()>40) throw new IllegalArgumentException("Use a layer name from 1 to 40 characters.");
        if(layers.get(active).name.equals(name)) return;
        StackState old=new StackState(this); layers.get(active).name=name; stackEdit(old);
    }
    /** Why the active layer cannot merge down now, in words to show the person, or null when it can. */
    String mergeBlocker() {
        if(active==0) return "There is no layer below to merge into.";
        if(!layers.get(active).visible||!layers.get(active-1).visible) return "Show both layers before merging them.";
        return null;
    }
    /**
     * Merges the active layer into the one below it, as one undoable step. The two become a single layer,
     * named for the lower one and selected, that looks as they did together. Each layer's opacity is
     * applied to its coverage as they join, so the merged layer is fully opaque. Both must be showing,
     * since a hidden layer's marks could neither join the picture nor stay out of it.
     */
    void mergeDown() {
        idle(); String blocked=mergeBlocker(); if(blocked!=null) throw new IllegalStateException(blocked);
        Layer top=layers.get(active),under=layers.get(active-1);
        StackState old=new StackState(this);
        // Fresh arrays: the layers it replaces stay untouched for Undo and for saves in progress.
        byte[] mergedTones=new byte[width*height],mergedAlpha=new byte[width*height];
        for(int i=0;i<mergedTones.length;i++) {
            int pixel=over(under.tones[i]&255,coverage(under,i),top.tones[i]&255,coverage(top,i));
            mergedTones[i]=(byte)(pixel>>>8); mergedAlpha[i]=(byte)pixel;
        }
        layers.set(active-1,new Layer(under.name,true,mergedTones,mergedAlpha)); layers.remove(active);
        selectLayer(active-1); stackEdit(old);
    }
    private void stackEdit(StackState old) {
        revision++;
        addEdit(new Edit(old,new StackState(this))); markAllDirty();
    }
    /** Add transparent paper around every layer without resampling existing pixels. */
    void expand(int left, int top, int right, int bottom) {
        idle();
        if (left<0 || top<0 || right<0 || bottom<0) throw new IllegalArgumentException("Canvas margins cannot be negative");
        long w=(long)width+left+right, h=(long)height+top+bottom;
        if(w>4096 || h>4096) throw new IllegalArgumentException("This canvas is too large to expand further.");
        validateSize((int)w,(int)h);
        if(w==width && h==height)return;
        StackState old=new StackState(this);
        ArrayList<Layer> expanded=new ArrayList<>();
        for(Layer layer:layers) expanded.add(expanded(layer,(int)w,(int)h,left,top));
        Layer fluid=mask==null?null:expanded(mask,(int)w,(int)h,left,top);
        width=(int)w; height=(int)h; layers.clear(); layers.addAll(expanded); mask=fluid;
        captured=new boolean[columns()*((height+TILE-1)/TILE)];
        selectLayer(active); stackEdit(old);
    }
    /** A copy of {@code layer} on a larger transparent canvas, offset by {@code left} and {@code top}. */
    private Layer expanded(Layer layer,int w,int h,int left,int top) {
        byte[] ink=paper(w,h), coverage=new byte[w*h];
        for(int y=0;y<height;y++) {
            System.arraycopy(layer.tones,y*width,ink,(y+top)*w+left,width);
            System.arraycopy(layer.alpha,y*width,coverage,(y+top)*w+left,width);
        }
        Layer next=new Layer(layer.name,layer.visible,ink,coverage); next.opacity=layer.opacity; return next;
    }
    private void markAllDirty() { left=0; top=0; right=width; bottom=height; }
    private void addEdit(Edit edit) {
        for(Edit discarded:redo) historyBytes-=discarded.bytes;
        redo.clear(); undo.addLast(edit); historyBytes+=edit.bytes; trimHistory(HISTORY_BYTES);
    }
    /** Bottom-to-top source-over composition on white paper. Brushes read only the active layer. */
    int compositeTone(int x,int y) {
        int i=y*width+x, result=255;
        for(int n=0;n<layers.size();n++) {
            Layer layer=layers.get(n); if(!layer.visible) continue;
            int a=coverage(layer,i);
            result=((layer.tones[i]&255)*a+result*(255-a)+127)/255;
        }
        return result;
    }
    /** How much of pixel {@code i} the layer covers once its opacity is applied, 0 to 255. */
    private static int coverage(Layer layer,int i) { return ((layer.alpha[i]&255)*layer.opacity+50)/100; }
    int opacity(int x,int y) { return alpha[y*width+x]&255; }
    void eraseTone(int x,int y,float strength) {
        if(x<0||y<0||x>=width||y>=height) return;
        int a=opacity(x,y), removed=Math.max(1,Math.round(a*strength));
        if(strength>0) setPixel(x,y,tones[y*width+x]&255,Math.max(0,a-removed));
    }
    /** Anti-aliased edge pixels, with partial mask alpha, remove only that share of the gesture's original coverage. */
    void eraseMask(int[] mask,int stride,int x,int y,int w,int h) {
        for(int row=0;row<h;row++) for(int col=0;col<w;col++) {
            int c=mask[row*stride+col]>>>24, px=x+col, py=y+row;
            if(c==255) eraseTone(px,py,1);
            else if(c!=0 && px>=0 && py>=0 && px<width && py<height) eraseFromBase(px,py,c/255f);
        }
    }
    private boolean[] captured;
    private final LinkedHashMap<Integer, byte[]> before = new LinkedHashMap<>();
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int historyBytes;
    private boolean editing;
    private int left, top, right, bottom;

    ToneDocument(int width, int height) {
        this(width, height, paper(width, height));
        Arrays.fill(alpha,(byte)0);
    }
    ToneDocument(int width, int height, byte[] source) {
        validateSize(width, height);
        if (source.length != width * height) throw new IllegalArgumentException("Wrong tone count");
        this.width = width; this.height = height;
        byte[] coverage=new byte[source.length]; Arrays.fill(coverage,(byte)255);
        layers.add(new Layer("Layer 1",true,source.clone(),coverage)); selectLayer(0);
        captured = new boolean[columns() * ((height + TILE - 1) / TILE)];
        clearDirty();
    }
    ToneDocument(int width,int height,ArrayList<Layer> source,int active) { this(width,height,source,active,null); }
    /** @param mask the page's masking fluid, or null */
    ToneDocument(int width,int height,ArrayList<Layer> source,int active,Layer mask) {
        validateSize(width,height); this.width=width; this.height=height;
        if(source.isEmpty()||source.size()>MAX_LAYERS) throw new IllegalArgumentException("Invalid layer count");
        ArrayList<Layer> all=new ArrayList<>(source); if(mask!=null) all.add(mask);
        for(Layer layer:all)
            if(layer.tones.length!=width*height||layer.alpha.length!=width*height) throw new IllegalArgumentException("Invalid layer pixels");
        layers.addAll(source); this.mask=mask;
        selectLayer(active); captured=new boolean[columns()*((height+TILE-1)/TILE)]; clearDirty();
    }
    static void validateSize(int width, int height) {
        if (!validSize(width, height)) throw new IllegalArgumentException("Invalid canvas size");
    }
    static boolean validSize(int width, int height) {
        return width > 0 && height > 0 && width <= 4096 && height <= 4096 && (long)width * height <= MAX_PIXELS;
    }
    private static byte[] paper(int width, int height) {
        validateSize(width, height);
        byte[] bytes = new byte[width * height]; Arrays.fill(bytes, (byte)255); return bytes;
    }
    int tone(int x, int y) { int i=y*width+x; return onWhite(tones[i]&255,alpha[i]&255); }
    /** The active layer's tone, even while a gesture lays masking fluid, so fills can mask a painted region. */
    int layerTone(int x, int y) { Layer layer=layers.get(active); int i=y*width+x; return onWhite(layer.tones[i]&255,layer.alpha[i]&255); }
    private static int onWhite(int tone,int alpha) { return (tone*alpha+255*(255-alpha)+127)/255; }
    int strokeBaseTone(int x, int y) {
        int key=(y/TILE)*columns()+x/TILE;
        if(!captured[key]) return tone(x,y);
        int tileWidth=Math.min(TILE,width-(x/TILE)*TILE);
        int offset=(y%TILE)*tileWidth+x%TILE;
        byte[] tile=baseTile(key);
        return onWhite(tile[offset]&255,tile[tile.length/2+offset]&255);
    }
    byte[] snapshot() {
        byte[] result=new byte[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) result[y*width+x]=(byte)compositeTone(x,y);
        return result;
    }
    /** Opaque RGB pixels of the composite, calibrated as in {@link DotPattern#exportGray} or as raw logical tones. */
    int[] exportPixels(boolean calibrated) {
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int g=compositeTone(x,y); if(calibrated) g=DotPattern.exportGray(g);
            pixels[y*width+x]=0xff000000|(g<<16)|(g<<8)|g;
        }
        return pixels;
    }
    void begin() { begin(maskTarget); }
    /** Begins a gesture on the active layer, even while gestures lay masking fluid. */
    void beginLayer() { begin(false); }
    private void begin(boolean fluid) {
        if (editing) throw new IllegalStateException("Finish the current gesture first");
        if(fluid) {
            if(mask==null) mask=new Layer("Masking fluid",true,paper(width,height),new byte[width*height]);
            bind(mask);
        } else if(!layerVisible(active)) throw new IllegalStateException("Show this layer before drawing on it.");
        writable(); editing = true; before.clear();lastBaseTile=null;lastBaseKey=-1;glazed.clear();
    }
    void setTone(int x,int y,int gray) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(gray<0||gray>255) throw new IllegalArgumentException("Invalid tone");
        if(x<0||y<0||x>=width||y>=height||tone(x,y)==gray) return;
        // Tonal tools carry pigment over transparent paper; opaque brushes use paintTone.
        int coverage=Math.max(opacity(x,y),255-gray);
        setPixel(x,y,rawTone(gray,coverage),coverage);
    }
    /**
     * {@link #setTone} for a block of pixels, {@code grays} row by row, {@code w} to a row; a
     * negative gray leaves its pixel, as do those off the page. Wet paint changes this many
     * at once each frame, and the checks for the whole block are made once.
     */
    void setTones(int x,int y,int w,int h,int[] grays) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(mask!=null&&bound!=mask) {
            // Masking fluid shields some of these pixels; each one takes its share.
            for(int row=0;row<h;row++) for(int col=0;col<w;col++) if(grays[row*w+col]>=0) setTone(x+col,y+row,grays[row*w+col]);
            return;
        }
        for(int row=Math.max(0,-y);row<Math.min(h,height-y);row++) for(int col=Math.max(0,-x);col<Math.min(w,width-x);col++) {
            int gray=grays[row*w+col],px=x+col,py=y+row,i=py*width+px;
            if(gray<0) continue;
            if(gray>255) throw new IllegalArgumentException("Invalid tone");
            if(onWhite(tones[i]&255,alpha[i]&255)==gray) continue;
            int coverage=Math.max(alpha[i]&255,255-gray);
            store(px,py,rawTone(gray,coverage),coverage);
        }
    }
    private static int rawTone(int onWhite,int coverage) {
        return coverage==0?255:Math.max(0,Math.min(255,(onWhite*255-255*(255-coverage)+coverage/2)/coverage));
    }
    void glazeTone(int x,int y,int gray) { glazeTone(x,y,gray,255); }
    /** @param edge anti-aliased share of the pixel the glaze covers, out of 255 */
    void glazeTone(int x,int y,int gray,int edge) {
        if(gray==255) return;
        int pixel=strokeBasePixel(x,y),coverage=pixel&255;
        int result=transparentTone(onWhite(pixel>>>8,coverage),gray);
        coverage=255-transparentTone(255-coverage,gray);
        cover(x,y,rawTone(result,coverage),coverage,edge);
    }
    /** Moves the gesture's base tone toward {@code gray} by {@code strength}, covering at least that much. */
    void mixTone(int x,int y,int gray,float strength) { mixTone(x,y,gray,strength,255); }
    void mixTone(int x,int y,int gray,float strength,int edge) {
        int base=strokeBaseTone(x,y),result=Math.round(base+(gray-base)*strength);
        int coverage=Math.max(edge==255?opacity(x,y):strokeBasePixel(x,y)&255,Math.max(Math.round(255*strength),255-result));
        cover(x,y,rawTone(result,coverage),coverage,edge);
    }
    /** The active layer's pixel as this gesture found it: raw tone in the high byte, coverage in the low byte. */
    private int strokeBasePixel(int x,int y) {
        int key=(y/TILE)*columns()+x/TILE,i=y*width+x;
        if(!captured[key]) return (tones[i]&255)<<8|(alpha[i]&255);
        byte[] tile=baseTile(key); int w=Math.min(TILE,width-(x/TILE)*TILE),offset=(y%TILE)*w+x%TILE;
        return (tile[offset]&255)<<8|(tile[tile.length/2+offset]&255);
    }
    /** A captured tile as this gesture found it. Captures never change mid-gesture, so the last one is kept at hand. */
    private byte[] baseTile(int key) {
        if(lastBaseKey!=key||lastBaseTile==null) { lastBaseKey=key; lastBaseTile=before.get(key); }
        return lastBaseTile;
    }
    /**
     * Lays what full contact leaves ({@code gray} at {@code coverage}) on {@code edge}/255 of the pixel,
     * mixing premultiplied with the gesture's base. A partial edge never weakens a stronger contact
     * from an overlapping dab, so smooth strokes do not darken where stamps overlap.
     */
    private void cover(int x,int y,int gray,int coverage,int edge) {
        if(edge>=255) { setPixel(x,y,gray,coverage); return; }
        if(edge<=0||x<0||y<0||x>=width||y>=height) return;
        int base=strokeBasePixel(x,y),baseTone=base>>>8,baseAlpha=base&255,i=y*width+x;
        float t=edge/255f,a=baseAlpha+(coverage-baseAlpha)*t;
        float pigment=baseTone*baseAlpha+(gray*coverage-baseTone*baseAlpha)*t;
        int resultAlpha=Math.round(a),result=resultAlpha==0?255:Math.max(0,Math.min(255,Math.round(pigment/a)));
        int from=onWhite(baseTone,baseAlpha);
        int reach=Math.abs(onWhite(result,resultAlpha)-from)+Math.abs(resultAlpha-baseAlpha);
        if(reach>Math.abs(tone(x,y)-from)+Math.abs(opacity(x,y)-baseAlpha)) setPixel(x,y,result,resultAlpha);
    }
    /** Lays {@code gray} at {@code coverage} over the pixel as it is now, as a separate sheet would lie over it. */
    void overTone(int x,int y,int gray,int coverage) {
        if(x<0||y<0||x>=width||y>=height) return;
        int i=y*width+x, pixel=over(tones[i]&255,alpha[i]&255,gray,coverage);
        if((pixel&255)!=0) setPixel(x,y,pixel>>>8,pixel&255);
    }
    /**
     * {@code gray} at {@code coverage} laid over a pixel of raw tone {@code underTone} and coverage
     * {@code underAlpha}, as one sheet lies over another: the raw tone in the high byte and the coverage
     * in the low byte, a bare pixel (tone 255, coverage 0) where neither covers it.
     */
    private static int over(int underTone,int underAlpha,int gray,int coverage) {
        int kept=underAlpha*(255-coverage), resultAlpha=Math.min(255,coverage+(kept+127)/255);
        if(resultAlpha==0) return 255<<8;
        int pigment=gray*coverage*255+underTone*kept;
        return Math.max(0,Math.min(255,(pigment+resultAlpha*255/2)/(resultAlpha*255)))<<8|resultAlpha;
    }
    /** Strongest pencil contact in this gesture, carrying the chosen pigment and its coverage. */
    void pencilTone(int x,int y,int gray,float strength) { pencilTone(x,y,gray,strength,1); }
    /** @param depth how far from white toward {@code gray} this pixel's paper tooth can ever darken, across gestures */
    void pencilTone(int x,int y,int gray,float strength,float depth) {
        int base=strokeBaseTone(x,y),coverage=strokeBasePixel(x,y)&255;
        int target=Math.round(base+(gray-base)*strength),current=tone(x,y);
        if(gray<base) target=Math.max(target,Math.min(base,Math.round(255+(gray-255)*depth)));
        int resultAlpha=Math.round(coverage+(255-coverage)*strength);
        if((gray<base&&target>current)||(gray>base&&target<current)) return;
        if(target==current&&resultAlpha<=opacity(x,y)) return;
        setPixel(x,y,rawTone(target,resultAlpha),resultAlpha);
    }
    /** Source-over toward {@code gray} on the current pixel, always moving at least one step, like {@link #eraseTone}. */
    void sprayTone(int x,int y,int gray,float strength) {
        if(x<0||y<0||x>=width||y>=height||strength<=0) return;
        strength=Math.min(1,strength);
        int i=y*width+x,tone=tones[i]&255,coverage=alpha[i]&255;
        float a=strength*255+coverage*(1-strength);
        int result=Math.round((gray*strength*255+tone*coverage*(1-strength))/a);
        if(result==tone&&tone!=gray&&coverage>0) result+=gray>tone?1:-1;
        setPixel(x,y,result,Math.min(255,Math.max(coverage+1,Math.round(a))));
    }
    /** Remove coverage from the gesture's original layer, retaining its pigment. */
    void eraseFromBase(int x,int y,float strength) {
        int key=(y/TILE)*columns()+x/TILE,i=y*width+x,coverage=alpha[i]&255;
        if(captured[key]) coverage=strokeBasePixel(x,y)&255;
        int result=Math.round(coverage*(1-Math.max(0,Math.min(1,strength))));
        if(result<opacity(x,y)) setPixel(x,y,tones[i]&255,result);
    }
    /** Transparent color endpoint for fills and shapes; shades remain 0–255. */
    static final int ERASE = -1;
    void paintTone(int x,int y,int gray) { paintTone(x,y,gray,255); }
    /** Replace pigment and coverage; transparent fill endpoints must not paint white. */
    void paintTone(int x,int y,int gray,int coverage) { setPixel(x,y,gray,coverage); }
    /** Paint or erase a scanline for geometric shapes; capture undo tiles once, then fill contiguous pixels. */
    void paintSpan(int x, int end, int y, int gray) {
        if(!editing) throw new IllegalStateException("No active gesture");
        if(gray<ERASE || gray>255) throw new IllegalArgumentException("Invalid pixel");
        x=Math.max(0,x);end=Math.min(width,end);
        if(y<0 || y>=height || x>=end) return;
        if(mask!=null&&bound!=mask) {
            // Masking fluid shields some of these pixels; each one takes its share.
            for(int px=x;px<end;px++) if(gray==ERASE) setPixel(px,y,255,0); else setPixel(px,y,gray,255);
            return;
        }
        writable();
        int rowKey=(y/TILE)*columns();
        for(int column=x/TILE;column<=(end-1)/TILE;column++) {
            int key=rowKey+column;
            if(!captured[key]) { before.put(key,copyTile(key));captured[key]=true; }
        }
        Arrays.fill(tones,y*width+x,y*width+end,(byte)(gray==ERASE?255:gray));
        Arrays.fill(alpha,y*width+x,y*width+end,(byte)(gray==ERASE?0:255));
        left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,end);bottom=Math.max(bottom,y+1);
    }
    /** Restore only pixels removed by a resizing preview, keeping its original undo capture. */
    void restoreSpan(int x,int end,int y) {
        if(!editing) throw new IllegalStateException("No active gesture");
        x=Math.max(0,x);end=Math.min(width,end);
        if(y<0 || y>=height || x>=end)return;
        writable();
        for(int column=x/TILE;column<=(end-1)/TILE;column++) {
            int key=(y/TILE)*columns()+column;
            byte[] tile=before.get(key);
            if(tile==null)continue;
            int tileX=column*TILE,tileWidth=Math.min(TILE,width-tileX);
            int start=Math.max(x,tileX),stop=Math.min(end,tileX+tileWidth);
            int offset=(y%TILE)*tileWidth+start-tileX;
            System.arraycopy(tile,offset,tones,y*width+start,stop-start);
            System.arraycopy(tile,tile.length/2+offset,alpha,y*width+start,stop-start);
        }
        left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,end);bottom=Math.max(bottom,y+1);
    }
    private void setPixel(int x,int y,int gray,int coverage) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(gray<0||gray>255||coverage<0||coverage>255) throw new IllegalArgumentException("Invalid pixel");
        if(x<0||y<0||x>=width||y>=height) return;
        int i=y*width+x;
        int shield=bound==mask?0:shield(i);
        if(shield==255) return;
        if(shield>0) {
            // Only the unmasked share of the change reaches the paint the gesture found.
            int base=strokeBasePixel(x,y),baseTone=base>>>8,baseAlpha=base&255;
            float t=1-shield/255f,a=baseAlpha+(coverage-baseAlpha)*t;
            float pigment=baseTone*baseAlpha+(gray*coverage-baseTone*baseAlpha)*t;
            coverage=Math.round(a); gray=coverage==0?255:Math.max(0,Math.min(255,Math.round(pigment/a)));
        }
        if(coverage==0) gray=255;
        store(x,y,gray,coverage);
    }
    /** Writes a pixel on the page, unless it already holds this, capturing its tile for Undo first. */
    private void store(int x,int y,int gray,int coverage) {
        int i=y*width+x;
        if((tones[i]&255)==gray&&(alpha[i]&255)==coverage) return;
        int key=(y/TILE)*columns()+x/TILE;
        if(!captured[key]) { before.put(key,copyTile(key)); captured[key]=true; }
        writable(); tones[i]=(byte)gray; alpha[i]=(byte)coverage;
        left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x+1); bottom=Math.max(bottom,y+1);
    }
    /** Apply opaque logical raster pixels, generated by the accepted Android circle rasterizer; anti-aliased edges cover partly. */
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int edge = mask[row * stride + col] >>> 24;
            if (edge == 255) paintTone(x + col, y + row, gray);
            else if (edge != 0) cover(x + col, y + row, gray, 255, edge);
        }
    }
    /** Half-strength multiply keeps even black translucent; white is clear water. */
    static int transparentTone(int base, int gray) { return (base * (255 + gray) + 255) / 510; }
    /** One glaze per gesture, independent of overlapping interpolation stamps. */
    void transparentMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        if (gray != glazedGray) { glazed.clear(); glazedGray = gray; }
        long[] bits = null; int bitsKey = -1;
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            int edge = mask[row * stride + col] >>> 24;
            if (edge == 0 || px < 0 || py < 0 || px >= width || py >= height) continue;
            // A partly covered edge may yet be covered more; a fully glazed pixel is final.
            if (edge < 255) { glazeTone(px, py, gray, edge); continue; }
            int key = (py / TILE) * columns() + px / TILE;
            if (key != bitsKey) {
                bitsKey = key; bits = glazed.get(key);
                if (bits == null) glazed.put(key, bits = new long[TILE]);
            }
            long bit = 1L << (px % TILE);
            if ((bits[py % TILE] & bit) != 0) continue;
            bits[py % TILE] |= bit;
            glazeTone(px, py, gray, 255);
        }
    }
    /** Darken to the selected logical gray; repeated passes retain the same shade. */
    void flatWashMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if ((mask[row * stride + col] >>> 24) != 0
                    && px >= 0 && py >= 0 && px < width && py < height)
                setTone(px, py, Math.min(gray, tone(px, py)));
        }
    }
    /** Deposit only the shade's black dots; gaps leave the underlying tones intact. */
    void washMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            if ((mask[row * stride + col] >>> 24) != 0
                    && DotPattern.pixel(gray, x + col, y + row) == 0xff000000)
                setTone(x + col, y + row, 0);
        }
    }
    boolean finish() { return finish(false); }
    /** Animation belongs to the preceding wet stroke, not a separate Undo step. */
    boolean finishContinuation() { return finish(true); }
    private boolean finish(boolean continuation) {
        if (!editing) return false;
        editing = false;lastBaseTile=null;lastBaseKey=-1;glazed.clear();
        ArrayList<TileEdit> tiles = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : before.entrySet()) {
            captured[entry.getKey()] = false;
            byte[] after = copyTile(entry.getKey());
            if (!Arrays.equals(entry.getValue(), after))
                tiles.add(new TileEdit(entry.getKey(), entry.getValue(), after));
        }
        before.clear();
        Layer edited=bound; bind(layers.get(active));
        if (tiles.isEmpty()) return false;
        if (continuation && !undo.isEmpty() && redo.isEmpty() && undo.peekLast().layer==edited) {
            Edit previous = undo.removeLast(); historyBytes -= previous.bytes;
            LinkedHashMap<Integer, TileEdit> merged = new LinkedHashMap<>();
            for (TileEdit tile : previous.tiles) merged.put(tile.key, tile);
            for (TileEdit tile : tiles) {
                TileEdit old = merged.get(tile.key);
                merged.put(tile.key, new TileEdit(tile.key, old == null ? tile.before : old.before, tile.after));
            }
            tiles = new ArrayList<>();
            for (TileEdit tile : merged.values()) if (!Arrays.equals(tile.before, tile.after)) tiles.add(tile);
        }
        for (Edit edit : redo) historyBytes -= edit.bytes;
        redo.clear();
        if (!tiles.isEmpty()) { Edit edit = new Edit(tiles,edited); undo.addLast(edit); historyBytes += edit.bytes; }
        while (historyBytes > HISTORY_BYTES && undo.size() > 1) historyBytes -= undo.removeFirst().bytes;
        return true;
    }
    boolean canUndo() { return !undo.isEmpty(); }
    void trimHistory(int budget) {
        while(historyBytes>budget&&undo.size()>1)historyBytes-=undo.removeFirst().bytes;
        while(historyBytes>budget&&!redo.isEmpty())historyBytes-=redo.removeFirst().bytes;
    }
    /** Clear all disconnected marks as one undoable edit, retaining canvas dimensions. */
    boolean clear() {
        begin(false);
        for(int key=0;key<captured.length;key++) {
            byte[] tile=copyTile(key); boolean marked=false;
            for(int i=tile.length/2;i<tile.length;i++) if(tile[i]!=0) { marked=true; break; }
            if(marked) { before.put(key,tile); captured[key]=true; }
        }
        if(!before.isEmpty()) {
            Arrays.fill(tones,(byte)255); Arrays.fill(alpha,(byte)0); markAllDirty();
        }
        return finish();
    }
    /** Clear the current page, including hidden layers, as one structural undo step. */
    boolean clearAllLayers() {
        idle(); boolean marked=hasMask();
        for(Layer layer:layers)for(byte a:layer.alpha)if(a!=0) {marked=true;break;}
        if(!marked)return false;
        StackState old=new StackState(this); mask=null;
        for(int i=0;i<layers.size();i++) {
            Layer prior=layers.get(i),blank=new Layer(prior.name,prior.visible,paper(width,height),new byte[width*height]);
            blank.opacity=prior.opacity; layers.set(i,blank);
        }
        selectLayer(active); stackEdit(old); return true;
    }
    void cancel() {
        if (!editing) return;
        for (Map.Entry<Integer, byte[]> tile : before.entrySet()) {
            restoreTile(tile.getKey(), tile.getValue()); captured[tile.getKey()] = false;
        }
        before.clear(); lastBaseTile=null; lastBaseKey=-1; glazed.clear(); editing = false;
        bind(layers.get(active));
    }
    boolean canRedo() { return !redo.isEmpty(); }
    boolean undo() { return travel(undo, redo, false); }
    boolean redo() { return travel(redo, undo, true); }
    private boolean travel(ArrayDeque<Edit> source, ArrayDeque<Edit> target, boolean forward) {
        if (editing) throw new IllegalStateException("Finish the current gesture first");
        if (source.isEmpty()) return false;
        Edit edit = source.removeLast();
        if(edit.layer==null) { (forward?edit.next:edit.previous).restore(this); revision++; markAllDirty(); }
        else {
            int index=layers.indexOf(edit.layer);
            // Any other layer is masking fluid, laid since the page last had none.
            if(index<0) { mask=edit.layer; bind(mask); } else selectLayer(index);
            for (TileEdit tile : edit.tiles) restoreTile(tile.key, forward ? tile.after : tile.before);
            if(index<0) bind(layers.get(active));
        }
        target.addLast(edit); return true;
    }
    /** Returns [left, top, right, bottom], without clearing it. */
    int[] dirty() { return right > left && bottom > top ? new int[]{left, top, right, bottom} : null; }
    void clearDirty() { left = width; top = height; right = bottom = 0; }
    void render(int[] pixels, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int hatch = maskHatch(x + col, y + row);
            pixels[row * w + col] = hatch >= 0 ? (hatch == 0 ? 0xff000000 : 0xffffffff)
                    : DotPattern.pixel(compositeTone(x + col, y + row), x + col, y + row);
        }
    }
    private int columns() { return (width + TILE - 1) / TILE; }
    private byte[] copyTile(int key) {
        int x = key % columns() * TILE, y = key / columns() * TILE;
        int w = Math.min(TILE, width - x), h = Math.min(TILE, height - y);
        byte[] copy = new byte[w * h * 2];
        for (int row = 0; row < h; row++) {
            System.arraycopy(tones, (y + row) * width + x, copy, row * w, w);
            System.arraycopy(alpha, (y + row) * width + x, copy, w*h+row*w, w);
        }
        return copy;
    }
    private void restoreTile(int key, byte[] data) {
        writable();
        int x = key % columns() * TILE, y = key / columns() * TILE;
        int w = Math.min(TILE, width - x), h = Math.min(TILE, height - y);
        for (int row = 0; row < h; row++) {
            System.arraycopy(data, row * w, tones, (y + row) * width + x, w);
            System.arraycopy(data,w*h+row*w,alpha,(y+row)*width+x,w);
        }
        left = Math.min(left, x); top = Math.min(top, y);
        right = Math.max(right, x + w); bottom = Math.max(bottom, y + h);
    }
    private static final class TileEdit {
        final int key; final byte[] before, after;
        TileEdit(int key, byte[] before, byte[] after) { this.key = key; this.before = before; this.after = after; }
    }
    private static final class StackState {
        final ArrayList<Layer> layers;
        final Layer mask;
        final String[] names;
        final boolean[] visible;
        final int[] opacity;
        final int active, width, height;
        StackState(ToneDocument doc) {
            layers=new ArrayList<>(doc.layers); mask=doc.mask; active=doc.active; width=doc.width; height=doc.height;
            names=new String[layers.size()]; visible=new boolean[layers.size()]; opacity=new int[layers.size()];
            for(int i=0;i<layers.size();i++) { names[i]=layers.get(i).name; visible[i]=layers.get(i).visible; opacity[i]=layers.get(i).opacity; }
        }
        void restore(ToneDocument doc) {
            doc.width=width; doc.height=height;
            doc.captured=new boolean[doc.columns()*((height+TILE-1)/TILE)];
            doc.layers.clear(); doc.layers.addAll(layers); doc.mask=mask;
            for(int i=0;i<layers.size();i++) { layers.get(i).name=names[i]; layers.get(i).visible=visible[i]; layers.get(i).opacity=opacity[i]; }
            doc.selectLayer(active);
        }
    }
    private static final class Edit {
        final ArrayList<TileEdit> tiles; final int bytes;
        final Layer layer;
        final StackState previous,next;
        Edit(StackState previous,StackState next) {
            this.previous=previous; this.next=next; layer=null; tiles=null;
            int count=256;
            for(Layer layer:previous.layers) if(!next.layers.contains(layer)) count+=layer.tones.length*2;
            for(Layer layer:next.layers) if(!previous.layers.contains(layer)) count+=layer.tones.length*2;
            if(previous.mask!=next.mask) count+=(previous.mask!=null?previous.mask.tones.length*2:0)+(next.mask!=null?next.mask.tones.length*2:0);
            bytes=count;
        }
        Edit(ArrayList<TileEdit> tiles,Layer layer) {
            this.layer=layer; previous=next=null;
            this.tiles = tiles; int count = 0;
            for (TileEdit tile : tiles) count += tile.before.length + tile.after.length;
            bytes = count;
        }
    }
}
