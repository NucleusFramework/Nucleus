@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.deco

import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge

/**
 * Compose-side peer-level resize hit-test, structurally identical to JBR's
 * `sun.awt.wl.FrameDecoration`. Plugged into [TaoComposeSceneHostLinux] at the
 * input-dispatch boundary (`onPointerMove` / `onPointerButton`) so that resize
 * events are claimed BEFORE `scene.sendPointerEvent` runs — meaning Compose
 * never sees a click that was meant for resize, even when the click lands on
 * top of a scrollbar pinned to the window edge.
 *
 * Coordinates are logical pixels in the window's local frame (origin at
 * top-left). Band thickness matches the JBR default
 * (`FrameDecoration.DEFAULT_RESIZE_EDGE_THICKNESS = 5`), with corner detection
 * emerging naturally from the intersection of two edges (no separate, wider
 * corner zone — same precedence as JBR's `getResizeEdges` bitmask).
 */
internal class ResizeFrameDecoration(
    private val windowHandle: Long,
    /**
     * Edge band thickness in logical pixels. Matches JBR's
     * `FrameDecoration.DEFAULT_RESIZE_EDGE_THICKNESS = 5`. There is no
     * separate, wider "corner" band — diagonal resize is detected as the
     * intersection of two edges (top-left = top edge AND left edge), which
     * gives a natural 5×5 corner hotspot. Widening it makes the band feel
     * heavy on small windows; AWT's 5 px is the empirically-tuned sweet spot.
     */
    private val edgeThicknessLogical: Int = 5,
    /**
     * Edge band thickness for touch, in logical pixels. A 5 px mouse band is
     * unusable with a fingertip, so touch gets a much wider grab zone — closer
     * to the feel of native GTK client-side-decorated windows, whose resize
     * region also extends well beyond the visible border. Kept in logical px so
     * the physical zone scales consistently across HiDPI.
     */
    private val touchEdgeThicknessLogical: Int = 16,
) {
    /** Ordinals MUST match `NativeTaoBridge.nativeBeginResizeDrag` direction encoding. */
    enum class Direction(
        val code: Int,
        val cursorIcon: Int,
    ) {
        North(0, TaoCursorIcon.NS_RESIZE),
        South(1, TaoCursorIcon.NS_RESIZE),
        East(2, TaoCursorIcon.EW_RESIZE),
        West(3, TaoCursorIcon.EW_RESIZE),
        NorthWest(4, TaoCursorIcon.NWSE_RESIZE),
        NorthEast(5, TaoCursorIcon.NESW_RESIZE),
        SouthWest(6, TaoCursorIcon.NESW_RESIZE),
        SouthEast(7, TaoCursorIcon.NWSE_RESIZE),
    }

    private var inBand: Boolean = false

    /**
     * Returns the resize direction if [x], [y] (logical px, frame-local) sits
     * in the edge band of a frame sized [widthLogical] × [heightLogical].
     * Returns `null` if outside the band — caller forwards the event normally.
     *
     * [outerBandLogical] extends the hit zone *outside* the frame — used when
     * the window carries an invisible CSD shadow margin, whose area doubles as
     * the resize grip exactly like native GTK client-side decorations (the
     * window input shape clips it to GTK's 12 px resize ring).
     *
     * Corner zones win over edge zones (a click at (4, 4) on a 200×200 window
     * is `NorthWest`, not `North`). This matches the JBR + Tao precedence.
     */
    @Suppress("LongParameterList")
    fun hitTest(
        x: Float,
        y: Float,
        widthLogical: Int,
        heightLogical: Int,
        forTouch: Boolean = false,
        outerBandLogical: Int = 0,
    ): Direction? {
        if (!isInsideFrame(x, y, widthLogical, heightLogical, outerBandLogical)) return null
        val edge = if (forTouch) touchEdgeThicknessLogical else edgeThicknessLogical
        val nearLeft = x < edge
        val nearRight = x >= widthLogical - edge
        val nearTop = y < edge
        val nearBottom = y >= heightLogical - edge

        // Corner = intersection of two edges, exactly like JBR's
        // `getResizeEdges` bitmask precedence.
        return when {
            nearLeft && nearTop -> Direction.NorthWest
            nearRight && nearTop -> Direction.NorthEast
            nearLeft && nearBottom -> Direction.SouthWest
            nearRight && nearBottom -> Direction.SouthEast
            nearLeft -> Direction.West
            nearRight -> Direction.East
            nearTop -> Direction.North
            nearBottom -> Direction.South
            else -> null
        }
    }

    /**
     * A pointer OUTSIDE the window (delivered during a button-held drag by the
     * platform grab) is never on a resize handle — without this guard
     * `x < edge` / `x >= width - edge` match every out-of-bounds position and
     * the caller swallows the whole drag stream at the window border.
     */
    private fun isInsideFrame(
        x: Float,
        y: Float,
        widthLogical: Int,
        heightLogical: Int,
        outerBandLogical: Int = 0,
    ): Boolean =
        widthLogical > 0 &&
            heightLogical > 0 &&
            x >= -outerBandLogical &&
            y >= -outerBandLogical &&
            x < widthLogical + outerBandLogical &&
            y < heightLogical + outerBandLogical

    /**
     * Pointer-move hook. If [direction] is non-null the cursor is updated to
     * the matching resize icon and the caller MUST NOT forward the move to
     * Compose (the band owns the pointer). When the pointer transitions out
     * of the band the cursor override is cleared so Compose's
     * `PointerIcon`-driven cursor takes over again on the next move.
     *
     * Returns `true` if the event was consumed and must NOT be forwarded.
     */
    fun onMove(direction: Direction?): Boolean {
        if (direction != null) {
            NativeTaoBridge.setCursorIcon(windowHandle, direction.cursorIcon)
            inBand = true
            return true
        }
        if (inBand) {
            inBand = false
            // Restore the default cursor immediately; Compose will overwrite
            // it on the next motion if a `PointerIcon` modifier is in scope.
            NativeTaoBridge.setCursorIcon(windowHandle, TaoCursorIcon.DEFAULT)
        }
        return false
    }

    /**
     * Left-mouse-button press hook. If [direction] is non-null we start the
     * Tao resize drag and the caller MUST NOT forward the press to Compose.
     *
     * Returns `true` if the event was consumed.
     */
    fun onLeftPress(direction: Direction?): Boolean {
        if (direction == null) return false
        NativeTaoBridge.nativeBeginResizeDrag(windowHandle, direction.code)
        return true
    }
}
