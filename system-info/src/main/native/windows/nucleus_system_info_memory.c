// Memory information: total, free, available, used memory and swap.
// Sources: GlobalMemoryStatusEx, K32GetPerformanceInfo

#include "nucleus_system_info_common.h"
#include <psapi.h>

static BOOL get_mem_status(MEMORYSTATUSEX *ms) {
    ms->dwLength = sizeof(*ms);
    return GlobalMemoryStatusEx(ms);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeTotalMemory(
    JNIEnv *env, jclass clazz) {
    MEMORYSTATUSEX ms;
    if (!get_mem_status(&ms)) return 0;
    return (jlong)ms.ullTotalPhys;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeFreeMemory(
    JNIEnv *env, jclass clazz) {
    MEMORYSTATUSEX ms;
    if (!get_mem_status(&ms)) return 0;
    return (jlong)ms.ullAvailPhys;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeAvailableMemory(
    JNIEnv *env, jclass clazz) {
    MEMORYSTATUSEX ms;
    if (!get_mem_status(&ms)) return 0;
    return (jlong)ms.ullAvailPhys;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUsedMemory(
    JNIEnv *env, jclass clazz) {
    MEMORYSTATUSEX ms;
    if (!get_mem_status(&ms)) return 0;
    return (jlong)(ms.ullTotalPhys - ms.ullAvailPhys);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeTotalSwap(
    JNIEnv *env, jclass clazz) {
    PERFORMANCE_INFORMATION pi;
    pi.cb = sizeof(pi);
    if (!K32GetPerformanceInfo(&pi, sizeof(pi))) return 0;
    // Swap = commit limit minus physical total (in pages)
    ULONGLONG swap_pages = (ULONGLONG)pi.CommitLimit - (ULONGLONG)pi.PhysicalTotal;
    return (jlong)(swap_pages * pi.PageSize);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeFreeSwap(
    JNIEnv *env, jclass clazz) {
    PERFORMANCE_INFORMATION pi;
    pi.cb = sizeof(pi);
    if (!K32GetPerformanceInfo(&pi, sizeof(pi))) return 0;
    ULONGLONG swap_total = (ULONGLONG)pi.CommitLimit - (ULONGLONG)pi.PhysicalTotal;
    ULONGLONG swap_used;
    if (pi.CommitTotal > pi.PhysicalTotal) {
        swap_used = (ULONGLONG)pi.CommitTotal - (ULONGLONG)pi.PhysicalTotal;
    } else {
        swap_used = 0;
    }
    ULONGLONG swap_free = (swap_used < swap_total) ? (swap_total - swap_used) : 0;
    return (jlong)(swap_free * pi.PageSize);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUsedSwap(
    JNIEnv *env, jclass clazz) {
    PERFORMANCE_INFORMATION pi;
    pi.cb = sizeof(pi);
    if (!K32GetPerformanceInfo(&pi, sizeof(pi))) return 0;
    ULONGLONG swap_used;
    if (pi.CommitTotal > pi.PhysicalTotal) {
        swap_used = (ULONGLONG)pi.CommitTotal - (ULONGLONG)pi.PhysicalTotal;
    } else {
        swap_used = 0;
    }
    return (jlong)(swap_used * pi.PageSize);
}
