package dev.nucleusframework.window.tao

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import dev.nucleusframework.window.tao.dnd.TaoSyntheticDragEvent
import dev.nucleusframework.window.tao.dnd.TaoSyntheticDropEvent

/**
 * Backend-specific payload of an inbound drag-and-drop on the Tao backend.
 *
 * Read it from inside a `Modifier.dragAndDropTarget` callback through the
 * [taoPayload] extension:
 *
 * ```
 * onDrop = { event ->
 *     val files = event.taoPayload?.files ?: emptyList()
 *     …
 * }
 * ```
 *
 * `DragAndDropEvent.nativeEvent` itself is an AWT `DropTargetDropEvent` /
 * `DropTargetDragEvent` (so Compose's `event.awtTransferable` keeps working),
 * never this class — do not cast it directly.
 *
 * Files are only populated on the drop event; the hover events carry an empty list.
 */
public class TaoDragAndDropPayload internal constructor(
    public val files: List<String>,
)

/**
 * The [TaoDragAndDropPayload] carried by this event when it was produced by the
 * Tao backend, or `null` for events coming from any other backend.
 */
public val DragAndDropEvent.taoPayload: TaoDragAndDropPayload?
    @OptIn(ExperimentalComposeUiApi::class)
    get() =
        when (val native = nativeEvent) {
            is TaoSyntheticDropEvent -> native.payload
            is TaoSyntheticDragEvent -> native.payload
            else -> null
        }
