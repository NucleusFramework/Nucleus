/**
 * Transparent WGL rendering bridge for the Tao Windows overlay & popup
 * HWNDs.
 *
 * Per NATIVE_VIEW_WINDOWS_PLAN.md "Rendering surface":
 *
 *   1. The overlay HDC reuses the HOST's pixel format (cached at host
 *      init by `nucleus_tao_gl.c`). MSDN's wglShareLists invariant
 *      ("All rendering contexts of a shared display list must use an
 *      identical pixel format") carries over to the ARB share path on
 *      every known driver, including NVIDIA, where mismatched formats
 *      crash nvoglv64.dll inside flushAndSubmit on the frame following
 *      a context switch.
 *   2. The overlay HGLRC is created via
 *      `wglCreateContextAttribsARB(overlayDC, hostHGLRC, attribs)` —
 *      `hShareContext = hostHGLRC` atomically joins the popup to the
 *      host's share group at creation, sharing shaders/programs/textures.
 *      Each HWND keeps its own GrDirectContext; only server-side GL
 *      objects are shared.
 *   3. After window creation: arm `DwmEnableBlurBehindWindow` with the
 *      empty-region trick so DWM honors back-buffer alpha. Re-armed on
 *      WM_DWMCOMPOSITIONCHANGED.
 *   4. `wglSwapIntervalEXT(0)` on the overlay context. Vsync is provided
 *      by `DwmFlush()` after `SwapBuffers` (GLFW pattern, mitigates the
 *      windowed-mode SwapBuffers stutter on DWM).
 *   5. DWM polish: rounded corners (Win11), dark-mode immersive setting,
 *      and `DwmExtendFrameIntoClientArea` for a four-sided shadow.
 *
 * The host functions are resolved at runtime via
 * `GetProcAddress(GetModuleHandleW(L"nucleus_tao_gl.dll"), ..)` so this
 * DLL doesn't link against nucleus_tao_gl.dll directly.
 *
 * Linked into nucleus_tao_windows_native_view.dll.
 * Linked libraries: opengl32.lib gdi32.lib dwmapi.lib (added by build.bat).
 */

#include <jni.h>
#include <windows.h>
#include <dwmapi.h>
#include <GL/gl.h>
#include "nucleus_tao_windows_overlay_internal.h"

/* WGL ARB extension entry-points + bootstrap. */
typedef HGLRC (WINAPI *PFN_wglCreateContextAttribsARB)(HDC, HGLRC, const int *);
typedef BOOL  (WINAPI *PFN_wglSwapIntervalEXT)(int);

static PFN_wglCreateContextAttribsARB pwglCreateContextAttribsARB = NULL;
static PFN_wglSwapIntervalEXT         pwglSwapIntervalEXT         = NULL;
static volatile LONG sWglExtLoaded = 0;

#define WGL_CONTEXT_MAJOR_VERSION_ARB             0x2091
#define WGL_CONTEXT_MINOR_VERSION_ARB             0x2092
#define WGL_CONTEXT_PROFILE_MASK_ARB              0x9126
#define WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB 0x00000002

/* Cached host bridge function pointers. */
typedef HGLRC (*PFN_nucleus_tao_host_hglrc)(void);
typedef int   (*PFN_nucleus_tao_host_pixel_format)(PIXELFORMATDESCRIPTOR *);
static PFN_nucleus_tao_host_hglrc        pHostHglrc        = NULL;
static PFN_nucleus_tao_host_pixel_format pHostPixelFormat  = NULL;
static volatile LONG sHostBridgeResolved = 0;

/* DWM constants — defined manually because dwmapi.h ships them in
 * different SDK versions and our /NODEFAULTLIB build sometimes pulls
 * an older header. */
#ifndef DWMWA_USE_IMMERSIVE_DARK_MODE
#define DWMWA_USE_IMMERSIVE_DARK_MODE 20
#endif
#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
#ifndef DWMWCP_DEFAULT
#define DWMWCP_DEFAULT 0
#endif
#ifndef DWMWCP_DONOTROUND
#define DWMWCP_DONOTROUND 1
#endif
#ifndef DWMWCP_ROUND
#define DWMWCP_ROUND 2
#endif
#ifndef DWMWCP_ROUNDSMALL
#define DWMWCP_ROUNDSMALL 3
#endif

