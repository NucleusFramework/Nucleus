/**
 * Linux JNI bridge for the system proxy configuration.
 *
 * Mirrors Chromium's `net::ProxyConfigServiceLinux` GSettings path
 * (`org.gnome.system.proxy` and its http/https/ftp/socks children):
 *
 *  - mode / autoconfig-url / per-scheme host+port / ignore-hosts
 *  - change notifications via a private GMainContext (no polling)
 *
 * All GLib/GIO symbols are resolved with dlopen so the .so has no hard
 * link-time dependency on libgio — same pattern as linux-hidpi and
 * decorated-window-core.
 *
 * The 4-string config array reuses the Windows layout so the Kotlin side
 * can feed the same ProxyRules / ProxyBypassRules parsers:
 *
 *   [0] proxy   — WinInet-style `http=…;https=…;socks=socks5://…` or bare host:port
 *   [1] bypass  — `;`-joined ignore-hosts list
 *   [2] pacUrl  — autoconfig-url (empty when unset)
 *   [3] auto    — "1" when mode=auto without an explicit PAC URL (WPAD)
 */

#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <unistd.h>

#define CONFIG_INDEX_PROXY       0
#define CONFIG_INDEX_BYPASS      1
#define CONFIG_INDEX_PAC_URL     2
#define CONFIG_INDEX_AUTO_DETECT 3
#define CONFIG_LENGTH            4

#define PROXY_SCHEMA "org.gnome.system.proxy"

typedef void *(*fn_schema_source_get_default)(void);
typedef void *(*fn_schema_source_lookup)(void *, const char *, int);
typedef void *(*fn_settings_new)(const char *);
typedef void *(*fn_settings_get_child)(void *, const char *);
typedef char *(*fn_settings_get_string)(void *, const char *);
typedef int   (*fn_settings_get_int)(void *, const char *);
typedef char **(*fn_settings_get_strv)(void *, const char *);
typedef void  (*fn_object_unref)(void *);
typedef void  (*fn_g_free)(void *);
typedef void  (*fn_strfreev)(char **);
typedef unsigned long (*fn_signal_connect_data)(
    void *, const char *, void *, void *, void *, int);

typedef void *(*fn_main_context_new)(void);
typedef void  (*fn_main_context_unref)(void *);
typedef int   (*fn_main_context_iteration)(void *, int);
typedef void  (*fn_main_context_push_thread_default)(void *);
typedef void  (*fn_main_context_pop_thread_default)(void *);
typedef void  (*fn_main_context_wakeup)(void *);

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_change_pipe[2] = { -1, -1 };
static int g_wake_pipe[2] = { -1, -1 };
static int g_inotify_fd = -1;
static int g_inotify_wd = -1;
static pthread_t g_watch_thread;
static volatile int g_watch_running = 0;
static volatile int g_watch_started = 0;
/** -1 unknown, 0 unavailable, 1 running. */
static volatile int g_watch_available = -1;
static void *g_watch_libgio = NULL;
static void *g_watch_context = NULL;

static void *open_gio(void) {
    void *lib = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!lib) lib = dlopen("libgio-2.0.so", RTLD_LAZY | RTLD_LOCAL);
    return lib;
}

static int schema_exists(
    fn_schema_source_get_default gssg,
    fn_schema_source_lookup gssl,
    const char *schema) {
    void *source;
    void *found;
    if (!gssg || !gssl) return 0;
    source = gssg();
    if (!source) return 0;
    found = gssl(source, schema, 1);
    return found != NULL;
}

static jstring utf8_to_java(JNIEnv *env, const char *text) {
    if (text == NULL) return NULL;
    return (*env)->NewStringUTF(env, text);
}

static void append_proxy_entry(
    char *buffer,
    size_t capacity,
    int *first,
    const char *scheme_prefix,
    const char *host,
    int port) {
    size_t used;
    if (host == NULL || host[0] == '\0') return;
    used = strlen(buffer);
    if (used + 64 >= capacity) return;
    if (!*first) {
        buffer[used++] = ';';
        buffer[used] = '\0';
    }
    *first = 0;
    if (scheme_prefix != NULL) {
        snprintf(buffer + used, capacity - used, "%s%s:%d", scheme_prefix, host, port);
    } else {
        snprintf(buffer + used, capacity - used, "%s:%d", host, port);
    }
}

