/*
 * MacTrayBridge.m – JNI bridge for ComposeNativeTray macOS native library.
 *
 * Target Kotlin class: dev.nucleusframework.tray.internal.macos.MacNativeBridge
 * JNI prefix:          Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_
 */

#import <jni.h>
#import <dlfcn.h>
#import <string.h>
#import <stdlib.h>
#import <objc/runtime.h>
#import <AppKit/AppKit.h>
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
/*  JNI class/method name prefix                                              */
/* ========================================================================== */
#define JNI_PREFIX Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_

/* Concatenation helper */
#define JNI_FN(name) JNICALL JNI_PREFIX ## name

/* ========================================================================== */
/*  Callback GlobalRef storage                                                */
/* ========================================================================== */

/*
 * We store per-tray and per-menu-item GlobalRefs in simple linked lists
 * keyed by the native pointer. This is adequate for the small number of
 * callbacks involved (typically 1 tray + a few dozen menu items).
 */

typedef struct CallbackEntry {
    void *key;                   /* struct tray* or struct tray_menu_item* */
    jobject globalRef;           /* GlobalRef to Java callback object      */
    struct CallbackEntry *next;
} CallbackEntry;

static CallbackEntry *g_trayCallbacks = NULL;      /* tray left-click */
static CallbackEntry *g_menuCallbacks = NULL;       /* menu item click */
static CallbackEntry *g_menuOpenedCallbacks = NULL;  /* menu opened */
static CallbackEntry *g_themeCallback = NULL;        /* theme change (single) */

