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
 * the GL render surface is a child HWND.
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
    BOOL    startupBackgroundErase;
    BOOL    isFullscreen;
    LONG    savedStyle;
    LONG    savedExStyle;
    WINDOWPLACEMENT savedPlacement;
    /* Set while a title-bar touch interaction is being routed to DefWindowProc
     * (instead of Tao's consuming subclass) so the OS synthesises legacy mouse
     * messages for an OS-driven title-bar drag with Aero Snap. See decoWndProc. */
    BOOL    titleBarDragArmed;
    /* Set while a DWM system backdrop (Mica / Acrylic / Mica Alt) is applied.
     * The backdrop is only visible through an unpainted client area, so it
     * suppresses both the WM_ERASEBKGND fill and the caption/border color
     * overrides that would otherwise paint over it. */
    BOOL    backdropActive;
    /* Set while the frame is extended to the full sheet of glass
     * ({-1,-1,-1,-1}) for a DWM backdrop tier. Remembered so the fullscreen
     * toggle can re-derive the correct margins for the current mode — the
     * accent (Windows 10 acrylic) tier is backdropActive without the sheet. */
    BOOL    sheetOfGlass;
    /* TRUE while a fullscreen toggle's geometry change is in flight. While
     * set, the WM_WINDOWPOSCHANGED the change generates is forwarded
     * SYNCHRONOUSLY to the JVM (NativeTaoWindowsDecoBridge.
     * onFullscreenSizeChanged), which renders + presents the new-size frame
     * on this stack, before the geometry call returns — the Windows analog
     * of the macOS windowWillEnter/ExitFullScreen prepare (NucleusTaoMetal.m
     * willEnterFS). Rendering after SetWindowPos returned was always ≥1
     * composited frame too late (issue 413). NOTE: hooked at
     * WM_WINDOWPOSCHANGED, not WM_SIZE — Tao's subclass handles
     * WINDOWPOSCHANGED without DefWindowProc, so WM_SIZE never fires here. */
    BOOL    fsTransitionActive;
    /* Expected final client size of the in-flight toggle: only near-final
     * sizes are forwarded (the geometry ops also deliver intermediate
     * frame-recalc sizes, each worth a wasted relayout). Exit is an
     * estimate, hence the tolerance at the forward site. */
    int     fsExpectedW;
    int     fsExpectedH;
    /* Set when the active backdrop is the Windows 10 accent-policy acrylic
     * rather than a DWM one. DWM tints its own materials from the OS theme,
     * but the accent policy takes an explicit tint colour that has to be
     * re-pushed whenever the window background changes — otherwise a light
     * theme keeps the dark tint and the content becomes unreadable. */
    BOOL    accentActive;
    /* Explicit acrylic tint supplied by the app, as a premixed ABGR gradient
     * colour. When absent the window background colour is used instead. */
    BOOL    hasTint;
    DWORD   tintGradient;
    /* WS_SYSMENU is stripped while the sheet-of-glass frame is extended:
     * with it present DWM paints its own caption buttons over the client
     * area (offset from the app's) and steals their hover. Remembered here
     * so deactivating the backdrop restores the style bit it removed. */
    BOOL    strippedSysMenu;
    /* The resolved immersive-dark flag last pushed by the Kotlin side, and
     * whether one was pushed at all. Kept so WM_SETTINGCHANGE can restore it:
     * Tao's own handler re-derives the flag from the SYSTEM theme on every
     * settings change, silently overriding the app-resolved value — a light
     * app on a system flipped to dark got a dark Mica under light content. */
    BOOL    immersiveDark;
    BOOL    hasImmersiveDark;
    /* Client size at the last backdrop-mode WM_ERASEBKGND, so the erase can
     * black-fill (= alpha 0 = show the material) only the newly exposed
     * bands. Erasing the whole client each time made every interactive
     * resize flicker between bare material and content; erasing nothing left
     * half a screen of stale pixels on maximize. */
    int     lastEraseClientW;
    int     lastEraseClientH;
    /* Client-space rects (physical px) of the Compose-drawn caption buttons,
     * in the order minimize / maximize / close; all-zero = slot absent.
     * Published from the Kotlin side after every layout so WM_NCHITTEST can
     * answer HTMINBUTTON / HTMAXBUTTON / HTCLOSE over them — which is what
     * makes Windows 11 show the Snap Layouts flyout on maximize hover. The
     * buttons stay Compose-drawn and Compose-handled: the NC mouse messages
     * for these hit codes are forwarded back as client messages. */
    RECT    captionButtonRects[3];
    /* Fully borderless chrome (DecoratedWindow(undecorated = true) overlays /
     * ghosts). Suppresses DWM caption+border colours and the 1px bottom
     * frame extension that keeps the drop shadow — otherwise a transparent
     * window still shows a 1px system contour (themed border, often black
     * when the clear colour is alpha-0). */
    BOOL    borderlessChrome;
} DecoState;

/* Order inside DecoState.captionButtonRects. */
#define CAPTION_BUTTON_MINIMIZE 0
#define CAPTION_BUTTON_MAXIMIZE 1
#define CAPTION_BUTTON_CLOSE    2

/* The hit code each slot answers with. */
static const LRESULT kCaptionButtonHitCodes[3] = { HTMINBUTTON, HTMAXBUTTON, HTCLOSE };

static BOOL isCaptionButtonHit(WPARAM code) {
    return code == HTMINBUTTON || code == HTMAXBUTTON || code == HTCLOSE;
}

/* DWM constants — declared manually because the /NODEFAULTLIB build can pull
 * an SDK dwmapi.h predating Windows 11 22H2. */
#ifndef DWMWA_SYSTEMBACKDROP_TYPE
#define DWMWA_SYSTEMBACKDROP_TYPE 38
#endif
#ifndef DWMWA_COLOR_NONE
/* "Draw no caption/border fill at all" — NOT DWMWA_COLOR_DEFAULT (0xFFFFFFFF),
 * which restores the *opaque* system caption and hides the backdrop behind the
 * title bar. Same value flutter_acrylic uses for its Mica path. */
#define DWMWA_COLOR_NONE 0xFFFFFFFE
#endif
#ifndef DWMWA_COLOR_DEFAULT
#define DWMWA_COLOR_DEFAULT 0xFFFFFFFF
#endif
#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
/* DWM_WINDOW_CORNER_PREFERENCE wire values (avoid depending on a 22H2 SDK). */
#define NUCLEUS_DWMWCP_DEFAULT    0
#define NUCLEUS_DWMWCP_DONOTROUND 1
#ifndef DWMWA_VISIBLE_FRAME_BORDER_THICKNESS
/* Win11 22000+: thickness of the visible non-client border (logical px). */
#define DWMWA_VISIBLE_FRAME_BORDER_THICKNESS 37
#endif

/* Backdrop styles below this are "no backdrop": DWMSBT_AUTO (0) leaves the
 * choice to DWM, which means none for an ordinary window, and DWMSBT_NONE (1)
 * says so outright. Both keep the window opaque. */
#define BACKDROP_IS_ACTIVE(style) ((style) >= 2)

/* DWMSBT_TRANSIENTWINDOW — the only style with a real pre-22H2 counterpart. */
#define BACKDROP_STYLE_ACRYLIC 3

/* Which implementation tier to use. TIER_AUTO picks the best one this OS
 * supports; the others pin it so the fallbacks can be seen on a machine that
 * does not need them. Wire values shared with WindowsBackdropTier. */
#define TIER_AUTO         0
#define TIER_MODERN       1
#define TIER_LEGACY_MICA  2
#define TIER_WIN10_ACRYLIC 3

/* ================================================================== */
/*  Legacy backdrop fallbacks (Windows 10, Windows 11 before 22H2)     */
/*                                                                     */
/*  Both APIs below are UNDOCUMENTED. They are used only when the      */
/*  documented DWMWA_SYSTEMBACKDROP_TYPE is unavailable, and every     */
/*  failure path leaves the window plainly opaque.                     */
/* ================================================================== */

/* Undocumented: enables Mica on Windows 11 builds before 22H2, where
 * DWMWA_SYSTEMBACKDROP_TYPE does not exist yet. */
#define DWMWA_MICA_EFFECT 1029

/* First Windows 11 build (Mica exists from here on). */
#define BUILD_WIN11 22000

typedef enum {
    ACCENT_DISABLED = 0,
    ACCENT_ENABLE_BLURBEHIND = 3,
    ACCENT_ENABLE_ACRYLICBLURBEHIND = 4
} NUCLEUS_ACCENT_STATE;

