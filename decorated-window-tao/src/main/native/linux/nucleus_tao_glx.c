/**
 * JNI bridge: GLX renderer for the Tao backend on Linux.
 *
 * Linux counterpart of `nucleus_tao_metal.m` (macOS) and `nucleus_tao_gl.c`
 * (Windows / WGL). Wraps the Tao-owned X11 window in a GLX context so Skia's
 * GL backend (`DirectContext.makeGL()`) can render directly into it.
 *
 * Why GLX rather than EGL: Skiko's pre-built `libskiko-linux-x64.so` only
 * links against `glXGetCurrentContext` — its `GrGLMakeNativeInterface()` is
 * the GLX flavour. An EGL context (which would also work on Wayland natively)
 * is invisible to Skia, so `DirectContext.makeGL()` returns null. On Wayland
 * sessions the JVM forces `GDK_BACKEND=x11` before tao starts so the window
 * lands on XWayland — XWayland implements GLX over Wayland, which is enough
 * for Skia.
 *
 * Visual matching: GTK 3 windows on a compositing desktop use a 32-bit ARGB
 * visual that is GLX-compatible in practice; we just hand whatever XVisualInfo
 * the GdkWindow has to `glXCreateContext`. If the visual lacks GL support the
 * call fails and we fall back to creating a child X11 window with our own
 * GLX-chosen visual on top of the GTK XID, with `XShape` masking input so
 * pointer/keyboard events still reach GTK and tao.
 *
 * Linked libraries: -ldl. libGL.so / libX11.so / libXext.so are dlopen-ed at
 * runtime so the build doesn't require the dev packages.
 */

#include <jni.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

/* Toggle for verbose stderr diagnostics during bring-up. */
#define NUCLEUS_TAO_GLX_DEBUG 0
#if NUCLEUS_TAO_GLX_DEBUG
#define DBG(...) fprintf(stderr, "[nucleus_tao_glx] " __VA_ARGS__)
#else
#define DBG(...) ((void)0)
#endif

/* ── Xlib / GLX types & constants (subset, re-declared) ─────────────────── */

typedef unsigned long XID;
typedef XID Window;
typedef XID Pixmap;
typedef XID Colormap;
typedef XID Drawable;
typedef struct _XDisplay Display;
typedef void *Visual;
typedef void *GLXContext;
typedef void *GLXFBConfig;

typedef struct {
    Visual *visual;
    XID visualid;
    int screen;
    int depth;
    int class_;
    unsigned long red_mask;
    unsigned long green_mask;
    unsigned long blue_mask;
    int colormap_size;
    int bits_per_rgb;
} XVisualInfo;

typedef struct {
    int x, y;
    int width, height;
    int border_width;
    int depth;
    Visual *visual;
    Window root;
    int class_;
    int bit_gravity;
    int win_gravity;
    int backing_store;
    unsigned long backing_planes;
    unsigned long backing_pixel;
    int save_under;
    Colormap colormap;
    int map_installed;
    int map_state;
    long all_event_masks;
    long your_event_mask;
    long do_not_propagate_mask;
    int override_redirect;
    void *screen;
} XWindowAttributes;

typedef struct {
    Pixmap background_pixmap;
    unsigned long background_pixel;
    Pixmap border_pixmap;
    unsigned long border_pixel;
    int bit_gravity;
    int win_gravity;
    int backing_store;
    unsigned long backing_planes;
    unsigned long backing_pixel;
    int save_under;
    long event_mask;
    long do_not_propagate_mask;
    int override_redirect;
    Colormap colormap;
    XID cursor;
} XSetWindowAttributes;

#define None              0L
#define InputOutput       1
#define CWBorderPixel     (1L<<3)
#define CWEventMask       (1L<<11)
#define CWColormap        (1L<<13)

#define AllocNone         0

/* X11 font-cursor IDs (X11/cursorfont.h subset, kept inline so the build
 * doesn't pull the dev headers). Values are stable since X11R3 and form a
 * superset of what AWT's `Cursor` exposes. */
