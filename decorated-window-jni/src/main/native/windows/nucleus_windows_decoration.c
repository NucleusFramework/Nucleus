/**
 * JNI bridge for Windows custom window decoration (title-bar removal).
 *
 * Subclasses the HWND WndProc to:
 *   - WM_NCCALCSIZE: extend client area into the title bar
 *   - WM_NCHITTEST: 3-zone hit test (resize borders, caption, client)
 *   - WM_NCMOUSEMOVE: forward as WM_MOUSEMOVE for Compose pointer tracking
 *   - DwmExtendFrameIntoClientArea for DWM shadow
 *
 * Per-HWND state is stored via SetProp/GetProp.
 * DPI-aware: GetDpiForWindow / GetSystemMetricsForDpi resolved dynamically.
 *
 * Linked libraries: kernel32.lib user32.lib dwmapi.lib gdi32.lib
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>

/* ------------------------------------------------------------------ */
/*  /NODEFAULTLIB support                                              */
/* ------------------------------------------------------------------ */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

/* ------------------------------------------------------------------ */
/*  SM_CXPADDEDBORDERWIDTH guard — not in all SDK versions             */
/* ------------------------------------------------------------------ */
#ifndef SM_CXPADDEDBORDERWIDTH
#define SM_CXPADDEDBORDERWIDTH 92
#endif

/* ------------------------------------------------------------------ */
/*  DPI-aware function pointers (resolved once)                        */
/* ------------------------------------------------------------------ */
typedef UINT (WINAPI *PFN_GetDpiForWindow)(HWND);
typedef int  (WINAPI *PFN_GetSystemMetricsForDpi)(int, UINT);
typedef BOOL (WINAPI *PFN_AdjustWindowRectExForDpi)(LPRECT, DWORD, BOOL, DWORD, UINT);

static PFN_GetDpiForWindow         pGetDpiForWindow         = NULL;
static PFN_GetSystemMetricsForDpi  pGetSystemMetricsForDpi  = NULL;
static PFN_AdjustWindowRectExForDpi pAdjustWindowRectExForDpi = NULL;
static volatile BOOL dpiApiResolved = FALSE;

