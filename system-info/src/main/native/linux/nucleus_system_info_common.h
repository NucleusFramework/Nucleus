// Common includes and helpers for nucleus_system_info native Linux implementation.

#ifndef NUCLEUS_SYSTEM_INFO_COMMON_H
#define NUCLEUS_SYSTEM_INFO_COMMON_H

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// JNI class path for the Linux bridge
#define BRIDGE_CLASS "dev/nucleusframework/nucleus/systeminfo/linux/NativeLinuxSystemInfoBridge"

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