#define XC_xterm           152
#define XC_hand2           60
#define XC_crosshair       34
#define XC_watch           150
#define XC_fleur           52
#define XC_X_cursor        0
#define XC_question_arrow  92
#define XC_sb_h_double_arrow 108
#define XC_sb_v_double_arrow 116
#define XC_top_left_corner 134
#define XC_top_right_corner 136
#define XC_left_ptr        68

/* GLX attribute tokens (X11/extensions/glx.h) */
#define GLX_USE_GL          1
#define GLX_RGBA            4
#define GLX_DOUBLEBUFFER    5
#define GLX_RED_SIZE        8
#define GLX_GREEN_SIZE      9
#define GLX_BLUE_SIZE       10
#define GLX_ALPHA_SIZE      11
#define GLX_DEPTH_SIZE      12
#define GLX_STENCIL_SIZE    13

/* XShape — used both for input-transparency and for rounded-corner clipping
 * (`ShapeBounding`). Same approach as `java.awt.Window.setShape` on the AWT
 * backend, which feeds an XShape rounded-rect through XSetWindowShape. */
#define ShapeBounding     0
#define ShapeInput        2
#define ShapeSet          0
#define Unsorted          0

typedef struct {
    short x;
    short y;
    unsigned short width;
    unsigned short height;
} XRectangle;

/* ── Function pointer types ───────────────────────────────────────────── */

typedef int (*PFN_XGetWindowAttributes)(Display *, Window, XWindowAttributes *);
typedef Window (*PFN_XCreateWindow)(Display *, Window, int, int, unsigned int, unsigned int,
                                    unsigned int, int, unsigned int, Visual *,
                                    unsigned long, XSetWindowAttributes *);
typedef int (*PFN_XDestroyWindow)(Display *, Window);
typedef int (*PFN_XMapWindow)(Display *, Window);
typedef int (*PFN_XResizeWindow)(Display *, Window, unsigned int, unsigned int);
typedef int (*PFN_XSync)(Display *, int);
typedef int (*PFN_XFlush)(Display *);
typedef Colormap (*PFN_XCreateColormap)(Display *, Window, Visual *, int);
typedef int (*PFN_XFreeColormap)(Display *, Colormap);
typedef int (*PFN_XFree)(void *);
typedef XID (*PFN_XCreateFontCursor)(Display *, unsigned int);
typedef int (*PFN_XFreeCursor)(Display *, XID);
typedef int (*PFN_XDefineCursor)(Display *, Window, XID);
typedef int (*PFN_XUndefineCursor)(Display *, Window);

typedef void (*PFN_XShapeCombineRectangles)(Display *, Window, int, int, int,
                                            void *, int, int, int);

typedef XVisualInfo *(*PFN_glXChooseVisual)(Display *, int, int *);
typedef GLXContext (*PFN_glXCreateContext)(Display *, XVisualInfo *, GLXContext, int);
typedef void (*PFN_glXDestroyContext)(Display *, GLXContext);
typedef int (*PFN_glXMakeCurrent)(Display *, Drawable, GLXContext);
typedef void (*PFN_glXSwapBuffers)(Display *, Drawable);
typedef int (*PFN_glXGetConfig)(Display *, XVisualInfo *, int, int *);
typedef int (*PFN_glXSwapIntervalEXT)(Display *, Drawable, int);

/* ── Loaded function pointers (lazy, per-process) ────────────────────────── */

static void *g_x11_lib = NULL;
static void *g_xext_lib = NULL;
static void *g_gl_lib = NULL;

static PFN_XGetWindowAttributes p_XGetWindowAttributes = NULL;
static PFN_XCreateWindow        p_XCreateWindow = NULL;
static PFN_XDestroyWindow       p_XDestroyWindow = NULL;
static PFN_XMapWindow           p_XMapWindow = NULL;
static PFN_XResizeWindow        p_XResizeWindow = NULL;
static PFN_XSync                p_XSync = NULL;
static PFN_XFlush               p_XFlush = NULL;
static PFN_XCreateColormap      p_XCreateColormap = NULL;
static PFN_XFreeColormap        p_XFreeColormap = NULL;
static PFN_XFree                p_XFree = NULL;
static PFN_XCreateFontCursor    p_XCreateFontCursor = NULL;
static PFN_XFreeCursor          p_XFreeCursor = NULL;
static PFN_XDefineCursor        p_XDefineCursor = NULL;
static PFN_XUndefineCursor      p_XUndefineCursor = NULL;

