/**
 * JNI overlay HWND lifecycle + WndProc for the Tao Windows NativeView.
 *
 * Creates an owned WS_POPUP HWND with WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW,
 * hosts a Compose scene rendered through a transparent WGL context (see
 * nucleus_tao_windows_overlay_gl.c). WndProc handles WM_NCHITTEST
 * (region-based click-through), WM_MOUSEACTIVATE (MA_NOACTIVATE),
 * WM_DPICHANGED, and pointer/wheel forwarding to the JNI callback.
 *
 * Phase 1: stubs only.
 * Linked into nucleus_tao_windows_native_view.dll.
 */

#include <jni.h>
#include <windows.h>

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeCreateOverlay(
    JNIEnv *env, jclass clazz, jlong ownerHwnd) {
    (void)env; (void)clazz; (void)ownerHwnd;
    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayFrame(
    JNIEnv *env, jclass clazz, jlong overlay, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz; (void)overlay; (void)xPx; (void)yPx; (void)widthPx; (void)heightPx;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayRegions(
    JNIEnv *env, jclass clazz, jlong overlay, jfloatArray rectsXYWHPx, jint count) {
    (void)env; (void)clazz; (void)overlay; (void)rectsXYWHPx; (void)count;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayCallback(
    JNIEnv *env, jclass clazz, jlong overlay, jobject callback) {
    (void)env; (void)clazz; (void)overlay; (void)callback;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSetOverlayKeyCallback(
    JNIEnv *env, jclass clazz, jlong overlay, jobject callback) {
    (void)env; (void)clazz; (void)overlay; (void)callback;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeReleaseOverlay(
    JNIEnv *env, jclass clazz, jlong overlay) {
    (void)env; (void)clazz; (void)overlay;
}
