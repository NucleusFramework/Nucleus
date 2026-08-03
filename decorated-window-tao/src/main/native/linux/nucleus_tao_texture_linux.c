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

/* GBM — dlopen-ed, and only for the bundled test producer. Real applications
 * bring their own DMA-BUF; nothing on the import path needs libgbm. */
typedef void     *(*PFN_gbm_create_device)(int);
typedef void      (*PFN_gbm_device_destroy)(void *);
typedef void     *(*PFN_gbm_bo_create)(void *, uint32_t, uint32_t, uint32_t, uint32_t);
typedef void      (*PFN_gbm_bo_destroy)(void *);
typedef int       (*PFN_gbm_bo_get_fd)(void *);
typedef uint32_t  (*PFN_gbm_bo_get_stride)(void *);
typedef uint64_t  (*PFN_gbm_bo_get_modifier)(void *);

static PFN_gbm_create_device   p_gbm_create_device   = NULL;
static PFN_gbm_device_destroy  p_gbm_device_destroy  = NULL;
static PFN_gbm_bo_create       p_gbm_bo_create       = NULL;
static PFN_gbm_bo_destroy      p_gbm_bo_destroy      = NULL;
static PFN_gbm_bo_get_fd       p_gbm_bo_get_fd       = NULL;
static PFN_gbm_bo_get_stride   p_gbm_bo_get_stride   = NULL;
static PFN_gbm_bo_get_modifier p_gbm_bo_get_modifier = NULL;

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

/* Single-plane 32-bit RGB FourCCs. Anything else (planar YUV, 10-bit, …) would
 * import but sample as garbage once Skia treats plane 0 as RGBA8, so it is
 * rejected up front rather than silently mis-rendered. */
