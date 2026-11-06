#include <jni.h>
#import <CFNetwork/CFProxySupport.h>
#import <CoreFoundation/CoreFoundation.h>
#import <SystemConfiguration/SystemConfiguration.h>

#include <pthread.h>
#include <stdio.h>
#include <string.h>

/**
 * macOS JNI bridge for the system proxy configuration.
 *
 * Mirrors Chromium's `net::ProxyConfigServiceMac` and `ProxyResolverApple`:
 *
 *  - `SCDynamicStoreCopyProxies` reads the effective configuration (PAC URL,
 *    WPAD flag, per-scheme HTTP/HTTPS/FTP/SOCKS hosts, exception list,
 *    ExcludeSimpleHostnames).
 *  - `CFNetworkExecuteProxyAutoConfigurationURL` evaluates a PAC script for a
 *    URL, pumping a private CFRunLoop mode so the call looks synchronous from
 *    the JVM (same technique as Chromium's ProxyResolverApple).
 *  - `SCDynamicStore` notification keys report configuration changes without
 *    polling; the wait parks on the current thread's CFRunLoop until a change
 *    or timeout.
 *
 * The 4-string config array reuses the Windows/Linux layout so the Kotlin side
 * can feed the same ProxyRules / ProxyBypassRules parsers:
 *
 *   [0] proxy   — WinInet-style `http=…;https=…;socks=socks5://…`
 *   [1] bypass  — `;`-joined ExceptionsList, plus `<local>` when
 *                 ExcludeSimpleHostnames is set
 *   [2] pacUrl  — ProxyAutoConfigURLString (empty when unset / disabled)
 *   [3] auto    — "1" when ProxyAutoDiscoveryEnable is set (WPAD)
 */

#define CONFIG_INDEX_PROXY       0
#define CONFIG_INDEX_BYPASS      1
#define CONFIG_INDEX_PAC_URL     2
#define CONFIG_INDEX_AUTO_DETECT 3
#define CONFIG_LENGTH            4

/* PAC evaluation timeout — a stuck script must never park a connection forever. */
#define PAC_TIMEOUT_SECONDS 10.0

#define PROXY_BUF_SIZE  1024
#define BYPASS_BUF_SIZE 2048

/* ── CF helpers ── */

static int dict_bool(CFDictionaryRef dict, CFStringRef key, int defaultValue) {
    CFNumberRef number;
    int value;

    if (dict == NULL) return defaultValue;
    number = (CFNumberRef)CFDictionaryGetValue(dict, key);
    if (number == NULL || CFGetTypeID(number) != CFNumberGetTypeID()) {
        return defaultValue;
    }
    if (!CFNumberGetValue(number, kCFNumberIntType, &value)) {
        return defaultValue;
    }
    return value != 0;
}

static int dict_int(CFDictionaryRef dict, CFStringRef key, int defaultValue) {
    CFNumberRef number;
    int value;

    if (dict == NULL) return defaultValue;
    number = (CFNumberRef)CFDictionaryGetValue(dict, key);
    if (number == NULL || CFGetTypeID(number) != CFNumberGetTypeID()) {
        return defaultValue;
    }
    if (!CFNumberGetValue(number, kCFNumberIntType, &value)) {
        return defaultValue;
    }
    return value;
}

/**
 * Copies a CFString into [out], returning the number of bytes written (excluding
 * the trailing NUL), or -1 when the string is missing / conversion fails.
 */
static int cfstring_to_utf8(CFStringRef text, char *out, size_t outSize) {
    if (text == NULL || out == NULL || outSize == 0) return -1;
    if (!CFStringGetCString(text, out, (CFIndex)outSize, kCFStringEncodingUTF8)) {
        return -1;
    }
    return (int)strlen(out);
}

static jstring utf8_to_java(JNIEnv *env, const char *text) {
    if (text == NULL) return NULL;
    return (*env)->NewStringUTF(env, text);
}