/* Loads wglCreateContextAttribsARB / wglSwapIntervalEXT via a
 * throw-away 1.x context — the standard WGL bootstrap. The host's
 * `nucleus_tao_gl.c` may have already loaded these in the same
 * process, but since they live in a separate DLL we re-resolve them
 * here from any current GL context (the host's HGLRC is current at
 * the time the overlay is created if the host is up). */
static LRESULT CALLBACK dummyWndProcGl(HWND h, UINT m, WPARAM w, LPARAM l) {
    return DefWindowProcW(h, m, w, l);
}

static void loadWglExtensions(void) {
    if (InterlockedCompareExchange(&sWglExtLoaded, 1, 0) != 0) return;

    /* Try to resolve from the currently-current context first (cheaper). */
    HGLRC current = wglGetCurrentContext();
    if (current) {
        pwglCreateContextAttribsARB = (PFN_wglCreateContextAttribsARB)
            wglGetProcAddress("wglCreateContextAttribsARB");
        pwglSwapIntervalEXT = (PFN_wglSwapIntervalEXT)
            wglGetProcAddress("wglSwapIntervalEXT");
        if (pwglCreateContextAttribsARB) return;
    }

    /* Fallback: bootstrap a dummy context. */
    HINSTANCE hInst = GetModuleHandleW(NULL);
    WNDCLASSW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = dummyWndProcGl;
    wc.hInstance = hInst;
    wc.lpszClassName = L"NucleusTaoOverlayGlDummy";
    RegisterClassW(&wc);

    HWND hwnd = CreateWindowW(L"NucleusTaoOverlayGlDummy", L"", WS_OVERLAPPED,
        0, 0, 1, 1, NULL, NULL, hInst, NULL);
    if (!hwnd) return;

    HDC hdc = GetDC(hwnd);
    PIXELFORMATDESCRIPTOR pfd;
    ZeroMemory(&pfd, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    int pf = ChoosePixelFormat(hdc, &pfd);
    SetPixelFormat(hdc, pf, &pfd);

    HGLRC tmp = wglCreateContext(hdc);
    if (tmp) {
        HDC prevDc = wglGetCurrentDC();
        HGLRC prevRc = wglGetCurrentContext();
        wglMakeCurrent(hdc, tmp);
        pwglCreateContextAttribsARB = (PFN_wglCreateContextAttribsARB)
            wglGetProcAddress("wglCreateContextAttribsARB");
        pwglSwapIntervalEXT = (PFN_wglSwapIntervalEXT)
            wglGetProcAddress("wglSwapIntervalEXT");
        wglMakeCurrent(prevDc, prevRc);
        wglDeleteContext(tmp);
    }

    ReleaseDC(hwnd, hdc);
    DestroyWindow(hwnd);
    UnregisterClassW(L"NucleusTaoOverlayGlDummy", hInst);
}

static void resolveHostBridge(void) {
    if (InterlockedCompareExchange(&sHostBridgeResolved, 1, 0) != 0) return;
    HMODULE m = GetModuleHandleW(L"nucleus_tao_gl.dll");
    if (!m) return;
    pHostHglrc = (PFN_nucleus_tao_host_hglrc)
        GetProcAddress(m, "nucleus_tao_host_hglrc");
    pHostPixelFormat = (PFN_nucleus_tao_host_pixel_format)
        GetProcAddress(m, "nucleus_tao_host_pixel_format");
}

/** Empty-region blur-behind: DWM honors back-buffer alpha without WS_EX_LAYERED. */
static void armBlurBehind(HWND hwnd) {
    DWM_BLURBEHIND bb;
    ZeroMemory(&bb, sizeof(bb));
    bb.dwFlags = DWM_BB_ENABLE | DWM_BB_BLURREGION;
    bb.fEnable = TRUE;
    bb.hRgnBlur = CreateRectRgn(0, 0, -1, -1);
    DwmEnableBlurBehindWindow(hwnd, &bb);
    if (bb.hRgnBlur) DeleteObject(bb.hRgnBlur);
}

static void applyDwmPolish(HWND hwnd) {
    /* Rounded corners — Win11 22000+, silently no-ops on Win10. */
    DWM_WINDOW_CORNER_PREFERENCE corner = DWMWCP_ROUNDSMALL;
    DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
                          &corner, sizeof(corner));

    /* Mirror the host's dark-mode setting (best effort: just enable). */
    BOOL dark = TRUE;
    DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                          &dark, sizeof(dark));

    /* Four-sided shadow via extended frame margins of {1,1,1,1}. */
    MARGINS margins = { 1, 1, 1, 1 };
    DwmExtendFrameIntoClientArea(hwnd, &margins);
}