static PFN_XShapeCombineRectangles p_XShapeCombineRectangles = NULL;

static PFN_glXChooseVisual         p_glXChooseVisual = NULL;
static PFN_glXCreateContext        p_glXCreateContext = NULL;
static PFN_glXDestroyContext       p_glXDestroyContext = NULL;
static PFN_glXMakeCurrent          p_glXMakeCurrent = NULL;
static PFN_glXSwapBuffers          p_glXSwapBuffers = NULL;
static PFN_glXGetConfig            p_glXGetConfig = NULL;
static PFN_glXSwapIntervalEXT      p_glXSwapIntervalEXT = NULL;

static int load_libs(void) {
    if (g_gl_lib && g_x11_lib) return 1;
    /* RTLD_GLOBAL — Skia's GL backend resolves entry points via
     * `dlsym(RTLD_DEFAULT, "gl*")`, so the GL .so must be loaded with
     * RTLD_GLOBAL or its symbols stay invisible and `DirectContext.makeGL()`
     * fails with "Can't create OpenGL DirectContext". */
    if (!g_gl_lib) {
        g_gl_lib = dlopen("libGL.so.1", RTLD_LAZY | RTLD_GLOBAL);
        if (!g_gl_lib) g_gl_lib = dlopen("libGL.so", RTLD_LAZY | RTLD_GLOBAL);
    }
    if (!g_x11_lib) {
        g_x11_lib = dlopen("libX11.so.6", RTLD_LAZY | RTLD_GLOBAL);
        if (!g_x11_lib) g_x11_lib = dlopen("libX11.so", RTLD_LAZY | RTLD_GLOBAL);
    }
    if (!g_xext_lib) {
        g_xext_lib = dlopen("libXext.so.6", RTLD_LAZY | RTLD_GLOBAL);
        if (!g_xext_lib) g_xext_lib = dlopen("libXext.so", RTLD_LAZY | RTLD_GLOBAL);
    }
    if (!g_gl_lib || !g_x11_lib) return 0;

    p_XGetWindowAttributes = (PFN_XGetWindowAttributes)dlsym(g_x11_lib, "XGetWindowAttributes");
    p_XCreateWindow        = (PFN_XCreateWindow)dlsym(g_x11_lib, "XCreateWindow");
    p_XDestroyWindow       = (PFN_XDestroyWindow)dlsym(g_x11_lib, "XDestroyWindow");
    p_XMapWindow           = (PFN_XMapWindow)dlsym(g_x11_lib, "XMapWindow");
    p_XResizeWindow        = (PFN_XResizeWindow)dlsym(g_x11_lib, "XResizeWindow");
    p_XSync                = (PFN_XSync)dlsym(g_x11_lib, "XSync");
    p_XFlush               = (PFN_XFlush)dlsym(g_x11_lib, "XFlush");
    p_XCreateColormap      = (PFN_XCreateColormap)dlsym(g_x11_lib, "XCreateColormap");
    p_XFreeColormap        = (PFN_XFreeColormap)dlsym(g_x11_lib, "XFreeColormap");
    p_XFree                = (PFN_XFree)dlsym(g_x11_lib, "XFree");
    p_XCreateFontCursor    = (PFN_XCreateFontCursor)dlsym(g_x11_lib, "XCreateFontCursor");
    p_XFreeCursor          = (PFN_XFreeCursor)dlsym(g_x11_lib, "XFreeCursor");
    p_XDefineCursor        = (PFN_XDefineCursor)dlsym(g_x11_lib, "XDefineCursor");
    p_XUndefineCursor      = (PFN_XUndefineCursor)dlsym(g_x11_lib, "XUndefineCursor");

    if (g_xext_lib) {
        p_XShapeCombineRectangles = (PFN_XShapeCombineRectangles)
            dlsym(g_xext_lib, "XShapeCombineRectangles");
    }

    p_glXChooseVisual    = (PFN_glXChooseVisual)dlsym(g_gl_lib, "glXChooseVisual");
    p_glXCreateContext   = (PFN_glXCreateContext)dlsym(g_gl_lib, "glXCreateContext");
    p_glXDestroyContext  = (PFN_glXDestroyContext)dlsym(g_gl_lib, "glXDestroyContext");
    p_glXMakeCurrent     = (PFN_glXMakeCurrent)dlsym(g_gl_lib, "glXMakeCurrent");
    p_glXSwapBuffers     = (PFN_glXSwapBuffers)dlsym(g_gl_lib, "glXSwapBuffers");
    p_glXGetConfig       = (PFN_glXGetConfig)dlsym(g_gl_lib, "glXGetConfig");
    p_glXSwapIntervalEXT = (PFN_glXSwapIntervalEXT)dlsym(g_gl_lib, "glXSwapIntervalEXT");

    return p_XGetWindowAttributes && p_glXChooseVisual && p_glXCreateContext &&
           p_glXMakeCurrent && p_glXSwapBuffers;
}

