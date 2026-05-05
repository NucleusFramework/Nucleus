package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_windows_deco"

/**
 * JNI bridge to the WndProc subclass that gives a Tao HWND a custom title bar
 * (client-area extension via `WM_NCCALCSIZE`, hit-test routing via
 * `WM_NCHITTEST`, DWM shadow via `DwmExtendFrameIntoClientArea`).
 *
 * Mirrors the API of `decorated-window-jni`'s `JniWindowsDecorationBridge`,
 * minus the Skiko-AWT child-window plumbing (Tao renders into the HWND
 * directly via WGL).
 */
internal object NativeTaoWindowsDecoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoWindowsDecoBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeInstallDecoration(hwnd: Long, titleBarHeightPx: Int)

    @JvmStatic
    external fun nativeUninstallDecoration(hwnd: Long)

    @JvmStatic
    external fun nativeSetTitleBarHeight(hwnd: Long, heightPx: Int)

    /** ARGB; updates the WM_ERASEBKGND fill, DWM caption/border colors, and
     * dark-mode flag based on luminance. */
    @JvmStatic
    external fun nativeSetBackgroundColor(hwnd: Long, argb: Int)

    @JvmStatic
    external fun nativeSetFullscreen(hwnd: Long, fullscreen: Boolean)

    @JvmStatic
    external fun nativeIsFullscreen(hwnd: Long): Boolean

    /**
     * Sets the owner of [childHwnd] to [ownerHwnd] via `GWLP_HWNDPARENT`. The
     * child stays above the owner in z-order, is hidden when the owner is
     * minimised, and does not appear in the taskbar. Pass `0` for [ownerHwnd]
     * to clear the relationship.
     *
     * Used by `DecoratedDialog` to mirror the native owner semantics of an
     * AWT `JDialog`.
     */
    @JvmStatic
    external fun nativeSetOwner(childHwnd: Long, ownerHwnd: Long)

    /**
     * Returns the window's outer bounds as `[x, y, width, height]` in physical
     * screen pixels, or `null` if the HWND is invalid.
     */
    @JvmStatic
    external fun nativeGetWindowRect(hwnd: Long): LongArray?

    /**
     * Returns the primary monitor's work area (full screen minus taskbar) as
     * `[x, y, width, height]` in physical pixels. Used to resolve
     * [androidx.compose.ui.window.WindowPosition.Aligned] for the initial
     * outer position of a window.
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorWorkArea(): LongArray?

    /**
     * Returns the primary monitor's scale factor encoded as `(scale * 1000)`.
     * Falls back gracefully when `GetDpiForSystem` is unavailable. Used as a
     * scale source while a Tao window's own scale factor is not yet
     * resolvable (the window object exists but the native HWND has not been
     * created yet).
     */
    @JvmStatic
    external fun nativeGetPrimaryMonitorScaleMilli(): Int
}
