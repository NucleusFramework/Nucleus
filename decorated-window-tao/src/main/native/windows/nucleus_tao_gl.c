/**
 * JNI bridge: renderer for the Tao backend on Windows.
 *
 * Single backend: EGL / ANGLE — OpenGL ES translated to Direct3D 11 by ANGLE
 * (libEGL.dll + libGLESv2.dll, shipped alongside this DLL). D3D11 has a
 * guaranteed WARP software fallback, so it works on RDP sessions, VMs and
 * driverless machines. Skia uses its GL backend — it assembles the GL
 * interface from the current ANGLE ES context via eglGetProcAddress
 * (DirectContext.makeGLWithInterface), exactly like the Skia ANGLE bots.
 *
 * The legacy WGL backend was removed once the NativeView overlay gained its
 * DirectComposition path (see nucleus_tao_windows_overlay_dcomp.cpp): D3D11
 * absorbs the driver variance WGL was exposed to, WARP covers the machines
 * WGL couldn't, and dropping it deleted the swap-thread present machinery
 * (ANGLE presents inline — a cross-thread present on ANGLE's shared
 * per-display D3D11 device deadlocks the global display lock).
 *
 * Presentation can be a FLIP-model DXGI swapchain created here on the render
 * surface, not the window surface ANGLE would build for the HWND: ANGLE's
 * HWND swapchain is blt-model, and a blt present is copied into the GDI
 * redirection surface clipped to the window's visible region — which ends
 * at the edge of the desktop. A frame presented while the window is partly
 * off-screen therefore never reaches DWM for that part: the window shows a
 * white (never painted) or stale band when dragged into view, and taskbar
 * thumbnails / Alt+Tab, which DWM draws from the same copy, show it even
 * if it never is. A flip-model swapchain is handed to DWM whole — but it is
 * opt-in for now, see flipPresentEnabled for what it still breaks. ANGLE
 * renders into a D3D11 texture through an EGL_ANGLE_d3d_texture_client_buffer
 * pbuffer (the context's draw surface — so every eglMakeCurrent below binds
 * it), and nativePresent copies that texture into the back buffer and
 * presents (same pattern as the overlay path, overlay_dcomp.cpp). The
 * texture is upside down for D3D (GL row 0 = texture row 0), so the JVM
 * renders with a TOP_LEFT origin into it (nativeIsTopDown). A window that
 * needs per-pixel alpha keeps ANGLE's window surface, which is also the
 * fallback should any step fail (createPresentPath).
 *
 * The GL surface is NOT the Tao HWND itself: it is a borderless WS_CHILD
 * "render surface" HWND covering the client area, kept at the BOTTOM of the
 * sibling z-order so NativeView children (WebView, …) composite above the
 * Compose canvas. The child is transparent to input (WS_EX_TRANSPARENT +
 * HTTRANSPARENT): mouse, touch, OLE drag-and-drop all fall through to the
 * Tao HWND, whose subclass (nucleus_tao_windows_deco.c) keeps handling them.
 *
 * Sequence used by the JVM side:
 *   nativeAttach(hwnd)     → creates render-surface child + ES context
 *   nativeMakeCurrent(h)   → restores current context (defensive)
 *   nativeResize(h,w,h,s)  → resizes the child, stores dimensions for the
 *                            next BackendRenderTarget
 *   <Skia rendering>
 *   nativePresent(h)       → flip-chain copy + Present (eglSwapBuffers on
 *                            the fallback window surface)
 *   nativeDetach(h)        → tear-down
 *
 * Linked libraries: gdi32.lib user32.lib kernel32.lib
 * (ANGLE is loaded dynamically via LoadLibrary — no import lib.)
 */

#include <jni.h>
#include <windows.h>

/* ANGLE EGL entry points are resolved at runtime via GetProcAddress; we only
 * need the Khronos typedefs and the ANGLE-specific platform constants, so we
 * suppress the prototypes (we never link libEGL). */
#define EGL_EGL_PROTOTYPES 0
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>

#define COBJMACROS
#include <d3d11.h>
#include <dxgi1_3.h>

/* GUIDs kept local — avoids linking dxguid.lib under /NODEFAULTLIB. */
static const GUID kIID_IDXGIDevice =
    { 0x54ec77fa, 0x1377, 0x44e6, { 0x8c, 0x32, 0x88, 0xfd, 0x5f, 0x44, 0xc8, 0x4c } };
static const GUID kIID_IDXGIFactory2 =
    { 0x50c83a1c, 0xe072, 0x4c48, { 0x87, 0xb0, 0x36, 0x30, 0xfa, 0x36, 0xa6, 0xd0 } };
static const GUID kIID_IDXGISwapChain2 =
    { 0xa8be2ac4, 0x199f, 0x4946, { 0xb3, 0x31, 0x79, 0x59, 0x9f, 0xb9, 0x8d, 0xe7 } };
static const GUID kIID_ID3D11Texture2D_Gl =
    { 0x6f15aaf2, 0xd208, 0x4e89, { 0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c } };

/* /NODEFAULTLIB support */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

/* ================================================================== */
/*  ANGLE / EGL entry points (resolved from libEGL.dll at runtime)     */
/* ================================================================== */

typedef void (APIENTRY *PFN_glViewport)(int, int, int, int);
typedef void (APIENTRY *PFN_glClearColor)(float, float, float, float);
typedef void (APIENTRY *PFN_glClear)(unsigned int);
typedef void (APIENTRY *PFN_glDisable)(unsigned int);
typedef void (APIENTRY *PFN_glFlush)(void);

#define NUCLEUS_GL_COLOR_BUFFER_BIT 0x00004000u
#define NUCLEUS_GL_SCISSOR_TEST     0x0C11u

static HMODULE sLibEGL    = NULL;
static HMODULE sLibGLESv2 = NULL;
static volatile BOOL eglLoaded    = FALSE;
static BOOL          eglAvailable = FALSE;

