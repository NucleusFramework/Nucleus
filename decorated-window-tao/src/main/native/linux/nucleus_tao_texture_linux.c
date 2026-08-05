/**
 * JNI bridge: external GPU texture import for the TextureView composable
 * (Linux / EGL backend). Compiled into `libnucleus_tao_egl.so` next to
 * `nucleus_tao_egl.c` — the same arrangement as `nucleus_tao_texture.c` inside
 * `nucleus_tao_gl.dll` on Windows and `texture.m` inside
 * `libnucleus_tao_metal.dylib` on macOS, so no new library (and no CI workflow
 * change) is needed.
 *
 * Import path:
 *   producer DMA-BUF (or a ready-made EGLImage) →
 *   eglCreateImageKHR(EGL_LINUX_DMA_BUF_EXT) on the *window's* EGLDisplay →
 *   glEGLImageTargetTexture2DOES onto a fresh GL_TEXTURE_2D →
 *   Kotlin adopts that texture with Skia (`Image.adoptTextureFrom`) and
 *   composites it into the Compose scene (see TextureViewLinux.kt).
 *
 * Why DMA-BUF: it is Linux's shareable GPU buffer — the moral equivalent of
 * the DXGI shared handle on Windows and the IOSurface on macOS. The imported
 * texture *aliases* the producer's memory, so there is no copy anywhere on the
 * path and, unlike both other platforms, no per-frame native call either: the
 * producer's writes are visible to the next Skia draw that samples the
 * texture. Frame signalling stays a pure Compose concern
 * (`markFrameAvailable` → draw-pass invalidation).
 *
 * Colour channels are the driver's business: the FourCC is handed to
 * eglCreateImage, so GL sampling of the resulting texture always yields
 * (R, G, B, A) whatever the buffer's byte order is. Kotlin therefore always
 * describes the adopted texture to Skia as RGBA8 — no per-format swizzle.
 *
 * Planar YUV (a video decoder's native output) goes through the very same
 * entry point, once per plane: the accepted FourCC set includes the
 * single-channel and two-channel plane formats (`R8`, `GR88` / `RG88`), so an
 * NV12 or I420 buffer is imported as two or three independent textures that
 * Kotlin samples through one runtime-effect shader. Importing the *whole*
 * multi-plane image instead would need `GL_TEXTURE_EXTERNAL_OES`, which is an
 * ES-only target no desktop GL driver exposes — Mesa refuses such an image on
 * `GL_TEXTURE_2D` outright.
 *
 * Fencing: the zero-copy contract ("finish your writes before you signal") is
 * the default, but a producer that would rather not block can hand over an
 * acquire fence instead — [nativeWaitFence] turns a `sync_file` fd into an EGL
 * sync object and makes the consumer's context wait on the GPU, so nothing on
 * either side stalls the CPU. Needs EGL_ANDROID_native_fence_sync (Mesa and
 * NVIDIA both ship it); the fence fd is dup-ed, never consumed, because every
 * surface drawing the frame has to wait on its own context.
 *
 * Threading: import / destroy must run with the target window's EGL context
 * current on the calling thread, because they create and delete GL objects
 * that Skia's `DirectContext` for that context will own. On Linux that is the
 * natural state — Compose composition and the draw pass both run inside
 * `ComposeScene.render()`, between the host's `nativeMakeCurrent` and
 * `nativeReleaseCurrent`. [nativeIsAttachmentCurrent] lets the Kotlin side
 * verify it (and bind the context itself on teardown paths).
 *
 * The bundled test producer owns a private GBM device + EGL display + context
 * and renders its pattern with plain `glClear` + `glScissor` (no shaders), so
 * it is a real GPU producer on a device of its own — the Linux twin of
 * `D3D11TestTextureProducer` / `MetalTestTextureProducer`. CPU-writing a GBM
 * buffer instead was not an option: `gbm_bo_map` is unsupported by the NVIDIA
 * driver, which also refuses `GBM_BO_USE_LINEAR`.
 */

#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "../shared/nucleus_tao_egl_binding.h"

#include "nucleus_tao_egl_internal.h"

#define NUCLEUS_TAO_TEX_DEBUG 0
#if NUCLEUS_TAO_TEX_DEBUG
#define DBG(...) fprintf(stderr, "[nucleus_tao_texture] " __VA_ARGS__)
#else
#define DBG(...) ((void)0)
#endif

/* ── EGL / GL types & constants (subset, re-declared like the sibling TU) ── */

typedef void         *EGLDisplay;
typedef void         *EGLConfig;
typedef void         *EGLContext;
typedef void         *EGLSurface;
typedef void         *EGLImageKHR;
typedef void         *EGLSyncKHR;
typedef void         *EGLClientBuffer;
typedef int           EGLBoolean;
typedef int           EGLint;
typedef unsigned int  EGLenum;

typedef unsigned int  GLenum;
typedef unsigned int  GLuint;
typedef int           GLint;
typedef int           GLsizei;
typedef float         GLfloat;

#define EGL_FALSE                            0
#define EGL_TRUE                             1
#define EGL_NONE                             0x3038
#define EGL_NO_CONTEXT                       ((EGLContext) 0)
#define EGL_NO_DISPLAY                       ((EGLDisplay) 0)
#define EGL_NO_SURFACE                       ((EGLSurface) 0)
#define EGL_NO_IMAGE_KHR                     ((EGLImageKHR) 0)
#define EGL_EXTENSIONS                       0x3055
#define EGL_WIDTH                            0x3057
#define EGL_HEIGHT                           0x3056
#define EGL_RED_SIZE                         0x3024
#define EGL_SURFACE_TYPE                     0x3033
#define EGL_PBUFFER_BIT                      0x0001
#define EGL_RENDERABLE_TYPE                  0x3040
#define EGL_OPENGL_BIT                       0x0008
#define EGL_OPENGL_API                       0x30A2
#define EGL_PLATFORM_GBM_KHR                 0x31D7
#define EGL_DRAW                             0x3059
#define EGL_READ                             0x305A
#define EGL_LINUX_DMA_BUF_EXT                0x3270
#define EGL_LINUX_DRM_FOURCC_EXT             0x3271
#define EGL_DMA_BUF_PLANE0_FD_EXT            0x3272
#define EGL_DMA_BUF_PLANE0_OFFSET_EXT        0x3273
#define EGL_DMA_BUF_PLANE0_PITCH_EXT         0x3274
#define EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT   0x3443
#define EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT   0x3444
#define EGL_IMAGE_PRESERVED_KHR              0x30D2
#define EGL_SYNC_NATIVE_FENCE_ANDROID        0x3144
#define EGL_SYNC_NATIVE_FENCE_FD_ANDROID     0x3145
#define EGL_NO_NATIVE_FENCE_FD_ANDROID       (-1)
#define EGL_NO_SYNC_KHR                      ((EGLSyncKHR) 0)

#define GL_NO_ERROR                          0
#define GL_TEXTURE_2D                        0x0DE1
#define GL_TEXTURE_BINDING_2D                0x8069
#define GL_TEXTURE_MIN_FILTER                0x2801
#define GL_TEXTURE_MAG_FILTER                0x2800
#define GL_TEXTURE_WRAP_S                    0x2802
#define GL_TEXTURE_WRAP_T                    0x2803
#define GL_LINEAR                            0x2601
#define GL_CLAMP_TO_EDGE                     0x812F
#define GL_FRAMEBUFFER                       0x8D40
#define GL_COLOR_ATTACHMENT0                 0x8CE0
#define GL_FRAMEBUFFER_COMPLETE              0x8CD5
#define GL_COLOR_BUFFER_BIT                  0x00004000
#define GL_SCISSOR_TEST                      0x0C11

/* DRM format modifier meaning "the buffer layout is implicit" — the value the
 * kernel/Mesa use for "unknown". We then omit the modifier attributes so the
 * driver falls back to its legacy, modifier-less import path. */
#define NUCLEUS_DRM_FORMAT_MOD_INVALID  0x00FFFFFFFFFFFFFFULL

/* Staged failure codes returned by the import entry points (negative so the
 * Kotlin side can log the failing stage). EGL/GL failures carry the driver's
 * error in the low 16 bits: -((stage << 16) | error). */
#define NUCLEUS_TEX_ERR_ARGS         (-1) /* bad size / fd / unsupported FourCC */
#define NUCLEUS_TEX_ERR_NO_CONTEXT   (-2) /* no EGL context current here        */
#define NUCLEUS_TEX_ERR_UNSUPPORTED  (-3) /* EGL_EXT_image_dma_buf_import absent */
#define NUCLEUS_TEX_ERR_MODIFIERS    (-4) /* explicit modifier, no _modifiers ext */
#define NUCLEUS_TEX_ERR_ENTRYPOINTS  (-5) /* eglCreateImageKHR / target-texture  */
#define NUCLEUS_TEX_STAGE_IMAGE        6  /* eglCreateImageKHR failed            */
#define NUCLEUS_TEX_STAGE_TEXTURE      7  /* glEGLImageTargetTexture2DOES failed */

