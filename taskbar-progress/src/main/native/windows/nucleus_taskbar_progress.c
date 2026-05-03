/**
 * JNI bridge for Windows taskbar progress (ITaskbarList3).
 *
 * Provides native implementations for:
 *   - Setting taskbar progress value (SetProgressValue)
 *   - Setting taskbar progress state (SetProgressState)
 *   - Requesting user attention via FlashWindowEx
 *
 * COM ITaskbarList3 interface is resolved lazily on first call.
 * HWND is extracted from java.awt.Window via JNI (bypasses JPMS).
 *
 * Linked libraries: kernel32.lib ole32.lib user32.lib
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

/* ---- COM GUIDs --------------------------------------------------- */

/* CLSID_TaskbarList = {56FDF344-FD6D-11d0-958A-006097C9A090} */
static const GUID CLSID_TaskbarList = {
    0x56FDF344, 0xFD6D, 0x11d0,
    { 0x95, 0x8A, 0x00, 0x60, 0x97, 0xC9, 0xA0, 0x90 }
};

/* IID_ITaskbarList3 = {EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF} */
static const GUID IID_ITaskbarList3 = {
    0xEA1AFB91, 0x9E28, 0x4B86,
    { 0x90, 0xE9, 0x9E, 0x9F, 0x8A, 0x5E, 0xEF, 0xAF }
};

/* ---- Minimal COM declarations ------------------------------------ */

typedef HRESULT (WINAPI *PFN_CoInitializeEx)(LPVOID, DWORD);
typedef HRESULT (WINAPI *PFN_CoCreateInstance)(const GUID*, LPVOID, DWORD, const GUID*, LPVOID*);

#ifndef COINIT_APARTMENTTHREADED
#define COINIT_APARTMENTTHREADED 0x2
#endif

#ifndef CLSCTX_INPROC_SERVER
#define CLSCTX_INPROC_SERVER 0x1
#endif

/* ITaskbarList3 vtable indices (IUnknown=0-2, ITaskbarList=3-6, ITaskbarList2=7, ITaskbarList3=8+) */
#define VTABLE_SetProgressValue  9
#define VTABLE_SetProgressState 10

/* ---- FlashWindowEx constants ------------------------------------- */

#ifndef FLASHW_STOP
#define FLASHW_STOP         0x00000000
#endif
#ifndef FLASHW_CAPTION
#define FLASHW_CAPTION      0x00000001
#endif
#ifndef FLASHW_TRAY
#define FLASHW_TRAY         0x00000002
#endif
#ifndef FLASHW_ALL
#define FLASHW_ALL          (FLASHW_CAPTION | FLASHW_TRAY)
#endif
#ifndef FLASHW_TIMERNOFG
#define FLASHW_TIMERNOFG    0x0000000C
#endif

typedef BOOL (WINAPI *PFN_FlashWindowEx)(void *pfwi);

typedef struct {
    UINT  cbSize;
    HWND  hwnd;
    DWORD dwFlags;
    UINT  uCount;
    DWORD dwTimeout;
} MY_FLASHWINFO;

/* ---- Global state ------------------------------------------------ */

static void *g_taskbarList = NULL;  /* ITaskbarList3 pointer */
static BOOL g_initialized = FALSE;

/* ---- COM initialization ------------------------------------------ */

static BOOL EnsureTaskbarList(void) {
    HMODULE hOle32;
    PFN_CoInitializeEx pfnCoInit;
    PFN_CoCreateInstance pfnCoCreate;
    void *pUnk = NULL;
    void **vTable;
    HRESULT hr;
    /* ITaskbarList::HrInit is at vtable index 3 */
    HRESULT (WINAPI *pfnHrInit)(void *pThis);

    if (g_initialized) return g_taskbarList != NULL;
    g_initialized = TRUE;

    hOle32 = LoadLibraryW(L"ole32.dll");
    if (!hOle32) return FALSE;

    pfnCoInit = (PFN_CoInitializeEx)GetProcAddress(hOle32, "CoInitializeEx");
    pfnCoCreate = (PFN_CoCreateInstance)GetProcAddress(hOle32, "CoCreateInstance");
    if (!pfnCoInit || !pfnCoCreate) return FALSE;

    /* COINIT_APARTMENTTHREADED — safe for UI thread */
    pfnCoInit(NULL, COINIT_APARTMENTTHREADED);

    hr = pfnCoCreate(&CLSID_TaskbarList, NULL, CLSCTX_INPROC_SERVER,
                     &IID_ITaskbarList3, &pUnk);
    if (hr != S_OK || !pUnk) return FALSE;

    /* Call ITaskbarList::HrInit (vtable index 3) */
    vTable = *(void ***)pUnk;
    pfnHrInit = (HRESULT (WINAPI *)(void *))vTable[3];
    hr = pfnHrInit(pUnk);
    if (hr != S_OK) {
        /* Release on failure */
        ((ULONG (WINAPI *)(void *))vTable[2])(pUnk);
        return FALSE;
    }

    g_taskbarList = pUnk;
    return TRUE;
}

