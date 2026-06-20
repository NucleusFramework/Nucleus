/**
 * Linux global hotkey JNI bridge.
 *
 * Two backends selected at runtime:
 *   - X11:    XGrabKey / XUngrabKey on root window (X11 sessions)
 *   - Portal: org.freedesktop.portal.GlobalShortcuts D-Bus portal (Wayland)
 *
 * IMPORTANT Wayland/portal constraints:
 *   - All D-Bus work runs on a dedicated GLib thread (same GMainContext).
 *   - The portal thread stays permanently attached to the JVM.
 *   - GNOME requires the app_id to pass g_application_id_is_valid (reverse-DNS).
 *   - BindShortcuts sends the full shortcut set each time (portal API design).
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>

#include <X11/Xlib.h>
#include <X11/keysym.h>
#include <X11/XKBlib.h>

#include <gio/gio.h>

#include "nucleus_hotkey_keys.h"

/* ------------------------------------------------------------------ */
/* Constants                                                           */
/* ------------------------------------------------------------------ */

#define MAX_HOTKEYS 256

typedef enum { BACKEND_NONE = 0, BACKEND_X11, BACKEND_PORTAL } BackendType;

/* ------------------------------------------------------------------ */
/* Hotkey entry                                                        */
/* ------------------------------------------------------------------ */

typedef struct {
    jlong id;
    int   keyCode;
    int   modifiers;
    /* X11 */
    unsigned int x11_keycode;
    unsigned int x11_modifiers;
    /* Portal */
    char shortcut_id[32];
    char description[256];   /* user-readable action label for the portal dialog */
} HotKeyEntry;

/* ------------------------------------------------------------------ */
/* Global state                                                        */
/* ------------------------------------------------------------------ */

static JavaVM      *g_jvm            = NULL;
static jclass       g_bridgeClass    = NULL;
static jmethodID    g_onHotKeyMethod = NULL;

static BackendType  g_backend  = BACKEND_NONE;
static volatile int g_running  = 0;
static pthread_t    g_thread;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static HotKeyEntry g_hotkeys[MAX_HOTKEYS];
static int         g_hotkeyCount = 0;

/* X11 */
static Display *g_display    = NULL;
static Window   g_rootWindow = 0;

/* Portal */
static GDBusConnection *g_dbus_conn     = NULL;
static GMainContext     *g_portal_ctx    = NULL;
static GMainLoop        *g_portal_loop   = NULL;
static char             *g_session_handle = NULL;
static guint             g_activated_sub  = 0;
static JNIEnv           *g_portal_env    = NULL;   /* permanently attached */

/* Portal init handshake */
static pthread_mutex_t g_pinit_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_pinit_cond  = PTHREAD_COND_INITIALIZER;
static volatile int    g_pinit_done  = 0;
static volatile int    g_pinit_result = 0;
static char            g_pinit_error[512] = {0};

/* Monotonic counter for unique D-Bus token/path generation */
static int             g_session_seq = 0;

/* Portal request/response sync (used only on the portal thread) */
static volatile int    g_resp_done = 0;
static volatile guint32 g_resp_code = 99;

/* Portal bind request (any thread → portal thread, blocking) */
typedef struct {
    volatile int done;
    volatile int result;
    char         error[512];
    pthread_mutex_t mutex;
    pthread_cond_t  cond;
} BindRequest;

/* ------------------------------------------------------------------ */
/* JNI callback                                                        */
/* ------------------------------------------------------------------ */

