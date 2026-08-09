package dev.nucleusframework.launcher.windows

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
 * Windows are addressed by raw `HWND`, so any windowing backend works: pass
 * `TaoWindow.nativeHandle` on the Tao backend, or use the AWT overloads which
 * resolve the `HWND` via [WindowsWindowHandle].
 *
 * Click events are delivered via the [ThumbBarClickListener] callback on the
 * thread that owns the window (the AWT EDT for AWT windows, the Tao event loop
 * for Tao windows).
 *
 * Works with **all packaging types** (APPX, NSIS, MSI, distributable).
 *
 * Thread-safe singleton.
 */
public object WindowsThumbnailToolbar {
    private val logger = Logger.getLogger(WindowsThumbnailToolbar::class.java.name)

    /** The last error message from a native operation, or null if the last operation succeeded. */
    public var lastError: String? = null
        private set

    // Cache HWND per AWT window so we can unregister even after the peer is disposed
    private val hwndCache = ConcurrentHashMap<Window, Long>()

    /** Whether the native library is loaded and functional on this platform. */
    public val isAvailable: Boolean get() = NativeWindowsTaskbarBridge.isLoaded

    /**
     * Register thumbnail toolbar buttons for a window.
     *
     * This can only be called **once** per window — Windows does not allow adding buttons
     * after the initial registration. To change button state later, use [updateButtons].
     *
     * @param hwnd    The `HWND` of the window whose taskbar thumbnail gets the buttons.
     * @param buttons Up to 7 buttons. Each must have a unique [ThumbnailToolbarButton.id] (0–6).
     * @param onClick Callback invoked on the window's owning thread when any button is clicked.
     * @return true if the buttons were added successfully.
     */
    public fun setButtons(
        hwnd: Long,
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

        val arrays = marshalButtons(buttons)
        val error =
            NativeWindowsTaskbarBridge.nativeThumbBarSetButtons(
                hwnd,
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
        }
        return error == null
    }

    /**
     * Register thumbnail toolbar buttons for an AWT window. See [setButtons].
     */
    public fun setButtons(
        window: Window,
        buttons: List<ThumbnailToolbarButton>,
        onClick: ThumbBarClickListener? = null,
    ): Boolean {
        // Cache HWND while the AWT peer is still alive
        val hwnd = WindowsWindowHandle.of(window)
        if (hwnd != 0L) hwndCache[window] = hwnd
        val added = setButtons(hwnd, buttons, onClick)
        if (!added) hwndCache.remove(window)
        return added
    }

    /**
     * Update the state of previously registered buttons.
     *
     * Only call this after [setButtons] has been called for the same window.
     * Button IDs must match those originally registered.
     *
     * @param hwnd    The `HWND` of the window.
     * @param buttons Updated button definitions (same IDs as originally registered).
     * @return true if the buttons were updated successfully.
     */
    public fun updateButtons(
        hwnd: Long,
        buttons: List<ThumbnailToolbarButton>,
    ): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }

        val arrays = marshalButtons(buttons)
        val error =
            NativeWindowsTaskbarBridge.nativeThumbBarUpdateButtons(
                hwnd,
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
     * Update the state of previously registered buttons of an AWT window. See [updateButtons].
     */
    public fun updateButtons(
        window: Window,
        buttons: List<ThumbnailToolbarButton>,
    ): Boolean = updateButtons(hwndCache[window] ?: WindowsWindowHandle.of(window), buttons)

    /**
     * Unregister the thumbnail toolbar callback and restore the original window procedure.
     *
     * Call this when the window is closing or when you no longer need button click events.
     *
     * @param hwnd The `HWND` of the window.
     * @return true if cleanup succeeded.
     */
    public fun unregister(hwnd: Long): Boolean {
        if (!isAvailable) {
            lastError = "Native library not available"
            return false
        }
        val error = NativeWindowsTaskbarBridge.nativeThumbBarUnregister(hwnd)
        lastError = error
        if (error != null) {
            logger.warning("ThumbBarUnregister failed: $error")
        }
        return error == null
    }

    /**
     * Unregister the thumbnail toolbar of an AWT window. Uses the `HWND` cached
     * at [setButtons] time, so it works even after the AWT peer is disposed.
     */
    public fun unregister(window: Window): Boolean {
        val hwnd = hwndCache.remove(window) ?: WindowsWindowHandle.of(window)
        return unregister(hwnd)
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
