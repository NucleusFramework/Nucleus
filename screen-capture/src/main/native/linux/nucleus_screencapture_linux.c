/**
 * JNI bridge for Linux screen capture.
 *
 *   - X11: XGetImage on the root window (or a window / its XComposite pixmap),
 *     RandR monitors for the display list, XFixes for the cursor.
 *   - Wayland: org.freedesktop.portal.Screenshot over D-Bus, decoded with
 *     gdk-pixbuf.
 *
 * Every library is dlopen'd on first use - nothing is linked at build time, so
 * the .so loads on a machine without X11, D-Bus or gdk-pixbuf and the
 * missing backend simply reports itself as unavailable.
 *
 * X11 calls are serialized by one mutex: the error handlers are process-wide,
 * and a capture installs its own for the duration of the call (chaining every
 * error that is not on its connection to the previous handler, so a toolkit
 * that shares the process keeps its own handling). Each call opens and closes
 * its own connection, so display changes are always seen and nothing is held
 * between captures.
 *
 * Portal screenshots are written by the portal to a file (usually in the
 * user's Pictures folder). The caller asked for pixels, not a saved picture, so
 * the file is deleted once decoded - only when it is a regular file (never a
 * symlink or anything else).
 */

#include <jni.h>
#include "../../../../../native-common/nucleus_jni.h"

#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/Xrandr.h>
#include <X11/extensions/Xfixes.h>
#include <X11/extensions/Xcomposite.h>
#include <dbus/dbus.h>

#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

/* Mirrors NativeScreenCapture.STATUS_* / PERMISSION_* / BACKEND_*. */
enum {
    ST_OK = 0,
    ST_UNSUPPORTED = 1,
    ST_PERMISSION_DENIED = 2,
    ST_DISPLAY_NOT_FOUND = 3,
    ST_WINDOW_NOT_FOUND = 4,
    ST_CANCELLED = 5,
    ST_FAILED = 6,
    ST_TIMEOUT = 7,
    ST_INVALID_REGION = 8,
};
#define PERMISSION_NOT_REQUIRED 3
#define BACKEND_NONE 0
#define BACKEND_X11 4

/* Java arrays are indexed by int. */
#define MAX_PIXELS ((int64_t)0x7FFFFFF0)

/* ------------------------------------------------------------------------ */
/* JNI helpers                                                              */
/* ------------------------------------------------------------------------ */

static void set_message(JNIEnv *env, jobjectArray message, const char *fmt, ...) {
    if (message == NULL || (*env)->GetArrayLength(env, message) < 1) return;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    jstring s = (*env)->NewStringUTF(env, buf);
    if (s == NULL) {
        nucleus_jni_clear_exception(env);
        return;
    }
    (*env)->SetObjectArrayElement(env, message, 0, s);
    (*env)->DeleteLocalRef(env, s);
}

static void set_result(JNIEnv *env, jintArray result, jint status, jint w, jint h) {
    if (result == NULL || (*env)->GetArrayLength(env, result) < 3) return;
    jint v[3] = {status, w, h};
    (*env)->SetIntArrayRegion(env, result, 0, 3, v);
}

/* ------------------------------------------------------------------------ */
/* Dynamic loading                                                          */
/* ------------------------------------------------------------------------ */

typedef int (*XIOErrorExitHandler_t)(Display *, void *);

static struct {
    int loaded;
    Display *(*OpenDisplay)(const char *);
    int (*CloseDisplay)(Display *);
    XErrorHandler (*SetErrorHandler)(XErrorHandler);
    XIOErrorHandler (*SetIOErrorHandler)(XIOErrorHandler);
    void (*SetIOErrorExitHandler)(Display *, XIOErrorExitHandler_t, void *);
    int (*Sync)(Display *, Bool);
    XImage *(*GetImage)(Display *, Drawable, int, int, unsigned int, unsigned int, unsigned long, int);
    Status (*GetWindowAttributes)(Display *, Window, XWindowAttributes *);
    Bool (*TranslateCoordinates)(Display *, Window, Window, int, int, int *, int *, Window *);
    int (*QueryColors)(Display *, Colormap, XColor *, int);
    char *(*GetAtomName)(Display *, Atom);
    int (*Free)(void *);
    int (*FreePixmap)(Display *, Pixmap);
    Bool (*QueryExtension)(Display *, const char *, int *, int *, int *);
} X;

static struct {
    int loaded;
    Bool (*QueryExtension)(Display *, int *, int *);
    Status (*QueryVersion)(Display *, int *, int *);
    XRRMonitorInfo *(*GetMonitors)(Display *, Window, Bool, int *);
    void (*FreeMonitors)(XRRMonitorInfo *);
    XRRScreenResources *(*GetScreenResourcesCurrent)(Display *, Window);
    void (*FreeScreenResources)(XRRScreenResources *);
    XRROutputInfo *(*GetOutputInfo)(Display *, XRRScreenResources *, RROutput);
    void (*FreeOutputInfo)(XRROutputInfo *);
    XRRCrtcInfo *(*GetCrtcInfo)(Display *, XRRScreenResources *, RRCrtc);
    void (*FreeCrtcInfo)(XRRCrtcInfo *);
    RROutput (*GetOutputPrimary)(Display *, Window);
} XRR;

static struct {
    int loaded;
    Bool (*QueryExtension)(Display *, int *, int *);
    XFixesCursorImage *(*GetCursorImage)(Display *);
} XFX;

static struct {
    int loaded;
    Bool (*QueryExtension)(Display *, int *, int *);
    Status (*QueryVersion)(Display *, int *, int *);
    Pixmap (*NameWindowPixmap)(Display *, Window);
} XCOMP;

static pthread_once_t g_x11_once = PTHREAD_ONCE_INIT;

#define LOAD(lib, table, field, name) (table).field = (void *)dlsym(lib, name)

static void load_x11(void) {
    void *lib = dlopen("libX11.so.6", RTLD_LAZY | RTLD_LOCAL);
    if (lib != NULL) {
        LOAD(lib, X, OpenDisplay, "XOpenDisplay");
        LOAD(lib, X, CloseDisplay, "XCloseDisplay");
        LOAD(lib, X, SetErrorHandler, "XSetErrorHandler");
        LOAD(lib, X, SetIOErrorHandler, "XSetIOErrorHandler");
        LOAD(lib, X, SetIOErrorExitHandler, "XSetIOErrorExitHandler");
        LOAD(lib, X, Sync, "XSync");
        LOAD(lib, X, GetImage, "XGetImage");
        LOAD(lib, X, GetWindowAttributes, "XGetWindowAttributes");
        LOAD(lib, X, TranslateCoordinates, "XTranslateCoordinates");
        LOAD(lib, X, QueryColors, "XQueryColors");
        LOAD(lib, X, GetAtomName, "XGetAtomName");
        LOAD(lib, X, Free, "XFree");
        LOAD(lib, X, FreePixmap, "XFreePixmap");
        LOAD(lib, X, QueryExtension, "XQueryExtension");
        X.loaded = X.OpenDisplay && X.CloseDisplay && X.SetErrorHandler && X.Sync && X.GetImage &&
                   X.GetWindowAttributes && X.TranslateCoordinates && X.QueryColors && X.GetAtomName &&
                   X.Free && X.FreePixmap;
    }
    if (!X.loaded) return;

    lib = dlopen("libXrandr.so.2", RTLD_LAZY | RTLD_LOCAL);
    if (lib != NULL) {
        LOAD(lib, XRR, QueryExtension, "XRRQueryExtension");
        LOAD(lib, XRR, QueryVersion, "XRRQueryVersion");
        LOAD(lib, XRR, GetMonitors, "XRRGetMonitors");
        LOAD(lib, XRR, FreeMonitors, "XRRFreeMonitors");
        LOAD(lib, XRR, GetScreenResourcesCurrent, "XRRGetScreenResourcesCurrent");
        LOAD(lib, XRR, FreeScreenResources, "XRRFreeScreenResources");
        LOAD(lib, XRR, GetOutputInfo, "XRRGetOutputInfo");
        LOAD(lib, XRR, FreeOutputInfo, "XRRFreeOutputInfo");
        LOAD(lib, XRR, GetCrtcInfo, "XRRGetCrtcInfo");
        LOAD(lib, XRR, FreeCrtcInfo, "XRRFreeCrtcInfo");
        LOAD(lib, XRR, GetOutputPrimary, "XRRGetOutputPrimary");
        XRR.loaded = XRR.QueryExtension && XRR.QueryVersion && XRR.GetScreenResourcesCurrent &&
                     XRR.FreeScreenResources && XRR.GetOutputInfo && XRR.FreeOutputInfo && XRR.GetCrtcInfo &&
                     XRR.FreeCrtcInfo && XRR.GetOutputPrimary;
    }

    lib = dlopen("libXfixes.so.3", RTLD_LAZY | RTLD_LOCAL);
    if (lib != NULL) {
        LOAD(lib, XFX, QueryExtension, "XFixesQueryExtension");
        LOAD(lib, XFX, GetCursorImage, "XFixesGetCursorImage");
        XFX.loaded = XFX.QueryExtension && XFX.GetCursorImage;
    }

    lib = dlopen("libXcomposite.so.1", RTLD_LAZY | RTLD_LOCAL);
    if (lib != NULL) {
        LOAD(lib, XCOMP, QueryExtension, "XCompositeQueryExtension");
        LOAD(lib, XCOMP, QueryVersion, "XCompositeQueryVersion");
        LOAD(lib, XCOMP, NameWindowPixmap, "XCompositeNameWindowPixmap");
        XCOMP.loaded = XCOMP.QueryExtension && XCOMP.QueryVersion && XCOMP.NameWindowPixmap;
    }
}

