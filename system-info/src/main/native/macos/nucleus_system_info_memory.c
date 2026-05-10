// Memory information: total, free, available, used memory and swap.
// Sources: sysctl hw.memsize, host_statistics64(), sysctl vm.swapusage

#include "nucleus_system_info_common.h"
#include <mach/mach.h>
#include <mach/mach_host.h>

static vm_size_t get_page_size(void) {
    static vm_size_t page_size = 0;
    if (page_size == 0) {
        host_page_size(mach_host_self(), &page_size);
    }
    return page_size;
}

static int get_vm_stats(vm_statistics64_data_t *stats) {
    mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
    return host_statistics64(mach_host_self(), HOST_VM_INFO64,
                             (host_info64_t)stats, &count) == KERN_SUCCESS ? 0 : -1;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeTotalMemory(
    JNIEnv *env, jclass clazz) {
    int64_t total = 0;
    sysctl_int64("hw.memsize", &total);
    return (jlong)total;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeFreeMemory(
    JNIEnv *env, jclass clazz) {
    vm_statistics64_data_t stats;
    if (get_vm_stats(&stats) != 0) return 0;
    return (jlong)((uint64_t)stats.free_count * get_page_size());
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeAvailableMemory(
    JNIEnv *env, jclass clazz) {
    int64_t total = 0;
    sysctl_int64("hw.memsize", &total);
    vm_statistics64_data_t stats;
    if (get_vm_stats(&stats) != 0) return 0;
    vm_size_t ps = get_page_size();
    // Used = active + wired + compressor (compressed pages in RAM)
    uint64_t used = ((uint64_t)stats.active_count + stats.wire_count +
                     stats.compressor_page_count) * ps;
    return (jlong)((uint64_t)total > used ? (uint64_t)total - used : 0);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUsedMemory(
    JNIEnv *env, jclass clazz) {
    vm_statistics64_data_t stats;
    if (get_vm_stats(&stats) != 0) return 0;
    vm_size_t ps = get_page_size();
    return (jlong)(((uint64_t)stats.active_count + stats.wire_count +
                    stats.compressor_page_count) * ps);
}

// Swap via sysctl vm.swapusage
typedef struct {
    uint64_t total;
    uint64_t avail;
    uint64_t used;
} swap_info_t;

static int get_swap_info(swap_info_t *si) {
    struct xsw_usage xsw;
    size_t len = sizeof(xsw);
    if (sysctlbyname("vm.swapusage", &xsw, &len, NULL, 0) != 0) return -1;
    si->total = xsw.xsu_total;
    si->avail = xsw.xsu_avail;
    si->used = xsw.xsu_used;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeTotalSwap(
    JNIEnv *env, jclass clazz) {
    swap_info_t si;
    if (get_swap_info(&si) != 0) return 0;
    return (jlong)si.total;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeFreeSwap(
    JNIEnv *env, jclass clazz) {
    swap_info_t si;
    if (get_swap_info(&si) != 0) return 0;
    return (jlong)si.avail;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUsedSwap(
    JNIEnv *env, jclass clazz) {
    swap_info_t si;
    if (get_swap_info(&si) != 0) return 0;
    return (jlong)si.used;
}
