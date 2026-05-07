package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_windows_native_view"

/**
 * JNI bridge for the Windows overlay HWND — a `WS_POPUP` owned window
 * with `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` that hosts a Compose scene
 * rendered through a transparent WGL context (`WGL_ALPHA_BITS_ARB = 8`
 * + `DwmEnableBlurBehindWindow` with an empty region).
 *
 * The overlay HGLRC joins the host's WGL share group via
 * `wglCreateContextAttribsARB(.., hostHGLRC, ..)`, sharing shaders /
 * programs / textures while keeping its own `GrDirectContext`. Pixel
 * format matches the host's exactly (`wglShareLists` requirement,
 * carried over to the ARB share path on every known driver).
 *
 * Threading: every entry point must run on the owner HWND's UI thread.
 */
internal object NativeTaoWindowsOverlayBridge {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoWindowsOverlayBridge::class.java)

    interface OverlayEventCallback {
        /** [type] = 1 down, 2 up, 3 move. [button] = 0 none, 1 primary, 2 secondary, 3 middle. */
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int)

        /** WHEEL_DELTA-normalized scroll units. */
        @Suppress("FunctionParameterNaming")
        fun onScroll(x: Float, y: Float, dx: Float, dy: Float)
    }

    interface OverlayKeyCallback {
        /** [type] = 1 down, 2 up. Returns true if consumed. */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int): Boolean
    }

    @JvmStatic
    external fun nativeCreateOverlay(ownerHwnd: Long): Long

    @JvmStatic
    external fun nativeSetOverlayFrame(overlay: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)

    @JvmStatic
    external fun nativeSetOverlayRegions(overlay: Long, rectsXYWHPx: FloatArray, count: Int)

    @JvmStatic
    external fun nativeSetOverlayCallback(overlay: Long, callback: OverlayEventCallback?)

    @JvmStatic
    external fun nativeSetOverlayKeyCallback(overlay: Long, callback: OverlayKeyCallback?)

    /** Binds the overlay's WGL context (`wglMakeCurrent`). */
    @JvmStatic
    external fun nativeMakeCurrent(overlay: Long): Boolean

    /** Presents the back-buffer (`SwapBuffers` followed by `DwmFlush()` for vsync). */
    @JvmStatic
    external fun nativeSwapBuffers(overlay: Long)

    @JvmStatic
    external fun nativeReleaseOverlay(overlay: Long)
}
