/**
 * JNI bridge: WGL renderer for the Tao backend on Windows.
 *
 * Creates an OpenGL 3.3 compatibility context on the HWND so Skia's
 * `DirectContext.makeGL()` can render directly into the window's default
 * framebuffer. Modeled after the Metal helper on macOS.
 *
 * Sequence used by the JVM side:
 *   nativeAttach(hwnd)     → creates HDC + HGLRC, makes current
 *   nativeMakeCurrent(h)   → restores current context (defensive)
 *   nativeResize(h,w,h,s)  → stores dimensions for the next BackendRenderTarget
 *   <Skia rendering>
 *   nativePresent(h)       → SwapBuffers
 *   nativeDetach(h)        → tear-down
 *
 * Linked libraries: opengl32.lib gdi32.lib user32.lib kernel32.lib
 */

#include <jni.h>
#include <windows.h>
#include <GL/gl.h>

/* /NODEFAULTLIB support */
int _fltused = 0;

#pragma function(memset)
void *memset(void *dest, int c, size_t count) {
    unsigned char *p = (unsigned char *)dest;
    while (count--) *p++ = (unsigned char)c;
    return dest;
}

/* WGL extension function pointers (loaded via a temporary dummy context) */
typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);
typedef BOOL  (WINAPI *PFNWGLCHOOSEPIXELFORMATARBPROC)(HDC, const int *, const FLOAT *, UINT, int *, UINT *);
typedef BOOL  (WINAPI *PFNWGLSWAPINTERVALEXTPROC)(int);

static PFNWGLCREATECONTEXTATTRIBSARBPROC pwglCreateContextAttribsARB = NULL;
static PFNWGLCHOOSEPIXELFORMATARBPROC    pwglChoosePixelFormatARB    = NULL;
static PFNWGLSWAPINTERVALEXTPROC         pwglSwapIntervalEXT         = NULL;
static volatile BOOL extensionsLoaded = FALSE;

#define WGL_DRAW_TO_WINDOW_ARB                    0x2001
#define WGL_ACCELERATION_ARB                      0x2003
#define WGL_FULL_ACCELERATION_ARB                 0x2027
#define WGL_SUPPORT_OPENGL_ARB                    0x2010
#define WGL_DOUBLE_BUFFER_ARB                     0x2011
#define WGL_PIXEL_TYPE_ARB                        0x2013
#define WGL_TYPE_RGBA_ARB                         0x202B
#define WGL_COLOR_BITS_ARB                        0x2014
#define WGL_ALPHA_BITS_ARB                        0x201B
#define WGL_DEPTH_BITS_ARB                        0x2022
#define WGL_STENCIL_BITS_ARB                      0x2023

#define WGL_CONTEXT_MAJOR_VERSION_ARB             0x2091
#define WGL_CONTEXT_MINOR_VERSION_ARB             0x2092
#define WGL_CONTEXT_PROFILE_MASK_ARB              0x9126
#define WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB 0x00000002

static LRESULT CALLBACK dummyWndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    return DefWindowProcW(h, m, w, l);
}

/* Bootstrap: WGL extension entry-points are only retrievable while a legacy
 * context is current, so we create a throw-away window + 1.x context first. */
