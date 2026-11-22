package dev.nucleusframework.tray.internal.windows

import dev.nucleusframework.tray.utils.TrayClickTracker
import dev.nucleusframework.tray.utils.debugln
import dev.nucleusframework.tray.utils.errorln
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One Windows notification-area icon, owned by a dedicated thread.
 *
 * The thread creates the native tray, applies every state change and pumps the hidden window's
 * messages, sleeping in between (no polling). Other threads only publish the latest wanted
 * [State] and wake it: updates are conflated, so a burst of them costs one native rebuild and
 * always ends on the newest one.
 */
internal class WindowsTrayManager(
    private val instanceId: String,
) {
    /** A menu entry. [checkToken] identifies a checkable item across rebuilds. */
    data class MenuItem(
        val text: String,
        val iconPath: String? = null,
        val isEnabled: Boolean = true,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false,
        val onClick: (() -> Unit)? = null,
        val subMenuItems: List<MenuItem> = emptyList(),
        val isSeparator: Boolean = false,
        val checkToken: Any? = null,
    )

    /** Everything the icon shows. */
    data class State(
        val iconPath: String,
        val tooltip: String,
        val onLeftClick: (() -> Unit)?,
        val onMenuOpened: (() -> Unit)?,
        val menuItems: List<MenuItem>,
    )

    private val lock = Object()
    private var pending: State? = null
    private val checkPatches = mutableListOf<Pair<Any, Boolean>>()
    private var started = false
    private var stopped = false
    private var thread: Thread? = null

    // The native struct is freed by the tray thread; wakers read it under this lock.
    private val handleLock = Object()

    @Volatile private var trayHandle: Long = 0L

    @Volatile private var running = true

    private val initialized = CountDownLatch(1)

    private val callbackScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> errorln { "[WindowsTray] $instanceId callback failed: $e" } },
        )

    /** Publishes [state] as the one to show, starting the tray on first use. */
    fun submit(state: State) {
        synchronized(lock) {
            if (stopped) return
            pending = state
            if (!started) {
                started = true
                thread =
                    Thread({ runTray() }, "NucleusTray-$instanceId").apply {
                        isDaemon = true
                        start()
                    }
            }
        }
        wake(cancelMenu = false)
    }

    /** Sets the checked state of the checkable item created with [token]. */
    fun updateMenuItemCheckedState(
        token: Any,
        isChecked: Boolean,
    ) {
        synchronized(lock) {
            if (stopped) return
            checkPatches += token to isChecked
        }
        wake(cancelMenu = false)
    }

    /** Blocks until the native icon exists (or failed to), for at most [timeoutMs]. */
    fun awaitInitialized(timeoutMs: Long): Boolean = initialized.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Removes the icon and ends the thread. The manager cannot be reused. */
    fun stop() {
        val toJoin =
            synchronized(lock) {
                if (stopped) return
                stopped = true
                running = false
                thread
            }
        // Closes a popup menu that is still open: TrackPopupMenu would otherwise hold the thread.
        wake(cancelMenu = true)
        if (toJoin != null && toJoin !== Thread.currentThread()) {
            toJoin.join(STOP_TIMEOUT_MS)
            if (toJoin.isAlive) errorln { "[WindowsTray] $instanceId thread did not stop in ${STOP_TIMEOUT_MS}ms" }
        }
        callbackScope.cancel()
    }

    /** Re-reads the icon position (the shell may move icons after creation). */
    fun refreshPosition() {
        synchronized(handleLock) { recordPosition(trayHandle) }
    }

    /** Hidden window and Shell icon id of this tray, or null before init / after exit. */
    fun nativeIdentity(): Pair<Long, Int>? =
        synchronized(handleLock) {
            val handle = trayHandle
            if (handle == 0L) return null
            val out = LongArray(2)
            if (WindowsNativeBridge.nativeGetIdentity(handle, out)) out[0] to out[1].toInt() else null
        }

    private fun wake(cancelMenu: Boolean) {
        synchronized(handleLock) {
            val handle = trayHandle
            if (handle != 0L) WindowsNativeBridge.nativeWakeTray(handle, cancelMenu)
        }
    }

    // ------------------------------------------------------------ tray thread

    private var shown: State? = null
    private var shownMenu: Long = 0L

    private fun runTray() {
        var handle = 0L
        try {
            val first = synchronized(lock) { pending.also { pending = null } } ?: return
            handle = WindowsNativeBridge.nativeCreateTray(first.iconPath, first.tooltip)
            check(handle != 0L) { "nativeCreateTray failed" }
            apply(handle, first, initialized = false)
            val result = WindowsNativeBridge.nativeInitTray(handle)
            check(result == 0) { "nativeInitTray failed: $result" }
            synchronized(handleLock) { trayHandle = handle }
            initialized.countDown()
            recordPosition(handle)
            debugln { "[WindowsTray] $instanceId ready" }

            while (running) {
                val (state, patches) =
                    synchronized(lock) {
                        val s = pending
                        pending = null
                        val p = checkPatches.toList()
                        checkPatches.clear()
                        s to p
                    }
                val next = state ?: shown?.takeIf { patches.isNotEmpty() }
                if (next != null) apply(handle, patches.fold(next) { s, (t, c) -> s.patched(t, c) }, initialized = true)
                if (!running) break
                WindowsNativeBridge.nativePumpTray(handle, wait = true)
            }
        } catch (t: Throwable) {
            errorln { "[WindowsTray] $instanceId failed: $t" }
        } finally {
            initialized.countDown()
            if (handle != 0L) {
                synchronized(handleLock) {
                    trayHandle = 0L
                    WindowsNativeBridge.nativeFreeTray(handle)
                }
            }
            if (shownMenu != 0L) WindowsNativeBridge.nativeFreeMenuItems(shownMenu)
            shownMenu = 0L
            debugln { "[WindowsTray] $instanceId stopped" }
        }
    }

    private fun apply(
        handle: Long,
        state: State,
        initialized: Boolean,
    ) {
        WindowsNativeBridge.nativeSetTrayIcon(handle, state.iconPath)
        WindowsNativeBridge.nativeSetTrayTooltip(handle, state.tooltip)
        WindowsNativeBridge.nativeSetTrayCallback(
            handle,
            state.onLeftClick?.let { onClick ->
                Runnable {
                    recordPosition(handle)
                    dispatch(onClick)
                }
            },
        )
        WindowsNativeBridge.nativeSetMenuOpenedCallback(handle, state.onMenuOpened?.let { Runnable { dispatch(it) } })

        // The old menu is freed only once the native side points at the new one.
        val previous = shownMenu
        val menu = if (state.menuItems.isEmpty()) 0L else buildMenu(handle, state.menuItems)
        WindowsNativeBridge.nativeSetTrayMenu(handle, menu)
        if (initialized) WindowsNativeBridge.nativeUpdateTray(handle)
        if (previous != 0L) WindowsNativeBridge.nativeFreeMenuItems(previous)
        shownMenu = menu
        shown = state
    }

    private fun buildMenu(
        trayHandle: Long,
        items: List<MenuItem>,
    ): Long {
        val menu = WindowsNativeBridge.nativeCreateMenuItems(items.size)
        items.forEachIndexed { index, item ->
            WindowsNativeBridge.nativeSetMenuItem(
                menu,
                index,
                item.text,
                item.iconPath,
                !item.isEnabled,
                item.isChecked,
                item.isSeparator,
            )
            item.onClick?.let { onClick ->
                WindowsNativeBridge.nativeSetMenuItemCallback(
                    menu,
                    index,
                    Runnable {
                        recordPosition(trayHandle)
                        dispatch(onClick)
                    },
                )
            }
            if (item.subMenuItems.isNotEmpty()) {
                WindowsNativeBridge.nativeSetMenuItemSubmenu(menu, index, buildMenu(trayHandle, item.subMenuItems))
            }
        }
        return menu
    }

    private fun dispatch(callback: () -> Unit) {
        if (running) callbackScope.launch { callback() }
    }

    private fun recordPosition(handle: Long) {
        if (handle == 0L) return
        val xy = IntArray(2)
        if (WindowsNativeBridge.nativeGetNotificationIconsPosition(handle, xy) != 0) {
            // Raw physical pixels; TrayApp converts them against the screen geometry it owns.
            TrayClickTracker.recordClick(instanceId, xy[0], xy[1])
        }
    }

    private fun State.patched(
        token: Any,
        checked: Boolean,
    ): State = copy(menuItems = menuItems.patched(token, checked))

    private fun List<MenuItem>.patched(
        token: Any,
        checked: Boolean,
    ): List<MenuItem> =
        map { item ->
            when {
                item.checkToken === token -> item.copy(isChecked = checked)
                item.subMenuItems.isNotEmpty() -> item.copy(subMenuItems = item.subMenuItems.patched(token, checked))
                else -> item
            }
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
