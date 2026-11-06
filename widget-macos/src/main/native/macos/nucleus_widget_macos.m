// nucleus_widget_macos.m
//
// JNI side of nucleus.widget-macos: WidgetKit's WidgetCenter / ControlCenter through the
// @_cdecl functions of WidgetKitBridge.swift, Darwin notifications (libnotify), and the
// configuration of the Nucleus widget extension embedded in the app bundle.

#import <Foundation/Foundation.h>
#include <notify.h>
#include <pwd.h>
#include <stdlib.h>
#include <unistd.h>
#include "../../../../../native-common/nucleus_jni.h"

// WidgetKitBridge.swift
extern void nucleus_wk_reload_timelines(const char *kind);
extern void nucleus_wk_reload_all_timelines(void);
extern bool nucleus_wk_invalidate_configuration_recommendations(void);
extern bool nucleus_wk_invalidate_relevance(const char *kind);
extern char *nucleus_wk_current_configurations(int32_t timeoutMillis);
extern char *nucleus_wk_current_push_info(int32_t timeoutMillis);
extern char *nucleus_wk_user_info_key(int32_t which);
extern bool nucleus_wk_controls_available(void);
extern void nucleus_wk_reload_controls(const char *kind);
extern void nucleus_wk_reload_all_controls(void);
extern char *nucleus_wk_current_controls(int32_t timeoutMillis);

static JavaVM *g_jvm = NULL;
static jclass g_bridgeClass = NULL;
static jmethodID g_onNotificationMethod = NULL;

#define BRIDGE_CLASS "dev/nucleusframework/widget/macos/NativeWidgetKitBridge"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, BRIDGE_CLASS);
    if (clazz == NULL) return JNI_ERR;
    g_bridgeClass = (*env)->NewGlobalRef(env, clazz);
    g_onNotificationMethod = (*env)->GetStaticMethodID(env, clazz, "onDarwinNotification", "(I)V");
    if (g_onNotificationMethod == NULL) return JNI_ERR;
    return JNI_VERSION_1_8;
}

static jstring toJString(JNIEnv *env, NSString *value) {
    return value == nil ? NULL : (*env)->NewStringUTF(env, value.UTF8String);
}

// Takes ownership of a malloc'ed string from the Swift bridge.
static jstring takeJString(JNIEnv *env, char *value) {
    if (value == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, value);
    free(value);
    return result;
}

// MARK: - WidgetCenter / ControlCenter

