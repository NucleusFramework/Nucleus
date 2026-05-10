package dev.nucleusframework.nucleus.launcher.linux

import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon

/**
 * Represents a single menu item in a `com.canonical.dbusmenu` quicklist.
 *
 * Items are identified by their [id]. When a user clicks an item, the
 * [LinuxQuicklist.Listener.onItemClicked] callback is invoked with that ID.
 *
 * @property id Unique numeric identifier for this item. Must be > 0 (0 is reserved for the root).
 * @property label Display text. Supports mnemonics with `_` prefix (e.g. `"_Open"`).
 * @property icon Freedesktop icon (e.g. `FreedesktopIcon.Action.DOCUMENT_OPEN`), or `null` for no icon.
 * @property enabled Whether the item is clickable.
 * @property visible Whether the item is shown in the menu.
 * @property type Item type: [ItemType.STANDARD] or [ItemType.SEPARATOR].
 * @property toggleType Toggle behavior: [ToggleType.NONE], [ToggleType.CHECKBOX], or [ToggleType.RADIO].
 * @property toggleState Toggle state: -1 = indeterminate, 0 = unchecked, 1 = checked.
 * @property shortcut Keyboard shortcut descriptors (e.g. `listOf("Control", "S")`).
 * @property disposition Visual disposition: [Disposition.NORMAL], [Disposition.INFORMATIONAL],
 *   [Disposition.WARNING], or [Disposition.ALERT].
 * @property children Sub-menu items. Non-empty means this item has a submenu.
 */
data class DbusmenuItem(
    val id: Int,
    val label: String = "",
    val icon: FreedesktopIcon? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val type: ItemType = ItemType.STANDARD,
    val toggleType: ToggleType = ToggleType.NONE,
    val toggleState: Int = -1,
    val shortcut: List<String> = emptyList(),
    val disposition: Disposition = Disposition.NORMAL,
    val children: List<DbusmenuItem> = emptyList(),
) {
    enum class ItemType(
        val value: String,
    ) {
        STANDARD("standard"),
        SEPARATOR("separator"),
    }

    enum class ToggleType(
        val value: String,
    ) {
        NONE(""),
        CHECKBOX("checkmark"),
        RADIO("radio"),
    }

    enum class Disposition(
        val value: String,
    ) {
        NORMAL("normal"),
        INFORMATIONAL("informational"),
        WARNING("warning"),
        ALERT("alert"),
    }

    companion object {
        /** Creates a separator item. */
        fun separator(id: Int): DbusmenuItem = DbusmenuItem(id = id, type = ItemType.SEPARATOR)
    }
}
