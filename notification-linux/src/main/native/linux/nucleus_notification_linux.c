/**
 * JNI bridge for Linux desktop notifications via D-Bus (GIO/GDBus).
 *
 * Fully implements the freedesktop Desktop Notifications Specification:
 * https://specifications.freedesktop.org/notification/latest-single/
 *
 * Methods: Notify, CloseNotification, GetCapabilities, GetServerInformation
 * Signals: NotificationClosed, ActionInvoked, ActivationToken
 *
 * Inside a Flatpak sandbox (/.flatpak-info) the same calls go through the notification portal
 * (org.freedesktop.portal.Notification) instead, which needs no D-Bus permission in the manifest:
 * AddNotification / RemoveNotification, and its ActionInvoked signal. The portal has no close
 * signal, no expiry timeout, no sound and no raw image data; the rest maps over (see portal_notify).
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include "../../../../../native-common/nucleus_jni.h"
#include <gio/gio.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <stdint.h>
#include <unistd.h>

/* ---- D-Bus constants -------------------------------------------------- */

#define SERVICE_NAME "org.freedesktop.Notifications"
#define OBJECT_PATH  "/org/freedesktop/Notifications"
#define INTERFACE    "org.freedesktop.Notifications"

#define PORTAL_NAME      "org.freedesktop.portal.Desktop"
#define PORTAL_PATH      "/org/freedesktop/portal/desktop"
#define PORTAL_INTERFACE "org.freedesktop.portal.Notification"

/* An image the portal is handed as bytes is read whole: keep it to icon sizes. */
#define PORTAL_MAX_ICON_BYTES (4 * 1024 * 1024)

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

/* Portal mode: -1 unknown, 0 direct D-Bus, 1 portal. Ids the portal knows us by, as decimal strings. */
static volatile int g_use_portal = -1;
static volatile gint g_portal_next_id = 0;

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

/* ---- String conversion ------------------------------------------------ */

/*
 * GetStringUTFChars / NewStringUTF speak JNI *modified* UTF-8, which encodes
 * supplementary characters (most emoji) as two 3-byte surrogates. GLib rejects
 * that for the D-Bus "s" type and substitutes "[Invalid UTF-8]" (#844), so all
 * strings cross the boundary as UTF-16 and are converted with GLib instead.
 */

/* Returns a standard UTF-8 copy of a Java string (g_free it), or NULL. */
static gchar *jstring_to_utf8(JNIEnv *env, jstring str) {
    if (str == NULL) return NULL;

    jsize len = (*env)->GetStringLength(env, str);
    const jchar *chars = (*env)->GetStringChars(env, str, NULL);
    if (chars == NULL) return NULL;

    /* Java strings may hold lone surrogates, which g_utf16_to_utf8 rejects:
     * replace them with U+FFFD, like String.getBytes(UTF_8) does with '?'. */
    gunichar2 *units = g_new(gunichar2, len > 0 ? len : 1);
    for (jsize i = 0; i < len; i++) {
        gunichar2 c = (gunichar2)chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len
                && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            units[i] = c;
            units[i + 1] = (gunichar2)chars[i + 1];
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            units[i] = 0xFFFD;
        } else {
            units[i] = c;
        }
    }
    (*env)->ReleaseStringChars(env, str, chars);

    gchar *utf8 = g_utf16_to_utf8(units, (glong)len, NULL, NULL, NULL);
    g_free(units);
    return utf8;
}

/* Returns a Java string built from standard UTF-8, or NULL. */
static jstring utf8_to_jstring(JNIEnv *env, const gchar *utf8) {
    if (utf8 == NULL) return NULL;

    glong len = 0;
    gunichar2 *units = g_utf8_to_utf16(utf8, -1, NULL, &len, NULL);
    if (units == NULL) return NULL;

    jstring str = (*env)->NewString(env, (const jchar *)units, (jsize)len);
    g_free(units);
    return str;
}

