/**
 * JNI bridge for OS media controls via MPRIS D-Bus (GIO/GDBus).
 *
 * Implements:
 *   org.mpris.MediaPlayer2         — root interface (Identity, Raise, Quit)
 *   org.mpris.MediaPlayer2.Player  — playback interface
 *
 * Specification:
 *   https://specifications.freedesktop.org/mpris-spec/latest/
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include <gio/gio.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <inttypes.h>

/* ---- Constants -------------------------------------------------------- */

#define MPRIS_OBJECT_PATH     "/org/mpris/MediaPlayer2"
#define MPRIS_ROOT_IFACE      "org.mpris.MediaPlayer2"
#define MPRIS_PLAYER_IFACE    "org.mpris.MediaPlayer2.Player"
#define PROPERTIES_IFACE      "org.freedesktop.DBus.Properties"

#define STATUS_STOPPED 0
#define STATUS_PAUSED  1
#define STATUS_PLAYING 2

/* ---- Introspection XML ------------------------------------------------ */

static const gchar mpris_root_xml[] =
    "<node>"
    "  <interface name='org.mpris.MediaPlayer2'>"
    "    <method name='Raise'/>"
    "    <method name='Quit'/>"
    "    <property name='CanQuit' type='b' access='read'/>"
    "    <property name='CanRaise' type='b' access='read'/>"
    "    <property name='HasTrackList' type='b' access='read'/>"
    "    <property name='Identity' type='s' access='read'/>"
    "    <property name='DesktopEntry' type='s' access='read'/>"
    "    <property name='SupportedUriSchemes' type='as' access='read'/>"
    "    <property name='SupportedMimeTypes' type='as' access='read'/>"
    "  </interface>"
    "</node>";

static const gchar mpris_player_xml[] =
    "<node>"
    "  <interface name='org.mpris.MediaPlayer2.Player'>"
    "    <method name='Next'/>"
    "    <method name='Previous'/>"
    "    <method name='Pause'/>"
    "    <method name='PlayPause'/>"
    "    <method name='Stop'/>"
    "    <method name='Play'/>"
    "    <method name='Seek'>"
    "      <arg type='x' name='Offset' direction='in'/>"
    "    </method>"
    "    <method name='SetPosition'>"
    "      <arg type='o' name='TrackId' direction='in'/>"
    "      <arg type='x' name='Position' direction='in'/>"
    "    </method>"
    "    <method name='OpenUri'>"
    "      <arg type='s' name='Uri' direction='in'/>"
    "    </method>"
    "    <signal name='Seeked'>"
    "      <arg type='x' name='Position'/>"
    "    </signal>"
    "    <property name='PlaybackStatus' type='s' access='read'/>"
    "    <property name='Rate' type='d' access='readwrite'/>"
    "    <property name='Metadata' type='a{sv}' access='read'/>"
    "    <property name='Volume' type='d' access='readwrite'/>"
    "    <property name='Position' type='x' access='read'/>"
    "    <property name='MinimumRate' type='d' access='read'/>"
    "    <property name='MaximumRate' type='d' access='read'/>"
    "    <property name='CanGoNext' type='b' access='read'/>"
    "    <property name='CanGoPrevious' type='b' access='read'/>"
    "    <property name='CanPlay' type='b' access='read'/>"
    "    <property name='CanPause' type='b' access='read'/>"
    "    <property name='CanSeek' type='b' access='read'/>"
    "    <property name='CanControl' type='b' access='read'/>"
    "  </interface>"
    "</node>";

/* ---- State ------------------------------------------------------------ */

static JavaVM          *g_jvm = NULL;
static jclass           g_bridge_class = NULL;
static jmethodID        g_on_event_method = NULL;

static pthread_mutex_t  g_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t   g_ready_cond  = PTHREAD_COND_INITIALIZER;

/* Configuration */
static char *g_bus_name     = NULL;   /* full name, e.g. "org.mpris.MediaPlayer2.MyApp" */
static char *g_display_name = NULL;

