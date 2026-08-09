/**
 * JNI bridge for Linux energy efficiency mode and screen-awake (caffeine).
 *
 * Provides native implementations for:
 *   - Checking if Linux energy APIs are available (compile-time check)
 *   - Enabling efficiency mode (nice +19, timer slack 100ms, ioprio IDLE)
 *   - Disabling efficiency mode (restore defaults)
 *   - Awake (caffeine) via composite backend:
 *       1. GNOME SessionManager DBus Inhibit (session bus)
 *       2. freedesktop PowerManagement DBus Inhibit (session bus, SYSTEM_ONLY)
 *       3. systemd-logind DBus Inhibit (system bus)
 *       4. X11 XScreenSaverSuspend (via libXss, SYSTEM_AND_DISPLAY only)
 *
 * All native libraries (libdbus-1, libX11, libXss) are loaded at runtime
 * via dlopen() to avoid hard dependencies.
 *
 * No special privileges are required.
 *
 * Linked libraries: libc, libdl (automatic)
 */

#include <jni.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <errno.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

/* ---- ioprio constants -------------------------------------------- */

#define IOPRIO_WHO_PROCESS 1
#define IOPRIO_CLASS_IDLE  3
#define IOPRIO_CLASS_SHIFT 13

/* ---- nativeIsSupported ------------------------------------------- */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeIsSupported(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
#ifdef SYS_ioprio_set
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/* ---- nativeEnableEfficiencyMode ---------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeEnableEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int first_error = 0;

    /* 1. CPU nice +19 */
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, 19) != 0) {
        first_error = errno;
    }

    /* 2. Timer slack 100 ms (coalescing) */
    if (prctl(PR_SET_TIMERSLACK, (unsigned long)100000000L, 0, 0, 0) != 0) {
        if (first_error == 0) first_error = errno;
    }

    /* 3. I/O scheduling class IDLE */
#ifdef SYS_ioprio_set
    if (syscall(SYS_ioprio_set, IOPRIO_WHO_PROCESS, 0,
                (IOPRIO_CLASS_IDLE << IOPRIO_CLASS_SHIFT) | 0) != 0) {
        if (first_error == 0) first_error = errno;
    }
#endif

    return (jint)first_error;
}

/* ---- nativeEnableThreadEfficiencyMode ---------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeEnableThreadEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int first_error = 0;

    /* 1. CPU nice +19 (per-thread on Linux — each thread is a schedulable entity) */
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, 19) != 0) {
        first_error = errno;
    }

    /* 2. Timer slack 100 ms (always per-thread) */
    if (prctl(PR_SET_TIMERSLACK, (unsigned long)100000000L, 0, 0, 0) != 0) {
        if (first_error == 0) first_error = errno;
    }

    /* 3. I/O scheduling class IDLE (per-thread with IOPRIO_WHO_PROCESS + tid 0) */
#ifdef SYS_ioprio_set
    if (syscall(SYS_ioprio_set, IOPRIO_WHO_PROCESS, 0,
                (IOPRIO_CLASS_IDLE << IOPRIO_CLASS_SHIFT) | 0) != 0) {
        if (first_error == 0) first_error = errno;
    }
#endif

    return (jint)first_error;
}

/* ---- nativeDisableThreadEfficiencyMode --------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeDisableThreadEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int first_error = 0;

    /* 1. CPU nice reset to 0 — may fail with EACCES without CAP_SYS_NICE,
     *    which is expected: thread-level mode is meant to be used with
     *    withEfficiencyMode() where the thread is discarded afterward. */
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, 0) != 0 && errno != EACCES) {
        first_error = errno;
    }

    /* 2. Timer slack reset to thread default */
    if (prctl(PR_SET_TIMERSLACK, 0L, 0, 0, 0) != 0) {
        if (first_error == 0) first_error = errno;
    }

    /* 3. I/O scheduling class reset to default */
#ifdef SYS_ioprio_set
    if (syscall(SYS_ioprio_set, IOPRIO_WHO_PROCESS, 0, 0) != 0) {
        if (first_error == 0) first_error = errno;
    }
#endif

    return (jint)first_error;
}

