/**
 * JNI bridge: GTK widget reparenting helpers used by the
 * `NucleusPlatformView.GtkWidget` variant of `NativeView` on Linux.
 *
 * Linux equivalent of the macOS `nativeAddSubview` /
 * `nativeSetSubviewFrame` family in `macos/native_view.m`. The user
 * supplies a raw `GtkWidget*` (typically a WebKit2GTK `WebKitWebView`
 * or any other GTK 3 widget) and this helper:
 *
 *   1. Reparents it into a single `GtkOverlay` injected lazily into
 *      Tao's existing content `GtkBox`. **GtkOverlay** rather than
 *      GtkFixed because GtkFixed reports its preferred size as the
 *      bounding box of its children, propagating up the chain
 *      (Fixed → Box → ApplicationWindow) and pinning the window's
 *      minimum size to the embedded widget's requested size — making
 *      the window fight every Compose layout pass when the user
 *      tries to shrink it. GtkOverlay derives its preferred size
 *      from its *main child* only; we pin the main child to (0, 0)
 *      so the overlay reports min = 0 regardless of how many
 *      WebViews are stacked on top. The user's widget is added via
 *      `gtk_overlay_add_overlay` and positioned through the
 *      `get-child-position` signal, reading per-widget rects we
 *      cached on the GObject. The mount itself is deferred to the
 *      first real Compose rect (`nativeSetFrame`) so the widget
 *      realises directly at its final size.
 *   2. Moves and resizes by updating the cached rect and queuing a
 *      resize on the overlay, which re-fires `get-child-position`.
 *   3. Removes it on detach via `gtk_container_remove`.
 *
 * Linked libraries: -ldl. libgtk-3.so.0 / libgobject-2.0.so.0 are
 * dlopen-ed at runtime so the build doesn't require the dev headers
 * (cflags resolve via pkg-config at compile time, but the linker
 * stays standalone like `nucleus_tao_egl.c`).
 *
 * Threading: every entry point must run on the GTK main thread (= Tao
 * event-loop thread = Compose dispatcher thread).
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

/* ── GTK / GObject types we need (forward-declared to avoid pulling
 *    libgtk-3 dev headers into the build). ────────────────────────── */

typedef int gint;
typedef int gboolean;
typedef void GtkWidget;        /* opaque */
typedef void GtkContainer;     /* opaque */
typedef void GtkOverlay;       /* opaque */
typedef unsigned long gulong;
typedef int GtkOrientation;

typedef struct _GList GList;
struct _GList {
    void  *data;
    GList *next;
    GList *prev;
};

#define GTK_ORIENTATION_HORIZONTAL 0
#define GTK_ORIENTATION_VERTICAL   1

/* GdkRectangle / GtkAllocation share the same layout in GTK 3. The
 * `get-child-position` signal hands us a GdkRectangle*. */
typedef struct {
    gint x;
    gint y;
    gint width;
    gint height;
} GdkRectangle;

#define GTK_TRUE  1
#define GTK_FALSE 0

/* ── Function pointer table (resolved lazily) ───────────────────────── */

typedef void          GtkWindow;       /* opaque */
typedef GtkWidget *(*PFN_gtk_bin_get_child)(GtkWidget *bin);
typedef GtkWidget *(*PFN_gtk_widget_get_parent)(GtkWidget *widget);
typedef void       (*PFN_gtk_container_add)(GtkContainer *c, GtkWidget *w);
typedef void       (*PFN_gtk_container_remove)(GtkContainer *c, GtkWidget *w);
typedef void       (*PFN_gtk_window_set_focus)(GtkWindow *w, GtkWidget *focus);
typedef GtkWidget *(*PFN_gtk_event_box_new)(void);
typedef void       (*PFN_gtk_event_box_set_visible_window)(GtkWidget *box, gboolean visible);
typedef GtkWidget *(*PFN_gtk_widget_get_toplevel)(GtkWidget *w);
typedef gboolean   (*PFN_gtk_widget_translate_coordinates)(
    GtkWidget *src, GtkWidget *dst, int sx, int sy, int *dx, int *dy);
typedef void       (*PFN_gtk_widget_add_events)(GtkWidget *w, int events);
typedef void       (*PFN_gtk_widget_set_can_focus)(GtkWidget *w, gboolean can_focus);
typedef void       (*PFN_gtk_widget_set_app_paintable)(GtkWidget *w, gboolean paintable);
typedef gboolean   (*PFN_gtk_widget_grab_focus)(GtkWidget *w);
typedef void       (*PFN_gtk_widget_destroy)(GtkWidget *w);
typedef GtkWidget *(*PFN_gtk_overlay_new)(void);
typedef void       (*PFN_gtk_overlay_add_overlay)(GtkOverlay *o, GtkWidget *w);
typedef GtkWidget *(*PFN_gtk_box_new)(GtkOrientation orientation, gint spacing);
typedef void       (*PFN_gtk_box_pack_start)(
    GtkContainer *box, GtkWidget *w, gboolean expand, gboolean fill, unsigned int padding);
typedef void       (*PFN_gtk_widget_set_size_request)(GtkWidget *w, gint width, gint height);
typedef void       (*PFN_gtk_widget_set_halign)(GtkWidget *w, gint align);
typedef void       (*PFN_gtk_widget_set_valign)(GtkWidget *w, gint align);
typedef void       (*PFN_gtk_widget_show)(GtkWidget *w);
typedef void       (*PFN_gtk_widget_queue_resize)(GtkWidget *w);
typedef gboolean   (*PFN_g_type_check_instance_is_a)(void *instance, gulong type);
typedef gulong     (*PFN_gtk_box_get_type)(void);
typedef gulong     (*PFN_gtk_widget_get_type)(void);
typedef void       (*PFN_g_object_set_data)(void *obj, const char *key, void *data);
typedef void       (*PFN_g_object_set_data_full)(
    void *obj, const char *key, void *data, void (*destroy)(void *));
typedef void      *(*PFN_g_object_get_data)(void *obj, const char *key);
typedef gulong     (*PFN_g_signal_connect_data)(
    void *instance, const char *signal, void (*handler)(void), void *data,
    void (*destroy)(void *, void *), int connect_flags);
typedef void      *(*PFN_gtk_widget_get_window)(GtkWidget *w);
typedef gboolean   (*PFN_gtk_widget_event)(GtkWidget *w, void *event);
typedef GList     *(*PFN_gtk_container_get_children)(GtkContainer *c);
typedef void      *(*PFN_gdk_event_copy)(const void *event);
typedef void       (*PFN_gdk_event_free)(void *event);
typedef void      *(*PFN_g_object_ref)(void *obj);
typedef void       (*PFN_g_object_unref)(void *obj);
typedef void       (*PFN_g_list_free)(GList *list);
typedef GtkWidget *(*PFN_gtk_window_get_focus)(GtkWindow *window);
typedef void       (*PFN_gtk_container_check_resize)(GtkContainer *container);
typedef void       (*PFN_gtk_widget_queue_draw)(GtkWidget *widget);
typedef void      *(*PFN_gdk_window_get_display)(void *window);
typedef void      *(*PFN_gdk_display_get_default_seat)(void *display);
typedef void      *(*PFN_gdk_seat_get_pointer)(void *seat);
typedef void      *(*PFN_gdk_window_get_device_position)(
    void *window, void *device, int *x, int *y, unsigned int *mask);

/* GtkAlign enum — `GTK_ALIGN_FILL` = 0 (GTK 3), `GTK_ALIGN_START` = 1.
 * We use START on the dummy main child so it doesn't request expansion. */
#define GTK_ALIGN_START 1

