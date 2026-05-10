/**
 * JNI bridge for Linux desktop notifications via D-Bus (GIO/GDBus).
 *
 * Fully implements the freedesktop Desktop Notifications Specification:
 * https://specifications.freedesktop.org/notification/latest-single/
 *
 * Methods: Notify, CloseNotification, GetCapabilities, GetServerInformation
 * Signals: NotificationClosed, ActionInvoked, ActivationToken
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include <gio/gio.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <stdint.h>

/* ---- D-Bus constants -------------------------------------------------- */

#define SERVICE_NAME "org.freedesktop.Notifications"
#define OBJECT_PATH  "/org/freedesktop/Notifications"
#define INTERFACE    "org.freedesktop.Notifications"

/* Sentinel for "position hint not set" — matches Int.MIN_VALUE in Kotlin */
#define POS_NOT_SET  INT32_MIN

/* ---- Global state ----------------------------------------------------- */

static JavaVM *g_jvm = NULL;

/* Shared D-Bus connection for method calls */
static GDBusConnection *g_conn = NULL;

/* Signal listener thread state */
static pthread_mutex_t g_listen_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_listen_cond  = PTHREAD_COND_INITIALIZER;
static pthread_t       g_thread;
static volatile int    g_running      = 0;
static GMainLoop      *g_signal_loop  = NULL;

/* Cached JNI references for signal callbacks */
static jclass    g_bridge_class      = NULL;
static jmethodID g_on_closed_method  = NULL;
static jmethodID g_on_action_method  = NULL;
static jmethodID g_on_token_method   = NULL;

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
    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

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

/* ---- JNI callback ID cache ------------------------------------------- */

static int ensure_callback_ids(JNIEnv *env) {
    if (g_bridge_class != NULL) return 1;

    jclass cls = (*env)->FindClass(env,
        "dev/nucleusframework/nucleus/notification/linux/NativeLinuxNotificationBridge");
    if (cls == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return 0;
    }
    g_bridge_class = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);

    g_on_closed_method = (*env)->GetStaticMethodID(env, g_bridge_class,
        "onNotificationClosed", "(II)V");
    g_on_action_method = (*env)->GetStaticMethodID(env, g_bridge_class,
        "onActionInvoked", "(ILjava/lang/String;)V");
    g_on_token_method = (*env)->GetStaticMethodID(env, g_bridge_class,
        "onActivationToken", "(ILjava/lang/String;)V");

    if (!g_on_closed_method || !g_on_action_method || !g_on_token_method) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
        return 0;
    }
    return 1;
}