/* ------------------------------------------------------------------------ */
/* X11 session: one connection, error handlers scoped to it                 */
/* ------------------------------------------------------------------------ */

static pthread_mutex_t g_x11_lock = PTHREAD_MUTEX_INITIALIZER;
static Display *g_dpy = NULL;          /* connection of the call in progress */
static volatile int g_x_error = 0;     /* last X error code on g_dpy */
static volatile int g_x_io_dead = 0;   /* g_dpy lost its server */
static XErrorHandler g_prev_error = NULL;
static XIOErrorHandler g_prev_io = NULL;

static int on_x_error(Display *dpy, XErrorEvent *event) {
    if (dpy == g_dpy) {
        g_x_error = event->error_code;
        return 0;
    }
    return g_prev_error != NULL ? g_prev_error(dpy, event) : 0;
}

static int on_x_io_error(Display *dpy) {
    if (dpy == g_dpy) {
        g_x_io_dead = 1;
        return 0; /* Xlib then calls the per-display exit handler instead of exit() */
    }
    return g_prev_io != NULL ? g_prev_io(dpy) : 0;
}

static int on_x_io_exit(Display *dpy, void *data) {
    (void)dpy;
    (void)data;
    g_x_io_dead = 1;
    return 0;
}

/*
 * Opens a connection with the lock held. The IO error handler is only
 * replaced when libX11 has XSetIOErrorExitHandler (1.7+): without it, a
 * returning IO handler still ends in exit(), so there is nothing to gain.
 */
static Display *x_session_open(void) {
    pthread_mutex_lock(&g_x11_lock);
    Display *dpy = X.OpenDisplay(NULL);
    if (dpy == NULL) {
        pthread_mutex_unlock(&g_x11_lock);
        return NULL;
    }
    g_dpy = dpy;
    g_x_error = 0;
    g_x_io_dead = 0;
    g_prev_error = X.SetErrorHandler(on_x_error);
    if (g_prev_error == on_x_error) g_prev_error = NULL;
    if (X.SetIOErrorExitHandler != NULL && X.SetIOErrorHandler != NULL) {
        g_prev_io = X.SetIOErrorHandler(on_x_io_error);
        if (g_prev_io == on_x_io_error) g_prev_io = NULL;
        X.SetIOErrorExitHandler(dpy, on_x_io_exit, NULL);
    }
    return dpy;
}

static void x_session_close(Display *dpy) {
    if (!g_x_io_dead) X.Sync(dpy, False);
    /* Put the previous handlers back, unless someone replaced ours meanwhile. */
    XErrorHandler current = X.SetErrorHandler(g_prev_error);
    if (current != on_x_error) X.SetErrorHandler(current);
    if (X.SetIOErrorExitHandler != NULL && X.SetIOErrorHandler != NULL) {
        XIOErrorHandler io = X.SetIOErrorHandler(g_prev_io);
        if (io != on_x_io_error) X.SetIOErrorHandler(io);
    }
    X.CloseDisplay(dpy);
    g_dpy = NULL;
    g_prev_error = NULL;
    g_prev_io = NULL;
    pthread_mutex_unlock(&g_x11_lock);
}

/* Flushes the request queue; returns the X error it raised (0 = none). */
static int x_take_error(Display *dpy) {
    if (!g_x_io_dead) X.Sync(dpy, False);
    int error = g_x_error;
    g_x_error = 0;
    return error;
}

/* ------------------------------------------------------------------------ */
/* Displays                                                                 */
/* ------------------------------------------------------------------------ */

typedef struct {
    char name[128];
    int x, y, w, h;
    int primary;
} Monitor;

typedef struct {
    Monitor *items;
    int count;
    int capacity;
} MonitorList;

static void monitors_add(MonitorList *list, const char *name, int x, int y, int w, int h, int primary) {
    if (w <= 0 || h <= 0) return;
    if (list->count == list->capacity) {
        int capacity = list->capacity == 0 ? 8 : list->capacity * 2;
        Monitor *items = realloc(list->items, (size_t)capacity * sizeof(Monitor));
        if (items == NULL) return;
        list->items = items;
        list->capacity = capacity;
    }
    Monitor *m = &list->items[list->count++];
    snprintf(m->name, sizeof(m->name), "%s", name != NULL && name[0] != '\0' ? name : "screen");
    m->x = x;
    m->y = y;
    m->w = w;
    m->h = h;
    m->primary = primary;
}

static int has_randr(Display *dpy, int major_min, int minor_min) {
    if (!XRR.loaded) return 0;
    int event_base, error_base, major = 0, minor = 0;
    if (!XRR.QueryExtension(dpy, &event_base, &error_base)) return 0;
    if (!XRR.QueryVersion(dpy, &major, &minor)) return 0;
    return major > major_min || (major == major_min && minor >= minor_min);
}

