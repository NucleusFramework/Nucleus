/**
 * JNI bridge for Linux layout direction detection and titlebar button layout.
 *
 * Layout direction:
 *   Uses Pango (via dlopen) to detect the system locale's text direction.
 *
 * Button layout:
 *   Reads and monitors org.gnome.desktop.wm.preferences → button-layout
 *   via GSettings (libgio dlopen), or the Settings portal inside a Flatpak.
 *   A background thread runs a GMainLoop to receive change notifications
 *   in real-time.
 *
 * Linked libraries: -ldl -lpthread
 */
#include <jni.h>
#include "../../../../../native-common/nucleus_jni.h"
#include <locale.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>

/* ================================================================== */
/*  Pango direction constants (from pango-types.h)                    */
/* ================================================================== */
#define PANGO_DIRECTION_LTR      0
#define PANGO_DIRECTION_RTL      1
#define PANGO_DIRECTION_TTB_LTR  2
#define PANGO_DIRECTION_TTB_RTL  3
#define PANGO_DIRECTION_WEAK_LTR 4
#define PANGO_DIRECTION_WEAK_RTL 5
#define PANGO_DIRECTION_NEUTRAL  6

/* Fallback RTL language codes (ISO 639-1 / 639-3) */
static const char *RTL_LANGS[] = {
    "ar", "he", "fa", "ur", "yi", "ps", "sd", "ckb", "ku", "ug", "syr", "dv"
};
static const int RTL_LANGS_COUNT = sizeof(RTL_LANGS) / sizeof(RTL_LANGS[0]);

