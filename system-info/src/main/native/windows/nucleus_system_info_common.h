// Common includes and helpers for nucleus_system_info native Windows implementation.

#ifndef NUCLEUS_SYSTEM_INFO_COMMON_H
#define NUCLEUS_SYSTEM_INFO_COMMON_H

#ifndef WINVER
#define WINVER 0x0601
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#ifndef NTDDI_VERSION
#define NTDDI_VERSION 0x06010000
#endif

#include <jni.h>
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// JNI class path for the Windows bridge
#define BRIDGE_CLASS "dev/nucleusframework/nucleus/systeminfo/windows/NativeWindowsSystemInfoBridge"

// Helper: convert a wide string to a UTF-8 malloc'd string. Caller must free.
static inline char *wchar_to_utf8(const wchar_t *wstr) {
    if (!wstr || !wstr[0]) return NULL;
    int len = WideCharToMultiByte(CP_UTF8, 0, wstr, -1, NULL, 0, NULL, NULL);
    if (len <= 0) return NULL;
    char *buf = (char *)malloc(len);
    if (!buf) return NULL;
    WideCharToMultiByte(CP_UTF8, 0, wstr, -1, buf, len, NULL, NULL);
    return buf;
}

// Helper: create a Java String from a UTF-8 C string, handling NULL gracefully
static inline jstring to_jstring(JNIEnv *env, const char *str) {
    if (!str) return NULL;
    return (*env)->NewStringUTF(env, str);
}

// Helper: create a Java String from a wide string
static inline jstring wchar_to_jstring(JNIEnv *env, const wchar_t *wstr) {
    if (!wstr || !wstr[0]) return NULL;
    char *utf8 = wchar_to_utf8(wstr);
    if (!utf8) return NULL;
    jstring result = (*env)->NewStringUTF(env, utf8);
    free(utf8);
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

// Helper: read a DWORD value from the registry. Returns TRUE on success.
static inline BOOL reg_read_dword(HKEY root, const wchar_t *subkey, const wchar_t *value_name, DWORD *out) {
    HKEY hk;
    if (RegOpenKeyExW(root, subkey, 0, KEY_READ, &hk) != ERROR_SUCCESS) return FALSE;
    DWORD type, size = sizeof(DWORD);
    LONG ret = RegQueryValueExW(hk, value_name, NULL, &type, (LPBYTE)out, &size);
    RegCloseKey(hk);
    return (ret == ERROR_SUCCESS && type == REG_DWORD);
}

// Helper: read a string value from the registry into a malloc'd UTF-8 buffer. Caller must free.
static inline char *reg_read_string(HKEY root, const wchar_t *subkey, const wchar_t *value_name) {
    HKEY hk;
    if (RegOpenKeyExW(root, subkey, 0, KEY_READ, &hk) != ERROR_SUCCESS) return NULL;
    DWORD type, size = 0;
    if (RegQueryValueExW(hk, value_name, NULL, &type, NULL, &size) != ERROR_SUCCESS || type != REG_SZ) {
        RegCloseKey(hk);
        return NULL;
    }
    wchar_t *wbuf = (wchar_t *)malloc(size + sizeof(wchar_t));
    if (!wbuf) { RegCloseKey(hk); return NULL; }
    memset(wbuf, 0, size + sizeof(wchar_t));
    if (RegQueryValueExW(hk, value_name, NULL, NULL, (LPBYTE)wbuf, &size) != ERROR_SUCCESS) {
        free(wbuf);
        RegCloseKey(hk);
        return NULL;
    }
    RegCloseKey(hk);
    char *result = wchar_to_utf8(wbuf);
    free(wbuf);
    return result;
}

#endif // NUCLEUS_SYSTEM_INFO_COMMON_H
