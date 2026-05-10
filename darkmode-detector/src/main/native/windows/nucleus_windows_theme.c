/**
 * JNI bridge for Windows dark-mode detection.
 *
 * Provides native implementations for:
 *   - Reading the AppsUseLightTheme registry value
 *   - Monitoring registry changes via a native background thread
 *
 * The monitoring thread uses RegNotifyChangeKeyValue in async mode
 * with WaitForMultipleObjects so it can be cleanly stopped via a
 * signalled event.
 *
 * Linked libraries: advapi32.lib
 */

#include <jni.h>
#include <windows.h>

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

/* Cached JavaVM pointer, set in JNI_OnLoad */
static JavaVM *g_jvm = NULL;

/* Monitoring thread handle and stop event */
static HANDLE g_thread = NULL;
static HANDLE g_stopEvent = NULL;
static volatile LONG g_running = 0;

static const char *REG_PATH =
    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
static const char *REG_VALUE = "AppsUseLightTheme";

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/* ------------------------------------------------------------------ */
/*  Helper: read current dark mode state from registry                 */
/* ------------------------------------------------------------------ */
static jboolean is_dark(void) {
    DWORD value = 1; /* default: light */
    DWORD size = sizeof(value);
    LONG err = RegGetValueA(
        HKEY_CURRENT_USER, REG_PATH, REG_VALUE,
        RRF_RT_REG_DWORD, NULL, &value, &size);

    if (err != ERROR_SUCCESS) {
        return JNI_FALSE; /* key absent -> light */
    }
    return (value == 0) ? JNI_TRUE : JNI_FALSE;
}

/* ------------------------------------------------------------------ */
/*  nativeIsDark()                                                     */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_windows_NativeWindowsBridge_nativeIsDark(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return is_dark();
}

/* ------------------------------------------------------------------ */
/*  Notify the Kotlin bridge about a theme change                      */
/* ------------------------------------------------------------------ */
static void notify_java(jboolean isDark) {
    if (g_jvm == NULL) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    int didAttach = 0;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) {
            return;
        }
        didAttach = 1;
    } else if (attached != JNI_OK) {
        return;
    }

    jclass bridgeClass = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/darkmodedetector/windows/NativeWindowsBridge");
    if (bridgeClass != NULL) {
        jmethodID method = (*env)->GetStaticMethodID(env,
            bridgeClass, "onThemeChanged", "(Z)V");
        if (method != NULL) {
            (*env)->CallStaticVoidMethod(env, bridgeClass, method, isDark);
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
/*  Monitoring thread                                                  */
/* ------------------------------------------------------------------ */
static DWORD WINAPI monitor_thread(LPVOID arg) {
    (void)arg;

    HKEY hKey = NULL;
    LONG err = RegOpenKeyExA(
        HKEY_CURRENT_USER, REG_PATH, 0, KEY_READ, &hKey);
    if (err != ERROR_SUCCESS) {
        return 1;
    }

    HANDLE hEvent = CreateEventA(NULL, FALSE, FALSE, NULL);
    if (hEvent == NULL) {
        RegCloseKey(hKey);
        return 1;
    }

    jboolean lastValue = is_dark();
    HANDLE waitHandles[2] = { hEvent, g_stopEvent };

    while (g_running) {
        /* Register for async notification */
        err = RegNotifyChangeKeyValue(
            hKey, FALSE, REG_NOTIFY_CHANGE_LAST_SET, hEvent, TRUE);
        if (err != ERROR_SUCCESS) {
            break;
        }

        /* Wait for either registry change or stop signal */
        DWORD result = WaitForMultipleObjects(2, waitHandles, FALSE, INFINITE);
        if (result == WAIT_OBJECT_0) {
            /* Registry changed */
            jboolean currentValue = is_dark();
            if (currentValue != lastValue) {
                lastValue = currentValue;
                notify_java(currentValue);
            }
        } else {
            /* Stop event signalled or error */
            break;
        }
    }

    CloseHandle(hEvent);
    RegCloseKey(hKey);
    return 0;
}

/* ------------------------------------------------------------------ */
/*  nativeStartObserving()                                             */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_windows_NativeWindowsBridge_nativeStartObserving(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (g_running) {
        return; /* already observing */
    }

    g_stopEvent = CreateEventA(NULL, TRUE, FALSE, NULL);
    if (g_stopEvent == NULL) {
        return;
    }

    g_running = 1;

    g_thread = CreateThread(NULL, 0, monitor_thread, NULL, 0, NULL);
    if (g_thread == NULL) {
        CloseHandle(g_stopEvent);
        g_stopEvent = NULL;
        g_running = 0;
    }
}

/* ------------------------------------------------------------------ */
/*  nativeStopObserving()                                              */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_windows_NativeWindowsBridge_nativeStopObserving(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (!g_running) {
        return; /* not running */
    }

    g_running = 0;

    /* Signal the stop event to unblock WaitForMultipleObjects */
    if (g_stopEvent != NULL) {
        SetEvent(g_stopEvent);
    }

    /* Wait for the thread to finish */
    if (g_thread != NULL) {
        WaitForSingleObject(g_thread, INFINITE);
        CloseHandle(g_thread);
        g_thread = NULL;
    }

    if (g_stopEvent != NULL) {
        CloseHandle(g_stopEvent);
        g_stopEvent = NULL;
    }
}
