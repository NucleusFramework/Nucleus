/**
 * JNI bridge for Linux startup setup: HiDPI scale factor detection, and the
 * glibc malloc arena cap (nativeSetMallocArenaMax, at the end of this file).
 *
 * Replicates JetBrains Runtime's systemScale.c approach:
 * detects the native display scale factor from multiple sources so that
 * Compose Desktop applications can apply it via sun.java2d.uiScale before
 * AWT initialises, enabling correct rendering on high-DPI screens.
 *
 * Detection order (same priority as JBR):
 *   1. J2D_UISCALE   — explicit JVM override (env var)
 *   2. GSettings     — GNOME integer scaling via libgio (dlopen, no hard dep)
 *   3. Mutter DBus   — GNOME fractional scaling via libgio GDBus (dlopen)
 *   4. GDK_SCALE     — GTK environment variable
 *   5. GDK_DPI_SCALE — GTK fractional DPI multiplier
 *   6. Xft.dpi       — X Resource Manager via libX11 (dlopen, no hard dep)
 *
 * All external libraries (libgio, libX11) are loaded at runtime via dlopen
 * so the .so itself has no hard link-time dependencies beyond libc/libdl.
 * Linked libraries: -ldl
 */

#define _GNU_SOURCE /* RTLD_DEFAULT */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

/* ------------------------------------------------------------------ */
/*  Minimal type stubs (avoid hard dependency on X11/GLib headers)     */
/* ------------------------------------------------------------------ */

/* XrmValue as defined in X11/Xresource.h */
typedef struct {
    unsigned int size;
    void        *addr;
} MyXrmValue;

/* ------------------------------------------------------------------ */
/*  readEnvDouble — parse a positive double from an env variable       */
/* ------------------------------------------------------------------ */
static double readEnvDouble(const char *name) {
    const char *val = getenv(name);
    if (!val || val[0] == '\0') return 0.0;
    char *end;
    double d = strtod(val, &end);
    return (end != val && d > 0.0) ? d : 0.0;
}

/* ------------------------------------------------------------------ */
/*  readGnomeScaleFactor                                               */
/*  Queries org.gnome.desktop.interface → scaling-factor via libgio.  */
/*  Uses dlopen so we have no hard link-time dependency on GLib.       */
/* ------------------------------------------------------------------ */
static double readGnomeScaleFactor(void) {
    void *libgio = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!libgio) return 0.0;

    typedef void* (*fn_schema_source_get_default)(void);
    typedef void* (*fn_schema_source_lookup)(void*, const char*, int);
    typedef void* (*fn_settings_new)(const char*);
    typedef unsigned int (*fn_settings_get_uint)(void*, const char*);
    typedef void (*fn_object_unref)(void*);

    fn_schema_source_get_default gssg =
        (fn_schema_source_get_default)dlsym(libgio, "g_settings_schema_source_get_default");
    fn_schema_source_lookup gssl =
        (fn_schema_source_lookup)dlsym(libgio, "g_settings_schema_source_lookup");
    fn_settings_new gsn =
        (fn_settings_new)dlsym(libgio, "g_settings_new");
    fn_settings_get_uint gsgu =
        (fn_settings_get_uint)dlsym(libgio, "g_settings_get_uint");
    fn_object_unref gou =
        (fn_object_unref)dlsym(libgio, "g_object_unref");

    double scale = 0.0;

    if (gssg && gssl && gsn && gsgu && gou) {
        void *source = gssg();
        if (source) {
            /*
             * Guard with g_settings_schema_source_lookup before calling
             * g_settings_new(): the latter aborts the process if the schema
             * is missing.
             */
            void *schema = gssl(source, "org.gnome.desktop.interface", 1 /* recursive */);
            if (schema) {
                void *settings = gsn("org.gnome.desktop.interface");
                if (settings) {
                    unsigned int val = gsgu(settings, "scaling-factor");
                    if (val > 0) scale = (double)val;
                    gou(settings);
                }
            }
        }
    }

    dlclose(libgio);
    return scale;
}