static void list_monitors(Display *dpy, MonitorList *list) {
    Window root = DefaultRootWindow(dpy);

    /* RandR 1.5 monitors: what desktops call a monitor (tiled outputs merged, setmonitor honoured). */
    if (XRR.GetMonitors != NULL && XRR.FreeMonitors != NULL && has_randr(dpy, 1, 5)) {
        int n = 0;
        XRRMonitorInfo *monitors = XRR.GetMonitors(dpy, root, True, &n);
        if (x_take_error(dpy) == 0 && monitors != NULL) {
            for (int i = 0; i < n; i++) {
                char *name = monitors[i].name != None ? X.GetAtomName(dpy, monitors[i].name) : NULL;
                monitors_add(list, name, monitors[i].x, monitors[i].y, monitors[i].width, monitors[i].height,
                             monitors[i].primary);
                if (name != NULL) X.Free(name);
            }
        }
        if (monitors != NULL) XRR.FreeMonitors(monitors);
        if (list->count > 0) goto primary;
    }

    /* RandR 1.3: one entry per connected output with a CRTC. */
    if (has_randr(dpy, 1, 3)) {
        XRRScreenResources *res = XRR.GetScreenResourcesCurrent(dpy, root);
        if (x_take_error(dpy) == 0 && res != NULL) {
            RROutput primary = XRR.GetOutputPrimary(dpy, root);
            for (int i = 0; i < res->noutput; i++) {
                XRROutputInfo *output = XRR.GetOutputInfo(dpy, res, res->outputs[i]);
                if (output == NULL) continue;
                if (output->connection == RR_Connected && output->crtc != None) {
                    XRRCrtcInfo *crtc = XRR.GetCrtcInfo(dpy, res, output->crtc);
                    if (crtc != NULL) {
                        monitors_add(list, output->name, crtc->x, crtc->y, (int)crtc->width, (int)crtc->height,
                                     res->outputs[i] == primary);
                        XRR.FreeCrtcInfo(crtc);
                    }
                }
                XRR.FreeOutputInfo(output);
            }
        }
        if (res != NULL) XRR.FreeScreenResources(res);
        x_take_error(dpy);
        if (list->count > 0) goto primary;
    }

    /* No RandR: the whole root window. */
    monitors_add(list, "screen", 0, 0, DisplayWidth(dpy, DefaultScreen(dpy)), DisplayHeight(dpy, DefaultScreen(dpy)),
                 1);

primary:
    for (int i = 0; i < list->count; i++) {
        if (list->items[i].primary) return;
    }
    if (list->count > 0) list->items[0].primary = 1;
}

/* ------------------------------------------------------------------------ */
/* Pixel conversion                                                         */
/* ------------------------------------------------------------------------ */

typedef struct {
    int shift;
    int bits;
} Channel;

static Channel channel_of(unsigned long mask) {
    Channel c = {0, 0};
    if (mask == 0) return c;
    while (((mask >> c.shift) & 1UL) == 0) c.shift++;
    while (((mask >> (c.shift + c.bits)) & 1UL) != 0) c.bits++;
    return c;
}

/*
 * Widens a channel to 8 bits by bit replication (5-bit 0b10110 -> 0b10110101),
 * the expansion xwd, ImageMagick and pixman use, so captures of a 16-bit
 * screen match what the rest of the toolchain reports.
 */
static inline uint32_t channel_value(unsigned long pixel, Channel c) {
    if (c.bits == 0) return 0;
    uint32_t max = (c.bits >= 32) ? 0xFFFFFFFFu : ((1u << c.bits) - 1u);
    uint32_t v = (uint32_t)((pixel >> c.shift) & max);
    if (c.bits >= 8) return v >> (c.bits - 8);
    uint32_t out = 0;
    int filled = 0;
    while (filled < 8) {
        out = (out << c.bits) | v;
        filled += c.bits;
    }
    return out >> (filled - 8);
}

typedef struct {
    unsigned long red_mask, green_mask, blue_mask;
    Colormap colormap;
    int indexed; /* PseudoColor / StaticColor / GrayScale / StaticGray */
    int depth;
} PixelFormat;

static PixelFormat format_of_visual(Visual *visual, int depth, Colormap colormap) {
    PixelFormat f;
    memset(&f, 0, sizeof(f));
    f.depth = depth;
    f.colormap = colormap;
    if (visual != NULL) {
        f.red_mask = visual->red_mask;
        f.green_mask = visual->green_mask;
        f.blue_mask = visual->blue_mask;
#if defined(__cplusplus) || defined(c_plusplus)
        int cls = visual->c_class;
#else
        int cls = visual->class;
#endif
        f.indexed = cls != TrueColor && cls != DirectColor;
    }
    return f;
}

/*
 * Colormap of an indexed visual as 0xAARRGGBB, one entry per pixel value;
 * NULL for a TrueColor / DirectColor format. *failed is set when the format
 * is indexed but cannot be resolved.
 */
static uint32_t *indexed_lut(Display *dpy, PixelFormat f, int *failed) {
    *failed = 0;
    if (!f.indexed) return NULL;
    if (f.depth <= 0 || f.depth > 12 || f.colormap == None) {
        *failed = 1;
        return NULL;
    }
    int n = 1 << f.depth;
    XColor *colors = calloc((size_t)n, sizeof(XColor));
    uint32_t *lut = calloc((size_t)n, sizeof(uint32_t));
    if (colors == NULL || lut == NULL) {
        free(colors);
        free(lut);
        *failed = 1;
        return NULL;
    }
    for (int i = 0; i < n; i++) colors[i].pixel = (unsigned long)i;
    X.QueryColors(dpy, f.colormap, colors, n);
    if (x_take_error(dpy) != 0) {
        free(colors);
        free(lut);
        *failed = 1;
        return NULL;
    }
    for (int i = 0; i < n; i++) {
        lut[i] = 0xFF000000u | ((uint32_t)(colors[i].red >> 8) << 16) | ((uint32_t)(colors[i].green >> 8) << 8) |
                 (uint32_t)(colors[i].blue >> 8);
    }
    free(colors);
    return lut;
}

/*
 * Writes the w x h image into out (0xAARRGGBB, opaque). Makes no X or JNI
 * call, so it can run inside a primitive-array critical section.
 */
static int convert_image(XImage *image, PixelFormat f, const uint32_t *lut, int w, int h, jint *out) {
    unsigned long rm = image->red_mask != 0 ? image->red_mask : f.red_mask;
    unsigned long gm = image->green_mask != 0 ? image->green_mask : f.green_mask;
    unsigned long bm = image->blue_mask != 0 ? image->blue_mask : f.blue_mask;

    if (f.indexed) {
        if (lut == NULL) return 0;
        unsigned long index_mask = (1UL << f.depth) - 1UL;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[(size_t)y * w + x] = (jint)lut[XGetPixel(image, x, y) & index_mask];
        }
        return 1;
    }

    /* Fast path: 32 bpp, native little-endian x8r8g8b8. */
    if (image->bits_per_pixel == 32 && image->byte_order == LSBFirst && rm == 0xFF0000UL && gm == 0xFF00UL &&
        bm == 0xFFUL) {
        for (int y = 0; y < h; y++) {
            const uint32_t *row = (const uint32_t *)(image->data + (size_t)y * image->bytes_per_line);
            jint *dst = out + (size_t)y * w;
            for (int x = 0; x < w; x++) dst[x] = (jint)(row[x] | 0xFF000000u);
        }
        return 1;
    }

    if (rm == 0 || gm == 0 || bm == 0) return 0;
    Channel r = channel_of(rm), g = channel_of(gm), b = channel_of(bm);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            unsigned long p = XGetPixel(image, x, y);
            out[(size_t)y * w + x] = (jint)(0xFF000000u | (channel_value(p, r) << 16) | (channel_value(p, g) << 8) |
                                            channel_value(p, b));
        }
    }
    return 1;
}

static XFixesCursorImage *cursor_image(Display *dpy) {
    if (!XFX.loaded) return NULL;
    int event_base, error_base;
    if (!XFX.QueryExtension(dpy, &event_base, &error_base)) return NULL;
    XFixesCursorImage *cursor = XFX.GetCursorImage(dpy);
    if (x_take_error(dpy) != 0) {
        if (cursor != NULL) X.Free(cursor);
        return NULL;
    }
    return cursor;
}

