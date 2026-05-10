// CPU information: per-core details, usage, frequency, physical core count.
// Sources: /proc/cpuinfo, /proc/stat, sysconf()

#include "nucleus_system_info_common.h"
#include <ctype.h>

#define MAX_CPUS 1024

// Static storage for CPU usage calculation (requires two samples)
static unsigned long long prev_total[MAX_CPUS + 1];
static unsigned long long prev_idle[MAX_CPUS + 1];
static int usage_initialized = 0;

static int count_logical_cpus(void) {
    return (int)sysconf(_SC_NPROCESSORS_ONLN);
}

// Parse /proc/cpuinfo for a per-CPU field value
typedef struct {
    char vendor_id[128];
    char model_name[256];
    double mhz;
} cpuinfo_entry_t;

static int parse_cpuinfo(cpuinfo_entry_t *entries, int max_entries) {
    FILE *f = fopen("/proc/cpuinfo", "r");
    if (!f) return 0;
    char line[512];
    int idx = -1;
    while (fgets(line, sizeof(line), f) && idx < max_entries) {
        if (strncmp(line, "processor", 9) == 0) {
            idx++;
            memset(&entries[idx], 0, sizeof(cpuinfo_entry_t));
        } else if (idx >= 0) {
            if (strncmp(line, "vendor_id", 9) == 0) {
                char *val = strchr(line, ':');
                if (val) {
                    val++;
                    while (*val == ' ') val++;
                    size_t len = strlen(val);
                    if (len > 0 && val[len - 1] == '\n') val[len - 1] = '\0';
                    strncpy(entries[idx].vendor_id, val, sizeof(entries[idx].vendor_id) - 1);
                }
            } else if (strncmp(line, "model name", 10) == 0) {
                char *val = strchr(line, ':');
                if (val) {
                    val++;
                    while (*val == ' ') val++;
                    size_t len = strlen(val);
                    if (len > 0 && val[len - 1] == '\n') val[len - 1] = '\0';
                    strncpy(entries[idx].model_name, val, sizeof(entries[idx].model_name) - 1);
                }
            } else if (strncmp(line, "cpu MHz", 7) == 0) {
                char *val = strchr(line, ':');
                if (val) entries[idx].mhz = atof(val + 1);
            }
        }
    }
    fclose(f);
    return idx + 1;
}

// Read /proc/stat and compute CPU usage since last call
static float compute_global_cpu_usage(void) {
    FILE *f = fopen("/proc/stat", "r");
    if (!f) return 0.0f;
    char line[256];
    if (!fgets(line, sizeof(line), f)) { fclose(f); return 0.0f; }
    fclose(f);

    // Parse: cpu  user nice system idle iowait irq softirq steal
    unsigned long long user, nice, system, idle, iowait, irq, softirq, steal;
    if (sscanf(line, "cpu  %llu %llu %llu %llu %llu %llu %llu %llu",
               &user, &nice, &system, &idle, &iowait, &irq, &softirq, &steal) < 4) {
        return 0.0f;
    }

    unsigned long long total = user + nice + system + idle + iowait + irq + softirq + steal;
    unsigned long long idle_total = idle + iowait;

    if (!usage_initialized) {
        prev_total[0] = total;
        prev_idle[0] = idle_total;
        usage_initialized = 1;
        return 0.0f;
    }

    unsigned long long total_diff = total - prev_total[0];
    unsigned long long idle_diff = idle_total - prev_idle[0];
    prev_total[0] = total;
    prev_idle[0] = idle_total;

    if (total_diff == 0) return 0.0f;
    return (float)(total_diff - idle_diff) / (float)total_diff * 100.0f;
}