static void fireHotKey(jlong id, jint keyCode, jint modifiers) {
    if (!g_jvm || !g_bridgeClass || !g_onHotKeyMethod) return;

    JNIEnv *env = NULL;
    int didAttach = 0;
    jint st = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (st == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return;
        didAttach = 1;
    } else if (st != JNI_OK) return;

    (*env)->CallStaticVoidMethod(env, g_bridgeClass, g_onHotKeyMethod, id, keyCode, modifiers);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (didAttach) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* Portal thread uses the permanently-attached env */
static void fireHotKeyPortal(jlong id, jint keyCode, jint modifiers) {
    if (!g_portal_env || !g_bridgeClass || !g_onHotKeyMethod) return;
    (*g_portal_env)->CallStaticVoidMethod(g_portal_env, g_bridgeClass, g_onHotKeyMethod,
                                          id, keyCode, modifiers);
    if ((*g_portal_env)->ExceptionCheck(g_portal_env))
        (*g_portal_env)->ExceptionClear(g_portal_env);
}

/* awtToKeySym() and buildTrigger() live in nucleus_hotkey_keys.h so the
 * native unit test can exercise the trigger format directly. */

/* ================================================================== */
/* X11 backend                                                         */
/* ================================================================== */

static unsigned int toX11Mods(int m) {
    unsigned int x = 0;
    if (m & MOD_ALT)     x |= Mod1Mask;
    if (m & MOD_CONTROL) x |= ControlMask;
    if (m & MOD_SHIFT)   x |= ShiftMask;
    if (m & MOD_META)    x |= Mod4Mask;
    return x;
}

static const unsigned int LOCKS[] = {
    0, LockMask, Mod2Mask, Mod3Mask,
    LockMask|Mod2Mask, LockMask|Mod3Mask, Mod2Mask|Mod3Mask,
    LockMask|Mod2Mask|Mod3Mask, Mod5Mask,
    LockMask|Mod5Mask, Mod2Mask|Mod5Mask, Mod3Mask|Mod5Mask,
    LockMask|Mod2Mask|Mod5Mask, LockMask|Mod3Mask|Mod5Mask,
    Mod2Mask|Mod3Mask|Mod5Mask, LockMask|Mod2Mask|Mod3Mask|Mod5Mask,
};
#define NUM_LOCKS (sizeof(LOCKS)/sizeof(LOCKS[0]))

static int  x11err = 0;
static int  x11errH(Display *d, XErrorEvent *e) { (void)d;(void)e; x11err=1; return 0; }

static int x11_grab(HotKeyEntry *e) {
    x11err = 0;
    XSetErrorHandler(x11errH);
    XSync(g_display, False);
    for (int i = 0; i < (int)NUM_LOCKS; i++)
        XGrabKey(g_display, e->x11_keycode, e->x11_modifiers|LOCKS[i],
                 g_rootWindow, True, GrabModeAsync, GrabModeAsync);
    XSync(g_display, False);
    XSetErrorHandler(NULL);
    return x11err ? -1 : 0;
}

static void x11_ungrab(HotKeyEntry *e) {
    for (int i = 0; i < (int)NUM_LOCKS; i++)
        XUngrabKey(g_display, e->x11_keycode, e->x11_modifiers|LOCKS[i], g_rootWindow);
    XSync(g_display, False);
}

#define X11_MODMASK (ShiftMask|ControlMask|Mod1Mask|Mod4Mask)

static void *x11_loop(void *arg) {
    (void)arg;
    XEvent ev;
    while (g_running) {
        while (XPending(g_display) > 0) {
            XNextEvent(g_display, &ev);
            if (ev.type != KeyPress) continue;
            unsigned int kc = ev.xkey.keycode, st = ev.xkey.state & X11_MODMASK;
            pthread_mutex_lock(&g_mutex);
            for (int i = 0; i < g_hotkeyCount; i++) {
                if (g_hotkeys[i].x11_keycode == kc && g_hotkeys[i].x11_modifiers == st) {
                    jlong id = g_hotkeys[i].id;
                    jint k = g_hotkeys[i].keyCode, m = g_hotkeys[i].modifiers;
                    pthread_mutex_unlock(&g_mutex);
                    fireHotKey(id, k, m);
                    goto next;
                }
            }
            pthread_mutex_unlock(&g_mutex);
            next:;
        }
        usleep(10000);
    }
    return NULL;
}

/* ================================================================== */
/* Portal backend                                                      */
/* ================================================================== */

#define PORTAL_BUS   "org.freedesktop.portal.Desktop"
#define PORTAL_PATH  "/org/freedesktop/portal/desktop"
#define PORTAL_IFACE "org.freedesktop.portal.GlobalShortcuts"
#define REQ_IFACE    "org.freedesktop.portal.Request"

/* Build sender part: ":1.42" → "1_42" */
static void senderPart(char *out, int sz) {
    const gchar *u = g_dbus_connection_get_unique_name(g_dbus_conn);
    int j = 0;
    for (int i = (u[0]==':') ? 1 : 0; u[i] && j < sz-1; i++)
        out[j++] = (u[i]=='.') ? '_' : u[i];
    out[j] = '\0';
}

/* Response signal handler (runs on portal thread) */
static void on_response(GDBusConnection *c, const gchar *s, const gchar *p,
                         const gchar *ifc, const gchar *sig, GVariant *params, gpointer ud) {
    (void)c;(void)s;(void)p;(void)ifc;(void)sig;(void)ud;
    guint32 code = 99;
    g_variant_get(params, "(u@a{sv})", &code, NULL);
    g_resp_code = code;
    g_resp_done = 1;
}

/* Activated signal handler (runs on portal thread) */
static void on_activated(GDBusConnection *c, const gchar *s, const gchar *p,
                          const gchar *ifc, const gchar *sig, GVariant *params, gpointer ud) {
    (void)c;(void)s;(void)p;(void)ifc;(void)sig;(void)ud;
    const gchar *sh = NULL, *sid = NULL;
    guint64 ts = 0;
    GVariant *opts = NULL;
    g_variant_get(params, "(&o&st@a{sv})", &sh, &sid, &ts, &opts);
    if (opts) g_variant_unref(opts);
    if (!sid) return;

    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < g_hotkeyCount; i++) {
        if (strcmp(g_hotkeys[i].shortcut_id, sid) == 0) {
            jlong id = g_hotkeys[i].id;
            jint kc = g_hotkeys[i].keyCode, m = g_hotkeys[i].modifiers;
            pthread_mutex_unlock(&g_mutex);
            fireHotKeyPortal(id, kc, m);
            return;
        }
    }
    pthread_mutex_unlock(&g_mutex);
}