/**
 * Appends `scheme=host:port` (or `scheme=socks5://host:port` for SOCKS) to
 * [buffer]. Returns 0 on success, -1 when the host is missing or the buffer is
 * full. SOCKS defaults to SOCKS5 on modern macOS (System Preferences has no
 * SOCKS4 toggle).
 */
static int append_proxy_entry(
    char *buffer,
    size_t bufferSize,
    int *length,
    const char *scheme,
    CFDictionaryRef dict,
    CFStringRef hostKey,
    CFStringRef portKey,
    int defaultPort,
    int socks) {

    char host[256];
    int port;
    int written;
    CFStringRef hostRef = (CFStringRef)CFDictionaryGetValue(dict, hostKey);

    if (hostRef == NULL || CFGetTypeID(hostRef) != CFStringGetTypeID()) return -1;
    if (cfstring_to_utf8(hostRef, host, sizeof(host)) <= 0) return -1;

    port = dict_int(dict, portKey, defaultPort);
    if (port <= 0 || port > 65535) port = defaultPort;

    if (*length > 0 && (size_t)*length + 1 < bufferSize) {
        buffer[(*length)++] = ';';
        buffer[*length] = '\0';
    }

    if (socks) {
        written = snprintf(
            buffer + *length,
            bufferSize - (size_t)*length,
            "%s=socks5://%s:%d",
            scheme,
            host,
            port);
    } else {
        written = snprintf(
            buffer + *length,
            bufferSize - (size_t)*length,
            "%s=%s:%d",
            scheme,
            host,
            port);
    }

    if (written < 0 || (size_t)written >= bufferSize - (size_t)*length) {
        buffer[*length] = '\0';
        return -1;
    }
    *length += written;
    return 0;
}

/** Builds the WinInet-style proxy string from an SCDynamicStore proxies dict. */
static void build_proxy_string(CFDictionaryRef dict, char *out, size_t outSize) {
    int length = 0;
    out[0] = '\0';

    if (dict_bool(dict, kSCPropNetProxiesHTTPEnable, 0)) {
        append_proxy_entry(
            out, outSize, &length, "http", dict,
            kSCPropNetProxiesHTTPProxy, kSCPropNetProxiesHTTPPort, 80, 0);
    }
    if (dict_bool(dict, kSCPropNetProxiesHTTPSEnable, 0)) {
        append_proxy_entry(
            out, outSize, &length, "https", dict,
            kSCPropNetProxiesHTTPSProxy, kSCPropNetProxiesHTTPSPort, 443, 0);
    }
    if (dict_bool(dict, kSCPropNetProxiesFTPEnable, 0)) {
        append_proxy_entry(
            out, outSize, &length, "ftp", dict,
            kSCPropNetProxiesFTPProxy, kSCPropNetProxiesFTPPort, 21, 0);
    }
    if (dict_bool(dict, kSCPropNetProxiesSOCKSEnable, 0)) {
        /* `socks=` is the WinInet fallback scheme; see ProxyRules.parse. */
        append_proxy_entry(
            out, outSize, &length, "socks", dict,
            kSCPropNetProxiesSOCKSProxy, kSCPropNetProxiesSOCKSPort, 1080, 1);
    }
}

/**
 * Builds the bypass list: ExceptionsList joined by `;`, plus `<local>` when
 * ExcludeSimpleHostnames is set (Chromium's PrependRuleToBypassSimpleHostnames).
 */
