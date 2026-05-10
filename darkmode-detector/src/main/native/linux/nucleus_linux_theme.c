/**
 * JNI bridge for Linux dark-mode detection via XDG Desktop Portal.
 *
 * Uses the org.freedesktop.portal.Settings D-Bus interface to:
 *   - Read the "color-scheme" preference (org.freedesktop.appearance namespace)
 *   - Monitor for SettingChanged signals in real-time
 *
 * color-scheme values: 0 = no preference, 1 = prefer-dark, 2 = prefer-light
 *
 * Linked libraries: libdbus-1 (dynamically)
 */

#include <jni.h>
#include <dbus/dbus.h>
#include <pthread.h>
#include <string.h>

/* Cached JavaVM pointer, set in JNI_OnLoad */
static JavaVM *g_jvm = NULL;

/* D-Bus connection used by the monitoring thread */
static DBusConnection *g_conn = NULL;

/* Monitoring thread handle */
static pthread_t g_thread;
static volatile int g_running = 0;

/* Portal constants */
static const char *PORTAL_BUS   = "org.freedesktop.portal.Desktop";
static const char *PORTAL_PATH  = "/org/freedesktop/portal/desktop";
static const char *PORTAL_IFACE = "org.freedesktop.portal.Settings";
static const char *APPEARANCE_NS = "org.freedesktop.appearance";
static const char *COLOR_SCHEME  = "color-scheme";

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/**
 * Extract the color-scheme uint32 from the Read() reply.
 * The reply signature is v(v(u)) — a variant wrapping a variant wrapping a uint32.
 * Returns: 0 (no pref), 1 (dark), 2 (light), or -1 on error.
 */
static int extract_color_scheme(DBusMessage *reply) {
    DBusMessageIter iter, outer_variant, inner_variant;

    dbus_message_iter_init(reply, &iter);

    /* Outer variant */
    if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_VARIANT) return -1;
    dbus_message_iter_recurse(&iter, &outer_variant);

    /* Inner variant */
    if (dbus_message_iter_get_arg_type(&outer_variant) != DBUS_TYPE_VARIANT) {
        /* Some portals only wrap once — try reading uint32 directly */
        if (dbus_message_iter_get_arg_type(&outer_variant) == DBUS_TYPE_UINT32) {
            dbus_uint32_t val;
            dbus_message_iter_get_basic(&outer_variant, &val);
            return (int)val;
        }
        return -1;
    }
    dbus_message_iter_recurse(&outer_variant, &inner_variant);

    if (dbus_message_iter_get_arg_type(&inner_variant) == DBUS_TYPE_UINT32) {
        dbus_uint32_t val;
        dbus_message_iter_get_basic(&inner_variant, &val);
        return (int)val;
    }
    return -1;
}

/**
 * Read the current color-scheme value from the portal.
 * Returns 1 if dark, 0 otherwise. Uses a private connection.
 */
static jboolean read_color_scheme(void) {
    DBusError err;
    dbus_error_init(&err);

    DBusConnection *conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (conn == NULL) {
        dbus_error_free(&err);
        return JNI_FALSE;
    }

    DBusMessage *msg = dbus_message_new_method_call(
        PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE, "Read");
    if (msg == NULL) {
        dbus_connection_unref(conn);
        return JNI_FALSE;
    }

    const char *ns = APPEARANCE_NS;
    const char *key = COLOR_SCHEME;
    dbus_message_append_args(msg,
        DBUS_TYPE_STRING, &ns,
        DBUS_TYPE_STRING, &key,
        DBUS_TYPE_INVALID);

    DBusMessage *reply = dbus_connection_send_with_reply_and_block(
        conn, msg, 1000, &err);
    dbus_message_unref(msg);

    jboolean result = JNI_FALSE;
    if (reply != NULL) {
        int scheme = extract_color_scheme(reply);
        if (scheme == 1) result = JNI_TRUE;
        dbus_message_unref(reply);
    }

    dbus_error_free(&err);
    dbus_connection_unref(conn);
    return result;
}