/* Media state */
static char   *g_title      = NULL;
static char   *g_artist     = NULL;
static char   *g_album      = NULL;
static char   *g_cover_url  = NULL;
static gint64  g_duration_us = -1;
static gint64  g_position_us =  0;
static gint    g_status      = STATUS_STOPPED;
static gdouble g_volume      = 1.0;
static guint64 g_track_counter = 0;

/* Thread & GDBus */
static GDBusConnection *g_conn = NULL;
static GMainContext    *g_ctx  = NULL;
static GMainLoop       *g_loop = NULL;
static pthread_t        g_thread;
static volatile int     g_running = 0;
static volatile int     g_ready   = 0;  /* 1 ok, -1 failed */
static guint            g_own_id  = 0;
static guint            g_root_reg_id   = 0;
static guint            g_player_reg_id = 0;
static GDBusNodeInfo   *g_root_node   = NULL;
static GDBusNodeInfo   *g_player_node = NULL;

/* ---- JNI helpers ------------------------------------------------------ */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

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

static int ensure_callback_ids(JNIEnv *env) {
    if (g_bridge_class != NULL) return 1;
    jclass cls = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/media/control/linux/NativeLinuxBridge");
    if (!cls) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); return 0; }
    g_bridge_class = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);
    g_on_event_method = (*env)->GetStaticMethodID(env, g_bridge_class,
        "onMediaControlEvent", "(Ljava/lang/String;)V");
    if (!g_on_event_method) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
        return 0;
    }
    return 1;
}

/* ---- JSON helpers (minimal, single-producer) -------------------------- */

static void json_append_escaped(GString *out, const char *s) {
    g_string_append_c(out, '"');
    if (!s) { g_string_append_c(out, '"'); return; }
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        switch (*p) {
            case '"':  g_string_append(out, "\\\""); break;
            case '\\': g_string_append(out, "\\\\"); break;
            case '\b': g_string_append(out, "\\b"); break;
            case '\f': g_string_append(out, "\\f"); break;
            case '\n': g_string_append(out, "\\n"); break;
            case '\r': g_string_append(out, "\\r"); break;
            case '\t': g_string_append(out, "\\t"); break;
            default:
                if (*p < 0x20) {
                    g_string_append_printf(out, "\\u%04x", *p);
                } else {
                    g_string_append_c(out, (char)*p);
                }
        }
    }
    g_string_append_c(out, '"');
}

static void dispatch_event_simple(const char *type) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (!env || !ensure_callback_ids(env)) { release_env(attached); return; }

    GString *s = g_string_new("{\"type\":");
    json_append_escaped(s, type);
    g_string_append_c(s, '}');

    jstring js = (*env)->NewStringUTF(env, s->str);
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);

    g_string_free(s, TRUE);
    release_env(attached);
}

static void dispatch_event_offset(const char *type, gint64 value_us) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (!env || !ensure_callback_ids(env)) { release_env(attached); return; }

    GString *s = g_string_new("{\"type\":");
    json_append_escaped(s, type);
    g_string_append_printf(s, ",\"offsetUs\":%" PRId64 "}", value_us);

    jstring js = (*env)->NewStringUTF(env, s->str);
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);

    g_string_free(s, TRUE);
    release_env(attached);
}

static void dispatch_event_position(gint64 position_us) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (!env || !ensure_callback_ids(env)) { release_env(attached); return; }

    GString *s = g_string_new("{\"type\":\"set_position\",\"positionUs\":");
    g_string_append_printf(s, "%" PRId64 "}", position_us);

    jstring js = (*env)->NewStringUTF(env, s->str);
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);

    g_string_free(s, TRUE);
    release_env(attached);
}

static void dispatch_event_volume(gdouble volume) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (!env || !ensure_callback_ids(env)) { release_env(attached); return; }

    char buf[64];
    snprintf(buf, sizeof(buf), "{\"type\":\"set_volume\",\"volume\":%.6f}", volume);

    jstring js = (*env)->NewStringUTF(env, buf);
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);
    release_env(attached);
}

