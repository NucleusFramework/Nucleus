@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * A point on a rectangle, used to pin one window to another.
 *
 * Corner values ([TopLeft], [BottomRight], …) resolve to that corner; edge
 * values ([Top], [Left], …) resolve to the middle of that edge; [Center]
 * resolves to the middle of the rectangle.
 *
 * Used twice by a [WindowPositioner]: once on the parent's anchor rectangle
 * ([WindowPositioner.parentAnchor]) and once on the child window
 * ([WindowPositioner.childAnchor]).
 */
public enum class WindowAnchor {
    /** The middle of the rectangle. */
    Center,

    /** The middle of the top edge. */
    Top,

    /** The middle of the bottom edge. */
    Bottom,

    /** The middle of the left edge. */
    Left,

    /** The middle of the right edge. */
    Right,

    /** The top-left corner. */
    TopLeft,

    /** The top-right corner. */
    TopRight,

    /** The bottom-left corner. */
    BottomLeft,

    /** The bottom-right corner. */
    BottomRight,
}

/**
 * How a window may be nudged when the position a [WindowPositioner] computes
 * would put it (partly) outside the monitor work area.
 *
 * Adjustments are tried in a fixed precedence and the first one that lands the
 * whole window inside the work area wins:
 *
 *  1. [flipHorizontal] / [flipVertical] — mirror both anchors and the offset to
 *     the opposite side of the anchor rectangle.
 *  2. [slideHorizontal] / [slideVertical] — translate along the axis until the
 *     window fits.
 *  3. [resizeHorizontal] / [resizeVertical] — shrink along the axis until the
 *     window fits.
 *
 * When none of the enabled adjustments fits, the unadjusted position is used.
 */
public data class WindowConstraintAdjustment(
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val slideHorizontal: Boolean = false,
    val slideVertical: Boolean = false,
    val resizeHorizontal: Boolean = false,
    val resizeVertical: Boolean = false,
) {
    /** Ready-made combinations, in increasing order of how far they'll go. */
    public companion object {
        /** No adjustment: the anchored position is used verbatim. */
        public val None: WindowConstraintAdjustment = WindowConstraintAdjustment()

        /** Slide along both axes until the window fits. */
        public val Slide: WindowConstraintAdjustment =
            WindowConstraintAdjustment(slideHorizontal = true, slideVertical = true)

        /** Mirror to the opposite side of the anchor rectangle on both axes. */
        public val Flip: WindowConstraintAdjustment =
            WindowConstraintAdjustment(flipHorizontal = true, flipVertical = true)

        /** Flip first, then slide — the sensible default for tool palettes. */
        public val FlipAndSlide: WindowConstraintAdjustment =
            WindowConstraintAdjustment(
                flipHorizontal = true,
                flipVertical = true,
                slideHorizontal = true,
                slideVertical = true,
            )

        /** Every adjustment, shrinking the window as a last resort. */
        public val All: WindowConstraintAdjustment =
            WindowConstraintAdjustment(
                flipHorizontal = true,
                flipVertical = true,
                slideHorizontal = true,
                slideVertical = true,
                resizeHorizontal = true,
                resizeVertical = true,
            )
    }
}

/**
 * Declarative placement rule for a child window relative to its parent.
 *
 * The child is placed by putting its [childAnchor] on top of the parent's
 * [parentAnchor] and then translating by [offset]. For example
 * `WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left)`
 * hangs the child off the parent's right edge, vertically centred; adding
 * `offset = DpOffset(8.dp, 0.dp)` leaves an 8 dp gap.
 *
 * The anchor point is clamped to the parent's own rectangle before the child
 * anchor is applied, so a child can never be flung far away by an anchor
 * rectangle that sticks out of its parent.
 *
 * Used by [SatelliteWindow] for the satellite's initial placement.
 *
 * @property parentAnchor the point on the parent's anchor rectangle to pin to.
 * @property childAnchor the point on the child window pinned to [parentAnchor].
 * @property offset translation applied after the two anchors meet — typically
 *   the gap between the parent and a palette hanging off its edge. Applied
 *   *after* the anchor point is clamped to [parentRect][place], unlike
 *   Flutter's positioner, which clamps the offset anchor point and therefore
 *   swallows any offset pointing away from the parent.
 * @property constraintAdjustment how to keep the child inside the work area.
 *   Defaults to [WindowConstraintAdjustment.FlipAndSlide] so an anchored window
 *   near a screen edge stays reachable; pass [WindowConstraintAdjustment.None]
 *   for raw anchoring.
 */