/* Blends the cursor over out, whose top-left pixel is at (origin_x, origin_y) in root coordinates. */
static void draw_cursor(const XFixesCursorImage *cursor, jint *out, int w, int h, int origin_x, int origin_y) {
    int left = cursor->x - cursor->xhot - origin_x;
    int top = cursor->y - cursor->yhot - origin_y;
    for (int cy = 0; cy < cursor->height; cy++) {
        int y = top + cy;
        if (y < 0 || y >= h) continue;
        for (int cx = 0; cx < cursor->width; cx++) {
            int x = left + cx;
            if (x < 0 || x >= w) continue;
            /* XFixes pixels: premultiplied ARGB, one per unsigned long. */
            uint32_t src = (uint32_t)cursor->pixels[(size_t)cy * cursor->width + cx];
            uint32_t a = src >> 24;
            if (a == 0) continue;
            uint32_t dst = (uint32_t)out[(size_t)y * w + x];
            uint32_t inv = 255u - a;
            uint32_t rr = ((src >> 16) & 0xFF) + (((dst >> 16) & 0xFF) * inv + 127) / 255;
            uint32_t gg = ((src >> 8) & 0xFF) + (((dst >> 8) & 0xFF) * inv + 127) / 255;
            uint32_t bb = (src & 0xFF) + ((dst & 0xFF) * inv + 127) / 255;
            if (rr > 255) rr = 255;
            if (gg > 255) gg = 255;
            if (bb > 255) bb = 255;
            out[(size_t)y * w + x] = (jint)(0xFF000000u | (rr << 16) | (gg << 8) | bb);
        }
    }
}

/*
 * Grabs (x, y, w, h) of drawable and converts it straight into a new Java
 * array - no intermediate native buffer: on a 4K screen that is 33 MB per
 * capture, which per-thread malloc arenas would otherwise keep resident.
 * Returns ST_OK with *out set, or an error with a message.
 */
static int grab(JNIEnv *env, Display *dpy, Drawable drawable, PixelFormat f, int x, int y, int w, int h,
                int include_cursor, int cursor_origin_x, int cursor_origin_y, jintArray *out, jobjectArray message) {
    *out = NULL;
    if ((int64_t)w * (int64_t)h > MAX_PIXELS) {
        set_message(env, message, "%dx%d is too large for one image", w, h);
        return ST_FAILED;
    }
    XImage *image = X.GetImage(dpy, drawable, x, y, (unsigned int)w, (unsigned int)h, AllPlanes, ZPixmap);
    int error = x_take_error(dpy);
    if (g_x_io_dead) {
        if (image != NULL) XDestroyImage(image);
        set_message(env, message, "X server connection lost");
        return ST_FAILED;
    }
    if (image == NULL || error != 0) {
        if (image != NULL) XDestroyImage(image);
        set_message(env, message, "XGetImage failed (X error %d)", error);
        return ST_FAILED;
    }

    /* Every X round trip happens before the critical section. */
    int lut_failed = 0;
    uint32_t *lut = indexed_lut(dpy, f, &lut_failed);
    XFixesCursorImage *cursor = include_cursor && !lut_failed ? cursor_image(dpy) : NULL;
    int status = ST_FAILED;
    jintArray array = NULL;
    if (lut_failed) {
        set_message(env, message, "Cannot resolve the colormap of a depth %d visual", f.depth);
        goto done;
    }
    array = (*env)->NewIntArray(env, (jsize)((int64_t)w * h));
    if (array == NULL) goto done; /* OutOfMemoryError pending: the caller sees it */
    jint *pixels = (*env)->GetPrimitiveArrayCritical(env, array, NULL);
    if (pixels == NULL) goto done;
    int converted = convert_image(image, f, lut, w, h, pixels);
    if (converted && cursor != NULL) draw_cursor(cursor, pixels, w, h, cursor_origin_x, cursor_origin_y);
    (*env)->ReleasePrimitiveArrayCritical(env, array, pixels, 0);
    if (!converted) {
        set_message(env, message, "Unsupported pixel format (depth %d, %d bpp)", image->depth, image->bits_per_pixel);
        goto done;
    }
    *out = array;
    array = NULL;
    status = ST_OK;

done:
    if (array != NULL) (*env)->DeleteLocalRef(env, array);
    if (cursor != NULL) X.Free(cursor);
    free(lut);
    XDestroyImage(image);
    return status;
}

/* ------------------------------------------------------------------------ */
/* JNI: X11                                                                 */
/* ------------------------------------------------------------------------ */

EXPORT JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeBackend(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    pthread_once(&g_x11_once, load_x11);
    if (!X.loaded) return BACKEND_NONE;
    Display *dpy = x_session_open();
    if (dpy == NULL) return BACKEND_NONE;
    x_session_close(dpy);
    return BACKEND_X11;
}

EXPORT JNIEXPORT jint JNICALL Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeListDisplays(
    JNIEnv *env, jclass cls, jobject sink, jobjectArray message) {
    (void)cls;
    pthread_once(&g_x11_once, load_x11);
    if (!X.loaded) {
        set_message(env, message, "libX11 is not available");
        return ST_UNSUPPORTED;
    }
    jclass sink_class = (*env)->GetObjectClass(env, sink);
    jmethodID add = (*env)->GetMethodID(env, sink_class, "add",
                                        "(Ljava/lang/String;Ljava/lang/String;IIIIIIFZ)V");
    if (add == NULL) {
        nucleus_jni_clear_exception(env);
        set_message(env, message, "DisplayCollector.add not found");
        return ST_FAILED;
    }

    Display *dpy = x_session_open();
    if (dpy == NULL) {
        set_message(env, message, "Cannot open X display '%s'", getenv("DISPLAY") != NULL ? getenv("DISPLAY") : "");
        return ST_UNSUPPORTED;
    }
    MonitorList list = {0};
    list_monitors(dpy, &list);
    x_session_close(dpy);

    int status = ST_OK;
    for (int i = 0; i < list.count; i++) {
        Monitor *m = &list.items[i];
        jstring id = (*env)->NewStringUTF(env, m->name);
        if (id == NULL) {
            nucleus_jni_clear_exception(env);
            status = ST_FAILED;
            break;
        }
        (*env)->CallVoidMethod(env, sink, add, id, id, m->x, m->y, m->w, m->h, m->w, m->h, (jfloat)1.0f,
                               (jboolean)(m->primary ? JNI_TRUE : JNI_FALSE));
        (*env)->DeleteLocalRef(env, id);
        if (nucleus_jni_clear_exception(env)) {
            set_message(env, message, "DisplayCollector.add threw");
            status = ST_FAILED;
            break;
        }
    }
    free(list.items);
    return status;
}

