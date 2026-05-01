package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_glx"

/**
 * JNI bridge to the GLX helper that turns a Tao Linux X11 window (XID) into an
 * OpenGL-rendering surface usable from Skiko.
 *
 * Linux counterpart of [NativeMetalBridge] (macOS) and [NativeTaoGlBridge]
 * (Windows). All methods must be invoked from the thread that owns the Tao
 * event loop — `glXMakeCurrent` is per-thread.
 *
 * Why GLX (not EGL): Skiko's pre-built `libskiko-linux-x64.so` is linked with
 * `-lGL` only and ships the GLX flavour of `GrGLMakeNativeInterface()`, which
 * resolves the current context through `glXGetCurrentContext`. JetBrains/
 * skiko#1052 (merged 2026-01-29) added `-lEGL` + the EGL flavour, but it's
 * gated on `targetArch == Arch.Arm64` — Linux x64 still ships GLX-only as of
 * Skiko 0.144.x. The JVM therefore forces `GDK_BACKEND=x11` at startup so Tao
 * always opens an X11 window (XWayland on Wayland sessions), which is enough
 * for GLX to function. A future EGL bridge could replace this on aarch64 (or
 * on x64 once Skiko ships EGL there too).
 *
 * Visual matching: if GTK's window already has a GLX-compatible visual
 * (typical on compositing desktops), the helper renders directly into it.
 * Otherwise it creates an input-transparent X11 child window with its own
 * GLX-chosen visual on top of the GTK parent — pointer / keyboard events
 * still propagate to GTK and tao via XShape masking.
 */
internal object NativeTaoGlxBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoGlxBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Creates a GLX context bound to the X11 [xid] reachable through [display]
     * (= `Display *`), makes it current on the calling thread and returns an
     * opaque attachment handle. [widthPx] / [heightPx] are used as the initial
     * size of the GL child window if the parent's visual isn't GL-compatible.
     */
    @JvmStatic
    external fun nativeAttach(
        display: Long,
        xid: Long,
        widthPx: Int,
        heightPx: Int,
    ): Long

    @JvmStatic
    external fun nativeDetach(handle: Long)

    /** Defensive: re-binds the GLX context on the current thread. */
    @JvmStatic
    external fun nativeMakeCurrent(handle: Long)

    /**
     * Stores new dimensions and resizes the GL child window (if any) so the
     * next render targets the right size. X11 doesn't auto-grow children with
     * their parent — without this the GL canvas freezes at its initial size.
     */
    @JvmStatic
    external fun nativeResize(handle: Long, widthPx: Int, heightPx: Int, scale: Float)

    /** Pumps the back-buffer to screen via `glXSwapBuffers`. */
    @JvmStatic
    external fun nativePresent(handle: Long)

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int

    /**
     * Clips the visible region of the GL surface (and of the GTK parent) to a
     * rounded rectangle of size `widthPx × heightPx` with the given corner
     * radius (in physical pixels). Pass `cornerRadiusPx = 0` to reset to a
     * sharp rectangle — used when the window enters the maximized / fullscreen
     * state, matching `decorated-window-jni`'s `window.shape = null` path.
     *
     * Implemented via `XShapeCombineRectangles(ShapeBounding)` on both the GL
     * child window and the GTK parent — same XShape extension that
     * `java.awt.Window.setShape(RoundRectangle2D)` uses internally.
     */
    @JvmStatic
    external fun nativeSetRoundedShape(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Int,
    )

    /**
     * Sets a cursor icon on the GL surface. `code` follows [TaoCursorIcon]
     * so callers stay symmetric across platforms. On Linux this must be
     * invoked through the GLX helper rather than through tao's
     * `Window::set_cursor_icon` because the GTK parent's cursor doesn't
     * propagate to the child X11 window we render into — Compose's
     * `PointerIcon.Text` would otherwise stay invisible inside text fields.
     */
    @JvmStatic
    external fun nativeSetCursorIcon(handle: Long, code: Int)
}
