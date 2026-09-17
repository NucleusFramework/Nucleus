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
 * Formats carried here match what AWT's clipboard exposes, so nothing is lost
 * by taking this path instead:
 *
 *   - text     `gtk_clipboard_set_text` / `request_text`; GTK registers every
 *              text alias (UTF8_STRING, STRING, TEXT, text/plain…) itself.
 *   - images   `gtk_clipboard_set_image` / `request_image` through GdkPixbuf,
 *              which is what makes GTK advertise image/png, image/bmp,
 *              image/jpeg and image/tiff and convert between them on demand.
 *              PNG is the exchange format across JNI.
 *   - files    `text/uri-list` (plus `x-special/gnome-copied-files`, which is
 *              what file managers actually paste), served from a payload this
 *              file owns until the selection changes hands.
 *
 * Reads come in two shapes: an asynchronous one that hands the bytes to a
 * callback (used by the suspending Kotlin API) and a `wait_*` one that spins a
 * nested GTK main loop (used when a `Transferable` is read from a thread that
 * cannot suspend). Every payload crosses as **bytes**, never `jstring`:
 * `GetStringUTFChars` produces modified UTF-8 (CESU-8), which corrupts every
 * non-BMP character (emoji) and truncates embedded NULs.
 *
 * Linked libraries: -ldl. libgtk-3.so.0 / libglib-2.0.so.0 /
 * libgobject-2.0.so.0 / libgdk_pixbuf-2.0.so.0 are dlopen-ed at runtime, like
 * the other helpers in this directory. GDK symbols resolve through the libgtk
 * handle (dlsym searches the handle's dependency chain).
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose's `Dispatchers.Main`).
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

/* ── GTK / GDK / GLib types (forward-declared: no dev headers needed) ── */

typedef int           gint;
typedef unsigned int  guint;
typedef int           gboolean;
typedef char          gchar;
typedef unsigned char guchar;
typedef unsigned long gsize;
typedef void          GtkClipboard;     /* opaque */
typedef void          GdkDisplay;       /* opaque */
typedef void          GdkPixbuf;        /* opaque */
typedef void          GdkPixbufLoader;  /* opaque */
typedef void          GtkSelectionData; /* opaque */
typedef void          GError;           /* opaque */
typedef void         *GdkAtom;          /* GdkAtom is a pointer-sized handle in GTK 3 */

/** GtkTargetEntry, GTK 3 ABI. */
typedef struct {
    gchar *target;
    guint flags;
    guint info;
} GtkTargetEntry;

typedef void (*TextReceivedFunc)(GtkClipboard *clipboard, const gchar *text, void *data);
typedef void (*ImageReceivedFunc)(GtkClipboard *clipboard, GdkPixbuf *pixbuf, void *data);
typedef void (*UrisReceivedFunc)(GtkClipboard *clipboard, gchar **uris, void *data);
typedef void (*TargetsReceivedFunc)(GtkClipboard *clipboard, GdkAtom *atoms, gint n_atoms, void *data);
typedef void (*ClipboardGetFunc)(
    GtkClipboard *clipboard, GtkSelectionData *selection_data, guint info, void *user_data);
typedef void (*ClipboardClearFunc)(GtkClipboard *clipboard, void *user_data);

typedef GtkClipboard *(*PFN_gtk_clipboard_get)(GdkAtom selection);
typedef void          (*PFN_gtk_clipboard_set_text)(GtkClipboard *c, const gchar *text, gint len);
typedef void          (*PFN_gtk_clipboard_set_image)(GtkClipboard *c, GdkPixbuf *pixbuf);
typedef gboolean      (*PFN_gtk_clipboard_set_with_data)(
    GtkClipboard *c, const GtkTargetEntry *targets, guint n_targets,
    ClipboardGetFunc get_func, ClipboardClearFunc clear_func, void *user_data);
typedef void          (*PFN_gtk_clipboard_clear)(GtkClipboard *c);
typedef void          (*PFN_gtk_clipboard_request_text)(GtkClipboard *c, TextReceivedFunc cb, void *data);
typedef void          (*PFN_gtk_clipboard_request_image)(GtkClipboard *c, ImageReceivedFunc cb, void *data);
typedef void          (*PFN_gtk_clipboard_request_uris)(GtkClipboard *c, UrisReceivedFunc cb, void *data);
typedef void          (*PFN_gtk_clipboard_request_targets)(GtkClipboard *c, TargetsReceivedFunc cb, void *data);
typedef gchar        *(*PFN_gtk_clipboard_wait_for_text)(GtkClipboard *c);
typedef GdkPixbuf    *(*PFN_gtk_clipboard_wait_for_image)(GtkClipboard *c);
typedef gchar       **(*PFN_gtk_clipboard_wait_for_uris)(GtkClipboard *c);
typedef gboolean      (*PFN_gtk_clipboard_wait_is_text_available)(GtkClipboard *c);
typedef void          (*PFN_gtk_selection_data_set)(
    GtkSelectionData *sel, GdkAtom type, gint format, const guchar *data, gint length);
typedef GdkAtom       (*PFN_gtk_selection_data_get_target)(GtkSelectionData *sel);
typedef GdkAtom       (*PFN_gdk_atom_intern)(const gchar *atom_name, gboolean only_if_exists);
typedef gchar        *(*PFN_gdk_atom_name)(GdkAtom atom);
typedef GdkDisplay   *(*PFN_gdk_display_get_default)(void);
typedef GdkPixbufLoader *(*PFN_gdk_pixbuf_loader_new)(void);
typedef gboolean      (*PFN_gdk_pixbuf_loader_write)(
    GdkPixbufLoader *loader, const guchar *buf, gsize count, GError **error);
typedef gboolean      (*PFN_gdk_pixbuf_loader_close)(GdkPixbufLoader *loader, GError **error);
typedef GdkPixbuf    *(*PFN_gdk_pixbuf_loader_get_pixbuf)(GdkPixbufLoader *loader);
typedef gboolean      (*PFN_gdk_pixbuf_save_to_buffer)(
    GdkPixbuf *pixbuf, gchar **buffer, gsize *size, const char *type, GError **error, ...);
typedef void          (*PFN_g_free)(void *mem);
typedef void          (*PFN_g_strfreev)(gchar **str_array);
typedef void          (*PFN_g_object_unref)(void *object);

static struct {
    int initialized;
    PFN_gtk_clipboard_get                    gtk_clipboard_get;
    PFN_gtk_clipboard_set_text               gtk_clipboard_set_text;
    PFN_gtk_clipboard_set_image              gtk_clipboard_set_image;
    PFN_gtk_clipboard_set_with_data          gtk_clipboard_set_with_data;
    PFN_gtk_clipboard_clear                  gtk_clipboard_clear;
    PFN_gtk_clipboard_request_text           gtk_clipboard_request_text;
    PFN_gtk_clipboard_request_image          gtk_clipboard_request_image;
    PFN_gtk_clipboard_request_uris           gtk_clipboard_request_uris;
    PFN_gtk_clipboard_request_targets        gtk_clipboard_request_targets;
    PFN_gtk_clipboard_wait_for_text          gtk_clipboard_wait_for_text;
    PFN_gtk_clipboard_wait_for_image         gtk_clipboard_wait_for_image;
    PFN_gtk_clipboard_wait_for_uris          gtk_clipboard_wait_for_uris;
    PFN_gtk_clipboard_wait_is_text_available gtk_clipboard_wait_is_text_available;
    PFN_gtk_selection_data_set               gtk_selection_data_set;
    PFN_gtk_selection_data_get_target        gtk_selection_data_get_target;
    PFN_gdk_atom_intern                      gdk_atom_intern;
    PFN_gdk_atom_name                        gdk_atom_name;
    PFN_gdk_display_get_default              gdk_display_get_default;
    PFN_gdk_pixbuf_loader_new                gdk_pixbuf_loader_new;
    PFN_gdk_pixbuf_loader_write              gdk_pixbuf_loader_write;
    PFN_gdk_pixbuf_loader_close              gdk_pixbuf_loader_close;
    PFN_gdk_pixbuf_loader_get_pixbuf         gdk_pixbuf_loader_get_pixbuf;
    PFN_gdk_pixbuf_save_to_buffer            gdk_pixbuf_save_to_buffer;
    PFN_g_free                               g_free;
    PFN_g_strfreev                           g_strfreev;
    PFN_g_object_unref                       g_object_unref;
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
    const char *gtk_libs[]    = { "libgtk-3.so.0", "libgtk-3.so", NULL };
    const char *glib_libs[]   = { "libglib-2.0.so.0", "libglib-2.0.so", NULL };
    const char *gobject_libs[] = { "libgobject-2.0.so.0", "libgobject-2.0.so", NULL };
    const char *pixbuf_libs[] = { "libgdk_pixbuf-2.0.so.0", "libgdk_pixbuf-2.0.so", NULL };
    void *libgtk     = load_first(gtk_libs);
    void *libglib    = load_first(glib_libs);
    void *libgobject = load_first(gobject_libs);
    void *libpixbuf  = load_first(pixbuf_libs);
    if (libgtk == NULL || libglib == NULL || libgobject == NULL || libpixbuf == NULL) return 0;

    g.gtk_clipboard_get          = (PFN_gtk_clipboard_get)          dlsym(libgtk, "gtk_clipboard_get");
    g.gtk_clipboard_set_text     = (PFN_gtk_clipboard_set_text)     dlsym(libgtk, "gtk_clipboard_set_text");
    g.gtk_clipboard_set_image    = (PFN_gtk_clipboard_set_image)    dlsym(libgtk, "gtk_clipboard_set_image");
    g.gtk_clipboard_set_with_data =
        (PFN_gtk_clipboard_set_with_data) dlsym(libgtk, "gtk_clipboard_set_with_data");
    g.gtk_clipboard_clear        = (PFN_gtk_clipboard_clear)        dlsym(libgtk, "gtk_clipboard_clear");
    g.gtk_clipboard_request_text = (PFN_gtk_clipboard_request_text) dlsym(libgtk, "gtk_clipboard_request_text");
    g.gtk_clipboard_request_image =
        (PFN_gtk_clipboard_request_image) dlsym(libgtk, "gtk_clipboard_request_image");
    g.gtk_clipboard_request_uris = (PFN_gtk_clipboard_request_uris) dlsym(libgtk, "gtk_clipboard_request_uris");
    g.gtk_clipboard_request_targets =
        (PFN_gtk_clipboard_request_targets) dlsym(libgtk, "gtk_clipboard_request_targets");
    g.gtk_clipboard_wait_for_text =
        (PFN_gtk_clipboard_wait_for_text) dlsym(libgtk, "gtk_clipboard_wait_for_text");
    g.gtk_clipboard_wait_for_image =
        (PFN_gtk_clipboard_wait_for_image) dlsym(libgtk, "gtk_clipboard_wait_for_image");
    g.gtk_clipboard_wait_for_uris =
        (PFN_gtk_clipboard_wait_for_uris) dlsym(libgtk, "gtk_clipboard_wait_for_uris");
    g.gtk_clipboard_wait_is_text_available =
        (PFN_gtk_clipboard_wait_is_text_available) dlsym(libgtk, "gtk_clipboard_wait_is_text_available");
    g.gtk_selection_data_set     = (PFN_gtk_selection_data_set)     dlsym(libgtk, "gtk_selection_data_set");
    g.gtk_selection_data_get_target =
        (PFN_gtk_selection_data_get_target) dlsym(libgtk, "gtk_selection_data_get_target");
    /* gdk_* live in libgdk-3.so.0, which libgtk-3 depends on — dlsym on the
     * libgtk handle searches that dependency chain. */
    g.gdk_atom_intern            = (PFN_gdk_atom_intern)            dlsym(libgtk, "gdk_atom_intern");
    g.gdk_atom_name              = (PFN_gdk_atom_name)              dlsym(libgtk, "gdk_atom_name");
    g.gdk_display_get_default    = (PFN_gdk_display_get_default)    dlsym(libgtk, "gdk_display_get_default");

    g.gdk_pixbuf_loader_new      = (PFN_gdk_pixbuf_loader_new)      dlsym(libpixbuf, "gdk_pixbuf_loader_new");
    g.gdk_pixbuf_loader_write    = (PFN_gdk_pixbuf_loader_write)    dlsym(libpixbuf, "gdk_pixbuf_loader_write");
    g.gdk_pixbuf_loader_close    = (PFN_gdk_pixbuf_loader_close)    dlsym(libpixbuf, "gdk_pixbuf_loader_close");
    g.gdk_pixbuf_loader_get_pixbuf =
        (PFN_gdk_pixbuf_loader_get_pixbuf) dlsym(libpixbuf, "gdk_pixbuf_loader_get_pixbuf");
    g.gdk_pixbuf_save_to_buffer  = (PFN_gdk_pixbuf_save_to_buffer)  dlsym(libpixbuf, "gdk_pixbuf_save_to_buffer");

    g.g_free                     = (PFN_g_free)                     dlsym(libglib, "g_free");
    g.g_strfreev                 = (PFN_g_strfreev)                 dlsym(libglib, "g_strfreev");
    g.g_object_unref             = (PFN_g_object_unref)             dlsym(libgobject, "g_object_unref");

    if (!g.gtk_clipboard_get || !g.gtk_clipboard_set_text || !g.gtk_clipboard_set_image ||
        !g.gtk_clipboard_set_with_data || !g.gtk_clipboard_clear ||
        !g.gtk_clipboard_request_text || !g.gtk_clipboard_request_image ||
        !g.gtk_clipboard_request_uris || !g.gtk_clipboard_request_targets ||
        !g.gtk_clipboard_wait_for_text || !g.gtk_clipboard_wait_for_image ||
        !g.gtk_clipboard_wait_for_uris || !g.gtk_clipboard_wait_is_text_available ||
        !g.gtk_selection_data_set || !g.gtk_selection_data_get_target ||
        !g.gdk_atom_intern || !g.gdk_atom_name || !g.gdk_display_get_default ||
        !g.gdk_pixbuf_loader_new || !g.gdk_pixbuf_loader_write || !g.gdk_pixbuf_loader_close ||
        !g.gdk_pixbuf_loader_get_pixbuf || !g.gdk_pixbuf_save_to_buffer ||
        !g.g_free || !g.g_strfreev || !g.g_object_unref) {
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

/* ── JNI plumbing ───────────────────────────────────────────────────── */

static JavaVM *sJVM = NULL;

static void ensure_jvm(JNIEnv *env) {
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
}

/**
 * Resolved per call rather than cached: the callback interface has one
 * implementation class per call site, and a jmethodID looked up on one of them
 * must not be used to invoke another. One extra lookup per clipboard read is
 * free next to the round-trip it accompanies.
 */
static jmethodID on_bytes_method(JNIEnv *env, jobject callback) {
    jclass clazz = (*env)->GetObjectClass(env, callback);
    if (clazz == NULL) return NULL;
    jmethodID method = (*env)->GetMethodID(env, clazz, "onBytes", "([B)V");
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

static jbyteArray bytes_to_array(JNIEnv *env, const void *data, size_t len) {
    if (data == NULL) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr == NULL) return NULL;
    if (len > 0) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) data);
    return arr;
}

static jbyteArray string_to_array(JNIEnv *env, const gchar *text) {
    return text == NULL ? NULL : bytes_to_array(env, text, strlen(text));
}

/**
 * Joins a NULL-terminated string vector with '\n' into a freshly malloc-ed
 * buffer. Used for URI lists and target names, which are the only two
 * multi-valued payloads and never contain a newline themselves.
 */
static char *join_lines(gchar **items, size_t *out_len) {
    size_t total = 0;
    int count = 0;
    for (; items != NULL && items[count] != NULL; count++) total += strlen(items[count]) + 1;
    if (count == 0) {
        *out_len = 0;
        char *empty = malloc(1);
        if (empty != NULL) empty[0] = '\0';
        return empty;
    }
    char *buffer = malloc(total);
    if (buffer == NULL) return NULL;
    size_t offset = 0;
    for (int i = 0; i < count; i++) {
        size_t len = strlen(items[i]);
        memcpy(buffer + offset, items[i], len);
        offset += len;
        if (i + 1 < count) buffer[offset++] = '\n';
    }
    *out_len = offset;
    return buffer;
}

/** Invokes the one-shot callback and releases its global ref. */
static void deliver(jobject callback, const void *data, size_t len) {
    if (callback == NULL) return;
    JNIEnv *env = attach_jvm_thread();
    if (env == NULL) return; /* No JVM to release the ref through either. */
    jmethodID method = on_bytes_method(env, callback);
    if (method != NULL) {
        jbyteArray arr = bytes_to_array(env, data, len);
        (*env)->CallVoidMethod(env, callback, method, arr);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (arr != NULL) (*env)->DeleteLocalRef(env, arr);
    }
    (*env)->DeleteGlobalRef(env, callback);
}

/* ── Asynchronous receivers ─────────────────────────────────────────── */

static void on_text_received(GtkClipboard *clipboard, const gchar *text, void *data) {
    (void) clipboard;
    deliver((jobject) data, text, text == NULL ? 0 : strlen(text));
}

/**
 * Re-encodes whatever GTK decoded (bmp, tiff, jpeg…) as PNG, so the JVM side
 * has a single format to decode. The pixbuf belongs to GTK for the duration of
 * this callback only.
 */
static void on_image_received(GtkClipboard *clipboard, GdkPixbuf *pixbuf, void *data) {
    (void) clipboard;
    jobject callback = (jobject) data;
    if (pixbuf == NULL) {
        deliver(callback, NULL, 0);
        return;
    }
    gchar *png = NULL;
    gsize png_size = 0;
    if (!g.gdk_pixbuf_save_to_buffer(pixbuf, &png, &png_size, "png", NULL, NULL) || png == NULL) {
        deliver(callback, NULL, 0);
        return;
    }
    deliver(callback, png, (size_t) png_size);
    g.g_free(png);
}

static void on_uris_received(GtkClipboard *clipboard, gchar **uris, void *data) {
    (void) clipboard;
    jobject callback = (jobject) data;
    if (uris == NULL) {
        deliver(callback, NULL, 0);
        return;
    }
    size_t len = 0;
    char *joined = join_lines(uris, &len);
    deliver(callback, joined, len);
    free(joined);
}

static void on_targets_received(GtkClipboard *clipboard, GdkAtom *atoms, gint n_atoms, void *data) {
    (void) clipboard;
    jobject callback = (jobject) data;
    if (atoms == NULL || n_atoms <= 0) {
        deliver(callback, NULL, 0);
        return;
    }
    gchar **names = calloc((size_t) n_atoms + 1, sizeof(gchar *));
    if (names == NULL) {
        deliver(callback, NULL, 0);
        return;
    }
    for (gint i = 0; i < n_atoms; i++) names[i] = g.gdk_atom_name(atoms[i]);
    size_t len = 0;
    char *joined = join_lines(names, &len);
    deliver(callback, joined, len);
    free(joined);
    for (gint i = 0; i < n_atoms; i++) g.g_free(names[i]);
    free(names);
}

/* ── Owned payload for targets GTK cannot serve on its own ──────────── */

/**
 * Backing store for `gtk_clipboard_set_with_data`: GTK asks for one target at
 * a time and we answer from here, so the bytes have to outlive the call that
 * published them. Freed by [payload_clear] when the selection changes hands.
 */
typedef struct {
    int count;
    guchar **data;
    gsize *sizes;
    GtkTargetEntry *targets;
} payload_t;

static void payload_free(payload_t *payload) {
    if (payload == NULL) return;
    for (int i = 0; i < payload->count; i++) {
        free(payload->data[i]);
        free(payload->targets[i].target);
    }
    free(payload->data);
    free(payload->sizes);
    free(payload->targets);
    free(payload);
}

static void payload_get(GtkClipboard *clipboard, GtkSelectionData *selection, guint info, void *user_data) {
    (void) clipboard;
    payload_t *payload = (payload_t *) user_data;
    if (payload == NULL || (int) info >= payload->count) return;
    g.gtk_selection_data_set(
        selection, g.gtk_selection_data_get_target(selection), 8 /* bits per unit */,
        payload->data[info], (gint) payload->sizes[info]);
}

static void payload_clear(GtkClipboard *clipboard, void *user_data) {
    (void) clipboard;
    payload_free((payload_t *) user_data);
}

/** Takes ownership of `count` (mime, bytes) pairs and publishes them. */
static int publish_payload(
    GtkClipboard *clipboard, const char *const *mimes, const guchar *const *blobs,
    const gsize *sizes, int count)
{
    payload_t *payload = calloc(1, sizeof(payload_t));
    if (payload == NULL) return 0;
    payload->count = count;
    payload->data = calloc((size_t) count, sizeof(guchar *));
    payload->sizes = calloc((size_t) count, sizeof(gsize));
    payload->targets = calloc((size_t) count, sizeof(GtkTargetEntry));
    if (payload->data == NULL || payload->sizes == NULL || payload->targets == NULL) {
        payload_free(payload);
        return 0;
    }
    for (int i = 0; i < count; i++) {
        payload->data[i] = malloc(sizes[i] > 0 ? sizes[i] : 1);
        payload->targets[i].target = strdup(mimes[i]);
        if (payload->data[i] == NULL || payload->targets[i].target == NULL) {
            payload_free(payload);
            return 0;
        }
        memcpy(payload->data[i], blobs[i], sizes[i]);
        payload->sizes[i] = sizes[i];
        payload->targets[i].flags = 0;
        payload->targets[i].info = (guint) i;
    }
    if (!g.gtk_clipboard_set_with_data(
            clipboard, payload->targets, (guint) count, payload_get, payload_clear, payload)) {
        payload_free(payload);
        return 0;
    }
    return 1;
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

/**
 * Publishes an image from PNG bytes. GdkPixbuf decodes it once here so that
 * `gtk_clipboard_set_image` can advertise — and convert to — every image
 * target GTK knows, which is how a paste into GIMP or LibreOffice finds a
 * format it accepts.
 */
EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeSetImagePng(
    JNIEnv *env, jclass clazz, jbyteArray png)
{
    (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL || png == NULL) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, png);
    jbyte *bytes = (*env)->GetByteArrayElements(env, png, NULL);
    if (bytes == NULL) return JNI_FALSE;

    jboolean result = JNI_FALSE;
    GdkPixbufLoader *loader = g.gdk_pixbuf_loader_new();
    if (loader != NULL) {
        if (g.gdk_pixbuf_loader_write(loader, (const guchar *) bytes, (gsize) len, NULL) &&
            g.gdk_pixbuf_loader_close(loader, NULL)) {
            GdkPixbuf *pixbuf = g.gdk_pixbuf_loader_get_pixbuf(loader);
            if (pixbuf != NULL) {
                /* set_image refs the pixbuf; the loader keeps the only other
                 * reference and drops it on unref below. */
                g.gtk_clipboard_set_image(clipboard, pixbuf);
                result = JNI_TRUE;
            }
        } else {
            g.gdk_pixbuf_loader_close(loader, NULL);
        }
        g.g_object_unref(loader);
    }
    (*env)->ReleaseByteArrayElements(env, png, bytes, JNI_ABORT);
    return result;
}

/**
 * Publishes a file list from newline-separated `file://` URIs. Two targets go
 * out: `text/uri-list`, which is the freedesktop standard AWT's
 * `javaFileListFlavor` maps to, and `x-special/gnome-copied-files`, which is
 * what GTK file managers actually read on paste.
 */
EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeSetUriListUtf8(
    JNIEnv *env, jclass clazz, jbyteArray uriList)
{
    (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL || uriList == NULL) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, uriList);
    if (len <= 0) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, uriList, NULL);
    if (bytes == NULL) return JNI_FALSE;

    /* text/uri-list is CRLF-separated per RFC 2483; the gnome target is
     * "copy\n" followed by LF-separated URIs. */
    size_t crlf_capacity = (size_t) len * 2 + 2;
    char *crlf = malloc(crlf_capacity);
    size_t gnome_capacity = (size_t) len + sizeof("copy\n");
    char *gnome = malloc(gnome_capacity);
    jboolean result = JNI_FALSE;
    if (crlf != NULL && gnome != NULL) {
        size_t crlf_len = 0;
        for (jsize i = 0; i < len; i++) {
            if (bytes[i] == '\n') {
                crlf[crlf_len++] = '\r';
                crlf[crlf_len++] = '\n';
            } else {
                crlf[crlf_len++] = (char) bytes[i];
            }
        }
        crlf[crlf_len++] = '\r';
        crlf[crlf_len++] = '\n';

        memcpy(gnome, "copy\n", 5);
        memcpy(gnome + 5, bytes, (size_t) len);
        size_t gnome_len = 5 + (size_t) len;

        const char *mimes[] = { "text/uri-list", "x-special/gnome-copied-files" };
        const guchar *blobs[] = { (const guchar *) crlf, (const guchar *) gnome };
        const gsize sizes[] = { (gsize) crlf_len, (gsize) gnome_len };
        result = publish_payload(clipboard, mimes, blobs, sizes, 2) ? JNI_TRUE : JNI_FALSE;
    }
    free(crlf);
    free(gnome);
    (*env)->ReleaseByteArrayElements(env, uriList, bytes, JNI_ABORT);
    return result;
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
#define DEFINE_REQUEST(name, gtk_call, receiver)                                          \
    EXPORT jboolean JNICALL                                                               \
    Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_##name(        \
        JNIEnv *env, jclass clazz, jobject callback)                                      \
    {                                                                                     \
        (void) clazz;                                                                     \
        if (callback == NULL) return JNI_FALSE;                                           \
        GtkClipboard *clipboard = clipboard_or_null();                                    \
        ensure_jvm(env);                                                                  \
        if (clipboard == NULL || on_bytes_method(env, callback) == NULL) return JNI_FALSE; \
        jobject global = (*env)->NewGlobalRef(env, callback);                             \
        if (global == NULL) return JNI_FALSE;                                             \
        g.gtk_call(clipboard, receiver, global);                                          \
        return JNI_TRUE;                                                                  \
    }

