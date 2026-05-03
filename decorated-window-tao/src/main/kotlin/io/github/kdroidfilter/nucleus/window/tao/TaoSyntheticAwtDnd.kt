package io.github.kdroidfilter.nucleus.window.tao

import java.awt.Point
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetContext
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import javax.swing.JPanel

/**
 * Synthetic AWT drag-and-drop events for the Tao backend.
 *
 * Compose's `DragAndDropEvent.awtTransferable` extension (in
 * `compose-ui-desktop`) does:
 *
 * ```
 * when (nativeEvent) {
 *     is DropTargetDragEvent -> nativeEvent.transferable
 *     is DropTargetDropEvent -> nativeEvent.transferable
 *     else -> error("Unrecognized AWT drag event: $nativeEvent")
 * }
 * ```
 *
 * To make `event.awtTransferable` work transparently on the Tao backend, we
 * publish the OS payload as a synthetic [DropTargetDragEvent] /
 * [DropTargetDropEvent] subclass whose [getTransferable] is overridden to
 * return our [TaoFilesTransferable] adapter. AWT's class hierarchy requires a
 * real [DropTargetContext] in the constructor — we pin a single shadow
 * `JPanel`/`DropTarget` pair that is never displayed, mirroring the pattern
 * already used elsewhere in the backend (e.g. the `KEY_TYPED` synthetic
 * `KeyEvent` source).
 *
 * The synthetic events also carry the [TaoDragAndDropPayload] directly via
 * [TaoSyntheticDropEvent.payload] / [TaoSyntheticDragEvent.payload] so a
 * non-AWT consumer can pattern-match without invoking the toolkit at all.
 */
internal object TaoSyntheticDropContext {
    private val shadowComponent: java.awt.Component = JPanel()
    private val shadowDropTarget: DropTarget = DropTarget(
        shadowComponent,
        DnDConstants.ACTION_COPY_OR_MOVE,
        null,
        true,
    )

    val context: DropTargetContext get() = shadowDropTarget.dropTargetContext
}

internal class TaoSyntheticDragEvent(
    cursorLocn: Point,
    dropAction: Int,
    private val backingTransferable: Transferable,
    val payload: TaoDragAndDropPayload,
) : DropTargetDragEvent(
    TaoSyntheticDropContext.context,
    cursorLocn,
    dropAction,
    DnDConstants.ACTION_COPY_OR_MOVE,
) {
    override fun getTransferable(): Transferable = backingTransferable

    override fun acceptDrag(dragOperation: Int) { /* synthetic — no real peer to notify */ }
    override fun rejectDrag() { /* synthetic */ }
}

internal class TaoSyntheticDropEvent(
    cursorLocn: Point,
    dropAction: Int,
    private val backingTransferable: Transferable,
    val payload: TaoDragAndDropPayload,
) : DropTargetDropEvent(
    TaoSyntheticDropContext.context,
    cursorLocn,
    dropAction,
    DnDConstants.ACTION_COPY_OR_MOVE,
) {
    override fun getTransferable(): Transferable = backingTransferable

    override fun acceptDrop(dropAction: Int) { /* synthetic — no peer */ }
    override fun rejectDrop() { /* synthetic */ }
    override fun dropComplete(success: Boolean) { /* synthetic — completion handled natively */ }
    override fun isLocalTransfer(): Boolean = false
}
