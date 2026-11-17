package dev.nucleusframework.tray.internal.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `nucleus_tray.dll`. Every call taking a tray handle, except [nativeWakeTray] and
 * [nativeGetIdentity], must come from that tray's own thread (see `tray.h`).
 */
internal object WindowsNativeBridge {
    init {
        NativeLibraryLoader.load("nucleus_tray", WindowsNativeBridge::class.java)
    }

    // -- Tray lifecycle --

    @JvmStatic external fun nativeCreateTray(
        iconPath: String,
        tooltip: String,
    ): Long

    /** Removes the icon, destroys the window and frees the tray and its callbacks. */
    @JvmStatic external fun nativeFreeTray(handle: Long)

    @JvmStatic external fun nativeSetTrayIcon(
        handle: Long,
        iconPath: String,
    )

    @JvmStatic external fun nativeSetTrayTooltip(
        handle: Long,
        tooltip: String,
    )

    @JvmStatic external fun nativeSetTrayCallback(
        handle: Long,
        callback: Runnable?,
    )

    @JvmStatic external fun nativeSetMenuOpenedCallback(
        handle: Long,
        callback: Runnable?,
    )

    /** Points the tray at a menu built with [nativeCreateMenuItems] (0 for none). */
    @JvmStatic external fun nativeSetTrayMenu(
        trayHandle: Long,
        menuHandle: Long,
    )

    @JvmStatic external fun nativeInitTray(handle: Long): Int

    /** Dispatches pending messages; with [wait], first sleeps until one arrives. */
    @JvmStatic external fun nativePumpTray(
        handle: Long,
        wait: Boolean,
    ): Int

    /** Rebuilds the menu and pushes icon + tooltip to the shell. */
    @JvmStatic external fun nativeUpdateTray(handle: Long)

    /** Any thread: wakes [nativePumpTray]; [cancelMenu] also closes an open popup menu. */
    @JvmStatic external fun nativeWakeTray(
        handle: Long,
        cancelMenu: Boolean,
    )

    // -- Menu items --

    @JvmStatic external fun nativeCreateMenuItems(count: Int): Long

    @JvmStatic external fun nativeSetMenuItem(
        menuHandle: Long,
        index: Int,
        text: String?,
        iconPath: String?,
        disabled: Boolean,
        checked: Boolean,
        separator: Boolean,
    )

    @JvmStatic external fun nativeSetMenuItemCallback(
        menuHandle: Long,
        index: Int,
        callback: Runnable?,
    )

    @JvmStatic external fun nativeSetMenuItemSubmenu(
        menuHandle: Long,
        index: Int,
        submenuHandle: Long,
    )

    /** Frees a menu built with [nativeCreateMenuItems], its submenus and their callbacks. */
    @JvmStatic external fun nativeFreeMenuItems(menuHandle: Long)

    // -- Geometry --

    /** Writes [hwnd, uid] of an initialized tray into [out]; false when it has none. */
    @JvmStatic external fun nativeGetIdentity(
        handle: Long,
        out: LongArray,
    ): Boolean

    /** Writes [x, y] (physical px) into [outXY]. Returns non-zero if precise. */
    @JvmStatic external fun nativeGetNotificationIconsPosition(
        handle: Long,
        outXY: IntArray,
    ): Int

    @JvmStatic external fun nativeGetNotificationIconsRegion(): String?
}