/* ---- HWND extraction from java.awt.Window ------------------------ */

static HWND GetHwndFromAwtWindow(JNIEnv *env, jobject awtWindow) {
    jclass awtAccessorClass;
    jmethodID getCompAccessor;
    jobject compAccessor;
    jclass compAccessorClass;
    jmethodID getPeer;
    jobject peer;
    jclass wCompPeerClass;
    jmethodID getHWnd;
    jlong hwnd;

    if (!awtWindow) return NULL;

    /* AWTAccessor.getComponentAccessor() */
    awtAccessorClass = (*env)->FindClass(env, "sun/awt/AWTAccessor");
    if (!awtAccessorClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    getCompAccessor = (*env)->GetStaticMethodID(env, awtAccessorClass,
        "getComponentAccessor", "()Lsun/awt/AWTAccessor$ComponentAccessor;");
    if (!getCompAccessor || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, awtAccessorClass);
        return NULL;
    }

    compAccessor = (*env)->CallStaticObjectMethod(env, awtAccessorClass, getCompAccessor);
    (*env)->DeleteLocalRef(env, awtAccessorClass);
    if (!compAccessor || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    /* componentAccessor.getPeer(window) */
    compAccessorClass = (*env)->FindClass(env, "sun/awt/AWTAccessor$ComponentAccessor");
    if (!compAccessorClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, compAccessor);
        return NULL;
    }

    getPeer = (*env)->GetMethodID(env, compAccessorClass,
        "getPeer", "(Ljava/awt/Component;)Ljava/awt/peer/ComponentPeer;");
    (*env)->DeleteLocalRef(env, compAccessorClass);
    if (!getPeer || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, compAccessor);
        return NULL;
    }

    peer = (*env)->CallObjectMethod(env, compAccessor, getPeer, awtWindow);
    (*env)->DeleteLocalRef(env, compAccessor);
    if (!peer || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    /* peer.getHWnd() */
    wCompPeerClass = (*env)->FindClass(env, "sun/awt/windows/WComponentPeer");
    if (!wCompPeerClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return NULL;
    }

    getHWnd = (*env)->GetMethodID(env, wCompPeerClass, "getHWnd", "()J");
    (*env)->DeleteLocalRef(env, wCompPeerClass);
    if (!getHWnd || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return NULL;
    }

    hwnd = (*env)->CallLongMethod(env, peer, getHWnd);
    (*env)->DeleteLocalRef(env, peer);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }

    return (HWND)(intptr_t)hwnd;
}

/* ---- JNI exports ------------------------------------------------- */

/* ---- Shared helpers (HWND-driven) -------------------------------- */

static jint SetProgressByHwnd(HWND hwnd, jlong completed, jlong total) {
    void **vTable;
    HRESULT (WINAPI *pfnSetProgressValue)(void*, HWND, ULONGLONG, ULONGLONG);
    HRESULT hr;

    if (!EnsureTaskbarList()) return -1;
    if (!hwnd) return -2;

    vTable = *(void ***)g_taskbarList;
    pfnSetProgressValue = (HRESULT (WINAPI *)(void*, HWND, ULONGLONG, ULONGLONG))
        vTable[VTABLE_SetProgressValue];

    hr = pfnSetProgressValue(g_taskbarList, hwnd, (ULONGLONG)completed, (ULONGLONG)total);
    return (jint)hr;
}