static PFNEGLGETPROCADDRESSPROC        pEglGetProcAddress        = NULL;
static PFNEGLGETPLATFORMDISPLAYEXTPROC pEglGetPlatformDisplayEXT = NULL;
static PFNEGLGETDISPLAYPROC            pEglGetDisplay            = NULL;
static PFNEGLINITIALIZEPROC            pEglInitialize            = NULL;
static PFNEGLBINDAPIPROC               pEglBindAPI               = NULL;
static PFNEGLCHOOSECONFIGPROC          pEglChooseConfig          = NULL;
static PFNEGLCREATEWINDOWSURFACEPROC   pEglCreateWindowSurface   = NULL;
static PFNEGLCREATECONTEXTPROC         pEglCreateContext         = NULL;
static PFNEGLMAKECURRENTPROC           pEglMakeCurrent           = NULL;
static PFNEGLSWAPBUFFERSPROC           pEglSwapBuffers           = NULL;
static PFNEGLSWAPINTERVALPROC          pEglSwapInterval          = NULL;
static PFNEGLDESTROYCONTEXTPROC        pEglDestroyContext        = NULL;
static PFNEGLDESTROYSURFACEPROC        pEglDestroySurface        = NULL;
static PFN_glViewport                  pglViewport               = NULL;
static PFN_glClearColor                pglClearColor             = NULL;
static PFN_glClear                     pglClear                  = NULL;
static PFN_glDisable                   pglDisable                = NULL;
static PFN_glFlush                     pglFlush                  = NULL;
static PFNEGLQUERYDISPLAYATTRIBEXTPROC pEglQueryDisplayAttribEXT = NULL;
static PFNEGLQUERYDEVICEATTRIBEXTPROC  pEglQueryDeviceAttribEXT  = NULL;
static PFNEGLCREATEPBUFFERFROMCLIENTBUFFERPROC pEglCreatePbufferFromClientBuffer = NULL;

static void loadEgl(void) {
    if (eglLoaded) return;
    eglLoaded = TRUE;

    /* The DLLs ship next to the other nucleus_tao_*.dll; the JVM side extracts
     * them and pre-loads libGLESv2/libEGL so they resolve by bare name here. */
    sLibEGL = LoadLibraryW(L"libEGL.dll");
    if (!sLibEGL) return;
    sLibGLESv2 = LoadLibraryW(L"libGLESv2.dll"); /* libEGL also pulls it in */

    pEglGetProcAddress      = (PFNEGLGETPROCADDRESSPROC)      GetProcAddress(sLibEGL, "eglGetProcAddress");
    pEglGetDisplay          = (PFNEGLGETDISPLAYPROC)          GetProcAddress(sLibEGL, "eglGetDisplay");
    pEglInitialize          = (PFNEGLINITIALIZEPROC)          GetProcAddress(sLibEGL, "eglInitialize");
    pEglBindAPI             = (PFNEGLBINDAPIPROC)             GetProcAddress(sLibEGL, "eglBindAPI");
    pEglChooseConfig        = (PFNEGLCHOOSECONFIGPROC)        GetProcAddress(sLibEGL, "eglChooseConfig");
    pEglCreateWindowSurface = (PFNEGLCREATEWINDOWSURFACEPROC) GetProcAddress(sLibEGL, "eglCreateWindowSurface");
    pEglCreateContext       = (PFNEGLCREATECONTEXTPROC)       GetProcAddress(sLibEGL, "eglCreateContext");
    pEglMakeCurrent         = (PFNEGLMAKECURRENTPROC)         GetProcAddress(sLibEGL, "eglMakeCurrent");
    pEglSwapBuffers         = (PFNEGLSWAPBUFFERSPROC)         GetProcAddress(sLibEGL, "eglSwapBuffers");
    pEglSwapInterval        = (PFNEGLSWAPINTERVALPROC)        GetProcAddress(sLibEGL, "eglSwapInterval");
    pEglDestroyContext      = (PFNEGLDESTROYCONTEXTPROC)      GetProcAddress(sLibEGL, "eglDestroyContext");
    pEglDestroySurface      = (PFNEGLDESTROYSURFACEPROC)      GetProcAddress(sLibEGL, "eglDestroySurface");

    pEglGetPlatformDisplayEXT = (PFNEGLGETPLATFORMDISPLAYEXTPROC)
        GetProcAddress(sLibEGL, "eglGetPlatformDisplayEXT");
    if (!pEglGetPlatformDisplayEXT && pEglGetProcAddress) {
        pEglGetPlatformDisplayEXT = (PFNEGLGETPLATFORMDISPLAYEXTPROC)
            pEglGetProcAddress("eglGetPlatformDisplayEXT");
    }

    if (sLibGLESv2) pglViewport = (PFN_glViewport) GetProcAddress(sLibGLESv2, "glViewport");
    if (!pglViewport && pEglGetProcAddress) pglViewport = (PFN_glViewport) pEglGetProcAddress("glViewport");
    if (sLibGLESv2) {
        pglClearColor = (PFN_glClearColor) GetProcAddress(sLibGLESv2, "glClearColor");
        pglClear      = (PFN_glClear)      GetProcAddress(sLibGLESv2, "glClear");
        pglDisable    = (PFN_glDisable)    GetProcAddress(sLibGLESv2, "glDisable");
    }
    if (pEglGetProcAddress) {
        if (!pglClearColor) pglClearColor = (PFN_glClearColor) pEglGetProcAddress("glClearColor");
        if (!pglClear)      pglClear      = (PFN_glClear)      pEglGetProcAddress("glClear");
        if (!pglDisable)    pglDisable    = (PFN_glDisable)    pEglGetProcAddress("glDisable");
        pglFlush = (PFN_glFlush) pEglGetProcAddress("glFlush");
        pEglQueryDisplayAttribEXT = (PFNEGLQUERYDISPLAYATTRIBEXTPROC)
            pEglGetProcAddress("eglQueryDisplayAttribEXT");
        pEglQueryDeviceAttribEXT = (PFNEGLQUERYDEVICEATTRIBEXTPROC)
            pEglGetProcAddress("eglQueryDeviceAttribEXT");
        pEglCreatePbufferFromClientBuffer = (PFNEGLCREATEPBUFFERFROMCLIENTBUFFERPROC)
            pEglGetProcAddress("eglCreatePbufferFromClientBuffer");
    }

    eglAvailable = (pEglInitialize && pEglChooseConfig && pEglCreateContext &&
                    pEglCreateWindowSurface && pEglMakeCurrent && pEglSwapBuffers &&
                    pEglDestroyContext && pEglDestroySurface);
}