static jobjectArray read_gsettings_config(JNIEnv *env) {
    void *libgio;
    fn_schema_source_get_default gssg;
    fn_schema_source_lookup gssl;
    fn_settings_new gsn;
    fn_settings_get_child gchild;
    fn_settings_get_string gstr;
    fn_settings_get_int gint;
    fn_settings_get_strv gstrv;
    fn_object_unref gou;
    fn_g_free gfree;
    fn_strfreev gstrfreev;

    void *root = NULL;
    void *http = NULL;
    void *https = NULL;
    void *ftp = NULL;
    void *socks = NULL;

    char *mode = NULL;
    char *pac_url = NULL;
    char *http_host = NULL;
    char *https_host = NULL;
    char *ftp_host = NULL;
    char *socks_host = NULL;
    char **ignore = NULL;

    int http_port = 0;
    int https_port = 0;
    int ftp_port = 0;
    int socks_port = 0;

    char proxy_buf[1024];
    char bypass_buf[2048];
    char auto_detect_flag[2];

    jclass string_class;
    jobjectArray result;
    jstring j_proxy, j_bypass, j_pac, j_auto;

    libgio = open_gio();
    if (!libgio) return NULL;

    gssg = (fn_schema_source_get_default)dlsym(libgio, "g_settings_schema_source_get_default");
    gssl = (fn_schema_source_lookup)dlsym(libgio, "g_settings_schema_source_lookup");
    gsn = (fn_settings_new)dlsym(libgio, "g_settings_new");
    gchild = (fn_settings_get_child)dlsym(libgio, "g_settings_get_child");
    gstr = (fn_settings_get_string)dlsym(libgio, "g_settings_get_string");
    gint = (fn_settings_get_int)dlsym(libgio, "g_settings_get_int");
    gstrv = (fn_settings_get_strv)dlsym(libgio, "g_settings_get_strv");
    gou = (fn_object_unref)dlsym(libgio, "g_object_unref");
    gfree = (fn_g_free)dlsym(libgio, "g_free");
    gstrfreev = (fn_strfreev)dlsym(libgio, "g_strfreev");

    if (!gssg || !gssl || !gsn || !gchild || !gstr || !gint || !gstrv ||
        !gou || !gfree || !gstrfreev) {
        dlclose(libgio);
        return NULL;
    }

    if (!schema_exists(gssg, gssl, PROXY_SCHEMA)) {
        dlclose(libgio);
        return NULL;
    }

    root = gsn(PROXY_SCHEMA);
    if (!root) {
        dlclose(libgio);
        return NULL;
    }

    http = gchild(root, "http");
    https = gchild(root, "https");
    ftp = gchild(root, "ftp");
    socks = gchild(root, "socks");

    mode = gstr(root, "mode");
    pac_url = gstr(root, "autoconfig-url");
    ignore = gstrv(root, "ignore-hosts");

    if (http) {
        http_host = gstr(http, "host");
        http_port = gint(http, "port");
    }
    if (https) {
        https_host = gstr(https, "host");
        https_port = gint(https, "port");
    }
    if (ftp) {
        ftp_host = gstr(ftp, "host");
        ftp_port = gint(ftp, "port");
    }
    if (socks) {
        socks_host = gstr(socks, "host");
        socks_port = gint(socks, "port");
    }

    proxy_buf[0] = '\0';
    bypass_buf[0] = '\0';
    auto_detect_flag[0] = '0';
    auto_detect_flag[1] = '\0';

    if (mode != NULL && strcmp(mode, "auto") == 0) {
        if (pac_url == NULL || pac_url[0] == '\0') {
            auto_detect_flag[0] = '1';
        }
    } else if (mode != NULL && strcmp(mode, "manual") == 0) {
        int first = 1;
        int has_http = http_host && http_host[0];
        int has_https = https_host && https_host[0];
        int has_ftp = ftp_host && ftp_host[0];
        int has_socks = socks_host && socks_host[0];
        int only_http = has_http && !has_https && !has_ftp && !has_socks;

        if (only_http) {
            append_proxy_entry(proxy_buf, sizeof(proxy_buf), &first, NULL, http_host, http_port);
        } else {
            append_proxy_entry(proxy_buf, sizeof(proxy_buf), &first, "http=", http_host, http_port);
            append_proxy_entry(proxy_buf, sizeof(proxy_buf), &first, "https=", https_host, https_port);
            append_proxy_entry(proxy_buf, sizeof(proxy_buf), &first, "ftp=", ftp_host, ftp_port);
            if (has_socks) {
                char socks_spec[512];
                int port = socks_port > 0 ? socks_port : 1080;
                snprintf(socks_spec, sizeof(socks_spec), "socks5://%s:%d", socks_host, port);
                if (!first) {
                    size_t used = strlen(proxy_buf);
                    if (used + 2 < sizeof(proxy_buf)) {
                        proxy_buf[used++] = ';';
                        proxy_buf[used] = '\0';
                    }
                }
                first = 0;
                {
                    size_t used = strlen(proxy_buf);
                    snprintf(proxy_buf + used, sizeof(proxy_buf) - used, "socks=%s", socks_spec);
                }
            }
        }
    }

    if (ignore != NULL) {
        size_t used = 0;
        size_t i;
        for (i = 0; ignore[i] != NULL; i++) {
            size_t len = strlen(ignore[i]);
            if (used + len + 2 >= sizeof(bypass_buf)) break;
            if (used > 0) bypass_buf[used++] = ';';
            memcpy(bypass_buf + used, ignore[i], len);
            used += len;
            bypass_buf[used] = '\0';
        }
    }

    string_class = (*env)->FindClass(env, "java/lang/String");
    result = string_class != NULL
        ? (*env)->NewObjectArray(env, CONFIG_LENGTH, string_class, NULL)
        : NULL;

    if (result != NULL) {
        j_proxy = utf8_to_java(env, proxy_buf);
        j_bypass = utf8_to_java(env, bypass_buf);
        j_pac = utf8_to_java(env, (pac_url && pac_url[0]) ? pac_url : "");
        j_auto = utf8_to_java(env, auto_detect_flag);

        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_PROXY, j_proxy);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_BYPASS, j_bypass);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_PAC_URL, j_pac);
        (*env)->SetObjectArrayElement(env, result, CONFIG_INDEX_AUTO_DETECT, j_auto);

        if (j_proxy) (*env)->DeleteLocalRef(env, j_proxy);
        if (j_bypass) (*env)->DeleteLocalRef(env, j_bypass);
        if (j_pac) (*env)->DeleteLocalRef(env, j_pac);
        if (j_auto) (*env)->DeleteLocalRef(env, j_auto);
    }

    if (mode) gfree(mode);
    if (pac_url) gfree(pac_url);
    if (http_host) gfree(http_host);
    if (https_host) gfree(https_host);
    if (ftp_host) gfree(ftp_host);
    if (socks_host) gfree(socks_host);
    if (ignore) gstrfreev(ignore);

    if (socks) gou(socks);
    if (ftp) gou(ftp);
    if (https) gou(https);
    if (http) gou(http);
    gou(root);
    dlclose(libgio);
    return result;
}

