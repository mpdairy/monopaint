#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>

static void fail(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type) (*env)->ThrowNew(env,type,message);
}

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
        columns[x-left]=p>=0 && p<(swapped?src.height:src.width)?(int)p:-1;
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
    for(int y=top;y<bottom;y++) {
        uint32_t *out=(uint32_t *)((uint8_t *)target_pixels+(size_t)y*dst.stride)+(size_t)left;
        float p=(swapped?m[1]:m[4])*(y+.5f)+(swapped?m[2]:m[5]);
        int row=p>=0 && p<(swapped?src.width:src.height)?(int)p:-1;
        int phase=(y&7)*8;
        for(int x=left;x<right;x++) {
            int col=columns[x-left];
            int tone=255;
            if(row>=0 && col>=0)
                tone=*((uint8_t *)source_pixels+(size_t)(swapped?col:row)*src.stride+(size_t)(swapped?row:col)*4);
            *out++=(uint32_t)pattern[tone*64+phase+(x&7)];
        }
    }
    AndroidBitmap_unlockPixels(env,target);AndroidBitmap_unlockPixels(env,source);
    (*env)->ReleaseIntArrayElements(env,dots,pattern,JNI_ABORT);
    free(columns);
}
