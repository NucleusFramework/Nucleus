// Process information: enumeration, memory, CPU, status, paths.
// Sources: CreateToolhelp32Snapshot, OpenProcess, GetProcessMemoryInfo, GetProcessTimes

#include "nucleus_system_info_common.h"
#include <tlhelp32.h>
#include <psapi.h>

#define MAX_PROCS 8192

typedef struct {
    DWORD pid;
    DWORD ppid;
    char name[260];
    char exe[MAX_PATH];
    ULONGLONG memory;       // Working set (bytes)
    ULONGLONG virtual_mem;  // Page file usage (bytes)
    float cpu_usage;
    char status[16];        // "Run" or "Unknown"
    ULONGLONG start_time;   // Unix timestamp
    ULONGLONG run_time;     // Seconds
    char cmd[4096];         // NUL-separated command line
    char cwd[MAX_PATH];
} process_entry_t;

static process_entry_t g_procs[MAX_PROCS];
static int g_proc_count = 0;

// Convert FILETIME (100-ns intervals since 1601) to Unix seconds
static ULONGLONG filetime_to_unix(const FILETIME *ft) {
    ULARGE_INTEGER uli;
    uli.LowPart = ft->dwLowDateTime;
    uli.HighPart = ft->dwHighDateTime;
    if (uli.QuadPart < 116444736000000000ULL) return 0;
    return (uli.QuadPart - 116444736000000000ULL) / 10000000ULL;
}

static void get_process_details(process_entry_t *p) {
    p->exe[0] = '\0';
    p->cmd[0] = '\0';
    p->cwd[0] = '\0';
    p->memory = 0;
    p->virtual_mem = 0;
    p->cpu_usage = 0.0f;
    strcpy(p->status, "Run");
    p->start_time = 0;
    p->run_time = 0;

    // Try full access first, then limited
    HANDLE hProc = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, p->pid);
    if (!hProc) {
        hProc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, p->pid);
    }
    if (!hProc) {
        strcpy(p->status, "Unknown");
        return;
    }

    // Executable path
    wchar_t wexe[MAX_PATH];
    DWORD exe_size = MAX_PATH;
    if (QueryFullProcessImageNameW(hProc, 0, wexe, &exe_size)) {
        char *utf8 = wchar_to_utf8(wexe);
        if (utf8) {
            strncpy(p->exe, utf8, sizeof(p->exe) - 1);
            free(utf8);
        }
    }

    // Memory info
    PROCESS_MEMORY_COUNTERS_EX pmc;
    pmc.cb = sizeof(pmc);
    if (K32GetProcessMemoryInfo(hProc, (PROCESS_MEMORY_COUNTERS *)&pmc, sizeof(pmc))) {
        p->memory = pmc.WorkingSetSize;
        p->virtual_mem = pmc.PagefileUsage;
    }

    // Process times
    FILETIME create_ft, exit_ft, kernel_ft, user_ft;
    if (GetProcessTimes(hProc, &create_ft, &exit_ft, &kernel_ft, &user_ft)) {
        p->start_time = filetime_to_unix(&create_ft);

        FILETIME now_ft;
        GetSystemTimeAsFileTime(&now_ft);
        ULONGLONG now_unix = filetime_to_unix(&now_ft);
        if (now_unix > p->start_time) {
            p->run_time = now_unix - p->start_time;
        }
    }

    // Check if process is still alive
    DWORD exit_code;
    if (GetExitCodeProcess(hProc, &exit_code)) {
        if (exit_code != STILL_ACTIVE) {
            strcpy(p->status, "Unknown");
        }
    }

    CloseHandle(hProc);
}

static void refresh_processes(void) {
    g_proc_count = 0;

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return;

    PROCESSENTRY32W pe;
    pe.dwSize = sizeof(pe);
    if (!Process32FirstW(snap, &pe)) {
        CloseHandle(snap);
        return;
    }

    do {
        if (g_proc_count >= MAX_PROCS) break;
        process_entry_t *p = &g_procs[g_proc_count];
        memset(p, 0, sizeof(*p));

        p->pid = pe.th32ProcessID;
        p->ppid = pe.th32ParentProcessID;

        char *name = wchar_to_utf8(pe.szExeFile);
        if (name) {
            strncpy(p->name, name, sizeof(p->name) - 1);
            free(name);
        }

        get_process_details(p);
        g_proc_count++;
    } while (Process32NextW(snap, &pe));

    CloseHandle(snap);
}