static struct {
    int initialized;
    PFN_gtk_bin_get_child         gtk_bin_get_child;
    PFN_gtk_widget_get_parent     gtk_widget_get_parent;
    PFN_gtk_container_add         gtk_container_add;
    PFN_gtk_container_remove      gtk_container_remove;
    PFN_gtk_overlay_new           gtk_overlay_new;
    PFN_gtk_overlay_add_overlay   gtk_overlay_add_overlay;
    PFN_gtk_box_new               gtk_box_new;
    PFN_gtk_box_pack_start        gtk_box_pack_start;
    PFN_gtk_widget_set_size_request gtk_widget_set_size_request;
    PFN_gtk_widget_set_halign     gtk_widget_set_halign;
    PFN_gtk_widget_set_valign     gtk_widget_set_valign;
    PFN_gtk_widget_show           gtk_widget_show;
    PFN_gtk_widget_queue_resize   gtk_widget_queue_resize;
    PFN_gtk_window_set_focus      gtk_window_set_focus;
    PFN_gtk_event_box_new         gtk_event_box_new;
    PFN_gtk_event_box_set_visible_window gtk_event_box_set_visible_window;
    PFN_gtk_widget_get_toplevel   gtk_widget_get_toplevel;
    PFN_gtk_widget_translate_coordinates gtk_widget_translate_coordinates;
    PFN_gtk_widget_add_events     gtk_widget_add_events;
    PFN_gtk_widget_set_can_focus  gtk_widget_set_can_focus;
    PFN_gtk_widget_set_app_paintable gtk_widget_set_app_paintable;
    PFN_gtk_widget_grab_focus     gtk_widget_grab_focus;
    PFN_gtk_widget_destroy        gtk_widget_destroy;
    PFN_g_type_check_instance_is_a g_type_check_instance_is_a;
    PFN_gtk_box_get_type          gtk_box_get_type;
    PFN_gtk_widget_get_type       gtk_widget_get_type;
    PFN_g_object_set_data         g_object_set_data;
    PFN_g_object_set_data_full    g_object_set_data_full;
    PFN_g_object_get_data         g_object_get_data;
    PFN_g_signal_connect_data     g_signal_connect_data;
    PFN_gtk_widget_get_window     gtk_widget_get_window;
    PFN_gtk_widget_event          gtk_widget_event;
    PFN_gtk_container_get_children gtk_container_get_children;
    PFN_gdk_event_copy            gdk_event_copy;
    PFN_gdk_event_free            gdk_event_free;
    PFN_g_object_ref              g_object_ref;
    PFN_g_object_unref            g_object_unref;
    PFN_g_list_free               g_list_free;
    /* Optional: keyboard-owner bookkeeping and the live button state. */
    PFN_gtk_window_get_focus      gtk_window_get_focus;
    PFN_gtk_container_check_resize gtk_container_check_resize;
    PFN_gtk_widget_queue_draw     gtk_widget_queue_draw;
    PFN_gdk_window_get_display    gdk_window_get_display;
    PFN_gdk_display_get_default_seat gdk_display_get_default_seat;
    PFN_gdk_seat_get_pointer      gdk_seat_get_pointer;
    PFN_gdk_window_get_device_position gdk_window_get_device_position;
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
    const char *gobj_libs[] = { "libgobject-2.0.so.0", "libgobject-2.0.so", NULL };
    void *libgtk  = load_first(gtk_libs);
    void *libgobj = load_first(gobj_libs);
    if (libgtk == NULL || libgobj == NULL) return 0;

    g.gtk_bin_get_child           = (PFN_gtk_bin_get_child)           dlsym(libgtk, "gtk_bin_get_child");
    g.gtk_widget_get_parent       = (PFN_gtk_widget_get_parent)       dlsym(libgtk, "gtk_widget_get_parent");
    g.gtk_container_add           = (PFN_gtk_container_add)           dlsym(libgtk, "gtk_container_add");
    g.gtk_container_remove        = (PFN_gtk_container_remove)        dlsym(libgtk, "gtk_container_remove");
    g.gtk_overlay_new             = (PFN_gtk_overlay_new)             dlsym(libgtk, "gtk_overlay_new");
    g.gtk_overlay_add_overlay     = (PFN_gtk_overlay_add_overlay)     dlsym(libgtk, "gtk_overlay_add_overlay");
    g.gtk_box_new                 = (PFN_gtk_box_new)                 dlsym(libgtk, "gtk_box_new");
    g.gtk_box_pack_start          = (PFN_gtk_box_pack_start)          dlsym(libgtk, "gtk_box_pack_start");
    g.gtk_widget_set_size_request = (PFN_gtk_widget_set_size_request) dlsym(libgtk, "gtk_widget_set_size_request");
    g.gtk_widget_set_halign       = (PFN_gtk_widget_set_halign)       dlsym(libgtk, "gtk_widget_set_halign");
    g.gtk_widget_set_valign       = (PFN_gtk_widget_set_valign)       dlsym(libgtk, "gtk_widget_set_valign");
    g.gtk_widget_show             = (PFN_gtk_widget_show)             dlsym(libgtk, "gtk_widget_show");
    g.gtk_widget_queue_resize     = (PFN_gtk_widget_queue_resize)     dlsym(libgtk, "gtk_widget_queue_resize");
    g.gtk_window_set_focus        = (PFN_gtk_window_set_focus)        dlsym(libgtk, "gtk_window_set_focus");
    g.gtk_event_box_new           = (PFN_gtk_event_box_new)           dlsym(libgtk, "gtk_event_box_new");
    g.gtk_event_box_set_visible_window = (PFN_gtk_event_box_set_visible_window) dlsym(libgtk, "gtk_event_box_set_visible_window");
    g.gtk_widget_get_toplevel     = (PFN_gtk_widget_get_toplevel)     dlsym(libgtk, "gtk_widget_get_toplevel");
    g.gtk_widget_translate_coordinates = (PFN_gtk_widget_translate_coordinates) dlsym(libgtk, "gtk_widget_translate_coordinates");
    g.gtk_widget_add_events       = (PFN_gtk_widget_add_events)       dlsym(libgtk, "gtk_widget_add_events");
    g.gtk_widget_set_can_focus    = (PFN_gtk_widget_set_can_focus)    dlsym(libgtk, "gtk_widget_set_can_focus");
    g.gtk_widget_set_app_paintable = (PFN_gtk_widget_set_app_paintable) dlsym(libgtk, "gtk_widget_set_app_paintable");
    g.gtk_widget_grab_focus       = (PFN_gtk_widget_grab_focus)       dlsym(libgtk, "gtk_widget_grab_focus");
    g.gtk_widget_destroy          = (PFN_gtk_widget_destroy)          dlsym(libgtk, "gtk_widget_destroy");
    g.gtk_box_get_type            = (PFN_gtk_box_get_type)            dlsym(libgtk, "gtk_box_get_type");
    g.gtk_widget_get_type         = (PFN_gtk_widget_get_type)         dlsym(libgtk, "gtk_widget_get_type");

    g.g_type_check_instance_is_a  = (PFN_g_type_check_instance_is_a)  dlsym(libgobj, "g_type_check_instance_is_a");
    g.g_object_set_data           = (PFN_g_object_set_data)           dlsym(libgobj, "g_object_set_data");
    g.g_object_set_data_full      = (PFN_g_object_set_data_full)      dlsym(libgobj, "g_object_set_data_full");
    g.g_object_get_data           = (PFN_g_object_get_data)           dlsym(libgobj, "g_object_get_data");
    g.g_signal_connect_data       = (PFN_g_signal_connect_data)       dlsym(libgobj, "g_signal_connect_data");

    /* Optional: forwarding live GdkEvents onto an embedded widget
     * (interop blending). Missing symbols degrade dispatch to a no-op
     * so the rest of NativeView still mounts. GdkWindow is a plain
     * GObject in GTK 3 — refcounts go through g_object_ref/unref
     * (gdk_window_ref/unref no longer exist in libgdk-3). */
    const char *gdk_libs[]  = { "libgdk-3.so.0", "libgdk-3.so", NULL };
    const char *glib_libs[] = { "libglib-2.0.so.0", "libglib-2.0.so", NULL };
    void *libgdk  = load_first(gdk_libs);
    void *libglib = load_first(glib_libs);
    g.gtk_widget_get_window       = (PFN_gtk_widget_get_window)       dlsym(libgtk, "gtk_widget_get_window");
    g.gtk_widget_event            = (PFN_gtk_widget_event)            dlsym(libgtk, "gtk_widget_event");
    g.gtk_container_get_children  = (PFN_gtk_container_get_children)  dlsym(libgtk, "gtk_container_get_children");
    if (libgdk != NULL) {
        g.gdk_event_copy          = (PFN_gdk_event_copy)              dlsym(libgdk, "gdk_event_copy");
        g.gdk_event_free          = (PFN_gdk_event_free)              dlsym(libgdk, "gdk_event_free");
        g.gdk_window_get_display  = (PFN_gdk_window_get_display)      dlsym(libgdk, "gdk_window_get_display");
        g.gdk_display_get_default_seat = (PFN_gdk_display_get_default_seat) dlsym(libgdk, "gdk_display_get_default_seat");
        g.gdk_seat_get_pointer    = (PFN_gdk_seat_get_pointer)        dlsym(libgdk, "gdk_seat_get_pointer");
        g.gdk_window_get_device_position = (PFN_gdk_window_get_device_position) dlsym(libgdk, "gdk_window_get_device_position");
    }
    g.gtk_window_get_focus        = (PFN_gtk_window_get_focus)        dlsym(libgtk, "gtk_window_get_focus");
    g.gtk_container_check_resize  = (PFN_gtk_container_check_resize)  dlsym(libgtk, "gtk_container_check_resize");
    g.gtk_widget_queue_draw       = (PFN_gtk_widget_queue_draw)       dlsym(libgtk, "gtk_widget_queue_draw");
    g.g_object_ref                = (PFN_g_object_ref)                dlsym(libgobj, "g_object_ref");
    g.g_object_unref              = (PFN_g_object_unref)              dlsym(libgobj, "g_object_unref");
    if (libglib != NULL) {
        g.g_list_free             = (PFN_g_list_free)                 dlsym(libglib, "g_list_free");
    }

