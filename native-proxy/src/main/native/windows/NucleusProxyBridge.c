#include <jni.h>
#include <windows.h>
#include <winhttp.h>

/**
 * Windows JNI bridge for the system proxy configuration.
 *
 * Uses the same WinHTTP surface as Chromium (`net::ProxyConfigServiceWin` and
 * `net::ProxyResolverWinHttp`):
 *
 *  - `WinHttpGetIEProxyConfigForCurrentUser` reads the effective per-user
 *    configuration: WPAD flag, PAC script URL, proxy string and bypass list.
 *    WinHTTP already merges the machine-wide and Group Policy values.
 *  - `WinHttpGetProxyForUrl` fetches and evaluates the PAC script, either from
 *    the configured URL or from a WPAD lookup (DHCP + DNS A). As in Chromium,
 *    the call is first made without auto-logon and only retried with it after
 *    ERROR_WINHTTP_LOGIN_FAILURE, because auto-logon disables the script cache.
 *  - `RegNotifyChangeKeyValue` on the Internet Settings keys reports
 *    configuration changes without polling.
 *
 * Built with /NODEFAULTLIB - no CRT dependency, Win32 heap APIs only.
 */

/* CRT-free: provide memcpy/memset/memcmp so the linker resolves them. */
#pragma function(memcpy, memset, memcmp)

void *memcpy(void *dst, const void *src, size_t n) {
    BYTE *d = (BYTE *)dst;
    const BYTE *s = (const BYTE *)src;
    while (n--) *d++ = *s++;
    return dst;
}

void *memset(void *dst, int val, size_t n) {
    BYTE *d = (BYTE *)dst;
    while (n--) *d++ = (BYTE)val;
    return dst;
}

int memcmp(const void *a, const void *b, size_t n) {
    const BYTE *pa = (const BYTE *)a;
    const BYTE *pb = (const BYTE *)b;
    while (n--) {
        if (*pa != *pb) return (int)*pa - (int)*pb;
        pa++; pb++;
    }
    return 0;
}

/* ── Config array layout, mirrors WindowsProxyBridge.INDEX_* ── */

#define CONFIG_INDEX_PROXY       0
#define CONFIG_INDEX_BYPASS      1
#define CONFIG_INDEX_PAC_URL     2
#define CONFIG_INDEX_AUTO_DETECT 3
#define CONFIG_LENGTH            4

/* WinHTTP timeouts (ms) - a PAC fetch must never stall a connection for long. */
#define RESOLVE_TIMEOUT_MS  10000
#define CONNECT_TIMEOUT_MS  10000
#define SEND_TIMEOUT_MS     10000
#define RECEIVE_TIMEOUT_MS  10000

/* ── Heap helpers ── */

static void *heap_alloc(SIZE_T size) {
    return HeapAlloc(GetProcessHeap(), 0, size);
}

static void heap_free(void *ptr) {
    if (ptr) HeapFree(GetProcessHeap(), 0, ptr);
}

/* ── String helpers ── */

static SIZE_T wide_length(const WCHAR *text) {
    SIZE_T length = 0;
    if (text == NULL) return 0;
    while (text[length] != L'\0') length++;
    return length;
}

static jstring wide_to_java(JNIEnv *env, const WCHAR *text) {
    if (text == NULL) return NULL;
    return (*env)->NewString(env, (const jchar *)text, (jsize)wide_length(text));
}

/** Copies a Java string into a heap-allocated, NUL-terminated UTF-16 buffer. */
static WCHAR *java_to_wide(JNIEnv *env, jstring text) {
    jsize length;
    WCHAR *buffer;

    if (text == NULL) return NULL;
    length = (*env)->GetStringLength(env, text);
    buffer = (WCHAR *)heap_alloc(((SIZE_T)length + 1) * sizeof(WCHAR));
    if (buffer == NULL) return NULL;
    if (length > 0) {
        (*env)->GetStringRegion(env, text, 0, length, (jchar *)buffer);
    }
    buffer[length] = L'\0';
    return buffer;
}

/* ── Cached WinHTTP session ── */

static PVOID volatile g_session = NULL;

/**
 * Returns the process-wide WinHTTP session used for PAC resolution, creating it
 * on first use. WINHTTP_ACCESS_TYPE_NO_PROXY is required: the session must not
 * itself go through a proxy to fetch the script.
 */