/* Skia GrGLGetProc trampoline. The default DirectContext.makeGL() assembles
 * its GL interface via WGL (wglGetProcAddress + opengl32) and therefore fails
 * under an ANGLE context. Skia's DirectContext.makeGLWithInterface() instead
 * takes a GrGLAssembledInterface built from this getProc, which resolves names
 * through ANGLE's eglGetProcAddress (it returns core ES entry points too) —
 * the same mechanism Skiko uses for its ANGLE backend. The JVM passes the
 * address of this function to GLAssembledInterface.createFromNativePointers. */
typedef void (*NucleusGLFuncPtr)(void);
static NucleusGLFuncPtr nucleus_tao_egl_get_proc(void *ctx, const char *name) {
    (void)ctx;
    if (!pEglGetProcAddress) return NULL;
    return (NucleusGLFuncPtr) pEglGetProcAddress(name);
}

/* ================================================================== */
/*  Attachment record                                                  */
/* ================================================================== */

typedef struct {
    HWND  hwnd;        /* Tao top-level window (input, decoration) */
    HWND  surfaceHwnd; /* render-surface child */
    EGLDisplay eglDisplay;
    EGLSurface eglSurface; /* flip mode: the pbuffer over [texture] */
    EGLContext eglContext;
    EGLConfig  eglConfig;
    int   widthPx;
    int   heightPx;
    float scale;
    BOOL  alpha;       /* per-pixel alpha requested: ANGLE's blt surface */

    /* Flip-model presentation (see the file header); all NULL when the
     * attachment fell back to ANGLE's window surface. */
    ID3D11DeviceContext *imCtx;     /* ANGLE's immediate context */
    IDXGISwapChain1     *swapChain;
    ID3D11Texture2D     *texture;   /* what ANGLE renders into */
    HANDLE frameLatencyWaitable;
    int    chainWidthPx;
    int    chainHeightPx;
    UINT   syncInterval;
} GlAttachment;

/* ================================================================== */
/*  Render-surface child window                                        */
/* ================================================================== */

static const wchar_t *kSurfaceClassName = L"NucleusTaoGlSurface";
static volatile LONG sSurfaceClassRegistered = 0;

static LRESULT CALLBACK surfaceWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_NCHITTEST:
        /* Transparent to every hit test: mouse, touch (WM_POINTER) and
         * WindowFromPoint (OLE drag-and-drop) all resolve to the Tao
         * parent, whose deco subclass owns input handling. */
        return HTTRANSPARENT;
    case WM_ERASEBKGND:
        /* GL owns every pixel. The themed startup fill stays on the parent
         * (deco WM_ERASEBKGND); without WS_CLIPCHILDREN it covers this
         * child's area too until the first eglSwapBuffers. */
        return 1;
    case WM_PAINT:
        /* Painting happens via EGL outside the paint cycle; validate so
         * the update region doesn't refire WM_PAINT forever. */
        ValidateRect(hwnd, NULL);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

static void ensureSurfaceClassRegistered(void) {
    if (InterlockedCompareExchange(&sSurfaceClassRegistered, 1, 0) != 0) return;
    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    /* CS_OWNDC: GetDC(hwnd) returns a stable HDC for the window's
     * lifetime. EGL binds the surface to the HWND, but a stable DC keeps
     * DWM/driver interactions predictable for a long-lived GL child. */
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = surfaceWndProc;
    wc.hInstance = GetModuleHandleW(NULL);
    wc.lpszClassName = kSurfaceClassName;
    wc.hbrBackground = NULL;
    RegisterClassW(&wc);
}

/* Creates the render-surface child covering the parent's current client
 * area. Returns NULL on failure. */
