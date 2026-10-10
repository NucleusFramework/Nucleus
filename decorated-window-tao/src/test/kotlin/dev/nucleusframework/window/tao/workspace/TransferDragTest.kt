package dev.nucleusframework.window.tao.workspace

import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.TaoTransferableAccess
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.dnd.TaoPrivateTransfer
import dev.nucleusframework.window.tao.dockSideAt
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The coordinate-space and payload rules the DnD-carried cross-window drag
 * rests on. Both are pure functions of what an inbound drag event carries, so
 * they are checked here rather than against a compositor.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class TransferDragTest {
    private val layout = Rect(0f, 40f, 720f, 760f)
    private val zone = 64f

    @Test
    fun `nearest edge within the zone wins`() {
        assertEquals(DockSide.Left, dockSideAt(layout, Offset(layout.left + 1f, layout.center.y), zone))
        assertEquals(DockSide.Right, dockSideAt(layout, Offset(layout.right - 1f, layout.center.y), zone))
        assertEquals(DockSide.Top, dockSideAt(layout, Offset(layout.center.x, layout.top + 1f), zone))
        assertEquals(DockSide.Bottom, dockSideAt(layout, Offset(layout.center.x, layout.bottom - 1f), zone))
    }

    @Test
    fun `a corner resolves to the closer of its two edges`() {
        // 10 px from the top, 30 from the left: the top edge is nearer.
        assertEquals(DockSide.Top, dockSideAt(layout, Offset(layout.left + 30f, layout.top + 10f), zone))
        assertEquals(DockSide.Left, dockSideAt(layout, Offset(layout.left + 10f, layout.top + 30f), zone))
    }

    @Test
    fun `content and points outside the layout are no zone`() {
        assertNull(dockSideAt(layout, layout.center, zone))
        assertNull(dockSideAt(layout, Offset(layout.right + 1f, layout.center.y), zone))
        // Inside the *window* but above the layout — a title bar, say.
        assertNull(dockSideAt(layout, Offset(layout.center.x, layout.top - 1f), zone))
    }

    @Test
    fun `a zone wider than the layout still resolves to exactly one side`() {
        // A dock zone deeper than the layout it is measured in: every point is
        // within range of all four edges, and the nearest must still win
        // outright rather than the sides overlapping.
        val wide = Rect(0f, 0f, 400f, 200f)
        assertEquals(DockSide.Top, dockSideAt(wide, Offset(200f, 40f), zonePx = 1000f))
        assertEquals(DockSide.Left, dockSideAt(wide, Offset(30f, 100f), zonePx = 1000f))
        assertEquals(DockSide.Bottom, dockSideAt(wide, Offset(200f, 160f), zonePx = 1000f))
    }

    @Test
    fun `the private payload round-trips under its own flavor only`() {
        val transferable = TaoPrivateTransfer.transferable("workspace-drag")
        assertEquals("workspace-drag", TaoPrivateTransfer.tokenOf(transferable))
        assertEquals(listOf(TaoPrivateTransfer.FLAVOR), transferable.transferDataFlavors.toList())
        assertFalse(
            transferable.isDataFlavorSupported(DataFlavor.stringFlavor),
            "a private payload must not masquerade as text a foreign target could take",
        )
    }

    @Test
    fun `an ordinary transferable carries no token`() {
        assertNull(TaoPrivateTransfer.tokenOf(StringSelection("hello")))
    }

    /**
     * The transfer's completion callback is the only signal that the platform
     * session is over, and therefore the only thing that ends the workspace's
     * drag: without it the drop record is never acted on and the drop-zone
     * highlights never clear, with nothing logged. Asserted directly, because
     * a gesture stranded this way looks exactly like one that never started.
     */
    @Test
    fun `the transfer ends the drag when the platform reports the session over`() {
        val drag = RecordingDrag()
        val data = transferDragData(drag, drag.ghostSizePx, hotspotPx = Offset(4f, 6f))
        assertEquals(0, drag.ended, "the drag must not end before the platform says so")
        requireNotNull(data.onTransferCompleted) { "a transfer with no completion callback strands the gesture" }
            .invoke(DragAndDropTransferAction.Move)
        assertEquals(1, drag.ended)
        assertEquals(0, drag.cancelled)
    }

    @Test
    fun `the transfer carries the private token and a Move action only`() {
        val drag = RecordingDrag()
        val data = transferDragData(drag, drag.ghostSizePx, Offset.Zero)
        assertEquals(listOf(DragAndDropTransferAction.Move), data.supportedActions.toList())
        val awt = requireNotNull(TaoTransferableAccess.toAwt(data.transferable))
        assertEquals(TRANSFER_DRAG_TOKEN, TaoPrivateTransfer.tokenOf(awt))
    }

    @Test
    fun `the decoration offset puts the hotspot under the pointer, clamped to the icon`() {
        val drag = RecordingDrag()
        val size = drag.ghostSizePx
        // Inside the icon: the offset is the hotspot, negated.
        assertEquals(Offset(-4f, -6f), transferDragData(drag, size, Offset(4f, 6f)).dragDecorationOffset)
        // Past the icon — clamps to its edge rather than pushing it off the pointer.
        assertEquals(
            Offset(-size.width, -size.height),
            transferDragData(drag, size, Offset(9_999f, 9_999f)).dragDecorationOffset,
        )
        // Behind the origin clamps to it. Compared by distance: negating a
        // clamped zero yields -0.0f, which `Offset.Zero` does not equal even
        // though it is the same point.
        assertEquals(0f, transferDragData(drag, size, Offset(-50f, -50f)).dragDecorationOffset.getDistance())
    }

    @Test
    fun `without a picture the icon is the title card, one to one`() {
        val drag = RecordingDrag()
        val ghost = transferGhost(drag, picture = null)
        assertEquals(drag.ghostSizePx, ghost.sizePx)
        assertEquals(1f, ghost.scale)
        // A grab in the strip maps straight into the card.
        assertEquals(Offset(30f, 10f), ghost.hotspotPx(Offset(30f, 10f)))
    }

    @Test
    fun `a picture is shown reduced and capped on its longer edge`() {
        val palette = RecordingDrag(source = TransferGhostSource.WholeWindow)
        val small = ImageBitmap(300, 400)
        val reduced = transferGhost(palette, small)
        // Float products: compared within a hundredth of a pixel.
        assertEquals(180f, reduced.sizePx.width, PX_TOLERANCE, "a small palette is shown at the reduction scale")
        assertEquals(240f, reduced.sizePx.height, PX_TOLERANCE)

        val tall = ImageBitmap(400, 2000)
        val capped = transferGhost(palette, tall)
        assertEquals(480f, capped.sizePx.height, PX_TOLERANCE, "the longer edge stops at the cap")
        assertEquals(96f, capped.sizePx.width, PX_TOLERANCE)
        assertEquals(capped.sizePx.height / 2000f, capped.scale, SCALE_TOLERANCE)
    }

    @Test
    fun `the hotspot follows the grab point into the reduced picture of a region`() {
        val panel = RecordingDrag(source = TransferGhostSource.Region(IntRect(420, 40, 720, 760)))
        val ghost = transferGhost(panel, ImageBitmap(300, 720))
        // Grabbed 70 px into the panel's header: the same point, reduced.
        val hotspot = ghost.hotspotPx(Offset(490f, 55f))
        assertEquals(70f * 0.6f, hotspot.x, PX_TOLERANCE)
        assertEquals(15f * 0.6f, hotspot.y, PX_TOLERANCE)
        // A grab outside the region clamps to the icon's edge.
        assertEquals(0f, ghost.hotspotPx(Offset(0f, 0f)).getDistance(), PX_TOLERANCE)
        assertEquals(ghost.sizePx.width, ghost.hotspotPx(Offset(5_000f, 55f)).x, PX_TOLERANCE)
        // No grab position at all: hung from the top edge, centred.
        assertEquals(ghost.sizePx.width / 2f, ghost.hotspotPx(null).x, PX_TOLERANCE)
    }

    private companion object {
        const val PX_TOLERANCE = 0.01f
        const val SCALE_TOLERANCE = 0.0001f
    }

    private class RecordingDrag(
        val source: TransferGhostSource = TransferGhostSource.None,
    ) : TransferDrag {
        var ended = 0
        var cancelled = 0

        override val title = "Tools"
        override val ghostSizePx = Size(220f, 30f)
        override val ghostSource: TransferGhostSource get() = source

        override fun end() {
            ended++
        }

        override fun cancel() {
            cancelled++
        }
    }
}