static HINTERNET proxy_session(void) {
    HINTERNET created;
    PVOID previous;

    PVOID existing = InterlockedCompareExchangePointer(&g_session, NULL, NULL);
    if (existing != NULL) return (HINTERNET)existing;

    created = WinHttpOpen(
        L"Nucleus", WINHTTP_ACCESS_TYPE_NO_PROXY,
        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (created == NULL) return NULL;

    WinHttpSetTimeouts(
        created, RESOLVE_TIMEOUT_MS, CONNECT_TIMEOUT_MS,
        SEND_TIMEOUT_MS, RECEIVE_TIMEOUT_MS);

    previous = InterlockedCompareExchangePointer(&g_session, (PVOID)created, NULL);
    if (previous != NULL) {
        WinHttpCloseHandle(created);
        return (HINTERNET)previous;
    }
    return created;
}

/* ── Watched registry keys (same set as Chromium) ── */

typedef struct {
    HKEY    root;
    LPCWSTR path;
} WatchKey;

#define INTERNET_SETTINGS       L"Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
#define INTERNET_SETTINGS_POLICY L"Software\\Policies\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
#define INTERNET_CONNECTIONS    L"Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Connections"

static const WatchKey WATCH_KEYS[] = {
    { HKEY_CURRENT_USER,  INTERNET_SETTINGS },
    { HKEY_LOCAL_MACHINE, INTERNET_SETTINGS },
    { HKEY_CURRENT_USER,  INTERNET_SETTINGS_POLICY },
    { HKEY_LOCAL_MACHINE, INTERNET_SETTINGS_POLICY },
    { HKEY_CURRENT_USER,  INTERNET_CONNECTIONS },
    { HKEY_LOCAL_MACHINE, INTERNET_CONNECTIONS }
};

#define WATCH_KEY_COUNT (sizeof(WATCH_KEYS) / sizeof(WATCH_KEYS[0]))
#define WATCH_FILTER    (REG_NOTIFY_CHANGE_NAME | REG_NOTIFY_CHANGE_LAST_SET)

/* ── Wake event, lets the JVM release a parked watcher thread ── */

static PVOID volatile g_wake_event = NULL;

static HANDLE wake_event(void) {
    HANDLE created;
    PVOID previous;

    PVOID existing = InterlockedCompareExchangePointer(&g_wake_event, NULL, NULL);
    if (existing != NULL) return (HANDLE)existing;

    /* Manual reset: the waiter clears it once it has observed the signal. */
    created = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (created == NULL) return NULL;

    previous = InterlockedCompareExchangePointer(&g_wake_event, (PVOID)created, NULL);
    if (previous != NULL) {
        CloseHandle(created);
        return (HANDLE)previous;
    }
    return created;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpReserved) {
    (void)hinstDLL;
    (void)fdwReason;
    (void)lpReserved;
    return TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nativeproxy_windows_WindowsProxyBridge_nativeGetProxyConfig(
    JNIEnv *env, jclass clazz) {

    WINHTTP_CURRENT_USER_IE_PROXY_CONFIG config;
    jclass stringClass;
    jobjectArray result;

    (void)clazz;

    memset(&config, 0, sizeof(config));
    if (!WinHttpGetIEProxyConfigForCurrentUser(&config)) {
        /* Fails when there is no interactive user (services, session 0). */
        return NULL;
    }

    stringClass = (*env)->FindClass(env, "java/lang/String");
    result = stringClass != NULL
        ? (*env)->NewObjectArray(env, CONFIG_LENGTH, stringClass, NULL)
        : NULL;

    if (result != NULL) {
        jstring proxy = wide_to_java(env, config.lpszProxy);
        jstring bypass = wide_to_java(env, config.lpszProxyBypass);
        jstring pacUrl = wide_to_java(env, config.lpszAutoConfigUrl);
        jstring autoDetect = wide_to_java(env, config.fAutoDetect ? L"1" : L"0");

        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_PROXY, proxy);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_BYPASS, bypass);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_PAC_URL, pacUrl);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_AUTO_DETECT, autoDetect);

        if (proxy != NULL) (*env)->DeleteLocalRef(env, proxy);
        if (bypass != NULL) (*env)->DeleteLocalRef(env, bypass);
        if (pacUrl != NULL) (*env)->DeleteLocalRef(env, pacUrl);
        if (autoDetect != NULL) (*env)->DeleteLocalRef(env, autoDetect);
    }

    /* The struct members are allocated by WinHTTP and owned by the caller. */
    if (config.lpszProxy != NULL) GlobalFree(config.lpszProxy);
    if (config.lpszProxyBypass != NULL) GlobalFree(config.lpszProxyBypass);
    if (config.lpszAutoConfigUrl != NULL) GlobalFree(config.lpszAutoConfigUrl);

    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nativeproxy_windows_WindowsProxyBridge_nativeResolveProxyForUrl(
    JNIEnv *env, jclass clazz, jstring url, jstring pacUrl) {

    HINTERNET session;
    WCHAR *urlText;
    WCHAR *pacText;
    WINHTTP_AUTOPROXY_OPTIONS options;
    WINHTTP_PROXY_INFO info;
    BOOL resolved;
    jstring result;

    (void)clazz;

    session = proxy_session();
    if (session == NULL) return NULL;

    urlText = java_to_wide(env, url);
    if (urlText == NULL) return NULL;
    pacText = java_to_wide(env, pacUrl);

    memset(&options, 0, sizeof(options));
    if (pacText != NULL) {
        options.dwFlags = WINHTTP_AUTOPROXY_CONFIG_URL;
        options.lpszAutoConfigUrl = pacText;
    } else {
        options.dwFlags = WINHTTP_AUTOPROXY_AUTO_DETECT;
        options.dwAutoDetectFlags =
            WINHTTP_AUTO_DETECT_TYPE_DHCP | WINHTTP_AUTO_DETECT_TYPE_DNS_A;
    }
    /* Auto-logon bypasses the script cache, so only enable it when required. */
    options.fAutoLogonIfChallenged = FALSE;

    memset(&info, 0, sizeof(info));
    resolved = WinHttpGetProxyForUrl(session, urlText, &options, &info);
    if (!resolved && GetLastError() == ERROR_WINHTTP_LOGIN_FAILURE) {
        options.fAutoLogonIfChallenged = TRUE;
        memset(&info, 0, sizeof(info));
        resolved = WinHttpGetProxyForUrl(session, urlText, &options, &info);
    }

    heap_free(urlText);
    heap_free(pacText);

    if (!resolved) return NULL;

    if (info.dwAccessType == WINHTTP_ACCESS_TYPE_NO_PROXY || info.lpszProxy == NULL) {
        /* The script resolved to DIRECT: an empty string, not a failure. */
        result = (*env)->NewString(env, (const jchar *)L"", 0);
    } else {
        result = wide_to_java(env, info.lpszProxy);
    }

    /* The per-URL bypass list is redundant here: the caller already applied the
     * configured bypass rules before asking for a PAC resolution. */
    if (info.lpszProxy != NULL) GlobalFree(info.lpszProxy);
    if (info.lpszProxyBypass != NULL) GlobalFree(info.lpszProxyBypass);

    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nativeproxy_windows_WindowsProxyBridge_nativeWaitForConfigChange(
    JNIEnv *env, jclass clazz, jint timeoutMillis) {

    HKEY    keys[WATCH_KEY_COUNT];
    HANDLE  events[WATCH_KEY_COUNT + 1];
    DWORD   watched = 0;
    DWORD   total;
    DWORD   waitResult;
    HANDLE  wake;
    BOOL    changed = FALSE;
    DWORD   i;

    (void)env;
    (void)clazz;

    for (i = 0; i < WATCH_KEY_COUNT; i++) {
        HKEY   key = NULL;
        HANDLE event;

        if (RegOpenKeyExW(WATCH_KEYS[i].root, WATCH_KEYS[i].path, 0,
                          KEY_NOTIFY, &key) != ERROR_SUCCESS) {
            /* A policy key simply may not exist on this machine. */
            continue;
        }

        event = CreateEventW(NULL, TRUE, FALSE, NULL);
        if (event == NULL) {
            RegCloseKey(key);
            continue;
        }

        if (RegNotifyChangeKeyValue(key, TRUE, WATCH_FILTER, event, TRUE) != ERROR_SUCCESS) {
            CloseHandle(event);
            RegCloseKey(key);
            continue;
        }

        keys[watched] = key;
        events[watched] = event;
        watched++;
    }

    wake = wake_event();
    total = watched;
    if (wake != NULL) {
        events[total] = wake;
        total++;
    }

    if (total == 0) {
        Sleep(timeoutMillis > 0 ? (DWORD)timeoutMillis : 0);
        return JNI_FALSE;
    }

    waitResult = WaitForMultipleObjects(
        total, events, FALSE,
        timeoutMillis >= 0 ? (DWORD)timeoutMillis : INFINITE);

    /* Only the registry handles mean "configuration changed"; the wake event is
     * the JVM asking the watcher to stop. */
    if (waitResult >= WAIT_OBJECT_0 && waitResult < WAIT_OBJECT_0 + watched) {
        changed = TRUE;
    } else if (wake != NULL && waitResult == WAIT_OBJECT_0 + watched) {
        ResetEvent(wake);
    }

    for (i = 0; i < watched; i++) {
        CloseHandle(events[i]);
        RegCloseKey(keys[i]);
    }

    return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nativeproxy_windows_WindowsProxyBridge_nativeWakeWatcher(
    JNIEnv *env, jclass clazz) {

    HANDLE wake = wake_event();

    (void)env;
    (void)clazz;

    if (wake != NULL) SetEvent(wake);
}
