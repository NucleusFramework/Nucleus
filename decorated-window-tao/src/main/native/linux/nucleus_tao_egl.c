/**
 * JNI bridge: EGL renderer for the Tao backend on Linux.
 *
 * Drives Skia's GL backend through `GLAssembledInterface.createFromNativePointers`
 * + `DirectContext.makeGLWithInterface(...)`, which lets us hand Skia an
 * `eglGetProcAddress`-resolved set of GL entry points instead of the
 * GLX-flavoured `GrGLMakeNativeInterface()` that Skiko's own `MakeGL()` returns.
 *
 * Two attach paths share the helper:
 *   - `nativeAttachX11(Display*, Window, …)`     — `EGL_PLATFORM_X11_KHR`
 *   - `nativeAttachWayland(wl_display*, wl_surface*, …)` — `EGL_PLATFORM_WAYLAND_KHR`
 *                                                     + `wl_egl_window`
 *
 * Selection between this helper and the legacy `nucleus_tao_glx.c` is
 * driven by `nucleus.tao.linux.renderer` (system property) or
 * `NUCLEUS_TAO_LINUX_RENDERER` (env-var); see `TaoComposeSceneHostLinux`.
 *
 * Linked libraries: -ldl. libEGL.so.1, libGL.so.1 / libOpenGL.so.0,
 * libX11.so.6, libXext.so.6 and libwayland-egl.so.1 are dlopen-ed at
 * runtime so the build doesn't require the dev packages and the .so
 * ships standalone. libwayland-egl is only required for the Wayland
 * attach path; X11-only setups don't need it.
 *
 * Wayland architecture. tao 0.35 + GTK 3 owns the `wl_surface` and
 * paints a cairo-shm buffer to it on every draw signal (event_loop.rs:912
 * of tao 0.35); GTK delays its `xdg_wm_base.get_xdg_surface(wl_surface)`
 * setup until after the first draw. Rendering EGL directly onto that
 * surface races with GTK's commits and trips
 * `xdg_wm_base.error(invalid_surface_state)`. The fix:
 * `nativeAttachWayland` creates an owned `wl_subsurface` child of GTK's
 * surface — same architectural pattern as the X11 child-window fallback
 * we use for visual mismatches. GTK keeps owning the parent + xdg_toplevel,
 * we render into the subsurface in `set_desync` mode so our buffer commits
 * land independently. Implementation hand-rolls `wl_compositor.create_surface`,
 * `wl_subcompositor.get_subsurface`, `set_position` and `set_desync` against
 * libwayland-client.so.0 — the C protocol headers aren't pulled at build
 * time so the .so ships standalone.
 *
 * Selection: GDK auto-picks the backend (= native Wayland on Wayland
 * sessions, X11 elsewhere). Set `NUCLEUS_TAO_LINUX_RENDERER=x11` to force
 * XWayland — useful for apps that need X11-specific features Wayland
 * deliberately doesn't expose (always-on-top, programmatic positioning,
 * global pointer queries, etc.).
 *
 * TODO (planned follow-ups, in priority order):
 *   - `wp_fractional_scale_v1` + `wp_viewporter` binding to honor
 *     fractional HiDPI scales correctly on Wayland (GTK 3 reports only
 *     integer scales, so 125% / 150% sessions currently get a buffer at
 *     1x and the compositor upscales).
 *   - `zxdg_decoration_manager_v1.set_mode(client_side)` so KWin /
 *     Wayland-Plasma 5 doesn't draw a server-side titlebar around our
 *     custom-chrome windows.
 *   - Frame-callback occlusion timeout: `eglSwapBuffers` blocks
 *     indefinitely when the surface is fully occluded by another window
 *     on wlroots compositors. Detect via `xdg_toplevel.configure` losing
 *     the `activated` state and fall back to a polling redraw.
 *   - NVIDIA `egl-wayland2` runtime detection — driver < 560 with the
 *     legacy egl-wayland can't resize an EGLSurface (drag-resize is
 *     broken upstream); document the package as a runtime requirement.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

#include "nucleus_tao_egl_internal.h"

#define NUCLEUS_TAO_EGL_DEBUG 0
#if NUCLEUS_TAO_EGL_DEBUG
#define DBG(...) fprintf(stderr, "[nucleus_tao_egl] " __VA_ARGS__)
#else
#define DBG(...) ((void)0)
#endif

/* ── EGL types & constants (subset, re-declared) ────────────────────────── */

typedef void          *EGLDisplay;
typedef void          *EGLConfig;
typedef void          *EGLSurface;
typedef void          *EGLContext;
typedef int            EGLBoolean;
typedef int            EGLint;
typedef unsigned int   EGLenum;
typedef void          *EGLNativeDisplayType;
typedef unsigned long  EGLNativeWindowType;   /* X11 Window XID on Xlib */

#define EGL_TRUE                       1
#define EGL_FALSE                      0
#define EGL_NO_DISPLAY                 ((EGLDisplay) 0)
#define EGL_NO_CONTEXT                 ((EGLContext) 0)
#define EGL_NO_SURFACE                 ((EGLSurface) 0)
#define EGL_NONE                       0x3038
#define EGL_RED_SIZE                   0x3024
#define EGL_GREEN_SIZE                 0x3023
#define EGL_BLUE_SIZE                  0x3022
#define EGL_ALPHA_SIZE                 0x3021
#define EGL_DEPTH_SIZE                 0x3025
#define EGL_STENCIL_SIZE               0x3026
#define EGL_SAMPLES                    0x3031
#define EGL_SURFACE_TYPE               0x3033
#define EGL_RENDERABLE_TYPE            0x3040
#define EGL_NATIVE_VISUAL_ID           0x302E
#define EGL_WINDOW_BIT                 0x0004
#define EGL_OPENGL_BIT                 0x0008
#define EGL_OPENGL_API                 0x30A2
#define EGL_CONTEXT_MAJOR_VERSION      0x3098
#define EGL_CONTEXT_MINOR_VERSION      0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK            0x30FD
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT  0x00000002
#define EGL_PLATFORM_X11_KHR           0x31D5
#define EGL_PLATFORM_WAYLAND_KHR       0x31D8

/* ── Xlib types & constants (subset) ────────────────────────────────────── */

typedef unsigned long XID;
typedef XID           Window;
typedef XID           Colormap;
typedef XID           Pixmap;
typedef struct _XDisplay Display;
typedef void         *Visual;
typedef unsigned long VisualID;

typedef struct {
    int            x, y;
    int            width, height;
    int            border_width;
    int            depth;
    Visual        *visual;
    Window         root;
    int            class_;
    int            bit_gravity;
    int            win_gravity;
    int            backing_store;
    unsigned long  backing_planes;
    unsigned long  backing_pixel;
    int            save_under;
    Colormap       colormap;
    int            map_installed;
    int            map_state;
    long           all_event_masks;
    long           your_event_mask;
    long           do_not_propagate_mask;
    int            override_redirect;
    void          *screen;
} XWindowAttributes;

/* `XVisualInfo` matches Xutil.h. We only read .visual / .visualid / .depth /
 * .screen — the rest is here so the struct size matches Xlib's. */
typedef struct {
    Visual        *visual;
    VisualID       visualid;
    int            screen;
    int            depth;
    int            class_;
    unsigned long  red_mask;
    unsigned long  green_mask;
    unsigned long  blue_mask;
    int            colormap_size;
    int            bits_per_rgb;
} XVisualInfo;

typedef struct {
    Pixmap        background_pixmap;
    unsigned long background_pixel;
    Pixmap        border_pixmap;
    unsigned long border_pixel;
    int           bit_gravity;
    int           win_gravity;
    int           backing_store;
    unsigned long backing_planes;
    unsigned long backing_pixel;
    int           save_under;
    long          event_mask;
    long          do_not_propagate_mask;
    int           override_redirect;
    Colormap      colormap;
    XID           cursor;
} XSetWindowAttributes;

typedef struct {
    short          x, y;
    unsigned short width, height;
} XRectangle;

#define None              0L
#define InputOutput       1
#define AllocNone         0
#define CWBorderPixel     (1L << 3)
#define CWEventMask       (1L << 11)
#define CWColormap        (1L << 13)

#define VisualIDMask      0x0001

/* XShape extension. Used to make the EGL-rendering child window
 * input-transparent so X routes pointer / keyboard events back to the GTK
 * parent — same trick the GLX helper uses. */
#define ShapeBounding 0
#define ShapeInput    2
#define ShapeSet      0
#define Unsorted      0

/* ── Function pointer types ─────────────────────────────────────────────── */

