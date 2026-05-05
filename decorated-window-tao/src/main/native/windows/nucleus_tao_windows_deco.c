/**
 * JNI bridge for Tao-window custom decoration on Windows.
 *
 * Subclasses the HWND created by Tao to:
 *   - WM_NCCALCSIZE: extend client area into the title bar
 *   - WM_NCHITTEST: 3-zone hit test (resize borders, caption, client)
 *   - WM_NCMOUSEMOVE: forward as WM_MOUSEMOVE for Compose pointer tracking
 *   - DwmExtendFrameIntoClientArea for DWM shadow
 *
 * Forked from decorated-window-jni's nucleus_windows_decoration.c with the
 * Skiko-AWT child-window plumbing removed: a Tao window has no child HWND —
 * the WGL render surface is the HWND itself.
 *
 * Per-HWND state stored via SetProp/GetProp.
 * Linked libraries: kernel32.lib user32.lib dwmapi.lib gdi32.lib shell32.lib
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>

/* /NODEFAULTLIB support */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

#ifndef SM_CXPADDEDBORDERWIDTH
#define SM_CXPADDEDBORDERWIDTH 92
#endif

/* DPI-aware function pointers (resolved once) */
typedef UINT (WINAPI *PFN_GetDpiForWindow)(HWND);
typedef int  (WINAPI *PFN_GetSystemMetricsForDpi)(int, UINT);

static PFN_GetDpiForWindow         pGetDpiForWindow = NULL;
static PFN_GetSystemMetricsForDpi  pGetSystemMetricsForDpi = NULL;
static volatile BOOL dpiApiResolved = FALSE;

static void resolveDpiApis(void) {
    if (dpiApiResolved) return;
    HMODULE hUser32 = GetModuleHandleA("user32.dll");
    if (hUser32) {
        pGetDpiForWindow = (PFN_GetDpiForWindow)
            GetProcAddress(hUser32, "GetDpiForWindow");
        pGetSystemMetricsForDpi = (PFN_GetSystemMetricsForDpi)
            GetProcAddress(hUser32, "GetSystemMetricsForDpi");
    }
    dpiApiResolved = TRUE;
}

static UINT getDpi(HWND hwnd) {
    if (pGetDpiForWindow) return pGetDpiForWindow(hwnd);
    HDC hdc = GetDC(hwnd);
    UINT dpi = (UINT)GetDeviceCaps(hdc, LOGPIXELSX);
    ReleaseDC(hwnd, hdc);
    return dpi;
}

static int getSystemMetrics(int index, UINT dpi) {
    if (pGetSystemMetricsForDpi) return pGetSystemMetricsForDpi(index, dpi);
    return GetSystemMetrics(index);
}

/* Per-HWND state */
static const wchar_t *PROP_NAME = L"NucleusTaoDecoState";

typedef struct {
    WNDPROC originalWndProc;
    int     titleBarHeightPx;
    COLORREF bgColor;
    BOOL    isFullscreen;
    LONG    savedStyle;
    LONG    savedExStyle;
    WINDOWPLACEMENT savedPlacement;
} DecoState;

static DecoState *getState(HWND hwnd) {
    return (DecoState *)GetPropW(hwnd, PROP_NAME);
}

static int getResizeBorderWidth(HWND hwnd, BOOL isVertical) {
    UINT dpi = getDpi(hwnd);
    int frameMetric = isVertical ? SM_CXSIZEFRAME : SM_CYSIZEFRAME;
    return getSystemMetrics(frameMetric, dpi)
         + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
}

static BOOL isAutoHideTaskbar(UINT edge, RECT monitorRect) {
    APPBARDATA abd;
    abd.cbSize = sizeof(abd);
    abd.uEdge = edge;
    abd.rc = monitorRect;
    return (BOOL)SHAppBarMessage(ABM_GETAUTOHIDEBAR, &abd);
}

