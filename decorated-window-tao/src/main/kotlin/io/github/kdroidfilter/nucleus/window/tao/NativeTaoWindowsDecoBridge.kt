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
}