EXPORT JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeCaptureDisplay(
    JNIEnv *env, jclass cls, jstring display_id, jint rx, jint ry, jint rw, jint rh, jboolean include_cursor,
    jintArray result, jobjectArray message) {
    (void)cls;
    set_result(env, result, ST_FAILED, 0, 0);
    pthread_once(&g_x11_once, load_x11);
    if (!X.loaded) {
        set_result(env, result, ST_UNSUPPORTED, 0, 0);
        set_message(env, message, "libX11 is not available");
        return NULL;
    }
    if (display_id == NULL) {
        set_result(env, result, ST_DISPLAY_NOT_FOUND, 0, 0);
        return NULL;
    }
    const char *id = (*env)->GetStringUTFChars(env, display_id, NULL);
    if (id == NULL) return NULL;

    Display *dpy = x_session_open();
    if (dpy == NULL) {
        (*env)->ReleaseStringUTFChars(env, display_id, id);
        set_result(env, result, ST_UNSUPPORTED, 0, 0);
        set_message(env, message, "Cannot open X display");
        return NULL;
    }

    MonitorList list = {0};
    list_monitors(dpy, &list);
    Monitor *monitor = NULL;
    for (int i = 0; i < list.count; i++) {
        if (strcmp(list.items[i].name, id) == 0) {
            monitor = &list.items[i];
            break;
        }
    }

    jintArray pixels = NULL;
    int status;
    int out_w = 0, out_h = 0;
    if (monitor == NULL) {
        status = ST_DISPLAY_NOT_FOUND;
        set_message(env, message, "No display '%s'", id);
    } else {
        /* Clip the monitor to the root window (a RandR monitor may extend past it), then the region to that. */
        int64_t root_w = DisplayWidth(dpy, DefaultScreen(dpy)), root_h = DisplayHeight(dpy, DefaultScreen(dpy));
        int64_t ml = monitor->x < 0 ? 0 : monitor->x, mt = monitor->y < 0 ? 0 : monitor->y;
        int64_t mr = (int64_t)monitor->x + monitor->w, mb = (int64_t)monitor->y + monitor->h;
        if (mr > root_w) mr = root_w;
        if (mb > root_h) mb = root_h;
        int64_t l = ml, t = mt, r = mr, b = mb;
        if (rw > 0 && rh > 0) {
            int64_t ql = (int64_t)monitor->x + rx, qt = (int64_t)monitor->y + ry;
            int64_t qr = ql + rw, qb = qt + rh;
            if (ql > l) l = ql;
            if (qt > t) t = qt;
            if (qr < r) r = qr;
            if (qb < b) b = qb;
        }
        if (r <= l || b <= t) {
            status = ST_INVALID_REGION;
            set_message(env, message, "Region (%d, %d, %d, %d) does not intersect display '%s'", rx, ry, rw, rh, id);
        } else {
            Window root = DefaultRootWindow(dpy);
            int screen = DefaultScreen(dpy);
            PixelFormat f = format_of_visual(DefaultVisual(dpy, screen), DefaultDepth(dpy, screen),
                                             DefaultColormap(dpy, screen));
            out_w = (int)(r - l);
            out_h = (int)(b - t);
            status = grab(env, dpy, root, f, (int)l, (int)t, out_w, out_h, include_cursor, (int)l, (int)t, &pixels,
                          message);
        }
    }
    free(list.items);
    x_session_close(dpy);
    (*env)->ReleaseStringUTFChars(env, display_id, id);
    if (status != ST_OK) {
        set_result(env, result, status, 0, 0);
        return NULL;
    }
    set_result(env, result, ST_OK, out_w, out_h);
    return pixels;
}

/* The window's content through its XComposite pixmap (valid even where it is covered). */
static int grab_composite(JNIEnv *env, Display *dpy, Window window, XWindowAttributes *attrs, int include_cursor,
                          int origin_x, int origin_y, jintArray *out) {
    *out = NULL;
    if (!XCOMP.loaded) return 0;
    int event_base, error_base, major = 0, minor = 0;
    if (!XCOMP.QueryExtension(dpy, &event_base, &error_base)) return 0;
    if (!XCOMP.QueryVersion(dpy, &major, &minor) || (major == 0 && minor < 2)) return 0;
    Pixmap pixmap = XCOMP.NameWindowPixmap(dpy, window);
    /* BadMatch when the window is not redirected (no compositing manager). */
    if (x_take_error(dpy) != 0 || pixmap == None) {
        if (pixmap != None) {
            X.FreePixmap(dpy, pixmap);
            x_take_error(dpy);
        }
        return 0;
    }
    PixelFormat f = format_of_visual(attrs->visual, attrs->depth, attrs->colormap);
    int bw = attrs->border_width;
    int status = grab(env, dpy, pixmap, f, bw, bw, attrs->width, attrs->height, include_cursor, origin_x, origin_y,
                      out, NULL);
    X.FreePixmap(dpy, pixmap);
    x_take_error(dpy);
    if (status != ST_OK && (*env)->ExceptionCheck(env)) return -1;
    return status == ST_OK;
}

EXPORT JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeCaptureWindow(
    JNIEnv *env, jclass cls, jlong window_id, jboolean include_cursor, jintArray result, jobjectArray message) {
    (void)cls;
    set_result(env, result, ST_FAILED, 0, 0);
    pthread_once(&g_x11_once, load_x11);
    if (!X.loaded) {
        set_result(env, result, ST_UNSUPPORTED, 0, 0);
        set_message(env, message, "libX11 is not available");
        return NULL;
    }
    if (window_id <= 0 || window_id > 0xFFFFFFFFLL) {
        set_result(env, result, ST_WINDOW_NOT_FOUND, 0, 0);
        set_message(env, message, "Not an X11 window id: %lld", (long long)window_id);
        return NULL;
    }
    Display *dpy = x_session_open();
    if (dpy == NULL) {
        set_result(env, result, ST_UNSUPPORTED, 0, 0);
        set_message(env, message, "Cannot open X display");
        return NULL;
    }
    Window window = (Window)window_id;
    Window root = DefaultRootWindow(dpy);
    XWindowAttributes attrs;
    memset(&attrs, 0, sizeof(attrs));
    Status ok = X.GetWindowAttributes(dpy, window, &attrs);
    int error = x_take_error(dpy);

    jintArray pixels = NULL;
    int status = ST_OK;
    int out_w = 0, out_h = 0;
    int root_x = 0, root_y = 0;
    Window child;
    if (!ok || error != 0) {
        status = ST_WINDOW_NOT_FOUND;
        set_message(env, message, "No window 0x%lx (X error %d)", (unsigned long)window, error);
    } else if (attrs.map_state != IsViewable) {
        status = ST_WINDOW_NOT_FOUND;
        set_message(env, message, "Window 0x%lx is not viewable", (unsigned long)window);
    } else if (!X.TranslateCoordinates(dpy, window, root, 0, 0, &root_x, &root_y, &child) || x_take_error(dpy) != 0) {
        status = ST_WINDOW_NOT_FOUND;
        set_message(env, message, "Window 0x%lx vanished", (unsigned long)window);
    } else {
        int composite = grab_composite(env, dpy, window, &attrs, include_cursor, root_x, root_y, &pixels);
        if (composite < 0) {
            status = ST_FAILED; /* pending Java exception */
        } else if (composite > 0) {
            out_w = attrs.width;
            out_h = attrs.height;
        } else {
            /*
             * No composite pixmap: read the window itself, clipped to the part
             * that is on the screen (XGetImage raises BadMatch past its edge).
             */
            int64_t sw = DisplayWidth(dpy, DefaultScreen(dpy)), sh = DisplayHeight(dpy, DefaultScreen(dpy));
            int64_t l = root_x < 0 ? -(int64_t)root_x : 0, t = root_y < 0 ? -(int64_t)root_y : 0;
            int64_t r = attrs.width, b = attrs.height;
            if ((int64_t)root_x + r > sw) r = sw - root_x;
            if ((int64_t)root_y + b > sh) b = sh - root_y;
            if (r <= l || b <= t) {
                status = ST_WINDOW_NOT_FOUND;
                set_message(env, message, "Window 0x%lx is off screen", (unsigned long)window);
            } else {
                PixelFormat f = format_of_visual(attrs.visual, attrs.depth, attrs.colormap);
                out_w = (int)(r - l);
                out_h = (int)(b - t);
                status = grab(env, dpy, window, f, (int)l, (int)t, out_w, out_h, include_cursor,
                              root_x + (int)l, root_y + (int)t, &pixels, message);
                if (status != ST_OK && !(*env)->ExceptionCheck(env)) {
                    /* The window disappeared between the checks and the grab. */
                    XWindowAttributes again;
                    if (!X.GetWindowAttributes(dpy, window, &again) || x_take_error(dpy) != 0) {
                        status = ST_WINDOW_NOT_FOUND;
                    }
                }
            }
        }
    }
    x_session_close(dpy);
    if (status != ST_OK) {
        set_result(env, result, status, 0, 0);
        return NULL;
    }
    set_result(env, result, ST_OK, out_w, out_h);
    return pixels;
}

EXPORT JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativePermissionStatus(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return PERMISSION_NOT_REQUIRED;
}

EXPORT JNIEXPORT jint JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativeRequestPermission(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return PERMISSION_NOT_REQUIRED;
}

/* ------------------------------------------------------------------------ */
/* Portal (Wayland)                                                         */
/* ------------------------------------------------------------------------ */

