// CPU information: count, vendor, brand, frequency, usage.
// Sources: CPUID, GetNativeSystemInfo, GetSystemTimes, PDH performance counters,
//          CallNtPowerInformation, GetLogicalProcessorInformationEx

#include "nucleus_system_info_common.h"
#include <powerbase.h>
#include <intrin.h>

#include <pdh.h>
#include <pdhmsg.h>

#define MAX_CPUS 1024

// Previous idle/kernel/user times for global CPU usage calculation
static ULONGLONG g_prev_idle = 0, g_prev_kernel = 0, g_prev_user = 0;
static int g_cpu_init = 0;

// Cached per-CPU frequency and usage from the PDH processor counters
static long g_effective_freq[MAX_CPUS];  // MHz, 0 if unavailable
static long g_pct_perf[MAX_CPUS];        // last valid % of base frequency, 100 until sampled
static float g_per_cpu_usage[MAX_CPUS];  // last valid %, -1 until sampled
static int g_perf_cpu_count = 0;
static int g_perf_data_valid = 0;

// Guards the PDH query and the cached arrays above: two concurrent collections
// would shorten each other's sampling interval.
static SRWLOCK g_perf_lock = SRWLOCK_INIT;

static int get_logical_cpu_count(void) {
    SYSTEM_INFO si;
    GetNativeSystemInfo(&si);
    return (int)si.dwNumberOfProcessors;
}

// Get CPU vendor ID via CPUID (leaf 0)
static void get_cpu_vendor(char *vendor, size_t size) {
    int info[4];
    __cpuid(info, 0);
    if (size >= 13) {
        memcpy(vendor, &info[1], 4);
        memcpy(vendor + 4, &info[3], 4);
        memcpy(vendor + 8, &info[2], 4);
        vendor[12] = '\0';
    }
}

// Get CPU brand string via CPUID (leaves 0x80000002-0x80000004)
static void get_cpu_brand(char *brand, size_t size) {
    int info[4];
    __cpuid(info, 0x80000000);
    if ((unsigned int)info[0] < 0x80000004) {
        brand[0] = '\0';
        return;
    }
    char buf[49];
    __cpuid((int *)(buf + 0), 0x80000002);
    __cpuid((int *)(buf + 16), 0x80000003);
    __cpuid((int *)(buf + 32), 0x80000004);
    buf[48] = '\0';
    char *p = buf;
    while (*p == ' ') p++;
    strncpy(brand, p, size - 1);
    brand[size - 1] = '\0';
}

// ============================================================================
// PDH performance counters for per-CPU metrics
// ============================================================================
// Reads the counters behind Win32_PerfFormattedData_Counters_ProcessorInformation
// directly: the WMI query costs ~300 ms per call, a PDH collection ~0.5 ms (#786).
// English counter paths work regardless of the OS locale. No admin privileges needed.
//
// Instance names are "Group,Number" (e.g. "0,5"), plus "_Total" / "0,_Total" aggregates.
//   Processor Frequency           — base frequency in MHz (instantaneous)
//   % Processor Performance       — effective performance as % of base (>100% = turbo)
//   % Processor Time              — CPU usage %
// The two percentages are rate counters: they need two collections, so the query takes
// its first sample when it is opened. A rate sample is also invalid when a core slept
// through the whole interval (PDH_CALC_NEGATIVE_DENOMINATOR): each core then keeps its
// last valid value, and only a core never sampled falls back.

static PDH_HQUERY g_pdh_query = NULL;
static PDH_HCOUNTER g_pdh_freq = NULL;
static PDH_HCOUNTER g_pdh_perf = NULL;
static PDH_HCOUNTER g_pdh_time = NULL;
static int g_pdh_state = 0;  // 0 = not opened, 1 = open, -1 = unavailable