static void resolveDpiApis(void) {
    if (dpiApiResolved) return;
    HMODULE hUser32 = GetModuleHandleA("user32.dll");
    if (hUser32) {
        pGetDpiForWindow = (PFN_GetDpiForWindow)
            GetProcAddress(hUser32, "GetDpiForWindow");
        pGetSystemMetricsForDpi = (PFN_GetSystemMetricsForDpi)
            GetProcAddress(hUser32, "GetSystemMetricsForDpi");
        pAdjustWindowRectExForDpi = (PFN_AdjustWindowRectExForDpi)
            GetProcAddress(hUser32, "AdjustWindowRectExForDpi");
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

/* ------------------------------------------------------------------ */
/*  Per-HWND state                                                     */
/* ------------------------------------------------------------------ */
static const wchar_t *PROP_NAME = L"NucleusDecoState";
static const wchar_t *CHILD_PROP_NAME = L"NucleusChildState";

typedef struct {
    WNDPROC originalWndProc;
    int     titleBarHeightPx;
    BOOL    forceHitTestClient;
    HWND    childHwnd;
    /* Background color (COLORREF = 0x00BBGGRR) for WM_ERASEBKGND */
    COLORREF bgColor;
    /* Fullscreen state */
    BOOL    isFullscreen;
    LONG    savedStyle;
    LONG    savedExStyle;
    WINDOWPLACEMENT savedPlacement;
    /* Min/max size override (logical pixels, 0 = not set) */
    POINT   minSizePx;
    POINT   maxSizePx;
    /* Debug counters */
    int     hitTestCount;
    int     hitTestCaption;
    int     hitTestClient;
    int     hitTestBorder;
    int     nccalcsizeCount;
    int     lastPtY;
    int     lastWinTop;
    int     anyMsgCount;
} DecoState;

typedef struct {
    WNDPROC originalWndProc;
    HWND    parentHwnd;
} ChildState;

static DecoState *getState(HWND hwnd) {
    return (DecoState *)GetPropW(hwnd, PROP_NAME);
}

static ChildState *getChildState(HWND hwnd) {
    return (ChildState *)GetPropW(hwnd, CHILD_PROP_NAME);
}

/* ------------------------------------------------------------------ */
/*  Resize border width helper                                         */
/* ------------------------------------------------------------------ */
static int getResizeBorderWidth(HWND hwnd, BOOL isVertical) {
    UINT dpi = getDpi(hwnd);
    int frameMetric = isVertical ? SM_CXSIZEFRAME : SM_CYSIZEFRAME;
    int border = getSystemMetrics(frameMetric, dpi)
               + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
    return border;
}

/* ------------------------------------------------------------------ */
/*  Auto-hide taskbar detection                                        */
/* ------------------------------------------------------------------ */
static BOOL isAutoHideTaskbar(UINT edge, RECT monitorRect) {
    APPBARDATA abd;
    abd.cbSize = sizeof(abd);
    abd.uEdge = edge;
    abd.rc = monitorRect;
    return (BOOL)SHAppBarMessage(ABM_GETAUTOHIDEBAR, &abd);
}

/* ------------------------------------------------------------------ */
/*  Debug output (temporary — writes to debugger + log file)           */
/* ------------------------------------------------------------------ */
static void debugLog(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    wvsprintfA(buf, fmt, ap);
    va_end(ap);
    OutputDebugStringA(buf);
    OutputDebugStringA("\n");
}

/* ------------------------------------------------------------------ */
/*  Child WndProc: returns HTTRANSPARENT in title bar area so that     */
/*  WM_NCHITTEST is forwarded to the parent frame.                     */
/* ------------------------------------------------------------------ */
static LRESULT CALLBACK childWndProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    ChildState *cs = getChildState(hwnd);
    if (!cs) return DefWindowProcW(hwnd, msg, wParam, lParam);

    /* Fill background with parent's bgColor to avoid white flash on resize */
    if (msg == WM_ERASEBKGND) {
        DecoState *parentState = getState(cs->parentHwnd);
        if (parentState) {
            HDC hdc = (HDC)wParam;
            RECT rc;
            GetClientRect(hwnd, &rc);
            HBRUSH brush = CreateSolidBrush(parentState->bgColor);
            FillRect(hdc, &rc, brush);
            DeleteObject(brush);
            return 1;
        }
    }

    if (msg == WM_NCHITTEST) {
        /* Only return HTTRANSPARENT for the top resize border so the
         * parent frame can handle HTTOP/HTTOPLEFT/HTTOPRIGHT.
         * Everything else (title bar, client) returns HTCLIENT so
         * all clicks reach Compose, which handles buttons, switches,
         * and initiates native drag for unconsumed clicks. */
        DecoState *parentState = getState(cs->parentHwnd);
        if (parentState && !IsZoomed(cs->parentHwnd) && !parentState->isFullscreen) {
            POINT pt;
            pt.x = (short)LOWORD(lParam);
            pt.y = (short)HIWORD(lParam);

            RECT parentRect;
            GetWindowRect(cs->parentHwnd, &parentRect);
            int borderHeight = getResizeBorderWidth(cs->parentHwnd, FALSE);

            if (pt.y < parentRect.top + borderHeight) {
                return HTTRANSPARENT;
            }
        }
    }

    if (msg == WM_NCDESTROY) {
        WNDPROC origProc = cs->originalWndProc;
        RemovePropW(hwnd, CHILD_PROP_NAME);
        HeapFree(GetProcessHeap(), 0, cs);
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)origProc);
        return CallWindowProcW(origProc, hwnd, msg, wParam, lParam);
    }

    return CallWindowProcW(cs->originalWndProc, hwnd, msg, wParam, lParam);
}