static void build_bypass_string(CFDictionaryRef dict, char *out, size_t outSize) {
    CFArrayRef exceptions;
    CFIndex count;
    CFIndex i;
    int length = 0;

    out[0] = '\0';

    exceptions = (CFArrayRef)CFDictionaryGetValue(dict, kSCPropNetProxiesExceptionsList);
    if (exceptions != NULL && CFGetTypeID(exceptions) == CFArrayGetTypeID()) {
        count = CFArrayGetCount(exceptions);
        for (i = 0; i < count; i++) {
            char entry[256];
            CFStringRef item = (CFStringRef)CFArrayGetValueAtIndex(exceptions, i);
            if (item == NULL || CFGetTypeID(item) != CFStringGetTypeID()) continue;
            if (cfstring_to_utf8(item, entry, sizeof(entry)) <= 0) continue;

            if (length > 0 && (size_t)length + 1 < outSize) {
                out[length++] = ';';
                out[length] = '\0';
            }
            {
                int written = snprintf(out + length, outSize - (size_t)length, "%s", entry);
                if (written < 0 || (size_t)written >= outSize - (size_t)length) {
                    out[length] = '\0';
                    break;
                }
                length += written;
            }
        }
    }

    if (dict_bool(dict, kSCPropNetProxiesExcludeSimpleHostnames, 0)) {
        if (length > 0 && (size_t)length + 1 < outSize) {
            out[length++] = ';';
            out[length] = '\0';
        }
        if ((size_t)length + 7 < outSize) {
            memcpy(out + length, "<local>", 8);
        }
    }
}

/* ── PAC resolution ── */

typedef struct {
    CFTypeRef result; /* retained CFArrayRef of proxies, or CFErrorRef */
} PacResult;

static void pac_result_callback(void *client, CFArrayRef proxies, CFErrorRef error) {
    PacResult *state = (PacResult *)client;
    if (state == NULL || state->result != NULL) return;
    if (error != NULL) {
        state->result = CFRetain(error);
    } else if (proxies != NULL) {
        state->result = CFRetain(proxies);
    }
    CFRunLoopStop(CFRunLoopGetCurrent());
}

/**
 * Formats a CFArray of CFNetwork proxy dictionaries into a WinInet-style proxy
 * list (`host:port` / `socks5://host:port`, `;`-joined). Returns an empty
 * string for DIRECT-only results. Writes into [out]; returns 0 on success.
 */
static int format_proxy_array(CFArrayRef proxies, char *out, size_t outSize) {
    CFIndex count;
    CFIndex i;
    int length = 0;
    int sawDirect = 0;

    out[0] = '\0';
    if (proxies == NULL) return -1;

    count = CFArrayGetCount(proxies);
    for (i = 0; i < count; i++) {
        CFDictionaryRef entry = (CFDictionaryRef)CFArrayGetValueAtIndex(proxies, i);
        CFStringRef type;
        char host[256];
        int port;
        int written;
        CFStringRef hostRef;
        CFNumberRef portRef;

        if (entry == NULL || CFGetTypeID(entry) != CFDictionaryGetTypeID()) continue;

        type = (CFStringRef)CFDictionaryGetValue(entry, kCFProxyTypeKey);
        if (type == NULL) continue;

        if (CFEqual(type, kCFProxyTypeNone)) {
            sawDirect = 1;
            continue;
        }
        /* Nested PAC URLs are not re-resolved; treat as failure to fall back. */
        if (CFEqual(type, kCFProxyTypeAutoConfigurationURL) ||
            CFEqual(type, kCFProxyTypeAutoConfigurationJavaScript)) {
            return -1;
        }

        hostRef = (CFStringRef)CFDictionaryGetValue(entry, kCFProxyHostNameKey);
        if (hostRef == NULL || CFGetTypeID(hostRef) != CFStringGetTypeID()) continue;
        if (cfstring_to_utf8(hostRef, host, sizeof(host)) <= 0) continue;

        port = 0;
        portRef = (CFNumberRef)CFDictionaryGetValue(entry, kCFProxyPortNumberKey);
        if (portRef != NULL && CFGetTypeID(portRef) == CFNumberGetTypeID()) {
            CFNumberGetValue(portRef, kCFNumberIntType, &port);
        }

        if (length > 0 && (size_t)length + 1 < outSize) {
            out[length++] = ';';
            out[length] = '\0';
        }

        if (CFEqual(type, kCFProxyTypeSOCKS)) {
            if (port <= 0) port = 1080;
            written = snprintf(
                out + length, outSize - (size_t)length, "socks5://%s:%d", host, port);
        } else if (CFEqual(type, kCFProxyTypeHTTPS)) {
            if (port <= 0) port = 443;
            written = snprintf(
                out + length, outSize - (size_t)length, "https://%s:%d", host, port);
        } else {
            /* HTTP and FTP proxies dial as HTTP CONNECT / plain HTTP. */
            if (port <= 0) port = 80;
            written = snprintf(
                out + length, outSize - (size_t)length, "http://%s:%d", host, port);
        }

        if (written < 0 || (size_t)written >= outSize - (size_t)length) {
            out[length] = '\0';
            return -1;
        }
        length += written;
    }

    if (length == 0 && sawDirect) {
        /* DIRECT: empty string (matches the Windows bridge contract). */
        out[0] = '\0';
        return 0;
    }
    return length > 0 ? 0 : -1;
}

