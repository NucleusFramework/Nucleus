package dev.nucleusframework.window.tao.dnd

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.TaoTransferableAccess
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Guards the friend-package accessor [TaoTransferableAccess] against Compose
 * renaming or removing its `internal` `AwtDragAndDropTransferable` type.
 *
 * A rename would break the Java accessor at compile time (it references the
 * type by name), so the module wouldn't build. This test additionally covers
 * the runtime contract: a `DragAndDropTransferable` built from an AWT
 * `Transferable` via Compose's public factory must still be unwrappable back
 * to that exact AWT object — if Compose's factory ever stopped producing an
 * `AwtDragAndDropTransferable`, `toAwt` would silently return null and this
 * fails.
 *
 * Headless-safe: no AWT toolkit peer is created (the transferable is a plain
 * data object), so it runs in ordinary CI.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TaoTransferableAccessGuardTest {
    @Test
    fun `toAwt unwraps a Compose AWT-bound transferable back to its source`() {
        val awt = TaoDropTransferable(TaoSceneDnD.inboundPayload(files = arrayOf(File("guard.txt").absolutePath)))
        val compose = DragAndDropTransferable(awt)
        val unwrapped = TaoTransferableAccess.toAwt(compose)
        assertNotNull(
            unwrapped,
            "Compose's internal AwtDragAndDropTransferable is no longer matched — " +
                "the friend-package accessor needs updating.",
        )
        assertSame(awt, unwrapped, "toAwt must return the exact backing AWT transferable")
    }
}
