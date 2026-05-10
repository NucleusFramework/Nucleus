/**
 * JNI bridge for Linux systemd user session management via D-Bus (GIO/GDBus).
 *
 * Replaces systemctl --user subprocess calls with direct D-Bus method
 * invocations on org.freedesktop.systemd1.Manager.
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include <gio/gio.h>
#include <string.h>
#include <stdio.h>

/* ---- D-Bus constants -------------------------------------------------- */

#define SYSTEMD_SERVICE   "org.freedesktop.systemd1"
#define SYSTEMD_PATH      "/org/freedesktop/systemd1"
#define MANAGER_INTERFACE "org.freedesktop.systemd1.Manager"
#define UNIT_INTERFACE    "org.freedesktop.systemd1.Unit"
#define TIMER_INTERFACE   "org.freedesktop.systemd1.Timer"
#define PROPS_INTERFACE   "org.freedesktop.DBus.Properties"

#define DBUS_TIMEOUT_MS   10000

/* ---- JNI class name macro --------------------------------------------- */

#define JNI_PREFIX Java_dev_nucleusframework_nucleus_scheduler_internal_LinuxSystemdSchedulerJni_
#define CONCAT2(a, b) a##b
#define CONCAT(a, b) CONCAT2(a, b)
#define JNI_FN(name) CONCAT(JNI_PREFIX, name)

/* ---- Global state ----------------------------------------------------- */

static GDBusConnection *g_conn = NULL;

/* ---- D-Bus connection ------------------------------------------------- */

static GDBusConnection *get_connection(void) {
    if (g_conn != NULL && !g_dbus_connection_is_closed(g_conn)) {
        return g_conn;
    }
    if (g_conn != NULL) {
        g_object_unref(g_conn);
        g_conn = NULL;
    }
    GError *error = NULL;
    g_conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (error != NULL) {
        g_error_free(error);
        g_conn = NULL;
    }
    return g_conn;
}

/* ---- Helper: call Manager method with no return value ----------------- */

static int manager_call_no_reply(const char *method, GVariant *params) {
    GDBusConnection *conn = get_connection();
    if (!conn) return 0;

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
        method, params, NULL,
        G_DBUS_CALL_FLAGS_NONE, DBUS_TIMEOUT_MS, NULL, &error);

    if (error != NULL) {
        g_error_free(error);
        return 0;
    }
    if (result) g_variant_unref(result);
    return 1;
}

/* ---- Helper: get a property from a unit object path ------------------- */

static GVariant *get_unit_property(const char *unit_name,
                                   const char *iface,
                                   const char *prop_name) {
    GDBusConnection *conn = get_connection();
    if (!conn) return NULL;

    /* Step 1: GetUnit(unit_name) -> object_path */
    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
        "GetUnit", g_variant_new("(s)", unit_name),
        G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE,
        DBUS_TIMEOUT_MS, NULL, &error);

    if (error != NULL) {
        g_error_free(error);
        return NULL;
    }

    const gchar *unit_path = NULL;
    g_variant_get(result, "(&o)", &unit_path);
    gchar *path_copy = g_strdup(unit_path);
    g_variant_unref(result);

    /* Step 2: Properties.Get(iface, prop_name) on unit object path */
    error = NULL;
    result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, path_copy, PROPS_INTERFACE,
        "Get", g_variant_new("(ss)", iface, prop_name),
        G_VARIANT_TYPE("(v)"), G_DBUS_CALL_FLAGS_NONE,
        DBUS_TIMEOUT_MS, NULL, &error);
    g_free(path_copy);

    if (error != NULL) {
        g_error_free(error);
        return NULL;
    }

    GVariant *inner = NULL;
    g_variant_get(result, "(v)", &inner);
    g_variant_unref(result);
    return inner; /* caller must g_variant_unref */
}

/* ---- Helper: init GVariantBuilder string array from jobjectArray ------- */

static void init_string_array_builder(GVariantBuilder *builder, JNIEnv *env, jobjectArray arr) {
    g_variant_builder_init(builder, G_VARIANT_TYPE("as"));
    jsize len = (*env)->GetArrayLength(env, arr);

    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        g_variant_builder_add(builder, "s", utf);
        (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
    }
}

