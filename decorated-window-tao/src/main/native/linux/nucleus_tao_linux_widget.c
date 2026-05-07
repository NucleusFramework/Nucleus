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
 *      cached on the GObject.
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

typedef GtkWidget *(*PFN_gtk_bin_get_child)(GtkWidget *bin);
typedef GtkWidget *(*PFN_gtk_widget_get_parent)(GtkWidget *widget);
typedef void       (*PFN_gtk_container_add)(GtkContainer *c, GtkWidget *w);
typedef void       (*PFN_gtk_container_remove)(GtkContainer *c, GtkWidget *w);
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
typedef void       (*PFN_g_object_set_data)(void *obj, const char *key, void *data);
typedef void       (*PFN_g_object_set_data_full)(
    void *obj, const char *key, void *data, void (*destroy)(void *));
typedef void      *(*PFN_g_object_get_data)(void *obj, const char *key);
typedef gulong     (*PFN_g_signal_connect_data)(
    void *instance, const char *signal, void (*handler)(void), void *data,
    void (*destroy)(void *, void *), int connect_flags);

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
    PFN_g_type_check_instance_is_a g_type_check_instance_is_a;
    PFN_gtk_box_get_type          gtk_box_get_type;
    PFN_g_object_set_data         g_object_set_data;
    PFN_g_object_set_data_full    g_object_set_data_full;
    PFN_g_object_get_data         g_object_get_data;
    PFN_g_signal_connect_data     g_signal_connect_data;
} g;