/* WndProc subclass */
static LRESULT CALLBACK decoWndProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    DecoState *state = getState(hwnd);
    if (!state) return DefWindowProcW(hwnd, msg, wParam, lParam);

    switch (msg) {

    /* No-op: the GL back buffer already holds the latest frame. Filling here
     * (even with the title-bar color) flashes between the fill and the next
     * SwapBuffers — visible as solid-color scintillation while dragging or
     * resizing. Returning 1 tells Windows the area was "erased" without
     * painting anything; SwapBuffers in the next render cycle restores it. */
    case WM_ERASEBKGND:
        return 1;

    case WM_NCCALCSIZE: {
        if (!wParam) break;
        if (state->isFullscreen) return 0;

        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lParam;
        RECT originalTop = params->rgrc[0];

        LRESULT result = CallWindowProcW(state->originalWndProc,
                                          hwnd, msg, wParam, lParam);

        params->rgrc[0].top = originalTop.top;

        if (IsZoomed(hwnd)) {
            UINT dpi = getDpi(hwnd);
            int borderWidth = getSystemMetrics(SM_CYSIZEFRAME, dpi)
                            + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
            params->rgrc[0].top += borderWidth;

            HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO mi;
            mi.cbSize = sizeof(mi);
            if (GetMonitorInfoW(hMon, &mi)) {
                if (params->rgrc[0].top == mi.rcMonitor.top
                    && isAutoHideTaskbar(ABE_TOP, mi.rcMonitor)) {
                    params->rgrc[0].top += 1;
                }
                if (params->rgrc[0].bottom == mi.rcMonitor.bottom
                    && isAutoHideTaskbar(ABE_BOTTOM, mi.rcMonitor)) {
                    params->rgrc[0].bottom -= 1;
                }
                if (params->rgrc[0].left == mi.rcMonitor.left
                    && isAutoHideTaskbar(ABE_LEFT, mi.rcMonitor)) {
                    params->rgrc[0].left += 1;
                }
                if (params->rgrc[0].right == mi.rcMonitor.right
                    && isAutoHideTaskbar(ABE_RIGHT, mi.rcMonitor)) {
                    params->rgrc[0].right -= 1;
                }
            }
        }

        return result;
    }

    case WM_NCHITTEST: {
        POINT pt;
        pt.x = (short)LOWORD(lParam);
        pt.y = (short)HIWORD(lParam);

        RECT windowRect;
        GetWindowRect(hwnd, &windowRect);

        int borderWidth = getResizeBorderWidth(hwnd, TRUE);
        int borderHeight = getResizeBorderWidth(hwnd, FALSE);

        if (!IsZoomed(hwnd) && !state->isFullscreen) {
            if (pt.x < windowRect.left + borderWidth &&
                pt.y < windowRect.top + borderHeight) return HTTOPLEFT;
            if (pt.x >= windowRect.right - borderWidth &&
                pt.y < windowRect.top + borderHeight) return HTTOPRIGHT;
            if (pt.x < windowRect.left + borderWidth &&
                pt.y >= windowRect.bottom - borderHeight) return HTBOTTOMLEFT;
            if (pt.x >= windowRect.right - borderWidth &&
                pt.y >= windowRect.bottom - borderHeight) return HTBOTTOMRIGHT;
            if (pt.x < windowRect.left + borderWidth) return HTLEFT;
            if (pt.x >= windowRect.right - borderWidth) return HTRIGHT;
            if (pt.y < windowRect.top + borderHeight) return HTTOP;
            if (pt.y >= windowRect.bottom - borderHeight) return HTBOTTOM;
        }

        /* Title bar zone — always HTCLIENT.
         * NEVER return HTMINBUTTON/HTMAXBUTTON/HTCLOSE: DWM would draw native
         * buttons on top of our Compose UI. Compose handles the whole title
         * bar, including its own min/max/close buttons; unconsumed clicks call
         * window.dragWindow() which posts WM_NCLBUTTONDOWN HTCAPTION via Tao. */
        if (pt.y < windowRect.top + state->titleBarHeightPx) {
            return HTCLIENT;
        }

        return HTCLIENT;
    }

    case WM_NCLBUTTONDOWN: {
        if (wParam == HTCAPTION) {
            ReleaseCapture();
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        break;
    }

    case WM_NCLBUTTONDBLCLK: {
        if (wParam == HTCAPTION) {
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        break;
    }

    case WM_NCMOUSEMOVE: {
        POINT pt;
        pt.x = (short)LOWORD(lParam);
        pt.y = (short)HIWORD(lParam);
        ScreenToClient(hwnd, &pt);
        PostMessageW(hwnd, WM_MOUSEMOVE, 0, MAKELPARAM(pt.x, pt.y));
        break;
    }

    case WM_SYSCOMMAND: {
        if (state->isFullscreen) {
            WPARAM cmd = wParam & 0xFFF0;
            if (cmd == SC_RESTORE || cmd == SC_MAXIMIZE ||
                cmd == SC_SIZE   || cmd == SC_MOVE) {
                return 0;
            }
        }
        break;
    }

    case WM_NCDESTROY: {
        if (state->isFullscreen) {
            SetWindowLongW(hwnd, GWL_STYLE, state->savedStyle);
            SetWindowLongW(hwnd, GWL_EXSTYLE, state->savedExStyle);
        }
        WNDPROC origProc = state->originalWndProc;
        RemovePropW(hwnd, PROP_NAME);
        HeapFree(GetProcessHeap(), 0, state);
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)origProc);
        return CallWindowProcW(origProc, hwnd, msg, wParam, lParam);
    }

    } /* end switch */

    return CallWindowProcW(state->originalWndProc, hwnd, msg, wParam, lParam);
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)lpvReserved;
    if (fdwReason == DLL_PROCESS_ATTACH) {
        resolveDpiApis();
    }
    return TRUE;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: io.github.kdroidfilter.nucleus.window.tao                 */