static void dispatch_event_uri(const char *uri) {
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (!env || !ensure_callback_ids(env)) { release_env(attached); return; }

    GString *s = g_string_new("{\"type\":\"open_uri\",\"uri\":");
    json_append_escaped(s, uri);
    g_string_append_c(s, '}');

    jstring js = (*env)->NewStringUTF(env, s->str);
    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_event_method, js);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, js);

    g_string_free(s, TRUE);
    release_env(attached);
}

/* ---- MPRIS helpers ---------------------------------------------------- */

static const char *status_to_string(gint status) {
    switch (status) {
        case STATUS_PLAYING: return "Playing";
        case STATUS_PAUSED:  return "Paused";
        default:             return "Stopped";
    }
}

/* Caller must hold g_state_mutex. */
static GVariant *build_metadata_locked(void) {
    GVariantBuilder b;
    g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));

    char track_path[96];
    snprintf(track_path, sizeof(track_path),
             "/dev/nucleusframework/nucleus/track/%" G_GUINT64_FORMAT,
             g_track_counter);
    g_variant_builder_add(&b, "{sv}", "mpris:trackid",
                          g_variant_new_object_path(track_path));

    if (g_duration_us > 0) {
        g_variant_builder_add(&b, "{sv}", "mpris:length",
                              g_variant_new_int64(g_duration_us));
    }
    if (g_cover_url && g_cover_url[0]) {
        g_variant_builder_add(&b, "{sv}", "mpris:artUrl",
                              g_variant_new_string(g_cover_url));
    }
    if (g_title && g_title[0]) {
        g_variant_builder_add(&b, "{sv}", "xesam:title",
                              g_variant_new_string(g_title));
    }
    if (g_artist && g_artist[0]) {
        const char *arr[2] = { g_artist, NULL };
        g_variant_builder_add(&b, "{sv}", "xesam:artist",
                              g_variant_new_strv(arr, 1));
    }
    if (g_album && g_album[0]) {
        g_variant_builder_add(&b, "{sv}", "xesam:album",
                              g_variant_new_string(g_album));
    }

    return g_variant_builder_end(&b);
}

static void emit_properties_changed(GVariantBuilder *changed) {
    if (!g_conn) { g_variant_builder_clear(changed); return; }
    GVariantBuilder invalidated;
    g_variant_builder_init(&invalidated, G_VARIANT_TYPE("as"));
    GVariant *params = g_variant_new("(sa{sv}as)",
                                     MPRIS_PLAYER_IFACE, changed, &invalidated);
    GError *err = NULL;
    g_dbus_connection_emit_signal(g_conn, NULL, MPRIS_OBJECT_PATH,
                                  PROPERTIES_IFACE, "PropertiesChanged",
                                  params, &err);
    if (err) g_error_free(err);
}

/* GSource callbacks to emit signals from the main loop thread ---------- */

typedef struct { char *name; GVariant *value; } PropChange;

static gboolean do_emit_prop_change(gpointer user_data) {
    PropChange *pc = (PropChange *)user_data;
    GVariantBuilder changed;
    g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&changed, "{sv}", pc->name, pc->value);
    emit_properties_changed(&changed);
    g_free(pc->name);
    g_free(pc);
    return G_SOURCE_REMOVE;
}

static void schedule_prop_change(const char *name, GVariant *value) {
    if (!g_ctx) { g_variant_unref(g_variant_ref_sink(value)); return; }
    PropChange *pc = g_new0(PropChange, 1);
    pc->name  = g_strdup(name);
    pc->value = g_variant_ref_sink(value);
    GSource *src = g_idle_source_new();
    g_source_set_callback(src, do_emit_prop_change, pc, NULL);
    g_source_attach(src, g_ctx);
    g_source_unref(src);
}

typedef struct { gint64 position_us; } SeekedPayload;

static gboolean do_emit_seeked(gpointer user_data) {
    SeekedPayload *sp = (SeekedPayload *)user_data;
    if (g_conn) {
        GError *err = NULL;
        g_dbus_connection_emit_signal(g_conn, NULL, MPRIS_OBJECT_PATH,
                                      MPRIS_PLAYER_IFACE, "Seeked",
                                      g_variant_new("(x)", sp->position_us), &err);
        if (err) g_error_free(err);
    }
    g_free(sp);
    return G_SOURCE_REMOVE;
}

