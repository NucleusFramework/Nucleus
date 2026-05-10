/**
 * JNI bridge for Windows system color detection.
 *
 * Uses RegNotifyChangeKeyValue to watch the DWM accent color registry key
 * and SystemParametersInfoW for high contrast detection.
 * Notifies Kotlin via JNI callbacks (event-driven, no polling).
 *
 * Linked libraries: kernel32.lib user32.lib advapi32.lib
 */

#include <jni.h>
#include <windows.h>

/* ------------------------------------------------------------------ */
/*  /NODEFAULTLIB support                                              */
/* ------------------------------------------------------------------ */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

/* ------------------------------------------------------------------ */
/*  Constants                                                          */
/* ------------------------------------------------------------------ */
#define DWM_KEY L"SOFTWARE\\Microsoft\\Windows\\DWM"
#define A11Y_KEY L"SOFTWARE\\Microsoft\\Accessibility"
#define BYTE_MASK 0xFF

/* ------------------------------------------------------------------ */
/*  Global state                                                       */
/* ------------------------------------------------------------------ */
static JavaVM *g_jvm = NULL;
static HANDLE g_watchThread = NULL;
static volatile LONG g_stopFlag = 0;
static HANDLE g_stopEvent = NULL;

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)lpvReserved;
    if (fdwReason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinstDLL);
    }
    return TRUE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/* ------------------------------------------------------------------ */
/*  Registry helpers                                                   */
/* ------------------------------------------------------------------ */

/**
 * Reads the AccentColor DWORD from HKCU\SOFTWARE\Microsoft\Windows\DWM.
 * Returns TRUE on success, fills r/g/b (0-255).
 */
static BOOL readAccentColor(int *r, int *g, int *b) {
    HKEY hKey;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, DWM_KEY, 0, KEY_READ, &hKey) != ERROR_SUCCESS)
        return FALSE;

    DWORD value = 0;
    DWORD size = sizeof(value);
    DWORD type = 0;
    LONG result = RegQueryValueExW(hKey, L"AccentColor", NULL, &type, (LPBYTE)&value, &size);
    RegCloseKey(hKey);

    if (result != ERROR_SUCCESS || type != REG_DWORD)
        return FALSE;

    /* AccentColor is stored as AABBGGRR */
    *r = (int)(value & BYTE_MASK);
    *g = (int)((value >> 8) & BYTE_MASK);
    *b = (int)((value >> 16) & BYTE_MASK);
    return TRUE;
}

/**
 * Checks high contrast mode using SystemParametersInfoW.
 */
static BOOL isHighContrast(void) {
    HIGHCONTRASTW hc;
    hc.cbSize = sizeof(hc);
    if (SystemParametersInfoW(SPI_GETHIGHCONTRAST, sizeof(hc), &hc, 0)) {
        return (hc.dwFlags & HCF_HIGHCONTRASTON) != 0;
    }
    return FALSE;
}

/* ------------------------------------------------------------------ */
/*  JNI callback helpers                                               */
/* ------------------------------------------------------------------ */