static void loadWglExtensions(void) {
    if (extensionsLoaded) return;

    HINSTANCE hInst = GetModuleHandleW(NULL);
    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = dummyWndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = L"NucleusTaoGlDummy";
    RegisterClassW(&wc);

    HWND hwnd = CreateWindowW(L"NucleusTaoGlDummy", L"", WS_OVERLAPPED,
        0, 0, 1, 1, NULL, NULL, hInst, NULL);
    if (!hwnd) { extensionsLoaded = TRUE; return; }

    HDC hdc = GetDC(hwnd);
    PIXELFORMATDESCRIPTOR pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.cStencilBits = 8;
    int pf = ChoosePixelFormat(hdc, &pfd);
    SetPixelFormat(hdc, pf, &pfd);

    HGLRC hglrc = wglCreateContext(hdc);
    if (hglrc) {
        wglMakeCurrent(hdc, hglrc);
        pwglCreateContextAttribsARB = (PFNWGLCREATECONTEXTATTRIBSARBPROC)
            wglGetProcAddress("wglCreateContextAttribsARB");
        pwglChoosePixelFormatARB = (PFNWGLCHOOSEPIXELFORMATARBPROC)
            wglGetProcAddress("wglChoosePixelFormatARB");
        pwglSwapIntervalEXT = (PFNWGLSWAPINTERVALEXTPROC)
            wglGetProcAddress("wglSwapIntervalEXT");
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(hglrc);
    }

    ReleaseDC(hwnd, hdc);
    DestroyWindow(hwnd);
    UnregisterClassW(L"NucleusTaoGlDummy", hInst);

    extensionsLoaded = TRUE;
}

typedef struct {
    HWND  hwnd;
    HDC   hdc;
    HGLRC hglrc;
    int   widthPx;
    int   heightPx;
    float scale;
} WglAttachment;

/* ================================================================== */
/*  Host pixel-format / HGLRC sharing                                  */
/*                                                                     */
/*  Exported so the overlay+popup DLL (nucleus_tao_windows_native_view) */
/*  can join the host's WGL share group via                            */
/*  `wglCreateContextAttribsARB(.., hostHGLRC, ..)`. The overlay HDC's  */
/*  pixel format MUST match the host's exactly (wglShareLists           */
/*  invariant carried over to the ARB share path on every known         */
/*  driver), so we expose the cached PFD + format index for             */
/*  `SetPixelFormat(overlayDC, hostFormatIndex, &cachedPfd)`.           */
/*                                                                     */
/*  Resolved by overlay_gl.c via                                        */
/*  `GetProcAddress(GetModuleHandleW(L"nucleus_tao_gl.dll"), ..)`.      */
/* ================================================================== */

static int                   sHostPixelFormatIndex = 0;
static PIXELFORMATDESCRIPTOR sHostPfd;
static HGLRC                 sHostHglrc = NULL;

__declspec(dllexport) HGLRC nucleus_tao_host_hglrc(void) {
    return sHostHglrc;
}

__declspec(dllexport) int nucleus_tao_host_pixel_format(PIXELFORMATDESCRIPTOR *outPfd) {
    if (outPfd) *outPfd = sHostPfd;
    return sHostPixelFormatIndex;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL; (void)fdwReason; (void)lpvReserved;
    return TRUE;
}

/* ================================================================== */
/*  JNI exports                                                        */
/*  Package: io.github.kdroidfilter.nucleus.window.tao                 */
/*  Class:   NativeTaoGlBridge                                         */
/* ================================================================== */

