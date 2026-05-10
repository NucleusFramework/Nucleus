/**
 * JNI bridge for Unity Launcher API + Dbusmenu via D-Bus (GIO/GDBus).
 *
 * Implements:
 *   com.canonical.Unity.LauncherEntry  — Update signal, Query method
 *   com.canonical.dbusmenu             — GetLayout, GetGroupProperties,
 *                                        GetProperty, Event, AboutToShow,
 *                                        LayoutUpdated signal
 *
 * Specification:
 *   https://wiki.ubuntu.com/Unity/LauncherAPI
 *   https://github.com/AyatanaIndicators/libdbusmenu (canonical dbusmenu)
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include <gio/gio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>

/* ---- D-Bus constants -------------------------------------------------- */

#define LAUNCHER_INTERFACE "com.canonical.Unity.LauncherEntry"
#define LAUNCHER_OBJECT    "/com/canonical/Unity/LauncherEntry"
#define DBUSMENU_INTERFACE "com.canonical.dbusmenu"

#define BOOL_NOT_SET (-1)
#define MAX_MENU_ITEMS 256

/* ---- LauncherEntry introspection XML ---------------------------------- */

static const gchar launcher_introspection_xml[] =
    "<node>"
    "  <interface name='com.canonical.Unity.LauncherEntry'>"
    "    <method name='Query'>"
    "      <arg type='s' name='app_uri' direction='out'/>"
    "      <arg type='a{sv}' name='properties' direction='out'/>"
    "    </method>"
    "    <signal name='Update'>"
    "      <arg type='s' name='app_uri'/>"
    "      <arg type='a{sv}' name='properties'/>"
    "    </signal>"
    "  </interface>"
    "</node>";

/* ---- Dbusmenu introspection XML --------------------------------------- */

static const gchar dbusmenu_introspection_xml[] =
    "<node>"
    "  <interface name='com.canonical.dbusmenu'>"
    "    <method name='GetLayout'>"
    "      <arg type='i' name='parentId' direction='in'/>"
    "      <arg type='i' name='recursionDepth' direction='in'/>"
    "      <arg type='as' name='propertyNames' direction='in'/>"
    "      <arg type='u' name='revision' direction='out'/>"
    "      <arg type='(ia{sv}av)' name='layout' direction='out'/>"
    "    </method>"
    "    <method name='GetGroupProperties'>"
    "      <arg type='ai' name='ids' direction='in'/>"
    "      <arg type='as' name='propertyNames' direction='in'/>"
    "      <arg type='a(ia{sv})' name='properties' direction='out'/>"
    "    </method>"
    "    <method name='GetProperty'>"
    "      <arg type='i' name='id' direction='in'/>"
    "      <arg type='s' name='name' direction='in'/>"
    "      <arg type='v' name='value' direction='out'/>"
    "    </method>"
    "    <method name='Event'>"
    "      <arg type='i' name='id' direction='in'/>"
    "      <arg type='s' name='eventId' direction='in'/>"
    "      <arg type='v' name='data' direction='in'/>"
    "      <arg type='u' name='timestamp' direction='in'/>"
    "    </method>"
    "    <method name='AboutToShow'>"
    "      <arg type='i' name='id' direction='in'/>"
    "      <arg type='b' name='needUpdate' direction='out'/>"
    "    </method>"
    "    <method name='EventGroup'>"
    "      <arg type='a(isvu)' name='events' direction='in'/>"
    "      <arg type='ai' name='idErrors' direction='out'/>"
    "    </method>"
    "    <method name='AboutToShowGroup'>"
    "      <arg type='ai' name='ids' direction='in'/>"
    "      <arg type='ai' name='updatesNeeded' direction='out'/>"
    "      <arg type='ai' name='idErrors' direction='out'/>"
    "    </method>"
    "    <signal name='LayoutUpdated'>"
    "      <arg type='u' name='revision'/>"
    "      <arg type='i' name='parent'/>"
    "    </signal>"
    "    <signal name='ItemsPropertiesUpdated'>"
    "      <arg type='a(ia{sv})' name='updatedProps'/>"
    "      <arg type='a(ias)' name='removedProps'/>"
    "    </signal>"
    "    <property name='Version' type='u' access='read'/>"
    "    <property name='Status' type='s' access='read'/>"
    "  </interface>"
    "</node>";

/* ---- Menu item structure ---------------------------------------------- */

typedef struct {
    gint32 id;
    gint32 parent_id;
    char  *label;
    char  *icon_name;
    char  *type;       /* "standard" or "separator" */
    int    enabled;
    int    visible;
    char  *toggle_type; /* "", "checkmark", "radio" */
    gint32 toggle_state;
    char  *disposition; /* "normal", "informational", "warning", "alert" */
} MenuItem;

/* ---- Dbusmenu server instance ----------------------------------------- */

