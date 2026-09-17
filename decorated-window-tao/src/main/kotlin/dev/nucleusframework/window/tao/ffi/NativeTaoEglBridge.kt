package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_egl"

/**
 * JNI bridge to the EGL helper that turns a Tao Linux window (X11 XID — and
 * eventually a Wayland `wl_surface*`) into an EGL-rendering surface usable
 * from Skiko via [org.jetbrains.skia.GLAssembledInterface] +
 * [org.jetbrains.skia.DirectContext.makeGLWithInterface].
 *
 * EGL is the only Linux renderer: the legacy GLX helper
 * (`nucleus_tao_glx.c` / `NativeTaoGlxBridge`) has been removed, and both
 * backends go through this bridge — `nativeAttachX11` on X11/XWayland
 * sessions, `nativeAttachWayland` on native Wayland (selection follows GDK's
 * backend; `NUCLEUS_TAO_LINUX_RENDERER=x11` forces XWayland, see
 * [dev.nucleusframework.window.tao.scene.TaoComposeSceneHostLinux]).
 *
 * All methods must run on the thread that owns the EGL context — `eglMakeCurrent`
 * is per-thread. In our usage that's the Tao event-loop thread, same model
 * as the GLX path.
 */
@Suppress("TooManyFunctions")
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
     * [bufferScale] is GTK's integer surface scale, applied to the child
     * surface via `wl_surface.set_buffer_scale` so a `logical × scale` px
     * buffer is read as `logical` surface units (matching GTK's parent).
     * Without it the subsurface renders ~scale× oversized and input is
     * miscalibrated.
     *
     * [swapInterval] is passed to `eglSwapInterval`: 1 for toplevel-backed
     * attachments (FIFO pacing against the compositor's frame callbacks),
     * 0 for popup overlays whose EGL child hangs off GDK's own synchronized
     * `wl_subsurface` — FIFO commits there stay cached compositor-side and
     * Mesa's pending `wp_commit_timer_v1` timestamp is never consumed, so the
     * next `set_timestamp` raises a fatal "Commit already has timestamp"
     * protocol error (see [TaoWindow.isPopup]).
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
        bufferScale: Int,
        swapInterval: Int,
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

    /**
     * Positions the content subsurface at ([xLogical], [yLogical]) inside
     * GTK's parent surface. (0,0) for plain undecorated toplevels; the GTK
     * theme's shadow margins when the yaru-style hidden-titlebar CSD is
     * active, so the EGL content fills exactly the visible window area and
     * GTK's native drop shadow stays visible in the margin ring around it.
     * Cheap no-op when unchanged; no-op on X11.
     */
    @JvmStatic
    external fun nativeSetContentOffset(
        handle: Long,
        xLogical: Int,
        yLogical: Int,
    ): Boolean

    /**
     * Wayland only: declares which part of the content surface is fully opaque,
     * in surface (logical) units, with the four [cornerRadius]-sized corners
     * excluded (they are painted transparent so the drop shadow shows through).
     *
     * Without this the compositor must treat our full-window surface as
     * translucent and cannot cull the drop-shadow subsurface or GTK's toplevel
     * underneath it — it alpha-blends all three every frame, which shows up as
     * slower frame callbacks and therefore a slower resize. See
     * `docs/linux-wayland-resize-latency.md`.
     *
     * Pass `logicalW <= 0` when the window really is translucent, which clears
     * the region. Queued state — it lands with the next buffer commit.
     */
    @JvmStatic
    external fun nativeSetOpaqueRegion(
        handle: Long,
        logicalW: Int,
        logicalH: Int,
        cornerRadius: Int,
    )

    /** Pumps the back-buffer to screen via `eglSwapBuffers`. */
    @JvmStatic
    external fun nativePresent(handle: Long)

    /**
     * Calls `eglSwapInterval` on the EGL display. Must be called while the
     * EGL context is current (after [nativeMakeCurrent]).
     * Use `interval = 0` to disable vsync during resize (avoids blocking
     * for Wayland frame callbacks during buffer-size negotiation).
     * Restore `interval = 1` on the first stable-size frame.
     */
    @JvmStatic
    external fun nativeSetSwapInterval(
        handle: Long,
        interval: Int,
    )

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

    /**
     * Wayland only: puts the content sub-surface in `set_sync` (buffers apply
     * with GTK's toplevel commit, atomically with the positions of embedded
     * native views) or back in `set_desync` (buffers apply on their own).
     * No-op on X11.
     */
    @JvmStatic
    external fun nativeSetSubsurfaceSync(
        handle: Long,
        sync: Boolean,
    )
}
