// GPU information: name, vendor/device IDs, memory, driver version, live metrics.
// Static info: IOKit IOAccelerator (AGXAccelerator on Apple Silicon, various on Intel)
// Live metrics: IOKit PerformanceStatistics + SMC fallback for temperature/power.
// Inspired by exelban/stats GPU module (MIT).

#include "nucleus_system_info_common.h"
#include <IOKit/IOKitLib.h>
#include <CoreFoundation/CoreFoundation.h>
#include <math.h>

#define MAX_GPUS 8

// SMC structures (same as component module — duplicated to keep files self-contained)
#define KERNEL_INDEX_SMC 2
#define SMC_CMD_READ_KEYINFO 9
#define SMC_CMD_READ_BYTES 5

typedef struct {
    char major;
    char minor;
    char build;
    char reserved[1];
    uint16_t release;
} gpu_smc_vers_t;

typedef struct {
    uint16_t version;
    uint16_t length;
    uint32_t cpuPLimit;
    uint32_t gpuPLimit;
    uint32_t memPLimit;
} gpu_smc_pLimitData_t;

typedef struct {
    uint32_t dataSize;
    uint32_t dataType;
    char dataAttributes;
} gpu_smc_keyInfo_t;

typedef struct {
    uint32_t key;
    gpu_smc_vers_t vers;
    gpu_smc_pLimitData_t pLimitData;
    gpu_smc_keyInfo_t keyInfo;
    uint8_t result;
    uint8_t status;
    uint8_t data8;
    uint32_t data32;
    uint8_t bytes[32];
} gpu_smc_data_t;

#define DATATYPE_SP78 0x73703738
#define DATATYPE_FLT  0x666C7420
#define DATATYPE_SP3C 0x73703363
#define DATATYPE_SP4B 0x73703462
#define DATATYPE_SP5A 0x73703561

static uint32_t gpu_chars_to_key(const char *str) {
    return ((uint32_t)(unsigned char)str[0] << 24) |
           ((uint32_t)(unsigned char)str[1] << 16) |
           ((uint32_t)(unsigned char)str[2] << 8)  |
           (uint32_t)(unsigned char)str[3];
}

typedef struct {
    char name[128];
    uint32_t vendor_id;
    uint32_t device_id;
    long long dedicated_video_memory;
    long long dedicated_system_memory;
    long long shared_system_memory;
    char driver_version[64];
    // Live metrics (NAN / -1 = unavailable)
    float temperature;
    float gpu_usage;
    long long memory_used;
    int core_clock_mhz;
    int memory_clock_mhz;
    float fan_speed_pct;
    float power_draw_watts;
} gpu_entry_t;

static int g_gpu_count = 0;
static gpu_entry_t g_gpus[MAX_GPUS];

// ---- SMC helpers for GPU power/temperature fallback ----

static io_connect_t g_gpu_smc_conn = 0;

static int gpu_smc_open(void) {
    if (g_gpu_smc_conn) return 0;
    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault,
                                                       IOServiceMatching("AppleSMC"));
    if (!service) return -1;
    kern_return_t kr = IOServiceOpen(service, mach_task_self(), 0, &g_gpu_smc_conn);
    IOObjectRelease(service);
    return (kr == KERN_SUCCESS) ? 0 : -1;
}

static int gpu_smc_read_key(uint32_t key, uint32_t *data_type, uint8_t *bytes, uint32_t *data_size) {
    gpu_smc_data_t input, output;
    memset(&input, 0, sizeof(input));
    memset(&output, 0, sizeof(output));

    input.key = key;
    input.data8 = SMC_CMD_READ_KEYINFO;
    size_t out_size = sizeof(output);
    kern_return_t kr = IOConnectCallStructMethod(g_gpu_smc_conn, KERNEL_INDEX_SMC,
                                                  &input, sizeof(input),
                                                  &output, &out_size);
    if (kr != KERN_SUCCESS) return -1;

    uint32_t size = output.keyInfo.dataSize;
    uint32_t type = output.keyInfo.dataType;
    if (size == 0 || size > 32) return -1;

    memset(&input, 0, sizeof(input));
    memset(&output, 0, sizeof(output));
    input.key = key;
    input.keyInfo.dataSize = size;
    input.data8 = SMC_CMD_READ_BYTES;
    out_size = sizeof(output);
    kr = IOConnectCallStructMethod(g_gpu_smc_conn, KERNEL_INDEX_SMC,
                                    &input, sizeof(input),
                                    &output, &out_size);
    if (kr != KERN_SUCCESS) return -1;

    *data_type = type;
    *data_size = size;
    memcpy(bytes, output.bytes, size);
    return 0;
}

