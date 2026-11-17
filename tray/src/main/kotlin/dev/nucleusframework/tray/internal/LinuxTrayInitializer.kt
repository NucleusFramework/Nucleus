package dev.nucleusframework.tray.internal

import dev.nucleusframework.tray.internal.linux.LinuxTrayManager
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.LinuxTrayMenuBuilderImpl
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object LinuxTrayInitializer {
    private const val DEFAULT_ID: String = "_default"

    private val trayMenuImpls: MutableMap<String, LinuxTrayMenuBuilderImpl> = mutableMapOf()
    private val linuxTrayManagers: MutableMap<String, LinuxTrayManager> = mutableMapOf()
    private val lock = ReentrantLock()

    @Synchronized
    fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        lock.withLock {
            val existing = linuxTrayManagers[id]
            if (existing == null) {
                val manager = LinuxTrayManager(id, iconPath, tooltip, onLeftClick, onMenuOpened)
                linuxTrayManagers[id] = manager

                val menuImpl =
                    if (menuContent != null) {
                        LinuxTrayMenuBuilderImpl(iconPath, tooltip, onLeftClick, trayManager = manager).apply {
                            menuContent()
                        }
                    } else {
                        null
                    }
                menuImpl?.let { impl ->
                    trayMenuImpls[id]?.dispose()
                    trayMenuImpls[id] = impl
                    impl.build().forEach { item -> manager.addMenuItem(item) }
                }

                manager.startTray()
            } else {
                update(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)
            }
        }
    }

    @Synchronized
    fun update(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        lock.withLock {
            val manager = linuxTrayManagers[id]
            if (manager == null) {
                initialize(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)
                return
            }

            val newMenuItems =
                if (menuContent != null) {
                    val newImpl =
                        LinuxTrayMenuBuilderImpl(iconPath, tooltip, onLeftClick, trayManager = manager).apply {
                            menuContent()
                        }
                    trayMenuImpls[id]?.dispose()
                    trayMenuImpls[id] = newImpl
                    newImpl.build()
                } else {
                    null
                }

            manager.update(iconPath, tooltip, onLeftClick, newMenuItems, onMenuOpened)
        }
    }

    @Synchronized
    fun dispose(id: String) {
        // Remove references under lock quickly to avoid holding the lock during teardown
        val manager: LinuxTrayManager?
        val menuImpl: LinuxTrayMenuBuilderImpl?
        lock.withLock {
            manager = linuxTrayManagers.remove(id)
            menuImpl = trayMenuImpls.remove(id)
        }
        // Dispose menu builder immediately (cheap)
        try {
            menuImpl?.dispose()
        } catch (_: Throwable) {
        }
        // Stop tray asynchronously to avoid freezing the UI thread and avoid holding the lock
        manager?.let { m ->
            Thread({
                try {
                    m.stopTray()
                } catch (_: Throwable) {
                }
            }, "LinuxTray-Dispose-$id").apply {
                isDaemon = true
                start()
            }
        }
    }

    // Backward-compatible single-tray API
    fun initialize(
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) = initialize(DEFAULT_ID, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)

    fun update(
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) = update(DEFAULT_ID, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)

    fun dispose() = dispose(DEFAULT_ID)
}
