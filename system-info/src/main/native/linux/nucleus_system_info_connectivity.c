// Network connectivity, metered status and change monitoring on Linux.
//
// Two D-Bus backends, tried in order (the same chain GLib's GNetworkMonitor
// and Qt's QNetworkInformation use, portal first so sandboxed apps work):
//
// 1. xdg-desktop-portal NetworkMonitor (session bus)
//      org.freedesktop.portal.Desktop /org/freedesktop/portal/desktop
//      GetStatus() -> (a{sv})            v3: available b, metered b, connectivity u
//      GetAvailable/GetMetered/GetConnectivity   v2 fallback
//      signal changed()                  no payload since v2 ((b) in v1): re-query
//
// 2. NetworkManager (system bus)
//      org.freedesktop.NetworkManager /org/freedesktop/NetworkManager
//      Properties.GetAll -> State u, Connectivity u, Metered u
//      signal org.freedesktop.DBus.Properties.PropertiesChanged: re-query
//
// The monitor runs on its own thread with its own GMainContext, so it never
// touches the default context GTK (and the Tao backend) iterate. Both the
// portal and NetworkManager emit bursts of identical notifications, so the
// monitor only calls back into Java when the state actually changed.
//
// All external libraries are loaded at runtime via dlopen (no hard deps).

#include "nucleus_system_info_common.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <dlfcn.h>
#include <pthread.h>

// ---------------------------------------------------------------------------
// Minimal GLib / GIO declarations
// ---------------------------------------------------------------------------

typedef unsigned int   guint;
typedef unsigned int   guint32;
typedef int            gint;
typedef int            gboolean;
typedef void          *gpointer;

typedef struct _GError {
    guint32 domain;
    gint    code;
    char   *message;
} GError;

typedef struct _GVariant        GVariant;
typedef struct _GVariantType    GVariantType;
typedef struct _GDBusConnection GDBusConnection;
typedef struct _GMainContext    GMainContext;
typedef struct _GMainLoop       GMainLoop;
typedef struct _GSource         GSource;

#define G_BUS_TYPE_SYSTEM 1
#define G_BUS_TYPE_SESSION 2
#define G_DBUS_CALL_FLAGS_NONE 0
#define G_DBUS_SIGNAL_FLAGS_NONE 0
#define CALL_TIMEOUT_MS 2000

typedef void (*GDBusSignalCallback)(GDBusConnection *, const char *, const char *,
                                    const char *, const char *, GVariant *, gpointer);
typedef gboolean (*GSourceFunc)(gpointer);

typedef struct {
    GDBusConnection *(*bus_get_sync)(gint, gpointer, GError **);
    GVariant *(*call_sync)(GDBusConnection *, const char *, const char *, const char *,
                           const char *, GVariant *, const GVariantType *,
                           gint, gint, gpointer, GError **);
    guint (*signal_subscribe)(GDBusConnection *, const char *, const char *, const char *,
                              const char *, const char *, gint, GDBusSignalCallback,
                              gpointer, gpointer);
    void (*signal_unsubscribe)(GDBusConnection *, guint);
    GVariant *(*variant_new)(const char *, ...);
    void (*variant_get)(GVariant *, const char *, ...);
    gboolean (*variant_lookup)(GVariant *, const char *, const char *, ...);
    GVariant *(*variant_get_child_value)(GVariant *, gint);
    void (*variant_unref)(GVariant *);
    const GVariantType *(*variant_type_checked)(const char *);
    void (*object_unref)(gpointer);
    void (*error_free)(GError *);
    GMainContext *(*main_context_new)(void);
    void (*main_context_unref)(GMainContext *);
    void (*main_context_push_thread_default)(GMainContext *);
    void (*main_context_pop_thread_default)(GMainContext *);
    GMainLoop *(*main_loop_new)(GMainContext *, gboolean);
    void (*main_loop_run)(GMainLoop *);
    void (*main_loop_quit)(GMainLoop *);
    void (*main_loop_unref)(GMainLoop *);
    GSource *(*idle_source_new)(void);
    void (*source_set_callback)(GSource *, GSourceFunc, gpointer, gpointer);
    guint (*source_attach)(GSource *, GMainContext *);
    void (*source_unref)(GSource *);
} Gio;