static float gpu_smc_read_float(const char *key_str) {
    uint32_t key = gpu_chars_to_key(key_str);
    uint32_t data_type = 0, data_size = 0;
    uint8_t bytes[32] = {0};

    if (gpu_smc_read_key(key, &data_type, bytes, &data_size) != 0)
        return NAN;

    if (data_size >= 2 && (data_type == DATATYPE_SP78 ||
                           data_type == DATATYPE_SP3C ||
                           data_type == DATATYPE_SP4B ||
                           data_type == DATATYPE_SP5A)) {
        int16_t raw = (int16_t)((bytes[0] << 8) | bytes[1]);
        int frac_bits = 8;
        if (data_type == DATATYPE_SP3C) frac_bits = 12;
        else if (data_type == DATATYPE_SP4B) frac_bits = 11;
        else if (data_type == DATATYPE_SP5A) frac_bits = 10;
        return (float)raw / (float)(1 << frac_bits);
    } else if (data_type == DATATYPE_FLT && data_size >= 4) {
        float val;
        memcpy(&val, bytes, sizeof(float));
        return val;
    }

    return NAN;
}

// ---- IOKit helpers (reused from existing gpu code) ----

static uint32_t read_le32_property(io_service_t service, CFStringRef key) {
    CFDataRef data = (CFDataRef)IORegistryEntryCreateCFProperty(
        service, key, kCFAllocatorDefault, 0);
    if (!data) return 0;
    uint32_t val = 0;
    if (CFDataGetLength(data) >= 4) {
        const uint8_t *bytes = CFDataGetBytePtr(data);
        val = (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
              ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
    }
    CFRelease(data);
    return val;
}

static int read_string_property(io_service_t service, CFStringRef key, char *buf, size_t bufsize) {
    CFStringRef str = (CFStringRef)IORegistryEntryCreateCFProperty(
        service, key, kCFAllocatorDefault, 0);
    if (!str) return -1;
    if (CFGetTypeID(str) != CFStringGetTypeID()) { CFRelease(str); return -1; }
    Boolean ok = CFStringGetCString(str, buf, (CFIndex)bufsize, kCFStringEncodingUTF8);
    CFRelease(str);
    return ok ? 0 : -1;
}

static long long read_number_property(io_service_t service, CFStringRef key) {
    CFNumberRef num = (CFNumberRef)IORegistryEntryCreateCFProperty(
        service, key, kCFAllocatorDefault, 0);
    if (!num) return 0;
    if (CFGetTypeID(num) != CFNumberGetTypeID()) { CFRelease(num); return 0; }
    long long val = 0;
    CFNumberGetValue(num, kCFNumberSInt64Type, &val);
    CFRelease(num);
    return val;
}

// Read a number from a CFDictionary (used for PerformanceStatistics sub-keys)
static int read_dict_number(CFDictionaryRef dict, const char *stat_key, long long *out) {
    if (!dict) return -1;
    CFStringRef key = CFStringCreateWithCString(kCFAllocatorDefault, stat_key,
                                                 kCFStringEncodingUTF8);
    int found = -1;
    CFNumberRef num = (CFNumberRef)CFDictionaryGetValue(dict, key);
    if (num && CFGetTypeID(num) == CFNumberGetTypeID()) {
        CFNumberGetValue(num, kCFNumberSInt64Type, out);
        found = 0;
    }
    CFRelease(key);
    return found;
}

// Walk up to find a property on a parent (e.g., device-id on IOPCIDevice parent)
static uint32_t find_parent_le32_property(io_service_t start, CFStringRef key) {
    io_service_t current = start;
    IOObjectRetain(current);
    int depth = 0;
    while (current && depth < 10) {
        CFDataRef data = (CFDataRef)IORegistryEntryCreateCFProperty(
            current, key, kCFAllocatorDefault, 0);
        if (data && CFGetTypeID(data) == CFDataGetTypeID() && CFDataGetLength(data) >= 4) {
            const uint8_t *bytes = CFDataGetBytePtr(data);
            uint32_t val = (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) |
                           ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
            CFRelease(data);
            IOObjectRelease(current);
            return val;
        }
        if (data) CFRelease(data);
        io_service_t parent = 0;
        kern_return_t kr = IORegistryEntryGetParentEntry(current, kIOServicePlane, &parent);
        IOObjectRelease(current);
        if (kr != KERN_SUCCESS) break;
        current = parent;
        depth++;
    }
    if (current) IOObjectRelease(current);
    return 0;
}

// ---- GPU temperature from SMC (fallback when PerformanceStatistics has no temp) ----

// Try multiple SMC keys, return average of valid readings
static float gpu_smc_temperature(void) {
    if (gpu_smc_open() != 0) return NAN;

    // GPU temperature SMC keys by priority
    // Generic GPU keys first, then chip-specific
    static const char *keys[] = {
        "TGDD",  // AMD Radeon GPU
        "TCGC",  // Intel iGPU
        "TG0D",  // GPU diode
        "TG0P",  // GPU proximity
        NULL
    };

    for (int i = 0; keys[i]; i++) {
        float val = gpu_smc_read_float(keys[i]);
        if (!isnan(val) && val > 0.0f && val < 150.0f) return val;
    }

    // Apple Silicon: average GPU sensor readings
    // Try common M-series GPU keys
    static const char *apple_keys[] = {
        "Tg05", "Tg0D", "Tg0L", "Tg0T",  // M1
        "Tg0f", "Tg0j",                      // M2
        "Tg0G", "Tg0H", "Tg1U", "Tg1k",  // M4
        "Tg0U", "Tg0X", "Tg0d", "Tg0g",  // M5
        NULL
    };

    float sum = 0;
    int count = 0;
    for (int i = 0; apple_keys[i]; i++) {
        float val = gpu_smc_read_float(apple_keys[i]);
        if (!isnan(val) && val > 0.0f && val < 150.0f) {
            sum += val;
            count++;
        }
    }
    if (count > 0) return sum / count;

    return NAN;
}

// ---- GPU power from SMC ----

static float gpu_smc_power(void) {
    if (gpu_smc_open() != 0) return NAN;

    static const char *keys[] = {
        "PG0C",  // GPU power (generic)
        "PCPG",  // Intel Graphics power
        "PCGC",  // Intel GPU power (variant)
        "PCGM",  // Intel GPU power (IMON)
        NULL
    };

    for (int i = 0; keys[i]; i++) {
        float val = gpu_smc_read_float(keys[i]);
        if (!isnan(val) && val > 0.0f && val < 1000.0f) return val;
    }

    return NAN;
}

// ---- Main refresh ----

static void refresh_gpus(void) {
    g_gpu_count = 0;

    CFMutableDictionaryRef matching = IOServiceMatching("IOAccelerator");
    if (!matching) return;

    io_iterator_t iter;
    if (IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iter) != KERN_SUCCESS) {
        return;
    }

    io_service_t service;
    while ((service = IOIteratorNext(iter)) != 0 && g_gpu_count < MAX_GPUS) {
        gpu_entry_t *g = &g_gpus[g_gpu_count];
        memset(g, 0, sizeof(gpu_entry_t));

        // Initialize optional fields to unavailable
        g->temperature = NAN;
        g->gpu_usage = NAN;
        g->memory_used = -1;
        g->core_clock_mhz = -1;
        g->memory_clock_mhz = -1;
        g->fan_speed_pct = NAN;
        g->power_draw_watts = NAN;

        // Name from "model" property
        if (read_string_property(service, CFSTR("model"), g->name, sizeof(g->name)) != 0) {
            read_string_property(service, CFSTR("IOClass"), g->name, sizeof(g->name));
        }
        if (g->name[0] == '\0') {
            IOObjectRelease(service);
            continue;
        }

        // Vendor ID
        g->vendor_id = read_le32_property(service, CFSTR("vendor-id"));
        if (g->vendor_id == 0) {
            g->vendor_id = find_parent_le32_property(service, CFSTR("vendor-id"));
        }

        // Device ID
        g->device_id = read_le32_property(service, CFSTR("device-id"));
        if (g->device_id == 0) {
            g->device_id = find_parent_le32_property(service, CFSTR("device-id"));
        }

        // Read ALL properties at once — IORegistryEntryCreateCFProperties returns
        // live PerformanceStatistics, while IORegistryEntryCreateCFProperty (singular)
        // returns stale/cached utilization values on macOS.
        CFMutableDictionaryRef allProps = NULL;
        IORegistryEntryCreateCFProperties(service, &allProps, kCFAllocatorDefault, 0);

        CFDictionaryRef perfStats = NULL;
        if (allProps) {
            CFDictionaryRef ps = (CFDictionaryRef)CFDictionaryGetValue(allProps, CFSTR("PerformanceStatistics"));
            if (ps && CFGetTypeID(ps) == CFDictionaryGetTypeID()) {
                perfStats = ps;
            }
        }

        // ---- Static memory info ----

        // Shared system memory from PerformanceStatistics
        long long perf_val = 0;
        if (read_dict_number(perfStats, "Alloc system memory", &perf_val) == 0 && perf_val > 0) {
            g->shared_system_memory = perf_val;
        }

        // VRAM for discrete GPUs (Intel Macs with AMD/Nvidia)
        g->dedicated_video_memory = read_number_property(service, CFSTR("VRAM,totalMB"));
        if (g->dedicated_video_memory > 0) {
            g->dedicated_video_memory *= 1024 * 1024;
        }

        // Driver version
        read_string_property(service, CFSTR("IOSourceVersion"), g->driver_version,
                             sizeof(g->driver_version));

        // ---- Live metrics from PerformanceStatistics ----

        // Temperature: PerformanceStatistics first, SMC fallback
        long long temp_val = 0;
        if (read_dict_number(perfStats, "Temperature(C)", &temp_val) == 0 && temp_val > 0) {
            g->temperature = (float)temp_val;
        } else {
            g->temperature = gpu_smc_temperature();
        }

        // GPU utilization — same logic as Stats.app: read Device Utilization %
        // directly, fallback to GPU Activity(%). No smoothing or averaging —
        // callers should poll periodically for smooth readings.
        {
            long long util_val = 0;
            if (read_dict_number(perfStats, "Device Utilization %", &util_val) == 0) {
                if (util_val > 100) util_val = 100;
                g->gpu_usage = (float)util_val;
            } else if (read_dict_number(perfStats, "GPU Activity(%)", &util_val) == 0) {
                if (util_val > 100) util_val = 100;
                g->gpu_usage = (float)util_val;
            }
        }

        // Memory used (in-use system memory)
        long long mem_used = 0;
        if (read_dict_number(perfStats, "In use system memory", &mem_used) == 0 && mem_used > 0) {
            g->memory_used = mem_used;
        }

        // Core clock (MHz)
        long long core_clock = 0;
        if (read_dict_number(perfStats, "Core Clock(MHz)", &core_clock) == 0 && core_clock > 0) {
            g->core_clock_mhz = (int)core_clock;
        }

        // Memory clock (MHz)
        long long mem_clock = 0;
        if (read_dict_number(perfStats, "Memory Clock(MHz)", &mem_clock) == 0 && mem_clock > 0) {
            g->memory_clock_mhz = (int)mem_clock;
        }

        // Fan speed (%)
        long long fan = 0;
        if (read_dict_number(perfStats, "Fan Speed(%)", &fan) == 0 && fan >= 0) {
            g->fan_speed_pct = (float)fan;
        }

        // Power draw: SMC
        g->power_draw_watts = gpu_smc_power();

        if (allProps) CFRelease(allProps);

        g_gpu_count++;
        IOObjectRelease(service);
    }
    IOObjectRelease(iter);
}

