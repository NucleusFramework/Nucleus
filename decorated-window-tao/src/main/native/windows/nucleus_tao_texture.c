/**
 * JNI bridge: external texture import for the TextureView composable
 * (Windows / ANGLE backend). Compiled into nucleus_tao_gl.dll next to
 * nucleus_tao_gl.c — it reuses that file's host EGL registry through the
 * exported nucleus_tao_host_egl_* accessors (same-DLL extern calls).
 *
 * Import path:
 *   producer D3D11 texture (legacy DXGI shared handle) →
 *   OpenSharedResource on ANGLE's own D3D11 device (retrieved via
 *   EGL_EXT_device_query, exactly like overlay_dcomp.cpp) →
 *   eglCreatePbufferFromClientBuffer with EGL_D3D_TEXTURE_ANGLE →
 *   eglBindTexImage onto a GL ES texture → Skia adopts the texture id
 *   (Image.adoptTextureFrom) and samples it while compositing the
 *   Compose scene.
 *
 *   (The one-step EGL_D3D_TEXTURE_2D_SHARE_HANDLE_ANGLE buftype is
 *   rejected with EGL_BAD_PARAMETER by the shipped ANGLE build, so the
 *   handle is opened explicitly instead.)
 *
 * NT handles (D3D11_RESOURCE_MISC_SHARED_NTHANDLE) are NOT accepted by
 * ANGLE's share-handle client-buffer path — producers must use the
 * legacy shared handle (IDXGIResource::GetSharedHandle).
 *
 * Synchronization — two modes, detected automatically at import:
 *   - Producer texture carries an IDXGIKeyedMutex
 *     (D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX): the pbuffer wraps a
 *     PRIVATE staging texture on ANGLE's device; nativeUpdateFrame
 *     performs AcquireSync(0) → CopyResource(private ← shared) →
 *     ReleaseSync(0). Skia only ever samples the private copy — no
 *     tearing, at the cost of one GPU-GPU copy per frame (the same
 *     trade-off Flutter's external-texture path makes).
 *   - No keyed mutex (plain D3D11_RESOURCE_MISC_SHARED): the pbuffer
 *     wraps the shared texture directly — true zero copy. The producer
 *     must Flush() after writing; a redraw racing a producer write may
 *     sample a partially updated frame (tearing), never stale memory
 *     or a crash.
 *
 * A minimal self-contained D3D11 "test producer" (solid-colour clears
 * into a shared texture, optional keyed mutex) ships alongside the
 * import path so demos and smoke tests can exercise TextureView
 * end-to-end without an external video/GL pipeline.
 *
 * Threading: import/update/destroy must run on the Tao event-loop
 * thread — nativeUpdateFrame drives ANGLE's immediate context, which
 * ANGLE itself uses on that thread. The test producer owns a separate
 * device and is safe from any single producer thread.
 */

#include <jni.h>
#include <windows.h>
/* Declaration only — the implementation comes from nucleus_tao_gl.c's
 * /NODEFAULTLIB memset shim at link time. */
#include <string.h>

#include "../shared/nucleus_tao_egl_binding.h"

#define EGL_EGL_PROTOTYPES 0
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>

#define COBJMACROS
#include <d3d11.h>

/* Host accessors exported by nucleus_tao_gl.c (same DLL). */
extern int   nucleus_tao_host_egl_for_hwnd(void *hwnd, void **dpy, void **ctx, void **cfg);
extern void *nucleus_tao_host_egl_display(void);
extern void *nucleus_tao_host_egl_context(void);
extern void *nucleus_tao_host_egl_config(void);
extern void *nucleus_tao_host_egl_proc(const char *name);

/* ================================================================== */
/*  GL ES entry points (resolved through the host's eglGetProcAddress) */
/* ================================================================== */

#define NUCLEUS_GL_TEXTURE_2D         0x0DE1u
#define NUCLEUS_GL_TEXTURE_BINDING_2D 0x8069u
#define NUCLEUS_GL_TEXTURE_MIN_FILTER 0x2801u
#define NUCLEUS_GL_TEXTURE_MAG_FILTER 0x2800u
#define NUCLEUS_GL_TEXTURE_WRAP_S     0x2802u
#define NUCLEUS_GL_TEXTURE_WRAP_T     0x2803u
#define NUCLEUS_GL_LINEAR             0x2601u
#define NUCLEUS_GL_CLAMP_TO_EDGE      0x812Fu

typedef void (APIENTRY *PFN_glGenTextures)(int, unsigned int *);
typedef void (APIENTRY *PFN_glDeleteTextures)(int, const unsigned int *);
typedef void (APIENTRY *PFN_glBindTexture)(unsigned int, unsigned int);
typedef void (APIENTRY *PFN_glTexParameteri)(unsigned int, unsigned int, int);
typedef void (APIENTRY *PFN_glGetIntegerv)(unsigned int, int *);

static volatile BOOL sTexResolved = FALSE;
static BOOL sTexAvailable = FALSE;