static void storeCallback(CallbackEntry **list, void *key, JNIEnv *env, jobject callback) {
    /* Remove existing entry for this key if any */
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

static jobject findCallback(CallbackEntry *list, void *key) {
    for (CallbackEntry *e = list; e; e = e->next) {
        if (e->key == key) return e->globalRef;
    }
    return NULL;
}

static void removeCallback(CallbackEntry **list, void *key) {
    JNIEnv *env = getJNIEnv();
    if (!env) return;
    CallbackEntry **pp = list;
    while (*pp) {
        if ((*pp)->key == key) {
            CallbackEntry *old = *pp;
            *pp = old->next;
            (*env)->DeleteGlobalRef(env, old->globalRef);
            free(old);
            return;
        }
        pp = &(*pp)->next;
    }
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
/*  C callback trampolines                                                    */
/* ========================================================================== */

/*
 * Cached method IDs — resolved once via the interface class so that
 * GraalVM native-image only needs the interface registered for JNI,
 * not every lambda / anonymous class that implements it.
 */
static jmethodID g_runMethodID = NULL;
static jmethodID g_onThemeChangedMethodID = NULL;

static jmethodID getRunnableRunMethod(JNIEnv *env) {
    if (g_runMethodID == NULL) {
        jclass cls = (*env)->FindClass(env, "java/lang/Runnable");
        if (cls) {
            g_runMethodID = (*env)->GetMethodID(env, cls, "run", "()V");
            (*env)->DeleteLocalRef(env, cls);
        }
    }
    return g_runMethodID;
}

static jmethodID getThemeChangedMethod(JNIEnv *env) {
    if (g_onThemeChangedMethodID == NULL) {
        jclass cls = (*env)->FindClass(env, "dev/nucleusframework/tray/internal/macos/MacNativeBridge$ThemeChangeCallback");
        if (cls) {
            g_onThemeChangedMethodID = (*env)->GetMethodID(env, cls, "onThemeChanged", "(I)V");
            (*env)->DeleteLocalRef(env, cls);
        }
    }
    return g_onThemeChangedMethodID;
}

/* Called by the Swift tray_callback when the tray icon is left-clicked */
static void trayCbTrampoline(struct tray *t) {
    JNIEnv *env = getJNIEnv();
    if (!env) return;
    jobject runnable = findCallback(g_trayCallbacks, t);
    if (!runnable) return;
    jmethodID run = getRunnableRunMethod(env);
    if (!run) return;
    (*env)->CallVoidMethod(env, runnable, run);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Called by the Swift menu delegate when a menu item is clicked */
static void menuItemCbTrampoline(struct tray_menu_item *item) {
    JNIEnv *env = getJNIEnv();
    if (!env) return;
    jobject runnable = findCallback(g_menuCallbacks, item);
    if (!runnable) return;
    jmethodID run = getRunnableRunMethod(env);
    if (!run) return;
    (*env)->CallVoidMethod(env, runnable, run);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Called by the Swift click handler when the menu is about to open */
static void menuOpenedCbTrampoline(struct tray *t) {
    JNIEnv *env = getJNIEnv();
    if (!env) return;
    jobject runnable = findCallback(g_menuOpenedCallbacks, t);
    if (!runnable) return;
    jmethodID run = getRunnableRunMethod(env);
    if (!run) return;
    (*env)->CallVoidMethod(env, runnable, run);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Called by the Swift appearance observer when the theme changes */
static void themeCbTrampoline(int isDark) {
    JNIEnv *env = getJNIEnv();
    if (!env) return;
    if (!g_themeCallback) return;
    jobject cb = g_themeCallback->globalRef;
    if (!cb) return;
    jmethodID method = getThemeChangedMethod(env);
    if (!method) return;
    (*env)->CallVoidMethod(env, cb, method, (jint)isDark);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ========================================================================== */
/*  Tray lifecycle                                                            */
/* ========================================================================== */

JNIEXPORT jlong JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeCreateTray(
    JNIEnv *env, jclass clazz, jstring iconPath, jstring tooltip)
{
    (void)clazz;
    struct tray *t = (struct tray *)calloc(1, sizeof(struct tray));
    if (!t) return 0;

    t->icon_filepath = jni_utf8_dup(env, iconPath);
    t->tooltip = jni_utf8_dup(env, tooltip);

    t->menu = NULL;
    t->cb = NULL;
    return (jlong)(uintptr_t)t;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeFreeTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    removeCallback(&g_trayCallbacks, t);
    free((void *)t->icon_filepath);
    free((void *)t->tooltip);
    free(t);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetTrayIcon(
    JNIEnv *env, jclass clazz, jlong handle, jstring iconPath)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    free((void *)t->icon_filepath);
    t->icon_filepath = jni_utf8_dup(env, iconPath);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetTrayTooltip(
    JNIEnv *env, jclass clazz, jlong handle, jstring tooltip)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    free((void *)t->tooltip);
    t->tooltip = jni_utf8_dup(env, tooltip);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetTrayCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    storeCallback(&g_trayCallbacks, t, env, callback);
    t->cb = (callback != NULL) ? trayCbTrampoline : NULL;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMenuOpenedCallback(
    JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    storeCallback(&g_menuOpenedCallbacks, t, env, callback);
    tray_set_menu_opened_callback(t, (callback != NULL) ? menuOpenedCbTrampoline : NULL);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetTrayMenu(
    JNIEnv *env, jclass clazz, jlong trayHandle, jlong menuHandle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)trayHandle;
    if (!t) return;
    t->menu = (struct tray_menu_item *)(uintptr_t)menuHandle;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeClearTrayMenu(
    JNIEnv *env, jclass clazz, jlong trayHandle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)trayHandle;
    if (!t) return;
    t->menu = NULL;
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeInitTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return -1;
    return (jint)tray_init(t);
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeLoopTray(
    JNIEnv *env, jclass clazz, jint blocking)
{
    (void)env; (void)clazz;
    return (jint)tray_loop((int)blocking);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeUpdateTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    tray_update(t);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeDisposeTray(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    tray_dispose(t);
    /* Clean up callback refs for this tray */
    removeCallback(&g_trayCallbacks, t);
    removeCallback(&g_menuOpenedCallbacks, t);
    /* Free the struct and its strings */
    free((void *)t->icon_filepath);
    free((void *)t->tooltip);
    free(t);
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeExitTray(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    tray_exit();
    clearAllCallbacks(&g_trayCallbacks);
    clearAllCallbacks(&g_menuCallbacks);
    clearAllCallbacks(&g_menuOpenedCallbacks);
}

/* ========================================================================== */
/*  Menu items                                                                */
/* ========================================================================== */

JNIEXPORT jlong JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeCreateMenuItems(
    JNIEnv *env, jclass clazz, jint count)
{
    (void)env; (void)clazz;
    /* Allocate count+1 items; the last one is the sentinel (all zeros/NULL) */
    struct tray_menu_item *items = (struct tray_menu_item *)calloc((size_t)count + 1, sizeof(struct tray_menu_item));
    return (jlong)(uintptr_t)items;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMenuItem(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index,
    jstring text, jstring iconPath, jint disabled, jint checked)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    struct tray_menu_item *item = &items[index];

    /* Free previous strings if overwriting */
    free((void *)item->text);
    free((void *)item->icon_filepath);

    item->text = jni_utf8_dup(env, text);
    item->icon_filepath = jni_utf8_dup(env, iconPath);

    item->disabled = (int)disabled;
    item->checked = (int)checked;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMenuItemCallback(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index, jobject callback)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    struct tray_menu_item *item = &items[index];
    storeCallback(&g_menuCallbacks, item, env, callback);
    item->cb = (callback != NULL) ? menuItemCbTrampoline : NULL;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMenuItemShortcut(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index,
    jstring keyEquivalent, jlong modifierMask)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    struct tray_menu_item *item = &items[index];

    free((void *)item->key_equivalent);

    item->key_equivalent = jni_utf8_dup(env, keyEquivalent);
    item->key_equivalent_mod_mask = (unsigned long)modifierMask;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMenuItemSubmenu(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint index, jlong submenuHandle)
{
    (void)env; (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    items[index].submenu = (struct tray_menu_item *)(uintptr_t)submenuHandle;
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeFreeMenuItems(
    JNIEnv *env, jclass clazz, jlong menuHandle, jint count)
{
    (void)clazz;
    struct tray_menu_item *items = (struct tray_menu_item *)(uintptr_t)menuHandle;
    if (!items) return;
    for (int i = 0; i < count; i++) {
        removeCallback(&g_menuCallbacks, &items[i]);
        free((void *)items[i].text);
        free((void *)items[i].icon_filepath);
        free((void *)items[i].key_equivalent);
        /* Note: submenus are freed by their own nativeFreeMenuItems call */
    }
    free(items);
}

/* ========================================================================== */
/*  Theme                                                                     */
/* ========================================================================== */

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetThemeCallback(
    JNIEnv *env, jclass clazz, jobject callback)
{
    (void)clazz;
    /* Store a single global theme callback */
    if (g_themeCallback) {
        (*env)->DeleteGlobalRef(env, g_themeCallback->globalRef);
        free(g_themeCallback);
        g_themeCallback = NULL;
    }
    if (callback != NULL) {
        g_themeCallback = (CallbackEntry *)malloc(sizeof(CallbackEntry));
        g_themeCallback->key = NULL;
        g_themeCallback->globalRef = (*env)->NewGlobalRef(env, callback);
        g_themeCallback->next = NULL;
        tray_set_theme_callback(themeCbTrampoline);
    }
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeIsMenuDark(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)tray_is_menu_dark();
}

/* ========================================================================== */
/*  Position                                                                  */
/* ========================================================================== */

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeGetStatusItemPosition(
    JNIEnv *env, jclass clazz, jintArray outXY)
{
    (void)clazz;
    int x = 0, y = 0;
    int precise = tray_get_status_item_position(&x, &y);
    jint buf[2] = { (jint)x, (jint)y };
    (*env)->SetIntArrayRegion(env, outXY, 0, 2, buf);
    return (jint)precise;
}

JNIEXPORT jstring JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeGetStatusItemRegion(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    const char *region = tray_get_status_item_region();
    if (!region) return (*env)->NewStringUTF(env, "top-right");
    jstring result = (*env)->NewStringUTF(env, region);
    free((void *)region);
    return result;
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeGetStatusItemPositionFor(
    JNIEnv *env, jclass clazz, jlong handle, jintArray outXY)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    int x = 0, y = 0;
    int precise = tray_get_status_item_position_for(t, &x, &y);
    jint buf[2] = { (jint)x, (jint)y };
    (*env)->SetIntArrayRegion(env, outXY, 0, 2, buf);
    return (jint)precise;
}

JNIEXPORT jstring JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeGetStatusItemRegionFor(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    const char *region = tray_get_status_item_region_for(t);
    if (!region) return (*env)->NewStringUTF(env, "top-right");
    jstring result = (*env)->NewStringUTF(env, region);
    free((void *)region);
    return result;
}

/* ========================================================================== */
/*  Appearance                                                                */
/* ========================================================================== */

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetIconsForAppearance(
    JNIEnv *env, jclass clazz, jlong handle, jstring lightIcon, jstring darkIcon)
{
    (void)clazz;
    struct tray *t = (struct tray *)(uintptr_t)handle;
    if (!t) return;
    char *lightUtf = jni_utf8_dup(env, lightIcon);
    char *darkUtf = jni_utf8_dup(env, darkIcon);
    tray_set_icons_for_appearance(t, lightUtf, darkUtf);
    free(lightUtf);
    free(darkUtf);
}

/* ========================================================================== */
/*  Window management                                                         */
/* ========================================================================== */

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeShowInDock(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)tray_show_in_dock();
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeHideFromDock(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)tray_hide_from_dock();
}

JNIEXPORT void JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMoveToActiveSpace(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    tray_set_windows_move_to_active_space();
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeSetMoveToActiveSpaceForWindow(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    return (jint)tray_set_move_to_active_space_for_view((void *)(uintptr_t)viewPtr);
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeIsFloatingWindowOnActiveSpace(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)tray_is_floating_window_on_active_space();
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeBringFloatingWindowToFront(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)tray_bring_floating_window_to_front();
}

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeIsOnActiveSpaceForView(
    JNIEnv *env, jclass clazz, jlong viewPtr)
{
    (void)env; (void)clazz;
    return (jint)tray_is_on_active_space_for_view((void *)(uintptr_t)viewPtr);
}

/* ========================================================================== */
/*  Mouse                                                                     */
/* ========================================================================== */

JNIEXPORT jint JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeGetMouseButtonState(
    JNIEnv *env, jclass clazz, jint button)
{
    (void)env; (void)clazz;
    return (jint)tray_get_mouse_button_state((int)button);
}

/* ========================================================================== */
/*  JAWT – resolve AWT native view pointer dynamically                        */
/* ========================================================================== */

/*
 * JAWT types and function pointer – resolved at runtime via dlsym so we
 * don't need to link against the JAWT framework at build time.
 */

#ifndef _JAWT_H_
/* Minimal JAWT declarations if the header isn't available */
typedef jint JAWT_Version;

typedef struct {
    jint x, y, width, height;
} JAWT_Rectangle;

typedef struct JAWT_DrawingSurfaceInfo {
    void *platformInfo;
    void *ds;      /* back-pointer */
    JAWT_Rectangle bounds;
    jint clipSize;
    JAWT_Rectangle *clip;
} JAWT_DrawingSurfaceInfo;

typedef struct JAWT_DrawingSurface {
    JNIEnv *env;
    jobject target;
    jint (JNICALL *Lock)(struct JAWT_DrawingSurface *ds);
    JAWT_DrawingSurfaceInfo *(JNICALL *GetDrawingSurfaceInfo)(struct JAWT_DrawingSurface *ds);
    void (JNICALL *FreeDrawingSurfaceInfo)(JAWT_DrawingSurfaceInfo *dsi);
    void (JNICALL *Unlock)(struct JAWT_DrawingSurface *ds);
} JAWT_DrawingSurface;

typedef struct {
    JAWT_Version version;
    JAWT_DrawingSurface *(JNICALL *GetDrawingSurface)(JNIEnv *env, jobject target);
    void (JNICALL *FreeDrawingSurface)(JAWT_DrawingSurface *ds);
    void (JNICALL *Lock)(JNIEnv *env);
    void (JNICALL *Unlock)(JNIEnv *env);
    /* JAWT 9+ */
    jobject (JNICALL *GetComponent)(JNIEnv *env, void *platformInfo);
} JAWT;

#define JAWT_VERSION_1_4 0x00010004
#define JAWT_VERSION_9   0x00090000
#define JAWT_LOCK_ERROR  0x00000001

typedef jboolean (JNICALL *JAWT_GetAWT_t)(JNIEnv *env, JAWT *awt);
#endif /* _JAWT_H_ */

JNIEXPORT jlong JNICALL Java_dev_nucleusframework_tray_internal_macos_MacNativeBridge_nativeGetAWTViewPtr(
    JNIEnv *env, jclass clazz, jobject awtComponent)
{
    (void)clazz;
    if (awtComponent == NULL) return 0;

    /* Dynamically resolve JAWT_GetAWT */
    static JAWT_GetAWT_t jawt_GetAWT = NULL;
    if (jawt_GetAWT == NULL) {
        jawt_GetAWT = (JAWT_GetAWT_t)dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
        if (jawt_GetAWT == NULL) return 0;
    }

    JAWT awt;
    awt.version = JAWT_VERSION_1_4;
    if (!jawt_GetAWT(env, &awt)) return 0;

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, awtComponent);
    if (!ds) return 0;

    jint lockResult = ds->Lock(ds);
    if (lockResult & JAWT_LOCK_ERROR) {
        awt.FreeDrawingSurface(ds);
        return 0;
    }

    JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
    jlong viewPtr = 0;
    if (dsi && dsi->platformInfo) {
        /*
         * On macOS Cocoa, platformInfo points to an id<NSObject> which is
         * the NSView (specifically an AWTSurfaceLayers subclass).
         * We cast it to a raw pointer and return as jlong.
         */
        viewPtr = (jlong)(uintptr_t)(*(void **)dsi->platformInfo);
    }

    if (dsi) ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);

    return viewPtr;
}
