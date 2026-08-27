/**
 * JNI bridge: standalone transparent popup panel for Linux (X11 / XWayland).
 *
 * The Linux counterpart of `windows/nucleus_tao_windows_popup.c` and
 * `macos/popup_panel.m`: a top-level, ownerless, override-redirect ARGB32
 * X11 window with per-pixel transparency, positioned in global screen
 * coordinates, that never appears in the taskbar / Alt-Tab and never
 * activates the application. Rendering is done by the Kotlin host through
 * `NativeTaoEglBridge.nativeAttachX11` on the window this module creates.
 *
 * Why raw X11 and not GTK: GDK's backend is process-wide (native Wayland
 * on Wayland sessions), and Wayland has no ownerless globally-positioned
 * topmost surface (no layer-shell in vendored Tao, `gtk_window_move` is a
 * no-op on xdg-toplevels). A raw X11 window on its own `XOpenDisplay`
 * connection is an independent X client that works even while the app
 * itself is a native Wayland client — through XWayland, which is present
 * on effectively all desktops. When `XOpenDisplay` fails (rare
 * Wayland-only kiosks), `nativeIsAvailable` returns false and the caller
 * falls back to a regular window.
 *
 * Visual selection: the window's visual is derived from EGL itself (first
 * alpha=8 desktop-GL `EGLConfig` whose `EGL_NATIVE_VISUAL_ID` is a 32-bit
 * X visual). `nativeAttachX11` later resolves the same `EGLDisplay` for
 * the same `Display*` and matches that exact config — no child-window
 * fallback, alpha preserved end to end.
 *
 * Threading model — two X connections, each single-threaded (no
 * XInitThreads dependency):
 *   - The COMMAND connection (`g_cmd_dpy`) is owned by the Tao main
 *     thread. Every JNI entry point below runs on it (the composable
 *     wrapper guarantees this): create/move/map/unmap/cursor/destroy.
 *     The Kotlin host also hands this `Display*` to
 *     `NativeTaoEglBridge.nativeAttachX11`, so EGL work stays on the
 *     same thread/connection.
 *   - Each panel owns an EVENT connection + thread: it opens its own
 *     `Display`, **creates the X window** (so this client is the creator),
 *     calls `XSelectInput` on the panel XID and blocks in a `poll()` loop
 *     on the X fd + a quit pipe. Input events are forwarded to Java
 *     through cached JNI method IDs (same pattern as
 *     `nucleus_tao_linux_widget.c`).
 *
 *     The window is created on the event connection on purpose: XDND
 *     ClientMessages are sent with `event_mask = NoEventMask`, which the
 *     X server delivers only to the *creating* client. The command
 *     connection never pumps `XNextEvent`, so a command-created window
 *     would queue every `XdndEnter`/`Position`/`Drop` forever. Creating
 *     on the event thread makes that thread the creator and the XDND
 *     destination. Move/map/cursor/destroy still go through the command
 *     connection — those requests are XID-based and not creator-bound.
 *     EGL attach keeps using the command `Display*` (`nativeDisplayPtr`);
 *     `eglCreateWindowSurface` keys on the XID, which is server-global.
 *
 * Outside-click: XI2 raw ButtonPress on the root window — the X11 analog
 * of the Windows `WH_MOUSE_LL` hook (raw events are observe-only, don't
 * consume, and multiple clients may listen). Fully global on X11
 * sessions; under XWayland raw events only fire while X11 surfaces have
 * input focus — the tray-icon toggle covers the remaining cases.
 *
 * Inbound file DnD lives in `nucleus_tao_linux_popup_xdnd.c` — the event
 * thread is the creating client, so it is the only place XDND
 * ClientMessages (mask 0) arrive. This file just forwards them.
 *
 * Keyboard: clicking the panel while focusable calls
 * `XSetInputFocus(RevertToParent)` (the `takeKeyboardFocus()` equivalent).
 * Key events forward the ACTIVE-layout keysym resolved to a Unicode code
 * point via libxkbcommon (`xkb_keysym_to_utf32` — needed for non-Latin-1
 * layouts, e.g. Hebrew), plus a LATIN keysym scanned across XKB groups as
 * the vkCode so shortcuts (Ctrl+C on a Hebrew layout) land on the right
 * `Key`. `linuxNativeKeyToAwt` (TaoKeyLinux.kt) does the keysym → AWT VK
 * translation Kotlin-side.
 *
 * Build: compiled against the system X11/XI2 headers (guaranteed present
 * wherever Tao's GTK 3 dev headers are, i.e. every build environment) but
 * linked with `-ldl` only — libX11.so.6, libXi.so.6, libxkbcommon.so.0
 * and libEGL.so.1 are dlopen-ed at runtime so the .so ships standalone,
 * matching `nucleus_tao_egl.c`. XEvent/XVisualInfo layouts are stable
 * Xlib ABI; using the real headers avoids hand-redeclaring the XEvent
 * union.
 */

#include "nucleus_tao_linux_popup.h"

#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <X11/XKBlib.h>
#include <X11/cursorfont.h>

/* ── Wire format (must stay in sync with TaoNativeWireFormat.kt) ────────── */

#define WIRE_PTR_DOWN 1
#define WIRE_PTR_UP   2
#define WIRE_PTR_MOVE 3

#define WIRE_BUTTON_NONE      0
#define WIRE_BUTTON_PRIMARY   1
#define WIRE_BUTTON_SECONDARY 2

#define WIRE_KEY_DOWN 1
#define WIRE_KEY_UP   2

#define WIRE_MOD_SHIFT 0x1
#define WIRE_MOD_CTRL  0x2
#define WIRE_MOD_ALT   0x4
#define WIRE_MOD_META  0x8

