#import <Cocoa/Cocoa.h>
#include <jni.h>

// Cached JavaVM pointer, set in JNI_OnLoad
static JavaVM *g_jvm = NULL;

// Observer token returned by NSDistributedNotificationCenter
static id g_observer = nil;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_mac_NativeDarkModeBridge_nativeIsDark(
    JNIEnv *env, jclass clazz) {
    @autoreleasepool {
        NSString *style = [[NSUserDefaults standardUserDefaults]
                            stringForKey:@"AppleInterfaceStyle"];
        if (style != nil &&
            [style rangeOfString:@"dark" options:NSCaseInsensitiveSearch].location != NSNotFound) {
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_mac_NativeDarkModeBridge_nativeStartObserving(
    JNIEnv *env, jclass clazz) {
    if (g_observer != nil) return; // already observing

    g_observer = [[NSDistributedNotificationCenter defaultCenter]
        addObserverForName:@"AppleInterfaceThemeChangedNotification"
                    object:nil
                     queue:nil
                usingBlock:^(NSNotification *note) {
                    @autoreleasepool {
                        if (g_jvm == NULL) return;

                        JNIEnv *cbEnv = NULL;
                        jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&cbEnv, JNI_VERSION_1_8);
                        BOOL didAttach = NO;
                        if (attached == JNI_EDETACHED) {
                            if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&cbEnv, NULL) != JNI_OK) {
                                return;
                            }
                            didAttach = YES;
                        } else if (attached != JNI_OK) {
                            return;
                        }

                        NSString *style = [[NSUserDefaults standardUserDefaults]
                                            stringForKey:@"AppleInterfaceStyle"];
                        jboolean isDark = (style != nil &&
                            [style rangeOfString:@"dark" options:NSCaseInsensitiveSearch].location != NSNotFound)
                            ? JNI_TRUE : JNI_FALSE;

                        jclass bridgeClass = (*cbEnv)->FindClass(cbEnv,
                            "dev/nucleusframework/nucleus/darkmodedetector/mac/NativeDarkModeBridge");
                        if (bridgeClass != NULL) {
                            jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv,
                                bridgeClass, "onThemeChanged", "(Z)V");
                            if (method != NULL) {
                                (*cbEnv)->CallStaticVoidMethod(cbEnv, bridgeClass, method, isDark);
                            }
                        }

                        if ((*cbEnv)->ExceptionCheck(cbEnv)) {
                            (*cbEnv)->ExceptionClear(cbEnv);
                        }

                        if (didAttach) {
                            (*g_jvm)->DetachCurrentThread(g_jvm);
                        }
                    }
                }];
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_mac_NativeDarkModeBridge_nativeStopObserving(
    JNIEnv *env, jclass clazz) {
    if (g_observer != nil) {
        [[NSDistributedNotificationCenter defaultCenter] removeObserver:g_observer];
        g_observer = nil;
    }
}
