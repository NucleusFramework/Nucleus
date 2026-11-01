// JNI shim of nucleus_macstore: forwards requests to the Swift StoreKit bridge and
// delivers its answers to dev.nucleusframework.store.mac.internal.NativeMacStoreBridge.

#import <Foundation/Foundation.h>
#include "../../../../../native-common/nucleus_jni.h"
#include "nucleus_macstore.h"

static JavaVM *gVm;
static jclass gBridge;
static jmethodID gOnResponse;
static jmethodID gOnEvent;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gVm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "dev/nucleusframework/store/mac/internal/NativeMacStoreBridge");
    if (local == NULL) return JNI_ERR;
    gBridge = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    gOnResponse = (*env)->GetStaticMethodID(env, gBridge, "onResponse", "(JLjava/lang/String;)V");
    gOnEvent = (*env)->GetStaticMethodID(env, gBridge, "onEvent", "(Ljava/lang/String;)V");
    if (gOnResponse == NULL || gOnEvent == NULL) return JNI_ERR;
    return JNI_VERSION_1_8;
}

// Swift answers from its cooperative pool: attach for the call only, never leave a
// thread attached behind (the Windows bridge learned that a dangling attach outlives it).
static JNIEnv *attach(BOOL *attached) {
    JNIEnv *env = NULL;
    *attached = NO;
    jint state = (*gVm)->GetEnv(gVm, (void **)&env, JNI_VERSION_1_8);
    if (state == JNI_EDETACHED) {
        if ((*gVm)->AttachCurrentThreadAsDaemon(gVm, (void **)&env, NULL) != JNI_OK) return NULL;
        *attached = YES;
    } else if (state != JNI_OK) {
        return NULL;
    }
    return env;
}

static void detach(BOOL attached) {
    if (attached) (*gVm)->DetachCurrentThread(gVm);
}

// NewStringUTF expects modified UTF-8 and mangles characters outside the BMP (emoji in a
// product name); go through UTF-16 instead.
static jstring newString(JNIEnv *env, const char *utf8) {
    NSString *string = [NSString stringWithUTF8String:utf8] ?: @"";
    NSUInteger length = string.length;
    unichar *chars = malloc(MAX(length, 1) * sizeof(unichar));
    if (chars == NULL) return NULL;
    [string getCharacters:chars range:NSMakeRange(0, length)];
    jstring result = (*env)->NewString(env, chars, (jsize)length);
    free(chars);
    return result;
}

void nms_respond(int64_t requestId, const char *envelope) {
    @autoreleasepool {
        BOOL attached;
        JNIEnv *env = attach(&attached);
        if (env == NULL) return;
        jstring json = newString(env, envelope);
        if (json != NULL) {
            (*env)->CallStaticVoidMethod(env, gBridge, gOnResponse, (jlong)requestId, json);
            (*env)->DeleteLocalRef(env, json);
        }
        nucleus_jni_clear_exception(env);
        detach(attached);
    }
}

void nms_event(const char *payload) {
    @autoreleasepool {
        BOOL attached;
        JNIEnv *env = attach(&attached);
        if (env == NULL) return;
        jstring json = newString(env, payload);
        if (json != NULL) {
            (*env)->CallStaticVoidMethod(env, gBridge, gOnEvent, json);
            (*env)->DeleteLocalRef(env, json);
        }
        nucleus_jni_clear_exception(env);
        detach(attached);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_store_mac_internal_NativeMacStoreBridge_nativeRequest(
    JNIEnv *env, jclass cls, jlong requestId, jstring method, jstring args) {
    (void)cls;
    const char *m = (*env)->GetStringUTFChars(env, method, NULL);
    const char *a = (*env)->GetStringUTFChars(env, args, NULL);
    if (m != NULL && a != NULL) {
        // Copy before releasing: the Swift side reads them on another thread.
        NSString *methodString = [NSString stringWithUTF8String:m];
        NSString *argsString = [[NSString alloc] initWithBytes:a length:strlen(a) encoding:NSUTF8StringEncoding];
        nms_request((int64_t)requestId, methodString.UTF8String, (argsString ?: @"{}").UTF8String);
    }
    if (m != NULL) (*env)->ReleaseStringUTFChars(env, method, m);
    if (a != NULL) (*env)->ReleaseStringUTFChars(env, args, a);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_store_mac_internal_NativeMacStoreBridge_nativeCancel(
    JNIEnv *env, jclass cls, jlong requestId) {
    (void)env;
    (void)cls;
    nms_cancel((int64_t)requestId);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_store_mac_internal_NativeMacStoreBridge_nativeInFlight(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)nms_in_flight();
}