/**
 * Evaluates the PAC script at [pacUrlUtf8] for [urlUtf8]. Returns a newly
 * allocated UTF-8 proxy list (caller frees with free), an empty malloc'd string
 * for DIRECT, or NULL on failure.
 */
static char *resolve_pac(const char *urlUtf8, const char *pacUrlUtf8) {
    CFURLRef queryUrl;
    CFURLRef pacUrl;
    CFDictionaryRef emptyDict;
    CFArrayRef dummy;
    PacResult state;
    CFStreamClientContext context;
    CFRunLoopSourceRef source;
    CFStringRef privateMode;
    char formatted[PROXY_BUF_SIZE];
    char *result;

    if (urlUtf8 == NULL || pacUrlUtf8 == NULL || pacUrlUtf8[0] == '\0') return NULL;

    queryUrl = CFURLCreateWithBytes(
        kCFAllocatorDefault,
        (const UInt8 *)urlUtf8,
        (CFIndex)strlen(urlUtf8),
        kCFStringEncodingUTF8,
        NULL);
    pacUrl = CFURLCreateWithBytes(
        kCFAllocatorDefault,
        (const UInt8 *)pacUrlUtf8,
        (CFIndex)strlen(pacUrlUtf8),
        kCFStringEncodingUTF8,
        NULL);
    if (queryUrl == NULL || pacUrl == NULL) {
        if (queryUrl) CFRelease(queryUrl);
        if (pacUrl) CFRelease(pacUrl);
        return NULL;
    }

    /*
     * Work around <rdar://problem/5530166>: a dummy CFNetworkCopyProxiesForURL
     * call initialises internal CFNetwork state required by
     * CFNetworkExecuteProxyAutoConfigurationURL (same fix as Chromium).
     */
    emptyDict = CFDictionaryCreate(NULL, NULL, NULL, 0, NULL, NULL);
    dummy = emptyDict != NULL
        ? CFNetworkCopyProxiesForURL(queryUrl, emptyDict)
        : NULL;
    if (emptyDict) CFRelease(emptyDict);
    if (dummy) CFRelease(dummy);

    state.result = NULL;
    memset(&context, 0, sizeof(context));
    context.info = &state;

    source = CFNetworkExecuteProxyAutoConfigurationURL(
        pacUrl, queryUrl, pac_result_callback, &context);
    CFRelease(queryUrl);
    CFRelease(pacUrl);

    if (source == NULL) return NULL;

    privateMode = CFSTR("dev.nucleusframework.nativeproxy.pac");
    CFRunLoopAddSource(CFRunLoopGetCurrent(), source, privateMode);
    CFRunLoopRunInMode(privateMode, PAC_TIMEOUT_SECONDS, false);
    CFRunLoopRemoveSource(CFRunLoopGetCurrent(), source, privateMode);
    CFRelease(source);

    if (state.result == NULL) return NULL;

    if (CFGetTypeID(state.result) == CFErrorGetTypeID()) {
        CFRelease(state.result);
        return NULL;
    }

    if (format_proxy_array((CFArrayRef)state.result, formatted, sizeof(formatted)) != 0) {
        CFRelease(state.result);
        return NULL;
    }
    CFRelease(state.result);

    result = (char *)malloc(strlen(formatted) + 1);
    if (result == NULL) return NULL;
    memcpy(result, formatted, strlen(formatted) + 1);
    return result;
}