/**
 * Subscribe to Response, call a portal method, poll for the response.
 * MUST be called on the portal thread (g_portal_ctx is thread-default).
 * Returns 0 on success, -1 on error. Writes detail to err_buf.
 */
static int portal_call(const char *method, GVariant *params,
                        const char *token, char *err_buf, int err_sz) {
    char sp[256]; senderPart(sp, sizeof(sp));
    char rp[512];
    snprintf(rp, sizeof(rp), "/org/freedesktop/portal/desktop/request/%s/%s", sp, token);

    g_resp_done = 0;
    g_resp_code = 99;

    guint sub = g_dbus_connection_signal_subscribe(
        g_dbus_conn, PORTAL_BUS, REQ_IFACE, "Response",
        rp, NULL, G_DBUS_SIGNAL_FLAGS_NO_MATCH_RULE,
        on_response, NULL, NULL);

    GError *err = NULL;
    GVariant *r = g_dbus_connection_call_sync(
        g_dbus_conn, PORTAL_BUS, PORTAL_PATH, PORTAL_IFACE,
        method, params, G_VARIANT_TYPE("(o)"),
        G_DBUS_CALL_FLAGS_NONE, 30000, NULL, &err);

    if (err) {
        snprintf(err_buf, err_sz, "%s: %s", method, err->message);
        g_error_free(err);
        g_dbus_connection_signal_unsubscribe(g_dbus_conn, sub);
        return -1;
    }
    if (r) g_variant_unref(r);

    /* Poll the GMainContext for the Response signal */
    for (int t = 0; t < 3000 && !g_resp_done; t++) {
        while (g_main_context_iteration(g_portal_ctx, FALSE)) {}
        usleep(10000);
    }

    g_dbus_connection_signal_unsubscribe(g_dbus_conn, sub);

    if (!g_resp_done) {
        snprintf(err_buf, err_sz, "%s timed out (30s)", method);
        return -1;
    }
    if (g_resp_code != 0) {
        snprintf(err_buf, err_sz,
                 "%s rejected (code %u). "
                 "On GNOME the .desktop filename must be reverse-DNS "
                 "(e.g. com.example.App.desktop) and the app must be launched from it.",
                 method, g_resp_code);
        return -1;
    }
    return 0;
}

