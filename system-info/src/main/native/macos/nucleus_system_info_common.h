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
#define BRIDGE_CLASS "dev/nucleusframework/systeminfo/macos/NativeMacOsSystemInfoBridge"

// Helper: create a Java String from a C string, handling NULL gracefully
static inline jstring to_jstring(JNIEnv *env, const char *str) {
    if (!str) return NULL;
    return (*env)->NewStringUTF(env, str);
}

// Helper: create a Java String from a byte buffer that may contain NULs (a
// NUL-separated argv). NewStringUTF stops at the first NUL and requires
// modified UTF-8, so decode to UTF-16 here instead; NULs are kept as U+0000 and
// invalid UTF-8 sequences become U+FFFD.
static inline jstring to_jstring_bytes(JNIEnv *env, const char *buf, size_t len) {
    if (!buf) return NULL;
    // UTF-16 never needs more code units than UTF-8 needs bytes.
    jchar *out = (jchar *)malloc((len > 0 ? len : 1) * sizeof(jchar));
    if (!out) return NULL;
    const unsigned char *s = (const unsigned char *)buf;
    size_t n = 0, i = 0;
    while (i < len) {
        unsigned int c = s[i];
        if (c < 0x80) { out[n++] = (jchar)c; i++; continue; }
        int extra;
        unsigned int cp, min;
        if ((c & 0xE0) == 0xC0)      { extra = 1; cp = c & 0x1F; min = 0x80; }
        else if ((c & 0xF0) == 0xE0) { extra = 2; cp = c & 0x0F; min = 0x800; }
        else if ((c & 0xF8) == 0xF0) { extra = 3; cp = c & 0x07; min = 0x10000; }
        else { out[n++] = 0xFFFD; i++; continue; }
        int valid = i + (size_t)extra < len;
        for (int k = 1; valid && k <= extra; k++) {
            if ((s[i + k] & 0xC0) != 0x80) valid = 0;
            else cp = (cp << 6) | (s[i + k] & 0x3F);
        }
        if (!valid || cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out[n++] = 0xFFFD; i++; continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out[n++] = (jchar)(0xD800 | (cp >> 10));
            out[n++] = (jchar)(0xDC00 | (cp & 0x3FF));
        } else {
            out[n++] = (jchar)cp;
        }
        i += (size_t)extra + 1;
    }
    jstring result = (*env)->NewString(env, out, (jsize)n);
    free(out);
    return result;
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