static HWND createRenderSurface(HWND parent) {
    ensureSurfaceClassRegistered();

    RECT rc;
    if (!GetClientRect(parent, &rc)) { rc.right = 1; rc.bottom = 1; }
    int w = (int)(rc.right - rc.left); if (w < 1) w = 1;
    int h = (int)(rc.bottom - rc.top); if (h < 1) h = 1;

    HWND surface = CreateWindowExW(
        WS_EX_TRANSPARENT | WS_EX_NOPARENTNOTIFY,
        kSurfaceClassName, L"",
        WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
        0, 0, w, h,
        parent, NULL, GetModuleHandleW(NULL), NULL);
    if (!surface) return NULL;

    /* Bottom of the sibling z-order: NativeView children (WebView, …)
     * attached later must composite above the Compose canvas. */
    SetWindowPos(surface, HWND_BOTTOM, 0, 0, 0, 0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    return surface;
}

/* ================================================================== */
/*  Host EGL sharing                                                   */
/*                                                                     */
/*  Exported so the overlay+popup DLL (nucleus_tao_windows_native_view)*/
/*  can bind d3d-texture pbuffers against the host's display/context/  */
/*  config (see nucleus_tao_windows_overlay_dcomp.cpp). Resolved via   */
/*  GetProcAddress on this DLL.                                        */
/* ================================================================== */

/* Multi-host registry. Each TaoComposeSceneHostWindows attaches its own
 * EGLContext; the overlay/popup bridge (overlay_dcomp.cpp) borrows a host
 * context to bind its d3d-texture pbuffers. A single global trio is not
 * enough when more than one host coexists (a DecoratedDialog over a
 * DecoratedWindow): the dialog overwrites the global on attach and clears
 * it on detach, leaving the still-alive main window's popups with
 * EGL_NO_CONTEXT. Registered by HWND on nativeAttach, looked up by the
 * popup/overlay's parent host HWND (nucleus_tao_host_egl_for_hwnd).
 * Unbounded linked list (same pattern as overlay.c's sOwnerList) — a
 * fixed slot array would silently drop hosts past the cap.
 *
 * The headless bootstrap context (nativeEnsureHeadlessContext) lives in
 * its own statics, NOT the registry: it has no HWND, is never destroyed,
 * and must survive any window host's attach/detach cycle. Ownerless
 * (tray) panels resolve headless-first via the global accessors so they
 * never borrow a window context that a later nativeDetach destroys.
 *
 * All access runs on the single Tao event-loop thread; no lock needed. */
typedef struct HostEglEntry HostEglEntry;
struct HostEglEntry {
    HWND hwnd;
    EGLDisplay dpy;
    EGLContext ctx;
    EGLConfig  cfg;
    HostEglEntry *next;
};
static HostEglEntry *sHostEglList = NULL;

static EGLDisplay sHeadlessEglDisplay = EGL_NO_DISPLAY;
static EGLContext sHeadlessEglContext = EGL_NO_CONTEXT;
static EGLConfig  sHeadlessEglConfig  = NULL;
/* 1x1 pbuffer the headless context stays current on. Deliberately immortal,
 * like the context it backs: there is no headless teardown path (ownerless
 * panels rely on the trio outliving every window host), and destroying the
 * surface would leave the context bound to a dead draw target. Kept in a
 * static so the allocation is reachable and a future teardown can free it. */
static EGLSurface sHeadlessEglSurface = EGL_NO_SURFACE;

static void registerHostEgl(HWND hwnd, EGLDisplay dpy, EGLContext ctx, EGLConfig cfg) {
    if (!hwnd) {
        sHeadlessEglDisplay = dpy;
        sHeadlessEglContext = ctx;
        sHeadlessEglConfig = cfg;
        return;
    }
    for (HostEglEntry *e = sHostEglList; e; e = e->next) {
        if (e->hwnd == hwnd) {
            e->dpy = dpy; e->ctx = ctx; e->cfg = cfg;
            return;
        }
    }
    HostEglEntry *e = (HostEglEntry *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(HostEglEntry));
    if (!e) return;
    e->hwnd = hwnd;
    e->dpy = dpy; e->ctx = ctx; e->cfg = cfg;
    e->next = sHostEglList;
    sHostEglList = e;
}

static void unregisterHostEgl(HWND hwnd) {
    if (!hwnd) return;
    HostEglEntry **pp = &sHostEglList;
    while (*pp && (*pp)->hwnd != hwnd) pp = &(*pp)->next;
    if (*pp) {
        HostEglEntry *e = *pp;
        *pp = e->next;
        HeapFree(GetProcessHeap(), 0, e);
    }
}

/* Global fallback for ownerless (tray) panels: prefer the headless trio
 * — it is never destroyed, so a panel that binds it can't be orphaned by
 * a window host's nativeDetach. Fall back to any live window host. */
static void resolveFallbackEgl(EGLDisplay *dpy, EGLContext *ctx, EGLConfig *cfg) {
    if (sHeadlessEglContext != EGL_NO_CONTEXT) {
        *dpy = sHeadlessEglDisplay;
        *ctx = sHeadlessEglContext;
        *cfg = sHeadlessEglConfig;
        return;
    }
    if (sHostEglList) {
        *dpy = sHostEglList->dpy;
        *ctx = sHostEglList->ctx;
        *cfg = sHostEglList->cfg;
        return;
    }
    *dpy = EGL_NO_DISPLAY;
    *ctx = EGL_NO_CONTEXT;
    *cfg = NULL;
}

/* Historical values: 0 = WGL (removed), 1 = EGL/ANGLE. Kept exported so
 * the overlay DLL's backend probe stays a stable cross-DLL contract. */
__declspec(dllexport) int nucleus_tao_host_backend(void) {
    return 1;
}

__declspec(dllexport) void *nucleus_tao_host_egl_display(void) {
    EGLDisplay dpy; EGLContext ctx; EGLConfig cfg;
    resolveFallbackEgl(&dpy, &ctx, &cfg);
    return (void *)dpy;
}

__declspec(dllexport) void *nucleus_tao_host_egl_context(void) {
    EGLDisplay dpy; EGLContext ctx; EGLConfig cfg;
    resolveFallbackEgl(&dpy, &ctx, &cfg);
    return (void *)ctx;
}

__declspec(dllexport) void *nucleus_tao_host_egl_config(void) {
    EGLDisplay dpy; EGLContext ctx; EGLConfig cfg;
    resolveFallbackEgl(&dpy, &ctx, &cfg);
    return (void *)cfg;
}

/* Resolves the EGL trio registered for [hwnd] (the popup/overlay's parent
 * host window). Out-params are only written on a hit (return 1). Returns 0
 * when [hwnd] is NULL or not registered — callers fall back to the global
 * accessors (ownerless panel / headless context). */
__declspec(dllexport) int nucleus_tao_host_egl_for_hwnd(
    void *hwndVoid, void **dpyOut, void **ctxOut, void **cfgOut) {
    HWND hwnd = (HWND)hwndVoid;
    if (!hwnd) return 0;
    for (HostEglEntry *e = sHostEglList; e; e = e->next) {
        if (e->hwnd == hwnd) {
            if (dpyOut) *dpyOut = (void *)e->dpy;
            if (ctxOut) *ctxOut = (void *)e->ctx;
            if (cfgOut) *cfgOut = (void *)e->cfg;
            return 1;
        }
    }
    return 0;
}

/* Resolves an EGL entry point through the already-loaded libEGL.dll.
 * The overlay+popup DLL (overlay_dcomp.cpp) uses this instead of
 * loading/looking up libEGL itself, so all EGL resolution stays in one
 * place and works even where GetModuleHandleW("libEGL.dll") wouldn't
 * (name-mangled extraction). Returns NULL when ANGLE isn't loaded. */
__declspec(dllexport) void *nucleus_tao_host_egl_proc(const char *name) {
    if (sLibEGL) {
        void *p = (void *)GetProcAddress(sLibEGL, name);
        if (p) return p;
    }
    if (pEglGetProcAddress) return (void *)pEglGetProcAddress(name);
    return NULL;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

/* ================================================================== */
/*  EGL / ANGLE attach                                                 */
/* ================================================================== */

/* Asks ANGLE for a Direct3D-11 display of the given device type
 * (hardware adapter, or WARP software rasteriser). */
static EGLDisplay angleD3D11Display(EGLint deviceType) {
    const EGLint attribs[] = {
        EGL_PLATFORM_ANGLE_TYPE_ANGLE,        EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE, deviceType,
        EGL_NONE
    };
    return pEglGetPlatformDisplayEXT(EGL_PLATFORM_ANGLE_ANGLE, EGL_DEFAULT_DISPLAY, attribs);
}

/* ================================================================== */
/*  Flip-model presentation                                            */
/* ================================================================== */

#define FLIP_CHAIN_FORMAT DXGI_FORMAT_B8G8R8A8_UNORM
#define FLIP_CHAIN_FLAGS  DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT
/* Upper bound on the paced wait for a queued frame to leave the queue: an
 * occluded or minimised window may never retire it. */
#define FLIP_PACE_TIMEOUT_MS 50

/* Destroys the pbuffer and the texture behind it. The pbuffer is the
 * context's draw surface, so it is unbound first. */
static void releaseFlipTarget(GlAttachment *att) {
    if (att->eglSurface != EGL_NO_SURFACE) {
        pEglMakeCurrent(att->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        pEglDestroySurface(att->eglDisplay, att->eglSurface);
        att->eglSurface = EGL_NO_SURFACE;
    }
    if (att->texture) {
        ID3D11Texture2D_Release(att->texture);
        att->texture = NULL;
    }
}

/* Creates the [w]x[h] texture ANGLE renders into and the pbuffer over it,
 * and binds it. */
static BOOL createFlipTarget(GlAttachment *att, ID3D11Device *device, int w, int h) {
    D3D11_TEXTURE2D_DESC desc;
    memset(&desc, 0, sizeof(desc));
    desc.Width = (UINT)w;
    desc.Height = (UINT)h;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = FLIP_CHAIN_FORMAT;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    ID3D11Texture2D *tex = NULL;
    if (FAILED(ID3D11Device_CreateTexture2D(device, &desc, NULL, &tex))) return FALSE;

    const EGLint pbAttribs[] = {
        EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
        EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
        EGL_NONE
    };
    EGLSurface pb = pEglCreatePbufferFromClientBuffer(
        att->eglDisplay, EGL_D3D_TEXTURE_ANGLE, (EGLClientBuffer)tex, att->eglConfig, pbAttribs);
    if (pb == EGL_NO_SURFACE) {
        ID3D11Texture2D_Release(tex);
        return FALSE;
    }
    att->texture = tex;
    att->eglSurface = pb;
    return pEglMakeCurrent(att->eglDisplay, pb, pb, att->eglContext) ? TRUE : FALSE;
}

/* ANGLE's own D3D11 device: the pbuffer extension only accepts its
 * textures, and the copy into the back buffer must stay on its queue.
 * Borrowed — not AddRef'd. */
static ID3D11Device *angleDevice(EGLDisplay dpy) {
    if (!pEglQueryDisplayAttribEXT || !pEglQueryDeviceAttribEXT) return NULL;
    EGLAttrib devAttr = 0;
    if (!pEglQueryDisplayAttribEXT(dpy, EGL_DEVICE_EXT, &devAttr)) return NULL;
    EGLAttrib d3dAttr = 0;
    if (!pEglQueryDeviceAttribEXT((EGLDeviceEXT)devAttr, EGL_D3D11_DEVICE_ANGLE, &d3dAttr)) return NULL;
    return (ID3D11Device *)d3dAttr;
}

static void destroyFlipChain(GlAttachment *att) {
    releaseFlipTarget(att);
    if (att->frameLatencyWaitable) {
        CloseHandle(att->frameLatencyWaitable);
        att->frameLatencyWaitable = NULL;
    }
    if (att->swapChain) {
        IDXGISwapChain1_Release(att->swapChain);
        att->swapChain = NULL;
    }
    if (att->imCtx) {
        ID3D11DeviceContext_Release(att->imCtx);
        att->imCtx = NULL;
    }
}

/* Sets up flip-model presentation on the render surface (see the file
 * header) and leaves its pbuffer current. On failure everything is torn
 * down and FALSE returned; the caller falls back to ANGLE's window surface. */
static BOOL createFlipChain(GlAttachment *att) {
    if (!pEglCreatePbufferFromClientBuffer || !pglFlush) return FALSE;
    ID3D11Device *device = angleDevice(att->eglDisplay);
    if (!device) return FALSE;

    RECT rc;
    if (!GetClientRect(att->surfaceHwnd, &rc)) { rc.right = 1; rc.bottom = 1; }
    int w = (int)(rc.right - rc.left); if (w < 1) w = 1;
    int h = (int)(rc.bottom - rc.top); if (h < 1) h = 1;

    IDXGIDevice *dxgiDevice = NULL;
    IDXGIAdapter *adapter = NULL;
    IDXGIFactory2 *factory = NULL;
    IDXGISwapChain2 *chain2 = NULL;
    BOOL ok = FALSE;
    do {
        if (FAILED(ID3D11Device_QueryInterface(device, &kIID_IDXGIDevice, (void **)&dxgiDevice))) break;
        if (FAILED(IDXGIDevice_GetAdapter(dxgiDevice, &adapter))) break;
        if (FAILED(IDXGIAdapter_GetParent(adapter, &kIID_IDXGIFactory2, (void **)&factory))) break;

        DXGI_SWAP_CHAIN_DESC1 desc;
        memset(&desc, 0, sizeof(desc));
        desc.Width = (UINT)w;
        desc.Height = (UINT)h;
        desc.Format = FLIP_CHAIN_FORMAT;
        desc.SampleDesc.Count = 1;
        desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        desc.BufferCount = 2;
        /* Anchored top-left, never stretched: while the window outgrows the
         * last frame, the frame stays put instead of scaling. */
        desc.Scaling = DXGI_SCALING_NONE;
        desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
        desc.AlphaMode = DXGI_ALPHA_MODE_UNSPECIFIED;
        desc.Flags = FLIP_CHAIN_FLAGS;
        if (FAILED(IDXGIFactory2_CreateSwapChainForHwnd(
                factory, (IUnknown *)device, att->surfaceHwnd, &desc, NULL, NULL, &att->swapChain))) {
            /* FLIP_DISCARD is Windows 10+; FLIP_SEQUENTIAL is Windows 8. */
            desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
            if (FAILED(IDXGIFactory2_CreateSwapChainForHwnd(
                    factory, (IUnknown *)device, att->surfaceHwnd, &desc, NULL, NULL, &att->swapChain))) {
                att->swapChain = NULL;
                break;
            }
        }
        /* Alt+Enter is the app's to bind, not DXGI's exclusive fullscreen. */
        IDXGIFactory2_MakeWindowAssociation(factory, att->surfaceHwnd, DXGI_MWA_NO_ALT_ENTER);

        /* One frame in flight, waited on per swapchain — the pacing ANGLE's
         * blt present gave (eglSwapBuffers at interval 1 returning a refresh
         * apart), without IDXGIDevice1::SetMaximumFrameLatency, which every
         * window on ANGLE's single device would share. */
        if (FAILED(IDXGISwapChain1_QueryInterface(att->swapChain, &kIID_IDXGISwapChain2, (void **)&chain2))) break;
        if (FAILED(IDXGISwapChain2_SetMaximumFrameLatency(chain2, 1))) break;
        att->frameLatencyWaitable = IDXGISwapChain2_GetFrameLatencyWaitableObject(chain2);
        if (!att->frameLatencyWaitable) break;

        ID3D11Device_GetImmediateContext(device, &att->imCtx);
        att->chainWidthPx = w;
        att->chainHeightPx = h;
        att->syncInterval = 1;
        if (!createFlipTarget(att, device, w, h)) break;
        ok = TRUE;
    } while (0);

    if (chain2) IDXGISwapChain2_Release(chain2);
    if (factory) IDXGIFactory2_Release(factory);
    if (adapter) IDXGIAdapter_Release(adapter);
    if (dxgiDevice) IDXGIDevice_Release(dxgiDevice);
    if (!ok) destroyFlipChain(att);
    return ok;
}

/* Brings the swapchain and the render texture to [w]x[h] and leaves the new
 * pbuffer current. The texture's content does not survive — the frame that
 * follows a resize repaints it. */
static void resizeFlipChain(GlAttachment *att, int w, int h) {
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    if (w == att->chainWidthPx && h == att->chainHeightPx && att->texture) return;
    ID3D11Device *device = angleDevice(att->eglDisplay);
    if (!device) return;
    releaseFlipTarget(att);
    /* ResizeBuffers wants no outstanding back-buffer reference: presents
     * release theirs at once, and nothing else holds one. */
    IDXGISwapChain1_ResizeBuffers(att->swapChain, 0, (UINT)w, (UINT)h, DXGI_FORMAT_UNKNOWN, FLIP_CHAIN_FLAGS);
    att->chainWidthPx = w;
    att->chainHeightPx = h;
    /* On failure the surface stays EGL_NO_SURFACE: frames are skipped until
     * the next resize tries again. */
    createFlipTarget(att, device, w, h);
}

/* Copies the rendered texture into the back buffer and presents it. */
static void presentFlipChain(GlAttachment *att) {
    if (!att->texture) return;
    /* Everything ANGLE recorded must be on the immediate context before the
     * copy joins the same command stream. */
    pglFlush();
    ID3D11Texture2D *backBuffer = NULL;
    if (FAILED(IDXGISwapChain1_GetBuffer(att->swapChain, 0, &kIID_ID3D11Texture2D_Gl, (void **)&backBuffer))) {
        return;
    }
    ID3D11DeviceContext_CopyResource(att->imCtx, (ID3D11Resource *)backBuffer, (ID3D11Resource *)att->texture);
    ID3D11Texture2D_Release(backBuffer);
    IDXGISwapChain1_Present(att->swapChain, att->syncInterval, 0);
    /* Paced: return once the frame has left the queue, i.e. on the refresh
     * that shows it. Unpaced (modal resize, drags): return at once. */
    if (att->syncInterval != 0) {
        WaitForSingleObjectEx(att->frameLatencyWaitable, FLIP_PACE_TIMEOUT_MS, FALSE);
    }
}

/* ================================================================== */
/*  Present path                                                       */
/* ================================================================== */

/* Creates the render-surface child and the swapchain behind it, and leaves
 * its surface current: the flip chain, unless [alpha] — a flip-model HWND
 * swapchain cannot carry per-pixel alpha (DXGI only accepts
 * ALPHA_MODE_PREMULTIPLIED on composition swapchains, and DWM composes an
 * HWND flip chain opaque), while ANGLE's blt surface writes its alpha into
 * the redirection surface, which is how transparent windows and Mica /
 * Acrylic backdrops show through. That blt path keeps the off-screen gap
 * described in the file header; it is also the fallback when the flip chain
 * cannot be created. */
/* The flip chain is opt-in (NUCLEUS_TAO_FLIP_PRESENT=1) until it keeps the
 * window's geometry and its frames in step: composed apart from the
 * redirection surface, it let DWM show a resized window around a frame of
 * the previous size (trembling while resizing, unpainted white bands on a
 * window that grows before its next frame). */
static BOOL flipPresentEnabled(void) {
    static int enabled = -1;
    if (enabled < 0) {
        char value[8];
        DWORD n = GetEnvironmentVariableA("NUCLEUS_TAO_FLIP_PRESENT", value, sizeof(value));
        enabled = (n == 1 && value[0] == '1') ? 1 : 0;
    }
    return enabled ? TRUE : FALSE;
}

static BOOL createPresentPath(GlAttachment *att, BOOL alpha) {
    HWND surfaceHwnd = createRenderSurface(att->hwnd);
    if (!surfaceHwnd) return FALSE;
    if (att->widthPx > 0 && att->heightPx > 0) {
        SetWindowPos(surfaceHwnd, NULL, 0, 0, att->widthPx, att->heightPx,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_DEFERERASE);
    }
    att->surfaceHwnd = surfaceHwnd;
    att->alpha = alpha;
    if (!alpha && flipPresentEnabled() && createFlipChain(att)) return TRUE;

    const EGLint surfAttribs[] = { EGL_NONE };
    EGLSurface surface = pEglCreateWindowSurface(
        att->eglDisplay, att->eglConfig, (EGLNativeWindowType)surfaceHwnd, surfAttribs);
    if (surface == EGL_NO_SURFACE ||
        !pEglMakeCurrent(att->eglDisplay, surface, surface, att->eglContext)) {
        if (surface != EGL_NO_SURFACE) pEglDestroySurface(att->eglDisplay, surface);
        DestroyWindow(surfaceHwnd);
        att->surfaceHwnd = NULL;
        return FALSE;
    }
    att->eglSurface = surface;
    /* VSync ON: eglSwapBuffers paces the frame loop off the display
     * refresh, inline on the event-loop thread (no swap thread — see the
     * file header). */
    if (pEglSwapInterval) pEglSwapInterval(att->eglDisplay, 1);
    return TRUE;
}

/* Undoes createPresentPath; the context survives, bound to no surface. */
static void destroyPresentPath(GlAttachment *att) {
    pEglMakeCurrent(att->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (att->swapChain) {
        destroyFlipChain(att);
    } else if (att->eglSurface != EGL_NO_SURFACE) {
        pEglDestroySurface(att->eglDisplay, att->eglSurface);
        att->eglSurface = EGL_NO_SURFACE;
    }
    if (att->surfaceHwnd && IsWindow(att->surfaceHwnd)) DestroyWindow(att->surfaceHwnd);
    att->surfaceHwnd = NULL;
}

static GlAttachment *attachEgl(HWND hwnd, BOOL alpha) {
    loadEgl();
    if (!eglAvailable || !pEglGetPlatformDisplayEXT) return NULL;

    /* Try a hardware D3D11 adapter first; fall back to WARP (the software
     * D3D11 rasteriser available on RDP / VMs / driverless boxes). */
    const EGLint deviceTypes[] = {
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE,
    };
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLint major = 0, minor = 0;
    for (int i = 0; i < 2; ++i) {
        EGLDisplay d = angleD3D11Display(deviceTypes[i]);
        if (d != EGL_NO_DISPLAY && pEglInitialize(d, &major, &minor)) { dpy = d; break; }
    }
    if (dpy == EGL_NO_DISPLAY) return NULL;

    if (pEglBindAPI) pEglBindAPI(EGL_OPENGL_ES_API);

    /* Alpha 8 + stencil 8. Depth unused (Skia owns it).
     * EGL_PBUFFER_BIT: the DComp overlay path (overlay_dcomp.cpp) reuses
     * this exact config for its d3d-texture pbuffers — eglMakeCurrent
     * requires surface/context config compatibility. */
    const EGLint cfgAttribs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,    8,
        EGL_GREEN_SIZE,  8,
        EGL_BLUE_SIZE,   8,
        EGL_ALPHA_SIZE,  8,
        EGL_DEPTH_SIZE,  0,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = NULL;
    EGLint numConfig = 0;
    if (!pEglChooseConfig(dpy, cfgAttribs, &config, 1, &numConfig) || numConfig == 0) {
        return NULL;
    }

    /* Request an ES 3 context (Skia prefers it); fall back to ES 2. */
    const EGLint ctxAttribs3[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE };
    const EGLint ctxAttribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs3);
    if (ctx == EGL_NO_CONTEXT) {
        ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs2);
    }
    if (ctx == EGL_NO_CONTEXT) return NULL;

    GlAttachment *att = (GlAttachment *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(GlAttachment));
    if (!att) {
        pEglDestroyContext(dpy, ctx);
        return NULL;
    }
    att->hwnd = hwnd;
    att->eglDisplay = dpy;
    att->eglSurface = EGL_NO_SURFACE;
    att->eglContext = ctx;
    att->eglConfig = config;
    att->scale = 1.0f;

    if (!createPresentPath(att, alpha)) {
        pEglDestroyContext(dpy, ctx);
        HeapFree(GetProcessHeap(), 0, att);
        return NULL;
    }

    registerHostEgl(hwnd, dpy, ctx, config);
    return att;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: dev.nucleusframework.window.tao                           */
/*  Class:   NativeTaoGlBridge                                         */
/* ================================================================== */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong hwndLong, jboolean alpha)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return 0;
    return (jlong)(uintptr_t)attachEgl(hwnd, alpha ? TRUE : FALSE);
}

/* Switches the attachment between the flip chain and ANGLE's per-pixel-alpha
 * blt surface (see createPresentPath) — a Mica / Acrylic backdrop is armed
 * and disarmed at run time. Neither swapchain can follow the other on the
 * same HWND (DXGI keeps a window that has had a flip-model swapchain from
 * taking a blt one), so the render-surface child is recreated with it. The
 * new surface is current and holds no frame yet: the caller renders one at
 * once. Returns false when no present path could be rebuilt. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeSetAlpha(
    JNIEnv *env, jclass clazz, jlong handle, jboolean alphaJ)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return JNI_FALSE;
    BOOL alpha = alphaJ ? TRUE : FALSE;
    if (att->alpha == alpha && att->surfaceHwnd) return JNI_TRUE;
    /* Without the flip chain both modes are the same surface. */
    if (!flipPresentEnabled()) {
        att->alpha = alpha;
        return JNI_TRUE;
    }
    destroyPresentPath(att);
    if (createPresentPath(att, alpha)) return JNI_TRUE;
    /* The other path beats none at all. */
    return createPresentPath(att, !alpha) ? JNI_TRUE : JNI_FALSE;
}