typedef struct {
    char         *object_path;
    guint         registration_id;
    GDBusNodeInfo *node_info;
    MenuItem      items[MAX_MENU_ITEMS];
    int           item_count;
    guint32       revision;
    GMainLoop    *loop;
    GMainContext *ctx;
    pthread_t     thread;
    volatile int  running;
    /* Synchronization for thread-side registration */
    pthread_cond_t ready_cond;
    volatile int   ready;       /* 1 = registered OK, -1 = failed */
} DbusmenuServer;

/* ---- Global state ----------------------------------------------------- */

static JavaVM *g_jvm = NULL;
static GDBusConnection *g_conn = NULL;

/* LauncherEntry state */
static pthread_mutex_t g_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static guint           g_launcher_reg_id = 0;
static GDBusNodeInfo  *g_launcher_node_info = NULL;
static char   *g_app_uri          = NULL;
static int     g_has_count        = 0;
static gint64  g_count            = 0;
static int     g_count_visible    = BOOL_NOT_SET;
static int     g_has_progress     = 0;
static gdouble g_progress         = 0.0;
static int     g_progress_visible = BOOL_NOT_SET;
static int     g_urgent           = BOOL_NOT_SET;
static char   *g_quicklist        = NULL;
static int     g_updating         = BOOL_NOT_SET;
static GMainLoop *g_query_loop    = NULL;
static pthread_t  g_query_thread;
static volatile int g_query_running = 0;

/* Dbusmenu servers (up to 4 concurrent) */
#define MAX_SERVERS 4
static DbusmenuServer g_servers[MAX_SERVERS];
static int g_server_count = 0;

/* Cached JNI references for menu event callbacks */
static jclass    g_bridge_class = NULL;
static jmethodID g_on_event_method = NULL;

/* ---- JNI lifecycle ---------------------------------------------------- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

/* ---- JNI thread helpers ----------------------------------------------- */

static JNIEnv *get_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    jint res = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) == JNI_OK) {
            *attached = 1;
        } else {
            return NULL;
        }
    }
    return env;
}

static void release_env(int attached) {
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* ---- D-Bus connection ------------------------------------------------- */

static GDBusConnection *get_connection(void) {
    if (g_conn != NULL && !g_dbus_connection_is_closed(g_conn)) return g_conn;
    if (g_conn != NULL) { g_object_unref(g_conn); g_conn = NULL; }
    GError *error = NULL;
    g_conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (error) { g_error_free(error); g_conn = NULL; }
    return g_conn;
}

/* ---- JNI callback cache ----------------------------------------------- */

static int ensure_callback_ids(JNIEnv *env) {
    if (g_bridge_class != NULL) return 1;
    jclass cls = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/launcher/linux/NativeLinuxLauncherBridge");
    if (!cls) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); return 0; }
    g_bridge_class = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);
    g_on_event_method = (*env)->GetStaticMethodID(env, g_bridge_class,
        "onMenuItemEvent", "(Ljava/lang/String;I)V");
    if (!g_on_event_method) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
        return 0;
    }
    return 1;
}

/* ---- Helper: build LauncherEntry properties variant ------------------- */

static GVariant *build_properties_variant(
    int has_count, gint64 count, int count_visible,
    int has_progress, gdouble progress, int progress_visible,
    int urgent, const char *quicklist, int updating)
{
    GVariantBuilder b;
    g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
    if (has_count)
        g_variant_builder_add(&b, "{sv}", "count", g_variant_new_int64(count));
    if (count_visible != BOOL_NOT_SET)
        g_variant_builder_add(&b, "{sv}", "count-visible", g_variant_new_boolean(count_visible));
    if (has_progress)
        g_variant_builder_add(&b, "{sv}", "progress", g_variant_new_double(progress));
    if (progress_visible != BOOL_NOT_SET)
        g_variant_builder_add(&b, "{sv}", "progress-visible", g_variant_new_boolean(progress_visible));
    if (urgent != BOOL_NOT_SET)
        g_variant_builder_add(&b, "{sv}", "urgent", g_variant_new_boolean(urgent));
    if (quicklist)
        g_variant_builder_add(&b, "{sv}", "quicklist", g_variant_new_string(quicklist));
    if (updating != BOOL_NOT_SET)
        g_variant_builder_add(&b, "{sv}", "updating", g_variant_new_boolean(updating));
    return g_variant_builder_end(&b);
}