/* ------------------------------------------------------------------ */
/*  WndProc subclass (frame)                                           */
/* ------------------------------------------------------------------ */
static LRESULT CALLBACK decorationWndProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    DecoState *state = getState(hwnd);
    if (!state) return DefWindowProcW(hwnd, msg, wParam, lParam);

    state->anyMsgCount++;

    switch (msg) {

    /* -------------------------------------------------------------- */
    /*  WM_ERASEBKGND: fill with bgColor to avoid white flash on       */
    /*  resize. Without this, the default handler erases to the        */
    /*  window class brush (white) before Compose/Skiko renders.       */
    /* -------------------------------------------------------------- */
    case WM_ERASEBKGND: {
        HDC hdc = (HDC)wParam;
        RECT rc;
        GetClientRect(hwnd, &rc);
        HBRUSH brush = CreateSolidBrush(state->bgColor);
        FillRect(hdc, &rc, brush);
        DeleteObject(brush);
        return 1;
    }

    /* -------------------------------------------------------------- */
    /*  WM_WINDOWPOSCHANGING: prevent BitBlt during resize.            */
    /*  Without SWP_NOCOPYBITS, Windows copies old content and fills   */
    /*  the newly exposed strip with the class brush (white) before    */
    /*  WM_ERASEBKGND fires.                                           */
    /* -------------------------------------------------------------- */
    case WM_WINDOWPOSCHANGING: {
        WINDOWPOS *wp = (WINDOWPOS *)lParam;
        wp->flags |= SWP_NOCOPYBITS;
        break;
    }

    /* -------------------------------------------------------------- */
    /*  WM_NCCALCSIZE: extend client area into title bar               */
    /* -------------------------------------------------------------- */
    case WM_NCCALCSIZE: {
        state->nccalcsizeCount++;
        if (!wParam) break; /* wParam == FALSE → just use default */

        /* Fullscreen: client area fills entire window */
        if (state->isFullscreen) {
            return 0;
        }

        NCCALCSIZE_PARAMS *params = (NCCALCSIZE_PARAMS *)lParam;
        RECT originalTop = params->rgrc[0];

        /* Let the default handler compute the NC area first */
        LRESULT result = CallWindowProcW(state->originalWndProc,
                                          hwnd, msg, wParam, lParam);

        /* Restore the top coordinate so client area extends into title bar */
        params->rgrc[0].top = originalTop.top;

        /* When maximized, the window extends beyond the screen by the
         * frame border width. We need to offset the top by that amount
         * so the content doesn't go under the taskbar. */
        if (IsZoomed(hwnd)) {
            UINT dpi = getDpi(hwnd);
            int borderWidth = getSystemMetrics(SM_CYSIZEFRAME, dpi)
                            + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
            params->rgrc[0].top += borderWidth;

            /* Account for auto-hide taskbar: reserve 1px so the taskbar
             * can still be triggered by moving the mouse to the edge. */
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

    /* -------------------------------------------------------------- */
    /*  WM_NCHITTEST: 3-zone hit test                                  */
    /* -------------------------------------------------------------- */
    case WM_NCHITTEST: {
        state->hitTestCount++;

        POINT pt;
        pt.x = (short)LOWORD(lParam);
        pt.y = (short)HIWORD(lParam);

        RECT windowRect;
        GetWindowRect(hwnd, &windowRect);

        state->lastPtY = pt.y;
        state->lastWinTop = windowRect.top;

        /* Zone 1: resize borders */
        int borderWidth = getResizeBorderWidth(hwnd, TRUE);
        int borderHeight = getResizeBorderWidth(hwnd, FALSE);

        /* When maximized or fullscreen, no resize borders */
        if (!IsZoomed(hwnd) && !state->isFullscreen) {
            /* Top-left corner */
            if (pt.x < windowRect.left + borderWidth &&
                pt.y < windowRect.top + borderHeight) {
                state->hitTestBorder++; return HTTOPLEFT;
            }
            /* Top-right corner */
            if (pt.x >= windowRect.right - borderWidth &&
                pt.y < windowRect.top + borderHeight) {
                state->hitTestBorder++; return HTTOPRIGHT;
            }
            /* Bottom-left corner */
            if (pt.x < windowRect.left + borderWidth &&
                pt.y >= windowRect.bottom - borderHeight) {
                state->hitTestBorder++; return HTBOTTOMLEFT;
            }
            /* Bottom-right corner */
            if (pt.x >= windowRect.right - borderWidth &&
                pt.y >= windowRect.bottom - borderHeight) {
                state->hitTestBorder++; return HTBOTTOMRIGHT;
            }
            /* Left edge */
            if (pt.x < windowRect.left + borderWidth) {
                state->hitTestBorder++; return HTLEFT;
            }
            /* Right edge */
            if (pt.x >= windowRect.right - borderWidth) {
                state->hitTestBorder++; return HTRIGHT;
            }
            /* Top edge */
            if (pt.y < windowRect.top + borderHeight) {
                state->hitTestBorder++; return HTTOP;
            }
            /* Bottom edge */
            if (pt.y >= windowRect.bottom - borderHeight) {
                state->hitTestBorder++; return HTBOTTOM;
            }
        }

        /* Zone 2: title bar area — always HTCLIENT.
         * All title bar clicks go to Compose, which handles interactive
         * elements directly and initiates native drag for unconsumed clicks
         * via nativeStartDrag(). */
        if (pt.y < windowRect.top + state->titleBarHeightPx) {
            state->hitTestClient++;
            return HTCLIENT;
        }

        /* Zone 3: client area */
        state->hitTestClient++;
        return HTCLIENT;
    }

    /* -------------------------------------------------------------- */
    /*  WM_NCLBUTTONDOWN: pass to DefWindowProc for native drag        */
    /*  AWT's WndProc may not call DefWindowProc for this message,     */
    /*  so we bypass AWT to ensure native drag/snap behavior.          */
    /* -------------------------------------------------------------- */
    case WM_NCLBUTTONDOWN: {
        if (wParam == HTCAPTION) {
            ReleaseCapture();
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        break;
    }

    /* -------------------------------------------------------------- */
    /*  WM_NCLBUTTONDBLCLK: pass to DefWindowProc for native maximize  */
    /* -------------------------------------------------------------- */
    case WM_NCLBUTTONDBLCLK: {
        if (wParam == HTCAPTION) {
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        break;
    }

    /* -------------------------------------------------------------- */
    /*  WM_NCMOUSEMOVE: forward as WM_MOUSEMOVE for Compose tracking   */
    /* -------------------------------------------------------------- */
    case WM_NCMOUSEMOVE: {
        /* Convert screen coords to client coords and post WM_MOUSEMOVE */
        POINT pt;
        pt.x = (short)LOWORD(lParam);
        pt.y = (short)HIWORD(lParam);
        ScreenToClient(hwnd, &pt);
        PostMessageW(hwnd, WM_MOUSEMOVE, 0, MAKELPARAM(pt.x, pt.y));
        break;  /* also let original handle it */
    }

    /* -------------------------------------------------------------- */
    /*  WM_GETMINMAXINFO: fix DPI scaling for min/max size.            */
    /*  Standard OpenJDK stores logical pixels in ptMinTrackSize but  */
    /*  Windows expects physical pixels. JBR applies ScaleUpX/Y;      */
    /*  we replicate that fix here so it works on all JVMs.            */
    /* -------------------------------------------------------------- */
    case WM_GETMINMAXINFO: {
        /* Let AWT process first (sets unscaled values) */
        LRESULT result = CallWindowProcW(state->originalWndProc,
                                          hwnd, msg, wParam, lParam);
        LPMINMAXINFO lpmmi = (LPMINMAXINFO)lParam;
        UINT dpi = getDpi(hwnd);

        /* Override with DPI-scaled values if set (per-axis) */
        if (state->minSizePx.x > 0)
            lpmmi->ptMinTrackSize.x = MulDiv(state->minSizePx.x, dpi, 96);
        if (state->minSizePx.y > 0)
            lpmmi->ptMinTrackSize.y = MulDiv(state->minSizePx.y, dpi, 96);
        if (state->maxSizePx.x > 0)
            lpmmi->ptMaxTrackSize.x = MulDiv(state->maxSizePx.x, dpi, 96);
        if (state->maxSizePx.y > 0)
            lpmmi->ptMaxTrackSize.y = MulDiv(state->maxSizePx.y, dpi, 96);
        return result;
    }

    /* -------------------------------------------------------------- */
    /*  WM_SYSCOMMAND: block state-changing commands while fullscreen  */
    /*  to prevent native/Kotlin state desync. The application must   */
    /*  exit fullscreen via its own UI controls.                       */
    /* -------------------------------------------------------------- */
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

    /* -------------------------------------------------------------- */
    /*  WM_SIZE: safety net — detect when the window is resized        */
    /*  externally while fullscreen (e.g. via ShowWindow called        */
    /*  directly by AWT). Clears the flag and restores styles so       */
    /*  the frame is never permanently stripped.                       */
    /* -------------------------------------------------------------- */
    case WM_SIZE: {
        if (state->isFullscreen && wParam != SIZE_MINIMIZED) {
            int newW = (int)(short)LOWORD(lParam);
            int newH = (int)(short)HIWORD(lParam);
            HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO mi;
            mi.cbSize = sizeof(mi);
            if (GetMonitorInfoW(hMon, &mi)) {
                int monW = mi.rcMonitor.right - mi.rcMonitor.left;
                int monH = mi.rcMonitor.bottom - mi.rcMonitor.top;
                if (newW != monW || newH != monH) {
                    /* External resize detected — sync native state */
                    state->isFullscreen = FALSE;
                    SetWindowLongW(hwnd, GWL_STYLE, state->savedStyle);
                    SetWindowLongW(hwnd, GWL_EXSTYLE, state->savedExStyle);
                    SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED);
                }
            }
        }
        break;
    }

    /* -------------------------------------------------------------- */
    /*  WM_NCDESTROY: clean up state                                   */
    /* -------------------------------------------------------------- */
    case WM_NCDESTROY: {
        /* Safety: restore window styles if destroyed while fullscreen
         * so the OS does not hold stripped style bits in its cache.  */
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

/* ------------------------------------------------------------------ */
/*  DllMain                                                            */
/* ------------------------------------------------------------------ */
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)lpvReserved;
    if (fdwReason == DLL_PROCESS_ATTACH) {
        resolveDpiApis();
    }
    return TRUE;
}

/* ================================================================== */
/*  JNI exports                                                        */
/* ================================================================== */

/* Package: dev.nucleusframework.nucleus.window.utils.windows */
/* Class:   JniWindowsDecorationBridge */

/* -------------------------------------------------------------- */
/*  nativeInstallDecoration(long hwnd, int titleBarHeightPx)       */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeInstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint titleBarHeightPx)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;

    if (!hwnd || !IsWindow(hwnd)) return;

    /* Idempotent: if already installed, just update the height */
    DecoState *existing = getState(hwnd);
    if (existing) {
        existing->titleBarHeightPx = (int)titleBarHeightPx;
        return;
    }

    /* Allocate per-HWND state */
    DecoState *state = (DecoState *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(DecoState));
    if (!state) return;

    state->titleBarHeightPx = (int)titleBarHeightPx;
    state->forceHitTestClient = FALSE;

    /* Store state on the HWND */
    SetPropW(hwnd, PROP_NAME, (HANDLE)state);

    /* Subclass the window */
    LONG_PTR prevWndProc = SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC, (LONG_PTR)decorationWndProc);
    state->originalWndProc = (WNDPROC)prevWndProc;

    /* Subclass the first child window (Skiko canvas) so WM_NCHITTEST
     * returns HTTRANSPARENT in the title bar area, forwarding to frame. */
    HWND child = GetWindow(hwnd, GW_CHILD);
    if (child) {
        ChildState *cs = (ChildState *)HeapAlloc(
            GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(ChildState));
        if (cs) {
            cs->parentHwnd = hwnd;
            SetPropW(child, CHILD_PROP_NAME, (HANDLE)cs);
            cs->originalWndProc = (WNDPROC)SetWindowLongPtrW(
                child, GWLP_WNDPROC, (LONG_PTR)childWndProc);
            state->childHwnd = child;
        }
    }

    /* Extend DWM frame into the entire client area ("sheet of glass").
     * This makes the DWM background (opaque black) fill newly exposed
     * areas during resize instead of the window-class brush (white).
     * On macOS the equivalent is NSWindow.setBackgroundColor — both work
     * at the compositor level, below the GPU rendering surface.
     * DWM shadow is preserved regardless of margin values. */
    /* Extend just the bottom by 1px to keep DWM shadow without enabling
     * glass compositing over the client area. With glass ({-1,-1,-1,-1}),
     * transparent DirectX pixels would show the DWM glass backdrop (white by
     * default), making the flash worse. With {0,0,0,1} DWM treats the
     * client area as opaque: transparent pixels (from setTransparency=true)
     * render as black, which is invisible on dark-themed windows. */
    MARGINS margins = {0, 0, 0, 1};
    DwmExtendFrameIntoClientArea(hwnd, &margins);

    /* Force a frame recalculation */
    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

/* -------------------------------------------------------------- */
/*  nativeUninstallDecoration(long hwnd)                           */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeUninstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    /* Restore child window's original WndProc first */
    if (state->childHwnd && IsWindow(state->childHwnd)) {
        ChildState *cs = getChildState(state->childHwnd);
        if (cs) {
            SetWindowLongPtrW(state->childHwnd, GWLP_WNDPROC,
                              (LONG_PTR)cs->originalWndProc);
            RemovePropW(state->childHwnd, CHILD_PROP_NAME);
            HeapFree(GetProcessHeap(), 0, cs);
        }
    }

    /* Restore frame's original WndProc */
    SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)state->originalWndProc);

    RemovePropW(hwnd, PROP_NAME);
    HeapFree(GetProcessHeap(), 0, state);

    /* Reset DWM margins */
    MARGINS margins = {0, 0, 0, 0};
    DwmExtendFrameIntoClientArea(hwnd, &margins);

    /* Force frame recalculation */
    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