typedef struct {
    NUCLEUS_ACCENT_STATE AccentState;
    DWORD AccentFlags;
    DWORD GradientColor; /* ABGR, alpha drives how much shows through */
    DWORD AnimationId;
} NUCLEUS_ACCENT_POLICY;

typedef struct {
    DWORD  Attrib; /* WCA_ACCENT_POLICY */
    PVOID  pvData;
    SIZE_T cbData;
} NUCLEUS_WINDOWCOMPOSITIONATTRIBDATA;

#define WCA_ACCENT_POLICY 19

/* RTL_OSVERSIONINFOW, redeclared so we need no <winternl.h>. */
typedef struct {
    ULONG dwOSVersionInfoSize;
    ULONG dwMajorVersion;
    ULONG dwMinorVersion;
    ULONG dwBuildNumber;
    ULONG dwPlatformId;
    WCHAR szCSDVersion[128];
} NUCLEUS_OSVERSIONINFOW;

typedef LONG (WINAPI *PFN_RtlGetVersion)(NUCLEUS_OSVERSIONINFOW *);
typedef BOOL (WINAPI *PFN_SetWindowCompositionAttribute)(
    HWND, NUCLEUS_WINDOWCOMPOSITIONATTRIBDATA *);

static PFN_SetWindowCompositionAttribute pSetWindowCompositionAttribute = NULL;
static DWORD sOsBuildNumber = 0;
static volatile BOOL legacyApisResolved = FALSE;

static void resolveLegacyBackdropApis(void) {
    if (legacyApisResolved) return;
    legacyApisResolved = TRUE;

    HMODULE hUser32 = GetModuleHandleW(L"user32.dll");
    if (hUser32) {
        pSetWindowCompositionAttribute = (PFN_SetWindowCompositionAttribute)
            GetProcAddress(hUser32, "SetWindowCompositionAttribute");
    }
    /* RtlGetVersion rather than GetVersionEx: the latter is shimmed and lies
     * about the build number unless the app ships a compatibility manifest. */
    HMODULE hNtdll = GetModuleHandleW(L"ntdll.dll");
    if (hNtdll) {
        PFN_RtlGetVersion pRtlGetVersion =
            (PFN_RtlGetVersion)GetProcAddress(hNtdll, "RtlGetVersion");
        if (pRtlGetVersion) {
            NUCLEUS_OSVERSIONINFOW vi;
            ZeroMemory(&vi, sizeof(vi));
            vi.dwOSVersionInfoSize = sizeof(vi);
            if (pRtlGetVersion(&vi) == 0) sOsBuildNumber = vi.dwBuildNumber;
        }
    }
}

/* Windows 11 pre-22H2 Mica. Needs the sheet-of-glass margins like the modern
 * path. Returns TRUE when the attribute was accepted. */
static BOOL applyLegacyMica(HWND hwnd, BOOL enable) {
    if (sOsBuildNumber < BUILD_WIN11) return FALSE;
    BOOL value = enable;
    return SUCCEEDED(DwmSetWindowAttribute(hwnd, DWMWA_MICA_EFFECT,
                                           &value, sizeof(value)));
}

/* Opacity of the acrylic tint. The blur behind is arbitrary content — a bright
 * desktop under a dark theme, or the reverse — so the window's own background
 * has to dominate it or the text on top stops being readable. Tuned for
 * legibility first, effect second. */
#define ACRYLIC_TINT_ALPHA 0xCC000000u

/* Windows 10 acrylic. Unlike Mica this blurs what is actually behind the
 * window, and it composites against the window's own transparent pixels — so
 * it does NOT want the sheet-of-glass margins. [tint] is the window background
 * colour; DWM never themes this one for us, so callers must re-apply it
 * whenever that colour changes. */
static BOOL applyAccentAcrylic(HWND hwnd, BOOL enable, DWORD gradientColor) {
    if (!pSetWindowCompositionAttribute) return FALSE;

    NUCLEUS_ACCENT_POLICY accent;
    ZeroMemory(&accent, sizeof(accent));
    if (enable) {
        accent.AccentState = ACCENT_ENABLE_ACRYLICBLURBEHIND;
        accent.AccentFlags = 2; /* draw all borders */
        accent.GradientColor = gradientColor;
    } else {
        accent.AccentState = ACCENT_DISABLED;
    }

    NUCLEUS_WINDOWCOMPOSITIONATTRIBDATA data;
    data.Attrib = WCA_ACCENT_POLICY;
    data.pvData = &accent;
    data.cbData = sizeof(accent);
    return pSetWindowCompositionAttribute(hwnd, &data);
}

/* ACCENT_POLICY wants 0xAABBGGRR. A COLORREF is already 0x00BBGGRR, so it only
 * needs the default alpha; an app-supplied ARGB is 0xAARRGGBB and needs R and
 * B swapped, keeping the app's own alpha — that alpha is the whole point of
 * letting the app pass a colour. */
static DWORD gradientFromColorRef(COLORREF c) {
    return (DWORD)(c & 0x00FFFFFF) | ACRYLIC_TINT_ALPHA;
}

static DWORD gradientFromArgb(DWORD argb) {
    return (argb & 0xFF000000u)
         | ((argb & 0x000000FFu) << 16)
         |  (argb & 0x0000FF00u)
         | ((argb & 0x00FF0000u) >> 16);
}

/* The tint currently in force: the app's if it gave one, else the window
 * background. */
static DWORD resolveTintGradient(DecoState *state) {
    if (state && state->hasTint) return state->tintGradient;
    return gradientFromColorRef(state ? state->bgColor : RGB(32, 32, 32));
}

static void applyCaptionColors(HWND hwnd, DecoState *state);
static void applyFrameMargins(HWND hwnd, DecoState *state);

/* Turns a backdrop window back into a plain opaque themed one, immediately —
 * the last composited image before a close must be the theme colour, not
 * semi-transparent tint over a material DWM is about to drop (which fades
 * towards black). Shared by the WM_CLOSE handler and nativePrepareClose. */
static void revertBackdropForClose(HWND hwnd, DecoState *state) {
    if (!state || !state->backdropActive) return;
    state->backdropActive = FALSE;
    state->accentActive = FALSE;
    state->sheetOfGlass = FALSE;
    int none = 1; /* DWMSBT_NONE */
    DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, &none, sizeof(none));
    resolveLegacyBackdropApis();
    applyAccentAcrylic(hwnd, FALSE, 0);
    applyLegacyMica(hwnd, FALSE);
    applyFrameMargins(hwnd, state);
    applyCaptionColors(hwnd, state);
    HDC dc = GetDC(hwnd);
    RECT rc;
    if (dc && GetClientRect(hwnd, &rc)) {
        HBRUSH brush = CreateSolidBrush(state->bgColor);
        FillRect(dc, &rc, brush);
        DeleteObject(brush);
    }
    if (dc) ReleaseDC(hwnd, dc);
}

/* Applies the caption/border color to a window. While a backdrop is active the
 * caption fill is suppressed entirely (an opaque caption is what otherwise
 * leaves the title bar as a flat band above a Mica client area), but the
 * border keeps DWM's default: COLOR_NONE on the border renders the top edge
 * as a bare black line instead of the subtle system frame.
 *
 * Windowed CSD (no backdrop) also hides that 1px DWM contour — Compose
 * already draws `insideBorder` on the same edge pixels, and the themed DWM
 * stroke clips it (#522). The 1px bottom DwmExtendFrame margin is unchanged:
 * it only keeps the drop shadow. */
static void applyCaptionColors(HWND hwnd, DecoState *state) {
    BOOL backdrop = state && state->backdropActive;
    BOOL borderless = state && state->borderlessChrome;
    BOOL fullscreen = state && state->isFullscreen;
    COLORREF themed = state ? state->bgColor : RGB(255, 255, 255);
    COLORREF caption = backdrop ? (COLORREF)DWMWA_COLOR_NONE : themed;
    COLORREF border = backdrop ? (COLORREF)DWMWA_COLOR_DEFAULT : themed;
    UINT thickness = 1;
    /* Fullscreen: the borderless window covers the monitor exactly, so any
     * fill DWM still draws for these attributes shows as a 1px contour of
     * the wrong colour around the content — the issue-413 white edge (worst
     * with a backdrop, where the border is otherwise the light system
     * default). Same for borderlessChrome overlays (no system frame at all).
     * Regular CSD: hide the DWM stroke so the Compose 1dp frame is visible. */
    if (fullscreen || borderless) {
        caption = (COLORREF)DWMWA_COLOR_NONE;
        border = (COLORREF)DWMWA_COLOR_NONE;
        thickness = 0;
    } else if (!backdrop) {
        border = (COLORREF)DWMWA_COLOR_NONE;
        thickness = 0;
    }
    DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, &caption, sizeof(caption));
    DwmSetWindowAttribute(hwnd, 34 /* DWMWA_BORDER_COLOR */, &border, sizeof(border));
    DwmSetWindowAttribute(hwnd, DWMWA_VISIBLE_FRAME_BORDER_THICKNESS,
                          &thickness, sizeof(thickness));
}

