package dev.nucleusframework.window.tao.dnd

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
import dev.nucleusframework.window.tao.TaoDnDDiagnostics
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
    /**
     * Whether [outboundLauncher] can run a session whose only payload is a
     * [TaoPrivateTransfer] token. Only the Linux host does: the cross-window
     * gestures ride the DnD session there on native Wayland. Elsewhere such a
     * request is refused like any other with nothing to export.
     */
    private val acceptsPrivateData: Boolean = false,
) : PlatformDragAndDropManager {
    /**
     * Per-platform implementation of the actual OS drag session. Receives the
     * extracted payload (already coerced from the user's [Transferable] into
     * the cross-platform shape `files + text`).
     *
     * The contract is asynchronous because this is called from inside
     * Compose's `sendPointerEvent` dispatch, and [startDragAndDropTransfer]
     * must answer Compose before the OS session necessarily exists (#435):
     * returns `true` if the session was — or will be — started, in which case
     * [onCompleted] is invoked exactly once when the session ends, with the
     * action the destination accepted or `null` if cancelled. Returns `false`
     * without ever calling [onCompleted] when the session cannot start
     * (native library missing, window gone).
     *
     * macOS (`beginDraggingSession`) and Linux (`gtk_drag_begin_with_coordinates`)
     * run the session synchronously — their native calls cooperatively pump the
     * platform run loop — and call [onCompleted] before returning. Windows
     * defers `DoDragDrop` onto the main dispatcher so the modal session starts
     * with no Compose dispatch below it, and calls [onCompleted] one event-loop
     * iteration later, when `DoDragDrop` returns.
     */
    fun interface OutboundLauncher {
        fun launch(
            request: OutboundRequest,
            onCompleted: (DragAndDropTransferAction?) -> Unit,
        ): Boolean
    }

    class OutboundRequest internal constructor(
        val files: List<File>,
        val text: String?,
        /** In-process token, see [TaoPrivateTransfer]; `null` for an ordinary data drag. */
        val privateData: String?,
        val supportedActions: List<DragAndDropTransferAction>,
        val decorationSize: Size,
        val drawDragDecoration: DrawScope.() -> Unit,
        /**
         * Where the pointer sits inside the decoration, in the decoration's
         * own pixels. Compose (and AWT's `DragSource.startDrag`) place the
         * decoration's origin at the pointer *plus* the transfer's
         * `dragDecorationOffset`, so the pointer is at minus that offset.
         */
        val decorationHotspot: Offset,
    )

    init {
        TaoDnDDiagnostics.constructed.intValue++
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

        var inProgress = false
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
                    val privateData = TaoPrivateTransfer.tokenOf(awt)?.takeIf { acceptsPrivateData }
                    if (files.isEmpty() && text == null && privateData == null) {
                        TaoDnDDiagnostics.log("startDragAndDropTransfer skipped — no exportable data")
                        return false
                    }

                    val request =
                        OutboundRequest(
                            files = files,
                            text = text,
                            privateData = privateData,
                            supportedActions = transferData.supportedActions.toList(),
                            decorationSize = decorationSize,
                            drawDragDecoration = drawDragDecoration,
                            decorationHotspot = -transferData.dragDecorationOffset,
                        )
                    TaoDnDDiagnostics.log(
                        "starting OS drag files=${files.size} text=${text != null} private=${privateData != null}",
                    )
                    inProgress = true
                    val launched =
                        launcher.launch(request) { result ->
                            TaoDnDDiagnostics.log("OS drag completed action=$result")
                            inProgress = false
                            transferData.onTransferCompleted?.invoke(result)
                        }
                    if (!launched) {
                        inProgress = false
                        TaoDnDDiagnostics.log("startDragAndDropTransfer skipped — launcher refused")
                        return false
                    }
                    return true
                }
            }
        with(source) { scope.startDragAndDropTransfer(offset) { inProgress } }
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