// Opens the query lazily. Must be called with g_perf_lock held exclusively.
static int pdh_open(void) {
    if (g_pdh_state != 0) return g_pdh_state > 0;
    g_pdh_state = -1;
    if (PdhOpenQueryW(NULL, 0, &g_pdh_query) != ERROR_SUCCESS) return 0;

    // Counters missing on this OS stay NULL and their metric falls back
    if (PdhAddEnglishCounterW(g_pdh_query, L"\\Processor Information(*)\\Processor Frequency", 0, &g_pdh_freq) != ERROR_SUCCESS)
        g_pdh_freq = NULL;
    if (PdhAddEnglishCounterW(g_pdh_query, L"\\Processor Information(*)\\% Processor Performance", 0, &g_pdh_perf) != ERROR_SUCCESS)
        g_pdh_perf = NULL;
    if (PdhAddEnglishCounterW(g_pdh_query, L"\\Processor Information(*)\\% Processor Time", 0, &g_pdh_time) != ERROR_SUCCESS)
        g_pdh_time = NULL;

    if ((!g_pdh_freq && !g_pdh_time) || PdhCollectQueryData(g_pdh_query) != ERROR_SUCCESS) {
        PdhCloseQuery(g_pdh_query);
        g_pdh_query = NULL;
        return 0;
    }
    for (int i = 0; i < MAX_CPUS; i++) {
        g_pct_perf[i] = 100;
        g_per_cpu_usage[i] = -1.0f;
    }
    g_pdh_state = 1;
    return 1;
}

// Maps a "Group,Number" instance name to the logical processor index, -1 for aggregates.
static int pdh_instance_to_index(const wchar_t *name) {
    int group = 0, number = 0;
    if (!name || swscanf(name, L"%d,%d", &group, &number) != 2) return -1;
    if (group < 0 || number < 0) return -1;
    int idx = number;
    for (int g = 0; g < group; g++) idx += (int)GetActiveProcessorCount((WORD)g);
    return idx;
}

// Reads one counter for every instance. The caller frees the returned array.
static PDH_FMT_COUNTERVALUE_ITEM_W *pdh_read_counter(PDH_HCOUNTER counter, DWORD format, DWORD *item_count) {
    *item_count = 0;
    if (!counter) return NULL;
    DWORD size = 0;
    if (PdhGetFormattedCounterArrayW(counter, format, &size, item_count, NULL) != PDH_MORE_DATA)
        return NULL;
    PDH_FMT_COUNTERVALUE_ITEM_W *items = (PDH_FMT_COUNTERVALUE_ITEM_W *)malloc(size);
    if (!items) return NULL;
    if (PdhGetFormattedCounterArrayW(counter, format, &size, item_count, items) != ERROR_SUCCESS) {
        free(items);
        *item_count = 0;
        return NULL;
    }
    return items;
}

static int pdh_item_valid(const PDH_FMT_COUNTERVALUE_ITEM_W *item) {
    return item->FmtValue.CStatus == PDH_CSTATUS_VALID_DATA || item->FmtValue.CStatus == PDH_CSTATUS_NEW_DATA;
}