static struct {
    int loaded;
    void (*error_init)(DBusError *);
    void (*error_free)(DBusError *);
    dbus_bool_t (*error_is_set)(const DBusError *);
    dbus_bool_t (*threads_init_default)(void);
    DBusConnection *(*bus_get_private)(DBusBusType, DBusError *);
    void (*connection_close)(DBusConnection *);
    void (*connection_unref)(DBusConnection *);
    void (*connection_set_exit_on_disconnect)(DBusConnection *, dbus_bool_t);
    const char *(*bus_get_unique_name)(DBusConnection *);
    void (*bus_add_match)(DBusConnection *, const char *, DBusError *);
    DBusMessage *(*message_new_method_call)(const char *, const char *, const char *, const char *);
    void (*message_unref)(DBusMessage *);
    void (*message_set_no_reply)(DBusMessage *, dbus_bool_t);
    void (*message_iter_init_append)(DBusMessage *, DBusMessageIter *);
    dbus_bool_t (*message_iter_append_basic)(DBusMessageIter *, int, const void *);
    dbus_bool_t (*message_iter_open_container)(DBusMessageIter *, int, const char *, DBusMessageIter *);
    dbus_bool_t (*message_iter_close_container)(DBusMessageIter *, DBusMessageIter *);
    dbus_bool_t (*message_iter_init)(DBusMessage *, DBusMessageIter *);
    int (*message_iter_get_arg_type)(DBusMessageIter *);
    void (*message_iter_get_basic)(DBusMessageIter *, void *);
    dbus_bool_t (*message_iter_next)(DBusMessageIter *);
    void (*message_iter_recurse)(DBusMessageIter *, DBusMessageIter *);
    DBusMessage *(*connection_send_with_reply_and_block)(DBusConnection *, DBusMessage *, int, DBusError *);
    dbus_bool_t (*connection_send)(DBusConnection *, DBusMessage *, dbus_uint32_t *);
    void (*connection_flush)(DBusConnection *);
    dbus_bool_t (*connection_read_write)(DBusConnection *, int);
    DBusMessage *(*connection_pop_message)(DBusConnection *);
    dbus_bool_t (*message_is_signal)(DBusMessage *, const char *, const char *);
    const char *(*message_get_path)(DBusMessage *);
} D;

/* GError, declared here so the build needs no GLib headers. */
typedef struct {
    uint32_t domain;
    int code;
    char *message;
} GError_;

static struct {
    int loaded;
    void *(*new_from_file)(const char *, GError_ **);
    int (*get_width)(const void *);
    int (*get_height)(const void *);
    int (*get_rowstride)(const void *);
    int (*get_n_channels)(const void *);
    int (*get_bits_per_sample)(const void *);
    unsigned char *(*get_pixels)(const void *);
    void (*object_unref)(void *);
    void (*error_free)(GError_ *);
} PB;

static pthread_once_t g_portal_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_portal_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned long g_portal_counter = 0;

static void load_portal(void) {
    void *lib = dlopen("libdbus-1.so.3", RTLD_LAZY | RTLD_LOCAL);
    if (lib != NULL) {
        LOAD(lib, D, error_init, "dbus_error_init");
        LOAD(lib, D, error_free, "dbus_error_free");
        LOAD(lib, D, error_is_set, "dbus_error_is_set");
        LOAD(lib, D, threads_init_default, "dbus_threads_init_default");
        LOAD(lib, D, bus_get_private, "dbus_bus_get_private");
        LOAD(lib, D, connection_close, "dbus_connection_close");
        LOAD(lib, D, connection_unref, "dbus_connection_unref");
        LOAD(lib, D, connection_set_exit_on_disconnect, "dbus_connection_set_exit_on_disconnect");
        LOAD(lib, D, bus_get_unique_name, "dbus_bus_get_unique_name");
        LOAD(lib, D, bus_add_match, "dbus_bus_add_match");
        LOAD(lib, D, message_new_method_call, "dbus_message_new_method_call");
        LOAD(lib, D, message_unref, "dbus_message_unref");
        LOAD(lib, D, message_set_no_reply, "dbus_message_set_no_reply");
        LOAD(lib, D, message_iter_init_append, "dbus_message_iter_init_append");
        LOAD(lib, D, message_iter_append_basic, "dbus_message_iter_append_basic");
        LOAD(lib, D, message_iter_open_container, "dbus_message_iter_open_container");
        LOAD(lib, D, message_iter_close_container, "dbus_message_iter_close_container");
        LOAD(lib, D, message_iter_init, "dbus_message_iter_init");
        LOAD(lib, D, message_iter_get_arg_type, "dbus_message_iter_get_arg_type");
        LOAD(lib, D, message_iter_get_basic, "dbus_message_iter_get_basic");
        LOAD(lib, D, message_iter_next, "dbus_message_iter_next");
        LOAD(lib, D, message_iter_recurse, "dbus_message_iter_recurse");
        LOAD(lib, D, connection_send_with_reply_and_block, "dbus_connection_send_with_reply_and_block");
        LOAD(lib, D, connection_send, "dbus_connection_send");
        LOAD(lib, D, connection_flush, "dbus_connection_flush");
        LOAD(lib, D, connection_read_write, "dbus_connection_read_write");
        LOAD(lib, D, connection_pop_message, "dbus_connection_pop_message");
        LOAD(lib, D, message_is_signal, "dbus_message_is_signal");
        LOAD(lib, D, message_get_path, "dbus_message_get_path");
        D.loaded = D.error_init && D.error_free && D.error_is_set && D.threads_init_default && D.bus_get_private &&
                   D.connection_close && D.connection_unref && D.connection_set_exit_on_disconnect &&
                   D.bus_get_unique_name && D.bus_add_match && D.message_new_method_call && D.message_unref &&
                   D.message_set_no_reply && D.message_iter_init_append && D.message_iter_append_basic &&
                   D.message_iter_open_container && D.message_iter_close_container && D.message_iter_init &&
                   D.message_iter_get_arg_type && D.message_iter_get_basic && D.message_iter_next &&
                   D.message_iter_recurse && D.connection_send_with_reply_and_block && D.connection_send &&
                   D.connection_flush && D.connection_read_write && D.connection_pop_message &&
                   D.message_is_signal && D.message_get_path;
        if (D.loaded) D.threads_init_default();
    }

    void *pixbuf = dlopen("libgdk_pixbuf-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    void *gobject = dlopen("libgobject-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    void *glib = dlopen("libglib-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (pixbuf != NULL && gobject != NULL && glib != NULL) {
        LOAD(pixbuf, PB, new_from_file, "gdk_pixbuf_new_from_file");
        LOAD(pixbuf, PB, get_width, "gdk_pixbuf_get_width");
        LOAD(pixbuf, PB, get_height, "gdk_pixbuf_get_height");
        LOAD(pixbuf, PB, get_rowstride, "gdk_pixbuf_get_rowstride");
        LOAD(pixbuf, PB, get_n_channels, "gdk_pixbuf_get_n_channels");
        LOAD(pixbuf, PB, get_bits_per_sample, "gdk_pixbuf_get_bits_per_sample");
        LOAD(pixbuf, PB, get_pixels, "gdk_pixbuf_get_pixels");
        LOAD(gobject, PB, object_unref, "g_object_unref");
        LOAD(glib, PB, error_free, "g_error_free");
        PB.loaded = PB.new_from_file && PB.get_width && PB.get_height && PB.get_rowstride && PB.get_n_channels &&
                    PB.get_bits_per_sample && PB.get_pixels && PB.object_unref && PB.error_free;
    }
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* file:///a%20b -> /a b; NULL for anything but a local file URI. Caller frees. */
static char *path_of_uri(const char *uri) {
    const char *p;
    if (strncmp(uri, "file://", 7) != 0) return NULL;
    p = uri + 7;
    if (strncmp(p, "localhost/", 10) == 0) p += 9;
    if (*p != '/') return NULL;
    size_t n = strlen(p);
    char *out = malloc(n + 1);
    if (out == NULL) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        if (p[i] == '%' && i + 2 < n && hex_value(p[i + 1]) >= 0 && hex_value(p[i + 2]) >= 0) {
            char c = (char)(hex_value(p[i + 1]) * 16 + hex_value(p[i + 2]));
            if (c == '\0') {
                free(out);
                return NULL;
            }
            out[o++] = c;
            i += 2;
        } else if (p[i] == '?' || p[i] == '#') {
            break;
        } else {
            out[o++] = p[i];
        }
    }
    out[o] = '\0';
    return out;
}

/* Appends a{sv} entry key -> variant of basic type. */
static int append_option(DBusMessageIter *dict, const char *key, int type, const char *signature, const void *value) {
    DBusMessageIter entry, variant;
    if (!D.message_iter_open_container(dict, DBUS_TYPE_DICT_ENTRY, NULL, &entry)) return 0;
    if (!D.message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key)) return 0;
    if (!D.message_iter_open_container(&entry, DBUS_TYPE_VARIANT, signature, &variant)) return 0;
    if (!D.message_iter_append_basic(&variant, type, value)) return 0;
    if (!D.message_iter_close_container(&entry, &variant)) return 0;
    return D.message_iter_close_container(dict, &entry);
}

