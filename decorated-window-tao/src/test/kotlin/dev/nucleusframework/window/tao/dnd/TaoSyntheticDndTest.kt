package dev.nucleusframework.window.tao.dnd

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TaoDragAndDropPayload
import dev.nucleusframework.window.tao.scene.runTaoSceneTest
import dev.nucleusframework.window.tao.taoPayload
import org.junit.Assume.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural coverage of the inbound drag-and-drop conversion: native OS
 * payload → [TaoFilesTransferable] → [TaoSyntheticDragEvent] /
 * [TaoSyntheticDropEvent] → Compose [DragAndDropEvent] → application
 * `dragAndDropTarget`. The event construction below mirrors the hosts'
 * `InboundDnDCallback.makeDragEvent` / `makeDropEvent` verbatim (see
 * `TaoComposeSceneHostLinux.kt`), so a contract break in either the
 * synthetic AWT event shape or Compose's `awtTransferable` accessor fails
 * here before it fails on a real drop.
 *
 * JVM-only (not in [TaoSceneTestBattery]): the synthetic drop context pins
 * an AWT `Component`/`DropTarget` pair, which the no-AWT native image
 * deliberately never initialises outside a real drag. For the same reason
 * the tests skip under a headless AWT (headless CI runners): constructing an
 * AWT `DropTarget` requires a real toolkit peer. In production these events
 * are only ever built while a real drag is in flight, i.e. with a display.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TaoSyntheticDndTest {
    @BeforeTest
    fun requireDisplay() {
        assumeFalse("synthetic AWT DnD needs a non-headless toolkit", GraphicsEnvironment.isHeadless())
    }

    private fun makeDropEvent(
        xPx: Int,
        yPx: Int,
        files: List<File>,
    ): DragAndDropEvent {
        // Mirror of InboundDnDCallback.makeDropEvent in the three hosts.
        val payload = TaoDragAndDropPayload(files = files.map { it.absolutePath })
        val native =
            TaoSyntheticDropEvent(
                cursorLocn = Point(xPx, yPx),
                dropAction = DnDConstants.ACTION_COPY,
                backingTransferable = TaoFilesTransferable(files),
                payload = payload,
            )
        return DragAndDropEvent(
            action = DragAndDropTransferAction.Copy,
            nativeEvent = native,
            positionInRootImpl = Offset(xPx.toFloat(), yPx.toFloat()),
        )
    }

    private fun makeDragEvent(
        xPx: Int,
        yPx: Int,
    ): DragAndDropEvent {
        val payload = TaoDragAndDropPayload(files = emptyList())
        val native =
            TaoSyntheticDragEvent(
                cursorLocn = Point(xPx, yPx),
                dropAction = DnDConstants.ACTION_COPY,
                backingTransferable = TaoFilesTransferable(emptyList()),
                payload = payload,
            )
        return DragAndDropEvent(
            action = DragAndDropTransferAction.Copy,
            nativeEvent = native,
            positionInRootImpl = Offset(xPx.toFloat(), yPx.toFloat()),
        )
    }

    @Test
    fun `native file payload reaches Compose through awtTransferable`() {
        val files = listOf(File("/tmp/report.pdf"), File("/tmp/photo.png"))
        val event = makeDropEvent(10, 20, files)

        // This is the exact accessor application code uses on a drop — it
        // pattern-matches the nativeEvent on the AWT DropTarget event types.
        val transferable = event.awtTransferable
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertEquals(files, transferable.getTransferData(DataFlavor.javaFileListFlavor))
        assertEquals(
            files.joinToString("\n") { it.absolutePath },
            transferable.getTransferData(DataFlavor.stringFlavor),
        )

        // Non-AWT consumers pattern-match the payload directly.
        val native = event.nativeEvent as TaoSyntheticDropEvent
        assertEquals(files.map { it.absolutePath }, native.payload.files)
        assertEquals(Point(10, 20), (native as DropTargetDropEvent).location)
    }

    @Test
    fun `taoPayload exposes the dropped files to application code`() {
        val files = listOf(File("/tmp/report.pdf"), File("/tmp/photo.png"))
        val event = makeDropEvent(10, 20, files)

        // #912: nativeEvent is the synthetic AWT event, never the payload itself,
        // so the formerly documented cast always yielded null.
        assertNull(event.nativeEvent as? TaoDragAndDropPayload)
        assertEquals(files.map { it.absolutePath }, event.taoPayload?.files)
        assertEquals(emptyList(), makeDragEvent(10, 20).taoPayload?.files)
    }

    @Test
    fun `synthetic drop drives a Compose dragAndDropTarget in a real scene`() =
        runTaoSceneTest(width = 200, height = 200) {
            var entered = 0
            var dropped: List<String>? = null
            var payloadFiles: List<String>? = null
            val target =
                object : DragAndDropTarget {
                    override fun onEntered(event: DragAndDropEvent) {
                        entered++
                    }

                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        payloadFiles = event.taoPayload?.files
                        @Suppress("UNCHECKED_CAST")
                        val files =
                            event.awtTransferable
                                .getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        dropped = files.map { it.absolutePath }
                        return true
                    }
                }
            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
                )
            }
            frameUntilIdle()

            // Mirror of InboundDnDCallback.onDragEnter / onDragOver / onDrop,
            // minus JNI. Compose resolves per-target enter/exit on the move
            // step, so the host always follows enter with at least one over.
            val root = scene.rootDragAndDropNode
            val enter = makeDragEvent(100, 100)
            assertTrue(root.acceptDragAndDropTransfer(enter), "drop target must accept the transfer")
            root.onStarted(enter)
            root.onEntered(enter)
            root.onMoved(makeDragEvent(100, 100))
            frameUntilIdle()
            assertTrue(entered >= 1, "onEntered must reach the application target (got $entered)")

            val droppedFile = File("dropped.txt")
            val drop = makeDropEvent(100, 100, listOf(droppedFile))
            assertTrue(root.onDrop(drop), "drop must be accepted by the application target")
            root.onEnded(drop)
            frameUntilIdle()
            // Compare through absolutePath both ways — the conversion round-trips
            // File objects, and the string form is platform-normalised.
            assertEquals(listOf(droppedFile.absolutePath), dropped)
            assertEquals(listOf(droppedFile.absolutePath), payloadFiles)
        }
}
