package dev.nucleusframework.tray.internal.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `libnucleus_tray.dylib` (`nucleus_tray_macos.m`).
 *
 * Every call is thread-safe and returns without waiting for the AppKit main thread: the native
 * side applies published states there, newest first. A handle from [nativeCreateTray] stays valid
 * until [nativeDispose].
 */
internal object MacNativeBridge {
    private val loaded = NativeLibraryLoader.load("nucleus_tray", MacNativeBridge::class.java)
    val isLoaded: Boolean get() = loaded

    /** Menu entry flags of [nativeSubmit]. */
    const val FLAG_DISABLED: Int = 1
    const val FLAG_CHECKED: Int = 2
    const val FLAG_SEPARATOR: Int = 4

    @JvmStatic external fun nativeCreateTray(): Long

    /**
     * Publishes everything the status item shows. The menu is flattened in pre-order: entry `i`
     * is a child of the closest previous entry whose depth is `depths[i] - 1`.
     */
    @JvmStatic external fun nativeSubmit(
        handle: Long,
        iconPath: String,
        tooltip: String,
        click: Runnable?,
        menuOpened: Runnable?,
        titles: Array<String?>,
        iconPaths: Array<String?>,
        keyEquivalents: Array<String?>,
        modifierMasks: LongArray,
        flags: IntArray,
        depths: IntArray,
        callbacks: Array<Runnable?>,
    )

    /** Light / dark variants of the last published icon, swapped with the menu bar appearance. */
    @JvmStatic external fun nativeSetIconsForAppearance(
        handle: Long,
        lightIcon: String,
        darkIcon: String,
    )

    /** Removes the status item (closing its menu if open) and releases [handle]. Final. */
    @JvmStatic external fun nativeDispose(handle: Long)

    /** Writes [x, y] (points, top-left origin) into [outXY]; 0 while the item is not laid out. */
    @JvmStatic external fun nativeGetStatusItemPosition(
        handle: Long,
        outXY: IntArray,
    ): Int

    /** "top-left" | "top-right", or null while the item is not laid out. */
    @JvmStatic external fun nativeGetStatusItemRegion(handle: Long): String?

    @JvmStatic external fun nativeSetThemeCallback(callback: ThemeChangeCallback?)

    @JvmStatic external fun nativeIsMenuDark(): Int

    // -- Diagnostics and tests --

    /** Snapshot of the AppKit state of a tray (status item and menu), null on timeout. */
    @JvmStatic external fun nativeDebugDescribe(handle: Long): String?

    /** Status items created by this library still alive (held weakly), -1 on timeout. */
    @JvmStatic external fun nativeDebugStatusItemCount(): Int

    /** The AppKit main thread's current run-loop mode, read without waiting for it. */
    @JvmStatic external fun nativeDebugMainMode(): String?

    /** Queues a real mouse click on the status item. */
    @JvmStatic external fun nativeDebugClick(
        handle: Long,
        secondary: Boolean,
    )

    /** Queues the selection of the menu item at [path] (indices from the root menu). */
    @JvmStatic external fun nativeDebugPerformItem(
        handle: Long,
        path: IntArray,
    )

    /** Queues the dismissal of the open menu. */
    @JvmStatic external fun nativeDebugCancelMenu(handle: Long)

    /** Menu-bar appearance changes, called on the AppKit main thread. */
    interface ThemeChangeCallback {
        fun onThemeChanged(isDark: Int)
    }
}