/* ==== JNI Functions ==================================================== */

/*
 * nativeReload() -> boolean
 * Equivalent to: systemctl --user daemon-reload
 */
JNIEXPORT jboolean JNICALL
JNI_FN(nativeReload)(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return manager_call_no_reply("Reload", NULL) ? JNI_TRUE : JNI_FALSE;
}

/*
 * nativeEnableUnitFiles(String[] unitFiles, boolean startNow) -> String?
 * Equivalent to: systemctl --user enable [--now] <units>
 * Returns null on success, error message on failure.
 */
JNIEXPORT jstring JNICALL
JNI_FN(nativeEnableUnitFiles)(JNIEnv *env, jclass clazz,
                              jobjectArray unitFiles, jboolean startNow) {
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return (*env)->NewStringUTF(env, "D-Bus session bus not available");

    GVariantBuilder builder;
    init_string_array_builder(&builder, env, unitFiles);

    /* EnableUnitFiles(as files, b runtime, b force) -> (b, a(sss)) */
    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
        "EnableUnitFiles",
        g_variant_new("(asbb)", &builder, FALSE, TRUE),
        NULL, G_DBUS_CALL_FLAGS_NONE, DBUS_TIMEOUT_MS, NULL, &error);

    if (error != NULL) {
        jstring msg = (*env)->NewStringUTF(env, error->message);
        g_error_free(error);
        return msg;
    }
    if (result) g_variant_unref(result);

    /* If startNow, also StartUnit for each file */
    if (startNow) {
        jsize len = (*env)->GetArrayLength(env, unitFiles);
        for (jsize i = 0; i < len; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, unitFiles, i);
            const char *utf = (*env)->GetStringUTFChars(env, js, NULL);

            error = NULL;
            result = g_dbus_connection_call_sync(
                conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
                "StartUnit", g_variant_new("(ss)", utf, "replace"),
                NULL, G_DBUS_CALL_FLAGS_NONE, DBUS_TIMEOUT_MS, NULL, &error);

            (*env)->ReleaseStringUTFChars(env, js, utf);
            (*env)->DeleteLocalRef(env, js);

            if (error != NULL) {
                jstring msg = (*env)->NewStringUTF(env, error->message);
                g_error_free(error);
                return msg;
            }
            if (result) g_variant_unref(result);
        }
    }

    return NULL; /* success */
}

/*
 * nativeDisableUnitFiles(String[] unitFiles, boolean stopNow) -> String?
 * Equivalent to: systemctl --user disable [--now] <units>
 * Returns null on success, error message on failure.
 */
JNIEXPORT jstring JNICALL
JNI_FN(nativeDisableUnitFiles)(JNIEnv *env, jclass clazz,
                               jobjectArray unitFiles, jboolean stopNow) {
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return (*env)->NewStringUTF(env, "D-Bus session bus not available");

    /* If stopNow, first StopUnit for each file */
    if (stopNow) {
        jsize len = (*env)->GetArrayLength(env, unitFiles);
        for (jsize i = 0; i < len; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, unitFiles, i);
            const char *utf = (*env)->GetStringUTFChars(env, js, NULL);

            GError *error = NULL;
            GVariant *result = g_dbus_connection_call_sync(
                conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
                "StopUnit", g_variant_new("(ss)", utf, "replace"),
                NULL, G_DBUS_CALL_FLAGS_NONE, DBUS_TIMEOUT_MS, NULL, &error);

            (*env)->ReleaseStringUTFChars(env, js, utf);
            (*env)->DeleteLocalRef(env, js);

            /* Ignore stop errors — unit may not be running */
            if (error != NULL) g_error_free(error);
            if (result) g_variant_unref(result);
        }
    }

    GVariantBuilder builder;
    init_string_array_builder(&builder, env, unitFiles);

    /* DisableUnitFiles(as files, b runtime) -> a(sss) */
    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
        "DisableUnitFiles",
        g_variant_new("(asb)", &builder, FALSE),
        NULL, G_DBUS_CALL_FLAGS_NONE, DBUS_TIMEOUT_MS, NULL, &error);

    if (error != NULL) {
        jstring msg = (*env)->NewStringUTF(env, error->message);
        g_error_free(error);
        return msg;
    }
    if (result) g_variant_unref(result);

    return NULL; /* success */
}

