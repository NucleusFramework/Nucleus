// CPU information: per-core details, usage, frequency, physical core count.
// Sources: sysctl, host_processor_info(), IOKit (Apple Silicon frequency)

#include "nucleus_system_info_common.h"
#include <mach/mach.h>
#include <mach/processor_info.h>
#include <mach/mach_host.h>
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>

#define MAX_CPUS 1024

// Static storage for CPU usage calculation (requires two samples)
static natural_t prev_ticks[MAX_CPUS][CPU_STATE_MAX];
static unsigned long long g_prev_total = 0, g_prev_active = 0;
static int usage_initialized = 0;

static int count_logical_cpus(void) {
    int32_t count = 0;
    sysctl_int32("hw.logicalcpu", &count);
    return count > 0 ? count : (int)sysconf(_SC_NPROCESSORS_ONLN);
}

// Get CPU max frequency in MHz via IOKit (Apple Silicon) or sysctl (Intel)
static jlong get_cpu_frequency_mhz(void) {
    // Intel: hw.cpufrequency is available
    int64_t freq_hz = 0;
    if (sysctl_int64("hw.cpufrequency", &freq_hz) == 0 && freq_hz > 0) {
        return (jlong)(freq_hz / 1000000);
    }

    // Apple Silicon: read from IOKit device tree "voltage-states5-sram" or similar
    // The property contains pairs of (frequency_hz: uint32_le, voltage: uint32_le)
    // The last pair holds the maximum frequency.
    static const char *freq_props[] = {
        "voltage-states5-sram",  // P-cores on most Apple Silicon
        "voltage-states9-sram",  // P-cores on some chips (M3+)
        "voltage-states1-sram",  // E-cores (fallback)
        NULL
    };

    CFMutableDictionaryRef matching = IOServiceMatching("AppleARMIODevice");
    if (!matching) return 0;

    io_iterator_t iter;
    if (IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iter) != KERN_SUCCESS) {
        return 0;
    }

    int64_t max_freq_hz = 0;
    io_service_t service;
    while ((service = IOIteratorNext(iter)) != 0) {
        for (int p = 0; freq_props[p]; p++) {
            CFStringRef key = CFStringCreateWithCString(kCFAllocatorDefault,
                freq_props[p], kCFStringEncodingUTF8);
            CFDataRef data = (CFDataRef)IORegistryEntryCreateCFProperty(
                service, key, kCFAllocatorDefault, 0);
            CFRelease(key);
            if (!data) continue;

            CFIndex len = CFDataGetLength(data);
            if (len >= 8) {
                const uint8_t *bytes = CFDataGetBytePtr(data);
                // Last pair at offset (len - 8): 4 bytes freq + 4 bytes voltage
                uint32_t freq = 0;
                memcpy(&freq, bytes + len - 8, sizeof(uint32_t));
                if ((int64_t)freq > max_freq_hz) {
                    max_freq_hz = (int64_t)freq;
                }
            }
            CFRelease(data);
        }
        IOObjectRelease(service);
    }
    IOObjectRelease(iter);

    return max_freq_hz > 0 ? (jlong)(max_freq_hz / 1000000) : 0;
}