/* ------------------------------------------------------------------ */
/*  nativeIsDark()                                                     */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_linux_NativeLinuxBridge_nativeIsDark(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return read_color_scheme();
}

/**
 * Notify the Kotlin bridge about a theme change.
 */
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
        "dev/nucleusframework/nucleus/darkmodedetector/linux/NativeLinuxBridge");
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

/**
 * Extract color-scheme from a SettingChanged signal.
 * Signal signature: (s s v) — namespace, key, value
 * Returns color-scheme value or -1 on error/mismatch.
 */
static int extract_signal_color_scheme(DBusMessage *msg) {
    DBusMessageIter iter;
    if (!dbus_message_iter_init(msg, &iter)) return -1;

    /* First arg: namespace (string) */
    if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING) return -1;
    const char *ns;
    dbus_message_iter_get_basic(&iter, &ns);
    if (strcmp(ns, APPEARANCE_NS) != 0) return -1;

    /* Second arg: key (string) */
    if (!dbus_message_iter_next(&iter)) return -1;
    if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING) return -1;
    const char *key;
    dbus_message_iter_get_basic(&iter, &key);
    if (strcmp(key, COLOR_SCHEME) != 0) return -1;

    /* Third arg: variant wrapping uint32 */
    if (!dbus_message_iter_next(&iter)) return -1;
    if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_VARIANT) return -1;
    DBusMessageIter variant;
    dbus_message_iter_recurse(&iter, &variant);
    if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_UINT32) {
        dbus_uint32_t val;
        dbus_message_iter_get_basic(&variant, &val);
        return (int)val;
    }
    return -1;
}

/**
 * Monitoring thread: listens for SettingChanged signals on the session bus.
 */
static void *monitor_thread(void *arg) {
    (void)arg;
    DBusError err;
    dbus_error_init(&err);

    /* Use a private connection so closing it doesn't affect the shared one */
    g_conn = dbus_bus_get_private(DBUS_BUS_SESSION, &err);
    if (g_conn == NULL) {
        dbus_error_free(&err);
        return NULL;
    }

    /* Subscribe to SettingChanged signal */
    dbus_bus_add_match(g_conn,
        "type='signal',"
        "interface='org.freedesktop.portal.Settings',"
        "member='SettingChanged',"
        "path='/org/freedesktop/portal/desktop'",
        &err);
    if (dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        dbus_connection_close(g_conn);
        dbus_connection_unref(g_conn);
        g_conn = NULL;
        return NULL;
    }
    dbus_connection_flush(g_conn);

    /* Dispatch loop */
    while (g_running) {
        /* Block for up to 500ms waiting for messages */
        if (!dbus_connection_read_write(g_conn, 500)) {
            break; /* connection closed */
        }

        DBusMessage *msg;
        while ((msg = dbus_connection_pop_message(g_conn)) != NULL) {
            if (dbus_message_is_signal(msg,
                    "org.freedesktop.portal.Settings", "SettingChanged")) {
                int scheme = extract_signal_color_scheme(msg);
                if (scheme >= 0) {
                    notify_java(scheme == 1 ? JNI_TRUE : JNI_FALSE);
                }
            }
            dbus_message_unref(msg);
        }
    }

    dbus_connection_close(g_conn);
    dbus_connection_unref(g_conn);
    g_conn = NULL;
    dbus_error_free(&err);
    return NULL;
}

/* ------------------------------------------------------------------ */
/*  nativeStartObserving()                                             */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_linux_NativeLinuxBridge_nativeStartObserving(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (g_running) return; /* already observing */

    g_running = 1;
    pthread_create(&g_thread, NULL, monitor_thread, NULL);
}

/* ------------------------------------------------------------------ */
/*  nativeStopObserving()                                              */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_darkmodedetector_linux_NativeLinuxBridge_nativeStopObserving(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (!g_running) return;

    g_running = 0;
    /* The dispatch loop will exit on the next timeout or when the connection
       is closed. We join the thread to ensure clean shutdown. */
    pthread_join(g_thread, NULL);
}
