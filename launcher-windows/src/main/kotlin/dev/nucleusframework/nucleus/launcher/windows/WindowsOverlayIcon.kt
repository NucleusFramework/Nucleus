package dev.nucleusframework.nucleus.launcher.windows

import java.awt.Window
import java.util.logging.Logger

/**
 * Windows overlay icon API via JNI (ITaskbarList3::SetOverlayIcon).
 *
 * Displays a small 16x16 status icon on the bottom-right corner of the app's
 * taskbar button — commonly used for status indicators (online/busy, error, etc.).
 *
 * Unlike [WindowsBadgeManager] which requires APPX/MSIX packaging, overlay icons
 * work with **all packaging types** (APPX, NSIS, MSI, distributable).
 *
 * Thread-safe singleton.
 */
object WindowsOverlayIcon {
    private val logger = Logger.getLogger(WindowsOverlayIcon::class.java.simpleName)

    /** The last error message from a native operation, or null if the last operation succeeded. */
    var lastError: String? = null
        private set

    /** Whether the native library is loaded and functional on this platform. */
    val isAvailable: Boolean get() = NativeWindowsTaskbarBridge.isLoaded

    /**
     * Set an overlay icon on the taskbar button.
     *
     * @param window      The AWT window whose taskbar button gets the overlay.
     * @param icon        The icon source.
     * @param description Accessibility text describing the overlay.
     * @return true if the overlay was set successfully.
     */
    fun setIcon(
        window: Window,
        icon: TaskbarIconSource,
        description: String = "",
    ): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        val error =
            NativeWindowsTaskbarBridge.nativeSetOverlayIcon(
                window,
                iconType = icon.nativeType(),
                iconPath = icon.nativePath(),
                iconIndex = icon.nativeIndex(),
                description = description,
            )
        lastError = error
        if (error != null) {
            logger.warning("SetOverlayIcon failed: $error")
        }
        return error == null
    }

    /**
     * Remove the overlay icon from the taskbar button.
     *
     * @param window The AWT window.
     * @return true if the overlay was cleared successfully.
     */
    fun clearIcon(window: Window): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        val error = NativeWindowsTaskbarBridge.nativeClearOverlayIcon(window)
        lastError = error
        if (error != null) {
            logger.warning("ClearOverlayIcon failed: $error")
        }
        return error == null
    }
}

@Suppress("MagicNumber")
internal fun TaskbarIconSource.nativeType(): Int =
    when (this) {
        is TaskbarIconSource.FromStock -> 0
        is TaskbarIconSource.FromFile -> 1
        is TaskbarIconSource.FromResource -> 2
    }

internal fun TaskbarIconSource.nativePath(): String =
    when (this) {
        is TaskbarIconSource.FromStock -> ""
        is TaskbarIconSource.FromFile -> path
        is TaskbarIconSource.FromResource -> dllPath
    }

internal fun TaskbarIconSource.nativeIndex(): Int =
    when (this) {
        is TaskbarIconSource.FromStock -> stockIcon.id
        is TaskbarIconSource.FromFile -> 0
        is TaskbarIconSource.FromResource -> index
    }
