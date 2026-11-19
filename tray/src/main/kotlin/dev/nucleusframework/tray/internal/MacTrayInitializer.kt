package dev.nucleusframework.tray.internal

import dev.nucleusframework.tray.InternalNucleusTrayApi
import dev.nucleusframework.tray.internal.macos.MacNativeBridge
import dev.nucleusframework.tray.internal.macos.MacTrayManager
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.MacTrayMenuBuilderImpl

/** macOS status items by id. Every call is thread-safe and never waits for the AppKit main thread. */
@InternalNucleusTrayApi
public object MacTrayInitializer {
    private val trayManagers = HashMap<String, MacTrayManager>()

    /** The tray the id-less position queries answer for: the one changed last. */
    private var lastId: String? = null

    private fun manager(id: String): MacTrayManager? = synchronized(this) { trayManagers[id] }

    /** Creates tray [id], or updates it when it exists. Returns at once. */
    internal fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        val manager =
            synchronized(this) {
                lastId = id
                trayManagers.getOrPut(id) { MacTrayManager(id) }
            }
        val menuItems = MacTrayMenuBuilderImpl(manager).apply { menuContent?.invoke(this) }.build()
        manager.submit(MacTrayManager.State(iconPath, tooltip, onLeftClick, onMenuOpened, menuItems))
    }

    /** Same as [initialize]: creates or updates tray [id]. */
    internal fun update(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ): Unit = initialize(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)

    /** Light / dark variants of tray [id]'s current icon. */
    internal fun setAppearanceIcons(
        id: String,
        lightIconPath: String,
        darkIconPath: String,
    ) {
        manager(id)?.setAppearanceIcons(lightIconPath, darkIconPath)
    }

    /** Removes tray [id]. */
    internal fun dispose(id: String) {
        synchronized(this) {
            if (lastId == id) lastId = null
            trayManagers.remove(id)
        }?.stop()
    }

    /** Status-item position of the last changed tray (points, top-left origin); false if not laid out. */
    public fun statusItemPosition(outXY: IntArray): Boolean =
        synchronized(this) { lastId }?.let { statusItemPositionFor(it, outXY) } ?: false

    /** Screen region ("top-left" | "top-right") of the last changed tray, or null. */
    public fun statusItemRegion(): String? = synchronized(this) { lastId }?.let { statusItemRegionFor(it) }

    /** Status-item position of tray [id] (points, top-left origin); false until it is laid out. */
    public fun statusItemPositionFor(
        id: String,
        outXY: IntArray,
    ): Boolean = manager(id)?.position(outXY) ?: false

    /** Screen region ("top-left" | "top-right") of tray [id], or null until it is laid out. */
    public fun statusItemRegionFor(id: String): String? = manager(id)?.region()

    /** Ids of the live trays (diagnostics and tests). */
    internal fun debugIds(): List<String> = synchronized(this) { trayManagers.keys.toList() }

    /** AppKit snapshot of tray [id] (diagnostics and tests), null if absent or on timeout. */
    internal fun debugDescribe(id: String): String? =
        manager(id)?.withHandle { MacNativeBridge.nativeDebugDescribe(it) }

    /** Status items still alive, held weakly (diagnostics and tests), -1 on timeout. */
    internal fun debugStatusItemCount(): Int = MacNativeBridge.nativeDebugStatusItemCount()

    /** Queues a real mouse click on tray [id] (diagnostics and tests). */
    internal fun debugClick(
        id: String,
        secondary: Boolean,
    ) {
        manager(id)?.withHandle { MacNativeBridge.nativeDebugClick(it, secondary) }
    }

    /** Queues the selection of the menu item at [path] of tray [id] (diagnostics and tests). */
    internal fun debugPerformItem(
        id: String,
        path: IntArray,
    ) {
        manager(id)?.withHandle { MacNativeBridge.nativeDebugPerformItem(it, path) }
    }

    /** Queues the dismissal of tray [id]'s open menu (diagnostics and tests). */
    internal fun debugCancelMenu(id: String) {
        manager(id)?.withHandle { MacNativeBridge.nativeDebugCancelMenu(it) }
    }
}