/* ── Attachment state ─────────────────────────────────────────────────── */

typedef struct {
    Display    *display;
    Window      parent_xid;       /* GTK's X11 window */
    Window      child_xid;        /* Our GL-visual child (0 if rendering directly into parent) */
    Colormap    child_colormap;
    GLXContext  glx_context;
    Drawable    glx_drawable;     /* = child_xid if non-zero, else parent_xid */
    int         widthPx;
    int         heightPx;
    float       scale;
    /* X11 cursor associated with the GL surface, recreated on each
     * `setCursorIcon`. Without it, the GTK parent's cursor doesn't propagate
     * to the child window — so Compose's text/hand/etc. icons stay invisible. */
    XID         current_cursor;
    int         current_cursor_code;
} GlxAttachment;

static int parent_visual_is_gl_compatible(Display *dpy, XVisualInfo *parent_info) {
    if (!p_glXGetConfig) return 0;
    int use_gl = 0;
    if (p_glXGetConfig(dpy, parent_info, GLX_USE_GL, &use_gl) != 0) return 0;
    if (!use_gl) return 0;
    int dbl = 0;
    if (p_glXGetConfig(dpy, parent_info, GLX_DOUBLEBUFFER, &dbl) != 0) return 0;
    return dbl ? 1 : 0;
}