/* ------------------------------------------------------------------ */
/*  readGnomeMutterScale                                               */
/*  Queries the active logical-monitor scale from                      */
/*  org.gnome.Mutter.DisplayConfig via libgio's GDBus client           */
/*  (dlopened, same pattern as readGnomeScaleFactor). This is the      */
/*  only source of truth for GNOME fractional scaling (1.25, 1.5,      */
/*  5/3, …); on such sessions GSettings scaling-factor stays at 0.     */
/*                                                                     */
/*  Returns the scale of the logical monitor flagged primary, or the   */
/*  first non-zero scale if none is marked primary, or 0.0 on any      */
/*  failure (service missing, not on GNOME, dbus denied, parse error). */
/* ------------------------------------------------------------------ */
static double readGnomeMutterScale(void) {
    void *libgio = dlopen("libgio-2.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!libgio) return 0.0;

    /* GBusType enum: SESSION = 2 */
    typedef void *(*fn_bus_get)(int bus_type, void *cancellable, void **error);
    typedef void *(*fn_dbus_call)(void *conn, const char *bus_name,
                                  const char *obj_path, const char *iface,
                                  const char *method, void *params,
                                  void *reply_type, int flags, int timeout,
                                  void *cancellable, void **error);
    typedef void *(*fn_var_child)(void *variant, size_t index);
    typedef size_t (*fn_var_n)(void *variant);
    typedef double (*fn_var_dbl)(void *variant);
    typedef int (*fn_var_bool)(void *variant);
    typedef void (*fn_unref)(void *ptr);
    typedef void (*fn_error_free)(void *error);

    fn_bus_get     gbgs = (fn_bus_get)     dlsym(libgio, "g_bus_get_sync");
    fn_dbus_call   gcs  = (fn_dbus_call)   dlsym(libgio, "g_dbus_connection_call_sync");
    fn_var_child   gvcv = (fn_var_child)   dlsym(libgio, "g_variant_get_child_value");
    fn_var_n       gvnc = (fn_var_n)       dlsym(libgio, "g_variant_n_children");
    fn_var_dbl     gvgd = (fn_var_dbl)     dlsym(libgio, "g_variant_get_double");
    fn_var_bool    gvgb = (fn_var_bool)    dlsym(libgio, "g_variant_get_boolean");
    fn_unref       gvu  = (fn_unref)       dlsym(libgio, "g_variant_unref");
    fn_unref       gou  = (fn_unref)       dlsym(libgio, "g_object_unref");
    fn_error_free  gef  = (fn_error_free)  dlsym(libgio, "g_error_free");

    double scale = 0.0;

    if (gbgs && gcs && gvcv && gvnc && gvgd && gvgb && gvu && gou) {
        void *error = NULL;
        void *conn = gbgs(2 /* G_BUS_TYPE_SESSION */, NULL, &error);
        if (conn && !error) {
            void *reply = gcs(
                conn,
                "org.gnome.Mutter.DisplayConfig",
                "/org/gnome/Mutter/DisplayConfig",
                "org.gnome.Mutter.DisplayConfig",
                "GetCurrentState",
                NULL /* parameters */,
                NULL /* reply_type */,
                0    /* G_DBUS_CALL_FLAGS_NONE */,
                2000 /* 2 s timeout */,
                NULL /* cancellable */,
                &error);
            if (reply && !error) {
                /*
                 * Reply is the 4-tuple
                 *   (uint32 serial, monitors, logical_monitors, properties)
                 * Logical-monitor entries have the shape
                 *   (int32 x, int32 y, double scale,
                 *    uint32 transform, bool primary,
                 *    array<monitor_spec>, dict properties)
                 * We want index 2 (scale) of whichever entry is primary.
                 */
                void *logical = gvcv(reply, 2);
                if (logical) {
                    size_t n = gvnc(logical);
                    double first = 0.0;
                    for (size_t i = 0; i < n; i++) {
                        void *lm = gvcv(logical, i);
                        if (!lm) continue;
                        void *sc_v = gvcv(lm, 2);
                        void *pr_v = gvcv(lm, 4);
                        double s = sc_v ? gvgd(sc_v) : 0.0;
                        int prim = pr_v ? gvgb(pr_v) : 0;
                        if (sc_v) gvu(sc_v);
                        if (pr_v) gvu(pr_v);
                        gvu(lm);
                        if (s > 0.0) {
                            if (prim) { scale = s; break; }
                            if (first == 0.0) first = s;
                        }
                    }
                    if (scale <= 0.0) scale = first;
                    gvu(logical);
                }
                gvu(reply);
            } else if (error && gef) {
                gef(error);
                error = NULL;
            }
            gou(conn);
        } else if (error && gef) {
            gef(error);
        }
    }

    dlclose(libgio);
    return scale;
}

/* ------------------------------------------------------------------ */
/*  readXftScale                                                       */
/*  Reads Xft.dpi from the X11 Resource Manager via dlopen(libX11).  */
/*  No hard link-time dependency on libX11.                            */
/* ------------------------------------------------------------------ */
static double readXftScale(void) {
    void *libx11 = dlopen("libX11.so.6", RTLD_LAZY | RTLD_LOCAL);
    if (!libx11) return 0.0;

    typedef void* (*fn_XOpenDisplay)(const char*);
    typedef char* (*fn_XResourceManagerString)(void*);
    typedef int   (*fn_XCloseDisplay)(void*);
    typedef void  (*fn_XrmInitialize)(void);
    typedef void* (*fn_XrmGetStringDatabase)(const char*);
    typedef int   (*fn_XrmGetResource)(void*, const char*, const char*, char**, MyXrmValue*);
    typedef void  (*fn_XrmDestroyDatabase)(void*);

    fn_XOpenDisplay         fOpen   = (fn_XOpenDisplay)dlsym(libx11, "XOpenDisplay");
    fn_XResourceManagerString fRm   = (fn_XResourceManagerString)dlsym(libx11, "XResourceManagerString");
    fn_XCloseDisplay        fClose  = (fn_XCloseDisplay)dlsym(libx11, "XCloseDisplay");
    fn_XrmInitialize        fRmInit = (fn_XrmInitialize)dlsym(libx11, "XrmInitialize");
    fn_XrmGetStringDatabase fRmDb   = (fn_XrmGetStringDatabase)dlsym(libx11, "XrmGetStringDatabase");
    fn_XrmGetResource       fRmGet  = (fn_XrmGetResource)dlsym(libx11, "XrmGetResource");
    fn_XrmDestroyDatabase   fRmDel  = (fn_XrmDestroyDatabase)dlsym(libx11, "XrmDestroyDatabase");

    if (!fOpen || !fRm || !fClose || !fRmInit || !fRmDb || !fRmGet || !fRmDel) {
        dlclose(libx11);
        return 0.0;
    }

    double scale = 0.0;
    void *dpy = fOpen(NULL);
    if (dpy) {
        char *rm = fRm(dpy);
        if (rm) {
            fRmInit();
            void *db = fRmDb(rm);
            if (db) {
                MyXrmValue value = { 0, NULL };
                char *type = NULL;
                if (fRmGet(db, "Xft.dpi", "Xft.Dpi", &type, &value) && value.addr) {
                    char *end;
                    double dpi = strtod((char *)value.addr, &end);
                    if (end != (char *)value.addr && dpi >= 96.0) {
                        scale = dpi / 96.0;
                    }
                }
                fRmDel(db);
            }
        }
        fClose(dpy);
    }

    dlclose(libx11);
    return scale;
}

/* ------------------------------------------------------------------ */
/*  nativeGetScaleFactor — JNI entry point                            */
/* ------------------------------------------------------------------ */
JNIEXPORT jdouble JNICALL
Java_dev_nucleusframework_hidpi_LinuxStartupBridge_nativeGetScaleFactor(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    double scale;

    /* 1. Explicit JVM override — highest priority */
    scale = readEnvDouble("J2D_UISCALE");
    if (scale > 0.0) return (jdouble)scale;

    /* 2. GNOME GSettings integer scaling */
    scale = readGnomeScaleFactor();
    if (scale > 0.0) return (jdouble)scale;

    /* 3. GNOME fractional scaling via Mutter DBus. This is the only
     *    source on Wayland sessions that use scale-monitor-framebuffer,
     *    where GSettings scaling-factor is 0 and the GDK_* vars are unset. */
    scale = readGnomeMutterScale();
    if (scale > 0.0) return (jdouble)scale;

    /* 4. GDK_SCALE — set by GNOME session / GTK apps */
    scale = readEnvDouble("GDK_SCALE");
    if (scale > 0.0) return (jdouble)scale;

    /* 5. GDK_DPI_SCALE — fractional DPI multiplier */
    scale = readEnvDouble("GDK_DPI_SCALE");
    if (scale > 0.0) return (jdouble)scale;

    /* 6. Xft.dpi from X Resource Manager */
    scale = readXftScale();
    if (scale > 0.0) return (jdouble)scale;

    return 0.0; /* not detected; let the JVM use its own detection */
}

/* ------------------------------------------------------------------ */
/*  nativeApplyScaleToEnv                                              */
/*  Sets GDK_SCALE in the process environment so that the JDK's       */
/*  native X11GraphicsDevice.getNativeScaleFactor() detects the scale  */
/*  through the standard path (not the debug sun.java2d.uiScale path). */
/*  This ensures both rendering AND mouse event coordinates are        */
/*  properly scaled by the JDK's XWindow.scaleDown() calls.            */
/*  Uses setenv(..., 0) to avoid overriding a value already set        */
/*  by the desktop session.                                            */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_hidpi_LinuxStartupBridge_nativeApplyScaleToEnv(
    JNIEnv *env, jclass clazz, jint scale)
{
    (void)env; (void)clazz;
    if (scale <= 1) return;

    char buf[16];
    snprintf(buf, sizeof(buf), "%d", (int)scale);

    /* 0 = don't overwrite if already set by the desktop session */
    setenv("GDK_SCALE", buf, 0);
}

/* ------------------------------------------------------------------ */
/*  nativeSetMallocArenaMax                                            */
/*  Caps glibc's malloc arenas (mallopt(M_ARENA_MAX)). glibc creates   */
/*  up to 8 x cores of them and each keeps what it once held, so a     */
/*  many-threaded process ends up with far more RSS than it uses       */
/*  (#757). Resolved through dlsym and gated on a glibc-only symbol:   */
/*  musl has no arenas and a stub mallopt.                             */
/*  Returns 1 when applied, 0 when mallopt refused it, -1 off glibc.   */
/* ------------------------------------------------------------------ */
#define NUCLEUS_M_ARENA_MAX (-8) /* M_ARENA_MAX from glibc's malloc.h */

typedef int (*MalloptFn)(int, int);

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_hidpi_LinuxStartupBridge_nativeSetMallocArenaMax(
    JNIEnv *env, jclass clazz, jint max)
{
    (void)env; (void)clazz;
    if (dlsym(RTLD_DEFAULT, "gnu_get_libc_version") == NULL) return -1;

    MalloptFn mallopt_fn = (MalloptFn)dlsym(RTLD_DEFAULT, "mallopt");
    if (mallopt_fn == NULL) return -1;

    return mallopt_fn(NUCLEUS_M_ARENA_MAX, (int)max) == 1 ? 1 : 0;
}