/* TaoCursorIcon codes (NativeTaoBridge.kt). */
#define ICON_DEFAULT     0
#define ICON_TEXT        1
#define ICON_HAND        2
#define ICON_CROSSHAIR   3
#define ICON_WAIT        4
#define ICON_MOVE        5
#define ICON_NOT_ALLOWED 6
#define ICON_HELP        7
#define ICON_PROGRESS    8
#define ICON_EW_RESIZE   9
#define ICON_NS_RESIZE   10
#define ICON_NESW_RESIZE 11
#define ICON_NWSE_RESIZE 12

/* ── dlopen-ed symbol tables ────────────────────────────────────────────── */

typedef struct xkb_keysym_dummy xkb_keysym_dummy; /* unused, keeps clang-tidy calm */

PopupX11 fn;

static void *load_first(const char *const *names) {
    for (int i = 0; names[i] != NULL; i++) {
        void *h = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
        if (h != NULL) return h;
    }
    return NULL;
}

static int ensure_libs_loaded(void) {
    if (fn.initialized) return 1;

    const char *x11_libs[]    = { "libX11.so.6", "libX11.so", NULL };
    const char *xi_libs[]     = { "libXi.so.6", "libXi.so", NULL };
    const char *xkb_libs[]    = { "libxkbcommon.so.0", "libxkbcommon.so", NULL };
    const char *egl_libs[]    = { "libEGL.so.1", "libEGL.so", NULL };
    const char *xrandr_libs[] = { "libXrandr.so.2", "libXrandr.so", NULL };

    void *libx11 = load_first(x11_libs);
    if (libx11 == NULL) { DBG("libX11 not found\n"); return 0; }
    void *libxi  = load_first(xi_libs);   /* optional: outside-click only */
    void *libxkb = load_first(xkb_libs);  /* optional: non-Latin-1 layouts */
    void *libegl = load_first(egl_libs);  /* optional: falls back to XGetVisualInfo */
    void *libxrandr = load_first(xrandr_libs); /* optional: full-screen fallback */

#define X11_SYM(name) fn.name = (__typeof__(fn.name)) dlsym(libx11, #name)
    X11_SYM(XOpenDisplay);       X11_SYM(XCloseDisplay);
    X11_SYM(XCreateWindow);      X11_SYM(XDestroyWindow);
    X11_SYM(XMapRaised);         X11_SYM(XUnmapWindow);
    X11_SYM(XRaiseWindow);       X11_SYM(XMoveResizeWindow);
    X11_SYM(XFlush);             X11_SYM(XSync);
    X11_SYM(XSelectInput);       X11_SYM(XNextEvent);
    X11_SYM(XPending);           X11_SYM(XCreateColormap);
    X11_SYM(XFreeColormap);      X11_SYM(XGetVisualInfo);
    X11_SYM(XFree);              X11_SYM(XSetInputFocus);
    X11_SYM(XCreateFontCursor);  X11_SYM(XDefineCursor);
    X11_SYM(XFreeCursor);        X11_SYM(XStoreName);
    X11_SYM(XResourceManagerString);
    X11_SYM(XLookupString);      X11_SYM(XkbKeycodeToKeysym);
    X11_SYM(XQueryExtension);    X11_SYM(XGetEventData);
    X11_SYM(XFreeEventData);     X11_SYM(XQueryPointer);
    X11_SYM(XInternAtom);        X11_SYM(XGetWindowProperty);
    X11_SYM(XChangeProperty);    X11_SYM(XDeleteProperty);
    X11_SYM(XSendEvent);         X11_SYM(XConvertSelection);
    X11_SYM(XSetSelectionOwner);
#undef X11_SYM

    if (libxi != NULL) {
        fn.XISelectEvents = (__typeof__(fn.XISelectEvents)) dlsym(libxi, "XISelectEvents");
        fn.XIQueryVersion = (__typeof__(fn.XIQueryVersion)) dlsym(libxi, "XIQueryVersion");
    }
    if (libxkb != NULL) {
        fn.xkb_keysym_to_utf32 =
            (__typeof__(fn.xkb_keysym_to_utf32)) dlsym(libxkb, "xkb_keysym_to_utf32");
    }
    if (libxrandr != NULL) {
        fn.XRRGetMonitors  = (__typeof__(fn.XRRGetMonitors))  dlsym(libxrandr, "XRRGetMonitors");
        fn.XRRFreeMonitors = (__typeof__(fn.XRRFreeMonitors)) dlsym(libxrandr, "XRRFreeMonitors");
    }
    if (libegl != NULL) {
        fn.eglGetPlatformDisplay =
            (__typeof__(fn.eglGetPlatformDisplay)) dlsym(libegl, "eglGetPlatformDisplay");
        fn.eglGetDisplay    = (__typeof__(fn.eglGetDisplay))    dlsym(libegl, "eglGetDisplay");
        fn.eglInitialize    = (__typeof__(fn.eglInitialize))    dlsym(libegl, "eglInitialize");
        fn.eglBindAPI       = (__typeof__(fn.eglBindAPI))       dlsym(libegl, "eglBindAPI");
        fn.eglChooseConfig  = (__typeof__(fn.eglChooseConfig))  dlsym(libegl, "eglChooseConfig");
        fn.eglGetConfigAttrib =
            (__typeof__(fn.eglGetConfigAttrib)) dlsym(libegl, "eglGetConfigAttrib");
    }

    if (!fn.XOpenDisplay || !fn.XCloseDisplay || !fn.XCreateWindow ||
        !fn.XDestroyWindow || !fn.XMapRaised || !fn.XUnmapWindow ||
        !fn.XRaiseWindow || !fn.XMoveResizeWindow || !fn.XFlush ||
        !fn.XSync || !fn.XSelectInput || !fn.XNextEvent || !fn.XPending ||
        !fn.XCreateColormap || !fn.XFreeColormap || !fn.XGetVisualInfo ||
        !fn.XFree || !fn.XSetInputFocus || !fn.XCreateFontCursor ||
        !fn.XDefineCursor || !fn.XFreeCursor || !fn.XLookupString ||
        !fn.XkbKeycodeToKeysym || !fn.XQueryPointer ||
        !fn.XInternAtom || !fn.XGetWindowProperty ||
        !fn.XChangeProperty || !fn.XDeleteProperty ||
        !fn.XSendEvent || !fn.XConvertSelection ||
        !fn.XSetSelectionOwner) {
        DBG("missing libX11 symbols\n");
        return 0;
    }
    fn.initialized = 1;
    return 1;
}