/* ---- nativeEnableLightEfficiencyMode ----------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeEnableLightEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* CPU nice +10 only — no ioprio, no timer slack */
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, 10) != 0) {
        return (jint)errno;
    }
    return 0;
}

/* ---- nativeDisableLightEfficiencyMode ---------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeDisableLightEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    /* Reset nice to 0 — may fail with EACCES without CAP_SYS_NICE,
     * which is expected: unprivileged processes cannot lower their
     * nice value once raised. */
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, 0) != 0 && errno != EACCES) {
        return (jint)errno;
    }
    return 0;
}

/* ---- nativeDisableEfficiencyMode --------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeDisableEfficiencyMode(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int first_error = 0;

    /* 1. CPU nice reset to 0 */
    errno = 0;
    if (setpriority(PRIO_PROCESS, 0, 0) != 0) {
        first_error = errno;
    }

    /* 2. Timer slack reset to thread default */
    if (prctl(PR_SET_TIMERSLACK, 0L, 0, 0, 0) != 0) {
        if (first_error == 0) first_error = errno;
    }

    /* 3. I/O scheduling class reset to default */
#ifdef SYS_ioprio_set
    if (syscall(SYS_ioprio_set, IOPRIO_WHO_PROCESS, 0, 0) != 0) {
        if (first_error == 0) first_error = errno;
    }
#endif

    return (jint)first_error;
}

/* ==================================================================
 * Awake (caffeine) via composite backend
 *
 * Backends are tried in order, and which ones are eligible depends on
 * the requested mode:
 *
 *   SYSTEM_AND_DISPLAY           SYSTEM_ONLY
 *   ------------------           -----------
 *   1. GNOME (IDLE|SUSPEND)      1. GNOME (SUSPEND)
 *   2. logind (idle)             2. freedesktop PowerManagement
 *   3. X11 (XScreenSaverSuspend) 3. logind (idle)
 *
 * X11 XScreenSaverSuspend only holds off the screen saver, so it can
 * never satisfy a SYSTEM_ONLY request and is skipped there. Conversely
 * org.freedesktop.PowerManagement only inhibits automatic sleep and
 * leaves the screen saver alone, so it is only used for SYSTEM_ONLY.
 *
 * All libraries are loaded lazily via dlopen() so that the module
 * works even when some libraries are not installed.
 * ================================================================== */

/* Must match AwakeMode.nativeCode in LinuxEnergyManager.kt */
#define AWAKE_SYSTEM_AND_DISPLAY 0
#define AWAKE_SYSTEM_ONLY        1
#define AWAKE_NONE               (-1)

/* ---- Backend type tracking ---------------------------------------- */

enum awake_backend {
    BACKEND_NONE              = 0,
    BACKEND_GNOME             = 1,
    BACKEND_LOGIND            = 2,
    BACKEND_X11               = 3,
    BACKEND_POWER_MANAGEMENT  = 4
};

/* ---- DBus types and constants ------------------------------------- */

typedef void   DBusConnection;
typedef void   DBusMessage;
typedef unsigned int dbus_bool_t;
typedef uint32_t     dbus_uint32_t;

typedef struct {
    const char *name;
    const char *message;
    unsigned int dummy1;
    void *padding1;
} DBusError;

#define DBUS_BUS_SESSION    0
#define DBUS_BUS_SYSTEM     1
#define DBUS_TYPE_INVALID   0
#define DBUS_TYPE_STRING    ((int)'s')
#define DBUS_TYPE_UINT32    ((int)'u')
#define DBUS_TYPE_UNIX_FD   ((int)'h')
#define DBUS_TIMEOUT_MS     2000

/* ---- X11 types ---------------------------------------------------- */

typedef void* Display;

/*
 * One held awake request. Every backend fills in only the handles it
 * needs; the rest stay at their empty values. Keeping the whole request
 * in one value lets nativeKeepAwake() acquire the new request before
 * dropping the old one, so a mode switch never leaves a gap during which
 * the machine is free to sleep.
 *
 * Access is single-threaded from Kotlin (@Synchronized on the manager).
 */
