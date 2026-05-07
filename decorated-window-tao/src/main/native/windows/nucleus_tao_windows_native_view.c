/**
 * JNI subview path for the Tao Windows NativeView.
 *
 * Embeds a user-supplied child HWND under the Tao main HWND:
 *   - SetParent + flips WS_CHILD, strips popup/caption/thickframe
 *   - WS_CLIPCHILDREN on parent so the parent's WGL SwapBuffers skips
 *     the child region (otherwise GL paints over the child every frame)
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

static HWND hwnd_from_jlong(jlong v) {
    return (HWND)(uintptr_t)v;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jlong childHwnd) {
    (void)env; (void)clazz;
    HWND parent = hwnd_from_jlong(parentHwnd);
    HWND child = hwnd_from_jlong(childHwnd);
    if (!IsWindow(parent) || !IsWindow(child)) return;

    /* Convert to a child window: strip popup/caption/thickframe, add
     * WS_CHILD, then reparent. Order matters — SetParent on a top-level
     * WS_OVERLAPPED window without flipping styles first triggers
     * Windows to recompute the non-client area twice. */
    LONG_PTR style = GetWindowLongPtrW(child, GWL_STYLE);
    style &= ~(LONG_PTR)(WS_POPUP | WS_OVERLAPPED | WS_CAPTION | WS_THICKFRAME |
                         WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
    style |= WS_CHILD;
    SetWindowLongPtrW(child, GWL_STYLE, style);

    /* Strip extended styles that don't make sense for an embedded child. */
    LONG_PTR ex = GetWindowLongPtrW(child, GWL_EXSTYLE);
    ex &= ~(LONG_PTR)(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE | WS_EX_CLIENTEDGE |
                      WS_EX_STATICEDGE | WS_EX_APPWINDOW);
    SetWindowLongPtrW(child, GWL_EXSTYLE, ex);

    SetParent(child, parent);

    /* Force the frame to be recomputed before the next paint. */
    SetWindowPos(child, NULL, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);

    /* Add WS_CLIPCHILDREN to the parent so its WGL SwapBuffers (and any
     * GDI paint) skips the child's region. Without this, the main scene's
     * GL framebuffer covers the embedded child every frame and the user
     * sees nothing where the child should be. */
    LONG_PTR pstyle = GetWindowLongPtrW(parent, GWL_STYLE);
    if ((pstyle & WS_CLIPCHILDREN) == 0) {
        SetWindowLongPtrW(parent, GWL_STYLE, pstyle | WS_CLIPCHILDREN);
        SetWindowPos(parent, NULL, 0, 0, 0, 0,
                     SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    }

    ShowWindow(child, SW_SHOWNA);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong childHwnd) {
    (void)env; (void)clazz;
    HWND child = hwnd_from_jlong(childHwnd);
    if (!IsWindow(child)) return;
    /* Strip WS_CHILD before SetParent(NULL) so Windows doesn't complain
     * about an orphaned child. The handle is the user's; we don't destroy it. */
    LONG_PTR style = GetWindowLongPtrW(child, GWL_STYLE);
    style &= ~(LONG_PTR)WS_CHILD;
    SetWindowLongPtrW(child, GWL_STYLE, style);
    SetParent(child, NULL);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeSetFrame(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jlong childHwnd,
    jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz; (void)parentHwnd;
    HWND child = hwnd_from_jlong(childHwnd);
    if (!IsWindow(child)) return;
    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;
    SetWindowPos(child, NULL, xPx, yPx, widthPx, heightPx,
                 SWP_NOZORDER | SWP_NOACTIVATE | SWP_DEFERERASE);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsNativeViewBridge_nativeSetCornerRadius(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jlong childHwnd, jfloat radiusPx) {
    (void)env; (void)clazz; (void)parentHwnd;
    HWND child = hwnd_from_jlong(childHwnd);
    if (!IsWindow(child)) return;

    RECT rc;
    if (!GetClientRect(child, &rc)) return;
    int w = rc.right - rc.left;
    int h = rc.bottom - rc.top;
    if (w < 1 || h < 1) return;

    if (radiusPx <= 0.0f) {
        SetWindowRgn(child, NULL, TRUE);
        return;
    }

    /* Cap at min(w, h) / 2 so callers can pass +Inf for fully circular. */
    int cap = (w < h ? w : h) / 2;
    int r = (int)radiusPx;
    if (r > cap || radiusPx > (jfloat)cap) r = cap;
    if (r < 1) r = 1;

    /* CreateRoundRectRgn takes the *diameter*, not the radius. */
    int dia = r * 2;
    HRGN rgn = CreateRoundRectRgn(0, 0, w + 1, h + 1, dia, dia);
    if (!rgn) return;
    /* SetWindowRgn takes ownership on success. */
    if (!SetWindowRgn(child, rgn, TRUE)) {
        DeleteObject(rgn);
    }
}
