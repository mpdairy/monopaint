package io.github.mpdairy.monopaint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoritative 8-bit tones, including black stipples deposited by watercolor.
 * Display dithering is derived from these tones. Confined to its owner thread. */
final class ToneDocument {
    static final int MAX_LAYERS = 8;
    static final int MAX_PIXELS = 1920 * 2560;
    private static final int TILE = 64;
    private static final int HISTORY_BYTES = 32 * 1024 * 1024;
    final int width, height;
    private byte[] tones, alpha;
    private byte[] sprayBaseTile;
    private int sprayBaseKey=-1;
    private final ArrayList<Layer> layers = new ArrayList<>();
    private int active;
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
        Snapshot(ToneDocument document) {
            width=document.width; height=document.height; active=document.active;
            for(Layer layer:document.layers) layers.add(layer.copy());
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
        Layer layer=layers.get(active);
        if(layer.shared) {
            layer.tones=layer.tones.clone(); layer.alpha=layer.alpha.clone(); layer.shared=false;
            tones=layer.tones; alpha=layer.alpha;
        }
    }
    private void idle() { if(editing) throw new IllegalStateException("Finish the current gesture first"); }
    void selectLayer(int index) {
        idle(); Layer layer=layers.get(index); if(active!=index)revision++;
        active=index; tones=layer.tones; alpha=layer.alpha;
    }
    void addLayer() {
        idle(); if(layers.size()>=MAX_LAYERS) throw new IllegalStateException("Each page can have up to "+MAX_LAYERS+" layers.");
        StackState old=new StackState(this);
        int number=1;
        while(hasName("Layer "+number)) number++;
        layers.add(active+1,new Layer("Layer "+number,true,paper(width,height),new byte[width*height]));
        selectLayer(active+1); stackEdit(old);
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
    private void stackEdit(StackState old) {
        revision++;
        addEdit(new Edit(old,new StackState(this))); markAllDirty();
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
            int a=((layer.alpha[i]&255)*layer.opacity+50)/100;
            result=((layer.tones[i]&255)*a+result*(255-a)+127)/255;
        }
        return result;
    }
    int opacity(int x,int y) { return alpha[y*width+x]&255; }
    void eraseTone(int x,int y,float strength) {
        if(x<0||y<0||x>=width||y>=height) return;
        int a=opacity(x,y), removed=Math.max(1,Math.round(a*strength));
        if(strength>0) setPixel(x,y,tones[y*width+x]&255,Math.max(0,a-removed));
    }
    void eraseMask(int[] mask,int stride,int x,int y,int w,int h) {
        for(int row=0;row<h;row++) for(int col=0;col<w;col++)
            if((mask[row*stride+col]>>>24)!=0) eraseTone(x+col,y+row,1);
    }
    private final boolean[] captured;
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
    ToneDocument(int width,int height,ArrayList<Layer> source,int active) {
        validateSize(width,height); this.width=width; this.height=height;
        if(source.isEmpty()||source.size()>MAX_LAYERS) throw new IllegalArgumentException("Invalid layer count");
        for(Layer layer:source) {
            if(layer.tones.length!=width*height||layer.alpha.length!=width*height) throw new IllegalArgumentException("Invalid layer pixels");
            layers.add(layer);
        }
        selectLayer(active); captured=new boolean[columns()*((height+TILE-1)/TILE)]; clearDirty();
    }
    static void validateSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long)width * height > MAX_PIXELS) throw new IllegalArgumentException("Invalid canvas size");
    }
    private static byte[] paper(int width, int height) {
        validateSize(width, height);
        byte[] bytes = new byte[width * height]; Arrays.fill(bytes, (byte)255); return bytes;
    }
    int tone(int x, int y) { int i=y*width+x; return onWhite(tones[i]&255,alpha[i]&255); }
    private static int onWhite(int tone,int alpha) { return (tone*alpha+255*(255-alpha)+127)/255; }
    int strokeBaseTone(int x, int y) {
        int key=(y/TILE)*columns()+x/TILE;
        if(!captured[key]) return tone(x,y);
        int tileWidth=Math.min(TILE,width-(x/TILE)*TILE);
        int offset=(y%TILE)*tileWidth+x%TILE;
        byte[] tile=before.get(key);
        return onWhite(tile[offset]&255,tile[tile.length/2+offset]&255);
    }
    byte[] snapshot() {
        byte[] result=new byte[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) result[y*width+x]=(byte)compositeTone(x,y);
        return result;
    }
    void begin() {
        if (editing) throw new IllegalStateException("Finish the current gesture first");
        if(!layerVisible(active)) throw new IllegalStateException("Show this layer before drawing on it.");
        writable(); editing = true; before.clear();sprayBaseTile=null;sprayBaseKey=-1;
    }
    void setTone(int x,int y,int gray) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(gray<0||gray>255) throw new IllegalArgumentException("Invalid tone");
        if(x<0||y<0||x>=width||y>=height||tone(x,y)==gray) return;
        // Tonal tools carry pigment over transparent paper; opaque brushes use paintTone.
        int coverage=Math.max(opacity(x,y),255-gray);
        setPixel(x,y,rawTone(gray,coverage),coverage);
    }
    private static int rawTone(int onWhite,int coverage) {
        return coverage==0?255:Math.max(0,Math.min(255,(onWhite*255-255*(255-coverage)+coverage/2)/coverage));
    }
    void glazeTone(int x,int y,int gray) {
        if(gray==255) return;
        int key=(y/TILE)*columns()+x/TILE;
        int base=strokeBaseTone(x,y),coverage=opacity(x,y);
        if(captured[key]) {
            byte[] tile=before.get(key); int w=Math.min(TILE,width-(x/TILE)*TILE);
            coverage=tile[tile.length/2+(y%TILE)*w+x%TILE]&255;
        }
        int result=transparentTone(base,gray);
        coverage=255-transparentTone(255-coverage,gray);
        setPixel(x,y,rawTone(result,coverage),coverage);
    }
    /** Moves the gesture's base tone toward {@code gray} by {@code strength}, covering at least that much. */
    void mixTone(int x,int y,int gray,float strength) {
        int base=strokeBaseTone(x,y),result=Math.round(base+(gray-base)*strength);
        int coverage=Math.max(opacity(x,y),Math.max(Math.round(255*strength),255-result));
        setPixel(x,y,rawTone(result,coverage),coverage);
    }
    /** Strongest pencil contact in this gesture, carrying the chosen pigment and its coverage. */
    void pencilTone(int x,int y,int gray,float strength) {
        int key=(y/TILE)*columns()+x/TILE,base=strokeBaseTone(x,y),coverage=opacity(x,y);
        if(captured[key]) {
            byte[] tile=before.get(key); int w=Math.min(TILE,width-(x/TILE)*TILE);
            coverage=tile[tile.length/2+(y%TILE)*w+x%TILE]&255;
        }
        int target=Math.round(base+(gray-base)*strength),current=tone(x,y);
        int resultAlpha=Math.round(coverage+(255-coverage)*strength);
        if((gray<base&&target>current)||(gray>base&&target<current)) return;
        if(target==current&&resultAlpha<=opacity(x,y)) return;
        setPixel(x,y,rawTone(target,resultAlpha),resultAlpha);
    }
    /** Cumulative source-over spray, always recomputed from the untouched stroke base. */
    void sprayTone(int x,int y,int gray,float strength) {
        int key=(y/TILE)*columns()+x/TILE;
        int i=y*width+x,base=tones[i]&255,coverage=alpha[i]&255;
        if(captured[key]) {
            if(sprayBaseKey!=key || sprayBaseTile==null) {sprayBaseKey=key;sprayBaseTile=before.get(key);}
            int w=Math.min(TILE,width-(x/TILE)*TILE),offset=(y%TILE)*w+x%TILE;
            base=sprayBaseTile[offset]&255;coverage=sprayBaseTile[sprayBaseTile.length/2+offset]&255;
        }
        float a=strength*255+coverage*(1-strength);
        int result=a==0?255:Math.round((gray*strength*255+base*coverage*(1-strength))/a);
        setPixel(x,y,result,Math.round(a));
    }
    /** Remove coverage from the gesture's original layer, retaining its pigment. */
    void eraseFromBase(int x,int y,float strength) {
        int key=(y/TILE)*columns()+x/TILE,i=y*width+x,coverage=alpha[i]&255;
        if(captured[key]) {
            if(sprayBaseKey!=key || sprayBaseTile==null) {sprayBaseKey=key;sprayBaseTile=before.get(key);}
            int w=Math.min(TILE,width-(x/TILE)*TILE),offset=(y%TILE)*w+x%TILE;
            coverage=sprayBaseTile[sprayBaseTile.length/2+offset]&255;
        }
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
        if(coverage==0) gray=255;
        int i=y*width+x;
        if((tones[i]&255)==gray&&(alpha[i]&255)==coverage) return;
        int key=(y/TILE)*columns()+x/TILE;
        if(!captured[key]) { before.put(key,copyTile(key)); captured[key]=true; }
        writable(); tones[i]=(byte)gray; alpha[i]=(byte)coverage;
        left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x+1); bottom=Math.max(bottom,y+1);
    }
    /** Apply opaque logical raster pixels, generated by the accepted Android circle rasterizer. */
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            if ((mask[row * stride + col] >>> 24) != 0) paintTone(x + col, y + row, gray);
        }
    }
    /** Half-strength multiply keeps even black translucent; white is clear water. */
    static int transparentTone(int base, int gray) { return (base * (255 + gray) + 255) / 510; }
    /** One glaze per gesture, independent of overlapping interpolation stamps. */
    void transparentMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if ((mask[row * stride + col] >>> 24) != 0
                    && px >= 0 && py >= 0 && px < width && py < height)
                glazeTone(px, py, gray);
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
        editing = false;sprayBaseTile=null;sprayBaseKey=-1;
        ArrayList<TileEdit> tiles = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : before.entrySet()) {
            captured[entry.getKey()] = false;
            byte[] after = copyTile(entry.getKey());
            if (!Arrays.equals(entry.getValue(), after))
                tiles.add(new TileEdit(entry.getKey(), entry.getValue(), after));
        }
        before.clear();
        if (tiles.isEmpty()) return false;
        if (continuation && !undo.isEmpty() && redo.isEmpty() && undo.peekLast().layer==layers.get(active)) {
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
        if (!tiles.isEmpty()) { Edit edit = new Edit(tiles,layers.get(active)); undo.addLast(edit); historyBytes += edit.bytes; }
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
        begin();
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
        idle(); boolean marked=false;
        for(Layer layer:layers)for(byte a:layer.alpha)if(a!=0) {marked=true;break;}
        if(!marked)return false;
        StackState old=new StackState(this);
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
        before.clear(); editing = false;
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
            selectLayer(layers.indexOf(edit.layer));
            for (TileEdit tile : edit.tiles) restoreTile(tile.key, forward ? tile.after : tile.before);
        }
        target.addLast(edit); return true;
    }
    /** Returns [left, top, right, bottom], without clearing it. */
    int[] dirty() { return right > left && bottom > top ? new int[]{left, top, right, bottom} : null; }
    void clearDirty() { left = width; top = height; right = bottom = 0; }
    void render(int[] pixels, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++)
            pixels[row * w + col] = DotPattern.pixel(compositeTone(x + col, y + row), x + col, y + row);
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
        final String[] names;
        final boolean[] visible;
        final int[] opacity;
        final int active;
        StackState(ToneDocument doc) {
            layers=new ArrayList<>(doc.layers); active=doc.active;
            names=new String[layers.size()]; visible=new boolean[layers.size()]; opacity=new int[layers.size()];
            for(int i=0;i<layers.size();i++) { names[i]=layers.get(i).name; visible[i]=layers.get(i).visible; opacity[i]=layers.get(i).opacity; }
        }
        void restore(ToneDocument doc) {
            doc.layers.clear(); doc.layers.addAll(layers);
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
