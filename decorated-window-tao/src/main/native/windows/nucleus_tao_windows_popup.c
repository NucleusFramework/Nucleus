/**
 * JNI popup HWND lifecycle for the Tao Windows NativeView.
 *
 * Each popup is a top-level WS_POPUP HWND owned by the parent (the Tao
 * main HWND, even for nested popups — single-level owner chain) with
 * WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW. Each owns its own transparent
 * WGL context joined to the host's share group via
 * `wglCreateContextAttribsARB(.., hostHGLRC, ..)` (rendering delegated
 * to `nucleus_tao_windows_overlay_gl.c`).
 *
 * Outside-click monitoring uses `SetCapture(hwndPopup)` immediately
 * after `ShowWindow(SW_SHOWNOACTIVATE)`; in WM_LBUTTONDOWN /
 * RBUTTONDOWN / MBUTTONDOWN we test the click point against the popup
 * rect and fire the JNI listener if outside. Capture handoff for nested
 * popups is tracked via an active-popup chain so the outer popup
 * doesn't treat capture loss caused by a child popup as outside-click.
 *
 * Phase 1: stubs only.
 * Linked into nucleus_tao_windows_native_view.dll.
 */

#include <jni.h>
#include <windows.h>

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeCreatePanel(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz; (void)parentHwnd;
    (void)xPx; (void)yPx; (void)widthPx; (void)heightPx;
    return 0;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSetFrameInWindow(
    JNIEnv *env, jclass clazz, jlong panel, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz; (void)panel; (void)xPx; (void)yPx; (void)widthPx; (void)heightPx;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSetFocusable(
    JNIEnv *env, jclass clazz, jlong panel, jboolean focusable) {
    (void)env; (void)clazz; (void)panel; (void)focusable;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeContentHwnd(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    return panel;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz; (void)panel;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSwapBuffers(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz; (void)panel;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSetEventCallback(
    JNIEnv *env, jclass clazz, jlong panel, jobject callback) {
    (void)env; (void)clazz; (void)panel; (void)callback;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeInstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel, jobject listener) {
    (void)env; (void)clazz; (void)panel; (void)listener;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeUninstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz; (void)panel;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeRelease(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz; (void)panel;
}
