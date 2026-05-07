/**
 * JNI popup HWND lifecycle for the Tao Windows NativeView.
 *
 * Each popup is a top-level WS_POPUP HWND owned by the parent (the Tao
 * main HWND, even for nested popups — single-level owner chain) with
 * WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW. Each owns its own transparent
 * WGL context joined to the host's share group via the shared
 * `nucleus_tao_overlay_gl_init` (rendering delegated to overlay_gl.c).
 *
 * Outside-click dismissal (per NATIVE_VIEW_WINDOWS_PLAN.md "Input/focus"):
 *   - `SetCapture(hwndPopup)` immediately after `ShowWindow(SW_SHOWNOACTIVATE)`
 *     when an outside-click monitor is installed.
 *   - In WM_LBUTTONDOWN/RBUTTONDOWN/MBUTTONDOWN: ClientToScreen +
 *     GetWindowRect + PtInRect; if outside, fire JNI listener; if
 *     inside, dispatch to Compose.
 *   - WM_CAPTURECHANGED handles capture loss (Alt-Tab, system modal,
 *     nested popup taking capture). Capture transfer to a known sibling
 *     popup is tracked via the active-popup chain so the outer popup
 *     doesn't treat it as outside-click.
 *
 * Replaces the heavier WH_MOUSE_LL global hook from earlier drafts.
 *
 * Linked into nucleus_tao_windows_native_view.dll.
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>
#include "nucleus_tao_windows_overlay_internal.h"

#define EVT_PTR_DOWN  1
#define EVT_PTR_UP    2
#define EVT_PTR_MOVE  3

#define BTN_NONE      0
#define BTN_PRIMARY   1
#define BTN_SECONDARY 2
#define BTN_MIDDLE    3

#define WHEEL_DELTA_F  120.0f

typedef struct PopupState PopupState;
struct PopupState {
    HWND parent;
    GlSurface gl;
    BOOL focusable;

    /* JNI callbacks (jobject global refs). */
    jobject eventCb;
    jobject outsideListener;

    /* Set TRUE while SetCapture is held by this popup. */
    BOOL captureHeld;

    /* Linked list of currently-open popups (for capture handoff
     * disambiguation between the outer menu and a freshly-opened
     * child). The list is ordered most-recent-first. */
    PopupState *nextOpen;
};

/* ============================================================ */
/*  Globals                                                     */
/* ============================================================ */

static const wchar_t *kPopupClassName = L"NucleusTaoPopupCls";
static volatile LONG sPopupClassRegistered = 0;

static CRITICAL_SECTION sOpenListLock;
static volatile LONG sOpenListInited = 0;
static PopupState *sOpenChainHead = NULL;

static JavaVM *sJVM = NULL;
static jclass sEventCbClass = NULL;
static jmethodID sOnPointerMethod = NULL; /* (IFFII)V */
static jmethodID sOnScrollMethod = NULL;  /* (FFFF)V */
static jmethodID sOnKeyMethod = NULL;     /* (IIII)V */
static jclass sOutsideClass = NULL;
static jmethodID sOnOutsideClickMethod = NULL; /* (II)V */
static volatile LONG sCacheInitedBits = 0;

typedef DPI_AWARENESS_CONTEXT (WINAPI *PFN_SetThreadDpiAwarenessContext)(DPI_AWARENESS_CONTEXT);
static PFN_SetThreadDpiAwarenessContext pSetThreadDpiAwarenessContext = NULL;
static volatile LONG sDpiResolved = 0;

static void ensureGlobalsInit(void) {
    if (InterlockedCompareExchange(&sOpenListInited, 1, 0) == 0) {
        InitializeCriticalSection(&sOpenListLock);
    }
    if (InterlockedCompareExchange(&sDpiResolved, 1, 0) == 0) {
        HMODULE u = GetModuleHandleW(L"user32.dll");
        if (u) {
            pSetThreadDpiAwarenessContext = (PFN_SetThreadDpiAwarenessContext)
                GetProcAddress(u, "SetThreadDpiAwarenessContext");
        }
    }
}

static JNIEnv *attachThread(void) {
    if (sJVM == NULL) return NULL;
    JNIEnv *env = NULL;
    jint st = (*sJVM)->GetEnv(sJVM, (void **)&env, JNI_VERSION_1_8);
    if (st == JNI_OK) return env;
    if (st == JNI_EDETACHED &&
        (*sJVM)->AttachCurrentThreadAsDaemon(sJVM, (void **)&env, NULL) == JNI_OK) {
        return env;
    }
    return NULL;
}