// Read per-CPU load info and compute usage since last call
static void read_cpu_ticks(float *global_usage, float *per_cpu_usages, int count) {
    natural_t num_cpus = 0;
    processor_info_array_t cpu_info = NULL;
    mach_msg_type_number_t info_count = 0;

    if (host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO,
                            &num_cpus, &cpu_info, &info_count) != KERN_SUCCESS) {
        if (global_usage) *global_usage = 0.0f;
        return;
    }

    unsigned long long total_user = 0, total_sys = 0, total_idle = 0, total_nice = 0;
    int n = (int)num_cpus;
    if (per_cpu_usages && n > count) n = count;

    for (int i = 0; i < (int)num_cpus; i++) {
        integer_t *ticks = &cpu_info[i * CPU_STATE_MAX];
        unsigned int user  = (unsigned int)ticks[CPU_STATE_USER];
        unsigned int sys   = (unsigned int)ticks[CPU_STATE_SYSTEM];
        unsigned int idle  = (unsigned int)ticks[CPU_STATE_IDLE];
        unsigned int nice  = (unsigned int)ticks[CPU_STATE_NICE];

        if (per_cpu_usages && i < count && usage_initialized) {
            unsigned int d_user = user - prev_ticks[i][CPU_STATE_USER];
            unsigned int d_sys  = sys  - prev_ticks[i][CPU_STATE_SYSTEM];
            unsigned int d_idle = idle - prev_ticks[i][CPU_STATE_IDLE];
            unsigned int d_nice = nice - prev_ticks[i][CPU_STATE_NICE];
            unsigned int d_total = d_user + d_sys + d_idle + d_nice;
            per_cpu_usages[i] = d_total > 0
                ? (float)(d_user + d_sys + d_nice) / (float)d_total * 100.0f
                : 0.0f;
        } else if (per_cpu_usages && i < count) {
            per_cpu_usages[i] = 0.0f;
        }

        if (i < MAX_CPUS) {
            prev_ticks[i][CPU_STATE_USER]   = user;
            prev_ticks[i][CPU_STATE_SYSTEM] = sys;
            prev_ticks[i][CPU_STATE_IDLE]   = idle;
            prev_ticks[i][CPU_STATE_NICE]   = nice;
        }

        total_user += user;
        total_sys  += sys;
        total_idle += idle;
        total_nice += nice;
    }

    if (global_usage) {
        unsigned long long cur_total = total_user + total_sys + total_idle + total_nice;
        unsigned long long cur_active = total_user + total_sys + total_nice;
        if (usage_initialized) {
            unsigned long long d_total = cur_total - g_prev_total;
            unsigned long long d_active = cur_active - g_prev_active;
            *global_usage = d_total > 0 ? (float)d_active / (float)d_total * 100.0f : 0.0f;
        } else {
            *global_usage = 0.0f;
        }
        g_prev_total = cur_total;
        g_prev_active = cur_active;
    }

    usage_initialized = 1;

    vm_deallocate(mach_task_self(), (vm_address_t)cpu_info,
                  info_count * sizeof(natural_t));
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGlobalCpuUsage(
    JNIEnv *env, jclass clazz) {
    float usage = 0.0f;
    read_cpu_ticks(&usage, NULL, 0);
    return (jfloat)usage;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativePhysicalCoreCount(
    JNIEnv *env, jclass clazz) {
    int32_t count = 0;
    sysctl_int32("hw.physicalcpu", &count);
    return (jint)count;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuCount(
    JNIEnv *env, jclass clazz) {
    return (jint)count_logical_cpus();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuNames(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    const char **names = (const char **)malloc(count * sizeof(char *));
    char buf[32];
    for (int i = 0; i < count; i++) {
        snprintf(buf, sizeof(buf), "cpu%d", i);
        names[i] = strdup(buf);
    }
    jobjectArray result = to_string_array(env, names, count);
    for (int i = 0; i < count; i++) free((void *)names[i]);
    free(names);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuVendorIds(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    char *vendor = sysctl_string("machdep.cpu.vendor");
    const char *v = vendor ? vendor : "Apple";
    const char **arr = (const char **)malloc(count * sizeof(char *));
    for (int i = 0; i < count; i++) arr[i] = v;
    jobjectArray result = to_string_array(env, arr, count);
    free(arr);
    if (vendor) free(vendor);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuBrands(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    char *brand = sysctl_string("machdep.cpu.brand_string");
    if (!brand) {
        brand = sysctl_string("machdep.cpu.brand");
        if (!brand) brand = strdup("Apple Silicon");
    }
    const char **arr = (const char **)malloc(count * sizeof(char *));
    for (int i = 0; i < count; i++) arr[i] = brand;
    jobjectArray result = to_string_array(env, arr, count);
    free(arr);
    free(brand);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuFrequencies(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;

    jlong freq_mhz = get_cpu_frequency_mhz();

    jlong *freqs = (jlong *)calloc(count, sizeof(jlong));
    for (int i = 0; i < count; i++) freqs[i] = freq_mhz;
    jlongArray result = (*env)->NewLongArray(env, count);
    (*env)->SetLongArrayRegion(env, result, 0, count, freqs);
    free(freqs);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuUsages(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    float *usages = (float *)calloc(count, sizeof(float));
    read_cpu_ticks(NULL, usages, count);
    jfloatArray result = (*env)->NewFloatArray(env, count);
    (*env)->SetFloatArrayRegion(env, result, 0, count, usages);
    free(usages);
    return result;
}
