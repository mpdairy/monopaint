/* Desktop checks for a page turned by any angle: scripts/test_document.sh builds and runs this. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "viewport_turn.h"

#define PAGE_W 64
#define PAGE_H 48
#define VIEW_W 400
#define VIEW_H 360
#define SCALE 3
/* Rows are padded, so a wrong stride shows up. */
#define SOURCE_STRIDE ((PAGE_W+5)*4)
#define TARGET_STRIDE ((VIEW_W+3)*4)
#define BLANK 0xdeadbeefu
#define RADIANS(degrees) ((degrees)*3.14159265358979323846/180)

static uint8_t source[SOURCE_STRIDE*PAGE_H];
static int32_t pattern[256*64];
static uint32_t target[(TARGET_STRIDE/4)*VIEW_H],whole[(TARGET_STRIDE/4)*VIEW_H];

static void check(int ok,const char *what,double angle,int a,int b) {
    if(ok) return;
    fprintf(stderr,"FAIL at %g degrees: %s (%d, %d)\n",angle,what,a,b);
    exit(1);
}
static uint8_t tone(int x,int y) { return (uint8_t)(x*7+y*13); }
/* Names the tone and the screen position it was drawn at, so both can be read back from a pixel. */
static uint32_t shown(int tone_value,int x,int y) {
    return 0xff000000u|(uint32_t)tone_value|((uint32_t)((y&7)*8+(x&7))<<8);
}
/* Page to view: scale, turn clockwise by {@code degrees} about the page's corner, then move. */
static void forward(double degrees,double tx,double ty,double u,double v,double *x,double *y) {
    double r=RADIANS(degrees),c=cos(r),s=sin(r);
    *x=tx+SCALE*(c*u-s*v); *y=ty+SCALE*(s*u+c*v);
}
/* The matrix the renderer is given: view back to page, in Android Matrix value order. */
static void inverse(double degrees,double tx,double ty,float m[9]) {
    double r=RADIANS(degrees),c=cos(r),s=sin(r);
    m[0]=(float)(c/SCALE); m[1]=(float)(s/SCALE); m[2]=(float)(-(c*tx+s*ty)/SCALE);
    m[3]=(float)(-s/SCALE); m[4]=(float)(c/SCALE); m[5]=(float)((s*tx-c*ty)/SCALE);
    m[6]=m[7]=0; m[8]=1;
}
static void clear(uint32_t *pixels) { for(size_t i=0;i<sizeof(target)/sizeof(target[0]);i++) pixels[i]=BLANK; }
static uint32_t at(const uint32_t *pixels,int x,int y) { return pixels[(size_t)y*(TARGET_STRIDE/4)+(size_t)x]; }

static void turn(double degrees) {
    /* Centre the turned page on the view. */
    double xs[4],ys[4],left=1e9,right=-1e9,top=1e9,bottom=-1e9;
    double corners[4][2]={{0,0},{PAGE_W,0},{0,PAGE_H},{PAGE_W,PAGE_H}};
    for(int i=0;i<4;i++) {
        forward(degrees,0,0,corners[i][0],corners[i][1],&xs[i],&ys[i]);
        left=fmin(left,xs[i]); right=fmax(right,xs[i]);
        top=fmin(top,ys[i]); bottom=fmax(bottom,ys[i]);
    }
    double tx=VIEW_W/2.0-(left+right)/2,ty=VIEW_H/2.0-(top+bottom)/2;
    float m[9]; inverse(degrees,tx,ty,m);

    clear(target);
    render_turned(source,PAGE_W,PAGE_H,SOURCE_STRIDE,(uint8_t *)target,TARGET_STRIDE,m,pattern,0,0,VIEW_W,VIEW_H);
    /* Every pixel in the area is written, and the padding beyond it is left alone. */
    for(int y=0;y<VIEW_H;y++) {
        for(int x=0;x<VIEW_W;x++) check(at(target,x,y)!=BLANK,"unwritten pixel",degrees,x,y);
        for(int x=VIEW_W;x<TARGET_STRIDE/4;x++) check(at(target,x,y)==BLANK,"wrote into row padding",degrees,x,y);
    }
    /* Each page pixel appears where its centre goes, in its own tone and at that screen position. */
    for(int py=0;py<PAGE_H;py++)for(int px=0;px<PAGE_W;px++) {
        double fx,fy; forward(degrees,tx,ty,px+.5,py+.5,&fx,&fy);
        int x=(int)floor(fx),y=(int)floor(fy);
        check(x>=0 && y>=0 && x<VIEW_W && y<VIEW_H,"page leaves the view",degrees,px,py);
        check(at(target,x,y)==shown(tone(px,py),x,y),"page pixel in the wrong place or tone",degrees,px,py);
    }
    /* Pixels off the page are white, and the page covers the area it should. */
    long page=0;
    for(int y=0;y<VIEW_H;y++)for(int x=0;x<VIEW_W;x++) if(at(target,x,y)!=0xffffffffu) page++;
    double wanted=(double)PAGE_W*PAGE_H*SCALE*SCALE;
    check(page>wanted*.95 && page<wanted*1.05,"turned page covers the wrong area",degrees,(int)page,(int)wanted);
    check(at(target,0,0)==0xffffffffu && at(target,VIEW_W-1,VIEW_H-1)==0xffffffffu,"canvas corners are not white",degrees,0,0);

    /* A partial area, as when only the pen's dirty rectangle is redrawn, matches the full render
     * inside it and touches nothing outside it. */
    memcpy(whole,target,sizeof(target));
    clear(target);
    render_turned(source,PAGE_W,PAGE_H,SOURCE_STRIDE,(uint8_t *)target,TARGET_STRIDE,m,pattern,101,83,223,191);
    for(int y=0;y<VIEW_H;y++)for(int x=0;x<VIEW_W;x++) {
        int in=x>=101 && x<223 && y>=83 && y<191;
        check(at(target,x,y)==(in?at(whole,x,y):BLANK),"partial render differs from the full render",degrees,x,y);
    }
}

/* Like the quarter-turn paths, a pixel reads the source under its centre. At 150% that is not the
 * source under its corner: target pixel 1 shows source 1 (centre 1.5/1.5), not source 0. */
static void centre_convention(void) {
    float m[9]={1/1.5f,0,0,0,1/1.5f,0,0,0,1};
    clear(target);
    render_turned(source,PAGE_W,PAGE_H,SOURCE_STRIDE,(uint8_t *)target,TARGET_STRIDE,m,pattern,0,0,40,30);
    for(int y=0;y<30;y++)for(int x=0;x<40;x++)
        check(at(target,x,y)==shown(tone((2*x+1)/3,(2*y+1)/3),x,y),"pixel centre is not what is sampled",0,x,y);
}

int main(void) {
    for(int t=0;t<256;t++)for(int i=0;i<64;i++) pattern[t*64+i]=(int32_t)shown(t,i&7,i>>3);
    for(int y=0;y<PAGE_H;y++)for(int x=0;x<PAGE_W;x++) {
        uint8_t *pixel=source+(size_t)y*SOURCE_STRIDE+(size_t)x*4;
        pixel[0]=tone(x,y); pixel[1]=pixel[2]=pixel[3]=0x55;   /* only the first byte is the tone */
    }
    const double angles[]={0,30,45,90,137,180,-75,-179.5,359.25};
    for(size_t i=0;i<sizeof(angles)/sizeof(angles[0]);i++) turn(angles[i]);
    centre_convention();
    printf("PASS: turned page lands every pixel in place, samples pixel centres, white off the page, strides and partial areas\n");
    return 0;
}
