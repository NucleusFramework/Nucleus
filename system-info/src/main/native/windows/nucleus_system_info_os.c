// OS information: name, kernel version, OS version, hostname, arch, uptime, boot time.
// Sources: Registry, GetTickCount64, GetComputerNameExW, GetNativeSystemInfo

#include "nucleus_system_info_common.h"

#define NT_CURRENT_VERSION L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeOsName(
    JNIEnv *env, jclass clazz) {
    char *product = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"ProductName");
    if (!product) return to_jstring(env, "Windows");
    // Detect Windows 11: build >= 22000 but ProductName may still say "Windows 10"
    DWORD build = 0;
    if (reg_read_dword(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentMajorVersionNumber", &build)) {
        // build var reused; read the actual build number string
    }
    char *build_str = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentBuildNumber");
    if (build_str) {
        int build_num = atoi(build_str);
        if (build_num >= 22000 && strstr(product, "Windows 10")) {
            // Replace "Windows 10" with "Windows 11" in the product name
            char *p = strstr(product, "Windows 10");
            if (p) {
                size_t new_len = strlen(product) + 2;
                char *new_product = (char *)malloc(new_len);
                if (new_product) {
                    size_t prefix_len = p - product;
                    memcpy(new_product, product, prefix_len);
                    memcpy(new_product + prefix_len, "Windows 11", 10);
                    strcpy(new_product + prefix_len + 10, p + 10);
                    free(product);
                    product = new_product;
                }
            }
        }
        free(build_str);
    }
    jstring result = to_jstring(env, product);
    free(product);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeKernelVersion(
    JNIEnv *env, jclass clazz) {
    char *build = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentBuildNumber");
    if (!build) return NULL;
    // Append UBR (Update Build Revision) if available
    DWORD ubr = 0;
    if (reg_read_dword(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"UBR", &ubr)) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%s.%lu", build, (unsigned long)ubr);
        free(build);
        return to_jstring(env, buf);
    }
    jstring result = to_jstring(env, build);
    free(build);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeOsVersion(
    JNIEnv *env, jclass clazz) {
    DWORD major = 0, minor = 0;
    if (!reg_read_dword(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentMajorVersionNumber", &major))
        return NULL;
    reg_read_dword(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentMinorVersionNumber", &minor);
    char *build = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentBuildNumber");
    char buf[64];
    if (build) {
        snprintf(buf, sizeof(buf), "%lu.%lu.%s", (unsigned long)major, (unsigned long)minor, build);
        free(build);
    } else {
        snprintf(buf, sizeof(buf), "%lu.%lu", (unsigned long)major, (unsigned long)minor);
    }
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeLongOsVersion(
    JNIEnv *env, jclass clazz) {
    char *product = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"ProductName");
    char *display_ver = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"DisplayVersion");
    char *build = reg_read_string(HKEY_LOCAL_MACHINE, NT_CURRENT_VERSION, L"CurrentBuildNumber");

    char buf[256];
    // Detect Win11
    int build_num = build ? atoi(build) : 0;
    const char *name = product ? product : "Windows";
    if (build_num >= 22000 && product && strstr(product, "Windows 10")) {
        // Use "Windows 11" in long version
        char *p = strstr(product, "10");
        if (p) {
            size_t prefix_len = p - product;
            char modified[256];
            snprintf(modified, prefix_len + 1, "%s", product);
            snprintf(modified + prefix_len, sizeof(modified) - prefix_len, "11%s", p + 2);
            name = modified;
            if (display_ver && build) {
                snprintf(buf, sizeof(buf), "%s %s (Build %s)", modified, display_ver, build);
            } else if (build) {
                snprintf(buf, sizeof(buf), "%s (Build %s)", modified, build);
            } else {
                snprintf(buf, sizeof(buf), "%s", modified);
            }
            goto done;
        }
    }

    if (display_ver && build) {
        snprintf(buf, sizeof(buf), "%s %s (Build %s)", name, display_ver, build);
    } else if (build) {
        snprintf(buf, sizeof(buf), "%s (Build %s)", name, build);
    } else {
        snprintf(buf, sizeof(buf), "%s", name);
    }

done:
    if (product) free(product);
    if (display_ver) free(display_ver);
    if (build) free(build);
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDistributionId(
    JNIEnv *env, jclass clazz) {
    return to_jstring(env, "windows");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeHostName(
    JNIEnv *env, jclass clazz) {
    wchar_t buf[256];
    DWORD size = sizeof(buf) / sizeof(buf[0]);
    if (!GetComputerNameExW(ComputerNamePhysicalDnsHostname, buf, &size)) return NULL;
    return wchar_to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeCpuArch(
    JNIEnv *env, jclass clazz) {
    SYSTEM_INFO si;
    GetNativeSystemInfo(&si);
    switch (si.wProcessorArchitecture) {
        case PROCESSOR_ARCHITECTURE_AMD64: return to_jstring(env, "x86_64");
        case PROCESSOR_ARCHITECTURE_ARM64: return to_jstring(env, "aarch64");
        case PROCESSOR_ARCHITECTURE_INTEL: return to_jstring(env, "x86");
        case PROCESSOR_ARCHITECTURE_ARM:   return to_jstring(env, "arm");
        case PROCESSOR_ARCHITECTURE_IA64:  return to_jstring(env, "ia64");
        default: return to_jstring(env, "unknown");
    }
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUptime(
    JNIEnv *env, jclass clazz) {
    return (jlong)(GetTickCount64() / 1000ULL);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBootTime(
    JNIEnv *env, jclass clazz) {
    ULONGLONG uptime_ms = GetTickCount64();
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    // FILETIME is 100-nanosecond intervals since 1601-01-01
    // Unix epoch offset: 116444736000000000
    ULARGE_INTEGER uli;
    uli.LowPart = ft.dwLowDateTime;
    uli.HighPart = ft.dwHighDateTime;
    // Convert to seconds since Unix epoch
    jlong now_sec = (jlong)((uli.QuadPart - 116444736000000000ULL) / 10000000ULL);
    return now_sec - (jlong)(uptime_ms / 1000ULL);
}