typedef struct {
    enum awake_backend backend;
    int                mode;      /* AWAKE_* */
    DBusConnection    *conn;      /* GNOME, PowerManagement, logind */
    dbus_uint32_t      cookie;    /* GNOME, PowerManagement */
    int                fd;        /* logind */
    Display            display;   /* X11 */
} awake_request;

#define AWAKE_REQUEST_EMPTY { BACKEND_NONE, AWAKE_NONE, NULL, 0, -1, NULL }

static awake_request g_awake = AWAKE_REQUEST_EMPTY;

/** Human-readable reason, visible in `systemd-inhibit --list` and friends. */
static const char* awake_reason(int mode) {
    return (mode == AWAKE_SYSTEM_ONLY) ? "keepAwake(SYSTEM_ONLY)"
                                       : "keepAwake(SYSTEM_AND_DISPLAY)";
}

/* ---- DBus function pointers --------------------------------------- */

static void *g_libdbus = NULL;

typedef void            (*fn_dbus_error_init)(DBusError *);
typedef dbus_bool_t     (*fn_dbus_error_is_set)(const DBusError *);
typedef void            (*fn_dbus_error_free)(DBusError *);
typedef DBusConnection* (*fn_dbus_bus_get_private)(int, DBusError *);
typedef void            (*fn_dbus_connection_close)(DBusConnection *);
typedef void            (*fn_dbus_connection_unref)(DBusConnection *);
typedef void            (*fn_dbus_connection_set_exit_on_disconnect)(DBusConnection *, dbus_bool_t);
typedef DBusMessage*    (*fn_dbus_message_new_method_call)(const char *, const char *, const char *, const char *);
typedef void            (*fn_dbus_message_unref)(DBusMessage *);
typedef dbus_bool_t     (*fn_dbus_message_append_args)(DBusMessage *, int, ...);
typedef DBusMessage*    (*fn_dbus_connection_send_with_reply_and_block)(DBusConnection *, DBusMessage *, int, DBusError *);
typedef dbus_bool_t     (*fn_dbus_message_get_args)(DBusMessage *, DBusError *, int, ...);

static fn_dbus_error_init                           p_dbus_error_init;
static fn_dbus_error_is_set                         p_dbus_error_is_set;
static fn_dbus_error_free                           p_dbus_error_free;
static fn_dbus_bus_get_private                      p_dbus_bus_get_private;
static fn_dbus_connection_close                     p_dbus_connection_close;
static fn_dbus_connection_unref                     p_dbus_connection_unref;
static fn_dbus_connection_set_exit_on_disconnect    p_dbus_connection_set_exit_on_disconnect;
static fn_dbus_message_new_method_call              p_dbus_message_new_method_call;
static fn_dbus_message_unref                        p_dbus_message_unref;
static fn_dbus_message_append_args                  p_dbus_message_append_args;
static fn_dbus_connection_send_with_reply_and_block p_dbus_send_with_reply;
static fn_dbus_message_get_args                     p_dbus_message_get_args;

static int load_dbus(void) {
    if (g_libdbus) return 1;
    g_libdbus = dlopen("libdbus-1.so.3", RTLD_LAZY);
    if (!g_libdbus) g_libdbus = dlopen("libdbus-1.so", RTLD_LAZY);
    if (!g_libdbus) return 0;

    p_dbus_error_init       = (fn_dbus_error_init)      dlsym(g_libdbus, "dbus_error_init");
    p_dbus_error_is_set     = (fn_dbus_error_is_set)    dlsym(g_libdbus, "dbus_error_is_set");
    p_dbus_error_free       = (fn_dbus_error_free)      dlsym(g_libdbus, "dbus_error_free");
    p_dbus_bus_get_private  = (fn_dbus_bus_get_private)  dlsym(g_libdbus, "dbus_bus_get_private");
    p_dbus_connection_close = (fn_dbus_connection_close) dlsym(g_libdbus, "dbus_connection_close");
    p_dbus_connection_unref = (fn_dbus_connection_unref) dlsym(g_libdbus, "dbus_connection_unref");
    p_dbus_connection_set_exit_on_disconnect = (fn_dbus_connection_set_exit_on_disconnect)
        dlsym(g_libdbus, "dbus_connection_set_exit_on_disconnect");
    p_dbus_message_new_method_call = (fn_dbus_message_new_method_call) dlsym(g_libdbus, "dbus_message_new_method_call");
    p_dbus_message_unref    = (fn_dbus_message_unref)   dlsym(g_libdbus, "dbus_message_unref");
    p_dbus_message_append_args = (fn_dbus_message_append_args) dlsym(g_libdbus, "dbus_message_append_args");
    p_dbus_send_with_reply  = (fn_dbus_connection_send_with_reply_and_block) dlsym(g_libdbus, "dbus_connection_send_with_reply_and_block");
    p_dbus_message_get_args = (fn_dbus_message_get_args) dlsym(g_libdbus, "dbus_message_get_args");

    if (!p_dbus_error_init || !p_dbus_bus_get_private || !p_dbus_connection_close ||
        !p_dbus_message_new_method_call ||
        !p_dbus_message_append_args || !p_dbus_send_with_reply || !p_dbus_message_get_args) {
        dlclose(g_libdbus);
        g_libdbus = NULL;
        return 0;
    }
    return 1;
}

