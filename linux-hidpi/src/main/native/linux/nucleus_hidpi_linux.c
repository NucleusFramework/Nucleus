/**
 * JNI bridge for Linux HiDPI scale factor detection.
 *
 * Replicates JetBrains Runtime's systemScale.c approach:
 * detects the native display scale factor from multiple sources so that
 * Compose Desktop applications can apply it via sun.java2d.uiScale before
 * AWT initialises, enabling correct rendering on high-DPI screens.
 *
 * Detection order (same priority as JBR):
 *   1. J2D_UISCALE   — explicit JVM override (env var)
 *   2. GSettings     — GNOME integer scaling via libgio (dlopen, no hard dep)
 *   3. GDK_SCALE     — GTK environment variable
 *   4. GDK_DPI_SCALE — GTK fractional DPI multiplier
 *   5. Xft.dpi       — X Resource Manager via libX11 (dlopen, no hard dep)
 *
 * All external libraries (libgio, libX11) are loaded at runtime via dlopen
 * so the .so itself has no hard link-time dependencies beyond libc/libdl.
 * Linked libraries: -ldl
 */

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
Java_dev_nucleusframework_nucleus_hidpi_HiDpiLinuxBridge_nativeGetScaleFactor(
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

    /* 3. GDK_SCALE — set by GNOME session / GTK apps */
    scale = readEnvDouble("GDK_SCALE");
    if (scale > 0.0) return (jdouble)scale;

    /* 4. GDK_DPI_SCALE — fractional DPI multiplier */
    scale = readEnvDouble("GDK_DPI_SCALE");
    if (scale > 0.0) return (jdouble)scale;

    /* 5. Xft.dpi from X Resource Manager */
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
Java_dev_nucleusframework_nucleus_hidpi_HiDpiLinuxBridge_nativeApplyScaleToEnv(
    JNIEnv *env, jclass clazz, jint scale)
{
    (void)env; (void)clazz;
    if (scale <= 1) return;

    char buf[16];
    snprintf(buf, sizeof(buf), "%d", (int)scale);

    /* 0 = don't overwrite if already set by the desktop session */
    setenv("GDK_SCALE", buf, 0);
}