/*  Class:   NativeTaoWindowsDecoBridge                                */
/* ================================================================== */

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeInstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint titleBarHeightPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *existing = getState(hwnd);
    if (existing) {
        existing->titleBarHeightPx = (int)titleBarHeightPx;
        return;
    }

    DecoState *state = (DecoState *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(DecoState));
    if (!state) return;

    state->titleBarHeightPx = (int)titleBarHeightPx;
    state->bgColor = RGB(255, 255, 255);

    SetPropW(hwnd, PROP_NAME, (HANDLE)state);

    LONG_PTR prevWndProc = SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC, (LONG_PTR)decoWndProc);
    state->originalWndProc = (WNDPROC)prevWndProc;

    /* Extend bottom by 1px to keep DWM shadow without enabling glass over the
     * client area. With {0,0,0,1} DWM treats the client area as opaque, so
     * transparent pixels render as black (invisible on dark themes). */
    MARGINS margins = {0, 0, 0, 1};
    DwmExtendFrameIntoClientArea(hwnd, &margins);

    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeUninstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)state->originalWndProc);
    RemovePropW(hwnd, PROP_NAME);
    HeapFree(GetProcessHeap(), 0, state);

    MARGINS margins = {0, 0, 0, 0};
    DwmExtendFrameIntoClientArea(hwnd, &margins);

    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeSetTitleBarHeight(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint heightPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;
    DecoState *state = getState(hwnd);
    if (state) state->titleBarHeightPx = (int)heightPx;
}

