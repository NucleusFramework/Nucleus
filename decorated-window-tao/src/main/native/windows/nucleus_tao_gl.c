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
 *   nativePresent(h)       → eglSwapBuffers
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
    EGLSurface eglSurface;
    EGLContext eglContext;
    EGLConfig  eglConfig;
    int   widthPx;
    int   heightPx;
    float scale;
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

static GlAttachment *attachEgl(HWND hwnd) {
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

    HWND surfaceHwnd = createRenderSurface(hwnd);
    if (!surfaceHwnd) return NULL;

    const EGLint surfAttribs[] = { EGL_NONE };
    EGLSurface surface = pEglCreateWindowSurface(
        dpy, config, (EGLNativeWindowType)surfaceHwnd, surfAttribs);
    if (surface == EGL_NO_SURFACE) {
        DestroyWindow(surfaceHwnd);
        return NULL;
    }

    /* Request an ES 3 context (Skia prefers it); fall back to ES 2. */
    const EGLint ctxAttribs3[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE };
    const EGLint ctxAttribs2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs3);
    if (ctx == EGL_NO_CONTEXT) {
        ctx = pEglCreateContext(dpy, config, EGL_NO_CONTEXT, ctxAttribs2);
    }
    if (ctx == EGL_NO_CONTEXT) {
        pEglDestroySurface(dpy, surface);
        DestroyWindow(surfaceHwnd);
        return NULL;
    }

    if (!pEglMakeCurrent(dpy, surface, surface, ctx)) {
        pEglDestroyContext(dpy, ctx);
        pEglDestroySurface(dpy, surface);
        DestroyWindow(surfaceHwnd);
        return NULL;
    }
    /* VSync ON: eglSwapBuffers paces the frame loop off the display
     * refresh, inline on the event-loop thread (no swap thread — see the
     * file header). */
    if (pEglSwapInterval) pEglSwapInterval(dpy, 1);

    GlAttachment *att = (GlAttachment *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(GlAttachment));
    if (!att) {
        pEglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        pEglDestroyContext(dpy, ctx);
        pEglDestroySurface(dpy, surface);
        DestroyWindow(surfaceHwnd);
        return NULL;
    }
    att->hwnd = hwnd;
    att->surfaceHwnd = surfaceHwnd;
    att->eglDisplay = dpy;
    att->eglSurface = surface;
    att->eglContext = ctx;
    att->eglConfig = config;
    att->scale = 1.0f;

    registerHostEgl(hwnd, dpy, ctx, config);
    return att;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: dev.nucleusframework.window.tao                           */
/*  Class:   NativeTaoGlBridge                                         */
/* ================================================================== */

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return 0;
    return (jlong)(uintptr_t)attachEgl(hwnd);
}

/* Address of the GrGLGetProc trampoline for ANGLE (see nucleus_tao_egl_get_proc).
 * Passed to GLAssembledInterface.createFromNativePointers as the fPtr. */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeEglGetProcFn(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong)(uintptr_t)&nucleus_tao_egl_get_proc;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;

    HWND hostHwnd = att->hwnd;
    /* The EGLDisplay is process-wide (shared with overlays); never
     * eglTerminate it here — just drop this window's context + surface. */
    pEglMakeCurrent(att->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    pEglDestroyContext(att->eglDisplay, att->eglContext);
    pEglDestroySurface(att->eglDisplay, att->eglSurface);
    if (IsWindow(att->surfaceHwnd)) DestroyWindow(att->surfaceHwnd);
    HeapFree(GetProcessHeap(), 0, att);
    /* Unregister after freeing att: recompute the global trio to a
     * still-alive host so sibling hosts' popups/overlays keep a live
     * context (a DecoratedDialog detaching must not wipe the main
     * window's). */
    unregisterHostEgl(hostHwnd);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att) return;
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeResize(
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
    if (att->surfaceHwnd != att->hwnd && IsWindow(att->surfaceHwnd)) {
        SetWindowPos(att->surfaceHwnd, NULL, 0, 0, att->widthPx, att->heightPx,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_DEFERERASE);
    }
    /* The ANGLE window surface tracks the HWND size automatically; the
     * explicit viewport keeps Skia surface creation in step. */
    pEglMakeCurrent(att->eglDisplay, att->eglSurface, att->eglSurface, att->eglContext);
    if (pglViewport) pglViewport(0, 0, att->widthPx, att->heightPx);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativePresent(
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
    pEglSwapBuffers(att->eglDisplay, att->eglSurface);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeSetVSyncEnabled(
    JNIEnv *env, jclass clazz, jlong handle, jboolean enabled)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    if (!att || !pEglSwapInterval) return;
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
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    GlAttachment *att = (GlAttachment *)(uintptr_t)handle;
    return att ? (jint)att->heightPx : 0;
}

/* Headless bootstrap: creates the shared ANGLE display/config/context bound
 * to a 1x1 pbuffer when no window host has attached yet. Lets standalone
 * popup surfaces (overlay_dcomp) run in apps without any Tao window — e.g.
 * a tray-only app whose UI lives entirely in a transparent popup panel.
 * NOT thread-safe (check-then-init on the shared EGL statics): call only
 * from the Tao main thread, like every entry point touching the process
 * EGL context. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_NativeTaoGlBridge_nativeEnsureHeadlessContext(
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
    /* No HWND (headless) — primes the global fallback only, not the
     * per-HWND registry. registerHostEgl(NULL,...) handles that branch. */
    registerHostEgl(NULL, dpy, ctx, config);
    return JNI_TRUE;
}