// Helper: fill a single process entry by PID
static int fill_single_process(DWORD pid, process_entry_t *p) {
    memset(p, 0, sizeof(*p));
    p->pid = pid;

    // Get name from snapshot
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;

    PROCESSENTRY32W pe;
    pe.dwSize = sizeof(pe);
    int found = 0;
    if (Process32FirstW(snap, &pe)) {
        do {
            if (pe.th32ProcessID == pid) {
                char *name = wchar_to_utf8(pe.szExeFile);
                if (name) {
                    strncpy(p->name, name, sizeof(p->name) - 1);
                    free(name);
                }
                p->ppid = pe.th32ParentProcessID;
                found = 1;
                break;
            }
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);

    if (!found) return 0;
    get_process_details(p);
    return 1;
}

// --- JNI bulk process functions ---

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessCount(
    JNIEnv *env, jclass clazz) {
    refresh_processes();
    return g_proc_count;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessPids(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_proc_count);
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].pid;
    (*env)->SetLongArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_PROCS];
    for (int i = 0; i < g_proc_count; i++) names[i] = g_procs[i].name;
    return to_string_array(env, names, g_proc_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessExes(
    JNIEnv *env, jclass clazz) {
    const char *exes[MAX_PROCS];
    for (int i = 0; i < g_proc_count; i++) exes[i] = g_procs[i].exe;
    return to_string_array(env, exes, g_proc_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_proc_count);
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessVirtualMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_proc_count);
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].virtual_mem;
    (*env)->SetLongArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessCpuUsages(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_proc_count);
    jfloat *vals = (jfloat *)malloc(g_proc_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = g_procs[i].cpu_usage;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessStatuses(
    JNIEnv *env, jclass clazz) {
    const char *statuses[MAX_PROCS];
    for (int i = 0; i < g_proc_count; i++) statuses[i] = g_procs[i].status;
    return to_string_array(env, statuses, g_proc_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessStartTimes(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_proc_count);
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].start_time;
    (*env)->SetLongArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessRunTimes(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_proc_count);
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].run_time;
    (*env)->SetLongArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessParentPids(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_proc_count);
    jlong *vals = (jlong *)malloc(g_proc_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_proc_count; i++) vals[i] = (jlong)g_procs[i].ppid;
    (*env)->SetLongArrayRegion(env, arr, 0, g_proc_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessCmds(
    JNIEnv *env, jclass clazz) {
    const char *cmds[MAX_PROCS];
    for (int i = 0; i < g_proc_count; i++) cmds[i] = g_procs[i].cmd;
    return to_string_array(env, cmds, g_proc_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessCwds(
    JNIEnv *env, jclass clazz) {
    const char *cwds[MAX_PROCS];
    for (int i = 0; i < g_proc_count; i++) cwds[i] = g_procs[i].cwd;
    return to_string_array(env, cwds, g_proc_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessRoots(
    JNIEnv *env, jclass clazz) {
    // Windows doesn't have a per-process root concept; use exe drive root
    const char *roots[MAX_PROCS];
    static char root_bufs[MAX_PROCS][4];
    for (int i = 0; i < g_proc_count; i++) {
        if (g_procs[i].exe[0] && g_procs[i].exe[1] == ':') {
            root_bufs[i][0] = g_procs[i].exe[0];
            root_bufs[i][1] = ':';
            root_bufs[i][2] = '\\';
            root_bufs[i][3] = '\0';
        } else {
            root_bufs[i][0] = '\0';
        }
        roots[i] = root_bufs[i];
    }
    return to_string_array(env, roots, g_proc_count);
}

// --- Single process by PID ---

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidName(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return NULL;
    return to_jstring(env, p.name);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidExe(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return NULL;
    return to_jstring(env, p.exe);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidMemory(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return 0;
    return (jlong)p.memory;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidVirtualMemory(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return 0;
    return (jlong)p.virtual_mem;
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidCpuUsage(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return 0.0f;
    return p.cpu_usage;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidStatus(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return NULL;
    return to_jstring(env, p.status);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidStartTime(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return 0;
    return (jlong)p.start_time;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidRunTime(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return 0;
    return (jlong)p.run_time;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidParentPid(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return -1;
    return (jlong)p.ppid;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidCmd(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return NULL;
    return to_jstring(env, p.cmd);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidCwd(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return NULL;
    return to_jstring(env, p.cwd);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProcessByPidRoot(
    JNIEnv *env, jclass clazz, jlong pid) {
    process_entry_t p;
    if (!fill_single_process((DWORD)pid, &p)) return NULL;
    if (p.exe[0] && p.exe[1] == ':') {
        char root[4] = { p.exe[0], ':', '\\', '\0' };
        return to_jstring(env, root);
    }
    return NULL;
}
