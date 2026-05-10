/**
 * sample_webview.c — Linux WebKit2GTK helper for the sample-tao
 * "WebView" tab demo.
 *
 * Minimal: creates a real `WebKitWebView` widget and exposes its
 * `GtkWidget*` so it can be reparented into Tao's content widget tree
 * via `NucleusPlatformView.GtkWidget`. No offscreen rendering, no
 * texture capture — the widget paints directly into the GTK window
 * like any other GTK widget.
 *
 * Builds into `libsample_tao_webview_linux.so`. Loaded via
 * `NativeLibraryLoader.load("sample_tao_webview_linux")`.
 *
 * Threading: every entry point must run on the GTK main thread.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeCreate(
    JNIEnv *env, jclass clazz)
{
    (void) env; (void) clazz;
    GtkWidget *web_view = webkit_web_view_new();
    if (web_view == NULL) return 0;
    /* Take a strong floating-ref so the widget survives even when not
     * yet parented (Compose calls `nativeCreate` from `factory{}` and
     * the embed happens slightly later on the same thread). The
     * matching unref happens on `nativeRelease`. */
    g_object_ref_sink(web_view);
    return (jlong) (uintptr_t) web_view;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeRelease(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return;
    GtkWidget *web_view = (GtkWidget *) (uintptr_t) handle;
    /* The Compose disposable effect order guarantees the widget has
     * already been removed from its GtkFixed parent before we get
     * here, so the only outstanding strong ref is the one we took
     * via `g_object_ref_sink`. Calling `gtk_widget_destroy` *before*
     * the unref is what triggers the
     *   "instance has no handler with id 'N'"
     * GLib warnings out of WebKit2GTK — it races WebKit's own
     * teardown which has already dropped those handlers.
     * Releasing our ref is enough: the ref count drops to 0 and GTK
     * runs the standard dispose chain at finalize time. */
    g_object_unref(web_view);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeLoadUrl(
    JNIEnv *env, jclass clazz, jlong handle, jstring url_str)
{
    (void) clazz;
    if (handle == 0 || url_str == NULL) return;
    GtkWidget *web_view = (GtkWidget *) (uintptr_t) handle;
    const char *cUrl = (*env)->GetStringUTFChars(env, url_str, NULL);
    if (cUrl == NULL) return;
    webkit_web_view_load_uri(WEBKIT_WEB_VIEW(web_view), cUrl);
    (*env)->ReleaseStringUTFChars(env, url_str, cUrl);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeGoBack(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return;
    webkit_web_view_go_back(WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle));
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeGoForward(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return;
    webkit_web_view_go_forward(WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle));
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeReload(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return;
    webkit_web_view_reload(WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle));
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeCanGoBack(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    return webkit_web_view_can_go_back(
        WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeCanGoForward(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    return webkit_web_view_can_go_forward(
        WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeCurrentUrl(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) clazz;
    if (handle == 0) return NULL;
    const gchar *uri = webkit_web_view_get_uri(
        WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle));
    if (uri == NULL) return NULL;
    return (*env)->NewStringUTF(env, uri);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_sampletao_SampleWebViewLinuxBridge_nativeIsLoading(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void) env; (void) clazz;
    if (handle == 0) return JNI_FALSE;
    return webkit_web_view_is_loading(
        WEBKIT_WEB_VIEW((GtkWidget *) (uintptr_t) handle)) ? JNI_TRUE : JNI_FALSE;
}
