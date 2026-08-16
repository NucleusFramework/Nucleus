package dev.nucleusframework.window.jewel

import androidx.compose.foundation.ContextMenuItem
import dev.nucleusframework.application.contextmenu.ContextMenuEntry
import dev.nucleusframework.application.contextmenu.ContextMenuIcon
import dev.nucleusframework.application.contextmenu.ContextMenuItemInterpreter
import dev.nucleusframework.application.contextmenu.DefaultContextMenuItemInterpreter
import org.jetbrains.jewel.ui.component.ContextMenuDivider
import org.jetbrains.jewel.ui.component.ContextMenuItemOption
import org.jetbrains.jewel.ui.component.ContextMenuItemOptionAction
import org.jetbrains.jewel.ui.component.ContextSubmenu

/**
 * Jewel-aware [ContextMenuItemInterpreter]: maps `ContextMenuDivider` /
 * `ContextSubmenu` / `ContextMenuItemOption.actionType` onto [ContextMenuEntry]
 * so the native menu can show OS icons for Cut / Copy / Paste instead of
 * Jewel's `AllIconsKeys`.
 *
 * Custom `IconKey`s on items without an [ContextMenuItemOptionAction] are
 * ignored in this version (no SVG → `NSImage` rasteriser yet). Use
 * [dev.nucleusframework.application.contextmenu.NucleusContextMenuItem] with
 * a [ContextMenuIcon] for those.
 */
public object JewelContextMenuInterpreter : ContextMenuItemInterpreter {
    override fun interpret(
        item: ContextMenuItem,
        separator: ContextMenuItem,
    ): ContextMenuEntry =
        when (item) {
            ContextMenuDivider -> ContextMenuEntry.Separator
            is ContextSubmenu ->
                ContextMenuEntry.Submenu(
                    label = item.label,
                    items = item.submenu().map { child -> interpret(child, separator) },
                )
            is ContextMenuItemOption ->
                ContextMenuEntry.Item(
                    label = item.label,
                    enabled = item.enabled,
                    icon = item.actionType.toStockIcon(),
                    onClick = item.onClick,
                )
            else -> DefaultContextMenuItemInterpreter.interpret(item, separator)
        }
}

private fun ContextMenuItemOptionAction?.toStockIcon(): ContextMenuIcon? =
    when (this) {
        ContextMenuItemOptionAction.CutMenuItemOptionAction -> ContextMenuIcon.Cut
        ContextMenuItemOptionAction.CopyMenuItemOptionAction -> ContextMenuIcon.Copy
        ContextMenuItemOptionAction.PasteMenuItemOptionAction -> ContextMenuIcon.Paste
        ContextMenuItemOptionAction.SelectAllMenuItemOptionAction -> ContextMenuIcon.SelectAll
        null -> null
    }