public data class WindowPositioner(
    val parentAnchor: WindowAnchor = WindowAnchor.Center,
    val childAnchor: WindowAnchor = WindowAnchor.Center,
    val offset: DpOffset = DpOffset.Zero,
    val constraintAdjustment: WindowConstraintAdjustment = WindowConstraintAdjustment.FlipAndSlide,
) {
    /**
     * Resolves the screen rectangle for a child window of [childSize].
     *
     * All rectangles are in the same coordinate space — screen dp with a
     * top-left origin — and the result is too:
     *
     * @param childSize the child window's outer (frame) size.
     * @param anchorRect the rectangle the child is anchored to. Usually the
     *   parent window's frame, or a sub-rectangle of it (a toolbar button).
     * @param parentRect the parent window's frame; bounds the anchor point.
     * @param workArea the monitor work area the child must stay inside
     *   (screen minus taskbar / menu bar / dock).
     */
    public fun place(
        childSize: DpSize,
        anchorRect: DpRect,
        parentRect: DpRect,
        workArea: DpRect,
    ): DpRect =
        placeIn(
            childSize = childSize.toSize(),
            anchorRect = anchorRect.toRect(),
            parentRect = parentRect.toRect(),
            workArea = workArea.toRect(),
        ).toDpRect()

    /**
     * [place] in raw floats, so callers that already work in physical pixels
     * (the satellite follow path) don't round-trip through [DpRect].
     *
     * [scale] converts [offset] — the only dp-valued input — into the unit the
     * rectangles are expressed in: `1f` for dp, the monitor scale factor for
     * physical pixels.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    internal fun placeIn(
        childSize: Size,
        anchorRect: Rect,
        parentRect: Rect,
        workArea: Rect,
        scale: Float = 1f,
    ): Rect {
        val delta = Offset(offset.x.value * scale, offset.y.value * scale)

        fun candidate(
            parent: WindowAnchor,
            child: WindowAnchor,
            translation: Offset,
        ): Rect {
            // Clamp the anchor *point*, then translate: an anchor rectangle
            // that sticks out of its parent can't fling the child across the
            // screen, while an [offset] meant to open a gap on the outside of
            // the parent survives. See the note on [offset].
            val anchorPoint = parent.pointOn(anchorRect).clampTo(parentRect) + translation
            val origin = anchorPoint + child.originShiftFor(childSize)
            return Rect(origin, childSize)
        }

        val unadjusted = candidate(parentAnchor, childAnchor, delta)
        if (workArea.covers(unadjusted)) return unadjusted

        if (constraintAdjustment.flipHorizontal) {
            val flipped =
                candidate(
                    parentAnchor.flippedHorizontally(),
                    childAnchor.flippedHorizontally(),
                    Offset(-delta.x, delta.y),
                )
            if (workArea.covers(flipped)) return flipped
        }
        if (constraintAdjustment.flipVertical) {
            val flipped =
                candidate(
                    parentAnchor.flippedVertically(),
                    childAnchor.flippedVertically(),
                    Offset(delta.x, -delta.y),
                )
            if (workArea.covers(flipped)) return flipped
        }
        if (constraintAdjustment.flipHorizontal && constraintAdjustment.flipVertical) {
            val flipped =
                candidate(
                    parentAnchor.flippedHorizontally().flippedVertically(),
                    childAnchor.flippedHorizontally().flippedVertically(),
                    Offset(-delta.x, -delta.y),
                )
            if (workArea.covers(flipped)) return flipped
        }

        if (constraintAdjustment.slideHorizontal || constraintAdjustment.slideVertical) {
            var origin = unadjusted.topLeft
            if (constraintAdjustment.slideHorizontal) {
                origin = Offset(slideInto(origin.x, childSize.width, workArea.left, workArea.right), origin.y)
            }
            if (constraintAdjustment.slideVertical) {
                origin = Offset(origin.x, slideInto(origin.y, childSize.height, workArea.top, workArea.bottom))
            }
            val slid = Rect(origin, childSize)
            if (workArea.covers(slid)) return slid
        }

        if (constraintAdjustment.resizeHorizontal || constraintAdjustment.resizeVertical) {
            // Clip the overhanging axis to the work area — the window shrinks
            // to what fits and is never grown past what was asked for.
            val resized =
                Rect(
                    left =
                        if (constraintAdjustment.resizeHorizontal) {
                            maxOf(unadjusted.left, workArea.left)
                        } else {
                            unadjusted.left
                        },
                    top =
                        if (constraintAdjustment.resizeVertical) {
                            maxOf(unadjusted.top, workArea.top)
                        } else {
                            unadjusted.top
                        },
                    right =
                        if (constraintAdjustment.resizeHorizontal) {
                            minOf(unadjusted.right, workArea.right)
                        } else {
                            unadjusted.right
                        },
                    bottom =
                        if (constraintAdjustment.resizeVertical) {
                            minOf(unadjusted.bottom, workArea.bottom)
                        } else {
                            unadjusted.bottom
                        },
                )
            if (workArea.covers(resized)) return resized
        }

        return unadjusted
    }
}

/** Translation that keeps a span of [extent] starting at [start] inside `[min, max]`. */
private fun slideInto(
    start: Float,
    extent: Float,
    min: Float,
    max: Float,
): Float {
    val leadingOverhang = start - min
    val trailingOverhang = start + extent - max
    return when {
        leadingOverhang < 0f -> start - leadingOverhang
        trailingOverhang > 0f -> start - trailingOverhang
        else -> start
    }
}

private fun WindowAnchor.pointOn(rect: Rect): Offset =
    when (this) {
        WindowAnchor.Center -> rect.center
        WindowAnchor.Top -> rect.topCenter
        WindowAnchor.Bottom -> rect.bottomCenter
        WindowAnchor.Left -> rect.centerLeft
        WindowAnchor.Right -> rect.centerRight
        WindowAnchor.TopLeft -> rect.topLeft
        WindowAnchor.TopRight -> rect.topRight
        WindowAnchor.BottomLeft -> rect.bottomLeft
        WindowAnchor.BottomRight -> rect.bottomRight
    }

/** Shift from the anchor point to the child's top-left corner. */
private fun WindowAnchor.originShiftFor(size: Size): Offset =
    when (this) {
        WindowAnchor.Center -> Offset(-size.width / 2f, -size.height / 2f)
        WindowAnchor.Top -> Offset(-size.width / 2f, 0f)
        WindowAnchor.Bottom -> Offset(-size.width / 2f, -size.height)
        WindowAnchor.Left -> Offset(0f, -size.height / 2f)
        WindowAnchor.Right -> Offset(-size.width, -size.height / 2f)
        WindowAnchor.TopLeft -> Offset.Zero
        WindowAnchor.TopRight -> Offset(-size.width, 0f)
        WindowAnchor.BottomLeft -> Offset(0f, -size.height)
        WindowAnchor.BottomRight -> Offset(-size.width, -size.height)
    }

private fun WindowAnchor.flippedHorizontally(): WindowAnchor =
    when (this) {
        WindowAnchor.Left -> WindowAnchor.Right
        WindowAnchor.Right -> WindowAnchor.Left
        WindowAnchor.TopLeft -> WindowAnchor.TopRight
        WindowAnchor.TopRight -> WindowAnchor.TopLeft
        WindowAnchor.BottomLeft -> WindowAnchor.BottomRight
        WindowAnchor.BottomRight -> WindowAnchor.BottomLeft
        WindowAnchor.Center, WindowAnchor.Top, WindowAnchor.Bottom -> this
    }

private fun WindowAnchor.flippedVertically(): WindowAnchor =
    when (this) {
        WindowAnchor.Top -> WindowAnchor.Bottom
        WindowAnchor.Bottom -> WindowAnchor.Top
        WindowAnchor.TopLeft -> WindowAnchor.BottomLeft
        WindowAnchor.BottomLeft -> WindowAnchor.TopLeft
        WindowAnchor.TopRight -> WindowAnchor.BottomRight
        WindowAnchor.BottomRight -> WindowAnchor.TopRight
        WindowAnchor.Center, WindowAnchor.Left, WindowAnchor.Right -> this
    }

private fun Offset.clampTo(rect: Rect): Offset =
    Offset(x.coerceIn(rect.left, rect.right), y.coerceIn(rect.top, rect.bottom))

/** True when [other] lies entirely inside this rectangle. */
private fun Rect.covers(other: Rect): Boolean =
    left <= other.left && right >= other.right && top <= other.top && bottom >= other.bottom

private fun DpSize.toSize(): Size = Size(width.value, height.value)

private fun DpRect.toRect(): Rect = Rect(left.value, top.value, right.value, bottom.value)

private fun Rect.toDpRect(): DpRect = DpRect(left.dp, top.dp, right.dp, bottom.dp)