static void schedule_seeked(gint64 position_us) {
    if (!g_ctx) return;
    SeekedPayload *sp = g_new0(SeekedPayload, 1);
    sp->position_us = position_us;
    GSource *src = g_idle_source_new();
    g_source_set_callback(src, do_emit_seeked, sp, NULL);
    g_source_attach(src, g_ctx);
    g_source_unref(src);
}

/* ---- D-Bus vtables ---------------------------------------------------- */

static void root_handle_method(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *method, GVariant *params,
    GDBusMethodInvocation *invocation, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)params; (void)user_data;
    if (g_strcmp0(method, "Raise") == 0) {
        dispatch_event_simple("raise");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "Quit") == 0) {
        dispatch_event_simple("quit");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR,
            G_DBUS_ERROR_UNKNOWN_METHOD, "Unknown method: %s", method);
    }
}

static GVariant *root_get_property(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *prop, GError **error, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)user_data;
    if (g_strcmp0(prop, "CanQuit") == 0) return g_variant_new_boolean(TRUE);
    if (g_strcmp0(prop, "CanRaise") == 0) return g_variant_new_boolean(TRUE);
    if (g_strcmp0(prop, "HasTrackList") == 0) return g_variant_new_boolean(FALSE);
    if (g_strcmp0(prop, "Identity") == 0) {
        pthread_mutex_lock(&g_state_mutex);
        GVariant *v = g_variant_new_string(g_display_name ? g_display_name : "Nucleus App");
        pthread_mutex_unlock(&g_state_mutex);
        return v;
    }
    if (g_strcmp0(prop, "DesktopEntry") == 0) return g_variant_new_string("");
    if (g_strcmp0(prop, "SupportedUriSchemes") == 0) {
        const char *empty[] = { NULL };
        return g_variant_new_strv(empty, 0);
    }
    if (g_strcmp0(prop, "SupportedMimeTypes") == 0) {
        const char *empty[] = { NULL };
        return g_variant_new_strv(empty, 0);
    }
    g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown: %s", prop);
    return NULL;
}

static const GDBusInterfaceVTable root_vtable = {
    root_handle_method, root_get_property, NULL, {0}
};

static void player_handle_method(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *method, GVariant *params,
    GDBusMethodInvocation *invocation, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)user_data;

    if (g_strcmp0(method, "Next") == 0) {
        dispatch_event_simple("next");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "Previous") == 0) {
        dispatch_event_simple("previous");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "Pause") == 0) {
        dispatch_event_simple("pause");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "PlayPause") == 0) {
        dispatch_event_simple("toggle");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "Stop") == 0) {
        dispatch_event_simple("stop");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "Play") == 0) {
        dispatch_event_simple("play");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "Seek") == 0) {
        gint64 offset = 0;
        g_variant_get(params, "(x)", &offset);
        dispatch_event_offset("seek", offset);
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "SetPosition") == 0) {
        const gchar *trackid = NULL;
        gint64 position = 0;
        g_variant_get(params, "(&ox)", &trackid, &position);
        (void)trackid;
        pthread_mutex_lock(&g_state_mutex);
        gint64 duration = g_duration_us;
        pthread_mutex_unlock(&g_state_mutex);
        if (position < 0 || (duration > 0 && position > duration)) {
            g_dbus_method_invocation_return_value(invocation, NULL);
            return;
        }
        dispatch_event_position(position);
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else if (g_strcmp0(method, "OpenUri") == 0) {
        const gchar *uri = NULL;
        g_variant_get(params, "(&s)", &uri);
        dispatch_event_uri(uri ? uri : "");
        g_dbus_method_invocation_return_value(invocation, NULL);
    } else {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR,
            G_DBUS_ERROR_UNKNOWN_METHOD, "Unknown method: %s", method);
    }
}

