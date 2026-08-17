package dev.nucleusframework.application.contextmenu

import dev.nucleusframework.core.runtime.Platform

/**
 * Icon shown next to a [NucleusContextMenuItem] when the native context menu
 * is active.
 *
 * Stock values (`Cut`, `Copy`, `Paste`, …) resolve to the OS glyph (SF Symbol
 * on macOS, 16 dp Breeze vectors on the Qt/KDE Compose flyout, Segoe Fluent
 * Icons on the Windows Compose flyout). Adwaita (GTK) flyouts hide icons.
 * [SfSymbol] is a raw macOS symbol name and is ignored on Windows / Linux.
 */
public sealed class ContextMenuIcon {
    /** System cut / scissors glyph. */
    public data object Cut : ContextMenuIcon()

    /** System copy glyph. */
    public data object Copy : ContextMenuIcon()

    /** System paste / clipboard glyph. */
    public data object Paste : ContextMenuIcon()

    /** System select-all glyph. Often omitted on macOS. */
    public data object SelectAll : ContextMenuIcon()

    /** System delete / trash glyph. */
    public data object Delete : ContextMenuIcon()

    /** System folder glyph. */
    public data object Folder : ContextMenuIcon()

    /**
     * A raw SF Symbol name (macOS 11+), e.g. `"square.and.arrow.up"`.
     *
     * Ignored on Windows and Linux.
     */
    public data class SfSymbol(
        public val name: String,
    ) : ContextMenuIcon()
}

/**
 * Conventional accelerator for this stock icon, formatted like Jewel / GTK:
 * `Ctrl+C` on Linux and Windows, `⌘C` on macOS.
 *
 * [ContextMenuIcon.Delete], [ContextMenuIcon.Folder], and
 * [ContextMenuIcon.SfSymbol] have no standard shortcut and return `null`.
 */
public fun ContextMenuIcon.stockShortcut(): String? =
    when (this) {
        ContextMenuIcon.Cut -> primaryModifierShortcut("X")
        ContextMenuIcon.Copy -> primaryModifierShortcut("C")
        ContextMenuIcon.Paste -> primaryModifierShortcut("V")
        ContextMenuIcon.SelectAll -> primaryModifierShortcut("A")
        ContextMenuIcon.Delete,
        ContextMenuIcon.Folder,
        is ContextMenuIcon.SfSymbol,
        -> null
    }

internal fun primaryModifierShortcut(key: String): String =
    when (Platform.Current) {
        Platform.MacOS -> "⌘$key"
        else -> "Ctrl+$key"
    }
