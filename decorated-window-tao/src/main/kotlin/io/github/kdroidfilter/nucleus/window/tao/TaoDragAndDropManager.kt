package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.scene.ComposeSceneDragAndDropNode
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * Compose-MP plumbing recap (verified against ui-desktop:1.10.x):
 *   - PlatformContext.dragAndDropManager (PlatformContext.skiko.kt:150) is
 *     read by RootNodeOwner.skiko.kt:133 at scene construction.
 *   - Compose's foundation Modifier.dragAndDropSource ends up calling
 *     [requestDragAndDropTransfer] when the drag threshold is crossed.
 *   - Inbound OS events (drop, hover) are routed by the host directly into
 *     [ComposeSceneDragAndDropNode] — not this manager's responsibility.
 *
 * Outbound (Compose source → OS) is opt-in per host: each platform host
 * passes an [outboundLauncher] that knows how to call the OS DnD API
 * (`DoDragDrop`, `beginDraggingSession`, `gtk_drag_begin`). When null (e.g.
 * macOS/Linux until their Stage 5 lands), the manager logs the request and
 * returns false so Compose treats the gesture as not-started.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class TaoDragAndDropManager(
    @Suppress("unused") // wired in stage 2+ for inbound proxy through the manager
    private val getRootNode: () -> ComposeSceneDragAndDropNode,
    private val outboundLauncher: OutboundLauncher? = null,
) : PlatformDragAndDropManager {
    /**
     * Per-platform implementation of the actual OS drag session. Receives the
     * extracted payload (already coerced from the user's [Transferable] into
     * the cross-platform shape `files + text`) and returns the [DROPEFFECT]
     * the destination accepted, or `null` if cancelled.
     *
     * On Windows this maps to `DoDragDrop`. On macOS it will map to
     * `[NSView beginDraggingSessionWithItems:event:source:]`. On Linux to
     * `gtk_drag_begin_with_coordinates`.
     */
    fun interface OutboundLauncher {
        fun launch(request: OutboundRequest): DragAndDropTransferAction?
    }

    class OutboundRequest internal constructor(
        val files: List<File>,
        val text: String?,
        val supportedActions: List<DragAndDropTransferAction>,
        val decorationSize: Size,
        val drawDragDecoration: DrawScope.() -> Unit,
    )

    init {
        TaoDnDDiagnostics.constructed.intValue++
        TaoDnDDiagnostics.log("manager constructed (outbound=${outboundLauncher != null})")
    }

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

        var started = false
        val scope =
            object : PlatformDragAndDropSource.StartTransferScope {
                override fun startDragAndDropTransfer(
                    transferData: DragAndDropTransferData,
                    decorationSize: Size,
                    drawDragDecoration: DrawScope.() -> Unit,
                ): Boolean {
                    TaoDnDDiagnostics.transfers.intValue++
                    val launcher =
                        outboundLauncher ?: run {
                            TaoDnDDiagnostics.log("startDragAndDropTransfer skipped — no outbound launcher")
                            return false
                        }

                    val awt =
                        transferData.awtTransferable() ?: run {
                            TaoDnDDiagnostics.log("startDragAndDropTransfer skipped — non-AWT transferable")
                            return false
                        }
                    val files = awt.extractFiles()
                    val text = awt.extractText()
                    if (files.isEmpty() && text == null) {
                        TaoDnDDiagnostics.log("startDragAndDropTransfer skipped — no exportable data")
                        return false
                    }

                    val request =
                        OutboundRequest(
                            files = files,
                            text = text,
                            supportedActions = transferData.supportedActions.toList(),
                            decorationSize = decorationSize,
                            drawDragDecoration = drawDragDecoration,
                        )
                    TaoDnDDiagnostics.log("starting OS drag files=${files.size} text=${text != null}")
                    val result = launcher.launch(request)
                    TaoDnDDiagnostics.log("OS drag completed action=$result")
                    transferData.onTransferCompleted?.invoke(result)
                    started = result != null
                    return true
                }
            }
        with(source) { scope.startDragAndDropTransfer(offset) { started } }
    }

    /**
     * Reaches through Compose's `internal interface AwtDragAndDropTransferable`
     * (defined in `androidx.compose.ui.draganddrop`, JVM module-private) to
     * extract the user's underlying `java.awt.datatransfer.Transferable`.
     *
     * Goes through a Java helper in the same package: Java doesn't honour
     * Kotlin's `internal` visibility, so a same-package Java file can cast
     * to the interface and call its method directly — zero reflection,
     * native-image friendly.
     */
    private fun DragAndDropTransferData.awtTransferable(): Transferable? =
        androidx.compose.ui.draganddrop.TaoTransferableAccess
            .toAwt(this.transferable)

    @Suppress("UNCHECKED_CAST")
    private fun Transferable.extractFiles(): List<File> =
        if (
            isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        ) {
            runCatching {
                getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    private fun Transferable.extractText(): String? =
        if (
            isDataFlavorSupported(DataFlavor.stringFlavor)
        ) {
            runCatching {
                getTransferData(DataFlavor.stringFlavor) as? String
            }.getOrNull()
        } else {
            null
        }
}
