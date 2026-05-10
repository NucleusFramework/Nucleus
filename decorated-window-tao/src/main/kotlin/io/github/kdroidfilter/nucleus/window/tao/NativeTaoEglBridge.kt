package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_egl"

/**
 * JNI bridge to the EGL helper that turns a Tao Linux window (X11 XID — and
 * eventually a Wayland `wl_surface*`) into an EGL-rendering surface usable
 * from Skiko via [org.jetbrains.skia.GLAssembledInterface] +
 * [org.jetbrains.skia.DirectContext.makeGLWithInterface].
 *
 * Replaces [NativeTaoGlxBridge] on the path selected by the JVM system
 * property `nucleus.tao.linux.renderer = glx | egl` (see
 * [io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneHostLinux]).
 *
 * Phase 1 (this commit): X11 only. Wayland (`nativeAttachWayland`) and the
 * `wp_fractional_scale_v1` / `wp_viewporter` plumbing land in follow-ups.
 *
 * All methods must run on the thread that owns the EGL context — `eglMakeCurrent`
 * is per-thread. In our usage that's the Tao event-loop thread, same model
 * as the GLX path.
 */
internal object NativeTaoEglBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoEglBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Creates an EGL display + window surface bound to the X11 [xid] reachable
     * through [displayPtr] (= `Display *`), makes the context current on the
     * calling thread and returns an opaque attachment handle. Returns 0 on
     * failure (driver lacks desktop GL, X visual mismatch, etc.).
     */
    @JvmStatic
    external fun nativeAttachX11(
        displayPtr: Long,
        xid: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /**
     * Wayland-native attach. Wraps [wlSurfacePtr] (= `wl_surface*`) into a
     * `wl_egl_window` via libwayland-egl and binds an EGL window surface
     * against `eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, [wlDisplayPtr])`.
     *
     * [widthPx] / [heightPx] are the **physical** pixel dimensions (logical
     * × scale) — the compositor expects buffer dimensions, not logical ones.
     *
     * Returns 0 if libwayland-egl isn't available on the system; the caller
     * should fall back to the X11 path or surface a clear error.
     */
    @JvmStatic
    external fun nativeAttachWayland(
        wlDisplayPtr: Long,
        wlSurfacePtr: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    @JvmStatic
    external fun nativeDetach(handle: Long)

    /** Re-binds the EGL context on the current thread. */
    @JvmStatic
    external fun nativeMakeCurrent(handle: Long)

    /**
     * Releases the EGL context from the current thread (no context current
     * after the call). Required to hand the context between the main render
     * thread (which builds the frame via Skia) and the swap thread (which
     * blocks in `eglSwapBuffers` waiting for the compositor's vsync — see
     * `TaoComposeSceneHostLinux.SwapThread`).
     */
    @JvmStatic
    external fun nativeReleaseCurrent(handle: Long)

    /**
     * Stores new dimensions. On X11 the EGL surface follows the X window
     * automatically (GTK reissues XResizeWindow on the parent), so this is
     * a no-op aside from the cached [widthPx] / [heightPx]. The Wayland
     * path will additionally invoke `wl_egl_window_resize`.
     */
    @JvmStatic
    external fun nativeResize(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        scale: Float,
    )

    /** Pumps the back-buffer to screen via `eglSwapBuffers`. */
    @JvmStatic
    external fun nativePresent(handle: Long)

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int

    /**
     * Switches the EGL surface from "fully input-transparent" to
     * region-restricted input routing. Used by the overlay slot of
     * [NativeView] on Linux: only points inside one of [rectsPx] are
     * delivered to the Compose surface; everything else falls through
     * to GTK / the embedded native widget — same UX as macOS's
     * `NucleusTaoNativeOverlayView.hitTest:` returning `nil` for
     * non-interactive areas.
     *
     * [rectsPx] is a flat `(x, y, w, h) × count` float array in
     * surface-local pixels with a top-left origin (matches Compose
     * `boundsInWindow`). [count] = 0 resets to the default empty
     * region (full passthrough).
     *
     * Backend coverage:
     *  - **Wayland**: `wl_compositor.create_region` + `wl_region.add` per
     *    rect + `wl_surface.set_input_region` + `wl_surface.commit`.
     *  - **X11 child-window fallback** (visual mismatch): applies
     *    `XShapeCombineRectangles(ShapeInput, ShapeSet)` on the child.
     *  - **X11 default-visual**: no separate Compose window, no shape
     *    we can apply without breaking GTK; falls through silently
     *    (overlay clicks won't be intercepted in that path).
     */
    @JvmStatic
    external fun nativeSetInputRegion(
        handle: Long,
        rectsPx: FloatArray,
        count: Int,
    )

    /**
     * Returns the address of a C function `void* fn(void* ctx, const char*
     * name)` matching Skia's `GrGLGetProc` signature. Pass to
     * [org.jetbrains.skia.GLAssembledInterface.createFromNativePointers] with
     * `ctxPtr = 0`. The function pointer is stable for the lifetime of the
     * shared object and may be reused across windows.
     *
     * Resolves through `eglGetProcAddress` first, falling back to
     * `dlsym(libGL.so.1)` for ancient drivers that don't honor
     * `EGL_KHR_get_all_proc_addresses` for core 1.0/1.1 entry points.
     */
    @JvmStatic
    external fun nativeGetProcAddrFunctionPointer(): Long
}