/* The DwmExtendFrameIntoClientArea margins for the current mode. Windowed
 * keeps the 1px bottom extension that preserves the DWM drop shadow (or the
 * full sheet of glass while a DWM backdrop tier is live). Fullscreen drops
 * the 1px band: with the window covering the monitor it composites as a
 * light hairline along the bottom edge (issue 413).
 *
 * borderlessChrome uses ZERO margins on purpose. The classic {0,0,0,1}
 * bottom extension exists *only* to keep the DWM drop shadow; sheet-of-glass
 * {-1,-1,-1,-1} also keeps a soft system shadow around the window. Ghost
 * overlays (`DecoratedWindow(undecorated = true)`) want neither — per-pixel
 * transparency still works via tao's DwmEnableBlurBehindWindow empty region
 * (`with_transparent`). */
static void applyFrameMargins(HWND hwnd, DecoState *state) {
    BOOL borderless = state && state->borderlessChrome;
    BOOL sheet = state && state->sheetOfGlass && !borderless;
    BOOL fs = state && state->isFullscreen;
    MARGINS margins;
    if (borderless) {
        margins.cxLeftWidth = margins.cxRightWidth = 0;
        margins.cyTopHeight = margins.cyBottomHeight = 0;
    } else {
        margins.cxLeftWidth    = sheet ? -1 : 0;
        margins.cxRightWidth   = sheet ? -1 : 0;
        margins.cyTopHeight    = sheet ? -1 : 0;
        margins.cyBottomHeight = sheet ? -1 : (fs ? 0 : 1);
    }
    DwmExtendFrameIntoClientArea(hwnd, &margins);
}

/* Borderless fullscreen is deliberately NOT sized exactly to the monitor:
 * when a window's client rect matches the screen, the graphics driver / DWM
 * promote its swapchain to independent flip ("fullscreen optimization").
 * The promotion and every later demotion (popup, alt-tab, toggle) briefly
 * composites DWM's stale cached frame — the issue-413 dual ghost — and on
 * some stacks leaves a light 1px contour. AMD drivers apply this heuristic
 * on the client rectangle alone, ignoring window styles entirely (measured
 * by the Godot team: godotengine/godot#63500), so the only reliable opt-out
 * is to overhang the screen by a couple of pixels and clip them back off
 * with a window region — the same mechanism Godot's multiwindow fullscreen
 * uses (_get_screen_expand_offset + SetWindowRgn). */
#define FS_EXPAND_PX 2

/* Picks the edge the fullscreen overhang hangs off: the bottom, unless
 * another monitor sits directly below (the overhang should stay off every
 * screen), then the right. Both occupied: bottom — the window region keeps
 * the overhang invisible there too. */
static void getFullscreenExpandOffset(
    HMONITOR hMon, const MONITORINFO *mi, int *dx, int *dy)
{
    POINT below;
    below.x = (mi->rcMonitor.left + mi->rcMonitor.right) / 2;
    below.y = mi->rcMonitor.bottom + 1;
    POINT right;
    right.x = mi->rcMonitor.right + 1;
    right.y = (mi->rcMonitor.top + mi->rcMonitor.bottom) / 2;
    HMONITOR mBelow = MonitorFromPoint(below, MONITOR_DEFAULTTONULL);
    HMONITOR mRight = MonitorFromPoint(right, MONITOR_DEFAULTTONULL);
    if (mBelow == NULL || mBelow == hMon) {
        *dx = 0; *dy = FS_EXPAND_PX;
    } else if (mRight == NULL || mRight == hMon) {
        *dx = FS_EXPAND_PX; *dy = 0;
    } else {
        *dx = 0; *dy = FS_EXPAND_PX;
    }
}

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

static BOOL isOwnedTaoPopup(HWND root, HWND owner) {
    if (!root || !owner || root == owner) return FALSE;

    wchar_t className[64];
    if (!GetClassNameW(root, className, 64)) return FALSE;
    if (lstrcmpW(className, L"NucleusTaoPopupCls") != 0) return FALSE;

    HWND currentOwner = GetWindow(root, GW_OWNER);
    while (currentOwner) {
        if (currentOwner == owner) return TRUE;
        currentOwner = GetWindow(currentOwner, GW_OWNER);
    }
    return FALSE;
}

/* ── JVM upcall for the fullscreen transition ─────────────────────────── */
/* Cached in nativeInstallDecoration (the first JNI call with an env). Same
 * pattern as NucleusTaoMetal.m's ensureMetalJVMCached. */
static JavaVM   *sDecoJVM = NULL;
static jclass    sDecoBridgeClass = NULL;   /* global ref */
static jmethodID sDecoOnFullscreenSize = NULL;

