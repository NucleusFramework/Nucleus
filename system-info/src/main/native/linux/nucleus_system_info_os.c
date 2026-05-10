// OS information: name, kernel, version, hostname, arch, uptime, boot time.
// Sources: uname(), /etc/os-release, gethostname(), sysinfo()

#include "nucleus_system_info_common.h"
#include <sys/utsname.h>
#include <sys/sysinfo.h>
#include <time.h>

// Parse a value from /etc/os-release for the given key (e.g., "NAME", "VERSION_ID")
static char *parse_os_release(const char *key) {
    FILE *f = fopen("/etc/os-release", "r");
    if (!f) return NULL;
    char line[512];
    size_t key_len = strlen(key);
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, key, key_len) == 0 && line[key_len] == '=') {
            char *val = line + key_len + 1;
            // Strip quotes and newline
            size_t len = strlen(val);
            if (len > 0 && val[len - 1] == '\n') val[--len] = '\0';
            if (len >= 2 && val[0] == '"' && val[len - 1] == '"') {
                val[len - 1] = '\0';
                val++;
            }
            fclose(f);
            return strdup(val);
        }
    }
    fclose(f);
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeOsName(
    JNIEnv *env, jclass clazz) {
    char *name = parse_os_release("NAME");
    if (!name) return NULL;
    jstring result = to_jstring(env, name);
    free(name);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeKernelVersion(
    JNIEnv *env, jclass clazz) {
    struct utsname info;
    if (uname(&info) != 0) return NULL;
    return to_jstring(env, info.release);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeOsVersion(
    JNIEnv *env, jclass clazz) {
    char *version = parse_os_release("VERSION_ID");
    if (!version) return NULL;
    jstring result = to_jstring(env, version);
    free(version);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeLongOsVersion(
    JNIEnv *env, jclass clazz) {
    char *name = parse_os_release("PRETTY_NAME");
    if (!name) {
        // Fallback: "Linux (distro version)"
        char *distro = parse_os_release("NAME");
        char *ver = parse_os_release("VERSION_ID");
        if (distro && ver) {
            char buf[256];
            snprintf(buf, sizeof(buf), "Linux (%s %s)", distro, ver);
            free(distro);
            free(ver);
            return to_jstring(env, buf);
        }
        if (distro) free(distro);
        if (ver) free(ver);
        return NULL;
    }
    jstring result = to_jstring(env, name);
    free(name);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeDistributionId(
    JNIEnv *env, jclass clazz) {
    char *id = parse_os_release("ID");
    if (!id) return to_jstring(env, "linux");
    jstring result = to_jstring(env, id);
    free(id);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeHostName(
    JNIEnv *env, jclass clazz) {
    char buf[256];
    if (gethostname(buf, sizeof(buf)) != 0) return NULL;
    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuArch(
    JNIEnv *env, jclass clazz) {
    struct utsname info;
    if (uname(&info) != 0) return NULL;
    return to_jstring(env, info.machine);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeUptime(
    JNIEnv *env, jclass clazz) {
    struct sysinfo si;
    if (sysinfo(&si) != 0) return 0;
    return (jlong)si.uptime;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBootTime(
    JNIEnv *env, jclass clazz) {
    struct sysinfo si;
    if (sysinfo(&si) != 0) return 0;
    time_t now = time(NULL);
    return (jlong)(now - si.uptime);
}