/* ── Command connection (Tao main thread) ───────────────────────────────── */

static Display *g_cmd_dpy = NULL;
static int g_cmd_dpy_failed = 0;

static Display *ensure_cmd_display(void) {
    if (g_cmd_dpy != NULL) return g_cmd_dpy;
    if (g_cmd_dpy_failed) return NULL;
    if (!ensure_libs_loaded()) { g_cmd_dpy_failed = 1; return NULL; }
    g_cmd_dpy = fn.XOpenDisplay(NULL);
    if (g_cmd_dpy == NULL) {
        DBG("XOpenDisplay failed (no X server / XWayland?)\n");
        g_cmd_dpy_failed = 1;
    }
    return g_cmd_dpy;
}

/* ── JNI callback plumbing ──────────────────────────────────────────────── */

JavaVM *g_jvm = NULL;

/* Cached once per interface, from the first registered instance (all
 * implementors are the same named classes — GraalVM metadata requirement). */
static jmethodID g_on_pointer_event = NULL; /* (IFFII)V  */
static jmethodID g_on_scroll        = NULL; /* (FFFF)V   */
static jmethodID g_on_key_event     = NULL; /* (IIII)V   */
static jmethodID g_on_outside_click = NULL; /* (II)V     */

static JNIEnv *attach_jvm_thread(void) {
    if (g_jvm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint status = (*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **) &env, NULL) != JNI_OK) {
            return NULL;
        }
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

/* Balances attach_jvm_thread on native-owned threads. Event threads are
 * created by pthread_create, so a JNI_OK GetEnv here can only mean we
 * attached earlier on this same thread — detaching is always correct. */