typedef EGLDisplay (*PFN_eglGetDisplay)(EGLNativeDisplayType);
typedef EGLDisplay (*PFN_eglGetPlatformDisplay)(EGLenum, void *, const intptr_t *);
typedef EGLBoolean (*PFN_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*PFN_eglTerminate)(EGLDisplay);
typedef EGLBoolean (*PFN_eglBindAPI)(EGLenum);
typedef EGLBoolean (*PFN_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLBoolean (*PFN_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
typedef EGLContext (*PFN_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLBoolean (*PFN_eglDestroyContext)(EGLDisplay, EGLContext);
typedef EGLSurface (*PFN_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
typedef EGLBoolean (*PFN_eglDestroySurface)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*PFN_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (*PFN_eglSwapBuffers)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*PFN_eglSwapInterval)(EGLDisplay, EGLint);
typedef EGLint     (*PFN_eglGetError)(void);
typedef void      *(*PFN_eglGetProcAddress)(const char *);
typedef const char *(*PFN_eglQueryString)(EGLDisplay, EGLint);
typedef EGLContext (*PFN_eglGetCurrentContext)(void);
typedef EGLDisplay (*PFN_eglGetCurrentDisplay)(void);

#define EGL_VENDOR  0x3053
#define EGL_VERSION 0x3054

/* ── Wayland client + EGL helpers ───────────────────────────────────────── */

/* Opaque types — we only ever pass them to libwayland-* and libwayland-egl. */
typedef struct wl_egl_window_  wl_egl_window;
typedef struct wl_display_     wl_display;
typedef struct wl_surface_     wl_surface;
typedef struct wl_proxy_       wl_proxy;
typedef struct wl_event_queue_ wl_event_queue;

typedef wl_egl_window *(*PFN_wl_egl_window_create)(wl_surface *, int, int);
typedef void           (*PFN_wl_egl_window_destroy)(wl_egl_window *);
typedef void           (*PFN_wl_egl_window_resize)(wl_egl_window *, int, int, int, int);

/* `wl_message` and `wl_interface` are the static introspection tables for
 * each Wayland interface. We don't define our own — we read pointers via
 * dlsym from libwayland-client.so.0 — but we need the layout to pass them
 * to `wl_proxy_marshal_flags`. */
struct wl_message {
    const char *name;
    const char *signature;
    const struct wl_interface **types;
};
struct wl_interface {
    const char *name;
    int version;
    int method_count;
    const struct wl_message *methods;
    int event_count;
    const struct wl_message *events;
};

/* Subset of libwayland-client.so.0 we use for the wl_subsurface child path.
 * varargs `wl_proxy_marshal_flags` is the universal request-marshaller — we
 * call it with the same arg layout the inline statics in
 * <wayland-client-protocol.h> use. */
typedef wl_proxy *(*PFN_wl_proxy_marshal_flags)(
    wl_proxy *proxy, uint32_t opcode, const struct wl_interface *interface,
    uint32_t version, uint32_t flags, ...);
typedef int             (*PFN_wl_proxy_add_listener)(wl_proxy *, void (**)(void), void *);
typedef void            (*PFN_wl_proxy_destroy)(wl_proxy *);
typedef void            (*PFN_wl_proxy_set_queue)(wl_proxy *, wl_event_queue *);
typedef uint32_t        (*PFN_wl_proxy_get_version)(wl_proxy *);
typedef wl_event_queue *(*PFN_wl_display_create_queue)(wl_display *);
typedef int             (*PFN_wl_display_roundtrip_queue)(wl_display *, wl_event_queue *);
typedef int             (*PFN_wl_display_dispatch_queue)(wl_display *, wl_event_queue *);
typedef int             (*PFN_wl_display_dispatch_queue_pending)(wl_display *, wl_event_queue *);
typedef void            (*PFN_wl_event_queue_destroy)(wl_event_queue *);
typedef int             (*PFN_wl_display_flush)(wl_display *);

/* Wayland protocol opcodes (from wayland.xml; stable since the protocol
 * was frozen in 2014, won't change). Re-declared instead of imported so
 * the build doesn't pull in libwayland-dev headers. */
#define WL_MARSHAL_FLAG_DESTROY            1
#define WL_DISPLAY_GET_REGISTRY            1
#define WL_REGISTRY_BIND                   0
#define WL_COMPOSITOR_CREATE_SURFACE       0
#define WL_COMPOSITOR_CREATE_REGION        1
#define WL_REGION_DESTROY                  0
#define WL_REGION_ADD                      1
#define WL_SUBCOMPOSITOR_GET_SUBSURFACE    1
#define WL_SUBSURFACE_DESTROY              0
#define WL_SUBSURFACE_SET_POSITION         1
#define WL_SUBSURFACE_SET_SYNC             4
#define WL_SUBSURFACE_SET_DESYNC           5
#define WL_SURFACE_DESTROY                 0
#define WL_SURFACE_ATTACH                  1
#define WL_SURFACE_DAMAGE                  2
#define WL_SURFACE_FRAME                   3
#define WL_SURFACE_SET_OPAQUE_REGION       4
#define WL_SURFACE_SET_INPUT_REGION        5
#define WL_SURFACE_COMMIT                  6
#define WL_SURFACE_SET_BUFFER_SCALE        8

/* ── Xlib function pointer types ────────────────────────────────────────── */

typedef int          (*PFN_XGetWindowAttributes)(Display *, Window, XWindowAttributes *);
typedef VisualID     (*PFN_XVisualIDFromVisual)(Visual *);
typedef XVisualInfo *(*PFN_XGetVisualInfo)(Display *, long, XVisualInfo *, int *);
typedef int          (*PFN_XFree)(void *);
typedef Colormap     (*PFN_XCreateColormap)(Display *, Window, Visual *, int);
typedef int          (*PFN_XFreeColormap)(Display *, Colormap);
typedef Window       (*PFN_XCreateWindow)(Display *, Window, int, int, unsigned int, unsigned int,
                                          unsigned int, int, unsigned int, Visual *,
                                          unsigned long, XSetWindowAttributes *);
typedef int          (*PFN_XDestroyWindow)(Display *, Window);
typedef int          (*PFN_XMapWindow)(Display *, Window);
typedef int          (*PFN_XSync)(Display *, int);
typedef int          (*PFN_XFlush)(Display *);
typedef int          (*PFN_XResizeWindow)(Display *, Window, unsigned int, unsigned int);
typedef void         (*PFN_XShapeCombineRectangles)(Display *, Window, int, int, int,
                                                    XRectangle *, int, int, int);

/* ── Globals: dlopen handles + resolved symbols ─────────────────────────── */

static void *g_libegl = NULL;
static void *g_libgl  = NULL;     /* libGL.so.1 / libOpenGL.so.0 — for dlsym fallback */
static void *g_libx11 = NULL;

static PFN_eglGetDisplay         p_eglGetDisplay         = NULL;
static PFN_eglGetPlatformDisplay p_eglGetPlatformDisplay = NULL;
static PFN_eglInitialize         p_eglInitialize         = NULL;
static PFN_eglBindAPI            p_eglBindAPI            = NULL;
static PFN_eglChooseConfig       p_eglChooseConfig       = NULL;
static PFN_eglGetConfigAttrib    p_eglGetConfigAttrib    = NULL;
static PFN_eglCreateContext      p_eglCreateContext      = NULL;
static PFN_eglDestroyContext     p_eglDestroyContext     = NULL;
static PFN_eglCreateWindowSurface p_eglCreateWindowSurface = NULL;
static PFN_eglDestroySurface     p_eglDestroySurface     = NULL;
static PFN_eglMakeCurrent        p_eglMakeCurrent        = NULL;
static PFN_eglSwapBuffers        p_eglSwapBuffers        = NULL;
static PFN_eglSwapInterval       p_eglSwapInterval       = NULL;
static PFN_eglGetError           p_eglGetError           = NULL;
static PFN_eglGetProcAddress     p_eglGetProcAddress     = NULL;
static PFN_eglQueryString        p_eglQueryString        = NULL;
static PFN_eglGetCurrentContext  p_eglGetCurrentContext  = NULL;
static PFN_eglGetCurrentDisplay  p_eglGetCurrentDisplay  = NULL;

static PFN_XGetWindowAttributes  p_XGetWindowAttributes  = NULL;
static PFN_XVisualIDFromVisual   p_XVisualIDFromVisual   = NULL;
static PFN_XGetVisualInfo        p_XGetVisualInfo        = NULL;
static PFN_XFree                 p_XFree                 = NULL;
static PFN_XCreateColormap       p_XCreateColormap       = NULL;
static PFN_XFreeColormap         p_XFreeColormap         = NULL;
static PFN_XCreateWindow         p_XCreateWindow         = NULL;
static PFN_XDestroyWindow        p_XDestroyWindow        = NULL;
static PFN_XMapWindow            p_XMapWindow            = NULL;
static PFN_XSync                 p_XSync                 = NULL;
static PFN_XFlush                p_XFlush                = NULL;
static PFN_XResizeWindow         p_XResizeWindow         = NULL;
static PFN_XShapeCombineRectangles p_XShapeCombineRectangles = NULL;

static void *g_libxext = NULL;
static void *g_libwlegl = NULL;
static void *g_libwlclient = NULL;
static int g_libs_loaded = 0;

static PFN_wl_egl_window_create  p_wl_egl_window_create  = NULL;
static PFN_wl_egl_window_destroy p_wl_egl_window_destroy = NULL;
static PFN_wl_egl_window_resize  p_wl_egl_window_resize  = NULL;

/* libwayland-client function pointers + interface globals (the latter
 * are exported `const struct wl_interface` symbols in the .so). */
static PFN_wl_proxy_marshal_flags     p_wl_proxy_marshal_flags     = NULL;
static PFN_wl_proxy_add_listener      p_wl_proxy_add_listener      = NULL;
static PFN_wl_proxy_destroy           p_wl_proxy_destroy           = NULL;
static PFN_wl_proxy_set_queue         p_wl_proxy_set_queue         = NULL;
static PFN_wl_proxy_get_version       p_wl_proxy_get_version       = NULL;
static PFN_wl_display_create_queue          p_wl_display_create_queue          = NULL;
static PFN_wl_display_roundtrip_queue       p_wl_display_roundtrip_queue       = NULL;
static PFN_wl_display_dispatch_queue        p_wl_display_dispatch_queue        = NULL;
static PFN_wl_display_dispatch_queue_pending p_wl_display_dispatch_queue_pending = NULL;
static PFN_wl_event_queue_destroy           p_wl_event_queue_destroy           = NULL;
static PFN_wl_display_flush                 p_wl_display_flush                 = NULL;

static const struct wl_interface *g_wl_registry_interface     = NULL;
static const struct wl_interface *g_wl_compositor_interface   = NULL;
static const struct wl_interface *g_wl_subcompositor_interface= NULL;
static const struct wl_interface *g_wl_subsurface_interface   = NULL;
static const struct wl_interface *g_wl_surface_interface      = NULL;
static const struct wl_interface *g_wl_region_interface       = NULL;
static const struct wl_interface *g_wl_callback_interface     = NULL;

static int load_libs(void) {
    if (g_libs_loaded) return 1;

    if (!g_libegl) g_libegl = dlopen("libEGL.so.1", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libegl) g_libegl = dlopen("libEGL.so",   RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libegl) {
        DBG("dlopen libEGL.so.1 failed: %s\n", dlerror());
        return 0;
    }

    /* libGL is *only* the dlsym fallback for proc-address resolution on
     * drivers that don't honor EGL_KHR_get_all_proc_addresses. Failing to
     * find it isn't fatal — modern Mesa & NVIDIA drivers return all entry
     * points through eglGetProcAddress directly. */
    if (!g_libgl) g_libgl = dlopen("libGL.so.1",     RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libgl) g_libgl = dlopen("libOpenGL.so.0", RTLD_LAZY | RTLD_GLOBAL);

    if (!g_libx11) g_libx11 = dlopen("libX11.so.6", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libx11) g_libx11 = dlopen("libX11.so",   RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libx11) {
        DBG("dlopen libX11.so.6 failed: %s\n", dlerror());
        return 0;
    }

    /* libXext for XShape — required only for the child-window fallback path
     * that kicks in when GDK's X visual doesn't match any EGLConfig
     * (typical on XWayland; see attach loop below). Failing to load it
     * isn't fatal: we'll just refuse to fall back and surface a clearer
     * EGL_BAD_CONFIG error. */
    if (!g_libxext) g_libxext = dlopen("libXext.so.6", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libxext) g_libxext = dlopen("libXext.so",   RTLD_LAZY | RTLD_GLOBAL);

    /* libwayland-egl + libwayland-client — needed for the Wayland attach
     * path only. We don't fail load_libs() if missing; X11 sessions don't
     * need them and `nativeAttachWayland` will return 0 with a clear log. */
    if (!g_libwlegl) g_libwlegl = dlopen("libwayland-egl.so.1", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libwlegl) g_libwlegl = dlopen("libwayland-egl.so",   RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libwlclient) g_libwlclient = dlopen("libwayland-client.so.0", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libwlclient) g_libwlclient = dlopen("libwayland-client.so",   RTLD_LAZY | RTLD_GLOBAL);

#define LOAD(lib, sym) p_##sym = (PFN_##sym) dlsym(lib, #sym)
    LOAD(g_libegl, eglGetDisplay);
    LOAD(g_libegl, eglGetPlatformDisplay);
    LOAD(g_libegl, eglInitialize);
    LOAD(g_libegl, eglBindAPI);
    LOAD(g_libegl, eglChooseConfig);
    LOAD(g_libegl, eglGetConfigAttrib);
    LOAD(g_libegl, eglCreateContext);
    LOAD(g_libegl, eglDestroyContext);
    LOAD(g_libegl, eglCreateWindowSurface);
    LOAD(g_libegl, eglDestroySurface);
    LOAD(g_libegl, eglMakeCurrent);
    LOAD(g_libegl, eglSwapBuffers);
    LOAD(g_libegl, eglSwapInterval);
    LOAD(g_libegl, eglGetError);
    LOAD(g_libegl, eglGetProcAddress);
    LOAD(g_libegl, eglQueryString);
    /* Used by nucleus_tao_texture_linux.c to resolve (and validate) the EGL
     * display/context the external-texture import must run on. */
    LOAD(g_libegl, eglGetCurrentContext);
    LOAD(g_libegl, eglGetCurrentDisplay);

    LOAD(g_libx11, XGetWindowAttributes);
    LOAD(g_libx11, XVisualIDFromVisual);
    LOAD(g_libx11, XGetVisualInfo);
    LOAD(g_libx11, XFree);
    LOAD(g_libx11, XCreateColormap);
    LOAD(g_libx11, XFreeColormap);
    LOAD(g_libx11, XCreateWindow);
    LOAD(g_libx11, XDestroyWindow);
    LOAD(g_libx11, XMapWindow);
    LOAD(g_libx11, XSync);
    LOAD(g_libx11, XFlush);
    LOAD(g_libx11, XResizeWindow);
    if (g_libxext) {
        p_XShapeCombineRectangles =
            (PFN_XShapeCombineRectangles) dlsym(g_libxext, "XShapeCombineRectangles");
    }
    if (g_libwlegl) {
        p_wl_egl_window_create  =
            (PFN_wl_egl_window_create)  dlsym(g_libwlegl, "wl_egl_window_create");
        p_wl_egl_window_destroy =
            (PFN_wl_egl_window_destroy) dlsym(g_libwlegl, "wl_egl_window_destroy");
        p_wl_egl_window_resize  =
            (PFN_wl_egl_window_resize)  dlsym(g_libwlegl, "wl_egl_window_resize");
    }
    if (g_libwlclient) {
        p_wl_proxy_marshal_flags =
            (PFN_wl_proxy_marshal_flags) dlsym(g_libwlclient, "wl_proxy_marshal_flags");
        p_wl_proxy_add_listener =
            (PFN_wl_proxy_add_listener)  dlsym(g_libwlclient, "wl_proxy_add_listener");
        p_wl_proxy_destroy =
            (PFN_wl_proxy_destroy)       dlsym(g_libwlclient, "wl_proxy_destroy");
        p_wl_proxy_set_queue =
            (PFN_wl_proxy_set_queue)     dlsym(g_libwlclient, "wl_proxy_set_queue");
        p_wl_proxy_get_version =
            (PFN_wl_proxy_get_version)   dlsym(g_libwlclient, "wl_proxy_get_version");
        p_wl_display_create_queue =
            (PFN_wl_display_create_queue)          dlsym(g_libwlclient, "wl_display_create_queue");
        p_wl_display_roundtrip_queue =
            (PFN_wl_display_roundtrip_queue)       dlsym(g_libwlclient, "wl_display_roundtrip_queue");
        p_wl_display_dispatch_queue =
            (PFN_wl_display_dispatch_queue)        dlsym(g_libwlclient, "wl_display_dispatch_queue");
        p_wl_display_dispatch_queue_pending =
            (PFN_wl_display_dispatch_queue_pending) dlsym(g_libwlclient, "wl_display_dispatch_queue_pending");
        p_wl_event_queue_destroy =
            (PFN_wl_event_queue_destroy)           dlsym(g_libwlclient, "wl_event_queue_destroy");
        p_wl_display_flush =
            (PFN_wl_display_flush)                 dlsym(g_libwlclient, "wl_display_flush");
        g_wl_registry_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_registry_interface");
        g_wl_compositor_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_compositor_interface");
        g_wl_subcompositor_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_subcompositor_interface");
        g_wl_subsurface_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_subsurface_interface");
        g_wl_surface_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_surface_interface");
        g_wl_region_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_region_interface");
        g_wl_callback_interface =
            (const struct wl_interface *) dlsym(g_libwlclient, "wl_callback_interface");
    }
#undef LOAD

    g_libs_loaded =
        p_eglInitialize && p_eglBindAPI && p_eglChooseConfig &&
        p_eglCreateContext && p_eglCreateWindowSurface && p_eglMakeCurrent &&
        p_eglSwapBuffers && p_eglGetProcAddress &&
        p_XGetWindowAttributes && p_XVisualIDFromVisual;

    return g_libs_loaded;
}

/* ── Diagnostics: log EGL vendor/version once on first attach ───────────── */

/**
 * Logs the EGL vendor/version + a NVIDIA-specific runtime hint to stderr the
 * first time we initialize a display. Always emitted (regardless of
 * NUCLEUS_TAO_EGL_DEBUG) because the NVIDIA hint is actionable: drag-resize on
 * Wayland is broken on driver < 560 unless the system has the new
 * `egl-wayland2` external-platform library installed alongside the legacy
 * `egl-wayland`. Users hit this and file "resize stutters / locks up" without
 * knowing what to install.
 *
 * Heuristic: on Wayland sessions (`is_wayland`), check the EGL vendor string —
 * NVIDIA's contains "NVIDIA". The "is egl-wayland2 installed?" check would
 * require parsing /proc/self/maps for `libnvidia-egl-wayland2.so` which is
 * fragile; we just print the hint unconditionally on NVIDIA Wayland and let
 * users decide if they need it.
 */
static int g_diag_logged = 0;
static void log_egl_diagnostics_once(EGLDisplay edpy, int is_wayland) {
    if (g_diag_logged) return;
    g_diag_logged = 1;
    if (!p_eglQueryString) return;
    const char *vendor  = p_eglQueryString(edpy, EGL_VENDOR);
    const char *version = p_eglQueryString(edpy, EGL_VERSION);
    DBG("EGL %s, vendor: %s, platform: %s\n",
        version ? version : "?",
        vendor  ? vendor  : "?",
        is_wayland ? "Wayland" : "X11");
    if (is_wayland && vendor && strstr(vendor, "NVIDIA")) {
        DBG("NOTE: NVIDIA Wayland — if drag-resize "
            "stutters or hangs, ensure your distro ships the\n"
            "[nucleus_tao_egl]       `egl-wayland2` package alongside "
            "`egl-wayland` (driver 560+ uses the new dma-buf backend that\n"
            "[nucleus_tao_egl]       supports proper EGLSurface resize; "
            "the legacy egl-wayland cannot resize EGLSurfaces).\n");
    }
}

/* ── Skia proc-address loader ───────────────────────────────────────────── */

/**
 * Signature matches Skia's `GrGLGetProc`:  void* fn(void* ctx, const char* name).
 * Tries `eglGetProcAddress` first — on every driver advertising
 * `EGL_KHR_get_all_proc_addresses` (Mesa 11+, NVIDIA 470+) this resolves all
 * core 1.0/1.1 entry points as well as extensions. Falls back to `dlsym` on
 * libGL/libOpenGL for the long tail of strict drivers, otherwise Skia
 * silently disables features whose entry points came back NULL.
 *
 * Address handed back to the JVM via `nativeGetProcAddrFunctionPointer`,
 * then forwarded to `GLAssembledInterface.createFromNativePointers(0, fnPtr)`.
 */
static void *nucleus_tao_egl_get_proc(void *ctx, const char *name) {
    (void) ctx;
    void *p = NULL;
    if (p_eglGetProcAddress) p = p_eglGetProcAddress(name);
    if (!p && g_libgl)        p = dlsym(g_libgl, name);
    return p;
}

/* ── Per-window state ───────────────────────────────────────────────────── */

typedef struct {
    EGLDisplay display;
    EGLConfig  config;
    EGLContext context;
    EGLSurface surface;
    /* X11 plumbing. `parent_xid` is always the GTK-owned XID; `child_xid` is
     * non-zero only when GDK's visual didn't match any EGLConfig and we had
     * to create a child window with a Mesa-canonical visual on top. Both
     * stay 0 on the Wayland path. */
    Display   *xdisplay;
    Window     parent_xid;
    Window     child_xid;
    Colormap   child_colormap;
    /* Wayland plumbing. We never render directly to GTK's wl_surface —
     * instead we own a `wl_subsurface` child of it, which decouples our
     * buffer commits from GTK's xdg_shell handshake and its cairo paint
     * cycle (see file header for the full diagnosis). All these proxies
     * live on `wl_queue` so events on them don't race with GDK's default
     * queue. nativeDetach destroys them in this order: wl_window →
     * wl_subsurface → wl_child_surface → wl_compositor / wl_subcompositor →
     * wl_registry → wl_queue. */
    wl_display     *wl_display_conn;   /* GTK's wl_display — not owned. */
    wl_event_queue *wl_queue;
    wl_proxy       *wl_registry;
    wl_proxy       *wl_compositor;
    wl_proxy       *wl_subcompositor;
    wl_proxy       *wl_parent_surface; /* GTK's wl_surface — not owned, not destroyed. */
    wl_proxy       *wl_child_surface;
    wl_proxy       *wl_subsurface;
    wl_egl_window  *wl_window;
    /* Content-area origin inside the parent surface, logical px. (0,0) for a
     * plain undecorated toplevel; the GTK theme's shadow margins when the
     * hidden-titlebar CSD is active (GTK then draws its native drop shadow in
     * the ring around the content subsurface). Applied via
     * wl_subsurface.set_position — parent-surface state, takes effect on
     * GTK's next commit. */
    int             content_off_x;
    int             content_off_y;
    int             widthPx;
    int             heightPx;
    float      scale;
} EglAttachment;

/* ── Internal surface shared inside libnucleus_tao_egl.so ───────────────── */
/* Implemented here because this TU owns the dlopen'd EGL entry points and the
 * per-window attachment state; see nucleus_tao_egl_internal.h. */

int nucleus_tao_egl_ensure_libs(void) {
    return load_libs();
}

void *nucleus_tao_egl_proc_address(const char *name) {
    return nucleus_tao_egl_get_proc(NULL, name);
}

void *nucleus_tao_egl_current_display(void) {
    return p_eglGetCurrentDisplay ? p_eglGetCurrentDisplay() : NULL;
}

void *nucleus_tao_egl_current_context(void) {
    return p_eglGetCurrentContext ? p_eglGetCurrentContext() : NULL;
}

void *nucleus_tao_egl_attachment_context(long long handle) {
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    return att ? att->context : NULL;
}

/* ── JNI surface ────────────────────────────────────────────────────────── */

/**
 * Creates an EGL display + context on the X11 connection [xdisplayPtr] and a
 * window surface bound to [xid]. The chosen `EGLConfig` is filtered to match
 * the X visual already assigned to the GTK window (typically ARGB32 when
 * `with_transparent(true)` was passed to the WindowBuilder), so
 * `eglCreateWindowSurface` doesn't return `EGL_BAD_MATCH` on Mesa.
 *
 * Caller must invoke this on the thread that will own the EGL context.
 * Returns an opaque attachment handle, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeAttachX11(
    JNIEnv *env, jclass clazz,
    jlong xdisplayPtr, jlong xidLong,
    jint widthPx, jint heightPx)
{
    (void) env; (void) clazz;
    if (!xdisplayPtr || !xidLong) return 0;
    if (!load_libs()) return 0;

    Display *xdpy = (Display *) (uintptr_t) xdisplayPtr;
    Window   xwin = (Window)    (uintptr_t) xidLong;
    DBG("attachX11: xdpy=%p xid=0x%lx wxh=%dx%d\n", (void*)xdpy, xwin, widthPx, heightPx);

    /* 1) EGL display from the X11 connection. Prefer the EGL 1.5 platform
     *    function — it makes the platform explicit and works uniformly on
     *    Mesa & NVIDIA. Fall back to the legacy `eglGetDisplay(Display*)`
     *    which is sloppier but still accepts an X11 Display* as a native
     *    display type on every shipping driver. */
    EGLDisplay edpy = EGL_NO_DISPLAY;
    if (p_eglGetPlatformDisplay) {
        edpy = p_eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR, xdpy, NULL);
    }
    if (edpy == EGL_NO_DISPLAY && p_eglGetDisplay) {
        edpy = p_eglGetDisplay((EGLNativeDisplayType) xdpy);
    }
    if (edpy == EGL_NO_DISPLAY) {
        DBG("eglGetDisplay returned EGL_NO_DISPLAY\n");
        return 0;
    }

    EGLint maj = 0, min = 0;
    if (!p_eglInitialize(edpy, &maj, &min)) {
        DBG("eglInitialize failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        return 0;
    }
    DBG("EGL %d.%d initialized\n", maj, min);
    log_egl_diagnostics_once(edpy, /*is_wayland=*/0);

    /* 2) Desktop GL — must be set before eglCreateContext. Skia chooses
     *    GL vs GLES from `glGetString(GL_VERSION)` at make-current time;
     *    we want desktop because `GrGLMakeAssembledInterface` resolves
     *    desktop entry points. */
    if (!p_eglBindAPI(EGL_OPENGL_API)) {
        DBG("eglBindAPI(EGL_OPENGL_API) failed (driver lacks desktop GL?)\n");
        return 0;
    }

    /* 3) Pick a config matching the GTK window's X visual. On a compositing
     *    desktop with `with_transparent(true)`, GDK assigns an ARGB32 visual
     *    and we'll match against the ARGB EGL config; without compositing
     *    the visual is RGB888 and the same lookup picks an alpha=0 config. */
    const EGLint cfg_attrs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_DEPTH_SIZE,      0,    /* Skia provides its own depth/stencil   */
        EGL_STENCIL_SIZE,    0,    /* attachment to the SkSurface's FBO.    */
        EGL_SAMPLES,         0,    /* MSAA off; Skia handles AA itself.     */
        EGL_NONE
    };
    EGLConfig cfgs[64];
    EGLint    ncfg = 0;
    if (!p_eglChooseConfig(edpy, cfg_attrs, cfgs, 64, &ncfg) || ncfg <= 0) {
        DBG("eglChooseConfig returned no configs\n");
        return 0;
    }

    XWindowAttributes wa;
    memset(&wa, 0, sizeof(wa));
    if (!p_XGetWindowAttributes(xdpy, xwin, &wa) || !wa.visual) {
        DBG("XGetWindowAttributes failed\n");
        return 0;
    }
    VisualID want = p_XVisualIDFromVisual(wa.visual);
    DBG("window visualid=0x%lx depth=%d wxh=%dx%d\n",
        want, wa.depth, wa.width, wa.height);
    DBG("eglChooseConfig returned %d configs\n", ncfg);

    EGLConfig chosen = NULL;
    for (EGLint i = 0; i < ncfg; ++i) {
        EGLint id = 0;
        p_eglGetConfigAttrib(edpy, cfgs[i], EGL_NATIVE_VISUAL_ID, &id);
        DBG("  cfg[%d] visualid=0x%lx\n", i, (unsigned long)(unsigned)id);
        if ((VisualID) id == want) { chosen = cfgs[i]; break; }
    }

    /* Phase-1 widening: GTK 3 with `decorations=false` and no `transparent=true`
     * gets a 24-bit RGB888 visual on most setups, which our default ALPHA_SIZE=8
     * config request can't match. Re-query without the alpha constraint so we
     * land on a 24-bit config whose native visual matches the window. The
     * eventual rounded-corner work will pass `with_transparent(true)` to tao
     * and round-trip through ARGB; until then this fallback keeps Mesa happy. */
    if (!chosen) {
        DBG("no ARGB EGL config matches X visualid 0x%lx — retrying without alpha\n", want);
        const EGLint cfg_attrs_no_alpha[] = {
            EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
            EGL_RED_SIZE,        8,
            EGL_GREEN_SIZE,      8,
            EGL_BLUE_SIZE,       8,
            EGL_DEPTH_SIZE,      0,
            EGL_STENCIL_SIZE,    0,
            EGL_SAMPLES,         0,
            EGL_NONE
        };
        EGLConfig cfgs2[64];
        EGLint ncfg2 = 0;
        if (p_eglChooseConfig(edpy, cfg_attrs_no_alpha, cfgs2, 64, &ncfg2) && ncfg2 > 0) {
            DBG("  retry returned %d configs\n", ncfg2);
            for (EGLint i = 0; i < ncfg2; ++i) {
                EGLint id = 0;
                p_eglGetConfigAttrib(edpy, cfgs2[i], EGL_NATIVE_VISUAL_ID, &id);
                DBG("    cfg2[%d] visualid=0x%lx\n", i, (unsigned long)(unsigned)id);
                if ((VisualID) id == want) { chosen = cfgs2[i]; break; }
            }
            /* If no exact-visual match, prefer an ARGB config (alpha > 0)
             * so the eventual alpha-blended rounded-corner work has alpha
             * available on the EGL surface. Mesa typically lists RGB-only
             * configs first, so cfgs2[0] is a 24-bit visual on most setups —
             * walk the list and grab the first one with EGL_ALPHA_SIZE > 0. */
            if (!chosen) {
                for (EGLint i = 0; i < ncfg2; ++i) {
                    EGLint a = 0;
                    p_eglGetConfigAttrib(edpy, cfgs2[i], EGL_ALPHA_SIZE, &a);
                    if (a > 0) { chosen = cfgs2[i]; break; }
                }
            }
            /* Last resort: take cfgs2[0]. NVIDIA accepts the cross-match
             * silently; Mesa surfaces EGL_BAD_MATCH from
             * eglCreateWindowSurface so the error is at least visible. */
            if (!chosen && ncfg2 > 0) chosen = cfgs2[0];
        }
    }
    if (!chosen) chosen = cfgs[0];
    DBG("chosen EGLConfig=%p\n", (void*)chosen);

    /* Re-read the chosen config's native visual ID — used below to decide
     * whether we can render straight into the GTK window or need a
     * child-window with a matching visual. */
    EGLint chosen_vid = 0;
    p_eglGetConfigAttrib(edpy, chosen, EGL_NATIVE_VISUAL_ID, &chosen_vid);
    int needs_child = ((VisualID) chosen_vid != want);
    DBG("chosen visualid=0x%lx, needs_child=%d\n", (unsigned long)(unsigned)chosen_vid, needs_child);

    /* 4) Compat profile 3.3 — minimum for Skia's modern GL renderer; drivers
     *    will hand us a higher version if available. */
    const EGLint ctx_attrs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 3,
        EGL_CONTEXT_OPENGL_PROFILE_MASK,
            EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT,
        EGL_NONE
    };
    EGLContext ctx = p_eglCreateContext(edpy, chosen, EGL_NO_CONTEXT, ctx_attrs);
    if (ctx == EGL_NO_CONTEXT) {
        DBG("eglCreateContext failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        return 0;
    }

    /* When the GTK window's X visual doesn't match any of Mesa's EGLConfigs
     * (typical on XWayland: GDK's `screen.rgba_visual()` returns a different
     * 32-bit ARGB visual than the one Mesa registered with EGL), Mesa rejects
     * `eglCreateWindowSurface` with EGL_BAD_CONFIG. Mirror the GLX helper's
     * fallback: create a child X window with Mesa's expected visual on top
     * of the GTK parent, make it input-transparent via XShape so events
     * still flow to GTK, and bind EGL to that child instead.  */
    Window  egl_xid       = xwin;
    Window  child_xid     = (Window) None;
    Colormap child_cmap   = (Colormap) None;
    if (needs_child) {
        if (!p_XGetVisualInfo || !p_XCreateColormap || !p_XCreateWindow ||
            !p_XMapWindow      || !p_XSync) {
            DBG("Xlib symbols missing for child-window fallback\n");
            p_eglDestroyContext(edpy, ctx);
            return 0;
        }
        XVisualInfo template;
        memset(&template, 0, sizeof(template));
        template.visualid = (VisualID) chosen_vid;
        int n_vinfo = 0;
        XVisualInfo *vinfos = p_XGetVisualInfo(xdpy, VisualIDMask, &template, &n_vinfo);
        if (!vinfos || n_vinfo <= 0) {
            DBG("XGetVisualInfo for visualid 0x%lx returned no match\n",
                (unsigned long)(unsigned)chosen_vid);
            if (vinfos && p_XFree) p_XFree(vinfos);
            p_eglDestroyContext(edpy, ctx);
            return 0;
        }
        XVisualInfo vinfo = vinfos[0];
        if (p_XFree) p_XFree(vinfos);
        DBG("child visual: id=0x%lx depth=%d screen=%d\n",
            vinfo.visualid, vinfo.depth, vinfo.screen);

        child_cmap = p_XCreateColormap(xdpy, wa.root, vinfo.visual, AllocNone);

        XSetWindowAttributes swa;
        memset(&swa, 0, sizeof(swa));
        swa.colormap         = child_cmap;
        swa.event_mask       = 0;     /* don't subscribe — events go to parent */
        swa.background_pixel = 0;
        swa.border_pixel     = 0;
        unsigned int cw = (widthPx  > 0) ? (unsigned int) widthPx  : (unsigned int) wa.width;
        unsigned int ch = (heightPx > 0) ? (unsigned int) heightPx : (unsigned int) wa.height;
        if (cw == 0) cw = 1;
        if (ch == 0) ch = 1;
        child_xid = p_XCreateWindow(
            xdpy, xwin, 0, 0, cw, ch, 0,
            vinfo.depth, InputOutput, vinfo.visual,
            CWBorderPixel | CWColormap | CWEventMask, &swa);
        if (!child_xid) {
            DBG("XCreateWindow for child failed\n");
            if (p_XFreeColormap) p_XFreeColormap(xdpy, child_cmap);
            p_eglDestroyContext(edpy, ctx);
            return 0;
        }

        /* Make the child input-transparent so X11 routes pointer / keyboard
         * events back to the GTK parent (and therefore to tao's event loop).
         * Without this, every click hits the EGL surface and tao goes deaf. */
        if (p_XShapeCombineRectangles) {
            p_XShapeCombineRectangles(xdpy, child_xid, ShapeInput, 0, 0,
                                       NULL, 0, ShapeSet, Unsorted);
        } else {
            DBG("WARN: XShapeCombineRectangles not available — child window "
                "will eat input. Install libXext for proper input routing.\n");
        }
        p_XMapWindow(xdpy, child_xid);
        if (p_XSync) p_XSync(xdpy, 0);

        DBG("child_xid=0x%lx mapped over parent=0x%lx (%ux%u)\n",
            child_xid, xwin, cw, ch);
        egl_xid = child_xid;
    }

    EGLSurface surf = p_eglCreateWindowSurface(edpy, chosen,
                                               (EGLNativeWindowType) egl_xid, NULL);
    if (surf == EGL_NO_SURFACE) {
        DBG("eglCreateWindowSurface failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        if (child_xid && p_XDestroyWindow)  p_XDestroyWindow(xdpy, child_xid);
        if (child_cmap && p_XFreeColormap)  p_XFreeColormap(xdpy, child_cmap);
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }

    if (!p_eglMakeCurrent(edpy, surf, surf, ctx)) {
        DBG("eglMakeCurrent failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        p_eglDestroySurface(edpy, surf);
        if (child_xid && p_XDestroyWindow)  p_XDestroyWindow(xdpy, child_xid);
        if (child_cmap && p_XFreeColormap)  p_XFreeColormap(xdpy, child_cmap);
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }

    if (p_eglSwapInterval) p_eglSwapInterval(edpy, 1);

    EglAttachment *att = (EglAttachment *) calloc(1, sizeof(EglAttachment));
    if (!att) {
        p_eglMakeCurrent(edpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(edpy, surf);
        if (child_xid && p_XDestroyWindow)  p_XDestroyWindow(xdpy, child_xid);
        if (child_cmap && p_XFreeColormap)  p_XFreeColormap(xdpy, child_cmap);
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }
    att->display        = edpy;
    att->config         = chosen;
    att->context        = ctx;
    att->surface        = surf;
    att->xdisplay       = xdpy;
    att->parent_xid     = xwin;
    att->child_xid      = child_xid;
    att->child_colormap = child_cmap;
    att->widthPx        = widthPx  > 0 ? widthPx  : wa.width;
    att->heightPx       = heightPx > 0 ? heightPx : wa.height;
    att->scale          = 1.0f;
    DBG("attached: edpy=%p ctx=%p surf=%p (child=0x%lx)\n",
        edpy, (void*)ctx, (void*)surf, child_xid);
    return (jlong) (uintptr_t) att;
}

/* ── Wayland subsurface plumbing ────────────────────────────────────────── */

/**
 * State carried by the wl_registry::global listener while we're binding
 * `wl_compositor` and `wl_subcompositor` during attach. Owned by the calling
 * thread for the duration of the roundtrip — do not leak.
 */
typedef struct {
    wl_proxy *registry;
    wl_proxy *compositor;
    wl_proxy *subcompositor;
} WlBindState;

static void wl_registry_global(
    void *data, wl_proxy *registry, uint32_t name,
    const char *interface, uint32_t version)
{
    WlBindState *st = (WlBindState *) data;
    if (!st->compositor && strcmp(interface, "wl_compositor") == 0) {
        /* `wl_registry::bind` has signature "usun" (uint name, string interface,
         * uint version, new_id). For new_id without statically-known interface
         * libwayland sends `interface_name`+`version`+`new_id_handle` triplet on
         * the wire. Match what `wl_registry_bind` inline does. */
        uint32_t v = version < 4 ? version : 4;
        st->compositor = p_wl_proxy_marshal_flags(
            registry, WL_REGISTRY_BIND, g_wl_compositor_interface, v, 0,
            name, "wl_compositor", v, NULL);
    } else if (!st->subcompositor && strcmp(interface, "wl_subcompositor") == 0) {
        uint32_t v = version < 1 ? version : 1;
        st->subcompositor = p_wl_proxy_marshal_flags(
            registry, WL_REGISTRY_BIND, g_wl_subcompositor_interface, v, 0,
            name, "wl_subcompositor", v, NULL);
    }
}

static void wl_registry_global_remove(
    void *data, wl_proxy *registry, uint32_t name)
{
    (void) data; (void) registry; (void) name;
    /* No-op: compositor / subcompositor never disappear at runtime. */
}

/* Pointer-table fed to wl_proxy_add_listener — order must match the events
 * declared in wl_registry's wl_message[] (global, global_remove). */
static void (*const wl_registry_listener[])(void) = {
    (void (*)(void)) wl_registry_global,
    (void (*)(void)) wl_registry_global_remove,
};

/**
 * Sets the child subsurface's `buffer_scale` so the compositor reads our
 * `logical × scale` px buffer as `logical` surface units — matching GTK's
 * parent surface. Without it `buffer_scale` defaults to 1 and the subsurface
 * renders ~`scale`× oversized (and input lands in the wrong place because the
 * visible content no longer matches the scene's pixel geometry).
 *
 * GTK3 only ever reports an integer scale, so we mirror that integer here
 * (true fractional sharpness would need wp_viewporter + wp_fractional_scale_v1,
 * which is only clean once the renderer owns the toplevel — see file header).
 *
 * This only queues double-buffered surface state; it's applied atomically with
 * the new buffer at the next `eglSwapBuffers` commit, so no explicit
 * `wl_surface.commit` is issued here.
 */
static void wl_set_buffer_scale(EglAttachment *att, int scale) {
    if (!att || !att->wl_child_surface || !p_wl_proxy_marshal_flags) return;
    if (scale < 1) scale = 1;
    p_wl_proxy_marshal_flags(
        att->wl_child_surface, WL_SURFACE_SET_BUFFER_SCALE, NULL,
        p_wl_proxy_get_version(att->wl_child_surface), 0, scale);
}

/**
 * Rounds a buffer dimension UP to the next multiple of `scale`.
 *
 * The buffer we attach must be an integer multiple of the `buffer_scale`
 * announced above — the protocol says so and Mutter enforces it as a fatal
 * `wl_surface.invalid_size` ("Buffer size (101x61) must be an integer multiple
 * of the buffer_scale (2)"), which drops the connection and kills the client
 * (issue #502; weston silently tolerates it, so this only ever bit real GNOME
 * sessions). Callers are expected to pass aligned sizes already — Compose popup
 * bounds are aligned in `TaoPopupSceneLayerLinux`, window sizes come from the
 * compositor's own configure — so this is the backstop that keeps *any* future
 * caller from turning a rounding slip into a crash. Rounding up (never down:
 * a 1 px surface would collapse to 0) costs at most `scale - 1` px of
 * transparent edge.
 */
static int wl_align_to_buffer_scale(int px, int scale) {
    if (scale < 1) scale = 1;
    if (px <= scale) return scale;
    int remainder = px % scale;
    return remainder == 0 ? px : px + (scale - remainder);
}


/**
 * Wayland-native attach.
 *
 * We don't render onto GTK's wl_surface — GTK paints a cairo SHM buffer
 * onto it on every draw signal, and any `eglSwapBuffers` we'd issue on
 * the same surface would race with GTK's commit (and worse: trip the
 * xdg_shell `invalid_surface_state` error if we attached before GTK's
 * `get_xdg_surface`). Instead we own a `wl_subsurface` child of GTK's
 * surface. GTK keeps owning the parent + xdg_toplevel; we render into
 * the subsurface in `set_desync` mode so our commits land independently.
 *
 * Shape: same architectural pattern as the X11 child-window fallback,
 * just expressed through Wayland protocol primitives.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeAttachWayland(
    JNIEnv *env, jclass clazz,
    jlong wlDisplayPtr, jlong wlSurfacePtr,
    jint widthPx, jint heightPx, jint bufferScale, jint swapInterval)
{
    (void) env; (void) clazz;
    if (!wlDisplayPtr || !wlSurfacePtr) return 0;
    if (!load_libs()) return 0;
    if (!p_wl_egl_window_create) {
        fprintf(stderr,
                "[nucleus_tao_egl] Wayland path unavailable — libwayland-egl.so.1 missing.\n");
        return 0;
    }
    if (!p_wl_proxy_marshal_flags || !g_wl_registry_interface ||
        !g_wl_compositor_interface || !g_wl_subcompositor_interface ||
        !g_wl_subsurface_interface || !g_wl_surface_interface) {
        fprintf(stderr,
                "[nucleus_tao_egl] Wayland path unavailable — libwayland-client.so.0 "
                "or its interface tables couldn't be resolved.\n");
        return 0;
    }

    wl_display *wdpy  = (wl_display *) (uintptr_t) wlDisplayPtr;
    wl_proxy   *wparent = (wl_proxy *)   (uintptr_t) wlSurfacePtr;
    int bscale = bufferScale > 0 ? bufferScale : 1;
    /* Aligned to the scale we are about to announce — see
     * wl_align_to_buffer_scale (an unaligned first commit is fatal). */
    int phys_w = wl_align_to_buffer_scale(widthPx  > 0 ? widthPx  : 1, bscale);
    int phys_h = wl_align_to_buffer_scale(heightPx > 0 ? heightPx : 1, bscale);
    DBG("attachWayland: wl_display=%p parent_wl_surface=%p wxh=%dx%d\n",
        (void*)wdpy, (void*)wparent, phys_w, phys_h);

    /* ── 1) Bind wl_compositor + wl_subcompositor on a private event queue ── */
    /* Private queue keeps registry / compositor / subcompositor / our
     * subsurface events away from GDK's default queue — otherwise our
     * roundtrip below would dispatch GDK events out of band and freeze GTK.
     */
    wl_event_queue *queue = p_wl_display_create_queue(wdpy);
    if (!queue) {
        DBG("wl_display_create_queue failed\n");
        return 0;
    }

    /* `wl_display.get_registry` constructor — we treat the wl_display* as a
     * wl_proxy, which is safe because libwayland's wl_display is allocated
     * through the same wl_proxy machinery (the type punning is documented
     * in the wayland-client header). */
    wl_proxy *registry = p_wl_proxy_marshal_flags(
        (wl_proxy *) wdpy, WL_DISPLAY_GET_REGISTRY,
        g_wl_registry_interface,
        p_wl_proxy_get_version((wl_proxy *) wdpy),
        0, NULL);
    if (!registry) {
        DBG("wl_display.get_registry returned NULL\n");
        p_wl_event_queue_destroy(queue);
        return 0;
    }
    p_wl_proxy_set_queue(registry, queue);

    WlBindState bind_state = { registry, NULL, NULL };
    p_wl_proxy_add_listener(
        registry, (void (**)(void)) wl_registry_listener, &bind_state);

    /* Roundtrip on OUR queue: send `wl_display.sync`, dispatch all events
     * received on `queue` until the sync done event arrives — covers the
     * initial burst of `wl_registry::global` events.
     *
     * Compositor / subcompositor pointers also need their own queue or
     * future events on them (e.g. the bind ack) would land on GDK's queue.
     * `wl_proxy_set_queue` on freshly-created proxies only matters for
     * future events — they're set inside `wl_registry_global` after creation
     * because the proxy is created by wl_proxy_marshal_flags above. We do
     * the set_queue here explicitly to be defensive. */
    if (p_wl_display_roundtrip_queue(wdpy, queue) < 0) {
        DBG("wl_display_roundtrip_queue failed\n");
        if (bind_state.compositor) p_wl_proxy_destroy(bind_state.compositor);
        if (bind_state.subcompositor) p_wl_proxy_destroy(bind_state.subcompositor);
        p_wl_proxy_destroy(registry);
        p_wl_event_queue_destroy(queue);
        return 0;
    }
    if (!bind_state.compositor || !bind_state.subcompositor) {
        fprintf(stderr,
                "[nucleus_tao_egl] Wayland compositor missing %s%s%s — "
                "compositor too old? subsurface support is mandatory in Wayland 1.4+.\n",
                bind_state.compositor ? "" : "wl_compositor",
                (!bind_state.compositor && !bind_state.subcompositor) ? " and " : "",
                bind_state.subcompositor ? "" : "wl_subcompositor");
        if (bind_state.compositor) p_wl_proxy_destroy(bind_state.compositor);
        if (bind_state.subcompositor) p_wl_proxy_destroy(bind_state.subcompositor);
        p_wl_proxy_destroy(registry);
        p_wl_event_queue_destroy(queue);
        return 0;
    }
    p_wl_proxy_set_queue(bind_state.compositor, queue);
    p_wl_proxy_set_queue(bind_state.subcompositor, queue);

    /* ── 2) Create our owned child wl_surface via wl_compositor.create_surface ── */
    wl_proxy *child_surface = p_wl_proxy_marshal_flags(
        bind_state.compositor, WL_COMPOSITOR_CREATE_SURFACE,
        g_wl_surface_interface,
        p_wl_proxy_get_version(bind_state.compositor),
        0, NULL);
    if (!child_surface) {
        DBG("wl_compositor.create_surface returned NULL\n");
        p_wl_proxy_destroy(bind_state.subcompositor);
        p_wl_proxy_destroy(bind_state.compositor);
        p_wl_proxy_destroy(registry);
        p_wl_event_queue_destroy(queue);
        return 0;
    }
    p_wl_proxy_set_queue(child_surface, queue);

    /* ── 3) Make the child a subsurface of GTK's parent ── */
    wl_proxy *subsurface = p_wl_proxy_marshal_flags(
        bind_state.subcompositor, WL_SUBCOMPOSITOR_GET_SUBSURFACE,
        g_wl_subsurface_interface,
        p_wl_proxy_get_version(bind_state.subcompositor),
        0,
        /* new_id placeholder */ NULL,
        /* surface (child)   */ child_surface,
        /* parent (GTK's)    */ wparent);
    if (!subsurface) {
        DBG("wl_subcompositor.get_subsurface returned NULL\n");
        p_wl_proxy_marshal_flags(child_surface, WL_SURFACE_DESTROY, NULL,
            p_wl_proxy_get_version(child_surface), WL_MARSHAL_FLAG_DESTROY);
        p_wl_proxy_destroy(bind_state.subcompositor);
        p_wl_proxy_destroy(bind_state.compositor);
        p_wl_proxy_destroy(registry);
        p_wl_event_queue_destroy(queue);
        return 0;
    }
    p_wl_proxy_set_queue(subsurface, queue);

    /* Position relative to parent (top-left corner aligned), and `set_desync`
     * so our buffer commits don't have to wait for the parent's transaction.
     * Spec: subsurface becomes mapped once parent is mapped AND a non-NULL
     * buffer has been applied — first eglSwapBuffers does the latter, GTK
     * does the former. */
    p_wl_proxy_marshal_flags(
        subsurface, WL_SUBSURFACE_SET_POSITION, NULL,
        p_wl_proxy_get_version(subsurface), 0,
        /*x*/ 0, /*y*/ 0);
    p_wl_proxy_marshal_flags(
        subsurface, WL_SUBSURFACE_SET_DESYNC, NULL,
        p_wl_proxy_get_version(subsurface), 0);

    /* Make the child surface input-transparent so the compositor routes
     * pointer / keyboard / touch events to GTK's parent surface (where tao
     * has its event handlers wired). Without this our EGL-rendered surface
     * sits on top of GTK's parent and silently swallows every click — the
     * Wayland equivalent of XShape `ShapeInput=empty` that the X11 child
     * window uses for the same purpose.
     *
     * `wl_compositor.create_region` with no rects added = empty region.
     * `set_input_region(empty)` makes the surface ignore all input. The
     * region is double-buffered surface state, applied at the next commit
     * on the child (and that commit is gated on the parent's commit while
     * we're still in default sync mode — but `set_input_region` queued
     * before the first eglSwapBuffers is fine because the buffer commit
     * carries the input region with it). */
    if (g_wl_region_interface) {
        wl_proxy *empty_region = p_wl_proxy_marshal_flags(
            bind_state.compositor, WL_COMPOSITOR_CREATE_REGION,
            g_wl_region_interface,
            p_wl_proxy_get_version(bind_state.compositor),
            0, NULL);
        if (empty_region) {
            p_wl_proxy_set_queue(empty_region, queue);
            p_wl_proxy_marshal_flags(
                child_surface, WL_SURFACE_SET_INPUT_REGION, NULL,
                p_wl_proxy_get_version(child_surface), 0,
                empty_region);
            /* The compositor only needs the region for the duration of
             * `set_input_region` — we can destroy our handle right away. */
            p_wl_proxy_marshal_flags(empty_region, WL_REGION_DESTROY, NULL,
                p_wl_proxy_get_version(empty_region), WL_MARSHAL_FLAG_DESTROY);
            /* Explicit commit so the input region becomes active immediately
             * — without it, the empty region only takes effect at the first
             * `eglSwapBuffers` (which carries an implicit commit). DnD events
             * arriving before that first frame would otherwise reach the
             * subsurface and steal pointer focus from the GTK parent, which
             * is exactly the bug we're fixing. The subsurface is in desync
             * mode (set just above), so this commit applies independently of
             * the parent's transaction.
             *
             * `wl_surface.commit` takes no arguments — `flags=0` is correct. */
            p_wl_proxy_marshal_flags(
                child_surface, WL_SURFACE_COMMIT, NULL,
                p_wl_proxy_get_version(child_surface), 0);
        }
    }

    /* ── 4) EGL setup against our child wl_surface ── */
    EGLDisplay edpy = EGL_NO_DISPLAY;
    if (p_eglGetPlatformDisplay) {
        edpy = p_eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, wdpy, NULL);
    }
    if (edpy == EGL_NO_DISPLAY && p_eglGetDisplay) {
        edpy = p_eglGetDisplay((EGLNativeDisplayType) wdpy);
    }
    if (edpy == EGL_NO_DISPLAY) {
        DBG("eglGetPlatformDisplay(WAYLAND) returned EGL_NO_DISPLAY\n");
        goto fail_after_subsurface;
    }
    EGLint maj = 0, min = 0;
    if (!p_eglInitialize(edpy, &maj, &min)) {
        DBG("eglInitialize failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        goto fail_after_subsurface;
    }
    DBG("EGL %d.%d initialized (Wayland)\n", maj, min);
    log_egl_diagnostics_once(edpy, /*is_wayland=*/1);

    if (!p_eglBindAPI(EGL_OPENGL_API)) {
        DBG("eglBindAPI(EGL_OPENGL_API) failed on Wayland EGL\n");
        goto fail_after_subsurface;
    }

    const EGLint cfg_attrs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_DEPTH_SIZE,      0,
        EGL_STENCIL_SIZE,    0,
        EGL_SAMPLES,         0,
        EGL_NONE
    };
    EGLConfig cfg = NULL;
    EGLint ncfg = 0;
    if (!p_eglChooseConfig(edpy, cfg_attrs, &cfg, 1, &ncfg) || ncfg <= 0 || !cfg) {
        DBG("eglChooseConfig (Wayland) returned no configs\n");
        goto fail_after_subsurface;
    }

    const EGLint ctx_attrs[] = {
        EGL_CONTEXT_MAJOR_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 3,
        EGL_CONTEXT_OPENGL_PROFILE_MASK,
            EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT,
        EGL_NONE
    };
    EGLContext ctx = p_eglCreateContext(edpy, cfg, EGL_NO_CONTEXT, ctx_attrs);
    if (ctx == EGL_NO_CONTEXT) {
        DBG("eglCreateContext (Wayland) failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        goto fail_after_subsurface;
    }

    /* Wrap our owned child wl_surface — NOT GTK's — into a wl_egl_window. */
    wl_egl_window *wlwin = p_wl_egl_window_create((wl_surface *) child_surface, phys_w, phys_h);
    if (!wlwin) {
        DBG("wl_egl_window_create returned NULL\n");
        p_eglDestroyContext(edpy, ctx);
        goto fail_after_subsurface;
    }
    EGLSurface surf = p_eglCreateWindowSurface(edpy, cfg,
                                               (EGLNativeWindowType) wlwin, NULL);
    if (surf == EGL_NO_SURFACE) {
        DBG("eglCreateWindowSurface (Wayland) failed: 0x%x\n",
            p_eglGetError ? p_eglGetError() : 0);
        if (p_wl_egl_window_destroy) p_wl_egl_window_destroy(wlwin);
        p_eglDestroyContext(edpy, ctx);
        goto fail_after_subsurface;
    }
    if (!p_eglMakeCurrent(edpy, surf, surf, ctx)) {
        DBG("eglMakeCurrent (Wayland) failed: 0x%x\n",
            p_eglGetError ? p_eglGetError() : 0);
        p_eglDestroySurface(edpy, surf);
        if (p_wl_egl_window_destroy) p_wl_egl_window_destroy(wlwin);
        p_eglDestroyContext(edpy, ctx);
        goto fail_after_subsurface;
    }
    /* eglSwapInterval(1) — relies on the JVM-side architecture binding the
     * EGL context to a *dedicated* swap thread, not the GTK main thread. On
     * Wayland the swap blocks waiting for the compositor's frame callback,
     * which only fires when the GTK main loop pumps `wl_display_dispatch`.
     * If the swap and the dispatch share a thread we deadlock. The Kotlin
     * side (`TaoComposeSceneHostLinux.SwapThread`) makes the context
     * current on a separate thread and calls `nativePresent` there, so the
     * GTK main thread keeps draining wl events while the swap thread waits
     * on the compositor — and we get true hardware vsync without melting
     * the CPU.
     *
     * swapInterval == 0 is used for popup overlays (drag ghosts): their EGL
     * child is a subsurface of GDK's own synchronized wl_subsurface, so FIFO
     * commits stay cached compositor-side and Mesa's pending
     * wp_commit_timer_v1 timestamp is never consumed — the next
     * set_timestamp raises a fatal "Commit already has timestamp" protocol
     * error. Interval 0 keeps Mesa off the fifo/commit-timing path entirely;
     * pacing for those surfaces is event-driven (pointer motion) anyway. */
    if (p_eglSwapInterval) p_eglSwapInterval(edpy, swapInterval ? 1 : 0);

    EglAttachment *att = (EglAttachment *) calloc(1, sizeof(EglAttachment));
    if (!att) {
        p_eglMakeCurrent(edpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(edpy, surf);
        if (p_wl_egl_window_destroy) p_wl_egl_window_destroy(wlwin);
        p_eglDestroyContext(edpy, ctx);
        goto fail_after_subsurface;
    }
    att->display          = edpy;
    att->config           = cfg;
    att->context          = ctx;
    att->surface          = surf;
    att->wl_queue         = queue;
    att->wl_registry      = registry;
    att->wl_compositor    = bind_state.compositor;
    att->wl_subcompositor = bind_state.subcompositor;
    att->wl_display_conn  = wdpy;
    att->wl_parent_surface = wparent;
    att->wl_child_surface = child_surface;
    att->wl_subsurface    = subsurface;
    att->wl_window        = wlwin;
    att->widthPx          = phys_w;
    att->heightPx         = phys_h;
    att->scale            = (float) bscale;
    /* Match GTK's integer surface scale so the `logical × scale` px buffer is
     * read as `logical` surface units (no oversize, input stays calibrated). */
    wl_set_buffer_scale(att, bscale);
    DBG("attached (Wayland subsurface): edpy=%p ctx=%p surf=%p child_surf=%p subsurf=%p scale=%d\n",
        edpy, (void*)ctx, (void*)surf, (void*)child_surface, (void*)subsurface,
        bufferScale);
    return (jlong) (uintptr_t) att;

fail_after_subsurface:
    /* Failure path: tear down the subsurface chain in destruction order. */
    p_wl_proxy_marshal_flags(subsurface, WL_SUBSURFACE_DESTROY, NULL,
        p_wl_proxy_get_version(subsurface), WL_MARSHAL_FLAG_DESTROY);
    p_wl_proxy_marshal_flags(child_surface, WL_SURFACE_DESTROY, NULL,
        p_wl_proxy_get_version(child_surface), WL_MARSHAL_FLAG_DESTROY);
    p_wl_proxy_destroy(bind_state.subcompositor);
    p_wl_proxy_destroy(bind_state.compositor);
    p_wl_proxy_destroy(registry);
    p_wl_event_queue_destroy(queue);
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    if (att->display) {
        p_eglMakeCurrent(att->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (att->surface) p_eglDestroySurface(att->display, att->surface);
        if (att->context) p_eglDestroyContext(att->display, att->context);
    }
    /* Tear down the child window if we created one. Order matters: destroy
     * the X window before its colormap to avoid X server warnings. */
    if (att->xdisplay && att->child_xid && p_XDestroyWindow) {
        p_XDestroyWindow(att->xdisplay, att->child_xid);
    }
    if (att->xdisplay && att->child_colormap && p_XFreeColormap) {
        p_XFreeColormap(att->xdisplay, att->child_colormap);
    }
    /* Wayland path: destroy the proxies in strict reverse-creation order to
     * keep the compositor's bookkeeping happy.
     *   wl_egl_window → wl_subsurface → wl_child_surface → globals →
     *   registry → queue
     * Inverting any pair triggers a `Bad object` protocol error from Mutter. */
    if (att->wl_window && p_wl_egl_window_destroy) {
        p_wl_egl_window_destroy(att->wl_window);
    }
    if (att->wl_subsurface && p_wl_proxy_marshal_flags) {
        p_wl_proxy_marshal_flags(att->wl_subsurface, WL_SUBSURFACE_DESTROY,
            NULL, p_wl_proxy_get_version(att->wl_subsurface),
            WL_MARSHAL_FLAG_DESTROY);
    }
    if (att->wl_child_surface && p_wl_proxy_marshal_flags) {
        p_wl_proxy_marshal_flags(att->wl_child_surface, WL_SURFACE_DESTROY,
            NULL, p_wl_proxy_get_version(att->wl_child_surface),
            WL_MARSHAL_FLAG_DESTROY);
    }
    if (att->wl_compositor && p_wl_proxy_destroy)    p_wl_proxy_destroy(att->wl_compositor);
    if (att->wl_subcompositor && p_wl_proxy_destroy) p_wl_proxy_destroy(att->wl_subcompositor);
    if (att->wl_registry && p_wl_proxy_destroy)      p_wl_proxy_destroy(att->wl_registry);
    if (att->wl_queue && p_wl_event_queue_destroy)   p_wl_event_queue_destroy(att->wl_queue);
    free(att);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    p_eglMakeCurrent(att->display, att->surface, att->surface, att->context);
}

/**
 * Releases the EGL context from the calling thread, leaving no context
 * current. Required when handing the GL context between the GTK main
 * thread (which records draw commands via Skia) and the swap thread
 * (which calls `eglSwapBuffers` and blocks for vsync) — EGL contexts can
 * only be current on one thread at a time.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeReleaseCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    p_eglMakeCurrent(att->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    att->widthPx  = widthPx  > 0 ? widthPx  : 1;
    att->heightPx = heightPx > 0 ? heightPx : 1;
    att->scale    = scale;
    /* When we have a child X window, X11 doesn't auto-resize children with
     * their parent — without this the EGL drawable freezes at its initial
     * size while the GTK frame stretches. XFlush (not XSync) — same NVIDIA
     * Blackwell deadlock concern as the GLX helper noted at length. */
    if (att->xdisplay && att->child_xid && p_XResizeWindow) {
        p_XResizeWindow(att->xdisplay, att->child_xid,
                        (unsigned int) att->widthPx, (unsigned int) att->heightPx);
        if (p_XFlush) p_XFlush(att->xdisplay);
    }
    /* Wayland: `wl_egl_window_resize` informs libwayland-egl that the EGL
     * back buffer should be reallocated at the new size on the next
     * eglSwapBuffers. Without this the buffer stays at its original
     * dimensions and the compositor scales it up, blurring the result. */
    if (att->wl_window && p_wl_egl_window_resize) {
        int bscale = (int) (scale + 0.5f);
        if (bscale < 1) bscale = 1;
        /* Keep the new buffer an integer multiple of the announced scale — see
         * wl_align_to_buffer_scale. */
        att->widthPx  = wl_align_to_buffer_scale(att->widthPx,  bscale);
        att->heightPx = wl_align_to_buffer_scale(att->heightPx, bscale);
        p_wl_egl_window_resize(att->wl_window,
                               att->widthPx, att->heightPx, 0, 0);
        /* Track DPI changes: re-assert the integer buffer scale so the new
         * buffer is still read as `logical` surface units. Queued state, lands
         * with the next eglSwapBuffers commit. */
        wl_set_buffer_scale(att, bscale);
    }
    /* If we render straight into the GTK X window, the EGL surface follows
     * automatically (GTK already issues XResizeWindow on the parent). */
}

/**
 * Positions the content subsurface at ([xLogical], [yLogical]) inside GTK's
 * parent surface. (0,0) for plain undecorated toplevels; the theme's shadow
 * margins when the hidden-titlebar CSD is active, so the EGL content fills
 * exactly the visible window area and GTK's own drop shadow stays visible in
 * the margin ring. Subsurface position is parent-surface state — it takes
 * effect on GTK's next commit, which follows naturally from its ongoing
 * draws. Cheap no-op when the offset is unchanged; no-op on X11 (the CSD is
 * never latched there).
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeSetContentOffset(
    JNIEnv *env, jclass clazz, jlong handle, jint xLogical, jint yLogical)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att || !att->wl_subsurface || !p_wl_proxy_marshal_flags) return JNI_FALSE;
    if (att->content_off_x == xLogical && att->content_off_y == yLogical) return JNI_FALSE;
    att->content_off_x = xLogical;
    att->content_off_y = yLogical;
    p_wl_proxy_marshal_flags(att->wl_subsurface, WL_SUBSURFACE_SET_POSITION,
        NULL, p_wl_proxy_get_version(att->wl_subsurface), 0,
        xLogical, yLogical);
    /* Sub-surface position is PARENT-surface state — it only takes effect on
     * GTK's next commit, and after a maximize/restore GTK has already
     * committed its reallocation by the time this runs and then goes idle,
     * which would leave the old offset applied forever (content shifted
     * bottom-right by the former shadow margins). This used to issue an
     * empty commit on GTK's toplevel surface here. That is not safe: GDK
     * attaches its SHM buffer in `end_paint` and commits it in
     * `after_paint`, and a commit of ours between the two hands the
     * compositor a buffer GDK still counts as staged — the release then
     * fails GDK's `buffer_release_callback` check and cairo aborts the
     * process (seen after a minimize/restore storm). The caller asks GTK to
     * repaint the toplevel instead, and GTK's own commit applies the
     * position. Returns whether the offset changed, so the caller knows to. */
    if (p_wl_display_flush && att->wl_display_conn) p_wl_display_flush(att->wl_display_conn);
    return JNI_TRUE;
}

/**
 * Switches the content sub-surface between `set_sync` and `set_desync`.
 *
 * Normally desync: Compose's buffers land on their own, independently of
 * GTK's cairo paint cycle (see the file header). Through an interactive
 * resize that independence is the problem: an embedded native view
 * (`NativeView`, e.g. WebKit's accelerated sub-surface) is positioned by GTK
 * on its allocation, and a sub-surface position is parent state that only
 * takes effect on GTK's toplevel commit — one GTK paint after Compose laid
 * the new hole out and swapped. The embed peels off the hole by a frame on
 * every configure. In sync mode our buffer is cached by the compositor and
 * applied atomically with that same GTK commit, hole and embed together.
 * Per the protocol, `set_desync` applies any cached state at once, so
 * leaving sync mode never strands a frame.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeSetSubsurfaceSync(
    JNIEnv *env, jclass clazz, jlong handle, jboolean sync)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att || !att->wl_subsurface || !p_wl_proxy_marshal_flags) return;
    p_wl_proxy_marshal_flags(att->wl_subsurface,
        sync ? WL_SUBSURFACE_SET_SYNC : WL_SUBSURFACE_SET_DESYNC,
        NULL, p_wl_proxy_get_version(att->wl_subsurface), 0);
    if (p_wl_display_flush && att->wl_display_conn) p_wl_display_flush(att->wl_display_conn);
}

/**
 * Declares which part of the content surface is fully opaque.
 *
 * Nothing ever set this, even though the whole design depends on it: without an
 * opaque region the compositor must assume our full-window surface is
 * translucent, so it cannot cull *anything* underneath — not the drop-shadow
 * subsurface's interior, not GTK's toplevel. It alpha-blends all three, every
 * frame, over the whole window.
 *
 * That lands on the compositor's frame timing, which is what GDK's frame clock
 * waits on, which is what sets how fast the window edge can move during a
 * resize. Measured: the toplevel's frame callback comes back ~5 ms sooner with
 * the shadow disabled entirely. Declaring the opaque region lets the compositor
 * discard the same work without removing the shadow.
 *
 * [cornerRadius] carves the four corners out of the region, because
 * `applyFrameDecoration` paints them transparent so the shadow shows through —
 * claiming them opaque would leave square corners with the shadow clipped away.
 *
 * Pass `logicalW <= 0` to clear the region (window genuinely translucent).
 * Coordinates are surface-local (logical) units. Queued state: it lands with the
 * next `eglSwapBuffers` commit, so there is no extra commit and no race with the
 * swap thread.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeSetOpaqueRegion(
    JNIEnv *env, jclass clazz, jlong handle,
    jint logicalW, jint logicalH, jint cornerRadius)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att || !att->wl_child_surface || !p_wl_proxy_marshal_flags) return;
    if (!att->wl_compositor || !g_wl_region_interface) return;

    if (logicalW <= 0 || logicalH <= 0) {
        p_wl_proxy_marshal_flags(
            att->wl_child_surface, WL_SURFACE_SET_OPAQUE_REGION, NULL,
            p_wl_proxy_get_version(att->wl_child_surface), 0, NULL);
        return;
    }

    wl_proxy *region = p_wl_proxy_marshal_flags(
        (wl_proxy *) att->wl_compositor, WL_COMPOSITOR_CREATE_REGION,
        g_wl_region_interface,
        p_wl_proxy_get_version((wl_proxy *) att->wl_compositor), 0, NULL);
    if (!region) return;
    if (att->wl_queue && p_wl_proxy_set_queue) p_wl_proxy_set_queue(region, att->wl_queue);

    int r = cornerRadius;
    if (r < 0) r = 0;
    if (2 * r >= logicalW || 2 * r >= logicalH) r = 0;
    if (r == 0) {
        p_wl_proxy_marshal_flags(region, WL_REGION_ADD, NULL,
            p_wl_proxy_get_version(region), 0, 0, 0, logicalW, logicalH);
    } else {
        /* Everything except the four r x r corner squares. */
        p_wl_proxy_marshal_flags(region, WL_REGION_ADD, NULL,
            p_wl_proxy_get_version(region), 0, 0, r, logicalW, logicalH - 2 * r);
        p_wl_proxy_marshal_flags(region, WL_REGION_ADD, NULL,
            p_wl_proxy_get_version(region), 0, r, 0, logicalW - 2 * r, r);
        p_wl_proxy_marshal_flags(region, WL_REGION_ADD, NULL,
            p_wl_proxy_get_version(region), 0, r, logicalH - r, logicalW - 2 * r, r);
    }
    p_wl_proxy_marshal_flags(
        att->wl_child_surface, WL_SURFACE_SET_OPAQUE_REGION, NULL,
        p_wl_proxy_get_version(att->wl_child_surface), 0, region);
    p_wl_proxy_marshal_flags(region, WL_REGION_DESTROY, NULL,
        p_wl_proxy_get_version(region), WL_MARSHAL_FLAG_DESTROY);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    p_eglSwapBuffers(att->display, att->surface);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeSetSwapInterval(
    JNIEnv *env, jclass clazz, jlong handle, jint interval)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att || !p_eglSwapInterval) return;
    /* eglSwapInterval requires the surface to be current. The swap thread
     * always releases before signalling idle, so the caller must ensure it
     * holds the context (nativeMakeCurrent) before calling this. */
    p_eglSwapInterval(att->display, (EGLint) interval);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    return att ? (jint) att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    return att ? (jint) att->heightPx : 0;
}