// Must be called with g_perf_lock held exclusively.
static void refresh_perf_data(void) {
    g_perf_data_valid = 0;
    g_perf_cpu_count = 0;

    for (int i = 0; i < MAX_CPUS; i++) g_effective_freq[i] = 0;

    if (!pdh_open() || PdhCollectQueryData(g_pdh_query) != ERROR_SUCCESS) return;

    int cpu_logical_count = get_logical_cpu_count();
    if (cpu_logical_count > MAX_CPUS) cpu_logical_count = MAX_CPUS;
    DWORD n;

    // % Processor Performance (effective % of base, >100 = turbo)
    PDH_FMT_COUNTERVALUE_ITEM_W *items = pdh_read_counter(g_pdh_perf, PDH_FMT_LONG | PDH_FMT_NOCAP100, &n);
    for (DWORD i = 0; items && i < n; i++) {
        int idx = pdh_instance_to_index(items[i].szName);
        if (idx >= 0 && idx < cpu_logical_count && pdh_item_valid(&items[i]) && items[i].FmtValue.longValue > 0)
            g_pct_perf[idx] = items[i].FmtValue.longValue;
    }
    free(items);

    // Processor Frequency (base MHz) → effective frequency = base × performance%
    items = pdh_read_counter(g_pdh_freq, PDH_FMT_LONG, &n);
    for (DWORD i = 0; items && i < n; i++) {
        int idx = pdh_instance_to_index(items[i].szName);
        if (idx < 0 || idx >= cpu_logical_count || !pdh_item_valid(&items[i])) continue;
        long base_mhz = items[i].FmtValue.longValue;
        if (base_mhz > 0) g_effective_freq[idx] = base_mhz * g_pct_perf[idx] / 100;
        if (idx >= g_perf_cpu_count) g_perf_cpu_count = idx + 1;
    }
    free(items);

    // % Processor Time (CPU usage %)
    items = pdh_read_counter(g_pdh_time, PDH_FMT_DOUBLE, &n);
    for (DWORD i = 0; items && i < n; i++) {
        int idx = pdh_instance_to_index(items[i].szName);
        if (idx < 0 || idx >= cpu_logical_count) continue;
        if (pdh_item_valid(&items[i])) {
            double v = items[i].FmtValue.doubleValue;
            g_per_cpu_usage[idx] = (float)(v > 100.0 ? 100.0 : (v < 0.0 ? 0.0 : v));
        }
        if (idx >= g_perf_cpu_count) g_perf_cpu_count = idx + 1;
    }
    free(items);

    g_perf_data_valid = (g_perf_cpu_count > 0);
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGlobalCpuUsage(
    JNIEnv *env, jclass clazz) {
    FILETIME idle_ft, kernel_ft, user_ft;
    if (!GetSystemTimes(&idle_ft, &kernel_ft, &user_ft)) return 0.0f;

    ULARGE_INTEGER idle_u, kernel_u, user_u;
    idle_u.LowPart = idle_ft.dwLowDateTime;    idle_u.HighPart = idle_ft.dwHighDateTime;
    kernel_u.LowPart = kernel_ft.dwLowDateTime; kernel_u.HighPart = kernel_ft.dwHighDateTime;
    user_u.LowPart = user_ft.dwLowDateTime;    user_u.HighPart = user_ft.dwHighDateTime;

    if (!g_cpu_init) {
        g_prev_idle = idle_u.QuadPart;
        g_prev_kernel = kernel_u.QuadPart;
        g_prev_user = user_u.QuadPart;
        g_cpu_init = 1;
        return 0.0f;
    }

    ULONGLONG d_idle = idle_u.QuadPart - g_prev_idle;
    ULONGLONG d_kernel = kernel_u.QuadPart - g_prev_kernel;
    ULONGLONG d_user = user_u.QuadPart - g_prev_user;

    g_prev_idle = idle_u.QuadPart;
    g_prev_kernel = kernel_u.QuadPart;
    g_prev_user = user_u.QuadPart;

    ULONGLONG total = d_kernel + d_user;
    if (total == 0) return 0.0f;
    ULONGLONG active = total - d_idle;
    return (float)((double)active / (double)total * 100.0);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativePhysicalCoreCount(
    JNIEnv *env, jclass clazz) {
    DWORD len = 0;
    GetLogicalProcessorInformationEx(RelationProcessorCore, NULL, &len);
    if (GetLastError() != ERROR_INSUFFICIENT_BUFFER) return 0;

    BYTE *buf = (BYTE *)malloc(len);
    if (!buf) return 0;
    if (!GetLogicalProcessorInformationEx(RelationProcessorCore,
            (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)buf, &len)) {
        free(buf);
        return 0;
    }

    int count = 0;
    DWORD offset = 0;
    while (offset < len) {
        PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX info =
            (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)(buf + offset);
        if (info->Relationship == RelationProcessorCore) count++;
        offset += info->Size;
    }
    free(buf);
    return count;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuCount(
    JNIEnv *env, jclass clazz) {
    return get_logical_cpu_count();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuNames(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    const char **names = (const char **)malloc(count * sizeof(char *));
    if (!names) return NULL;
    char name[32];
    for (int i = 0; i < count; i++) {
        snprintf(name, sizeof(name), "cpu%d", i);
        names[i] = _strdup(name);
    }
    jobjectArray result = to_string_array(env, names, count);
    for (int i = 0; i < count; i++) free((void *)names[i]);
    free(names);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuVendorIds(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    char vendor[16];
    get_cpu_vendor(vendor, sizeof(vendor));
    const char **vendors = (const char **)malloc(count * sizeof(char *));
    if (!vendors) return NULL;
    for (int i = 0; i < count; i++) vendors[i] = vendor;
    jobjectArray result = to_string_array(env, vendors, count);
    free(vendors);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuBrands(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    char brand[64];
    get_cpu_brand(brand, sizeof(brand));
    const char **brands = (const char **)malloc(count * sizeof(char *));
    if (!brands) return NULL;
    for (int i = 0; i < count; i++) brands[i] = brand;
    jobjectArray result = to_string_array(env, brands, count);
    free(brands);
    return result;
}

// PROCESSOR_POWER_INFORMATION is not always in SDK headers
typedef struct {
    ULONG Number;
    ULONG MaxMhz;
    ULONG CurrentMhz;
    ULONG MhzLimit;
    ULONG MaxIdleState;
    ULONG CurrentIdleState;
} MY_PROCESSOR_POWER_INFORMATION;

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuFrequencies(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    jlongArray arr = (*env)->NewLongArray(env, count);

    // Refresh PDH perf data (includes effective frequency and per-CPU usage)
    jlong *freqs = (jlong *)malloc(count * sizeof(jlong));
    AcquireSRWLockExclusive(&g_perf_lock);
    refresh_perf_data();
    int valid = g_perf_data_valid && freqs;
    if (valid) {
        for (int i = 0; i < count; i++) {
            freqs[i] = (i < g_perf_cpu_count && i < MAX_CPUS) ? (jlong)g_effective_freq[i] : 0;
        }
    }
    ReleaseSRWLockExclusive(&g_perf_lock);

    if (valid) {
        (*env)->SetLongArrayRegion(env, arr, 0, count, freqs);
        free(freqs);
        return arr;
    }
    free(freqs);

    // Fallback: CallNtPowerInformation (returns base/nominal frequency)
    {
        DWORD buf_size = count * sizeof(MY_PROCESSOR_POWER_INFORMATION);
        MY_PROCESSOR_POWER_INFORMATION *ppi = (MY_PROCESSOR_POWER_INFORMATION *)malloc(buf_size);
        if (ppi) {
            NTSTATUS status = CallNtPowerInformation(ProcessorInformation, NULL, 0, ppi, buf_size);
            if (status == 0) {
                jlong *freqs = (jlong *)malloc(count * sizeof(jlong));
                if (freqs) {
                    for (int i = 0; i < count; i++) {
                        freqs[i] = (jlong)ppi[i].CurrentMhz;
                    }
                    (*env)->SetLongArrayRegion(env, arr, 0, count, freqs);
                    free(freqs);
                }
            }
            free(ppi);
        }
    }

    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuUsages(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    jfloatArray arr = (*env)->NewFloatArray(env, count);

    // Use per-CPU data from the PDH query (already refreshed by nativeCpuFrequencies)
    jfloat *per_cpu = (jfloat *)malloc(count * sizeof(jfloat));
    int all_ok = 0;
    AcquireSRWLockShared(&g_perf_lock);
    if (per_cpu && g_perf_data_valid && g_perf_cpu_count >= count) {
        all_ok = 1;
        for (int i = 0; i < count && all_ok; i++) {
            per_cpu[i] = g_per_cpu_usage[i];
            if (per_cpu[i] < 0.0f) all_ok = 0;
        }
    }
    ReleaseSRWLockShared(&g_perf_lock);

    if (all_ok) {
        (*env)->SetFloatArrayRegion(env, arr, 0, count, per_cpu);
        free(per_cpu);
        return arr;
    }
    free(per_cpu);

    // Fallback: global usage for all cores
    float global = 0.0f;
    FILETIME idle_ft, kernel_ft, user_ft;
    if (GetSystemTimes(&idle_ft, &kernel_ft, &user_ft)) {
        ULARGE_INTEGER idle_u, kernel_u, user_u;
        idle_u.LowPart = idle_ft.dwLowDateTime;    idle_u.HighPart = idle_ft.dwHighDateTime;
        kernel_u.LowPart = kernel_ft.dwLowDateTime; kernel_u.HighPart = kernel_ft.dwHighDateTime;
        user_u.LowPart = user_ft.dwLowDateTime;    user_u.HighPart = user_ft.dwHighDateTime;

        if (g_cpu_init) {
            ULONGLONG d_idle = idle_u.QuadPart - g_prev_idle;
            ULONGLONG d_kernel = kernel_u.QuadPart - g_prev_kernel;
            ULONGLONG d_user = user_u.QuadPart - g_prev_user;
            ULONGLONG total = d_kernel + d_user;
            if (total > 0) {
                global = (float)((double)(total - d_idle) / (double)total * 100.0);
            }
        }
    }

    jfloat *usages = (jfloat *)malloc(count * sizeof(jfloat));
    if (usages) {
        for (int i = 0; i < count; i++) usages[i] = global;
        (*env)->SetFloatArrayRegion(env, arr, 0, count, usages);
        free(usages);
    }
    return arr;
}