/* Parses Response(u response, a{sv} results); *uri is a malloc'd copy of results["uri"]. */
static int parse_response(DBusMessage *msg, dbus_uint32_t *code, char **uri) {
    DBusMessageIter iter, dict, entry, variant;
    *uri = NULL;
    if (!D.message_iter_init(msg, &iter) || D.message_iter_get_arg_type(&iter) != DBUS_TYPE_UINT32) return 0;
    D.message_iter_get_basic(&iter, code);
    if (!D.message_iter_next(&iter) || D.message_iter_get_arg_type(&iter) != DBUS_TYPE_ARRAY) return 1;
    D.message_iter_recurse(&iter, &dict);
    while (D.message_iter_get_arg_type(&dict) == DBUS_TYPE_DICT_ENTRY) {
        const char *key = NULL;
        D.message_iter_recurse(&dict, &entry);
        if (D.message_iter_get_arg_type(&entry) == DBUS_TYPE_STRING) {
            D.message_iter_get_basic(&entry, &key);
            if (key != NULL && strcmp(key, "uri") == 0 && D.message_iter_next(&entry) &&
                D.message_iter_get_arg_type(&entry) == DBUS_TYPE_VARIANT) {
                D.message_iter_recurse(&entry, &variant);
                if (D.message_iter_get_arg_type(&variant) == DBUS_TYPE_STRING) {
                    const char *value = NULL;
                    D.message_iter_get_basic(&variant, &value);
                    if (value != NULL) {
                        free(*uri);
                        *uri = strdup(value);
                    }
                }
            }
        }
        D.message_iter_next(&dict);
    }
    return 1;
}

static int add_match(DBusConnection *conn, const char *rule) {
    DBusError err;
    D.error_init(&err);
    D.bus_add_match(conn, rule, &err);
    int ok = !D.error_is_set(&err);
    D.error_free(&err);
    return ok;
}

#define RESPONSE_RULE \
    "type='signal',sender='org.freedesktop.portal.Desktop',interface='org.freedesktop.portal.Request',member='Response',"

/*
 * Subscribes to Response signals before the call so none can be missed: on
 * this connection's whole request namespace (a portal that ignores
 * handle_token still builds its path there, and may answer before its reply
 * has been read), else - buses before dbus 1.5 reject path_namespace - on
 * the predicted path only.
 */
static void add_response_match(DBusConnection *conn, const char *sender, const char *expected) {
    char rule[1024];
    snprintf(rule, sizeof(rule), RESPONSE_RULE "path_namespace='/org/freedesktop/portal/desktop/request/%s'", sender);
    if (add_match(conn, rule)) return;
    snprintf(rule, sizeof(rule), RESPONSE_RULE "path='%s'", expected);
    add_match(conn, rule);
}

static void add_path_match(DBusConnection *conn, const char *path) {
    char rule[1024];
    snprintf(rule, sizeof(rule), RESPONSE_RULE "path='%s'", path);
    add_match(conn, rule);
}

/* Best effort: tell the portal we gave up on the request. */
static void close_request(DBusConnection *conn, const char *path) {
    DBusMessage *msg =
        D.message_new_method_call("org.freedesktop.portal.Desktop", path, "org.freedesktop.portal.Request", "Close");
    if (msg == NULL) return;
    D.message_set_no_reply(msg, TRUE);
    D.connection_send(conn, msg, NULL);
    D.connection_flush(conn);
    D.message_unref(msg);
}

/* Decodes path into a new Java array; ST_OK or an error with a message. */
static int decode_file(JNIEnv *env, const char *path, jintArray *out, int *out_w, int *out_h, jobjectArray message) {
    *out = NULL;
    GError_ *error = NULL;
    void *pixbuf = PB.new_from_file(path, &error);
    if (pixbuf == NULL) {
        set_message(env, message, "Cannot decode %s: %s", path, error != NULL && error->message ? error->message : "?");
        if (error != NULL) PB.error_free(error);
        return ST_FAILED;
    }
    int w = PB.get_width(pixbuf), h = PB.get_height(pixbuf);
    int stride = PB.get_rowstride(pixbuf), channels = PB.get_n_channels(pixbuf);
    int bits = PB.get_bits_per_sample(pixbuf);
    const unsigned char *data = PB.get_pixels(pixbuf);
    if (w <= 0 || h <= 0 || bits != 8 || (channels != 3 && channels != 4) || data == NULL ||
        (int64_t)w * h > MAX_PIXELS) {
        set_message(env, message, "Unsupported screenshot %dx%d, %d channels of %d bits", w, h, channels, bits);
        PB.object_unref(pixbuf);
        return ST_FAILED;
    }
    jint *pixels = malloc((size_t)w * (size_t)h * sizeof(jint));
    if (pixels == NULL) {
        PB.object_unref(pixbuf);
        set_message(env, message, "Out of native memory for %dx%d", w, h);
        return ST_FAILED;
    }
    for (int y = 0; y < h; y++) {
        const unsigned char *row = data + (size_t)y * stride;
        for (int x = 0; x < w; x++) {
            const unsigned char *p = row + (size_t)x * channels;
            pixels[(size_t)y * w + x] = (jint)(0xFF000000u | ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | p[2]);
        }
    }
    PB.object_unref(pixbuf);
    jintArray array = (*env)->NewIntArray(env, (jsize)((int64_t)w * h));
    if (array == NULL) {
        free(pixels);
        return ST_FAILED;
    }
    (*env)->SetIntArrayRegion(env, array, 0, (jsize)((int64_t)w * h), pixels);
    free(pixels);
    *out = array;
    *out_w = w;
    *out_h = h;
    return ST_OK;
}

static int is_unsupported_error(const char *name) {
    return name != NULL &&
           (strcmp(name, DBUS_ERROR_SERVICE_UNKNOWN) == 0 || strcmp(name, DBUS_ERROR_UNKNOWN_METHOD) == 0 ||
            strcmp(name, "org.freedesktop.DBus.Error.UnknownObject") == 0 ||
            strcmp(name, "org.freedesktop.DBus.Error.UnknownInterface") == 0 ||
            strcmp(name, DBUS_ERROR_NAME_HAS_NO_OWNER) == 0 || strcmp(name, DBUS_ERROR_SPAWN_SERVICE_NOT_FOUND) == 0 ||
            strcmp(name, "org.freedesktop.DBus.Error.ServiceNotFound") == 0);
}

