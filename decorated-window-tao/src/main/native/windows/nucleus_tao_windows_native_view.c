/**
 * JNI subview path for the Tao Windows NativeView.
 *
 * Embeds a user-supplied child HWND under the Tao main HWND:
 *   - SetParent + flips WS_CHILD, strips popup/caption/thickframe
 *   - WS_CLIPCHILDREN on parent so the parent's GL present skips
 *     the child region (otherwise GL paints over the child every frame)
 *   - SetWindowPos for sizing
 *   - SetWindowRgn(CreateRoundRectRgn) for rounded corners
 *
 * Linked into nucleus_tao_windows_native_view.dll alongside the overlay
 * + popup + DComp bridges (single combined DLL to limit JNI loader hops).
 *
 * Linked libraries: kernel32.lib user32.lib gdi32.lib
 */

#include <jni.h>
#include <windows.h>
#include "nucleus_tao_windows_overlay_internal.h"

#ifndef WM_MOUSEHWHEEL
#define WM_MOUSEHWHEEL 0x020E
#endif

/* /NODEFAULTLIB shim shared across all .c files linked into this DLL. */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

#pragma function(memcpy)
void *memcpy(void *dest, const void *src, size_t count) {
    unsigned char *d = (unsigned char *)dest;
    const unsigned char *s = (const unsigned char *)src;
    while (count--) *d++ = *s++;
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
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeAttach(
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

    /* Add WS_CLIPCHILDREN to the parent so its GL present (and any
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
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDetach(
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
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeSetFrame(
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

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeIsFocusInTree(
    JNIEnv *env, jclass clazz, jlong parentHwnd) {
    (void)env; (void)clazz;
    HWND parent = hwnd_from_jlong(parentHwnd);
    if (!IsWindow(parent)) return JNI_FALSE;
    HWND focused = GetFocus();
    if (!focused) return JNI_FALSE;
    if (focused == parent) return JNI_TRUE;
    return IsChild(parent, focused) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeSetCornerRadius(
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

/* Compose physical pixels (top-left, parent-client) → a mouse message
 * on the embedded child. When [childHwnd] is not a window (WebView2
 * CompositionController, hwnd=0) the message is posted to [parentHwnd]
 * so a parent subclass (sample_webview.cpp SendMouseInput) still sees it. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDispatchPointer(
    JNIEnv *env, jclass clazz,
    jlong parentHwnd, jlong childHwnd,
    jint type, jfloat xPx, jfloat yPx, jint button, jboolean pressed)
{
    (void)env; (void)clazz;
    HWND parent = hwnd_from_jlong(parentHwnd);
    HWND child = hwnd_from_jlong(childHwnd);
    if (!IsWindow(parent)) return;
    HWND target = IsWindow(child) ? child : parent;
    if (type == 1 && IsWindow(child)) SetFocus(child);
    if (nucleus_tao_replay_last_native_input(target)) return;
    POINT pt = { (LONG)xPx, (LONG)yPx };
    if (target != parent) {
        MapWindowPoints(parent, target, &pt, 1);
    }
    UINT msg;
    if (type == 1) {
        msg = (button == 2) ? WM_RBUTTONDOWN :
              (button == 3) ? WM_MBUTTONDOWN : WM_LBUTTONDOWN;
    } else if (type == 2) {
        msg = (button == 2) ? WM_RBUTTONUP :
              (button == 3) ? WM_MBUTTONUP : WM_LBUTTONUP;
    } else {
        msg = WM_MOUSEMOVE;
    }
    WPARAM mk = 0;
    if (button == 2 || (pressed == JNI_TRUE && button == 2)) mk |= MK_RBUTTON;
    else if (button == 3 || (pressed == JNI_TRUE && button == 3)) mk |= MK_MBUTTON;
    else if (button == 1 || pressed == JNI_TRUE) mk |= MK_LBUTTON;
    if (GetKeyState(VK_SHIFT) & 0x8000) mk |= MK_SHIFT;
    if (GetKeyState(VK_CONTROL) & 0x8000) mk |= MK_CONTROL;
    LPARAM lp = MAKELPARAM((short)pt.x, (short)pt.y);
    SendMessageW(target, msg, mk, lp);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDispatchScroll(
    JNIEnv *env, jclass clazz,
    jlong parentHwnd, jlong childHwnd,
    jfloat xPx, jfloat yPx, jfloat dx, jfloat dy)
{
    (void)env; (void)clazz;
    HWND parent = hwnd_from_jlong(parentHwnd);
    HWND child = hwnd_from_jlong(childHwnd);
    if (!IsWindow(parent)) return;
    HWND target = IsWindow(child) ? child : parent;
    if (nucleus_tao_replay_last_native_input(target)) return;
    POINT pt = { (LONG)xPx, (LONG)yPx };
    ClientToScreen(parent, &pt);
    UINT msg = (dx != 0.0f && (dy == 0.0f || (dx > dy || dx < -dy)))
        ? WM_MOUSEHWHEEL : WM_MOUSEWHEEL;
    /* Compose/AWT deltas are already negated vs Win32. */
    short delta = (short)(msg == WM_MOUSEHWHEEL ? (-dx * 120.0f) : (-dy * 120.0f));
    SendMessageW(target, msg, MAKEWPARAM(0, delta), MAKELPARAM((short)pt.x, (short)pt.y));
}

/* ── Diagnostics for the headful suite ──────────────────────────────────
 *
 * A NativeView case needs a real, focusable child HWND — one that takes
 * Win32 keyboard focus on click and shows an I-beam — to race against
 * Compose. The test module cannot create one itself, so these hand out a
 * plain single-line EDIT control and read the focus and the text back.
 * Nothing here is used by NativeView proper. */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDiagCreateEdit(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    /* Hidden top-level: nativeAttach flips it to WS_CHILD and reparents,
     * exactly the path a user-created control takes. */
    HWND edit = CreateWindowExW(
        0, L"EDIT", L"",
        WS_POPUP | ES_LEFT | ES_AUTOHSCROLL,
        0, 0, 64, 24,
        NULL, NULL, GetModuleHandleW(NULL), NULL);
    return (jlong)(uintptr_t)edit;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDiagDestroyWindow(
    JNIEnv *env, jclass clazz, jlong hwnd) {
    (void)env; (void)clazz;
    HWND h = hwnd_from_jlong(hwnd);
    if (IsWindow(h)) DestroyWindow(h);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDiagFocusedHwnd(
    JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jlong)(uintptr_t)GetFocus();
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDiagWindowText(
    JNIEnv *env, jclass clazz, jlong hwnd) {
    (void)clazz;
    HWND h = hwnd_from_jlong(hwnd);
    if (!IsWindow(h)) return NULL;
    WCHAR buf[512];
    int len = GetWindowTextW(h, buf, 512);
    if (len < 0) len = 0;
    return (*env)->NewString(env, (const jchar *)buf, (jsize)len);
}

/* The control's rectangle in its parent's client coordinates (physical
 * px, top-left origin) as `[x, y, w, h]`, or null when not a window. */
JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsNativeViewBridge_nativeDiagWindowFrame(
    JNIEnv *env, jclass clazz, jlong hwnd) {
    (void)clazz;
    HWND h = hwnd_from_jlong(hwnd);
    if (!IsWindow(h)) return NULL;
    HWND parent = GetParent(h);
    RECT rect;
    if (!GetWindowRect(h, &rect)) return NULL;
    POINT corners[2] = { { rect.left, rect.top }, { rect.right, rect.bottom } };
    if (parent) MapWindowPoints(NULL, parent, corners, 2);
    jint out[4] = { corners[0].x, corners[0].y, corners[1].x - corners[0].x, corners[1].y - corners[0].y };
    jintArray result = (*env)->NewIntArray(env, 4);
    if (result == NULL) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 4, out);
    return result;
}
