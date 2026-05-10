/**
 * nucleus_autolaunch_linux.c
 *
 * JNI bridge for auto-launch at Linux login via the XDG Desktop Portal.
 *
 * Uses org.freedesktop.portal.Background.RequestBackground with autostart=true,
 * which is the only freedesktop-standard mechanism that works uniformly for
 * classic desktop apps (deb/rpm/AppImage) and Flatpak sandboxed apps.
 *
 * Portal spec:
 *   https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Background.html
 *
 * Dependencies: GLib/GIO (libgio-2.0).
 */

#include <jni.h>
#include <gio/gio.h>
#include <glib/gstdio.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <stdint.h>
#include <errno.h>

/* ---- Portal constants -------------------------------------------------- */

#define PORTAL_BUS        "org.freedesktop.portal.Desktop"
#define PORTAL_PATH       "/org/freedesktop/portal/desktop"
#define PORTAL_IFACE_BG   "org.freedesktop.portal.Background"
#define PORTAL_IFACE_REQ  "org.freedesktop.portal.Request"

/* Portal Response signal status codes */
#define PORTAL_STATUS_OK        0
#define PORTAL_STATUS_CANCELLED 1
#define PORTAL_STATUS_ERROR     2

/* Return codes exposed to Kotlin */
#define RC_OK              0
#define RC_ERROR          -1
#define RC_USER_DENIED    -2
#define RC_NO_PORTAL      -3

/* ---- Diagnostic log ---------------------------------------------------- */

#define DIAG_MAX 8192
static pthread_mutex_t g_diag_mutex = PTHREAD_MUTEX_INITIALIZER;
static char g_diag[DIAG_MAX];
static size_t g_diag_len = 0;

static void al_log(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;

    fprintf(stderr, "[autolaunch] %s\n", buf);
    fflush(stderr);

    pthread_mutex_lock(&g_diag_mutex);
    size_t avail = (g_diag_len < DIAG_MAX - 1) ? (DIAG_MAX - 1 - g_diag_len) : 0;
    if (avail > 0) {
        int w = snprintf(g_diag + g_diag_len, avail, "[autolaunch] %s\n", buf);
        if (w > 0) g_diag_len += (size_t)w > avail ? avail : (size_t)w;
    }
    pthread_mutex_unlock(&g_diag_mutex);
}

/* ---- D-Bus session connection (lazily cached) -------------------------- */

static GDBusConnection *g_conn = NULL;
static pthread_mutex_t g_conn_mutex = PTHREAD_MUTEX_INITIALIZER;

static GDBusConnection *get_connection(void) {
    pthread_mutex_lock(&g_conn_mutex);
    if (g_conn != NULL && !g_dbus_connection_is_closed(g_conn)) {
        pthread_mutex_unlock(&g_conn_mutex);
        return g_conn;
    }
    if (g_conn != NULL) {
        g_object_unref(g_conn);
        g_conn = NULL;
    }
    GError *error = NULL;
    g_conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (error != NULL) {
        al_log("g_bus_get_sync failed: %s", error->message);
        g_error_free(error);
        g_conn = NULL;
    }
    pthread_mutex_unlock(&g_conn_mutex);
    return g_conn;
}

/* ---- Request path prediction -------------------------------------------
 *
 * Per portal spec, the Request object path is predictable:
 *   /org/freedesktop/portal/desktop/request/SENDER/TOKEN
 * where SENDER is the unique bus name (":1.42") with leading ':' stripped
 * and '.' replaced by '_'.
 *
 * We must subscribe to Response BEFORE making the call to avoid race.
 */

static gchar *build_request_path(GDBusConnection *conn, const char *token) {
    const gchar *unique = g_dbus_connection_get_unique_name(conn);
    if (unique == NULL) return NULL;
    /* Skip leading ':' */
    const gchar *sender = (unique[0] == ':') ? unique + 1 : unique;
    gchar *escaped = g_strdup(sender);
    for (gchar *p = escaped; *p; ++p) {
        if (*p == '.') *p = '_';
    }
    gchar *path = g_strdup_printf("/org/freedesktop/portal/desktop/request/%s/%s", escaped, token);
    g_free(escaped);
    return path;
}

/* ---- Sync Response wait helper ----------------------------------------- */