static void detach_jvm_thread(void) {
    if (g_jvm == NULL) return;
    JNIEnv *env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_8) == JNI_OK) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void cache_event_callback_ids(JNIEnv *env, jobject callback) {
    if (g_on_pointer_event != NULL) return;
    jclass cls = (*env)->GetObjectClass(env, callback);
    if (cls == NULL) return;
    g_on_pointer_event = (*env)->GetMethodID(env, cls, "onPointerEvent", "(IFFII)V");
    g_on_scroll        = (*env)->GetMethodID(env, cls, "onScroll", "(FFFF)V");
    g_on_key_event     = (*env)->GetMethodID(env, cls, "onKeyEvent", "(IIII)V");
    (*env)->DeleteLocalRef(env, cls);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void cache_outside_listener_id(JNIEnv *env, jobject listener) {
    if (g_on_outside_click != NULL) return;
    jclass cls = (*env)->GetObjectClass(env, listener);
    if (cls == NULL) return;
    g_on_outside_click = (*env)->GetMethodID(env, cls, "onOutsideClick", "(II)V");
    (*env)->DeleteLocalRef(env, cls);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ── Keysym helpers ─────────────────────────────────────────────────────── */

/* Active-layout keysym → Unicode code point. libxkbcommon handles every
 * legacy keysym block (Hebrew, Cyrillic, Greek…); the fallback covers
 * Latin-1 and the direct-Unicode keysym range only. */
static int keysym_to_codepoint(KeySym ks) {
    if (fn.xkb_keysym_to_utf32 != NULL) {
        return (int) fn.xkb_keysym_to_utf32((uint32_t) ks);
    }
    if (ks >= 0x20 && ks <= 0xFF) return (int) ks;              /* Latin-1  */
    if ((ks & 0xFF000000UL) == 0x01000000UL) return (int) (ks & 0x00FFFFFF);
    return 0;
}

/* Scans XKB groups for a Latin keysym at shift level 0 so shortcuts land
 * on the right key under non-Latin layouts (Ctrl+C while typing Hebrew).
 * Falls back to the group-0 keysym. */
static KeySym vk_keysym_for(Display *dpy, KeyCode keycode) {
    for (unsigned group = 0; group < 4; group++) {
        KeySym ks = fn.XkbKeycodeToKeysym(dpy, keycode, group, 0);
        if (ks == NoSymbol) continue;
        if ((ks >= 'a' && ks <= 'z') || (ks >= 'A' && ks <= 'Z') ||
            (ks >= '0' && ks <= '9')) {
            return ks;
        }
    }
    KeySym base = fn.XkbKeycodeToKeysym(dpy, keycode, 0, 0);
    return base != NoSymbol ? base : 0;
}

static int wire_modifiers(unsigned state) {
    int mods = 0;
    if (state & ShiftMask)   mods |= WIRE_MOD_SHIFT;
    if (state & ControlMask) mods |= WIRE_MOD_CTRL;
    if (state & Mod1Mask)    mods |= WIRE_MOD_ALT;   /* Alt   */
    if (state & Mod4Mask)    mods |= WIRE_MOD_META;  /* Super */
    return mods;
}

static int wire_button(unsigned xbutton) {
    switch (xbutton) {
        case Button1: return WIRE_BUTTON_PRIMARY;
        case Button3: return WIRE_BUTTON_SECONDARY;
        default:      return WIRE_BUTTON_NONE;
    }
}

static int create_panel_window(Display *dpy, Panel *p) {
    Window root = DefaultRootWindow(dpy);
    XVisualInfo tmpl;
    memset(&tmpl, 0, sizeof(tmpl));
    tmpl.visualid = p->visual_id;
    int n = 0;
    XVisualInfo *vi = fn.XGetVisualInfo(dpy, VisualIDMask, &tmpl, &n);
    if (vi == NULL || n <= 0) {
        if (vi != NULL) fn.XFree(vi);
        DBG("event thread: visual 0x%lx not found\n", (unsigned long) p->visual_id);
        return 0;
    }
    Visual *visual = vi[0].visual;
    int depth = vi[0].depth;
    fn.XFree(vi);

    Colormap cmap = fn.XCreateColormap(dpy, root, visual, AllocNone);
    XSetWindowAttributes swa;
    memset(&swa, 0, sizeof(swa));
    swa.colormap = cmap;
    swa.border_pixel = 0;
    swa.background_pixel = 0;
    swa.override_redirect = True;
    unsigned w = p->w > 0 ? (unsigned) p->w : 1;
    unsigned h = p->h > 0 ? (unsigned) p->h : 1;
    Window win = fn.XCreateWindow(
        dpy, root, p->x, p->y, w, h, 0, depth, InputOutput, visual,
        CWColormap | CWBorderPixel | CWBackPixel | CWOverrideRedirect, &swa);
    if (win == 0) {
        fn.XFreeColormap(dpy, cmap);
        return 0;
    }
    if (fn.XStoreName != NULL) fn.XStoreName(dpy, win, "NucleusStandalonePopup");
    p->win = win;
    p->cmap = cmap;
    popup_xdnd_intern_atoms(dpy, p);
    popup_xdnd_set_aware(dpy, p);
    fn.XSync(dpy, False);
    DBG("event thread: window 0x%lx created (XdndAware)\n", (unsigned long) win);
    return 1;
}

/* ── Event thread ───────────────────────────────────────────────────────── */

static void forward_pointer(JNIEnv *env, Panel *p, int type, float x, float y,
                            int button, int mods) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->event_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_pointer_event == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_pointer_event, (jint) type,
                           (jfloat) x, (jfloat) y, (jint) button, (jint) mods);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void forward_scroll(JNIEnv *env, Panel *p, float x, float y,
                           float dx, float dy) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->event_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_scroll == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_scroll, (jfloat) x, (jfloat) y,
                           (jfloat) dx, (jfloat) dy);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void forward_key(JNIEnv *env, Panel *p, int type, int vk, int codepoint,
                        int mods) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->event_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_key_event == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_key_event, (jint) type, (jint) vk,
                           (jint) codepoint, (jint) mods);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void forward_outside_click(JNIEnv *env, Panel *p, int button) {
    pthread_mutex_lock(&p->lock);
    jobject cb = p->outside_cb;
    pthread_mutex_unlock(&p->lock);
    if (cb == NULL || g_on_outside_click == NULL) return;
    (*env)->CallVoidMethod(env, cb, g_on_outside_click, (jint) 1, (jint) button);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Raw XI2 ButtonPress: hit-test the pointer against the panel rect and
 * notify when the press landed outside while the panel is visible. */
static void handle_raw_press(JNIEnv *env, Display *dpy, Panel *p, int button) {
    pthread_mutex_lock(&p->lock);
    int visible = p->visible;
    int px = p->x, py = p->y, pw = p->w, ph = p->h;
    jobject cb = p->outside_cb;
    pthread_mutex_unlock(&p->lock);
    if (!visible || cb == NULL) return;

    Window root_ret, child_ret;
    int root_x = 0, root_y = 0, wx = 0, wy = 0;
    unsigned mask = 0;
    Window root = DefaultRootWindow(dpy);
    if (!fn.XQueryPointer(dpy, root, &root_ret, &child_ret, &root_x, &root_y,
                          &wx, &wy, &mask)) {
        return;
    }
    int inside = root_x >= px && root_x < px + pw &&
                 root_y >= py && root_y < py + ph;
    if (!inside) {
        int wire = button == 1 ? WIRE_BUTTON_PRIMARY
                 : button == 3 ? WIRE_BUTTON_SECONDARY
                 : 3; /* "other", matches the macOS monitor's encoding */
        forward_outside_click(env, p, wire);
    }
}

static void handle_key_event(JNIEnv *env, Display *dpy, Panel *p, XKeyEvent *ke,
                             int wire_type) {
    char buf[8];
    KeySym active_ks = NoSymbol;
    fn.XLookupString(ke, buf, sizeof(buf), &active_ks, NULL);
    int codepoint = active_ks != NoSymbol ? keysym_to_codepoint(active_ks) : 0;
    KeySym vk = vk_keysym_for(dpy, (KeyCode) ke->keycode);
    /* Non-printable actives (arrows, F-keys…) map to codepoint 0 upstream;
     * dispatchSyntheticKeyTyped filters control chars anyway. */
    forward_key(env, p, wire_type, (int) vk, codepoint, wire_modifiers(ke->state));
}

static void signal_ready(Panel *p, int ready) {
    pthread_mutex_lock(&p->lock);
    p->ready = ready;
    pthread_cond_signal(&p->ready_cond);
    pthread_mutex_unlock(&p->lock);
}

static void *event_thread_main(void *arg) {
    Panel *p = (Panel *) arg;

    Display *dpy = fn.XOpenDisplay(NULL);
    if (dpy == NULL) {
        signal_ready(p, -1);
        return NULL;
    }
    if (!create_panel_window(dpy, p)) {
        fn.XCloseDisplay(dpy);
        signal_ready(p, -1);
        return NULL;
    }

    /* Per-client event mask. We are the creating client, so mask-0
     * XDND ClientMessages land here too. SelectionNotify is always
     * delivered to the converting client (also us). */
    fn.XSelectInput(dpy, p->win,
                    ButtonPressMask | ButtonReleaseMask | PointerMotionMask |
                    KeyPressMask | KeyReleaseMask | StructureNotifyMask |
                    PropertyChangeMask);

    /* XI2 raw buttons on the root for the outside-click monitor. Selected
     * unconditionally (cheap); forwarding is gated on the Java listener. */
    int xi_opcode = -1;
    if (fn.XISelectEvents != NULL && fn.XIQueryVersion != NULL &&
        fn.XQueryExtension != NULL && fn.XGetEventData != NULL) {
        int ev_base = 0, err_base = 0;
        if (fn.XQueryExtension(dpy, "XInputExtension", &xi_opcode, &ev_base, &err_base)) {
            int maj = 2, min = 0;
            if (fn.XIQueryVersion(dpy, &maj, &min) == Success) {
                unsigned char mask_bits[XIMaskLen(XI_LASTEVENT)];
                memset(mask_bits, 0, sizeof(mask_bits));
                XISetMask(mask_bits, XI_RawButtonPress);
                XIEventMask evmask = {
                    .deviceid = XIAllMasterDevices,
                    .mask_len = sizeof(mask_bits),
                    .mask = mask_bits,
                };
                fn.XISelectEvents(dpy, DefaultRootWindow(dpy), &evmask, 1);
            } else {
                xi_opcode = -1;
            }
        } else {
            xi_opcode = -1;
        }
    }
    fn.XFlush(dpy);

    JNIEnv *env = attach_jvm_thread();
    /* Ready only after the window exists AND JNI is attached — callers
     * (including the XDND smoke source) may send ClientMessages immediately. */
    signal_ready(p, 1);

    struct pollfd fds[2] = {
        { .fd = ConnectionNumber(dpy), .events = POLLIN },
        { .fd = p->quit_pipe[0],       .events = POLLIN },
    };

    int running = 1;
    while (running) {
        /* Drain everything already buffered before blocking in poll —
         * Xlib reads whole batches off the socket, so poll() alone would
         * sleep on events sitting in the client-side queue. */
        while (fn.XPending(dpy) > 0) {
            XEvent ev;
            fn.XNextEvent(dpy, &ev);
            if (env == NULL) continue;

            switch (ev.type) {
                case ButtonPress: {
                    XButtonEvent *be = &ev.xbutton;
                    if (be->button >= Button4 && be->button <= 7) {
                        /* 4/5 = vertical wheel, 6/7 = horizontal. One line
                         * per click; sign matches the Compose convention
                         * (positive Y scrolls the content down). */
                        float dx = be->button == 6 ? -1.0f : be->button == 7 ? 1.0f : 0.0f;
                        float dy = be->button == Button4 ? -1.0f : be->button == Button5 ? 1.0f : 0.0f;
                        forward_scroll(env, p, (float) be->x, (float) be->y, dx, dy);
                        break;
                    }
                    pthread_mutex_lock(&p->lock);
                    int focusable = p->focusable;
                    pthread_mutex_unlock(&p->lock);
                    if (focusable) {
                        /* takeKeyboardFocus() equivalent: OR windows never
                         * receive focus from the WM, grab it explicitly. */
                        fn.XSetInputFocus(dpy, p->win, RevertToParent, be->time);
                    }
                    forward_pointer(env, p, WIRE_PTR_DOWN, (float) be->x, (float) be->y,
                                    wire_button(be->button), wire_modifiers(be->state));
                    break;
                }
                case ButtonRelease: {
                    XButtonEvent *be = &ev.xbutton;
                    if (be->button >= Button4 && be->button <= 7) break;
                    forward_pointer(env, p, WIRE_PTR_UP, (float) be->x, (float) be->y,
                                    wire_button(be->button), wire_modifiers(be->state));
                    break;
                }
                case MotionNotify: {
                    XMotionEvent *me = &ev.xmotion;
                    forward_pointer(env, p, WIRE_PTR_MOVE, (float) me->x, (float) me->y,
                                    WIRE_BUTTON_NONE, wire_modifiers(me->state));
                    break;
                }
                case KeyPress:
                    handle_key_event(env, dpy, p, &ev.xkey, WIRE_KEY_DOWN);
                    break;
                case KeyRelease:
                    handle_key_event(env, dpy, p, &ev.xkey, WIRE_KEY_UP);
                    break;
                case DestroyNotify:
                    if (ev.xdestroywindow.window == p->win) running = 0;
                    break;
                case ClientMessage:
                    popup_xdnd_on_client_message(dpy, env, p, &ev.xclient);
                    break;
                case SelectionNotify:
                    popup_xdnd_on_selection_notify(dpy, env, p, &ev.xselection);
                    break;
                case GenericEvent: {
                    XGenericEventCookie *cookie = &ev.xcookie;
                    if (xi_opcode >= 0 && cookie->extension == xi_opcode &&
                        cookie->evtype == XI_RawButtonPress &&
                        fn.XGetEventData(dpy, cookie)) {
                        XIRawEvent *raw = (XIRawEvent *) cookie->data;
                        handle_raw_press(env, dpy, p, raw->detail);
                        fn.XFreeEventData(dpy, cookie);
                    }
                    break;
                }
                default:
                    break;
            }
        }
        if (!running) break;

        if (poll(fds, 2, -1) < 0) continue;
        if (fds[1].revents & POLLIN) break; /* quit pipe */
    }

    /* We created the window + colormap on this connection. Default
     * close-down mode is DestroyAll, so XCloseDisplay would destroy them
     * — and the command thread's nativeRelease used to XDestroyWindow
     * afterwards, which is a fatal BadWindow. Destroy here, once. */
    if (p->win != 0) {
        fn.XDestroyWindow(dpy, p->win);
        p->win = 0;
    }
    if (p->cmap != 0) {
        fn.XFreeColormap(dpy, p->cmap);
        p->cmap = 0;
    }
    fn.XFlush(dpy);
    fn.XCloseDisplay(dpy);
    detach_jvm_thread();
    return NULL;
}

/* ── JNI exports ────────────────────────────────────────────────────────── */

EXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeIsAvailable(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    return ensure_cmd_display() != NULL ? JNI_TRUE : JNI_FALSE;
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeDisplayPtr(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    return (jlong) (uintptr_t) ensure_cmd_display();
}

/* Xft.dpi from the root resource database. X clients live in the X
 * coordinate space (logical under XWayland), so GDK's Wayland scale must
 * NOT be used for this panel — see TaoStandalonePopupHostLinux.
 *
 * Opens its own short-lived connection: TaoScreenGeometry routes here from
 * arbitrary threads (tray callbacks, Tao main) and the command connection
 * is single-thread-owned. */
EXPORT jfloat JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeScale(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    if (!ensure_libs_loaded() || fn.XResourceManagerString == NULL) return 1.0f;
    Display *dpy = fn.XOpenDisplay(NULL);
    if (dpy == NULL) return 1.0f;
    float scale = 1.0f;
    const char *rm = fn.XResourceManagerString(dpy);
    if (rm != NULL) {
        const char *entry = strstr(rm, "Xft.dpi:");
        if (entry != NULL) {
            double dpi = atof(entry + strlen("Xft.dpi:"));
            if (dpi >= 48.0 && dpi <= 480.0) scale = (float) (dpi / 96.0);
        }
    }
    fn.XCloseDisplay(dpy);
    return scale;
}

/* Reads one CARDINAL[] root property; returns the item count (0 on failure).
 * The returned pointer must be XFree'd by the caller. */
static unsigned long read_root_cardinals(Display *dpy, Window root,
                                         const char *name, long max_items,
                                         unsigned long **out_items) {
    *out_items = NULL;
    Atom atom = fn.XInternAtom(dpy, name, True);
    if (atom == None) return 0;
    Atom actual_type = None;
    int actual_format = 0;
    unsigned long nitems = 0, bytes_after = 0;
    unsigned char *prop = NULL;
    if (fn.XGetWindowProperty(dpy, root, atom, 0, max_items, False, XA_CARDINAL,
                              &actual_type, &actual_format, &nitems, &bytes_after,
                              &prop) != Success ||
        prop == NULL || actual_format != 32 || nitems == 0) {
        if (prop != NULL) fn.XFree(prop);
        return 0;
    }
    *out_items = (unsigned long *) prop;
    return nitems;
}

/*
 * Primary-monitor work area in X11 pixels, `[x, y, width, height]`:
 * the XRandR primary monitor (full screen without XRandR) intersected with
 * EWMH's `_NET_WORKAREA` for the current desktop (screen minus panels/docks
 * — Mutter maintains it on XWayland too). Backs
 * `TaoScreenGeometry.primaryMonitorWorkAreaPx` when no realized Tao window
 * exists (panel-only tray apps); the GDK path needs a window, this one
 * doesn't. Own short-lived connection — callable from any thread.
 */
EXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativePrimaryWorkArea(
    JNIEnv *env, jclass clazz)
{
    (void) clazz;
    if (!ensure_libs_loaded()) return NULL;
    Display *dpy = fn.XOpenDisplay(NULL);
    if (dpy == NULL) return NULL;
    Window root = DefaultRootWindow(dpy);

    long mon_x = 0, mon_y = 0;
    long mon_w = DisplayWidth(dpy, DefaultScreen(dpy));
    long mon_h = DisplayHeight(dpy, DefaultScreen(dpy));
    if (fn.XRRGetMonitors != NULL && fn.XRRFreeMonitors != NULL) {
        int nmon = 0;
        XRRMonitorInfo *mons = fn.XRRGetMonitors(dpy, root, True, &nmon);
        if (mons != NULL) {
            for (int i = 0; i < nmon; i++) {
                if (mons[i].primary || i == 0) {
                    mon_x = mons[i].x;
                    mon_y = mons[i].y;
                    mon_w = mons[i].width;
                    mon_h = mons[i].height;
                }
                if (mons[i].primary) break;
            }
            fn.XRRFreeMonitors(mons);
        }
    }

    /* _NET_WORKAREA holds 4 CARDINALs per desktop; pick the current one. */
    unsigned long desktop = 0;
    unsigned long *items = NULL;
    if (read_root_cardinals(dpy, root, "_NET_CURRENT_DESKTOP", 1, &items) >= 1) {
        desktop = items[0];
    }
    if (items != NULL) { fn.XFree(items); items = NULL; }

    long out_x = mon_x, out_y = mon_y, out_w = mon_w, out_h = mon_h;
    unsigned long nitems =
        read_root_cardinals(dpy, root, "_NET_WORKAREA", 4L * 64, &items);
    if (items != NULL) {
        if (nitems < (desktop + 1) * 4) desktop = 0;
        if (nitems >= (desktop + 1) * 4) {
            long wa_x = (long) items[desktop * 4];
            long wa_y = (long) items[desktop * 4 + 1];
            long wa_w = (long) items[desktop * 4 + 2];
            long wa_h = (long) items[desktop * 4 + 3];
            long left   = mon_x > wa_x ? mon_x : wa_x;
            long top    = mon_y > wa_y ? mon_y : wa_y;
            long right  = (mon_x + mon_w) < (wa_x + wa_w) ? (mon_x + mon_w) : (wa_x + wa_w);
            long bottom = (mon_y + mon_h) < (wa_y + wa_h) ? (mon_y + mon_h) : (wa_y + wa_h);
            if (right > left && bottom > top) {
                out_x = left;
                out_y = top;
                out_w = right - left;
                out_h = bottom - top;
            }
        }
        fn.XFree(items);
    }
    fn.XCloseDisplay(dpy);

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result == NULL) return NULL;
    jlong values[4] = { out_x, out_y, out_w, out_h };
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}

/* Picks the X visual for the panel from EGL's alpha-capable desktop-GL
 * configs so the later `nativeAttachX11` matches the exact same config.
 * Falls back to any 32-bit TrueColor visual, then to CopyFromParent. */
static Visual *choose_argb_visual(Display *dpy, int *out_depth) {
    *out_depth = 0;

    if (fn.eglInitialize != NULL && fn.eglChooseConfig != NULL &&
        fn.eglGetConfigAttrib != NULL && fn.eglBindAPI != NULL) {
        void *edpy = NULL;
        if (fn.eglGetPlatformDisplay != NULL) {
            edpy = fn.eglGetPlatformDisplay(0x31D5 /* EGL_PLATFORM_X11_KHR */, dpy, NULL);
        }
        if (edpy == NULL && fn.eglGetDisplay != NULL) {
            edpy = fn.eglGetDisplay(dpy);
        }
        int maj = 0, min = 0;
        if (edpy != NULL && fn.eglInitialize(edpy, &maj, &min) &&
            fn.eglBindAPI(0x30A2 /* EGL_OPENGL_API */)) {
            const int attrs[] = {
                0x3033, 0x0004,   /* EGL_SURFACE_TYPE,    EGL_WINDOW_BIT */
                0x3040, 0x0008,   /* EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT */
                0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, /* RGBA 8888 */
                0x3038            /* EGL_NONE */
            };
            void *cfgs[64];
            int ncfg = 0;
            if (fn.eglChooseConfig(edpy, attrs, cfgs, 64, &ncfg) && ncfg > 0) {
                for (int i = 0; i < ncfg; i++) {
                    int vid = 0;
                    fn.eglGetConfigAttrib(edpy, cfgs[i], 0x302E /* NATIVE_VISUAL_ID */, &vid);
                    if (vid == 0) continue;
                    XVisualInfo tmpl;
                    memset(&tmpl, 0, sizeof(tmpl));
                    tmpl.visualid = (VisualID) vid;
                    int n = 0;
                    XVisualInfo *vi = fn.XGetVisualInfo(dpy, VisualIDMask, &tmpl, &n);
                    if (vi != NULL && n > 0 && vi[0].depth == 32) {
                        Visual *v = vi[0].visual;
                        *out_depth = vi[0].depth;
                        fn.XFree(vi);
                        DBG("visual from EGL config: 0x%lx depth 32\n", (unsigned long) vid);
                        return v;
                    }
                    if (vi != NULL) fn.XFree(vi);
                }
            }
        }
    }

    XVisualInfo tmpl;
    memset(&tmpl, 0, sizeof(tmpl));
    tmpl.depth = 32;
    tmpl.class = TrueColor;
    int n = 0;
    XVisualInfo *vi = fn.XGetVisualInfo(dpy, VisualDepthMask | VisualClassMask, &tmpl, &n);
    if (vi != NULL && n > 0) {
        Visual *v = vi[0].visual;
        *out_depth = vi[0].depth;
        fn.XFree(vi);
        DBG("visual fallback: first ARGB32\n");
        return v;
    }
    if (vi != NULL) fn.XFree(vi);
    return NULL;
}

static void panel_free(JNIEnv *env, Panel *p) {
    if (p->quit_pipe[0] >= 0) close(p->quit_pipe[0]);
    if (p->quit_pipe[1] >= 0) close(p->quit_pipe[1]);
    if (env != NULL) {
        if (p->event_cb != NULL) (*env)->DeleteGlobalRef(env, p->event_cb);
        if (p->outside_cb != NULL) (*env)->DeleteGlobalRef(env, p->outside_cb);
        if (p->dnd_cb != NULL) (*env)->DeleteGlobalRef(env, p->dnd_cb);
    }
    pthread_cond_destroy(&p->ready_cond);
    pthread_mutex_destroy(&p->lock);
    free(p);
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeCreatePanel(
    JNIEnv *env, jclass clazz, jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void) clazz;
    Display *dpy = ensure_cmd_display();
    if (dpy == NULL) return 0;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);

    int depth = 0;
    Visual *visual = choose_argb_visual(dpy, &depth);
    if (visual == NULL) {
        DBG("no ARGB visual — compositor missing?\n");
        return 0;
    }

    Panel *p = (Panel *) calloc(1, sizeof(Panel));
    if (p == NULL) return 0;
    p->visual_id = visual->visualid;
    p->depth = depth;
    p->x = xPx;
    p->y = yPx;
    p->w = widthPx > 0 ? widthPx : 1;
    p->h = heightPx > 0 ? heightPx : 1;
    p->quit_pipe[0] = p->quit_pipe[1] = -1;
    pthread_mutex_init(&p->lock, NULL);
    pthread_cond_init(&p->ready_cond, NULL);
    if (pipe(p->quit_pipe) != 0) {
        p->quit_pipe[0] = p->quit_pipe[1] = -1;
    }

    if (pthread_create(&p->evt_thread, NULL, event_thread_main, p) != 0) {
        DBG("event thread creation failed\n");
        panel_free(env, p);
        return 0;
    }
    p->evt_thread_started = 1;

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 2;
    pthread_mutex_lock(&p->lock);
    while (p->ready == 0) {
        int rc = pthread_cond_timedwait(&p->ready_cond, &p->lock, &deadline);
        if (rc == ETIMEDOUT) break;
    }
    int ready = p->ready;
    Window win = p->win;
    pthread_mutex_unlock(&p->lock);

    if (ready != 1 || win == 0) {
        DBG("event thread failed to create window\n");
        if (p->quit_pipe[1] >= 0) {
            char one = 1;
            ssize_t ignored = write(p->quit_pipe[1], &one, 1);
            (void) ignored;
        }
        pthread_join(p->evt_thread, NULL);
        p->evt_thread_started = 0;
        panel_free(env, p);
        return 0;
    }

    DBG("panel created: win=0x%lx depth=%d visual=0x%lx\n",
        (unsigned long) win, depth, (unsigned long) p->visual_id);
    return (jlong) (uintptr_t) p;
}

EXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeWindowXid(
    JNIEnv *env, jclass clazz, jlong panel)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    return p != NULL ? (jlong) p->win : 0;
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSetFrameOnScreen(
    JNIEnv *env, jclass clazz, jlong panel, jint xPx, jint yPx, jint widthPx, jint heightPx)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL || dpy == NULL) return;
    unsigned w = widthPx > 0 ? (unsigned) widthPx : 1;
    unsigned h = heightPx > 0 ? (unsigned) heightPx : 1;
    fn.XMoveResizeWindow(dpy, p->win, xPx, yPx, w, h);
    fn.XFlush(dpy);
    pthread_mutex_lock(&p->lock);
    p->x = xPx;
    p->y = yPx;
    p->w = (int) w;
    p->h = (int) h;
    pthread_mutex_unlock(&p->lock);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSetPanelVisible(
    JNIEnv *env, jclass clazz, jlong panel, jboolean visible)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL || dpy == NULL) return;
    if (visible) {
        fn.XMapRaised(dpy, p->win);
    } else {
        fn.XUnmapWindow(dpy, p->win);
    }
    fn.XFlush(dpy);
    pthread_mutex_lock(&p->lock);
    p->visible = visible ? 1 : 0;
    pthread_mutex_unlock(&p->lock);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSetFocusable(
    JNIEnv *env, jclass clazz, jlong panel, jboolean focusable)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    pthread_mutex_lock(&p->lock);
    p->focusable = focusable ? 1 : 0;
    pthread_mutex_unlock(&p->lock);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSetPanelCursor(
    JNIEnv *env, jclass clazz, jlong panel, jint iconCode)
{
    (void) env; (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL || dpy == NULL) return;
    unsigned shape;
    switch (iconCode) {
        case ICON_TEXT:        shape = XC_xterm;              break;
        case ICON_HAND:        shape = XC_hand2;              break;
        case ICON_CROSSHAIR:   shape = XC_crosshair;          break;
        case ICON_WAIT:        shape = XC_watch;              break;
        case ICON_MOVE:        shape = XC_fleur;              break;
        case ICON_NOT_ALLOWED: shape = XC_X_cursor;           break;
        case ICON_HELP:        shape = XC_question_arrow;     break;
        case ICON_PROGRESS:    shape = XC_watch;              break;
        case ICON_EW_RESIZE:   shape = XC_sb_h_double_arrow;  break;
        case ICON_NS_RESIZE:   shape = XC_sb_v_double_arrow;  break;
        case ICON_NESW_RESIZE: shape = XC_bottom_left_corner; break;
        case ICON_NWSE_RESIZE: shape = XC_bottom_right_corner; break;
        default:               shape = XC_left_ptr;           break;
    }
    Cursor cursor = fn.XCreateFontCursor(dpy, shape);
    fn.XDefineCursor(dpy, p->win, cursor);
    fn.XFlush(dpy);
    if (p->cursor != None) fn.XFreeCursor(dpy, p->cursor);
    p->cursor = cursor;
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeSetEventCallback(
    JNIEnv *env, jclass clazz, jlong panel, jobject callback)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    jobject global = NULL;
    if (callback != NULL) {
        cache_event_callback_ids(env, callback);
        global = (*env)->NewGlobalRef(env, callback);
    }
    pthread_mutex_lock(&p->lock);
    jobject prev = p->event_cb;
    p->event_cb = global;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeInstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel, jobject listener)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL || listener == NULL) return;
    if (g_jvm == NULL) (*env)->GetJavaVM(env, &g_jvm);
    cache_outside_listener_id(env, listener);
    jobject global = (*env)->NewGlobalRef(env, listener);
    pthread_mutex_lock(&p->lock);
    jobject prev = p->outside_cb;
    p->outside_cb = global;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeUninstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    if (p == NULL) return;
    pthread_mutex_lock(&p->lock);
    jobject prev = p->outside_cb;
    p->outside_cb = NULL;
    pthread_mutex_unlock(&p->lock);
    if (prev != NULL) (*env)->DeleteGlobalRef(env, prev);
}

EXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_PopupNativeBridgeLinux_nativeRelease(
    JNIEnv *env, jclass clazz, jlong panel)
{
    (void) clazz;
    Panel *p = (Panel *) (uintptr_t) panel;
    Display *dpy = g_cmd_dpy;
    if (p == NULL) return;

    if (p->evt_thread_started) {
        if (p->quit_pipe[1] >= 0) {
            char one = 1;
            ssize_t ignored = write(p->quit_pipe[1], &one, 1);
            (void) ignored;
        }
        pthread_join(p->evt_thread, NULL);
    }
    if (p->quit_pipe[0] >= 0) close(p->quit_pipe[0]);
    if (p->quit_pipe[1] >= 0) close(p->quit_pipe[1]);

    pthread_mutex_lock(&p->lock);
    jobject event_cb = p->event_cb;
    jobject outside_cb = p->outside_cb;
    jobject dnd_cb = p->dnd_cb;
    p->event_cb = NULL;
    p->outside_cb = NULL;
    p->dnd_cb = NULL;
    pthread_mutex_unlock(&p->lock);
    if (event_cb != NULL) (*env)->DeleteGlobalRef(env, event_cb);
    if (outside_cb != NULL) (*env)->DeleteGlobalRef(env, outside_cb);
    if (dnd_cb != NULL) (*env)->DeleteGlobalRef(env, dnd_cb);

    if (dpy != NULL) {
        if (p->cursor != None) fn.XFreeCursor(dpy, p->cursor);
        /* Window + colormap are destroyed by the event thread (creator)
         * before it closes its Display. Touching them here is BadWindow. */
        fn.XFlush(dpy);
    }
    pthread_cond_destroy(&p->ready_cond);
    pthread_mutex_destroy(&p->lock);
    free(p);
}