/* ---- JNI callback ID cache ------------------------------------------- */

static int ensure_callback_ids(JNIEnv *env) {
    if (g_bridge_class != NULL) return 1;

    jclass cls = (*env)->FindClass(env,
        "dev/nucleusframework/notification/linux/NativeLinuxNotificationBridge");
    if (cls == NULL) {
        nucleus_jni_clear_exception(env);
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
        nucleus_jni_clear_exception(env);
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
        return 0;
    }
    return 1;
}

/* ==== Portal (Flatpak) ================================================== */

/* Flatpak mounts /.flatpak-info in every sandbox; outside it the server is talked to directly. */
static int use_portal(void) {
    if (g_use_portal < 0) g_use_portal = access("/.flatpak-info", F_OK) == 0 ? 1 : 0;
    return g_use_portal;
}

/* A serialized GIcon for the portal: a file (path or file:// URI) as bytes, else a themed name. */
static GVariant *portal_icon(const gchar *icon) {
    if (icon == NULL || icon[0] == '\0') return NULL;
    gchar *path = g_str_has_prefix(icon, "file://") ? g_filename_from_uri(icon, NULL, NULL) : g_strdup(icon);
    GVariant *result = NULL;
    if (path != NULL && g_path_is_absolute(path)) {
        gchar *contents = NULL;
        gsize length = 0;
        if (g_file_get_contents(path, &contents, &length, NULL) && length <= PORTAL_MAX_ICON_BYTES) {
            GBytes *bytes = g_bytes_new_take(contents, length);
            GIcon *gicon = g_bytes_icon_new(bytes);
            result = g_icon_serialize(gicon);
            g_object_unref(gicon);
            g_bytes_unref(bytes);
        } else {
            g_free(contents);
        }
    } else if (path != NULL) {
        GIcon *gicon = g_themed_icon_new(path);
        result = g_icon_serialize(gicon);
        g_object_unref(gicon);
    }
    g_free(path);
    return result;
}

static const char *portal_priority(jint urgency) {
    switch (urgency) {
        case 0: return "low";
        case 2: return "urgent";
        default: return "normal";
    }
}