static void notifyAccentColorChanged(int r, int g, int b) {
    if (g_jvm == NULL) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    BOOL didAttach = FALSE;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = TRUE;
    } else if (attached != JNI_OK) {
        return;
    }

    jclass bridgeClass = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/systemcolor/windows/NativeWindowsSystemColorBridge");
    if (bridgeClass != NULL) {
        jmethodID method = (*env)->GetStaticMethodID(env,
            bridgeClass, "onAccentColorChanged", "(III)V");
        if (method != NULL) {
            (*env)->CallStaticVoidMethod(env, bridgeClass, method,
                (jint)r, (jint)g, (jint)b);
        }
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void notifyHighContrastChanged(BOOL isHigh) {
    if (g_jvm == NULL) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    BOOL didAttach = FALSE;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = TRUE;
    } else if (attached != JNI_OK) {
        return;
    }

    jclass bridgeClass = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/systemcolor/windows/NativeWindowsSystemColorBridge");
    if (bridgeClass != NULL) {
        jmethodID method = (*env)->GetStaticMethodID(env,
            bridgeClass, "onHighContrastChanged", "(Z)V");
        if (method != NULL) {
            (*env)->CallStaticVoidMethod(env, bridgeClass, method,
                isHigh ? JNI_TRUE : JNI_FALSE);
        }
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/* ------------------------------------------------------------------ */
/*  Registry watcher thread                                            */
/* ------------------------------------------------------------------ */

static DWORD WINAPI watchThreadProc(LPVOID param) {
    (void)param;

    int prevR = 0, prevG = 0, prevB = 0;
    BOOL prevHasColor = readAccentColor(&prevR, &prevG, &prevB);
    BOOL prevHighContrast = isHighContrast();

    /* Open registry keys for notification */
    HKEY hDwmKey = NULL;
    HKEY hA11yKey = NULL;
    RegOpenKeyExW(HKEY_CURRENT_USER, DWM_KEY, 0, KEY_NOTIFY, &hDwmKey);
    RegOpenKeyExW(HKEY_CURRENT_USER, A11Y_KEY, 0, KEY_NOTIFY, &hA11yKey);

    HANDLE dwmEvent = CreateEventW(NULL, FALSE, FALSE, NULL);
    HANDLE a11yEvent = CreateEventW(NULL, FALSE, FALSE, NULL);

    while (!g_stopFlag) {
        HANDLE waitHandles[3];
        DWORD handleCount = 0;

        /* Always include the stop event */
        waitHandles[handleCount++] = g_stopEvent;

        /* Register for DWM key changes */
        if (hDwmKey != NULL && dwmEvent != NULL) {
            RegNotifyChangeKeyValue(hDwmKey, FALSE, REG_NOTIFY_CHANGE_LAST_SET,
                dwmEvent, TRUE);
            waitHandles[handleCount++] = dwmEvent;
        }

        /* Register for accessibility key changes */
        if (hA11yKey != NULL && a11yEvent != NULL) {
            RegNotifyChangeKeyValue(hA11yKey, FALSE, REG_NOTIFY_CHANGE_LAST_SET,
                a11yEvent, TRUE);
            waitHandles[handleCount++] = a11yEvent;
        }

        DWORD waitResult = WaitForMultipleObjects(handleCount, waitHandles, FALSE, INFINITE);

        /* Stop event signaled */
        if (waitResult == WAIT_OBJECT_0) break;

        /* Check for accent color change */
        int newR = 0, newG = 0, newB = 0;
        BOOL hasColor = readAccentColor(&newR, &newG, &newB);
        if (hasColor && (!prevHasColor || newR != prevR || newG != prevG || newB != prevB)) {
            prevR = newR; prevG = newG; prevB = newB;
            prevHasColor = TRUE;
            notifyAccentColorChanged(newR, newG, newB);
        }

        /* Check for high contrast change */
        BOOL newHighContrast = isHighContrast();
        if (newHighContrast != prevHighContrast) {
            prevHighContrast = newHighContrast;
            notifyHighContrastChanged(newHighContrast);
        }
    }

    if (dwmEvent) CloseHandle(dwmEvent);
    if (a11yEvent) CloseHandle(a11yEvent);
    if (hDwmKey) RegCloseKey(hDwmKey);
    if (hA11yKey) RegCloseKey(hA11yKey);

    return 0;
}

/* ------------------------------------------------------------------ */
/*  JNI exports                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_windows_NativeWindowsSystemColorBridge_nativeGetAccentColor(
    JNIEnv *env, jclass clazz, jintArray out) {
    (void)clazz;
    int r = 0, g = 0, b = 0;
    if (!readAccentColor(&r, &g, &b)) return JNI_FALSE;
    jint rgb[3];
    rgb[0] = r; rgb[1] = g; rgb[2] = b;
    (*env)->SetIntArrayRegion(env, out, 0, 3, rgb);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_windows_NativeWindowsSystemColorBridge_nativeIsHighContrast(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return isHighContrast() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_windows_NativeWindowsSystemColorBridge_nativeIsAccentColorSupported(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    int r, g, b;
    return readAccentColor(&r, &g, &b) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_windows_NativeWindowsSystemColorBridge_nativeStartObserving(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_watchThread != NULL) return;

    g_stopFlag = 0;
    g_stopEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    g_watchThread = CreateThread(NULL, 0, watchThreadProc, NULL, 0, NULL);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_windows_NativeWindowsSystemColorBridge_nativeStopObserving(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_watchThread == NULL) return;

    g_stopFlag = 1;
    if (g_stopEvent) SetEvent(g_stopEvent);
    WaitForSingleObject(g_watchThread, 5000);
    CloseHandle(g_watchThread);
    g_watchThread = NULL;
    if (g_stopEvent) { CloseHandle(g_stopEvent); g_stopEvent = NULL; }
}
