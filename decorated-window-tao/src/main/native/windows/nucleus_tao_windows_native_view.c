/**
 * JNI subview path for the Tao Windows NativeView.
 *
 * Embeds a user-supplied child HWND under the Tao main HWND:
 *   - SetParent + flips WS_CHILD, strips popup/caption styles
 *   - SetWindowPos for sizing
 *   - SetWindowRgn(CreateRoundRectRgn) for rounded corners
 *
 * Linked into nucleus_tao_windows_native_view.dll alongside the overlay
 * + popup + WGL bridges (single combined DLL to limit JNI loader hops).
 *
 * Linked libraries: kernel32.lib user32.lib gdi32.lib
 */

#include <jni.h>
#include <windows.h>

/* /NODEFAULTLIB shim shared across all .c files linked into this DLL. */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
    (void)hinst; (void)reason; (void)reserved;
    return TRUE;
}

/* Phase 1: stubs only. Phase 2 implements full SetParent/SetWindowPos/SetWindowRgn. */

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jlong childHwnd) {
    (void)env; (void)clazz; (void)parentHwnd; (void)childHwnd;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong childHwnd) {
    (void)env; (void)clazz; (void)childHwnd;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeSetFrame(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jlong childHwnd,
    jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz; (void)parentHwnd; (void)childHwnd;
    (void)xPx; (void)yPx; (void)widthPx; (void)heightPx;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeSetCornerRadius(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jlong childHwnd, jfloat radiusPx) {
    (void)env; (void)clazz; (void)parentHwnd; (void)childHwnd; (void)radiusPx;
}
