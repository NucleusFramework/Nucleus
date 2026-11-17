package dev.nucleusframework.tray.internal.windows

import dev.nucleusframework.tray.utils.TrayClickTracker
import dev.nucleusframework.tray.utils.debugln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WindowsTrayManager(
    private val instanceId: String,
    private var iconPath: String,
    private var tooltip: String = "",
    private var onLeftClick: (() -> Unit)? = null,
    private var onMenuOpened: (() -> Unit)? = null,
) {
    private var trayHandle: Long = 0L
    private val running = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val updateLock = ReentrantLock()
    private val initLatch = CountDownLatch(1)

    // Track allocated menu handles for cleanup
    private val menuHandles: MutableList<Pair<Long, Int>> = mutableListOf()

    // Thread for running the tray (similar to macOS)
    private var trayThread: Thread? = null

    // Coroutine scopes for callback handling
    private var mainScope: CoroutineScope? = null
    private var ioScope: CoroutineScope? = null

    // Queue for updates to be processed on the tray thread
    private val updateQueue = mutableListOf<UpdateRequest>()
    private val updateQueueLock = Object()

    // Last applied menu structure + pending checked-state patches (processed on the tray thread)
    private var currentMenuItems: List<MenuItem> = emptyList()
    private val checkedUpdateQueue = mutableListOf<Pair<String, Boolean>>()

    companion object {
        private fun log(message: String) {
            debugln { "[WindowsTrayManager] $message" }
        }
    }

    // Update request data class
    private data class UpdateRequest(
        val iconPath: String,
        val tooltip: String,
        val onLeftClick: (() -> Unit)?,
        val onMenuOpened: (() -> Unit)?,
        val menuItems: List<MenuItem>,
    )

    // Top level MenuItem class
    data class MenuItem(
        val text: String,
        val iconPath: String? = null,
        val isEnabled: Boolean = true,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false,
        val onClick: (() -> Unit)? = null,
        val subMenuItems: List<MenuItem> = emptyList(),
    )

    fun initialize(menuItems: List<MenuItem>) {
        log("initialize() called with ${menuItems.size} menu items")
        updateLock.withLock {
            if (initialized.get()) {
                log("Already initialized, delegating to update()")
                update(iconPath, tooltip, onLeftClick, onMenuOpened, menuItems)
                return
            }

            running.set(true)
            currentMenuItems = menuItems

            // Create coroutine scopes
            mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            // Create and start the tray thread
            trayThread =
                Thread {
                    try {
                        log("Tray thread started")

                        // Create tray via JNI
                        val handle = WindowsNativeBridge.nativeCreateTray(iconPath, tooltip)
                        if (handle == 0L) throw RuntimeException("Failed to create tray")
                        trayHandle = handle

                        // Set up callbacks and menu on this thread
                        setupLeftClickCallback(handle)
                        setupMenu(handle, menuItems)

                        // Initialize the tray on this thread
                        log("Calling nativeInitTray() on tray thread")
                        val initResult = WindowsNativeBridge.nativeInitTray(handle)
                        log("nativeInitTray() returned: $initResult")

                        if (initResult != 0) {
                            throw RuntimeException("Failed to initialize tray: $initResult")
                        }

                        // Set menu-opened callback after init (TrayContext must exist)
                        setupMenuOpenedCallback(handle)

                        initialized.set(true)

                        // Signal that initialization is complete before entering the loop
                        initLatch.countDown()

                        // Run the blocking message loop on this thread
                        runMessageLoop()
                    } catch (e: Throwable) {
                        log("Error in tray thread: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        // Safety net: release latch in case an Error prevented the
                        // countDown above from being reached.  CountDownLatch.countDown()
                        // is a no-op when the count is already 0, so calling it twice
                        // on the success path is harmless.
                        initLatch.countDown()
                        cleanupTray()
                    }
                }.apply {
                    name = "WindowsTray-Thread"
                    isDaemon = false // Don't make it daemon so it can clean up properly
                    start()
                }

            // Wait for initialization to complete
            try {
                initLatch.await()
            } catch (e: InterruptedException) {
                log("Interrupted while waiting for initialization")
                e.printStackTrace()
            }
        }
    }

    fun update(
        newIconPath: String,
        newTooltip: String,
        newOnLeftClick: (() -> Unit)?,
        newOnMenuOpened: (() -> Unit)?,
        newMenuItems: List<MenuItem>,
    ) {
        log("update() called - icon: $newIconPath, tooltip: $newTooltip, menuItems: ${newMenuItems.size}")

        if (!initialized.get()) {
            log("Not initialized, calling initialize()")
            iconPath = newIconPath
            tooltip = newTooltip
            onLeftClick = newOnLeftClick
            onMenuOpened = newOnMenuOpened
            initialize(newMenuItems)
            return
        }

        // Queue the update to be processed on the tray thread
        synchronized(updateQueueLock) {
            updateQueue.add(UpdateRequest(newIconPath, newTooltip, newOnLeftClick, newOnMenuOpened, newMenuItems))
            updateQueueLock.notify()
        }
    }

    /**
     * Patches the checked state of a checkable item (matched by its full menu text) without
     * requiring a full rebuild from the caller. Processed on the tray thread.
     */
    fun updateMenuItemCheckedState(
        text: String,
        isChecked: Boolean,
    ) {
        synchronized(updateQueueLock) {
            checkedUpdateQueue.add(text to isChecked)
            updateQueueLock.notify()
        }
    }

    private fun runMessageLoop() {
        log("Entering message loop on tray thread")
        var consecutiveErrors = 0
        var initialPosCaptured = false
        var initialPosAttempts = 0
        var positionErrorCount = 0
        val maxPositionErrors = 3

        while (running.get()) {
            try {
                // Try to capture initial precise tray icon position (per instance)
                if (!initialPosCaptured && initialPosAttempts < 60 && positionErrorCount < maxPositionErrors) {
                    initialPosAttempts++
                    if (safeGetTrayPosition(instanceId)) {
                        initialPosCaptured = true
                        log("Captured initial tray icon position")
                    }
                }

                // Check for pending updates
                processUpdateQueue()
                processCheckedUpdateQueue()

                // Process Windows messages with non-blocking call
                val result = WindowsNativeBridge.nativeLoopTray(0)

                when (result) {
                    -1 -> {
                        log("nativeLoopTray returned -1 (error or quit)")
                        if (running.get() && initialized.get()) {
                            consecutiveErrors++
                            if (consecutiveErrors > 5) {
                                log("Too many consecutive errors, exiting loop")
                                break
                            }
                            Thread.sleep(100)

                            // Try to recover
                            val handle = trayHandle
                            if (handle != 0L) {
                                try {
                                    log("Attempting to recover tray...")
                                    WindowsNativeBridge.nativeUpdateTray(handle)
                                    consecutiveErrors = 0
                                } catch (e: Exception) {
                                    log("Failed to recover: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        } else {
                            break
                        }
                    }
                    0 -> {
                        // Normal operation
                        consecutiveErrors = 0
                    }
                    else -> {
                        log("nativeLoopTray returned unexpected value: $result")
                        consecutiveErrors = 0
                    }
                }
                Thread.sleep(50)
            } catch (e: Exception) {
                log("Exception in message loop: ${e.message}")
                if (running.get()) {
                    e.printStackTrace()
                    Thread.sleep(100)
                } else {
                    break
                }
            }
        }
        log("Message loop ended")
    }

    /**
     * Safely attempts to get the tray position with error handling
     * @return true if position was successfully obtained, false otherwise
     */
    private fun safeGetTrayPosition(instanceId: String): Boolean {
        return try {
            val outXY = IntArray(2)
            val precise = WindowsNativeBridge.nativeGetNotificationIconsPosition(outXY) != 0
            log("nativeGetNotificationIconsPosition: precise=$precise, rawX=${outXY[0]}, rawY=${outXY[1]}")
            if (precise) {
                // Record the raw physical click; TrayApp converts to logical and
                // resolves the corner against the Tao-backed screen geometry it owns.
                TrayClickTracker.recordClick(instanceId, outXY[0], outXY[1])
                true
            } else {
                false
            }
        } catch (e: Error) {
            // Handle invalid memory access error
            log("Failed to get tray icon position (memory access error): ${e.message}")
            false
        } catch (e: Exception) {
            // Handle other exceptions
            log("Failed to get tray icon position: ${e.message}")
            false
        }
    }

    /**
     * Public method to force a fresh capture of the tray icon position.
     * Called when Windows may have reorganized icons after creation.
     */
    fun refreshPosition() {
        log("Refreshing tray position...")
        safeGetTrayPosition(instanceId)
    }

    private fun processUpdateQueue() {
        val update =
            synchronized(updateQueueLock) {
                if (updateQueue.isNotEmpty()) {
                    updateQueue.removeAt(0)
                } else {
                    null
                }
            }

        if (update != null) {
            log("Processing update from queue")
            performUpdate(update)
        }
    }

    private fun processCheckedUpdateQueue() {
        val updates =
            synchronized(updateQueueLock) {
                if (checkedUpdateQueue.isEmpty()) return
                val copy = checkedUpdateQueue.toList()
                checkedUpdateQueue.clear()
                copy
            }

        val handle = trayHandle
        if (handle == 0L) return

        currentMenuItems =
            updates.fold(currentMenuItems) { items, (text, checked) ->
                patchCheckedState(items, text, checked)
            }

        log("Applying ${updates.size} checked-state patch(es)")
        freeMenuHandles()
        setupMenu(handle, currentMenuItems)
        try {
            WindowsNativeBridge.nativeUpdateTray(handle)
        } catch (e: Throwable) {
            log("Failed to apply checked-state patch: ${e.message}")
        }
    }

    private fun patchCheckedState(
        items: List<MenuItem>,
        text: String,
        checked: Boolean,
    ): List<MenuItem> =
        items.map { item ->
            when {
                item.isCheckable && item.text == text -> item.copy(isChecked = checked)
                item.subMenuItems.isNotEmpty() ->
                    item.copy(subMenuItems = patchCheckedState(item.subMenuItems, text, checked))
                else -> item
            }
        }

    private fun performUpdate(update: UpdateRequest) {
        // Update properties
        iconPath = update.iconPath
        tooltip = update.tooltip
        onLeftClick = update.onLeftClick
        onMenuOpened = update.onMenuOpened
        currentMenuItems = update.menuItems

        val handle = trayHandle
        if (handle == 0L) return

        // Free old menu handles
        freeMenuHandles()

        // Update tray properties
        WindowsNativeBridge.nativeSetTrayIcon(handle, update.iconPath)
        WindowsNativeBridge.nativeSetTrayTooltip(handle, update.tooltip)

        // Set up new callbacks and menu
        setupLeftClickCallback(handle)
        setupMenu(handle, update.menuItems)
        setupMenuOpenedCallback(handle)

        // Update the native tray
        log("Calling nativeUpdateTray()")
        try {
            WindowsNativeBridge.nativeUpdateTray(handle)
            log("nativeUpdateTray() completed")
        } catch (e: Error) {
            log("Failed to update tray (memory access error): ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            log("Failed to update tray: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupLeftClickCallback(handle: Long) {
        val leftClick = onLeftClick
        if (leftClick != null) {
            log("Setting up left click callback")
            WindowsNativeBridge.nativeSetTrayCallback(
                handle,
                Runnable {
                    log("Left click callback invoked")
                    try {
                        // Capture precise tray position on the tray thread (per-instance)
                        safeGetTrayPosition(instanceId)

                        // Execute callback in IO scope (like macOS)
                        mainScope?.launch {
                            ioScope?.launch {
                                leftClick()
                            }
                        }
                    } catch (e: Exception) {
                        log("Error in left click callback: ${e.message}")
                        e.printStackTrace()
                    }
                },
            )
        } else {
            log("No left click callback set")
            WindowsNativeBridge.nativeSetTrayCallback(handle, null)
        }
    }

    private fun setupMenuOpenedCallback(handle: Long) {
        val callback = onMenuOpened
        if (callback != null) {
            log("Setting up menu opened callback")
            WindowsNativeBridge.nativeSetMenuOpenedCallback(
                handle,
                Runnable {
                    log("Menu opened callback invoked")
                    try {
                        mainScope?.launch {
                            ioScope?.launch {
                                callback()
                            }
                        }
                    } catch (e: Exception) {
                        log("Error in menu opened callback: ${e.message}")
                        e.printStackTrace()
                    }
                },
            )
        } else {
            log("No menu opened callback set")
            WindowsNativeBridge.nativeSetMenuOpenedCallback(handle, null)
        }
    }

    private fun setupMenu(
        handle: Long,
        menuItems: List<MenuItem>,
    ) {
        if (menuItems.isEmpty()) {
            log("No menu items to set up")
            WindowsNativeBridge.nativeClearTrayMenu(handle)
            return
        }

        log("Setting up ${menuItems.size} menu items")
        val menuHandle = buildMenuItems(menuItems)
        WindowsNativeBridge.nativeSetTrayMenu(handle, menuHandle)
    }

    private fun buildMenuItems(menuItems: List<MenuItem>): Long {
        val menuHandle = WindowsNativeBridge.nativeCreateMenuItems(menuItems.size)
        menuHandles.add(menuHandle to menuItems.size)

        menuItems.forEachIndexed { index, item ->
            WindowsNativeBridge.nativeSetMenuItem(
                menuHandle,
                index,
                item.text,
                item.iconPath,
                if (item.isEnabled) 0 else 1,
                if (item.isChecked) 1 else 0,
            )

            item.onClick?.let { onClick ->
                val callback =
                    Runnable {
                        log("Menu item clicked: ${item.text}")
                        try {
                            // Capture precise tray position on the tray thread (per-instance)
                            safeGetTrayPosition(instanceId)

                            if (running.get()) {
                                // Execute callback in IO scope (like macOS)
                                mainScope?.launch {
                                    ioScope?.launch {
                                        onClick()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            log("Error in menu item callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                WindowsNativeBridge.nativeSetMenuItemCallback(menuHandle, index, callback)
            }

            if (item.subMenuItems.isNotEmpty()) {
                val subMenuHandle = buildMenuItems(item.subMenuItems)
                WindowsNativeBridge.nativeSetMenuItemSubmenu(menuHandle, index, subMenuHandle)
            }
        }

        return menuHandle
    }

    private fun freeMenuHandles() {
        for ((handle, count) in menuHandles) {
            WindowsNativeBridge.nativeFreeMenuItems(handle, count)
        }
        menuHandles.clear()
    }

    private fun cleanupTray() {
        if (initialized.get()) {
            try {
                log("Calling nativeExitTray()")
                WindowsNativeBridge.nativeExitTray()
            } catch (e: Error) {
                log("Error in nativeExitTray() (memory access): ${e.message}")
                e.printStackTrace()
            } catch (e: Exception) {
                log("Error in nativeExitTray(): ${e.message}")
                e.printStackTrace()
            }
        }

        // Free menu handles
        freeMenuHandles()

        // Free tray struct
        val handle = trayHandle
        if (handle != 0L) {
            try {
                WindowsNativeBridge.nativeFreeTray(handle)
            } catch (_: Throwable) {
            }
            trayHandle = 0L
        }

        initialized.set(false)
    }

    fun stopTray() {
        log("stopTray() called")
        updateLock.withLock {
            running.set(false)

            // Wake up the thread if it's waiting
            synchronized(updateQueueLock) {
                updateQueueLock.notify()
            }

            // Wait for the tray thread to finish
            trayThread?.let { thread ->
                try {
                    thread.join(5000) // Wait up to 5 seconds
                    if (thread.isAlive) {
                        log("Tray thread still alive after 5 seconds, interrupting")
                        thread.interrupt()
                    }
                } catch (e: InterruptedException) {
                    log("Interrupted while waiting for tray thread")
                    e.printStackTrace()
                }
            }

            // Cancel coroutines
            mainScope?.cancel()
            ioScope?.cancel()
            mainScope = null
            ioScope = null
            trayThread = null

            log("Tray stopped and cleaned up")
        }
    }
}
