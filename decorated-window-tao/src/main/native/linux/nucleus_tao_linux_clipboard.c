/**
 * JNI bridge: GTK clipboard access for the Tao Linux backend (issue #582).
 *
 * Compose Desktop's only clipboard implementation goes through
 * `java.awt.Toolkit.getSystemClipboard()`, which on Linux is X11-only
 * (`sun.awt.X11.XToolkit`). A Tao window is a GTK window on whichever GDK
 * backend the session provides — Wayland by default — so the AWT clipboard
 * ends up reading a *different* selection than the one the window lives on.
 * On KWin the Wayland selection is only published to XWayland while an X11
 * window is active, which makes paste fail in the ordinary case, and with no
 * XWayland at all `getSystemClipboard()` throws `HeadlessException`.
 *
 * GDK is the right layer to fix this at: it speaks the *current* backend
 * (`wl_data_device` with the window's own seat on Wayland, `CLIPBOARD` on
 * X11), needs no `wlr-data-control` (unlike `wl-clipboard` / `arboard`), does
 * not fork a helper process to serve paste requests, and serves the data from
 * this process — the same path every other GTK app takes.
 *
 * Text is exchanged as UTF-8 **byte arrays** rather than `jstring`, because
 * `GetStringUTFChars` produces modified UTF-8 (CESU-8): a jstring round-trip
 * corrupts every non-BMP character (emoji) and truncates embedded NULs.
 *
 * Linked libraries: -ldl. libgtk-3.so.0 / libglib-2.0.so.0 are dlopen-ed at
 * runtime, like the other helpers in this directory. GDK symbols resolve
 * through the libgtk handle (dlsym searches the handle's dependency chain).
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose's `Dispatchers.Main`).
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <dlfcn.h>

/* ── GTK / GDK / GLib types (forward-declared: no dev headers needed) ── */

typedef int   gint;
typedef int   gboolean;
typedef char  gchar;
typedef void  GtkClipboard; /* opaque */
typedef void  GdkDisplay;   /* opaque */
typedef void *GdkAtom;      /* GdkAtom is a pointer-sized handle in GTK 3 */

/* GtkClipboardTextReceivedFunc */
typedef void (*TextReceivedFunc)(GtkClipboard *clipboard, const gchar *text, void *data);

typedef GtkClipboard *(*PFN_gtk_clipboard_get)(GdkAtom selection);
typedef void          (*PFN_gtk_clipboard_set_text)(GtkClipboard *c, const gchar *text, gint len);
typedef void          (*PFN_gtk_clipboard_clear)(GtkClipboard *c);
typedef void          (*PFN_gtk_clipboard_request_text)(
    GtkClipboard *c, TextReceivedFunc callback, void *user_data);
typedef gchar        *(*PFN_gtk_clipboard_wait_for_text)(GtkClipboard *c);
typedef gboolean      (*PFN_gtk_clipboard_wait_is_text_available)(GtkClipboard *c);
typedef GdkAtom       (*PFN_gdk_atom_intern)(const gchar *atom_name, gboolean only_if_exists);
typedef GdkDisplay   *(*PFN_gdk_display_get_default)(void);
typedef void          (*PFN_g_free)(void *mem);

static struct {
    int initialized;
    PFN_gtk_clipboard_get                    gtk_clipboard_get;
    PFN_gtk_clipboard_set_text               gtk_clipboard_set_text;
    PFN_gtk_clipboard_clear                  gtk_clipboard_clear;
    PFN_gtk_clipboard_request_text           gtk_clipboard_request_text;
    PFN_gtk_clipboard_wait_for_text          gtk_clipboard_wait_for_text;
    PFN_gtk_clipboard_wait_is_text_available gtk_clipboard_wait_is_text_available;
    PFN_gdk_atom_intern                      gdk_atom_intern;
    PFN_gdk_display_get_default              gdk_display_get_default;
    PFN_g_free                               g_free;
} g;

