package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_gl"

/**
 * JNI bridge to the EGL/ANGLE helper that turns a Tao HWND into a
 * GL-ES-rendering surface usable from Skiko. Windows-only counterpart of
 * [NativeMetalBridge]. ANGLE translates the ES calls to Direct3D 11
 * (WARP software fallback included), so this works on RDP/VMs and
 * driverless machines too.
 *
 * The ES context is bound per-thread (`eglMakeCurrent`). Rendering AND
 * presentation both run inline on the event-loop thread: a cross-thread
 * present on ANGLE's shared per-display D3D11 device deadlocks the
 * global display lock (the reason the old WGL backend's swap thread
 * never applied here).
 */
@Suppress("TooManyFunctions") // one external per JNI entry point
internal object NativeTaoGlBridge {
    init {
        // ANGLE (libEGL + libGLESv2) backs the Direct3D-11 render path.
        // They ship next to the other Windows native libs but are only
        // present on win32-*; load libGLESv2 by absolute path FIRST (libEGL
        // depends on it) so the native `LoadLibraryW("libEGL.dll")` resolves
        // the already-loaded module by base name.
        //
        // libGLESv2 must ALSO sit in libEGL's cache directory: at eglInitialize
        // ANGLE's libEGL loads libGLESv2 via an absolute path built from its own
        // module dir (SearchType::ModuleDir). The content-addressed loader gives
        // each library its own <fingerprint>/ dir, so without this sidecar the
        // two DLLs land in different dirs and ANGLE can't find libGLESv2.
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            NativeLibraryLoader.load("libGLESv2", NativeTaoGlBridge::class.java)
            NativeLibraryLoader.load(
                "libEGL",
                NativeTaoGlBridge::class.java,
                sidecarFiles = listOf("libGLESv2.dll"),
            )
        }
    }

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoGlBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Creates an input-transparent render-surface child HWND covering the
     * window's client area, binds an ANGLE ES context to it and makes it
     * current on the calling thread. Returns an opaque attachment handle,
     * or 0 on failure (ANGLE DLLs missing or D3D11 unavailable).
     *
     * Rendering goes through a child (not the Tao HWND itself), kept at
     * the bottom of the sibling z-order so NativeView children (WebView, …)
     * composite above the Compose canvas.
     */
    @JvmStatic
    external fun nativeAttach(hwnd: Long): Long

    /**
     * Address of the native GrGLGetProc trampoline routing to ANGLE's
     * `eglGetProcAddress`. Passed to
     * [org.jetbrains.skia.GLAssembledInterface.createFromNativePointers] so
     * `DirectContext.makeGLWithInterface` can assemble an EGL/ES GL interface
     * (the default `makeGL()` uses WGL and fails on ANGLE).
     */
    @JvmStatic
    external fun nativeEglGetProcFn(): Long

    @JvmStatic
    external fun nativeDetach(handle: Long)

    /** Re-binds the ES context on the current thread. Defensive — `attach`
     * already makes it current, but overlay/popup renderers re-bind their
     * own pbuffer surfaces on the same thread between host frames. */
    @JvmStatic
    external fun nativeMakeCurrent(handle: Long)

    /** Stores the new dimensions and updates the GL viewport. Call on resize
     * or scale-factor change before the next render. */
    @JvmStatic
    external fun nativeResize(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        scale: Float,
    )

    /** Pumps the back-buffer to screen via `eglSwapBuffers` (vsync-paced,
     * inline). Must be invoked **after** `Surface.flushAndSubmit`. */
    @JvmStatic
    external fun nativePresent(handle: Long)

    /**
     * Age of the back buffer the next frame renders into (#755): `1` when
     * the surface keeps the previous frame (`EGL_BUFFER_PRESERVED` on ANGLE's
     * offscreen texture) and presents sub-rectangles (`EGL_NV_post_sub_buffer`),
     * `-1` when it supports no partial redraw.
     */
    @JvmStatic
    external fun nativeBufferAge(handle: Long): Int

    /**
     * Presents like [nativePresent], copying only the `(x, y, width, height)`
     * rectangle — surface pixels, **bottom-left** origin (the EGL convention)
     * — which DXGI also receives as the frame's dirty rect (#755).
     */
    @JvmStatic
    external fun nativePresentWithDamage(
        handle: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )

    /**
     * Presents one frame cleared to [argb]. Used by the fullscreen toggle
     * right after [nativeResize]: DWM registers the child HWND resize
     * immediately but the resized swapchain's first buffer only reaches it
     * at the next present — a composition falling into that gap shows an
     * uninitialized (black) buffer. Sub-millisecond, shrinks the gap and
     * colours it with the themed background. Callers must `resetGLAll()`
     * the shared DirectContext afterwards.
     */
    @JvmStatic
    external fun nativeClearPresent(
        handle: Long,
        argb: Int,
    )

    /**
     * Toggles VSync via `eglSwapInterval`: `true` = pace presents on the display
     * refresh (default), `false` = present immediately. Dropped to `false` for
     * the duration of the OS modal resize/move loop so the synchronous
     * per-WM_SIZE present doesn't block on VBlank while a border is dragged.
     */
    @JvmStatic
    external fun nativeSetVSyncEnabled(
        handle: Long,
        enabled: Boolean,
    )

    @JvmStatic
    external fun nativeWidth(handle: Long): Int

    @JvmStatic
    external fun nativeHeight(handle: Long): Int

    /**
     * Allows mirroring frames into a DirectComposition visual over the window
     * while it overhangs the desktop (opaque windows only). The blt present is
     * clipped at the desktop's edge; the visual reaches DWM whole — the part of
     * the window off-screen, taskbar thumbnails and Alt+Tab included — and
     * steps aside while the window's size changes. Nothing is allocated while
     * the window is fully on-screen. See `nucleus_tao_gl_mirror.cpp`.
     *
     * Returns whether mirroring is on: `false` when [enabled] is, or when the
     * driver lacks what it needs (DirectComposition, the ANGLE EGLImage
     * extension). A failure later on is reported by [nativeNeedsRepaintOnMove].
     */
    @JvmStatic
    external fun nativeSetMirrorEnabled(
        handle: Long,
        enabled: Boolean,
    ): Boolean

    /**
     * Whether a window move must be answered with a frame: true without a
     * working mirror (the blt present lost what was off-screen), and while one
     * is allocated (a frame swapped fully on-screen releases it).
     */
    @JvmStatic
    external fun nativeNeedsRepaintOnMove(handle: Long): Boolean

    /** Whether a frame mirror is allocated right now (the window overhangs the desktop). */
    @JvmStatic
    external fun nativeHasMirror(handle: Long): Boolean

    /**
     * Bootstraps the shared ANGLE display/config/context against a 1x1
     * pbuffer when no window host has attached yet. Standalone popup panels
     * (see [TaoStandalonePopup]) need the shared context to exist before
     * `nativeCreatePanel`; in apps that already opened a Tao window this is
     * a cheap no-op. Returns `false` when ANGLE/D3D11 is unavailable.
     */
    @JvmStatic
    external fun nativeEnsureHeadlessContext(): Boolean
}
