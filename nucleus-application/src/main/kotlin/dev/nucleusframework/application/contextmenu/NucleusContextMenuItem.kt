package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

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
 * Prefer this over an empty [ContextMenuItem] so every renderer that owns its
 * chrome can recognise it.
 */
public object NucleusContextMenuDivider : ContextMenuItem(
    label = "---",
    enabled = false,
    onClick = {},
)

/**
 * Divider understood by the context-menu renderer installed in this subtree,
 * or `null` when the ambient renderer has no notion of a separator.
 *
 * A renderer that owns the chrome publishes its own divider here:
 * [NativeContextMenuProvider] provides [NucleusContextMenuDivider] (drawn by
 * `NSMenu` on macOS and by the Fluent / Adwaita / Breeze flyouts elsewhere),
 * and `ProvideJewelSpellcheckMenu` provides Jewel's `ContextMenuDivider`.
 *
 * The default is `null`: Compose's own representation — and any custom one an
 * app installs — cannot draw a divider, so features that build menu items
 * (spellcheck) must omit separators instead of inventing chrome. Read it
 * rather than assuming a sentinel is drawable.
 */
public val LocalContextMenuDivider: ProvidableCompositionLocal<ContextMenuItem?> =
    staticCompositionLocalOf { null }

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
