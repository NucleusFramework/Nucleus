package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_windows_native_view"

/**
 * JNI bridge for the Windows NativeView subview path. Reparents a
 * user-supplied child HWND under the Tao main HWND, sizes it via
 * `SetWindowPos`, and clips it via `SetWindowRgn(CreateRoundRectRgn)`
 * for rounded corners.
 *
 * Mirrors macOS `NativeTaoMacOsNativeViewBridge`, but operates on
 * HWNDs instead of NSViews. All entry points must run on the Tao
 * main UI thread (= the thread that owns the parent HWND).
 */
internal object NativeTaoWindowsNativeViewBridge {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoWindowsNativeViewBridge::class.java)

    @JvmStatic
    external fun nativeAttach(
        parentHwnd: Long,
        childHwnd: Long,
    )

    @JvmStatic
    external fun nativeDetach(childHwnd: Long)

    @JvmStatic
    external fun nativeSetFrame(
        parentHwnd: Long,
        childHwnd: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    /**
     * Clips [childHwnd] with a rounded-rect region. [radiusPx] is capped
     * natively at `min(w, h) / 2`; pass `Float.POSITIVE_INFINITY` for
     * fully circular.
     */
    @JvmStatic
    external fun nativeSetCornerRadius(
        parentHwnd: Long,
        childHwnd: Long,
        radiusPx: Float,
    )

    /**
     * Returns true if the calling thread's currently-focused HWND is
     * [parentHwnd] itself or any descendant. Used by the Windows
     * `DecoratedWindow` to keep the window visually "active" when
     * keyboard focus moves to an embedded child HWND like WebView2 —
     * Tao reports the main HWND as unfocused (Win32 focus is on the
     * child) but for app purposes the window is still in use.
     */
    @JvmStatic
    external fun nativeIsFocusInTree(parentHwnd: Long): Boolean

    /**
     * Synthesises a mouse message onto [childHwnd] at the parent-client
     * (top-left, physical pixels) coordinate. When [childHwnd] is not a
     * window (WebView2 CompositionController), the message is sent to
     * [parentHwnd] so a parent subclass can forward it.
     * [type]: 1 down, 2 up, 3 move. [button]: 0 none, 1 primary, 2 secondary.
     */
    @JvmStatic
    external fun nativeDispatchPointer(
        parentHwnd: Long,
        childHwnd: Long,
        type: Int,
        xPx: Float,
        yPx: Float,
        button: Int,
        pressed: Boolean,
    )

    /** Forwards a Compose scroll delta as `WM_MOUSEWHEEL` / `WM_MOUSEHWHEEL`. */
    @JvmStatic
    external fun nativeDispatchScroll(
        parentHwnd: Long,
        childHwnd: Long,
        xPx: Float,
        yPx: Float,
        dx: Float,
        dy: Float,
    )
}
