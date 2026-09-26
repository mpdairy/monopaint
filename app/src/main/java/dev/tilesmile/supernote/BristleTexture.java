package dev.tilesmile.supernote;

/** Bristle lanes stay aligned with the brush head, including its pen-tilt angle.
 * Paper coordinates keep overlapping stamps stable when movement changes direction.
 * Never writes white. */
final class BristleTexture {
    static void apply(int[] mask, int stride, int left, int top, int width, int height,
                      float radius, float angle, float amount, int seed) {
        if (amount<=0 || radius<2) return;
        amount=Math.min(1,amount);
        float cs=(float)Math.cos(Math.toRadians(angle)),sn=(float)Math.sin(Math.toRadians(angle));
        float laneWidth=Math.max(1,radius*.10f),patchWidth=Math.max(3,radius*.45f);
        float length=Math.max(6,radius*.9f);
        for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
            int index=row*stride+col;
            if((mask[index]>>>24)==0)continue;
            float x=left+col+.5f,y=top+row+.5f;
            float u=x*cs+y*sn,v=(-x*sn+y*cs)/length;
            int section=(int)Math.floor(v);float t=v-section;t=t*t*(3-2*t);
            float bend=(mix(noise(section,0,seed),noise(section+1,0,seed),t)-.5f)*radius*.5f*amount;
            int lane=(int)Math.floor((u+bend)/laneWidth),patch=(int)Math.floor(u/patchWidth);
            float tuft=mix(noise(patch,section,seed^0x7b1d),noise(patch,section+1,seed^0x7b1d),t);
            if(noise(lane,1,seed)<amount*.5f || tuft<amount*.32f
                    || noise(left+col,top+row,seed^0x319a)<amount*.045f)mask[index]=0;
        }
    }
    private static float mix(float a,float b,float t) { return a+(b-a)*t; }
    private static float noise(int x,int y,int seed) {
        int hash=x*0x1f123bb5+y*0x05491333+seed;hash^=hash>>>16;hash*=0x45d9f3b;hash^=hash>>>16;
        return (hash&65535)/65536f;
    }
    private BristleTexture() {}
}
