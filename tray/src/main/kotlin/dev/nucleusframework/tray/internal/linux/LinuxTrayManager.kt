package dev.nucleusframework.tray.internal.linux

import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.tray.utils.TrayClickTracker
import dev.nucleusframework.tray.utils.errorln
import dev.nucleusframework.tray.utils.infoln
import dev.nucleusframework.tray.utils.warnln
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * C/sd-bus-based Linux tray manager using JNI bridge.
 *
 * Replaces the previous Go/JNA implementation with a direct C + sd-bus approach.
 * Follows the same handle-based pattern as macOS (MacTrayManager).
 */
internal class LinuxTrayManager(
    private val instanceId: String,
    private var iconPath: String,
    private var tooltip: String = "",
    private var onLeftClick: (() -> Unit)? = null,
    private var onMenuOpened: (() -> Unit)? = null,
) {
    companion object {
        // Ensures only one systray runtime is active at a time
        private val lifecyclePermit = java.util.concurrent.Semaphore(1, true)
    }

    data class MenuItem(
        val text: String,
        val isEnabled: Boolean = true,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false,
        val iconPath: String? = null,
        val shortcut: dev.nucleusframework.tray.menu.KeyShortcut? = null,
        val onClick: (() -> Unit)? = null,
        val subMenuItems: List<MenuItem> = emptyList(),
    )

    private val native = LinuxNativeBridge

    private val lock = ReentrantLock()
    private val running = AtomicBoolean(false)
    private val permitHeld = AtomicBoolean(false)

    // Menu state built by builder
    private val menuItems: MutableList<MenuItem> = mutableListOf()

    // Mapping from menu item title to native IDs
    private val idByTitle: MutableMap<String, Int> = mutableMapOf()
    private val actionById: MutableMap<Int, () -> Unit> = mutableMapOf()

    private fun isKDEDesktop(): Boolean = LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

    // Native handle
    private var trayHandle: Long = 0L

    // Thread / lifecycle
    private var shutdownHook: Thread? = null
    private var loopThread: Thread? = null

    fun addMenuItem(menuItem: MenuItem) {
        lock.withLock { menuItems.add(menuItem) }
    }

    fun updateMenuItemCheckedState(
        label: String,
        isChecked: Boolean,
    ) {
        var fallback = false
        lock.withLock {
            val idx = menuItems.indexOfFirst { it.text == label }
            if (idx != -1) {
                val current = menuItems[idx]
                menuItems[idx] = current.copy(isChecked = isChecked)
            }
            val id = idByTitle[label]
            if (id != null && trayHandle != 0L) {
                try {
                    if (isChecked) {
                        native.nativeItemCheck(trayHandle, id)
                    } else {
                        native.nativeItemUncheck(trayHandle, id)
                    }
                } catch (_: Throwable) {
                    fallback = true
                }
            } else {
                fallback = true
            }
        }
        if (fallback) rebuildMenu()
    }

    fun update(
        newIconPath: String,
        newTooltip: String,
        newOnLeftClick: (() -> Unit)?,
        newMenuItems: List<MenuItem>?,
        newOnMenuOpened: (() -> Unit)? = null,
    ) {
        val iconChanged: Boolean
        val tooltipChanged: Boolean
        lock.withLock {
            if (!running.get()) return
            iconChanged = (iconPath != newIconPath)
            tooltipChanged = (tooltip != newTooltip)
            iconPath = newIconPath
            tooltip = newTooltip
            onLeftClick = newOnLeftClick
            onMenuOpened = newOnMenuOpened
            if (newMenuItems != null) {
                menuItems.clear()
                menuItems.addAll(newMenuItems)
            }
        }

        if (iconChanged) setIconFromFileSafe(iconPath)
        if (tooltipChanged) {
            runCatching { native.nativeSetTooltip(trayHandle, tooltip) }
                .onFailure { e -> warnln { "[LinuxTrayManager] Failed to set tooltip: ${e.message}" } }
        }

        if (newMenuItems != null) rebuildMenu()
    }

    fun startTray() {
        try {
            lifecyclePermit.acquire()
            permitHeld.set(true)
        } catch (t: Throwable) {
            warnln { "[LinuxTrayManager] Failed to acquire lifecycle permit: ${t.message}" }
            return
        }

        var started = false
        try {
            if (!running.compareAndSet(false, true)) {
                lifecyclePermit.release()
                permitHeld.set(false)
                return
            }

            shutdownHook = Thread { stopTray() }.also { Runtime.getRuntime().addShutdownHook(it) }

            val readyLatch = CountDownLatch(1)

            // Read initial icon bytes
            val iconBytes =
                runCatching { File(iconPath).takeIf { it.isFile }?.readBytes() }
                    .getOrNull()

            // Create native tray
            trayHandle = native.nativeCreate(iconBytes, tooltip)
            if (trayHandle == 0L) {
                errorln { "[LinuxTrayManager] Failed to create native tray" }
                return
            }

            // Set title
            runCatching { native.nativeSetTitle(trayHandle, tooltip) }

            // Set click callback
            native.nativeSetClickCallback(
                trayHandle,
                JniRunnable {
                    try {
                        val xy = IntArray(2)
                        native.nativeGetLastClickXY(trayHandle, xy)
                        TrayClickTracker.recordClick(xy[0], xy[1])
                    } catch (_: Throwable) {
                    }
                    onLeftClick?.invoke()
                },
            )

            // Set menu-opened callback
            native.nativeSetMenuOpenedCallback(
                trayHandle,
                JniRunnable { onMenuOpened?.invoke() },
            )

            // Build menu before starting the loop
            rebuildMenu()

            // Start event loop in daemon thread
            loopThread =
                Thread({
                    try {
                        readyLatch.countDown()
                        native.nativeRun(trayHandle)
                    } catch (t: Throwable) {
                        errorln { "[LinuxTrayManager] loop error: $t" }
                    }
                }, "LinuxTray-Loop").apply {
                    isDaemon = true
                    start()
                }

            try {
                readyLatch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            started = true
        } catch (t: Throwable) {
            errorln { "[LinuxTrayManager] startTray failed: $t" }
        } finally {
            if (!started) {
                running.set(false)
                if (trayHandle != 0L) {
                    try {
                        native.nativeQuit(trayHandle)
                    } catch (_: Throwable) {
                    }
                    try {
                        loopThread?.join(500)
                    } catch (_: Throwable) {
                    }
                    try {
                        native.nativeDestroy(trayHandle)
                    } catch (_: Throwable) {
                    }
                    trayHandle = 0L
                }
                loopThread = null
                try {
                    shutdownHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
                } catch (_: Throwable) {
                }
                shutdownHook = null
                if (permitHeld.compareAndSet(true, false)) {
                    try {
                        lifecyclePermit.release()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    fun stopTray() {
        if (!running.compareAndSet(true, false)) return
        try {
            if (trayHandle != 0L) native.nativeQuit(trayHandle)
        } catch (_: Throwable) {
        }

        try {
            loopThread?.join(500)
            if (loopThread?.isAlive == true) {
                warnln { "[LinuxTrayManager] loop thread still alive after join timeout" }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            if (trayHandle != 0L) native.nativeDestroy(trayHandle)
        } catch (_: Throwable) {
        }

        trayHandle = 0L
        loopThread = null
        idByTitle.clear()
        actionById.clear()
        try {
            shutdownHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
        } catch (_: Throwable) {
        }
        shutdownHook = null
        if (permitHeld.compareAndSet(true, false)) {
            try {
                lifecyclePermit.release()
            } catch (_: Throwable) {
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    private fun setIconFromFileSafe(path: String) {
        runCatching {
            val file = File(path)
            if (file.isFile && trayHandle != 0L) {
                native.nativeSetIcon(trayHandle, file.readBytes())
            } else {
                warnln { "[LinuxTrayManager] Icon file not found: $path" }
            }
        }.onFailure { e -> warnln { "[LinuxTrayManager] Failed to set icon from $path: ${e.message}" } }
    }

    private fun rebuildMenu() {
        if (trayHandle == 0L) return
        infoln { "[LinuxTrayManager] Rebuilding menu" }
        idByTitle.clear()
        actionById.clear()
        runCatching { native.nativeResetMenu(trayHandle) }
        val items = lock.withLock { menuItems.toList() }
        // KDE quirk: empty menu causes issues, add dummy separator
        val effectiveItems = if (items.isEmpty() && isKDEDesktop()) listOf(MenuItem("-")) else items
        effectiveItems.forEach { addMenuItemRecursive(null, it) }
    }

    private fun addMenuItemRecursive(
        parentId: Int?,
        item: MenuItem,
    ) {
        try {
            if (item.text == "-") {
                if (parentId == null) {
                    native.nativeAddSeparator(trayHandle)
                } else {
                    runCatching { native.nativeAddSubSeparator(trayHandle, parentId) }
                        .onFailure {
                            val id = native.nativeAddSubMenuItem(trayHandle, parentId, "-", null)
                            native.nativeItemDisable(trayHandle, id)
                        }
                }
                return
            }

            val id =
                if (parentId == null) {
                    if (item.isCheckable) {
                        native.nativeAddMenuItemCheckbox(trayHandle, item.text, null, item.isChecked)
                    } else {
                        native.nativeAddMenuItem(trayHandle, item.text, null)
                    }
                } else {
                    if (item.isCheckable) {
                        native.nativeAddSubMenuItemCheckbox(trayHandle, parentId, item.text, null, item.isChecked)
                    } else {
                        native.nativeAddSubMenuItem(trayHandle, parentId, item.text, null)
                    }
                }
            idByTitle[item.text] = id
            item.onClick?.let { action ->
                actionById[id] = action
                native.nativeSetMenuItemCallback(trayHandle, id, JniRunnable { action() })
            }

            // Enable/Disable
            if (item.isEnabled) {
                native.nativeItemEnable(trayHandle, id)
            } else {
                native.nativeItemDisable(trayHandle, id)
            }

            // Icon
            item.iconPath?.let { iconPath ->
                runCatching {
                    val bytes = File(iconPath).takeIf { it.isFile }?.readBytes()
                    if (bytes != null) native.nativeItemSetIcon(trayHandle, id, bytes)
                }.onFailure { e -> warnln { "[LinuxTrayManager] Failed to set menu item icon: ${e.message}" } }
            }

            // Keyboard shortcut hint
            item.shortcut?.let { shortcut ->
                runCatching {
                    native.nativeItemSetShortcut(
                        trayHandle,
                        id,
                        shortcut.toLinuxKey(),
                        shortcut.ctrl,
                        shortcut.shift,
                        shortcut.alt,
                        shortcut.meta,
                    )
                }.onFailure { e -> warnln { "[LinuxTrayManager] Failed to set shortcut: ${e.message}" } }
            }

            // Submenu
            if (item.subMenuItems.isNotEmpty()) {
                item.subMenuItems.forEach { sub -> addMenuItemRecursive(id, sub) }
            }
        } catch (t: Throwable) {
            errorln { "[LinuxTrayManager] Error adding menu item '${item.text}': $t" }
        }
    }
}
