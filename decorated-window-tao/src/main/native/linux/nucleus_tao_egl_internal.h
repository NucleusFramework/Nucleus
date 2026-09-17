/**
 * Internal surface of `nucleus_tao_egl.c`, shared with the other translation
 * units linked into `libnucleus_tao_egl.so` (currently
 * `nucleus_tao_texture_linux.c`). Nothing here is exported from the library —
 * the build uses `-fvisibility=hidden` and these are intra-library calls only.
 *
 * The EGL entry points live in `nucleus_tao_egl.c` because it owns the
 * `dlopen` of libEGL / libGL and the `EglAttachment` per-window state; the
 * texture helper resolves everything else through [nucleus_tao_egl_proc_address].
 */

#ifndef NUCLEUS_TAO_EGL_INTERNAL_H
#define NUCLEUS_TAO_EGL_INTERNAL_H

/** dlopens libEGL / libGL / libX11 and resolves the entry points. 0 on failure. */
int nucleus_tao_egl_ensure_libs(void);

/**
 * `eglGetProcAddress` with a `dlsym(libGL)` fallback — the same resolver Skia
 * gets, so an entry point missing here is missing for Skia too.
 * [nucleus_tao_egl_ensure_libs] must have succeeded first.
 */
void *nucleus_tao_egl_proc_address(const char *name);

/** `EGLDisplay` current on the calling thread, or NULL. */
void *nucleus_tao_egl_current_display(void);

/** `EGLContext` current on the calling thread, or NULL. */
void *nucleus_tao_egl_current_context(void);

/** `EGLContext` of an `EglAttachment` handle (see `nativeAttachX11`), or NULL. */
void *nucleus_tao_egl_attachment_context(long long handle);

#endif /* NUCLEUS_TAO_EGL_INTERNAL_H */
