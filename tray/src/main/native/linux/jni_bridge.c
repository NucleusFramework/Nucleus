/*
 * jni_bridge.c – JNI bridge for ComposeNativeTray Linux native library.
 *
 * Target Kotlin class: dev.nucleusframework.tray.internal.linux.LinuxNativeBridge
 * JNI prefix:          Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_
 *
 * Follows the same patterns as macOS MacTrayBridge.m:
 * - JavaVM cache with JNI_OnLoad
 * - GlobalRef-based callback storage
 * - Runnable trampolines for callbacks
 * - Handle-based state (sni_tray* as jlong)
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <dlfcn.h>

#include "jni_utf8.h"
#include "sni.h"

/* ========================================================================== */
/*  JavaVM cache                                                              */
/* ========================================================================== */

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_8;
}

static JNIEnv *getJNIEnv(void) {
    JNIEnv *env = NULL;
    if (g_jvm == NULL) return NULL;
    jint rc = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8);
    if (rc == JNI_EDETACHED) {
        (*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL);
    }
    return env;
}

/* ========================================================================== */
/*  Callback storage (GlobalRef linked list, same as MacTrayBridge.m)         */
/* ========================================================================== */

typedef struct CallbackEntry {
    uintptr_t key;
    jobject globalRef;
    struct CallbackEntry *next;
} CallbackEntry;

static CallbackEntry *g_clickCallback = NULL;
static CallbackEntry *g_rclickCallback = NULL;
static CallbackEntry *g_menuCallbacks = NULL;
static CallbackEntry *g_menuOpenedCallback = NULL;

static void storeCallback(CallbackEntry **list, uintptr_t key, JNIEnv *env, jobject callback) {
    /* Remove existing entry for this key */
    CallbackEntry **pp = list;
    while (*pp) {
        if ((*pp)->key == key) {
            CallbackEntry *old = *pp;
            *pp = old->next;
            (*env)->DeleteGlobalRef(env, old->globalRef);
            free(old);
            break;
        }
        pp = &(*pp)->next;
    }
    if (callback == NULL) return;
    CallbackEntry *entry = malloc(sizeof(CallbackEntry));
    entry->key = key;
    entry->globalRef = (*env)->NewGlobalRef(env, callback);
    entry->next = *list;
    *list = entry;
}

static jobject findCallback(CallbackEntry *list, uintptr_t key) {
    for (CallbackEntry *e = list; e; e = e->next) {
        if (e->key == key) return e->globalRef;
    }
    return NULL;
}

static void clearAllCallbacks(CallbackEntry **list) {
    JNIEnv *env = getJNIEnv();
    CallbackEntry *e = *list;
    while (e) {
        CallbackEntry *next = e->next;
        if (env) (*env)->DeleteGlobalRef(env, e->globalRef);
        free(e);
        e = next;
    }
    *list = NULL;
}

/* ========================================================================== */
/*  Runnable invocation helper                                                */
/* ========================================================================== */

/* Cached Runnable class and run() method ID.
 * Using the interface class (java.lang.Runnable) instead of GetObjectClass()
 * so GraalVM native-image can resolve the method without needing to register
 * every lambda class for JNI access. */
static jclass g_runnableClass = NULL;
static jmethodID g_runMethod = NULL;

static void ensureRunnableCached(JNIEnv *env) {
    if (g_runnableClass) return;
    jclass cls = (*env)->FindClass(env, "java/lang/Runnable");
    if (!cls) return;
    g_runnableClass = (*env)->NewGlobalRef(env, cls);
    g_runMethod = (*env)->GetMethodID(env, g_runnableClass, "run", "()V");
}

