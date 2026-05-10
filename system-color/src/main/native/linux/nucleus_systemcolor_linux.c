/**
 * JNI bridge for Linux system color detection via XDG Desktop Portal (D-Bus).
 *
 * Queries org.freedesktop.portal.Settings.ReadOne for accent color and contrast,
 * and watches SettingChanged signals for live updates (event-driven, no polling).
 *
 * Linked libraries: dbus-1
 */

#include <jni.h>
#include <dbus/dbus.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

/* ------------------------------------------------------------------ */
/*  Constants                                                          */
/* ------------------------------------------------------------------ */
#define PORTAL_DEST      "org.freedesktop.portal.Desktop"
#define PORTAL_PATH      "/org/freedesktop/portal/desktop"
#define SETTINGS_IFACE   "org.freedesktop.portal.Settings"
#define READ_METHOD      "ReadOne"
#define APPEARANCE_NS    "org.freedesktop.appearance"
#define ACCENT_KEY       "accent-color"
#define CONTRAST_KEY     "contrast"

#define MATCH_RULE \
    "type='signal'," \
    "sender='" PORTAL_DEST "'," \
    "interface='" SETTINGS_IFACE "'," \
    "member='SettingChanged'," \
    "path='" PORTAL_PATH "'"

/* ------------------------------------------------------------------ */
/*  Global state                                                       */
/* ------------------------------------------------------------------ */
static JavaVM *g_jvm = NULL;
static pthread_t g_watchThread;
static volatile int g_watching = 0;
static volatile int g_stopFlag = 0;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

/* ------------------------------------------------------------------ */
/*  D-Bus helpers                                                      */
/* ------------------------------------------------------------------ */

/**
 * Calls org.freedesktop.portal.Settings.ReadOne and returns the reply.
 * Caller must unref the returned message.
 */
static DBusMessage *portal_read_one(DBusConnection *conn, const char *key) {
    DBusMessage *msg = dbus_message_new_method_call(
        PORTAL_DEST, PORTAL_PATH, SETTINGS_IFACE, READ_METHOD);
    if (!msg) return NULL;

    const char *ns = APPEARANCE_NS;
    dbus_message_append_args(msg,
        DBUS_TYPE_STRING, &ns,
        DBUS_TYPE_STRING, &key,
        DBUS_TYPE_INVALID);

    DBusError err;
    dbus_error_init(&err);
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(conn, msg, 2000, &err);
    dbus_message_unref(msg);

    if (dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        return NULL;
    }
    return reply;
}

/**
 * Extracts the accent-color (r,g,b) doubles from a ReadOne reply.
 *
 * The reply structure is: VARIANT(VARIANT(STRUCT(double, double, double)))
 * gdbus shows: (<(0.207..., 0.517..., 0.894...)>,)
 *
 * Returns 1 on success, 0 on failure.
 */
static int parse_accent_color(DBusMessage *reply, double *r, double *g, double *b) {
    DBusMessageIter root, v1, st;

    dbus_message_iter_init(reply, &root);

    /* ReadOne returns (v) — one variant wrapping the value */
    if (dbus_message_iter_get_arg_type(&root) != DBUS_TYPE_VARIANT) return 0;
    dbus_message_iter_recurse(&root, &v1);

    /* struct (d, d, d) */
    if (dbus_message_iter_get_arg_type(&v1) != DBUS_TYPE_STRUCT) return 0;
    dbus_message_iter_recurse(&v1, &st);

    if (dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE) return 0;
    dbus_message_iter_get_basic(&st, r);
    dbus_message_iter_next(&st);

    if (dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE) return 0;
    dbus_message_iter_get_basic(&st, g);
    dbus_message_iter_next(&st);

    if (dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE) return 0;
    dbus_message_iter_get_basic(&st, b);

    /* validate range */
    if (*r < 0.0 || *r > 1.0 || *g < 0.0 || *g > 1.0 || *b < 0.0 || *b > 1.0)
        return 0;

    return 1;
}

/**
 * Extracts a uint32 contrast value from a ReadOne reply.
 *
 * Reply structure: VARIANT(uint32)
 * Returns 1 on success, 0 on failure.
 */
