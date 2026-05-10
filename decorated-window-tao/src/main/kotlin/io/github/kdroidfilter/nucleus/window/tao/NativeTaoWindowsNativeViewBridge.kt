package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

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
    external fun nativeAttach(parentHwnd: Long, childHwnd: Long)

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
}