/* ==== Notify =========================================================== */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_notification_linux_NativeLinuxNotificationBridge_nativeNotify(
    JNIEnv *env, jclass clazz,
    jstring j_app_name,
    jint    replaces_id,
    jstring j_app_icon,
    jstring j_summary,
    jstring j_body,
    jobjectArray j_action_keys,
    jobjectArray j_action_labels,
    jint    urgency,
    jstring j_category,
    jstring j_desktop_entry,
    jstring j_image_path,
    jstring j_sound_file,
    jstring j_sound_name,
    jint    suppress_sound,
    jint    action_icons,
    jint    resident,
    jint    is_transient,
    jint    pos_x,
    jint    pos_y,
    jboolean has_image_data,
    jint    image_width,
    jint    image_height,
    jint    image_rowstride,
    jboolean image_has_alpha,
    jint    image_bits_per_sample,
    jint    image_channels,
    jbyteArray j_image_pixels,
    jint    expire_timeout)
{
    (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return 0;

    /* Get mandatory string parameters */
    const char *app_name = (*env)->GetStringUTFChars(env, j_app_name, NULL);
    const char *app_icon = (*env)->GetStringUTFChars(env, j_app_icon, NULL);
    const char *summary  = (*env)->GetStringUTFChars(env, j_summary, NULL);
    const char *body     = (*env)->GetStringUTFChars(env, j_body, NULL);
    if (!app_name || !app_icon || !summary || !body) goto release_strings;

    /* ---- Build actions array (as) ---- */
    GVariantBuilder actions_builder;
    g_variant_builder_init(&actions_builder, G_VARIANT_TYPE("as"));

    if (j_action_keys != NULL && j_action_labels != NULL) {
        jsize num_actions = (*env)->GetArrayLength(env, j_action_keys);
        for (jsize i = 0; i < num_actions; i++) {
            jstring jk = (jstring)(*env)->GetObjectArrayElement(env, j_action_keys, i);
            jstring jl = (jstring)(*env)->GetObjectArrayElement(env, j_action_labels, i);
            const char *key   = (*env)->GetStringUTFChars(env, jk, NULL);
            const char *label = (*env)->GetStringUTFChars(env, jl, NULL);
            if (key)   g_variant_builder_add(&actions_builder, "s", key);
            if (label)  g_variant_builder_add(&actions_builder, "s", label);
            if (key)   (*env)->ReleaseStringUTFChars(env, jk, key);
            if (label) (*env)->ReleaseStringUTFChars(env, jl, label);
            (*env)->DeleteLocalRef(env, jk);
            (*env)->DeleteLocalRef(env, jl);
        }
    }

    /* ---- Build hints dict (a{sv}) ---- */
    GVariantBuilder hints_builder;
    g_variant_builder_init(&hints_builder, G_VARIANT_TYPE("a{sv}"));

    /* urgency (BYTE) */
    if (urgency >= 0) {
        g_variant_builder_add(&hints_builder, "{sv}", "urgency",
            g_variant_new_byte((guchar)urgency));
    }

    /* category (STRING) */
    if (j_category != NULL) {
        const char *val = (*env)->GetStringUTFChars(env, j_category, NULL);
        if (val) {
            g_variant_builder_add(&hints_builder, "{sv}", "category",
                g_variant_new_string(val));
            (*env)->ReleaseStringUTFChars(env, j_category, val);
        }
    }

    /* desktop-entry (STRING) */
    if (j_desktop_entry != NULL) {
        const char *val = (*env)->GetStringUTFChars(env, j_desktop_entry, NULL);
        if (val) {
            g_variant_builder_add(&hints_builder, "{sv}", "desktop-entry",
                g_variant_new_string(val));
            (*env)->ReleaseStringUTFChars(env, j_desktop_entry, val);
        }
    }

    /* image-path (STRING) */
    if (j_image_path != NULL) {
        const char *val = (*env)->GetStringUTFChars(env, j_image_path, NULL);
        if (val) {
            g_variant_builder_add(&hints_builder, "{sv}", "image-path",
                g_variant_new_string(val));
            (*env)->ReleaseStringUTFChars(env, j_image_path, val);
        }
    }

    /* sound-file (STRING) */
    if (j_sound_file != NULL) {
        const char *val = (*env)->GetStringUTFChars(env, j_sound_file, NULL);
        if (val) {
            g_variant_builder_add(&hints_builder, "{sv}", "sound-file",
                g_variant_new_string(val));
            (*env)->ReleaseStringUTFChars(env, j_sound_file, val);
        }
    }

    /* sound-name (STRING) */
    if (j_sound_name != NULL) {
        const char *val = (*env)->GetStringUTFChars(env, j_sound_name, NULL);
        if (val) {
            g_variant_builder_add(&hints_builder, "{sv}", "sound-name",
                g_variant_new_string(val));
            (*env)->ReleaseStringUTFChars(env, j_sound_name, val);
        }
    }

    /* suppress-sound (BOOLEAN) */
    if (suppress_sound >= 0) {
        g_variant_builder_add(&hints_builder, "{sv}", "suppress-sound",
            g_variant_new_boolean(suppress_sound ? TRUE : FALSE));
    }

    /* action-icons (BOOLEAN) */
    if (action_icons >= 0) {
        g_variant_builder_add(&hints_builder, "{sv}", "action-icons",
            g_variant_new_boolean(action_icons ? TRUE : FALSE));
    }

    /* resident (BOOLEAN) */
    if (resident >= 0) {
        g_variant_builder_add(&hints_builder, "{sv}", "resident",
            g_variant_new_boolean(resident ? TRUE : FALSE));
    }

    /* transient (BOOLEAN) */
    if (is_transient >= 0) {
        g_variant_builder_add(&hints_builder, "{sv}", "transient",
            g_variant_new_boolean(is_transient ? TRUE : FALSE));
    }

    /* x / y (INT32) — both must be present per spec */
    if (pos_x != POS_NOT_SET && pos_y != POS_NOT_SET) {
        g_variant_builder_add(&hints_builder, "{sv}", "x",
            g_variant_new_int32(pos_x));
        g_variant_builder_add(&hints_builder, "{sv}", "y",
            g_variant_new_int32(pos_y));
    }

    /* image-data (iiibiiay) */
    if (has_image_data && j_image_pixels != NULL) {
        jsize pixel_len = (*env)->GetArrayLength(env, j_image_pixels);
        jbyte *pixels = (*env)->GetByteArrayElements(env, j_image_pixels, NULL);
        if (pixels != NULL) {
            GVariant *byte_array = g_variant_new_fixed_array(
                G_VARIANT_TYPE_BYTE, (const guchar *)pixels, (gsize)pixel_len, 1);

            GVariant *img = g_variant_new("(iiibii@ay)",
                (gint32)image_width,
                (gint32)image_height,
                (gint32)image_rowstride,
                (gboolean)(image_has_alpha ? TRUE : FALSE),
                (gint32)image_bits_per_sample,
                (gint32)image_channels,
                byte_array);

            g_variant_builder_add(&hints_builder, "{sv}", "image-data", img);

            (*env)->ReleaseByteArrayElements(env, j_image_pixels, pixels, JNI_ABORT);
        }
    }

    /* ---- Build full Notify call ---- */
    GVariant *params = g_variant_new("(susss@as@a{sv}i)",
        app_name,
        (guint32)replaces_id,
        app_icon,
        summary,
        body,
        g_variant_builder_end(&actions_builder),
        g_variant_builder_end(&hints_builder),
        (gint32)expire_timeout);

    /* Release mandatory strings before the (potentially slow) D-Bus call */
    (*env)->ReleaseStringUTFChars(env, j_app_name, app_name);
    (*env)->ReleaseStringUTFChars(env, j_app_icon, app_icon);
    (*env)->ReleaseStringUTFChars(env, j_summary, summary);
    (*env)->ReleaseStringUTFChars(env, j_body, body);
    app_name = app_icon = summary = body = NULL;

    /* Synchronous D-Bus method call */
    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(conn,
        SERVICE_NAME, OBJECT_PATH, INTERFACE,
        "Notify", params,
        G_VARIANT_TYPE("(u)"),
        G_DBUS_CALL_FLAGS_NONE,
        -1, NULL, &error);

    if (error != NULL) {
        g_error_free(error);
        return 0;
    }

    guint32 notification_id = 0;
    g_variant_get(result, "(u)", &notification_id);
    g_variant_unref(result);

    return (jint)notification_id;

release_strings:
    if (app_name) (*env)->ReleaseStringUTFChars(env, j_app_name, app_name);
    if (app_icon) (*env)->ReleaseStringUTFChars(env, j_app_icon, app_icon);
    if (summary)  (*env)->ReleaseStringUTFChars(env, j_summary, summary);
    if (body)     (*env)->ReleaseStringUTFChars(env, j_body, body);
    return 0;
}

/* ==== CloseNotification ================================================ */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_linux_NativeLinuxNotificationBridge_nativeCloseNotification(
    JNIEnv *env, jclass clazz, jint id)
{
    (void)env; (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return;

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(conn,
        SERVICE_NAME, OBJECT_PATH, INTERFACE,
        "CloseNotification",
        g_variant_new("(u)", (guint32)id),
        NULL,
        G_DBUS_CALL_FLAGS_NONE,
        -1, NULL, &error);

    if (error != NULL) {
        g_error_free(error);
        return;
    }
    if (result != NULL) g_variant_unref(result);
}

/* ==== GetCapabilities ================================================== */

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_notification_linux_NativeLinuxNotificationBridge_nativeGetCapabilities(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return NULL;

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(conn,
        SERVICE_NAME, OBJECT_PATH, INTERFACE,
        "GetCapabilities", NULL,
        G_VARIANT_TYPE("(as)"),
        G_DBUS_CALL_FLAGS_NONE,
        -1, NULL, &error);

    if (error != NULL) {
        g_error_free(error);
        return NULL;
    }

    GVariantIter *iter = NULL;
    g_variant_get(result, "(as)", &iter);

    gsize count = g_variant_iter_n_children(iter);
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, (jsize)count, string_class, NULL);

    const gchar *cap = NULL;
    jsize i = 0;
    while (g_variant_iter_next(iter, "&s", &cap)) {
        jstring j_cap = (*env)->NewStringUTF(env, cap);
        (*env)->SetObjectArrayElement(env, arr, i++, j_cap);
        (*env)->DeleteLocalRef(env, j_cap);
    }

    g_variant_iter_free(iter);
    g_variant_unref(result);
    return arr;
}

/* ==== GetServerInformation ============================================= */

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_notification_linux_NativeLinuxNotificationBridge_nativeGetServerInformation(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return NULL;

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(conn,
        SERVICE_NAME, OBJECT_PATH, INTERFACE,
        "GetServerInformation", NULL,
        G_VARIANT_TYPE("(ssss)"),
        G_DBUS_CALL_FLAGS_NONE,
        -1, NULL, &error);

    if (error != NULL) {
        g_error_free(error);
        return NULL;
    }

    const gchar *name = NULL, *vendor = NULL, *version = NULL, *spec_version = NULL;
    g_variant_get(result, "(&s&s&s&s)", &name, &vendor, &version, &spec_version);

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, 4, string_class, NULL);

    jstring j0 = (*env)->NewStringUTF(env, name);
    jstring j1 = (*env)->NewStringUTF(env, vendor);
    jstring j2 = (*env)->NewStringUTF(env, version);
    jstring j3 = (*env)->NewStringUTF(env, spec_version);

    (*env)->SetObjectArrayElement(env, arr, 0, j0);
    (*env)->SetObjectArrayElement(env, arr, 1, j1);
    (*env)->SetObjectArrayElement(env, arr, 2, j2);
    (*env)->SetObjectArrayElement(env, arr, 3, j3);

    (*env)->DeleteLocalRef(env, j0);
    (*env)->DeleteLocalRef(env, j1);
    (*env)->DeleteLocalRef(env, j2);
    (*env)->DeleteLocalRef(env, j3);

    g_variant_unref(result);
    return arr;
}