/* ===================================================================== */
/* LauncherEntry: Update signal                                          */
/* ===================================================================== */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeUpdate(
    JNIEnv *env, jclass clazz,
    jstring j_app_uri, jboolean has_count, jlong count, jint count_visible,
    jboolean has_progress, jdouble progress, jint progress_visible,
    jint urgent, jstring j_quicklist, jint updating)
{
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return JNI_FALSE;

    const char *app_uri = (*env)->GetStringUTFChars(env, j_app_uri, NULL);
    if (!app_uri) return JNI_FALSE;
    const char *quicklist = j_quicklist ? (*env)->GetStringUTFChars(env, j_quicklist, NULL) : NULL;

    GVariant *props = build_properties_variant(
        has_count, (gint64)count, (int)count_visible,
        has_progress, (gdouble)progress, (int)progress_visible,
        (int)urgent, quicklist, (int)updating);
    GVariant *params = g_variant_new("(s@a{sv})", app_uri, props);

    GError *error = NULL;
    g_dbus_connection_emit_signal(conn, NULL, LAUNCHER_OBJECT, LAUNCHER_INTERFACE,
        "Update", params, &error);

    /* Update internal state */
    pthread_mutex_lock(&g_state_mutex);
    g_free(g_app_uri); g_app_uri = g_strdup(app_uri);
    if (has_count) { g_has_count = 1; g_count = (gint64)count; }
    if (count_visible != BOOL_NOT_SET) g_count_visible = (int)count_visible;
    if (has_progress) { g_has_progress = 1; g_progress = (gdouble)progress; }
    if (progress_visible != BOOL_NOT_SET) g_progress_visible = (int)progress_visible;
    if (urgent != BOOL_NOT_SET) g_urgent = (int)urgent;
    if (quicklist) { g_free(g_quicklist); g_quicklist = g_strdup(quicklist); }
    if (updating != BOOL_NOT_SET) g_updating = (int)updating;
    pthread_mutex_unlock(&g_state_mutex);

    (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);
    if (j_quicklist && quicklist) (*env)->ReleaseStringUTFChars(env, j_quicklist, quicklist);
    if (error) { g_error_free(error); return JNI_FALSE; }
    g_dbus_connection_flush_sync(conn, NULL, NULL);
    return JNI_TRUE;
}

/* ===================================================================== */
/* LauncherEntry: Query handler                                          */
/* ===================================================================== */

static void launcher_handle_method(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *method, GVariant *params,
    GDBusMethodInvocation *invocation, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)params; (void)user_data;
    if (g_strcmp0(method, "Query") != 0) {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR,
            G_DBUS_ERROR_UNKNOWN_METHOD, "Unknown method: %s", method);
        return;
    }
    pthread_mutex_lock(&g_state_mutex);
    GVariant *props = build_properties_variant(
        g_has_count, g_count, g_count_visible, g_has_progress, g_progress,
        g_progress_visible, g_urgent, g_quicklist, g_updating);
    GVariant *result = g_variant_new("(s@a{sv})", g_app_uri ? g_app_uri : "", props);
    pthread_mutex_unlock(&g_state_mutex);
    g_dbus_method_invocation_return_value(invocation, result);
}

static const GDBusInterfaceVTable launcher_vtable = { launcher_handle_method, NULL, NULL, {0} };

static pthread_cond_t g_query_ready_cond = PTHREAD_COND_INITIALIZER;
static volatile int   g_query_ready = 0;