JNIEXPORT void JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeReloadTimelines(
    JNIEnv *env, jclass clazz, jstring kind) {
    const char *chars = (*env)->GetStringUTFChars(env, kind, NULL);
    nucleus_wk_reload_timelines(chars);
    (*env)->ReleaseStringUTFChars(env, kind, chars);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeReloadAllTimelines(
    JNIEnv *env, jclass clazz) {
    nucleus_wk_reload_all_timelines();
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeInvalidateConfigurationRecommendations(
    JNIEnv *env, jclass clazz) {
    return nucleus_wk_invalidate_configuration_recommendations() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeInvalidateRelevance(
    JNIEnv *env, jclass clazz, jstring kind) {
    const char *chars = (*env)->GetStringUTFChars(env, kind, NULL);
    bool done = nucleus_wk_invalidate_relevance(chars);
    (*env)->ReleaseStringUTFChars(env, kind, chars);
    return done ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeCurrentConfigurations(
    JNIEnv *env, jclass clazz, jint timeoutMillis) {
    return takeJString(env, nucleus_wk_current_configurations(timeoutMillis));
}

JNIEXPORT jstring JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeCurrentPushInfo(
    JNIEnv *env, jclass clazz, jint timeoutMillis) {
    return takeJString(env, nucleus_wk_current_push_info(timeoutMillis));
}

JNIEXPORT jstring JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeUserInfoKey(
    JNIEnv *env, jclass clazz, jint which) {
    return takeJString(env, nucleus_wk_user_info_key(which));
}

JNIEXPORT jboolean JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeControlsAvailable(
    JNIEnv *env, jclass clazz) {
    return nucleus_wk_controls_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeReloadControls(
    JNIEnv *env, jclass clazz, jstring kind) {
    const char *chars = (*env)->GetStringUTFChars(env, kind, NULL);
    nucleus_wk_reload_controls(chars);
    (*env)->ReleaseStringUTFChars(env, kind, chars);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeReloadAllControls(
    JNIEnv *env, jclass clazz) {
    nucleus_wk_reload_all_controls();
}

JNIEXPORT jstring JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeCurrentControls(
    JNIEnv *env, jclass clazz, jint timeoutMillis) {
    return takeJString(env, nucleus_wk_current_controls(timeoutMillis));
}

// MARK: - Darwin notifications

static void fireNotificationToJVM(int token) {
    if (g_jvm == NULL || g_bridgeClass == NULL) return;
    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    BOOL didAttach = NO;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = YES;
    } else if (attached != JNI_OK) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, g_bridgeClass, g_onNotificationMethod, (jint)token);
    nucleus_jni_clear_exception(env);
    if (didAttach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

JNIEXPORT jboolean JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeNotifyPost(
    JNIEnv *env, jclass clazz, jstring name) {
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    uint32_t status = notify_post(chars);
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return status == NOTIFY_STATUS_OK ? JNI_TRUE : JNI_FALSE;
}

// Returns the registration token, or -1. Handlers run on one serial queue, in posting order.
JNIEXPORT jint JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeNotifyRegister(
    JNIEnv *env, jclass clazz, jstring name) {
    static dispatch_queue_t queue;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        queue = dispatch_queue_create("dev.nucleusframework.widget.darwin-notifications", DISPATCH_QUEUE_SERIAL);
    });
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    int token = -1;
    uint32_t status = notify_register_dispatch(chars, &token, queue, ^(int fired) { fireNotificationToJVM(fired); });
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return status == NOTIFY_STATUS_OK ? token : -1;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeNotifyCancel(
    JNIEnv *env, jclass clazz, jint token) {
    notify_cancel(token);
}

// MARK: - Embedded widget extension

static NSString *realHome(void) {
    struct passwd *pw = getpwuid(getuid());
    return pw != NULL && pw->pw_dir != NULL ? [NSString stringWithUTF8String:pw->pw_dir] : NSHomeDirectory();
}

// The Nucleus widget extension of the running app bundle, as written by the Gradle plugin:
// [extension bundle id, app bundle id, store directory, then one "kind\tfamily,family" per
// widget], or null when the app has none (not bundled, or no widgets declared).
JNIEXPORT jobjectArray JNICALL Java_dev_nucleusframework_widget_macos_NativeWidgetKitBridge_nativeExtensionConfig(
    JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        NSURL *plugIns = NSBundle.mainBundle.builtInPlugInsURL;
        if (plugIns == nil) return NULL;
        NSArray<NSURL *> *entries = [NSFileManager.defaultManager contentsOfDirectoryAtURL:plugIns
                                                                includingPropertiesForKeys:nil
                                                                                   options:0
                                                                                     error:NULL];
        for (NSURL *entry in entries) {
            if (![entry.pathExtension isEqualToString:@"appex"]) continue;
            NSBundle *extension = [NSBundle bundleWithURL:entry];
            NSDictionary *info = extension.infoDictionary;
            NSDictionary *config = info[@"NucleusWidgets"];
            if (![config isKindOfClass:NSDictionary.class]) continue;

            NSString *store = nil;
            NSString *group = config[@"StoreAppGroup"];
            NSString *homePath = config[@"StoreHomePath"];
            if ([group isKindOfClass:NSString.class]) {
                store = [NSFileManager.defaultManager containerURLForSecurityApplicationGroupIdentifier:group].path;
            } else if ([homePath isKindOfClass:NSString.class]) {
                store = [realHome() stringByAppendingPathComponent:homePath];
            }
            if (store == nil) return NULL;

            NSMutableArray<NSString *> *values = [NSMutableArray array];
            [values addObject:extension.bundleIdentifier ?: @""];
            NSString *app = config[@"AppBundleIdentifier"];
            [values addObject:[app isKindOfClass:NSString.class] ? app : (NSBundle.mainBundle.bundleIdentifier ?: @"")];
            [values addObject:store];
            for (NSDictionary *widget in config[@"Widgets"]) {
                if (![widget isKindOfClass:NSDictionary.class] || ![widget[@"Kind"] isKindOfClass:NSString.class]) continue;
                NSArray *families = [widget[@"Families"] isKindOfClass:NSArray.class] ? widget[@"Families"] : @[];
                [values addObject:[NSString stringWithFormat:@"%@\t%@", widget[@"Kind"], [families componentsJoinedByString:@","]]];
            }

            jobjectArray result = (*env)->NewObjectArray(env, (jsize)values.count, (*env)->FindClass(env, "java/lang/String"), NULL);
            for (NSUInteger i = 0; i < values.count; i++) {
                jstring value = toJString(env, values[i]);
                (*env)->SetObjectArrayElement(env, result, (jsize)i, value);
                (*env)->DeleteLocalRef(env, value);
            }
            return result;
        }
        return NULL;
    }
}