/* ==== Signal handlers ================================================== */

static void on_notification_closed(
    GDBusConnection *conn, const gchar *sender,
    const gchar *path, const gchar *iface, const gchar *signal_name,
    GVariant *params, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface;
    (void)signal_name; (void)user_data;

    guint32 id = 0, reason = 0;
    g_variant_get(params, "(uu)", &id, &reason);

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return;

    if (ensure_callback_ids(env)) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_closed_method,
            (jint)id, (jint)reason);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }

    release_env(attached);
}

static void on_action_invoked(
    GDBusConnection *conn, const gchar *sender,
    const gchar *path, const gchar *iface, const gchar *signal_name,
    GVariant *params, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface;
    (void)signal_name; (void)user_data;

    guint32 id = 0;
    const gchar *action_key = NULL;
    g_variant_get(params, "(u&s)", &id, &action_key);

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return;

    if (ensure_callback_ids(env)) {
        jstring j_key = (*env)->NewStringUTF(env, action_key);
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_action_method,
            (jint)id, j_key);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, j_key);
    }

    release_env(attached);
}

static void on_activation_token(
    GDBusConnection *conn, const gchar *sender,
    const gchar *path, const gchar *iface, const gchar *signal_name,
    GVariant *params, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface;
    (void)signal_name; (void)user_data;

    guint32 id = 0;
    const gchar *token = NULL;
    g_variant_get(params, "(u&s)", &id, &token);

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return;

    if (ensure_callback_ids(env)) {
        jstring j_token = (*env)->NewStringUTF(env, token);
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_token_method,
            (jint)id, j_token);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, j_token);
    }

    release_env(attached);
}

