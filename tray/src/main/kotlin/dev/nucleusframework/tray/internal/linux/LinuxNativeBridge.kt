package dev.nucleusframework.tray.internal.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the native Linux tray library (libLinuxTray.so).
 * Replaces the previous JNA/Go-based approach with a pure C + sd-bus implementation.
 * Follows the same patterns as MacNativeBridge.
 */
internal object LinuxNativeBridge {
    init {
        NativeLibraryLoader.load(
            "nucleus_tray",
            LinuxNativeBridge::class.java,
        )
    }

    // -- Lifecycle ---------------------------------------------------------------

    /** Create a tray instance. Returns a native handle (pointer as long). */
    @JvmStatic external fun nativeCreate(
        iconBytes: ByteArray?,
        tooltip: String?,
    ): Long

    /** Start the D-Bus event loop (blocks until nativeQuit is called). */
    @JvmStatic external fun nativeRun(handle: Long): Int

    /** Signal the event loop to stop. Thread-safe. */
    @JvmStatic external fun nativeQuit(handle: Long)

    /** Destroy the tray and release all resources. Call after nativeRun returns. */
    @JvmStatic external fun nativeDestroy(handle: Long)

    // -- Tray properties ---------------------------------------------------------

    @JvmStatic external fun nativeSetIcon(
        handle: Long,
        iconBytes: ByteArray,
    )

    @JvmStatic external fun nativeSetTitle(
        handle: Long,
        title: String?,
    )

    @JvmStatic external fun nativeSetTooltip(
        handle: Long,
        tooltip: String?,
    )

    // -- Callbacks ---------------------------------------------------------------

    @JvmStatic external fun nativeSetClickCallback(
        handle: Long,
        callback: Runnable?,
    )

    @JvmStatic external fun nativeSetRClickCallback(
        handle: Long,
        callback: Runnable?,
    )

    /** Register a per-menu-item callback. The callback is keyed by menuId. */
    @JvmStatic external fun nativeSetMenuItemCallback(
        handle: Long,
        menuId: Int,
        callback: Runnable?,
    )

    /** Register a callback invoked when the menu is about to be shown. */
    @JvmStatic external fun nativeSetMenuOpenedCallback(
        handle: Long,
        callback: Runnable?,
    )

    // -- Click position ----------------------------------------------------------

    /** Writes [x, y] into outXY. */
    @JvmStatic external fun nativeGetLastClickXY(
        handle: Long,
        outXY: IntArray,
    )

    // -- Menu management ---------------------------------------------------------

    @JvmStatic external fun nativeResetMenu(handle: Long)

    @JvmStatic external fun nativeAddMenuItem(
        handle: Long,
        title: String?,
        tooltip: String?,
    ): Int

    @JvmStatic external fun nativeAddMenuItemCheckbox(
        handle: Long,
        title: String?,
        tooltip: String?,
        checked: Boolean,
    ): Int

    @JvmStatic external fun nativeAddSeparator(handle: Long)

    @JvmStatic external fun nativeAddSubMenuItem(
        handle: Long,
        parentId: Int,
        title: String?,
        tooltip: String?,
    ): Int

    @JvmStatic external fun nativeAddSubMenuItemCheckbox(
        handle: Long,
        parentId: Int,
        title: String?,
        tooltip: String?,
        checked: Boolean,
    ): Int

    @JvmStatic external fun nativeAddSubSeparator(
        handle: Long,
        parentId: Int,
    )

    // -- Per-item operations -----------------------------------------------------

    @JvmStatic external fun nativeItemSetTitle(
        handle: Long,
        id: Int,
        title: String?,
    ): Int

    @JvmStatic external fun nativeItemEnable(
        handle: Long,
        id: Int,
    )

    @JvmStatic external fun nativeItemDisable(
        handle: Long,
        id: Int,
    )

    @JvmStatic external fun nativeItemShow(
        handle: Long,
        id: Int,
    )

    @JvmStatic external fun nativeItemHide(
        handle: Long,
        id: Int,
    )

    @JvmStatic external fun nativeItemCheck(
        handle: Long,
        id: Int,
    )

    @JvmStatic external fun nativeItemUncheck(
        handle: Long,
        id: Int,
    )

    @JvmStatic external fun nativeItemSetIcon(
        handle: Long,
        id: Int,
        iconBytes: ByteArray,
    )

    @JvmStatic external fun nativeItemSetShortcut(
        handle: Long,
        id: Int,
        key: String,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        superMod: Boolean,
    )

    // -- X11 outside-click watcher -----------------------------------------------

    /** Open X11 display. Returns handle, or 0 if X11 is unavailable. */
    @JvmStatic external fun nativeX11OpenDisplay(): Long

    /** Get default root window for the display. */
    @JvmStatic external fun nativeX11DefaultRootWindow(displayHandle: Long): Long

    /** Query pointer. Writes [rootX, rootY, mask] into outData. Returns 1 on success. */
    @JvmStatic external fun nativeX11QueryPointer(
        displayHandle: Long,
        rootWindow: Long,
        outData: IntArray,
    ): Int

    /** Close X11 display. */
    @JvmStatic external fun nativeX11CloseDisplay(displayHandle: Long)
}
