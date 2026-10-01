package io.github.mpdairy.monopaint;

import java.io.*;
import java.util.Arrays;

public final class ShapeChecks {
    public static void main(String[] args) throws Exception {
        centeredCircles(); incrementalPreviews();
        for(ToolSettings.Shape shape:ToolSettings.Shape.values()) for(boolean filled:new boolean[]{false,true}) {
            ToneDocument doc=new ToneDocument(160,160);
            doc.begin();doc.paintTone(30,30,91);doc.finish();doc.addLayer();
            byte[] original=doc.snapshot();
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(shape).filled(filled).outlineWidth(4);
            ShapeStroke stroke=new ShapeStroke(doc,settings,70,20,20);
            stroke.preview(140,140);stroke.preview(80,100);
            check(doc.opacity(130,130)==0,"Resizing removes old preview "+shape);
            if(shape!=ToolSettings.Shape.LINE) {
                check(doc.opacity(40,50)==(filled?255:0),"Filled / hollow center "+shape);
                if(filled)check(doc.tone(40,50)==70,"Filled uses only selected shade");
            }
            check(stroke.finish(),"Shape commits "+shape);
            byte[] result=doc.snapshot();
            check(doc.undo() && Arrays.equals(original,doc.snapshot()),"One undo restores composite "+shape);
            check(doc.redo() && Arrays.equals(result,doc.snapshot()),"Redo restores shape "+shape);
            stroke=new ShapeStroke(doc,settings,255,10,10);stroke.preview(150,150);stroke.cancel();
            check(Arrays.equals(result,doc.snapshot()),"Cancel restores preview "+shape);
            doc.selectLayer(0);check(doc.tone(30,30)==91,"Underlying layer unchanged");
        }
        for(ToolSettings.Shape shape:new ToolSettings.Shape[]{ToolSettings.Shape.SQUARE}) {
            for(int sx:new int[]{-1,1}) for(int sy:new int[]{-1,1}) {
                ToneDocument doc=new ToneDocument(160,160);
                ShapeStroke stroke=new ShapeStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(shape).filled(true),0,80,80);
                stroke.preview(80+sx*30,80+sy*50);stroke.finish();
                int l=160,t=160,r=0,b=0;
                for(int y=0;y<160;y++)for(int x=0;x<160;x++)if(doc.opacity(x,y)>0){l=Math.min(l,x);t=Math.min(t,y);r=Math.max(r,x);b=Math.max(b,y);}
                check(r-l==b-t && r-l==49,"Constrained shape stays square in all drag directions");
            }
        }
        ToneDocument doc=new ToneDocument(100,100);
        ShapeStroke line=new ShapeStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SHAPES).outlineWidth(1),0,10.5f,10.5f);
        line.preview(90.5f,90.5f);line.finish();
        for(int i=11;i<90;i++)check(doc.tone(i,i)==0,"One pixel diagonal has no gaps");
        ShapeStroke clipped=new ShapeStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(ToolSettings.Shape.CIRCLE).filled(true),255,90,90);
        clipped.preview(-500,800);clipped.finish();
        check(doc.opacity(50,95)==255 && doc.tone(50,95)==255,"Clipped opaque white covers layer");
        doc.setLayerVisible(0,false);
        try {new ShapeStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SHAPES),0,10,10);throw new AssertionError("Hidden layer accepted");} catch(IllegalStateException expected) {}
        ToneDocument spans=new ToneDocument(137,91),pixels=new ToneDocument(137,91);
        java.util.Random random=new java.util.Random(719);
        spans.begin();pixels.begin();
        for(int i=0;i<300;i++) {
            int x=random.nextInt(180)-20,end=x+random.nextInt(170),y=random.nextInt(110)-10,tone=random.nextInt(256);
            spans.paintSpan(x,end,y,tone);
            for(int column=x;column<end;column++)pixels.paintTone(column,y,tone);
        }
        spans.finish();pixels.finish();
        check(Arrays.equals(DrawingBook.encode(spans),DrawingBook.encode(pixels)),"Fast clipped scanlines match pixel painting including alpha");
        check(spans.undo()&&pixels.undo()&&Arrays.equals(DrawingBook.encode(spans),DrawingBook.encode(pixels)),"Scanline undo matches pixel undo");
        ToolLibrary library=new ToolLibrary();library.select(ToolSettings.Tool.SHAPES);
        library.edit(library.current().shape(ToolSettings.Shape.OVAL).filled(true).outlineWidth(17));
        ToolSettings regular=library.current();ToolLibrary.Preset preset=library.add();
        library.edit(library.current().shape(ToolSettings.Shape.SQUARE).filled(false).outlineWidth(1).size(34));
        ToolLibrary restored=ToolLibrary.decode(library.encode());
        check(restored.current().equals(library.current()) && restored.activeId().equals(preset.id),"Shape favorite round trip");
        check(restored.builtin(ToolSettings.Tool.SHAPES).equals(regular),"Regular shape stays independent");
        // Actual immediately preceding format: nine tools and 48-byte settings records.
        library=new ToolLibrary();library.select(ToolSettings.Tool.AIRBRUSH);library.edit(library.current().strength(72));library.add("Spray");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(library.encode()));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        in.readInt();out.writeInt(0x5453503f);byte[] record=new byte[48];
        for(int i=0;i<23;i++){in.readFully(record);if(i!=9)out.write(record);in.skipBytes(6);}
        out.writeUTF(in.readUTF());int count=in.readInt();out.writeInt(count);
        for(int i=0;i<count;i++){out.writeUTF(in.readUTF());out.writeUTF(in.readUTF());in.readFully(record);out.write(record);in.skipBytes(6);}
        check(in.read()==-1,"Old format fixture consumes every byte");
        restored=ToolLibrary.decode(bytes.toByteArray());
        check(restored.current().equals(library.current()) && restored.activeId().equals(library.activeId()),"Previous version retains selected favorite");
        check(restored.builtin(ToolSettings.Tool.SHAPES).equals(ToolSettings.defaults(ToolSettings.Tool.SHAPES)),"Migration adds default Shapes");
        System.out.println("PASS: five shapes, solid/outline, resize preview, layer isolation, white coverage, undo/redo, cancellation, constraints, clipping, settings, favorites and previous-format migration");
    }
    private static void centeredCircles() {
        for(boolean filled:new boolean[]{false,true}) {
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(ToolSettings.Shape.CIRCLE).filled(filled).outlineWidth(4);
            byte[] reference=null;
            for(int sx:new int[]{-1,1})for(int sy:new int[]{-1,1}) {
                ToneDocument doc=new ToneDocument(160,160);
                ShapeStroke stroke=new ShapeStroke(doc,settings,40,80,80);
                stroke.preview(80+sx*30,80+sy*40);stroke.finish(); // 3–4–5 diagonal means radius 50.
                check(doc.opacity(80,80)==(filled?255:0),"Pen-down stays the filled/hollow center");
                for(int[] point:new int[][]{{32,80},{127,80},{80,32},{80,127}})
                    check(doc.tone(point[0],point[1])==40,"Radius extends equally in every direction");
                check(doc.opacity(29,80)==0&&doc.opacity(130,80)==0,"Radius is Euclidean distance to the pen");
                if(reference==null)reference=doc.snapshot();else check(Arrays.equals(reference,doc.snapshot()),"Equal radii in all quadrants give identical circles");
                check(doc.undo()&&!doc.canUndo()&&doc.redo(),"Centered circle commits as one undo");
            }
            ToneDocument doc=new ToneDocument(160,160);ShapeStroke stroke=new ShapeStroke(doc,settings,0,80,80);
            stroke.preview(130,80);byte[] horizontal=doc.snapshot();stroke.preview(80,130);
            check(Arrays.equals(horizontal,doc.snapshot()),"Horizontal and vertical drags use the same radius");
            stroke.preview(80,80);check(doc.opacity(127,80)==0,"Returning to center removes the preview");
            check(!stroke.finish()&&!doc.canUndo(),"Zero-radius circle adds no mark or history");
        }
        System.out.println("PASS: center-anchored circles, Euclidean radius, horizontal/vertical/diagonal drags, all quadrants, filled/outline, shrinking to zero and one-step undo");
    }
    private static void incrementalPreviews() throws Exception {
        java.util.Random random=new java.util.Random(8231);
        ToneDocument base=new ToneDocument(137,113);
        base.begin();
        for(int y=0;y<base.height;y++)for(int x=0;x<base.width;x++) {
            base.paintTone(x,y,(x*3+y*7)%256);
            if((x+y)%3==0)base.eraseTone(x,y,.6f);
        }
        base.finish();byte[] encoded=DrawingBook.encode(base);
        for(ToolSettings.Shape kind:ToolSettings.Shape.values())for(boolean filled:new boolean[]{false,true}) {
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(kind).filled(filled).outlineWidth(7);
            ToneDocument doc=DocumentCodec.read(new ByteArrayInputStream(encoded));
            ShapeStroke stroke=new ShapeStroke(doc,settings,255,68.25f,56.75f);
            for(int i=0;i<60;i++) {
                float x=random.nextInt(190)-25+.25f,y=random.nextInt(160)-25+.75f;
                ToneDocument.Snapshot before=doc.layerSnapshot();
                stroke.preview(x,y);
                java.util.List<int[]> damage=stroke.drainDirty();
                ToneDocument expected=DocumentCodec.read(new ByteArrayInputStream(encoded));
                ShapeStroke once=new ShapeStroke(expected,settings,255,68.25f,56.75f);once.preview(x,y);once.finish();
                ToneDocument.Snapshot actual=doc.layerSnapshot(), reference=expected.layerSnapshot();
                check(Arrays.equals(actual.layers.get(0).tones,reference.layers.get(0).tones)
                        && Arrays.equals(actual.layers.get(0).alpha,reference.layers.get(0).alpha),"Incremental preview matches fresh raster: "+kind);
                boolean[] covered=new boolean[doc.width*doc.height];
                for(int[] r:damage)for(int row=r[1];row<r[3];row++)for(int col=r[0];col<r[2];col++)covered[row*doc.width+col]=true;
                for(int pixel=0;pixel<covered.length;pixel++) {
                    boolean changed=before.layers.get(0).tones[pixel]!=actual.layers.get(0).tones[pixel]
                            || before.layers.get(0).alpha[pixel]!=actual.layers.get(0).alpha[pixel];
                    check(!changed || covered[pixel],"Sparse damage includes every changed pixel");
                }
                stroke.preview(x,y);check(stroke.drainDirty().isEmpty(),"Stationary preview causes no work");
            }
            stroke.cancel();check(Arrays.equals(encoded,DrawingBook.encode(doc)),"Incremental cancellation restores partial coverage and color");
        }
        for(boolean filled:new boolean[]{false,true}) {
            ToneDocument doc=new ToneDocument(1800,2400);
            ShapeStroke stroke=new ShapeStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SHAPES).shape(ToolSettings.Shape.RECTANGLE).filled(filled),0,100,100);
            stroke.preview(1500,2000);stroke.drainDirty();stroke.preview(1506,2005);
            long area=0;for(int[] r:stroke.drainDirty())area+=(long)(r[2]-r[0])*(r[3]-r[1]);
            check(area<80000,"Small rectangle resize must not redraw its multi-million-pixel interior");stroke.cancel();
        }
        DirtyRegions damage=new DirtyRegions(12);boolean[] required=new boolean[100*100];
        for(int i=0;i<200;i++) { int x=random.nextInt(99),y=random.nextInt(99);damage.add(x,y,x+2,y+2);for(int a=y;a<y+2;a++)for(int b=x;b<x+2;b++)required[a*100+b]=true; }
        java.util.List<int[]> patches=damage.drain();check(patches.size()<=12,"Damage queue is bounded");
        for(int[] r:patches)for(int y=r[1];y<r[3];y++)for(int x=r[0];x<r[2];x++)required[y*100+x]=false;
        for(boolean missing:required)check(!missing,"Coalescing retains all queued damage");
        System.out.println("PASS: incremental previews across quadrants/edges preserve tones and alpha, exact cancellation, complete bounded damage, and sparse rectangle updates");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