static void invokeRunnable(jobject runnable) {
    JNIEnv *env = getJNIEnv();
    if (!env || !runnable) return;
    ensureRunnableCached(env);
    if (!g_runMethod) return;
    (*env)->CallVoidMethod(env, runnable, g_runMethod);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ========================================================================== */
/*  C callback trampolines                                                    */
/* ========================================================================== */

static void click_trampoline(int32_t x, int32_t y, void *userdata) {
    (void)x; (void)y;
    uintptr_t key = (uintptr_t)userdata;
    jobject runnable = findCallback(g_clickCallback, key);
    if (runnable) invokeRunnable(runnable);
}

static void rclick_trampoline(int32_t x, int32_t y, void *userdata) {
    (void)x; (void)y;
    uintptr_t key = (uintptr_t)userdata;
    jobject runnable = findCallback(g_rclickCallback, key);
    if (runnable) invokeRunnable(runnable);
}

static void menu_item_trampoline(uint32_t id, void *userdata) {
    (void)userdata;
    jobject runnable = findCallback(g_menuCallbacks, (uintptr_t)id);
    if (runnable) invokeRunnable(runnable);
}

static void menu_opened_trampoline(void *userdata) {
    uintptr_t key = (uintptr_t)userdata;
    jobject runnable = findCallback(g_menuOpenedCallback, key);
    if (runnable) invokeRunnable(runnable);
}

/* ========================================================================== */
/*  JNI exports                                                               */
/* ========================================================================== */

/* ── Lifecycle ──────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeCreate(
    JNIEnv *env, jclass clazz, jbyteArray iconBytes, jstring tooltip)
{
    (void)clazz;

    char *tip = jni_utf8_dup(env, tooltip);

    const uint8_t *icon_data = NULL;
    jsize icon_len = 0;
    jbyte *icon_buf = NULL;
    if (iconBytes) {
        icon_len = (*env)->GetArrayLength(env, iconBytes);
        icon_buf = (*env)->GetByteArrayElements(env, iconBytes, NULL);
        icon_data = (const uint8_t *)icon_buf;
    }

    sni_tray *tray = sni_tray_create(icon_data, (size_t)icon_len, tip);

    if (icon_buf) (*env)->ReleaseByteArrayElements(env, iconBytes, icon_buf, JNI_ABORT);
    free(tip);

    return (jlong)(uintptr_t)tray;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeRun(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return -1;
    return (jint)sni_tray_run(tray);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeQuit(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    sni_tray_quit(tray);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeDestroy(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;

    /* Clean up all callbacks for this tray */
    uintptr_t key = (uintptr_t)tray;
    storeCallback(&g_clickCallback, key, env, NULL);
    storeCallback(&g_rclickCallback, key, env, NULL);
    storeCallback(&g_menuOpenedCallback, key, env, NULL);
    /* Menu callbacks are keyed by item id, clear all */
    clearAllCallbacks(&g_menuCallbacks);

    sni_tray_destroy(tray);
}

/* ── Tray properties ────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetIcon(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray iconBytes)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray || !iconBytes) return;

    jsize len = (*env)->GetArrayLength(env, iconBytes);
    jbyte *buf = (*env)->GetByteArrayElements(env, iconBytes, NULL);
    sni_tray_set_icon(tray, (const uint8_t *)buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, iconBytes, buf, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetTitle(
    JNIEnv *env, jclass clazz, jlong handle, jstring title)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    char *t = jni_utf8_dup(env, title);
    sni_tray_set_title(tray, t);
    free(t);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetTooltip(
    JNIEnv *env, jclass clazz, jlong handle, jstring tooltip)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    char *tt = jni_utf8_dup(env, tooltip);
    sni_tray_set_tooltip(tray, tt);
    free(tt);
}

/* ── Callbacks ──────────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetClickCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    uintptr_t key = (uintptr_t)tray;
    storeCallback(&g_clickCallback, key, env, callback);
    sni_tray_set_click_callback(tray,
                                 callback ? click_trampoline : NULL,
                                 (void *)key);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetRClickCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    uintptr_t key = (uintptr_t)tray;
    storeCallback(&g_rclickCallback, key, env, callback);
    sni_tray_set_rclick_callback(tray,
                                  callback ? rclick_trampoline : NULL,
                                  (void *)key);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetMenuItemCallback(
    JNIEnv *env, jclass clazz, jlong handle, jint menuId, jobject callback)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    storeCallback(&g_menuCallbacks, (uintptr_t)menuId, env, callback);
    /* Ensure the global menu callback trampoline is installed */
    sni_tray_set_menu_callback(tray, menu_item_trampoline, (void *)(uintptr_t)tray);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeSetMenuOpenedCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    uintptr_t key = (uintptr_t)tray;
    storeCallback(&g_menuOpenedCallback, key, env, callback);
    sni_tray_set_menu_opened_callback(tray,
                                       callback ? menu_opened_trampoline : NULL,
                                       (void *)key);
}

