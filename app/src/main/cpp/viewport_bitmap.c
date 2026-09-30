#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include <arm_neon.h>

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
            if(row<0) {for(int x=bx;x<end_x;x++)*out++=0xffffffff;continue;}
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
        jobject target,jobjectArray tones,jobjectArray alpha) {
    (void)clazz;
    AndroidBitmapInfo info;
    int count=(*env)->GetArrayLength(env,tones);
    if(count>8 || (*env)->GetArrayLength(env,alpha)!=count
            || AndroidBitmap_getInfo(env,target,&info)!=ANDROID_BITMAP_RESULT_SUCCESS
            || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(env,"Invalid navigation layers");return;
    }
    jbyteArray tone_refs[8]={0},alpha_refs[8]={0};
    const uint8_t *tone_pixels[8]={0},*alpha_pixels[8]={0};
    for(int i=0;i<count;i++) {
        tone_refs[i]=(*env)->GetObjectArrayElement(env,tones,i);
        alpha_refs[i]=(*env)->GetObjectArrayElement(env,alpha,i);
        if(!tone_refs[i] || !alpha_refs[i]
                || (*env)->GetArrayLength(env,tone_refs[i])!=(int)(info.width*info.height)
                || (*env)->GetArrayLength(env,alpha_refs[i])!=(int)(info.width*info.height)) {
            fail(env,"Invalid navigation layer size");return;
        }
    }
    void *pixels=NULL;
    if(AndroidBitmap_lockPixels(env,target,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env,"Cannot compose navigation bitmap");return;
    }
    int ready=1;
    for(int i=0;i<count && ready;i++) {
        tone_pixels[i]=(*env)->GetPrimitiveArrayCritical(env,tone_refs[i],NULL);
        if(tone_pixels[i])alpha_pixels[i]=(*env)->GetPrimitiveArrayCritical(env,alpha_refs[i],NULL);
        ready=tone_pixels[i] && alpha_pixels[i];
    }
    if(ready)for(uint32_t y=0;y<info.height;y++) {
        uint32_t *out=(uint32_t *)((uint8_t *)pixels+(size_t)y*info.stride);
        for(uint32_t x=0;x<info.width;x++)out[x]=0xffffffff;
        for(int layer=0;layer<count;layer++) {
            const uint8_t *t=tone_pixels[layer]+(size_t)y*info.width;
            const uint8_t *a=alpha_pixels[layer]+(size_t)y*info.width;
            for(uint32_t x=0;x<info.width;x++) {
                unsigned shade=(t[x]*a[x]+(out[x]&255)*(255-a[x])+127)/255;
                out[x]=0xff000000u|shade*0x010101u;
            }
        }
    }
    for(int i=count-1;i>=0;i--) {
        if(alpha_pixels[i])(*env)->ReleasePrimitiveArrayCritical(env,alpha_refs[i],(void *)alpha_pixels[i],JNI_ABORT);
        if(tone_pixels[i])(*env)->ReleasePrimitiveArrayCritical(env,tone_refs[i],(void *)tone_pixels[i],JNI_ABORT);
    }
    AndroidBitmap_unlockPixels(env,target);
}

/* Exact quarter-turn copy for the fixed-orientation panel. Small tiles keep both
 * reads and writes local; Canvas's general software transform is much slower. */
