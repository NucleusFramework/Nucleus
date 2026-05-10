// CPU information: count, vendor, brand, frequency, usage.
// Sources: CPUID, GetNativeSystemInfo, GetSystemTimes, WMI performance data,
//          CallNtPowerInformation, GetLogicalProcessorInformationEx

#include "nucleus_system_info_common.h"
#include <powerbase.h>
#include <intrin.h>

// WMI COM interfaces
#include <objbase.h>
#include <wbemcli.h>

#define MAX_CPUS 1024

// Previous idle/kernel/user times for global CPU usage calculation
static ULONGLONG g_prev_idle = 0, g_prev_kernel = 0, g_prev_user = 0;
static int g_cpu_init = 0;

// Cached per-CPU frequency and usage from WMI perf data
static long g_effective_freq[MAX_CPUS];  // MHz, 0 if unavailable
static float g_per_cpu_usage[MAX_CPUS];  // %, -1 if unavailable
static int g_perf_cpu_count = 0;
static int g_perf_data_valid = 0;

// Helper: extract a long from a VARIANT (handles integer types and VT_BSTR for uint64 WMI perf data)
static long wmi_variant_to_long(VARIANT *v) {
    switch (v->vt) {
        case VT_I4:   return v->lVal;
        case VT_UI4:  return (long)v->ulVal;
        case VT_I8:   return (long)v->llVal;
        case VT_UI8:  return (long)v->ullVal;
        case VT_I2:   return (long)v->iVal;
        case VT_UI2:  return (long)v->uiVal;
        case VT_BSTR: return (v->bstrVal) ? _wtol(v->bstrVal) : 0;
        default:      return 0;
    }
}

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
// WMI performance data query for per-CPU metrics
// ============================================================================
// Win32_PerfFormattedData_Counters_ProcessorInformation uses English property
// names regardless of OS locale. No admin privileges needed.
//
// Key properties:
//   Name                       — "NUMA,Core" e.g. "0,5" or "_Total"
//   ProcessorFrequency         — base frequency in MHz (static)
//   PercentProcessorPerformance — effective performance as % of base (>100% = turbo)
//   PercentProcessorTime       — CPU usage %
//   PercentofMaximumFrequency  — current P-state as % of max advertised