/*
 * Helper: open a private DBus connection and disable exit-on-disconnect.
 * Using dbus_bus_get_private() instead of dbus_bus_get() avoids sharing
 * the connection with the JVM (AT-SPI accessibility uses the session bus),
 * which prevents deadlocks when dbus_connection_send_with_reply_and_block()
 * dispatches pending messages on the shared connection.
 */
static DBusConnection* open_private_bus(int bus_type) {
    DBusError err;
    p_dbus_error_init(&err);

    DBusConnection *conn = p_dbus_bus_get_private(bus_type, &err);
    if (!conn) {
        if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
            p_dbus_error_free(&err);
        return NULL;
    }

    if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
        p_dbus_error_free(&err);

    /* Private connections call exit() on disconnect by default — disable that */
    if (p_dbus_connection_set_exit_on_disconnect)
        p_dbus_connection_set_exit_on_disconnect(conn, 0);

    return conn;
}

/*
 * Helper: close and unref a private DBus connection.
 * Private connections must be closed before unref.
 */
static void close_private_bus(DBusConnection *conn) {
    if (!conn) return;
    p_dbus_connection_close(conn);
    p_dbus_connection_unref(conn);
}

/* Helper: fire a method call that returns nothing we care about. */
static void send_and_discard(DBusConnection *conn, DBusMessage *msg) {
    DBusError err;
    p_dbus_error_init(&err);
    DBusMessage *reply = p_dbus_send_with_reply(conn, msg, DBUS_TIMEOUT_MS, &err);
    p_dbus_message_unref(msg);
    if (reply) p_dbus_message_unref(reply);
    if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
        p_dbus_error_free(&err);
}

/* ---- X11 function pointers ---------------------------------------- */

static void *g_libx11 = NULL;
static void *g_libxss = NULL;

typedef Display (*fn_XOpenDisplay)(const char *);
typedef int     (*fn_XCloseDisplay)(Display);
typedef int     (*fn_XFlush)(Display);
typedef void    (*fn_XScreenSaverSuspend)(Display, int);

static fn_XOpenDisplay          p_XOpenDisplay;
static fn_XCloseDisplay         p_XCloseDisplay;
static fn_XFlush                p_XFlush;
static fn_XScreenSaverSuspend   p_XScreenSaverSuspend;

static int load_x11(void) {
    if (g_libx11 && g_libxss) return 1;

    if (!g_libx11) {
        g_libx11 = dlopen("libX11.so.6", RTLD_LAZY);
        if (!g_libx11) g_libx11 = dlopen("libX11.so", RTLD_LAZY);
        if (!g_libx11) return 0;
    }

    p_XOpenDisplay  = (fn_XOpenDisplay)  dlsym(g_libx11, "XOpenDisplay");
    p_XCloseDisplay = (fn_XCloseDisplay) dlsym(g_libx11, "XCloseDisplay");
    p_XFlush        = (fn_XFlush)        dlsym(g_libx11, "XFlush");

    if (!p_XOpenDisplay || !p_XCloseDisplay || !p_XFlush) {
        dlclose(g_libx11);
        g_libx11 = NULL;
        return 0;
    }

    if (!g_libxss) {
        g_libxss = dlopen("libXss.so.1", RTLD_LAZY);
        if (!g_libxss) g_libxss = dlopen("libXss.so", RTLD_LAZY);
        if (!g_libxss) {
            dlclose(g_libx11);
            g_libx11 = NULL;
            return 0;
        }
    }

    p_XScreenSaverSuspend = (fn_XScreenSaverSuspend) dlsym(g_libxss, "XScreenSaverSuspend");
    if (!p_XScreenSaverSuspend) {
        dlclose(g_libxss); g_libxss = NULL;
        dlclose(g_libx11); g_libx11 = NULL;
        return 0;
    }

    return 1;
}