static void *load_first(const char *const *names) {
    for (int i = 0; names[i] != NULL; i++) {
        void *h = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
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
    g.gtk_box_get_type            = (PFN_gtk_box_get_type)            dlsym(libgtk, "gtk_box_get_type");

    g.g_type_check_instance_is_a  = (PFN_g_type_check_instance_is_a)  dlsym(libgobj, "g_type_check_instance_is_a");
    g.g_object_set_data           = (PFN_g_object_set_data)           dlsym(libgobj, "g_object_set_data");
    g.g_object_set_data_full      = (PFN_g_object_set_data_full)      dlsym(libgobj, "g_object_set_data_full");
    g.g_object_get_data           = (PFN_g_object_get_data)           dlsym(libgobj, "g_object_get_data");
    g.g_signal_connect_data       = (PFN_g_signal_connect_data)       dlsym(libgobj, "g_signal_connect_data");

    if (!g.gtk_bin_get_child || !g.gtk_widget_get_parent ||
        !g.gtk_container_add || !g.gtk_container_remove ||
        !g.gtk_overlay_new || !g.gtk_overlay_add_overlay ||
        !g.gtk_box_new || !g.gtk_box_pack_start ||
        !g.gtk_widget_set_size_request ||
        !g.gtk_widget_set_halign || !g.gtk_widget_set_valign ||
        !g.gtk_widget_show || !g.gtk_widget_queue_resize ||
        !g.gtk_box_get_type || !g.g_type_check_instance_is_a ||
        !g.g_object_set_data || !g.g_object_set_data_full ||
        !g.g_object_get_data || !g.g_signal_connect_data) {
        return 0;
    }
    g.initialized = 1;
    return 1;
}

/* ── Per-widget rect storage + overlay positioning ─────────────────── */

static const char NUCLEUS_OVERLAY_KEY[] = "nucleus_tao_widget_overlay";
static const char NUCLEUS_RECT_KEY[]    = "nucleus_tao_widget_rect";

typedef struct {
    gint x, y, w, h;
    gint valid;
} widget_rect_t;

/* `get-child-position` signal handler. Reads the cached rect from the
 * child's GObject data and writes it into [allocation]. Returning
 * TRUE tells GtkOverlay to use our values; FALSE falls back to the
 * default (which centers / fills the overlay child, not what we
 * want). */
static gboolean on_get_child_position(GtkWidget *overlay, GtkWidget *child,
                                      GdkRectangle *allocation, void *user_data) {
    (void) overlay; (void) user_data;
    widget_rect_t *r = (widget_rect_t *) g.g_object_get_data(child, NUCLEUS_RECT_KEY);
    if (r == NULL || !r->valid) return GTK_FALSE;
    if (allocation == NULL) return GTK_FALSE;
    allocation->x = r->x;
    allocation->y = r->y;
    allocation->width = r->w > 0 ? r->w : 1;
    allocation->height = r->h > 0 ? r->h : 1;
    return GTK_TRUE;
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

/* ── JNI exports ────────────────────────────────────────────────────── */

#define EXPORT JNIEXPORT __attribute__((visibility("default")))

EXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoLinuxWidgetBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong gtk_window_ptr, jlong widget_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (gtk_window_ptr == 0 || widget_ptr == 0) return;

    GtkWidget *gtk_window = (GtkWidget *) (uintptr_t) gtk_window_ptr;
    GtkWidget *widget     = (GtkWidget *) (uintptr_t) widget_ptr;

    GtkWidget *overlay = resolve_overlay_for_window(gtk_window);
    if (overlay == NULL) return;

    /* Defensive: if someone re-attaches an already-parented widget,
     * remove it from its old parent first. */
    GtkWidget *parent = g.gtk_widget_get_parent(widget);
    if (parent != NULL) {
        g.gtk_container_remove((GtkContainer *) parent, widget);
    }

    /* Force the embedded widget itself to report a 0 min-size so the
     * overlay's preferred size stays small even if the widget's
     * natural default would be large (WebKit's default is the
     * browser's idea of a "useful" minimum). */
    g.gtk_widget_set_size_request(widget, 0, 0);

    /* Initialize an empty rect — `get-child-position` returns FALSE
     * until the first nativeSetFrame call lands real values, so
     * GtkOverlay falls back to its default (centred) for the first
     * frame. The first nativeSetFrame typically fires the same tick
     * as nativeAttach so this is unobservable in practice. */
    widget_rect_t *rect = (widget_rect_t *)
        g.g_object_get_data(widget, NUCLEUS_RECT_KEY);
    if (rect == NULL) {
        rect = (widget_rect_t *) calloc(1, sizeof(*rect));
        if (rect != NULL) {
            g.g_object_set_data_full(widget, NUCLEUS_RECT_KEY, rect, free);
        }
    }

    g.gtk_overlay_add_overlay((GtkOverlay *) overlay, widget);
    g.gtk_widget_show(widget);
}

EXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoLinuxWidgetBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong widget_ptr)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (widget_ptr == 0) return;

    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    GtkWidget *parent = g.gtk_widget_get_parent(widget);
    if (parent != NULL) {
        g.gtk_container_remove((GtkContainer *) parent, widget);
    }
}

EXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoLinuxWidgetBridge_nativeSetFrame(
    JNIEnv *env, jclass clazz,
    jlong gtk_window_ptr, jlong widget_ptr,
    jint x_logical, jint y_logical, jint w_logical, jint h_logical)
{
    (void) env; (void) clazz;
    if (!ensure_gtk_loaded()) return;
    if (gtk_window_ptr == 0 || widget_ptr == 0) return;
    if (w_logical <= 0 || h_logical <= 0) return;

    GtkWidget *widget = (GtkWidget *) (uintptr_t) widget_ptr;
    widget_rect_t *rect = (widget_rect_t *)
        g.g_object_get_data(widget, NUCLEUS_RECT_KEY);
    if (rect == NULL) {
        /* Should have been attached first, but defend against
         * misuse — no-op if no rect storage. */
        return;
    }

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

    /* Trigger a re-layout pass on the overlay so
     * `get-child-position` runs with the new rect. The overlay
     * itself reports min = 0 (we pinned it via set_size_request),
     * so this does NOT propagate up to the GtkApplicationWindow —
     * shrinking the window stays cheap. */
    GtkWidget *overlay = g.gtk_widget_get_parent(widget);
    if (overlay != NULL) {
        g.gtk_widget_queue_resize(overlay);
    }
}
