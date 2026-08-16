package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem

/**
 * A [ContextMenuItem] that can carry a [ContextMenuIcon].
 *
 * Drop it into `ContextMenuArea` / `ContextMenuDataProvider` lists the same
 * way as a plain [ContextMenuItem]. Compose-drawn menus show the [label]
 * only; [NativeContextMenuRepresentation] also renders [icon].
 *
 * @param label Text shown in the menu.
 * @param enabled Whether the item can be chosen.
 * @param icon Optional stock or SF Symbol icon for the native menu.
 * @param onClick Invoked when the user chooses the item.
 */
public class NucleusContextMenuItem(
    label: String,
    enabled: Boolean = true,
    public val icon: ContextMenuIcon? = null,
    onClick: () -> Unit,
) : ContextMenuItem(label, enabled, onClick)

/**
 * Hairline separator for [NativeContextMenuRepresentation] and for apps that
 * build the item list themselves.
 *
 * Prefer this over an empty [ContextMenuItem] so every renderer (native,
 * Jewel `ContextMenuDivider`, spellcheck) can recognise it.
 */
public object NucleusContextMenuDivider : ContextMenuItem(
    label = "---",
    enabled = false,
    onClick = {},
)

/**
 * A nested submenu. [items] is evaluated when the native menu is materialised.
 *
 * Compose-drawn representations that do not understand this type show [label]
 * as a disabled row.
 */
public class NucleusContextMenuSubmenu(
    label: String,
    public val items: () -> List<ContextMenuItem>,
) : ContextMenuItem(label, enabled = false, onClick = {})
