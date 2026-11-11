/* The page's layers as native code reads them: shared by page composition and the previews. */
#ifndef MONOPAINT_LAYERS_H
#define MONOPAINT_LAYERS_H

#include <jni.h>
#include <stdint.h>

/* Keep equal to ToneDocument.MAX_LAYERS. */
#define MAX_LAYERS 10

static void fail(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type)(*env)->ThrowNew(env,type,message);
}

typedef struct {
    int count;
    jbyteArray toneRefs[MAX_LAYERS],alphaRefs[MAX_LAYERS];
    const uint8_t *tones[MAX_LAYERS],*alpha[MAX_LAYERS];
} Layers;

/* Looks up each layer's tone and coverage arrays, which must hold {@code pixels} each; 0 after throwing. */
static int layers_find(JNIEnv *env,Layers *layers,jobjectArray tones,jobjectArray alpha,size_t pixels) {
    int count=(*env)->GetArrayLength(env,tones);
    layers->count=0;
    if(count>MAX_LAYERS || (*env)->GetArrayLength(env,alpha)!=count) { fail(env,"Invalid layers"); return 0; }
    for(int i=0;i<count;i++) {
        layers->toneRefs[i]=(*env)->GetObjectArrayElement(env,tones,i);
        layers->alphaRefs[i]=(*env)->GetObjectArrayElement(env,alpha,i);
        layers->tones[i]=layers->alpha[i]=NULL;
        if(!layers->toneRefs[i] || !layers->alphaRefs[i]
                || (size_t)(*env)->GetArrayLength(env,layers->toneRefs[i])!=pixels
                || (size_t)(*env)->GetArrayLength(env,layers->alphaRefs[i])!=pixels) {
            fail(env,"Invalid layer size"); return 0;
        }
    }
    layers->count=count;
    return 1;
}
/* Pins every layer's pixels, after the bitmap is locked; pair with {@link layers_release} even on failure. */
static int layers_pin(JNIEnv *env,Layers *layers) {
    for(int i=0;i<layers->count;i++) {
        layers->tones[i]=(*env)->GetPrimitiveArrayCritical(env,layers->toneRefs[i],NULL);
        if(layers->tones[i])layers->alpha[i]=(*env)->GetPrimitiveArrayCritical(env,layers->alphaRefs[i],NULL);
        if(!layers->tones[i] || !layers->alpha[i])return 0;
    }
    return 1;
}
static void layers_release(JNIEnv *env,Layers *layers) {
    for(int i=layers->count-1;i>=0;i--) {
        if(layers->alpha[i])(*env)->ReleasePrimitiveArrayCritical(env,layers->alphaRefs[i],(void *)layers->alpha[i],JNI_ABORT);
        if(layers->tones[i])(*env)->ReleasePrimitiveArrayCritical(env,layers->toneRefs[i],(void *)layers->tones[i],JNI_ABORT);
        layers->tones[i]=layers->alpha[i]=NULL;
    }
}

/* {@code tone} at {@code alpha} coverage, faded to {@code opacity} percent, over {@code value}. */
static inline unsigned over(unsigned value,unsigned tone,unsigned alpha,unsigned opacity) {
    unsigned a=(alpha*opacity+50)/100;
    return (tone*a+value*(255-a)+127)/255;
}
/* The screen pixel for a tone: its gray while zoomed ({@code dots} null), else its dot pattern. */
static inline uint32_t shown(unsigned value,int x,int y,const jint *dots) {
    return dots?(uint32_t)dots[value*64+(y&7)*8+(x&7)]:0xff000000u|value*0x010101u;
}

#endif