/* AddNotification for Notify: returns the id (replaces_id when set), 0 on failure. */
static jint portal_notify(JNIEnv *env, GDBusConnection *conn, jint replaces_id,
                          const gchar *app_icon, const gchar *summary, const gchar *body,
                          jobjectArray j_action_keys, jobjectArray j_action_labels,
                          jint urgency, jstring j_image_path) {
    jint id = replaces_id > 0 ? replaces_id : (jint)g_atomic_int_add(&g_portal_next_id, 1) + 1;
    gchar id_str[16];
    g_snprintf(id_str, sizeof id_str, "%d", (int)id);

    GVariantBuilder notification;
    g_variant_builder_init(&notification, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&notification, "{sv}", "title", g_variant_new_string(summary));
    if (body[0] != '\0') g_variant_builder_add(&notification, "{sv}", "body", g_variant_new_string(body));
    if (urgency >= 0) {
        g_variant_builder_add(&notification, "{sv}", "priority", g_variant_new_string(portal_priority(urgency)));
    }

    /* The large image wins over the app icon: the portal shows one picture. */
    gchar *image_path = jstring_to_utf8(env, j_image_path);
    GVariant *icon = portal_icon(image_path != NULL ? image_path : app_icon);
    if (icon == NULL && image_path != NULL) icon = portal_icon(app_icon);
    g_free(image_path);
    if (icon != NULL) g_variant_builder_add(&notification, "{sv}", "icon", icon);

    /* "default" is the click on the notification itself; every other action is a button.
     * Names without the "app." prefix come back through the ActionInvoked signal. */
    GVariantBuilder buttons;
    g_variant_builder_init(&buttons, G_VARIANT_TYPE("aa{sv}"));
    gboolean has_buttons = FALSE;
    jsize num_actions =
        (j_action_keys != NULL && j_action_labels != NULL) ? (*env)->GetArrayLength(env, j_action_keys) : 0;
    for (jsize i = 0; i < num_actions; i++) {
        jstring jk = (jstring)(*env)->GetObjectArrayElement(env, j_action_keys, i);
        jstring jl = (jstring)(*env)->GetObjectArrayElement(env, j_action_labels, i);
        gchar *key = jstring_to_utf8(env, jk);
        gchar *label = jstring_to_utf8(env, jl);
        if (key != NULL && strcmp(key, "default") == 0) {
            g_variant_builder_add(&notification, "{sv}", "default-action", g_variant_new_string(key));
        } else if (key != NULL && label != NULL) {
            GVariantBuilder button;
            g_variant_builder_init(&button, G_VARIANT_TYPE("a{sv}"));
            g_variant_builder_add(&button, "{sv}", "label", g_variant_new_string(label));
            g_variant_builder_add(&button, "{sv}", "action", g_variant_new_string(key));
            g_variant_builder_add(&buttons, "a{sv}", &button);
            has_buttons = TRUE;
        }
        g_free(key);
        g_free(label);
        (*env)->DeleteLocalRef(env, jk);
        (*env)->DeleteLocalRef(env, jl);
    }
    GVariant *buttons_value = g_variant_builder_end(&buttons);
    if (has_buttons) {
        g_variant_builder_add(&notification, "{sv}", "buttons", buttons_value);
    } else {
        g_variant_unref(g_variant_ref_sink(buttons_value));
    }

    GError *error = NULL;
    GVariant *result = g_dbus_connection_call_sync(conn,
        PORTAL_NAME, PORTAL_PATH, PORTAL_INTERFACE,
        "AddNotification", g_variant_new("(s@a{sv})", id_str, g_variant_builder_end(&notification)),
        NULL, G_DBUS_CALL_FLAGS_NONE, -1, NULL, &error);
    if (error != NULL) {
        g_error_free(error);
        return 0;
    }
    if (result != NULL) g_variant_unref(result);
    return id;
}

/* ==== Notify =========================================================== */

