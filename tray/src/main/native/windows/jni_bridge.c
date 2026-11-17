/*
 * jni_bridge.c - JNI bridge of nucleus_tray.dll (Windows).
 *
 * Kotlin class: dev.nucleusframework.tray.internal.windows.WindowsNativeBridge
 *
 * Callbacks live in the struct they belong to (a global ref per tray / per menu item), never in
 * a process-wide list: disposing one tray cannot touch another tray's callbacks, and nothing
 * here needs a lock because every call for a tray comes from that tray's own thread.
 */

#include <jni.h>
#include <windows.h>
#include <stdint.h>
#include <stdlib.h>

#include "jni_utf8.h"
#include "../../../../../native-common/nucleus_jni.h"
#include "tray.h"

#define BRIDGE(name) Java_dev_nucleusframework_tray_internal_windows_WindowsNativeBridge_##name

static JavaVM *g_jvm = NULL;
static jmethodID g_run = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
    /* java.lang.Runnable, not each lambda class: GraalVM only needs Runnable registered. */
    jclass runnable = (*env)->FindClass(env, "java/lang/Runnable");
    if (!runnable) return JNI_ERR;
    g_run = (*env)->GetMethodID(env, runnable, "run", "()V");
    (*env)->DeleteLocalRef(env, runnable);
    return g_run ? JNI_VERSION_1_8 : JNI_ERR;
}

static JNIEnv *current_env(void)
{
    JNIEnv *env = NULL;
    if (!g_jvm) return NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_8) == JNI_OK) return env;
    /* Tray threads are Java threads; this only covers an unexpected native caller. */
    if ((*g_jvm)->AttachCurrentThreadAsDaemon(g_jvm, (void **)&env, NULL) != JNI_OK) return NULL;
    return env;
}

static void run(jobject runnable)
{
    JNIEnv *env = current_env();
    if (!env || !runnable) return;
    (*env)->CallVoidMethod(env, runnable, g_run);
    nucleus_jni_clear_exception(env);
}

static void replace_ref(JNIEnv *env, void **slot, jobject value)
{
    if (*slot) (*env)->DeleteGlobalRef(env, (jobject)*slot);
    *slot = value ? (*env)->NewGlobalRef(env, value) : NULL;
}

static void on_click(struct tray *t) { run((jobject)t->user_click); }
static void on_menu_opened(struct tray *t) { run((jobject)t->user_menu_opened); }
static void on_item(struct tray_menu_item *item) { run((jobject)item->user); }

static struct tray *tray_of(jlong handle) { return (struct tray *)(uintptr_t)handle; }
static struct tray_menu_item *items_of(jlong handle) { return (struct tray_menu_item *)(uintptr_t)handle; }

/* ------------------------------------------------------------------ tray */

JNIEXPORT jlong JNICALL BRIDGE(nativeCreateTray)(JNIEnv *env, jclass clazz, jstring iconPath, jstring tooltip)
{
    (void)clazz;
    struct tray *t = (struct tray *)calloc(1, sizeof(struct tray));
    if (!t) return 0;
    t->icon_filepath = jni_utf8_dup(env, iconPath);
    t->tooltip = jni_utf8_dup(env, tooltip);
    return (jlong)(uintptr_t)t;
}

JNIEXPORT void JNICALL BRIDGE(nativeFreeTray)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)clazz;
    struct tray *t = tray_of(handle);
    if (!t) return;
    tray_exit(t);
    replace_ref(env, &t->user_click, NULL);
    replace_ref(env, &t->user_menu_opened, NULL);
    free(t->icon_filepath);
    free(t->tooltip);
    free(t);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetTrayIcon)(JNIEnv *env, jclass clazz, jlong handle, jstring iconPath)
{
    (void)clazz;
    struct tray *t = tray_of(handle);
    if (!t) return;
    free(t->icon_filepath);
    t->icon_filepath = jni_utf8_dup(env, iconPath);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetTrayTooltip)(JNIEnv *env, jclass clazz, jlong handle, jstring tooltip)
{
    (void)clazz;
    struct tray *t = tray_of(handle);
    if (!t) return;
    free(t->tooltip);
    t->tooltip = jni_utf8_dup(env, tooltip);
}

JNIEXPORT void JNICALL BRIDGE(nativeSetTrayCallback)(JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    struct tray *t = tray_of(handle);
    if (!t) return;
    replace_ref(env, &t->user_click, callback);
    t->cb = callback ? on_click : NULL;
}

JNIEXPORT void JNICALL BRIDGE(nativeSetMenuOpenedCallback)(JNIEnv *env, jclass clazz, jlong handle, jobject callback)
{
    (void)clazz;
    struct tray *t = tray_of(handle);
    if (!t) return;
    replace_ref(env, &t->user_menu_opened, callback);
    t->menu_opened_cb = callback ? on_menu_opened : NULL;
}