JNIEXPORT jlong JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativeAttach(
    JNIEnv *env, jclass clazz, jlong hwndLong)
{
    (void)env; (void)clazz;
    HWND hwnd = (HWND)(uintptr_t)hwndLong;
    if (!hwnd || !IsWindow(hwnd)) return 0;

    loadWglExtensions();

    HDC hdc = GetDC(hwnd);
    if (!hdc) return 0;

    int pixelFormat = 0;
    PIXELFORMATDESCRIPTOR pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;

    if (pwglChoosePixelFormatARB) {
        /* WGL_ALPHA_BITS_ARB=8: required so the host shares its pixel
         * format with transparent overlay/popup HWNDs. The host stays
         * visually opaque (no DwmEnableBlurBehindWindow on it); the alpha
         * channel just sits unused in the back buffer. Aligning the
         * format also lets the overlay's HGLRC join the share group via
         * wglCreateContextAttribsARB(.., hostHGLRC, ..) — wglShareLists
         * requires identical pixel formats across share-group members. */
        const int attribs[] = {
            WGL_DRAW_TO_WINDOW_ARB, GL_TRUE,
            WGL_SUPPORT_OPENGL_ARB, GL_TRUE,
            WGL_DOUBLE_BUFFER_ARB,  GL_TRUE,
            WGL_ACCELERATION_ARB,   WGL_FULL_ACCELERATION_ARB,
            WGL_PIXEL_TYPE_ARB,     WGL_TYPE_RGBA_ARB,
            WGL_COLOR_BITS_ARB,     32,
            WGL_ALPHA_BITS_ARB,     8,
            WGL_DEPTH_BITS_ARB,     0,
            WGL_STENCIL_BITS_ARB,   8,
            0
        };
        UINT numFormats = 0;
        pwglChoosePixelFormatARB(hdc, attribs, NULL, 1, &pixelFormat, &numFormats);
        if (numFormats == 0) pixelFormat = 0;
    }
    if (pixelFormat == 0) {
        pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
        pfd.iPixelType = PFD_TYPE_RGBA;
        pfd.cColorBits = 32;
        pfd.cAlphaBits = 8;
        pfd.cStencilBits = 8;
        pixelFormat = ChoosePixelFormat(hdc, &pfd);
    }

    DescribePixelFormat(hdc, pixelFormat, sizeof(pfd), &pfd);
    if (!SetPixelFormat(hdc, pixelFormat, &pfd)) {
        ReleaseDC(hwnd, hdc);
        return 0;
    }
    /* Cache for cross-DLL share-group reuse. */
    sHostPixelFormatIndex = pixelFormat;
    sHostPfd = pfd;

    HGLRC hglrc = NULL;
    if (pwglCreateContextAttribsARB) {
        const int ctxAttribs[] = {
            WGL_CONTEXT_MAJOR_VERSION_ARB, 3,
            WGL_CONTEXT_MINOR_VERSION_ARB, 3,
            WGL_CONTEXT_PROFILE_MASK_ARB,  WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB,
            0
        };
        hglrc = pwglCreateContextAttribsARB(hdc, NULL, ctxAttribs);
    }
    if (!hglrc) {
        hglrc = wglCreateContext(hdc);
    }
    if (!hglrc) {
        ReleaseDC(hwnd, hdc);
        return 0;
    }

    wglMakeCurrent(hdc, hglrc);
    /* VSync ON: SwapBuffers blocks until the next display refresh. Required
     * for smooth scroll: Compose's `withFrameNanos` animation steps land on
     * regular display-aligned ticks, otherwise the irregular cadence of
     * software-throttled frames feels juddery compared to AWT/Skiko. */
    if (pwglSwapIntervalEXT) pwglSwapIntervalEXT(1);

    WglAttachment *att = (WglAttachment *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(WglAttachment));
    if (!att) {
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(hglrc);
        ReleaseDC(hwnd, hdc);
        return 0;
    }
    att->hwnd = hwnd;
    att->hdc = hdc;
    att->hglrc = hglrc;
    att->scale = 1.0f;
    sHostHglrc = hglrc;
    return (jlong)(uintptr_t)att;
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativeDetach(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    wglMakeCurrent(NULL, NULL);
    if (sHostHglrc == att->hglrc) sHostHglrc = NULL;
    wglDeleteContext(att->hglrc);
    ReleaseDC(att->hwnd, att->hdc);
    HeapFree(GetProcessHeap(), 0, att);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativeMakeCurrent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    wglMakeCurrent(att->hdc, att->hglrc);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativeResize(
    JNIEnv *env, jclass clazz, jlong handle, jint widthPx, jint heightPx, jfloat scale)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    att->widthPx = (int)widthPx;
    att->heightPx = (int)heightPx;
    att->scale = scale;
    /* Update GL viewport so subsequent Skia surface creation matches. */
    wglMakeCurrent(att->hdc, att->hglrc);
    glViewport(0, 0, att->widthPx, att->heightPx);
}

JNIEXPORT void JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativePresent(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    if (!att) return;
    SwapBuffers(att->hdc);
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativeWidth(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    return att ? (jint)att->widthPx : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_window_tao_NativeTaoGlBridge_nativeHeight(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    WglAttachment *att = (WglAttachment *)(uintptr_t)handle;
    return att ? (jint)att->heightPx : 0;
}