static GVariant *player_get_property(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *prop, GError **error, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)user_data;

    if (g_strcmp0(prop, "PlaybackStatus") == 0) {
        pthread_mutex_lock(&g_state_mutex);
        GVariant *v = g_variant_new_string(status_to_string(g_status));
        pthread_mutex_unlock(&g_state_mutex);
        return v;
    }
    if (g_strcmp0(prop, "Metadata") == 0) {
        pthread_mutex_lock(&g_state_mutex);
        GVariant *v = build_metadata_locked();
        pthread_mutex_unlock(&g_state_mutex);
        return v;
    }
    if (g_strcmp0(prop, "Volume") == 0) {
        pthread_mutex_lock(&g_state_mutex);
        GVariant *v = g_variant_new_double(g_volume);
        pthread_mutex_unlock(&g_state_mutex);
        return v;
    }
    if (g_strcmp0(prop, "Position") == 0) {
        pthread_mutex_lock(&g_state_mutex);
        GVariant *v = g_variant_new_int64(g_position_us);
        pthread_mutex_unlock(&g_state_mutex);
        return v;
    }
    if (g_strcmp0(prop, "Rate") == 0)        return g_variant_new_double(1.0);
    if (g_strcmp0(prop, "MinimumRate") == 0) return g_variant_new_double(1.0);
    if (g_strcmp0(prop, "MaximumRate") == 0) return g_variant_new_double(1.0);
    if (g_strcmp0(prop, "CanGoNext") == 0 ||
        g_strcmp0(prop, "CanGoPrevious") == 0 ||
        g_strcmp0(prop, "CanPlay") == 0 ||
        g_strcmp0(prop, "CanPause") == 0 ||
        g_strcmp0(prop, "CanSeek") == 0 ||
        g_strcmp0(prop, "CanControl") == 0) {
        return g_variant_new_boolean(TRUE);
    }
    g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown: %s", prop);
    return NULL;
}

static gboolean player_set_property(
    GDBusConnection *conn, const gchar *sender, const gchar *path,
    const gchar *iface, const gchar *prop, GVariant *value,
    GError **error, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface; (void)user_data; (void)error;
    if (g_strcmp0(prop, "Volume") == 0) {
        gdouble v = g_variant_get_double(value);
        if (v < 0.0) v = 0.0;
        if (v > 1.0) v = 1.0;
        dispatch_event_volume(v);
        return TRUE;
    }
    if (g_strcmp0(prop, "Rate") == 0) {
        /* Accept but ignore — we only support rate = 1.0 */
        return TRUE;
    }
    return FALSE;
}

static const GDBusInterfaceVTable player_vtable = {
    player_handle_method, player_get_property, player_set_property, {0}
};

/* ---- Bus name lifecycle ---------------------------------------------- */

static void on_bus_acquired(GDBusConnection *conn, const gchar *name, gpointer user_data) {
    (void)name; (void)user_data;
    pthread_mutex_lock(&g_state_mutex);
    g_conn = conn;

    GError *err = NULL;
    if (!g_root_node) g_root_node = g_dbus_node_info_new_for_xml(mpris_root_xml, &err);
    if (err) { g_error_free(err); err = NULL; }
    if (!g_player_node) g_player_node = g_dbus_node_info_new_for_xml(mpris_player_xml, &err);
    if (err) { g_error_free(err); err = NULL; }

    if (g_root_node && g_root_reg_id == 0) {
        g_root_reg_id = g_dbus_connection_register_object(conn, MPRIS_OBJECT_PATH,
            g_root_node->interfaces[0], &root_vtable, NULL, NULL, &err);
        if (err) { g_error_free(err); err = NULL; g_root_reg_id = 0; }
    }
    if (g_player_node && g_player_reg_id == 0) {
        g_player_reg_id = g_dbus_connection_register_object(conn, MPRIS_OBJECT_PATH,
            g_player_node->interfaces[0], &player_vtable, NULL, NULL, &err);
        if (err) { g_error_free(err); err = NULL; g_player_reg_id = 0; }
    }
    pthread_mutex_unlock(&g_state_mutex);
}