JNIEXPORT void JNICALL BRIDGE(nativeSetTrayMenu)(JNIEnv *env, jclass clazz, jlong trayHandle, jlong menuHandle)
{
    (void)env; (void)clazz;
    struct tray *t = tray_of(trayHandle);
    if (t) t->menu = items_of(menuHandle);
}

JNIEXPORT jint JNICALL BRIDGE(nativeInitTray)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    return (jint)tray_init(tray_of(handle));
}

JNIEXPORT jint JNICALL BRIDGE(nativePumpTray)(JNIEnv *env, jclass clazz, jlong handle, jboolean wait)
{
    (void)env; (void)clazz;
    return (jint)tray_pump(tray_of(handle), wait ? 1 : 0);
}

JNIEXPORT void JNICALL BRIDGE(nativeUpdateTray)(JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    tray_update(tray_of(handle));
}

JNIEXPORT void JNICALL BRIDGE(nativeWakeTray)(JNIEnv *env, jclass clazz, jlong handle, jboolean cancelMenu)
{
    (void)env; (void)clazz;
    tray_wake(tray_of(handle), cancelMenu ? 1 : 0);
}

/* ------------------------------------------------------------------ menu items */

JNIEXPORT jlong JNICALL BRIDGE(nativeCreateMenuItems)(JNIEnv *env, jclass clazz, jint count)
{
    (void)env; (void)clazz;
    if (count < 0) return 0;
    struct tray_menu_item *items = (struct tray_menu_item *)calloc((size_t)count + 1, sizeof(struct tray_menu_item));
    if (items) items[count].terminator = 1;
    return (jlong)(uintptr_t)items;
}

JNIEXPORT void JNICALL BRIDGE(nativeSetMenuItem)(JNIEnv *env, jclass clazz, jlong menuHandle, jint index,
                                                 jstring text, jstring iconPath, jboolean disabled,
                                                 jboolean checked, jboolean separator)
{
    (void)clazz;
    struct tray_menu_item *items = items_of(menuHandle);
    if (!items || index < 0) return;
    struct tray_menu_item *item = &items[index];
    free(item->text);
    free(item->icon_path);
    item->text = jni_utf8_dup(env, text);
    item->icon_path = jni_utf8_dup(env, iconPath);
    item->disabled = disabled ? 1 : 0;
    item->checked = checked ? 1 : 0;
    item->separator = separator ? 1 : 0;
}

JNIEXPORT void JNICALL BRIDGE(nativeSetMenuItemCallback)(JNIEnv *env, jclass clazz, jlong menuHandle, jint index,
                                                         jobject callback)
{
    (void)clazz;
    struct tray_menu_item *items = items_of(menuHandle);
    if (!items || index < 0) return;
    replace_ref(env, &items[index].user, callback);
    items[index].cb = callback ? on_item : NULL;
}

JNIEXPORT void JNICALL BRIDGE(nativeSetMenuItemSubmenu)(JNIEnv *env, jclass clazz, jlong menuHandle, jint index,
                                                        jlong submenuHandle)
{
    (void)env; (void)clazz;
    struct tray_menu_item *items = items_of(menuHandle);
    if (!items || index < 0) return;
    items[index].submenu = items_of(submenuHandle);
}

/* Frees an item array and, recursively, its submenus. */
static void free_items(JNIEnv *env, struct tray_menu_item *items)
{
    if (!items) return;
    for (struct tray_menu_item *m = items; !m->terminator; ++m) {
        replace_ref(env, &m->user, NULL);
        free(m->text);
        free(m->icon_path);
        free_items(env, m->submenu);
    }
    free(items);
}

JNIEXPORT void JNICALL BRIDGE(nativeFreeMenuItems)(JNIEnv *env, jclass clazz, jlong menuHandle)
{
    (void)clazz;
    free_items(env, items_of(menuHandle));
}

/* ------------------------------------------------------------------ geometry */

JNIEXPORT jboolean JNICALL BRIDGE(nativeGetIdentity)(JNIEnv *env, jclass clazz, jlong handle, jlongArray out)
{
    (void)clazz;
    void *hwnd = NULL;
    unsigned int uid = 0;
    if (!tray_get_identity(tray_of(handle), &hwnd, &uid)) return JNI_FALSE;
    jlong buf[2] = { (jlong)(uintptr_t)hwnd, (jlong)uid };
    (*env)->SetLongArrayRegion(env, out, 0, 2, buf);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL BRIDGE(nativeGetNotificationIconsPosition)(JNIEnv *env, jclass clazz, jlong handle,
                                                                  jintArray outXY)
{
    (void)clazz;
    int x = 0, y = 0;
    int precise = tray_get_notification_icons_position(tray_of(handle), &x, &y);
    jint buf[2] = { (jint)x, (jint)y };
    (*env)->SetIntArrayRegion(env, outXY, 0, 2, buf);
    return (jint)precise;
}

JNIEXPORT jstring JNICALL BRIDGE(nativeGetNotificationIconsRegion)(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    const char *region = tray_get_notification_icons_region();
    return region ? (*env)->NewStringUTF(env, region) : NULL;
}