/* -------------------------------------------------------------- */
/*  nativeSetForceHitTestClient(long hwnd, boolean force)          */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeSetForceHitTestClient(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean force)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    DecoState *state = getState(hwnd);
    if (state) {
        state->forceHitTestClient = force ? TRUE : FALSE;
    }
}

/* -------------------------------------------------------------- */
/*  nativeSetTitleBarHeight(long hwnd, int heightPx)               */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeSetTitleBarHeight(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint heightPx)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    DecoState *state = getState(hwnd);
    if (state) {
        state->titleBarHeightPx = (int)heightPx;
    }
}

/* -------------------------------------------------------------- */
/*  nativeStartDrag(long hwnd)                                     */
/*  Initiates a native window drag (with snap/tile support).       */
/*  Called from Compose when an unconsumed press occurs in the     */
/*  title bar background.                                          */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeStartDrag(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    POINT pt;
    GetCursorPos(&pt);

    /* Post (not Send) to avoid blocking the EDT. The WM_NCLBUTTONDOWN
     * handler calls ReleaseCapture + DefWindowProcW to start the modal
     * drag loop when AWT's message pump picks this up. */
    PostMessageW(hwnd, WM_NCLBUTTONDOWN, HTCAPTION, MAKELPARAM(pt.x, pt.y));
}

