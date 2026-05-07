/**
 * Internal contract between the overlay HWND lifecycle (overlay.c),
 * the popup HWND lifecycle (popup.c), and the transparent WGL
 * rendering bridge (overlay_gl.c). All three .c files are linked into
 * the same nucleus_tao_windows_native_view.dll.
 *
 * `GlSurface` is the minimal HWND-plus-GL state both lifecycles need.
 * Each owner (OverlayState / PopupState) embeds one as a field and
 * passes `&owner->gl` to the helpers below.
 */
#ifndef NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H
#define NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H

#include <windows.h>

typedef struct {
    HWND  hwnd;
    HDC   hdc;
    HGLRC hglrc;
} GlSurface;

/**
 * Resolves the host's pixel format + HGLRC from nucleus_tao_gl.dll,
 * applies them to the [hwnd] HDC, creates a new HGLRC via
 * `wglCreateContextAttribsARB(.., hostHGLRC, ..)` (joined to host's
 * share group), arms `DwmEnableBlurBehindWindow` with the empty-region
 * trick, applies DWM polish (rounded corners, dark mode, extended
 * frame for shadow), and sets `wglSwapIntervalEXT(0)` on the new
 * context. On success [gl] holds the resolved hdc + hglrc.
 *
 * Caller must have set [gl]->hwnd before calling.
 */
BOOL nucleus_tao_overlay_gl_init(GlSurface *gl);

/** Tears down the WGL context + HDC release. Safe on partial init. */
void nucleus_tao_overlay_gl_destroy(GlSurface *gl);

/** Re-arms `DwmEnableBlurBehindWindow` after WM_DWMCOMPOSITIONCHANGED. */
void nucleus_tao_overlay_gl_rearm_blur(GlSurface *gl);

#endif
