#import <Cocoa/Cocoa.h>
#include <jni.h>

static JavaVM *g_jvm = NULL;
static id g_colorObserver = nil;
static id g_contrastObserver = nil;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

// Returns YES if the user has chosen a specific accent color, NO if multicolor mode is active.
static BOOL isAccentColorSet(void) {
    return [[NSUserDefaults standardUserDefaults] objectForKey:@"AppleAccentColor"] != nil;
}

// Helper: call back into Kotlin with accent color RGB floats (or null if multicolor)
static void notifyAccentColorChanged(void) {
    if (g_jvm == NULL) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    BOOL didAttach = NO;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = YES;
    } else if (attached != JNI_OK) {
        return;
    }

    jclass bridgeClass = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/systemcolor/mac/NativeMacSystemColorBridge");
    if (bridgeClass != NULL) {
        if (!isAccentColorSet()) {
            // Multicolor mode: notify with null
            jmethodID method = (*env)->GetStaticMethodID(env,
                bridgeClass, "onAccentColorCleared", "()V");
            if (method != NULL) {
                (*env)->CallStaticVoidMethod(env, bridgeClass, method);
            }
        } else if (@available(macOS 10.14, *)) {
            NSColor *color = [[NSColor controlAccentColor]
                colorUsingColorSpace:[NSColorSpace genericRGBColorSpace]];
            if (color != nil) {
                jfloat r = (jfloat)[color redComponent];
                jfloat g = (jfloat)[color greenComponent];
                jfloat b = (jfloat)[color blueComponent];

                jmethodID method = (*env)->GetStaticMethodID(env,
                    bridgeClass, "onAccentColorChanged", "(FFF)V");
                if (method != NULL) {
                    (*env)->CallStaticVoidMethod(env, bridgeClass, method, r, g, b);
                }
            }
        }
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// Helper: call back into Kotlin with contrast mode boolean
static void notifyContrastChanged(void) {
    if (g_jvm == NULL) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    BOOL didAttach = NO;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = YES;
    } else if (attached != JNI_OK) {
        return;
    }

    jboolean isHigh = [[NSWorkspace sharedWorkspace]
        accessibilityDisplayShouldIncreaseContrast] ? JNI_TRUE : JNI_FALSE;

    jclass bridgeClass = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/systemcolor/mac/NativeMacSystemColorBridge");
    if (bridgeClass != NULL) {
        jmethodID method = (*env)->GetStaticMethodID(env,
            bridgeClass, "onContrastChanged", "(Z)V");
        if (method != NULL) {
            (*env)->CallStaticVoidMethod(env, bridgeClass, method, isHigh);
        }
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

// --- JNI exports ---

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_mac_NativeMacSystemColorBridge_nativeGetAccentColor(
    JNIEnv *env, jclass clazz, jfloatArray out) {
    @autoreleasepool {
        // In multicolor mode, AppleAccentColor is absent — return false so Kotlin gets null
        if (!isAccentColorSet()) {
            return JNI_FALSE;
        }
        if (@available(macOS 10.14, *)) {
            NSColor *color = [[NSColor controlAccentColor]
                colorUsingColorSpace:[NSColorSpace genericRGBColorSpace]];
            if (color != nil) {
                jfloat rgb[3];
                rgb[0] = (jfloat)[color redComponent];
                rgb[1] = (jfloat)[color greenComponent];
                rgb[2] = (jfloat)[color blueComponent];
                (*env)->SetFloatArrayRegion(env, out, 0, 3, rgb);
                return JNI_TRUE;
            }
        }
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_mac_NativeMacSystemColorBridge_nativeIsHighContrast(
    JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        return [[NSWorkspace sharedWorkspace]
            accessibilityDisplayShouldIncreaseContrast] ? JNI_TRUE : JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_mac_NativeMacSystemColorBridge_nativeIsAccentColorSupported(
    JNIEnv *env, jclass clazz) {
    if (@available(macOS 10.14, *)) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_mac_NativeMacSystemColorBridge_nativeStartObserving(
    JNIEnv *env, jclass clazz) {
    if (g_colorObserver != nil) return;

    // Observe accent color changes
    g_colorObserver = [[NSNotificationCenter defaultCenter]
        addObserverForName:NSSystemColorsDidChangeNotification
                    object:nil
                     queue:nil
                usingBlock:^(NSNotification *note) {
                    notifyAccentColorChanged();
                }];

    // Observe accessibility contrast changes
    g_contrastObserver = [[[NSWorkspace sharedWorkspace] notificationCenter]
        addObserverForName:NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification
                    object:nil
                     queue:nil
                usingBlock:^(NSNotification *note) {
                    notifyContrastChanged();
                }];
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_mac_NativeMacSystemColorBridge_nativeStopObserving(
    JNIEnv *env, jclass clazz) {
    if (g_colorObserver != nil) {
        [[NSNotificationCenter defaultCenter] removeObserver:g_colorObserver];
        g_colorObserver = nil;
    }
    if (g_contrastObserver != nil) {
        [[[NSWorkspace sharedWorkspace] notificationCenter] removeObserver:g_contrastObserver];
        g_contrastObserver = nil;
    }
}