/* ============================================================
 *  JNI exports
 *  Package: io.github.kdroidfilter.nucleus.window.tao
 *  Class:   NativeTaoGlxBridge
 * ============================================================ */

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeAttach(
    JNIEnv *env, jclass clazz,
    jlong displayPtr, jlong xidLong,
    jint widthPx, jint heightPx)
{
    (void)env; (void)clazz;
    if (!displayPtr || !xidLong) return 0;
    if (!load_libs()) return 0;

    Display *dpy = (Display *)(uintptr_t)displayPtr;
    Window parent = (Window)(uintptr_t)xidLong;
    DBG("attach: dpy=%p parent_xid=0x%lx wxh=%dx%d\n", (void*)dpy, parent, widthPx, heightPx);

    XWindowAttributes attrs;
    memset(&attrs, 0, sizeof(attrs));
    if (!p_XGetWindowAttributes(dpy, parent, &attrs)) {
        DBG("XGetWindowAttributes failed\n");
        return 0;
    }
    DBG("parent attrs: depth=%d wxh=%dx%d visual=%p root=0x%lx\n",
        attrs.depth, attrs.width, attrs.height, (void*)attrs.visual, attrs.root);

    /* Pick a baseline FBConfig-compatible visual. The single-screen JVM-owned
     * X display we got from tao only has screen 0. */
    int screen = 0;
    int single_buf_attribs[] = {
        GLX_RGBA, GLX_DOUBLEBUFFER,
        GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, GLX_ALPHA_SIZE, 8,
        GLX_DEPTH_SIZE, 0, GLX_STENCIL_SIZE, 8,
        (int)None
    };
    XVisualInfo *vinfo = p_glXChooseVisual(dpy, screen, single_buf_attribs);
    if (!vinfo) {
        DBG("glXChooseVisual returned NULL on screen %d\n", screen);
        return 0;
    }
    DBG("glXChooseVisual ok: depth=%d visualid=0x%lx\n", vinfo->depth, vinfo->visualid);

    /* If GTK's parent window already uses a GLX-compatible visual we render
     * directly into it (cheapest path). Otherwise create a child X11 window
     * with our GLX visual covering the parent and route rendering there. */
    int can_render_into_parent = 0;
    {
        XVisualInfo parent_vinfo;
        memset(&parent_vinfo, 0, sizeof(parent_vinfo));
        parent_vinfo.visual = attrs.visual;
        parent_vinfo.screen = screen;
        parent_vinfo.depth = attrs.depth;
        can_render_into_parent = parent_visual_is_gl_compatible(dpy, &parent_vinfo);
    }

    Window child = (Window)None;
    Colormap child_cmap = (Colormap)None;
    if (!can_render_into_parent) {
        child_cmap = p_XCreateColormap(dpy, attrs.root, vinfo->visual, AllocNone);
        XSetWindowAttributes swa;
        memset(&swa, 0, sizeof(swa));
        swa.colormap = child_cmap;
        swa.event_mask = 0;        /* don't subscribe — events go to parent */
        swa.background_pixel = 0;
        swa.border_pixel = 0;
        unsigned int w = widthPx > 0 ? (unsigned int)widthPx : (unsigned int)attrs.width;
        unsigned int h = heightPx > 0 ? (unsigned int)heightPx : (unsigned int)attrs.height;
        if (w == 0) w = 1;
        if (h == 0) h = 1;
        child = p_XCreateWindow(dpy, parent, 0, 0, w, h, 0,
                                vinfo->depth, InputOutput, vinfo->visual,
                                CWBorderPixel | CWColormap | CWEventMask, &swa);
        if (!child) {
            p_XFreeColormap(dpy, child_cmap);
            p_XFree(vinfo);
            return 0;
        }
        /* Make the child input-transparent so X11 routes pointer / keyboard
         * events back to GTK (and therefore to tao's event loop). Without
         * this our GL surface eats every click that lands on it. */
        if (p_XShapeCombineRectangles) {
            p_XShapeCombineRectangles(dpy, child, ShapeInput, 0, 0,
                                      NULL, 0, ShapeSet, Unsorted);
        }
        p_XMapWindow(dpy, child);
        p_XSync(dpy, 0);
    }

    DBG("can_render_into_parent=%d child_xid=0x%lx\n", can_render_into_parent, child);

    GLXContext ctx = p_glXCreateContext(dpy, vinfo, NULL, 1 /* direct rendering */);
    if (!ctx) {
        DBG("glXCreateContext returned NULL (direct=true)\n");
        if (child) {
            p_XDestroyWindow(dpy, child);
            p_XFreeColormap(dpy, child_cmap);
        }
        p_XFree(vinfo);
        return 0;
    }

    Drawable drawable = child ? (Drawable)child : (Drawable)parent;
    DBG("glXCreateContext ok: ctx=%p drawable=0x%lx\n", (void*)ctx, drawable);
    if (!p_glXMakeCurrent(dpy, drawable, ctx)) {
        DBG("glXMakeCurrent failed\n");
        p_glXDestroyContext(dpy, ctx);
        if (child) {
            p_XDestroyWindow(dpy, child);
            p_XFreeColormap(dpy, child_cmap);
        }
        p_XFree(vinfo);
        return 0;
    }
    /* VSync ON — same rationale as the WGL path. */
    if (p_glXSwapIntervalEXT) p_glXSwapIntervalEXT(dpy, drawable, 1);

    GlxAttachment *att = (GlxAttachment *)calloc(1, sizeof(GlxAttachment));
    if (!att) {
        p_glXMakeCurrent(dpy, (Drawable)None, NULL);
        p_glXDestroyContext(dpy, ctx);
        if (child) {
            p_XDestroyWindow(dpy, child);
            p_XFreeColormap(dpy, child_cmap);
        }
        p_XFree(vinfo);
        return 0;
    }
    att->display = dpy;
    att->parent_xid = parent;
    att->child_xid = child;
    att->child_colormap = child_cmap;
    att->glx_context = ctx;
    att->glx_drawable = drawable;
    att->widthPx = widthPx > 0 ? widthPx : attrs.width;
    att->heightPx = heightPx > 0 ? heightPx : attrs.height;
    att->scale = 1.0f;
    p_XFree(vinfo);
    return (jlong)(uintptr_t)att;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    if (!att) return;
    if (att->display && p_glXMakeCurrent) {
        p_glXMakeCurrent(att->display, (Drawable)None, NULL);
    }
    if (att->display && att->glx_context && p_glXDestroyContext) {
        p_glXDestroyContext(att->display, att->glx_context);
    }
    if (att->display && att->current_cursor && p_XFreeCursor) {
        p_XFreeCursor(att->display, att->current_cursor);
    }
    if (att->display && att->child_xid && p_XDestroyWindow) {
        p_XDestroyWindow(att->display, att->child_xid);
    }
    if (att->display && att->child_colormap && p_XFreeColormap) {
        p_XFreeColormap(att->display, att->child_colormap);
    }
    if (att->display && p_XFlush) p_XFlush(att->display);
    free(att);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    if (!att) return;
    p_glXMakeCurrent(att->display, att->glx_drawable, att->glx_context);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    if (!att) return;
    if (widthPx <= 0) widthPx = 1;
    if (heightPx <= 0) heightPx = 1;
    att->widthPx = widthPx;
    att->heightPx = heightPx;
    att->scale = scale;
    if (att->child_xid && p_XResizeWindow) {
        /* Keep the child overlay sized to the GTK parent. X11 doesn't auto-
         * resize child windows when the parent grows; without this our GL
         * canvas freezes at its initial size while the GTK frame stretches. */
        p_XResizeWindow(att->display, att->child_xid,
                        (unsigned int)widthPx, (unsigned int)heightPx);
        if (p_XSync) p_XSync(att->display, 0);
    }
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    if (!att) return;
    p_glXSwapBuffers(att->display, att->glx_drawable);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    return att ? (jint)att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    return att ? (jint)att->heightPx : 0;
}