DEFINE_REQUEST(nativeRequestTextUtf8, gtk_clipboard_request_text, on_text_received)
DEFINE_REQUEST(nativeRequestImagePng, gtk_clipboard_request_image, on_image_received)
DEFINE_REQUEST(nativeRequestUriListUtf8, gtk_clipboard_request_uris, on_uris_received)
DEFINE_REQUEST(nativeRequestTargetsUtf8, gtk_clipboard_request_targets, on_targets_received)

/**
 * Synchronous reads. GTK spins a nested main loop until the owner answers, so
 * these are for callers that cannot suspend — a `Transferable` read from
 * arbitrary code, and the deprecated `ClipboardManager`.
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
    jbyteArray arr = string_to_array(env, text);
    g.g_free(text);
    return arr;
}

EXPORT jbyteArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeWaitForImagePng(
    JNIEnv *env, jclass clazz)
{
    (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL) return NULL;
    GdkPixbuf *pixbuf = g.gtk_clipboard_wait_for_image(clipboard);
    if (pixbuf == NULL) return NULL;
    gchar *png = NULL;
    gsize png_size = 0;
    jbyteArray arr = NULL;
    if (g.gdk_pixbuf_save_to_buffer(pixbuf, &png, &png_size, "png", NULL, NULL) && png != NULL) {
        arr = bytes_to_array(env, png, (size_t) png_size);
        g.g_free(png);
    }
    /* wait_for_image returns a new reference, unlike the async variant. */
    g.g_object_unref(pixbuf);
    return arr;
}

EXPORT jbyteArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxClipboardBridge_nativeWaitForUriListUtf8(
    JNIEnv *env, jclass clazz)
{
    (void) clazz;
    GtkClipboard *clipboard = clipboard_or_null();
    if (clipboard == NULL) return NULL;
    gchar **uris = g.gtk_clipboard_wait_for_uris(clipboard);
    if (uris == NULL) return NULL;
    size_t len = 0;
    char *joined = join_lines(uris, &len);
    jbyteArray arr = bytes_to_array(env, joined, len);
    free(joined);
    g.g_strfreev(uris);
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