static void signal_pipe(int fd) {
    char byte = 1;
    if (fd >= 0) {
        ssize_t n = write(fd, &byte, 1);
        (void)n;
    }
}

static void drain_pipe(int fd) {
    char buf[64];
    if (fd < 0) return;
    while (read(fd, buf, sizeof(buf)) > 0) { }
}

static void on_gsettings_changed(void *settings, const char *key, void *user_data) {
    (void)settings;
    (void)key;
    (void)user_data;
    signal_pipe(g_change_pipe[1]);
}

static void mark_watch_unavailable(void) {
    pthread_mutex_lock(&g_lock);
    g_watch_available = 0;
    g_watch_running = 0;
    pthread_mutex_unlock(&g_lock);
}

static void *watch_thread_main(void *arg) {
    void *libgio;
    fn_schema_source_get_default gssg;
    fn_schema_source_lookup gssl;
    fn_settings_new gsn;
    fn_settings_get_child gchild;
    fn_object_unref gou;
    fn_signal_connect_data gsignal;
    fn_main_context_new ctx_new;
    fn_main_context_unref ctx_unref;
    fn_main_context_iteration ctx_iter;
    fn_main_context_push_thread_default ctx_push;
    fn_main_context_pop_thread_default ctx_pop;

    void *root = NULL;
    void *http = NULL;
    void *https = NULL;
    void *ftp = NULL;
    void *socks = NULL;
    void *context = NULL;

    (void)arg;

    libgio = open_gio();
    if (!libgio) {
        mark_watch_unavailable();
        return NULL;
    }

    gssg = (fn_schema_source_get_default)dlsym(libgio, "g_settings_schema_source_get_default");
    gssl = (fn_schema_source_lookup)dlsym(libgio, "g_settings_schema_source_lookup");
    gsn = (fn_settings_new)dlsym(libgio, "g_settings_new");
    gchild = (fn_settings_get_child)dlsym(libgio, "g_settings_get_child");
    gou = (fn_object_unref)dlsym(libgio, "g_object_unref");
    gsignal = (fn_signal_connect_data)dlsym(libgio, "g_signal_connect_data");
    ctx_new = (fn_main_context_new)dlsym(libgio, "g_main_context_new");
    ctx_unref = (fn_main_context_unref)dlsym(libgio, "g_main_context_unref");
    ctx_iter = (fn_main_context_iteration)dlsym(libgio, "g_main_context_iteration");
    ctx_push = (fn_main_context_push_thread_default)dlsym(libgio, "g_main_context_push_thread_default");
    ctx_pop = (fn_main_context_pop_thread_default)dlsym(libgio, "g_main_context_pop_thread_default");

    if (!gssg || !gssl || !gsn || !gchild || !gou || !gsignal ||
        !ctx_new || !ctx_unref || !ctx_iter || !ctx_push || !ctx_pop) {
        dlclose(libgio);
        mark_watch_unavailable();
        return NULL;
    }

    if (!schema_exists(gssg, gssl, PROXY_SCHEMA)) {
        dlclose(libgio);
        mark_watch_unavailable();
        return NULL;
    }

    context = ctx_new();
    if (!context) {
        dlclose(libgio);
        mark_watch_unavailable();
        return NULL;
    }
    ctx_push(context);

    root = gsn(PROXY_SCHEMA);
    if (!root) {
        ctx_pop(context);
        ctx_unref(context);
        dlclose(libgio);
        mark_watch_unavailable();
        return NULL;
    }

    http = gchild(root, "http");
    https = gchild(root, "https");
    ftp = gchild(root, "ftp");
    socks = gchild(root, "socks");

    gsignal(root, "changed", (void *)on_gsettings_changed, NULL, NULL, 0);
    if (http) gsignal(http, "changed", (void *)on_gsettings_changed, NULL, NULL, 0);
    if (https) gsignal(https, "changed", (void *)on_gsettings_changed, NULL, NULL, 0);
    if (ftp) gsignal(ftp, "changed", (void *)on_gsettings_changed, NULL, NULL, 0);
    if (socks) gsignal(socks, "changed", (void *)on_gsettings_changed, NULL, NULL, 0);

    pthread_mutex_lock(&g_lock);
    g_watch_libgio = libgio;
    g_watch_context = context;
    g_watch_available = 1;
    pthread_mutex_unlock(&g_lock);

    while (g_watch_running) {
        ctx_iter(context, 1);
    }

    ctx_pop(context);
    if (socks) gou(socks);
    if (ftp) gou(ftp);
    if (https) gou(https);
    if (http) gou(http);
    gou(root);
    ctx_unref(context);

    pthread_mutex_lock(&g_lock);
    g_watch_libgio = NULL;
    g_watch_context = NULL;
    pthread_mutex_unlock(&g_lock);

    dlclose(libgio);
    return NULL;
}