static void add_string_hint(JNIEnv *env, GVariantBuilder *builder,
                            const char *key, jstring value) {
    gchar *val = jstring_to_utf8(env, value);
    if (val == NULL) return;
    g_variant_builder_add(builder, "{sv}", key, g_variant_new_string(val));
    g_free(val);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_notification_linux_NativeLinuxNotificationBridge_nativeNotify(
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
    gchar *app_name = jstring_to_utf8(env, j_app_name);
    gchar *app_icon = jstring_to_utf8(env, j_app_icon);
    gchar *summary  = jstring_to_utf8(env, j_summary);
    gchar *body     = jstring_to_utf8(env, j_body);
    if (!app_name || !app_icon || !summary || !body) goto release_strings;

    if (use_portal()) {
        jint id = portal_notify(env, conn, replaces_id, app_icon, summary, body,
                                j_action_keys, j_action_labels, urgency, j_image_path);
        g_free(app_name);
        g_free(app_icon);
        g_free(summary);
        g_free(body);
        return id;
    }

    /* ---- Build actions array (as) ---- */
    GVariantBuilder actions_builder;
    g_variant_builder_init(&actions_builder, G_VARIANT_TYPE("as"));

    if (j_action_keys != NULL && j_action_labels != NULL) {
        jsize num_actions = (*env)->GetArrayLength(env, j_action_keys);
        for (jsize i = 0; i < num_actions; i++) {
            jstring jk = (jstring)(*env)->GetObjectArrayElement(env, j_action_keys, i);
            jstring jl = (jstring)(*env)->GetObjectArrayElement(env, j_action_labels, i);
            gchar *key   = jstring_to_utf8(env, jk);
            gchar *label = jstring_to_utf8(env, jl);
            if (key)   g_variant_builder_add(&actions_builder, "s", key);
            if (label) g_variant_builder_add(&actions_builder, "s", label);
            g_free(key);
            g_free(label);
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

    /* STRING hints */
    add_string_hint(env, &hints_builder, "category", j_category);
    add_string_hint(env, &hints_builder, "desktop-entry", j_desktop_entry);
    add_string_hint(env, &hints_builder, "image-path", j_image_path);
    add_string_hint(env, &hints_builder, "sound-file", j_sound_file);
    add_string_hint(env, &hints_builder, "sound-name", j_sound_name);

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

    /* g_variant_new copied the mandatory strings */
    g_free(app_name);
    g_free(app_icon);
    g_free(summary);
    g_free(body);

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
    g_free(app_name);
    g_free(app_icon);
    g_free(summary);
    g_free(body);
    return 0;
}

/* ==== CloseNotification ================================================ */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_notification_linux_NativeLinuxNotificationBridge_nativeCloseNotification(
    JNIEnv *env, jclass clazz, jint id)
{
    (void)env; (void)clazz;

    GDBusConnection *conn = get_connection();
    if (conn == NULL) return;

    if (use_portal()) {
        gchar id_str[16];
        g_snprintf(id_str, sizeof id_str, "%d", (int)id);
        GVariant *removed = g_dbus_connection_call_sync(conn,
            PORTAL_NAME, PORTAL_PATH, PORTAL_INTERFACE,
            "RemoveNotification", g_variant_new("(s)", id_str),
            NULL, G_DBUS_CALL_FLAGS_NONE, -1, NULL, NULL);
        if (removed != NULL) g_variant_unref(removed);
        return;
    }

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

/* The portal has no GetCapabilities / GetServerInformation: answer with what it supports. */
static jobjectArray new_string_array(JNIEnv *env, const char *const *values, jsize count) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, count, string_class, NULL);
    for (jsize i = 0; i < count; i++) {
        jstring value = utf8_to_jstring(env, values[i]);
        (*env)->SetObjectArrayElement(env, arr, i, value);
        (*env)->DeleteLocalRef(env, value);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_notification_linux_NativeLinuxNotificationBridge_nativeGetCapabilities(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;

    if (use_portal()) {
        static const char *const portal_capabilities[] = { "actions", "body" };
        return new_string_array(env, portal_capabilities, 2);
    }

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
        jstring j_cap = utf8_to_jstring(env, cap);
        (*env)->SetObjectArrayElement(env, arr, i++, j_cap);
        (*env)->DeleteLocalRef(env, j_cap);
    }

    g_variant_iter_free(iter);
    g_variant_unref(result);
    return arr;
}

/* ==== GetServerInformation ============================================= */

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_notification_linux_NativeLinuxNotificationBridge_nativeGetServerInformation(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;

    if (use_portal()) {
        static const char *const portal_information[] = { "xdg-desktop-portal", "freedesktop.org", "1", "1.2" };
        return new_string_array(env, portal_information, 4);
    }

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

    jstring j0 = utf8_to_jstring(env, name);
    jstring j1 = utf8_to_jstring(env, vendor);
    jstring j2 = utf8_to_jstring(env, version);
    jstring j3 = utf8_to_jstring(env, spec_version);

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
        nucleus_jni_clear_exception(env);
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
        jstring j_key = utf8_to_jstring(env, action_key);
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_action_method,
            (jint)id, j_key);
        nucleus_jni_clear_exception(env);
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
        jstring j_token = utf8_to_jstring(env, token);
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_token_method,
            (jint)id, j_token);
        nucleus_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, j_token);
    }

    release_env(attached);
}

/*
 * The portal's ActionInvoked(s id, s action, av parameter). Version 2 portals append the platform
 * data (a{sv}) to the parameter, with the activation token: hand it over first, like the server's
 * ActivationToken signal, which comes before its ActionInvoked.
 */
