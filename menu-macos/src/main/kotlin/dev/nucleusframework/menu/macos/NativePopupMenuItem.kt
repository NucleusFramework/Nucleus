package dev.nucleusframework.menu.macos

/**
 * One row in a [popUpNativeMenu] tree: a clickable item, a separator, or a
 * submenu. Used by the Tao native context-menu representation.
 */
public sealed class NativePopupMenuItem {
    /**
     * A clickable item.
     *
     * @param title Label shown in the menu.
     * @param enabled Whether the item can be chosen.
     * @param icon Optional native image (SF Symbol, named image, or file).
     * @param onClick Invoked on the UI thread after the menu dismisses if the
     *   user chose this item.
     */
    public class Entry(
        public val title: String,
        public val enabled: Boolean = true,
        public val icon: NsMenuItemImage? = null,
        public val onClick: () -> Unit = {},
    ) : NativePopupMenuItem()

    /** A horizontal separator. */
    public object Separator : NativePopupMenuItem()

    /**
     * A nested submenu.
     *
     * @param title Label of the submenu item.
     * @param items Children shown when the submenu opens.
     */
    public class Submenu(
        public val title: String,
        public val items: List<NativePopupMenuItem>,
    ) : NativePopupMenuItem()
}

/** Whether [popUpNativeMenu] can show an AppKit menu on this process. */
public val isNativePopupMenuAvailable: Boolean
    get() = NsMenu.isAvailable

/**
 * Pops a native `NSMenu` at the current cursor location.
 *
 * Blocks the calling thread (AppKit nested tracking loop) until the user
 * chooses an item or dismisses the menu. No-op and returns `false` when the
 * native library is not loaded.
 *
 * Do **not** call this on the UI thread of the Tao backend: from there the
 * tracking loop opens inside tao's event callback, whose reentrancy guard
 * suppresses every `MainEventsCleared` tick — Compose frames, animations and
 * the `Dispatchers.Main` pump all freeze until the menu closes. Call from a
 * background thread instead; the bridge marshals onto the AppKit main queue
 * itself, so the menu still opens on the main thread but from a regular queue
 * drain that leaves tao's run-loop observers firing.
 *
 * @return `true` if the user selected an item, `false` if dismissed or
 *   unavailable.
 */
public fun popUpNativeMenu(items: List<NativePopupMenuItem>): Boolean {
    if (!NsMenu.isAvailable || items.isEmpty()) return false
    val menu = NsMenu()
    menu.autoenablesItems = false
    try {
        materializePopupItems(menu, items)
        return menu.popUpAtCursor()
    } finally {
        menu.close()
    }
}

private fun materializePopupItems(
    menu: NsMenu,
    items: List<NativePopupMenuItem>,
) {
    for (item in items) {
        when (item) {
            is NativePopupMenuItem.Separator -> {
                val sep = NsMenuItem.separator()
                menu.addItem(sep)
                sep.close()
            }
            is NativePopupMenuItem.Submenu -> {
                val menuItem = NsMenuItem(item.title)
                val submenu = NsMenu(item.title)
                submenu.autoenablesItems = false
                materializePopupItems(submenu, item.items)
                menuItem.submenu = submenu
                menu.addItem(menuItem)
                menuItem.close()
                submenu.close()
            }
            is NativePopupMenuItem.Entry -> {
                val menuItem = NsMenuItem(item.title)
                if (!item.enabled) menuItem.isEnabled = false
                item.icon?.let { menuItem.image = it }
                menuItem.onAction = item.onClick
                menu.addItem(menuItem)
                menuItem.close()
            }
        }
    }
}
