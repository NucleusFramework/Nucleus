/**
 * JNI bridge: EGL renderer for the Tao backend on Linux.
 *
 * This is the new path that will eventually replace `nucleus_tao_glx.c`. It
 * drives Skia's GL backend through `GLAssembledInterface.createFromNativePointers`
 * + `DirectContext.makeGLWithInterface(...)`, which lets us hand Skia an
 * `eglGetProcAddress`-resolved set of GL entry points instead of the
 * GLX-flavoured `GrGLMakeNativeInterface()` that Skiko's own `MakeGL()` returns.
 *
 * Phase 1 (this file): X11 window surface only. Wayland (`wl_egl_window`) +
 * `wp_fractional_scale_v1` + `wp_viewporter` will land in follow-up commits.
 * Selection between this helper and the legacy GLX one is driven by the JVM
 * system property `nucleus.tao.linux.renderer = glx | egl` (see
 * `TaoComposeSceneHostLinux.attach()`).
 *
 * Linked libraries: -ldl. libEGL.so.1, libGL.so.1 / libOpenGL.so.0 and
 * libX11.so.6 are dlopen-ed at runtime so the build doesn't require the
 * dev packages and the .so ships standalone.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

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

/* ── Wayland EGL helpers ────────────────────────────────────────────────── */

/* Opaque types — we only ever pass them to libwayland-egl. */
typedef struct wl_egl_window_  wl_egl_window;
typedef struct wl_display_     wl_display;
typedef struct wl_surface_     wl_surface;

typedef wl_egl_window *(*PFN_wl_egl_window_create)(wl_surface *, int, int);
typedef void           (*PFN_wl_egl_window_destroy)(wl_egl_window *);
typedef void           (*PFN_wl_egl_window_resize)(wl_egl_window *, int, int, int, int);

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
static int g_libs_loaded = 0;

