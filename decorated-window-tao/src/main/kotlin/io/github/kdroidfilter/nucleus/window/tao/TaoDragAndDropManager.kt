package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.scene.ComposeSceneDragAndDropNode

/**
 * Stage 0 skeleton — logs every call so we can verify the Compose hook is
 * wired correctly. Native OS integration (NSDraggingDestination / IDropTarget /
 * gtk_drag_dest_set) lands in later stages.
 *
 * Compose-MP plumbing recap (verified against ui-desktop:1.10.3):
 *   - PlatformContext.dragAndDropManager (PlatformContext.skiko.kt:150) is
 *     read by RootNodeOwner.skiko.kt:133 at scene construction.
 *   - Compose's foundation Modifier.dragAndDropSource ends up calling
 *     [requestDragAndDropTransfer] when the drag threshold is crossed.
 *   - Inbound OS events (drop, hover) must be routed via
 *     [ComposeSceneDragAndDropNode] reachable as scene.rootDragAndDropNode —
 *     not the manager's responsibility, the host invokes it directly.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoDragAndDropManager(
    @Suppress("unused") // wired in stage 2+ for outbound OS drags
    private val getRootNode: () -> ComposeSceneDragAndDropNode,
) : PlatformDragAndDropManager {

    init {
        TaoDnDDiagnostics.constructed.intValue++
        TaoDnDDiagnostics.log("manager constructed")
    }

    // `true` so Compose actually calls requestDragAndDropTransfer when a
    // Modifier.dragAndDropSource crosses the drag threshold. The default
    // `false` short-circuits the call assuming the platform initiates the
    // session itself, which Tao does not do.
    override val isRequestDragAndDropTransferRequired: Boolean
        get() {
            TaoDnDDiagnostics.isRequiredQueries.intValue++
            return true
        }

    override fun requestDragAndDropTransfer(
        source: PlatformDragAndDropSource,
        offset: Offset,
    ) {
        TaoDnDDiagnostics.requests.intValue++
        TaoDnDDiagnostics.log("requestDragAndDropTransfer offset=$offset")

        // Drive the source so it produces its DragAndDropTransferData. Even
        // though we do not start an OS session yet, exercising this path
        // exposes any wiring problem early (Compose throws if the source's
        // lambda is never invoked while a drag-source modifier is registered).
        var started = false
        val scope = object : PlatformDragAndDropSource.StartTransferScope {
            override fun startDragAndDropTransfer(
                transferData: androidx.compose.ui.draganddrop.DragAndDropTransferData,
                decorationSize: androidx.compose.ui.geometry.Size,
                drawDragDecoration: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
            ): Boolean {
                TaoDnDDiagnostics.transfers.intValue++
                TaoDnDDiagnostics.log("startDragAndDropTransfer decorationSize=$decorationSize")
                started = true
                // Returning false tells Compose the session never actually
                // began at the platform level, which is accurate for the
                // skeleton — no OS drag was kicked off.
                return false
            }
        }
        with(source) {
            scope.startDragAndDropTransfer(offset) { started }
        }
    }
}