static void *load_first(const char *const *names) {
    for (int i = 0; names[i] != NULL; i++) {
        /* RTLD_LOCAL: keep GTK's closure out of the global symbol scope —
         * on NixOS it pulls libsqlite3, which interposes the sqlite bundled
         * in androidx/Room's JNI lib and segfaults (issue #366). */
        void *h = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (h != NULL) return h;
    }
    return NULL;
}

static int ensure_gtk_loaded(void) {
    if (g.initialized) return 1;
    const char *gtk_libs[]  = { "libgtk-3.so.0", "libgtk-3.so", NULL };
    const char *glib_libs[] = { "libglib-2.0.so.0", "libglib-2.0.so", NULL };
    void *libgtk  = load_first(gtk_libs);
    void *libglib = load_first(glib_libs);
    if (libgtk == NULL || libglib == NULL) return 0;

    g.gtk_clipboard_get          = (PFN_gtk_clipboard_get)          dlsym(libgtk, "gtk_clipboard_get");
    g.gtk_clipboard_set_text     = (PFN_gtk_clipboard_set_text)     dlsym(libgtk, "gtk_clipboard_set_text");
    g.gtk_clipboard_clear        = (PFN_gtk_clipboard_clear)        dlsym(libgtk, "gtk_clipboard_clear");
    g.gtk_clipboard_request_text = (PFN_gtk_clipboard_request_text) dlsym(libgtk, "gtk_clipboard_request_text");
    g.gtk_clipboard_wait_for_text =
        (PFN_gtk_clipboard_wait_for_text) dlsym(libgtk, "gtk_clipboard_wait_for_text");
    g.gtk_clipboard_wait_is_text_available =
        (PFN_gtk_clipboard_wait_is_text_available) dlsym(libgtk, "gtk_clipboard_wait_is_text_available");
    /* gdk_* live in libgdk-3.so.0, which libgtk-3 depends on — dlsym on the
     * libgtk handle searches that dependency chain. */
    g.gdk_atom_intern            = (PFN_gdk_atom_intern)            dlsym(libgtk, "gdk_atom_intern");
    g.gdk_display_get_default    = (PFN_gdk_display_get_default)    dlsym(libgtk, "gdk_display_get_default");
    g.g_free                     = (PFN_g_free)                     dlsym(libglib, "g_free");

    if (!g.gtk_clipboard_get || !g.gtk_clipboard_set_text || !g.gtk_clipboard_clear ||
        !g.gtk_clipboard_request_text || !g.gtk_clipboard_wait_for_text ||
        !g.gtk_clipboard_wait_is_text_available || !g.gdk_atom_intern ||
        !g.gdk_display_get_default || !g.g_free) {
        return 0;
    }
    g.initialized = 1;
    return 1;
}

/**
 * The `CLIPBOARD` selection of the default display, or NULL when GTK never
 * got initialised (Tao owns `gtk_init`; without a display
 * `gdk_display_get_default` returns NULL and `gtk_clipboard_get` would abort
 * on a GDK assertion).
 */
static GtkClipboard *clipboard_or_null(void) {
    if (!ensure_gtk_loaded()) return NULL;
    if (g.gdk_display_get_default() == NULL) return NULL;
    return g.gtk_clipboard_get(g.gdk_atom_intern("CLIPBOARD", 0 /* only_if_exists */));
}

/* ── JNI callback plumbing (async text reads) ───────────────────────── */

static JavaVM *sJVM = NULL;

static void ensure_jvm(JNIEnv *env) {
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
}

/**
 * Resolved per call rather than cached: the callback is a `fun interface`, so
 * its implementation class differs per call site (one synthetic class per
 * lambda) and a jmethodID looked up on one of them must not be used to invoke
 * another. One extra lookup per clipboard read is free.
 */
static jmethodID on_text_method(JNIEnv *env, jobject callback) {
    jclass clazz = (*env)->GetObjectClass(env, callback);
    if (clazz == NULL) return NULL;
    jmethodID method = (*env)->GetMethodID(env, clazz, "onText", "([B)V");
    (*env)->DeleteLocalRef(env, clazz);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return method;
}