/* -------------------------------------------------------------- */
/*  nativeGetHwnd(Window awtWindow) → long                         */
/*  Extracts the HWND from an AWT Window via JNI reflection.       */
/*  JNI bypasses JPMS module restrictions, so sun.awt.windows.*    */
/*  classes are accessible without --add-opens.                    */
/* -------------------------------------------------------------- */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeGetHwnd(
    JNIEnv *env, jclass clazz, jobject awtWindow)
{
    if (!awtWindow) return 0;

    /* AWTAccessor.getComponentAccessor() */
    jclass awtAccessorClass = (*env)->FindClass(env, "sun/awt/AWTAccessor");
    if (!awtAccessorClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    jmethodID getCompAccessor = (*env)->GetStaticMethodID(env, awtAccessorClass,
        "getComponentAccessor", "()Lsun/awt/AWTAccessor$ComponentAccessor;");
    if (!getCompAccessor || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, awtAccessorClass);
        return 0;
    }

    jobject compAccessor = (*env)->CallStaticObjectMethod(env, awtAccessorClass, getCompAccessor);
    (*env)->DeleteLocalRef(env, awtAccessorClass);
    if (!compAccessor || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    /* componentAccessor.getPeer(window) */
    jclass compAccessorClass = (*env)->FindClass(env, "sun/awt/AWTAccessor$ComponentAccessor");
    if (!compAccessorClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, compAccessor);
        return 0;
    }

    jmethodID getPeer = (*env)->GetMethodID(env, compAccessorClass,
        "getPeer", "(Ljava/awt/Component;)Ljava/awt/peer/ComponentPeer;");
    (*env)->DeleteLocalRef(env, compAccessorClass);
    if (!getPeer || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, compAccessor);
        return 0;
    }

    jobject peer = (*env)->CallObjectMethod(env, compAccessor, getPeer, awtWindow);
    (*env)->DeleteLocalRef(env, compAccessor);
    if (!peer || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    /* peer.getHWnd() */
    jclass wComponentPeerClass = (*env)->FindClass(env, "sun/awt/windows/WComponentPeer");
    if (!wComponentPeerClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return 0;
    }

    jmethodID getHWnd = (*env)->GetMethodID(env, wComponentPeerClass, "getHWnd", "()J");
    (*env)->DeleteLocalRef(env, wComponentPeerClass);
    if (!getHWnd || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, peer);
        return 0;
    }

    jlong hwnd = (*env)->CallLongMethod(env, peer, getHWnd);
    (*env)->DeleteLocalRef(env, peer);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }

    return hwnd;
}

