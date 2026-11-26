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
 *     val payload = event.taoPayload ?: return@onDrop false
 *     val link = payload.uris.firstOrNull() // e.g. an image dragged from a browser
 *     val text = payload.text
 *     val files = payload.files
 *     …
 * }
 * ```
 *
 * The same data is reachable through Compose's `event.awtTransferable`:
 * `DataFlavor.javaFileListFlavor` (files), `DataFlavor.stringFlavor` ([text], else the
 * URIs, else the file paths), `text/uri-list` and `application/x-java-url` (the URIs).
 *
 * `DragAndDropEvent.nativeEvent` itself is an AWT `DropTargetDropEvent` /
 * `DropTargetDragEvent` (so Compose's `event.awtTransferable` keeps working),
 * never this class — do not cast it directly.
 *
 * Hover events carry what the platform could read before the drop; the drop event
 * always carries the full payload.
 */
public class TaoDragAndDropPayload internal constructor(
    /** Absolute paths of the dragged files. */
    public val files: List<String>,
    /** Plain text offered by the drag source, or `null` when it offers none. */
    public val text: String? = null,
    /**
     * Non-file URIs offered by the drag source — a link or an image dragged
     * from a browser. Files are never repeated here, they are in [files].
     */
    public val uris: List<String> = emptyList(),
) {
    internal val isEmpty: Boolean get() = files.isEmpty() && text == null && uris.isEmpty()
}

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