static void ensureDecoJVMCached(JNIEnv *env) {
    if (sDecoJVM) return;
    if ((*env)->GetJavaVM(env, &sDecoJVM) != JNI_OK) { sDecoJVM = NULL; return; }
    jclass local = (*env)->FindClass(env,
        "dev/nucleusframework/window/tao/ffi/NativeTaoWindowsDecoBridge");
    if (local) {
        sDecoBridgeClass = (jclass)(*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
        sDecoOnFullscreenSize = (*env)->GetStaticMethodID(
            env, sDecoBridgeClass, "onFullscreenSizeChanged", "(JII)V");
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Calls NativeTaoWindowsDecoBridge.onFullscreenSizeChanged(hwnd, w, h) and
 * BLOCKS until the JVM has rendered + presented at that size. Invoked from
 * WM_WINDOWPOSCHANGED inside the fullscreen toggle's geometry change, on
 * the Tao main thread — the JVM render runs re-entrantly on this stack, so
 * the frame exists before the geometry change returns to the caller. */
static void notifyFullscreenSizeChanged(HWND hwnd, int w, int h) {
    if (!sDecoJVM || !sDecoBridgeClass || !sDecoOnFullscreenSize) return;
    JNIEnv *env = NULL;
    jint status = (*sDecoJVM)->GetEnv(sDecoJVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*sDecoJVM)->AttachCurrentThreadAsDaemon(sDecoJVM, (void **)&env, NULL) != JNI_OK) {
            return;
        }
    } else if (status != JNI_OK) {
        return;
    }
    if (!env) return;
    (*env)->CallStaticVoidMethod(env, sDecoBridgeClass, sDecoOnFullscreenSize,
        (jlong)(uintptr_t)hwnd, (jint)w, (jint)h);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* WndProc subclass */
static LRESULT CALLBACK decoWndProc(
    HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    DecoState *state = getState(hwnd);
    if (!state) return DefWindowProcW(hwnd, msg, wParam, lParam);

    /* Touch title-bar drag → Aero Snap.
     *
     * Tao routes touch through WM_POINTER and consumes it, so Windows never
     * promotes touch to the legacy mouse messages that the OS modal move loop
     * (and thus Aero Snap) needs. To restore that, we capture a title-bar touch
     * from its very first WM_POINTERDOWN and hand the whole pointer interaction
     * (down → update → up) to DefWindowProc, consuming it so it never reaches
     * Tao. DefWindowProc's pointer handling then synthesises the legacy
     * WM_MOUSE* messages: Compose sees a mouse press on the title bar and runs
     * its normal mouse drag (`dragWindow()` → WM_NCLBUTTONDOWN/HTCAPTION), which
     * enters the OS move loop with full Aero Snap and native
     * restore-from-maximized.
     *
     * A native hit-test gates the capture to the title-bar band. Because the
     * interaction is consumed, Compose never receives a touch press for that
     * contact (no stuck pointer); title-bar content/buttons still work via the
     * synthesised mouse click. */
    if (!state->titleBarDragArmed && msg == WM_POINTERDOWN) {
        POINT pt; pt.x = (int)(short)LOWORD(lParam); pt.y = (int)(short)HIWORD(lParam);
        ScreenToClient(hwnd, &pt);
        if (pt.y >= 0 && pt.y < state->titleBarHeightPx) {
            state->titleBarDragArmed = TRUE;
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
    }
    if (state->titleBarDragArmed) {
        switch (msg) {
        case WM_POINTERUPDATE:
        case WM_POINTERLEAVE:
        case WM_POINTERCAPTURECHANGED:
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        case WM_POINTERUP:
            state->titleBarDragArmed = FALSE;
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        default:
            break;
        }
    }

    switch (msg) {

    /* While a backdrop is active, WS_SYSMENU must stay off no matter who
     * writes the style: with the frame extended over the whole window, that
     * bit makes DWM paint its own caption buttons over the app-drawn ones and
     * steal their hover. A one-shot strip in nativeSetBackdropStyle is not
     * enough — Tao reapplies its cached window flags on state transitions
     * (maximize, restore, focus), which used to bring the buttons back on the
     * first maximize. Enforcing the invariant here catches every writer. */
    case WM_STYLECHANGING:
        if (wParam == GWL_STYLE && state->backdropActive) {
            STYLESTRUCT *ss = (STYLESTRUCT *)lParam;
            ss->styleNew &= ~(DWORD)WS_SYSMENU;
        }
        break;

    /* During ShowWindow, DWM can request an erased client surface before GL
     * has presented into the now-visible redirection surface. Paint the themed
     * background only for that startup gap; after the first native redraw event
     * this is disabled to avoid solid-color flicker while resizing or dragging. */
    case WM_ERASEBKGND:
        /* Borderless transparent overlays: claim the erase and paint nothing.
         * A solid fill would reappear as a contour; the GL surface + tao
         * DwmEnableBlurBehindWindow empty region already present the desktop. */
        if (state->borderlessChrome) {
            return 1;
        }
        /* With a backdrop, newly exposed areas must be erased to GDI black:
         * GDI writes alpha 0 on the 32bpp redirection surface, which the
         * sheet-of-glass frame composites as "show the backdrop" (the classic
         * DWM custom-frame trick). Without it, a maximize left half a screen
         * of stale opaque pixels until GL presented at the new size — a very
         * visible pop. But ONLY the growth bands: blacking the whole client
         * on every erase made interactive resizing strobe between bare
         * material and content several times a second. */
        if (state->backdropActive) {
            HDC hdc = (HDC)wParam;
            RECT rc;
            if (hdc && GetClientRect(hwnd, &rc)) {
                HBRUSH black = (HBRUSH)GetStockObject(BLACK_BRUSH);
                if (rc.right > state->lastEraseClientW) {
                    RECT band = { state->lastEraseClientW, 0, rc.right, rc.bottom };
                    FillRect(hdc, &band, black);
                }
                if (rc.bottom > state->lastEraseClientH) {
                    RECT band = { 0, state->lastEraseClientH, rc.right, rc.bottom };
                    FillRect(hdc, &band, black);
                }
                state->lastEraseClientW = rc.right;
                state->lastEraseClientH = rc.bottom;
            }
            return 1;
        }
        if (state->startupBackgroundErase) {
            HDC hdc = (HDC)wParam;
            RECT rc;
            if (hdc && GetClientRect(hwnd, &rc)) {
                HBRUSH brush = CreateSolidBrush(state->bgColor);
                FillRect(hdc, &rc, brush);
                DeleteObject(brush);
            }
        }
        return 1;


    /* Fullscreen toggle: render + present the new-size frame synchronously,
     * INSIDE the geometry change, before Tao's async RESIZED event would
     * ever run. Tao's handler runs first so its cached size stays
     * consistent. Only the (near-)final size is forwarded — intermediate
     * frame-recalc geometries live for microseconds inside the win32 call. */
    case WM_WINDOWPOSCHANGED: {
        if (state->fsTransitionActive) {
            LRESULT result = CallWindowProcW(state->originalWndProc, hwnd, msg, wParam, lParam);
            RECT rc;
            if (GetClientRect(hwnd, &rc) && rc.right > 0 && rc.bottom > 0) {
                int dw = (int)rc.right - state->fsExpectedW;
                if (dw < 0) dw = -dw;
                int dh = (int)rc.bottom - state->fsExpectedH;
                if (dh < 0) dh = -dh;
                /* Tolerance: the exit target is an estimate. */
                if (dw <= 64 && dh <= 64) {
                    notifyFullscreenSizeChanged(hwnd, (int)rc.right, (int)rc.bottom);
                }
            }
            return result;
        }
        break;
    }

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

        /* Resize borders only while the window is actually resizable —
         * tao's set_resizable(false) drops WS_THICKFRAME at runtime. */
        LONG_PTR style = GetWindowLongPtrW(hwnd, GWL_STYLE);
        if (!IsZoomed(hwnd) && !state->isFullscreen && (style & WS_THICKFRAME)) {
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

        /* Compose-drawn caption buttons answer with their real hit codes —
         * the Snap Layouts protocol: HTMAXBUTTON is what makes Windows 11
         * show the snap flyout on hover. DWM draws nothing for these codes
         * on an opaque client (measured; the ghost-window buttons that once
         * suggested otherwise were an unresponsive-probe artifact), and the
         * NC mouse messages they generate are forwarded back to Compose
         * below, so hover, press and click all stay Compose-handled. */
        /* Measured in CLIENT space: when maximized, WM_NCCALCSIZE above
         * shifts the client top down by the border width, so a window-rect
         * comparison would truncate the bottom of the caption-button zone. */
        POINT client = pt;
        ScreenToClient(hwnd, &client);
        if (client.y >= 0 && client.y < state->titleBarHeightPx) {
            for (int i = 0; i < 3; ++i) {
                const RECT *rc = &state->captionButtonRects[i];
                if (rc->right > rc->left && rc->bottom > rc->top &&
                    client.x >= rc->left && client.x < rc->right &&
                    client.y >= rc->top && client.y < rc->bottom) {
                    return kCaptionButtonHitCodes[i];
                }
            }
            return HTCLIENT;
        }

        return HTCLIENT;
    }

    case WM_NCLBUTTONDOWN: {
        if (wParam == HTCAPTION) {
            ReleaseCapture();
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        /* Caption-button zones are non-client only for the Snap Layouts
         * hit-test; the interaction itself belongs to the Compose buttons.
         * Re-forward as a client press — same pattern as WM_NCMOUSEMOVE. */
        if (isCaptionButtonHit(wParam)) {
            POINT pt;
            pt.x = (short)LOWORD(lParam);
            pt.y = (short)HIWORD(lParam);
            ScreenToClient(hwnd, &pt);
            PostMessageW(hwnd, WM_LBUTTONDOWN, MK_LBUTTON, MAKELPARAM(pt.x, pt.y));
            return 0;
        }
        break;
    }

    case WM_NCLBUTTONUP: {
        if (isCaptionButtonHit(wParam)) {
            POINT pt;
            pt.x = (short)LOWORD(lParam);
            pt.y = (short)HIWORD(lParam);
            ScreenToClient(hwnd, &pt);
            PostMessageW(hwnd, WM_LBUTTONUP, 0, MAKELPARAM(pt.x, pt.y));
            return 0;
        }
        break;
    }

    case WM_NCLBUTTONDBLCLK: {
        if (wParam == HTCAPTION) {
            return DefWindowProcW(hwnd, msg, wParam, lParam);
        }
        /* A fast second click on a caption button arrives as a double-click;
         * Compose sees it as an ordinary second press. */
        if (isCaptionButtonHit(wParam)) {
            POINT pt;
            pt.x = (short)LOWORD(lParam);
            pt.y = (short)HIWORD(lParam);
            ScreenToClient(hwnd, &pt);
            PostMessageW(hwnd, WM_LBUTTONDOWN, MK_LBUTTON, MAKELPARAM(pt.x, pt.y));
            return 0;
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

    /* The caption buttons are non-client for the Snap Layouts hit-test, so a
     * cursor leaving the window straight from one of them produces only a
     * WM_NCMOUSELEAVE — without this forward, Compose never sees an Exit and
     * the button's hover highlight sticks. */
    case WM_NCMOUSELEAVE:
        PostMessageW(hwnd, WM_MOUSELEAVE, 0, 0);
        break;

    /* Tao's WM_SETTINGCHANGE handler re-derives DWMWA_USE_IMMERSIVE_DARK_MODE
     * from the SYSTEM theme, silently overriding the app-resolved flag — a
     * light-forced app on a system flipped to dark got a dark Mica under
     * light content. Let Tao run, then restore the resolved value on top. */
    case WM_SETTINGCHANGE: {
        LRESULT result = CallWindowProcW(state->originalWndProc, hwnd, msg, wParam, lParam);
        if (state->hasImmersiveDark) {
            BOOL dark = state->immersiveDark;
            DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */,
                                  &dark, sizeof(dark));
        }
        return result;
    }

    case WM_SYSCOMMAND: {
        WPARAM cmd = wParam & 0xFFF0;
        /* Suppress the Alt / F10 system-menu activation. On a custom-decorated
         * window with no menu bar, SC_KEYMENU makes DefWindowProc enter a modal
         * menu loop that blocks the Tao/winit message pump (MainEventsCleared,
         * GL present) — the app appears frozen until Alt/Esc is pressed again.
         * Tao still processes WM_SYSKEYDOWN/UP, so Compose keeps receiving the
         * Alt KeyDown/KeyUp; we only prevent the menu loop from starting.
         * Mirrors AWT, which returns mrConsume for sys-keys and never lets
         * DefWindowProc generate SC_KEYMENU. */
        if (cmd == SC_KEYMENU) return 0;
        if (state->isFullscreen) {
            if (cmd == SC_RESTORE || cmd == SC_MAXIMIZE ||
                cmd == SC_SIZE   || cmd == SC_MOVE) {
                return 0;
            }
        }
        break;
    }

    /* No menu bar exists, so Alt+<key> would otherwise produce the Windows
     * "no matching mnemonic" beep. MNC_CLOSE tells DefWindowProc to dismiss
     * the (non-existent) menu silently. */
    case WM_MENUCHAR:
        return MAKELRESULT(0, MNC_CLOSE);

    /* WM_CLOSE is cancelable: Tao turns it into CLOSE_REQUESTED and does not
     * DestroyWindow until the app confirms via requestClose. Reverting the
     * backdrop here permanently killed Mica on "Save before quit?" → Cancel
     * while WindowsBackdrop was still composed. The opaque last frame is
     * prepared on the confirmed destroy path only (nativePrepareClose from
     * TaoWindow.requestClose / host.prepareClose). */
    case WM_CLOSE:
        break;

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
/*  Package: dev.nucleusframework.window.tao                 */
/*  Class:   NativeTaoWindowsDecoBridge                                */
/* ================================================================== */

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeInstallDecoration(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint titleBarHeightPx)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;

    /* Prime the native → JVM upcall for the fullscreen-transition prepare:
     * it fires from WM_WINDOWPOSCHANGED with no JNIEnv of its own. */
    ensureDecoJVMCached(env);

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
    state->startupBackgroundErase = TRUE;

    SetPropW(hwnd, PROP_NAME, (HANDLE)state);

    LONG_PTR prevWndProc = SetWindowLongPtrW(
        hwnd, GWLP_WNDPROC, (LONG_PTR)decoWndProc);
    state->originalWndProc = (WNDPROC)prevWndProc;

    /* Extend bottom by 1px to keep DWM shadow without enabling glass over the
     * client area. With {0,0,0,1} DWM treats the client area as opaque, so
     * transparent pixels render as black (invisible on dark themes). */
    MARGINS margins = {0, 0, 0, 1};
    DwmExtendFrameIntoClientArea(hwnd, &margins);
    applyCaptionColors(hwnd, state);

    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeUninstallDecoration(
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
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetTitleBarHeight(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint heightPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;
    DecoState *state = getState(hwnd);
    if (state) state->titleBarHeightPx = (int)heightPx;
}

/**
 * Fully borderless chrome for overlay/ghost windows
 * (`DecoratedWindow(undecorated = true)`).
 *
 * Kills every DWM source of contour / drop shadow we can:
 *  - caption + border colours → COLOR_NONE
 *  - frame margins → {0,0,0,0} (the 1px bottom band exists *only* for shadow)
 *  - squared corners (no rounded AA outline)
 *  - visible frame border thickness 0 (Win11)
 *  - system backdrop none (no Mica/Acrylic halo)
 *
 * Per-pixel transparency still comes from tao's DwmEnableBlurBehindWindow
 * empty region. Safe to call repeatedly (e.g. after show()).
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetBorderlessChrome(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean borderless)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;
    DecoState *state = getState(hwnd);
    if (!state) return;
    BOOL wanted = borderless ? TRUE : FALSE;
    state->borderlessChrome = wanted;
    /* Backdrop sheet-of-glass would re-arm frame extension + shadow. */
    if (wanted) {
        state->sheetOfGlass = FALSE;
        state->backdropActive = FALSE;
    }
    applyCaptionColors(hwnd, state);
    applyFrameMargins(hwnd, state);

    int corner = wanted ? NUCLEUS_DWMWCP_DONOTROUND : NUCLEUS_DWMWCP_DEFAULT;
    DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
                          &corner, sizeof(corner));

    if (wanted) {
        /* DWMSBT_NONE — no system material halo around the HWND. */
        int none = 1;
        DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE,
                              &none, sizeof(none));
    }

    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
}

/**
 * Applies the whole window theme in one call — background brush, DWM
 * caption/border colors, immersive-dark flag, and the Windows 10 acrylic tint
 * when that fallback is live.
 *
 * [isDark] arrives resolved from the Kotlin side (window-background luminance
 * unless `WindowAppearance` overrides it) rather than being re-derived here:
 * a single resolution point is what makes it impossible for the DWM material
 * and the Compose-drawn glyphs to disagree.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetBackgroundColor(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint argb, jboolean isDark)
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

    /* No-op on the caption while a backdrop is active — it repaints it opaque.
     * The color is still stored, so removing the backdrop restores it. */
    applyCaptionColors(hwnd, state);

    /* The Windows 10 acrylic tint is ours to maintain: DWM themes its own
     * materials, but the accent policy holds whatever colour it was last
     * given. Without this a light/dark switch keeps the old tint and the
     * content stops being readable over the blur. */
    if (state && state->accentActive) {
        resolveLegacyBackdropApis();
        applyAccentAcrylic(hwnd, TRUE, resolveTintGradient(state));
    }

    BOOL dark = isDark ? TRUE : FALSE;
    if (state) {
        state->immersiveDark = dark;
        state->hasImmersiveDark = TRUE;
    }
    DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */,
                          &dark, sizeof(dark));
}

/**
 * Applies a system backdrop, degrading across three tiers:
 *
 *   1. Windows 11 22H2+  DWMWA_SYSTEMBACKDROP_TYPE  (documented)
 *   2. Windows 11 <22H2  DWMWA_MICA_EFFECT          (undocumented)
 *   3. Windows 10        SetWindowCompositionAttribute acrylic (undocumented)
 *
 * [style] is the DWM_SYSTEMBACKDROP_TYPE wire value: 0 auto, 1 none,
 * 2 Mica, 3 Acrylic, 4 Mica Alt. Tier 2 has only one material, so Mica and
 * Mica Alt both map to it and Acrylic falls through to tier 3. Tier 3 has no
 * Mica at all — it blurs what is behind the window rather than tinting from
 * the wallpaper — so every active style degrades to acrylic there. The app
 * gets an effect, never the wrong-looking nothing.
 *
 * Tiers 1 and 2 need the client area turned into a "sheet of glass"
 * ({-1,-1,-1,-1}) so whatever the renderer leaves at alpha 0 shows the
 * material. Tier 3 composites against the window's own transparent pixels
 * instead and must keep ordinary margins. Deactivating restores the 1px
 * bottom extension the decoration installs for the DWM shadow — see
 * nativeInstallDecoration for why the inactive case must NOT stay at -1 (DWM
 * then renders transparent pixels as black).
 *
 * [tier] pins the implementation (TIER_* above) instead of letting the OS
 * decide, so the fallbacks can be previewed on a machine that would otherwise
 * always take tier 1.
 *
 * Returns the tier actually showing afterwards (TIER_MODERN /
 * TIER_LEGACY_MICA / TIER_WIN10_ACRYLIC), or 0 when none is — including a
 * style this OS cannot honour, which the caller treats as "stay opaque".
 * The caller needs the tier, not just a boolean: the accent tier carries its
 * own tint, so the Compose-side tint layer must not double it.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetBackdropStyle(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint style, jint tintArgb, jboolean hasTint,
    jint tier)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return 0;

    resolveLegacyBackdropApis();
    DecoState *state = getState(hwnd);
    if (state) {
        state->hasTint = hasTint ? TRUE : FALSE;
        state->tintGradient = gradientFromArgb((DWORD)tintArgb);
    }
    BOOL wanted = BACKDROP_IS_ACTIVE(style);
    BOOL active = FALSE;
    BOOL sheetOfGlass = FALSE;
    BOOL accent = FALSE;
    int appliedTier = 0;

    /* A pinned tier skips the ones above it. This has to happen before the
     * tier-1 call, not after: on a real Windows 10 that call fails and has no
     * effect, but on a modern one it would succeed and apply the backdrop
     * anyway, masking the tier being previewed. */
    BOOL allowModern = (tier == TIER_AUTO || tier == TIER_MODERN);
    BOOL allowLegacyMica = (tier == TIER_AUTO || tier == TIER_LEGACY_MICA);
    BOOL allowAccent = (tier == TIER_AUTO || tier == TIER_WIN10_ACRYLIC);

    /* Tier 1. Also the way OFF is expressed on 22H2+, so it runs either way. */
    int value = (int)style;
    BOOL modern = allowModern && SUCCEEDED(DwmSetWindowAttribute(
        hwnd, DWMWA_SYSTEMBACKDROP_TYPE, &value, sizeof(value)));

    if (modern) {
        active = wanted;
        if (active) appliedTier = TIER_MODERN;
        /* The sheet of glass is NOT optional, however much it looks like it:
         * without it DWM treats the client area as opaque and composites the
         * renderer's alpha-0 pixels as solid black rather than showing the
         * backdrop (measured). It also makes DWM draw its own caption buttons
         * over our custom title bar — countered by the WS_SYSMENU strip
         * below. */
        sheetOfGlass = wanted;
        /* Leaving a lower tier: its mechanisms must be dismantled, not just
         * outranked. A live ACCENT_POLICY under a modern backdrop renders the
         * whole client area black (measured on the tier-3 → auto path), and a
         * lingering legacy Mica attribute is the same hazard. */
        if (state && state->accentActive) applyAccentAcrylic(hwnd, FALSE, 0);
        applyLegacyMica(hwnd, FALSE);
    } else {
        /* Tier 2 — Mica / Mica Alt on Windows 11 before 22H2. */
        if (allowLegacyMica && wanted && style != BACKDROP_STYLE_ACRYLIC &&
            applyLegacyMica(hwnd, TRUE)) {
            active = TRUE;
            sheetOfGlass = TRUE;
            appliedTier = TIER_LEGACY_MICA;
            /* A tier-3 accent policy left running would fight this material
             * the same way it fights the modern one. */
            if (state && state->accentActive) applyAccentAcrylic(hwnd, FALSE, 0);
        } else {
            applyLegacyMica(hwnd, FALSE);
            /* Tier 3 — Windows 10 acrylic. A pinned higher tier must NOT fall
             * through to it: pinning a tier the OS lacks means "stay opaque",
             * not "show a different material instead". */
            DWORD gradient = resolveTintGradient(state);
            active = allowAccent && wanted && applyAccentAcrylic(hwnd, TRUE, gradient);
            if (!active) applyAccentAcrylic(hwnd, FALSE, gradient);
            accent = active;
            if (active) appliedTier = TIER_WIN10_ACRYLIC;
        }
    }

    if (state) {
        state->backdropActive = active;
        state->accentActive = accent;
        /* The delta-erase baseline starts at the current client size: the
         * existing content is already rendered, nothing to black out yet. */
        RECT rc;
        if (GetClientRect(hwnd, &rc)) {
            state->lastEraseClientW = rc.right;
            state->lastEraseClientH = rc.bottom;
        }
    }

    /* Measured on a probe reproducing this exact config (custom NCCALCSIZE +
     * child render surface + sheet of glass): with WS_SYSMENU present DWM
     * paints its own caption buttons over the top-right of the client area,
     * offset from the app-drawn ones, and intercepts their hover; stripping
     * the bit removes them while the backdrop keeps showing. Restored when
     * the backdrop goes. Costs the system menu (Alt+Space) while active. */
    if (state) {
        LONG_PTR winStyle = GetWindowLongPtrW(hwnd, GWL_STYLE);
        if (sheetOfGlass && (winStyle & WS_SYSMENU)) {
            SetWindowLongPtrW(hwnd, GWL_STYLE, winStyle & ~(LONG_PTR)WS_SYSMENU);
            state->strippedSysMenu = TRUE;
        } else if (!sheetOfGlass && state->strippedSysMenu) {
            SetWindowLongPtrW(hwnd, GWL_STYLE, winStyle | WS_SYSMENU);
            state->strippedSysMenu = FALSE;
        }
    }

    if (state) {
        state->sheetOfGlass = sheetOfGlass;
        applyFrameMargins(hwnd, state);
    } else {
        MARGINS margins;
        margins.cxLeftWidth    = sheetOfGlass ? -1 : 0;
        margins.cxRightWidth   = sheetOfGlass ? -1 : 0;
        margins.cyTopHeight    = sheetOfGlass ? -1 : 0;
        margins.cyBottomHeight = sheetOfGlass ? -1 : 1;
        DwmExtendFrameIntoClientArea(hwnd, &margins);
    }

    /* Re-resolve the caption: it is DWM's while a backdrop shows, and the
     * themed color again once the backdrop is gone. */
    applyCaptionColors(hwnd, state);

    /* The WS_SYSMENU change above only takes effect after a frame change.
     * Client geometry is unaffected (WM_NCCALCSIZE above yields the same
     * client rect either way), so this does not disturb the renderer size. */
    SetWindowPos(hwnd, NULL, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE |
        SWP_NOZORDER | SWP_NOACTIVATE);
    RedrawWindow(hwnd, NULL, NULL, RDW_INVALIDATE | RDW_FRAME);
    return appliedTier;
}

/**
 * Publishes the client-space rects (physical px) of the Compose-drawn caption
 * buttons, as 12 ints: min(x,y,w,h), max(x,y,w,h), close(x,y,w,h). An
 * all-zero quad clears that slot. See DecoState.captionButtonRects.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetCaptionButtonRects(
    JNIEnv *env, jclass clazz, jlong hwndLong, jintArray rectsArray)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;
    DecoState *state = getState(hwnd);
    if (!state || !rectsArray) return;
    if ((*env)->GetArrayLength(env, rectsArray) < 12) return;

    jint v[12];
    (*env)->GetIntArrayRegion(env, rectsArray, 0, 12, v);
    for (int i = 0; i < 3; ++i) {
        state->captionButtonRects[i].left   = v[i * 4];
        state->captionButtonRects[i].top    = v[i * 4 + 1];
        state->captionButtonRects[i].right  = v[i * 4] + v[i * 4 + 2];
        state->captionButtonRects[i].bottom = v[i * 4 + 1] + v[i * 4 + 3];
    }
}

/**
 * Reverts an active backdrop to a plain opaque themed window, synchronously.
 * Must be called on the confirmed destroy path (Kotlin requestClose) before
 * DestroyWindow. Not from WM_CLOSE: that message is cancelable (Tao emits
 * CLOSE_REQUESTED only), and a permanent revert there left Mica dead after
 * "Cancel" while the Compose backdrop holder was still live.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativePrepareClose(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;
    revertBackdropForClose(hwnd, getState(hwnd));
}

/* Headful / e2e probe: is a DWM system backdrop currently armed on this HWND?
 * Reads DecoState.backdropActive after nativeSetBackdropStyle / prepareClose. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeIsBackdropActive(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return JNI_FALSE;
    DecoState *state = getState(hwnd);
    return (state && state->backdropActive) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetStartupBackgroundEraseEnabled(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean enabled)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return;

    DecoState *state = getState(hwnd);
    if (state) state->startupBackgroundErase = enabled ? TRUE : FALSE;
}

/* Estimated client size after the fullscreen exit restore. Exact for the
 * normal case (WM_NCCALCSIZE keeps the full top and the default side/bottom
 * frame); the maximized case approximates with the work area. */
static void estimateRestoredClientSize(HWND hwnd, DecoState *state, int *w, int *h) {
    if (state->savedPlacement.showCmd == SW_SHOWMAXIMIZED) {
        HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO mi;
        mi.cbSize = sizeof(mi);
        GetMonitorInfoW(hMon, &mi);
        *w = mi.rcWork.right - mi.rcWork.left;
        *h = mi.rcWork.bottom - mi.rcWork.top;
    } else {
        UINT dpi = getDpi(hwnd);
        int fx = getSystemMetrics(SM_CXSIZEFRAME, dpi)
               + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
        int fy = getSystemMetrics(SM_CYSIZEFRAME, dpi)
               + getSystemMetrics(SM_CXPADDEDBORDERWIDTH, dpi);
        RECT nr = state->savedPlacement.rcNormalPosition;
        *w = (nr.right - nr.left) - 2 * fx;
        *h = (nr.bottom - nr.top) - fy;
    }
}

/* Target client size of the NEXT fullscreen toggle as [width, height], so
 * the caller can pre-layout (warm the Compose measure/layout at the final
 * size without presenting) before nativeSetFullscreen. NULL on a would-be
 * no-op. Enter is exact (monitor + overhang); exit is an estimate — the
 * synchronous WM_WINDOWPOSCHANGED prepare renders at the REAL size. */
JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeGetFullscreenTargetSize(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean fullscreen)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return NULL;
    DecoState *state = getState(hwnd);
    if (!state) return NULL;
    if ((fullscreen && state->isFullscreen) || (!fullscreen && !state->isFullscreen)) return NULL;

    jint targetSize[2] = { 0, 0 };
    if (fullscreen) {
        HMONITOR hMon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO mi;
        mi.cbSize = sizeof(mi);
        GetMonitorInfoW(hMon, &mi);
        int expandX = 0, expandY = 0;
        getFullscreenExpandOffset(hMon, &mi, &expandX, &expandY);
        targetSize[0] = (mi.rcMonitor.right - mi.rcMonitor.left) + expandX;
        targetSize[1] = (mi.rcMonitor.bottom - mi.rcMonitor.top) + expandY;
    } else {
        int w = 0, h = 0;
        estimateRestoredClientSize(hwnd, state, &w, &h);
        targetSize[0] = w;
        targetSize[1] = h;
    }

    if (targetSize[0] <= 0 || targetSize[1] <= 0) return NULL;
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    (*env)->SetIntArrayRegion(env, arr, 0, 2, targetSize);
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetFullscreen(
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

        /* The window KEEPS its WS_CAPTION | WS_THICKFRAME styles — same as
         * Windows Terminal's fullscreen. Stripping them detaches the DWM
         * frame, and every re-attach on exit paints 1-3 compositions of the
         * legacy GDI caption + basic frame regardless of call order
         * (captured on this machine). The styles are invisible in
         * fullscreen anyway: WM_NCCALCSIZE returns the full window as
         * client, WM_NCHITTEST disables the resize borders, and
         * WM_SYSCOMMAND blocks move/size. Only WS_MAXIMIZE is dropped, so
         * the fullscreen geometry is not fighting a zoomed state. */
        if (state->savedStyle & WS_MAXIMIZE) {
            SetWindowLongW(hwnd, GWL_STYLE, state->savedStyle & ~(LONG)WS_MAXIMIZE);
        }

        /* Drop every DWM adornment BEFORE the geometry jump, so no
         * intermediate composition still carries the windowed frame at the
         * fullscreen size: the 1px bottom frame extension (a light hairline
         * once the window covers the monitor), the caption/border fills
         * (the white contour, COLOR_DEFAULT in backdrop mode), and the
         * rounded-corner clip (anti-aliased light corners on a
         * rectangular-content screen). */
        applyFrameMargins(hwnd, state);
        applyCaptionColors(hwnd, state);
        int corner = NUCLEUS_DWMWCP_DONOTROUND;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
            &corner, sizeof(corner));

        /* Cover the monitor as a NON-topmost window (HWND_NOTOPMOST), exactly
         * matching SDL's borderless-fullscreen pattern. We deliberately do NOT
         * use HWND_TOPMOST: a topmost fullscreen window forms its own z-order
         * band, which pushes owned dialogs/popups *below* it (they'd need to be
         * topmost too) and gets the window promoted to a hardware overlay plane
         * (DirectFlip/MPO), so composited dialogs are hidden and the
         * independent-flip↔composed toggle flashes DWM's stale non-client cache
         * ("old frame"). As a plain top window, owned dialogs sit naturally
         * above it and DWM composes normally. The taskbar still hides because
         * the shell auto-hides it for a foreground window that covers the whole
         * monitor — no topmost required (this is exactly what SDL relies on). */
        /* Overhang the monitor by FS_EXPAND_PX on an edge with no adjacent
         * monitor (the overhang lands off-screen, so it is invisible): a
         * client rect that exactly matches the screen is what triggers the
         * driver's exclusive / independent-flip promotion (see
         * FS_EXPAND_PX). Deliberately NO SetWindowRgn clip: a region'd
         * window loses its DWM frame and is rendered in BASIC mode — the
         * exit transition then flashes the classic GDI caption and a black
         * surface while DWM re-takes the window (captured on this machine).
         * SWP_NOCOPYBITS keeps USER32 from bit-blitting the old windowed
         * client into the new rect (the issue-413 dual ghost). */
        int monW = mi.rcMonitor.right - mi.rcMonitor.left;
        int monH = mi.rcMonitor.bottom - mi.rcMonitor.top;
        int expandX = 0, expandY = 0;
        getFullscreenExpandOffset(hMon, &mi, &expandX, &expandY);

        /* The WM_WINDOWPOSCHANGED this generates re-enters the JVM
         * synchronously (decoWndProc), rendering + presenting the
         * fullscreen frame before SetWindowPos returns. */
        state->fsTransitionActive = TRUE;
        state->fsExpectedW = monW + expandX;
        state->fsExpectedH = monH + expandY;
        SetWindowPos(hwnd, HWND_NOTOPMOST,
            mi.rcMonitor.left, mi.rcMonitor.top,
            monW + expandX, monH + expandY,
            SWP_FRAMECHANGED | SWP_NOCOPYBITS);
        state->fsTransitionActive = FALSE;

        /* Wait for DWM to composite the new geometry before letting window
         * transitions animate again: a re-enable racing the composition
         * lets DWM animate the stale windowed frame into place. One flush
         * is at most one composition pass — no cloak, no hide. */
        DwmFlush();
        BOOL enableTransitions = FALSE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &enableTransitions, sizeof(enableTransitions));
    } else {
        if (!state->isFullscreen) return;

        BOOL disableTransitions = TRUE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &disableTransitions, sizeof(disableTransitions));

        state->isFullscreen = FALSE;

        /* Reassert the windowed DWM adornments the enter path dropped —
         * before the geometry restore, so the very first windowed
         * composition already has the themed border/caption back (a stale
         * fullscreen COLOR_NONE otherwise left a bare or accent-coloured
         * contour after exit). */
        applyFrameMargins(hwnd, state);
        applyCaptionColors(hwnd, state);
        int corner = NUCLEUS_DWMWCP_DEFAULT;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
            &corner, sizeof(corner));

        /* No styles to restore — they were never stripped (see the enter
         * path): the DWM frame stays attached across the whole toggle, so
         * the legacy-caption / basic-frame flash cannot happen. The
         * placement restore alone re-applies the saved geometry (and
         * SW_SHOWMAXIMIZED re-adds WS_MAXIMIZE itself). The synchronous
         * prepare renders the windowed-size frame inside the restore, so
         * DWM never composites the stale fullscreen frame cropped into the
         * small window. */
        state->fsTransitionActive = TRUE;
        estimateRestoredClientSize(hwnd, state, &state->fsExpectedW, &state->fsExpectedH);
        SetWindowPlacement(hwnd, &state->savedPlacement);
        SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOCOPYBITS);
        state->fsTransitionActive = FALSE;

        DwmFlush();
        BOOL enableTransitions = FALSE;
        DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
            &enableTransitions, sizeof(enableTransitions));
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeIsFullscreen(
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
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetOwner(
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

/* Returns the primary monitor's scale factor as `(scale * 1000)`. Falls back
 * to GetDeviceCaps(LOGPIXELSX) when GetDpiForSystem isn't available
 * (pre-Windows 10 1607). Used by DecoratedWindow when the window's own
 * scale factor isn't yet resolvable (pre-onWindowReady). */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeGetPrimaryMonitorScaleMilli(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    UINT dpi = 96;
    HMODULE hUser32 = GetModuleHandleA("user32.dll");
    if (hUser32) {
        typedef UINT (WINAPI *PFN_GetDpiForSystem)(void);
        PFN_GetDpiForSystem pGetDpiForSystem =
            (PFN_GetDpiForSystem)GetProcAddress(hUser32, "GetDpiForSystem");
        if (pGetDpiForSystem) {
            dpi = pGetDpiForSystem();
        } else {
            HDC hdc = GetDC(NULL);
            if (hdc) {
                dpi = (UINT)GetDeviceCaps(hdc, LOGPIXELSX);
                ReleaseDC(NULL, hdc);
            }
        }
    }
    if (dpi == 0) dpi = 96;
    return (jint)((dpi * 1000) / 96);
}

/* Returns [x, y, width, height] of the primary monitor's work area (full
 * screen minus the taskbar) in physical pixels. Used by DecoratedWindow to
 * resolve [WindowPosition.Aligned] for the initial outer position. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeGetPrimaryMonitorWorkArea(
    JNIEnv *env, jclass clazz)
{
    (void)clazz;
    RECT r;
    if (!SystemParametersInfoW(SPI_GETWORKAREA, 0, &r, 0)) return NULL;
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

/* Converts a window-client physical pixel position to screen physical
 * pixels. Returns [screenX, screenY] or NULL on failure. Used by the
 * touch-drag path in TitleBar.kt to compute window-move deltas: with
 * `RegisterTouchWindow` active, Windows does not synthesize mouse
 * messages from touch, so `WM_NCLBUTTONDOWN HTCAPTION` (PostMessage)
 * cannot drive a drag during a touch sequence. We instead track the
 * finger's screen position ourselves and apply `setOuterPosition`. */
JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeClientToScreen(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint xClient, jint yClient)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd) return NULL;
    POINT p; p.x = xClient; p.y = yClient;
    if (!ClientToScreen(hwnd, &p)) return NULL;
    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    jint values[2] = { (jint)p.x, (jint)p.y };
    (*env)->SetIntArrayRegion(env, arr, 0, 2, values);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeIsCursorOverWindowOrOwnedPopup(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND owner = (HWND)(uintptr_t)hwndLong;
    if (!owner || !IsWindow(owner)) return JNI_FALSE;

    POINT pt;
    if (!GetCursorPos(&pt)) return JNI_FALSE;
    HWND hit = WindowFromPoint(pt);
    if (!hit) return JNI_FALSE;

    HWND root = GetAncestor(hit, GA_ROOT);
    if (!root) root = hit;
    if (root == owner) return JNI_TRUE;
    return isOwnedTaoPopup(root, owner) ? JNI_TRUE : JNI_FALSE;
}

/* Returns [x, y, width, height] of the window's outer bounds in screen
 * coordinates (physical pixels). Used by DecoratedDialog to centre itself on
 * its parent. Returns NULL if hwnd is invalid. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeGetWindowRect(
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

static int roundToInt(double value) {
    return (int)(value >= 0.0 ? value + 0.5 : value - 0.5);
}

static int clampInt(int value, int minValue, int maxValue) {
    if (value < minValue) return minValue;
    if (value > maxValue) return maxValue;
    return value;
}

/* Win32 IsZoomed. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeIsMaximized(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return JNI_FALSE;
    return IsZoomed(hwnd) ? JNI_TRUE : JNI_FALSE;
}

/* Atomic unmaximize + reposition under the finger when a touch drag starts
 * on a maximized window. The horizontal anchor preserves the finger's
 * fractional X position within the title bar. Y is clamped to the monitor
 * work area top + the title-bar mid-height so the bar lands on screen.
 * Returns the restored outer rect as [x, y, w, h] in physical pixels. */
JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativePrepareTitleBarTouchDrag(
    JNIEnv *env, jclass clazz, jlong hwndLong,
    jint currentScreenX, jint currentScreenY,
    jint startScreenX, jint startScreenY)
{
    (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return NULL;

    RECT currentRect;
    if (!GetWindowRect(hwnd, &currentRect)) return NULL;
    jlongArray currentArr = NULL;
    if (!IsZoomed(hwnd)) {
        currentArr = (*env)->NewLongArray(env, 4);
        if (currentArr) {
            jlong values[4] = {
                (jlong)currentRect.left,
                (jlong)currentRect.top,
                (jlong)(currentRect.right - currentRect.left),
                (jlong)(currentRect.bottom - currentRect.top),
            };
            (*env)->SetLongArrayRegion(env, currentArr, 0, 4, values);
        }
        return currentArr;
    }

    WINDOWPLACEMENT wp;
    wp.length = sizeof(WINDOWPLACEMENT);
    if (!GetWindowPlacement(hwnd, &wp)) return NULL;

    int normalWidth = wp.rcNormalPosition.right - wp.rcNormalPosition.left;
    int normalHeight = wp.rcNormalPosition.bottom - wp.rcNormalPosition.top;
    if (normalWidth <= 0 || normalHeight <= 0) return NULL;

    int maximizedWidth = currentRect.right - currentRect.left;
    double xFraction = 0.5;
    if (maximizedWidth > 0) {
        xFraction = ((double)startScreenX - (double)currentRect.left) / (double)maximizedWidth;
        if (xFraction < 0.0) xFraction = 0.0;
        if (xFraction > 1.0) xFraction = 1.0;
    }

    int titleAnchorY = startScreenY - currentRect.top;
    DecoState *state = getState(hwnd);
    int maxTitleAnchorY = state
        ? state->titleBarHeightPx / 2
        : getSystemMetrics(SM_CYCAPTION, getDpi(hwnd)) / 2;
    if (maxTitleAnchorY < 1) maxTitleAnchorY = 1;
    titleAnchorY = clampInt(titleAnchorY, 0, maxTitleAnchorY);

    int targetLeft = roundToInt((double)currentScreenX - xFraction * (double)normalWidth);
    int targetTop = (int)currentScreenY - titleAnchorY;

    POINT monitorPoint; monitorPoint.x = currentScreenX; monitorPoint.y = currentScreenY;
    HMONITOR hMon = MonitorFromPoint(monitorPoint, MONITOR_DEFAULTTONEAREST);
    MONITORINFO mi; mi.cbSize = sizeof(mi);
    if (GetMonitorInfoW(hMon, &mi) && targetTop < mi.rcWork.top) {
        targetTop = mi.rcWork.top;
    }

    wp.flags &= ~(UINT)WPF_RESTORETOMAXIMIZED;
    wp.showCmd = SW_SHOWNORMAL;
    wp.rcNormalPosition.left = targetLeft;
    wp.rcNormalPosition.top = targetTop;
    wp.rcNormalPosition.right = targetLeft + normalWidth;
    wp.rcNormalPosition.bottom = targetTop + normalHeight;

    BOOL disableTransitions = TRUE;
    DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
        &disableTransitions, sizeof(disableTransitions));
    SetWindowPlacement(hwnd, &wp);
    SetWindowPos(hwnd, NULL, targetLeft, targetTop, normalWidth, normalHeight,
        SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    BOOL enableTransitions = FALSE;
    DwmSetWindowAttribute(hwnd, 3 /* DWMWA_TRANSITIONS_FORCEDISABLED */,
        &enableTransitions, sizeof(enableTransitions));

    RECT restoredRect;
    if (!GetWindowRect(hwnd, &restoredRect)) {
        restoredRect.left = targetLeft;
        restoredRect.top = targetTop;
        restoredRect.right = targetLeft + normalWidth;
        restoredRect.bottom = targetTop + normalHeight;
    }
    jlongArray arr = (*env)->NewLongArray(env, 4);
    if (!arr) return NULL;
    jlong values[4] = {
        (jlong)restoredRect.left,
        (jlong)restoredRect.top,
        (jlong)(restoredRect.right - restoredRect.left),
        (jlong)(restoredRect.bottom - restoredRect.top),
    };
    (*env)->SetLongArrayRegion(env, arr, 0, 4, values);
    return arr;
}

/* Synchronous outer-position move via `SetWindowPos(SWP_NOSIZE)`. Used by the
 * Windows touch title-bar drag path because Tao's `setOuterPosition` is
 * asynchronous (posts a user event to the Tao loop); under a touch stream
 * of 60-100 events/s that backlog produces visible lag. Calling
 * `SetWindowPos` directly from the touch-event handler keeps the window
 * pinned to the finger. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoWindowsDecoBridge_nativeSetWindowOuterPositionPx(
    JNIEnv *env, jclass clazz, jlong hwndLong, jint xPx, jint yPx)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return;
    SetWindowPos(hwnd, NULL, (int)xPx, (int)yPx, 0, 0,
        SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
}

