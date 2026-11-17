package dev.nucleusframework.tray.internal.macos

import androidx.compose.runtime.mutableStateOf
import dev.nucleusframework.tray.menu.KeyShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class MacTrayManager(
    private var iconPath: String,
    private var tooltip: String = "",
    onLeftClick: (() -> Unit)? = null,
    onMenuOpened: (() -> Unit)? = null,
) {
    private var trayHandle: Long = 0L
    private var menuHandle: Long = 0L
    private var menuItemCount: Int = 0
    private val menuItems: MutableList<MenuItem> = mutableListOf()
    private val running = AtomicBoolean(false)
    private val lock = ReentrantLock()

    // Coroutine scopes for callback handling
    private var mainScope: CoroutineScope? = null
    private var ioScope: CoroutineScope? = null

    // Keep submenu handles for cleanup
    private val submenuHandles: MutableList<Pair<Long, Int>> = mutableListOf()

    private val onLeftClickCallback = mutableStateOf(onLeftClick)
    private var onMenuOpenedCallback: (() -> Unit)? = onMenuOpened

    // Top level MenuItem class
    data class MenuItem(
        val text: String,
        val icon: String? = null,
        val isEnabled: Boolean = true,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false,
        val shortcut: KeyShortcut? = null,
        val onClick: (() -> Unit)? = null,
        val subMenuItems: List<MenuItem> = emptyList(),
    )

    fun addMenuItem(menuItem: MenuItem) {
        lock.withLock {
            menuItems.add(menuItem)
        }
    }

    // Update a menu item's checked state (including items nested in submenus)
    fun updateMenuItemCheckedState(
        label: String,
        isChecked: Boolean,
    ) {
        lock.withLock {
            val patched = patchCheckedState(menuItems.toList(), label, isChecked)
            if (patched != menuItems) {
                menuItems.clear()
                menuItems.addAll(patched)
                // Recreate the menu to reflect changes
                recreateMenu()
            }
        }
    }

    private fun patchCheckedState(
        items: List<MenuItem>,
        label: String,
        checked: Boolean,
    ): List<MenuItem> =
        items.map { item ->
            when {
                item.isCheckable && item.text == label -> item.copy(isChecked = checked)
                item.subMenuItems.isNotEmpty() ->
                    item.copy(subMenuItems = patchCheckedState(item.subMenuItems, label, checked))
                else -> item
            }
        }

    // Update the tray with new properties and menu items
    fun update(
        newIconPath: String,
        newTooltip: String,
        newOnLeftClick: (() -> Unit)?,
        newMenuItems: List<MenuItem>? = null,
        newOnMenuOpened: (() -> Unit)? = null,
    ) {
        lock.withLock {
            if (!running.get() || trayHandle == 0L) return

            // Update properties
            val iconChanged = this.iconPath != newIconPath
            val tooltipChanged = this.tooltip != newTooltip
            val onLeftClickChanged = this.onLeftClickCallback.value != newOnLeftClick
            val onMenuOpenedChanged = this.onMenuOpenedCallback != newOnMenuOpened

            // Update icon path and tooltip
            this.iconPath = newIconPath
            this.tooltip = newTooltip
            this.onLeftClickCallback.value = newOnLeftClick
            this.onMenuOpenedCallback = newOnMenuOpened

            if (iconChanged) {
                MacNativeBridge.nativeSetTrayIcon(trayHandle, newIconPath)
            }
            if (tooltipChanged) {
                MacNativeBridge.nativeSetTrayTooltip(trayHandle, newTooltip)
            }
            if (onLeftClickChanged) {
                initializeOnLeftClickCallback()
            }
            if (onMenuOpenedChanged) {
                initializeOnMenuOpenedCallback()
            }

            // Update menu items if provided
            if (newMenuItems != null) {
                menuItems.clear()
                menuItems.addAll(newMenuItems)
                recreateMenu()
            } else if (iconChanged || tooltipChanged || onLeftClickChanged) {
                MacNativeBridge.nativeUpdateTray(trayHandle)
            }
        }
    }

    // Recreate the menu with updated state
    private fun recreateMenu() {
        if (!running.get() || trayHandle == 0L) return

        // Free old menu
        freeCurrentMenu()

        // Recreate the menu
        initializeTrayMenu()

        // Update the tray
        MacNativeBridge.nativeUpdateTray(trayHandle)
    }

    private fun freeCurrentMenu() {
        // Free submenus first (in reverse order to handle nested)
        for ((handle, count) in submenuHandles.reversed()) {
            MacNativeBridge.nativeFreeMenuItems(handle, count)
        }
        submenuHandles.clear()

        // Free top-level menu
        if (menuHandle != 0L) {
            MacNativeBridge.nativeFreeMenuItems(menuHandle, menuItemCount)
            menuHandle = 0L
            menuItemCount = 0
        }
    }

    // Start the tray.
    //
    // Runs the whole native init synchronously on the calling thread — no
    // dedicated thread, no latch. `tray_init` hops to the GCD main queue by
    // itself when called off the macOS main thread, and runs inline when the
    // caller *is* the main thread (the Nucleus Tao backend composes on it).
    // The previous design (background thread + `initLatch.await()`) deadlocked
    // there: the main thread parked on the latch can't drain the main queue,
    // so the tray thread's `DispatchQueue.main.sync` inside `tray_init` never
    // ran. No event-pump loop is needed either: status-item events are
    // delivered by the application's own AppKit run loop.
    fun startTray() {
        lock.withLock {
            if (running.get()) {
                return
            }

            running.set(true)

            // Create new coroutine scopes
            mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            try {
                // Create tray structure via JNI
                trayHandle = MacNativeBridge.nativeCreateTray(iconPath, tooltip)
                if (trayHandle == 0L) {
                    throw IllegalStateException("Failed to allocate native tray struct")
                }

                initializeOnLeftClickCallback()
                initializeTrayMenu()

                val initResult = MacNativeBridge.nativeInitTray(trayHandle)
                if (initResult != 0) {
                    throw IllegalStateException("Failed to initialize tray: $initResult")
                }

                // Set menu-opened callback after init (TrayContext must exist)
                initializeOnMenuOpenedCallback()
            } catch (e: Exception) {
                e.printStackTrace()
                running.set(false)
                cleanupTray()
            }
        }
    }

    private fun initializeOnLeftClickCallback() {
        if (trayHandle == 0L) return

        val onClick = onLeftClickCallback.value
        if (onClick != null) {
            MacNativeBridge.nativeSetTrayCallback(
                trayHandle,
                Runnable {
                    mainScope?.launch {
                        ioScope?.launch {
                            onClick()
                        }
                    }
                },
            )
        } else {
            MacNativeBridge.nativeSetTrayCallback(trayHandle, null)
        }
    }

    private fun initializeOnMenuOpenedCallback() {
        if (trayHandle == 0L) return

        val callback = onMenuOpenedCallback
        if (callback != null) {
            MacNativeBridge.nativeSetMenuOpenedCallback(
                trayHandle,
                Runnable {
                    mainScope?.launch {
                        ioScope?.launch {
                            callback()
                        }
                    }
                },
            )
        } else {
            MacNativeBridge.nativeSetMenuOpenedCallback(trayHandle, null)
        }
    }

    private fun initializeTrayMenu() {
        if (trayHandle == 0L) return

        if (menuItems.isEmpty()) {
            MacNativeBridge.nativeClearTrayMenu(trayHandle)
            return
        }

        val count = menuItems.size
        val handle = MacNativeBridge.nativeCreateMenuItems(count)
        menuHandle = handle
        menuItemCount = count

        menuItems.forEachIndexed { index, item ->
            initializeNativeMenuItem(handle, index, item)
        }

        MacNativeBridge.nativeSetTrayMenu(trayHandle, handle)
    }

    private fun initializeNativeMenuItem(
        parentHandle: Long,
        index: Int,
        menuItem: MenuItem,
    ) {
        MacNativeBridge.nativeSetMenuItem(
            parentHandle,
            index,
            menuItem.text,
            menuItem.icon,
            if (menuItem.isEnabled) 0 else 1,
            if (menuItem.isChecked) 1 else 0,
        )

        menuItem.shortcut?.let { shortcut ->
            MacNativeBridge.nativeSetMenuItemShortcut(
                parentHandle,
                index,
                shortcut.toMacKeyEquivalent(),
                shortcut.toMacModifierMask(),
            )
        }

        menuItem.onClick?.let { onClick ->
            MacNativeBridge.nativeSetMenuItemCallback(
                parentHandle,
                index,
                Runnable {
                    if (!running.get()) return@Runnable
                    mainScope?.launch {
                        ioScope?.launch {
                            onClick()
                        }
                    }
                },
            )
        }

        if (menuItem.subMenuItems.isNotEmpty()) {
            val subCount = menuItem.subMenuItems.size
            val subHandle = MacNativeBridge.nativeCreateMenuItems(subCount)
            submenuHandles.add(subHandle to subCount)

            menuItem.subMenuItems.forEachIndexed { subIndex, subItem ->
                initializeNativeMenuItem(subHandle, subIndex, subItem)
            }

            MacNativeBridge.nativeSetMenuItemSubmenu(parentHandle, index, subHandle)
        }
    }

    private fun cleanupTray() {
        lock.withLock {
            if (trayHandle != 0L) {
                try {
                    // Free menu first
                    freeCurrentMenu()
                    // Dispose the tray (this frees the struct too)
                    MacNativeBridge.nativeDisposeTray(trayHandle)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                trayHandle = 0L
            }
            menuItems.clear()
        }
    }

    fun stopTray() {
        lock.withLock {
            if (!running.get()) {
                return
            }

            running.set(false)
            cleanupTray()
        }

        // Cancel coroutines
        mainScope?.cancel()
        ioScope?.cancel()
        mainScope = null
        ioScope = null
    }

    fun getNativeTrayHandle(): Long {
        return trayHandle
    }

    fun setAppearanceIcons(
        lightIconPath: String,
        darkIconPath: String,
    ) {
        lock.withLock {
            if (trayHandle == 0L) return
            MacNativeBridge.nativeSetIconsForAppearance(trayHandle, lightIconPath, darkIconPath)
        }
    }
}