/* Close the existing portal session (if any) and create a fresh one.
 * GNOME only allows one BindShortcuts call per session, so we recreate
 * the session before every BindShortcuts to work around this restriction.
 * MUST be called on the portal thread (g_portal_ctx is thread-default). */
static int portal_recreate_session(char *err_buf, int err_sz) {
    if (g_session_handle) {
        GError *ce = NULL;
        g_dbus_connection_call_sync(
            g_dbus_conn, PORTAL_BUS, g_session_handle,
            "org.freedesktop.portal.Session", "Close",
            NULL, NULL, G_DBUS_CALL_FLAGS_NONE, 1000, NULL, &ce);
        if (ce) g_error_free(ce); /* ignore close errors */
        free(g_session_handle);
        g_session_handle = NULL;
    }

    g_session_seq++;
    char csess_tok[64], sess_tok[64];
    snprintf(csess_tok, sizeof(csess_tok), "ncleus_cs%d", g_session_seq);
    snprintf(sess_tok,  sizeof(sess_tok),  "ncleus_s%d",  g_session_seq);

    GVariantBuilder opts;
    g_variant_builder_init(&opts, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&opts, "{sv}", "handle_token",         g_variant_new_string(csess_tok));
    g_variant_builder_add(&opts, "{sv}", "session_handle_token", g_variant_new_string(sess_tok));

    int ret = portal_call("CreateSession", g_variant_new("(a{sv})", &opts),
                          csess_tok, err_buf, err_sz);
    if (ret != 0) return -1;

    char sp[256]; senderPart(sp, sizeof(sp));
    char sess[512];
    snprintf(sess, sizeof(sess),
             "/org/freedesktop/portal/desktop/session/%s/%s", sp, sess_tok);
    g_session_handle = strdup(sess);
    return 0;
}

/* Build and call BindShortcuts for all registered hotkeys.
 * Recreates the session first — GNOME rejects a second BindShortcuts on the
 * same session with "Session already has bound shortcuts" (response code 2).
 * MUST be called on the portal thread. */
static int portal_bind(char *err_buf, int err_sz) {
    if (portal_recreate_session(err_buf, err_sz) != 0) return -1;

    GVariantBuilder shortcuts;
    g_variant_builder_init(&shortcuts, G_VARIANT_TYPE("a(sa{sv})"));

    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < g_hotkeyCount; i++) {
        GVariantBuilder props;
        g_variant_builder_init(&props, G_VARIANT_TYPE("a{sv}"));

        char trigger[128];
        buildTrigger(trigger, sizeof(trigger), g_hotkeys[i].modifiers, g_hotkeys[i].keyCode);

        /* description is a user-readable action label, NOT the key combination
         * (Freedesktop Shortcuts spec). Fall back to the trigger only when the
         * caller did not supply one, so the portal still has a non-empty label. */
        const char *desc = g_hotkeys[i].description[0] ? g_hotkeys[i].description
                                                       : (trigger[0] ? trigger : "Shortcut");
        g_variant_builder_add(&props, "{sv}", "description", g_variant_new_string(desc));
        if (trigger[0])
            g_variant_builder_add(&props, "{sv}", "preferred_trigger", g_variant_new_string(trigger));
        g_variant_builder_add(&shortcuts, "(sa{sv})", g_hotkeys[i].shortcut_id, &props);
    }
    pthread_mutex_unlock(&g_mutex);

    g_session_seq++;
    char bind_tok[64];
    snprintf(bind_tok, sizeof(bind_tok), "ncleus_b%d", g_session_seq);

    GVariantBuilder opts;
    g_variant_builder_init(&opts, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&opts, "{sv}", "handle_token", g_variant_new_string(bind_tok));

    return portal_call("BindShortcuts",
        g_variant_new("(oa(sa{sv})sa{sv})", g_session_handle, &shortcuts, "", &opts),
        bind_tok, err_buf, err_sz);
}

