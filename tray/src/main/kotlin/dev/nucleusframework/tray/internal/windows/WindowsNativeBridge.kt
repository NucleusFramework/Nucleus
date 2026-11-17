package dev.nucleusframework.tray.internal.windows

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the native Windows tray library (WinTray.dll).
 * Replaces the previous JNA-based approach.
 * Follows the same patterns as MacNativeBridge and LinuxNativeBridge.
 */
internal object WindowsNativeBridge {
    init {
        NativeLibraryLoader.load(
            "nucleus_tray",
            WindowsNativeBridge::class.java,
            
        )
    }

    // -- Tray lifecycle --

    @JvmStatic external fun nativeCreateTray(
        iconPath: String,
        tooltip: String,
    ): Long

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

    @JvmStatic external fun nativeSetTrayMenu(
        trayHandle: Long,
        menuHandle: Long,
    )

    @JvmStatic external fun nativeClearTrayMenu(trayHandle: Long)

    @JvmStatic external fun nativeInitTray(handle: Long): Int

    @JvmStatic external fun nativeLoopTray(blocking: Int): Int

    @JvmStatic external fun nativeUpdateTray(handle: Long)

    @JvmStatic external fun nativeExitTray()

    // -- Menu items --

    @JvmStatic external fun nativeCreateMenuItems(count: Int): Long

    @JvmStatic external fun nativeSetMenuItem(
        menuHandle: Long,
        index: Int,
        text: String,
        iconPath: String?,
        disabled: Int,
        checked: Int,
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

    @JvmStatic external fun nativeFreeMenuItems(
        menuHandle: Long,
        count: Int,
    )

    // -- Position --

    /** Writes [x, y] into outXY. Returns non-zero if precise. */
    @JvmStatic external fun nativeGetNotificationIconsPosition(outXY: IntArray): Int

    @JvmStatic external fun nativeGetNotificationIconsRegion(): String?
}