    if (!g.gtk_bin_get_child || !g.gtk_widget_get_parent ||
        !g.gtk_container_add || !g.gtk_container_remove ||
        !g.gtk_overlay_new || !g.gtk_overlay_add_overlay ||
        !g.gtk_box_new || !g.gtk_box_pack_start ||
        !g.gtk_widget_set_size_request ||
        !g.gtk_widget_set_halign || !g.gtk_widget_set_valign ||
        !g.gtk_widget_show || !g.gtk_widget_queue_resize ||
        !g.gtk_window_set_focus || !g.gtk_event_box_new ||
        !g.gtk_event_box_set_visible_window ||
        !g.gtk_widget_get_toplevel || !g.gtk_widget_translate_coordinates ||
        !g.gtk_widget_add_events ||
        !g.gtk_widget_set_can_focus || !g.gtk_widget_set_app_paintable ||
        !g.gtk_widget_grab_focus || !g.gtk_widget_destroy ||
        !g.gtk_box_get_type || !g.gtk_widget_get_type ||
        !g.g_type_check_instance_is_a ||
        !g.g_object_set_data || !g.g_object_set_data_full ||
        !g.g_object_get_data || !g.g_signal_connect_data) {
        return 0;
    }
    g.initialized = 1;
    return 1;
}

/* ── JNI callback (for input-box motion / button forwarding) ────────── */

static JavaVM      *sJVM = NULL;
static jclass       sCallbackClass = NULL;
static jmethodID    sOnEventMethod = NULL; /* (IIIII)V — type, x, y, button, pressed */
static jmethodID    sOnScrollMethod = NULL; /* (IIFF)V — x, y, dx, dy */

