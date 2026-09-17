package dev.nucleusframework.window.tao.scene

/**
 * Sub-pixel deadband for the Tao mouse stream (#615).
 *
 * Tao delivers cursor positions with sub-pixel precision (1/1024 px wire
 * format) and macOS emits a CursorMoved before every mouseDown/mouseUp, so a
 * click whose cursor drifts a fraction of a pixel between press and release
 * produces a real Move delta. Compose's mouse slop is only
 * `touchSlop × (0.125.dp / 18.dp)` (foundation `DragGestureDetector.kt`), so
 * ~0.2 px of drift starts any parent drag gesture, which consumes the move;
 * a child `clickable` then sees the consumed change in the Final pass and
 * cancels the tap — observed as "buttons need two clicks". The AWT backend
 * never sees this because it quantizes positions to integer logical points —
 * a de-facto 1 dp deadband.
 *
 * This class gives the Tao stream the same deadband without giving up
 * sub-pixel precision on real motion: Moves whose delta from the last
 * *dispatched* position is under 1 dp are suppressed, and the first Move past
 * the deadband dispatches its exact sub-pixel position.
 *
 * Press/Release/Exit/Scroll must be dispatched at [x]/[y] (the last
 * dispatched position), not the raw one: Compose's `SyntheticEventSender`
 * re-injects any position difference as a synthetic Move, which would
 * reintroduce the suppressed delta.
 */
internal class TaoPointerDeadband {
    /** X of the last Move actually dispatched to the scene (physical px). */
    var x: Float = 0f
        private set

    /** Y of the last Move actually dispatched to the scene (physical px). */
    var y: Float = 0f
        private set

    private var hasDispatched = false

    /**
     * Whether a Move to ([rawX], [rawY]) physical px should reach the scene;
     * records it as the new dispatched position when it should. [scale] is
     * the scene's px-per-dp factor — the deadband is 1 dp, matching the
     * de-facto precision of the AWT backend's integer-point stream.
     */
    fun shouldDispatchMove(
        rawX: Float,
        rawY: Float,
        scale: Float,
    ): Boolean {
        if (hasDispatched) {
            val threshold = if (scale > 0f) scale else 1f
            val dx = rawX - x
            val dy = rawY - y
            if (dx * dx + dy * dy < threshold * threshold) return false
        }
        x = rawX
        y = rawY
        hasDispatched = true
        return true
    }
}
