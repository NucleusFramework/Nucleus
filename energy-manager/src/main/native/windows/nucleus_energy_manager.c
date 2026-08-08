/**
 * JNI bridge for Windows energy efficiency mode (EcoQoS).
 *
 * Provides native implementations for:
 *   - Checking if EcoQoS is supported (SetProcessInformation available)
 *   - Enabling/disabling process efficiency mode (EcoQoS + IDLE_PRIORITY_CLASS)
 *   - Enabling/disabling thread efficiency mode (SetThreadInformation EcoQoS
 *     on Windows 11+ with THREAD_PRIORITY_IDLE fallback)
 *
 * SetProcessInformation / SetThreadInformation are resolved via GetProcAddress
 * for runtime compatibility with older Windows versions.
 *
 * Linked libraries: kernel32.lib
 */

#include <jni.h>
#include <windows.h>

/* ---- /NODEFAULTLIB stubs ----------------------------------------- */

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

int _fltused = 0;

/* ---- DllMain ----------------------------------------------------- */

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

/* ---- Fallback definitions for older SDK versions ----------------- */

#ifndef PROCESS_POWER_THROTTLING_CURRENT_VERSION
#define PROCESS_POWER_THROTTLING_CURRENT_VERSION 1
#endif
#ifndef PROCESS_POWER_THROTTLING_EXECUTION_SPEED
#define PROCESS_POWER_THROTTLING_EXECUTION_SPEED 0x1
#endif
#ifndef PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION
#define PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION 0x2
#endif

/* ProcessPowerThrottling = 4 in PROCESS_INFORMATION_CLASS enum */
#define MY_ProcessPowerThrottling 4

typedef struct {
    ULONG Version;
    ULONG ControlMask;
    ULONG StateMask;
} MY_PROCESS_POWER_THROTTLING_STATE;

typedef BOOL (WINAPI *PFN_SetProcessInformation)(
    HANDLE hProcess,
    int    ProcessInformationClass,
    LPVOID ProcessInformation,
    DWORD  ProcessInformationSize
);

typedef BOOL (WINAPI *PFN_SetThreadInformation)(
    HANDLE hThread,
    int    ThreadInformationClass,
    LPVOID ThreadInformation,
    DWORD  ThreadInformationSize
);

/* ThreadPowerThrottling = 4 in THREAD_INFORMATION_CLASS enum */
#define MY_ThreadPowerThrottling 4

static PFN_SetProcessInformation pfnSetProcessInfo = NULL;
static BOOL pfnResolved = FALSE;

static PFN_SetThreadInformation pfnSetThreadInfo = NULL;
static BOOL pfnThreadResolved = FALSE;

static PFN_SetProcessInformation ResolveFn(void) {
    if (!pfnResolved) {
        HMODULE hK32 = GetModuleHandleW(L"kernel32.dll");
        if (hK32) {
            pfnSetProcessInfo = (PFN_SetProcessInformation)
                GetProcAddress(hK32, "SetProcessInformation");
        }
        pfnResolved = TRUE;
    }
    return pfnSetProcessInfo;
}

static PFN_SetThreadInformation ResolveThreadFn(void) {
    if (!pfnThreadResolved) {
        HMODULE hK32 = GetModuleHandleW(L"kernel32.dll");
        if (hK32) {
            pfnSetThreadInfo = (PFN_SetThreadInformation)
                GetProcAddress(hK32, "SetThreadInformation");
        }
        pfnThreadResolved = TRUE;
    }
    return pfnSetThreadInfo;
}

/* ---- Awake state ------------------------------------------------- */

static volatile BOOL g_awakeActive = FALSE;

/* ---- nativeIsSupported ------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeIsSupported(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return ResolveFn() != NULL ? JNI_TRUE : JNI_FALSE;
}

/* ---- nativeEnableEfficiencyMode ---------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeEnableEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    PFN_SetProcessInformation pfn = ResolveFn();
    if (!pfn) return (jint)127; /* ERROR_PROC_NOT_FOUND */

    /* 1. Enable EcoQoS */
    MY_PROCESS_POWER_THROTTLING_STATE state;
    memset(&state, 0, sizeof(state));
    state.Version     = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                      | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
    state.StateMask   = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                      | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;

    if (!pfn(GetCurrentProcess(), MY_ProcessPowerThrottling,
             &state, sizeof(state))) {
        return (jint)GetLastError();
    }

    /* 2. Set IDLE_PRIORITY_CLASS for green leaf icon */
    if (!SetPriorityClass(GetCurrentProcess(), IDLE_PRIORITY_CLASS)) {
        return (jint)GetLastError();
    }

    return 0;
}

/* ---- nativeDisableEfficiencyMode --------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeDisableEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    PFN_SetProcessInformation pfn = ResolveFn();
    if (!pfn) return (jint)127;

    /* 1. Disable EcoQoS (request HighQoS) */
    MY_PROCESS_POWER_THROTTLING_STATE state;
    memset(&state, 0, sizeof(state));
    state.Version     = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                      | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
    state.StateMask   = 0; /* ControlMask set but StateMask = 0 -> HighQoS */

    if (!pfn(GetCurrentProcess(), MY_ProcessPowerThrottling,
             &state, sizeof(state))) {
        return (jint)GetLastError();
    }

    /* 2. Restore NORMAL_PRIORITY_CLASS */
    if (!SetPriorityClass(GetCurrentProcess(), NORMAL_PRIORITY_CLASS)) {
        return (jint)GetLastError();
    }

    return 0;
}