static void ensure_callback_cache(JNIEnv *env, jobject sample) {
    if (sOnEventMethod != NULL) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (sample == NULL) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (local == NULL) return;
    sCallbackClass = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (sCallbackClass == NULL) return;
    sOnEventMethod = (*env)->GetMethodID(env, sCallbackClass, "onEvent", "(IIIII)V");
    sOnScrollMethod = (*env)->GetMethodID(env, sCallbackClass, "onScroll", "(IIFF)V");
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
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

/* GObject destroy-notify owning the input-box callback global ref. Fires
 * when the data is replaced/cleared and when GTK finalises the EventBox
 * (e.g. toplevel teardown racing the Compose onDispose), so the ref cannot
 * leak whichever side wins. Runs on the GTK thread; attach-as-daemon covers
 * the finalise-from-a-non-JVM-thread case. */
static void overlay_cb_destroy_notify(void *data) {
    jobject ref = (jobject) data;
    if (ref == NULL) return;
    JNIEnv *env = attach_jvm_thread();
    if (env != NULL) (*env)->DeleteGlobalRef(env, ref);
}

#define EVT_OVERLAY_MOVE      0
#define EVT_OVERLAY_PRESS     1
#define EVT_OVERLAY_RELEASE   2
#define EVT_OVERLAY_FOCUS_OUT 3

static void invoke_callback(GtkWidget *box, int type, int x, int y, int button) {
    if (sOnEventMethod == NULL) return;
    jobject cb = (jobject) g.g_object_get_data(box, "nucleus_tao_overlay_cb");
    if (cb == NULL) return;
    JNIEnv *env = attach_jvm_thread();
    if (env == NULL) return;
    (*env)->CallVoidMethod(env, cb, sOnEventMethod, (jint) type, (jint) x, (jint) y,
                           (jint) button, (jint) (type == EVT_OVERLAY_PRESS ? 1 : 0));
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void invoke_scroll_callback(GtkWidget *box, int x, int y, float dx, float dy) {
    if (sOnScrollMethod == NULL) return;
    jobject cb = (jobject) g.g_object_get_data(box, "nucleus_tao_overlay_cb");
    if (cb == NULL) return;
    JNIEnv *env = attach_jvm_thread();
    if (env == NULL) return;
    (*env)->CallVoidMethod(env, cb, sOnScrollMethod,
        (jint) x, (jint) y, (jfloat) dx, (jfloat) dy);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ── Per-widget rect storage + overlay positioning ─────────────────── */

static const char NUCLEUS_OVERLAY_KEY[]   = "nucleus_tao_widget_overlay";
static const char NUCLEUS_RECT_KEY[]      = "nucleus_tao_widget_rect";
static const char NUCLEUS_INPUT_BOX_KEY[] = "nucleus_tao_input_box";

typedef struct {
    gint x, y, w, h;
    gint valid;
} widget_rect_t;

/* Whether [widget] is one of the EventBoxes this file creates — the input
 * boxes and the focus sink. GTK focus on one of them means Compose owns the
 * keyboard (Tao's toplevel handler feeds it); focus on anything else means
 * an embed does. Tao reads the same marker (`nucleus_tao_input_box`) to
 * decide whether a key press is Compose's or the embed's. */
static int is_nucleus_input_box(GtkWidget *widget) {
    return widget != NULL && g.g_object_get_data(widget, NUCLEUS_INPUT_BOX_KEY) != NULL;
}

/* The GTK focus widget of the toplevel [widget] lives in, or NULL. */
static GtkWidget *focus_widget_of(GtkWidget *widget) {
    if (g.gtk_window_get_focus == NULL) return NULL;
    GtkWidget *toplevel = g.gtk_widget_get_toplevel(widget);
    if (toplevel == NULL) return NULL;
    return g.gtk_window_get_focus((GtkWindow *) toplevel);
}


/* `get-child-position` signal handler. Reads the cached rect from the
 * child's GObject data and writes it into [allocation]. ALWAYS returns
 * TRUE with w,h >= 1: returning FALSE makes GtkOverlay fall back to
 * the child's preferred size, which is 0 for the size-request-pinned
 * embeds and the invisible EventBox — and the overlay unconditionally
 * wraps every overlay child in its own INPUT_OUTPUT GdkWindow
 * (gtk_overlay_create_child_window), so a 0-size fallback creates a
 * 0×0 gdk_window_new on Wayland ("losing last reference to
 * undestroyed window" followed by a use-after-free in
 * gdk_window_get_parent during draw propagation). Children whose
 * Compose rect hasn't landed yet park offscreen at 1×1 until the
 * first nativeSetFrame / nativeMoveInputBox. */
static gboolean on_get_child_position(GtkWidget *overlay, GtkWidget *child,
                                      GdkRectangle *allocation, void *user_data) {
    (void) overlay; (void) user_data;
    if (allocation == NULL) return GTK_FALSE;
    widget_rect_t *r = (widget_rect_t *) g.g_object_get_data(child, NUCLEUS_RECT_KEY);
    if (r == NULL || !r->valid) {
        allocation->x = -1;
        allocation->y = -1;
        allocation->width = 1;
        allocation->height = 1;
        return GTK_TRUE;
    }
    allocation->x = r->x;
    allocation->y = r->y;
    allocation->width = r->w > 0 ? r->w : 1;
    allocation->height = r->h > 0 ? r->h : 1;
    return GTK_TRUE;
}

/* Lazily allocates the per-widget rect cache. Freed by the GObject
 * destroy notify when the widget is finalised. */
static widget_rect_t *get_or_create_rect(GtkWidget *widget) {
    widget_rect_t *rect = (widget_rect_t *)
        g.g_object_get_data(widget, NUCLEUS_RECT_KEY);
    if (rect == NULL) {
        rect = (widget_rect_t *) calloc(1, sizeof(*rect));
        if (rect != NULL) {
            g.g_object_set_data_full(widget, NUCLEUS_RECT_KEY, rect, free);
        }
    }
    return rect;
}

/* Tao's GtkApplicationWindow has either:
 *   (a) a GtkBox child (when default_vbox = true), or
 *   (b) some other widget directly.
 *
 * For (a), we lazily inject a GtkOverlay inside the box. For (b),
 * keep it simple and bail with NULL — caller falls back to a no-op. */
static GtkWidget *resolve_overlay_for_window(GtkWidget *gtk_window) {
    if (gtk_window == NULL) return NULL;

    GtkWidget *cached = (GtkWidget *)
        g.g_object_get_data(gtk_window, NUCLEUS_OVERLAY_KEY);
    if (cached != NULL) return cached;

    GtkWidget *child = g.gtk_bin_get_child(gtk_window);
    if (child == NULL) return NULL;
    if (!g.g_type_check_instance_is_a(child, g.gtk_box_get_type())) {
        return NULL;
    }

    /* GtkOverlay's "main child" determines the overlay's preferred
     * size. A 0×0 dummy box pins min = 0 — the embedded widget's
     * request never reaches the GtkApplicationWindow, so the user
     * can shrink the window freely. */
    GtkWidget *dummy = g.gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 0);
    if (dummy == NULL) return NULL;
    g.gtk_widget_set_size_request(dummy, 0, 0);
    g.gtk_widget_set_halign(dummy, GTK_ALIGN_START);
    g.gtk_widget_set_valign(dummy, GTK_ALIGN_START);

    GtkWidget *overlay = g.gtk_overlay_new();
    if (overlay == NULL) return NULL;
    g.gtk_container_add((GtkContainer *) overlay, dummy);
    /* The overlay wraps every overlay child in an INPUT_OUTPUT
     * GdkWindow whose event mask is copied from the *overlay* widget
     * (gtk_overlay_create_child_window) — masks added to the EventBox
     * itself never reach that window's wl_surface. Select everything
     * the input boxes need before the first child window is created:
     * GDK_POINTER_MOTION=1<<2, BUTTON_PRESS=1<<8, BUTTON_RELEASE=1<<9,
     * SCROLL=1<<21, SMOOTH_SCROLL=1<<23. */
    g.gtk_widget_add_events(
        overlay, (1 << 2) | (1 << 8) | (1 << 9) | (1 << 21) | (1 << 23));
    /* Hook the per-frame positioning callback once. */
    g.g_signal_connect_data(
        overlay, "get-child-position",
        (void (*)(void)) on_get_child_position, NULL, NULL, 0);

    g.gtk_widget_set_size_request(overlay, 0, 0);
    g.gtk_box_pack_start((GtkContainer *) child, overlay, GTK_TRUE, GTK_TRUE, 0);
    g.gtk_widget_show(dummy);
    g.gtk_widget_show(overlay);

    g.g_object_set_data(gtk_window, NUCLEUS_OVERLAY_KEY, overlay);
    return overlay;
}

/* ── EGL context guard ─────────────────────────────────────────────
 *
 * gtk_overlay_add_overlay / gtk_widget_show / gtk_container_remove on
 * a mapped window can synchronously realise or tear down the embedded
 * widget, and WebKit's UI-process GL init leaves ITS EGL context
 * current on this thread. The mount runs in the middle of the host's
 * Skia render pass (onGloballyPositioned → nativeSetFrame), so Skia's
 * subsequent GL — including inline glyph-atlas flushes under atlas
 * pressure — would execute against the foreign context and permanently
 * corrupt the atlas (text drawn after the embed appears shows randomly
 * missing glyph instances). Save the caller's context around every
 * GTK call that can reach the embed's GL and restore it after. */

typedef void *(*PFN_eglGetCurrentContext)(void);
typedef void *(*PFN_eglGetCurrentDisplay)(void);
typedef void *(*PFN_eglGetCurrentSurface)(int readdraw);
typedef unsigned int (*PFN_eglMakeCurrent)(void *dpy, void *draw, void *read, void *ctx);

#define EGL_DRAW 0x3059
#define EGL_READ 0x305A

static struct {
    int initialized;
    PFN_eglGetCurrentContext get_current_context;
    PFN_eglGetCurrentDisplay get_current_display;
    PFN_eglGetCurrentSurface get_current_surface;
    PFN_eglMakeCurrent       make_current;
} egl;

static void ensure_egl_loaded(void) {
    if (egl.initialized) return;
    egl.initialized = 1;
    const char *egl_libs[] = { "libEGL.so.1", "libEGL.so", NULL };
    void *libegl = load_first(egl_libs);
    if (libegl == NULL) return;
    egl.get_current_context = (PFN_eglGetCurrentContext) dlsym(libegl, "eglGetCurrentContext");
    egl.get_current_display = (PFN_eglGetCurrentDisplay) dlsym(libegl, "eglGetCurrentDisplay");
    egl.get_current_surface = (PFN_eglGetCurrentSurface) dlsym(libegl, "eglGetCurrentSurface");
    egl.make_current         = (PFN_eglMakeCurrent)       dlsym(libegl, "eglMakeCurrent");
}

typedef struct {
    void *dpy, *draw, *read, *ctx;
    int valid;
} egl_snapshot_t;

static egl_snapshot_t egl_save(void) {
    egl_snapshot_t s = { 0 };
    ensure_egl_loaded();
    if (egl.get_current_context == NULL || egl.get_current_display == NULL ||
        egl.get_current_surface == NULL || egl.make_current == NULL) {
        return s;
    }
    s.ctx = egl.get_current_context();
    s.dpy = egl.get_current_display();
    s.draw = egl.get_current_surface(EGL_DRAW);
    s.read = egl.get_current_surface(EGL_READ);
    s.valid = 1;
    return s;
}

static void egl_restore(const egl_snapshot_t *s) {
    if (!s->valid || egl.get_current_context == NULL) return;
    if (egl.get_current_context() == s->ctx) return;
    if (s->ctx != NULL) {
        egl.make_current(s->dpy, s->draw, s->read, s->ctx);
    } else {
        /* Caller had no context: release whatever the GTK work left
         * current, on that context's own display. */
        void *dpy = egl.get_current_display();
        if (dpy != NULL) egl.make_current(dpy, NULL, NULL, NULL);
    }
}

/* Adds [widget] as an overlay child, unless it is already one. Called
 * from whichever of nativeAttach / nativeSetFrame runs second — the
 * mount is deferred until the first real Compose rect is known so the
 * widget never realises at the offscreen 1×1 parking allocation
 * (WebKit's GPU compositor sizes its glyph atlas at first paint and
 * never recovers from a 1×1 start: page text stays blank). */
/* Re-runs `get-child-position` for the overlay's children with the rects
 * just cached — synchronously. `gtk_widget_queue_resize` alone parks the
 * allocation until GTK's next frame-clock layout phase, one compositor
 * frame after Compose laid the slot out: through a resize the embed then
 * trails the window by a frame at best, and by several when the frame
 * clock is paced slower than the Compose layouts feeding it. Processing
 * the queued resize right here lands the embed in the same frame as the
 * Compose content around it. The overlay reports min = 0, so the pass
 * never reaches the GtkApplicationWindow. The embed's own size_allocate
 * may touch its GL (WebKit's accelerated surface): guard the thread's EGL
 * context like every other GTK call that can. */
static void relayout_overlay_now(GtkWidget *overlay) {
    g.gtk_widget_queue_resize(overlay);
    if (g.gtk_container_check_resize == NULL) return;
    egl_snapshot_t saved = egl_save();
    g.gtk_container_check_resize((GtkContainer *) overlay);
    egl_restore(&saved);
}

static void mount_on_overlay(GtkWidget *overlay, GtkWidget *widget) {
    GtkWidget *parent = g.gtk_widget_get_parent(widget);
    if (parent == overlay) return;
    egl_snapshot_t saved = egl_save();
    /* Defensive: if someone re-attaches an already-parented widget,
     * remove it from its old parent first. */
    if (parent != NULL) {
        g.gtk_container_remove((GtkContainer *) parent, widget);
    }
    /* Force the embedded widget itself to report a 0 min-size so the
     * overlay's preferred size stays small even if the widget's
     * natural default would be large (WebKit's default is the
     * browser's idea of a "useful" minimum). The real allocation
     * always comes from `get-child-position`. */
    g.gtk_widget_set_size_request(widget, 0, 0);
    g.gtk_overlay_add_overlay((GtkOverlay *) overlay, widget);
    g.gtk_widget_show(widget);
    egl_restore(&saved);
}

/* ── JNI exports ────────────────────────────────────────────────────── */

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

/**
 * Loads GTK through the same RTLD_LOCAL dlopen path as every other entry
 * point and returns its runtime version ("3.24.49"), or NULL when GTK is
 * unavailable. Probe for the issue-#366 regression test: proves GTK was
 * dlopen-ed and is functional in this process without leaking its
 * dependency closure into the global symbol scope.
 */
EXPORT jstring JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeGtkVersion(
    JNIEnv *env, jclass clazz)
{
    (void) clazz;
    if (!ensure_gtk_loaded()) return NULL;
    typedef unsigned int (*PFN_gtk_get_version)(void);
    /* dlopen(NOLOAD) re-yields the handle the loader already holds — the
     * lookup itself never maps a new copy of GTK. */
    void *libgtk = dlopen("libgtk-3.so.0", RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    if (libgtk == NULL) libgtk = dlopen("libgtk-3.so", RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    if (libgtk == NULL) return NULL;
    PFN_gtk_get_version get_major = (PFN_gtk_get_version) dlsym(libgtk, "gtk_get_major_version");
    PFN_gtk_get_version get_minor = (PFN_gtk_get_version) dlsym(libgtk, "gtk_get_minor_version");
    PFN_gtk_get_version get_micro = (PFN_gtk_get_version) dlsym(libgtk, "gtk_get_micro_version");
    if (!get_major || !get_minor || !get_micro) return NULL;
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u", get_major(), get_minor(), get_micro());
    return (*env)->NewStringUTF(env, buf);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr, jlong widget_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (gtk_window_ptr == 0 || widget_ptr == 0) return;

    GtkWidget *gtk_window = (GtkWidget *) (uintptr_t) gtk_window_ptr;
    GtkWidget *widget     = (GtkWidget *) (uintptr_t) widget_ptr;

    GtkWidget *overlay = resolve_overlay_for_window(gtk_window);
    if (overlay == NULL) return;

    /* Compose gives no ordering guarantee between this DisposableEffect
     * and the first onGloballyPositioned pass: either side may run
     * first. Whoever sees a valid rect first mounts; the other call
     * finds the widget already parented and leaves it alone. Until a
     * real rect lands the widget stays unparented — mounting it with
     * no rect would realise it at the 1×1 parking allocation. */
    widget_rect_t *rect = get_or_create_rect(widget);
    if (rect != NULL && rect->valid) {
        mount_on_overlay(overlay, widget);
    }
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong widget_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (widget_ptr == 0) return;

    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    GtkWidget *parent = g.gtk_widget_get_parent(widget);
    if (parent != NULL) {
        /* Teardown of the embed's GL can also swap the thread's EGL
         * context (see the EGL context guard above). */
        egl_snapshot_t saved = egl_save();
        g.gtk_container_remove((GtkContainer *) parent, widget);
        egl_restore(&saved);
    }
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeSetFrame(
    JNIEnv *env, jclass clazz,
    jlong gtk_window_ptr, jlong widget_ptr,
    jint x_logical, jint y_logical, jint w_logical, jint h_logical)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (gtk_window_ptr == 0 || widget_ptr == 0) return;
    if (w_logical <= 0 || h_logical <= 0) return;

    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    /* onGloballyPositioned can beat the attach DisposableEffect —
     * create the rect storage here too instead of requiring attach
     * to have run first. */
    widget_rect_t *rect = get_or_create_rect(widget);
    if (rect == NULL) return;

    /* Skip work if nothing changed — a window-resize gesture often
     * fires Compose layout passes with the same rect when only the
     * scale factor or some unrelated state shifted. */
    if (rect->valid && rect->x == x_logical && rect->y == y_logical &&
        rect->w == w_logical && rect->h == h_logical) {
        return;
    }

    rect->x = x_logical;
    rect->y = y_logical;
    rect->w = w_logical;
    rect->h = h_logical;
    rect->valid = 1;

    GtkWidget *parent = g.gtk_widget_get_parent(widget);
    if (parent == NULL) {
        /* First real rect: mount now, so the widget realises directly
         * at its final size (see mount_on_overlay). The add itself
         * queues the allocate pass that fires `get-child-position`. */
        GtkWidget *overlay =
            resolve_overlay_for_window((GtkWidget *) (uintptr_t) gtk_window_ptr);
        if (overlay != NULL) mount_on_overlay(overlay, widget);
        return;
    }

    /* Re-layout the overlay so `get-child-position` runs with the new
     * rect — now, not at the next frame-clock tick (see
     * relayout_overlay_now). */
    relayout_overlay_now(parent);
}

/* Clears the GTK window's focused widget. The Compose overlay slot
 * intercepts pointer events at the EGL subsurface (input region),
 * which means GTK never sees the click and keeps its previous focus
 * — typically the embedded WebKitWebView. With a focused widget
 * grabbing key events, GTK's `key-press-event` signal stops at that
 * widget and never reaches Tao's window-level handler, so the
 * Compose TextField never receives the keystroke.
 *
 * Releasing the focus (`gtk_window_set_focus(window, NULL)`) makes
 * key events propagate to the window-level handler instead, where
 * Tao picks them up and forwards them to the Compose scene. The
 * Compose-side focus chain then routes them to the focused
 * TextField. Equivalent in spirit to macOS's
 * `[window makeFirstResponder:overlay]` on click. */
EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeRequestKeyboardFocus(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (gtk_window_ptr == 0) return;
    GtkWindow *win = (GtkWindow *) (uintptr_t) gtk_window_ptr;
    g.gtk_window_set_focus(win, NULL);
}

/* Gives the keyboard back to Compose after a press Compose kept: when an
 * embed holds GTK focus (the user clicked into it earlier), a click on
 * Compose ground outside every input box would otherwise leave the keys
 * with the embed while Compose shows a focused text field. Clearing the
 * focus widget routes keys to the toplevel handler again — Tao picks them
 * up for Compose — and the next focus-in lands on the focus sink, never
 * on the embed. Returns whether anything changed. */
EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeClaimKeyboardForCompose(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded() || g.gtk_window_get_focus == NULL) return JNI_FALSE;
    if (gtk_window_ptr == 0) return JNI_FALSE;
    GtkWindow *win = (GtkWindow *) (uintptr_t) gtk_window_ptr;
    GtkWidget *focus = g.gtk_window_get_focus(win);
    if (focus == NULL || is_nucleus_input_box(focus)) return JNI_FALSE;
    g.gtk_window_set_focus(win, NULL);
    return JNI_TRUE;
}

/* The pointer's button state as GDK sees it right now (GDK_BUTTON1_MASK =
 * 1 << 8, BUTTON2 = 1 << 9, BUTTON3 = 1 << 10), or -1 when it cannot be
 * read. A press forwarded to an embed can lose its release to a grab the
 * embed takes — its context menu, a drag it starts — so the host asks GDK
 * which buttons are really down instead of trusting the last event. */
EXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeQueryPointerButtons(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded() || gtk_window_ptr == 0) return -1;
    if (g.gtk_widget_get_window == NULL || g.gdk_window_get_display == NULL ||
        g.gdk_display_get_default_seat == NULL || g.gdk_seat_get_pointer == NULL ||
        g.gdk_window_get_device_position == NULL) {
        return -1;
    }
    void *gdk_window = g.gtk_widget_get_window((GtkWidget *) (uintptr_t) gtk_window_ptr);
    if (gdk_window == NULL) return -1;
    void *display = g.gdk_window_get_display(gdk_window);
    void *seat = display != NULL ? g.gdk_display_get_default_seat(display) : NULL;
    void *pointer = seat != NULL ? g.gdk_seat_get_pointer(seat) : NULL;
    if (pointer == NULL) return -1;
    unsigned int mask = 0;
    g.gdk_window_get_device_position(gdk_window, pointer, NULL, NULL, &mask);
    return (jint) mask;
}

/* Asks GTK to paint — and so commit — its toplevel on its next frame. While
 * the content sub-surface is in sync mode (resize burst with an embed), a
 * Compose buffer is only shown by GTK's commit; GTK commits on every
 * configure while the pointer moves, and this covers the frames in between
 * and the last one after the pointer stops. */
EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeQueueToplevelDraw(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded() || g.gtk_widget_queue_draw == NULL || gtk_window_ptr == 0) return;
    g.gtk_widget_queue_draw((GtkWidget *) (uintptr_t) gtk_window_ptr);
}

/* ── Input-box overlay: hit capture for NativeView blending ──
 *
 * The Linux equivalent of Compose-first hit-testing over an embed. We
 * cannot use `wl_surface.set_input_region` on the EGL subsurface to
 * intercept clicks: nothing on our side listens for `wl_pointer.button`
 * events on that subsurface, so any click delivered to it gets dropped
 * by libwayland-client. Instead we synchronise an invisible
 * `GtkEventBox` overlay child with the Compose layout: the EventBox
 * sits at the `consumeOverlayPointerEvents` rect inside the GtkOverlay
 * we already inject, **above** the user's embedded widget in z-order
 * (GtkOverlay's add order). When the user clicks the rect, GTK
 * delivers `button-press-event` to the EventBox, which lets the event
 * bubble up unhandled all the way to `GtkApplicationWindow`, where
 * Tao's `connect_button_press_event` picks it up and forwards it to
 * the Compose scene — the standard event path. The user's GtkWidget
 * underneath never sees the click for that region.
 *
 * The EventBox additionally `gtk_widget_grab_focus()` itself on press
 * so subsequent keystrokes route through GTK's focus chain (EventBox
 * doesn't consume key events, so they bubble up to Tao the same way).
 * That replaces the earlier `gtk_window_set_focus(NULL)` hack and
 * makes typing into a Compose `BasicTextField` inside the overlay
 * Just Work. */

/* GdkEventButton partial layout — fields we read. Real GTK 3 layout:
 *   GdkEventType type; GdkWindow *window; gint8 send_event; guint32 time;
 *   gdouble x; gdouble y; gdouble *axes; guint state; guint button;
 *   GdkDevice *device; gdouble x_root; gdouble y_root;
 *
 * Note: `gint8 send_event` is followed by 3 bytes of padding before
 * the next 4-byte aligned field on most ABIs. We let the compiler
 * insert the natural padding by leaving struct alignment at default. */
typedef struct {
    int           type;         /* GdkEventType */
    void         *window;
    signed char   send_event;
    /* compiler inserts padding here for next-field alignment */
    unsigned int  time;
    double        x;
    double        y;
    double       *axes;
    unsigned int  state;
    unsigned int  button;       /* 1 = LEFT, 2 = MIDDLE, 3 = RIGHT */
    /* device, x_root, y_root, etc. follow */
} gdk_event_button_t;

/* Motion event has the same prefix up through y; doesn't have button. */
typedef struct {
    int           type;
    void         *window;
    signed char   send_event;
    unsigned int  time;
    double        x;
    double        y;
} gdk_event_motion_t;

/* GdkEventScroll partial layout — fields we read. Direction: 0 up,
 * 1 down, 2 left, 3 right, 4 smooth (delta_x/delta_y). */
typedef struct {
    int           type;
    void         *window;
    signed char   send_event;
    unsigned int  time;
    double        x;
    double        y;
    unsigned int  state;
    int           direction;
    void         *device;
    double        x_root;
    double        y_root;
    double        delta_x;
    double        delta_y;
} gdk_event_scroll_t;

/* Map GTK's native button code (1 = LEFT, 2 = MIDDLE, 3 = RIGHT) to
 * Tao's AWT-style encoding (`TaoMouseButton.LEFT = 0`, `RIGHT = 1`,
 * `MIDDLE = 2`). Anything else stays a passthrough — Compose's
 * `mapButton` falls back to `Primary` for unknown codes. */
static int gtk_button_to_tao(unsigned int gtk_button) {
    switch (gtk_button) {
        case 1: return 0; /* LEFT */
        case 2: return 2; /* MIDDLE */
        case 3: return 1; /* RIGHT */
        default: return (int) gtk_button;
    }
}

#define GDK_MOTION_NOTIFY   3
#define GDK_BUTTON_PRESS    4
#define GDK_2BUTTON_PRESS   5
#define GDK_3BUTTON_PRESS   6
#define GDK_BUTTON_RELEASE  7
#define GDK_SCROLL         31

/* Live GdkEvent* for the duration of an EventBox signal callback. The
 * Kotlin side dispatches into the Compose scene synchronously from
 * invoke_callback; when Compose leaves the hit unconsumed it calls
 * nativeDispatchPointer / nativeDispatchScroll re-entrantly — still
 * inside the signal handler — and we forward a retargeted *copy* of
 * this event. Outside a signal callback (e.g. the synthetic Move
 * Compose fires when a NativeView mounts under the cursor) there is
 * no device-backed event to forward, so dispatch degrades to a no-op:
 * synthesising GdkEvents from scratch has no GdkDevice, which makes
 * WebKit's device queries emit CRITICALs and later crash. */
static void *s_live_event = NULL;

/* Set when a press was handed to the embedded widget during the
 * current signal callback — the EventBox must then not steal GTK
 * keyboard focus back from it. */
static int s_live_event_forwarded = 0;

/* Delivers a retargeted copy of the live OS event to [widget]. The
 * copy keeps device / time / state / button; only the target window
 * and the window-local coords are swapped. GdkWindow is a GObject in
 * GTK 3, so plain g_object_ref/unref keep the refcounts balanced —
 * gdk_event_free unrefs whatever window the event points at. */
static int forward_live_event(GtkWidget *widget, double x, double y) {
    if (s_live_event == NULL || g.gdk_event_copy == NULL ||
        g.gdk_event_free == NULL || g.gtk_widget_event == NULL ||
        g.gtk_widget_get_window == NULL ||
        g.g_object_ref == NULL || g.g_object_unref == NULL) {
        return 0;
    }
    void *window = g.gtk_widget_get_window(widget);
    if (window == NULL) return 0;
    void *copy = g.gdk_event_copy(s_live_event);
    if (copy == NULL) return 0;
    gdk_event_motion_t *e = (gdk_event_motion_t *) copy;
    void *old = e->window;
    e->window = g.g_object_ref(window);
    if (old != NULL) g.g_object_unref(old);
    e->x = x;
    e->y = y;
    g.gtk_widget_event(widget, copy);
    g.gdk_event_free(copy);
    return 1;
}

static int live_event_type(void) {
    return s_live_event != NULL ? ((gdk_event_motion_t *) s_live_event)->type : -1;
}

/* Map EventBox-local event coords into Compose's content-area logical space.
 *
 * With the yaru-style hidden-titlebar CSD, the GtkApplicationWindow includes
 * theme shadow margins around the content GtkBox. Tao's normal pointer path
 * (and the EGL subsurface) use content (0,0) — translating to the *toplevel*
 * leaves those margins in the coords and shifts every overlay click by ~the
 * shadow size. Prefer the bin child (content box); fall back to the toplevel
 * when CSD is off / no child (identity for flat undecorated windows). */
static int translate_to_content(GtkWidget *widget, double ex, double ey, int *out_x, int *out_y) {
    *out_x = -1; *out_y = -1;
    if (g.gtk_widget_get_toplevel == NULL || g.gtk_widget_translate_coordinates == NULL) return 0;
    GtkWidget *toplevel = g.gtk_widget_get_toplevel(widget);
    if (toplevel == NULL) return 0;
    GtkWidget *target = toplevel;
    if (g.gtk_bin_get_child != NULL) {
        GtkWidget *child = g.gtk_bin_get_child(toplevel);
        if (child != NULL) target = child;
    }
    return g.gtk_widget_translate_coordinates(widget, target, (int) ex, (int) ey, out_x, out_y) ? 1 : 0;
}

static gboolean on_input_box_button_press(GtkWidget *widget, void *event_ptr,
                                          void *user_data) {
    (void) user_data;
    gdk_event_button_t *e = (gdk_event_button_t *) event_ptr;
    if (e == NULL) return GTK_FALSE;
    int wx, wy;
    translate_to_content(widget, e->x, e->y, &wx, &wy);
    /* Forward content-relative coords + press to Compose, bypassing
     * Tao's `cursor.window_at_position()` which on Wayland reports
     * EventBox-local coords because of WebKit's accelerated subsurface
     * stealing the seat focus. The callback updates the host's
     * `lastPointerX/Y` BEFORE Tao's bubbled-up button-press handler
     * fires, so the click hits-tests at the right place in Compose. */
    int taoBtn = gtk_button_to_tao(e->button);
    s_live_event = event_ptr;
    s_live_event_forwarded = 0;
    invoke_callback(widget, EVT_OVERLAY_MOVE, wx, wy, /*button*/ 0);
    invoke_callback(widget, EVT_OVERLAY_PRESS, wx, wy, taoBtn);
    s_live_event = NULL;
    /* Focus the box only when Compose kept the press; if it was
     * forwarded to the embed, the embed grabbed focus and stealing it
     * back would send the next keystrokes to Compose instead. And only
     * when the keyboard is not already Compose's: moving focus from one
     * of our boxes to another fires a focus-out that the Kotlin side
     * turns into "clear the Compose focus" — a click on a Compose button
     * over an embed would deselect the text field beside it. */
    if (!s_live_event_forwarded && g.gtk_widget_grab_focus != NULL &&
        !is_nucleus_input_box(focus_widget_of(widget))) {
        g.gtk_widget_grab_focus(widget);
    }
    /* TRUE = consume the event. We have already dispatched it to
     * Compose via the callback; letting GTK bubble it would cause
     * Tao's handler to ALSO send a click — duplicate event. */
    return GTK_TRUE;
}

static gboolean on_input_box_button_release(GtkWidget *widget, void *event_ptr,
                                            void *user_data) {
    (void) user_data;
    gdk_event_button_t *e = (gdk_event_button_t *) event_ptr;
    if (e == NULL) return GTK_FALSE;
    int wx, wy;
    translate_to_content(widget, e->x, e->y, &wx, &wy);
    s_live_event = event_ptr;
    invoke_callback(widget, EVT_OVERLAY_RELEASE, wx, wy, gtk_button_to_tao(e->button));
    s_live_event = NULL;
    return GTK_TRUE;
}

/* Fires when the EventBox loses GTK keyboard focus — typically when
 * the user clicks somewhere outside our overlay (e.g. on the embedded
 * WebView). Equivalent to macOS's `resignFirstResponder` callback;
 * Compose's `focusManager.releaseFocus()` is invoked on the Kotlin
 * side so a focused `BasicTextField` visually deselects. */
static gboolean on_input_box_focus_out(GtkWidget *widget, void *event_ptr,
                                       void *user_data) {
    (void) event_ptr; (void) user_data;
    invoke_callback(widget, EVT_OVERLAY_FOCUS_OUT, 0, 0, 0);
    return GTK_FALSE; /* let GTK handle its own focus chain bookkeeping */
}

static gboolean on_input_box_motion_notify(GtkWidget *widget, void *event_ptr,
                                           void *user_data) {
    (void) user_data;
    gdk_event_motion_t *e = (gdk_event_motion_t *) event_ptr;
    if (e == NULL) return GTK_FALSE;
    int wx, wy;
    translate_to_content(widget, e->x, e->y, &wx, &wy);
    s_live_event = event_ptr;
    invoke_callback(widget, EVT_OVERLAY_MOVE, wx, wy, /*button*/ 0);
    s_live_event = NULL;
    /* Consume so Tao's GtkApplicationWindow-level motion handler
     * doesn't ALSO fire — its `cursor.window_at_position()` reports
     * EventBox-local coords (broken by WebKit's accelerated
     * subsurface stealing seat focus), which collides with our
     * already-correct callback dispatch and produces alternating
     * Move events at conflicting positions. The visible symptom is a
     * cursor that flickers between the I-beam (when Compose hits the
     * TextField at the right pos) and the default arrow (when it
     * misses with the wrong pos). */
    return GTK_TRUE;
}

#define GDK_SCROLL_UP     0
#define GDK_SCROLL_DOWN   1
#define GDK_SCROLL_LEFT   2
#define GDK_SCROLL_RIGHT  3
#define GDK_SCROLL_SMOOTH 4

/* Hands the live wheel event straight to the embedded overlay child
 * under the pointer, if any. The Compose redispatch path is not
 * reliable for wheels — the demo-typical `Box(fillMaxSize)` overlay
 * sibling often swallows `PointerEventType.Scroll` before it reaches
 * the NativeView modifier — so the embed is served directly from the
 * EventBox handler. Rects live in overlay coords: the event's x/y are
 * relative to the box's own overlay child window, whose origin is the
 * box rect. */
static int forward_scroll_to_embed(GtkWidget *box, gdk_event_scroll_t *e) {
    if (g.gtk_container_get_children == NULL || g.g_list_free == NULL) return 0;
    GtkWidget *overlay = g.gtk_widget_get_parent(box);
    if (overlay == NULL) return 0;
    widget_rect_t *box_rect = (widget_rect_t *)
        g.g_object_get_data(box, NUCLEUS_RECT_KEY);
    if (box_rect == NULL || !box_rect->valid) return 0;
    double ox = box_rect->x + e->x;
    double oy = box_rect->y + e->y;
    GtkWidget *target = NULL;
    widget_rect_t *target_rect = NULL;
    GList *children = g.gtk_container_get_children((GtkContainer *) overlay);
    for (GList *it = children; it != NULL; it = it->next) {
        GtkWidget *child = (GtkWidget *) it->data;
        if (child == NULL) continue;
        if (g.g_object_get_data(child, NUCLEUS_INPUT_BOX_KEY) != NULL) continue;
        widget_rect_t *r = (widget_rect_t *)
            g.g_object_get_data(child, NUCLEUS_RECT_KEY);
        if (r == NULL || !r->valid) continue;
        if (ox < r->x || oy < r->y ||
            ox >= r->x + r->w || oy >= r->y + r->h) {
            continue;
        }
        /* Later children stack above earlier ones — keep the last hit. */
        target = child;
        target_rect = r;
    }
    if (children != NULL) g.g_list_free(children);
    if (target == NULL) return 0;
    s_live_event = e;
    int forwarded =
        forward_live_event(target, ox - target_rect->x, oy - target_rect->y);
    s_live_event = NULL;
    return forwarded;
}

static gboolean on_input_box_scroll(GtkWidget *widget, void *event_ptr,
                                    void *user_data) {
    (void) user_data;
    gdk_event_scroll_t *e = (gdk_event_scroll_t *) event_ptr;
    if (e == NULL) return GTK_FALSE;
    /* Wheel over an embed scrolls the embed, always consumed either
     * way so the same wheel never double-dispatches through Tao. */
    if (forward_scroll_to_embed(widget, e)) return GTK_TRUE;
    int wx, wy;
    translate_to_content(widget, e->x, e->y, &wx, &wy);
    float dx = (float) e->delta_x;
    float dy = (float) e->delta_y;
    if (e->direction == GDK_SCROLL_UP) { dx = 0.f; dy = -1.f; }
    else if (e->direction == GDK_SCROLL_DOWN) { dx = 0.f; dy = 1.f; }
    else if (e->direction == GDK_SCROLL_LEFT) { dx = -1.f; dy = 0.f; }
    else if (e->direction == GDK_SCROLL_RIGHT) { dx = 1.f; dy = 0.f; }
    s_live_event = event_ptr;
    invoke_scroll_callback(widget, wx, wy, dx, dy);
    s_live_event = NULL;
    return GTK_TRUE;
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeAddInputBox(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return 0;
    if (gtk_window_ptr == 0) return 0;
    GtkWidget *gtk_window = (GtkWidget *) (uintptr_t) gtk_window_ptr;

    GtkWidget *overlay = resolve_overlay_for_window(gtk_window);
    if (overlay == NULL) return 0;

    GtkWidget *box = g.gtk_event_box_new();
    if (box == NULL) return 0;
    /* CRITICAL: visible_window = FALSE so the EventBox does NOT have
     * its own GdkWindow. With visible_window = TRUE (default), GTK
     * creates an X / Wayland sub-window that captures pointer events
     * and reports motion coordinates **relative to itself**, not the
     * GtkApplicationWindow. Tao's `connect_motion_notify_event`
     * handler reads `cursor.window_at_position()` which returns
     * EventBox-local coords; Compose stores these in `lastPointerX/Y`,
     * and the subsequent click is dispatched to the wrong widget
     * because the position no longer matches the rect Compose laid
     * out. With visible_window = FALSE the EventBox uses its parent's
     * GdkWindow, motion events are reported in window coords
     * (correct), and click capture still works through GTK's widget
     * hit-test on the EventBox's allocation. */
    g.gtk_event_box_set_visible_window(box, GTK_FALSE);
    /* Need can-focus for keyboard routing on click — without this,
     * grab_focus is a no-op and keys keep going to the previously
     * focused widget (= embedded WebView). */
    g.gtk_widget_set_can_focus(box, GTK_TRUE);
    /* Don't paint a background — the EventBox is purely an event
     * sink, the visual rendering is Compose's job. */
    g.gtk_widget_set_app_paintable(box, GTK_TRUE);
    /* Allocate the rect cache (same pointer-key as embedded
     * widgets), with destroy notify so it's freed when the EventBox
     * itself is finalised. */
    get_or_create_rect(box);
    /* Tag as an input box so forward_scroll_to_embed skips it when
     * looking for the embedded widget under the pointer. */
    g.g_object_set_data(box, NUCLEUS_INPUT_BOX_KEY, (void *) 1);
    g.g_signal_connect_data(
        box, "button-press-event",
        (void (*)(void)) on_input_box_button_press, NULL, NULL, 0);
    g.g_signal_connect_data(
        box, "button-release-event",
        (void (*)(void)) on_input_box_button_release, NULL, NULL, 0);
    /* GDK_POINTER_MOTION_MASK=4, GDK_SCROLL_MASK=1<<21,
     * GDK_SMOOTH_SCROLL_MASK=1<<23. */
    g.gtk_widget_add_events(box, 4 | (1 << 21) | (1 << 23));
    g.g_signal_connect_data(
        box, "motion-notify-event",
        (void (*)(void)) on_input_box_motion_notify, NULL, NULL, 0);
    g.g_signal_connect_data(
        box, "scroll-event",
        (void (*)(void)) on_input_box_scroll, NULL, NULL, 0);
    g.g_signal_connect_data(
        box, "focus-out-event",
        (void (*)(void)) on_input_box_focus_out, NULL, NULL, 0);
    g.gtk_overlay_add_overlay((GtkOverlay *) overlay, box);
    g.gtk_widget_show(box);
    return (jlong) (uintptr_t) box;
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeMoveInputBox(
    JNIEnv *env, jclass clazz, jlong box_ptr,
    jint x_logical, jint y_logical, jint w_logical, jint h_logical)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (box_ptr == 0) return;
    if (w_logical <= 0 || h_logical <= 0) return;
    GtkWidget *box = (GtkWidget *) (uintptr_t) box_ptr;

    widget_rect_t *rect = get_or_create_rect(box);
    if (rect == NULL) return;
    if (rect->valid && rect->x == x_logical && rect->y == y_logical &&
        rect->w == w_logical && rect->h == h_logical) return;
    rect->x = x_logical; rect->y = y_logical;
    rect->w = w_logical; rect->h = h_logical;
    rect->valid = 1;

    GtkWidget *overlay = g.gtk_widget_get_parent(box);
    if (overlay != NULL) relayout_overlay_now(overlay);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeSetInputBoxCallback(
    JNIEnv *env, jclass clazz, jlong box_ptr, jobject callback)
{
    (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (box_ptr == 0) return;
    GtkWidget *box = (GtkWidget *) (uintptr_t) box_ptr;
    if (callback == NULL) {
        /* Clearing the data fires overlay_cb_destroy_notify on the previous
         * ref, if any — GObject owns the release in every path. */
        g.g_object_set_data(box, "nucleus_tao_overlay_cb", NULL);
        return;
    }
    ensure_callback_cache(env, callback);
    jobject globalRef = (*env)->NewGlobalRef(env, callback);
    g.g_object_set_data_full(box, "nucleus_tao_overlay_cb", globalRef,
                             overlay_cb_destroy_notify);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeRemoveInputBox(
    JNIEnv *env, jclass clazz, jlong box_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (box_ptr == 0) return;
    GtkWidget *box = (GtkWidget *) (uintptr_t) box_ptr;
    /* During app shutdown, GTK destroys the toplevel which transitively
     * destroys our EventBox before our DisposableEffect onDispose runs.
     * Calling gtk_widget_destroy on a stale pointer crashes with
     * `assertion 'GTK_IS_WIDGET (widget)' failed`. Validate the type
     * tag first via the GObject ABI so we silently skip in that case
     * instead of polluting the logs. (In that case GObject finalisation
     * already fired overlay_cb_destroy_notify for the callback ref.) */
    if (!g.g_type_check_instance_is_a(box, g.gtk_widget_get_type())) {
        return;
    }
    /* gtk_widget_destroy unparents from the GtkOverlay and releases
     * the GdkWindow + signal handlers; the rect cache and the callback
     * global ref attached via g_object_set_data_full are freed by their
     * destroy notifies. */
    g.gtk_widget_destroy(box);
}

/* [type] 1 down, 2 up, 3 move. Coords are widget-local logical pixels.
 * Only forwards a copy of the live EventBox event, and only when its
 * GDK type matches the Compose event being redispatched — the Move
 * that invoke_callback fires just before a press must not deliver the
 * stashed BUTTON_PRESS to the embed a second time. Without a matching
 * live event this is a no-op (never synthesise, never touch
 * gtk_get_current_event). */
EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDispatchPointer(
    JNIEnv *env, jclass clazz, jlong widget_ptr,
    jint type, jint x_logical, jint y_logical, jint button, jboolean pressed)
{
    (void) env; (void) clazz; (void) button; (void) pressed;
    if (!ensure_gtk_loaded()) return;
    if (widget_ptr == 0 || s_live_event == NULL) return;
    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    if (!g.g_type_check_instance_is_a(widget, g.gtk_widget_get_type())) return;

    int live = live_event_type();
    int matches =
        (type == 1 && (live == GDK_BUTTON_PRESS || live == GDK_2BUTTON_PRESS ||
                       live == GDK_3BUTTON_PRESS)) ||
        (type == 2 && live == GDK_BUTTON_RELEASE) ||
        (type == 3 && live == GDK_MOTION_NOTIFY);
    if (!matches) return;

    if (type == 1 && g.gtk_widget_grab_focus != NULL) {
        g.gtk_widget_grab_focus(widget);
    }
    if (forward_live_event(widget, (double) x_logical, (double) y_logical) &&
        type == 1) {
        s_live_event_forwarded = 1;
    }
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDispatchScroll(
    JNIEnv *env, jclass clazz, jlong widget_ptr,
    jint x_logical, jint y_logical, jfloat dx, jfloat dy)
{
    (void) env; (void) clazz; (void) dx; (void) dy;
    if (!ensure_gtk_loaded()) return;
    if (widget_ptr == 0 || live_event_type() != GDK_SCROLL) return;
    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    if (!g.g_type_check_instance_is_a(widget, g.gtk_widget_get_type())) return;
    forward_live_event(widget, (double) x_logical, (double) y_logical);
}

/* ── Diagnostics for the headful suite ──────────────────────────────────
 *
 * The test module has no way to fabricate a `GtkWidget*` of its own, and
 * a NativeView case needs a real, focusable one: a widget that takes GTK
 * keyboard focus on click and shows an I-beam is what the focus and
 * cursor races between Compose and an embed happen against. These entry
 * points hand out a plain `GtkEntry`, say which widget the GTK window
 * currently focuses, and read the entry back — nothing here is used by
 * `NativeView` itself. Resolved lazily and optionally, so a GTK without
 * one of these symbols still mounts embeds. */

typedef GtkWidget  *(*PFN_gtk_entry_new)(void);
typedef const char *(*PFN_gtk_entry_get_text)(GtkWidget *entry);
typedef GtkWidget  *(*PFN_gtk_window_get_focus)(GtkWindow *window);
typedef gboolean    (*PFN_gtk_widget_has_focus)(GtkWidget *widget);
typedef void       *(*PFN_g_object_ref_sink)(void *object);
typedef void        (*PFN_gtk_widget_get_allocation)(GtkWidget *widget, GdkRectangle *allocation);
typedef gboolean    (*PFN_gtk_widget_get_mapped)(GtkWidget *widget);

static struct {
    int resolved;
    PFN_gtk_entry_new        gtk_entry_new;
    PFN_gtk_entry_get_text   gtk_entry_get_text;
    PFN_gtk_window_get_focus gtk_window_get_focus;
    PFN_gtk_widget_has_focus gtk_widget_has_focus;
    PFN_g_object_ref_sink    g_object_ref_sink;
    PFN_gtk_widget_get_allocation gtk_widget_get_allocation;
    PFN_gtk_widget_get_mapped gtk_widget_get_mapped;
} diag;

static int ensure_diag_loaded(void) {
    if (!ensure_gtk_loaded()) return 0;
    if (diag.resolved) return diag.gtk_entry_new != NULL;
    diag.resolved = 1;
    const char *gtk_libs[] = { "libgtk-3.so.0", "libgtk-3.so", NULL };
    void *libgtk = load_first(gtk_libs);
    if (libgtk == NULL) return 0;
    diag.gtk_entry_new        = (PFN_gtk_entry_new)        dlsym(libgtk, "gtk_entry_new");
    diag.gtk_entry_get_text   = (PFN_gtk_entry_get_text)   dlsym(libgtk, "gtk_entry_get_text");
    diag.gtk_window_get_focus = (PFN_gtk_window_get_focus) dlsym(libgtk, "gtk_window_get_focus");
    diag.gtk_widget_has_focus = (PFN_gtk_widget_has_focus) dlsym(libgtk, "gtk_widget_has_focus");
    diag.gtk_widget_get_allocation = (PFN_gtk_widget_get_allocation) dlsym(libgtk, "gtk_widget_get_allocation");
    diag.gtk_widget_get_mapped = (PFN_gtk_widget_get_mapped) dlsym(libgtk, "gtk_widget_get_mapped");
    const char *gobj_libs[] = { "libgobject-2.0.so.0", "libgobject-2.0.so", NULL };
    void *libgobj = load_first(gobj_libs);
    if (libgobj != NULL) diag.g_object_ref_sink = (PFN_g_object_ref_sink) dlsym(libgobj, "g_object_ref_sink");
    return diag.gtk_entry_new != NULL && diag.g_object_ref_sink != NULL;
}

/* A fresh, unparented `GtkEntry` — the caller owns it until
 * nativeDiagDestroyWidget. Owned the way a well-behaved embedder owns a
 * widget it hands to NativeView: `g_object_ref_sink` here, so the
 * container's unparent on detach does not finalise it under the app, and
 * `g_object_unref` after the destroy. Shown here so the deferred mount in
 * nativeSetFrame maps it as soon as it is realised. */
EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDiagCreateEntry(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    if (!ensure_diag_loaded()) return 0;
    GtkWidget *entry = diag.gtk_entry_new();
    if (entry == NULL) return 0;
    diag.g_object_ref_sink(entry);
    g.gtk_widget_set_can_focus(entry, GTK_TRUE);
    g.gtk_widget_show(entry);
    return (jlong) (uintptr_t) entry;
}

/* Destroys a widget made by nativeDiagCreateEntry. Detaches it first so
 * GtkOverlay's child window bookkeeping runs before GTK finalises it. */
EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDiagDestroyWidget(
    JNIEnv *env, jclass clazz, jlong widget_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded() || widget_ptr == 0) return;
    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    if (!g.g_type_check_instance_is_a(widget, g.gtk_widget_get_type())) return;
    GtkWidget *parent = g.gtk_widget_get_parent(widget);
    if (parent != NULL) g.gtk_container_remove((GtkContainer *) parent, widget);
    g.gtk_widget_destroy(widget);
    g.g_object_unref(widget);
}

/* The widget the GTK window routes key events to, as a pointer, or 0
 * when nothing in the window has focus. A NativeView case compares it
 * against its entry and against nothing — after a click on Compose the
 * focus must sit on an input box (or nowhere), never on the embed. */
EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDiagFocusWidget(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_diag_loaded() || diag.gtk_window_get_focus == NULL) return 0;
    if (gtk_window_ptr == 0) return 0;
    GtkWidget *focus = diag.gtk_window_get_focus((GtkWindow *) (uintptr_t) gtk_window_ptr);
    return (jlong) (uintptr_t) focus;
}

/* Whether [widget_ptr] itself has GTK focus (its toplevel need not be active). */
EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDiagWidgetHasFocus(
    JNIEnv *env, jclass clazz, jlong widget_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_diag_loaded() || diag.gtk_widget_has_focus == NULL) return JNI_FALSE;
    if (widget_ptr == 0) return JNI_FALSE;
    return diag.gtk_widget_has_focus((GtkWidget *) (uintptr_t) widget_ptr) ? JNI_TRUE : JNI_FALSE;
}

/* The text typed into an entry made by nativeDiagCreateEntry — proves
 * that keystrokes reached the embed (or did not) after a focus change. */
EXPORT jstring JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDiagEntryText(
    JNIEnv *env, jclass clazz, jlong widget_ptr)
{
    (void) clazz;
    if (!ensure_diag_loaded() || diag.gtk_entry_get_text == NULL) return NULL;
    if (widget_ptr == 0) return NULL;
    const char *text = diag.gtk_entry_get_text((GtkWidget *) (uintptr_t) widget_ptr);
    return text != NULL ? (*env)->NewStringUTF(env, text) : NULL;
}

/* Where the probe actually sits: its allocation translated into the
 * coordinates of Tao's content box (the widget Compose's origin maps to),
 * in logical pixels, as `[x, y, w, h]` — or null while it is not mapped.
 * A resize case compares this against the Compose slot to measure how far
 * the embed trails the layout. */
EXPORT jintArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxWidgetBridge_nativeDiagWidgetFrame(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr, jlong widget_ptr)
{
    (void) clazz;
    if (!ensure_diag_loaded() || diag.gtk_widget_get_allocation == NULL || diag.gtk_widget_get_mapped == NULL) {
        return NULL;
    }
    if (gtk_window_ptr == 0 || widget_ptr == 0) return NULL;
    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    if (!g.g_type_check_instance_is_a(widget, g.gtk_widget_get_type())) return NULL;
    if (!diag.gtk_widget_get_mapped(widget)) return NULL;
    GtkWidget *content = g.gtk_bin_get_child((GtkWidget *) (uintptr_t) gtk_window_ptr);
    if (content == NULL) return NULL;
    GdkRectangle allocation;
    diag.gtk_widget_get_allocation(widget, &allocation);
    int x = 0, y = 0;
    if (!g.gtk_widget_translate_coordinates(widget, content, 0, 0, &x, &y)) return NULL;
    jint out[4] = { x, y, allocation.width, allocation.height };
    jintArray result = (*env)->NewIntArray(env, 4);
    if (result == NULL) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 4, out);
    return result;
}
