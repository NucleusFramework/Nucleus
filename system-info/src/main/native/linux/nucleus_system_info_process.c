// Process information.
// Sources: /proc/[pid]/stat, /proc/[pid]/status, /proc/[pid]/cmdline, /proc/[pid]/exe, /proc/[pid]/cwd

#include "nucleus_system_info_common.h"
#include <dirent.h>
#include <sys/types.h>
#include <ctype.h>
#include <time.h>

#define MAX_PROCS 8192

typedef struct {
    long pid;
    char name[256];
    char exe[512];
    long long memory;         // RSS in bytes
    long long virtual_memory; // VmSize in bytes
    float cpu_usage;
    char status[32];
    long long start_time;     // Unix timestamp
    long long run_time;       // seconds
    long parent_pid;
    char cmd[1024];           // NUL-separated cmdline
    char cwd[512];
    char root[512];
} proc_entry_t;

static int g_proc_count = 0;
static proc_entry_t g_procs[MAX_PROCS];

static long get_clock_ticks(void) {
    static long ticks = 0;
    if (ticks == 0) ticks = sysconf(_SC_CLK_TCK);
    return ticks;
}

static long long get_boot_time(void) {
    static long long boot = 0;
    if (boot == 0) {
        char buf[256];
        size_t len;
        char *contents = read_file_contents("/proc/stat", &len);
        if (contents) {
            char *p = strstr(contents, "btime ");
            if (p) boot = atoll(p + 6);
            free(contents);
        }
    }
    return boot;
}

// Parse /proc/[pid]/stat to get process status, ppid, utime, stime, starttime, rss, vsize
static int parse_proc_stat(long pid, proc_entry_t *pe) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
    size_t len;
    char *contents = read_file_contents(path, &len);
    if (!contents) return -1;

    // Find the comm field (enclosed in parentheses) — handle names with spaces/parens
    char *open_paren = strchr(contents, '(');
    char *close_paren = strrchr(contents, ')');
    if (!open_paren || !close_paren) { free(contents); return -1; }

    // Extract name
    size_t name_len = close_paren - open_paren - 1;
    if (name_len >= sizeof(pe->name)) name_len = sizeof(pe->name) - 1;
    memcpy(pe->name, open_paren + 1, name_len);
    pe->name[name_len] = '\0';

    // Parse fields after the closing paren
    // state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt
    // utime stime cutime cstime priority nice num_threads itrealvalue starttime
    // vsize rss ...
    char state_char;
    long ppid;
    unsigned long long utime, stime, starttime, vsize;
    long rss;
    int matched = sscanf(close_paren + 2,
        "%c %ld %*d %*d %*d %*d %*u %*u %*u %*u %*u "
        "%llu %llu %*u %*u %*d %*d %*d %*d %llu "
        "%llu %ld",
        &state_char, &ppid, &utime, &stime, &starttime, &vsize, &rss);
    free(contents);
    if (matched < 7) return -1;

    pe->pid = pid;
    pe->parent_pid = ppid;
    pe->virtual_memory = (long long)vsize;
    pe->memory = rss * sysconf(_SC_PAGESIZE);

    long ticks = get_clock_ticks();
    long long boot_time = get_boot_time();
    pe->start_time = boot_time + (long long)(starttime / ticks);
    pe->run_time = time(NULL) - pe->start_time;

    // CPU usage approximation (snapshot, not delta-based)
    unsigned long long total_time = utime + stime;
    double seconds = (double)(time(NULL) - pe->start_time);
    pe->cpu_usage = (seconds > 0) ? (float)((double)total_time / (double)ticks / seconds * 100.0) : 0.0f;

    switch (state_char) {
        case 'R': strncpy(pe->status, "Run", sizeof(pe->status)); break;
        case 'S': strncpy(pe->status, "Sleep", sizeof(pe->status)); break;
        case 'D': strncpy(pe->status, "UninterruptibleDiskSleep", sizeof(pe->status)); break;
        case 'Z': strncpy(pe->status, "Zombie", sizeof(pe->status)); break;
        case 'T': strncpy(pe->status, "Stop", sizeof(pe->status)); break;
        case 't': strncpy(pe->status, "Tracing", sizeof(pe->status)); break;
        case 'I': strncpy(pe->status, "Idle", sizeof(pe->status)); break;
        default:  strncpy(pe->status, "Unknown", sizeof(pe->status)); break;
    }
    return 0;
}