static Gio gio;
static int gio_ok;
static pthread_once_t gio_once = PTHREAD_ONCE_INIT;

#define LOAD(field, symbol) \
    if (!(*(void **)&gio.field = dlsym(lib, symbol))) return;

// libgio is loaded once and never unloaded: GDBus keeps a worker thread
// running inside it for as long as a bus connection is alive.
static void loadGio(void) {
    void *lib = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!lib) return;
    LOAD(bus_get_sync, "g_bus_get_sync")
    LOAD(call_sync, "g_dbus_connection_call_sync")
    LOAD(signal_subscribe, "g_dbus_connection_signal_subscribe")
    LOAD(signal_unsubscribe, "g_dbus_connection_signal_unsubscribe")
    LOAD(variant_new, "g_variant_new")
    LOAD(variant_get, "g_variant_get")
    LOAD(variant_lookup, "g_variant_lookup")
    LOAD(variant_get_child_value, "g_variant_get_child_value")
    LOAD(variant_unref, "g_variant_unref")
    LOAD(variant_type_checked, "g_variant_type_checked_")
    LOAD(object_unref, "g_object_unref")
    LOAD(error_free, "g_error_free")
    LOAD(main_context_new, "g_main_context_new")
    LOAD(main_context_unref, "g_main_context_unref")
    LOAD(main_context_push_thread_default, "g_main_context_push_thread_default")
    LOAD(main_context_pop_thread_default, "g_main_context_pop_thread_default")
    LOAD(main_loop_new, "g_main_loop_new")
    LOAD(main_loop_run, "g_main_loop_run")
    LOAD(main_loop_quit, "g_main_loop_quit")
    LOAD(main_loop_unref, "g_main_loop_unref")
    LOAD(idle_source_new, "g_idle_source_new")
    LOAD(source_set_callback, "g_source_set_callback")
    LOAD(source_attach, "g_source_attach")
    LOAD(source_unref, "g_source_unref")
    gio_ok = 1;
}

static int ensureGio(void) {
    pthread_once(&gio_once, loadGio);
    return gio_ok;
}

// ---------------------------------------------------------------------------
// State model, shared with Kotlin (LinuxSystemInfo maps these codes)
// ---------------------------------------------------------------------------

#define METERED_UNKNOWN   0
#define METERED_NO        1
#define METERED_YES       2

#define LEVEL_UNKNOWN     0
#define LEVEL_NONE        1
#define LEVEL_LOCAL       2
#define LEVEL_LIMITED     3
#define LEVEL_PORTAL      4
#define LEVEL_FULL        5

typedef struct {
    int available;
    int metered;
    int level;
} NetState;

static const NetState UNKNOWN_STATE = { 0, METERED_UNKNOWN, LEVEL_UNKNOWN };

// Calls a no-argument (or NULL-parameter) method; returns NULL on any error.
static GVariant *call(GDBusConnection *conn, const char *name, const char *path,
                      const char *iface, const char *method, GVariant *params,
                      const char *reply_type) {
    GError *error = NULL;
    GVariant *result = gio.call_sync(conn, name, path, iface, method, params,
                                     gio.variant_type_checked(reply_type),
                                     G_DBUS_CALL_FLAGS_NONE, CALL_TIMEOUT_MS, NULL, &error);
    if (!result && error) gio.error_free(error);
    return result;
}

static int callBool(GDBusConnection *conn, const char *name, const char *path,
                    const char *iface, const char *method, int *out) {
    GVariant *result = call(conn, name, path, iface, method, NULL, "(b)");
    if (!result) return 0;
    gboolean value = 0;
    gio.variant_get(result, "(b)", &value);
    gio.variant_unref(result);
    *out = value ? 1 : 0;
    return 1;
}