/* ── Input region (overlay slot of `NativeView`) ─────────────────────────
 *
 * Switches the EGL surface from the default "fully input-transparent"
 * mode (where every click falls through to GTK and reaches the embedded
 * native widget) to a region-restricted mode: the compositor / X server
 * only routes input to our surface when the cursor sits inside one of
 * the supplied rects. Outside those rects, input still falls through to
 * GTK as before. This is the Linux equivalent of macOS's
 * `NucleusTaoNativeOverlayView.hitTest:` returning `nil` for points
 * outside any registered region.
 *
 * [rectsPx] is a flat (x, y, w, h) × [count] float array in surface-
 * local pixels with a top-left origin (matches Compose `boundsInWindow`
 * → physical px). `count == 0` resets to the default empty region
 * (full passthrough).
 *
 * Wayland: marshals `wl_compositor.create_region` + `wl_region.add` for
 * each rect + `wl_surface.set_input_region` + `wl_surface.commit`.
 *
 * X11 child-window fallback: applies `XShapeCombineRectangles(ShapeInput,
 * rects, ShapeSet)` on `child_xid`. Only effective when a child window
 * was created (visual-mismatch fallback path) — the default-visual X11
 * path renders directly into GTK's xwin and has nothing we can shape
 * without breaking GTK input. We log a warning in that case; full
 * X11-default support would require always materialising a child window
 * for Compose, which is left as a follow-up. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeSetInputRegion(
    JNIEnv *env, jclass clazz, jlong handle, jfloatArray rectsPx, jint count)
{
    (void) clazz;
    DBG("nativeSetInputRegion handle=0x%lx count=%d\n",
        (unsigned long) handle, (int) count);
    if (handle == 0) return;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    DBG("  wl_child_surface=%p wl_compositor=%p child_xid=0x%lx\n",
        (void *) att->wl_child_surface, (void *) att->wl_compositor,
        (unsigned long) att->child_xid);

    if (count < 0) count = 0;
    int safe_count = 0;
    if (count > 0 && rectsPx != NULL) {
        jsize len = (*env)->GetArrayLength(env, rectsPx);
        safe_count = (int) (len / 4);
        if (safe_count > count) safe_count = count;
    }
    if (safe_count > 0 && rectsPx != NULL) {
        jfloat *raw = (*env)->GetFloatArrayElements(env, rectsPx, NULL);
        if (raw != NULL) {
            for (int i = 0; i < safe_count; i++) {
                DBG("  rect[%d] = (%g, %g, %g, %g)\n",
                    i, raw[i*4+0], raw[i*4+1], raw[i*4+2], raw[i*4+3]);
            }
            (*env)->ReleaseFloatArrayElements(env, rectsPx, raw, JNI_ABORT);
        }
    }

    /* Wayland path. ───────────────────────────────────────────────── */
    if (att->wl_child_surface != NULL && att->wl_compositor != NULL &&
        g_wl_region_interface != NULL && p_wl_proxy_marshal_flags != NULL) {

        wl_proxy *region = p_wl_proxy_marshal_flags(
            (wl_proxy *) att->wl_compositor, WL_COMPOSITOR_CREATE_REGION,
            g_wl_region_interface,
            p_wl_proxy_get_version((wl_proxy *) att->wl_compositor),
            0, NULL);
        if (region != NULL) {
            if (att->wl_queue != NULL && p_wl_proxy_set_queue) {
                p_wl_proxy_set_queue(region, att->wl_queue);
            }
            /* Pull the float quartets into integer surface coords —
             * `wl_region.add` takes ints. We round-down position and
             * round-up size so the region never undershoots the rect
             * Compose drew (avoids 1-pixel unclickable seams on
             * fractional layouts). */
            if (safe_count > 0 && rectsPx != NULL) {
                jfloat *raw = (*env)->GetFloatArrayElements(env, rectsPx, NULL);
                if (raw != NULL) {
                    for (int i = 0; i < safe_count; i++) {
                        int x = (int) raw[i * 4 + 0];
                        int y = (int) raw[i * 4 + 1];
                        int w = (int) (raw[i * 4 + 2] + 0.5f);
                        int h = (int) (raw[i * 4 + 3] + 0.5f);
                        if (w <= 0 || h <= 0) continue;
                        p_wl_proxy_marshal_flags(
                            region, WL_REGION_ADD, NULL,
                            p_wl_proxy_get_version(region), 0,
                            x, y, w, h);
                    }
                    (*env)->ReleaseFloatArrayElements(env, rectsPx, raw, JNI_ABORT);
                }
            }
            p_wl_proxy_marshal_flags(
                (wl_proxy *) att->wl_child_surface, WL_SURFACE_SET_INPUT_REGION,
                NULL, p_wl_proxy_get_version((wl_proxy *) att->wl_child_surface), 0,
                region);
            p_wl_proxy_marshal_flags(
                region, WL_REGION_DESTROY, NULL,
                p_wl_proxy_get_version(region), WL_MARSHAL_FLAG_DESTROY);
            /* Commit so the new input region takes effect on the next
             * frame; subsurface is in desync mode so this lands without
             * waiting for the GTK parent. */
            p_wl_proxy_marshal_flags(
                (wl_proxy *) att->wl_child_surface, WL_SURFACE_COMMIT, NULL,
                p_wl_proxy_get_version((wl_proxy *) att->wl_child_surface), 0);
        }
        return;
    }

    /* X11 child-window fallback. ─────────────────────────────────── */
    if (att->xdisplay != NULL && att->child_xid != (Window) 0 &&
        p_XShapeCombineRectangles != NULL) {
        XRectangle stack_rects[32];
        XRectangle *rects = stack_rects;
        if (safe_count > (int) (sizeof(stack_rects) / sizeof(stack_rects[0]))) {
            rects = (XRectangle *) calloc((size_t) safe_count, sizeof(XRectangle));
            if (rects == NULL) return;
        }
        if (safe_count > 0 && rectsPx != NULL) {
            jfloat *raw = (*env)->GetFloatArrayElements(env, rectsPx, NULL);
            if (raw != NULL) {
                for (int i = 0; i < safe_count; i++) {
                    rects[i].x      = (short) raw[i * 4 + 0];
                    rects[i].y      = (short) raw[i * 4 + 1];
                    rects[i].width  = (unsigned short) (raw[i * 4 + 2] + 0.5f);
                    rects[i].height = (unsigned short) (raw[i * 4 + 3] + 0.5f);
                }
                (*env)->ReleaseFloatArrayElements(env, rectsPx, raw, JNI_ABORT);
            }
        }
        p_XShapeCombineRectangles(
            att->xdisplay, att->child_xid, ShapeInput, 0, 0,
            rects, safe_count, ShapeSet, Unsorted);
        if (rects != stack_rects) free(rects);
        return;
    }

    /* X11 default visual: no separate Compose window, can't shape
     * without breaking GTK. Falls back to current full-passthrough
     * behaviour — overlay clicks won't be intercepted. */
    DBG("nativeSetInputRegion: no shape-able backend (X11 default visual);"
        " overlay clicks pass through to GTK.\n");
}

/**
 * Returns the address of `nucleus_tao_egl_get_proc` so the JVM can pass it to
 * `GLAssembledInterface.createFromNativePointers(0, fnPtr)`. The function
 * pointer is stable for the lifetime of this shared object — same address
 * across multiple windows (Skia keeps a per-`GrGLInterface` ref to it).
 *
 * Loads the EGL libraries on first call so the proc loader is ready before
 * Skia starts asking for entry points.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoEglBridge_nativeGetProcAddrFunctionPointer(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    if (!load_libs()) return 0;
    return (jlong) (uintptr_t) &nucleus_tao_egl_get_proc;
}
