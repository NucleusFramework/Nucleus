// Process information.
// Sources: libproc (proc_listallpids, proc_pidinfo, proc_pidpath), sysctl KERN_PROCARGS2

#include "nucleus_system_info_common.h"
#include <libproc.h>
#include <sys/proc_info.h>
#include <mach/mach_time.h>
#include <time.h>

#define MAX_PROCS 8192

typedef struct {
    long pid;
    char name[256];
    char exe[PROC_PIDPATHINFO_MAXSIZE];
    long long memory;         // Resident memory in bytes
    long long virtual_memory; // Virtual memory in bytes
    float cpu_usage;
    char status[32];
    long long start_time;     // Unix timestamp
    long long run_time;       // Seconds
    long parent_pid;
    char cmd[2048];           // NUL-separated command line
    char cwd[MAXPATHLEN];
    char root[MAXPATHLEN];
} proc_entry_t;

static int g_proc_count = 0;
static proc_entry_t g_procs[MAX_PROCS];

static void parse_process_status(uint32_t status, char *buf, size_t bufsize) {
    switch (status) {
        case SIDL:  strncpy(buf, "Idle", bufsize); break;
        case SRUN:  strncpy(buf, "Run", bufsize); break;
        case SSLEEP: strncpy(buf, "Sleep", bufsize); break;
        case SSTOP: strncpy(buf, "Stop", bufsize); break;
        case SZOMB: strncpy(buf, "Zombie", bufsize); break;
        default:    strncpy(buf, "Unknown", bufsize); break;
    }
}

static int read_process_info(pid_t pid, proc_entry_t *pe) {
    memset(pe, 0, sizeof(proc_entry_t));
    pe->pid = pid;
    pe->parent_pid = -1;

    // Basic BSD info: name, ppid, status, start time
    struct proc_bsdinfo bsdinfo;
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &bsdinfo, sizeof(bsdinfo)) > 0) {
        strncpy(pe->name, bsdinfo.pbi_comm, sizeof(pe->name) - 1);
        pe->parent_pid = bsdinfo.pbi_ppid;
        pe->start_time = (long long)bsdinfo.pbi_start_tvsec;
        pe->run_time = (long long)(time(NULL) - bsdinfo.pbi_start_tvsec);
        parse_process_status(bsdinfo.pbi_status, pe->status, sizeof(pe->status));
    } else {
        return -1;
    }

    // Task info: memory, CPU time
    struct proc_taskinfo taskinfo;
    if (proc_pidinfo(pid, PROC_PIDTASKINFO, 0, &taskinfo, sizeof(taskinfo)) > 0) {
        pe->memory = (long long)taskinfo.pti_resident_size;
        pe->virtual_memory = (long long)taskinfo.pti_virtual_size;
        // CPU usage: total user+system time / elapsed time
        uint64_t total_time_ns = taskinfo.pti_total_user + taskinfo.pti_total_system;
        double elapsed = (double)pe->run_time;
        if (elapsed > 0) {
            pe->cpu_usage = (float)((double)total_time_ns / 1e9 / elapsed * 100.0);
        }
    }

    // Executable path
    proc_pidpath(pid, pe->exe, sizeof(pe->exe));

    // Current working directory and root
    struct proc_vnodepathinfo vnodeinfo;
    if (proc_pidinfo(pid, PROC_PIDVNODEPATHINFO, 0, &vnodeinfo, sizeof(vnodeinfo)) > 0) {
        strncpy(pe->cwd, vnodeinfo.pvi_cdir.vip_path, sizeof(pe->cwd) - 1);
        strncpy(pe->root, vnodeinfo.pvi_rdir.vip_path, sizeof(pe->root) - 1);
    }

    // Command line via sysctl KERN_PROCARGS2
    int mib[3] = { CTL_KERN, KERN_PROCARGS2, pid };
    size_t argmax = sizeof(pe->cmd);
    char argbuf[4096];
    size_t argbuf_size = sizeof(argbuf);
    if (sysctl(mib, 3, argbuf, &argbuf_size, NULL, 0) == 0 && argbuf_size > sizeof(int)) {
        // First 4 bytes = argc
        int argc = 0;
        memcpy(&argc, argbuf, sizeof(int));
        // After argc: exec_path\0, then NUL-separated argv entries
        char *p = argbuf + sizeof(int);
        char *end = argbuf + argbuf_size;
        // Skip exec path
        while (p < end && *p != '\0') p++;
        // Skip trailing NULs after exec path
        while (p < end && *p == '\0') p++;
        // Copy the NUL-separated argv
        size_t remaining = end - p;
        if (remaining > sizeof(pe->cmd) - 1) remaining = sizeof(pe->cmd) - 1;
        if (remaining > 0) {
            memcpy(pe->cmd, p, remaining);
            pe->cmd[remaining] = '\0';
        }
    }

    return 0;
}

static void refresh_processes(void) {
    g_proc_count = 0;
    int count = proc_listallpids(NULL, 0);
    if (count <= 0) return;

    pid_t *pids = (pid_t *)malloc(count * sizeof(pid_t));
    if (!pids) return;
    count = proc_listallpids(pids, count * sizeof(pid_t));
    if (count <= 0) { free(pids); return; }

    for (int i = 0; i < count && g_proc_count < MAX_PROCS; i++) {
        if (pids[i] <= 0) continue;
        if (read_process_info(pids[i], &g_procs[g_proc_count]) == 0) {
            g_proc_count++;
        }
    }
    free(pids);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessCount(
    JNIEnv *env, jclass clazz) {
    refresh_processes();
    return (jint)g_proc_count;
}

#define PROC_STRING_ARRAY(jni_name, field) \
JNIEXPORT jobjectArray JNICALL \
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_##jni_name( \
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
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_##jni_name( \
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
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessCpuUsages(
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

static int read_single_process(pid_t pid, proc_entry_t *pe) {
    return read_process_info(pid, pe);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidName(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.name);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidExe(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.exe);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidMemory(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return 0;
    return (jlong)pe.memory;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidVirtualMemory(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return 0;
    return (jlong)pe.virtual_memory;
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidCpuUsage(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return 0.0f;
    return pe.cpu_usage;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidStatus(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.status);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidStartTime(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return 0;
    return (jlong)pe.start_time;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidRunTime(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return 0;
    return (jlong)pe.run_time;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidParentPid(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return -1;
    return (jlong)pe.parent_pid;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidCmd(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.cmd);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidCwd(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.cwd);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPidRoot(
    JNIEnv *env, jclass clazz, jlong pid) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return NULL;
    return to_jstring(env, pe.root);
}
