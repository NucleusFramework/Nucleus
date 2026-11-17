package dev.nucleusframework.tray.internal

import dev.nucleusframework.tray.internal.macos.MacNativeBridge
import dev.nucleusframework.tray.internal.macos.MacTrayManager
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.menu.internal.MacTrayMenuBuilderImpl

internal object MacTrayInitializer {
    private const val DEFAULT_ID: String = "_default"

    // Manage multiple tray managers and builders by ID
    private val trayManagers: MutableMap<String, MacTrayManager> = mutableMapOf()
    private val trayMenuBuilders: MutableMap<String, MacTrayMenuBuilderImpl> = mutableMapOf()

    // Accessors for per-instance integration
    @Synchronized
    internal fun getManager(id: String): MacTrayManager? = trayManagers[id]

    @Synchronized
    internal fun getNativeTrayHandle(id: String): Long = trayManagers[id]?.getNativeTrayHandle() ?: 0L

    // Status-item queries used by the TrayApp (`dev.nucleusframework.tray.app`) to place the TrayApp popup,
    // wrapping the internal JNI bridge so it stays out of the public surface.

    /** Global status-item position in physical pixels; `false` if not precisely available. */
    fun statusItemPosition(outXY: IntArray): Boolean =
        runCatching { MacNativeBridge.nativeGetStatusItemPosition(outXY) != 0 }.getOrDefault(false)

    /** Global status-item screen region ("top-left" | "top-right" | …), or `null` if unavailable. */
    fun statusItemRegion(): String? = runCatching { MacNativeBridge.nativeGetStatusItemRegion() }.getOrNull()

    /** Per-instance status-item position in physical pixels; `false` if the tray/handle isn't ready. */
    @Synchronized
    fun statusItemPositionFor(
        id: String,
        outXY: IntArray,
    ): Boolean {
        val handle = getNativeTrayHandle(id)
        if (handle == 0L) return false
        return runCatching { MacNativeBridge.nativeGetStatusItemPositionFor(handle, outXY) != 0 }.getOrDefault(false)
    }

    /** Per-instance status-item screen region ("top-left" | "top-right" | …), or `null` if unavailable. */
    @Synchronized
    fun statusItemRegionFor(id: String): String? {
        val handle = getNativeTrayHandle(id)
        if (handle == 0L) return null
        return runCatching { MacNativeBridge.nativeGetStatusItemRegionFor(handle) }.getOrNull()
    }

    /**
     * Drains one pending AppKit event. TrayApp does this via the Compose main
     * dispatcher; tests that query [statusItemPositionFor] off a Compose runtime
     * must pump so the status item can lay out.
     */
    fun pumpEventLoop() {
        if (!MacNativeBridge.isLoaded) return
        runCatching { MacNativeBridge.nativeLoopTray(0) }
    }

    @Synchronized
    fun initialize(
        id: String,
        iconPath: String,
        tooltip: String,
        onLeftClick: (() -> Unit)? = null,
        menuContent: (TrayMenuBuilder.() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
    ) {
        var manager = trayManagers[id]
        if (manager == null) {
            // Create a new manager for this ID
            manager = MacTrayManager(iconPath, tooltip, onLeftClick, onMenuOpened)
            trayManagers[id] = manager

            // Build menu for this manager
            val builder =
                MacTrayMenuBuilderImpl(
                    iconPath,
                    tooltip,
                    onLeftClick,
                    trayManager = manager,
                ).apply {
                    menuContent?.invoke(this)
                }
            // Replace old builder for this ID if any
            trayMenuBuilders.remove(id)?.dispose()
            trayMenuBuilders[id] = builder

            val menuItems = builder.build()
            // Add each built item to manager before starting
            menuItems.forEach { manager.addMenuItem(it) }

            // Start the macOS tray for this manager
            manager.startTray()
        } else {
            // Existing manager: delegate to update with the provided content
            update(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)
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
        val manager = trayManagers[id]
        if (manager == null) {
            // If manager doesn't exist, initialize it
            initialize(id, iconPath, tooltip, onLeftClick, menuContent, onMenuOpened)
            return
        }

        // Rebuild menu if content provided
        val newMenuItems =
            if (menuContent != null) {
                val builder =
                    MacTrayMenuBuilderImpl(
                        iconPath,
                        tooltip,
                        onLeftClick,
                        trayManager = manager,
                    ).apply {
                        menuContent()
                    }
                trayMenuBuilders.remove(id)?.dispose()
                trayMenuBuilders[id] = builder
                builder.build()
            } else {
                null
            }

        manager.update(iconPath, tooltip, onLeftClick, newMenuItems, onMenuOpened)
    }

    @Synchronized
    fun setAppearanceIcons(
        id: String,
        lightIconPath: String,
        darkIconPath: String,
    ) {
        trayManagers[id]?.setAppearanceIcons(lightIconPath, darkIconPath)
    }

    @Synchronized
    fun dispose(id: String) {
        trayMenuBuilders.remove(id)?.dispose()
        trayManagers.remove(id)?.stopTray()
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