static void on_name_acquired(GDBusConnection *conn, const gchar *name, gpointer user_data) {
    (void)conn; (void)name; (void)user_data;
    pthread_mutex_lock(&g_state_mutex);
    g_ready = 1;
    pthread_cond_broadcast(&g_ready_cond);
    pthread_mutex_unlock(&g_state_mutex);
}

static void on_name_lost(GDBusConnection *conn, const gchar *name, gpointer user_data) {
    (void)conn; (void)name; (void)user_data;
    pthread_mutex_lock(&g_state_mutex);
    if (g_ready == 0) {
        g_ready = -1;
        pthread_cond_broadcast(&g_ready_cond);
    }
    pthread_mutex_unlock(&g_state_mutex);
}

/* ---- Service thread --------------------------------------------------- */

static void *service_thread_func(void *arg) {
    (void)arg;
    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);

    pthread_mutex_lock(&g_state_mutex);
    g_ctx = ctx;
    char *bus_name = g_strdup(g_bus_name ? g_bus_name : "org.mpris.MediaPlayer2.NucleusApp");
    pthread_mutex_unlock(&g_state_mutex);

    g_own_id = g_bus_own_name(G_BUS_TYPE_SESSION, bus_name,
        G_BUS_NAME_OWNER_FLAGS_REPLACE,
        on_bus_acquired, on_name_acquired, on_name_lost,
        NULL, NULL);
    g_free(bus_name);

    GMainLoop *loop = g_main_loop_new(ctx, FALSE);

    pthread_mutex_lock(&g_state_mutex);
    g_loop = loop;
    pthread_mutex_unlock(&g_state_mutex);

    g_main_loop_run(loop);

    /* Cleanup on exit */
    pthread_mutex_lock(&g_state_mutex);
    if (g_own_id) { g_bus_unown_name(g_own_id); g_own_id = 0; }
    if (g_conn) {
        if (g_root_reg_id)   { g_dbus_connection_unregister_object(g_conn, g_root_reg_id);   g_root_reg_id = 0; }
        if (g_player_reg_id) { g_dbus_connection_unregister_object(g_conn, g_player_reg_id); g_player_reg_id = 0; }
        g_conn = NULL;
    }
    g_loop = NULL;
    g_ctx  = NULL;
    g_running = 0;
    g_ready   = 0;
    pthread_mutex_unlock(&g_state_mutex);

    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(ctx);
    g_main_context_unref(ctx);
    return NULL;
}