typedef struct {
    GMainLoop *loop;
    GMainContext *ctx;
    int status;            /* PORTAL_STATUS_* */
    int got_response;      /* 1 if signal fired */
    int autostart_result;  /* -1 unknown, 0 false, 1 true */
} ResponseCtx;

static void on_response_signal(GDBusConnection *c,
                               const gchar *sender,
                               const gchar *object_path,
                               const gchar *interface_name,
                               const gchar *signal_name,
                               GVariant *parameters,
                               gpointer user_data) {
    (void)c; (void)sender; (void)object_path; (void)interface_name; (void)signal_name;
    ResponseCtx *rc = (ResponseCtx *)user_data;

    guint32 status = PORTAL_STATUS_ERROR;
    GVariant *results = NULL;
    g_variant_get(parameters, "(u@a{sv})", &status, &results);

    rc->status = (int)status;
    rc->got_response = 1;

    if (results != NULL) {
        GVariant *autostart = g_variant_lookup_value(results, "autostart", G_VARIANT_TYPE_BOOLEAN);
        if (autostart != NULL) {
            rc->autostart_result = g_variant_get_boolean(autostart) ? 1 : 0;
            g_variant_unref(autostart);
        }
        g_variant_unref(results);
    }

    if (rc->loop != NULL) g_main_loop_quit(rc->loop);
}

/* ==== nativeIsPortalAvailable ========================================== */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeIsPortalAvailable(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    GDBusConnection *conn = get_connection();
    if (conn == NULL) return JNI_FALSE;

    GError *error = NULL;
    GVariant *res = g_dbus_connection_call_sync(conn,
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        "org.freedesktop.DBus",
        "NameHasOwner",
        g_variant_new("(s)", PORTAL_BUS),
        G_VARIANT_TYPE("(b)"),
        G_DBUS_CALL_FLAGS_NONE, 3000, NULL, &error);
    if (error != NULL) {
        al_log("NameHasOwner failed: %s", error->message);
        g_error_free(error);
        return JNI_FALSE;
    }
    gboolean owned = FALSE;
    g_variant_get(res, "(b)", &owned);
    g_variant_unref(res);
    return owned ? JNI_TRUE : JNI_FALSE;
}

