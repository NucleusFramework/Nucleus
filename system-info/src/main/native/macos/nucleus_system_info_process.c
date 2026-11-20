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
    char *cmd;                // argv, each argument NUL-terminated, malloc'd
    size_t cmd_len;
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

// Command line via sysctl KERN_PROCARGS2. The buffer holds argc, the exec path,
// NUL padding, the argc argv strings and then the environment: stop after argc
// strings so envp never reaches Java.
static void read_process_cmd(pid_t pid, proc_entry_t *pe) {
    static int argmax = 0;
    if (argmax <= 0) {
        int argmax_mib[2] = { CTL_KERN, KERN_ARGMAX };
        size_t argmax_size = sizeof(argmax);
        if (sysctl(argmax_mib, 2, &argmax, &argmax_size, NULL, 0) != 0 || argmax <= 0) return;
    }
    char *buf = (char *)malloc((size_t)argmax);
    if (!buf) return;

    int mib[3] = { CTL_KERN, KERN_PROCARGS2, pid };
    size_t size = (size_t)argmax;
    if (sysctl(mib, 3, buf, &size, NULL, 0) != 0 || size <= sizeof(int)) { free(buf); return; }

    int argc = 0;
    memcpy(&argc, buf, sizeof(int));
    char *p = buf + sizeof(int);
    char *end = buf + size;
    while (p < end && *p != '\0') p++;  // exec path
    while (p < end && *p == '\0') p++;  // padding
    char *args = p;
    for (int i = 0; i < argc && p < end; i++) {
        while (p < end && *p != '\0') p++;
        if (p < end) p++;               // keep the argument's NUL terminator
    }

    size_t len = (size_t)(p - args);
    if (len > 0) {
        memmove(buf, args, len);
        char *cmd = (char *)realloc(buf, len);
        pe->cmd = cmd ? cmd : buf;
        pe->cmd_len = len;
    } else {
        free(buf);
    }
}

static void free_proc_entry(proc_entry_t *pe) {
    free(pe->cmd);
    pe->cmd = NULL;
    pe->cmd_len = 0;
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

    read_process_cmd(pid, pe);

    return 0;
}

static void refresh_processes(void) {
    for (int i = 0; i < g_proc_count; i++) free_proc_entry(&g_procs[i]);
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
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessCount(
    JNIEnv *env, jclass clazz) {
    refresh_processes();
    return (jint)g_proc_count;
}

#define PROC_STRING_ARRAY(jni_name, field) \
JNIEXPORT jobjectArray JNICALL \
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_##jni_name( \
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
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_##jni_name( \
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

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessCmds(
    JNIEnv *env, jclass clazz) {
    if (g_proc_count <= 0) return NULL;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, g_proc_count, string_class, NULL);
    (*env)->DeleteLocalRef(env, string_class);
    for (int i = 0; i < g_proc_count; i++) {
        jstring js = to_jstring_bytes(env, g_procs[i].cmd ? g_procs[i].cmd : "", g_procs[i].cmd_len);
        (*env)->SetObjectArrayElement(env, arr, i, js);
        if (js) (*env)->DeleteLocalRef(env, js);
    }
    return arr;
}

PROC_STRING_ARRAY(nativeProcessCwds, cwd)
PROC_STRING_ARRAY(nativeProcessRoots, root)

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessCpuUsages(
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

// Slots of nativeProcessByPid's arrays, mirrored in NativeMacOsSystemInfoBridge.
enum { PROCESS_LONG_PARENT_PID, PROCESS_LONG_MEMORY, PROCESS_LONG_VIRTUAL_MEMORY,
       PROCESS_LONG_START_TIME, PROCESS_LONG_RUN_TIME, PROCESS_LONG_COUNT };
enum { PROCESS_FLOAT_CPU_USAGE, PROCESS_FLOAT_COUNT };
enum { PROCESS_STRING_NAME, PROCESS_STRING_EXE, PROCESS_STRING_STATUS, PROCESS_STRING_CMD,
       PROCESS_STRING_CWD, PROCESS_STRING_ROOT, PROCESS_STRING_COUNT };

static void set_jstring_slot(JNIEnv *env, jobjectArray strings, int slot, jstring js) {
    (*env)->SetObjectArrayElement(env, strings, slot, js);
    if (js) (*env)->DeleteLocalRef(env, js);
}

static void set_string_slot(JNIEnv *env, jobjectArray strings, int slot, const char *value) {
    set_jstring_slot(env, strings, slot, to_jstring(env, value));
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeProcessByPid(
    JNIEnv *env, jclass clazz, jlong pid, jlongArray longs, jfloatArray floats, jobjectArray strings) {
    proc_entry_t pe;
    if (read_single_process((pid_t)pid, &pe) != 0) return JNI_FALSE;

    jlong long_values[PROCESS_LONG_COUNT];
    long_values[PROCESS_LONG_PARENT_PID] = (jlong)pe.parent_pid;
    long_values[PROCESS_LONG_MEMORY] = (jlong)pe.memory;
    long_values[PROCESS_LONG_VIRTUAL_MEMORY] = (jlong)pe.virtual_memory;
    long_values[PROCESS_LONG_START_TIME] = (jlong)pe.start_time;
    long_values[PROCESS_LONG_RUN_TIME] = (jlong)pe.run_time;
    (*env)->SetLongArrayRegion(env, longs, 0, PROCESS_LONG_COUNT, long_values);

    jfloat float_values[PROCESS_FLOAT_COUNT];
    float_values[PROCESS_FLOAT_CPU_USAGE] = pe.cpu_usage;
    (*env)->SetFloatArrayRegion(env, floats, 0, PROCESS_FLOAT_COUNT, float_values);

    set_string_slot(env, strings, PROCESS_STRING_NAME, pe.name);
    set_string_slot(env, strings, PROCESS_STRING_EXE, pe.exe);
    set_string_slot(env, strings, PROCESS_STRING_STATUS, pe.status);
    set_jstring_slot(env, strings, PROCESS_STRING_CMD,
                     to_jstring_bytes(env, pe.cmd ? pe.cmd : "", pe.cmd_len));
    set_string_slot(env, strings, PROCESS_STRING_CWD, pe.cwd);
    set_string_slot(env, strings, PROCESS_STRING_ROOT, pe.root);
    free_proc_entry(&pe);
    return JNI_TRUE;
}