/* ===================================================================== */
/* JNI entry points                                                      */
/* ===================================================================== */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_linux_NativeLinuxBridge_nativeConfigure(
    JNIEnv *env, jclass clazz, jstring j_bus_name, jstring j_display_name)
{
    (void)clazz;
    const char *bus = (*env)->GetStringUTFChars(env, j_bus_name, NULL);
    const char *disp = (*env)->GetStringUTFChars(env, j_display_name, NULL);

    pthread_mutex_lock(&g_state_mutex);
    g_free(g_bus_name);     g_bus_name     = g_strdup(bus);
    g_free(g_display_name); g_display_name = g_strdup(disp);
    pthread_mutex_unlock(&g_state_mutex);

    (*env)->ReleaseStringUTFChars(env, j_bus_name, bus);
    (*env)->ReleaseStringUTFChars(env, j_display_name, disp);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_linux_NativeLinuxBridge_nativeSetMetadata(
    JNIEnv *env, jclass clazz,
    jstring j_title, jstring j_artist, jstring j_album, jstring j_cover, jlong duration_ms)
{
    (void)clazz;
    const char *title  = j_title  ? (*env)->GetStringUTFChars(env, j_title,  NULL) : NULL;
    const char *artist = j_artist ? (*env)->GetStringUTFChars(env, j_artist, NULL) : NULL;
    const char *album  = j_album  ? (*env)->GetStringUTFChars(env, j_album,  NULL) : NULL;
    const char *cover  = j_cover  ? (*env)->GetStringUTFChars(env, j_cover,  NULL) : NULL;

    pthread_mutex_lock(&g_state_mutex);
    g_free(g_title);     g_title     = title  ? g_strdup(title)  : NULL;
    g_free(g_artist);    g_artist    = artist ? g_strdup(artist) : NULL;
    g_free(g_album);     g_album     = album  ? g_strdup(album)  : NULL;
    g_free(g_cover_url); g_cover_url = cover  ? g_strdup(cover)  : NULL;
    g_duration_us = (duration_ms > 0) ? (gint64)duration_ms * 1000 : -1;
    g_track_counter++;
    GVariant *metadata = build_metadata_locked();
    pthread_mutex_unlock(&g_state_mutex);

    schedule_prop_change("Metadata", metadata);

    if (title)  (*env)->ReleaseStringUTFChars(env, j_title,  title);
    if (artist) (*env)->ReleaseStringUTFChars(env, j_artist, artist);
    if (album)  (*env)->ReleaseStringUTFChars(env, j_album,  album);
    if (cover)  (*env)->ReleaseStringUTFChars(env, j_cover,  cover);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_linux_NativeLinuxBridge_nativeSetPlaybackState(
    JNIEnv *env, jclass clazz, jint status, jlong position_ms)
{
    (void)env; (void)clazz;
    int status_changed = 0;
    int position_changed = 0;
    gint64 new_position_us = 0;

    pthread_mutex_lock(&g_state_mutex);
    if (status != g_status) { g_status = status; status_changed = 1; }
    if (position_ms >= 0) {
        new_position_us = (gint64)position_ms * 1000;
        if (new_position_us != g_position_us) {
            g_position_us = new_position_us;
            position_changed = 1;
        }
    }
    pthread_mutex_unlock(&g_state_mutex);

    if (status_changed) {
        schedule_prop_change("PlaybackStatus",
            g_variant_new_string(status_to_string(status)));
    }
    if (position_changed) {
        schedule_seeked(new_position_us);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_linux_NativeLinuxBridge_nativeSetVolume(
    JNIEnv *env, jclass clazz, jdouble volume)
{
    (void)env; (void)clazz;
    if (volume < 0.0) volume = 0.0;
    if (volume > 1.0) volume = 1.0;
    int changed = 0;
    pthread_mutex_lock(&g_state_mutex);
    if (volume != g_volume) { g_volume = volume; changed = 1; }
    pthread_mutex_unlock(&g_state_mutex);

    if (changed) {
        schedule_prop_change("Volume", g_variant_new_double(volume));
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_media_control_linux_NativeLinuxBridge_nativeStartListening(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_state_mutex);
    if (g_running) { pthread_mutex_unlock(&g_state_mutex); return JNI_TRUE; }
    if (!g_bus_name) {
        pthread_mutex_unlock(&g_state_mutex);
        return JNI_FALSE;
    }
    g_running = 1;
    g_ready   = 0;
    if (pthread_create(&g_thread, NULL, service_thread_func, NULL) != 0) {
        g_running = 0;
        pthread_mutex_unlock(&g_state_mutex);
        return JNI_FALSE;
    }
    /* Wait until the bus name is acquired (or definitively lost) */
    while (g_ready == 0 && g_running) {
        pthread_cond_wait(&g_ready_cond, &g_state_mutex);
    }
    jboolean ok = (g_ready == 1) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_state_mutex);
    return ok;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_media_control_linux_NativeLinuxBridge_nativeStopListening(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_state_mutex);
    GMainLoop *loop = g_loop;
    int running = g_running;
    pthread_mutex_unlock(&g_state_mutex);

    if (running && loop) {
        g_main_loop_quit(loop);
        pthread_join(g_thread, NULL);
    }

    pthread_mutex_lock(&g_state_mutex);
    if (g_root_node)   { g_dbus_node_info_unref(g_root_node);   g_root_node   = NULL; }
    if (g_player_node) { g_dbus_node_info_unref(g_player_node); g_player_node = NULL; }
    pthread_mutex_unlock(&g_state_mutex);
}
