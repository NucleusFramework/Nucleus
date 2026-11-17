package dev.nucleusframework.tray.internal

import dev.nucleusframework.tray.internal.windows.WindowsNativeBridge
import dev.nucleusframework.tray.internal.windows.WindowsTrayManager
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.WindowsTrayMenuBuilderImpl

/** Windows tray icons by id. Every call is thread-safe and never waits on a tray thread except [dispose]. */
internal object WindowsTrayInitializer {
    private val trayManagers = HashMap<String, WindowsTrayManager>()

    init {
        // A JVM that exits with icons still registered leaves "ghost" icons in the notification
        // area until the user hovers them: remove them on the way out.
        Runtime.getRuntime().addShutdownHook(
            Thread(
                { synchronized(this) { trayManagers.values.toList() }.forEach { it.stop() } },
                "NucleusTray-shutdown",
            ),
        )
    }

    /** Native notification-area region ("top-left" | "top-right" | …), or `null` if unavailable. */
    fun notificationIconsRegion(): String? =
        runCatching {
            WindowsNativeBridge.nativeGetNotificationIconsRegion()
        }.getOrNull()

    /** Creates tray [id], or updates it when it exists. Returns at once: the change is applied by the tray's thread. */
    fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        val manager = synchronized(this) { trayManagers.getOrPut(id) { WindowsTrayManager(id) } }
        val menuItems = WindowsTrayMenuBuilderImpl(manager).apply { menuContent?.invoke(this) }.build()
        manager.submit(WindowsTrayManager.State(iconPath, tooltip, onLeftClick, onMenuOpened, menuItems))
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

    /** Removes tray [id] and waits for its thread to end. */
    fun dispose(id: String) {
        synchronized(this) { trayManagers.remove(id) }?.stop()
    }

    /** Forces a fresh capture of the icon position: Windows may reorganize icons after creation. */
    fun refreshPosition(id: String) {
        synchronized(this) { trayManagers[id] }?.refreshPosition()
    }

    /** Hidden window and Shell icon id of tray [id], for diagnostics and tests. */
    fun nativeIdentity(id: String): Pair<Long, Int>? = synchronized(this) { trayManagers[id] }?.nativeIdentity()

    /** Waits until tray [id] exists natively (or failed to); false on timeout. */
    fun awaitInitialized(
        id: String,
        timeoutMs: Long,
    ): Boolean = synchronized(this) { trayManagers[id] }?.awaitInitialized(timeoutMs) ?: false
}