/* ==== nativeRequestBackground =========================================
 *
 * Sets autostart on or off via RequestBackground. Blocks until the portal
 * Response signal fires or the timeout elapses.
 *
 * Returns: RC_OK, RC_USER_DENIED, RC_NO_PORTAL, RC_ERROR.
 */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeRequestBackground(
    JNIEnv *env, jclass clazz,
    jboolean enable,
    jobjectArray j_commandline,
    jstring j_reason)
{
    (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return RC_NO_PORTAL;

    /* Build commandline (as) */
    GVariantBuilder cmd_builder;
    g_variant_builder_init(&cmd_builder, G_VARIANT_TYPE("as"));
    int have_cmd = 0;
    if (j_commandline != NULL) {
        jsize n = (*env)->GetArrayLength(env, j_commandline);
        for (jsize i = 0; i < n; ++i) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, j_commandline, i);
            if (js == NULL) continue;
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            if (s != NULL) {
                g_variant_builder_add(&cmd_builder, "s", s);
                (*env)->ReleaseStringUTFChars(env, js, s);
                have_cmd = 1;
            }
            (*env)->DeleteLocalRef(env, js);
        }
    }
    if (!have_cmd && enable) {
        al_log("enable called with empty commandline");
        g_variant_builder_clear(&cmd_builder);
        return RC_ERROR;
    }

    /* handle_token: unique per call */
    char token[64];
    snprintf(token, sizeof(token), "nucleus_al_%u_%ld",
             (unsigned)g_random_int(), (long)time(NULL));

    gchar *req_path = build_request_path(conn, token);
    if (req_path == NULL) {
        g_variant_builder_clear(&cmd_builder);
        return RC_ERROR;
    }

    /* Subscribe to Response BEFORE making the call */
    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);
    GMainLoop *loop = g_main_loop_new(ctx, FALSE);

    ResponseCtx rc_ctx;
    rc_ctx.loop = loop;
    rc_ctx.ctx = ctx;
    rc_ctx.status = PORTAL_STATUS_ERROR;
    rc_ctx.got_response = 0;
    rc_ctx.autostart_result = -1;

    guint sub_id = g_dbus_connection_signal_subscribe(conn,
        PORTAL_BUS,
        PORTAL_IFACE_REQ,
        "Response",
        req_path,
        NULL,
        G_DBUS_SIGNAL_FLAGS_NO_MATCH_RULE,
        on_response_signal,
        &rc_ctx,
        NULL);

    /* Build options dict a{sv} */
    GVariantBuilder opts_builder;
    g_variant_builder_init(&opts_builder, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&opts_builder, "{sv}", "handle_token", g_variant_new_string(token));
    g_variant_builder_add(&opts_builder, "{sv}", "autostart", g_variant_new_boolean(enable ? TRUE : FALSE));
    g_variant_builder_add(&opts_builder, "{sv}", "background", g_variant_new_boolean(FALSE));
    g_variant_builder_add(&opts_builder, "{sv}", "dbus-activatable", g_variant_new_boolean(FALSE));

    if (have_cmd) {
        g_variant_builder_add(&opts_builder, "{sv}", "commandline", g_variant_builder_end(&cmd_builder));
    } else {
        g_variant_builder_clear(&cmd_builder);
    }

    if (j_reason != NULL) {
        const char *reason = (*env)->GetStringUTFChars(env, j_reason, NULL);
        if (reason != NULL) {
            g_variant_builder_add(&opts_builder, "{sv}", "reason", g_variant_new_string(reason));
            (*env)->ReleaseStringUTFChars(env, j_reason, reason);
        }
    }

    GVariant *params = g_variant_new("(sa{sv})", "", &opts_builder);

    /* Make the call */
    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(conn,
        PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE_BG,
        "RequestBackground",
        params,
        G_VARIANT_TYPE("(o)"),
        G_DBUS_CALL_FLAGS_NONE, 30000, NULL, &error);

    int ret = RC_ERROR;
    if (error != NULL) {
        al_log("RequestBackground failed: %s", error->message);
        g_error_free(error);
        goto cleanup;
    }

    const gchar *returned_path = NULL;
    g_variant_get(result, "(&o)", &returned_path);
    if (returned_path != NULL && strcmp(returned_path, req_path) != 0) {
        /* Portal returned a different handle — resubscribe */
        g_dbus_connection_signal_unsubscribe(conn, sub_id);
        sub_id = g_dbus_connection_signal_subscribe(conn,
            PORTAL_BUS, PORTAL_IFACE_REQ, "Response",
            returned_path, NULL,
            G_DBUS_SIGNAL_FLAGS_NO_MATCH_RULE,
            on_response_signal, &rc_ctx, NULL);
    }
    g_variant_unref(result);

    /* Wait for Response signal, up to 60s (user may see a prompt) */
    guint timeout_id = g_timeout_add_seconds_full(G_PRIORITY_DEFAULT, 60,
        (GSourceFunc)g_main_loop_quit, loop, NULL);
    g_main_loop_run(loop);
    g_source_remove(timeout_id);

    if (!rc_ctx.got_response) {
        al_log("portal Response timeout");
        ret = RC_ERROR;
    } else if (rc_ctx.status == PORTAL_STATUS_OK) {
        ret = RC_OK;
        al_log("RequestBackground ok (autostart=%d)", rc_ctx.autostart_result);
    } else if (rc_ctx.status == PORTAL_STATUS_CANCELLED) {
        ret = RC_USER_DENIED;
        al_log("RequestBackground cancelled by user");
    } else {
        ret = RC_ERROR;
        al_log("RequestBackground failed, portal status=%d", rc_ctx.status);
    }

cleanup:
    g_dbus_connection_signal_unsubscribe(conn, sub_id);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(ctx);
    g_main_context_unref(ctx);
    g_free(req_path);
    return ret;
}

/* ========================================================================
 * systemd --user API (for deb/rpm/AppImage host installs)
 *
 * Uses org.freedesktop.systemd1.Manager to enable/disable a user unit file.
 * The unit file itself is written to $XDG_CONFIG_HOME/systemd/user/ directly
 * (standard search path picked up by `systemctl --user daemon-reload`).
 *
 * systemd handles its own quoting of ExecStart=, so paths with spaces work.
 * Detection at runtime: process inherits INVOCATION_ID env var from systemd.
 * ====================================================================== */

#define SYSTEMD_BUS       "org.freedesktop.systemd1"
#define SYSTEMD_PATH      "/org/freedesktop/systemd1"
#define SYSTEMD_IFACE_MGR "org.freedesktop.systemd1.Manager"

