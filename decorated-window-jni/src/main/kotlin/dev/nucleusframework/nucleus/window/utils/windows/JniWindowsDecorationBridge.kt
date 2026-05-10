package dev.nucleusframework.nucleus.window.utils.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_windows_decoration"

@Suppress("TooManyFunctions")
internal object JniWindowsDecorationBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, JniWindowsDecorationBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // Installs the custom decoration (subclasses WndProc, sets up DWM shadow).
    // Idempotent: if already installed, updates the title bar height.
    @JvmStatic
    external fun nativeInstallDecoration(
        hwnd: Long,
        titleBarHeightPx: Int,
    )

    // Removes the custom decoration and restores the original WndProc.
    @JvmStatic
    external fun nativeUninstallDecoration(hwnd: Long)

    // Toggles the forceHitTestClient flag. When true, WM_NCHITTEST returns
    // HTCLIENT in the title bar area so Compose handles the click.
    @JvmStatic
    external fun nativeSetForceHitTestClient(
        hwnd: Long,
        force: Boolean,
    )

    // Updates the title bar height used by the hit-test logic.
    @JvmStatic
    external fun nativeSetTitleBarHeight(
        hwnd: Long,
        heightPx: Int,
    )

    // Initiates a native window drag (with snap/tile support).
    // Called from Compose when an unconsumed press occurs in the title bar.
    @JvmStatic
    external fun nativeStartDrag(hwnd: Long)

    // Extracts the HWND from an AWT Window via JNI (bypasses JPMS restrictions).
    // Returns 0 if the handle cannot be obtained.
    @JvmStatic
    external fun nativeGetHwnd(awtWindow: java.awt.Window): Long

    // Applies rounded corners and DWM shadow to an undecorated dialog window (WS_POPUP).
    // Uses DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND (Windows 11+, no-op on older).
    @JvmStatic
    external fun nativeApplyDialogStyle(hwnd: Long)

    // Enters or exits native fullscreen mode.
    // Enter: saves style/exstyle/placement, removes caption/frame, covers the monitor.
    // Exit: restores saved style/exstyle/placement (maximized, floating, etc.).
    @JvmStatic
    external fun nativeSetFullscreen(
        hwnd: Long,
        fullscreen: Boolean,
    )

    // Returns true if the window is currently in native fullscreen mode.
    @JvmStatic
    external fun nativeIsFullscreen(hwnd: Long): Boolean

    // Sets the background fill color for WM_ERASEBKGND (avoids white flash on resize).
    // Pass the ARGB int from Compose Color.toArgb(); alpha is ignored (opaque fill).
    @JvmStatic
    external fun nativeSetBackgroundColor(
        hwnd: Long,
        argb: Int,
    )

    // Sets the minimum window size in logical pixels. The native
    // WM_GETMINMAXINFO handler applies DPI scaling automatically.
    // Pass (0, 0) to disable the override and fall back to AWT default.
    @JvmStatic
    external fun nativeSetMinimumSize(
        hwnd: Long,
        widthPx: Int,
        heightPx: Int,
    )

    // Sets the maximum window size in logical pixels. The native
    // WM_GETMINMAXINFO handler applies DPI scaling automatically.
    // Pass (0, 0) to disable the override and fall back to AWT default.
    @JvmStatic
    external fun nativeSetMaximumSize(
        hwnd: Long,
        widthPx: Int,
        heightPx: Int,
    )

    // Returns debug counters as a string (temporary).
    @JvmStatic
    external fun nativeGetDebugInfo(hwnd: Long): String
}