/* ------------------------------------------------------------------ */
/*  Lightweight WndProc for dialogs: only handles WM_ERASEBKGND and    */
/*  WM_WINDOWPOSCHANGING to prevent resize flash.                      */
/* ------------------------------------------------------------------ */
static LRESULT CALLBACK dialogDecoWndProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    DecoState *state = getState(hwnd);
    if (!state) return DefWindowProcW(hwnd, msg, wParam, lParam);

    switch (msg) {

    case WM_ERASEBKGND: {
        HDC hdc = (HDC)wParam;
        RECT rc;
        GetClientRect(hwnd, &rc);
        HBRUSH brush = CreateSolidBrush(state->bgColor);
        FillRect(hdc, &rc, brush);
        DeleteObject(brush);
        return 1;
    }

    case WM_WINDOWPOSCHANGING: {
        WINDOWPOS *wp = (WINDOWPOS *)lParam;
        wp->flags |= SWP_NOCOPYBITS;
        break;
    }

    case WM_NCDESTROY: {
        WNDPROC origProc = state->originalWndProc;
        RemovePropW(hwnd, PROP_NAME);
        HeapFree(GetProcessHeap(), 0, state);
        SetWindowLongPtrW(hwnd, GWLP_WNDPROC, (LONG_PTR)origProc);
        return CallWindowProcW(origProc, hwnd, msg, wParam, lParam);
    }

    }

    return CallWindowProcW(state->originalWndProc, hwnd, msg, wParam, lParam);
}

/* -------------------------------------------------------------- */
/*  nativeApplyDialogStyle(long hwnd)                              */
/*  Applies rounded corners + DWM shadow to an undecorated popup   */
/*  dialog window (WS_POPUP without WS_CAPTION).                   */
/*  Also subclasses the WndProc to handle WM_ERASEBKGND and        */
/*  WM_WINDOWPOSCHANGING (SWP_NOCOPYBITS) to prevent resize flash. */
/*  DWMWA_WINDOW_CORNER_PREFERENCE (33) + DWMWCP_ROUND (2) are    */
/*  Windows 11 22000+ only; silently ignored on older Windows.     */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeApplyDialogStyle(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    /* Idempotent: if already installed, nothing to do */
    if (getState(hwnd)) return;

    /* Allocate per-HWND state for WM_ERASEBKGND background fill */
    DecoState *state = (DecoState *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(DecoState));
    if (!state) return;

    SetPropW(hwnd, PROP_NAME, (HANDLE)state);

    /* Subclass with lightweight dialog WndProc */
    LONG_PTR prevWndProc = SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC, (LONG_PTR)dialogDecoWndProc);
    state->originalWndProc = (WNDPROC)prevWndProc;

    /* Request rounded corners (Windows 11+, silently ignored on older) */
    DWORD preference = 2; /* DWMWCP_ROUND */
    DwmSetWindowAttribute(hwnd, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */,
                          &preference, sizeof(preference));

    /* DWM drop shadow for popup window */
    MARGINS margins = {0, 0, 0, 1};
    DwmExtendFrameIntoClientArea(hwnd, &margins);
}

