package dev.nucleusframework.tray.internal

import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.tray.internal.linux.LinuxNativeBridge
import dev.nucleusframework.tray.internal.linux.LinuxTrayManager
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.LinuxTrayMenuBuilderImpl
import dev.nucleusframework.tray.utils.AppIdProvider

/** StatusNotifierItems by id. Every call is thread-safe and returns without waiting for a tray thread. */
internal object LinuxTrayInitializer {
    private val trayManagers = HashMap<String, LinuxTrayManager>()

    private fun manager(id: String): LinuxTrayManager? = synchronized(this) { trayManagers[id] }

    /** Creates tray [id], or updates it when it exists. Returns at once. */
    fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        val manager =
            synchronized(this) {
                trayManagers.getOrPut(id) { LinuxTrayManager(id, "${AppIdProvider.appId()}-$id") }
            }
        var menuItems = LinuxTrayMenuBuilderImpl(manager).apply { menuContent?.invoke(this) }.build()
        // Plasma treats an item whose menu has no entry as broken; a lone separator shows nothing.
        if (menuItems.isEmpty() && LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE) {
            menuItems = listOf(LinuxTrayManager.MenuItem(text = "", isSeparator = true))
        }
        manager.submit(LinuxTrayManager.State(iconPath, tooltip, onLeftClick, onMenuOpened, menuItems))
    }

    /** Same as [initialize]: creates or updates tray [id]. */
    fun update(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ): Unit = initialize(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)

    /** Removes tray [id]. */
    fun dispose(id: String) {
        synchronized(this) { trayManagers.remove(id) }?.stop()
    }

    /** Ids of the live trays (diagnostics and tests). */
    fun debugIds(): List<String> = synchronized(this) { trayManagers.keys.toList() }

    /** Trays whose native thread still runs, disposed ones that are still closing included (tests). */
    fun debugLiveNativeTrays(): Int = LinuxNativeBridge.nativeDebugLiveTrays()

    /** The `Id` property tray [id] publishes (tests find it on the bus by this). */
    fun debugItemId(id: String): String = "${AppIdProvider.appId()}-$id"

    /** True while tray [id] exists (tests). */
    fun debugHas(id: String): Boolean = manager(id) != null
}
