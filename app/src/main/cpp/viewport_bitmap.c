#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include "layers.h"


/* Sample logical tones, then apply the shared screen-pixel calibration in one pass.
 * This touches only app-owned bitmaps, never the display device. */
JNIEXPORT void JNICALL
Java_io_github_mpdairy_monopaint_ViewportBitmap_nativeRender(JNIEnv *env,jclass clazz,
        jobject source,jobject target,jfloatArray inverse,jintArray dots,
        jint left,jint top,jint right,jint bottom) {
    (void)clazz;
    AndroidBitmapInfo src,dst;
    if(AndroidBitmap_getInfo(env,source,&src)!=ANDROID_BITMAP_RESULT_SUCCESS
            || AndroidBitmap_getInfo(env,target,&dst)!=ANDROID_BITMAP_RESULT_SUCCESS
            || src.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format!=ANDROID_BITMAP_FORMAT_RGBA_8888
            || left<0 || top<0 || right<=left || bottom<=top || (uint32_t)right>dst.width || (uint32_t)bottom>dst.height
            || (*env)->GetArrayLength(env,inverse)!=9 || (*env)->GetArrayLength(env,dots)!=256*64) {
        fail(env,"Invalid zoom raster"); return;
    }
    jfloat m[9];(*env)->GetFloatArrayRegion(env,inverse,0,9,m);
    for(int i=0;i<9;i++) if(!isfinite(m[i])) {fail(env,"Invalid zoom transform");return;}
    int swapped=fabsf(m[0])<.000001f && fabsf(m[4])<.000001f;
    if(!swapped && (fabsf(m[1])>.000001f || fabsf(m[3])>.000001f)) {
        fail(env,"Zoom requires a rectangular transform");return;
    }
    int *columns=malloc((size_t)(right-left)*sizeof(int));
    if(!columns) {fail(env,"Cannot allocate zoom columns");return;}
    for(int x=left;x<right;x++) {
        float p=(swapped?m[3]:m[0])*(x+.5f)+(swapped?m[5]:m[2]);
        columns[x-left]=p>=0 && p<(swapped?src.height:src.width)?(int)p*(swapped?(int)src.stride:4):-1;
    }
    jint *pattern=(*env)->GetIntArrayElements(env,dots,NULL);
    if(!pattern) {free(columns);return;}
    void *source_pixels=NULL,*target_pixels=NULL;
    if(AndroidBitmap_lockPixels(env,source,&source_pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        (*env)->ReleaseIntArrayElements(env,dots,pattern,JNI_ABORT);free(columns);fail(env,"Cannot read zoom tones");return;
    }
    if(AndroidBitmap_lockPixels(env,target,&target_pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env,source);
        (*env)->ReleaseIntArrayElements(env,dots,pattern,JNI_ABORT);free(columns);fail(env,"Cannot write zoom raster");return;
    }
    // Transposed reads in landscape otherwise stride through an entire page for
    // every output row. Small tiles reuse source cache lines in either orientation.
    int tile_width=swapped?32:right-left, tile_height=swapped?32:bottom-top;
    for(int by=top;by<bottom;by+=tile_height)for(int bx=left;bx<right;bx+=tile_width) {
        int end_y=by+tile_height<bottom?by+tile_height:bottom;
        int end_x=bx+tile_width<right?bx+tile_width:right;
        for(int y=by;y<end_y;y++) {
            uint32_t *out=(uint32_t *)((uint8_t *)target_pixels+(size_t)y*dst.stride)+(size_t)bx;
            float p=(swapped?m[1]:m[4])*(y+.5f)+(swapped?m[2]:m[5]);
            int row=p>=0 && p<(swapped?src.width:src.height)?(int)p:-1;
            int phase=(y&7)*8;
            if(row<0) {for(int x=bx;x<end_x;x++)*out++=(uint32_t)pattern[255*64+phase+(x&7)];continue;}
            const uint8_t *base=(uint8_t *)source_pixels+(size_t)row*(swapped?4:src.stride);
            for(int x=bx;x<end_x;x++) {
                int offset=columns[x-left];
                int tone=offset>=0?base[offset]:255;
                *out++=(uint32_t)pattern[tone*64+phase+(x&7)];
            }
        }
    }
    AndroidBitmap_unlockPixels(env,target);AndroidBitmap_unlockPixels(env,source);
    (*env)->ReleaseIntArrayElements(env,dots,pattern,JNI_ABORT);
    free(columns);
}

/* Flatten visible logical layers once at gesture entry. A snapshot owns the source
 * references; only this app-owned bitmap is written, never document/undo pixels. */
JNIEXPORT void JNICALL
Java_io_github_mpdairy_monopaint_ViewportBitmap_nativeCompose(JNIEnv *env,jclass clazz,
        jobject target,jobjectArray tones,jobjectArray alpha,jintArray opacity,jintArray dots) {
    (void)clazz;
    AndroidBitmapInfo info;
    Layers layers;
    if(AndroidBitmap_getInfo(env,target,&info)!=ANDROID_BITMAP_RESULT_SUCCESS
            || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(env,"Invalid navigation layers");return;
    }
    if(!layers_find(env,&layers,tones,alpha,(size_t)info.width*info.height))return;
    int count=layers.count;
    if((*env)->GetArrayLength(env,opacity)!=count || (dots && (*env)->GetArrayLength(env,dots)!=256*64)) {
        fail(env,"Invalid navigation layers");return;
    }
    jint opacities[MAX_LAYERS]={0};
    (*env)->GetIntArrayRegion(env,opacity,0,count,opacities);
    void *pixels=NULL;
    if(AndroidBitmap_lockPixels(env,target,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env,"Cannot compose navigation bitmap");return;
    }
    int ready=1;
    jint *pattern=dots?(*env)->GetIntArrayElements(env,dots,NULL):NULL;
    if(dots && !pattern)ready=0;
    if(ready)ready=layers_pin(env,&layers);
    if(ready)for(uint32_t y=0;y<info.height;y++) {
        uint32_t *out=(uint32_t *)((uint8_t *)pixels+(size_t)y*info.stride);
        for(uint32_t x=0;x<info.width;x++)out[x]=0xffffffff;
        for(int layer=0;layer<count;layer++) {
            const uint8_t *t=layers.tones[layer]+(size_t)y*info.width;
            const uint8_t *a=layers.alpha[layer]+(size_t)y*info.width;
            for(uint32_t x=0;x<info.width;x++)
                out[x]=0xff000000u|over(out[x]&255,t[x],a[x],opacities[layer])*0x010101u;
        }
        if(pattern)for(uint32_t x=0;x<info.width;x++)out[x]=shown(out[x]&255,(int)x,(int)y,pattern);
    }
    layers_release(env,&layers);
    if(pattern)(*env)->ReleaseIntArrayElements(env,dots,pattern,JNI_ABORT);
    AndroidBitmap_unlockPixels(env,target);
}