static void *query_thread_func(void *arg) {
    (void)arg;
    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);

    /* Register the LauncherEntry object on THIS thread's context */
    GDBusConnection *conn = get_connection();
    pthread_mutex_lock(&g_state_mutex);
    if (conn && g_launcher_node_info && g_launcher_reg_id == 0) {
        GError *err = NULL;
        g_launcher_reg_id = g_dbus_connection_register_object(conn, LAUNCHER_OBJECT,
            g_launcher_node_info->interfaces[0], &launcher_vtable, NULL, NULL, &err);
        if (err) { g_error_free(err); g_launcher_reg_id = 0; }
    }

    GMainLoop *loop = g_main_loop_new(ctx, FALSE);
    g_query_loop = loop;
    g_query_ready = 1;
    pthread_cond_broadcast(&g_query_ready_cond);
    pthread_mutex_unlock(&g_state_mutex);

    g_main_loop_run(loop);

    pthread_mutex_lock(&g_state_mutex);
    g_query_loop = NULL; g_query_running = 0;
    pthread_mutex_unlock(&g_state_mutex);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(ctx);
    g_main_context_unref(ctx);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeRegisterQueryHandler(
    JNIEnv *env, jclass clazz, jstring j_app_uri)
{
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return JNI_FALSE;
    const char *app_uri = (*env)->GetStringUTFChars(env, j_app_uri, NULL);
    if (!app_uri) return JNI_FALSE;

    pthread_mutex_lock(&g_state_mutex);
    g_free(g_app_uri); g_app_uri = g_strdup(app_uri);
    if (!g_launcher_node_info) {
        GError *err = NULL;
        g_launcher_node_info = g_dbus_node_info_new_for_xml(launcher_introspection_xml, &err);
        if (err) { g_error_free(err); pthread_mutex_unlock(&g_state_mutex);
            (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri); return JNI_FALSE; }
    }
    /* Registration happens inside the thread — on its own GMainContext */
    if (!g_query_running) {
        g_query_running = 1;
        g_query_ready = 0;
        if (pthread_create(&g_query_thread, NULL, query_thread_func, NULL) != 0) {
            g_query_running = 0; pthread_mutex_unlock(&g_state_mutex);
            (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri); return JNI_FALSE;
        }
        /* Wait for thread to register the object and start the loop */
        while (!g_query_ready && g_query_running) {
            pthread_cond_wait(&g_query_ready_cond, &g_state_mutex);
        }
    }
    pthread_mutex_unlock(&g_state_mutex);
    (*env)->ReleaseStringUTFChars(env, j_app_uri, app_uri);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeSetState(
    JNIEnv *env, jclass clazz,
    jboolean has_count, jlong count, jint count_visible,
    jboolean has_progress, jdouble progress, jint progress_visible,
    jint urgent, jstring j_quicklist, jint updating)
{
    (void)clazz;
    const char *ql = j_quicklist ? (*env)->GetStringUTFChars(env, j_quicklist, NULL) : NULL;
    pthread_mutex_lock(&g_state_mutex);
    if (has_count) { g_has_count = 1; g_count = (gint64)count; }
    if (count_visible != BOOL_NOT_SET) g_count_visible = (int)count_visible;
    if (has_progress) { g_has_progress = 1; g_progress = (gdouble)progress; }
    if (progress_visible != BOOL_NOT_SET) g_progress_visible = (int)progress_visible;
    if (urgent != BOOL_NOT_SET) g_urgent = (int)urgent;
    if (ql) { g_free(g_quicklist); g_quicklist = g_strdup(ql); }
    if (updating != BOOL_NOT_SET) g_updating = (int)updating;
    pthread_mutex_unlock(&g_state_mutex);
    if (j_quicklist && ql) (*env)->ReleaseStringUTFChars(env, j_quicklist, ql);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeUnregister(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_state_mutex);
    if (g_query_running && g_query_loop) {
        g_main_loop_quit(g_query_loop);
        pthread_mutex_unlock(&g_state_mutex);
        pthread_join(g_query_thread, NULL);
        pthread_mutex_lock(&g_state_mutex);
    }
    if (g_launcher_reg_id && g_conn) {
        g_dbus_connection_unregister_object(g_conn, g_launcher_reg_id);
        g_launcher_reg_id = 0;
    }
    if (g_launcher_node_info) { g_dbus_node_info_unref(g_launcher_node_info); g_launcher_node_info = NULL; }
    g_free(g_app_uri); g_app_uri = NULL;
    g_free(g_quicklist); g_quicklist = NULL;
    g_has_count = 0; g_count = 0; g_count_visible = BOOL_NOT_SET;
    g_has_progress = 0; g_progress = 0.0; g_progress_visible = BOOL_NOT_SET;
    g_urgent = BOOL_NOT_SET; g_updating = BOOL_NOT_SET;
    pthread_mutex_unlock(&g_state_mutex);
}

/* ===================================================================== */
/* Dbusmenu server: helper functions                                     */
/* ===================================================================== */

static DbusmenuServer *find_server(const char *path) {
    for (int i = 0; i < g_server_count; i++) {
        if (g_servers[i].object_path && g_strcmp0(g_servers[i].object_path, path) == 0)
            return &g_servers[i];
    }
    return NULL;
}

static MenuItem *find_item(DbusmenuServer *srv, gint32 id) {
    for (int i = 0; i < srv->item_count; i++) {
        if (srv->items[i].id == id) return &srv->items[i];
    }
    return NULL;
}

static GVariant *build_item_properties(DbusmenuServer *srv, MenuItem *item) {
    GVariantBuilder b;
    g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
    if (item->label && item->label[0])
        g_variant_builder_add(&b, "{sv}", "label", g_variant_new_string(item->label));
    if (item->icon_name && item->icon_name[0])
        g_variant_builder_add(&b, "{sv}", "icon-name", g_variant_new_string(item->icon_name));
    if (item->type && g_strcmp0(item->type, "standard") != 0)
        g_variant_builder_add(&b, "{sv}", "type", g_variant_new_string(item->type));
    if (!item->enabled)
        g_variant_builder_add(&b, "{sv}", "enabled", g_variant_new_boolean(FALSE));
    if (!item->visible)
        g_variant_builder_add(&b, "{sv}", "visible", g_variant_new_boolean(FALSE));
    if (item->toggle_type && item->toggle_type[0])
        g_variant_builder_add(&b, "{sv}", "toggle-type", g_variant_new_string(item->toggle_type));
    if (item->toggle_state >= 0)
        g_variant_builder_add(&b, "{sv}", "toggle-state", g_variant_new_int32(item->toggle_state));
    if (item->disposition && g_strcmp0(item->disposition, "normal") != 0)
        g_variant_builder_add(&b, "{sv}", "disposition", g_variant_new_string(item->disposition));

    /* Mark items with children as having a submenu */
    for (int i = 0; i < srv->item_count; i++) {
        if (srv->items[i].parent_id == item->id) {
            g_variant_builder_add(&b, "{sv}", "children-display", g_variant_new_string("submenu"));
            break;
        }
    }
    return g_variant_builder_end(&b);
}

/* Recursively build the (ia{sv}av) layout variant for a given parent ID */
static GVariant *build_layout(DbusmenuServer *srv, gint32 parent_id, int depth) {
    MenuItem *item = (parent_id == 0) ? NULL : find_item(srv, parent_id);

    GVariant *props;
    if (item) {
        props = build_item_properties(srv, item);
    } else {
        /* Root node: empty properties */
        GVariantBuilder b;
        g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
        /* Root with children needs children-display */
        g_variant_builder_add(&b, "{sv}", "children-display", g_variant_new_string("submenu"));
        props = g_variant_builder_end(&b);
    }

    /* Build children array */
    GVariantBuilder children;
    g_variant_builder_init(&children, G_VARIANT_TYPE("av"));

    if (depth != 0) {
        for (int i = 0; i < srv->item_count; i++) {
            if (srv->items[i].parent_id == parent_id) {
                GVariant *child = build_layout(srv, srv->items[i].id, depth > 0 ? depth - 1 : -1);
                g_variant_builder_add(&children, "v", child);
            }
        }
    }

    return g_variant_new("(i@a{sv}av)", parent_id, props, &children);
}

/* ===================================================================== */
/* Dbusmenu server: D-Bus method handler                                 */
/* ===================================================================== */

static void dbusmenu_handle_method(
    GDBusConnection *conn, const gchar *sender, const gchar *object_path,
    const gchar *iface, const gchar *method, GVariant *params,
    GDBusMethodInvocation *invocation, gpointer user_data)
{
    (void)conn; (void)sender; (void)iface;
    DbusmenuServer *srv = (DbusmenuServer *)user_data;

    if (!srv) {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR,
            G_DBUS_ERROR_FAILED, "Server not found for %s", object_path);
        return;
    }

    if (g_strcmp0(method, "GetLayout") == 0) {
        gint32 parent_id = 0;
        gint32 depth = -1;
        GVariant *prop_names = NULL;
        g_variant_get(params, "(ii@as)", &parent_id, &depth, &prop_names);
        if (prop_names) g_variant_unref(prop_names);

        pthread_mutex_lock(&g_state_mutex);
        GVariant *layout = build_layout(srv, parent_id, depth);
        guint32 rev = srv->revision;
        pthread_mutex_unlock(&g_state_mutex);

        g_dbus_method_invocation_return_value(invocation,
            g_variant_new("(u@(ia{sv}av))", rev, layout));

    } else if (g_strcmp0(method, "GetGroupProperties") == 0) {
        GVariant *ids_var = NULL;
        GVariant *prop_names = NULL;
        g_variant_get(params, "(@ai@as)", &ids_var, &prop_names);
        if (prop_names) g_variant_unref(prop_names);

        GVariantBuilder result;
        g_variant_builder_init(&result, G_VARIANT_TYPE("a(ia{sv})"));

        pthread_mutex_lock(&g_state_mutex);
        GVariantIter iter;
        g_variant_iter_init(&iter, ids_var);
        gint32 id;
        while (g_variant_iter_next(&iter, "i", &id)) {
            MenuItem *item = find_item(srv, id);
            if (item) {
                GVariant *props = build_item_properties(srv, item);
                g_variant_builder_add(&result, "(i@a{sv})", id, props);
            }
        }
        pthread_mutex_unlock(&g_state_mutex);

        g_variant_unref(ids_var);
        g_dbus_method_invocation_return_value(invocation,
            g_variant_new("(@a(ia{sv}))", g_variant_builder_end(&result)));

    } else if (g_strcmp0(method, "GetProperty") == 0) {
        gint32 id;
        const gchar *prop_name = NULL;
        g_variant_get(params, "(i&s)", &id, &prop_name);

        pthread_mutex_lock(&g_state_mutex);
        MenuItem *item = find_item(srv, id);
        GVariant *value = NULL;
        if (item) {
            if (g_strcmp0(prop_name, "label") == 0) value = g_variant_new_string(item->label ?: "");
            else if (g_strcmp0(prop_name, "icon-name") == 0) value = g_variant_new_string(item->icon_name ?: "");
            else if (g_strcmp0(prop_name, "type") == 0) value = g_variant_new_string(item->type ?: "standard");
            else if (g_strcmp0(prop_name, "enabled") == 0) value = g_variant_new_boolean(item->enabled);
            else if (g_strcmp0(prop_name, "visible") == 0) value = g_variant_new_boolean(item->visible);
            else if (g_strcmp0(prop_name, "toggle-type") == 0) value = g_variant_new_string(item->toggle_type ?: "");
            else if (g_strcmp0(prop_name, "toggle-state") == 0) value = g_variant_new_int32(item->toggle_state);
            else if (g_strcmp0(prop_name, "disposition") == 0) value = g_variant_new_string(item->disposition ?: "normal");
        }
        pthread_mutex_unlock(&g_state_mutex);

        if (value) {
            g_dbus_method_invocation_return_value(invocation, g_variant_new("(v)", value));
        } else {
            g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR,
                G_DBUS_ERROR_UNKNOWN_PROPERTY, "Property %s not found on item %d", prop_name, id);
        }

    } else if (g_strcmp0(method, "Event") == 0) {
        gint32 id = 0;
        const gchar *event_id = NULL;
        GVariant *data = NULL;
        guint32 timestamp = 0;
        g_variant_get(params, "(i&s@vu)", &id, &event_id, &data, &timestamp);
        if (data) g_variant_unref(data);

        /* Forward all events to Kotlin (clicked, hovered, opened, closed) */
        int attached = 0;
        JNIEnv *env = get_env(&attached);
        if (env && ensure_callback_ids(env)) {
            jstring j_path = (*env)->NewStringUTF(env, srv->object_path);
            (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method,
                j_path, (jint)id);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, j_path);
        }
        release_env(attached);
        g_dbus_method_invocation_return_value(invocation, NULL);

    } else if (g_strcmp0(method, "EventGroup") == 0) {
        /* Batch event: a(isvu) */
        GVariantIter *iter = NULL;
        g_variant_get(params, "(a(isvu))", &iter);

        gint32 id;
        const gchar *event_id;
        GVariant *data;
        guint32 timestamp;

        int attached = 0;
        JNIEnv *env = get_env(&attached);

        while (g_variant_iter_next(iter, "(i&s@vu)", &id, &event_id, &data, &timestamp)) {
            if (data) g_variant_unref(data);

            if (env && ensure_callback_ids(env)) {
                jstring j_path = (*env)->NewStringUTF(env, srv->object_path);
                (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method,
                    j_path, (jint)id);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                (*env)->DeleteLocalRef(env, j_path);
            }
        }
        g_variant_iter_free(iter);
        release_env(attached);

        /* Return empty error array */
        GVariantBuilder errors;
        g_variant_builder_init(&errors, G_VARIANT_TYPE("ai"));
        g_dbus_method_invocation_return_value(invocation,
            g_variant_new("(ai)", &errors));

    } else if (g_strcmp0(method, "AboutToShow") == 0) {
        gint32 about_id = 0;
        g_variant_get(params, "(i)", &about_id);
        g_dbus_method_invocation_return_value(invocation, g_variant_new("(b)", FALSE));

    } else if (g_strcmp0(method, "AboutToShowGroup") == 0) {
        GVariantBuilder updates, errors;
        g_variant_builder_init(&updates, G_VARIANT_TYPE("ai"));
        g_variant_builder_init(&errors, G_VARIANT_TYPE("ai"));
        g_dbus_method_invocation_return_value(invocation,
            g_variant_new("(aiai)", &updates, &errors));

    } else {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR,
            G_DBUS_ERROR_UNKNOWN_METHOD, "Unknown method: %s", method);
    }
}