void nucleus_tao_overlay_gl_rearm_blur(GlSurface *gl) {
    if (gl && gl->hwnd) armBlurBehind(gl->hwnd);
}

BOOL nucleus_tao_overlay_gl_init(GlSurface *gl) {
    if (!gl || !gl->hwnd) return FALSE;
    HWND hwnd = gl->hwnd;

    loadWglExtensions();
    resolveHostBridge();

    HDC hdc = GetDC(hwnd);
    if (!hdc) return FALSE;

    /* SINGLE-HGLRC ARCHITECTURE: we DON'T create a new HGLRC for the
     * overlay. Instead we set the overlay HDC to the host's exact pixel
     * format, then expose the host's HGLRC as our hglrc — wglMakeCurrent
     * will switch the host's HGLRC to draw into the overlay's HDC.
     *
     * Why: NVIDIA's WGL ICD reproducibly crashes nvoglv64.dll inside
     * Skia's flushAndSubmit when the host's HGLRC and a sibling HGLRC
     * coexist in the same process — even with identical pixel formats
     * and the share-group join via wglCreateContextAttribsARB
     * (hShareContext = hostHGLRC). The single-HGLRC path eliminates
     * the multi-context surface entirely. Skia keeps a single
     * DirectContext (the host's), shared across host + every overlay
     * + every popup. resetGLAll() between HDC switches handles the
     * "default framebuffer changed" state delta.
     *
     * We still apply DwmEnableBlurBehindWindow + DWM polish on the
     * overlay HWND so DWM honors its back-buffer alpha (the host HWND
     * stays opaque because it's not armed). */
    HGLRC hostHglrc = pHostHglrc ? pHostHglrc() : NULL;
    if (!hostHglrc) {
        /* Host hasn't initialized its WGL yet (shouldn't happen in
         * practice — overlay creation always follows host attach). */
        ReleaseDC(hwnd, hdc);
        return FALSE;
    }

    int hostFormat = 0;
    PIXELFORMATDESCRIPTOR pfd;
    ZeroMemory(&pfd, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    if (pHostPixelFormat) {
        hostFormat = pHostPixelFormat(&pfd);
    }
    if (hostFormat == 0) {
        ReleaseDC(hwnd, hdc);
        return FALSE;
    }
    if (!SetPixelFormat(hdc, hostFormat, &pfd)) {
        ReleaseDC(hwnd, hdc);
        return FALSE;
    }

    gl->hdc = hdc;
    gl->hglrc = hostHglrc; /* Borrowed reference — NOT deleted on destroy. */

    armBlurBehind(hwnd);
    applyDwmPolish(hwnd);
    return TRUE;
}

void nucleus_tao_overlay_gl_destroy(GlSurface *gl) {
    if (!gl) return;
    /* gl->hglrc is the host's HGLRC — don't delete it. Just release the
     * HDC. If the host's HGLRC happens to be current on this HDC,
     * wglMakeCurrent(NULL, NULL) would also clear the host's binding,
     * so the host re-makes-current on its next render anyway. */
    if (gl->hdc && gl->hwnd) ReleaseDC(gl->hwnd, gl->hdc);
    gl->hdc = NULL;
    gl->hglrc = NULL;
}

/* JNI exports for nativeMakeCurrent / nativeSwapBuffers live in
 * overlay.c and popup.c respectively — each owns the lifecycle struct
 * containing the GlSurface. */