static void ensureEventCallbackCache(JNIEnv *env, jobject sample) {
    if (sCacheInitedBits & 1) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (!sample) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (!local) return;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return;
    jmethodID m1 = (*env)->GetMethodID(env, global, "onPointerEvent", "(IFFII)V");
    jmethodID m2 = (*env)->GetMethodID(env, global, "onScroll", "(FFFF)V");
    jmethodID m3 = (*env)->GetMethodID(env, global, "onKeyEvent", "(IIII)V");
    if (m1 && m2 && m3) {
        sEventCbClass = global;
        sOnPointerMethod = m1; sOnScrollMethod = m2; sOnKeyMethod = m3;
        InterlockedOr(&sCacheInitedBits, 1);
    } else {
        (*env)->DeleteGlobalRef(env, global);
    }
}

static void ensureOutsideCallbackCache(JNIEnv *env, jobject sample) {
    if (sCacheInitedBits & 2) return;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    if (!sample) return;
    jclass local = (*env)->GetObjectClass(env, sample);
    if (!local) return;
    jclass global = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!global) return;
    jmethodID m = (*env)->GetMethodID(env, global, "onOutsideClick", "(II)V");
    if (m) {
        sOutsideClass = global; sOnOutsideClickMethod = m;
        InterlockedOr(&sCacheInitedBits, 2);
    } else {
        (*env)->DeleteGlobalRef(env, global);
    }
}

/* ============================================================ */
/*  Active-popup chain (capture handoff disambiguation)          */
/* ============================================================ */

static void chainAdd(PopupState *p) {
    EnterCriticalSection(&sOpenListLock);
    p->nextOpen = sOpenChainHead;
    sOpenChainHead = p;
    LeaveCriticalSection(&sOpenListLock);
}

static void chainRemove(PopupState *p) {
    EnterCriticalSection(&sOpenListLock);
    PopupState **pp = &sOpenChainHead;
    while (*pp && *pp != p) pp = &(*pp)->nextOpen;
    if (*pp == p) *pp = p->nextOpen;
    p->nextOpen = NULL;
    LeaveCriticalSection(&sOpenListLock);
}

static BOOL isCaptureHwndKnownPopupLocked(HWND captureHwnd) {
    for (PopupState *q = sOpenChainHead; q; q = q->nextOpen) {
        if (q->gl.hwnd == captureHwnd) return TRUE;
    }
    return FALSE;
}

/* ============================================================ */
/*  Event dispatch helpers                                      */
/* ============================================================ */

static int modifierMask(void) {
    int m = 0;
    if (GetKeyState(VK_SHIFT)   & 0x8000) m |= 0x01;
    if (GetKeyState(VK_CONTROL) & 0x8000) m |= 0x02;
    if (GetKeyState(VK_MENU)    & 0x8000) m |= 0x04;
    if (GetKeyState(VK_LWIN)    & 0x8000) m |= 0x08;
    if (GetKeyState(VK_RWIN)    & 0x8000) m |= 0x08;
    return m;
}