/* Invoked on the portal thread via g_main_context_invoke */
static gboolean portal_bind_on_thread(gpointer data) {
    BindRequest *req = data;
    req->result = portal_bind(req->error, sizeof(req->error));
    pthread_mutex_lock(&req->mutex);
    req->done = 1;
    pthread_cond_signal(&req->cond);
    pthread_mutex_unlock(&req->mutex);
    return G_SOURCE_REMOVE;
}

/* Posts bind work to the portal thread and blocks until it completes.
 * Callers MUST NOT be the portal thread itself. */
static int portal_bind_sync(char *err_buf, int err_sz) {
    BindRequest req = { .done = 0, .result = -1, .error = {0} };
    pthread_mutex_init(&req.mutex, NULL);
    pthread_cond_init(&req.cond, NULL);

    g_main_context_invoke(g_portal_ctx, portal_bind_on_thread, &req);

    pthread_mutex_lock(&req.mutex);
    while (!req.done) pthread_cond_wait(&req.cond, &req.mutex);
    pthread_mutex_unlock(&req.mutex);

    if (req.result != 0 && err_buf)
        snprintf(err_buf, err_sz, "%s", req.error);

    pthread_mutex_destroy(&req.mutex);
    pthread_cond_destroy(&req.cond);
    return req.result;
}

/* Portal thread entry: attach JVM, subscribe signals, run main loop.
 * Session creation is deferred to the first portal_bind() call. */
static void *portal_thread(void *arg) {
    (void)arg;

    /* Permanently attach to JVM — portal callbacks use g_portal_env directly */
    if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&g_portal_env, NULL) != JNI_OK) {
        snprintf(g_pinit_error, sizeof(g_pinit_error), "Failed to attach portal thread to JVM");
        pthread_mutex_lock(&g_pinit_mutex);
        g_pinit_result = -1; g_pinit_done = 1;
        pthread_cond_signal(&g_pinit_cond);
        pthread_mutex_unlock(&g_pinit_mutex);
        return NULL;
    }

    g_portal_ctx = g_main_context_new();
    g_main_context_push_thread_default(g_portal_ctx);

    /* Subscribe to Activated (and Deactivated) signals once — survives session recreation */
    g_activated_sub = g_dbus_connection_signal_subscribe(
        g_dbus_conn, PORTAL_BUS, PORTAL_IFACE, "Activated",
        PORTAL_PATH, NULL, G_DBUS_SIGNAL_FLAGS_NONE,
        on_activated, NULL, NULL);

    g_portal_loop = g_main_loop_new(g_portal_ctx, FALSE);

    /* Signal init success — session created lazily on first register */
    pthread_mutex_lock(&g_pinit_mutex);
    g_pinit_result = 0; g_pinit_done = 1;
    pthread_cond_signal(&g_pinit_cond);
    pthread_mutex_unlock(&g_pinit_mutex);

    /* Run main loop: dispatches Activated signals + bind requests */
    g_main_loop_run(g_portal_loop);

    g_main_context_pop_thread_default(g_portal_ctx);
    (*g_jvm)->DetachCurrentThread(g_jvm);
    g_portal_env = NULL;
    return NULL;
}

static int portal_init(void) {
    GError *err = NULL;
    g_dbus_conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &err);
    if (err) {
        snprintf(g_pinit_error, sizeof(g_pinit_error), "D-Bus connect: %s", err->message);
        g_error_free(err);
        return -1;
    }

    g_pinit_done = 0;
    g_pinit_result = 0;
    g_running = 1;

    if (pthread_create(&g_thread, NULL, portal_thread, NULL) != 0) {
        g_running = 0;
        g_dbus_conn = NULL;
        return -1;
    }

    /* Wait for portal thread to finish init */
    pthread_mutex_lock(&g_pinit_mutex);
    while (!g_pinit_done) pthread_cond_wait(&g_pinit_cond, &g_pinit_mutex);
    int result = g_pinit_result;
    pthread_mutex_unlock(&g_pinit_mutex);

    if (result != 0) {
        g_running = 0;
        if (g_portal_loop) g_main_loop_quit(g_portal_loop);
        pthread_join(g_thread, NULL);
        if (g_portal_loop) { g_main_loop_unref(g_portal_loop); g_portal_loop = NULL; }
        if (g_portal_ctx)  { g_main_context_unref(g_portal_ctx); g_portal_ctx = NULL; }
        g_dbus_conn = NULL;
    }
    return result;
}

