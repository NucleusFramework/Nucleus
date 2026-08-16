package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem

/**
 * A [ContextMenuItem] that can carry a [ContextMenuIcon] and a keyboard
 * shortcut.
 *
 * Drop it into `ContextMenuArea` / `ContextMenuDataProvider` lists the same
 * way as a plain [ContextMenuItem]. Compose-drawn menus show the [label]
 * only; [NativeContextMenuRepresentation] also renders [icon] and [shortcut]
 * (shortcut on the Linux / Windows flyouts).
 *
 * @param label Text shown in the menu.
 * @param enabled Whether the item can be chosen.
 * @param icon Optional stock or SF Symbol icon for the native menu.
 * @param shortcut Accelerator shown on the right of the Linux / Windows
 *   flyout (e.g. `Ctrl+C`). `null` falls back to [ContextMenuIcon.stockShortcut]
 *   for Cut / Copy / Paste / Select All. Pass `""` to hide the fallback.
 * @param onClick Invoked when the user chooses the item.
 */
public class NucleusContextMenuItem(
    label: String,
    enabled: Boolean = true,
    public val icon: ContextMenuIcon? = null,
    public val shortcut: String? = null,
    onClick: () -> Unit,
) : ContextMenuItem(label, enabled, onClick)

internal fun NucleusContextMenuItem.resolvedShortcut(): String? =
    when (shortcut) {
        null -> icon?.stockShortcut()
        else -> shortcut.takeIf { it.isNotEmpty() }
    }

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
