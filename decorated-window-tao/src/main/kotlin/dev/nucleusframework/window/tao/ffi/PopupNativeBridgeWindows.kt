package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * Windows counterpart to the macOS [PopupNativeBridge]. Each
 * TaoPopupSceneLayerWindows owns a top-level WS_POPUP HWND with
 * WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, plus a transparent DComp-presented surface
 * using the host HGLRC.
 *
 * Outside-click dismissal uses a thread-local WH_MOUSE hook. The native
 * side compares clicks against the logical content rect, not the inflated
 * draw-bounds HWND used for Compose-drawn elevation.
 */
@Suppress("TooManyFunctions")
internal object PopupNativeBridgeWindows {
    private const val LIBRARY_NAME = "nucleus_tao_windows_native_view"

    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, PopupNativeBridgeWindows::class.java)

    interface EventCallback {
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        )

        @Suppress("FunctionParameterNaming")
        fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        )

        /** [type] = 1 down, 2 up. [isRepeat]: a key-down sent again because the key is held. */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
            isRepeat: Boolean,
        )
    }

    interface OutsideClickListener {
        /** [type] = 1 (always Press). [button] = 1 primary, 2 secondary, 3 other. */
        fun onOutsideClick(
            type: Int,
            button: Int,
        )
    }

    /**
     * [parentHwnd] may be `0` for a standalone (ownerless) panel — [xPx]/[yPx]
     * are then absolute screen coordinates instead of parent-client-relative.
     */
    @JvmStatic
    external fun nativeCreatePanel(
        parentHwnd: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** Shows/hides the panel (SW_SHOWNOACTIVATE / SW_HIDE) without releasing it. */
    @JvmStatic
    external fun nativeSetPanelVisible(
        panel: Long,
        visible: Boolean,
    )

    /**
     * Raises/restores the system timer resolution (timeBeginPeriod(1),
     * refcounted). Required while pacing animations from JVM scheduled
     * executors: the default ~15.6 ms quantum halves the frame rate.
     */
    @JvmStatic
    external fun nativeSetHighResTimer(enable: Boolean)

    /** Sets the client-area cursor from a [TaoCursorIcon] constant. */
    @JvmStatic
    external fun nativeSetPanelCursor(
        panel: Long,
        cursorIcon: Int,
    )

    @JvmStatic
    external fun nativeSetFrameInWindow(
        panel: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
        contentXPx: Int,
        contentYPx: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
    )

    @JvmStatic
    external fun nativeSetFocusable(
        panel: Long,
        focusable: Boolean,
    )

    /** Returns the popup HWND itself. */
    @JvmStatic
    external fun nativeContentHwnd(panel: Long): Long

    @JvmStatic
    external fun nativeMakeCurrent(panel: Long): Boolean

    @JvmStatic
    external fun nativeSwapBuffers(panel: Long)

    @JvmStatic
    external fun nativeSetEventCallback(
        panel: Long,
        callback: EventCallback?,
    )

    @JvmStatic
    external fun nativeInstallOutsideClickMonitor(
        panel: Long,
        listener: OutsideClickListener,
    )

    @JvmStatic
    external fun nativeUninstallOutsideClickMonitor(panel: Long)

    @JvmStatic
    external fun nativeRelease(panel: Long)
}