static int parse_uint32_value(DBusMessage *reply, dbus_uint32_t *out) {
    DBusMessageIter root, v1;

    dbus_message_iter_init(reply, &root);

    if (dbus_message_iter_get_arg_type(&root) != DBUS_TYPE_VARIANT) return 0;
    dbus_message_iter_recurse(&root, &v1);

    if (dbus_message_iter_get_arg_type(&v1) != DBUS_TYPE_UINT32) return 0;
    dbus_message_iter_get_basic(&v1, out);

    return 1;
}

/**
 * Reads the accent color via D-Bus. Returns 1 on success.
 */
static int read_accent_color(double *r, double *g, double *b) {
    DBusError err;
    dbus_error_init(&err);
    DBusConnection *conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (!conn || dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        return 0;
    }

    DBusMessage *reply = portal_read_one(conn, ACCENT_KEY);
    if (!reply) {
        dbus_connection_unref(conn);
        return 0;
    }

    int ok = parse_accent_color(reply, r, g, b);
    dbus_message_unref(reply);
    dbus_connection_unref(conn);
    return ok;
}

/**
 * Reads the contrast mode via D-Bus. Returns 1 if high contrast.
 */
static int read_high_contrast(void) {
    DBusError err;
    dbus_error_init(&err);
    DBusConnection *conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (!conn || dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        return 0;
    }

    DBusMessage *reply = portal_read_one(conn, CONTRAST_KEY);
    if (!reply) {
        dbus_connection_unref(conn);
        return 0;
    }

    dbus_uint32_t val = 0;
    int ok = parse_uint32_value(reply, &val);
    dbus_message_unref(reply);
    dbus_connection_unref(conn);
    return ok && val == 1;
}

/* ------------------------------------------------------------------ */
/*  JNI callback helpers                                               */
/* ------------------------------------------------------------------ */

