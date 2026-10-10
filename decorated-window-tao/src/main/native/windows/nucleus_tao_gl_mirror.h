/* DirectComposition mirror of a Tao window's frames — see nucleus_tao_gl_mirror.cpp. */
#ifndef NUCLEUS_TAO_GL_MIRROR_H
#define NUCLEUS_TAO_GL_MIRROR_H

#include <windows.h>
#include <d3d11.h>

#ifdef __cplusplus
extern "C" {
#endif

/* WM_TIMER id the mirror arms on the render child, and how long the window's
 * size must hold still before the mirror is shown again. */
#define NUCLEUS_TAO_MIRROR_TIMER_ID   0x4E4D /* 'NM' */
#define NUCLEUS_TAO_MIRROR_SETTLE_MS  200

typedef struct NucleusTaoMirror NucleusTaoMirror;

/* Whether DirectComposition can be loaded at all. */
BOOL nucleus_tao_mirror_available(void);

/* Mirror targeting [surfaceHwnd] (the render child), on ANGLE's [device]. NULL when DirectComposition is unavailable. */
NucleusTaoMirror *nucleus_tao_mirror_create(HWND surfaceHwnd, ID3D11Device *device);
void nucleus_tao_mirror_destroy(NucleusTaoMirror *mirror);

/* The texture each frame is copied into, (re)created at [w]x[h]; NULL on failure. */
ID3D11Texture2D *nucleus_tao_mirror_texture(NucleusTaoMirror *mirror, int w, int h);

/* The texture holds a new frame (already flushed to ANGLE's immediate context). */
void nucleus_tao_mirror_frame(NucleusTaoMirror *mirror);

/* The window's size is about to change: take the visual down until it settles. */
void nucleus_tao_mirror_suspend(NucleusTaoMirror *mirror);

/* A DirectComposition / DXGI step failed: the mirror shows nothing and never
 * will; the caller drops it rather than copying frames into it. */
BOOL nucleus_tao_mirror_failed(NucleusTaoMirror *mirror);

/* NUCLEUS_TAO_MIRROR_TIMER_ID fired on the render child. */
void nucleus_tao_mirror_timer(NucleusTaoMirror *mirror);

#ifdef __cplusplus
}
#endif

#endif