#define NUCLEUS_TEST_BAR_PX 16

/* The planar format the bundled test producer allocates, mirrored from
 * NucleusYuvFormat on the Kotlin side. YV12 is the same buffer with its chroma
 * planes listed the other way round, which is a consumer-side concern. */
#define NUCLEUS_TEST_YUV_I420 0

/* Colour spaces, mirrored from NucleusYuvColorSpace. The producer converts its
 * pattern to Y'CbCr with these; the consumer's shader inverts the very same
 * definition, so a composited frame must come back the colour the caller asked
 * for (what the YUV smoke tests assert). */
#define NUCLEUS_YUV_BT601_LIMITED 0
#define NUCLEUS_YUV_BT601_FULL    1
#define NUCLEUS_YUV_BT709_LIMITED 2
#define NUCLEUS_YUV_BT709_FULL    3

#define NUCLEUS_TEST_MAX_PLANES 3

/* ── Entry points ────────────────────────────────────────────────────────── */

typedef EGLDisplay  (*PFN_eglGetPlatformDisplayEXT)(EGLenum, void *, const EGLint *);
typedef EGLBoolean  (*PFN_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean  (*PFN_eglTerminate)(EGLDisplay);
typedef EGLBoolean  (*PFN_eglBindAPI)(EGLenum);
typedef EGLBoolean  (*PFN_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext  (*PFN_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLBoolean  (*PFN_eglDestroyContext)(EGLDisplay, EGLContext);
typedef EGLBoolean  (*PFN_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLSurface  (*PFN_eglGetCurrentSurface)(EGLint);
typedef EGLint      (*PFN_eglGetError)(void);
typedef const char *(*PFN_eglQueryString)(EGLDisplay, EGLint);
typedef EGLImageKHR (*PFN_eglCreateImageKHR)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *);
typedef EGLBoolean  (*PFN_eglDestroyImageKHR)(EGLDisplay, EGLImageKHR);
typedef EGLSyncKHR  (*PFN_eglCreateSyncKHR)(EGLDisplay, EGLenum, const EGLint *);
typedef EGLBoolean  (*PFN_eglDestroySyncKHR)(EGLDisplay, EGLSyncKHR);
typedef EGLint      (*PFN_eglWaitSyncKHR)(EGLDisplay, EGLSyncKHR, EGLint);
typedef EGLint      (*PFN_eglDupNativeFenceFDANDROID)(EGLDisplay, EGLSyncKHR);

typedef void   (*PFN_glEGLImageTargetTexture2DOES)(GLenum, EGLImageKHR);
typedef void   (*PFN_glGenTextures)(GLsizei, GLuint *);
typedef void   (*PFN_glDeleteTextures)(GLsizei, const GLuint *);
typedef void   (*PFN_glBindTexture)(GLenum, GLuint);
typedef void   (*PFN_glTexParameteri)(GLenum, GLenum, GLint);
typedef void   (*PFN_glGetIntegerv)(GLenum, GLint *);
typedef GLenum (*PFN_glGetError)(void);
typedef void   (*PFN_glGenFramebuffers)(GLsizei, GLuint *);
typedef void   (*PFN_glDeleteFramebuffers)(GLsizei, const GLuint *);
typedef void   (*PFN_glBindFramebuffer)(GLenum, GLuint);
typedef void   (*PFN_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
typedef GLenum (*PFN_glCheckFramebufferStatus)(GLenum);
typedef void   (*PFN_glClearColor)(GLfloat, GLfloat, GLfloat, GLfloat);
typedef void   (*PFN_glClear)(GLenum);
typedef void   (*PFN_glScissor)(GLint, GLint, GLsizei, GLsizei);
typedef void   (*PFN_glEnable)(GLenum);
typedef void   (*PFN_glDisable)(GLenum);
typedef void   (*PFN_glViewport)(GLint, GLint, GLsizei, GLsizei);
typedef void   (*PFN_glFinish)(void);
typedef void   (*PFN_glFlush)(void);

static PFN_eglGetPlatformDisplayEXT     p_eglGetPlatformDisplayEXT = NULL;
static PFN_eglInitialize                p_eglInitialize            = NULL;
static PFN_eglTerminate                 p_eglTerminate             = NULL;
static PFN_eglBindAPI                   p_eglBindAPI               = NULL;
static PFN_eglChooseConfig              p_eglChooseConfig          = NULL;
static PFN_eglCreateContext             p_eglCreateContext         = NULL;
static PFN_eglDestroyContext            p_eglDestroyContext        = NULL;
static PFN_eglMakeCurrent               p_eglMakeCurrent           = NULL;
static PFN_eglGetCurrentSurface         p_eglGetCurrentSurface     = NULL;
static PFN_eglGetError                  p_eglGetError              = NULL;
static PFN_eglQueryString               p_eglQueryString           = NULL;
static PFN_eglCreateImageKHR            p_eglCreateImageKHR        = NULL;
static PFN_eglDestroyImageKHR           p_eglDestroyImageKHR       = NULL;
static PFN_eglCreateSyncKHR             p_eglCreateSyncKHR         = NULL;
static PFN_eglDestroySyncKHR            p_eglDestroySyncKHR        = NULL;
static PFN_eglWaitSyncKHR               p_eglWaitSyncKHR           = NULL;
static PFN_eglDupNativeFenceFDANDROID   p_eglDupNativeFenceFDANDROID = NULL;

static PFN_glEGLImageTargetTexture2DOES p_glEGLImageTargetTexture2DOES = NULL;
static PFN_glGenTextures                p_glGenTextures            = NULL;
static PFN_glDeleteTextures             p_glDeleteTextures         = NULL;
static PFN_glBindTexture                p_glBindTexture            = NULL;
static PFN_glTexParameteri              p_glTexParameteri          = NULL;
static PFN_glGetIntegerv                p_glGetIntegerv            = NULL;
static PFN_glGetError                   p_glGetError               = NULL;
static PFN_glGenFramebuffers            p_glGenFramebuffers        = NULL;
static PFN_glDeleteFramebuffers         p_glDeleteFramebuffers     = NULL;
static PFN_glBindFramebuffer            p_glBindFramebuffer        = NULL;
static PFN_glFramebufferTexture2D       p_glFramebufferTexture2D   = NULL;
static PFN_glCheckFramebufferStatus     p_glCheckFramebufferStatus = NULL;
static PFN_glClearColor                 p_glClearColor             = NULL;
static PFN_glClear                      p_glClear                  = NULL;
static PFN_glScissor                    p_glScissor                = NULL;
static PFN_glEnable                     p_glEnable                 = NULL;
static PFN_glDisable                    p_glDisable                = NULL;
static PFN_glViewport                   p_glViewport               = NULL;
static PFN_glFinish                     p_glFinish                 = NULL;
static PFN_glFlush                      p_glFlush                  = NULL;

/* GBM — dlopen-ed, and only for the bundled test producer. Real applications
 * bring their own DMA-BUF; nothing on the import path needs libgbm. */
typedef void     *(*PFN_gbm_create_device)(int);
typedef void      (*PFN_gbm_device_destroy)(void *);
typedef void     *(*PFN_gbm_bo_create)(void *, uint32_t, uint32_t, uint32_t, uint32_t);
typedef void      (*PFN_gbm_bo_destroy)(void *);
typedef int       (*PFN_gbm_bo_get_fd)(void *);
typedef uint32_t  (*PFN_gbm_bo_get_stride)(void *);
typedef uint64_t  (*PFN_gbm_bo_get_modifier)(void *);
typedef int       (*PFN_gbm_bo_get_plane_count)(void *);
typedef int       (*PFN_gbm_bo_get_fd_for_plane)(void *, int);
typedef uint32_t  (*PFN_gbm_bo_get_offset)(void *, int);
typedef uint32_t  (*PFN_gbm_bo_get_stride_for_plane)(void *, int);

static PFN_gbm_create_device   p_gbm_create_device   = NULL;
static PFN_gbm_device_destroy  p_gbm_device_destroy  = NULL;
static PFN_gbm_bo_create       p_gbm_bo_create       = NULL;
static PFN_gbm_bo_destroy      p_gbm_bo_destroy      = NULL;
static PFN_gbm_bo_get_fd       p_gbm_bo_get_fd       = NULL;
static PFN_gbm_bo_get_stride   p_gbm_bo_get_stride   = NULL;
static PFN_gbm_bo_get_modifier p_gbm_bo_get_modifier = NULL;
/* Plane accessors: only the planar test producer needs them, and only libgbm
 * 17+ has them — a driver stack without them just cannot host that producer. */
static PFN_gbm_bo_get_plane_count      p_gbm_bo_get_plane_count      = NULL;
static PFN_gbm_bo_get_fd_for_plane     p_gbm_bo_get_fd_for_plane     = NULL;
static PFN_gbm_bo_get_offset           p_gbm_bo_get_offset           = NULL;
static PFN_gbm_bo_get_stride_for_plane p_gbm_bo_get_stride_for_plane = NULL;

#define GBM_BO_USE_RENDERING (1u << 2)

/* Resolution runs exactly once per table, published through `pthread_once` —
 * which is also the memory barrier that keeps a second thread from observing
 * "resolved" before the pointer stores above are visible to it. Imports run on
 * the event-loop thread while the test producer is documented as callable from
 * its own thread, so the tables really are reached from two threads. */
static pthread_once_t g_resolve_once     = PTHREAD_ONCE_INIT;
static pthread_once_t g_resolve_gbm_once = PTHREAD_ONCE_INIT;
static int g_resolved      = 0;
static int g_gbm_resolved  = 0;

static int resolve_entry_points_locked(void) {
    if (!nucleus_tao_egl_ensure_libs()) return 0;

    /* Core EGL comes from libEGL directly: eglGetProcAddress is only
     * guaranteed to return core entry points on drivers advertising
     * EGL_KHR_get_all_proc_addresses. Extensions and GL go through the shared
     * resolver (eglGetProcAddress + dlsym(libGL)) — the very same loader Skia
     * was handed, so anything missing here is missing for Skia too. */
    void *libegl = dlopen("libEGL.so.1", RTLD_LAZY | RTLD_GLOBAL);
    if (!libegl) libegl = dlopen("libEGL.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!libegl) {
        DBG("dlopen libEGL failed: %s\n", dlerror());
        return 0;
    }
#define LOAD_EGL(sym) p_##sym = (PFN_##sym) dlsym(libegl, #sym)
    LOAD_EGL(eglInitialize);
    LOAD_EGL(eglTerminate);
    LOAD_EGL(eglBindAPI);
    LOAD_EGL(eglChooseConfig);
    LOAD_EGL(eglCreateContext);
    LOAD_EGL(eglDestroyContext);
    LOAD_EGL(eglMakeCurrent);
    LOAD_EGL(eglGetCurrentSurface);
    LOAD_EGL(eglGetError);
    LOAD_EGL(eglQueryString);
#undef LOAD_EGL

#define LOAD_PROC(sym) p_##sym = (PFN_##sym) nucleus_tao_egl_proc_address(#sym)
    LOAD_PROC(eglGetPlatformDisplayEXT);
    LOAD_PROC(eglCreateImageKHR);
    LOAD_PROC(eglDestroyImageKHR);
    /* Fence sync: optional. A driver without EGL_ANDROID_native_fence_sync
     * simply leaves producers on the "finish before you signal" contract. */
    LOAD_PROC(eglCreateSyncKHR);
    LOAD_PROC(eglDestroySyncKHR);
    LOAD_PROC(eglWaitSyncKHR);
    LOAD_PROC(eglDupNativeFenceFDANDROID);
    LOAD_PROC(glEGLImageTargetTexture2DOES);
    LOAD_PROC(glGenTextures);
    LOAD_PROC(glDeleteTextures);
    LOAD_PROC(glBindTexture);
    LOAD_PROC(glTexParameteri);
    LOAD_PROC(glGetIntegerv);
    LOAD_PROC(glGetError);
    LOAD_PROC(glGenFramebuffers);
    LOAD_PROC(glDeleteFramebuffers);
    LOAD_PROC(glBindFramebuffer);
    LOAD_PROC(glFramebufferTexture2D);
    LOAD_PROC(glCheckFramebufferStatus);
    LOAD_PROC(glClearColor);
    LOAD_PROC(glClear);
    LOAD_PROC(glScissor);
    LOAD_PROC(glEnable);
    LOAD_PROC(glDisable);
    LOAD_PROC(glViewport);
    LOAD_PROC(glFinish);
    LOAD_PROC(glFlush);
#undef LOAD_PROC

    if (!p_eglQueryString || !p_eglCreateImageKHR || !p_eglDestroyImageKHR ||
        !p_glEGLImageTargetTexture2DOES || !p_glGenTextures || !p_glDeleteTextures ||
        !p_glBindTexture || !p_glTexParameteri || !p_glGetIntegerv || !p_glGetError) {
        DBG("missing import entry points\n");
        return 0;
    }
    return 1;
}

static void resolve_entry_points_once(void) {
    g_resolved = resolve_entry_points_locked();
}

static int resolve_entry_points(void) {
    pthread_once(&g_resolve_once, resolve_entry_points_once);
    return g_resolved;
}

static int resolve_gbm_locked(void) {
    void *libgbm = dlopen("libgbm.so.1", RTLD_LAZY | RTLD_GLOBAL);
    if (!libgbm) libgbm = dlopen("libgbm.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!libgbm) {
        DBG("dlopen libgbm failed: %s\n", dlerror());
        return 0;
    }
#define LOAD_GBM(sym) p_##sym = (PFN_##sym) dlsym(libgbm, #sym)
    LOAD_GBM(gbm_create_device);
    LOAD_GBM(gbm_device_destroy);
    LOAD_GBM(gbm_bo_create);
    LOAD_GBM(gbm_bo_destroy);
    LOAD_GBM(gbm_bo_get_fd);
    LOAD_GBM(gbm_bo_get_stride);
    LOAD_GBM(gbm_bo_get_modifier);
    LOAD_GBM(gbm_bo_get_plane_count);
    LOAD_GBM(gbm_bo_get_fd_for_plane);
    LOAD_GBM(gbm_bo_get_offset);
    LOAD_GBM(gbm_bo_get_stride_for_plane);
#undef LOAD_GBM
    if (!p_gbm_create_device || !p_gbm_bo_create || !p_gbm_bo_get_fd ||
        !p_gbm_bo_get_stride) {
        return 0;
    }
    return 1;
}

static void resolve_gbm_once(void) {
    g_gbm_resolved = resolve_gbm_locked();
}

static int resolve_gbm(void) {
    pthread_once(&g_resolve_gbm_once, resolve_gbm_once);
    return g_gbm_resolved;
}

/* ── Import ─────────────────────────────────────────────────────────────── */

typedef struct {
    EGLDisplay  display;
    EGLImageKHR image;
    /* 0 when the caller handed us a ready-made EGLImage it keeps owning. */
    int         owns_image;
    GLuint      texture;
    int         widthPx;
    int         heightPx;
} NucleusTaoImportedTexture;

#define IMPORT_OF(ptr) ((NucleusTaoImportedTexture *) (uintptr_t) (ptr))

static jlong staged_error(int stage, int code) {
    return -(jlong) (((unsigned) stage << 16) | ((unsigned) code & 0xFFFFu));
}

static int has_extension(EGLDisplay display, const char *name) {
    const char *exts = p_eglQueryString(display, EGL_EXTENSIONS);
    if (!exts) return 0;
    /* Substring search is enough here: neither name is a prefix of a *different*
     * extension, and "..._import" being a prefix of "..._import_modifiers" is
     * exactly the containment we want. */
    return strstr(exts, name) != NULL;
}

/**
 * Bytes per pixel of an accepted **single-plane** FourCC, or 0 when the format
 * is not one we can import. Anything outside this set (10-bit, tri-channel
 * packings, a multi-plane FourCC handed over whole) would import on some drivers
 * and then sample as garbage, so it is rejected up front rather than silently
 * mis-rendered.
 *
 * The one-channel entry is a *plane* of a planar YUV buffer: I420 and YV12 are
 * imported as three R8 planes, one per component. The value doubles as the stride
 * validator: a stride below `width * bpp` cannot describe the plane.
 */
static int fourcc_bytes_per_pixel(int fourcc) {
    switch ((unsigned) fourcc) {
        case 0x34325241u: /* AR24 — DRM_FORMAT_ARGB8888 */
        case 0x34325258u: /* XR24 — DRM_FORMAT_XRGB8888 */
        case 0x34324241u: /* AB24 — DRM_FORMAT_ABGR8888 */
        case 0x34324258u: /* XB24 — DRM_FORMAT_XBGR8888 */
        case 0x34324152u: /* RA24 — DRM_FORMAT_RGBA8888 */
        case 0x34325852u: /* RX24 — DRM_FORMAT_RGBX8888 */
        case 0x34324142u: /* BA24 — DRM_FORMAT_BGRA8888 */
        case 0x34325842u: /* BX24 — DRM_FORMAT_BGRX8888 */
            return 4;
        case 0x20203852u: /* R8   — DRM_FORMAT_R8, a luma or chroma plane */
            return 1;
        default:
            return 0;
    }
}

/**
 * Wraps one DMA-BUF plane as an EGLImage on [display]. The fd is only read
 * here — EGL takes its own reference to the underlying buffer, so the caller
 * stays the owner and may close it right after this returns.
 */
static EGLImageKHR create_dmabuf_image(
        EGLDisplay display, int fd, int fourcc, int widthPx, int heightPx,
        int stride, int offset, uint64_t modifier) {
    const int explicit_modifier = modifier != NUCLEUS_DRM_FORMAT_MOD_INVALID;
    EGLint attrs[19];
    int i = 0;
    attrs[i++] = EGL_WIDTH;                      attrs[i++] = widthPx;
    attrs[i++] = EGL_HEIGHT;                     attrs[i++] = heightPx;
    attrs[i++] = EGL_LINUX_DRM_FOURCC_EXT;       attrs[i++] = fourcc;
    attrs[i++] = EGL_DMA_BUF_PLANE0_FD_EXT;      attrs[i++] = fd;
    attrs[i++] = EGL_DMA_BUF_PLANE0_OFFSET_EXT;  attrs[i++] = offset;
    attrs[i++] = EGL_DMA_BUF_PLANE0_PITCH_EXT;   attrs[i++] = stride;
    if (explicit_modifier) {
        attrs[i++] = EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT;
        attrs[i++] = (EGLint) (uint32_t) (modifier & 0xFFFFFFFFULL);
        attrs[i++] = EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT;
        attrs[i++] = (EGLint) (uint32_t) (modifier >> 32);
    }
    /* The producer's pixels must survive the import — we are a consumer. */
    attrs[i++] = EGL_IMAGE_PRESERVED_KHR;        attrs[i++] = EGL_TRUE;
    attrs[i++] = EGL_NONE;
    return p_eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT,
                               (EGLClientBuffer) NULL, attrs);
}

/**
 * Creates a GL_TEXTURE_2D whose storage *is* [image]. The previous binding of
 * the active texture unit is restored so Skia's GL state cache stays valid —
 * cheaper and less invasive than the `DirectContext.resetGLAll()` the Windows
 * import path would need. Returns 0 (with the GL error in [gl_error]) on
 * failure.
 */
static GLuint texture_from_image(EGLImageKHR image, GLenum *gl_error) {
    GLint previous = 0;
    p_glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
    GLuint texture = 0;
    p_glGenTextures(1, &texture);
    if (texture == 0) {
        *gl_error = p_glGetError();
        return 0;
    }
    p_glBindTexture(GL_TEXTURE_2D, texture);
    /* Sane defaults only: Skia sets its own sampler state on every draw that
     * samples the adopted texture. */
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    while (p_glGetError() != GL_NO_ERROR) { /* drain pre-existing errors */ }
    p_glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
    GLenum err = p_glGetError();
    p_glBindTexture(GL_TEXTURE_2D, (GLuint) previous);
    if (err != GL_NO_ERROR) {
        p_glDeleteTextures(1, &texture);
        *gl_error = err;
        return 0;
    }
    return texture;
}

static jlong wrap_import(
        EGLDisplay display, EGLImageKHR image, int owns_image, GLuint texture,
        int widthPx, int heightPx) {
    NucleusTaoImportedTexture *t = (NucleusTaoImportedTexture *)
        calloc(1, sizeof(NucleusTaoImportedTexture));
    if (t == NULL) return 0;
    t->display    = display;
    t->image      = image;
    t->owns_image = owns_image;
    t->texture    = texture;
    t->widthPx    = widthPx;
    t->heightPx   = heightPx;
    return (jlong) (uintptr_t) t;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeImportDmaBuf(
        JNIEnv *env, jclass clazz, jint fd, jint fourcc, jint widthPx, jint heightPx,
        jint stride, jint offset, jlong modifier) {
    (void) env; (void) clazz;
    if (fd < 0 || widthPx < 1 || heightPx < 1 || stride < 1 || offset < 0) {
        return NUCLEUS_TEX_ERR_ARGS;
    }
    const int bpp = fourcc_bytes_per_pixel(fourcc);
    if (bpp == 0) return NUCLEUS_TEX_ERR_ARGS;
    /* A stride below the row size cannot describe the plane. Drivers often
     * accept such an image and then sample past the end of it — a caller who
     * passed the stride in pixels instead of bytes would get garbage instead of
     * a clean failure. */
    if ((long long) stride < (long long) widthPx * bpp) return NUCLEUS_TEX_ERR_ARGS;
    if (!resolve_entry_points()) return NUCLEUS_TEX_ERR_ENTRYPOINTS;

    EGLDisplay display = (EGLDisplay) nucleus_tao_egl_current_display();
    if (display == EGL_NO_DISPLAY || nucleus_tao_egl_current_context() == NULL) {
        return NUCLEUS_TEX_ERR_NO_CONTEXT;
    }
    if (!has_extension(display, "EGL_EXT_image_dma_buf_import")) {
        return NUCLEUS_TEX_ERR_UNSUPPORTED;
    }
    const uint64_t mod = (uint64_t) modifier;
    if (mod != NUCLEUS_DRM_FORMAT_MOD_INVALID &&
        !has_extension(display, "EGL_EXT_image_dma_buf_import_modifiers")) {
        return NUCLEUS_TEX_ERR_MODIFIERS;
    }

    EGLImageKHR image = create_dmabuf_image(
        display, fd, fourcc, widthPx, heightPx, stride, offset, mod);
    if (image == EGL_NO_IMAGE_KHR) {
        EGLint err = p_eglGetError ? p_eglGetError() : 0;
        DBG("eglCreateImageKHR failed: 0x%x\n", err);
        return staged_error(NUCLEUS_TEX_STAGE_IMAGE, err);
    }
    GLenum gl_error = GL_NO_ERROR;
    GLuint texture = texture_from_image(image, &gl_error);
    if (texture == 0) {
        p_eglDestroyImageKHR(display, image);
        DBG("glEGLImageTargetTexture2DOES failed: 0x%x\n", gl_error);
        return staged_error(NUCLEUS_TEX_STAGE_TEXTURE, (int) gl_error);
    }
    jlong handle = wrap_import(display, image, /*owns_image=*/1, texture, widthPx, heightPx);
    if (handle == 0) {
        p_glDeleteTextures(1, &texture);
        p_eglDestroyImageKHR(display, image);
    }
    return handle;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeImportEglImage(
        JNIEnv *env, jclass clazz, jlong eglImage, jint widthPx, jint heightPx) {
    (void) env; (void) clazz;
    if (eglImage == 0 || widthPx < 1 || heightPx < 1) return NUCLEUS_TEX_ERR_ARGS;
    if (!resolve_entry_points()) return NUCLEUS_TEX_ERR_ENTRYPOINTS;

    EGLDisplay display = (EGLDisplay) nucleus_tao_egl_current_display();
    if (display == EGL_NO_DISPLAY || nucleus_tao_egl_current_context() == NULL) {
        return NUCLEUS_TEX_ERR_NO_CONTEXT;
    }
    EGLImageKHR image = (EGLImageKHR) (uintptr_t) eglImage;
    GLenum gl_error = GL_NO_ERROR;
    GLuint texture = texture_from_image(image, &gl_error);
    if (texture == 0) {
        DBG("glEGLImageTargetTexture2DOES(external image) failed: 0x%x\n", gl_error);
        return staged_error(NUCLEUS_TEX_STAGE_TEXTURE, (int) gl_error);
    }
    /* owns_image = 0: the producer created the EGLImage and keeps it. */
    jlong handle = wrap_import(display, image, /*owns_image=*/0, texture, widthPx, heightPx);
    if (handle == 0) p_glDeleteTextures(1, &texture);
    return handle;
}

/* GL texture id backing the import — fed to Skia's `BackendTexture.makeGL`. */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeGlTextureId(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle <= 0) return 0;
    return (jint) IMPORT_OF(handle)->texture;
}

/**
 * Releases the import. [deleteTexture] must be true only when Skia never
 * adopted the texture (`Image.adoptTextureFrom` transfers ownership and Skia
 * deletes it with the Image). Requires the importing EGL context to be current
 * whenever a GL delete is asked for.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle, jboolean deleteTexture) {
    (void) env; (void) clazz;
    if (handle <= 0) return;
    NucleusTaoImportedTexture *t = IMPORT_OF(handle);
    if (deleteTexture && t->texture != 0 && p_glDeleteTextures) {
        p_glDeleteTextures(1, &t->texture);
    }
    if (t->owns_image && t->image != EGL_NO_IMAGE_KHR && p_eglDestroyImageKHR) {
        p_eglDestroyImageKHR(t->display, t->image);
    }
    free(t);
}

/**
 * Whether the EGL context of [attachment] (an `EglAttachment` from
 * `nativeAttachX11` / `nativeAttachWayland`) is current on the calling thread.
 * The Kotlin side calls this before importing: on the normal path composition
 * already runs with the host context bound, and on teardown paths it binds the
 * context itself and re-checks.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeIsAttachmentCurrent(
        JNIEnv *env, jclass clazz, jlong attachment) {
    (void) env; (void) clazz;
    if (attachment == 0) return JNI_FALSE;
    if (!resolve_entry_points()) return JNI_FALSE;
    void *context = nucleus_tao_egl_attachment_context((long long) attachment);
    if (context == NULL) return JNI_FALSE;
    return nucleus_tao_egl_current_context() == context ? JNI_TRUE : JNI_FALSE;
}

/* ── Binding save / restore ──────────────────────────────────────────────
 *
 * The Kotlin side binds a surface's EGL context on teardown paths that run
 * outside that surface's render pass — and those paths can be *inside another*
 * surface's render pass (a popup's `TextureView` is disposed while the parent
 * window's scene is mid-render, with the window's context current). Unbinding
 * afterwards would leave that thread with no context, and the rest of the host
 * frame would issue GL against nothing: the Linux twin of the ANGLE
 * surface-restore bug this feature already fixed on Windows.
 *
 * The bookkeeping (one slot, nesting refused, consumed once) lives in the shared
 * header; what stays here is reading this platform's current binding and putting
 * it back. Per thread, because the test producer really does reach this bridge
 * from its own thread. */

static __thread NucleusTaoEglBindingSlot g_displaced;

/**
 * Snapshots the EGL binding current on this thread so [nativeRestoreBinding]
 * can put it back. Returns false when a snapshot is already outstanding on this
 * thread (nesting), in which case the caller must not rebind.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeSaveCurrentBinding(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (!resolve_entry_points()) return JNI_FALSE;
    return nucleus_tao_egl_binding_save(
        &g_displaced,
        nucleus_tao_egl_current_display(),
        nucleus_tao_egl_current_context(),
        p_eglGetCurrentSurface ? p_eglGetCurrentSurface(EGL_DRAW) : EGL_NO_SURFACE,
        p_eglGetCurrentSurface ? p_eglGetCurrentSurface(EGL_READ) : EGL_NO_SURFACE)
        ? JNI_TRUE : JNI_FALSE;
}

/**
 * Restores the binding [nativeSaveCurrentBinding] snapshotted. Returns false
 * when nothing was current at that point — the caller then unbinds through
 * `NativeTaoEglBridge.nativeReleaseCurrent`, which knows a display to do it
 * with (`eglMakeCurrent` needs one even to unbind).
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeRestoreBinding(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    void *display, *context, *draw, *read;
    if (!nucleus_tao_egl_binding_take(&g_displaced, &display, &context, &draw, &read)) {
        return JNI_FALSE;
    }
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return JNI_FALSE;
    return p_eglMakeCurrent((EGLDisplay) display, (EGLSurface) draw,
                            (EGLSurface) read, (EGLContext) context) == EGL_TRUE
        ? JNI_TRUE : JNI_FALSE;
}

/** Whether the currently bound EGL display can import DMA-BUFs at all. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeIsDmaBufImportSupported(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (!resolve_entry_points()) return JNI_FALSE;
    EGLDisplay display = (EGLDisplay) nucleus_tao_egl_current_display();
    if (display == EGL_NO_DISPLAY) return JNI_FALSE;
    return has_extension(display, "EGL_EXT_image_dma_buf_import") ? JNI_TRUE : JNI_FALSE;
}

/* ── Acquire fences ──────────────────────────────────────────────────────
 *
 * The default contract is "finish your writes before you signal the frame",
 * which costs the producer a `glFinish`. A producer that would rather not block
 * its own thread can pass a `sync_file` fd instead — anything a GPU API hands
 * out as a fence (`eglDupNativeFenceFDANDROID`, `VK_KHR_external_fence_fd`, a
 * V4L2 / VA-API out-fence) — and the *consumer's* GPU waits on it.
 *
 * The wait has to be issued on the context that will sample the texture, and a
 * frame can be composited by more than one surface (a window and a tray panel
 * each own a context), so the fd is dup-ed here rather than consumed: EGL takes
 * ownership of the dup and closes it with the sync object, while the caller's fd
 * stays valid for the next surface — and for its own bookkeeping. */

/** Whether the currently bound EGL display can wait on a native fence fd. */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeIsNativeFenceSupported(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (!resolve_entry_points()) return JNI_FALSE;
    if (!p_eglCreateSyncKHR || !p_eglDestroySyncKHR || !p_eglWaitSyncKHR) return JNI_FALSE;
    EGLDisplay display = (EGLDisplay) nucleus_tao_egl_current_display();
    if (display == EGL_NO_DISPLAY) return JNI_FALSE;
    return has_extension(display, "EGL_ANDROID_native_fence_sync") ? JNI_TRUE : JNI_FALSE;
}

/**
 * Makes the EGL context current on this thread wait for [fenceFd] to signal
 * before executing any command issued after this call — a GPU-side wait, so
 * neither thread blocks. Returns false when the driver has no native fence sync,
 * no context is current, or the fd is not a fence; the caller then falls back to
 * drawing straight away (the producer's own "finish before you signal" contract
 * is what keeps that safe).
 *
 * [fenceFd] stays the caller's: only a dup of it is handed to EGL.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeWaitFence(
        JNIEnv *env, jclass clazz, jint fenceFd) {
    (void) env; (void) clazz;
    if (fenceFd < 0) return JNI_FALSE;
    if (!resolve_entry_points()) return JNI_FALSE;
    if (!p_eglCreateSyncKHR || !p_eglDestroySyncKHR || !p_eglWaitSyncKHR) return JNI_FALSE;
    EGLDisplay display = (EGLDisplay) nucleus_tao_egl_current_display();
    if (display == EGL_NO_DISPLAY || nucleus_tao_egl_current_context() == NULL) return JNI_FALSE;

    /* EGL closes the fd it is given when the sync object dies, whether or not
     * creation succeeded — hence the dup, and hence closing it ourselves only
     * on the path where creation refused it. */
    int dup_fd = fcntl(fenceFd, F_DUPFD_CLOEXEC, 0);
    if (dup_fd < 0) return JNI_FALSE;
    const EGLint attrs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, dup_fd, EGL_NONE };
    EGLSyncKHR sync = p_eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        DBG("eglCreateSyncKHR(native fence) failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        close(dup_fd);
        return JNI_FALSE;
    }
    const EGLBoolean waited = p_eglWaitSyncKHR(display, sync, 0) == EGL_TRUE;
    p_eglDestroySyncKHR(display, sync);
    return waited ? JNI_TRUE : JNI_FALSE;
}

/**
 * Closes a fence fd the Kotlin side took ownership of (the acquire fence a
 * controller holds until the next frame replaces it). Plain `close(2)`, exposed
 * here because the JDK offers no way to close a bare descriptor.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeCloseFenceFd(
        JNIEnv *env, jclass clazz, jint fenceFd) {
    (void) env; (void) clazz;
    if (fenceFd >= 0) close(fenceFd);
}

/* ================================================================== */
/*  GBM + EGL test producer (demos / smoke tests)                      */
/* ================================================================== */

/**
 * One render target of the producer: a plane of its GBM buffer, imported on the
 * producer's own EGL display and attached to an FBO. A packed RGB producer has
 * exactly one; an NV12 one has two (R8 luma + two-channel chroma), an I420 one
 * three (R8 each).
 */
typedef struct {
    /* Exported descriptor for this plane — owned here, closed on destroy. */
    int         fd;
    int         offset;
    int         stride;
    uint64_t    modifier;
    int         widthPx;
    int         heightPx;
    int         fourcc;
    EGLImageKHR image;
    GLuint      texture;
    GLuint      fbo;
} NucleusTaoTestPlane;

typedef struct {
    int         drmFd;
    void       *gbmDevice;
    void       *bo;
    int         planeCount;
    NucleusTaoTestPlane planes[NUCLEUS_TEST_MAX_PLANES];
    int         widthPx;
    int         heightPx;
    /* < 0 for a packed RGB buffer; otherwise the NucleusYuvColorSpace the
     * pattern is converted to before it is written to the planes. */
    int         yuvColorSpace;
    /* Private EGL display + context: the DMA-BUF is the only thing shared with
     * the consumer, exactly like a real producer. */
    EGLDisplay  display;
    EGLContext  context;
    /* Whatever was current on the calling thread when the producer bound its
     * own context, restored on release — a producer driven from the thread that
     * also renders the Compose scene must not steal the host's context. Only
     * ever written between a bind/release pair, which the Kotlin wrapper's lock
     * keeps non-reentrant. */
    EGLDisplay  savedDisplay;
    EGLContext  savedContext;
    EGLSurface  savedDraw;
    EGLSurface  savedRead;
} NucleusTaoLinuxTestProducer;

#define PRODUCER_OF(ptr) ((NucleusTaoLinuxTestProducer *) (uintptr_t) (ptr))

/** Opens the first usable render node and wraps it in a GBM device. */
static int open_gbm_device(int *out_fd, void **out_device) {
    for (int minor = 128; minor < 136; minor++) {
        char path[32];
        snprintf(path, sizeof(path), "/dev/dri/renderD%d", minor);
        int fd = open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) continue;
        void *device = p_gbm_create_device(fd);
        if (device != NULL) {
            *out_fd = fd;
            *out_device = device;
            return 1;
        }
        close(fd);
    }
    DBG("no usable /dev/dri/renderD* node\n");
    return 0;
}

/**
 * Creates an EGL display on [gbmDevice] plus a surfaceless context
 * (EGL_KHR_surfaceless_context, universal on Mesa and NVIDIA) — enough to
 * render into an FBO whose colour attachment is a DMA-BUF.
 */
static int create_gbm_egl_context(
        void *gbmDevice, EGLDisplay *out_display, EGLContext *out_context) {
    if (!p_eglGetPlatformDisplayEXT || !p_eglInitialize || !p_eglChooseConfig ||
        !p_eglCreateContext || !p_eglMakeCurrent || !p_eglBindAPI) {
        return 0;
    }
    EGLDisplay display = p_eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, gbmDevice, NULL);
    if (display == EGL_NO_DISPLAY) {
        DBG("eglGetPlatformDisplayEXT(GBM) failed\n");
        return 0;
    }
    EGLint major = 0, minor = 0;
    if (!p_eglInitialize(display, &major, &minor)) {
        DBG("eglInitialize(GBM) failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        if (p_eglTerminate) p_eglTerminate(display);
        return 0;
    }
    /* Desktop GL, like the window path: the FBO + scissor clears used to draw
     * the pattern exist in both APIs, and matching the host keeps the driver on
     * one code path. */
    p_eglBindAPI(EGL_OPENGL_API);
    const EGLint config_attrs[] = {
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE,        8,
        EGL_NONE
    };
    EGLConfig config = NULL;
    EGLint count = 0;
    if (!p_eglChooseConfig(display, config_attrs, &config, 1, &count) || count < 1) {
        DBG("eglChooseConfig(GBM) returned no config\n");
        if (p_eglTerminate) p_eglTerminate(display);
        return 0;
    }
    EGLContext context = p_eglCreateContext(display, config, EGL_NO_CONTEXT, NULL);
    if (context == EGL_NO_CONTEXT) {
        DBG("eglCreateContext(GBM) failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        if (p_eglTerminate) p_eglTerminate(display);
        return 0;
    }
    *out_display = display;
    *out_context = context;
    return 1;
}

/**
 * Binds the producer's context on the calling thread. Producers are driven from
 * a background dispatcher whose thread may differ from call to call, and an EGL
 * context can only be current on one thread at a time — so every entry point
 * binds and releases around its work.
 *
 * Whatever was already current here is saved and restored by
 * [producer_release]: a producer called from the thread that renders the
 * Compose scene (a main-thread frame callback, or a headless test driving both
 * sides) would otherwise leave that thread with no context, and the host's next
 * Skia call would run against nothing.
 */
static int producer_bind(NucleusTaoLinuxTestProducer *p) {
    p->savedDisplay = (EGLDisplay) nucleus_tao_egl_current_display();
    p->savedContext = (EGLContext) nucleus_tao_egl_current_context();
    p->savedDraw = p_eglGetCurrentSurface ? p_eglGetCurrentSurface(EGL_DRAW) : EGL_NO_SURFACE;
    p->savedRead = p_eglGetCurrentSurface ? p_eglGetCurrentSurface(EGL_READ) : EGL_NO_SURFACE;
    return p_eglMakeCurrent(p->display, EGL_NO_SURFACE, EGL_NO_SURFACE, p->context)
        == EGL_TRUE;
}

static void producer_release(NucleusTaoLinuxTestProducer *p) {
    if (p->savedContext != EGL_NO_CONTEXT && p->savedDisplay != EGL_NO_DISPLAY) {
        p_eglMakeCurrent(p->savedDisplay, p->savedDraw, p->savedRead, p->savedContext);
    } else {
        p_eglMakeCurrent(p->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    p->savedDisplay = EGL_NO_DISPLAY;
    p->savedContext = EGL_NO_CONTEXT;
}

/** Clears the bound FBO to [argb], premultiplied as Skia samples it. */
static void producer_clear(jint argb) {
    const GLfloat a = (GLfloat) ((argb >> 24) & 0xFF) / 255.0f;
    p_glClearColor(
        a * (GLfloat) ((argb >> 16) & 0xFF) / 255.0f,
        a * (GLfloat) ((argb >>  8) & 0xFF) / 255.0f,
        a * (GLfloat) ( argb        & 0xFF) / 255.0f,
        a);
    p_glClear(GL_COLOR_BUFFER_BIT);
}

/**
 * Y'CbCr texture values (each 0..1, as a plane sampler returns them) of an ARGB
 * colour in [colorSpace] — the exact inverse of the matrix the consumer's shader
 * applies, derived from the same Kr/Kb rather than copied from a table, so the
 * two cannot drift apart. A frame written here therefore composites back as the
 * colour asked for, which is what the YUV smoke tests assert.
 */
static void argb_to_yuv(int colorSpace, jint argb, float *y, float *u, float *v) {
    const float r = (float) ((argb >> 16) & 0xFF) / 255.0f;
    const float g = (float) ((argb >>  8) & 0xFF) / 255.0f;
    const float b = (float) ( argb        & 0xFF) / 255.0f;
    const int bt709 = colorSpace == NUCLEUS_YUV_BT709_LIMITED ||
                      colorSpace == NUCLEUS_YUV_BT709_FULL;
    const int limited = colorSpace == NUCLEUS_YUV_BT601_LIMITED ||
                        colorSpace == NUCLEUS_YUV_BT709_LIMITED;
    const float kr = bt709 ? 0.2126f : 0.299f;
    const float kb = bt709 ? 0.0722f : 0.114f;
    const float luma = kr * r + (1.0f - kr - kb) * g + kb * b;
    const float cb = (b - luma) / (2.0f * (1.0f - kb));
    const float cr = (r - luma) / (2.0f * (1.0f - kr));
    /* 128, not 127.5: the neutral chroma sample is the same in both ranges. */
    const float neutral = 128.0f / 255.0f;
    if (limited) {
        *y = (16.0f + 219.0f * luma) / 255.0f;
        *u = neutral + cb * (224.0f / 255.0f);
        *v = neutral + cr * (224.0f / 255.0f);
    } else {
        *y = luma;
        *u = neutral + cb;
        *v = neutral + cr;
    }
}

/**
 * Clears the bound FBO to the component plane [index] of this producer carries:
 * luma for plane 0, then Cb and Cr.
 */
static void producer_clear_plane(
        const NucleusTaoLinuxTestProducer *p, int index, float y, float u, float v) {
    (void) p;
    p_glClearColor(index == 0 ? y : (index == 1 ? u : v), 0.0f, 0.0f, 1.0f);
    p_glClear(GL_COLOR_BUFFER_BIT);
}

/**
 * Imports plane [index] of the producer's buffer on the producer's own display
 * and attaches it to a fresh FBO. The producer's context must be current.
 * Returns 0 on failure, with whatever it built already recorded in the plane so
 * [producer_free] releases it.
 */
static int producer_plane_init(
        NucleusTaoLinuxTestProducer *p, int index, int fourcc, int widthPx, int heightPx) {
    NucleusTaoTestPlane *plane = &p->planes[index];
    plane->fourcc   = fourcc;
    plane->widthPx  = widthPx;
    plane->heightPx = heightPx;
    plane->image = create_dmabuf_image(p->display, plane->fd, fourcc, widthPx, heightPx,
                                       plane->stride, plane->offset, plane->modifier);
    if (plane->image == EGL_NO_IMAGE_KHR) {
        DBG("producer plane %d eglCreateImageKHR(%.4s) failed: 0x%x\n",
            index, (const char *) &fourcc, p_eglGetError ? p_eglGetError() : 0);
        return 0;
    }
    GLenum gl_error = GL_NO_ERROR;
    plane->texture = texture_from_image(plane->image, &gl_error);
    if (plane->texture == 0) {
        DBG("producer plane %d target texture failed: 0x%x\n", index, gl_error);
        return 0;
    }
    p_glGenFramebuffers(1, &plane->fbo);
    p_glBindFramebuffer(GL_FRAMEBUFFER, plane->fbo);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                             plane->texture, 0);
    const GLenum status = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        DBG("producer plane %d FBO incomplete: 0x%x\n", index, status);
        return 0;
    }
    return 1;
}

/** Releases everything the producer owns. Safe on a partially built one. */
static void producer_free(NucleusTaoLinuxTestProducer *p) {
    if (p->context != EGL_NO_CONTEXT && producer_bind(p)) {
        for (int i = 0; i < p->planeCount; i++) {
            if (p->planes[i].fbo != 0 && p_glDeleteFramebuffers) {
                p_glDeleteFramebuffers(1, &p->planes[i].fbo);
            }
            if (p->planes[i].texture != 0) p_glDeleteTextures(1, &p->planes[i].texture);
        }
        producer_release(p);
    }
    for (int i = 0; i < p->planeCount; i++) {
        if (p->planes[i].image != EGL_NO_IMAGE_KHR && p_eglDestroyImageKHR) {
            p_eglDestroyImageKHR(p->display, p->planes[i].image);
        }
        if (p->planes[i].fd >= 0) close(p->planes[i].fd);
    }
    if (p->context != EGL_NO_CONTEXT) p_eglDestroyContext(p->display, p->context);
    if (p->display != EGL_NO_DISPLAY && p_eglTerminate) p_eglTerminate(p->display);
    if (p->bo != NULL && p_gbm_bo_destroy) p_gbm_bo_destroy(p->bo);
    if (p->gbmDevice != NULL && p_gbm_device_destroy) p_gbm_device_destroy(p->gbmDevice);
    if (p->drmFd >= 0) close(p->drmFd);
    free(p);
}

/** Allocates the producer shell, its render node, GBM device and EGL context. */
static NucleusTaoLinuxTestProducer *producer_new(int widthPx, int heightPx, int planeCount) {
    if (!p_glGenFramebuffers || !p_glBindFramebuffer || !p_glFramebufferTexture2D ||
        !p_glCheckFramebufferStatus || !p_glClear || !p_glClearColor || !p_glScissor) {
        return NULL;
    }
    NucleusTaoLinuxTestProducer *p = (NucleusTaoLinuxTestProducer *)
        calloc(1, sizeof(NucleusTaoLinuxTestProducer));
    if (p == NULL) return NULL;
    p->drmFd      = -1;
    p->widthPx    = widthPx;
    p->heightPx   = heightPx;
    p->planeCount = planeCount;
    for (int i = 0; i < NUCLEUS_TEST_MAX_PLANES; i++) p->planes[i].fd = -1;
    if (!open_gbm_device(&p->drmFd, &p->gbmDevice) ||
        !create_gbm_egl_context(p->gbmDevice, &p->display, &p->context)) {
        producer_free(p);
        return NULL;
    }
    return p;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerCreate(
        JNIEnv *env, jclass clazz, jint widthPx, jint heightPx, jint fourcc) {
    (void) env; (void) clazz;
    if (widthPx < 1 || heightPx < 1 || fourcc_bytes_per_pixel(fourcc) != 4) return 0;
    if (!resolve_entry_points() || !resolve_gbm()) return 0;

    NucleusTaoLinuxTestProducer *p = producer_new(widthPx, heightPx, 1);
    if (p == NULL) return 0;
    p->yuvColorSpace = -1;

    p->bo = p_gbm_bo_create(p->gbmDevice, (uint32_t) widthPx, (uint32_t) heightPx,
                            (uint32_t) fourcc, GBM_BO_USE_RENDERING);
    if (p->bo == NULL) {
        DBG("gbm_bo_create failed\n");
        producer_free(p);
        return 0;
    }
    p->planes[0].fd       = p_gbm_bo_get_fd(p->bo);
    p->planes[0].stride   = (int) p_gbm_bo_get_stride(p->bo);
    p->planes[0].offset   = 0;
    p->planes[0].modifier = p_gbm_bo_get_modifier ? p_gbm_bo_get_modifier(p->bo)
                                                  : NUCLEUS_DRM_FORMAT_MOD_INVALID;
    if (p->planes[0].fd < 0 || p->planes[0].stride < 1 || !producer_bind(p)) {
        producer_free(p);
        return 0;
    }
    /* Same import on the producer side: the buffer is its render target. */
    const int ok = producer_plane_init(p, 0, fourcc, widthPx, heightPx);
    producer_release(p);
    if (!ok) {
        producer_free(p);
        return 0;
    }
    return (jlong) (uintptr_t) p;
}

/**
 * Planar counterpart: one `I420` GBM buffer, each of whose three planes is
 * imported as its own single-channel image and attached to an FBO — which is how
 * the producer can draw into a YUV buffer with nothing but scissored clears, and
 * how a real decoder's output looks to the consumer.
 *
 * Returns 0 when the driver cannot allocate or render to the format.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerCreateYuv(
        JNIEnv *env, jclass clazz, jint widthPx, jint heightPx, jint yuvFormat, jint colorSpace) {
    (void) env; (void) clazz;
    /* Even dimensions only: a 4:2:0 plane of an odd size has no exact half. */
    if (widthPx < 2 || heightPx < 2 || (widthPx % 2) != 0 || (heightPx % 2) != 0) return 0;
    if (yuvFormat != NUCLEUS_TEST_YUV_I420) return 0;
    if (colorSpace < NUCLEUS_YUV_BT601_LIMITED || colorSpace > NUCLEUS_YUV_BT709_FULL) return 0;
    if (!resolve_entry_points() || !resolve_gbm()) return 0;
    if (!p_gbm_bo_get_plane_count || !p_gbm_bo_get_fd_for_plane ||
        !p_gbm_bo_get_offset || !p_gbm_bo_get_stride_for_plane) {
        DBG("libgbm without plane accessors — no planar producer\n");
        return 0;
    }

    const int planeCount = 3;
    const uint32_t bufferFourcc = 0x32315559u; /* YU12 — I420 */
    NucleusTaoLinuxTestProducer *p = producer_new(widthPx, heightPx, planeCount);
    if (p == NULL) return 0;
    p->yuvColorSpace = colorSpace;

    p->bo = p_gbm_bo_create(p->gbmDevice, (uint32_t) widthPx, (uint32_t) heightPx,
                            bufferFourcc, GBM_BO_USE_RENDERING);
    if (p->bo == NULL || p_gbm_bo_get_plane_count(p->bo) < planeCount) {
        DBG("gbm_bo_create(planar) failed or returned too few planes\n");
        producer_free(p);
        return 0;
    }
    const uint64_t modifier = p_gbm_bo_get_modifier
        ? p_gbm_bo_get_modifier(p->bo) : NUCLEUS_DRM_FORMAT_MOD_INVALID;
    for (int i = 0; i < planeCount; i++) {
        p->planes[i].fd       = p_gbm_bo_get_fd_for_plane(p->bo, i);
        p->planes[i].offset   = (int) p_gbm_bo_get_offset(p->bo, i);
        p->planes[i].stride   = (int) p_gbm_bo_get_stride_for_plane(p->bo, i);
        p->planes[i].modifier = modifier;
        if (p->planes[i].fd < 0 || p->planes[i].stride < 1) {
            producer_free(p);
            return 0;
        }
    }
    if (!producer_bind(p)) {
        producer_free(p);
        return 0;
    }
    int ok = producer_plane_init(p, 0, 0x20203852 /* R8 */, widthPx, heightPx) &&
             producer_plane_init(p, 1, 0x20203852, widthPx / 2, heightPx / 2) &&
             producer_plane_init(p, 2, 0x20203852, widthPx / 2, heightPx / 2);
    producer_release(p);
    if (!ok) {
        producer_free(p);
        return 0;
    }
    return (jlong) (uintptr_t) p;
}

/** Number of DMA-BUF planes the producer publishes (1 for packed RGB). */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerPlaneCount(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void) env; (void) clazz;
    if (producer == 0) return 0;
    return (jint) PRODUCER_OF(producer)->planeCount;
}

/** Plane geometry, borrowed and valid until destroy. Index out of range → -1/0. */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerPlaneFd(
        JNIEnv *env, jclass clazz, jlong producer, jint index) {
    (void) env; (void) clazz;
    if (producer == 0 || index < 0 || index >= PRODUCER_OF(producer)->planeCount) return -1;
    return (jint) PRODUCER_OF(producer)->planes[index].fd;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerPlaneStride(
        JNIEnv *env, jclass clazz, jlong producer, jint index) {
    (void) env; (void) clazz;
    if (producer == 0 || index < 0 || index >= PRODUCER_OF(producer)->planeCount) return 0;
    return (jint) PRODUCER_OF(producer)->planes[index].stride;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerPlaneOffset(
        JNIEnv *env, jclass clazz, jlong producer, jint index) {
    (void) env; (void) clazz;
    if (producer == 0 || index < 0 || index >= PRODUCER_OF(producer)->planeCount) return 0;
    return (jint) PRODUCER_OF(producer)->planes[index].offset;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerPlaneModifier(
        JNIEnv *env, jclass clazz, jlong producer, jint index) {
    (void) env; (void) clazz;
    if (producer == 0 || index < 0 || index >= PRODUCER_OF(producer)->planeCount) {
        return (jlong) NUCLEUS_DRM_FORMAT_MOD_INVALID;
    }
    return (jlong) PRODUCER_OF(producer)->planes[index].modifier;
}

/** Fills every plane with the components of [argb]. Context must be current. */
static void producer_fill_locked(NucleusTaoLinuxTestProducer *p, jint argb) {
    if (p->yuvColorSpace < 0) {
        p_glBindFramebuffer(GL_FRAMEBUFFER, p->planes[0].fbo);
        p_glViewport(0, 0, p->widthPx, p->heightPx);
        producer_clear(argb);
        p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return;
    }
    float y = 0.0f, u = 0.0f, v = 0.0f;
    argb_to_yuv(p->yuvColorSpace, argb, &y, &u, &v);
    for (int i = 0; i < p->planeCount; i++) {
        p_glBindFramebuffer(GL_FRAMEBUFFER, p->planes[i].fbo);
        p_glViewport(0, 0, p->planes[i].widthPx, p->planes[i].heightPx);
        producer_clear_plane(p, i, y, u, v);
    }
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerFill(
        JNIEnv *env, jclass clazz, jlong producer, jint argb) {
    (void) env; (void) clazz;
    if (producer == 0) return;
    NucleusTaoLinuxTestProducer *p = PRODUCER_OF(producer);
    if (!producer_bind(p)) return;
    producer_fill_locked(p, argb);
    /* The frame must be fully written before the caller signals it — that is
     * what makes the consumer's zero-copy sampling tear-free. */
    p_glFinish();
    producer_release(p);
}

/**
 * Draws the animated test pattern into every plane. Context must be current;
 * deliberately does **not** finish, so the two entry points below can choose
 * between the blocking contract and an acquire fence.
 *
 * [argbBg] background plus a white vertical bar (x follows [tick]) and a white
 * horizontal bar (y follows [tick]) — the same shape the Windows and macOS
 * producers draw, so the demo looks identical on all three backends. Scissored
 * clears only, so the producer needs no shader pipeline; on a planar buffer each
 * plane gets the same rectangles scaled to its own size, which is exactly how a
 * 4:2:0 encoder subsamples.
 *
 * No y flip: rendering into an FBO whose colour attachment is this texture
 * writes texture row `y` — i.e. buffer row `y` — and the consumer adopts the
 * import as `SurfaceOrigin.TOP_LEFT`, so row 0 is the top on both sides and
 * [tick] moves the bar *down* the composited image like the other platforms.
 * `LinuxExternalTextureNativeSmokeTest` pins this end to end.
 */
static void producer_draw_pattern_locked(
        NucleusTaoLinuxTestProducer *p, jint tick, jint argbBg) {
    const int barW = p->widthPx  < NUCLEUS_TEST_BAR_PX ? p->widthPx  : NUCLEUS_TEST_BAR_PX;
    const int barH = p->heightPx < NUCLEUS_TEST_BAR_PX ? p->heightPx : NUCLEUS_TEST_BAR_PX;
    int barX = (tick * 2) % (p->widthPx  - barW + 1);
    int barY =  tick      % (p->heightPx - barH + 1);
    if (barX < 0) barX = 0;
    if (barY < 0) barY = 0;

    float bgY = 0.0f, bgU = 0.0f, bgV = 0.0f;
    float barY_ = 0.0f, barU = 0.0f, barV = 0.0f;
    if (p->yuvColorSpace >= 0) {
        argb_to_yuv(p->yuvColorSpace, argbBg, &bgY, &bgU, &bgV);
        argb_to_yuv(p->yuvColorSpace, (jint) 0xFFFFFFFF, &barY_, &barU, &barV);
    }
    for (int i = 0; i < p->planeCount; i++) {
        const NucleusTaoTestPlane *plane = &p->planes[i];
        /* Integer ratio: every plane is either full or half size here. */
        const int sx = p->widthPx  / plane->widthPx;
        const int sy = p->heightPx / plane->heightPx;
        const int pBarW = barW / sx < 1 ? 1 : barW / sx;
        const int pBarH = barH / sy < 1 ? 1 : barH / sy;
        p_glBindFramebuffer(GL_FRAMEBUFFER, plane->fbo);
        p_glViewport(0, 0, plane->widthPx, plane->heightPx);
        if (p->yuvColorSpace < 0) {
            producer_clear(argbBg);
        } else {
            producer_clear_plane(p, i, bgY, bgU, bgV);
        }
        p_glEnable(GL_SCISSOR_TEST);
        p_glScissor(barX / sx, 0, pBarW, plane->heightPx);
        if (p->yuvColorSpace < 0) {
            producer_clear(0xFFFFFFFF);
        } else {
            producer_clear_plane(p, i, barY_, barU, barV);
        }
        p_glScissor(0, barY / sy, plane->widthPx, pBarH);
        if (p->yuvColorSpace < 0) {
            producer_clear(0xFFFFFFFF);
        } else {
            producer_clear_plane(p, i, barY_, barU, barV);
        }
        p_glDisable(GL_SCISSOR_TEST);
    }
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerDrawPattern(
        JNIEnv *env, jclass clazz, jlong producer, jint tick, jint argbBg) {
    (void) env; (void) clazz;
    if (producer == 0) return;
    NucleusTaoLinuxTestProducer *p = PRODUCER_OF(producer);
    if (!producer_bind(p)) return;
    producer_draw_pattern_locked(p, tick, argbBg);
    p_glFinish();
    producer_release(p);
}

/**
 * Fence fd for the GL work issued so far on the producer's context, or -1 when
 * the driver has no native fence sync. The fence has to be flushed into the
 * command stream before its fd can be exported — that is the whole point of it:
 * the fd signals when the GPU reaches that point, with nothing blocking here.
 */
static int producer_fence_fd(NucleusTaoLinuxTestProducer *p) {
    if (!p_eglCreateSyncKHR || !p_eglDestroySyncKHR || !p_eglDupNativeFenceFDANDROID) return -1;
    if (!has_extension(p->display, "EGL_ANDROID_native_fence_sync")) return -1;
    const EGLint attrs[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE
    };
    EGLSyncKHR sync = p_eglCreateSyncKHR(p->display, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        DBG("producer eglCreateSyncKHR failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        return -1;
    }
    if (p_glFlush) p_glFlush();
    const int fd = p_eglDupNativeFenceFDANDROID(p->display, sync);
    p_eglDestroySyncKHR(p->display, sync);
    if (fd == EGL_NO_NATIVE_FENCE_FD_ANDROID) {
        DBG("eglDupNativeFenceFDANDROID failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        return -1;
    }
    return fd;
}

/**
 * Same pattern, published with an **acquire fence** instead of a `glFinish`:
 * returns a `sync_file` fd the consumer waits on (see [nativeWaitFence]), so
 * neither side blocks on the CPU. Ownership passes to the caller.
 *
 * Falls back to finishing synchronously — and returns -1 — when the driver has no
 * native fence sync, so the frame is always safe to signal either way.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerDrawPatternFenced(
        JNIEnv *env, jclass clazz, jlong producer, jint tick, jint argbBg) {
    (void) env; (void) clazz;
    if (producer == 0) return -1;
    NucleusTaoLinuxTestProducer *p = PRODUCER_OF(producer);
    if (!producer_bind(p)) return -1;
    producer_draw_pattern_locked(p, tick, argbBg);
    const int fd = producer_fence_fd(p);
    if (fd < 0) p_glFinish();
    producer_release(p);
    return (jint) fd;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerDestroy(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void) env; (void) clazz;
    if (producer == 0) return;
    producer_free(PRODUCER_OF(producer));
}

/* ── Headless consumer context (smoke tests) ─────────────────────────────── */

typedef struct {
    int        drmFd;
    void      *gbmDevice;
    EGLDisplay display;
    EGLContext context;
} NucleusTaoLinuxTestContext;

#define TEST_CONTEXT_OF(ptr) ((NucleusTaoLinuxTestContext *) (uintptr_t) (ptr))

/**
 * Creates a GBM-backed EGL context and makes it current on the calling thread
 * — a stand-in for a window's attachment, so the import chain (and a Skia
 * `DirectContext` on top of it) can be exercised with no window, no event loop
 * and no display server. Returns 0 when no render node / GBM / EGL is usable.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestContextCreate(
        JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    if (!resolve_entry_points() || !resolve_gbm()) return 0;
    NucleusTaoLinuxTestContext *c = (NucleusTaoLinuxTestContext *)
        calloc(1, sizeof(NucleusTaoLinuxTestContext));
    if (c == NULL) return 0;
    c->drmFd = -1;
    if (!open_gbm_device(&c->drmFd, &c->gbmDevice) ||
        !create_gbm_egl_context(c->gbmDevice, &c->display, &c->context) ||
        p_eglMakeCurrent(c->display, EGL_NO_SURFACE, EGL_NO_SURFACE, c->context) != EGL_TRUE) {
        if (c->context != EGL_NO_CONTEXT) p_eglDestroyContext(c->display, c->context);
        if (c->display != EGL_NO_DISPLAY && p_eglTerminate) p_eglTerminate(c->display);
        if (c->gbmDevice != NULL && p_gbm_device_destroy) p_gbm_device_destroy(c->gbmDevice);
        if (c->drmFd >= 0) close(c->drmFd);
        free(c);
        return 0;
    }
    return (jlong) (uintptr_t) c;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestContextDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    if (handle == 0) return;
    NucleusTaoLinuxTestContext *c = TEST_CONTEXT_OF(handle);
    p_eglMakeCurrent(c->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (c->context != EGL_NO_CONTEXT) p_eglDestroyContext(c->display, c->context);
    if (c->display != EGL_NO_DISPLAY && p_eglTerminate) p_eglTerminate(c->display);
    if (c->gbmDevice != NULL && p_gbm_device_destroy) p_gbm_device_destroy(c->gbmDevice);
    if (c->drmFd >= 0) close(c->drmFd);
    free(c);
}
