package dev.nucleusframework.tray.internal.linux

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to `libnucleus_tray.so` (`nucleus_tray_linux.c`): a StatusNotifierItem with a
 * `com.canonical.dbusmenu` menu, over sd-bus.
 *
 * Every call is thread-safe and returns at once: each tray has its own native thread and bus
 * connection, and applies the newest published state there. A handle from [nativeCreate] stays
 * valid until [nativeDispose].
 */
internal object LinuxNativeBridge {
    private val loaded = NativeLibraryLoader.load("nucleus_tray", LinuxNativeBridge::class.java)
    val isLoaded: Boolean get() = loaded

    /** Menu entry flags of [nativeSubmit]. */
    const val FLAG_DISABLED: Int = 1
    const val FLAG_CHECKED: Int = 2
    const val FLAG_SEPARATOR: Int = 4
    const val FLAG_CHECKABLE: Int = 8
    const val FLAG_SUBMENU: Int = 16

    /** Shortcut modifiers of [nativeSubmit]. */
    const val MOD_CTRL: Int = 1
    const val MOD_SHIFT: Int = 2
    const val MOD_ALT: Int = 4
    const val MOD_SUPER: Int = 8

    /**
     * Starts a tray: its thread, bus connection and well-known name. [itemId] is the item's `Id`
     * property. Nothing shows until the first [nativeSubmit]. Returns 0 on failure.
     */
    @JvmStatic external fun nativeCreate(itemId: String): Long

    /**
     * Publishes everything the tray shows. A newer call replaces a state not applied yet. An
     * [iconPath] that does not load keeps the icon shown. The menu is flattened in pre-order:
     * entry `i` is a child of the closest previous entry whose depth is `depths[i] - 1`.
     */
    @JvmStatic external fun nativeSubmit(
        handle: Long,
        iconPath: String?,
        tooltip: String,
        click: ClickCallback?,
        menuOpened: Runnable?,
        labels: Array<String?>,
        iconPaths: Array<String?>,
        shortcutKeys: Array<String?>,
        shortcutMods: IntArray,
        flags: IntArray,
        depths: IntArray,
        callbacks: Array<Runnable?>,
    )

    /** Removes the tray (the host drops it when its connection closes) and releases [handle]. Final. */
    @JvmStatic external fun nativeDispose(handle: Long)

    /** Trays whose native thread is still running (diagnostics and tests). */
    @JvmStatic external fun nativeDebugLiveTrays(): Int

    /** A primary click (`Activate`), with the position the host reported. */
    interface ClickCallback {
        fun onClick(
            x: Int,
            y: Int,
        )
    }
}