static GVariant *dbusmenu_get_property(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *prop, GError **error, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)user_data;
    if (g_strcmp0(prop, "Version") == 0) return g_variant_new_uint32(3);
    if (g_strcmp0(prop, "Status") == 0) return g_variant_new_string("normal");
    g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown property: %s", prop);
    return NULL;
}

static const GDBusInterfaceVTable dbusmenu_vtable = {
    dbusmenu_handle_method,
    dbusmenu_get_property,
    NULL,  /* set_property */
    {0}
};

/* Dbusmenu server thread — registers the D-Bus object on its own context */
static void *dbusmenu_thread_func(void *arg) {
    DbusmenuServer *srv = (DbusmenuServer *)arg;
    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);

    /* Register the D-Bus object on THIS thread's context */
    GDBusConnection *conn = get_connection();
    pthread_mutex_lock(&g_state_mutex);
    if (conn && srv->node_info) {
        GError *err = NULL;
        srv->registration_id = g_dbus_connection_register_object(conn,
            srv->object_path, srv->node_info->interfaces[0], &dbusmenu_vtable,
            srv, NULL, &err);
        if (err) {
            g_error_free(err);
            srv->ready = -1;
        } else {
            srv->ready = 1;
        }
    } else {
        srv->ready = -1;
    }

    GMainLoop *loop = g_main_loop_new(ctx, FALSE);
    srv->loop = loop;
    srv->ctx = ctx;
    pthread_cond_broadcast(&srv->ready_cond);
    pthread_mutex_unlock(&g_state_mutex);

    g_main_loop_run(loop);

    pthread_mutex_lock(&g_state_mutex);
    srv->loop = NULL;
    srv->ctx = NULL;
    srv->running = 0;
    pthread_mutex_unlock(&g_state_mutex);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(ctx);
    g_main_context_unref(ctx);
    return NULL;
}

