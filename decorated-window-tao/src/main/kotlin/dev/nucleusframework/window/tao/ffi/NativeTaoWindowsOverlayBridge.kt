package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_windows_native_view"

/**
 * JNI bridge for the Windows overlay HWND — a `WS_POPUP` owned window
 * with `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_NOREDIRECTIONBITMAP`
 * that composites the host Compose scene over NativeView child HWNDs
 * (Win32 children always paint above their parent) through the
 * EGL/ANGLE + DirectComposition bridge
 * (nucleus_tao_windows_overlay_dcomp.cpp).
 *
 * Threading: every entry point must run on the owner HWND's UI thread.
 */
internal object NativeTaoWindowsOverlayBridge {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoWindowsOverlayBridge::class.java)

    interface OverlayEventCallback {
        /** [type] = 1 down, 2 up, 3 move. [button] = 0 none, 1 primary, 2 secondary, 3 middle. */
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        )

        /** WHEEL_DELTA-normalized scroll units. */
        @Suppress("FunctionParameterNaming")
        fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        )
    }

    interface OverlayKeyCallback {
        /** [type] = 1 down, 2 up. Returns true if consumed. */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ): Boolean
    }

    @JvmStatic
    external fun nativeCreateOverlay(ownerHwnd: Long): Long

    @JvmStatic
    external fun nativeSetOverlayFrame(
        overlay: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    @JvmStatic
    external fun nativeSetOverlayRegions(
        overlay: Long,
        rectsXYWHPx: FloatArray,
        count: Int,
    )

    @JvmStatic
    external fun nativeSetOverlayCallback(
        overlay: Long,
        callback: OverlayEventCallback?,
    )

    @JvmStatic
    external fun nativeSetOverlayKeyCallback(
        overlay: Long,
        callback: OverlayKeyCallback?,
    )

    /** Binds the overlay's pbuffer surface on the host's EGLContext. */
    @JvmStatic
    external fun nativeMakeCurrent(overlay: Long): Boolean

    /** Presents the frame (CopyResource + `Present(0)` + DComp `Commit`). */
    @JvmStatic
    external fun nativeSwapBuffers(overlay: Long)

    @JvmStatic
    external fun nativeReleaseOverlay(overlay: Long)

    /**
     * Sets the cursor displayed when the pointer hovers the overlay.
     * [cursorCode] is one of [CURSOR_DEFAULT], [CURSOR_TEXT], [CURSOR_HAND],
     * [CURSOR_CROSSHAIR]. The value is stored on the overlay's state and
     * applied in `WM_SETCURSOR`. Called from the overlay scene's
     * `PlatformContext.setPointerIcon` whenever Compose decides the cursor
     * should change (e.g. hovering a `BasicTextField` → IBeam).
     */
    @JvmStatic
    external fun nativeSetCursor(
        overlay: Long,
        cursorCode: Int,
    )

    const val CURSOR_DEFAULT: Int = 0
    const val CURSOR_TEXT: Int = 1
    const val CURSOR_HAND: Int = 2
    const val CURSOR_CROSSHAIR: Int = 3
}