JNIEXPORT void JNICALL
Java_io_github_mpdairy_monopaint_DirectEink_nativeRotate(JNIEnv *env,jclass clazz,
        jobject source,jobject target,jint quarter,jint left,jint top,jint right,jint bottom) {
    (void)clazz;
    AndroidBitmapInfo src,dst;
    if(quarter<0 || quarter>3
            || AndroidBitmap_getInfo(env,source,&src)!=ANDROID_BITMAP_RESULT_SUCCESS
            || AndroidBitmap_getInfo(env,target,&dst)!=ANDROID_BITMAP_RESULT_SUCCESS
            || src.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format!=ANDROID_BITMAP_FORMAT_RGBA_8888
            || dst.width!=((quarter&1)?src.height:src.width) || dst.height!=((quarter&1)?src.width:src.height)
            || left<0 || top<0 || right<=left || bottom<=top || (uint32_t)right>dst.width || (uint32_t)bottom>dst.height) {
        fail(env,"Invalid panel rotation");return;
    }
    void *input=NULL,*output=NULL;
    if(AndroidBitmap_lockPixels(env,source,&input)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env,"Cannot read panel rotation");return;
    }
    if(AndroidBitmap_lockPixels(env,target,&output)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env,source);fail(env,"Cannot write panel rotation");return;
    }
    int full_right=left+(right-left)/4*4,full_bottom=top+(bottom-top)/4*4;
    // Transpose four packed pixels at a time. Scalar strided copies spend most
    // of a landscape frame on address arithmetic and one-pixel memory accesses.
    for(int y=top;y<full_bottom;y+=4)for(int x=left;x<full_right;x+=4) {
        int sx=quarter==1?y:quarter==2?(int)src.width-x-4:quarter==3?(int)src.width-y-4:x;
        int sy=quarter==1?(int)src.height-x-4:quarter==2?(int)src.height-y-4:quarter==3?x:y;
        const uint8_t *base=(uint8_t *)input+(size_t)sy*src.stride+(size_t)sx*4;
        uint32x4_t a=vld1q_u32((const uint32_t *)base);
        uint32x4_t b=vld1q_u32((const uint32_t *)(base+src.stride));
        uint32x4_t c=vld1q_u32((const uint32_t *)(base+2*src.stride));
        uint32x4_t d=vld1q_u32((const uint32_t *)(base+3*src.stride));
        uint32x4_t rows[4];
        if(quarter&1) {
            uint32x4x2_t ab=vtrnq_u32(a,b),cd=vtrnq_u32(c,d);
            rows[0]=vcombine_u32(vget_low_u32(ab.val[0]),vget_low_u32(cd.val[0]));
            rows[1]=vcombine_u32(vget_low_u32(ab.val[1]),vget_low_u32(cd.val[1]));
            rows[2]=vcombine_u32(vget_high_u32(ab.val[0]),vget_high_u32(cd.val[0]));
            rows[3]=vcombine_u32(vget_high_u32(ab.val[1]),vget_high_u32(cd.val[1]));
            if(quarter==3) {
                uint32x4_t swap=rows[0];rows[0]=rows[3];rows[3]=swap;
                swap=rows[1];rows[1]=rows[2];rows[2]=swap;
            }
        } else {
            rows[0]=quarter==2?d:a;rows[1]=quarter==2?c:b;
            rows[2]=quarter==2?b:c;rows[3]=quarter==2?a:d;
        }
        for(int row=0;row<4;row++) {
            uint32x4_t pixels=rows[row];
            if(quarter==1 || quarter==2) {
                pixels=vrev64q_u32(pixels);pixels=vextq_u32(pixels,pixels,2);
            }
            vst1q_u32((uint32_t *)((uint8_t *)output+(size_t)(y+row)*dst.stride)+(size_t)x,pixels);
        }
    }
    // Preserve exact clipping for the remaining one-to-three edge rows/columns.
    for(int y=top;y<bottom;y++)for(int x=y<full_bottom?full_right:left;x<right;x++) {
        int sx=quarter==1?y:quarter==2?(int)src.width-1-x:quarter==3?(int)src.width-1-y:x;
        int sy=quarter==1?(int)src.height-1-x:quarter==2?(int)src.height-1-y:quarter==3?x:y;
        *(uint32_t *)((uint8_t *)output+(size_t)y*dst.stride+(size_t)x*4)=
                *(uint32_t *)((uint8_t *)input+(size_t)sy*src.stride+(size_t)sx*4);
    }
    AndroidBitmap_unlockPixels(env,target);AndroidBitmap_unlockPixels(env,source);
}