static void on_portal_action_invoked(
    GDBusConnection *conn, const gchar *sender,
    const gchar *path, const gchar *iface, const gchar *signal_name,
    GVariant *params, gpointer user_data)
{
    (void)conn; (void)sender; (void)path; (void)iface;
    (void)signal_name; (void)user_data;

    const gchar *id_str = NULL, *action_key = NULL;
    GVariant *parameter = NULL;
    g_variant_get(params, "(&s&s@av)", &id_str, &action_key, &parameter);
    gint64 id = g_ascii_strtoll(id_str, NULL, 10);

    const gchar *token = NULL;
    GVariantIter iter;
    GVariant *element = NULL;
    g_variant_iter_init(&iter, parameter);
    while (token == NULL && g_variant_iter_next(&iter, "v", &element)) {
        if (g_variant_is_of_type(element, G_VARIANT_TYPE_VARDICT)) {
            g_variant_lookup(element, "activation-token", "&s", &token);
        }
        if (token == NULL) g_variant_unref(element);
    }

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env != NULL && id > 0 && ensure_callback_ids(env)) {
        if (token != NULL && token[0] != '\0') {
            jstring j_token = utf8_to_jstring(env, token);
            (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_token_method, (jint)id, j_token);
            nucleus_jni_clear_exception(env);
            (*env)->DeleteLocalRef(env, j_token);
        }
        jstring j_key = utf8_to_jstring(env, action_key);
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_action_method, (jint)id, j_key);
        nucleus_jni_clear_exception(env);
        (*env)->DeleteLocalRef(env, j_key);
    }
    if (env != NULL) release_env(attached);

    if (token != NULL) g_variant_unref(element);
    g_variant_unref(parameter);
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

    /* Subscribe to all three notification signals, or to the portal's only one */
    guint sub_portal = 0, sub_closed = 0, sub_action = 0, sub_token = 0;
    if (use_portal()) {
        sub_portal = g_dbus_connection_signal_subscribe(conn,
            PORTAL_NAME, PORTAL_INTERFACE, "ActionInvoked", PORTAL_PATH,
            NULL, G_DBUS_SIGNAL_FLAGS_NONE,
            on_portal_action_invoked, NULL, NULL);
    } else {
        sub_closed = g_dbus_connection_signal_subscribe(conn,
            NULL, INTERFACE, "NotificationClosed", OBJECT_PATH,
            NULL, G_DBUS_SIGNAL_FLAGS_NONE,
            on_notification_closed, NULL, NULL);

        sub_action = g_dbus_connection_signal_subscribe(conn,
            NULL, INTERFACE, "ActionInvoked", OBJECT_PATH,
            NULL, G_DBUS_SIGNAL_FLAGS_NONE,
            on_action_invoked, NULL, NULL);

        sub_token = g_dbus_connection_signal_subscribe(conn,
            NULL, INTERFACE, "ActivationToken", OBJECT_PATH,
            NULL, G_DBUS_SIGNAL_FLAGS_NONE,
            on_activation_token, NULL, NULL);
    }

    GMainLoop *loop = g_main_loop_new(ctx, FALSE);

    /* Signal the calling thread that we're ready */
    pthread_mutex_lock(&g_listen_mutex);
    g_signal_loop = loop;
    pthread_cond_broadcast(&g_listen_cond);
    pthread_mutex_unlock(&g_listen_mutex);

    /* Block until g_main_loop_quit() is called */
    g_main_loop_run(loop);

    /* Cleanup subscriptions */
    if (sub_portal) g_dbus_connection_signal_unsubscribe(conn, sub_portal);
    if (sub_closed) g_dbus_connection_signal_unsubscribe(conn, sub_closed);
    if (sub_action) g_dbus_connection_signal_unsubscribe(conn, sub_action);
    if (sub_token) g_dbus_connection_signal_unsubscribe(conn, sub_token);

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
Java_dev_nucleusframework_notification_linux_NativeLinuxNotificationBridge_nativeStartListening(
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
Java_dev_nucleusframework_notification_linux_NativeLinuxNotificationBridge_nativeStopListening(
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
