package io.github.mpdairy.monopaint;

/** Logical pixel operations, independent of display dithering. */
final class ToneDabs {
    static void softErase(ToneDocument doc, float cx, float cy, float radius) {
        erase(doc,cx,cy,radius,100,1);
    }
    static void erase(ToneDocument doc, float cx, float cy, float radius, int softness, float pressure) {
        soft(doc,cx,cy,radius,softness,pressure,1,ToneDocument.ERASE);
    }
    /**
     * A round dab that fades toward its edge over {@code softness}%. It erases when {@code gray}
     * is {@link ToneDocument#ERASE}, otherwise sprays {@code gray}; {@code flow} scales its strength.
     */
    static void soft(ToneDocument doc, float cx, float cy, float radius, int softness, float pressure, float flow, int gray) {
        int l = Math.max(0,(int)Math.floor(cx-radius)), t = Math.max(0,(int)Math.floor(cy-radius));
        int r = Math.min(doc.width,(int)Math.ceil(cx+radius)), b = Math.min(doc.height,(int)Math.ceil(cy+radius));
        for (int y=t;y<b;y++) for (int x=l;x<r;x++) {
            float q = ((x+.5f-cx)*(x+.5f-cx)+(y+.5f-cy)*(y+.5f-cy))/(radius*radius);
            if (q >= 1) continue;
            if (softness == 0 && gray == ToneDocument.ERASE) { doc.eraseTone(x,y,1); continue; }
            float feather = Math.max(.01f, softness/100f);
            float edge = Math.min(1,(1-q)/feather);
            float alpha = Math.min(1, (.08f + .20f*pressure) * edge * edge * flow);
            if (alpha <= .001f) continue;
            if (gray == ToneDocument.ERASE) doc.eraseTone(x,y,alpha);
            else doc.sprayTone(x,y,gray,alpha);
        }
    }
    static void soften(ToneDocument doc, float cx, float cy, float radius) {
        soften(doc,cx,cy,radius,new int[0]);
    }
    static int[] soften(ToneDocument doc, float cx, float cy, float radius, int[] sum) {
        return soften(doc,cx,cy,radius,0,0,sum);
    }
    static int[] soften(ToneDocument doc, float cx, float cy, float radius, float dx, float dy, int[] sum) {
        return soften(doc,cx,cy,radius,dx,dy,100,sum);
    }
    static int[] soften(ToneDocument doc, float cx, float cy, float radius, float dx, float dy, int strength, int[] sum) {
        if(strength<0||strength>100)throw new IllegalArgumentException("Invalid blending strength");
        if(strength==0)return sum;
        int l=Math.max(0,(int)Math.floor(cx-radius)), t=Math.max(0,(int)Math.floor(cy-radius));
        int r=Math.min(doc.width,(int)Math.ceil(cx+radius)), b=Math.min(doc.height,(int)Math.ceil(cy+radius));
        if (r<=l || b<=t) return sum;
        int reach=Math.max(2,Math.min(8,Math.round(radius*.2f)));
        int dragX=Math.round(dx*radius*.25f),dragY=Math.round(dy*radius*.25f);
        int sl=Math.max(0,l-reach-Math.abs(dragX)), st=Math.max(0,t-reach-Math.abs(dragY));
        int sr=Math.min(doc.width,r+reach+Math.abs(dragX)), sb=Math.min(doc.height,b+reach+Math.abs(dragY));
        int stride=sr-sl+1;
        int count=stride*(sb-st+1);
        if(sum.length<count) sum=new int[count]; else java.util.Arrays.fill(sum,0,count,0);
        // Build the summed neighborhood before writing any destination pixel.
        for (int y=st;y<sb;y++) {
            int row=0;
            for (int x=sl;x<sr;x++) {
                row+=doc.tone(x,y);
                sum[(y-st+1)*stride+x-sl+1]=sum[(y-st)*stride+x-sl+1]+row;
            }
        }
        for (int y=t;y<b;y++) for (int x=l;x<r;x++) {
            float q=((x+.5f-cx)*(x+.5f-cx)+(y+.5f-cy)*(y+.5f-cy))/(radius*radius);
            if (q>=1) continue;
            int x0=Math.max(sl,x-reach)-sl,y0=Math.max(st,y-reach)-st,x1=Math.min(sr,x+reach+1)-sl,y1=Math.min(sb,y+reach+1)-st;
            float mean=(sum[y1*stride+x1]-sum[y0*stride+x1]-sum[y1*stride+x0]+sum[y0*stride+x0])/(float)((x1-x0)*(y1-y0));
            if(dragX!=0||dragY!=0) {
                // Blend mostly from behind the moving paper tip, carrying tones forward.
                // Clamp the source center at paper edges; never sample outside the page.
                int sx=Math.max(0,Math.min(doc.width-1,x-dragX)),sy=Math.max(0,Math.min(doc.height-1,y-dragY));
                x0=Math.max(sl,sx-reach)-sl;y0=Math.max(st,sy-reach)-st;
                x1=Math.min(sr,sx+reach+1)-sl;y1=Math.min(sb,sy+reach+1)-st;
                float carried=(sum[y1*stride+x1]-sum[y0*stride+x1]-sum[y1*stride+x0]+sum[y0*stride+x0])/(float)((x1-x0)*(y1-y0));
                mean=.2f*mean+.8f*carried;
            }
            int old=doc.tone(x,y);
            doc.setTone(x,y,Math.round(old+(mean-old)*(.7f*strength/100f)*(1-q)*(1-q)));
        }
        return sum;
    }
    static void pencil(ToneDocument doc, float cx, float cy, float major, float minor, float angle, float pressure, int gray) {
        pencil(doc,cx,cy,major,minor,angle,pressure,gray,40);
    }
    static void pencil(ToneDocument doc, float cx, float cy, float major, float minor, float angle, float pressure, int gray, int hardness) {
        pencil(doc,cx,cy,major,minor,angle,pressure,gray,hardness,false);
    }
    static void pencil(ToneDocument doc, float cx, float cy, float major, float minor, float angle, float pressure, int gray, int hardness, boolean erasing) {
        float cs=(float)Math.cos(angle), sn=(float)Math.sin(angle), radius=major/2;
        int l=Math.max(0,(int)Math.floor(cx-radius)),t=Math.max(0,(int)Math.floor(cy-radius));
        int r=Math.min(doc.width,(int)Math.ceil(cx+radius)),b=Math.min(doc.height,(int)Math.ceil(cy+radius));
        for (int y=t;y<b;y++) for (int x=l;x<r;x++) {
            float dx=x+.5f-cx,dy=y+.5f-cy,u=(dx*cs+dy*sn)/(major/2),v=(-dx*sn+dy*cs)/(minor/2);
            float q=u*u+v*v; if(q>=1) continue;
            int hash=x*0x1f123bb5+y*0x05491333; hash^=hash>>>16; hash*=0x45d9f3b; hash^=hash>>>16;
            float grain=(hash&65535)/65535f;
            float hard=hardness/100f;
            if(grain<.04f+.24f*hard) continue;
            float alpha=(.18f+.82f*pressure)*(1-.78f*hard)*(.65f-.4f*hard+(.35f+.4f*hard)*grain)*Math.min(1,(1-q)*4);
            if(erasing) doc.eraseFromBase(x,y,alpha);
            else doc.pencilTone(x,y,gray,alpha);
        }
    }
    private ToneDabs() {}
}