/* ── Click position ─────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeGetLastClickXY(
    JNIEnv *env, jclass clazz, jlong handle, jintArray outXY)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray || !outXY) return;
    int32_t x = 0, y = 0;
    sni_tray_get_last_click_xy(tray, &x, &y);
    jint buf[2] = {(jint)x, (jint)y};
    (*env)->SetIntArrayRegion(env, outXY, 0, 2, buf);
}

/* ── Menu management ────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeResetMenu(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    /* Clear menu item callbacks */
    clearAllCallbacks(&g_menuCallbacks);
    sni_tray_reset_menu(tray);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeAddMenuItem(
    JNIEnv *env, jclass clazz, jlong handle, jstring title, jstring tooltip)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return 0;
    char *t = jni_utf8_dup(env, title);
    char *tt = jni_utf8_dup(env, tooltip);
    uint32_t id = sni_tray_add_menu_item(tray, t, tt);
    free(t);
    free(tt);
    return (jint)id;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeAddMenuItemCheckbox(
    JNIEnv *env, jclass clazz, jlong handle, jstring title, jstring tooltip, jboolean checked)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return 0;
    char *t = jni_utf8_dup(env, title);
    char *tt = jni_utf8_dup(env, tooltip);
    uint32_t id = sni_tray_add_menu_item_checkbox(tray, t, tt, checked ? 1 : 0);
    free(t);
    free(tt);
    return (jint)id;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeAddSeparator(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_add_separator(tray);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeAddSubMenuItem(
    JNIEnv *env, jclass clazz, jlong handle, jint parentId,
    jstring title, jstring tooltip)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return 0;
    char *t = jni_utf8_dup(env, title);
    char *tt = jni_utf8_dup(env, tooltip);
    uint32_t id = sni_tray_add_sub_menu_item(tray, (uint32_t)parentId, t, tt);
    free(t);
    free(tt);
    return (jint)id;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeAddSubMenuItemCheckbox(
    JNIEnv *env, jclass clazz, jlong handle, jint parentId,
    jstring title, jstring tooltip, jboolean checked)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return 0;
    char *t = jni_utf8_dup(env, title);
    char *tt = jni_utf8_dup(env, tooltip);
    uint32_t id = sni_tray_add_sub_menu_item_checkbox(tray, (uint32_t)parentId, t, tt, checked ? 1 : 0);
    free(t);
    free(tt);
    return (jint)id;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeAddSubSeparator(
    JNIEnv *env, jclass clazz, jlong handle, jint parentId)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_add_sub_separator(tray, (uint32_t)parentId);
}

/* ── Per-item operations ────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemSetTitle(
    JNIEnv *env, jclass clazz, jlong handle, jint id, jstring title)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return 0;
    char *t = jni_utf8_dup(env, title);
    int ok = sni_tray_item_set_title(tray, (uint32_t)id, t);
    free(t);
    return (jint)ok;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemEnable(
    JNIEnv *env, jclass clazz, jlong handle, jint id)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_item_enable(tray, (uint32_t)id);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemDisable(
    JNIEnv *env, jclass clazz, jlong handle, jint id)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_item_disable(tray, (uint32_t)id);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemShow(
    JNIEnv *env, jclass clazz, jlong handle, jint id)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_item_show(tray, (uint32_t)id);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemHide(
    JNIEnv *env, jclass clazz, jlong handle, jint id)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_item_hide(tray, (uint32_t)id);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemCheck(
    JNIEnv *env, jclass clazz, jlong handle, jint id)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_item_check(tray, (uint32_t)id);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemUncheck(
    JNIEnv *env, jclass clazz, jlong handle, jint id)
{
    (void)env; (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (tray) sni_tray_item_uncheck(tray, (uint32_t)id);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemSetIcon(
    JNIEnv *env, jclass clazz, jlong handle, jint id, jbyteArray iconBytes)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray || !iconBytes) return;
    jsize len = (*env)->GetArrayLength(env, iconBytes);
    jbyte *buf = (*env)->GetByteArrayElements(env, iconBytes, NULL);
    sni_tray_item_set_icon(tray, (uint32_t)id, (const uint8_t *)buf, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, iconBytes, buf, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeItemSetShortcut(
    JNIEnv *env, jclass clazz, jlong handle, jint id,
    jstring key, jboolean ctrl, jboolean shift, jboolean alt, jboolean superMod)
{
    (void)clazz;
    sni_tray *tray = (sni_tray *)(uintptr_t)handle;
    if (!tray) return;
    char *k = jni_utf8_dup(env, key);
    sni_tray_item_set_shortcut(tray, (uint32_t)id, k,
                                ctrl ? 1 : 0, shift ? 1 : 0,
                                alt ? 1 : 0, superMod ? 1 : 0);
    free(k);
}

/* ========================================================================== */
/*  X11 outside-click watcher (dynamically loaded to avoid hard dependency)   */
/* ========================================================================== */