/* -------------------------------------------------------------- */
/*  nativeSetFullscreen(long hwnd, boolean fullscreen)              */
/*  Enters or exits native fullscreen mode.                        */
/*  Enter: saves style/exstyle/placement, removes caption/frame,   */
/*         covers the entire monitor.                               */
/*  Exit:  restores saved style/exstyle/placement.                  */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeSetFullscreen(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean fullscreen)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    if (fullscreen) {
        if (state->isFullscreen) return; /* already fullscreen */

        /* Save current state */
        state->savedStyle = GetWindowLongW(hwnd, GWL_STYLE);
        state->savedExStyle = GetWindowLongW(hwnd, GWL_EXSTYLE);
        state->savedPlacement.length = sizeof(WINDOWPLACEMENT);
        GetWindowPlacement(hwnd, &state->savedPlacement);

        /* Get monitor dimensions early — needed for the rcNormalPosition
         * trick below and for the final SetWindowPos call. */
        HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO mi;
        mi.cbSize = sizeof(mi);
        GetMonitorInfoW(hMon, &mi);

        /* Suppress DWM animations so the "unmaximize" transition that
         * Windows plays when WS_MAXIMIZE is stripped does not flash. */
        BOOL disableTransitions = TRUE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &disableTransitions, sizeof(disableTransitions));

        /* When the window is maximized, override rcNormalPosition to the
         * fullscreen monitor rect BEFORE removing WS_MAXIMIZE.  Without
         * this, Windows "restores" the window to its pre-maximize size for
         * one frame, producing a visible shrink-then-expand artifact. */
        if (state->savedPlacement.showCmd == SW_SHOWMAXIMIZED) {
            WINDOWPLACEMENT wp = state->savedPlacement;
            wp.rcNormalPosition.left   = mi.rcMonitor.left;
            wp.rcNormalPosition.top    = mi.rcMonitor.top;
            wp.rcNormalPosition.right  = mi.rcMonitor.right;
            wp.rcNormalPosition.bottom = mi.rcMonitor.bottom;
            SetWindowPlacement(hwnd, &wp);
        }

        /* Mark fullscreen BEFORE SetWindowLongW so every WM_NCCALCSIZE
         * triggered by style changes already uses the fullscreen path
         * (return 0 = client area fills the whole window). */
        state->isFullscreen = TRUE;

        /* Remove window borders, title bar, and maximize flag.
         * WS_MAXIMIZE must be stripped because the system constrains
         * maximized windows to the work area (excluding the taskbar). */
        LONG style = state->savedStyle
            & ~(LONG)(WS_CAPTION | WS_THICKFRAME | WS_MAXIMIZE);
        SetWindowLongW(hwnd, GWL_STYLE, style);

        /* Remove extended window styles */
        LONG exStyle = state->savedExStyle
            & ~(LONG)(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE
                     | WS_EX_CLIENTEDGE | WS_EX_STATICEDGE);
        SetWindowLongW(hwnd, GWL_EXSTYLE, exStyle);

        /* HWND_TOPMOST keeps the window above the auto-hide taskbar.
         * Without it, a WS_EX_TOPMOST taskbar can slide over the window
         * when the user hovers the screen edge. */
        SetWindowPos(hwnd, HWND_TOPMOST,
            mi.rcMonitor.left, mi.rcMonitor.top,
            mi.rcMonitor.right - mi.rcMonitor.left,
            mi.rcMonitor.bottom - mi.rcMonitor.top,
            SWP_FRAMECHANGED);

        /* Re-enable DWM animations so the exit from fullscreen can
         * animate smoothly back to the previous window placement. */
        BOOL enableTransitions = FALSE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &enableTransitions, sizeof(enableTransitions));
    } else {
        if (!state->isFullscreen) return; /* already not fullscreen */

        /* Clear fullscreen flag BEFORE style restoration so every
         * WM_NCCALCSIZE triggered by the changes below immediately
         * uses the normal path (title-bar extension, resize borders). */
        state->isFullscreen = FALSE;

        /* Restore extended styles first (no size/position side-effects). */
        SetWindowLongW(hwnd, GWL_EXSTYLE, state->savedExStyle);

        /* Restore the main style WITHOUT WS_MAXIMIZE initially.
         * If we set WS_MAXIMIZE via SetWindowLongW, Windows constrains
         * the window to the work area and sends WM_SIZE(SIZE_RESTORED)
         * instead of WM_SIZE(SIZE_MAXIMIZED). AWT then misses the
         * maximize event and Frame.getExtendedState() stays stale.
         * SetWindowPlacement with SW_SHOWMAXIMIZED goes through the
         * proper maximize code path and sends the correct events. */
        LONG restoreStyle = state->savedStyle & ~(LONG)WS_MAXIMIZE;
        SetWindowLongW(hwnd, GWL_STYLE, restoreStyle);

        /* Restore window placement (maximized/normal state + position).
         * For SW_SHOWMAXIMIZED this re-applies WS_MAXIMIZE internally
         * and sends WM_SIZE(SIZE_MAXIMIZED) so AWT detects it. */
        SetWindowPlacement(hwnd, &state->savedPlacement);

        /* Remove topmost and force frame recalculation */
        SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED);
    }
}