static PFN_wl_egl_window_create  p_wl_egl_window_create  = NULL;
static PFN_wl_egl_window_destroy p_wl_egl_window_destroy = NULL;
static PFN_wl_egl_window_resize  p_wl_egl_window_resize  = NULL;

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

    /* libwayland-egl — needed for the Wayland attach path only. We don't
     * fail load_libs() if it's missing; X11 sessions don't need it and
     * `nativeAttachWayland` will return 0 with a clear log. */
    if (!g_libwlegl) g_libwlegl = dlopen("libwayland-egl.so.1", RTLD_LAZY | RTLD_GLOBAL);
    if (!g_libwlegl) g_libwlegl = dlopen("libwayland-egl.so",   RTLD_LAZY | RTLD_GLOBAL);

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
#undef LOAD

    g_libs_loaded =
        p_eglInitialize && p_eglBindAPI && p_eglChooseConfig &&
        p_eglCreateContext && p_eglCreateWindowSurface && p_eglMakeCurrent &&
        p_eglSwapBuffers && p_eglGetProcAddress &&
        p_XGetWindowAttributes && p_XVisualIDFromVisual;

    return g_libs_loaded;
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
    /* Wayland plumbing. `wl_window` is non-NULL only on the Wayland path —
     * it's the libwayland-egl handle that wraps a `wl_surface` into something
     * EGL can render into. Lifetime is tied to this attachment; destroyed
     * before `eglDestroySurface` to avoid use-after-free on the compositor side. */
    wl_egl_window *wl_window;
    int        widthPx;
    int        heightPx;
    float      scale;
} EglAttachment;

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
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeAttachX11(
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

/**
 * Wayland-native attach: wraps a `wl_surface*` into an EGL window surface
 * via libwayland-egl's `wl_egl_window` and creates the GL context against
 * `eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, …)`.
 *
 * No visual matching here — Wayland surfaces have no X visual concept; the
 * EGLConfig only needs to be alpha-capable (so the compositor can blend our
 * rounded corners) and OPENGL-renderable. Mesa's Wayland EGL is the de-facto
 * standard; NVIDIA driver 560+ supports the same path through `egl-wayland2`.
 */
JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeAttachWayland(
    JNIEnv *env, jclass clazz,
    jlong wlDisplayPtr, jlong wlSurfacePtr,
    jint widthPx, jint heightPx)
{
    (void) env; (void) clazz;
    if (!wlDisplayPtr || !wlSurfacePtr) return 0;
    if (!load_libs()) return 0;
    if (!p_wl_egl_window_create) {
        DBG("libwayland-egl not loaded — Wayland path unavailable\n");
        return 0;
    }

    wl_display *wdpy = (wl_display *) (uintptr_t) wlDisplayPtr;
    wl_surface *wsurf = (wl_surface *) (uintptr_t) wlSurfacePtr;
    int phys_w = widthPx > 0 ? widthPx : 1;
    int phys_h = heightPx > 0 ? heightPx : 1;
    DBG("attachWayland: wl_display=%p wl_surface=%p wxh=%dx%d\n",
        (void*)wdpy, (void*)wsurf, phys_w, phys_h);

    /* 1) EGL display via the Wayland platform extension. EGL 1.5 core, also
     *    available through `EGL_EXT_platform_wayland`. Always prefer the
     *    explicit-platform call over `eglGetDisplay(wl_display*)` — the
     *    legacy variant is ambiguous when both X11 and Wayland clients live
     *    in the same process. */
    EGLDisplay edpy = EGL_NO_DISPLAY;
    if (p_eglGetPlatformDisplay) {
        edpy = p_eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, wdpy, NULL);
    }
    if (edpy == EGL_NO_DISPLAY && p_eglGetDisplay) {
        edpy = p_eglGetDisplay((EGLNativeDisplayType) wdpy);
    }
    if (edpy == EGL_NO_DISPLAY) {
        DBG("eglGetPlatformDisplay(WAYLAND) returned EGL_NO_DISPLAY\n");
        return 0;
    }
    EGLint maj = 0, min = 0;
    if (!p_eglInitialize(edpy, &maj, &min)) {
        DBG("eglInitialize failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        return 0;
    }
    DBG("EGL %d.%d initialized (Wayland)\n", maj, min);

    if (!p_eglBindAPI(EGL_OPENGL_API)) {
        DBG("eglBindAPI(EGL_OPENGL_API) failed on Wayland EGL\n");
        return 0;
    }

    /* 2) Pick an ARGB config. No native-visual matching needed on Wayland. */
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
        return 0;
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
        return 0;
    }

    /* 3) Wrap the wl_surface into a wl_egl_window. The compositor sees the
     *    wl_egl_window as the buffer source for the surface; resize is via
     *    `wl_egl_window_resize` (see nativeResize). */
    wl_egl_window *wlwin = p_wl_egl_window_create(wsurf, phys_w, phys_h);
    if (!wlwin) {
        DBG("wl_egl_window_create returned NULL\n");
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }

    EGLSurface surf = p_eglCreateWindowSurface(edpy, cfg,
                                               (EGLNativeWindowType) wlwin, NULL);
    if (surf == EGL_NO_SURFACE) {
        DBG("eglCreateWindowSurface (Wayland) failed: 0x%x\n",
            p_eglGetError ? p_eglGetError() : 0);
        if (p_wl_egl_window_destroy) p_wl_egl_window_destroy(wlwin);
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }

    if (!p_eglMakeCurrent(edpy, surf, surf, ctx)) {
        DBG("eglMakeCurrent (Wayland) failed: 0x%x\n",
            p_eglGetError ? p_eglGetError() : 0);
        p_eglDestroySurface(edpy, surf);
        if (p_wl_egl_window_destroy) p_wl_egl_window_destroy(wlwin);
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }

    if (p_eglSwapInterval) p_eglSwapInterval(edpy, 1);

    EglAttachment *att = (EglAttachment *) calloc(1, sizeof(EglAttachment));
    if (!att) {
        p_eglMakeCurrent(edpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(edpy, surf);
        if (p_wl_egl_window_destroy) p_wl_egl_window_destroy(wlwin);
        p_eglDestroyContext(edpy, ctx);
        return 0;
    }
    att->display   = edpy;
    att->config    = cfg;
    att->context   = ctx;
    att->surface   = surf;
    att->wl_window = wlwin;
    att->widthPx   = phys_w;
    att->heightPx  = phys_h;
    att->scale     = 1.0f;
    DBG("attached (Wayland): edpy=%p ctx=%p surf=%p wlwin=%p\n",
        edpy, (void*)ctx, (void*)surf, (void*)wlwin);
    return (jlong) (uintptr_t) att;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeDetach(
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
    /* Wayland path: destroy the wl_egl_window AFTER eglDestroySurface so
     * the EGL surface's buffer release runs before the compositor loses
     * the wl_surface ↔ buffer link. Tao destroys the wl_surface itself
     * later when the GTK window is unrealized — nothing for us to do
     * about that. */
    if (att->wl_window && p_wl_egl_window_destroy) {
        p_wl_egl_window_destroy(att->wl_window);
    }
    free(att);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    p_eglMakeCurrent(att->display, att->surface, att->surface, att->context);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeResize(
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
        p_wl_egl_window_resize(att->wl_window,
                               att->widthPx, att->heightPx, 0, 0);
    }
    /* If we render straight into the GTK X window, the EGL surface follows
     * automatically (GTK already issues XResizeWindow on the parent). */
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    if (!att) return;
    p_eglSwapBuffers(att->display, att->surface);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    return att ? (jint) att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    EglAttachment *att = (EglAttachment *) (uintptr_t) handle;
    return att ? (jint) att->heightPx : 0;
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
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoEglBridge_nativeGetProcAddrFunctionPointer(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    if (!load_libs()) return 0;
    return (jlong) (uintptr_t) &nucleus_tao_egl_get_proc;
}
