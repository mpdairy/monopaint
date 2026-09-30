#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>

#define MAX_LAYERS 8
static void fail(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type)(*env)->ThrowNew(env,type,message);
}

typedef struct {
    uint8_t *tones[MAX_LAYERS],*alpha[MAX_LAYERS];
    int visible[MAX_LAYERS],count,active,shade,logical,width;
    uint8_t *pixels;
    uint32_t stride;
    jint *dots;
    int left,top,right,bottom;
} Raster;

static void span(Raster *r,int y,int left,int right,int paint) {
    if(left<0)left=0;
    if(right>r->width)right=r->width;
    if(left>=right)return;
    if(left<r->left)r->left=left;
    if(right>r->right)r->right=right;
    if(y<r->top)r->top=y;
    if(y+1>r->bottom)r->bottom=y+1;
    uint32_t *out=(uint32_t *)(r->pixels+(size_t)y*r->stride);
    int offset=y*r->width;
    for(int x=left;x<right;x++) {
        int value=paint?r->shade:255;
        for(int layer=paint?r->active+1:0;layer<r->count;layer++) {
            if(!r->visible[layer])continue;
            int a=r->alpha[layer][offset+x];
            if(a)value=(r->tones[layer][offset+x]*a+value*(255-a)+127)/255;
        }
        out[x]=r->logical?0xff000000u|(uint32_t)value*0x010101u
                :(uint32_t)r->dots[value*64+(y&7)*8+(x&7)];
    }
}
static void difference(Raster *r,int y,const jint *source,const jint *subtract,int paint) {
    for(int part=0;part<4;part+=2) {
        int left=source[part],right=source[part+1];
        if(left>=right)continue;
        for(int cut=0;cut<4 && left<right;cut+=2) {
            int a=subtract[cut],b=subtract[cut+1];
            if(a>=b || b<=left || a>=right)continue;
            if(a>left)span(r,y,left,a,paint);
            if(b>left)left=b;
        }
        if(left<right)span(r,y,left,right,paint);
    }
}

JNIEXPORT void JNICALL
Java_io_github_mpdairy_monopaint_ShapePreview_nativeApply(JNIEnv *env,jclass clazz,jobject display,
        jobjectArray tones,jobjectArray alpha,jintArray visible,jint active,jint shade,
        jintArray previous,jintArray next,jintArray dots,jboolean logical,jintArray dirty) {
    (void)clazz;
    AndroidBitmapInfo info;
    int count=(*env)->GetArrayLength(env,tones);
    if(AndroidBitmap_getInfo(env,display,&info)!=ANDROID_BITMAP_RESULT_SUCCESS
            || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || count<1 || count>MAX_LAYERS
            || active<0 || active>=count || shade<0 || shade>255
            || (*env)->GetArrayLength(env,alpha)!=count || (*env)->GetArrayLength(env,visible)!=count
            || (*env)->GetArrayLength(env,previous)!=(int)info.height*4
            || (*env)->GetArrayLength(env,next)!=(int)info.height*4
            || (*env)->GetArrayLength(env,dots)!=256*64 || (*env)->GetArrayLength(env,dirty)!=4) {
        fail(env,"Invalid shape preview");return;
    }
    jbyteArray toneRefs[MAX_LAYERS]={0},alphaRefs[MAX_LAYERS]={0};
    Raster r={.count=count,.active=active,.shade=shade,.logical=logical,.width=(int)info.width,
              .stride=info.stride,.left=(int)info.width,.top=(int)info.height};
    (*env)->GetIntArrayRegion(env,visible,0,count,r.visible);
    for(int i=0;i<count;i++) {
        toneRefs[i]=(*env)->GetObjectArrayElement(env,tones,i);
        alphaRefs[i]=(*env)->GetObjectArrayElement(env,alpha,i);
        if(!toneRefs[i] || !alphaRefs[i]
                || (*env)->GetArrayLength(env,toneRefs[i])!=(int)(info.width*info.height)
                || (*env)->GetArrayLength(env,alphaRefs[i])!=(int)(info.width*info.height)) {
            fail(env,"Invalid shape layers");return;
        }
    }
    void *pixels=NULL;
    if(AndroidBitmap_lockPixels(env,display,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env,"Cannot write shape preview");return;
    }
    r.pixels=pixels;
    // Get metadata/lock the bitmap before entering nested critical array sections.
    // No allocation, blocking, or arbitrary JNI calls occur while the arrays are pinned.
    jint *old=(*env)->GetPrimitiveArrayCritical(env,previous,NULL);
    jint *current=old?(*env)->GetPrimitiveArrayCritical(env,next,NULL):NULL;
    r.dots=current?(*env)->GetPrimitiveArrayCritical(env,dots,NULL):NULL;
    int ready=r.dots!=NULL;
    for(int i=0;i<count && ready;i++) {
        r.tones[i]=(*env)->GetPrimitiveArrayCritical(env,toneRefs[i],NULL);
        if(r.tones[i])r.alpha[i]=(*env)->GetPrimitiveArrayCritical(env,alphaRefs[i],NULL);
        ready=r.tones[i] && r.alpha[i];
    }
    if(ready)for(int y=0;y<(int)info.height;y++) {
        difference(&r,y,old+y*4,current+y*4,0);
        difference(&r,y,current+y*4,old+y*4,1);
    }
    for(int i=count-1;i>=0;i--) {
        if(r.alpha[i])(*env)->ReleasePrimitiveArrayCritical(env,alphaRefs[i],r.alpha[i],JNI_ABORT);
        if(r.tones[i])(*env)->ReleasePrimitiveArrayCritical(env,toneRefs[i],r.tones[i],JNI_ABORT);
    }
    if(r.dots)(*env)->ReleasePrimitiveArrayCritical(env,dots,r.dots,JNI_ABORT);
    if(current)(*env)->ReleasePrimitiveArrayCritical(env,next,current,JNI_ABORT);
    if(old)(*env)->ReleasePrimitiveArrayCritical(env,previous,old,JNI_ABORT);
    AndroidBitmap_unlockPixels(env,display);
    if(!ready)return;
    jint bounds[4]={r.left,r.top,r.right,r.bottom};
    (*env)->SetIntArrayRegion(env,dirty,0,4,bounds);
}