/* Return codes for systemd state */
#define RC_STATE_DISABLED        0
#define RC_STATE_ENABLED         1
#define RC_STATE_ENABLED_RUNTIME 2
#define RC_STATE_NOT_INSTALLED  -2

static gchar *systemd_user_unit_path(const char *unit_name) {
    const gchar *cfg = g_get_user_config_dir();
    return g_build_filename(cfg, "systemd", "user", unit_name, NULL);
}

static int systemd_reload(GDBusConnection *conn) {
    GError *err = NULL;
    GVariant *res = g_dbus_connection_call_sync(conn,
        SYSTEMD_BUS, SYSTEMD_PATH, SYSTEMD_IFACE_MGR,
        "Reload", NULL, NULL,
        G_DBUS_CALL_FLAGS_NONE, 10000, NULL, &err);
    if (err != NULL) {
        al_log("systemd Reload failed: %s", err->message);
        g_error_free(err);
        return RC_ERROR;
    }
    if (res != NULL) g_variant_unref(res);
    return RC_OK;
}

/* ==== nativeWriteUnitFile ============================================== */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeWriteUnitFile(
    JNIEnv *env, jclass clazz,
    jstring j_unit_name, jstring j_content)
{
    (void)clazz;
    const char *unit = (*env)->GetStringUTFChars(env, j_unit_name, NULL);
    const char *content = (*env)->GetStringUTFChars(env, j_content, NULL);
    if (unit == NULL || content == NULL) {
        if (unit) (*env)->ReleaseStringUTFChars(env, j_unit_name, unit);
        if (content) (*env)->ReleaseStringUTFChars(env, j_content, content);
        return RC_ERROR;
    }

    int ret = RC_ERROR;
    gchar *path = systemd_user_unit_path(unit);
    gchar *dir = g_path_get_dirname(path);
    if (g_mkdir_with_parents(dir, 0755) != 0) {
        al_log("mkdir %s failed: %s", dir, g_strerror(errno));
        goto out;
    }

    GError *err = NULL;
    if (!g_file_set_contents(path, content, -1, &err)) {
        al_log("write %s failed: %s", path, err ? err->message : "(unknown)");
        if (err) g_error_free(err);
        goto out;
    }
    ret = RC_OK;

out:
    g_free(dir);
    g_free(path);
    (*env)->ReleaseStringUTFChars(env, j_unit_name, unit);
    (*env)->ReleaseStringUTFChars(env, j_content, content);
    return ret;
}

/* ==== nativeDeleteUnitFile ============================================= */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeDeleteUnitFile(
    JNIEnv *env, jclass clazz, jstring j_unit_name)
{
    (void)clazz;
    const char *unit = (*env)->GetStringUTFChars(env, j_unit_name, NULL);
    if (unit == NULL) return RC_ERROR;

    gchar *path = systemd_user_unit_path(unit);
    int ret = RC_OK;
    if (g_unlink(path) != 0 && errno != ENOENT) {
        al_log("unlink %s failed: %s", path, g_strerror(errno));
        ret = RC_ERROR;
    }
    g_free(path);
    (*env)->ReleaseStringUTFChars(env, j_unit_name, unit);
    return ret;
}

/* ==== nativeEnableUnit ================================================= */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeEnableUnit(
    JNIEnv *env, jclass clazz, jstring j_unit_name)
{
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (conn == NULL) return RC_ERROR;

    const char *unit = (*env)->GetStringUTFChars(env, j_unit_name, NULL);
    if (unit == NULL) return RC_ERROR;

    /* EnableUnitFiles(in as files, in b runtime, in b force,
                      out b carries_install_info, out a(sss) changes) */
    GVariantBuilder files;
    g_variant_builder_init(&files, G_VARIANT_TYPE("as"));
    g_variant_builder_add(&files, "s", unit);

    GVariant *params = g_variant_new("(asbb)", &files, FALSE, TRUE);

    GError *err = NULL;
    GVariant *res = g_dbus_connection_call_sync(conn,
        SYSTEMD_BUS, SYSTEMD_PATH, SYSTEMD_IFACE_MGR,
        "EnableUnitFiles", params,
        G_VARIANT_TYPE("(ba(sss))"),
        G_DBUS_CALL_FLAGS_NONE, 10000, NULL, &err);

    int ret = RC_ERROR;
    if (err != NULL) {
        al_log("EnableUnitFiles(%s) failed: %s", unit, err->message);
        g_error_free(err);
    } else {
        ret = RC_OK;
        if (res != NULL) g_variant_unref(res);
        systemd_reload(conn);
    }

    (*env)->ReleaseStringUTFChars(env, j_unit_name, unit);
    return ret;
}

