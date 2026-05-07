/**
 * Internal contract between the overlay HWND lifecycle (overlay.c) and
 * the transparent WGL rendering bridge (overlay_gl.c). Both .c files
 * are linked into the same nucleus_tao_windows_native_view.dll.
 *
 * The full OverlayState lives in overlay.c — overlay_gl.c only needs
 * its WGL fields, exposed via accessor inlines. Same shape used by
 * popup.c for its PopupState (different struct, same accessor protocol).
 */
#ifndef NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H
#define NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H

#include <windows.h>

typedef struct OverlayState OverlayState;

/* Implemented in overlay_gl.c. Resolves the host's pixel format + HGLRC
 * from nucleus_tao_gl.dll, applies them to the overlay HDC, creates the
 * popup HGLRC via wglCreateContextAttribsARB(.., hostHGLRC, ..), arms
 * DwmEnableBlurBehindWindow + DWM polish (corner radius, dark mode,
 * extended frame for shadow), and sets wglSwapIntervalEXT(0). Returns
 * TRUE on success. Safe to call on an already-failed state (no-op). */
BOOL nucleus_tao_overlay_gl_init(OverlayState *s);

/** Tears down the WGL context + HDC release. Safe on partial init. */
void nucleus_tao_overlay_gl_destroy(OverlayState *s);

/** Re-arms DwmEnableBlurBehindWindow after WM_DWMCOMPOSITIONCHANGED. */
void nucleus_tao_overlay_gl_rearm_blur(OverlayState *s);

/* WGL field accessors — defined in overlay.c. */
HDC   nucleus_tao_overlay_get_hdc(OverlayState *s);
HGLRC nucleus_tao_overlay_get_hglrc(OverlayState *s);
HWND  nucleus_tao_overlay_get_hwnd(OverlayState *s);
void  nucleus_tao_overlay_set_gl_resources(OverlayState *s, HDC hdc, HGLRC hglrc);

#endif
