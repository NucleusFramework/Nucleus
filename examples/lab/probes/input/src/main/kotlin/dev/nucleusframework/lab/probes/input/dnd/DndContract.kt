package dev.nucleusframework.lab.probes.input.dnd

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront

/** What a drop carried, read through every flavor the transferable offers. */
@Immutable
data class DropRecord(
    val index: Int,
    val mimeTypes: List<String>,
    val files: List<String>,
    val text: String?,
    val uris: List<String>,
    val error: String?,
)

enum class DragPhase { Started, Entered, Moved, Exited, Ended }

@Immutable
data class DndState(
    val phases: Map<DragPhase, Int> = emptyMap(),
    val hovering: Boolean = false,
    val drops: List<DropRecord> = emptyList(),
    val dropCount: Int = 0,
    /** `kind → action` of every drag that left from here. */
    val exports: List<String> = emptyList(),
)

sealed interface DndIntent {
    data object Reset : DndIntent
}

sealed interface DndEvent {
    data class Phase(
        val phase: DragPhase,
    ) : DndEvent

    data class Dropped(
        val mimeTypes: List<String>,
        val files: List<String>,
        val text: String?,
        val uris: List<String>,
        val error: String?,
    ) : DndEvent

    data class Exported(
        val kind: String,
        val action: String,
    ) : DndEvent

    data object Cleared : DndEvent
}

object DndReducer : Reducer<DndState, DndEvent> {
    private const val LOG = 12

    override fun reduce(
        state: DndState,
        event: DndEvent,
    ): DndState =
        when (event) {
            is DndEvent.Phase ->
                state.copy(
                    phases = state.phases + (event.phase to (state.phases[event.phase] ?: 0) + 1),
                    hovering =
                        when (event.phase) {
                            DragPhase.Entered, DragPhase.Moved -> true
                            DragPhase.Exited, DragPhase.Ended -> false
                            DragPhase.Started -> state.hovering
                        },
                )
            is DndEvent.Dropped -> {
                val index = state.dropCount + 1
                val record = DropRecord(index, event.mimeTypes, event.files, event.text, event.uris, event.error)
                state.copy(drops = state.drops.pushFront(record, LOG), dropCount = index, hovering = false)
            }
            is DndEvent.Exported ->
                state.copy(
                    exports = state.exports.pushFront("${event.kind} → ${event.action}", LOG),
                )
            DndEvent.Cleared -> DndState()
        }
}