/* ================================================================== */
/*  Cached JavaVM pointer, set in JNI_OnLoad                          */
/* ================================================================== */
static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/* ================================================================== */
/*  RTL detection via Pango                                           */
/* ================================================================== */
static int detect_rtl_via_pango(void) {
    setlocale(LC_ALL, "");

    void *libpango = dlopen("libpango-1.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!libpango) libpango = dlopen("libpango-1.0.so", RTLD_LAZY | RTLD_LOCAL);
    if (!libpango) return -1;

    typedef void*       (*fn_language_get_default)(void);
    typedef const char* (*fn_language_get_sample_string)(void*);
    typedef int         (*fn_find_base_dir)(const char*, int);

    fn_language_get_default       pango_lang_default =
        (fn_language_get_default)      dlsym(libpango, "pango_language_get_default");
    fn_language_get_sample_string pango_lang_sample  =
        (fn_language_get_sample_string)dlsym(libpango, "pango_language_get_sample_string");
    fn_find_base_dir              pango_base_dir     =
        (fn_find_base_dir)             dlsym(libpango, "pango_find_base_dir");

    int result = -1;
    if (pango_lang_default && pango_lang_sample && pango_base_dir) {
        void *lang = pango_lang_default();
        if (lang) {
            const char *sample = pango_lang_sample(lang);
            if (sample && sample[0] != '\0') {
                int dir = pango_base_dir(sample, -1);
                result = (dir == PANGO_DIRECTION_RTL ||
                          dir == PANGO_DIRECTION_WEAK_RTL) ? 1 : 0;
            }
        }
    }
    dlclose(libpango);
    return result;
}

static int detect_rtl_via_locale(void) {
    const char *locale = getenv("LC_ALL");
    if (!locale || locale[0] == '\0') locale = getenv("LC_MESSAGES");
    if (!locale || locale[0] == '\0') locale = getenv("LANG");
    if (!locale || locale[0] == '\0') return 0;
    if (strcmp(locale, "C") == 0 || strcmp(locale, "POSIX") == 0) return 0;

    char lang[16];
    int i = 0;
    while (i < 15 && locale[i] != '\0'
           && locale[i] != '_' && locale[i] != '.' && locale[i] != '@') {
        lang[i] = locale[i];
        i++;
    }
    lang[i] = '\0';
    if (i == 0) return 0;

    for (int j = 0; j < RTL_LANGS_COUNT; j++) {
        if (strcmp(lang, RTL_LANGS[j]) == 0) return 1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_NativeLayoutDirectionBridge_nativeIsRTL(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int result = detect_rtl_via_pango();
    if (result < 0) {
        result = detect_rtl_via_locale();
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

/* ================================================================== */
/*  GSettings button-layout: one-shot read                            */
/* ================================================================== */

/* GLib function typedefs used by both one-shot read and monitoring */
typedef void*        (*fn_schema_source_get_default)(void);
typedef void*        (*fn_schema_source_lookup)(void*, const char*, int);
typedef void*        (*fn_settings_new)(const char*);
typedef char*        (*fn_settings_get_string)(void*, const char*);
typedef void         (*fn_object_unref)(void*);
typedef void         (*fn_g_free)(void*);
typedef unsigned long (*fn_g_signal_connect_data)(void*, const char*, void*, void*, void*, int);
typedef void*        (*fn_g_main_context_new)(void);
typedef void         (*fn_g_main_context_unref)(void*);
typedef int          (*fn_g_main_context_iteration)(void*, int);
typedef void         (*fn_g_main_context_push_thread_default)(void*);
typedef void         (*fn_g_main_context_pop_thread_default)(void*);

/* ================================================================== */
/*  Settings portal (Flatpak)                                         */
/* ================================================================== */
/*
 * Inside a Flatpak, GSettings reads the app's own keyfile, not the host's
 * dconf: button-layout comes back as the schema default ("appmenu:close") and
 * minimize / maximize disappear. The host value is only reachable through
 * org.freedesktop.portal.Settings, which is also what GTK reads when sandboxed.
 */
#define BL_NAMESPACE "org.gnome.desktop.wm.preferences"
#define BL_KEY       "button-layout"

typedef void*       (*fn_g_bus_get_sync)(int, void*, void*);
typedef void*       (*fn_g_dbus_connection_call_sync)(void*, const char*, const char*, const char*,
                                                      const char*, void*, const void*, int, int,
                                                      void*, void*);
typedef unsigned int (*fn_g_dbus_connection_signal_subscribe)(void*, const char*, const char*,
                                                             const char*, const char*, const char*,
                                                             int, void*, void*, void*);
typedef void*       (*fn_g_variant_new)(const char*, ...);
typedef void*       (*fn_g_variant_get_child_value)(void*, size_t);
typedef void*       (*fn_g_variant_get_variant)(void*);
typedef char        (*fn_g_variant_classify)(void*);
typedef const char* (*fn_g_variant_get_string)(void*, size_t*);
typedef void        (*fn_g_variant_unref)(void*);

typedef struct {
    fn_g_bus_get_sync                     bus_get_sync;
    fn_g_dbus_connection_call_sync        call_sync;
    fn_g_dbus_connection_signal_subscribe signal_subscribe;
    fn_g_variant_new                      variant_new;
    fn_g_variant_get_child_value          get_child_value;
    fn_g_variant_get_variant              get_variant;
    fn_g_variant_classify                 classify;
    fn_g_variant_get_string               get_string;
    fn_g_variant_unref                    variant_unref;
} PortalApi;

static int is_inside_flatpak(void) {
    return access("/.flatpak-info", F_OK) == 0;
}

static int resolve_portal_api(void *libgio, PortalApi *api) {
    api->bus_get_sync     = (fn_g_bus_get_sync)dlsym(libgio, "g_bus_get_sync");
    api->call_sync        = (fn_g_dbus_connection_call_sync)dlsym(libgio, "g_dbus_connection_call_sync");
    api->signal_subscribe = (fn_g_dbus_connection_signal_subscribe)dlsym(libgio, "g_dbus_connection_signal_subscribe");
    api->variant_new      = (fn_g_variant_new)dlsym(libgio, "g_variant_new");
    api->get_child_value  = (fn_g_variant_get_child_value)dlsym(libgio, "g_variant_get_child_value");
    api->get_variant      = (fn_g_variant_get_variant)dlsym(libgio, "g_variant_get_variant");
    api->classify         = (fn_g_variant_classify)dlsym(libgio, "g_variant_classify");
    api->get_string       = (fn_g_variant_get_string)dlsym(libgio, "g_variant_get_string");
    api->variant_unref    = (fn_g_variant_unref)dlsym(libgio, "g_variant_unref");
    return api->bus_get_sync && api->call_sync && api->signal_subscribe && api->variant_new &&
           api->get_child_value && api->get_variant && api->classify && api->get_string &&
           api->variant_unref;
}

/* Copies the string inside [value], unwrapping variants (Read wraps it twice, ReadOne once). */
static char *portal_variant_to_string(const PortalApi *api, void *value) {
    void *current = value;
    int owned = 0;
    while (current && api->classify(current) == 'v') {
        void *inner = api->get_variant(current);
        if (owned) api->variant_unref(current);
        current = inner;
        owned = 1;
    }
    char *result = NULL;
    if (current && api->classify(current) == 's') {
        result = strdup(api->get_string(current, NULL));
    }
    if (owned && current) api->variant_unref(current);
    return result;
}

static char *portal_read_button_layout(const PortalApi *api, void *connection) {
    static const char *const methods[] = { "ReadOne", "Read" };
    for (int i = 0; i < 2; i++) {
        void *reply = api->call_sync(connection, "org.freedesktop.portal.Desktop",
            "/org/freedesktop/portal/desktop", "org.freedesktop.portal.Settings", methods[i],
            api->variant_new("(ss)", BL_NAMESPACE, BL_KEY), NULL, 0, 2000, NULL, NULL);
        if (!reply) continue;
        void *value = api->get_child_value(reply, 0);
        char *result = portal_variant_to_string(api, value);
        api->variant_unref(value);
        api->variant_unref(reply);
        if (result) return result;
    }
    return NULL;
}

static char *readPortalButtonLayout(void) {
    void *libgio = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!libgio) return NULL;
    char *result = NULL;
    PortalApi api;
    fn_object_unref gou = (fn_object_unref)dlsym(libgio, "g_object_unref");
    if (gou && resolve_portal_api(libgio, &api)) {
        void *connection = api.bus_get_sync(2 /* G_BUS_TYPE_SESSION */, NULL, NULL);
        if (connection) {
            result = portal_read_button_layout(&api, connection);
            gou(connection);
        }
    }
    dlclose(libgio);
    return result;
}

static char *readGnomeButtonLayout(void) {
    if (is_inside_flatpak()) return readPortalButtonLayout();

    void *libgio = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!libgio) return NULL;

    fn_schema_source_get_default gssg =
        (fn_schema_source_get_default)dlsym(libgio, "g_settings_schema_source_get_default");
    fn_schema_source_lookup gssl =
        (fn_schema_source_lookup)dlsym(libgio, "g_settings_schema_source_lookup");
    fn_settings_new gsn =
        (fn_settings_new)dlsym(libgio, "g_settings_new");
    fn_settings_get_string gsgs =
        (fn_settings_get_string)dlsym(libgio, "g_settings_get_string");
    fn_object_unref gou =
        (fn_object_unref)dlsym(libgio, "g_object_unref");
    fn_g_free gfree = (fn_g_free)dlsym(libgio, "g_free");

    char *result = NULL;

    if (gssg && gssl && gsn && gsgs && gou && gfree) {
        void *source = gssg();
        if (source) {
            void *schema = gssl(source, "org.gnome.desktop.wm.preferences", 1);
            if (schema) {
                void *settings = gsn("org.gnome.desktop.wm.preferences");
                if (settings) {
                    char *val = gsgs(settings, "button-layout");
                    if (val) {
                        result = strdup(val);
                        gfree(val);
                    }
                    gou(settings);
                }
            }
        }
    }

    dlclose(libgio);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_window_NativeLayoutDirectionBridge_nativeGetButtonLayout(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    char *layout = readGnomeButtonLayout();
    if (!layout) return NULL;
    jstring jstr = (*env)->NewStringUTF(env, layout);
    free(layout);
    return jstr;
}

/* ================================================================== */
/*  GSettings button-layout: reactive monitoring                      */
/* ================================================================== */

/* Monitoring thread state */
static pthread_t g_bl_thread;
static volatile int g_bl_running = 0;

/* Kept alive for the lifetime of the monitoring thread */
static void *g_bl_libgio   = NULL;
static void *g_bl_settings = NULL;
static void *g_bl_context  = NULL;

/* Resolved function pointers kept across the thread lifetime */
static fn_settings_get_string g_bl_get_string = NULL;
static fn_g_free              g_bl_gfree      = NULL;

/**
 * Notify the Kotlin bridge about a button-layout change.
 */
static void notify_button_layout(const char *layout) {
    if (g_jvm == NULL || layout == NULL) return;

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
        "dev/nucleusframework/window/NativeLayoutDirectionBridge");
    if (bridgeClass != NULL) {
        jmethodID method = (*env)->GetStaticMethodID(env,
            bridgeClass, "onButtonLayoutChanged", "(Ljava/lang/String;)V");
        if (method != NULL) {
            jstring jstr = (*env)->NewStringUTF(env, layout);
            if (jstr != NULL) {
                (*env)->CallStaticVoidMethod(env, bridgeClass, method, jstr);
                (*env)->DeleteLocalRef(env, jstr);
            }
        }
    }

    nucleus_jni_clear_exception(env);

    if (didAttach) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

/**
 * GSettings "changed::button-layout" signal callback.
 * Called on the monitoring thread's GMainContext.
 */
static void on_button_layout_changed(void *settings, const char *key, void *user_data) {
    (void)key; (void)user_data;
    if (g_bl_get_string == NULL || g_bl_gfree == NULL) return;

    char *val = g_bl_get_string(settings, "button-layout");
    if (val) {
        notify_button_layout(val);
        g_bl_gfree(val);
    }
}

/**
 * Settings portal "SettingChanged" callback (Flatpak), on the monitoring
 * thread's GMainContext. Parameters: (namespace, key, value).
 */
static PortalApi g_bl_portal;

static void on_portal_setting_changed(void *connection, const char *sender, const char *path,
                                      const char *iface, const char *signal, void *params,
                                      void *user_data) {
    (void)connection; (void)sender; (void)path; (void)iface; (void)signal; (void)user_data;
    void *key = g_bl_portal.get_child_value(params, 1);
    int matches = strcmp(g_bl_portal.get_string(key, NULL), BL_KEY) == 0;
    g_bl_portal.variant_unref(key);
    if (!matches) return;
    void *value = g_bl_portal.get_child_value(params, 2);
    char *layout = portal_variant_to_string(&g_bl_portal, value);
    g_bl_portal.variant_unref(value);
    if (layout) {
        notify_button_layout(layout);
        free(layout);
    }
}

/**
 * Monitoring thread: creates a GSettings object with a private GMainContext
 * and iterates the context to receive change signals.
 */
static void *button_layout_monitor_thread(void *arg) {
    (void)arg;

    g_bl_libgio = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!g_bl_libgio) return NULL;

    /* Resolve all needed GLib/GIO symbols */
    fn_schema_source_get_default gssg =
        (fn_schema_source_get_default)dlsym(g_bl_libgio, "g_settings_schema_source_get_default");
    fn_schema_source_lookup gssl =
        (fn_schema_source_lookup)dlsym(g_bl_libgio, "g_settings_schema_source_lookup");
    fn_settings_new gsn_simple =
        (fn_settings_new)dlsym(g_bl_libgio, "g_settings_new");

    fn_object_unref gou =
        (fn_object_unref)dlsym(g_bl_libgio, "g_object_unref");
    g_bl_get_string =
        (fn_settings_get_string)dlsym(g_bl_libgio, "g_settings_get_string");
    g_bl_gfree =
        (fn_g_free)dlsym(g_bl_libgio, "g_free");

    fn_g_signal_connect_data g_signal =
        (fn_g_signal_connect_data)dlsym(g_bl_libgio, "g_signal_connect_data");

    fn_g_main_context_new ctx_new =
        (fn_g_main_context_new)dlsym(g_bl_libgio, "g_main_context_new");
    fn_g_main_context_unref ctx_unref =
        (fn_g_main_context_unref)dlsym(g_bl_libgio, "g_main_context_unref");
    fn_g_main_context_iteration ctx_iter =
        (fn_g_main_context_iteration)dlsym(g_bl_libgio, "g_main_context_iteration");
    fn_g_main_context_push_thread_default ctx_push =
        (fn_g_main_context_push_thread_default)dlsym(g_bl_libgio, "g_main_context_push_thread_default");
    fn_g_main_context_pop_thread_default ctx_pop =
        (fn_g_main_context_pop_thread_default)dlsym(g_bl_libgio, "g_main_context_pop_thread_default");

    if (!gssg || !gssl || !gsn_simple || !gou || !g_bl_get_string ||
        !g_bl_gfree || !g_signal || !ctx_new || !ctx_unref ||
        !ctx_iter || !ctx_push || !ctx_pop) {
        dlclose(g_bl_libgio);
        g_bl_libgio = NULL;
        return NULL;
    }

    int use_portal = is_inside_flatpak();
    if (use_portal) {
        if (!resolve_portal_api(g_bl_libgio, &g_bl_portal)) goto cleanup;
    } else {
        /* Verify the schema exists */
        void *source = gssg();
        if (!source) goto cleanup;
        void *schema = gssl(source, BL_NAMESPACE, 1);
        if (!schema) goto cleanup;
    }

    /* Create a private GMainContext for this thread */
    g_bl_context = ctx_new();
    if (!g_bl_context) goto cleanup;
    ctx_push(g_bl_context);

    if (use_portal) {
        /* Signal subscriptions dispatch on the thread-default context they are made from.
           g_bl_settings holds the bus connection, released in cleanup like the GSettings. */
        g_bl_settings = g_bl_portal.bus_get_sync(2 /* G_BUS_TYPE_SESSION */, NULL, NULL);
        if (!g_bl_settings) {
            ctx_pop(g_bl_context);
            goto cleanup;
        }
        g_bl_portal.signal_subscribe(g_bl_settings, "org.freedesktop.portal.Desktop",
            "org.freedesktop.portal.Settings", "SettingChanged", "/org/freedesktop/portal/desktop",
            BL_NAMESPACE, 0, (void*)on_portal_setting_changed, NULL, NULL);
    } else {
        /* Create GSettings — it binds to the thread-default context */
        g_bl_settings = gsn_simple(BL_NAMESPACE);
        if (!g_bl_settings) {
            ctx_pop(g_bl_context);
            goto cleanup;
        }

        /* Connect to the "changed::button-layout" signal */
        g_signal(g_bl_settings, "changed::button-layout",
                 (void*)on_button_layout_changed, NULL, NULL, 0);
    }

    /* Dispatch loop — blocks until events arrive or timeout */
    while (g_bl_running) {
        /* blocking=TRUE: sleeps until there's something to dispatch.
           GMainContext wakes up on GSettings D-Bus signals. */
        ctx_iter(g_bl_context, 1 /* blocking */);
    }

    ctx_pop(g_bl_context);

cleanup:
    if (g_bl_settings && gou) {
        gou(g_bl_settings);
        g_bl_settings = NULL;
    }
    if (g_bl_context && ctx_unref) {
        ctx_unref(g_bl_context);
        g_bl_context = NULL;
    }
    if (g_bl_libgio) {
        dlclose(g_bl_libgio);
        g_bl_libgio = NULL;
    }
    g_bl_get_string = NULL;
    g_bl_gfree = NULL;
    return NULL;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_NativeLayoutDirectionBridge_nativeStartButtonLayoutObserving(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (g_bl_running) return;

    g_bl_running = 1;
    pthread_create(&g_bl_thread, NULL, button_layout_monitor_thread, NULL);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_NativeLayoutDirectionBridge_nativeStopButtonLayoutObserving(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (!g_bl_running) return;

    g_bl_running = 0;

    /* Wake up the blocking g_main_context_iteration by calling wakeup.
       We need libgio still loaded for this — the thread will dlclose it. */
    if (g_bl_libgio && g_bl_context) {
        typedef void (*fn_g_main_context_wakeup)(void*);
        fn_g_main_context_wakeup ctx_wakeup =
            (fn_g_main_context_wakeup)dlsym(g_bl_libgio, "g_main_context_wakeup");
        if (ctx_wakeup) {
            ctx_wakeup(g_bl_context);
        }
    }

    pthread_join(g_bl_thread, NULL);
}
