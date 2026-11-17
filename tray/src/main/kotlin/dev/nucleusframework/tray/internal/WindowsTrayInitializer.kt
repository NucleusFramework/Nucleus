package dev.nucleusframework.tray.internal

import dev.nucleusframework.tray.internal.windows.WindowsNativeBridge
import dev.nucleusframework.tray.internal.windows.WindowsTrayManager
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.WindowsTrayMenuBuilderImpl

internal object WindowsTrayInitializer {
    private const val DEFAULT_ID: String = "_default"

    // Manage multiple tray managers by ID to allow multiple tray icons
    private val trayManagers: MutableMap<String, WindowsTrayManager> = mutableMapOf()

    /**
     * Native notification-area region ("top-left" | "top-right" | …), or `null` if unavailable.
     * Exposed for the TrayApp (`dev.nucleusframework.tray.app`)'s tray-corner detection; wraps the internal
     * JNI bridge so it stays out of the public surface.
     */
    fun notificationIconsRegion(): String? =
        runCatching { WindowsNativeBridge.nativeGetNotificationIconsRegion() }.getOrNull()

    @Synchronized
    fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        val existing = trayManagers[id]
        val manager = existing ?: WindowsTrayManager(id, iconPath, tooltip, onLeftClick, onMenuOpened)
        trayManagers[id] = manager

        val menuItems =
            WindowsTrayMenuBuilderImpl(iconPath, tooltip, onLeftClick, trayManager = manager).apply {
                menuContent?.let { it() }
            }.build()

        if (existing == null) {
            manager.initialize(menuItems)
        } else {
            manager.update(iconPath, tooltip, onLeftClick, onMenuOpened, menuItems)
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
        // Same as initialize - it will handle both cases per ID
        initialize(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)
    }

    @Synchronized
    fun dispose(id: String) {
        trayManagers.remove(id)?.stopTray()
    }

    /**
     * Force a fresh capture of the tray icon position.
     * This is useful when Windows reorganizes icons after creation.
     */
    @Synchronized
    fun refreshPosition(id: String) {
        trayManagers[id]?.refreshPosition()
    }

    // Backward-compatible API for existing callers (single default tray)
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
