// Common includes and helpers for nucleus_system_info native macOS implementation.

#ifndef NUCLEUS_SYSTEM_INFO_COMMON_H
#define NUCLEUS_SYSTEM_INFO_COMMON_H

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/sysctl.h>

// JNI class path for the macOS bridge
#define BRIDGE_CLASS "dev/nucleusframework/nucleus/systeminfo/macos/NativeMacOsSystemInfoBridge"

// Helper: create a Java String from a C string, handling NULL gracefully
static inline jstring to_jstring(JNIEnv *env, const char *str) {
    if (!str) return NULL;
    return (*env)->NewStringUTF(env, str);
}

// Helper: create a Java String[] from a C string array
static inline jobjectArray to_string_array(JNIEnv *env, const char **strings, int count) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, count, string_class, NULL);
    for (int i = 0; i < count; i++) {
        jstring js = to_jstring(env, strings[i]);
        (*env)->SetObjectArrayElement(env, arr, i, js);
        if (js) (*env)->DeleteLocalRef(env, js);
    }
    (*env)->DeleteLocalRef(env, string_class);
    return arr;
}

// Helper: read a sysctl string value into a malloc'd buffer. Caller must free.
static inline char *sysctl_string(const char *name) {
    size_t len = 0;
    if (sysctlbyname(name, NULL, &len, NULL, 0) != 0 || len == 0) return NULL;
    char *buf = (char *)malloc(len);
    if (!buf) return NULL;
    if (sysctlbyname(name, buf, &len, NULL, 0) != 0) { free(buf); return NULL; }
    return buf;
}

// Helper: read a sysctl 32-bit integer
static inline int sysctl_int32(const char *name, int32_t *out) {
    size_t len = sizeof(int32_t);
    return sysctlbyname(name, out, &len, NULL, 0);
}

// Helper: read a sysctl 64-bit integer
static inline int sysctl_int64(const char *name, int64_t *out) {
    size_t len = sizeof(int64_t);
    return sysctlbyname(name, out, &len, NULL, 0);
}

#endif // NUCLEUS_SYSTEM_INFO_COMMON_H
