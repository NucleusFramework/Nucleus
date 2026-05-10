// Memory information: total, free, available, used memory and swap.
// Sources: /proc/meminfo

#include "nucleus_system_info_common.h"

// Parse a value in kB from /proc/meminfo for the given key
static long long parse_meminfo_kb(const char *contents, const char *key) {
    const char *p = strstr(contents, key);
    if (!p) return -1;
    p += strlen(key);
    // Skip to the number
    while (*p && (*p == ' ' || *p == ':')) p++;
    return atoll(p) * 1024; // Convert kB to bytes
}

typedef struct {
    long long total;
    long long free;
    long long available;
    long long buffers;
    long long cached;
    long long swap_total;
    long long swap_free;
} meminfo_t;

static int read_meminfo(meminfo_t *mi) {
    size_t len;
    char *contents = read_file_contents("/proc/meminfo", &len);
    if (!contents) return -1;
    mi->total = parse_meminfo_kb(contents, "MemTotal:");
    mi->free = parse_meminfo_kb(contents, "MemFree:");
    mi->available = parse_meminfo_kb(contents, "MemAvailable:");
    mi->buffers = parse_meminfo_kb(contents, "Buffers:");
    mi->cached = parse_meminfo_kb(contents, "Cached:");
    mi->swap_total = parse_meminfo_kb(contents, "SwapTotal:");
    mi->swap_free = parse_meminfo_kb(contents, "SwapFree:");
    free(contents);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeTotalMemory(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)mi.total;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeFreeMemory(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)mi.free;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeAvailableMemory(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)mi.available;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeUsedMemory(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)(mi.total - mi.available);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeTotalSwap(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)mi.swap_total;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeFreeSwap(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)mi.swap_free;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeUsedSwap(
    JNIEnv *env, jclass clazz) {
    meminfo_t mi;
    if (read_meminfo(&mi) != 0) return 0;
    return (jlong)(mi.swap_total - mi.swap_free);
}