// ---------------------------------------------------------------------------
// Backend 1: xdg-desktop-portal NetworkMonitor
// ---------------------------------------------------------------------------

#define PORTAL_BUS   "org.freedesktop.portal.Desktop"
#define PORTAL_PATH  "/org/freedesktop/portal/desktop"
#define PORTAL_IFACE "org.freedesktop.portal.NetworkMonitor"

// Portal connectivity: 1 local, 2 limited, 3 captive portal, 4 full.
static int portalLevel(guint32 connectivity, int available) {
    if (connectivity >= 1 && connectivity <= 4) return (int)connectivity + 1;
    return available ? LEVEL_UNKNOWN : LEVEL_NONE;
}

static int queryPortal(GDBusConnection *conn, NetState *out) {
    gboolean available = 0, metered = 0;
    guint32 connectivity = 0;

    // v3: a single round-trip.
    GVariant *status = call(conn, PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE,
                            "GetStatus", NULL, "(a{sv})");
    if (status) {
        GVariant *dict = gio.variant_get_child_value(status, 0);
        int found = gio.variant_lookup(dict, "available", "b", &available);
        gio.variant_lookup(dict, "metered", "b", &metered);
        gio.variant_lookup(dict, "connectivity", "u", &connectivity);
        gio.variant_unref(dict);
        gio.variant_unref(status);
        if (!found) return 0;
    } else {
        // v2: one call per property. GetAvailable failing means the portal is
        // absent, too old, or denies network access to this (sandboxed) app.
        int a = 0, m = 0;
        if (!callBool(conn, PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE, "GetAvailable", &a)) return 0;
        available = a;
        if (callBool(conn, PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE, "GetMetered", &m)) {
            metered = m;
        }
        GVariant *c = call(conn, PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE,
                           "GetConnectivity", NULL, "(u)");
        if (c) {
            gio.variant_get(c, "(u)", &connectivity);
            gio.variant_unref(c);
        }
    }

    out->available = available ? 1 : 0;
    out->metered = metered ? METERED_YES : METERED_NO;
    out->level = portalLevel(connectivity, available);
    return 1;
}

// ---------------------------------------------------------------------------
// Backend 2: NetworkManager
// ---------------------------------------------------------------------------

#define NM_BUS   "org.freedesktop.NetworkManager"
#define NM_PATH  "/org/freedesktop/NetworkManager"
#define PROPS_IFACE "org.freedesktop.DBus.Properties"

#define NM_STATE_CONNECTED_LOCAL 50
#define NM_STATE_CONNECTED_SITE  60

static int queryNetworkManager(GDBusConnection *conn, NetState *out) {
    GVariant *result = call(conn, NM_BUS, NM_PATH, PROPS_IFACE, "GetAll",
                            gio.variant_new("(s)", NM_BUS), "(a{sv})");
    if (!result) return 0;

    guint32 state = 0, connectivity = 0, metered = 0;
    GVariant *dict = gio.variant_get_child_value(result, 0);
    int found = gio.variant_lookup(dict, "State", "u", &state);
    gio.variant_lookup(dict, "Connectivity", "u", &connectivity);
    gio.variant_lookup(dict, "Metered", "u", &metered);
    gio.variant_unref(dict);
    gio.variant_unref(result);
    if (!found) return 0;

    // A default route exists from CONNECTED_SITE on, matching the portal's "available".
    out->available = state >= NM_STATE_CONNECTED_SITE;

    // NMMetered: 0 unknown, 1 yes, 2 no, 3 guess-yes, 4 guess-no.
    switch (metered) {
        case 1: case 3: out->metered = METERED_YES; break;
        case 2: case 4: out->metered = METERED_NO; break;
        default:        out->metered = METERED_UNKNOWN; break;
    }

    // NMConnectivityState: 0 unknown, 1 none, 2 portal, 3 limited, 4 full.
    switch (connectivity) {
        case 1:  out->level = state >= NM_STATE_CONNECTED_LOCAL ? LEVEL_LOCAL : LEVEL_NONE; break;
        case 2:  out->level = LEVEL_PORTAL; break;
        case 3:  out->level = LEVEL_LIMITED; break;
        case 4:  out->level = LEVEL_FULL; break;
        default: out->level = state < NM_STATE_CONNECTED_LOCAL ? LEVEL_NONE : LEVEL_UNKNOWN; break;
    }
    return 1;
}

