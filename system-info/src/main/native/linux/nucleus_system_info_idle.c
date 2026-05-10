// Idle time detection on Linux via X11 (XScreenSaverQueryInfo) and
// Wayland (D-Bus: Mutter IdleMonitor / freedesktop ScreenSaver).
// All external libraries are loaded at runtime via dlopen so that
// system-info does not acquire hard compile-time dependencies.

#include "nucleus_system_info_common.h"

#include <dlfcn.h>

// ---------------------------------------------------------------------------
// X11 / XScreenSaver types and function pointers (dlopen libXss + libX11)
// ---------------------------------------------------------------------------

typedef unsigned long XID;
typedef struct _XDisplay Display;
typedef XID Drawable;

typedef struct {
    int type;
    unsigned long serial;
    int send_event;
    Display *display;
    Drawable window;
    int state;
    int kind;
    unsigned long til_or_since;
    unsigned long idle;       // milliseconds
    unsigned long eventMask;
} XScreenSaverInfo;

typedef Display *(*pfn_XOpenDisplay)(const char *);
typedef int      (*pfn_XCloseDisplay)(Display *);
typedef XID      (*pfn_XDefaultRootWindow)(Display *);
typedef int      (*pfn_XScreenSaverQueryExtension)(Display *, int *, int *);
typedef int      (*pfn_XScreenSaverQueryInfo)(Display *, Drawable, XScreenSaverInfo *);
typedef XScreenSaverInfo *(*pfn_XScreenSaverAllocInfo)(void);
typedef int      (*pfn_XFree)(void *);

// ---------------------------------------------------------------------------
// GLib / GIO / GDBus types and function pointers (dlopen libgio-2.0)
// ---------------------------------------------------------------------------

typedef unsigned int   guint32;
typedef unsigned long  guint64;
typedef int            gint;
typedef void          *gpointer;

typedef struct _GError {
    guint32 domain;
    gint    code;
    char   *message;
} GError;

typedef struct _GVariant    GVariant;
typedef struct _GVariantType GVariantType;
typedef struct _GDBusConnection GDBusConnection;

typedef enum {
    G_BUS_TYPE_SESSION = 2,
} GBusType_e;

typedef enum {
    G_DBUS_CALL_FLAGS_NONE = 0,
} GDBusCallFlags_e;

typedef GDBusConnection *(*pfn_g_bus_get_sync)(gint, gpointer, GError **);
typedef GVariant *(*pfn_g_dbus_connection_call_sync)(
    GDBusConnection *, const char *, const char *, const char *,
    const char *, GVariant *, const GVariantType *,
    gint, gint, gpointer, GError **);
typedef void (*pfn_g_variant_get)(GVariant *, const char *, ...);
typedef void (*pfn_g_variant_unref)(GVariant *);
typedef void (*pfn_g_object_unref)(gpointer);
typedef void (*pfn_g_error_free)(GError *);
typedef const GVariantType *(*pfn_g_variant_type_checked_)(const char *);

// ---------------------------------------------------------------------------
// X11 idle time via XScreenSaverQueryInfo
// Returns milliseconds or -1 on failure.
// ---------------------------------------------------------------------------

static jlong getIdleTimeX11(void) {
    void *libX11 = dlopen("libX11.so.6", RTLD_LAZY | RTLD_LOCAL);
    if (!libX11) return -1;

    void *libXss = dlopen("libXss.so.1", RTLD_LAZY | RTLD_LOCAL);
    if (!libXss) { dlclose(libX11); return -1; }

    pfn_XOpenDisplay pXOpenDisplay =
        (pfn_XOpenDisplay)dlsym(libX11, "XOpenDisplay");
    pfn_XCloseDisplay pXCloseDisplay =
        (pfn_XCloseDisplay)dlsym(libX11, "XCloseDisplay");
    pfn_XDefaultRootWindow pXDefaultRootWindow =
        (pfn_XDefaultRootWindow)dlsym(libX11, "XDefaultRootWindow");
    pfn_XFree pXFree =
        (pfn_XFree)dlsym(libX11, "XFree");

    pfn_XScreenSaverQueryExtension pXSSQueryExt =
        (pfn_XScreenSaverQueryExtension)dlsym(libXss, "XScreenSaverQueryExtension");
    pfn_XScreenSaverQueryInfo pXSSQueryInfo =
        (pfn_XScreenSaverQueryInfo)dlsym(libXss, "XScreenSaverQueryInfo");
    pfn_XScreenSaverAllocInfo pXSSAllocInfo =
        (pfn_XScreenSaverAllocInfo)dlsym(libXss, "XScreenSaverAllocInfo");

    if (!pXOpenDisplay || !pXCloseDisplay || !pXDefaultRootWindow ||
        !pXFree || !pXSSQueryExt || !pXSSQueryInfo || !pXSSAllocInfo) {
        dlclose(libXss);
        dlclose(libX11);
        return -1;
    }

    jlong idleMs = -1;

    Display *dpy = pXOpenDisplay(NULL);
    if (dpy) {
        int event_base, error_base;
        if (pXSSQueryExt(dpy, &event_base, &error_base)) {
            XScreenSaverInfo *info = pXSSAllocInfo();
            if (info) {
                if (pXSSQueryInfo(dpy, pXDefaultRootWindow(dpy), info) != 0)
                    idleMs = (jlong)info->idle;
                pXFree(info);
            }
        }
        pXCloseDisplay(dpy);
    }

    dlclose(libXss);
    dlclose(libX11);
    return idleMs;
}

// ---------------------------------------------------------------------------
// GIO/GDBus helpers (loaded once per call)
// ---------------------------------------------------------------------------

