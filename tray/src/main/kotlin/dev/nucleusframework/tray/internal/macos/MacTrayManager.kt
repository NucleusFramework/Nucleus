package dev.nucleusframework.tray.internal.macos

import dev.nucleusframework.tray.menu.KeyShortcut
import dev.nucleusframework.tray.utils.errorln
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One macOS status item.
 *
 * [submit] hands the whole wanted [State] to the native side, which applies the newest one on
 * the AppKit main thread; nothing here waits for that thread, so a caller holding a lock the
 * main thread needs cannot deadlock. Callbacks run on [Dispatchers.IO], never on the main thread.
 */
internal class MacTrayManager(
    private val instanceId: String,
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
        val checkToken: Any? = null,
    )

    /** Everything the status item shows. */
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
                CoroutineExceptionHandler { _, e -> errorln { "[MacTray] $instanceId callback failed: $e" } },
        )

    /** Publishes [state] as the one to show, creating the status item on first use. */
    fun submit(state: State) {
        synchronized(lock) {
            if (stopped) return
            if (handle == 0L) handle = MacNativeBridge.nativeCreateTray()
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

    /** Light / dark variants of the current icon, swapped with the menu bar appearance. */
    fun setAppearanceIcons(
        lightIconPath: String,
        darkIconPath: String,
    ) {
        withHandle { MacNativeBridge.nativeSetIconsForAppearance(it, lightIconPath, darkIconPath) }
    }

    /** Removes the status item. The manager cannot be reused. */
    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            running = false
            if (handle != 0L) MacNativeBridge.nativeDispose(handle)
            handle = 0L
        }
        callbackScope.cancel()
    }

    /** Status item position (points, top-left origin); false until it is laid out. */
    fun position(outXY: IntArray): Boolean =
        withHandle { MacNativeBridge.nativeGetStatusItemPosition(it, outXY) != 0 } ?: false

    /** "top-left" | "top-right", or null until the status item is laid out. */
    fun region(): String? = withHandle { MacNativeBridge.nativeGetStatusItemRegion(it) }

    /** Runs [block] with the live native handle, or returns null once stopped / before the first [submit]. */
    fun <T> withHandle(block: (Long) -> T): T? =
        synchronized(lock) {
            if (handle == 0L) null else block(handle)
        }

    private fun push(
        handle: Long,
        state: State,
    ) {
        val flat = ArrayList<Pair<MenuItem, Int>>()
        flatten(state.menuItems, 0, flat)
        MacNativeBridge.nativeSubmit(
            handle,
            state.iconPath,
            state.tooltip,
            state.onLeftClick?.let { onClick -> Runnable { dispatch(onClick) } },
            state.onMenuOpened?.let { onOpened -> Runnable { dispatch(onOpened) } },
            Array(flat.size) { flat[it].first.text },
            Array(flat.size) { flat[it].first.iconPath },
            Array(flat.size) { flat[it].first.shortcut?.toMacKeyEquivalent() },
            LongArray(flat.size) { flat[it].first.shortcut?.toMacModifierMask() ?: 0L },
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
        if (!isEnabled) flags = flags or MacNativeBridge.FLAG_DISABLED
        if (isChecked) flags = flags or MacNativeBridge.FLAG_CHECKED
        if (isSeparator) flags = flags or MacNativeBridge.FLAG_SEPARATOR
        return flags
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