/*
 * nativeStartUnit(String unitName) -> boolean
 * Equivalent to: systemctl --user start <unit>
 */
JNIEXPORT jboolean JNICALL
JNI_FN(nativeStartUnit)(JNIEnv *env, jclass clazz, jstring unitName) {
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return JNI_FALSE;

    const char *name = (*env)->GetStringUTFChars(env, unitName, NULL);

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
        "StartUnit", g_variant_new("(ss)", name, "replace"),
        NULL, G_DBUS_CALL_FLAGS_NONE, DBUS_TIMEOUT_MS, NULL, &error);

    (*env)->ReleaseStringUTFChars(env, unitName, name);

    if (error != NULL) {
        g_error_free(error);
        return JNI_FALSE;
    }
    if (result) g_variant_unref(result);
    return JNI_TRUE;
}

/*
 * nativeGetUnitFileState(String unitName) -> String?
 * Equivalent to: systemctl --user is-enabled <unit>
 * Returns "enabled", "disabled", "static", etc. or null on error.
 */
JNIEXPORT jstring JNICALL
JNI_FN(nativeGetUnitFileState)(JNIEnv *env, jclass clazz, jstring unitName) {
    (void)clazz;
    GDBusConnection *conn = get_connection();
    if (!conn) return NULL;

    const char *name = (*env)->GetStringUTFChars(env, unitName, NULL);

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(
        conn, SYSTEMD_SERVICE, SYSTEMD_PATH, MANAGER_INTERFACE,
        "GetUnitFileState", g_variant_new("(s)", name),
        G_VARIANT_TYPE("(s)"), G_DBUS_CALL_FLAGS_NONE,
        DBUS_TIMEOUT_MS, NULL, &error);

    (*env)->ReleaseStringUTFChars(env, unitName, name);

    if (error != NULL) {
        g_error_free(error);
        return NULL;
    }

    const gchar *state = NULL;
    g_variant_get(result, "(&s)", &state);
    jstring jstate = (*env)->NewStringUTF(env, state);
    g_variant_unref(result);
    return jstate;
}

/*
 * nativeGetUnitActiveState(String unitName) -> String?
 * Equivalent to: systemctl --user show -p ActiveState --value <unit>
 * Returns "active", "inactive", "failed", etc. or null on error.
 */
JNIEXPORT jstring JNICALL
JNI_FN(nativeGetUnitActiveState)(JNIEnv *env, jclass clazz, jstring unitName) {
    (void)clazz;
    const char *name = (*env)->GetStringUTFChars(env, unitName, NULL);
    GVariant *value = get_unit_property(name, UNIT_INTERFACE, "ActiveState");
    (*env)->ReleaseStringUTFChars(env, unitName, name);

    if (!value) return NULL;

    const gchar *state = g_variant_get_string(value, NULL);
    jstring jstate = (*env)->NewStringUTF(env, state);
    g_variant_unref(value);
    return jstate;
}

/*
 * nativeGetTimerNextElapseUSec(String timerName) -> long
 * Equivalent to: systemctl --user show -p NextElapseUSecRealtime --value <timer>
 * Returns microseconds since epoch, or 0 on error.
 */
JNIEXPORT jlong JNICALL
JNI_FN(nativeGetTimerNextElapseUSec)(JNIEnv *env, jclass clazz, jstring timerName) {
    (void)clazz;
    const char *name = (*env)->GetStringUTFChars(env, timerName, NULL);
    GVariant *value = get_unit_property(name, TIMER_INTERFACE, "NextElapseUSecRealtime");
    (*env)->ReleaseStringUTFChars(env, timerName, name);

    if (!value) return 0;

    guint64 usec = g_variant_get_uint64(value);
    g_variant_unref(value);
    return (jlong)usec;
}