// ---- JNI exports: static info ----

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuCount(
    JNIEnv *env, jclass clazz) {
    refresh_gpus();
    return (jint)g_gpu_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuNames(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_gpu_count * sizeof(char *));
    for (int i = 0; i < g_gpu_count; i++) arr[i] = g_gpus[i].name;
    jobjectArray result = to_string_array(env, arr, g_gpu_count);
    free(arr);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuVendorIds(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].vendor_id;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDeviceIds(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].device_id;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDedicatedVideoMemories(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_video_memory;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDedicatedSystemMemories(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_system_memory;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuSharedSystemMemories(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].shared_system_memory;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuDriverVersions(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_gpu_count * sizeof(char *));
    for (int i = 0; i < g_gpu_count; i++) arr[i] = g_gpus[i].driver_version;
    jobjectArray result = to_string_array(env, arr, g_gpu_count);
    free(arr);
    return result;
}

// ---- JNI exports: live metrics ----

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuTemperatures(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].temperature;
    jfloatArray result = (*env)->NewFloatArray(env, g_gpu_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuUsages(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].gpu_usage;
    jfloatArray result = (*env)->NewFloatArray(env, g_gpu_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuMemoryUsed(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].memory_used;
    jlongArray result = (*env)->NewLongArray(env, g_gpu_count);
    (*env)->SetLongArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuCoreClocks(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jint *vals = (jint *)malloc(g_gpu_count * sizeof(jint));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].core_clock_mhz;
    jintArray result = (*env)->NewIntArray(env, g_gpu_count);
    (*env)->SetIntArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuMemoryClocks(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jint *vals = (jint *)malloc(g_gpu_count * sizeof(jint));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].memory_clock_mhz;
    jintArray result = (*env)->NewIntArray(env, g_gpu_count);
    (*env)->SetIntArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuFanSpeeds(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].fan_speed_pct;
    jfloatArray result = (*env)->NewFloatArray(env, g_gpu_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeGpuPowerDraws(
    JNIEnv *env, jclass clazz) {
    if (g_gpu_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].power_draw_watts;
    jfloatArray result = (*env)->NewFloatArray(env, g_gpu_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_gpu_count, vals);
    free(vals);
    return result;
}