static int portal_screenshot(JNIEnv *env, int interactive, int timeout_ms, jintArray *out, int *out_w, int *out_h,
                             jobjectArray message) {
    DBusError err;
    D.error_init(&err);
    DBusConnection *conn = D.bus_get_private(DBUS_BUS_SESSION, &err);
    if (conn == NULL) {
        set_message(env, message, "No D-Bus session bus: %s", D.error_is_set(&err) ? err.message : "?");
        D.error_free(&err);
        return ST_UNSUPPORTED;
    }
    D.connection_set_exit_on_disconnect(conn, FALSE);

    /* Predicted request path: /org/freedesktop/portal/desktop/request/<sender>/<token>. */
    const char *unique = D.bus_get_unique_name(conn);
    char sender[256];
    size_t s = 0;
    for (const char *c = unique != NULL ? unique : ""; *c != '\0' && s + 1 < sizeof(sender); c++) {
        if (*c == ':') continue;
        sender[s++] = *c == '.' ? '_' : *c;
    }
    sender[s] = '\0';
    char token[64];
    pthread_mutex_lock(&g_portal_lock);
    unsigned long counter = ++g_portal_counter;
    pthread_mutex_unlock(&g_portal_lock);
    snprintf(token, sizeof(token), "nucleus_sc_%ld_%lu", (long)getpid(), counter);
    char expected[512];
    snprintf(expected, sizeof(expected), "/org/freedesktop/portal/desktop/request/%s/%s", sender, token);
    add_response_match(conn, sender, expected);

    int status = ST_FAILED;
    char handle[512];
    snprintf(handle, sizeof(handle), "%s", expected);
    int64_t deadline = now_ms() + (timeout_ms > 0 ? timeout_ms : 60000);

    DBusMessage *call = D.message_new_method_call("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
                                                  "org.freedesktop.portal.Screenshot", "Screenshot");
    if (call == NULL) {
        set_message(env, message, "Out of memory building the portal call");
        goto done;
    }
    {
        DBusMessageIter args, dict;
        const char *parent = "";
        const char *token_ptr = token;
        dbus_bool_t interactive_value = interactive ? TRUE : FALSE;
        D.message_iter_init_append(call, &args);
        int built = D.message_iter_append_basic(&args, DBUS_TYPE_STRING, &parent) &&
                    D.message_iter_open_container(&args, DBUS_TYPE_ARRAY, "{sv}", &dict) &&
                    append_option(&dict, "handle_token", DBUS_TYPE_STRING, "s", &token_ptr) &&
                    append_option(&dict, "interactive", DBUS_TYPE_BOOLEAN, "b", &interactive_value) &&
                    D.message_iter_close_container(&args, &dict);
        if (!built) {
            D.message_unref(call);
            set_message(env, message, "Out of memory building the portal call");
            goto done;
        }
    }
    int64_t call_timeout = deadline - now_ms();
    if (call_timeout < 1) call_timeout = 1;
    if (call_timeout > 25000) call_timeout = 25000;
    DBusMessage *reply = D.connection_send_with_reply_and_block(conn, call, (int)call_timeout, &err);
    D.message_unref(call);
    if (reply == NULL) {
        if (is_unsupported_error(err.name)) {
            status = ST_UNSUPPORTED;
            set_message(env, message, "No screenshot portal: %s", err.message != NULL ? err.message : err.name);
        } else if (err.name != NULL && (strcmp(err.name, DBUS_ERROR_NO_REPLY) == 0 ||
                                        strcmp(err.name, DBUS_ERROR_TIMEOUT) == 0)) {
            status = ST_TIMEOUT;
            set_message(env, message, "Screenshot portal did not answer");
        } else if (err.name != NULL && (strcmp(err.name, DBUS_ERROR_ACCESS_DENIED) == 0 ||
                                        strcmp(err.name, "org.freedesktop.portal.Error.NotAllowed") == 0)) {
            status = ST_PERMISSION_DENIED;
            set_message(env, message, "%s", err.message != NULL ? err.message : err.name);
        } else {
            status = ST_FAILED;
            set_message(env, message, "Screenshot portal call failed: %s: %s", err.name != NULL ? err.name : "?",
                        err.message != NULL ? err.message : "");
        }
        D.error_free(&err);
        goto done;
    }
    {
        DBusMessageIter iter;
        if (D.message_iter_init(reply, &iter) && D.message_iter_get_arg_type(&iter) == DBUS_TYPE_OBJECT_PATH) {
            const char *path = NULL;
            D.message_iter_get_basic(&iter, &path);
            /* Older portals ignore handle_token: listen on the path they actually use too. */
            if (path != NULL && strcmp(path, expected) != 0) {
                snprintf(handle, sizeof(handle), "%s", path);
                add_path_match(conn, handle);
            }
        }
        D.message_unref(reply);
    }

    for (;;) {
        DBusMessage *msg;
        while ((msg = D.connection_pop_message(conn)) != NULL) {
            const char *path = D.message_get_path(msg);
            if (D.message_is_signal(msg, "org.freedesktop.portal.Request", "Response") && path != NULL &&
                (strcmp(path, handle) == 0 || strcmp(path, expected) == 0)) {
                dbus_uint32_t code = 2;
                char *uri = NULL;
                if (!parse_response(msg, &code, &uri)) {
                    status = ST_FAILED;
                    set_message(env, message, "Malformed portal response");
                } else if (code == 0) {
                    char *file = uri != NULL ? path_of_uri(uri) : NULL;
                    if (file == NULL) {
                        status = ST_FAILED;
                        set_message(env, message, "Portal returned no usable uri: %s", uri != NULL ? uri : "(none)");
                    } else {
                        status = decode_file(env, file, out, out_w, out_h, message);
                        struct stat st;
                        if (status == ST_OK && lstat(file, &st) == 0 && S_ISREG(st.st_mode)) unlink(file);
                        free(file);
                    }
                } else if (code == 1) {
                    status = ST_CANCELLED;
                    set_message(env, message, "The screenshot was cancelled");
                } else {
                    status = ST_FAILED;
                    set_message(env, message, "The screenshot portal failed (response %u)", (unsigned)code);
                }
                free(uri);
                D.message_unref(msg);
                goto done;
            }
            D.message_unref(msg);
        }
        int64_t remaining = deadline - now_ms();
        if (remaining <= 0) {
            status = ST_TIMEOUT;
            set_message(env, message, "The screenshot portal did not respond in %d ms", timeout_ms);
            close_request(conn, handle);
            goto done;
        }
        if (!D.connection_read_write(conn, (int)(remaining < 200 ? remaining : 200))) {
            status = ST_FAILED;
            set_message(env, message, "D-Bus connection lost");
            goto done;
        }
    }

done:
    D.connection_close(conn);
    D.connection_unref(conn);
    return status;
}

EXPORT JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_screencapture_internal_NativeScreenCapture_nativePortalScreenshot(
    JNIEnv *env, jclass cls, jboolean interactive, jint timeout_ms, jintArray result, jobjectArray message) {
    (void)cls;
    set_result(env, result, ST_FAILED, 0, 0);
    pthread_once(&g_portal_once, load_portal);
    if (!D.loaded) {
        set_result(env, result, ST_UNSUPPORTED, 0, 0);
        set_message(env, message, "libdbus-1 is not available");
        return NULL;
    }
    if (!PB.loaded) {
        set_result(env, result, ST_UNSUPPORTED, 0, 0);
        set_message(env, message, "gdk-pixbuf is not available to decode the screenshot");
        return NULL;
    }
    jintArray pixels = NULL;
    int w = 0, h = 0;
    int status = portal_screenshot(env, interactive, timeout_ms, &pixels, &w, &h, message);
    if (status != ST_OK) {
        set_result(env, result, status, 0, 0);
        return NULL;
    }
    set_result(env, result, ST_OK, w, h);
    return pixels;
}
