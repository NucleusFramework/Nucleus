package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

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
     * @param shortcut Accelerator shown on the right of the Linux / Windows
     *   flyout (e.g. `Ctrl+C`). `null` or empty hides it. macOS `NSMenu` does
     *   not use this field yet.
     */
    public class Item(
        public val label: String,
        public val enabled: Boolean,
        public val icon: ContextMenuIcon?,
        public val onClick: () -> Unit,
        public val shortcut: String? = null,
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
 * and [NucleusContextMenuSubmenu]. Jewel installs a richer interpreter (action
 * types → stock icons, `ContextMenuDivider`, submenus) from
 * `decorated-window-jewel`.
 */
public fun interface ContextMenuItemInterpreter {
    /** Interprets [item]. [separator] is the ambient [LocalContextMenuDivider]. */
    public fun interpret(
        item: ContextMenuItem,
        separator: ContextMenuItem,
    ): ContextMenuEntry
}

/**
 * Drops separators a renderer would draw as a bare rule: leading ones, trailing
 * ones, and runs of consecutive ones. Applied recursively to submenus.
 *
 * Menu items come from several independent contributors (the field's own
 * Cut / Copy / Paste, app extras, spellcheck), so a block that legitimately
 * brings its own divider can still end up next to someone else's.
 */
internal fun List<ContextMenuEntry>.withNormalizedSeparators(): List<ContextMenuEntry> {
    val normalized = mutableListOf<ContextMenuEntry>()
    forEach { entry ->
        when (entry) {
            is ContextMenuEntry.Separator -> {
                if (normalized.lastOrNull() is ContextMenuEntry.Item ||
                    normalized.lastOrNull() is ContextMenuEntry.Submenu
                ) {
                    normalized += entry
                }
            }
            is ContextMenuEntry.Submenu ->
                normalized += ContextMenuEntry.Submenu(entry.label, entry.items.withNormalizedSeparators())
            is ContextMenuEntry.Item -> normalized += entry
        }
    }
    while (normalized.lastOrNull() is ContextMenuEntry.Separator) {
        normalized.removeAt(normalized.lastIndex)
    }
    return normalized
}

/** Ambient [ContextMenuItemInterpreter]. Defaults to [DefaultContextMenuItemInterpreter]. */
public val LocalContextMenuItemInterpreter: ProvidableCompositionLocal<ContextMenuItemInterpreter> =
    staticCompositionLocalOf { DefaultContextMenuItemInterpreter }

/**
 * Default interpreter: Nucleus item types, the ambient and Nucleus dividers,
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
                    shortcut = item.resolvedShortcut(),
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