/* X11 types */
typedef void *X11Display;
typedef unsigned long X11Window;
typedef int X11Bool;

/* X11 function pointers (loaded via dlopen/dlsym) */
typedef X11Display (*fn_XOpenDisplay)(const char *);
typedef X11Window  (*fn_XDefaultRootWindow)(X11Display);
typedef X11Bool    (*fn_XQueryPointer)(X11Display, X11Window,
                                      X11Window *, X11Window *,
                                      int *, int *, int *, int *,
                                      unsigned int *);
typedef int        (*fn_XCloseDisplay)(X11Display);

static void        *g_x11_lib = NULL;
static fn_XOpenDisplay       g_XOpenDisplay = NULL;
static fn_XDefaultRootWindow g_XDefaultRootWindow = NULL;
static fn_XQueryPointer      g_XQueryPointer = NULL;
static fn_XCloseDisplay      g_XCloseDisplay = NULL;

static int ensure_x11(void) {
    if (g_x11_lib) return 1;
    g_x11_lib = dlopen("libX11.so.6", RTLD_LAZY);
    if (!g_x11_lib) g_x11_lib = dlopen("libX11.so", RTLD_LAZY);
    if (!g_x11_lib) return 0;
    g_XOpenDisplay       = (fn_XOpenDisplay)dlsym(g_x11_lib, "XOpenDisplay");
    g_XDefaultRootWindow = (fn_XDefaultRootWindow)dlsym(g_x11_lib, "XDefaultRootWindow");
    g_XQueryPointer      = (fn_XQueryPointer)dlsym(g_x11_lib, "XQueryPointer");
    g_XCloseDisplay      = (fn_XCloseDisplay)dlsym(g_x11_lib, "XCloseDisplay");
    if (!g_XOpenDisplay || !g_XDefaultRootWindow || !g_XQueryPointer || !g_XCloseDisplay) {
        dlclose(g_x11_lib);
        g_x11_lib = NULL;
        return 0;
    }
    return 1;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeX11OpenDisplay(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (!ensure_x11()) return 0;
    X11Display dpy = g_XOpenDisplay(NULL);
    return (jlong)(uintptr_t)dpy;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeX11DefaultRootWindow(
    JNIEnv *env, jclass clazz, jlong displayHandle)
{
    (void)env; (void)clazz;
    if (!g_XDefaultRootWindow) return 0;
    X11Display dpy = (X11Display)(uintptr_t)displayHandle;
    if (!dpy) return 0;
    return (jlong)g_XDefaultRootWindow(dpy);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeX11QueryPointer(
    JNIEnv *env, jclass clazz, jlong displayHandle, jlong rootWindow, jintArray outData)
{
    (void)clazz;
    if (!g_XQueryPointer) return 0;
    X11Display dpy = (X11Display)(uintptr_t)displayHandle;
    if (!dpy || !outData) return 0;

    X11Window root_ret, child_ret;
    int root_x, root_y, win_x, win_y;
    unsigned int mask;

    X11Bool ok = g_XQueryPointer(dpy, (X11Window)(unsigned long)rootWindow,
                                 &root_ret, &child_ret,
                                 &root_x, &root_y, &win_x, &win_y, &mask);

    /* outData: [rootX, rootY, mask] */
    jint buf[3] = {(jint)root_x, (jint)root_y, (jint)mask};
    (*env)->SetIntArrayRegion(env, outData, 0, 3, buf);

    return (jint)(ok != 0 ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_linux_LinuxNativeBridge_nativeX11CloseDisplay(
    JNIEnv *env, jclass clazz, jlong displayHandle)
{
    (void)env; (void)clazz;
    if (!g_XCloseDisplay) return;
    X11Display dpy = (X11Display)(uintptr_t)displayHandle;
    if (dpy) g_XCloseDisplay(dpy);
}