static PFNEGLGETERRORPROC                      pEglGetError2                     = NULL;
static PFNEGLQUERYDISPLAYATTRIBEXTPROC         pEglQueryDisplayAttribEXT2        = NULL;
static PFNEGLQUERYDEVICEATTRIBEXTPROC          pEglQueryDeviceAttribEXT2         = NULL;
static PFNEGLCREATEPBUFFERFROMCLIENTBUFFERPROC pEglCreatePbufferFromClientBuffer = NULL;
static PFNEGLBINDTEXIMAGEPROC                  pEglBindTexImage                  = NULL;
static PFNEGLRELEASETEXIMAGEPROC               pEglReleaseTexImage               = NULL;
static PFNEGLMAKECURRENTPROC                   pEglMakeCurrent2                  = NULL;
static PFNEGLGETCURRENTSURFACEPROC             pEglGetCurrentSurface2            = NULL;
static PFNEGLDESTROYSURFACEPROC                pEglDestroySurface2               = NULL;
static PFNEGLGETCURRENTDISPLAYPROC             pEglGetCurrentDisplay2            = NULL;
static PFNEGLGETCURRENTCONTEXTPROC             pEglGetCurrentContext2            = NULL;
static PFN_glGenTextures    pglGenTextures    = NULL;
static PFN_glDeleteTextures pglDeleteTextures = NULL;
static PFN_glBindTexture    pglBindTexture    = NULL;
static PFN_glTexParameteri  pglTexParameteri  = NULL;
static PFN_glGetIntegerv    pglGetIntegerv    = NULL;

static void resolveTexEntryPoints(void) {
    if (sTexResolved) return;

    pEglGetError2 = (PFNEGLGETERRORPROC) nucleus_tao_host_egl_proc("eglGetError");
    pEglQueryDisplayAttribEXT2 = (PFNEGLQUERYDISPLAYATTRIBEXTPROC)
        nucleus_tao_host_egl_proc("eglQueryDisplayAttribEXT");
    pEglQueryDeviceAttribEXT2 = (PFNEGLQUERYDEVICEATTRIBEXTPROC)
        nucleus_tao_host_egl_proc("eglQueryDeviceAttribEXT");
    pEglCreatePbufferFromClientBuffer = (PFNEGLCREATEPBUFFERFROMCLIENTBUFFERPROC)
        nucleus_tao_host_egl_proc("eglCreatePbufferFromClientBuffer");
    pEglBindTexImage       = (PFNEGLBINDTEXIMAGEPROC)      nucleus_tao_host_egl_proc("eglBindTexImage");
    pEglReleaseTexImage    = (PFNEGLRELEASETEXIMAGEPROC)   nucleus_tao_host_egl_proc("eglReleaseTexImage");
    pEglMakeCurrent2       = (PFNEGLMAKECURRENTPROC)       nucleus_tao_host_egl_proc("eglMakeCurrent");
    pEglGetCurrentSurface2 = (PFNEGLGETCURRENTSURFACEPROC) nucleus_tao_host_egl_proc("eglGetCurrentSurface");
    pEglDestroySurface2    = (PFNEGLDESTROYSURFACEPROC)    nucleus_tao_host_egl_proc("eglDestroySurface");
    pEglGetCurrentDisplay2 = (PFNEGLGETCURRENTDISPLAYPROC) nucleus_tao_host_egl_proc("eglGetCurrentDisplay");
    pEglGetCurrentContext2 = (PFNEGLGETCURRENTCONTEXTPROC) nucleus_tao_host_egl_proc("eglGetCurrentContext");
    /* ANGLE's eglGetProcAddress also returns core ES entry points
     * (EGL_KHR_get_all_proc_addresses). */
    pglGenTextures    = (PFN_glGenTextures)    nucleus_tao_host_egl_proc("glGenTextures");
    pglDeleteTextures = (PFN_glDeleteTextures) nucleus_tao_host_egl_proc("glDeleteTextures");
    pglBindTexture    = (PFN_glBindTexture)    nucleus_tao_host_egl_proc("glBindTexture");
    pglTexParameteri  = (PFN_glTexParameteri)  nucleus_tao_host_egl_proc("glTexParameteri");
    pglGetIntegerv    = (PFN_glGetIntegerv)    nucleus_tao_host_egl_proc("glGetIntegerv");

    sTexAvailable = (pEglCreatePbufferFromClientBuffer && pEglBindTexImage &&
                     pEglReleaseTexImage && pEglMakeCurrent2 && pEglGetCurrentSurface2 &&
                     pEglDestroySurface2 && pEglQueryDisplayAttribEXT2 &&
                     pEglQueryDeviceAttribEXT2 && pglGenTextures && pglDeleteTextures &&
                     pglBindTexture && pglTexParameteri && pglGetIntegerv);
    /* Published last: a racing caller must never see "resolved" while the
     * pointers are still NULL (it would report a spurious -1 "EGL missing"). */
    sTexResolved = TRUE;
}

/* Re-makes whatever was current before the pbuffer was bound current again.
 * A zeroed (never captured) surface reads as EGL_NO_SURFACE, in which case the
 * thread is simply left with nothing current. */
