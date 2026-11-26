package dev.nucleusframework.window.tao

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * A [PointerIcon] backed by a native Tao cursor, recognised by the Tao scene
 * hosts and passed straight to `Window::set_cursor_icon`.
 */
internal class TaoPointerIcon(
    val code: Int,
) : PointerIcon

/**
 * Pointer icons beyond the four Compose defines in common code
 * (`Default`, `Text`, `Hand`, `Crosshair`).
 *
 * Use them with `Modifier.pointerHoverIcon` like any other icon:
 *
 * ```kotlin
 * Modifier.pointerHoverIcon(TaoPointerIcons.Grab)
 * ```
 *
 * They resolve to the platform's own shapes (AppKit `openHandCursor` /
 * `closedHandCursor`, the freedesktop `grab` / `grabbing` themed cursors, the
 * Win32 equivalents), and fall back to the arrow where a platform has none.
 * Compose Desktop's AWT-based `PointerIcon(Cursor(…))` is not usable on this
 * backend — the process runs without AWT.
 */
public object TaoPointerIcons {
    /** Open hand: this element can be picked up. The hover state of a drag handle. */
    public val Grab: PointerIcon = TaoPointerIcon(TaoCursorIcon.GRAB)

    /** Closed hand: the element is being dragged. */
    public val Grabbing: PointerIcon = TaoPointerIcon(TaoCursorIcon.GRABBING)

    /** Four arrows: the element will be moved. */
    public val Move: PointerIcon = TaoPointerIcon(TaoCursorIcon.MOVE)

    /** The drop here is refused. */
    public val NotAllowed: PointerIcon = TaoPointerIcon(TaoCursorIcon.NOT_ALLOWED)

    /** Wait cursor: the app is busy and does not take input. */
    public val Wait: PointerIcon = TaoPointerIcon(TaoCursorIcon.WAIT)

    /** Progress cursor: busy, but still interactive. */
    public val Progress: PointerIcon = TaoPointerIcon(TaoCursorIcon.PROGRESS)

    /** Help cursor, usually a question mark. */
    public val Help: PointerIcon = TaoPointerIcon(TaoCursorIcon.HELP)

    /** Horizontal resize: a vertical splitter or a left/right window edge. */
    public val ResizeEastWest: PointerIcon = TaoPointerIcon(TaoCursorIcon.EW_RESIZE)

    /** Vertical resize: a horizontal splitter or a top/bottom window edge. */
    public val ResizeNorthSouth: PointerIcon = TaoPointerIcon(TaoCursorIcon.NS_RESIZE)
}