/* Maps the JVM-side `TaoCursorIcon` codes (mirrored 1:1 with Compose's
 * `PointerIcon` enum) to the X11 font-cursor IDs the X server understands.
 * Returns 0 (= XC_X_cursor) for unknown codes, but the caller should treat
 * `code == 0` (DEFAULT) as a request to clear the cursor entirely so the
 * child window inherits its parent's. */
static unsigned int x_cursor_for_code(int code) {
    switch (code) {
        case 1:  return XC_xterm;             /* TEXT */
        case 2:  return XC_hand2;             /* HAND */
        case 3:  return XC_crosshair;         /* CROSSHAIR */
        case 4:  return XC_watch;             /* WAIT */
        case 5:  return XC_fleur;             /* MOVE */
        case 6:  return XC_X_cursor;          /* NOT_ALLOWED */
        case 7:  return XC_question_arrow;    /* HELP */
        case 8:  return XC_watch;             /* PROGRESS */
        case 9:  return XC_sb_h_double_arrow; /* EW_RESIZE */
        case 10: return XC_sb_v_double_arrow; /* NS_RESIZE */
        case 11: return XC_top_right_corner;  /* NESW_RESIZE */
        case 12: return XC_top_left_corner;   /* NWSE_RESIZE */
        default: return XC_left_ptr;          /* DEFAULT (unused — see below) */
    }
}

