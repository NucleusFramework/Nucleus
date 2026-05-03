package io.github.kdroidfilter.nucleus.window.tao

/**
 * Backend-specific payload exposed as `DragAndDropEvent.nativeEvent` on the
 * Tao backend. Users can pattern-match on it from inside a
 * `Modifier.dragAndDropTarget` callback:
 *
 * ```
 * onDrop = { event ->
 *     val tao = event.nativeEvent as? TaoDragAndDropPayload
 *     val files = tao?.files ?: emptyList()
 *     …
 * }
 * ```
 *
 * Stage 3 covers files only on Windows; text / HTML / URLs land in follow-ups.
 */
class TaoDragAndDropPayload internal constructor(
    val files: List<String>,
)