static void notify_accent_color_changed(double r, double g, double b) {
    if (!g_jvm) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    int didAttach = 0;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = 1;
    } else if (attached != JNI_OK) {
        return;
    }

    jclass cls = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/systemcolor/linux/NativeLinuxSystemColorBridge");
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "onAccentColorChanged", "(FFF)V");
        if (mid) {
            (*env)->CallStaticVoidMethod(env, cls, mid,
                (jfloat)r, (jfloat)g, (jfloat)b);
        }
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (didAttach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void notify_high_contrast_changed(int isHigh) {
    if (!g_jvm) return;

    JNIEnv *env = NULL;
    jint attached = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    int didAttach = 0;
    if (attached == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = 1;
    } else if (attached != JNI_OK) {
        return;
    }

    jclass cls = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/systemcolor/linux/NativeLinuxSystemColorBridge");
    if (cls) {
        jmethodID mid = (*env)->GetStaticMethodID(env, cls, "onHighContrastChanged", "(Z)V");
        if (mid) {
            (*env)->CallStaticVoidMethod(env, cls, mid,
                isHigh ? JNI_TRUE : JNI_FALSE);
        }
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (didAttach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* ------------------------------------------------------------------ */
/*  Signal parsing helpers                                             */
/* ------------------------------------------------------------------ */

/**
 * Parses a SettingChanged signal.
 * Signal args: STRING namespace, STRING key, VARIANT value
 *
 * For accent-color, the variant contains STRUCT(double, double, double).
 * For contrast, the variant contains uint32.
 */
static void handle_setting_changed(DBusMessage *msg) {
    DBusMessageIter args, variant;
    const char *ns = NULL;
    const char *key = NULL;

    dbus_message_iter_init(msg, &args);

    /* arg 1: namespace string */
    if (dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING) return;
    dbus_message_iter_get_basic(&args, &ns);
    dbus_message_iter_next(&args);

    /* arg 2: key string */
    if (dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_STRING) return;
    dbus_message_iter_get_basic(&args, &key);
    dbus_message_iter_next(&args);

    if (!ns || !key || strcmp(ns, APPEARANCE_NS) != 0) return;

    /* arg 3: variant value */
    if (dbus_message_iter_get_arg_type(&args) != DBUS_TYPE_VARIANT) return;
    dbus_message_iter_recurse(&args, &variant);

    if (strcmp(key, ACCENT_KEY) == 0) {
        /* variant contains struct (d, d, d) */
        if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_STRUCT) {
            DBusMessageIter st;
            double r, g, b;
            dbus_message_iter_recurse(&variant, &st);

            if (dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE) return;
            dbus_message_iter_get_basic(&st, &r);
            dbus_message_iter_next(&st);
            if (dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE) return;
            dbus_message_iter_get_basic(&st, &g);
            dbus_message_iter_next(&st);
            if (dbus_message_iter_get_arg_type(&st) != DBUS_TYPE_DOUBLE) return;
            dbus_message_iter_get_basic(&st, &b);

            if (r >= 0.0 && r <= 1.0 && g >= 0.0 && g <= 1.0 && b >= 0.0 && b <= 1.0) {
                notify_accent_color_changed(r, g, b);
            }
        }
    } else if (strcmp(key, CONTRAST_KEY) == 0) {
        /* variant contains uint32 */
        if (dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_UINT32) {
            dbus_uint32_t val = 0;
            dbus_message_iter_get_basic(&variant, &val);
            notify_high_contrast_changed(val == 1);
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Signal watcher thread                                              */
/* ------------------------------------------------------------------ */

static void *watch_thread_proc(void *arg) {
    (void)arg;

    DBusError err;
    dbus_error_init(&err);
    /* Use a private connection so the watcher thread does not share
       the same DBusConnection with the main thread.  dbus_bus_get()
       returns a shared connection, and having two threads call
       read_write / send_with_reply_and_block on the same connection
       causes the initial ReadOne reply to be consumed by the watcher
       before the main thread can process it. */
    DBusConnection *conn = dbus_bus_get_private(DBUS_BUS_SESSION, &err);
    if (!conn || dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        return NULL;
    }

    dbus_bus_add_match(conn, MATCH_RULE, &err);
    if (dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        dbus_connection_unref(conn);
        return NULL;
    }
    dbus_connection_flush(conn);

    while (!g_stopFlag) {
        /* Block up to 500ms waiting for messages, then re-check stop flag */
        dbus_connection_read_write(conn, 500);

        DBusMessage *msg;
        while ((msg = dbus_connection_pop_message(conn)) != NULL) {
            if (dbus_message_is_signal(msg, SETTINGS_IFACE, "SettingChanged")) {
                handle_setting_changed(msg);
            }
            dbus_message_unref(msg);
        }
    }

    dbus_bus_remove_match(conn, MATCH_RULE, NULL);
    dbus_connection_close(conn);
    dbus_connection_unref(conn);
    return NULL;
}

/* ------------------------------------------------------------------ */
/*  JNI exports                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_linux_NativeLinuxSystemColorBridge_nativeGetAccentColor(
    JNIEnv *env, jclass clazz, jfloatArray out) {
    (void)clazz;
    double r = 0, g = 0, b = 0;
    if (!read_accent_color(&r, &g, &b)) return JNI_FALSE;
    jfloat rgb[3] = { (jfloat)r, (jfloat)g, (jfloat)b };
    (*env)->SetFloatArrayRegion(env, out, 0, 3, rgb);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_linux_NativeLinuxSystemColorBridge_nativeIsHighContrast(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return read_high_contrast() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_linux_NativeLinuxSystemColorBridge_nativeIsAccentColorSupported(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    double r, g, b;
    return read_accent_color(&r, &g, &b) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_linux_NativeLinuxSystemColorBridge_nativeStartObserving(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_watching) return;
    g_watching = 1;
    g_stopFlag = 0;
    pthread_create(&g_watchThread, NULL, watch_thread_proc, NULL);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_systemcolor_linux_NativeLinuxSystemColorBridge_nativeStopObserving(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (!g_watching) return;
    g_stopFlag = 1;
    pthread_join(g_watchThread, NULL);
    g_watching = 0;
}
