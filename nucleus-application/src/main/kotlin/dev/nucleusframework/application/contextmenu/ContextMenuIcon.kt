package dev.nucleusframework.application.contextmenu

/**
 * Icon shown next to a [NucleusContextMenuItem] when the native context menu
 * is active.
 *
 * Stock values (`Cut`, `Copy`, `Paste`, …) resolve to the OS glyph (SF Symbol
 * on macOS, Segoe Fluent Icons on the Windows Compose flyout). [SfSymbol] is
 * a raw macOS symbol name for custom items and is ignored on Windows / Linux.
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
