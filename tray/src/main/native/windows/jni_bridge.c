/*
 * jni_bridge.c - JNI bridge for ComposeNativeTray Windows native library.
 *
 * Target Kotlin class: dev.nucleusframework.tray.internal.windows.WindowsNativeBridge
 * JNI prefix:          Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_
 *
 * Follows the same patterns as macOS MacTrayBridge.m and Linux jni_bridge.c:
 * - JavaVM cache with JNI_OnLoad
 * - GlobalRef-based callback storage
 * - Runnable trampolines for callbacks
 * - Handle-based state (struct tray* as jlong)
 */

#include <jni.h>
#include <windows.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "jni_utf8.h"
#include "tray.h"

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
/*  Callback storage (GlobalRef linked list, same as Linux/Mac bridges)       */
/* ========================================================================== */

typedef struct CallbackEntry {
    uintptr_t key;
    jobject globalRef;
    struct CallbackEntry *next;
} CallbackEntry;

static CallbackEntry *g_trayCallbacks = NULL;
static CallbackEntry *g_menuCallbacks = NULL;
static CallbackEntry *g_menuOpenedCallbacks = NULL;

static void storeCallback(CallbackEntry **list, uintptr_t key, JNIEnv *env, jobject callback) {
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
    CallbackEntry *entry = (CallbackEntry *)malloc(sizeof(CallbackEntry));
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

static void tray_cb_trampoline(struct tray *t) {
    uintptr_t key = (uintptr_t)t;
    jobject runnable = findCallback(g_trayCallbacks, key);
    if (runnable) invokeRunnable(runnable);
}

static void menu_item_cb_trampoline(struct tray_menu_item *item) {
    uintptr_t key = (uintptr_t)item;
    jobject runnable = findCallback(g_menuCallbacks, key);
    if (runnable) invokeRunnable(runnable);
}

static void menu_opened_cb_trampoline(struct tray *t) {
    uintptr_t key = (uintptr_t)t;
    jobject runnable = findCallback(g_menuOpenedCallbacks, key);
    if (runnable) invokeRunnable(runnable);
}

/* ========================================================================== */
/*  JNI exports: Tray lifecycle                                               */
/* ========================================================================== */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeCreateTray(
    JNIEnv *env, jclass clazz, jstring iconPath, jstring tooltip)
{
    (void)clazz;
    struct tray *t = (struct tray *)calloc(1, sizeof(struct tray));
    if (!t) return 0;
    t->icon_filepath = jni_utf8_dup(env, iconPath);
    t->tooltip = jni_utf8_dup(env, tooltip);
    t->cb = NULL;
    t->menu = NULL;
    return (jlong)(uintptr_t)t;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeFreeTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;

    /* Remove tray callbacks */
    storeCallback(&g_trayCallbacks, (uintptr_t)t, env, NULL);
    storeCallback(&g_menuOpenedCallbacks, (uintptr_t)t, env, NULL);

    free((void *)t->icon_filepath);
    free((void *)t->tooltip);
    /* menu is freed separately via nativeFreeMenuItems */
    free(t);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetTrayIcon(
    JNIEnv *env, jclass clazz, jlong handle, jstring iconPath)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    free((void *)t->icon_filepath);
    t->icon_filepath = jni_utf8_dup(env, iconPath);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetTrayTooltip(
    JNIEnv *env, jclass clazz, jlong handle, jstring tooltip)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    free((void *)t->tooltip);
    t->tooltip = jni_utf8_dup(env, tooltip);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetTrayCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    storeCallback(&g_trayCallbacks, (uintptr_t)t, env, callback);
    t->cb = callback ? tray_cb_trampoline : NULL;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetMenuOpenedCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    storeCallback(&g_menuOpenedCallbacks, (uintptr_t)t, env, callback);
    tray_set_menu_opened_callback(t, callback ? menu_opened_cb_trampoline : NULL);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetTrayMenu(
    JNIEnv *env, jclass clazz, jlong trayHandle, jlong menuHandle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)trayHandle;
    struct tray_menu_item *m = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (t) t->menu = m;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeClearTrayMenu(
    JNIEnv *env, jclass clazz, jlong trayHandle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)trayHandle;
    if (t) t->menu = NULL;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeInitTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return -1;
    return (jint)tray_init(t);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeLoopTray(
    JNIEnv *env, jclass clazz, jint blocking)
{
    (void)env; (void)clazz;
    return (jint)tray_loop((int)blocking);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeUpdateTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (t) tray_update(t);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeExitTray(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    tray_exit();
    clearAllCallbacks(&g_menuCallbacks);
    clearAllCallbacks(&g_menuOpenedCallbacks);
}

/* ========================================================================== */
/*  JNI exports: Menu items                                                   */
/* ========================================================================== */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeCreateMenuItems(
    JNIEnv *env, jclass clazz, jint count)
{
    (void)env; (void)clazz;
    /* Allocate count+1 items; last one is zero-terminated (text=NULL) */
    struct tray_menu_item *items = (struct tray_menu_item *)calloc(
        (size_t)count + 1, sizeof(struct tray_menu_item));
    return (jlong)(uintptr_t)items;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetMenuItem(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index,
    jstring text, jstring iconPath, jint disabled, jint checked)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    struct tray_menu_item *item = &items[index];

    /* Free old strings if any */
    free(item->text);
    free(item->icon_path);

    item->text = jni_utf8_dup(env, text);
    item->icon_path = jni_utf8_dup(env, iconPath);
    item->disabled = (int)disabled;
    item->checked = (int)checked;
    item->cb = NULL;
    item->submenu = NULL;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetMenuItemCallback(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index, jobject callback)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    struct tray_menu_item *item = &items[index];
    storeCallback(&g_menuCallbacks, (uintptr_t)item, env, callback);
    item->cb = callback ? menu_item_cb_trampoline : NULL;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeSetMenuItemSubmenu(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index, jlong submenuHandle)
{
    (void)env; (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    items[index].submenu = (struct tray_menu_item *)(uintptr_t)submenuHandle;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeFreeMenuItems(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint count)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    for (int i = 0; i < count; i++) {
        struct tray_menu_item *item = &items[i];
        storeCallback(&g_menuCallbacks, (uintptr_t)item, env, NULL);
        free(item->text);
        free(item->icon_path);
        /* submenus are freed separately by Java */
    }
    free(items);
}

/* ========================================================================== */
/*  JNI exports: Position                                                     */
/* ========================================================================== */

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeGetNotificationIconsPosition(
    JNIEnv *env, jclass clazz, jintArray outXY)
{
    (void)clazz;
    int x = 0, y = 0;
    int precise = tray_get_notification_icons_position(&x, &y);
    jint buf[2] = { (jint)x, (jint)y };
    (*env)->SetIntArrayRegion(env, outXY, 0, 2, buf);
    return (jint)precise;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_nativeGetNotificationIconsRegion(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    const char *result = tray_get_notification_icons_region();
    if (!result) return NULL;
    return (*env)->NewStringUTF(env, result);
}