/* -------------------------------------------------------------- */
/*  nativeIsFullscreen(long hwnd) → boolean                        */
/*  Returns true if the window is in native fullscreen mode.       */
/* -------------------------------------------------------------- */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeIsFullscreen(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return JNI_FALSE;

    DecoState *state = getState(hwnd);
    if (!state) return JNI_FALSE;

    return state->isFullscreen ? JNI_TRUE : JNI_FALSE;
}

/* -------------------------------------------------------------- */
/*  nativeSetBackgroundColor(long hwnd, int argb)                  */
/*  Syncs DWM caption/border color and dark-mode flag with the     */
/*  window's title bar theme color.                                */
/*  Windows 11 22000+ for attrs 34/35; attr 20 back-ported to     */
/*  Windows 10 build 17763+. Silently ignored on older versions.   */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeSetBackgroundColor(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint argb)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    int r = (argb >> 16) & 0xFF;
    int g = (argb >>  8) & 0xFF;
    int b =  argb        & 0xFF;
    COLORREF color = RGB(r, g, b);

    DecoState *state = getState(hwnd);
    if (state) {
        state->bgColor = color;
    }

    /* Set DWM caption color (attr 35) and border color (attr 34).
     * Windows 11 22000+; silently ignored on older versions. */
    DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */,
                          &color, sizeof(color));
    DwmSetWindowAttribute(hwnd, 34 /* DWMWA_BORDER_COLOR */,
                          &color, sizeof(color));

    /* Switch DWM glass between light/dark based on luminance so that the
     * "sheet of glass" background that DWM composites during resize
     * matches the window theme.  DWMWA_USE_IMMERSIVE_DARK_MODE = 20.
     * Windows 11 22000+ / Windows 10 build 17763+; silently ignored on older. */
    int luminance = (r * 299 + g * 587 + b * 114) / 1000;
    BOOL isDark = (luminance < 128) ? TRUE : FALSE;
    DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */,
                          &isDark, sizeof(isDark));
}

/* -------------------------------------------------------------- */
/*  nativeGetDebugInfo(long hwnd) → String                         */
/*  Returns debug counters as a string for diagnostics.            */
/* -------------------------------------------------------------- */
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeGetDebugInfo(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    DecoState *state = hwnd ? getState(hwnd) : NULL;
    char buf[512];
    if (!state) {
        wsprintfA(buf, "NO STATE for hwnd=%p", hwnd);
    } else {
        wsprintfA(buf,
            "anyMsg=%d nccalcsize=%d hitTest=%d caption=%d client=%d border=%d "
            "tbH=%d lastPtY=%d lastWinTop=%d forced=%d",
            state->anyMsgCount, state->nccalcsizeCount,
            state->hitTestCount, state->hitTestCaption,
            state->hitTestClient, state->hitTestBorder,
            state->titleBarHeightPx, state->lastPtY, state->lastWinTop,
            (int)state->forceHitTestClient);
    }
    return (*env)->NewStringUTF(env, buf);
}

/* -------------------------------------------------------------- */
/*  nativeSetMinimumSize(long hwnd, int widthPx, int heightPx)     */
/*  Stores the minimum window size in logical pixels. The          */
/*  WM_GETMINMAXINFO handler applies DPI scaling automatically.    */
/*  Pass (0, 0) to disable the override and fall back to AWT.      */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeSetMinimumSize(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint widthPx, jint heightPx)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    state->minSizePx.x = (LONG)widthPx;
    state->minSizePx.y = (LONG)heightPx;
}

/* -------------------------------------------------------------- */
/*  nativeSetMaximumSize(long hwnd, int widthPx, int heightPx)     */
/*  Stores the maximum window size in logical pixels. The          */
/*  WM_GETMINMAXINFO handler applies DPI scaling automatically.    */
/*  Pass (0, 0) to disable the override and fall back to AWT.      */
/* -------------------------------------------------------------- */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_nucleus_window_utils_windows_JniWindowsDecorationBridge_nativeSetMaximumSize(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint widthPx, jint heightPx)
{
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    DecoState *state = getState(hwnd);
    if (!state) return;

    state->maxSizePx.x = (LONG)widthPx;
    state->maxSizePx.y = (LONG)heightPx;
}