/* ==== nativeDisableUnit ================================================ */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeDisableUnit(
    JNIEnv *env, jclass clazz, jstring j_unit_name)
{
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (conn == NULL) return RC_ERROR;

    const char *unit = (*env)->GetStringUTFChars(env, j_unit_name, NULL);
    if (unit == NULL) return RC_ERROR;

    /* DisableUnitFiles(in as files, in b runtime, out a(sss) changes) */
    GVariantBuilder files;
    g_variant_builder_init(&files, G_VARIANT_TYPE("as"));
    g_variant_builder_add(&files, "s", unit);

    GVariant *params = g_variant_new("(asb)", &files, FALSE);

    GError *err = NULL;
    GVariant *res = g_dbus_connection_call_sync(conn,
        SYSTEMD_BUS, SYSTEMD_PATH, SYSTEMD_IFACE_MGR,
        "DisableUnitFiles", params,
        G_VARIANT_TYPE("(a(sss))"),
        G_DBUS_CALL_FLAGS_NONE, 10000, NULL, &err);

    int ret = RC_ERROR;
    if (err != NULL) {
        /* Unit not loaded or already disabled — not a hard failure */
        al_log("DisableUnitFiles(%s): %s", unit, err->message);
        g_error_free(err);
        ret = RC_OK;
    } else {
        ret = RC_OK;
        if (res != NULL) g_variant_unref(res);
        systemd_reload(conn);
    }

    (*env)->ReleaseStringUTFChars(env, j_unit_name, unit);
    return ret;
}

/* ==== nativeGetUnitFileState =========================================== */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeGetUnitFileState(
    JNIEnv *env, jclass clazz, jstring j_unit_name)
{
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (conn == NULL) return RC_ERROR;

    const char *unit = (*env)->GetStringUTFChars(env, j_unit_name, NULL);
    if (unit == NULL) return RC_ERROR;

    GError *err = NULL;
    GVariant *res = g_dbus_connection_call_sync(conn,
        SYSTEMD_BUS, SYSTEMD_PATH, SYSTEMD_IFACE_MGR,
        "GetUnitFileState",
        g_variant_new("(s)", unit),
        G_VARIANT_TYPE("(s)"),
        G_DBUS_CALL_FLAGS_NONE, 5000, NULL, &err);

    int ret = RC_STATE_NOT_INSTALLED;
    if (err != NULL) {
        /* Unit file absent — systemd raises FileNotFound or NoSuchUnitFile.
           Both map to RC_STATE_NOT_INSTALLED; not worth logging. */
        const int absent = strstr(err->message, "FileNotFound") != NULL ||
                           strstr(err->message, "NoSuchUnit")   != NULL ||
                           strstr(err->message, "No such file") != NULL;
        if (!absent) {
            al_log("GetUnitFileState(%s) failed: %s", unit, err->message);
        }
        g_error_free(err);
    } else {
        const gchar *state = NULL;
        g_variant_get(res, "(&s)", &state);
        if (state == NULL) {
            ret = RC_STATE_NOT_INSTALLED;
        } else if (strcmp(state, "enabled") == 0) {
            ret = RC_STATE_ENABLED;
        } else if (strcmp(state, "enabled-runtime") == 0) {
            ret = RC_STATE_ENABLED_RUNTIME;
        } else {
            /* linked, disabled, static, masked, etc. */
            ret = RC_STATE_DISABLED;
        }
        g_variant_unref(res);
    }

    (*env)->ReleaseStringUTFChars(env, j_unit_name, unit);
    return ret;
}

/* ==== nativeGetDiagnostic ============================================== */

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_autolaunch_linux_NativeAutoLaunchLinuxBridge_nativeGetDiagnostic(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    pthread_mutex_lock(&g_diag_mutex);
    jstring s = (*env)->NewStringUTF(env, g_diag);
    pthread_mutex_unlock(&g_diag_mutex);
    return s;
}