/* ---- nativeEnableLightEfficiencyMode ----------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeEnableLightEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    PFN_SetProcessInformation pfn = ResolveFn();
    if (!pfn) return (jint)127; /* ERROR_PROC_NOT_FOUND */

    /* EcoQoS only — no IDLE_PRIORITY_CLASS */
    MY_PROCESS_POWER_THROTTLING_STATE state;
    memset(&state, 0, sizeof(state));
    state.Version     = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                      | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
    state.StateMask   = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                      | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;

    if (!pfn(GetCurrentProcess(), MY_ProcessPowerThrottling,
             &state, sizeof(state))) {
        return (jint)GetLastError();
    }

    return 0;
}

/* ---- nativeDisableLightEfficiencyMode ---------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeDisableLightEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    PFN_SetProcessInformation pfn = ResolveFn();
    if (!pfn) return (jint)127;

    /* Disable EcoQoS only — don't touch priority class */
    MY_PROCESS_POWER_THROTTLING_STATE state;
    memset(&state, 0, sizeof(state));
    state.Version     = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
                      | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
    state.StateMask   = 0; /* ControlMask set but StateMask = 0 -> HighQoS */

    if (!pfn(GetCurrentProcess(), MY_ProcessPowerThrottling,
             &state, sizeof(state))) {
        return (jint)GetLastError();
    }

    return 0;
}

/* ---- nativeEnableThreadEfficiencyMode --------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeEnableThreadEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* 1. Try SetThreadInformation with PowerThrottling (Windows 11+) */
    PFN_SetThreadInformation pfn = ResolveThreadFn();
    if (pfn) {
        MY_PROCESS_POWER_THROTTLING_STATE state;
        memset(&state, 0, sizeof(state));
        state.Version     = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
        state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
        state.StateMask   = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;

        /* Best-effort: ignore failure (e.g. older Windows with SetThreadInformation
           but no ThreadPowerThrottling support) */
        pfn(GetCurrentThread(), MY_ThreadPowerThrottling,
            &state, sizeof(state));
    }

    /* 2. Set THREAD_PRIORITY_IDLE — always available */
    if (!SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_IDLE)) {
        return (jint)GetLastError();
    }

    return 0;
}

/* ---- nativeDisableThreadEfficiencyMode -------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeDisableThreadEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* 1. Disable thread EcoQoS if available */
    PFN_SetThreadInformation pfn = ResolveThreadFn();
    if (pfn) {
        MY_PROCESS_POWER_THROTTLING_STATE state;
        memset(&state, 0, sizeof(state));
        state.Version     = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
        state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
        state.StateMask   = 0; /* HighQoS */

        pfn(GetCurrentThread(), MY_ThreadPowerThrottling,
            &state, sizeof(state));
    }

    /* 2. Restore THREAD_PRIORITY_NORMAL */
    if (!SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_NORMAL)) {
        return (jint)GetLastError();
    }

    return 0;
}

/* ---- nativeKeepAwake -------------------------------------------- */

#ifndef ES_CONTINUOUS
#define ES_CONTINUOUS        0x80000000
#endif
#ifndef ES_SYSTEM_REQUIRED
#define ES_SYSTEM_REQUIRED   0x00000001
#endif
#ifndef ES_DISPLAY_REQUIRED
#define ES_DISPLAY_REQUIRED  0x00000002
#endif

/* Must match AwakeMode.nativeCode in WindowsEnergyManager.kt */
#define AWAKE_SYSTEM_AND_DISPLAY 0
#define AWAKE_SYSTEM_ONLY        1

/*
 * SetThreadExecutionState is not additive: each call replaces the calling
 * thread's request, so switching modes needs no explicit release first.
 * ES_SYSTEM_REQUIRED alone keeps the machine running while leaving the
 * display sleep / screen saver timers untouched.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeKeepAwake(
    JNIEnv *env, jclass clazz, jint mode)
{
    (void)env; (void)clazz;

    EXECUTION_STATE flags = ES_CONTINUOUS | ES_SYSTEM_REQUIRED;
    if (mode != AWAKE_SYSTEM_ONLY) {
        flags |= ES_DISPLAY_REQUIRED;
    }

    DWORD prev = SetThreadExecutionState(flags);
    if (prev == 0) {
        return (jint)GetLastError();
    }
    g_awakeActive = TRUE;
    return 0;
}

/* ---- nativeReleaseAwake ----------------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeReleaseAwake(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    DWORD prev = SetThreadExecutionState(ES_CONTINUOUS);
    if (prev == 0) {
        return (jint)GetLastError();
    }
    g_awakeActive = FALSE;
    return 0;
}

/* ---- nativeQueryAwakeFlags -------------------------------------- */

/*
 * Reads back the execution state Windows currently holds for the calling
 * thread. There is no getter in the Win32 API, so the state is replaced by a
 * bare ES_CONTINUOUS — which returns the previous flags — and immediately
 * restored. Used to verify the flags the kernel actually recorded.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeQueryAwakeFlags(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    DWORD prev = SetThreadExecutionState(ES_CONTINUOUS);
    if (prev == 0) {
        return 0;
    }
    SetThreadExecutionState(prev);
    return (jint)prev;
}

/* ---- nativeIsAwakeActive ---------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_energymanager_windows_NativeWindowsEnergyBridge_nativeIsAwakeActive(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return g_awakeActive ? JNI_TRUE : JNI_FALSE;
}