static void dispatchPointer(PopupState *p, int type, int button, LPARAM lParam) {
    if (!p->eventCb || !sOnPointerMethod) return;
    JNIEnv *env = attachThread();
    if (!env) return;
    int x = (short)LOWORD(lParam);
    int y = (short)HIWORD(lParam);
    (*env)->CallVoidMethod(env, p->eventCb, sOnPointerMethod,
        (jint)type, (jfloat)x, (jfloat)y, (jint)button, (jint)modifierMask());
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void dispatchScroll(PopupState *p, int xLocal, int yLocal,
                           float dx, float dy) {
    if (!p->eventCb || !sOnScrollMethod) return;
    JNIEnv *env = attachThread();
    if (!env) return;
    (*env)->CallVoidMethod(env, p->eventCb, sOnScrollMethod,
        (jfloat)xLocal, (jfloat)yLocal, (jfloat)dx, (jfloat)dy);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void fireOutsideClick(PopupState *p, int button) {
    if (!p->outsideListener || !sOnOutsideClickMethod) return;
    JNIEnv *env = attachThread();
    if (!env) return;
    (*env)->CallVoidMethod(env, p->outsideListener, sOnOutsideClickMethod,
        (jint)1 /* press */, (jint)button);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* If [screen] is outside [hwnd]'s rect, fires the outside listener and
 * returns TRUE (caller should skip dispatching to Compose). */
static BOOL handleClickAgainstCapture(PopupState *p, int button, POINT screen) {
    if (!p->captureHeld || !p->outsideListener) return FALSE;
    RECT rc;
    if (!GetWindowRect(p->gl.hwnd, &rc)) return FALSE;
    if (PtInRect(&rc, screen)) return FALSE;
    fireOutsideClick(p, button);
    return TRUE;
}

/* ============================================================ */
/*  Popup WndProc                                               */
/* ============================================================ */

static LRESULT CALLBACK popupWndProc(HWND hwnd, UINT msg, WPARAM w, LPARAM l) {
    PopupState *p = (PopupState *)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_MOUSEACTIVATE:
        return p && p->focusable ? MA_ACTIVATE : MA_NOACTIVATE;

    case WM_NCHITTEST:
        /* Popup is interactive everywhere inside its bounds. */
        return HTCLIENT;

    case WM_LBUTTONDOWN:
    case WM_RBUTTONDOWN:
    case WM_MBUTTONDOWN: {
        if (!p) break;
        int btn = (msg == WM_LBUTTONDOWN) ? BTN_PRIMARY :
                  (msg == WM_RBUTTONDOWN) ? BTN_SECONDARY : BTN_MIDDLE;
        /* When SetCapture is held by us, the click point may land outside
         * our window rect — that's the case for outside-click dismissal.
         * Test the screen point against our rect; if outside, fire the
         * listener and DON'T dispatch to Compose. */
        POINT screen;
        screen.x = (short)LOWORD(l);
        screen.y = (short)HIWORD(l);
        ClientToScreen(hwnd, &screen);
        if (handleClickAgainstCapture(p, btn, screen)) return 0;
        dispatchPointer(p, EVT_PTR_DOWN, btn, l);
        return 0;
    }
    case WM_LBUTTONUP:   if (p) dispatchPointer(p, EVT_PTR_UP,   BTN_PRIMARY, l);   return 0;
    case WM_RBUTTONUP:   if (p) dispatchPointer(p, EVT_PTR_UP,   BTN_SECONDARY, l); return 0;
    case WM_MBUTTONUP:   if (p) dispatchPointer(p, EVT_PTR_UP,   BTN_MIDDLE, l);    return 0;
    case WM_MOUSEMOVE:   if (p) dispatchPointer(p, EVT_PTR_MOVE, BTN_NONE, l);      return 0;

    case WM_MOUSEWHEEL: {
        if (!p) break;
        short delta = (short)HIWORD(w);
        POINT pt; pt.x = (short)LOWORD(l); pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        dispatchScroll(p, pt.x, pt.y, 0.0f, (float)delta / WHEEL_DELTA_F);
        return 0;
    }
    case WM_MOUSEHWHEEL: {
        if (!p) break;
        short delta = (short)HIWORD(w);
        POINT pt; pt.x = (short)LOWORD(l); pt.y = (short)HIWORD(l);
        ScreenToClient(hwnd, &pt);
        dispatchScroll(p, pt.x, pt.y, (float)delta / WHEEL_DELTA_F, 0.0f);
        return 0;
    }

    case WM_CAPTURECHANGED: {
        /* Capture transferred to another HWND. Disambiguate: if the
         * receiver is a known sibling popup, it's an inner menu opening
         * — keep the listener installed (we'll re-acquire on dismiss).
         * Otherwise (Alt-Tab, system modal, foreign window) treat as
         * implicit dismiss: clear our capture state. */
        if (p) {
            HWND newCaptureHwnd = (HWND)l;
            EnterCriticalSection(&sOpenListLock);
            BOOL knownSibling = newCaptureHwnd && isCaptureHwndKnownPopupLocked(newCaptureHwnd);
            LeaveCriticalSection(&sOpenListLock);
            if (!knownSibling) {
                p->captureHeld = FALSE;
            }
        }
        return 0;
    }

    case WM_DPICHANGED: {
        const RECT *prc = (const RECT *)l;
        if (prc) {
            SetWindowPos(hwnd, NULL, prc->left, prc->top,
                         prc->right - prc->left, prc->bottom - prc->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
        }
        return 0;
    }

    case WM_ERASEBKGND:
        return 1;

    case WM_DWMCOMPOSITIONCHANGED:
        if (p) nucleus_tao_overlay_gl_rearm_blur(&p->gl);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, w, l);
}

static void ensurePopupClassRegistered(void) {
    if (InterlockedCompareExchange(&sPopupClassRegistered, 1, 0) != 0) return;
    WNDCLASSW wc;
    ZeroMemory(&wc, sizeof(wc));
    /* CS_OWNDC: stable HDC required for wglMakeCurrent across frames. */
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = popupWndProc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = kPopupClassName;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = NULL;
    RegisterClassW(&wc);
}

/* ============================================================ */
/*  JNI exports                                                 */
/* ============================================================ */

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeCreatePanel(
    JNIEnv *env, jclass clazz, jlong parentHwnd, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)clazz;
    if (sJVM == NULL) (*env)->GetJavaVM(env, &sJVM);
    HWND parent = (HWND)(uintptr_t)parentHwnd;
    if (!IsWindow(parent)) return 0;
    ensureGlobalsInit();
    ensurePopupClassRegistered();

    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;

    /* Resolve parent client → screen for initial placement. */
    POINT origin = {0, 0};
    ClientToScreen(parent, &origin);

    DPI_AWARENESS_CONTEXT prevDpi = NULL;
    if (pSetThreadDpiAwarenessContext) {
        prevDpi = pSetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }

    HWND hwnd = CreateWindowExW(
        WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW,
        kPopupClassName, L"",
        WS_POPUP,
        origin.x + xPx, origin.y + yPx, widthPx, heightPx,
        parent, NULL, GetModuleHandleW(NULL), NULL);

    if (pSetThreadDpiAwarenessContext && prevDpi) {
        pSetThreadDpiAwarenessContext(prevDpi);
    }

    if (!hwnd) return 0;

    PopupState *p = (PopupState *)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY,
                                            sizeof(PopupState));
    if (!p) { DestroyWindow(hwnd); return 0; }
    p->parent = parent;
    p->gl.hwnd = hwnd;
    p->focusable = FALSE;
    SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)p);

    if (!nucleus_tao_overlay_gl_init(&p->gl)) {
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        DestroyWindow(hwnd);
        HeapFree(GetProcessHeap(), 0, p);
        return 0;
    }

    chainAdd(p);
    ShowWindow(hwnd, SW_SHOWNOACTIVATE);
    return (jlong)(uintptr_t)p;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSetFrameInWindow(
    JNIEnv *env, jclass clazz, jlong panel, jint xPx, jint yPx, jint widthPx, jint heightPx) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !IsWindow(p->gl.hwnd) || !IsWindow(p->parent)) return;
    if (widthPx < 1) widthPx = 1;
    if (heightPx < 1) heightPx = 1;
    POINT origin = {0, 0};
    ClientToScreen(p->parent, &origin);
    SetWindowPos(p->gl.hwnd, NULL,
        origin.x + xPx, origin.y + yPx, widthPx, heightPx,
        SWP_NOACTIVATE | SWP_NOZORDER);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSetFocusable(
    JNIEnv *env, jclass clazz, jlong panel, jboolean focusable) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    p->focusable = focusable ? TRUE : FALSE;
}

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeContentHwnd(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    return p ? (jlong)(uintptr_t)p->gl.hwnd : 0;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !p->gl.hdc || !p->gl.hglrc) return JNI_FALSE;
    return wglMakeCurrent(p->gl.hdc, p->gl.hglrc) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSwapBuffers(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)env; (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !p->gl.hdc) return;
    SwapBuffers(p->gl.hdc);
    DwmFlush();
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeSetEventCallback(
    JNIEnv *env, jclass clazz, jlong panel, jobject callback) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    if (callback) ensureEventCallbackCache(env, callback);
    jobject prev = p->eventCb;
    p->eventCb = callback ? (*env)->NewGlobalRef(env, callback) : NULL;
    if (prev) (*env)->DeleteGlobalRef(env, prev);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeInstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel, jobject listener) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p || !listener) return;
    ensureOutsideCallbackCache(env, listener);
    jobject prev = p->outsideListener;
    p->outsideListener = (*env)->NewGlobalRef(env, listener);
    if (prev) (*env)->DeleteGlobalRef(env, prev);
    /* Acquire mouse capture so clicks anywhere in the process route to
     * our WndProc — letting us test inside-vs-outside in WM_*BUTTONDOWN.
     * For a nested popup, this transfers capture from the parent menu;
     * the parent's WM_CAPTURECHANGED handler keeps captureHeld TRUE
     * because we're a known sibling. */
    SetCapture(p->gl.hwnd);
    p->captureHeld = TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeUninstallOutsideClickMonitor(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    if (p->captureHeld && GetCapture() == p->gl.hwnd) {
        ReleaseCapture();
    }
    p->captureHeld = FALSE;
    if (p->outsideListener) {
        (*env)->DeleteGlobalRef(env, p->outsideListener);
        p->outsideListener = NULL;
    }
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_PopupNativeBridgeWindows_nativeRelease(
    JNIEnv *env, jclass clazz, jlong panel) {
    (void)clazz;
    PopupState *p = (PopupState *)(uintptr_t)panel;
    if (!p) return;
    if (p->captureHeld && GetCapture() == p->gl.hwnd) {
        ReleaseCapture();
    }
    chainRemove(p);
    nucleus_tao_overlay_gl_destroy(&p->gl);
    if (IsWindow(p->gl.hwnd)) {
        SetWindowLongPtrW(p->gl.hwnd, GWLP_USERDATA, 0);
        DestroyWindow(p->gl.hwnd);
    }
    if (p->eventCb) { (*env)->DeleteGlobalRef(env, p->eventCb); p->eventCb = NULL; }
    if (p->outsideListener) { (*env)->DeleteGlobalRef(env, p->outsideListener); p->outsideListener = NULL; }
    HeapFree(GetProcessHeap(), 0, p);
}