static void refresh_perf_data(void) {
    g_perf_data_valid = 0;
    g_perf_cpu_count = 0;

    for (int i = 0; i < MAX_CPUS; i++) {
        g_effective_freq[i] = 0;
        g_per_cpu_usage[i] = -1.0f;
    }

    // COM init
    HRESULT com_hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    int we_initialized_com = (com_hr == S_OK);
    if (FAILED(com_hr) && com_hr != RPC_E_CHANGED_MODE) return;

    IWbemLocator *locator = NULL;
    HRESULT hr = CoCreateInstance(&CLSID_WbemLocator, NULL, CLSCTX_INPROC_SERVER,
        &IID_IWbemLocator, (void **)&locator);
    if (FAILED(hr)) goto com_done;

    IWbemServices *services = NULL;
    {
        BSTR ns = SysAllocString(L"root\\cimv2");
        hr = locator->lpVtbl->ConnectServer(locator, ns, NULL, NULL, NULL, 0, NULL, NULL, &services);
        SysFreeString(ns);
    }
    if (FAILED(hr)) { locator->lpVtbl->Release(locator); goto com_done; }

    CoSetProxyBlanket((IUnknown *)services, RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, NULL,
        RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE, NULL, EOAC_NONE);

    // Query per-CPU performance data
    {
        IEnumWbemClassObject *enumerator = NULL;
        BSTR wql = SysAllocString(L"WQL");
        BSTR query = SysAllocString(
            L"SELECT Name, ProcessorFrequency, PercentProcessorPerformance, PercentProcessorTime "
            L"FROM Win32_PerfFormattedData_Counters_ProcessorInformation");
        hr = services->lpVtbl->ExecQuery(services, wql, query,
            WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY, NULL, &enumerator);
        SysFreeString(wql);
        SysFreeString(query);
        if (FAILED(hr)) goto svc_done;

        IWbemClassObject *obj = NULL;
        ULONG returned = 0;
        int cpu_logical_count = get_logical_cpu_count();

        while (enumerator->lpVtbl->Next(enumerator, 5000, 1, &obj, &returned) == S_OK && returned > 0) {
            VARIANT var;

            // Skip _Total aggregate
            VariantInit(&var);
            hr = obj->lpVtbl->Get(obj, L"Name", 0, &var, NULL, NULL);
            if (SUCCEEDED(hr) && var.vt == VT_BSTR && var.bstrVal) {
                if (wcsstr(var.bstrVal, L"_Total")) {
                    VariantClear(&var);
                    obj->lpVtbl->Release(obj);
                    continue;
                }

                // Parse "NUMA,Core" to get logical CPU index
                // Format is "0,0", "0,1", ..., "0,19"
                int numa = 0, core = 0;
                if (swscanf(var.bstrVal, L"%d,%d", &numa, &core) == 2) {
                    // core index maps directly to logical processor index
                    int idx = core;
                    if (idx >= 0 && idx < MAX_CPUS && idx < cpu_logical_count) {
                        VARIANT vf;

                        // ProcessorFrequency (base MHz)
                        VariantInit(&vf);
                        long base_mhz = 0;
                        if (obj->lpVtbl->Get(obj, L"ProcessorFrequency", 0, &vf, NULL, NULL) == S_OK)
                            base_mhz = wmi_variant_to_long(&vf);
                        VariantClear(&vf);

                        // PercentProcessorPerformance (effective % of base, >100 = turbo)
                        VariantInit(&vf);
                        long pct_perf = 100;
                        if (obj->lpVtbl->Get(obj, L"PercentProcessorPerformance", 0, &vf, NULL, NULL) == S_OK) {
                            long v = wmi_variant_to_long(&vf);
                            if (v > 0) pct_perf = v;
                        }
                        VariantClear(&vf);

                        // Effective frequency = base × performance%
                        if (base_mhz > 0 && pct_perf > 0) {
                            g_effective_freq[idx] = base_mhz * pct_perf / 100;
                        }

                        // PercentProcessorTime (CPU usage %)
                        VariantInit(&vf);
                        if (obj->lpVtbl->Get(obj, L"PercentProcessorTime", 0, &vf, NULL, NULL) == S_OK) {
                            long v = wmi_variant_to_long(&vf);
                            g_per_cpu_usage[idx] = (float)v;
                            if (g_per_cpu_usage[idx] > 100.0f) g_per_cpu_usage[idx] = 100.0f;
                            if (g_per_cpu_usage[idx] < 0.0f) g_per_cpu_usage[idx] = 0.0f;
                        }
                        VariantClear(&vf);

                        if (idx >= g_perf_cpu_count) g_perf_cpu_count = idx + 1;
                    }
                }
            }
            VariantClear(&var);
            obj->lpVtbl->Release(obj);
        }

        enumerator->lpVtbl->Release(enumerator);
        g_perf_data_valid = (g_perf_cpu_count > 0);
    }

svc_done:
    services->lpVtbl->Release(services);
    locator->lpVtbl->Release(locator);

com_done:
    if (we_initialized_com) CoUninitialize();
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGlobalCpuUsage(
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
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativePhysicalCoreCount(
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
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuCount(
    JNIEnv *env, jclass clazz) {
    return get_logical_cpu_count();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuNames(
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
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuVendorIds(
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
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuBrands(
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
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuFrequencies(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    jlongArray arr = (*env)->NewLongArray(env, count);

    // Refresh WMI perf data (includes effective frequency and per-CPU usage)
    refresh_perf_data();

    if (g_perf_data_valid) {
        jlong *freqs = (jlong *)malloc(count * sizeof(jlong));
        if (freqs) {
            for (int i = 0; i < count; i++) {
                freqs[i] = (i < g_perf_cpu_count) ? (jlong)g_effective_freq[i] : 0;
            }
            (*env)->SetLongArrayRegion(env, arr, 0, count, freqs);
            free(freqs);
            return arr;
        }
    }

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
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuUsages(
    JNIEnv *env, jclass clazz) {
    int count = get_logical_cpu_count();
    jfloatArray arr = (*env)->NewFloatArray(env, count);

    // Use per-CPU data from the WMI perf query (already refreshed by nativeCpuFrequencies)
    if (g_perf_data_valid && g_perf_cpu_count >= count) {
        jfloat *usages = (jfloat *)malloc(count * sizeof(jfloat));
        if (usages) {
            int all_ok = 1;
            for (int i = 0; i < count; i++) {
                if (g_per_cpu_usage[i] >= 0.0f) {
                    usages[i] = g_per_cpu_usage[i];
                } else {
                    all_ok = 0;
                    break;
                }
            }
            if (all_ok) {
                (*env)->SetFloatArrayRegion(env, arr, 0, count, usages);
                free(usages);
                return arr;
            }
            free(usages);
        }
    }

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