/* ==== Signal listener thread =========================================== */

static void *signal_thread_func(void *arg) {
    (void)arg;

    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);

    GDBusConnection *conn = get_connection();
    if (conn == NULL) {
        g_main_context_pop_thread_default(ctx);
        g_main_context_unref(ctx);
        pthread_mutex_lock(&g_listen_mutex);
        g_running = 0;
        pthread_cond_broadcast(&g_listen_cond);
        pthread_mutex_unlock(&g_listen_mutex);
        return NULL;
    }

    /* Subscribe to all three notification signals */
    guint sub_closed = g_dbus_connection_signal_subscribe(conn,
        NULL, INTERFACE, "NotificationClosed", OBJECT_PATH,
        NULL, G_DBUS_SIGNAL_FLAGS_NONE,
        on_notification_closed, NULL, NULL);

    guint sub_action = g_dbus_connection_signal_subscribe(conn,
        NULL, INTERFACE, "ActionInvoked", OBJECT_PATH,
        NULL, G_DBUS_SIGNAL_FLAGS_NONE,
        on_action_invoked, NULL, NULL);

    guint sub_token = g_dbus_connection_signal_subscribe(conn,
        NULL, INTERFACE, "ActivationToken", OBJECT_PATH,
        NULL, G_DBUS_SIGNAL_FLAGS_NONE,
        on_activation_token, NULL, NULL);

    GMainLoop *loop = g_main_loop_new(ctx, FALSE);

    /* Signal the calling thread that we're ready */
    pthread_mutex_lock(&g_listen_mutex);
    g_signal_loop = loop;
    pthread_cond_broadcast(&g_listen_cond);
    pthread_mutex_unlock(&g_listen_mutex);

    /* Block until g_main_loop_quit() is called */
    g_main_loop_run(loop);

    /* Cleanup subscriptions */
    g_dbus_connection_signal_unsubscribe(conn, sub_closed);
    g_dbus_connection_signal_unsubscribe(conn, sub_action);
    g_dbus_connection_signal_unsubscribe(conn, sub_token);

    pthread_mutex_lock(&g_listen_mutex);
    g_signal_loop = NULL;
    g_running = 0;
    pthread_mutex_unlock(&g_listen_mutex);

    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(ctx);
    g_main_context_unref(ctx);

    return NULL;
}

/* ==== StartListening / StopListening =================================== */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_notification_linux_NativeLinuxNotificationBridge_nativeStartListening(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    pthread_mutex_lock(&g_listen_mutex);
    if (g_running) {
        pthread_mutex_unlock(&g_listen_mutex);
        return JNI_TRUE;
    }
    g_running = 1;

    if (pthread_create(&g_thread, NULL, signal_thread_func, NULL) != 0) {
        g_running = 0;
        pthread_mutex_unlock(&g_listen_mutex);
        return JNI_FALSE;
    }

    /* Wait for the thread to initialise the main loop */
    while (g_signal_loop == NULL && g_running) {
        pthread_cond_wait(&g_listen_cond, &g_listen_mutex);
    }

    jboolean ok = g_running ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_listen_mutex);
    return ok;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_notification_linux_NativeLinuxNotificationBridge_nativeStopListening(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    pthread_mutex_lock(&g_listen_mutex);
    if (!g_running || g_signal_loop == NULL) {
        pthread_mutex_unlock(&g_listen_mutex);
        return;
    }
    g_main_loop_quit(g_signal_loop);
    pthread_mutex_unlock(&g_listen_mutex);

    pthread_join(g_thread, NULL);
}
