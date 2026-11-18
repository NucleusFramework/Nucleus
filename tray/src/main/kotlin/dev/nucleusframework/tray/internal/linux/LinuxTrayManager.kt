package dev.nucleusframework.tray.internal.linux

import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.utils.TrayClickTracker
import dev.nucleusframework.tray.utils.errorln
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One StatusNotifierItem.
 *
 * [submit] hands the whole wanted [State] to the native side, which applies the newest one on the
 * tray's own thread and tells the host what changed; nothing here waits for that thread. Callbacks
 * run on [Dispatchers.IO], never on the tray thread, so a slow or throwing callback never holds up
 * the D-Bus loop and a callback may update or dispose its own tray.
 */
internal class LinuxTrayManager(
    private val instanceId: String,
    private val itemId: String,
) {
    /** A menu entry. [checkToken] identifies a checkable item across rebuilds. */
    data class MenuItem(
        val text: String,
        val iconPath: String? = null,
        val isEnabled: Boolean = true,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false,
        val shortcut: KeyShortcut? = null,
        val onClick: (() -> Unit)? = null,
        val subMenuItems: List<MenuItem> = emptyList(),
        val isSeparator: Boolean = false,
        val isSubmenu: Boolean = false,
        val checkToken: Any? = null,
    )

    /** Everything the item shows. */
    data class State(
        val iconPath: String,
        val tooltip: String,
        val onLeftClick: (() -> Unit)?,
        val onMenuOpened: (() -> Unit)?,
        val menuItems: List<MenuItem>,
    )

    private val lock = Any()
    private var handle = 0L
    private var stopped = false
    private var shown: State? = null

    @Volatile private var running = true

    private val callbackScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> errorln { "[LinuxTray] $instanceId callback failed: $e" } },
        )

    /** Publishes [state] as the one to show, creating the item on first use. */
    fun submit(state: State) {
        synchronized(lock) {
            if (stopped) return
            if (handle == 0L) {
                if (!LinuxNativeBridge.isLoaded) {
                    errorln { "[LinuxTray] libnucleus_tray is not available; no tray icon" }
                    stopped = true
                    return
                }
                handle = LinuxNativeBridge.nativeCreate(itemId)
                if (handle == 0L) {
                    errorln { "[LinuxTray] $instanceId: the native tray could not be created" }
                    return
                }
            }
            shown = state
            push(handle, state)
        }
    }

    /** Sets the checked state of the checkable item created with [token]. */
    fun updateMenuItemCheckedState(
        token: Any,
        isChecked: Boolean,
    ) {
        synchronized(lock) {
            val state = shown ?: return
            submit(state.copy(menuItems = state.menuItems.patched(token, isChecked)))
        }
    }

    /** Removes the item. The manager cannot be reused. */
    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            running = false
            if (handle != 0L) LinuxNativeBridge.nativeDispose(handle)
            handle = 0L
        }
        callbackScope.cancel()
    }

    private fun push(
        handle: Long,
        state: State,
    ) {
        val flat = ArrayList<Pair<MenuItem, Int>>()
        flatten(state.menuItems, 0, flat)
        val onLeftClick = state.onLeftClick
        LinuxNativeBridge.nativeSubmit(
            handle,
            state.iconPath,
            state.tooltip,
            onLeftClick?.let {
                object : LinuxNativeBridge.ClickCallback {
                    override fun onClick(
                        x: Int,
                        y: Int,
                    ) {
                        // Recorded before the hand-off: TrayApp anchors its popup to its own icon.
                        TrayClickTracker.recordClick(instanceId, x, y)
                        dispatch(onLeftClick)
                    }
                }
            },
            state.onMenuOpened?.let { onOpened -> Runnable { dispatch(onOpened) } },
            Array(flat.size) { flat[it].first.text },
            Array(flat.size) { flat[it].first.iconPath },
            Array(flat.size) { flat[it].first.shortcut?.toLinuxKey() },
            IntArray(flat.size) { flat[it].first.shortcut?.linuxModifiers() ?: 0 },
            IntArray(flat.size) { flat[it].first.nativeFlags() },
            IntArray(flat.size) { flat[it].second },
            Array(flat.size) { i -> flat[i].first.onClick?.let { onClick -> Runnable { dispatch(onClick) } } },
        )
    }

    private fun flatten(
        items: List<MenuItem>,
        depth: Int,
        out: MutableList<Pair<MenuItem, Int>>,
    ) {
        for (item in items) {
            out += item to depth
            if (!item.isSeparator) flatten(item.subMenuItems, depth + 1, out)
        }
    }

    private fun MenuItem.nativeFlags(): Int {
        var flags = 0
        if (!isEnabled) flags = flags or LinuxNativeBridge.FLAG_DISABLED
        if (isChecked) flags = flags or LinuxNativeBridge.FLAG_CHECKED
        if (isCheckable) flags = flags or LinuxNativeBridge.FLAG_CHECKABLE
        if (isSeparator) flags = flags or LinuxNativeBridge.FLAG_SEPARATOR
        if (isSubmenu || subMenuItems.isNotEmpty()) flags = flags or LinuxNativeBridge.FLAG_SUBMENU
        return flags
    }

    private fun KeyShortcut.linuxModifiers(): Int {
        var mods = 0
        if (ctrl) mods = mods or LinuxNativeBridge.MOD_CTRL
        if (shift) mods = mods or LinuxNativeBridge.MOD_SHIFT
        if (alt) mods = mods or LinuxNativeBridge.MOD_ALT
        if (meta) mods = mods or LinuxNativeBridge.MOD_SUPER
        return mods
    }

    private fun dispatch(callback: () -> Unit) {
        if (running) callbackScope.launch { callback() }
    }

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
}