typedef struct {
    void *lib;
    pfn_g_bus_get_sync               bus_get_sync;
    pfn_g_dbus_connection_call_sync  call_sync;
    pfn_g_variant_get                variant_get;
    pfn_g_variant_unref              variant_unref;
    pfn_g_object_unref               object_unref;
    pfn_g_error_free                 error_free;
    pfn_g_variant_type_checked_      variant_type_checked;
} GioFuncs;

static int loadGio(GioFuncs *f) {
    f->lib = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!f->lib) return 0;

    // GDBus functions live in libgio, but g_variant_* and g_object_unref
    // live in libglib/libgobject which libgio pulls in transitively.
    f->bus_get_sync        = (pfn_g_bus_get_sync)dlsym(f->lib, "g_bus_get_sync");
    f->call_sync           = (pfn_g_dbus_connection_call_sync)dlsym(f->lib, "g_dbus_connection_call_sync");
    f->variant_get         = (pfn_g_variant_get)dlsym(f->lib, "g_variant_get");
    f->variant_unref       = (pfn_g_variant_unref)dlsym(f->lib, "g_variant_unref");
    f->object_unref        = (pfn_g_object_unref)dlsym(f->lib, "g_object_unref");
    f->error_free          = (pfn_g_error_free)dlsym(f->lib, "g_error_free");
    f->variant_type_checked = (pfn_g_variant_type_checked_)dlsym(f->lib, "g_variant_type_checked_");

    if (!f->bus_get_sync || !f->call_sync || !f->variant_get ||
        !f->variant_unref || !f->object_unref || !f->error_free ||
        !f->variant_type_checked) {
        dlclose(f->lib);
        f->lib = NULL;
        return 0;
    }
    return 1;
}

static void unloadGio(GioFuncs *f) {
    if (f->lib) { dlclose(f->lib); f->lib = NULL; }
}

// ---------------------------------------------------------------------------
// GNOME/Mutter idle time via D-Bus org.gnome.Mutter.IdleMonitor
// GetIdletime() returns uint64 milliseconds.
// ---------------------------------------------------------------------------

static jlong getIdleTimeMutterDBus(GioFuncs *f) {
    GError *error = NULL;
    GDBusConnection *conn = f->bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (!conn) {
        if (error) f->error_free(error);
        return -1;
    }

    GVariant *result = f->call_sync(conn,
        "org.gnome.Mutter.IdleMonitor",
        "/org/gnome/Mutter/IdleMonitor/Core",
        "org.gnome.Mutter.IdleMonitor",
        "GetIdletime", NULL,
        f->variant_type_checked("(t)"),
        G_DBUS_CALL_FLAGS_NONE, 1000, NULL, &error);

    jlong idleMs = -1;
    if (result) {
        guint64 idle = 0;
        f->variant_get(result, "(t)", &idle);
        idleMs = (jlong)idle;
        f->variant_unref(result);
    } else if (error) {
        f->error_free(error);
    }

    f->object_unref(conn);
    return idleMs;
}

// ---------------------------------------------------------------------------
// KDE/freedesktop idle time via D-Bus org.freedesktop.ScreenSaver
// GetSessionIdleTime() returns uint32 seconds.
// ---------------------------------------------------------------------------

static jlong getIdleTimeFreedesktopDBus(GioFuncs *f) {
    GError *error = NULL;
    GDBusConnection *conn = f->bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (!conn) {
        if (error) f->error_free(error);
        return -1;
    }

    GVariant *result = f->call_sync(conn,
        "org.freedesktop.ScreenSaver",
        "/org/freedesktop/ScreenSaver",
        "org.freedesktop.ScreenSaver",
        "GetSessionIdleTime", NULL,
        f->variant_type_checked("(u)"),
        G_DBUS_CALL_FLAGS_NONE, 1000, NULL, &error);

    jlong idleMs = -1;
    if (result) {
        guint32 idleSec = 0;
        f->variant_get(result, "(u)", &idleSec);
        idleMs = (jlong)idleSec * 1000LL;
        f->variant_unref(result);
    } else if (error) {
        f->error_free(error);
    }

    f->object_unref(conn);
    return idleMs;
}

// ---------------------------------------------------------------------------
// JNI entry point — auto-detects X11 / Wayland and returns idle seconds.
// ---------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeIdleTimeSeconds(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    const char *sessionType    = getenv("XDG_SESSION_TYPE");
    const char *waylandDisplay = getenv("WAYLAND_DISPLAY");
    const char *x11Display     = getenv("DISPLAY");

    int isWayland = (sessionType && strcmp(sessionType, "wayland") == 0)
                 || (waylandDisplay && waylandDisplay[0] != '\0');

    jlong idleMs = -1;

    if (isWayland) {
        // Wayland session: try D-Bus first, then XWayland fallback
        GioFuncs gio = {0};
        if (loadGio(&gio)) {
            idleMs = getIdleTimeMutterDBus(&gio);       // GNOME
            if (idleMs < 0)
                idleMs = getIdleTimeFreedesktopDBus(&gio); // KDE
            unloadGio(&gio);
        }
        if (idleMs < 0 && x11Display && x11Display[0] != '\0')
            idleMs = getIdleTimeX11();                   // XWayland fallback
    } else {
        // X11 or unknown session
        if (x11Display && x11Display[0] != '\0')
            idleMs = getIdleTimeX11();
        if (idleMs < 0) {
            GioFuncs gio = {0};
            if (loadGio(&gio)) {
                idleMs = getIdleTimeMutterDBus(&gio);
                if (idleMs < 0)
                    idleMs = getIdleTimeFreedesktopDBus(&gio);
                unloadGio(&gio);
            }
        }
    }

    if (idleMs < 0) return (jlong)-1;
    return idleMs / 1000LL;
}
