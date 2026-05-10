// Network connectivity and metered status detection on Linux.
// Uses xdg-desktop-portal NetworkMonitor D-Bus API:
//   Bus:       org.freedesktop.portal.Desktop
//   Path:      /org/freedesktop/portal/desktop
//   Interface: org.freedesktop.portal.NetworkMonitor
//
// Methods used:
//   GetAvailable() -> (b)   — true if a default route exists
//   GetMetered()   -> (b)   — true if connection is metered
//
// All external libraries loaded at runtime via dlopen (no hard deps).

#include "nucleus_system_info_common.h"

#include <dlfcn.h>

// GLib / GIO / GDBus types and function pointers (same pattern as idle.c)

typedef unsigned int   guint32;
typedef int            gint;
typedef int            gboolean;
typedef void          *gpointer;

typedef struct _GError {
    guint32 domain;
    gint    code;
    char   *message;
} GError;

typedef struct _GVariant        GVariant;
typedef struct _GVariantType    GVariantType;
typedef struct _GDBusConnection GDBusConnection;

typedef enum { G_BUS_TYPE_SESSION = 2 } GBusType_e;
typedef enum { G_DBUS_CALL_FLAGS_NONE = 0 } GDBusCallFlags_e;

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

static int loadGioConn(GioFuncs *f) {
    f->lib = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!f->lib) return 0;

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

static void unloadGioConn(GioFuncs *f) {
    if (f->lib) { dlclose(f->lib); f->lib = NULL; }
}

#define PORTAL_BUS   "org.freedesktop.portal.Desktop"
#define PORTAL_PATH  "/org/freedesktop/portal/desktop"
#define PORTAL_IFACE "org.freedesktop.portal.NetworkMonitor"

// ---------------------------------------------------------------------------
// GetAvailable() -> (b)
// ---------------------------------------------------------------------------

static int portalGetAvailable(GioFuncs *f, GDBusConnection *conn) {
    GError *error = NULL;
    GVariant *result = f->call_sync(conn,
        PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE,
        "GetAvailable", NULL,
        f->variant_type_checked("(b)"),
        G_DBUS_CALL_FLAGS_NONE, 2000, NULL, &error);

    if (!result) {
        if (error) f->error_free(error);
        return -1; // error
    }

    gboolean available = 0;
    f->variant_get(result, "(b)", &available);
    f->variant_unref(result);
    return available ? 1 : 0;
}

// ---------------------------------------------------------------------------
// GetMetered() -> (b)
// ---------------------------------------------------------------------------

static int portalGetMetered(GioFuncs *f, GDBusConnection *conn) {
    GError *error = NULL;
    GVariant *result = f->call_sync(conn,
        PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE,
        "GetMetered", NULL,
        f->variant_type_checked("(b)"),
        G_DBUS_CALL_FLAGS_NONE, 2000, NULL, &error);

    if (!result) {
        if (error) f->error_free(error);
        return -1; // error
    }

    gboolean metered = 0;
    f->variant_get(result, "(b)", &metered);
    f->variant_unref(result);
    return metered ? 1 : 0;
}

// ---------------------------------------------------------------------------
// JNI: nativeIsNetworkConnected
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeIsNetworkConnected(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    GioFuncs gio = {0};
    if (!loadGioConn(&gio)) return JNI_FALSE;

    GError *error = NULL;
    GDBusConnection *conn = gio.bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (!conn) {
        if (error) gio.error_free(error);
        unloadGioConn(&gio);
        return JNI_FALSE;
    }

    int available = portalGetAvailable(&gio, conn);
    gio.object_unref(conn);
    unloadGioConn(&gio);

    return (available == 1) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// JNI: nativeGetMeteredStatus
// Returns: 0 = UNKNOWN, 1 = UNMETERED, 2 = METERED
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGetMeteredStatus(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;

    GioFuncs gio = {0};
    if (!loadGioConn(&gio)) return 0;

    GError *error = NULL;
    GDBusConnection *conn = gio.bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (!conn) {
        if (error) gio.error_free(error);
        unloadGioConn(&gio);
        return 0;
    }

    int metered = portalGetMetered(&gio, conn);
    gio.object_unref(conn);
    unloadGioConn(&gio);

    if (metered < 0) return 0; // UNKNOWN
    return metered ? 2 : 1;    // METERED or UNMETERED
}