// ---------------------------------------------------------------------------
// Backend table
// ---------------------------------------------------------------------------

typedef struct {
    int bus_type;
    const char *name;
    const char *path;
    const char *signal_iface;
    const char *signal_member;
    int (*query)(GDBusConnection *, NetState *);
} Backend;

static const Backend BACKENDS[] = {
    { G_BUS_TYPE_SESSION, PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE, "changed", queryPortal },
    { G_BUS_TYPE_SYSTEM, NM_BUS, NM_PATH, PROPS_IFACE, "PropertiesChanged", queryNetworkManager },
};
#define BACKEND_COUNT ((int)(sizeof(BACKENDS) / sizeof(BACKENDS[0])))

static GDBusConnection *busFor(const Backend *backend) {
    GError *error = NULL;
    GDBusConnection *conn = gio.bus_get_sync(backend->bus_type, NULL, &error);
    if (!conn && error) gio.error_free(error);
    return conn;
}

// Finds the first backend that answers. On success the caller owns *conn_out.
static const Backend *queryFirstBackend(NetState *out, GDBusConnection **conn_out) {
    for (int i = 0; i < BACKEND_COUNT; i++) {
        GDBusConnection *conn = busFor(&BACKENDS[i]);
        if (!conn) continue;
        if (BACKENDS[i].query(conn, out)) {
            *conn_out = conn;
            return &BACKENDS[i];
        }
        gio.object_unref(conn);
    }
    *out = UNKNOWN_STATE;
    return NULL;
}

static jintArray toJava(JNIEnv *env, const NetState *state) {
    jint values[3] = { state->available, state->metered, state->level };
    jintArray array = (*env)->NewIntArray(env, 3);
    if (array) (*env)->SetIntArrayRegion(env, array, 0, 3, values);
    return array;
}

// ---------------------------------------------------------------------------
// JNI: one-shot query -> int[] { available, metered, level }
// ---------------------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeQueryConnectivity(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    NetState state = UNKNOWN_STATE;
    if (ensureGio()) {
        GDBusConnection *conn = NULL;
        if (queryFirstBackend(&state, &conn)) gio.object_unref(conn);
    }
    return toJava(env, &state);
}

// ---------------------------------------------------------------------------
// Change monitor
// ---------------------------------------------------------------------------

typedef struct {
    JavaVM *jvm;
    jclass bridge;            // global ref
    jmethodID callback;       // static void onConnectivityChanged(boolean, int, int)
    GMainContext *context;
    GMainLoop *loop;
    pthread_t thread;
    JNIEnv *env;              // the monitor thread's env, only used on that thread
    const Backend *backend;
    GDBusConnection *conn;
    NetState last;
    int has_last;
} Monitor;

static Monitor *monitor;      // guarded by the Kotlin side (start/stop are serialized)

static void publish(Monitor *m, const NetState *state) {
    if (m->has_last && memcmp(&m->last, state, sizeof(NetState)) == 0) return;
    m->last = *state;
    m->has_last = 1;
    (*m->env)->CallStaticVoidMethod(m->env, m->bridge, m->callback,
                                    (jboolean)(state->available ? JNI_TRUE : JNI_FALSE),
                                    (jint)state->metered, (jint)state->level);
    nucleus_jni_clear_exception(m->env);
}