/**
 * Sets a cursor icon on the GL window — required because the GTK parent's
 * cursor (the one tao's `Window::set_cursor_icon` updates) does not propagate
 * to our child X11 window. Compose calls into this whenever a hovered
 * `Modifier.pointerHoverIcon` (or the `PointerIcon.Text` from `BasicTextField`
 * / `BasicTextArea`) becomes the new icon.
 *
 * `code == 0` (DEFAULT) clears the cursor on the child via `XUndefineCursor`
 * so it inherits from the GTK parent — that way the GTK / GDK theme drives
 * the default arrow exactly like the AWT backend does.
 */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeSetCursorIcon(
    JNIEnv *env, jclass clazz, jlong handle, jint code)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    if (!att || !att->display) return;
    Window target = att->child_xid ? att->child_xid : att->parent_xid;
    if (!target) return;

    /* Skip if same code — saves a round-trip; mirrors what tao's own
     * cursor-icon path does on macOS / Windows. */
    if (att->current_cursor_code == code && att->current_cursor != 0) return;

    /* Free the previous cursor first so we don't leak XIDs. */
    if (att->current_cursor && p_XFreeCursor) {
        p_XFreeCursor(att->display, att->current_cursor);
        att->current_cursor = 0;
    }

    // No cache check — tao's GTK Linux backend installs a motion-notify
    // handler that calls `gdk_window_set_cursor("default")` on every pointer
    // move (for resize-edge detection on undecorated windows). That handler
    // races with us: we set I-beam on Enter, tao resets to default on the
    // next motion event, our cursor is gone before the user sees it. The
    // workaround is to re-apply the cursor on every Move event from the
    // Kotlin side, but the cache would skip the redundant call. Always go
    // through XDefineCursor to win the race.
    if (code == 0) {
        if (p_XUndefineCursor) {
            if (att->child_xid) p_XUndefineCursor(att->display, att->child_xid);
            if (att->parent_xid) p_XUndefineCursor(att->display, att->parent_xid);
        }
        if (att->current_cursor && p_XFreeCursor) {
            p_XFreeCursor(att->display, att->current_cursor);
            att->current_cursor = 0;
        }
        att->current_cursor_code = 0;
        if (p_XFlush) p_XFlush(att->display);
        return;
    }

    if (!p_XCreateFontCursor || !p_XDefineCursor) return;
    // Reuse the cached XID when re-applying the same code on every Move —
    // re-creating cursors thousands of times per second would fragment the
    // X resource pool.
    XID cursor;
    if (att->current_cursor_code == code && att->current_cursor != 0) {
        cursor = att->current_cursor;
    } else {
        if (att->current_cursor && p_XFreeCursor) {
            p_XFreeCursor(att->display, att->current_cursor);
            att->current_cursor = 0;
        }
        cursor = p_XCreateFontCursor(att->display, x_cursor_for_code(code));
        if (!cursor) return;
        att->current_cursor = cursor;
        att->current_cursor_code = code;
    }
    if (att->parent_xid) p_XDefineCursor(att->display, att->parent_xid, cursor);
    if (att->child_xid && att->child_xid != att->parent_xid) {
        p_XDefineCursor(att->display, att->child_xid, cursor);
    }
    if (p_XFlush) p_XFlush(att->display);
}

/* Builds a per-scanline list of XRectangle for a rounded rectangle of size
 * `(w, h)` with corner radius `r`, then calls `XShapeCombineRectangles` with
 * `ShapeBounding` to clip the window's visible region to that shape. Mirrors
 * `java.awt.Window.setShape(RoundRectangle2D)` on the AWT backend (which goes
 * through the very same XShape extension under the hood).
 *
 * `kind` must be 0 to clear the shape (`ShapeSet` an empty rect list rebuilds
 * a default rectangular shape; we instead call XShapeCombineMask(None) to
 * fully reset). Pass any non-zero `cornerRadius` to round; pass 0 to remove. */