static void read_proc_extras(long pid, proc_entry_t *pe) {
    // exe
    char link_path[64];
    snprintf(link_path, sizeof(link_path), "/proc/%ld/exe", pid);
    ssize_t n = readlink(link_path, pe->exe, sizeof(pe->exe) - 1);
    if (n > 0) pe->exe[n] = '\0'; else pe->exe[0] = '\0';

    // cwd
    snprintf(link_path, sizeof(link_path), "/proc/%ld/cwd", pid);
    n = readlink(link_path, pe->cwd, sizeof(pe->cwd) - 1);
    if (n > 0) pe->cwd[n] = '\0'; else pe->cwd[0] = '\0';

    // root
    snprintf(link_path, sizeof(link_path), "/proc/%ld/root", pid);
    n = readlink(link_path, pe->root, sizeof(pe->root) - 1);
    if (n > 0) pe->root[n] = '\0'; else pe->root[0] = '\0';

    // cmdline (NUL-separated)
    char cmd_path[64];
    snprintf(cmd_path, sizeof(cmd_path), "/proc/%ld/cmdline", pid);
    FILE *f = fopen(cmd_path, "r");
    if (f) {
        size_t read = fread(pe->cmd, 1, sizeof(pe->cmd) - 1, f);
        pe->cmd[read] = '\0';
        fclose(f);
    } else {
        pe->cmd[0] = '\0';
    }
}

static void refresh_processes(void) {
    g_proc_count = 0;
    DIR *proc_dir = opendir("/proc");
    if (!proc_dir) return;
    struct dirent *ent;
    while ((ent = readdir(proc_dir)) && g_proc_count < MAX_PROCS) {
        // Only numeric directories (PIDs)
        if (!isdigit(ent->d_name[0])) continue;
        long pid = atol(ent->d_name);
        if (pid <= 0) continue;

        proc_entry_t *pe = &g_procs[g_proc_count];
        memset(pe, 0, sizeof(proc_entry_t));
        if (parse_proc_stat(pid, pe) == 0) {
            read_proc_extras(pid, pe);
            g_proc_count++;
        }
    }
    closedir(proc_dir);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessCount(
    JNIEnv *env, jclass clazz) {
    refresh_processes();
    return (jint)g_proc_count;
}

#define PROC_STRING_ARRAY(jni_name, field) \
JNIEXPORT jobjectArray JNICALL \
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_##jni_name( \
    JNIEnv *env, jclass clazz) { \
    if (g_proc_count <= 0) return NULL; \
    const char **arr = (const char **)malloc(g_proc_count * sizeof(char *)); \
    for (int i = 0; i < g_proc_count; i++) arr[i] = g_procs[i].field; \
    jobjectArray result = to_string_array(env, arr, g_proc_count); \
    free(arr); \
    return result; \
}

#define PROC_LONG_ARRAY(jni_name, field) \
JNIEXPORT jlongArray JNICALL \
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_##jni_name( \
    JNIEnv *env, jclass clazz) { \
    if (g_proc_count <= 0) return NULL; \
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong)); \
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].field; \
    jlongArray result = (*env)->NewLongArray(env, g_proc_count); \
    (*env)->SetLongArrayRegion(env, result, 0, g_proc_count, vals); \
    free(vals); \
    return result; \
}

PROC_LONG_ARRAY(nativeProcessPids, pid)
PROC_STRING_ARRAY(nativeProcessNames, name)
PROC_STRING_ARRAY(nativeProcessExes, exe)
PROC_LONG_ARRAY(nativeProcessMemories, memory)
PROC_LONG_ARRAY(nativeProcessVirtualMemories, virtual_memory)
PROC_STRING_ARRAY(nativeProcessStatuses, status)
PROC_LONG_ARRAY(nativeProcessStartTimes, start_time)
PROC_LONG_ARRAY(nativeProcessRunTimes, run_time)
PROC_LONG_ARRAY(nativeProcessParentPids, parent_pid)
PROC_STRING_ARRAY(nativeProcessCmds, cmd)
PROC_STRING_ARRAY(nativeProcessCwds, cwd)
PROC_STRING_ARRAY(nativeProcessRoots, root)

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessCpuUsages(
    JNIEnv *env, jclass clazz) {
    if (g_proc_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_proc_count * sizeof(jfloat));
    for (int i = 0; i < g_proc_count; i++) vals[i] = g_procs[i].cpu_usage;
    jfloatArray result = (*env)->NewFloatArray(env, g_proc_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_proc_count, vals);
    free(vals);
    return result;
}

// Single process by PID
static int read_single_process(long pid, proc_entry_t *pe) {
    memset(pe, 0, sizeof(proc_entry_t));
    if (parse_proc_stat(pid, pe) != 0) return -1;
    read_proc_extras(pid, pe);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidName(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.name);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidExe(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.exe);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidMemory(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return 0;
    return (jlong)pe.memory;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidVirtualMemory(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return 0;
    return (jlong)pe.virtual_memory;
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidCpuUsage(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return 0.0f;
    return pe.cpu_usage;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidStatus(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.status);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidStartTime(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return 0;
    return (jlong)pe.start_time;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidRunTime(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return 0;
    return (jlong)pe.run_time;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidParentPid(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return -1;
    return (jlong)pe.parent_pid;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidCmd(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.cmd);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidCwd(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.cwd);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProcessByPidRoot(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((long)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.root);
}