// Read per-CPU usage from /proc/stat
static void compute_per_cpu_usage(float *usages, int count) {
    FILE *f = fopen("/proc/stat", "r");
    if (!f) return;
    char line[256];
    // Skip the aggregate "cpu" line
    if (!fgets(line, sizeof(line), f)) { fclose(f); return; }
    for (int i = 0; i < count && fgets(line, sizeof(line), f); i++) {
        if (strncmp(line, "cpu", 3) != 0) break;
        unsigned long long user, nice, system, idle_val, iowait = 0, irq = 0, softirq = 0, steal = 0;
        sscanf(line + 3, "%*d %llu %llu %llu %llu %llu %llu %llu %llu",
               &user, &nice, &system, &idle_val, &iowait, &irq, &softirq, &steal);
        unsigned long long total = user + nice + system + idle_val + iowait + irq + softirq + steal;
        unsigned long long idle_total = idle_val + iowait;
        int idx = i + 1; // index 0 is global
        if (idx < MAX_CPUS + 1) {
            if (prev_total[idx] == 0 && prev_idle[idx] == 0) {
                prev_total[idx] = total;
                prev_idle[idx] = idle_total;
                usages[i] = 0.0f;
            } else {
                unsigned long long total_diff = total - prev_total[idx];
                unsigned long long idle_diff = idle_total - prev_idle[idx];
                prev_total[idx] = total;
                prev_idle[idx] = idle_total;
                usages[i] = (total_diff == 0) ? 0.0f :
                    (float)(total_diff - idle_diff) / (float)total_diff * 100.0f;
            }
        }
    }
    fclose(f);
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGlobalCpuUsage(
    JNIEnv *env, jclass clazz) {
    return (jfloat)compute_global_cpu_usage();
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativePhysicalCoreCount(
    JNIEnv *env, jclass clazz) {
    // Count unique "core id" entries per "physical id" in /proc/cpuinfo
    FILE *f = fopen("/proc/cpuinfo", "r");
    if (!f) return 0;
    char line[256];
    // Simple approach: count unique "core id" lines (set-based would be ideal but overkill)
    // Use sysconf as a fallback — _SC_NPROCESSORS_CONF gives logical count
    // For physical: parse "cpu cores" from first processor block
    int cores = 0;
    int sockets = 0;
    int last_physical_id = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "cpu cores", 9) == 0) {
            char *val = strchr(line, ':');
            if (val) {
                int c = atoi(val + 1);
                if (c > cores) cores = c;
            }
        } else if (strncmp(line, "physical id", 11) == 0) {
            char *val = strchr(line, ':');
            if (val) {
                int pid = atoi(val + 1);
                if (pid != last_physical_id) {
                    sockets++;
                    last_physical_id = pid;
                }
            }
        }
    }
    fclose(f);
    if (sockets == 0) sockets = 1;
    return (jint)(cores * sockets > 0 ? cores * sockets : count_logical_cpus());
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuCount(
    JNIEnv *env, jclass clazz) {
    return (jint)count_logical_cpus();
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuNames(
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
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuVendorIds(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    cpuinfo_entry_t *entries = (cpuinfo_entry_t *)calloc(count, sizeof(cpuinfo_entry_t));
    int parsed = parse_cpuinfo(entries, count);
    const char **arr = (const char **)malloc(count * sizeof(char *));
    for (int i = 0; i < count; i++) {
        arr[i] = (i < parsed) ? entries[i].vendor_id : "";
    }
    jobjectArray result = to_string_array(env, arr, count);
    free(arr);
    free(entries);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuBrands(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    cpuinfo_entry_t *entries = (cpuinfo_entry_t *)calloc(count, sizeof(cpuinfo_entry_t));
    int parsed = parse_cpuinfo(entries, count);
    const char **arr = (const char **)malloc(count * sizeof(char *));
    for (int i = 0; i < count; i++) {
        arr[i] = (i < parsed) ? entries[i].model_name : "";
    }
    jobjectArray result = to_string_array(env, arr, count);
    free(arr);
    free(entries);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuFrequencies(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    cpuinfo_entry_t *entries = (cpuinfo_entry_t *)calloc(count, sizeof(cpuinfo_entry_t));
    int parsed = parse_cpuinfo(entries, count);
    jlong *freqs = (jlong *)calloc(count, sizeof(jlong));
    for (int i = 0; i < count; i++) {
        freqs[i] = (i < parsed) ? (jlong)entries[i].mhz : 0;
    }
    jlongArray result = (*env)->NewLongArray(env, count);
    (*env)->SetLongArrayRegion(env, result, 0, count, freqs);
    free(freqs);
    free(entries);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeCpuUsages(
    JNIEnv *env, jclass clazz) {
    int count = count_logical_cpus();
    if (count <= 0 || count > MAX_CPUS) return NULL;
    float *usages = (float *)calloc(count, sizeof(float));
    compute_per_cpu_usage(usages, count);
    jfloatArray result = (*env)->NewFloatArray(env, count);
    (*env)->SetFloatArrayRegion(env, result, 0, count, usages);
    free(usages);
    return result;
}
