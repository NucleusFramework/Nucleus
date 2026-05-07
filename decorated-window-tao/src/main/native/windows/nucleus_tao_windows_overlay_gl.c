/**
 * Transparent WGL rendering bridge for the Tao Windows overlay & popup
 * HWNDs.
 *
 * Phase 4 will implement; Phase 1 = stubs. Key design points (per
 * NATIVE_VIEW_WINDOWS_PLAN.md "Rendering surface"):
 *
 *   1. The popup HDC uses the SAME pixel format as the host (cached at
 *      host init by the existing `nucleus_tao_gl.c`). MSDN's
 *      `wglShareLists` requirement: "All rendering contexts of a shared
 *      display list must use an identical pixel format." Same constraint
 *      applies to the ARB share path.
 *   2. The popup HGLRC is created via
 *      `wglCreateContextAttribsARB(popupDC, hostHGLRC, attribs)` — the
 *      `hShareContext = hostHGLRC` parameter atomically joins the popup
 *      to the host's share group at creation time, sharing shaders /
 *      programs / textures. Each HWND keeps its own `GrDirectContext`,
 *      not the GL share group.
 *   3. After window creation: `DwmEnableBlurBehindWindow(hwnd, ..)` with
 *      empty region — DWM honors the back-buffer alpha. Re-armed on
 *      WM_DWMCOMPOSITIONCHANGED.
 *   4. `wglSwapIntervalEXT(0)` per popup; `SwapBuffers + DwmFlush()`
 *      provides the actual vsync.
 *
 * Linked into nucleus_tao_windows_native_view.dll.
 * Linked libraries: opengl32.lib gdi32.lib dwmapi.lib (added by build.bat).
 */

#include <jni.h>
#include <windows.h>

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong overlay) {
    (void)env; (void)clazz; (void)overlay;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsOverlayBridge_nativeSwapBuffers(
    JNIEnv *env, jclass clazz, jlong overlay) {
    (void)env; (void)clazz; (void)overlay;
}
