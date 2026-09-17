/**
 * Internal contract between the overlay HWND lifecycle (overlay.c),
 * the popup HWND lifecycle (popup.c) and the EGL/ANGLE +
 * DirectComposition rendering bridge (overlay_dcomp.cpp). All three
 * files are linked into the same nucleus_tao_windows_native_view.dll.
 *
 * `GlSurface` is the minimal HWND-plus-render state both lifecycles
 * need. Each owner (OverlayState / PopupState) embeds one as a field
 * and passes `&owner->gl` to the helpers below.
 *
 * Rendering model (see overlay_dcomp.cpp for the full picture):
 * Compose renders through the host's EGLContext into a D3D11 texture
 * (EGL_ANGLE_d3d_texture_client_buffer pbuffer), presented on a
 * composition swapchain (DXGI_ALPHA_MODE_PREMULTIPLIED) attached to
 * the HWND via a DComp target/visual. Overlay/popup HWNDs are created
 * with WS_EX_NOREDIRECTIONBITMAP — no GDI redirection surface behind
 * the visual tree.
 */
#ifndef NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H
#define NUCLEUS_TAO_WINDOWS_OVERLAY_INTERNAL_H

#include <windows.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    HWND  hwnd;
    /* The parent host window HWND this surface borrows its EGLContext
     * from (the Tao main window that called nativeAttach). 0 for
     * ownerless (tray) panels → createSurface falls back to the global
     * host trio. Set by the caller before nucleus_tao_overlay_gl_init. */
    HWND  hostHwnd;
    /* Opaque DcompSurface* owned by overlay_dcomp.cpp. */
    void *dcomp;
} GlSurface;

/**
 * Builds the DComp target/visual/swapchain + EGL pbuffer chain against
 * the host's EGL display/context/config (nucleus_tao_gl.dll exports)
 * and applies DWM styling: window polish (rounded corners, dark mode,
 * shadow) for persistent overlays, or the flat popup surface for
 * popups (nativeWindowPolish=FALSE — shadows/elevation come from
 * Compose draw bounds, matching AWT WindowComposeSceneLayer).
 *
 * Caller must have set [gl]->hwnd before calling.
 */
BOOL nucleus_tao_overlay_gl_init(GlSurface *gl, BOOL nativeWindowPolish);

/** Tears down the DComp/EGL state. Safe on partial init. */
void nucleus_tao_overlay_gl_destroy(GlSurface *gl);

/** Binds the surface's d3d-texture pbuffer on the calling thread via
 *  eglMakeCurrent with the host's EGLContext. */
BOOL nucleus_tao_overlay_gl_make_current(GlSurface *gl);

/** Presents the rendered frame: CopyResource into the composition
 *  swapchain's back buffer + Present(0) + DComp Commit. */
void nucleus_tao_overlay_gl_present(GlSurface *gl);

/**
 * Notifies the bridge of a surface size change (physical pixels).
 * DComp swapchains don't track the HWND: this resizes the swapchain
 * and rebuilds the intermediate texture + pbuffer. Call BEFORE the
 * next make_current/render. No-op when the size is unchanged.
 */
void nucleus_tao_overlay_gl_resize(GlSurface *gl, int widthPx, int heightPx);

/** Stash the OS mouse/wheel message currently being processed so
 *  NativeView redispatch can SendMessage the original instead of
 *  reconstructing deltas. */
void nucleus_tao_remember_native_input(HWND hwnd, UINT msg, WPARAM w, LPARAM l);

/** Replay the stashed message onto [target]. Wheel LPARAMs stay
 *  screen-space; mouse LPARAMs are mapped from the source HWND. */
/* Replays the remembered message onto [target] when it is of kind
 * [expectedMsg] (the message the caller would otherwise synthesise); returns
 * FALSE when nothing matching is remembered. [post] queues it with
 * PostMessageW instead of SendMessageW — for a child HWND, whose handler may
 * open a modal loop (an EDIT's context menu on WM_RBUTTONUP) that must not
 * run inside the Compose pointer dispatch that is forwarding the event. */
BOOL nucleus_tao_replay_last_native_input(HWND target, UINT expectedMsg, BOOL post);

#ifdef __cplusplus
}
#endif

#endif