/* Address of the GrGLGetProc trampoline for ANGLE (see nucleus_tao_egl_get_proc).
 * Passed to GLAssembledInterface.createFromNativePointers as the fPtr. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeEglGetProcFn(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong)(uintptr_t)&nucleus_tao_egl_get_proc;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;

    HWND hostHwnd = att->hwnd;
    /* The EGLDisplay is process-wide (shared with overlays); never
     * eglTerminate it here — just drop this window's context + surface. */
    destroyPresentPath(att);
    pEglDestroyContext(att->eglDisplay, att->eglContext);
    HeapFree(GetProcessHeap(), 0, att);
    /* Unregister after freeing att: recompute the global trio to a
     * still-alive host so sibling hosts' popups/overlays keep a live
     * context (a DecoratedDialog detaching must not wipe the main
     * window's). */
    unregisterHostEgl(hostHwnd);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    att->widthPx = (int)widthPx;
    att->heightPx = (int)heightPx;
    att->scale = scale;
    /* Keep the render-surface child glued to the client area. Runs in the
     * same event-dispatch turn as the parent's WM_SIZE (before the next
     * render), so the surface never lags the window visually. */
    if (att->surfaceHwnd && IsWindow(att->surfaceHwnd)) {
        SetWindowPos(att->surfaceHwnd, NULL, 0, 0, att->widthPx, att->heightPx,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_DEFERERASE);
    }
    /* The ANGLE window surface tracks the HWND size automatically; the flip
     * chain and its texture are resized here. The explicit viewport keeps
     * Skia surface creation in step. */
    if (att->swapChain) resizeFlipChain(att, att->widthPx, att->heightPx);
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
    if (pglViewport) pglViewport(0, 0, att->widthPx, att->heightPx);
}