static void apply_rounded_shape(Display *dpy, Window win, int w, int h, int r) {
    if (!p_XShapeCombineRectangles || !dpy || !win) return;
    if (w <= 0 || h <= 0) return;
    if (r > w / 2) r = w / 2;
    if (r > h / 2) r = h / 2;

    /* One rect per corner row + one big middle rect. */
    size_t cap = (size_t)(2 * r) + 1;
    XRectangle *rects = (XRectangle *)calloc(cap, sizeof(XRectangle));
    if (!rects) return;
    size_t n = 0;

    /* Top corner: y in [0, r). x_offset = r - sqrt(r^2 - (r-1-y)^2). */
    for (int y = 0; y < r; ++y) {
        double dy = (double)(r - 1 - y);
        double dx = sqrt((double)r * r - dy * dy);
        int off = (int)(r - dx + 0.5);
        if (off < 0) off = 0;
        if (off > w / 2) off = w / 2;
        rects[n].x = (short)off;
        rects[n].y = (short)y;
        rects[n].width = (unsigned short)(w - 2 * off);
        rects[n].height = 1;
        ++n;
    }

    /* Middle: one rectangle covering [r, h - r). */
    if (h - 2 * r > 0) {
        rects[n].x = 0;
        rects[n].y = (short)r;
        rects[n].width = (unsigned short)w;
        rects[n].height = (unsigned short)(h - 2 * r);
        ++n;
    }

    /* Bottom corner: mirror of top. */
    for (int y = h - r; y < h; ++y) {
        int local = y - (h - r); /* 0..r-1 */
        double dy = (double)local;
        double dx = sqrt((double)r * r - dy * dy);
        int off = (int)(r - dx + 0.5);
        if (off < 0) off = 0;
        if (off > w / 2) off = w / 2;
        rects[n].x = (short)off;
        rects[n].y = (short)y;
        rects[n].width = (unsigned short)(w - 2 * off);
        rects[n].height = 1;
        ++n;
    }

    p_XShapeCombineRectangles(dpy, win, ShapeBounding, 0, 0,
                              (void *)rects, (int)n, ShapeSet, Unsorted);
    free(rects);
}

/**
 * Sets a rounded-rectangle bounding shape on the GL surface (and on the
 * GTK parent if we're rendering directly into it). `cornerRadiusPx == 0`
 * resets the shape to the full rectangle — used when the window enters the
 * maximized/fullscreen state, mirroring the AWT path's `window.shape = null`.
 */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlxBridge_nativeSetRoundedShape(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jint cornerRadiusPx)
{
    (void)env; (void)clazz;
    GlxAttachment *att = (GlxAttachment *)(uintptr_t)handle;
    if (!att) return;

    /* Shape the window the user actually sees: child if we're using the
     * fallback overlay, parent otherwise. The other window is either
     * invisible (parent behind GL) or rectangular by construction. */
    Window target = att->child_xid ? att->child_xid : att->parent_xid;
    if (cornerRadiusPx <= 0) {
        /* Reset: a single full-size rectangle restores the default shape. */
        XRectangle full;
        full.x = 0;
        full.y = 0;
        full.width = (unsigned short)(widthPx > 0 ? widthPx : att->widthPx);
        full.height = (unsigned short)(heightPx > 0 ? heightPx : att->heightPx);
        if (p_XShapeCombineRectangles) {
            p_XShapeCombineRectangles(att->display, target, ShapeBounding, 0, 0,
                                      (void *)&full, 1, ShapeSet, Unsorted);
        }
    } else {
        apply_rounded_shape(att->display, target,
                            widthPx > 0 ? widthPx : att->widthPx,
                            heightPx > 0 ? heightPx : att->heightPx,
                            cornerRadiusPx);
    }
    /* Also shape the GTK parent — the child alone isn't enough, because the
     * rectangular parent shows through at the corner pixels (different
     * background fill / GTK theme). Mirroring the rounded shape on the
     * parent makes the visible region truly rounded. */
    if (att->child_xid && att->parent_xid && p_XShapeCombineRectangles) {
        if (cornerRadiusPx <= 0) {
            XRectangle full;
            full.x = 0;
            full.y = 0;
            full.width = (unsigned short)(widthPx > 0 ? widthPx : att->widthPx);
            full.height = (unsigned short)(heightPx > 0 ? heightPx : att->heightPx);
            p_XShapeCombineRectangles(att->display, att->parent_xid, ShapeBounding, 0, 0,
                                      (void *)&full, 1, ShapeSet, Unsorted);
        } else {
            apply_rounded_shape(att->display, att->parent_xid,
                                widthPx > 0 ? widthPx : att->widthPx,
                                heightPx > 0 ? heightPx : att->heightPx,
                                cornerRadiusPx);
        }
    }
    if (p_XFlush) p_XFlush(att->display);
}