/**
 * Watches the user dconf database. GSettings change signals are delivered on
 * the process-default GMainContext's GDBus connection; a private context often
 * never sees them. inotify on ~/.config/dconf/user is the reliable alternative
 * used by several desktop tools and covers both GNOME Settings and `gsettings`.
 */
static int ensure_inotify(void) {
    const char *home;
    char path[512];

    if (g_inotify_fd >= 0) return 1;

    g_inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (g_inotify_fd < 0) return 0;

    home = getenv("HOME");
    if (home == NULL || home[0] == '\0') return 1; /* pipe-only fallback */

    snprintf(path, sizeof(path), "%s/.config/dconf/user", home);
    g_inotify_wd = inotify_add_watch(
        g_inotify_fd, path, IN_CLOSE_WRITE | IN_MODIFY | IN_MOVED_TO);
    /* Missing file is fine — GSettings may still work via the pipe. */
    return 1;
}

static void drain_inotify(void) {
    char buf[4096];
    if (g_inotify_fd < 0) return;
    while (read(g_inotify_fd, buf, sizeof(buf)) > 0) { }
}

static int ensure_pipes(void) {
    if (g_change_pipe[0] < 0 && pipe(g_change_pipe) != 0) return 0;
    if (g_wake_pipe[0] < 0 && pipe(g_wake_pipe) != 0) return 0;
    return 1;
}