/* Presents one frame cleared to [argb]. Used by the fullscreen toggle right
 * after the child render surface is resized: DWM registers the HWND resize
 * immediately but the swapchain's first buffer at the new size only reaches
 * it at the next present — a composition falling into that gap shows the
 * uninitialized buffer (black). This sub-millisecond clear+present shrinks
 * the gap to ~1ms and colours it with the themed background. The caller
 * must resync Skia's GL state cache (resetGLAll) afterwards. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeClearPresent(
    JNIEnv *env, jclass clazz, jlong handle, jint argb)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att || !pglClearColor || !pglClear) return;
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
    /* Skia can leave scissoring enabled; glClear honours it. */
    if (pglDisable) pglDisable(NUCLEUS_GL_SCISSOR_TEST);
    float a = (float)((argb >> 24) & 0xFF) / 255.0f;
    float r = (float)((argb >> 16) & 0xFF) / 255.0f;
    float g = (float)((argb >>  8) & 0xFF) / 255.0f;
    float b = (float)( argb        & 0xFF) / 255.0f;
    pglClearColor(r, g, b, a);
    pglClear(NUCLEUS_GL_COLOR_BUFFER_BIT);
    if (att->swapChain) {
        presentFlipChain(att);
    } else {
        pEglSwapBuffers(att->eglDisplay, att->eglSurface);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    /* Defensive re-make-current: an overlay/popup renderer may have left
     * its d3d-texture pbuffer bound on this thread (single shared
     * EGLContext, see overlay_dcomp.cpp). eglSwapBuffers requires the
     * surface to be current; re-binding when already current is an ANGLE
     * fast-path no-op. */
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
    if (att->swapChain) {
        presentFlipChain(att);
    } else {
        pEglSwapBuffers(att->eglDisplay, att->eglSurface);
    }
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeSetVSyncEnabled(
    JNIEnv *env, jclass clazz, jlong handle, jboolean enabled)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    if (att->swapChain) {
        att->syncInterval = enabled ? 1 : 0;
        return;
    }
    if (!pEglSwapInterval) return;
    /* eglSwapInterval applies to the draw surface of the current context, so
     * make our window surface current first (an overlay/popup renderer may have
     * left its pbuffer bound on this thread). interval 1 = pace on the display
     * refresh (default — keeps animations aligned to VBlank); interval 0 =
     * present immediately, set for the duration of the OS modal resize/move
     * loop so border-drag frames don't block on VBlank. */
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
    pEglSwapInterval(att->eglDisplay, enabled ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->heightPx : 0;
}

/* Whether the draw surface is the flip chain's texture, whose rows are
 * top-down for D3D: the JVM then renders with a TOP_LEFT origin (see the
 * file header). False on ANGLE's window surface, which flips on present. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeIsTopDown(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return (att && att->swapChain) ? JNI_TRUE : JNI_FALSE;
}

/* Headless bootstrap: creates the shared ANGLE display/config/context bound
 * to a 1x1 pbuffer when no window host has attached yet. Lets standalone
 * popup surfaces (overlay_dcomp) run in apps without any Tao window — e.g.
 * a tray-only app whose UI lives entirely in a transparent popup panel.
 * NOT thread-safe (check-then-init on the shared EGL statics): call only
 * from the Tao main thread, like every entry point touching the process
 * EGL context. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoGlBridge_nativeEnsureHeadlessContext(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    /* Only the HEADLESS context short-circuits. A live window host is not
     * enough: its context dies with its nativeDetach, and an ownerless
     * panel that borrowed it would be left bound to a destroyed context.
     * The headless context is never destroyed, so ownerless panels always
     * get a stable trio (resolveFallbackEgl prefers it). */
    if (sHeadlessEglContext != EGL_NO_CONTEXT) return JNI_TRUE;
    loadEgl();
    if (!eglAvailable || !pEglGetPlatformDisplayEXT) return JNI_FALSE;

    const EGLint deviceTypes[] = {
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_HARDWARE_ANGLE,
        EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE,
    };
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLint major = 0, minor = 0;
    int i;
    for (i = 0; i < 2; ++i) {
        EGLDisplay d = angleD3D11Display(deviceTypes[i]);
        if (d != EGL_NO_DISPLAY && pEglInitialize(d, &major, &minor)) { dpy = d; break; }
    }
    if (dpy == EGL_NO_DISPLAY) return JNI_FALSE;
    if (pEglBindAPI) pEglBindAPI(EGL_OPENGL_ES_API);

    /* Same config attribs as attachEgl so overlay/popup pbuffers stay
     * config-compatible with this context. */
    const EGLint cfgAttribs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,    8,
        EGL_GREEN_SIZE,  8,
        EGL_BLUE_SIZE,   8,
        EGL_ALPHA_SIZE,  8,
        EGL_DEPTH_SIZE,  0,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = NULL;
    EGLint numConfig = 0;
    if (!pEglChooseConfig(dpy, cfgAttribs, &config, 1, &numConfig) || numConfig == 0) {
        return JNI_FALSE;
    }

    PFNEGLCREATEPBUFFERSURFACEPROC pCreatePbuffer =
        (PFNEGLCREATEPBUFFERSURFACEPROC)GetProcAddress(sLibEGL, "eglCreatePbufferSurface");
    if (!pCreatePbuffer) return JNI_FALSE;
    const EGLint pbAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = pCreatePbuffer(dpy, config, pbAttribs);
    if (surface == EGL_NO_SURFACE) return JNI_FALSE;

    const EGLint ctxAttribs3[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE };
    const EGLint ctxAttribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs3);
    if (ctx == EGL_NO_CONTEXT) {
        ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs2);
    }
    if (ctx == EGL_NO_CONTEXT) {
        pEglDestroySurface(dpy, surface);
        return JNI_FALSE;
    }
    if (!pEglMakeCurrent(dpy, surface, surface, ctx)) {
        pEglDestroyContext(dpy, ctx);
        pEglDestroySurface(dpy, surface);
        return JNI_FALSE;
    }
    sHeadlessEglSurface = surface;
    /* No HWND (headless) — primes the global fallback only, not the
     * per-HWND registry. registerHostEgl(NULL,...) handles that branch. */
    registerHostEgl(NULL, dpy, ctx, config);
    return JNI_TRUE;
}
