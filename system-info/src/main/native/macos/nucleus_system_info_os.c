// OS information: name, kernel, version, hostname, arch, uptime, boot time.
// Sources: sysctl, gethostname()

#include "nucleus_system_info_common.h"
#include <sys/types.h>
#include <time.h>

// Map major macOS version to marketing name
static const char *macos_codename(int major, int minor) {
    switch (major) {
        case 15: return "Sequoia";
        case 14: return "Sonoma";
        case 13: return "Ventura";
        case 12: return "Monterey";
        case 11: return "Big Sur";
        case 10:
            switch (minor) {
                case 15: return "Catalina";
                case 14: return "Mojave";
                case 13: return "High Sierra";
                case 12: return "Sierra";
                default: return NULL;
            }
        default: return NULL;
    }
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeOsName(
    JNIEnv *env, jclass clazz) {
    return to_jstring(env, "macOS");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeKernelVersion(
    JNIEnv *env, jclass clazz) {
    char *ver = sysctl_string("kern.osrelease");
    if (!ver) return NULL;
    jstring result = to_jstring(env, ver);
    free(ver);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeOsVersion(
    JNIEnv *env, jclass clazz) {
    char *ver = sysctl_string("kern.osproductversion");
    if (!ver) return NULL;
    jstring result = to_jstring(env, ver);
    free(ver);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeLongOsVersion(
    JNIEnv *env, jclass clazz) {
    char *version = sysctl_string("kern.osproductversion");
    char *kernel = sysctl_string("kern.osrelease");
    if (!version) { if (kernel) free(kernel); return NULL; }

    int major = 0, minor = 0;
    sscanf(version, "%d.%d", &major, &minor);
    const char *codename = macos_codename(major, minor);

    char buf[256];
    if (codename) {
        snprintf(buf, sizeof(buf), "macOS %s %s (Darwin %s)", version, codename,
                 kernel ? kernel : "");
    } else {
        snprintf(buf, sizeof(buf), "macOS %s (Darwin %s)", version, kernel ? kernel : "");
    }

    free(version);
    if (kernel) free(kernel);
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeDistributionId(
    JNIEnv *env, jclass clazz) {
    return to_jstring(env, "macos");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeHostName(
    JNIEnv *env, jclass clazz) {
    char buf[256];
    if (gethostname(buf, sizeof(buf)) != 0) return NULL;
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeCpuArch(
    JNIEnv *env, jclass clazz) {
    char *arch = sysctl_string("hw.machine");
    if (!arch) return NULL;
    jstring result = to_jstring(env, arch);
    free(arch);
    return result;
}

static int64_t get_boot_time_sec(void) {
    struct timeval boottime;
    size_t len = sizeof(boottime);
    int mib[2] = { CTL_KERN, KERN_BOOTTIME };
    if (sysctl(mib, 2, &boottime, &len, NULL, 0) != 0) return 0;
    return (int64_t)boottime.tv_sec;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUptime(
    JNIEnv *env, jclass clazz) {
    int64_t boot = get_boot_time_sec();
    if (boot == 0) return 0;
    return (jlong)(time(NULL) - boot);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeBootTime(
    JNIEnv *env, jclass clazz) {
    return (jlong)get_boot_time_sec();
}
