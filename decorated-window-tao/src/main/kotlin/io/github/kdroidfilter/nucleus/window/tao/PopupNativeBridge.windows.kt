package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * Windows counterpart to the macOS [PopupNativeBridge]. Each instance
 * of `TaoPopupSceneLayerWindows` owns a top-level `WS_POPUP` HWND with
 * `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW`, plus its own transparent WGL
 * context joined to the host's share group (per
 * `nucleus_tao_windows_overlay_gl.c`).
 *
 * Outside-click dismissal is implemented via `SetCapture(hwndPopup)` +
 * tests in WM_LBUTTONDOWN/RBUTTONDOWN/MBUTTONDOWN against the popup
 * rect — replaces the heavier `WH_MOUSE_LL` global hook. The native
 * side handles capture handoff for nested popups (parent transfers to
 * child on open, child returns to parent on dismiss).
 *
 * Threading: every entry point runs on the parent HWND's UI thread.
 */
internal object PopupNativeBridgeWindows {
    private const val LIBRARY_NAME = "nucleus_tao_windows_native_view"

    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, PopupNativeBridgeWindows::class.java)

    interface EventCallback {
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int)

        @Suppress("FunctionParameterNaming")
        fun onScroll(x: Float, y: Float, dx: Float, dy: Float)

        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int)
    }

    interface OutsideClickListener {
        /** [type] = 1 (always Press). [button] = 1 primary, 2 secondary, 3 other. */
        fun onOutsideClick(type: Int, button: Int)
    }

    @JvmStatic
    external fun nativeCreatePanel(
        parentHwnd: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ): Long

    @JvmStatic
    external fun nativeSetFrameInWindow(panel: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int)

    @JvmStatic
    external fun nativeSetFocusable(panel: Long, focusable: Boolean)

    /** Returns the popup HWND itself (the popup has no separate "content view" on Windows). */
    @JvmStatic
    external fun nativeContentHwnd(panel: Long): Long

    @JvmStatic
    external fun nativeMakeCurrent(panel: Long): Boolean

    @JvmStatic
    external fun nativeSwapBuffers(panel: Long)

    @JvmStatic
    external fun nativeSetEventCallback(panel: Long, callback: EventCallback?)

    @JvmStatic
    external fun nativeInstallOutsideClickMonitor(panel: Long, listener: OutsideClickListener)

    @JvmStatic
    external fun nativeUninstallOutsideClickMonitor(panel: Long)

    @JvmStatic
    external fun nativeRelease(panel: Long)
}
