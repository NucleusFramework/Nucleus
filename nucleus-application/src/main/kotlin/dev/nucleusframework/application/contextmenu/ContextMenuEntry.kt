package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.application.spellcheck.SpellcheckContextMenuSeparator

/**
 * Platform-neutral description of one context-menu row, after an
 * [ContextMenuItemInterpreter] has looked at the Compose [ContextMenuItem].
 */
public sealed class ContextMenuEntry {
    /**
     * A clickable row.
     *
     * @param label Text shown in the menu.
     * @param enabled Whether the row can be chosen.
     * @param icon Optional native icon.
     * @param onClick Invoked when the user chooses the row.
     */
    public class Item(
        public val label: String,
        public val enabled: Boolean,
        public val icon: ContextMenuIcon?,
        public val onClick: () -> Unit,
    ) : ContextMenuEntry()

    /** A horizontal separator. */
    public object Separator : ContextMenuEntry()

    /**
     * A nested submenu.
     *
     * @param label Title of the submenu item.
     * @param items Children, already interpreted.
     */
    public class Submenu(
        public val label: String,
        public val items: List<ContextMenuEntry>,
    ) : ContextMenuEntry()
}

/**
 * Turns a Compose [ContextMenuItem] into a [ContextMenuEntry].
 *
 * The default understands [NucleusContextMenuItem], [NucleusContextMenuDivider],
 * [NucleusContextMenuSubmenu], and the spellcheck separator sentinels. Jewel
 * installs a richer interpreter (action types → stock icons, `ContextMenuDivider`,
 * submenus) from `decorated-window-jewel`.
 */
public fun interface ContextMenuItemInterpreter {
    /** Interprets [item]. [separator] is the ambient spellcheck separator sentinel. */
    public fun interpret(
        item: ContextMenuItem,
        separator: ContextMenuItem,
    ): ContextMenuEntry
}

/** Ambient [ContextMenuItemInterpreter]. Defaults to [DefaultContextMenuItemInterpreter]. */
public val LocalContextMenuItemInterpreter: ProvidableCompositionLocal<ContextMenuItemInterpreter> =
    staticCompositionLocalOf { DefaultContextMenuItemInterpreter }

/**
 * Default interpreter: Nucleus item types, the spellcheck / Nucleus dividers,
 * everything else as a label-only row.
 */
public object DefaultContextMenuItemInterpreter : ContextMenuItemInterpreter {
    override fun interpret(
        item: ContextMenuItem,
        separator: ContextMenuItem,
    ): ContextMenuEntry =
        when {
            item === separator -> ContextMenuEntry.Separator
            item === NucleusContextMenuDivider -> ContextMenuEntry.Separator
            item === SpellcheckContextMenuSeparator -> ContextMenuEntry.Separator
            item is NucleusContextMenuSubmenu ->
                ContextMenuEntry.Submenu(
                    label = item.label,
                    items = item.items().map { child -> interpret(child, separator) },
                )
            item is NucleusContextMenuItem ->
                ContextMenuEntry.Item(
                    label = item.label,
                    enabled = item.enabled,
                    icon = item.icon,
                    onClick = item.onClick,
                )
            else ->
                ContextMenuEntry.Item(
                    label = item.label,
                    enabled = item.enabled,
                    icon = null,
                    onClick = item.onClick,
                )
        }
}