static void requery(Monitor *m) {
    NetState state;
    // A failed query mid-stream (portal restarting, NM going away) keeps the
    // last published state rather than reporting a spurious disconnection.
    if (m->backend->query(m->conn, &state)) publish(m, &state);
}

static void onSignal(GDBusConnection *conn, const char *sender, const char *path,
                     const char *iface, const char *member, GVariant *params, gpointer data) {
    (void)conn; (void)sender; (void)path; (void)iface; (void)member; (void)params;
    requery((Monitor *)data);
}

static gboolean quitLoop(gpointer data) {
    gio.main_loop_quit((GMainLoop *)data);
    return 0; // G_SOURCE_REMOVE
}

static void *monitorThread(void *arg) {
    Monitor *m = (Monitor *)arg;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "nucleus-connectivity-monitor", NULL };
    if ((*m->jvm)->AttachCurrentThreadAsDaemon(m->jvm, (void **)&m->env, &args) != JNI_OK) {
        return NULL;
    }

    // Signal callbacks are dispatched to the thread-default context at subscription time.
    gio.main_context_push_thread_default(m->context);

    NetState state;
    m->backend = queryFirstBackend(&state, &m->conn);
    if (!m->backend) {
        // No backend: report "unknown" once, nothing to watch.
        publish(m, &state);
    } else {
        guint changed = gio.signal_subscribe(m->conn, m->backend->name,
            m->backend->signal_iface, m->backend->signal_member, m->backend->path,
            NULL, G_DBUS_SIGNAL_FLAGS_NONE, onSignal, m, NULL);
        // Re-query when the service restarts (portal crash, NM restart).
        guint owner = gio.signal_subscribe(m->conn, "org.freedesktop.DBus",
            "org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus",
            m->backend->name, G_DBUS_SIGNAL_FLAGS_NONE, onSignal, m, NULL);

        // Query again after subscribing so no change between the two is lost.
        requery(m);
        gio.main_loop_run(m->loop);

        gio.signal_unsubscribe(m->conn, changed);
        gio.signal_unsubscribe(m->conn, owner);
        gio.object_unref(m->conn);
    }

    gio.main_context_pop_thread_default(m->context);
    (*m->jvm)->DetachCurrentThread(m->jvm);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeStartConnectivityMonitor(
    JNIEnv *env, jclass clazz) {
    if (monitor) return JNI_TRUE;
    if (!ensureGio()) return JNI_FALSE;

    Monitor *m = calloc(1, sizeof(Monitor));
    if (!m) return JNI_FALSE;
    (*env)->GetJavaVM(env, &m->jvm);
    m->callback = (*env)->GetStaticMethodID(env, clazz, "onConnectivityChanged", "(ZII)V");
    if (!m->callback) {
        nucleus_jni_clear_exception(env);
        free(m);
        return JNI_FALSE;
    }
    m->bridge = (*env)->NewGlobalRef(env, clazz);
    // Created here rather than on the thread so stop() can always post a quit to it.
    m->context = gio.main_context_new();
    m->loop = gio.main_loop_new(m->context, 0);

    if (pthread_create(&m->thread, NULL, monitorThread, m) != 0) {
        gio.main_loop_unref(m->loop);
        gio.main_context_unref(m->context);
        (*env)->DeleteGlobalRef(env, m->bridge);
        free(m);
        return JNI_FALSE;
    }
    monitor = m;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeStopConnectivityMonitor(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    Monitor *m = monitor;
    if (!m) return;
    monitor = NULL;

    // Quitting through an idle source on the loop's own context cannot be lost,
    // unlike g_main_loop_quit() racing a g_main_loop_run() that has not started yet.
    GSource *idle = gio.idle_source_new();
    gio.source_set_callback(idle, quitLoop, m->loop, NULL);
    gio.source_attach(idle, m->context);
    gio.source_unref(idle);

    pthread_join(m->thread, NULL);
    gio.main_loop_unref(m->loop);
    gio.main_context_unref(m->context);
    (*env)->DeleteGlobalRef(env, m->bridge);
    free(m);
}
