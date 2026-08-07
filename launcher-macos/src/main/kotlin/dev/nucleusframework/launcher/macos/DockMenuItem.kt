package dev.nucleusframework.launcher.macos

/**
 * Represents a single item in the macOS dock context menu.
 *
 * Items are identified by their [id]. When a user clicks an item, the
 * [MacOsDockMenu.listener] callback is invoked with that ID.
 *
 * Note: macOS Dock menus do not support images on menu items — the Dock
 * process strips them. Only text, separators, submenus, and enabled state
 * are rendered.
 *
 * @property id Unique numeric identifier for this item. Must be > 0.
 * @property title Display text for the menu item.
 * @property enabled Whether the item is clickable.
 * @property children Sub-menu items. Non-empty means this item has a submenu.
 */
public data class DockMenuItem(
    val id: Int,
    val title: String = "",
    val enabled: Boolean = true,
    val children: List<DockMenuItem> = emptyList(),
) {
    public companion object {
        /** Creates a separator item. */
        public fun separator(id: Int): DockMenuItem = DockMenuItem(id = id, title = "-")
    }
}