static void portal_shutdown(void) {
    if (g_activated_sub > 0) {
        g_dbus_connection_signal_unsubscribe(g_dbus_conn, g_activated_sub);
        g_activated_sub = 0;
    }
    if (g_portal_loop) g_main_loop_quit(g_portal_loop);
    pthread_join(g_thread, NULL);
    if (g_portal_loop) { g_main_loop_unref(g_portal_loop); g_portal_loop = NULL; }
    if (g_portal_ctx)  { g_main_context_unref(g_portal_ctx); g_portal_ctx = NULL; }
    free(g_session_handle); g_session_handle = NULL;
    g_dbus_conn = NULL;
}

/* ================================================================== */
/* Backend detection                                                   */
/* ================================================================== */

static BackendType detectBackend(void) {
    const char *st = getenv("XDG_SESSION_TYPE");
    const char *wd = getenv("WAYLAND_DISPLAY");
    if ((st && strcmp(st, "wayland") == 0) || wd) return BACKEND_PORTAL;
    return BACKEND_X11;
}

/* ================================================================== */
/* JNI exports                                                         */
/* ================================================================== */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_globalhotkey_linux_NativeLinuxHotKeyBridge_nativeInit(
    JNIEnv *env, jclass clazz) {

    if (g_running) return NULL;

    g_bridgeClass = (*env)->NewGlobalRef(env, clazz);
    g_onHotKeyMethod = (*env)->GetStaticMethodID(env, clazz, "onHotKey", "(JII)V");
    if (!g_onHotKeyMethod) {
        (*env)->DeleteGlobalRef(env, g_bridgeClass); g_bridgeClass = NULL;
        return (*env)->NewStringUTF(env, "onHotKey method not found");
    }

    g_backend = detectBackend();

    if (g_backend == BACKEND_X11) {
        g_display = XOpenDisplay(NULL);
        if (!g_display) {
            (*env)->DeleteGlobalRef(env, g_bridgeClass); g_bridgeClass = NULL;
            return (*env)->NewStringUTF(env, "Failed to open X11 display");
        }
        g_rootWindow = DefaultRootWindow(g_display);
        Bool sup;
        XkbSetDetectableAutoRepeat(g_display, True, &sup);
        g_running = 1;
        if (pthread_create(&g_thread, NULL, x11_loop, NULL) != 0) {
            g_running = 0;
            XCloseDisplay(g_display); g_display = NULL;
            (*env)->DeleteGlobalRef(env, g_bridgeClass); g_bridgeClass = NULL;
            return (*env)->NewStringUTF(env, "Failed to create X11 thread");
        }

    } else if (g_backend == BACKEND_PORTAL) {
        int ret = portal_init();
        if (ret != 0) {
            (*env)->DeleteGlobalRef(env, g_bridgeClass); g_bridgeClass = NULL;
            g_backend = BACKEND_NONE;
            return (*env)->NewStringUTF(env, g_pinit_error);
        }

    } else {
        (*env)->DeleteGlobalRef(env, g_bridgeClass); g_bridgeClass = NULL;
        return (*env)->NewStringUTF(env, "No display server detected");
    }

    return NULL;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_globalhotkey_linux_NativeLinuxHotKeyBridge_nativeRegister(
    JNIEnv *env, jclass clazz, jlong id, jint modifiers, jint keyCode, jstring description) {
    (void)clazz;
    if (!g_running) return (*env)->NewStringUTF(env, "Not initialized");

    pthread_mutex_lock(&g_mutex);
    if (g_hotkeyCount >= MAX_HOTKEYS) {
        pthread_mutex_unlock(&g_mutex);
        return (*env)->NewStringUTF(env, "Max hotkeys reached");
    }

    HotKeyEntry *e = &g_hotkeys[g_hotkeyCount];
    e->id = id; e->keyCode = keyCode; e->modifiers = modifiers;
    e->x11_keycode = 0; e->x11_modifiers = 0;
    snprintf(e->shortcut_id, sizeof(e->shortcut_id), "nucleus_%ld", (long)id);

    e->description[0] = '\0';
    if (description) {
        const char *d = (*env)->GetStringUTFChars(env, description, NULL);
        if (d) {
            snprintf(e->description, sizeof(e->description), "%s", d);
            (*env)->ReleaseStringUTFChars(env, description, d);
        }
    }

    if (g_backend == BACKEND_X11) {
        KeySym ks = awtToKeySym(keyCode);
        if (ks == NoSymbol) { pthread_mutex_unlock(&g_mutex); return (*env)->NewStringUTF(env, "Unsupported key"); }
        e->x11_keycode = XKeysymToKeycode(g_display, ks);
        if (!e->x11_keycode) { pthread_mutex_unlock(&g_mutex); return (*env)->NewStringUTF(env, "Key mapping failed"); }
        e->x11_modifiers = toX11Mods(modifiers);
        g_hotkeyCount++;
        pthread_mutex_unlock(&g_mutex);
        if (x11_grab(e) != 0) {
            pthread_mutex_lock(&g_mutex); g_hotkeyCount--; pthread_mutex_unlock(&g_mutex);
            return (*env)->NewStringUTF(env, "XGrabKey failed (already grabbed?)");
        }

    } else if (g_backend == BACKEND_PORTAL) {
        g_hotkeyCount++;
        pthread_mutex_unlock(&g_mutex);
        char err[512] = {0};
        if (portal_bind_sync(err, sizeof(err)) != 0) {
            pthread_mutex_lock(&g_mutex); g_hotkeyCount--; pthread_mutex_unlock(&g_mutex);
            return (*env)->NewStringUTF(env, err);
        }

    } else {
        pthread_mutex_unlock(&g_mutex);
        return (*env)->NewStringUTF(env, "No backend");
    }

    return NULL;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_globalhotkey_linux_NativeLinuxHotKeyBridge_nativeUnregister(
    JNIEnv *env, jclass clazz, jlong id) {
    (void)clazz;
    if (!g_running) return (*env)->NewStringUTF(env, "Not initialized");

    pthread_mutex_lock(&g_mutex);
    int found = -1;
    for (int i = 0; i < g_hotkeyCount; i++)
        if (g_hotkeys[i].id == id) { found = i; break; }
    if (found < 0) { pthread_mutex_unlock(&g_mutex); return (*env)->NewStringUTF(env, "Unknown id"); }

    HotKeyEntry entry = g_hotkeys[found];
    for (int i = found; i < g_hotkeyCount - 1; i++) g_hotkeys[i] = g_hotkeys[i+1];
    g_hotkeyCount--;
    pthread_mutex_unlock(&g_mutex);

    if (g_backend == BACKEND_X11) x11_ungrab(&entry);
    else if (g_backend == BACKEND_PORTAL) {
        char err[512] = {0};
        portal_bind_sync(err, sizeof(err)); /* rebind remaining; errors are non-fatal */
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_globalhotkey_linux_NativeLinuxHotKeyBridge_nativeShutdown(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    if (!g_running) return;
    g_running = 0;

    if (g_backend == BACKEND_X11 && g_display) {
        pthread_join(g_thread, NULL);
        pthread_mutex_lock(&g_mutex);
        for (int i = 0; i < g_hotkeyCount; i++) x11_ungrab(&g_hotkeys[i]);
        g_hotkeyCount = 0;
        pthread_mutex_unlock(&g_mutex);
        XCloseDisplay(g_display); g_display = NULL;
    }

    if (g_backend == BACKEND_PORTAL) {
        pthread_mutex_lock(&g_mutex); g_hotkeyCount = 0; pthread_mutex_unlock(&g_mutex);
        portal_shutdown();
    }

    g_backend = BACKEND_NONE;
    if (g_bridgeClass) { (*env)->DeleteGlobalRef(env, g_bridgeClass); g_bridgeClass = NULL; }
    g_onHotKeyMethod = NULL;
}
