// Common includes and helpers for nucleus_system_info native Linux implementation.

#ifndef NUCLEUS_SYSTEM_INFO_COMMON_H
#define NUCLEUS_SYSTEM_INFO_COMMON_H

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// JNI class path for the Linux bridge
#define BRIDGE_CLASS "dev/nucleusframework/systeminfo/linux/NativeLinuxSystemInfoBridge"

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

// Helper: read a single-line file into a buffer, returns bytes read or -1
static inline int read_file_line(const char *path, char *buf, size_t bufsize) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char *result = fgets(buf, (int)bufsize, f);
    fclose(f);
    if (!result) return -1;
    // Strip trailing newline
    size_t len = strlen(buf);
    if (len > 0 && buf[len - 1] == '\n') buf[len - 1] = '\0';
    return (int)strlen(buf);
}

// Helper: read entire file into a malloc'd buffer, returns NULL on failure.
// Works with /proc and /sys virtual files that report size 0.
static inline char *read_file_contents(const char *path, size_t *out_len) {
    FILE *f = fopen(path, "r");
    if (!f) return NULL;
    size_t capacity = 4096;
    size_t total = 0;
    char *buf = (char *)malloc(capacity);
    if (!buf) { fclose(f); return NULL; }
    size_t n;
    while ((n = fread(buf + total, 1, capacity - total - 1, f)) > 0) {
        total += n;
        if (total + 1 >= capacity) {
            capacity *= 2;
            char *tmp = (char *)realloc(buf, capacity);
            if (!tmp) { free(buf); fclose(f); return NULL; }
            buf = tmp;
        }
    }
    fclose(f);
    if (total == 0) { free(buf); return NULL; }
    buf[total] = '\0';
    if (out_len) *out_len = total;
    return buf;
}

#endif // NUCLEUS_SYSTEM_INFO_COMMON_H