/* ── Change watching via SCDynamicStore ── */

static pthread_mutex_t g_wait_mu = PTHREAD_MUTEX_INITIALIZER;
static CFRunLoopRef g_wait_loop = NULL;
static volatile int g_changed = 0;
static volatile int g_wake = 0;

static void on_proxy_config_change(
    SCDynamicStoreRef store,
    CFArrayRef changedKeys,
    void *info) {
    (void)store;
    (void)changedKeys;
    (void)info;
    g_changed = 1;
    pthread_mutex_lock(&g_wait_mu);
    if (g_wait_loop != NULL) {
        CFRunLoopStop(g_wait_loop);
    }
    pthread_mutex_unlock(&g_wait_mu);
}

/* ── JNI entry points ── */

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nativeproxy_macos_MacOsProxyBridge_nativeGetProxyConfig(
    JNIEnv *env,
    jclass clazz) {

    CFDictionaryRef dict;
    char proxy[PROXY_BUF_SIZE];
    char bypass[BYPASS_BUF_SIZE];
    char pacUrl[1024];
    jclass stringClass;
    jobjectArray result;
    jstring jProxy;
    jstring jBypass;
    jstring jPacUrl;
    jstring jAuto;

    (void)clazz;

    dict = SCDynamicStoreCopyProxies(NULL);
    if (dict == NULL) return NULL;

    build_proxy_string(dict, proxy, sizeof(proxy));
    build_bypass_string(dict, bypass, sizeof(bypass));

    pacUrl[0] = '\0';
    if (dict_bool(dict, kSCPropNetProxiesProxyAutoConfigEnable, 0)) {
        CFStringRef pacRef =
            (CFStringRef)CFDictionaryGetValue(dict, kSCPropNetProxiesProxyAutoConfigURLString);
        if (pacRef != NULL && CFGetTypeID(pacRef) == CFStringGetTypeID()) {
            cfstring_to_utf8(pacRef, pacUrl, sizeof(pacUrl));
        }
    }

    stringClass = (*env)->FindClass(env, "java/lang/String");
    result = stringClass != NULL
        ? (*env)->NewObjectArray(env, CONFIG_LENGTH, stringClass, NULL)
        : NULL;

    if (result != NULL) {
        jProxy = utf8_to_java(env, proxy);
        jBypass = utf8_to_java(env, bypass);
        jPacUrl = utf8_to_java(env, pacUrl);
        jAuto = utf8_to_java(
            env,
            dict_bool(dict, kSCPropNetProxiesProxyAutoDiscoveryEnable, 0) ? "1" : "0");

        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_PROXY, jProxy);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_BYPASS, jBypass);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_PAC_URL, jPacUrl);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_AUTO_DETECT, jAuto);

        if (jProxy) (*env)->DeleteLocalRef(env, jProxy);
        if (jBypass) (*env)->DeleteLocalRef(env, jBypass);
        if (jPacUrl) (*env)->DeleteLocalRef(env, jPacUrl);
        if (jAuto) (*env)->DeleteLocalRef(env, jAuto);
    }

    CFRelease(dict);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nativeproxy_macos_MacOsProxyBridge_nativeResolveProxyForUrl(
    JNIEnv *env,
    jclass clazz,
    jstring url,
    jstring pacUrl) {

    const char *urlUtf8;
    const char *pacUtf8;
    char *resolved;
    jstring result;

    (void)clazz;

    if (url == NULL || pacUrl == NULL) return NULL;

    urlUtf8 = (*env)->GetStringUTFChars(env, url, NULL);
    pacUtf8 = (*env)->GetStringUTFChars(env, pacUrl, NULL);
    if (urlUtf8 == NULL || pacUtf8 == NULL) {
        if (urlUtf8) (*env)->ReleaseStringUTFChars(env, url, urlUtf8);
        if (pacUtf8) (*env)->ReleaseStringUTFChars(env, pacUrl, pacUtf8);
        return NULL;
    }

    resolved = resolve_pac(urlUtf8, pacUtf8);

    (*env)->ReleaseStringUTFChars(env, url, urlUtf8);
    (*env)->ReleaseStringUTFChars(env, pacUrl, pacUtf8);

    if (resolved == NULL) return NULL;
    result = (*env)->NewStringUTF(env, resolved);
    free(resolved);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nativeproxy_macos_MacOsProxyBridge_nativeWaitForConfigChange(
    JNIEnv *env,
    jclass clazz,
    jint timeoutMillis) {

    SCDynamicStoreContext ctx;
    SCDynamicStoreRef store;
    CFStringRef proxiesKey;
    CFArrayRef keyArray;
    CFRunLoopSourceRef source;
    CFTimeInterval timeout;
    int changed;

    (void)env;
    (void)clazz;

    g_changed = 0;
    g_wake = 0;

    memset(&ctx, 0, sizeof(ctx));
    store = SCDynamicStoreCreate(
        kCFAllocatorDefault, CFSTR("dev.nucleusframework.nativeproxy"), on_proxy_config_change, &ctx);
    if (store == NULL) return JNI_FALSE;

    proxiesKey = SCDynamicStoreKeyCreateProxies(NULL);
    if (proxiesKey == NULL) {
        CFRelease(store);
        return JNI_FALSE;
    }
    keyArray = CFArrayCreate(
        kCFAllocatorDefault, (const void **)&proxiesKey, 1, &kCFTypeArrayCallBacks);
    CFRelease(proxiesKey);
    if (keyArray == NULL) {
        CFRelease(store);
        return JNI_FALSE;
    }

    if (!SCDynamicStoreSetNotificationKeys(store, keyArray, NULL)) {
        CFRelease(keyArray);
        CFRelease(store);
        return JNI_FALSE;
    }
    CFRelease(keyArray);

    source = SCDynamicStoreCreateRunLoopSource(kCFAllocatorDefault, store, 0);
    if (source == NULL) {
        CFRelease(store);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_wait_mu);
    g_wait_loop = CFRunLoopGetCurrent();
    pthread_mutex_unlock(&g_wait_mu);

    CFRunLoopAddSource(CFRunLoopGetCurrent(), source, kCFRunLoopDefaultMode);

    timeout = timeoutMillis <= 0 ? 0.0 : ((CFTimeInterval)timeoutMillis / 1000.0);
    /* returnAfterSourceHandled=false: run until CFRunLoopStop or the timeout. */
    CFRunLoopRunInMode(kCFRunLoopDefaultMode, timeout, false);

    CFRunLoopRemoveSource(CFRunLoopGetCurrent(), source, kCFRunLoopDefaultMode);

    pthread_mutex_lock(&g_wait_mu);
    g_wait_loop = NULL;
    pthread_mutex_unlock(&g_wait_mu);

    CFRelease(source);
    CFRelease(store);

    if (g_wake) return JNI_FALSE;
    changed = g_changed;
    return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nativeproxy_macos_MacOsProxyBridge_nativeWakeWatcher(
    JNIEnv *env,
    jclass clazz) {

    (void)env;
    (void)clazz;

    g_wake = 1;
    pthread_mutex_lock(&g_wait_mu);
    if (g_wait_loop != NULL) {
        CFRunLoopStop(g_wait_loop);
    }
    pthread_mutex_unlock(&g_wait_mu);
}