static JNIEnv *attach_jvm_thread(void) {
    if (sJVM == NULL) return NULL;
    JNIEnv *env = NULL;
    jint status = (*sJVM)->GetEnv(sJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) != JNI_OK) return NULL;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static jbyteArray to_byte_array(JNIEnv *env, const gchar *text) {
    if (text == NULL) return NULL;
    size_t len = strlen(text);
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr == NULL) return NULL;
    if (len > 0) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) text);
    return arr;
}

/**
 * GTK hands the selection contents (or NULL when there is no text on the
 * clipboard) to this on the main loop, possibly synchronously when we own the
 * selection ourselves. One-shot: the global ref taken by
 * `nativeRequestTextUtf8` is released here, whichever way it goes.
 */
static void on_text_received(GtkClipboard *clipboard, const gchar *text, void *data) {
    (void) clipboard;
    jobject callback = (jobject) data;
    if (callback == NULL) return;
    JNIEnv *env = attach_jvm_thread();
    if (env == NULL) return; /* No JVM to release the ref through either. */
    jmethodID method = on_text_method(env, callback);
    if (method != NULL) {
        jbyteArray arr = to_byte_array(env, text);
        (*env)->CallVoidMethod(env, callback, method, arr);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (arr != NULL) (*env)->DeleteLocalRef(env, arr);
    }
    (*env)->DeleteGlobalRef(env, callback);
}

/* ── JNI entry points ───────────────────────────────────────────────── */

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeIsAvailable(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    return clipboard_or_null() != NULL ? JNI_TRUE : JNI_FALSE;
}

EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeSetTextUtf8(
    JNIEnv *env, jclass clazz, jbyteArray utf8)
{
    (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL || utf8 == NULL) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, utf8);
    jbyte *bytes = (*env)->GetByteArrayElements(env, utf8, NULL);
    if (bytes == NULL) return JNI_FALSE;
    /* gtk_clipboard_set_text copies the text and answers paste requests from
     * GTK's own selection handler, so nothing has to stay alive on our side. */
    g.gtk_clipboard_set_text(clipboard, (const gchar *) bytes, (gint) len);
    (*env)->ReleaseByteArrayElements(env, utf8, bytes, JNI_ABORT);
    return JNI_TRUE;
}

/** Drops our ownership of the selection. No-op when another app owns it. */
EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeClear(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard != NULL) g.gtk_clipboard_clear(clipboard);
}

/**
 * Starts an asynchronous read. Returns JNI_FALSE when the request could not be
 * handed to GTK at all — the callback will never fire in that case and the
 * caller has to resume itself (see `NativeTaoLinuxClipboardBridge`).
 */
EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeRequestTextUtf8(
    JNIEnv *env, jclass clazz, jobject callback)
{
    (void) clazz;
    if (callback == NULL) return JNI_FALSE;
    GtkClipboard *clipboard = clipboard_or_null();
    ensure_jvm(env);
    if (clipboard == NULL || on_text_method(env, callback) == NULL) return JNI_FALSE;
    jobject global = (*env)->NewGlobalRef(env, callback);
    if (global == NULL) return JNI_FALSE;
    g.gtk_clipboard_request_text(clipboard, on_text_received, global);
    return JNI_TRUE;
}

/**
 * Synchronous read. GTK spins a nested main loop until the owner answers, so
 * this is only for the deprecated `ClipboardManager` path — the suspending
 * `Clipboard` API uses `nativeRequestTextUtf8`.
 */
EXPORT jbyteArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeWaitForTextUtf8(
    JNIEnv *env, jclass clazz)
{
    (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL) return NULL;
    gchar *text = g.gtk_clipboard_wait_for_text(clipboard);
    if (text == NULL) return NULL;
    jbyteArray arr = to_byte_array(env, text);
    g.g_free(text);
    return arr;
}

/** `gtk_clipboard_wait_is_text_available` — also spins a nested main loop. */
EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeHasText(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL) return JNI_FALSE;
    return g.gtk_clipboard_wait_is_text_available(clipboard) ? JNI_TRUE : JNI_FALSE;
}