static jint SetProgressStateByHwnd(HWND hwnd, jint flags) {
    void **vTable;
    HRESULT (WINAPI *pfnSetProgressState)(void*, HWND, int);
    HRESULT hr;

    if (!EnsureTaskbarList()) return -1;
    if (!hwnd) return -2;

    vTable = *(void ***)g_taskbarList;
    pfnSetProgressState = (HRESULT (WINAPI *)(void*, HWND, int))
        vTable[VTABLE_SetProgressState];

    hr = pfnSetProgressState(g_taskbarList, hwnd, (int)flags);
    return (jint)hr;
}

/* ---- AWT-window-driven entrypoints ------------------------------- */

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_windows_NativeWindowsTaskbarBridge_nativeSetProgress(
    JNIEnv *env, jclass clazz, jobject awtWindow, jlong completed, jlong total)
{
    HWND hwnd;
    (void)clazz;
    hwnd = GetHwndFromAwtWindow(env, awtWindow);
    return SetProgressByHwnd(hwnd, completed, total);
}

/* ---- HWND-driven entrypoints (used by non-AWT backends, e.g. Tao) - */

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_windows_NativeWindowsTaskbarBridge_nativeSetProgressByHwnd(
    JNIEnv *env, jclass clazz, jlong hwnd, jlong completed, jlong total)
{
    (void)env; (void)clazz;
    return SetProgressByHwnd((HWND)(intptr_t)hwnd, completed, total);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_windows_NativeWindowsTaskbarBridge_nativeSetProgressStateByHwnd(
    JNIEnv *env, jclass clazz, jlong hwnd, jint flags)
{
    (void)env; (void)clazz;
    return SetProgressStateByHwnd((HWND)(intptr_t)hwnd, flags);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_windows_NativeWindowsTaskbarBridge_nativeSetProgressState(
    JNIEnv *env, jclass clazz, jobject awtWindow, jint flags)
{
    HWND hwnd;
    (void)clazz;
    hwnd = GetHwndFromAwtWindow(env, awtWindow);
    return SetProgressStateByHwnd(hwnd, flags);
}

/* ---- nativeRequestAttention -------------------------------------- */

static jint RequestAttentionByHwnd(HWND hwnd, jint type) {
    static PFN_FlashWindowEx pfnFlash = NULL;
    static BOOL pfnFlashResolved = FALSE;
    MY_FLASHWINFO fwi;
    DWORD flags;
    UINT count;

    if (!pfnFlashResolved) {
        HMODULE hUser32 = GetModuleHandleW(L"user32.dll");
        if (hUser32) {
            pfnFlash = (PFN_FlashWindowEx)GetProcAddress(hUser32, "FlashWindowEx");
        }
        pfnFlashResolved = TRUE;
    }
    if (!pfnFlash) return -1;
    if (!hwnd) return -2;

    switch (type) {
        case 1: /* Informational: flash taskbar button 4 times */
            flags = FLASHW_TRAY;
            count = 4;
            break;
        case 2: /* Critical: flash everything until app gets focus */
            flags = FLASHW_ALL | FLASHW_TIMERNOFG;
            count = (UINT)-1; /* infinite until foreground */
            break;
        default: /* Stop */
            flags = FLASHW_STOP;
            count = 0;
            break;
    }

    memset(&fwi, 0, sizeof(fwi));
    fwi.cbSize    = sizeof(fwi);
    fwi.hwnd      = hwnd;
    fwi.dwFlags   = flags;
    fwi.uCount    = count;
    fwi.dwTimeout = 0;

    pfnFlash(&fwi);
    return 0;
}

/*
 * type: 0 = stop, 1 = informational (tray only, 4 flashes), 2 = critical (all, until foreground)
 */
JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_windows_NativeWindowsTaskbarBridge_nativeRequestAttention(
    JNIEnv *env, jclass clazz, jobject awtWindow, jint type)
{
    HWND hwnd;
    (void)clazz;
    hwnd = GetHwndFromAwtWindow(env, awtWindow);
    return RequestAttentionByHwnd(hwnd, type);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_windows_NativeWindowsTaskbarBridge_nativeRequestAttentionByHwnd(
    JNIEnv *env, jclass clazz, jlong hwnd, jint type)
{
    (void)env; (void)clazz;
    return RequestAttentionByHwnd((HWND)(intptr_t)hwnd, type);
}