/* ---- GNOME SessionManager backend --------------------------------- */

#define GNOME_INHIBIT_IDLE    8
#define GNOME_INHIBIT_SUSPEND 4

/*
 * INHIBIT_SUSPEND stops gnome-session from suspending the machine on idle,
 * INHIBIT_IDLE additionally stops the session from going idle at all, which
 * is what keeps the screen saver and display sleep away. SYSTEM_ONLY takes
 * the former only.
 */
static int gnome_inhibit(int mode, awake_request *out) {
    if (!load_dbus()) return -1;

    DBusConnection *conn = open_private_bus(DBUS_BUS_SESSION);
    if (!conn) return -1;

    DBusMessage *msg = p_dbus_message_new_method_call(
        "org.gnome.SessionManager",
        "/org/gnome/SessionManager",
        "org.gnome.SessionManager",
        "Inhibit");
    if (!msg) {
        close_private_bus(conn);
        return -1;
    }

    const char *app_name = "Nucleus EnergyManager";
    dbus_uint32_t xid = 0;
    const char *reason = awake_reason(mode);
    dbus_uint32_t flags = (mode == AWAKE_SYSTEM_ONLY)
        ? GNOME_INHIBIT_SUSPEND
        : (GNOME_INHIBIT_IDLE | GNOME_INHIBIT_SUSPEND);

    p_dbus_message_append_args(msg,
        DBUS_TYPE_STRING, &app_name,
        DBUS_TYPE_UINT32, &xid,
        DBUS_TYPE_STRING, &reason,
        DBUS_TYPE_UINT32, &flags,
        DBUS_TYPE_INVALID);

    DBusError err;
    p_dbus_error_init(&err);

    DBusMessage *reply = p_dbus_send_with_reply(conn, msg, DBUS_TIMEOUT_MS, &err);
    p_dbus_message_unref(msg);

    if (!reply) {
        if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
            p_dbus_error_free(&err);
        close_private_bus(conn);
        return -1;
    }

    dbus_uint32_t cookie = 0;
    dbus_bool_t parsed = p_dbus_message_get_args(reply, &err,
        DBUS_TYPE_UINT32, &cookie,
        DBUS_TYPE_INVALID);
    p_dbus_message_unref(reply);

    if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
        p_dbus_error_free(&err);

    if (!parsed) {
        close_private_bus(conn);
        return -1;
    }

    out->backend = BACKEND_GNOME;
    out->mode    = mode;
    out->conn    = conn;
    out->cookie  = cookie;
    return 0;
}

static int gnome_uninhibit(awake_request *req) {
    DBusMessage *msg = p_dbus_message_new_method_call(
        "org.gnome.SessionManager",
        "/org/gnome/SessionManager",
        "org.gnome.SessionManager",
        "Uninhibit");
    if (!msg) {
        close_private_bus(req->conn);
        return -1;
    }

    p_dbus_message_append_args(msg,
        DBUS_TYPE_UINT32, &req->cookie,
        DBUS_TYPE_INVALID);

    send_and_discard(req->conn, msg);
    close_private_bus(req->conn);
    return 0;
}

/* ---- freedesktop PowerManagement backend -------------------------- */

/*
 * org.freedesktop.PowerManagement.Inhibit inhibits *automatic sleep* and
 * nothing else — the screen saver is a separate interface
 * (org.freedesktop.ScreenSaver). That maps exactly onto SYSTEM_ONLY, and
 * covers the desktops that do not implement org.gnome.SessionManager
 * (KDE/powerdevil, Xfce, MATE, Cinnamon).
 */