/* Background color (ARGB) — synced to DWM caption/border color and dark-mode
 * flag so the "sheet of glass" composited during resize matches the theme. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeSetBackgroundColor(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint argb)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    int r = (argb >> 16) & 0xFF;
    int g = (argb >>  8) & 0xFF;
    int b =  argb        & 0xFF;
    COLORREF color = RGB(r, g, b);

    DecoState *state = getState(hwnd);
    if (state) state->bgColor = color;

    DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */,
                          &color, sizeof(color));
    DwmSetWindowAttribute(hwnd, 34 /* DWMWA_BORDER_COLOR */,
                          &color, sizeof(color));

    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
    BOOL isDark = (luminance < 128) ? TRUE : FALSE;
    DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */,
                          &isDark, sizeof(isDark));
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeSetFullscreen(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean fullscreen)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    if (fullscreen) {
        if (state->isFullscreen) return;

        state->savedStyle = GetWindowLongW(hwnd, GWL_STYLE);
        state->savedExStyle = GetWindowLongW(hwnd, GWL_EXSTYLE);
        state->savedPlacement.length = sizeof(WINDOWPLACEMENT);
        GetWindowPlacement(hwnd, &state->savedPlacement);

        HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO mi;
        mi.cbSize = sizeof(mi);
        GetMonitorInfoW(hMon, &mi);

        BOOL disableTransitions = TRUE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &disableTransitions, sizeof(disableTransitions));

        if (state->savedPlacement.showCmd == SW_SHOWMAXIMIZED) {
            WINDOWPLACEMENT wp = state->savedPlacement;
            wp.rcNormalPosition.left   = mi.rcMonitor.left;
            wp.rcNormalPosition.top    = mi.rcMonitor.top;
            wp.rcNormalPosition.right  = mi.rcMonitor.right;
            wp.rcNormalPosition.bottom = mi.rcMonitor.bottom;
            SetWindowPlacement(hwnd, &wp);
        }

        state->isFullscreen = TRUE;

        LONG style = state->savedStyle
            & ~(LONG)(WS_CAPTION | WS_THICKFRAME | WS_MAXIMIZE);
        SetWindowLongW(hwnd, GWL_STYLE, style);

        LONG exStyle = state->savedExStyle
            & ~(LONG)(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE
                     | WS_EX_CLIENTEDGE | WS_EX_STATICEDGE);
        SetWindowLongW(hwnd, GWL_EXSTYLE, exStyle);

        SetWindowPos(hwnd, HWND_TOPMOST,
            mi.rcMonitor.left, mi.rcMonitor.top,
            mi.rcMonitor.right - mi.rcMonitor.left,
            mi.rcMonitor.bottom - mi.rcMonitor.top,
            SWP_FRAMECHANGED);

        BOOL enableTransitions = FALSE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &enableTransitions, sizeof(enableTransitions));
    } else {
        if (!state->isFullscreen) return;

        state->isFullscreen = FALSE;

        SetWindowLongW(hwnd, GWL_EXSTYLE, state->savedExStyle);
        LONG restoreStyle = state->savedStyle & ~(LONG)WS_MAXIMIZE;
        SetWindowLongW(hwnd, GWL_STYLE, restoreStyle);
        SetWindowPlacement(hwnd, &state->savedPlacement);
        SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED);
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeIsFullscreen(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return JNI_FALSE;
    DecoState *state = getState(hwnd);
    return (state && state->isFullscreen) ? JNI_TRUE : JNI_FALSE;
}

/* Establishes a parent-child (owner) relationship between two HWNDs via
 * GWLP_HWNDPARENT. The child window:
 *   - stays above the owner in z-order
 *   - is hidden when the owner is minimised
 *   - does not appear in the taskbar
 * Used by DecoratedDialog to make the dialog behave like a real JDialog.
 * Pass ownerHwndLong == 0 to clear the owner. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeSetOwner(
    JNIEnv *env, jclass clazz, jlong childHwndLong, jlong ownerHwndLong)
{
    (void)env; (void)clazz;
    HWND child = (HWND)(uintptr_t)childHwndLong;
    HWND owner = (HWND)(uintptr_t)ownerHwndLong;
    if (!child) return;
#if defined(_WIN64)
    SetWindowLongPtrW(child, GWLP_HWNDPARENT, (LONG_PTR)owner);
#else
    SetWindowLongW(child, GWLP_HWNDPARENT, (LONG)(LONG_PTR)owner);
#endif
}

/* Wraps EnableWindow. Disabling the owner of a modal dialog blocks input on
 * the parent while the dialog is up — same behaviour as a native MessageBox
 * or a modal JDialog. */
JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeSetEnabled(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean enabled)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;
    EnableWindow(hwnd, enabled ? TRUE : FALSE);
}

/* Returns [x, y, width, height] of the window's outer bounds in screen
 * coordinates (physical pixels). Used by DecoratedDialog to centre itself on
 * its parent. Returns NULL if hwnd is invalid. */
JNIEXPORT jlongArray JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoWindowsDecoBridge_nativeGetWindowRect(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return NULL;
    RECT r;
    if (!GetWindowRect(hwnd, &r)) return NULL;
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (!arr) return NULL;
    jlong values[4];
    values[0] = (jlong)r.left;
    values[1] = (jlong)r.top;
    values[2] = (jlong)(r.right - r.left);
    values[3] = (jlong)(r.bottom - r.top);
    (*env)->SetLongArrayRegion(env, arr, 0, 4, values);
    return arr;
}
