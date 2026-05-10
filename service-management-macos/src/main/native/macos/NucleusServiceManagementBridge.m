#import <Cocoa/Cocoa.h>
#import <ServiceManagement/ServiceManagement.h>
#include <jni.h>

// ============================================================================
// Globals
// ============================================================================

static JavaVM *g_jvm = NULL;

#define BRIDGE_CLASS "dev/nucleusframework/nucleus/servicemanagement/macos/NativeMacServiceManagementBridge"

// Service types (must match AppService.kt companion constants)
#define TYPE_LOGIN_ITEM 0
#define TYPE_AGENT      1
#define TYPE_DAEMON     2
#define TYPE_MAIN_APP   3

// ============================================================================
// JNI helpers
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static JNIEnv *getEnv(BOOL *didAttach) {
    *didAttach = NO;
    if (g_jvm == NULL) return NULL;

    JNIEnv *env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) {
            return NULL;
        }
        *didAttach = YES;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void releaseEnv(BOOL didAttach) {
    if (didAttach && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void clearException(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static jstring toJString(JNIEnv *env, NSString *str) {
    if (str == nil) return NULL;
    const char *utf = [str UTF8String];
    return (*env)->NewStringUTF(env, utf ? utf : "");
}

static NSString *toNSString(JNIEnv *env, jstring jstr) {
    if (jstr == NULL) return nil;
    const char *utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (utf == NULL) return nil;
    NSString *str = [NSString stringWithUTF8String:utf];
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return str;
}

// ============================================================================
// SMAppService factory helper
// ============================================================================

/**
 * Creates an SMAppService instance for the given type and identifier.
 * Returns nil if macOS < 13.0 or invalid type.
 */
API_AVAILABLE(macos(13.0))
static SMAppService *createService(int type, NSString *identifier) {
    switch (type) {
        case TYPE_MAIN_APP:
            return [SMAppService mainAppService];
        case TYPE_LOGIN_ITEM:
            return [SMAppService loginItemServiceWithIdentifier:identifier];
        case TYPE_AGENT:
            return [SMAppService agentServiceWithPlistName:identifier];
        case TYPE_DAEMON:
            return [SMAppService daemonServiceWithPlistName:identifier];
        default:
            return nil;
    }
}

// ============================================================================
// JNI functions
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_servicemanagement_macos_NativeMacServiceManagementBridge_nativeIsAvailable(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (@available(macOS 13.0, *)) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_servicemanagement_macos_NativeMacServiceManagementBridge_nativeRegister(
    JNIEnv *env, jclass clazz, jint type, jstring jidentifier) {
    (void)clazz;

    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            NSString *identifier = toNSString(env, jidentifier);
            if (identifier == nil) {
                return toJString(env, @"Identifier must not be null");
            }

            SMAppService *service = createService(type, identifier);
            if (service == nil) {
                return toJString(env, @"Invalid service type");
            }

            NSError *error = nil;
            BOOL success = [service registerAndReturnError:&error];
            if (success) {
                return NULL; // success
            } else {
                NSString *msg = error.localizedDescription ?: @"Unknown registration error";
                return toJString(env, msg);
            }
        }
    }

    return toJString(env, @"SMAppService requires macOS 13.0+");
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_servicemanagement_macos_NativeMacServiceManagementBridge_nativeUnregister(
    JNIEnv *env, jclass clazz, jint type, jstring jidentifier, jlong callbackId) {
    (void)clazz;

    if (@available(macOS 13.0, *)) {
        NSString *identifier = toNSString(env, jidentifier);
        if (identifier == nil) {
            // Callback with error immediately
            BOOL didAttach = NO;
            JNIEnv *cbEnv = getEnv(&didAttach);
            if (cbEnv) {
                jclass bridgeClass = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (bridgeClass) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, bridgeClass,
                        "onUnregisterResult", "(JLjava/lang/String;)V");
                    if (method) {
                        jstring error = toJString(cbEnv, @"Identifier must not be null");
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, bridgeClass, method, callbackId, error);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
            return;
        }

        SMAppService *service = createService(type, identifier);
        if (service == nil) {
            BOOL didAttach = NO;
            JNIEnv *cbEnv = getEnv(&didAttach);
            if (cbEnv) {
                jclass bridgeClass = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (bridgeClass) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, bridgeClass,
                        "onUnregisterResult", "(JLjava/lang/String;)V");
                    if (method) {
                        jstring error = toJString(cbEnv, @"Invalid service type");
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, bridgeClass, method, callbackId, error);
                    }
                }
                clearException(cbEnv);
                releaseEnv(didAttach);
            }
            return;
        }

        [service unregisterWithCompletionHandler:^(NSError *error) {
            @autoreleasepool {
                BOOL didAttach = NO;
                JNIEnv *cbEnv = getEnv(&didAttach);
                if (cbEnv == NULL) return;

                jclass bridgeClass = (*cbEnv)->FindClass(cbEnv, BRIDGE_CLASS);
                if (bridgeClass != NULL) {
                    jmethodID method = (*cbEnv)->GetStaticMethodID(cbEnv, bridgeClass,
                        "onUnregisterResult", "(JLjava/lang/String;)V");
                    if (method != NULL) {
                        jstring jerror = NULL;
                        if (error != nil) {
                            jerror = toJString(cbEnv, error.localizedDescription);
                        }
                        (*cbEnv)->CallStaticVoidMethod(cbEnv, bridgeClass, method, callbackId, jerror);
                    }
                }

                clearException(cbEnv);
                releaseEnv(didAttach);
            }
        }];
    } else {
        // macOS < 13.0 — callback with error
        jclass bridgeClass = (*env)->FindClass(env, BRIDGE_CLASS);
        if (bridgeClass) {
            jmethodID method = (*env)->GetStaticMethodID(env, bridgeClass,
                "onUnregisterResult", "(JLjava/lang/String;)V");
            if (method) {
                jstring error = toJString(env, @"SMAppService requires macOS 13.0+");
                (*env)->CallStaticVoidMethod(env, bridgeClass, method, callbackId, error);
            }
        }
        clearException(env);
    }
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_servicemanagement_macos_NativeMacServiceManagementBridge_nativeGetStatus(
    JNIEnv *env, jclass clazz, jint type, jstring jidentifier) {
    (void)clazz;

    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            NSString *identifier = toNSString(env, jidentifier);
            if (identifier == nil) return 3; // NOT_FOUND

            SMAppService *service = createService(type, identifier);
            if (service == nil) return 3; // NOT_FOUND

            return (jint)service.status;
        }
    }

    return 0; // NOT_REGISTERED
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_servicemanagement_macos_NativeMacServiceManagementBridge_nativeOpenSystemSettingsLoginItems(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    if (@available(macOS 13.0, *)) {
        @autoreleasepool {
            [SMAppService openSystemSettingsLoginItems];
            return JNI_TRUE;
        }
    }

    return JNI_FALSE;
}