static int powermanagement_inhibit(int mode, awake_request *out) {
    if (!load_dbus()) return -1;

    DBusConnection *conn = open_private_bus(DBUS_BUS_SESSION);
    if (!conn) return -1;

    DBusMessage *msg = p_dbus_message_new_method_call(
        "org.freedesktop.PowerManagement",
        "/org/freedesktop/PowerManagement/Inhibit",
        "org.freedesktop.PowerManagement.Inhibit",
        "Inhibit");
    if (!msg) {
        close_private_bus(conn);
        return -1;
    }

    const char *app_name = "Nucleus EnergyManager";
    const char *reason = awake_reason(mode);

    p_dbus_message_append_args(msg,
        DBUS_TYPE_STRING, &app_name,
        DBUS_TYPE_STRING, &reason,
        DBUS_TYPE_INVALID);

    DBusError err;
    p_dbus_error_init(&err);

    DBusMessage *reply = p_dbus_send_with_reply(conn, msg, DBUS_TIMEOUT_MS, &err);
    p_dbus_message_unref(msg);

    if (!reply) {
        if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
            p_dbus_error_free(&err);
        close_private_bus(conn);
        return -1;
    }

    dbus_uint32_t cookie = 0;
    dbus_bool_t parsed = p_dbus_message_get_args(reply, &err,
        DBUS_TYPE_UINT32, &cookie,
        DBUS_TYPE_INVALID);
    p_dbus_message_unref(reply);

    if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
        p_dbus_error_free(&err);

    if (!parsed) {
        close_private_bus(conn);
        return -1;
    }

    out->backend = BACKEND_POWER_MANAGEMENT;
    out->mode    = mode;
    out->conn    = conn;
    out->cookie  = cookie;
    return 0;
}

static int powermanagement_uninhibit(awake_request *req) {
    DBusMessage *msg = p_dbus_message_new_method_call(
        "org.freedesktop.PowerManagement",
        "/org/freedesktop/PowerManagement/Inhibit",
        "org.freedesktop.PowerManagement.Inhibit",
        "UnInhibit");
    if (!msg) {
        close_private_bus(req->conn);
        return -1;
    }

    p_dbus_message_append_args(msg,
        DBUS_TYPE_UINT32, &req->cookie,
        DBUS_TYPE_INVALID);

    send_and_discard(req->conn, msg);
    close_private_bus(req->conn);
    return 0;
}

/* ---- systemd-logind backend --------------------------------------- */

/*
 * An "idle" inhibitor stops logind from running its IdleAction (typically
 * suspend) and does not touch the screen saver, so it serves both modes:
 * for SYSTEM_ONLY it is exactly right, for SYSTEM_AND_DISPLAY it is the
 * best a session-less fallback can do before X11 is tried.
 */
static int logind_inhibit(int mode, awake_request *out) {
    if (!load_dbus()) return -1;

    DBusConnection *conn = open_private_bus(DBUS_BUS_SYSTEM);
    if (!conn) return -1;

    DBusMessage *msg = p_dbus_message_new_method_call(
        "org.freedesktop.login1",
        "/org/freedesktop/login1",
        "org.freedesktop.login1.Manager",
        "Inhibit");
    if (!msg) {
        close_private_bus(conn);
        return -1;
    }

    const char *what = "idle";
    const char *who  = "Nucleus EnergyManager";
    const char *why  = awake_reason(mode);
    const char *inhibit_mode = "block";

    p_dbus_message_append_args(msg,
        DBUS_TYPE_STRING, &what,
        DBUS_TYPE_STRING, &who,
        DBUS_TYPE_STRING, &why,
        DBUS_TYPE_STRING, &inhibit_mode,
        DBUS_TYPE_INVALID);

    DBusError err;
    p_dbus_error_init(&err);

    DBusMessage *reply = p_dbus_send_with_reply(conn, msg, DBUS_TIMEOUT_MS, &err);
    p_dbus_message_unref(msg);

    if (!reply) {
        if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
            p_dbus_error_free(&err);
        close_private_bus(conn);
        return -1;
    }

    int fd = -1;
    dbus_bool_t parsed = p_dbus_message_get_args(reply, &err,
        DBUS_TYPE_UNIX_FD, &fd,
        DBUS_TYPE_INVALID);
    p_dbus_message_unref(reply);

    if (p_dbus_error_is_set && p_dbus_error_is_set(&err))
        p_dbus_error_free(&err);

    if (!parsed || fd < 0) {
        close_private_bus(conn);
        return -1;
    }

    out->backend = BACKEND_LOGIND;
    out->mode    = mode;
    out->conn    = conn;
    out->fd      = fd;
    return 0;
}