static void free_menu_item(MenuItem *item) {
    g_free(item->label); item->label = NULL;
    g_free(item->icon_name); item->icon_name = NULL;
    g_free(item->type); item->type = NULL;
    g_free(item->toggle_type); item->toggle_type = NULL;
    g_free(item->disposition); item->disposition = NULL;
}

/* ===================================================================== */
/* Dbusmenu: nativeSetMenu                                               */
/* ===================================================================== */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeSetMenu(
    JNIEnv *env, jclass clazz,
    jstring j_object_path,
    jintArray j_ids, jintArray j_parent_ids,
    jobjectArray j_labels, jobjectArray j_icon_names, jobjectArray j_types,
    jbooleanArray j_enabled, jbooleanArray j_visible,
    jobjectArray j_toggle_types, jintArray j_toggle_states,
    jobjectArray j_dispositions)
{
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return JNI_FALSE;

    const char *object_path = (*env)->GetStringUTFChars(env, j_object_path, NULL);
    if (!object_path) return JNI_FALSE;

    jsize count = (*env)->GetArrayLength(env, j_ids);
    if (count > MAX_MENU_ITEMS) count = MAX_MENU_ITEMS;

    jint *ids = (*env)->GetIntArrayElements(env, j_ids, NULL);
    jint *parent_ids = (*env)->GetIntArrayElements(env, j_parent_ids, NULL);
    jboolean *enabled = (*env)->GetBooleanArrayElements(env, j_enabled, NULL);
    jboolean *visible = (*env)->GetBooleanArrayElements(env, j_visible, NULL);
    jint *toggle_states = (*env)->GetIntArrayElements(env, j_toggle_states, NULL);

    pthread_mutex_lock(&g_state_mutex);

    /* Find or create server */
    DbusmenuServer *srv = find_server(object_path);
    int is_new = (srv == NULL);

    if (is_new) {
        if (g_server_count >= MAX_SERVERS) {
            pthread_mutex_unlock(&g_state_mutex);
            (*env)->ReleaseIntArrayElements(env, j_ids, ids, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, j_parent_ids, parent_ids, JNI_ABORT);
            (*env)->ReleaseBooleanArrayElements(env, j_enabled, enabled, JNI_ABORT);
            (*env)->ReleaseBooleanArrayElements(env, j_visible, visible, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, j_toggle_states, toggle_states, JNI_ABORT);
            (*env)->ReleaseStringUTFChars(env, j_object_path, object_path);
            return JNI_FALSE;
        }
        srv = &g_servers[g_server_count++];
        memset(srv, 0, sizeof(*srv));
        srv->object_path = g_strdup(object_path);
    } else {
        /* Clear existing items */
        for (int i = 0; i < srv->item_count; i++) free_menu_item(&srv->items[i]);
    }

    /* Populate items */
    srv->item_count = (int)count;
    for (int i = 0; i < (int)count; i++) {
        MenuItem *item = &srv->items[i];
        item->id = ids[i];
        item->parent_id = parent_ids[i];
        item->enabled = enabled[i];
        item->visible = visible[i];
        item->toggle_state = toggle_states[i];

        jstring jl = (jstring)(*env)->GetObjectArrayElement(env, j_labels, i);
        const char *s = (*env)->GetStringUTFChars(env, jl, NULL);
        item->label = g_strdup(s);
        (*env)->ReleaseStringUTFChars(env, jl, s);
        (*env)->DeleteLocalRef(env, jl);

        jl = (jstring)(*env)->GetObjectArrayElement(env, j_icon_names, i);
        s = (*env)->GetStringUTFChars(env, jl, NULL);
        item->icon_name = g_strdup(s);
        (*env)->ReleaseStringUTFChars(env, jl, s);
        (*env)->DeleteLocalRef(env, jl);

        jl = (jstring)(*env)->GetObjectArrayElement(env, j_types, i);
        s = (*env)->GetStringUTFChars(env, jl, NULL);
        item->type = g_strdup(s);
        (*env)->ReleaseStringUTFChars(env, jl, s);
        (*env)->DeleteLocalRef(env, jl);

        jl = (jstring)(*env)->GetObjectArrayElement(env, j_toggle_types, i);
        s = (*env)->GetStringUTFChars(env, jl, NULL);
        item->toggle_type = g_strdup(s);
        (*env)->ReleaseStringUTFChars(env, jl, s);
        (*env)->DeleteLocalRef(env, jl);

        jl = (jstring)(*env)->GetObjectArrayElement(env, j_dispositions, i);
        s = (*env)->GetStringUTFChars(env, jl, NULL);
        item->disposition = g_strdup(s);
        (*env)->ReleaseStringUTFChars(env, jl, s);
        (*env)->DeleteLocalRef(env, jl);
    }

    srv->revision++;

    /* Register D-Bus object if new — registration happens inside the server thread */
    if (is_new) {
        GError *err = NULL;
        srv->node_info = g_dbus_node_info_new_for_xml(dbusmenu_introspection_xml, &err);
        if (err) {
            g_error_free(err);
            g_free(srv->object_path); srv->object_path = NULL;
            g_server_count--;
            pthread_mutex_unlock(&g_state_mutex);
            goto release;
        }

        /* Prepare synchronization */
        pthread_cond_init(&srv->ready_cond, NULL);
        srv->ready = 0;
        srv->running = 1;

        /* Spawn thread — it will register the D-Bus object on its own GMainContext */
        if (pthread_create(&srv->thread, NULL, dbusmenu_thread_func, srv) != 0) {
            srv->running = 0;
            g_dbus_node_info_unref(srv->node_info); srv->node_info = NULL;
            g_free(srv->object_path); srv->object_path = NULL;
            g_server_count--;
            pthread_mutex_unlock(&g_state_mutex);
            goto release;
        }

        /* Wait for the thread to complete registration */
        while (srv->ready == 0) {
            pthread_cond_wait(&srv->ready_cond, &g_state_mutex);
        }

        if (srv->ready < 0) {
            /* Registration failed in thread */
            srv->running = 0;
            if (srv->loop) g_main_loop_quit(srv->loop);
            pthread_mutex_unlock(&g_state_mutex);
            pthread_join(srv->thread, NULL);
            pthread_mutex_lock(&g_state_mutex);
            g_dbus_node_info_unref(srv->node_info); srv->node_info = NULL;
            g_free(srv->object_path); srv->object_path = NULL;
            g_server_count--;
            pthread_mutex_unlock(&g_state_mutex);
            goto release;
        }
    }

    /* Emit LayoutUpdated signal */
    GError *sig_err = NULL;
    g_dbus_connection_emit_signal(conn, NULL, object_path, DBUSMENU_INTERFACE,
        "LayoutUpdated", g_variant_new("(ui)", srv->revision, 0), &sig_err);
    if (sig_err) g_error_free(sig_err);
    g_dbus_connection_flush_sync(conn, NULL, NULL);

    pthread_mutex_unlock(&g_state_mutex);

release:
    (*env)->ReleaseIntArrayElements(env, j_ids, ids, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, j_parent_ids, parent_ids, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, j_enabled, enabled, JNI_ABORT);
    (*env)->ReleaseBooleanArrayElements(env, j_visible, visible, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, j_toggle_states, toggle_states, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, j_object_path, object_path);

    return srv && srv->registration_id ? JNI_TRUE : JNI_FALSE;
}

