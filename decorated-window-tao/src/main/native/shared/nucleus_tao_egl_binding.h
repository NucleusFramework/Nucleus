/**
 * The per-thread EGL binding snapshot, shared by the Linux and Windows texture
 * bridges (nucleus_tao_texture_linux.c, nucleus_tao_texture.c). Header-only, so
 * neither build script needs to know about it.
 *
 * Why it exists: the Kotlin side binds a surface of its own from paths that can
 * run inside *another* surface's render pass — a tray panel's bring-up, resize,
 * render and teardown all arrive from the caller's composition, i.e. from inside
 * a window scene's ComposeScene.render(). Leaving that surface current sends the
 * remainder of the host frame, and its flushAndSubmit, somewhere it does not
 * belong. So the displaced binding is snapshotted before and put back after.
 *
 * What is shared is the bookkeeping, which is the part with the invariants:
 *   - one slot, one level deep. A nested save would overwrite the outer one, so
 *     [save] refuses to nest and the caller runs without rebinding — the
 *     enclosing scope restores when it unwinds, which is the same guarantee.
 *   - [take] hands the snapshot back and clears the slot in one step, so a
 *     failed re-bind cannot leave a stale snapshot behind to be restored twice.
 *
 * What is NOT shared, because it genuinely differs: how each platform reads the
 * current binding (dlopen-ed entry points vs the host EGL registry's), where the
 * slot lives (a __thread slot on Linux, whose test producer really does reach the
 * bridge from its own thread; a plain static on Windows, where every entry point
 * is documented event-loop-thread only and __declspec(thread) would need the
 * CRT's _tls_used that /NODEFAULTLIB drops), and what happens when nothing was
 * displaced (Linux reports it to the caller, which unbinds through the
 * attachment that knows a display; Windows unbinds natively).
 *
 * Handles are void* rather than EGLDisplay/EGLSurface/EGLContext: the two
 * callers get those typedefs from different places (vendored ANGLE headers vs
 * the bridge's own declarations), and EGL defines them all as void* anyway.
 */
#ifndef NUCLEUS_TAO_EGL_BINDING_H
#define NUCLEUS_TAO_EGL_BINDING_H

typedef struct {
    void *display;
    void *context;
    void *draw;
    void *read;
    int   saved;
} NucleusTaoEglBindingSlot;

/**
 * Stores the binding the caller just read into [slot]. Returns 0 — and keeps the
 * outstanding snapshot untouched — when [slot] already holds one, which is the
 * caller's signal not to rebind.
 */
static inline int nucleus_tao_egl_binding_save(
    NucleusTaoEglBindingSlot *slot,
    void *display,
    void *context,
    void *draw,
    void *read)
{
    if (slot->saved) return 0;
    slot->display = display;
    slot->context = context;
    slot->draw = draw;
    slot->read = read;
    slot->saved = 1;
    return 1;
}

/**
 * Hands the snapshot back through the out params and clears [slot]. Returns 0
 * when no snapshot was outstanding, leaving the out params untouched.
 *
 * A returned snapshot may still describe "nothing was current" (`display` or
 * `context` NULL) — the callers differ on what to do about that, so the test is
 * left to them. The slot is cleared either way: a snapshot is consumed once,
 * even if the re-bind the caller attempts with it fails.
 */
static inline int nucleus_tao_egl_binding_take(
    NucleusTaoEglBindingSlot *slot,
    void **display,
    void **context,
    void **draw,
    void **read)
{
    if (!slot->saved) return 0;
    *display = slot->display;
    *context = slot->context;
    *draw = slot->draw;
    *read = slot->read;
    slot->display = NULL;
    slot->context = NULL;
    slot->draw = NULL;
    slot->read = NULL;
    slot->saved = 0;
    return 1;
}

#endif /* NUCLEUS_TAO_EGL_BINDING_H */