static void restoreCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    if (draw != EGL_NO_SURFACE && ctx != EGL_NO_CONTEXT) {
        pEglMakeCurrent2(dpy, draw, read, ctx);
    } else {
        pEglMakeCurrent2(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

/* ── Binding save / restore ──────────────────────────────────────────────
 *
 * The Kotlin side binds a surface's own EGL surface on paths that run outside
 * that surface's render pass — a standalone tray panel's bring-up, resize,
 * render and teardown — and those paths can be *inside another* surface's render
 * pass: a `TaoStandalonePopup` composed into a live window builds its host from
 * `remember {}`, sizes it from the caller's layout and disposes it from
 * `onDispose`, all from inside the window scene's `ComposeScene.render()`.
 * Leaving the panel's 1x1 pbuffer current there sends the rest of that frame,
 * and its `flushAndSubmit`, into the panel instead of the window.
 *
 * The bookkeeping (one slot, nesting refused, consumed once) lives in the shared
 * header, next to the Linux bridge's; what stays here is reading this platform's
 * current binding and putting it back — including the unbind this side does
 * itself, since the process EGL display is reachable from the host registry.
 *
 * Single slot, not thread-local: every entry point in this file is documented
 * event-loop-thread only, and `__declspec(thread)` would need the CRT's
 * `_tls_used` that `/NODEFAULTLIB` drops. */

static NucleusTaoEglBindingSlot sDisplaced;

static BOOL bindingProcsAvailable(void) {
    resolveTexEntryPoints();
    return pEglMakeCurrent2 && pEglGetCurrentSurface2 &&
           pEglGetCurrentDisplay2 && pEglGetCurrentContext2;
}

/**
 * Snapshots the EGL binding current on this thread so [nativeRestoreBinding]
 * can put it back. Returns JNI_FALSE when a snapshot is already outstanding
 * (nesting), in which case the caller must not rebind.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeSaveCurrentBinding(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    if (!bindingProcsAvailable()) return JNI_FALSE;
    return nucleus_tao_egl_binding_save(
        &sDisplaced,
        pEglGetCurrentDisplay2(),
        pEglGetCurrentContext2(),
        pEglGetCurrentSurface2(EGL_DRAW),
        pEglGetCurrentSurface2(EGL_READ)) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Restores the binding [nativeSaveCurrentBinding] snapshotted. When nothing was
 * current at that point the thread is *unbound* instead: whatever the caller
 * bound in between is its own surface, and leaving it current would hand the
 * next unrelated GL work a foreign draw target. Nothing was displaced there by
 * construction — code running inside another surface's render pass always has
 * that surface current, so the snapshot is non-empty exactly when there is
 * something to put back.
 *
 * Returns JNI_FALSE only when there was no outstanding snapshot at all.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeRestoreBinding(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    void *display, *context, *draw, *read;
    if (!nucleus_tao_egl_binding_take(&sDisplaced, &display, &context, &draw, &read)) {
        return JNI_FALSE;
    }
    if (display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT) {
        return pEglMakeCurrent2((EGLDisplay)display, (EGLSurface)draw,
                                (EGLSurface)read, (EGLContext)context) ? JNI_TRUE : JNI_FALSE;
    }
    /* eglMakeCurrent needs a display even to unbind; the process display is the
     * one every surface here lives on. */
    EGLDisplay hostDpy = (EGLDisplay) nucleus_tao_host_egl_display();
    if (hostDpy == EGL_NO_DISPLAY) hostDpy = pEglGetCurrentDisplay2();
    if (hostDpy != EGL_NO_DISPLAY) {
        pEglMakeCurrent2(hostDpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    return JNI_TRUE;
}

/* GUIDs kept local — avoids linking dxguid.lib under /NODEFAULTLIB. */
/* {6f15aaf2-d208-4e89-9ab4-489535d34f9c} */
static const GUID kIID_ID3D11Texture2D =
    { 0x6f15aaf2, 0xd208, 0x4e89, { 0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c } };
/* {9d8e1289-d7b3-465f-8126-250e349af85d} */
static const GUID kIID_IDXGIKeyedMutex =
    { 0x9d8e1289, 0xd7b3, 0x465f, { 0x81, 0x26, 0x25, 0x0e, 0x34, 0x9a, 0xf8, 0x5d } };
/* {035f3ab4-482e-4e50-b41f-8a7f8bd8960b} */
static const GUID kIID_IDXGIResource =
    { 0x035f3ab4, 0x482e, 0x4e50, { 0xb4, 0x1f, 0x8a, 0x7f, 0x8b, 0xd8, 0x96, 0x0b } };

/* ================================================================== */
/*  Imported external texture                                          */
/* ================================================================== */

/* Consumer-side AcquireSync timeout. A producer holding the mutex for
 * longer than this simply costs the compositor one stale frame — the
 * copy is retried on the next markFrameAvailable. Never blocks the
 * event loop for more than one vsync. */
#define NUCLEUS_TEX_ACQUIRE_TIMEOUT_MS 8

typedef struct {
    EGLDisplay           dpy;
    EGLSurface           pbuffer;
    unsigned int         texId;
    /* Surfaces/context that were current when this import ran, so binding the
     * pbuffer can be undone. Import and destroy are called from inside
     * ComposeScene.render(), i.e. in the MIDDLE of a host frame whose Skia
     * output goes to the window surface — leaving the pbuffer current would
     * redirect the rest of that frame (and its flush) into the producer's
     * texture. */
    EGLSurface           hostDraw;
    EGLSurface           hostRead;
    EGLContext           hostCtx;
    /* Texture the pbuffer wraps: the private staging copy (keyed-mutex
     * mode) or the opened shared texture itself (direct mode). */
    ID3D11Texture2D     *sampleTexture;
    /* Keyed-mutex mode only — all NULL in direct mode. */
    ID3D11Texture2D     *sharedTexture;
    IDXGIKeyedMutex     *keyedMutex;
    ID3D11DeviceContext *copyCtx; /* ANGLE device's immediate context */
} NucleusExternalTexture;

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeImportD3D11SharedHandle(
    JNIEnv *env, jclass clazz, jlong hostHwnd, jlong sharedHandle, jint widthPx, jint heightPx)
{
    (void)env; (void)clazz;
    if (!sharedHandle || widthPx < 1 || heightPx < 1) return 0;
    resolveTexEntryPoints();
    if (!sTexAvailable) return -1;

    /* EGL trio of the composable's host window; global fallback covers
     * the headless/ownerless-panel case (mirrors overlay_dcomp.cpp). */
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLConfig  cfg = NULL;
    void *pdpy = NULL, *pctx = NULL, *pcfg = NULL;
    if (hostHwnd && nucleus_tao_host_egl_for_hwnd((void *)(uintptr_t)hostHwnd, &pdpy, &pctx, &pcfg)) {
        dpy = (EGLDisplay)pdpy;
        ctx = (EGLContext)pctx;
        cfg = (EGLConfig)pcfg;
    } else {
        dpy = (EGLDisplay)nucleus_tao_host_egl_display();
        ctx = (EGLContext)nucleus_tao_host_egl_context();
        cfg = (EGLConfig)nucleus_tao_host_egl_config();
    }
    if (dpy == EGL_NO_DISPLAY || ctx == EGL_NO_CONTEXT || !cfg) return -2;

    /* ANGLE's own D3D11 device (same retrieval as overlay_dcomp.cpp). */
    EGLAttrib deviceAttrib = 0;
    if (!pEglQueryDisplayAttribEXT2(dpy, EGL_DEVICE_EXT, &deviceAttrib) || !deviceAttrib) {
        return -(jlong)0x20001;
    }
    EGLAttrib d3dAttrib = 0;
    if (!pEglQueryDeviceAttribEXT2((EGLDeviceEXT)deviceAttrib, EGL_D3D11_DEVICE_ANGLE, &d3dAttrib) ||
        !d3dAttrib) {
        return -(jlong)0x20002;
    }
    ID3D11Device *angleDevice = (ID3D11Device *)d3dAttrib;

    /* Open the producer's shared texture on ANGLE's device: the resulting
     * ID3D11Texture2D belongs to that device, which is exactly what the
     * EGL_D3D_TEXTURE_ANGLE client-buffer path validates against. */
    ID3D11Texture2D *sharedTex = NULL;
    HRESULT hr = ID3D11Device_OpenSharedResource(
        angleDevice, (HANDLE)(uintptr_t)sharedHandle, &kIID_ID3D11Texture2D, (void **)&sharedTex);
    if (FAILED(hr) || !sharedTex) {
        return -(jlong)0x20003;
    }

    /* Keyed-mutex detection: producers that opted into
     * D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX get the tear-free staging
     * path; the mutex interface simply isn't there otherwise. */
    IDXGIKeyedMutex *keyedMutex = NULL;
    ID3D11Texture2D_QueryInterface(sharedTex, &kIID_IDXGIKeyedMutex, (void **)&keyedMutex);

    ID3D11Texture2D     *sampleTex = sharedTex;
    ID3D11Texture2D     *privateTex = NULL;
    ID3D11DeviceContext *copyCtx = NULL;
    if (keyedMutex) {
        D3D11_TEXTURE2D_DESC desc;
        ID3D11Texture2D_GetDesc(sharedTex, &desc);
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        desc.CPUAccessFlags = 0;
        desc.MiscFlags = 0;
        if (SUCCEEDED(ID3D11Device_CreateTexture2D(angleDevice, &desc, NULL, &privateTex))) {
            ID3D11Device_GetImmediateContext(angleDevice, &copyCtx);
            sampleTex = privateTex;
        } else {
            /* Staging texture unavailable — degrade to the direct path
             * (worst case tearing, same as a mutex-less producer). */
            IDXGIKeyedMutex_Release(keyedMutex);
            keyedMutex = NULL;
        }
    }

    const EGLint pbAttribs[] = {
        EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
        EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
        EGL_NONE
    };
    EGLSurface pb = pEglCreatePbufferFromClientBuffer(
        dpy, EGL_D3D_TEXTURE_ANGLE, (EGLClientBuffer)sampleTex, cfg, pbAttribs);
    if (pb == EGL_NO_SURFACE) {
        jlong err = (jlong)(0x30000 | (pEglGetError2 ? (unsigned int)pEglGetError2() : 0u));
        if (keyedMutex) IDXGIKeyedMutex_Release(keyedMutex);
        if (copyCtx) ID3D11DeviceContext_Release(copyCtx);
        if (privateTex) ID3D11Texture2D_Release(privateTex);
        ID3D11Texture2D_Release(sharedTex);
        return -err;
    }

    /* Bind the pbuffer's colour buffer onto a fresh GL texture. eglBindTexImage
     * needs the pbuffer current, so remember what was current first: this runs
     * from inside ComposeScene.render(), i.e. in the MIDDLE of a host frame
     * whose Skia output targets the window surface. Leaving the pbuffer bound
     * would send the rest of that frame — and its flushAndSubmit — into the
     * producer's texture instead of the window. */
    EGLSurface hostDraw = pEglGetCurrentSurface2(EGL_DRAW);
    EGLSurface hostRead = pEglGetCurrentSurface2(EGL_READ);
    EGLBoolean bound = EGL_FALSE;
    unsigned int tex = 0;
    if (pEglMakeCurrent2(dpy, pb, pb, ctx)) {
        int prevTex = 0;
        pglGetIntegerv(NUCLEUS_GL_TEXTURE_BINDING_2D, &prevTex);
        pglGenTextures(1, &tex);
        pglBindTexture(NUCLEUS_GL_TEXTURE_2D, tex);
        pglTexParameteri(NUCLEUS_GL_TEXTURE_2D, NUCLEUS_GL_TEXTURE_MIN_FILTER, NUCLEUS_GL_LINEAR);
        pglTexParameteri(NUCLEUS_GL_TEXTURE_2D, NUCLEUS_GL_TEXTURE_MAG_FILTER, NUCLEUS_GL_LINEAR);
        pglTexParameteri(NUCLEUS_GL_TEXTURE_2D, NUCLEUS_GL_TEXTURE_WRAP_S, (int)NUCLEUS_GL_CLAMP_TO_EDGE);
        pglTexParameteri(NUCLEUS_GL_TEXTURE_2D, NUCLEUS_GL_TEXTURE_WRAP_T, (int)NUCLEUS_GL_CLAMP_TO_EDGE);
        bound = pEglBindTexImage(dpy, pb, EGL_BACK_BUFFER);
        pglBindTexture(NUCLEUS_GL_TEXTURE_2D, (unsigned int)prevTex);
        restoreCurrent(dpy, hostDraw, hostRead, ctx);
    }
    if (!bound) {
        jlong err = (jlong)(0x50000 | (pEglGetError2 ? (unsigned int)pEglGetError2() : 0u));
        if (tex) pglDeleteTextures(1, &tex);
        if (pEglGetCurrentSurface2(EGL_DRAW) == pb) {
            restoreCurrent(dpy, hostDraw, hostRead, ctx);
        }
        pEglDestroySurface2(dpy, pb);
        if (keyedMutex) IDXGIKeyedMutex_Release(keyedMutex);
        if (copyCtx) ID3D11DeviceContext_Release(copyCtx);
        if (privateTex) ID3D11Texture2D_Release(privateTex);
        ID3D11Texture2D_Release(sharedTex);
        return -err;
    }

    NucleusExternalTexture *t = (NucleusExternalTexture *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusExternalTexture));
    if (!t) {
        pEglReleaseTexImage(dpy, pb, EGL_BACK_BUFFER);
        pglDeleteTextures(1, &tex);
        if (pEglGetCurrentSurface2(EGL_DRAW) == pb) {
            restoreCurrent(dpy, hostDraw, hostRead, ctx);
        }
        pEglDestroySurface2(dpy, pb);
        if (keyedMutex) IDXGIKeyedMutex_Release(keyedMutex);
        if (copyCtx) ID3D11DeviceContext_Release(copyCtx);
        if (privateTex) ID3D11Texture2D_Release(privateTex);
        ID3D11Texture2D_Release(sharedTex);
        return 0;
    }
    t->dpy = dpy;
    t->pbuffer = pb;
    t->texId = tex;
    t->hostDraw = hostDraw;
    t->hostRead = hostRead;
    t->hostCtx = ctx;
    t->sampleTexture = sampleTex;
    if (keyedMutex) {
        t->sharedTexture = sharedTex;
        t->keyedMutex = keyedMutex;
        t->copyCtx = copyCtx;
        /* Prime the staging copy so the first composited frame isn't an
         * uninitialized texture. Same timeout as the per-frame path rather
         * than 0: a producer that publishes one frame and goes idle would
         * otherwise need a markFrameAvailable to ever fill the staging
         * texture, and the copy that request triggers can lose the mutex
         * race too. (TextureView schedules a retry redraw on a skipped copy,
         * so this is belt-and-braces, not the only safeguard.) */
        if (IDXGIKeyedMutex_AcquireSync(keyedMutex, 0, NUCLEUS_TEX_ACQUIRE_TIMEOUT_MS) == S_OK) {
            ID3D11DeviceContext_CopyResource(
                copyCtx, (ID3D11Resource *)privateTex, (ID3D11Resource *)sharedTex);
            IDXGIKeyedMutex_ReleaseSync(keyedMutex, 0);
        }
    }
    return (jlong)(uintptr_t)t;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeGlTextureId(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    NucleusExternalTexture *t = (NucleusExternalTexture *)(uintptr_t)handle;
    return t ? (jint)t->texId : 0;
}

/* TRUE when the import runs the keyed-mutex staging path (tear-free). */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeIsSynchronized(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    NucleusExternalTexture *t = (NucleusExternalTexture *)(uintptr_t)handle;
    return (t && t->keyedMutex) ? JNI_TRUE : JNI_FALSE;
}

/* Pulls the latest producer frame into the staging texture (keyed-mutex
 * mode). Direct mode is a no-op — Skia already samples the live shared
 * texture. Returns FALSE when the producer held the mutex past the
 * timeout (frame skipped, previous content stays on screen).
 *
 * Must run on the event-loop thread: the copy goes through ANGLE's
 * immediate context, and D3D11 immediate contexts are not thread-safe.
 * Called from the draw pass, the copy is enqueued on the same device
 * queue Skia's GL work flushes to afterwards — the sampled content is
 * therefore always the copied frame, ordering is guaranteed by the
 * device's command serialization. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeUpdateFrame(
    JNIEnv *env, jclass clazz, jlong handle)
{
    (void)env; (void)clazz;
    NucleusExternalTexture *t = (NucleusExternalTexture *)(uintptr_t)handle;
    if (!t) return JNI_FALSE;
    if (!t->keyedMutex) return JNI_TRUE;
    /* S_OK check, not SUCCEEDED(): AcquireSync returns WAIT_TIMEOUT
     * (0x102) on contention, which is a "success" HRESULT. */
    if (IDXGIKeyedMutex_AcquireSync(t->keyedMutex, 0, NUCLEUS_TEX_ACQUIRE_TIMEOUT_MS) != S_OK) {
        return JNI_FALSE;
    }
    ID3D11DeviceContext_CopyResource(
        t->copyCtx, (ID3D11Resource *)t->sampleTexture, (ID3D11Resource *)t->sharedTexture);
    IDXGIKeyedMutex_ReleaseSync(t->keyedMutex, 0);
    return JNI_TRUE;
}

/* [deleteTexture] = JNI_TRUE only when Skia never adopted the texture id
 * (adoption transfers ownership — Skia deletes it with the Image). */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeDestroy(
    JNIEnv *env, jclass clazz, jlong handle, jboolean deleteTexture)
{
    (void)env; (void)clazz;
    NucleusExternalTexture *t = (NucleusExternalTexture *)(uintptr_t)handle;
    if (!t) return;
    if (t->pbuffer != EGL_NO_SURFACE) {
        pEglReleaseTexImage(t->dpy, t->pbuffer, EGL_BACK_BUFFER);
        /* Never destroy a surface while it's bound on this thread — and put the
         * host's own surface back rather than leaving the thread with nothing
         * current: disposal runs inside a host frame too. */
        if (pEglGetCurrentSurface2(EGL_DRAW) == t->pbuffer) {
            restoreCurrent(t->dpy, t->hostDraw, t->hostRead, t->hostCtx);
        }
        pEglDestroySurface2(t->dpy, t->pbuffer);
    }
    if (deleteTexture && t->texId) pglDeleteTextures(1, &t->texId);
    if (t->keyedMutex) IDXGIKeyedMutex_Release(t->keyedMutex);
    if (t->copyCtx) ID3D11DeviceContext_Release(t->copyCtx);
    if (t->sharedTexture) ID3D11Texture2D_Release(t->sharedTexture);
    if (t->sampleTexture) ID3D11Texture2D_Release(t->sampleTexture);
    HeapFree(GetProcessHeap(), 0, t);
}

/* ================================================================== */
/*  D3D11 test producer (demos / smoke tests)                          */
/* ================================================================== */

#define NUCLEUS_TEST_BAR_PX 16

typedef struct {
    ID3D11Device           *device;
    ID3D11DeviceContext    *imCtx;
    ID3D11Texture2D        *texture;
    ID3D11RenderTargetView *rtv;
    IDXGIKeyedMutex        *keyedMutex;   /* NULL without MISC_SHARED_KEYEDMUTEX */
    HANDLE                  sharedHandle; /* legacy handle — not a real NT handle, never closed */
    int                     widthPx;
    int                     heightPx;
    /* White RGBA scratch strip for the moving test-pattern bars,
     * NUCLEUS_TEST_BAR_PX * max(width,height) * 4 bytes. */
    unsigned char          *barPixels;
} NucleusTestProducer;

/* Clears to [argb] (premultiplied); when the producer has a keyed
 * mutex the caller must already hold it. */
static void testProducerClear(NucleusTestProducer *p, jint argb) {
    float a = (float)((argb >> 24) & 0xFF) / 255.0f;
    FLOAT rgba[4];
    rgba[0] = a * (float)((argb >> 16) & 0xFF) / 255.0f;
    rgba[1] = a * (float)((argb >>  8) & 0xFF) / 255.0f;
    rgba[2] = a * (float)( argb        & 0xFF) / 255.0f;
    rgba[3] = a;
    ID3D11DeviceContext_ClearRenderTargetView(p->imCtx, p->rtv, rgba);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeTestProducerCreate(
    JNIEnv *env, jclass clazz, jint widthPx, jint heightPx, jboolean useKeyedMutex)
{
    (void)env; (void)clazz;
    if (widthPx < 1 || heightPx < 1) return 0;

    HMODULE d3dMod = LoadLibraryW(L"d3d11.dll");
    if (!d3dMod) return 0;
    PFN_D3D11_CREATE_DEVICE pCreateDevice =
        (PFN_D3D11_CREATE_DEVICE)GetProcAddress(d3dMod, "D3D11CreateDevice");
    if (!pCreateDevice) {
        FreeLibrary(d3dMod);
        return 0;
    }

    /* Own device, distinct from ANGLE's — that's the point: the shared
     * handle is the only bridge between producer and compositor. */
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *imCtx = NULL;
    HRESULT hr = pCreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0,
                               NULL, 0, D3D11_SDK_VERSION, &device, NULL, &imCtx);
    if (FAILED(hr)) {
        hr = pCreateDevice(NULL, D3D_DRIVER_TYPE_WARP, NULL, 0,
                           NULL, 0, D3D11_SDK_VERSION, &device, NULL, &imCtx);
    }
    if (FAILED(hr) || !device || !imCtx) {
        FreeLibrary(d3dMod);
        return 0;
    }
    /* The device keeps d3d11.dll loaded from here on; releasing our own
     * reference keeps the failure paths above and the success path
     * symmetrical (one LoadLibrary, one FreeLibrary). */
    FreeLibrary(d3dMod);

    D3D11_TEXTURE2D_DESC desc;
    memset(&desc, 0, sizeof(desc));
    desc.Width = (UINT)widthPx;
    desc.Height = (UINT)heightPx;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    /* The two MISC_SHARED flags are mutually exclusive; KEYEDMUTEX also
     * yields a legacy shared handle via IDXGIResource::GetSharedHandle. */
    desc.MiscFlags = useKeyedMutex ? D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX
                                   : D3D11_RESOURCE_MISC_SHARED;
    ID3D11Texture2D *tex = NULL;
    if (FAILED(ID3D11Device_CreateTexture2D(device, &desc, NULL, &tex))) {
        ID3D11DeviceContext_Release(imCtx);
        ID3D11Device_Release(device);
        return 0;
    }

    IDXGIResource *dxgiRes = NULL;
    HANDLE shared = NULL;
    if (FAILED(ID3D11Texture2D_QueryInterface(tex, &kIID_IDXGIResource, (void **)&dxgiRes)) ||
        FAILED(IDXGIResource_GetSharedHandle(dxgiRes, &shared)) || !shared) {
        if (dxgiRes) IDXGIResource_Release(dxgiRes);
        ID3D11Texture2D_Release(tex);
        ID3D11DeviceContext_Release(imCtx);
        ID3D11Device_Release(device);
        return 0;
    }
    IDXGIResource_Release(dxgiRes);

    IDXGIKeyedMutex *mutex = NULL;
    if (useKeyedMutex) {
        ID3D11Texture2D_QueryInterface(tex, &kIID_IDXGIKeyedMutex, (void **)&mutex);
    }

    ID3D11RenderTargetView *rtv = NULL;
    if (FAILED(ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)tex, NULL, &rtv))) {
        if (mutex) IDXGIKeyedMutex_Release(mutex);
        ID3D11Texture2D_Release(tex);
        ID3D11DeviceContext_Release(imCtx);
        ID3D11Device_Release(device);
        return 0;
    }

    NucleusTestProducer *p = (NucleusTestProducer *)HeapAlloc(
        GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(NucleusTestProducer));
    if (!p) {
        ID3D11RenderTargetView_Release(rtv);
        if (mutex) IDXGIKeyedMutex_Release(mutex);
        ID3D11Texture2D_Release(tex);
        ID3D11DeviceContext_Release(imCtx);
        ID3D11Device_Release(device);
        return 0;
    }
    p->device = device;
    p->imCtx = imCtx;
    p->texture = tex;
    p->rtv = rtv;
    p->keyedMutex = mutex;
    p->sharedHandle = shared;
    p->widthPx = (int)widthPx;
    p->heightPx = (int)heightPx;
    int maxDim = widthPx > heightPx ? widthPx : heightPx;
    p->barPixels = (unsigned char *)HeapAlloc(
        GetProcessHeap(), 0, (SIZE_T)NUCLEUS_TEST_BAR_PX * (SIZE_T)maxDim * 4);
    if (p->barPixels) {
        memset(p->barPixels, 0xFF, (SIZE_T)NUCLEUS_TEST_BAR_PX * (SIZE_T)maxDim * 4);
    }
    return (jlong)(uintptr_t)p;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeTestProducerSharedHandle(
    JNIEnv *env, jclass clazz, jlong producer)
{
    (void)env; (void)clazz;
    NucleusTestProducer *p = (NucleusTestProducer *)(uintptr_t)producer;
    return p ? (jlong)(uintptr_t)p->sharedHandle : 0;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeTestProducerFill(
    JNIEnv *env, jclass clazz, jlong producer, jint argb)
{
    (void)env; (void)clazz;
    NucleusTestProducer *p = (NucleusTestProducer *)(uintptr_t)producer;
    if (!p) return;
    if (p->keyedMutex &&
        IDXGIKeyedMutex_AcquireSync(p->keyedMutex, 0, 100) != S_OK) {
        return; /* consumer stuck on the mutex — drop this frame */
    }
    /* Skia samples the adopted texture as premultiplied RGBA. */
    testProducerClear(p, argb);
    /* Producer and ANGLE devices are distinct: flush so the write is
     * visible through the shared resource before the next composite. */
    ID3D11DeviceContext_Flush(p->imCtx);
    if (p->keyedMutex) IDXGIKeyedMutex_ReleaseSync(p->keyedMutex, 0);
}

/* Animated test pattern: [argbBg] background plus a white vertical bar
 * (x follows [tick]) and a white horizontal bar (y follows [tick]) —
 * enough structure for contentScale / filterQuality demos and to make
 * tearing observable. */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeTestProducerDrawPattern(
    JNIEnv *env, jclass clazz, jlong producer, jint tick, jint argbBg)
{
    (void)env; (void)clazz;
    NucleusTestProducer *p = (NucleusTestProducer *)(uintptr_t)producer;
    if (!p || !p->barPixels) return;
    if (p->keyedMutex &&
        IDXGIKeyedMutex_AcquireSync(p->keyedMutex, 0, 100) != S_OK) {
        return; /* consumer stuck on the mutex — drop this frame */
    }
    testProducerClear(p, argbBg);

    /* Bars shrink to the texture on tiny producers: keeps the modulo
     * divisor >= 1 (a 15px-wide texture would otherwise divide by 0)
     * and the UpdateSubresource boxes inside the resource. */
    int barW = NUCLEUS_TEST_BAR_PX < p->widthPx ? NUCLEUS_TEST_BAR_PX : p->widthPx;
    int barH = NUCLEUS_TEST_BAR_PX < p->heightPx ? NUCLEUS_TEST_BAR_PX : p->heightPx;

    int barX = (tick * 2) % (p->widthPx - barW + 1);
    if (barX < 0) barX = 0;
    D3D11_BOX vBox;
    vBox.left = (UINT)barX;  vBox.right  = (UINT)(barX + barW);
    vBox.top = 0;            vBox.bottom = (UINT)p->heightPx;
    vBox.front = 0;          vBox.back   = 1;
    ID3D11DeviceContext_UpdateSubresource(
        p->imCtx, (ID3D11Resource *)p->texture, 0, &vBox,
        p->barPixels, (UINT)(barW * 4), 0);

    int barY = tick % (p->heightPx - barH + 1);
    if (barY < 0) barY = 0;
    D3D11_BOX hBox;
    hBox.left = 0;           hBox.right  = (UINT)p->widthPx;
    hBox.top = (UINT)barY;   hBox.bottom = (UINT)(barY + barH);
    hBox.front = 0;          hBox.back   = 1;
    ID3D11DeviceContext_UpdateSubresource(
        p->imCtx, (ID3D11Resource *)p->texture, 0, &hBox,
        p->barPixels, (UINT)(p->widthPx * 4), 0);

    ID3D11DeviceContext_Flush(p->imCtx);
    if (p->keyedMutex) IDXGIKeyedMutex_ReleaseSync(p->keyedMutex, 0);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoTextureBridge_nativeTestProducerDestroy(
    JNIEnv *env, jclass clazz, jlong producer)
{
    (void)env; (void)clazz;
    NucleusTestProducer *p = (NucleusTestProducer *)(uintptr_t)producer;
    if (!p) return;
    ID3D11RenderTargetView_Release(p->rtv);
    if (p->keyedMutex) IDXGIKeyedMutex_Release(p->keyedMutex);
    ID3D11Texture2D_Release(p->texture);
    ID3D11DeviceContext_Release(p->imCtx);
    ID3D11Device_Release(p->device);
    if (p->barPixels) HeapFree(GetProcessHeap(), 0, p->barPixels);
    HeapFree(GetProcessHeap(), 0, p);
}
