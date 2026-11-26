package dev.nucleusframework.window.tao.dnd

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.scene.ComposeSceneDragAndDropNode
import dev.nucleusframework.window.tao.TaoDragAndDropPayload
import java.awt.Point
import java.awt.dnd.DnDConstants

/**
 * Platform-agnostic drag-and-drop scene wiring, shared by the macOS, Windows and
 * Linux [dev.nucleusframework.window.tao.scene] hosts.
 *
 * The three hosts previously each carried a byte-identical copy of this logic;
 * the only per-platform differences are the native bridge object
 * (`NativeTao{MacOs,Windows,Linux}DndBridge`) and the first callback parameter
 * name (nsView/hwnd/handle). Everything below is independent of those, so it
 * lives here once.
 *
 * This helper is the **common denominator only** — event construction plus the
 * Compose-node handoff. Two per-host quirks are deliberately kept in each host's
 * thin `InboundDnDCallback` override rather than folded in here, because they
 * are genuine behavioural differences, not duplication:
 *  - `TaoDnDDiagnostics.log(...)` calls (macOS/Windows log; Linux does not);
 *  - the `if (!hasData) return NONE` short-circuit in `onDragEnter`
 *    (macOS/Windows guard drags carrying nothing they can read; Linux
 *    intentionally does not).
 * Folding either in here would silently change Linux behaviour.
 *
 * Inbound handlers return a plain `Boolean` (accepted?) rather than a
 * `DROP_EFFECT_*` constant: each host maps `true → its own DROP_EFFECT_COPY` and
 * `false → its own DROP_EFFECT_NONE`, so nothing here depends on the three
 * bridges happening to share the same integer values.
 *
 * Inbound data is a [TaoDragAndDropPayload] — files, plain text and non-file
 * URIs (#930). A host that only reads files goes through the `files` overloads;
 * one that also reads text or URIs builds the payload with [inboundPayload].
 *
 * Each host keeps its own named `InboundDnDCallback` implementing that bridge's
 * `Callback` interface (required for GraalVM JNI reachability — anonymous
 * classes aren't picked up by `GetMethodID`); the overrides just delegate here.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal object TaoSceneDnD {
    class InboundState {
        private var hoverData: TaoDragAndDropPayload? = null

        /** Updates the hover preview for this drag; `null` clears it. */
        fun onDragData(data: TaoDragAndDropPayload?) {
            hoverData = data?.takeUnless { it.isEmpty }
        }

        fun onDragFiles(files: Array<String>?) {
            onDragData(inboundPayload(files = files))
        }

        fun onDragEnter(
            node: ComposeSceneDragAndDropNode?,
            x: Int,
            y: Int,
        ): Boolean = TaoSceneDnD.onDragEnter(node = node, x = x, y = y, data = hoverData)

        fun onDragOver(
            node: ComposeSceneDragAndDropNode?,
            x: Int,
            y: Int,
        ): Boolean = TaoSceneDnD.onDragOver(node = node, x = x, y = y, data = hoverData)

        fun onDragLeave(node: ComposeSceneDragAndDropNode?) {
            hoverData = null
            TaoSceneDnD.onDragLeave(node = node)
        }

        fun onDrop(
            node: ComposeSceneDragAndDropNode?,
            x: Int,
            y: Int,
            data: TaoDragAndDropPayload?,
        ): Boolean {
            hoverData = null
            return TaoSceneDnD.onDrop(node = node, x = x, y = y, data = data)
        }

        fun onDrop(
            node: ComposeSceneDragAndDropNode?,
            x: Int,
            y: Int,
            files: Array<String>?,
        ): Boolean = onDrop(node = node, x = x, y = y, data = inboundPayload(files = files))
    }

    /**
     * Builds the inbound payload from what a native bridge read. Empty text and
     * blank URIs are dropped, and so is any `file:` URI: files belong in
     * [TaoDragAndDropPayload.files] only, so a `text/uri-list` mixing both kinds
     * can be passed as is once its file URIs were also converted to paths.
     */
    fun inboundPayload(
        files: Array<String>? = null,
        text: String? = null,
        uris: Array<String>? = null,
    ): TaoDragAndDropPayload =
        TaoDragAndDropPayload(
            files = files?.filter { it.isNotEmpty() }.orEmpty(),
            text = text?.takeIf { it.isNotEmpty() },
            uris =
                uris
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith("file:", ignoreCase = true) }
                    .orEmpty(),
        )

    private val EMPTY_PAYLOAD = TaoDragAndDropPayload(files = emptyList())

    private fun makeDragEvent(
        xPx: Int,
        yPx: Int,
        data: TaoDragAndDropPayload?,
    ): DragAndDropEvent = makeEvent(xPx, yPx, data, drop = false)

    private fun makeDropEvent(
        xPx: Int,
        yPx: Int,
        data: TaoDragAndDropPayload?,
    ): DragAndDropEvent = makeEvent(xPx, yPx, data, drop = true)

    private fun makeEvent(
        xPx: Int,
        yPx: Int,
        data: TaoDragAndDropPayload?,
        drop: Boolean,
    ): DragAndDropEvent {
        val payload = data ?: EMPTY_PAYLOAD
        val transferable = TaoDropTransferable(payload)
        val cursor = Point(xPx, yPx)
        val native =
            if (drop) {
                TaoSyntheticDropEvent(
                    cursorLocn = cursor,
                    dropAction = DnDConstants.ACTION_COPY,
                    backingTransferable = transferable,
                    payload = payload,
                )
            } else {
                TaoSyntheticDragEvent(
                    cursorLocn = cursor,
                    dropAction = DnDConstants.ACTION_COPY,
                    backingTransferable = transferable,
                    payload = payload,
                )
            }
        return DragAndDropEvent(
            action = DragAndDropTransferAction.Copy,
            nativeEvent = native,
            positionInRootImpl = Offset(xPx.toFloat(), yPx.toFloat()),
        )
    }

    /**
     * @return true if the drag was accepted (host maps to DROP_EFFECT_COPY, else
     *   NONE). Callers that guard drags carrying no readable data must do so
     *   before calling.
     */
    fun onDragEnter(
        node: ComposeSceneDragAndDropNode?,
        x: Int,
        y: Int,
        data: TaoDragAndDropPayload? = null,
    ): Boolean {
        if (node == null) return false
        val ev = makeDragEvent(x, y, data)
        val accepted = node.acceptDragAndDropTransfer(ev)
        if (accepted) {
            node.onStarted(ev)
            node.onEntered(ev)
            // The entry event carries a position, and only `onMoved` makes the
            // root resolve the target under it. Without this, the target the
            // pointer entered on is not entered until the next motion event —
            // so its highlight lags a frame, and a platform that delivers
            // enter → drop with no motion in between (or a drop right after a
            // re-entry) finds no target and refuses perfectly good files.
            node.onMoved(ev)
        }
        return accepted
    }

    /** @return true if a drop target is currently eligible. */
    fun onDragOver(
        node: ComposeSceneDragAndDropNode?,
        x: Int,
        y: Int,
        data: TaoDragAndDropPayload? = null,
    ): Boolean {
        if (node == null) return false
        val ev = makeDragEvent(x, y, data)
        node.onMoved(ev)
        return node.hasEligibleDropTarget
    }

    fun onDragLeave(node: ComposeSceneDragAndDropNode?) {
        if (node == null) return
        val ev = makeDragEvent(-1, -1, null)
        node.onExited(ev)
        node.onEnded(ev)
    }

    /** @return true if the drop was accepted. */
    fun onDrop(
        node: ComposeSceneDragAndDropNode?,
        x: Int,
        y: Int,
        files: Array<String>?,
    ): Boolean = onDrop(node = node, x = x, y = y, data = inboundPayload(files = files))

    /** @return true if the drop was accepted. */
    fun onDrop(
        node: ComposeSceneDragAndDropNode?,
        x: Int,
        y: Int,
        data: TaoDragAndDropPayload?,
    ): Boolean {
        if (node == null) return false
        val ev = makeDropEvent(x, y, data)
        val accepted = node.onDrop(ev)
        node.onEnded(ev)
        return accepted
    }

    /**
     * Shared outbound (Compose source → OS) drag launch. The host supplies its
     * bridge's `DROP_EFFECT_*` constants and a [startDrag] lambda wrapping the
     * platform `nativeStartDrag`; the guards (`isLoaded`, valid surface handle)
     * stay in the host since they read host state.
     */
    fun launchOutboundDrag(
        request: TaoDragAndDropManager.OutboundRequest,
        dropEffectCopy: Int,
        dropEffectMove: Int,
        dropEffectLink: Int,
        startDrag: (files: Array<String>?, text: String?, allowedEffects: Int) -> Int,
    ): DragAndDropTransferAction? {
        val allowed =
            request.supportedActions
                .fold(0) { acc, action ->
                    acc or
                        when (action) {
                            DragAndDropTransferAction.Copy -> dropEffectCopy
                            DragAndDropTransferAction.Move -> dropEffectMove
                            DragAndDropTransferAction.Link -> dropEffectLink
                            else -> 0
                        }
                }.let { if (it == 0) dropEffectCopy else it }
        val files =
            request.files
                .takeIf { it.isNotEmpty() }
                ?.map { it.absolutePath }
                ?.toTypedArray()
        return when (startDrag(files, request.text, allowed)) {
            dropEffectCopy -> DragAndDropTransferAction.Copy
            dropEffectMove -> DragAndDropTransferAction.Move
            dropEffectLink -> DragAndDropTransferAction.Link
            else -> null
        }
    }
}