/* ===================================================================== */
/* Dbusmenu: nativeDestroyMenu                                           */
/* ===================================================================== */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_launcher_linux_NativeLinuxLauncherBridge_nativeDestroyMenu(
    JNIEnv *env, jclass clazz, jstring j_object_path)
{
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, j_object_path, NULL);
    if (!path) return;

    pthread_mutex_lock(&g_state_mutex);
    DbusmenuServer *srv = find_server(path);
    if (srv) {
        /* Clear all items and emit LayoutUpdated so the DE removes the menu */
        for (int i = 0; i < srv->item_count; i++) free_menu_item(&srv->items[i]);
        srv->item_count = 0;
        srv->revision++;

        GDBusConnection *conn = get_connection();
        if (conn) {
            GError *err = NULL;
            g_dbus_connection_emit_signal(conn, NULL, path, DBUSMENU_INTERFACE,
                "LayoutUpdated", g_variant_new("(ui)", srv->revision, 0), &err);
            if (err) g_error_free(err);
            g_dbus_connection_flush_sync(conn, NULL, NULL);
        }

        /* Stop thread */
        if (srv->running && srv->loop) {
            g_main_loop_quit(srv->loop);
            pthread_mutex_unlock(&g_state_mutex);
            pthread_join(srv->thread, NULL);
            pthread_mutex_lock(&g_state_mutex);
        }

        /* Unregister D-Bus object */
        if (srv->registration_id && g_conn) {
            g_dbus_connection_unregister_object(g_conn, srv->registration_id);
        }
        if (srv->node_info) g_dbus_node_info_unref(srv->node_info);
        g_free(srv->object_path);

        /* Compact the server array */
        int idx = (int)(srv - g_servers);
        if (idx < g_server_count - 1) {
            memmove(&g_servers[idx], &g_servers[idx + 1],
                (g_server_count - idx - 1) * sizeof(DbusmenuServer));
        }
        g_server_count--;
    }
    pthread_mutex_unlock(&g_state_mutex);

    (*env)->ReleaseStringUTFChars(env, j_object_path, path);
}