static int is_supported_fourcc(int fourcc) {
    switch ((unsigned) fourcc) {
        case 0x34325241u: /* AR24 — DRM_FORMAT_ARGB8888 */
        case 0x34325258u: /* XR24 — DRM_FORMAT_XRGB8888 */
        case 0x34324241u: /* AB24 — DRM_FORMAT_ABGR8888 */
        case 0x34324258u: /* XB24 — DRM_FORMAT_XBGR8888 */
        case 0x34324152u: /* RA24 — DRM_FORMAT_RGBA8888 */
        case 0x34325852u: /* RX24 — DRM_FORMAT_RGBX8888 */
        case 0x34324142u: /* BA24 — DRM_FORMAT_BGRA8888 */
        case 0x34325842u: /* BX24 — DRM_FORMAT_BGRX8888 */
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
    if (!is_supported_fourcc(fourcc)) return NUCLEUS_TEX_ERR_ARGS;
    /* Every accepted FourCC is 4 bytes per pixel, so a stride below the row
     * size cannot describe the buffer. Drivers often accept such an image and
     * then sample past the end of the plane — a caller who passed the stride in
     * pixels instead of bytes would get garbage instead of a clean failure. */
    if (stride / 4 < widthPx) return NUCLEUS_TEX_ERR_ARGS;
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
 * The displaced binding is therefore snapshotted here, per thread, and restored
 * by [nativeRestoreBinding]. One level deep is all the callers need; a nested
 * save would overwrite the outer one, so [nativeSaveCurrentBinding] refuses to
 * nest and the caller keeps the "already current" fast path for that case. */

typedef struct {
    EGLDisplay display;
    EGLContext context;
    EGLSurface draw;
    EGLSurface read;
    int        saved;
} NucleusTaoEglBinding;

static __thread NucleusTaoEglBinding g_displaced;

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
    if (g_displaced.saved) return JNI_FALSE;
    g_displaced.display = (EGLDisplay) nucleus_tao_egl_current_display();
    g_displaced.context = (EGLContext) nucleus_tao_egl_current_context();
    g_displaced.draw = p_eglGetCurrentSurface ? p_eglGetCurrentSurface(EGL_DRAW) : EGL_NO_SURFACE;
    g_displaced.read = p_eglGetCurrentSurface ? p_eglGetCurrentSurface(EGL_READ) : EGL_NO_SURFACE;
    g_displaced.saved = 1;
    return JNI_TRUE;
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
    if (!g_displaced.saved) return JNI_FALSE;
    const EGLDisplay display = g_displaced.display;
    const EGLContext context = g_displaced.context;
    const EGLSurface draw = g_displaced.draw;
    const EGLSurface read = g_displaced.read;
    memset(&g_displaced, 0, sizeof(g_displaced));
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return JNI_FALSE;
    return p_eglMakeCurrent(display, draw, read, context) == EGL_TRUE ? JNI_TRUE : JNI_FALSE;
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

/* ================================================================== */
/*  GBM + EGL test producer (demos / smoke tests)                      */
/* ================================================================== */

typedef struct {
    int         drmFd;
    void       *gbmDevice;
    void       *bo;
    int         dmaBufFd;
    int         stride;
    uint64_t    modifier;
    int         fourcc;
    int         widthPx;
    int         heightPx;
    /* Private EGL display + context: the DMA-BUF is the only thing shared with
     * the consumer, exactly like a real producer. */
    EGLDisplay  display;
    EGLContext  context;
    EGLImageKHR image;
    GLuint      texture;
    GLuint      fbo;
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

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerCreate(
        JNIEnv *env, jclass clazz, jint widthPx, jint heightPx, jint fourcc) {
    (void) env; (void) clazz;
    if (widthPx < 1 || heightPx < 1 || !is_supported_fourcc(fourcc)) return 0;
    if (!resolve_entry_points() || !resolve_gbm()) return 0;
    if (!p_glGenFramebuffers || !p_glBindFramebuffer || !p_glFramebufferTexture2D ||
        !p_glCheckFramebufferStatus || !p_glClear || !p_glClearColor || !p_glScissor) {
        return 0;
    }

    NucleusTaoLinuxTestProducer *p = (NucleusTaoLinuxTestProducer *)
        calloc(1, sizeof(NucleusTaoLinuxTestProducer));
    if (p == NULL) return 0;
    p->drmFd    = -1;
    p->dmaBufFd = -1;
    p->widthPx  = widthPx;
    p->heightPx = heightPx;
    p->fourcc   = fourcc;

    if (!open_gbm_device(&p->drmFd, &p->gbmDevice)) goto fail;
    p->bo = p_gbm_bo_create(p->gbmDevice, (uint32_t) widthPx, (uint32_t) heightPx,
                            (uint32_t) fourcc, GBM_BO_USE_RENDERING);
    if (p->bo == NULL) {
        DBG("gbm_bo_create failed\n");
        goto fail;
    }
    p->dmaBufFd = p_gbm_bo_get_fd(p->bo);
    p->stride   = (int) p_gbm_bo_get_stride(p->bo);
    p->modifier = p_gbm_bo_get_modifier ? p_gbm_bo_get_modifier(p->bo)
                                        : NUCLEUS_DRM_FORMAT_MOD_INVALID;
    if (p->dmaBufFd < 0 || p->stride < 1) goto fail;

    if (!create_gbm_egl_context(p->gbmDevice, &p->display, &p->context)) goto fail;
    if (!producer_bind(p)) goto fail;

    /* Same import on the producer side: the buffer is its render target. */
    p->image = create_dmabuf_image(p->display, p->dmaBufFd, fourcc, widthPx, heightPx,
                                   p->stride, 0, p->modifier);
    if (p->image == EGL_NO_IMAGE_KHR) {
        DBG("producer eglCreateImageKHR failed: 0x%x\n", p_eglGetError ? p_eglGetError() : 0);
        producer_release(p);
        goto fail;
    }
    GLenum gl_error = GL_NO_ERROR;
    p->texture = texture_from_image(p->image, &gl_error);
    if (p->texture == 0) {
        producer_release(p);
        goto fail;
    }
    p_glGenFramebuffers(1, &p->fbo);
    p_glBindFramebuffer(GL_FRAMEBUFFER, p->fbo);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                             p->texture, 0);
    GLenum status = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    producer_release(p);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        DBG("producer FBO incomplete: 0x%x\n", status);
        goto fail;
    }
    return (jlong) (uintptr_t) p;

fail:
    if (p->image != EGL_NO_IMAGE_KHR && p_eglDestroyImageKHR) {
        p_eglDestroyImageKHR(p->display, p->image);
    }
    if (p->context != EGL_NO_CONTEXT) p_eglDestroyContext(p->display, p->context);
    if (p->display != EGL_NO_DISPLAY && p_eglTerminate) p_eglTerminate(p->display);
    if (p->dmaBufFd >= 0) close(p->dmaBufFd);
    if (p->bo != NULL && p_gbm_bo_destroy) p_gbm_bo_destroy(p->bo);
    if (p->gbmDevice != NULL && p_gbm_device_destroy) p_gbm_device_destroy(p->gbmDevice);
    if (p->drmFd >= 0) close(p->drmFd);
    free(p);
    return 0;
}

/** DMA-BUF fd of the producer's buffer — borrowed, valid until destroy. */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerFd(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void) env; (void) clazz;
    if (producer == 0) return -1;
    return (jint) PRODUCER_OF(producer)->dmaBufFd;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerStride(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void) env; (void) clazz;
    if (producer == 0) return 0;
    return (jint) PRODUCER_OF(producer)->stride;
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerModifier(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void) env; (void) clazz;
    if (producer == 0) return (jlong) NUCLEUS_DRM_FORMAT_MOD_INVALID;
    return (jlong) PRODUCER_OF(producer)->modifier;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerFill(
        JNIEnv *env, jclass clazz, jlong producer, jint argb) {
    (void) env; (void) clazz;
    if (producer == 0) return;
    NucleusTaoLinuxTestProducer *p = PRODUCER_OF(producer);
    if (!producer_bind(p)) return;
    p_glBindFramebuffer(GL_FRAMEBUFFER, p->fbo);
    p_glViewport(0, 0, p->widthPx, p->heightPx);
    producer_clear(argb);
    /* The frame must be fully written before the caller signals it — that is
     * what makes the consumer's zero-copy sampling tear-free. */
    p_glFinish();
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    producer_release(p);
}

/**
 * Animated test pattern: [argbBg] background plus a white vertical bar (x
 * follows [tick]) and a white horizontal bar (y follows [tick]) — the same
 * shape the Windows and macOS producers draw, so the demo looks identical on
 * all three backends. Drawn with scissored clears, so the producer needs no
 * shader pipeline.
 *
 * No y flip: rendering into an FBO whose colour attachment is this texture
 * writes texture row `y` — i.e. buffer row `y` — and the consumer adopts the
 * import as `SurfaceOrigin.TOP_LEFT`, so row 0 is the top on both sides and
 * [tick] moves the bar *down* the composited image like the other platforms.
 * `LinuxExternalTextureNativeSmokeTest` pins this end to end.
 */
JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerDrawPattern(
        JNIEnv *env, jclass clazz, jlong producer, jint tick, jint argbBg) {
    (void) env; (void) clazz;
    if (producer == 0) return;
    NucleusTaoLinuxTestProducer *p = PRODUCER_OF(producer);
    const int barW = p->widthPx  < NUCLEUS_TEST_BAR_PX ? p->widthPx  : NUCLEUS_TEST_BAR_PX;
    const int barH = p->heightPx < NUCLEUS_TEST_BAR_PX ? p->heightPx : NUCLEUS_TEST_BAR_PX;
    int barX = (tick * 2) % (p->widthPx  - barW + 1);
    int barY =  tick      % (p->heightPx - barH + 1);
    if (barX < 0) barX = 0;
    if (barY < 0) barY = 0;

    if (!producer_bind(p)) return;
    p_glBindFramebuffer(GL_FRAMEBUFFER, p->fbo);
    p_glViewport(0, 0, p->widthPx, p->heightPx);
    producer_clear(argbBg);
    p_glEnable(GL_SCISSOR_TEST);
    p_glScissor(barX, 0, barW, p->heightPx);
    producer_clear(0xFFFFFFFF);
    p_glScissor(0, barY, p->widthPx, barH);
    producer_clear(0xFFFFFFFF);
    p_glDisable(GL_SCISSOR_TEST);
    p_glFinish();
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    producer_release(p);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_window_tao_ffi_NativeTaoLinuxTextureBridge_nativeTestProducerDestroy(
        JNIEnv *env, jclass clazz, jlong producer) {
    (void) env; (void) clazz;
    if (producer == 0) return;
    NucleusTaoLinuxTestProducer *p = PRODUCER_OF(producer);
    if (producer_bind(p)) {
        if (p->fbo != 0 && p_glDeleteFramebuffers) p_glDeleteFramebuffers(1, &p->fbo);
        if (p->texture != 0) p_glDeleteTextures(1, &p->texture);
        producer_release(p);
    }
    if (p->image != EGL_NO_IMAGE_KHR) p_eglDestroyImageKHR(p->display, p->image);
    if (p->context != EGL_NO_CONTEXT) p_eglDestroyContext(p->display, p->context);
    if (p->display != EGL_NO_DISPLAY && p_eglTerminate) p_eglTerminate(p->display);
    if (p->dmaBufFd >= 0) close(p->dmaBufFd);
    if (p->bo != NULL && p_gbm_bo_destroy) p_gbm_bo_destroy(p->bo);
    if (p->gbmDevice != NULL && p_gbm_device_destroy) p_gbm_device_destroy(p->gbmDevice);
    if (p->drmFd >= 0) close(p->drmFd);
    free(p);
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
