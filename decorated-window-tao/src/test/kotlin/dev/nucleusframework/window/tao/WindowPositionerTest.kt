package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Placement arithmetic behind `SatelliteWindow`. Pure geometry — no windows, no
 * native calls — so the constraint-adjustment cascade (flip → slide → resize)
 * can be pinned down exactly, while the headful suite covers the real
 * two-window behaviour.
 */
class WindowPositionerTest {
    private val workArea = DpRect(0.dp, 0.dp, 1000.dp, 800.dp)
    private val parent = DpRect(100.dp, 100.dp, 500.dp, 400.dp)
    private val child = DpSize(200.dp, 100.dp)

    @Test
    fun `right to left anchoring hangs the child off the right edge of the parent`() {
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
            ).place(child, parent, parent, workArea)

        // Parent right edge, vertically centred on the parent.
        assertEquals(500.dp, placed.left)
        assertEquals(250.dp - 50.dp, placed.top)
        assertEquals(child.width, placed.width)
        assertEquals(child.height, placed.height)
    }

    @Test
    fun `offset is applied after the anchors meet`() {
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.TopRight,
                childAnchor = WindowAnchor.TopLeft,
                offset = DpOffset(12.dp, (-8).dp),
            ).place(child, parent, parent, workArea)

        assertEquals(512.dp, placed.left)
        assertEquals(92.dp, placed.top)
    }

    @Test
    fun `centre to centre puts the child on the middle of the parent`() {
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Center,
                childAnchor = WindowAnchor.Center,
            ).place(child, parent, parent, workArea)

        assertEquals(300.dp - 100.dp, placed.left)
        assertEquals(250.dp - 50.dp, placed.top)
    }

    @Test
    fun `a sub-rectangle of the parent anchors the child to that rectangle`() {
        val toolbarButton = DpRect(140.dp, 100.dp, 180.dp, 140.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.BottomLeft,
                childAnchor = WindowAnchor.TopLeft,
            ).place(child, toolbarButton, parent, workArea)

        assertEquals(140.dp, placed.left)
        assertEquals(140.dp, placed.top)
    }

    @Test
    fun `the anchor point is clamped to the parent rectangle`() {
        // An anchor rect that sticks far out of its parent must not fling the
        // child across the screen.
        val runaway = DpRect(900.dp, 700.dp, 950.dp, 750.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.TopLeft,
                childAnchor = WindowAnchor.TopLeft,
                constraintAdjustment = WindowConstraintAdjustment.None,
            ).place(child, runaway, parent, workArea)

        assertEquals(parent.right, placed.left)
        assertEquals(parent.bottom, placed.top)
    }

    @Test
    fun `no adjustment leaves the child outside the work area`() {
        val atRightEdge = DpRect(800.dp, 100.dp, 990.dp, 400.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
                constraintAdjustment = WindowConstraintAdjustment.None,
            ).place(child, atRightEdge, atRightEdge, workArea)

        assertEquals(990.dp, placed.left)
        assertTrue(placed.right > workArea.right, "expected the child to overhang: $placed")
    }

    @Test
    fun `flip mirrors the child to the other side when it would overhang`() {
        val atRightEdge = DpRect(800.dp, 100.dp, 990.dp, 400.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
                offset = DpOffset(10.dp, 0.dp),
                constraintAdjustment = WindowConstraintAdjustment.Flip,
            ).place(child, atRightEdge, atRightEdge, workArea)

        // Mirrored: anchored to the parent's *left* edge, and the offset flips
        // with it, so the gap stays on the outside of the parent.
        assertEquals(800.dp - 10.dp - child.width, placed.left)
        assertTrue(placed.left >= workArea.left)
        assertTrue(placed.right <= workArea.right)
    }

    @Test
    fun `slide translates the child back inside the work area`() {
        val atRightEdge = DpRect(800.dp, 100.dp, 990.dp, 400.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
                constraintAdjustment = WindowConstraintAdjustment.Slide,
            ).place(child, atRightEdge, atRightEdge, workArea)

        // Pushed left until the right edge touches the work area, size intact.
        assertEquals(workArea.right - child.width, placed.left)
        assertEquals(child.width, placed.width)
    }

    @Test
    fun `flip is preferred over slide`() {
        val atRightEdge = DpRect(800.dp, 100.dp, 990.dp, 400.dp)
        val flipAndSlide =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
                constraintAdjustment = WindowConstraintAdjustment.FlipAndSlide,
            ).place(child, atRightEdge, atRightEdge, workArea)
        val flipOnly =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
                constraintAdjustment = WindowConstraintAdjustment.Flip,
            ).place(child, atRightEdge, atRightEdge, workArea)

        assertEquals(flipOnly, flipAndSlide)
    }

    @Test
    fun `resize shrinks the child when nothing else fits`() {
        // Wider than the work area: neither flipping nor sliding can help.
        val huge = DpSize(1200.dp, 100.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Center,
                childAnchor = WindowAnchor.Center,
                constraintAdjustment = WindowConstraintAdjustment.All,
            ).place(huge, parent, parent, workArea)

        // Centred on the parent it would span -300..900; the overhanging edge
        // is clipped to the work area and the window is never grown to fill it.
        assertEquals(workArea.left, placed.left)
        assertEquals(900.dp, placed.right)
        assertTrue(placed.width < huge.width, "expected the child to shrink: $placed")
        assertEquals(huge.height, placed.height)
    }

    @Test
    fun `vertical flip mirrors a bottom anchored child upwards`() {
        val atBottom = DpRect(100.dp, 600.dp, 400.dp, 780.dp)
        val placed =
            WindowPositioner(
                parentAnchor = WindowAnchor.Bottom,
                childAnchor = WindowAnchor.Top,
                constraintAdjustment = WindowConstraintAdjustment.Flip,
            ).place(child, atBottom, atBottom, workArea)

        assertEquals(600.dp - child.height, placed.top)
        assertTrue(placed.bottom <= workArea.bottom)
    }

    @Test
    fun `an unconstrained placement is returned untouched by every adjustment`() {
        val positioner =
            WindowPositioner(
                parentAnchor = WindowAnchor.Right,
                childAnchor = WindowAnchor.Left,
                constraintAdjustment = WindowConstraintAdjustment.All,
            )
        val relaxed = positioner.copy(constraintAdjustment = WindowConstraintAdjustment.None)

        assertEquals(
            relaxed.place(child, parent, parent, workArea),
            positioner.place(child, parent, parent, workArea),
        )
    }
}
