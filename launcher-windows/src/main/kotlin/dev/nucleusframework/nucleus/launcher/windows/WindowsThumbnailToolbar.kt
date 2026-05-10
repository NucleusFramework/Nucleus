package dev.nucleusframework.nucleus.launcher.windows

import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Windows thumbnail toolbar API via JNI (ITaskbarList3).
 *
 * Adds up to 7 clickable buttons to the window's taskbar thumbnail preview.
 * Buttons are registered once per window with [setButtons]; after that, only
 * their state (icon, tooltip, flags) can be updated via [updateButtons].
 *
 * Click events are delivered on the AWT EDT via the [ThumbBarClickListener] callback.
 *
 * Works with **all packaging types** (APPX, NSIS, MSI, distributable).
 *
 * Thread-safe singleton.
 */
object WindowsThumbnailToolbar {
    private val logger = Logger.getLogger(WindowsThumbnailToolbar::class.java.simpleName)

    /** The last error message from a native operation, or null if the last operation succeeded. */
    var lastError: String? = null
        private set

    // Cache HWND per window so we can unregister even after the AWT peer is disposed
    private val hwndCache = ConcurrentHashMap<Window, Long>()

    /** Whether the native library is loaded and functional on this platform. */
    val isAvailable: Boolean get() = NativeWindowsTaskbarBridge.isLoaded

    /**
     * Register thumbnail toolbar buttons for a window.
     *
     * This can only be called **once** per window — Windows does not allow adding buttons
     * after the initial registration. To change button state later, use [updateButtons].
     *
     * @param window  The AWT window whose taskbar thumbnail gets the buttons.
     * @param buttons Up to 7 buttons. Each must have a unique [ThumbnailToolbarButton.id] (0–6).
     * @param onClick Callback invoked on the AWT EDT when any button is clicked.
     * @return true if the buttons were added successfully.
     */
    fun setButtons(
        window: Window,
        buttons: List<ThumbnailToolbarButton>,
        onClick: ThumbBarClickListener? = null,
    ): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        require(buttons.size <= ThumbnailToolbarButton.MAX_BUTTONS) {
            "Maximum ${ThumbnailToolbarButton.MAX_BUTTONS} buttons allowed, got ${buttons.size}"
        }

        // Cache HWND while the AWT peer is still alive
        val hwnd = NativeWindowsTaskbarBridge.nativeGetHwnd(window)
        if (hwnd != 0L) hwndCache[window] = hwnd

        val arrays = marshalButtons(buttons)
        val error =
            NativeWindowsTaskbarBridge.nativeThumbBarSetButtons(
                window,
                arrays.ids,
                arrays.tooltips,
                arrays.flags,
                arrays.iconTypes,
                arrays.iconPaths,
                arrays.iconIndices,
                onClick,
            )
        lastError = error
        if (error != null) {
            logger.warning("ThumbBarSetButtons failed: $error")
            hwndCache.remove(window)
        }
        return error == null
    }

    /**
     * Update the state of previously registered buttons.
     *
     * Only call this after [setButtons] has been called for the same window.
     * Button IDs must match those originally registered.
     *
     * @param window  The AWT window.
     * @param buttons Updated button definitions (same IDs as originally registered).
     * @return true if the buttons were updated successfully.
     */
    fun updateButtons(
        window: Window,
        buttons: List<ThumbnailToolbarButton>,
    ): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }

        val arrays = marshalButtons(buttons)
        val error =
            NativeWindowsTaskbarBridge.nativeThumbBarUpdateButtons(
                window,
                arrays.ids,
                arrays.tooltips,
                arrays.flags,
                arrays.iconTypes,
                arrays.iconPaths,
                arrays.iconIndices,
            )
        lastError = error
        if (error != null) {
            logger.warning("ThumbBarUpdateButtons failed: $error")
        }
        return error == null
    }

    /**
     * Unregister the thumbnail toolbar callback and restore the original window procedure.
     *
     * Call this when the window is closing or when you no longer need button click events.
     *
     * @param window The AWT window.
     * @return true if cleanup succeeded.
     */
    fun unregister(window: Window): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        // Try cached HWND first (works even after AWT peer is disposed)
        val cachedHwnd = hwndCache.remove(window)
        val error =
            if (cachedHwnd != null && cachedHwnd != 0L) {
                NativeWindowsTaskbarBridge.nativeThumbBarUnregisterByHwnd(cachedHwnd)
            } else {
                NativeWindowsTaskbarBridge.nativeThumbBarUnregister(window)
            }
        lastError = error
        if (error != null) {
            logger.warning("ThumbBarUnregister failed: $error")
        }
        return error == null
    }

    private data class ButtonArrays(
        val ids: IntArray,
        val tooltips: Array<String>,
        val flags: IntArray,
        val iconTypes: IntArray,
        val iconPaths: Array<String>,
        val iconIndices: IntArray,
    )

    private fun marshalButtons(buttons: List<ThumbnailToolbarButton>): ButtonArrays {
        val n = buttons.size
        val ids = IntArray(n)
        val tooltips = Array(n) { "" }
        val flags = IntArray(n)
        val iconTypes = IntArray(n)
        val iconPaths = Array(n) { "" }
        val iconIndices = IntArray(n)

        buttons.forEachIndexed { i, btn ->
            ids[i] = btn.id
            tooltips[i] = btn.tooltip
            flags[i] = btn.toNativeFlags()
            val icon = btn.icon
            if (icon != null) {
                iconTypes[i] = icon.nativeType()
                iconPaths[i] = icon.nativePath()
                iconIndices[i] = icon.nativeIndex()
            }
        }
        return ButtonArrays(ids, tooltips, flags, iconTypes, iconPaths, iconIndices)
    }
}