static int ensure_watcher(void) {
    pthread_mutex_lock(&g_lock);
    if (g_watch_available == 0) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    if (g_watch_started && g_watch_running) {
        pthread_mutex_unlock(&g_lock);
        return 1;
    }
    if (g_watch_started && !g_watch_running) {
        pthread_mutex_unlock(&g_lock);
        pthread_join(g_watch_thread, NULL);
        pthread_mutex_lock(&g_lock);
        g_watch_started = 0;
        if (g_watch_available == 0) {
            pthread_mutex_unlock(&g_lock);
            return 0;
        }
    }
    if (!ensure_pipes()) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    ensure_inotify();
    g_watch_running = 1;
    g_watch_available = -1;
    if (pthread_create(&g_watch_thread, NULL, watch_thread_main, NULL) != 0) {
        g_watch_running = 0;
        g_watch_available = 0;
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    g_watch_started = 1;
    pthread_mutex_unlock(&g_lock);

    {
        int i;
        for (i = 0; i < 50; i++) {
            if (g_watch_available != -1) break;
            usleep(1000);
        }
    }
    return g_watch_available == 1;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nativeproxy_linux_LinuxProxyBridge_nativeGetProxyConfig(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    return read_gsettings_config(env);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nativeproxy_linux_LinuxProxyBridge_nativeWaitForConfigChange(
    JNIEnv *env, jclass clazz, jint timeoutMillis) {
    struct pollfd fds[3];
    int nfds = 0;
    int idx_change = -1;
    int idx_wake = -1;
    int idx_inotify = -1;
    int rc;
    int changed = 0;

    (void)env;
    (void)clazz;

    /* Prefer the GSettings watcher when available; always try inotify. */
    ensure_watcher();
    ensure_inotify();
    ensure_pipes();

    if (g_change_pipe[0] < 0 && g_inotify_fd < 0) {
        if (timeoutMillis > 0) usleep((useconds_t)timeoutMillis * 1000u);
        return JNI_FALSE;
    }

    drain_pipe(g_change_pipe[0]);
    drain_pipe(g_wake_pipe[0]);
    drain_inotify();

    if (g_change_pipe[0] >= 0) {
        idx_change = nfds;
        fds[nfds].fd = g_change_pipe[0];
        fds[nfds].events = POLLIN;
        nfds++;
    }
    if (g_wake_pipe[0] >= 0) {
        idx_wake = nfds;
        fds[nfds].fd = g_wake_pipe[0];
        fds[nfds].events = POLLIN;
        nfds++;
    }
    if (g_inotify_fd >= 0) {
        idx_inotify = nfds;
        fds[nfds].fd = g_inotify_fd;
        fds[nfds].events = POLLIN;
        nfds++;
    }

    if (nfds == 0) {
        if (timeoutMillis > 0) usleep((useconds_t)timeoutMillis * 1000u);
        return JNI_FALSE;
    }

    rc = poll(fds, (nfds_t)nfds, timeoutMillis >= 0 ? timeoutMillis : -1);
    if (rc > 0) {
        if (idx_change >= 0 && (fds[idx_change].revents & POLLIN)) {
            drain_pipe(g_change_pipe[0]);
            changed = 1;
        }
        if (idx_inotify >= 0 && (fds[idx_inotify].revents & POLLIN)) {
            drain_inotify();
            changed = 1;
        }
        if (idx_wake >= 0 && (fds[idx_wake].revents & POLLIN)) {
            drain_pipe(g_wake_pipe[0]);
        }
    }
    return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nativeproxy_linux_LinuxProxyBridge_nativeWakeWatcher(
    JNIEnv *env, jclass clazz) {
    void *libgio;
    void *context;
    fn_main_context_wakeup ctx_wakeup;
    int should_join = 0;

    (void)env;
    (void)clazz;

    signal_pipe(g_wake_pipe[1]);

    pthread_mutex_lock(&g_lock);
    if (g_watch_started && g_watch_running) {
        g_watch_running = 0;
        libgio = g_watch_libgio;
        context = g_watch_context;
        if (libgio && context) {
            ctx_wakeup = (fn_main_context_wakeup)dlsym(libgio, "g_main_context_wakeup");
            if (ctx_wakeup) ctx_wakeup(context);
        }
        should_join = 1;
    }
    pthread_mutex_unlock(&g_lock);

    if (should_join) {
        pthread_join(g_watch_thread, NULL);
        pthread_mutex_lock(&g_lock);
        g_watch_started = 0;
        if (g_watch_available == 1) g_watch_available = -1;
        pthread_mutex_unlock(&g_lock);
    }
}