static int logind_uninhibit(awake_request *req) {
    if (req->fd >= 0) {
        close(req->fd);
        req->fd = -1;
    }
    close_private_bus(req->conn);
    return 0;
}

/* ---- X11 XScreenSaverSuspend backend ------------------------------ */

static int x11_inhibit(int mode, awake_request *out) {
    if (!load_x11()) return -1;

    Display display = p_XOpenDisplay(NULL);
    if (!display) return -1;

    p_XScreenSaverSuspend(display, 1);
    p_XFlush(display);

    out->backend = BACKEND_X11;
    out->mode    = mode;
    out->display = display;
    return 0;
}

static int x11_uninhibit(awake_request *req) {
    p_XScreenSaverSuspend(req->display, 0);
    p_XFlush(req->display);
    p_XCloseDisplay(req->display);
    req->display = NULL;
    return 0;
}

/* ---- Acquire / release dispatch ------------------------------------ */

static int awake_acquire(int mode, awake_request *out) {
    if (gnome_inhibit(mode, out) == 0) return 0;
    if (mode == AWAKE_SYSTEM_ONLY && powermanagement_inhibit(mode, out) == 0) return 0;
    if (logind_inhibit(mode, out) == 0) return 0;
    /* The screen saver alone never keeps the system awake */
    if (mode != AWAKE_SYSTEM_ONLY && x11_inhibit(mode, out) == 0) return 0;
    return -1;
}

static int awake_release(awake_request *req) {
    switch (req->backend) {
        case BACKEND_GNOME:            return gnome_uninhibit(req);
        case BACKEND_POWER_MANAGEMENT: return powermanagement_uninhibit(req);
        case BACKEND_LOGIND:           return logind_uninhibit(req);
        case BACKEND_X11:              return x11_uninhibit(req);
        case BACKEND_NONE:             return 0;
    }
    return 0;
}

/* ---- nativeKeepAwake ---------------------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeKeepAwake(
    JNIEnv *env, jclass clazz, jint mode)
{
    (void)env; (void)clazz;

    if (mode != AWAKE_SYSTEM_AND_DISPLAY && mode != AWAKE_SYSTEM_ONLY) {
        return EINVAL;
    }

    /* Same mode already held — idempotent */
    if (g_awake.backend != BACKEND_NONE && g_awake.mode == mode) {
        return 0;
    }

    /*
     * Acquire the new inhibitor before dropping the previous one, otherwise
     * the machine is briefly free to sleep in between.
     */
    awake_request next = AWAKE_REQUEST_EMPTY;
    if (awake_acquire(mode, &next) != 0) {
        return -1; /* No backend available */
    }

    if (g_awake.backend != BACKEND_NONE) {
        awake_release(&g_awake);
    }
    g_awake = next;
    return 0;
}

/* ---- nativeReleaseAwake ------------------------------------------- */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeReleaseAwake(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;

    if (g_awake.backend == BACKEND_NONE) {
        return 0; /* Nothing to release */
    }

    int rc = awake_release(&g_awake);
    awake_request empty = AWAKE_REQUEST_EMPTY;
    g_awake = empty;
    return (jint)rc;
}

/* ---- nativeIsAwakeActive ------------------------------------------ */

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeIsAwakeActive(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return g_awake.backend != BACKEND_NONE ? JNI_TRUE : JNI_FALSE;
}

/* ---- nativeQueryAwakeBackend -------------------------------------- */

/*
 * Which inhibitor is holding the current request (see enum awake_backend).
 * Exposed so that verification can query the right system service.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_energymanager_linux_NativeLinuxEnergyBridge_nativeQueryAwakeBackend(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)g_awake.backend;
}
