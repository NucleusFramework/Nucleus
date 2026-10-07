package dev.nucleusframework.lab.probes.input.clipboard

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.pushFront

@Immutable
data class ClipboardState(
    val bound: Boolean = false,
    val draft: String = "Copied by the Nucleus Lab",
    val watching: Boolean = false,
    val content: ClipboardContent? = null,
    /** Content changes seen while watching: something else (another app) wrote the clipboard. */
    val externalChanges: Int = 0,
    val lastWritten: Payload? = null,
    val history: List<String> = emptyList(),
)

sealed interface ClipboardIntent {
    data class Write(
        val payload: Payload,
    ) : ClipboardIntent

    data object Read : ClipboardIntent

    data class Watch(
        val on: Boolean,
    ) : ClipboardIntent
}

sealed interface ClipboardEvent {
    data class Bound(
        val bound: Boolean,
    ) : ClipboardEvent

    data class DraftChanged(
        val text: String,
    ) : ClipboardEvent

    data class Written(
        val payload: Payload,
    ) : ClipboardEvent

    /** Read on request (or right after our own write). */
    data class ReadBack(
        val content: ClipboardContent,
    ) : ClipboardEvent

    /** Read by the watcher; `null` when the read failed (the failure is its own event). */
    data class Polled(
        val content: ClipboardContent?,
    ) : ClipboardEvent

    data class WatchChanged(
        val on: Boolean,
    ) : ClipboardEvent

    data class Failed(
        val message: String,
    ) : ClipboardEvent
}

object ClipboardReducer : Reducer<ClipboardState, ClipboardEvent> {
    private const val HISTORY = 20

    override fun reduce(
        state: ClipboardState,
        event: ClipboardEvent,
    ): ClipboardState =
        when (event) {
            is ClipboardEvent.Bound -> state.copy(bound = event.bound)
            is ClipboardEvent.DraftChanged -> state.copy(draft = event.text)
            is ClipboardEvent.Written -> state.copy(lastWritten = event.payload).log("wrote ${event.payload}")
            is ClipboardEvent.ReadBack -> state.copy(content = event.content).log("read: ${event.content.describe()}")
            is ClipboardEvent.Polled -> {
                val content = event.content
                when {
                    content == null -> state
                    !state.isExternalChange(content) -> state.copy(content = content)
                    else ->
                        state
                            .copy(content = content, externalChanges = state.externalChanges + 1)
                            .log("changed outside: ${content.describe()}")
                }
            }
            is ClipboardEvent.WatchChanged -> state.copy(watching = event.on)
            is ClipboardEvent.Failed -> state.log("⚠ ${event.message}")
        }

    private fun ClipboardState.log(line: String) = copy(history = history.pushFront(line, HISTORY))

    private fun ClipboardContent.describe(): String = mimeTypes.joinToString().ifEmpty { "empty" }
}

/** A watcher read that differs from what was last seen: something else wrote the clipboard. */
fun ClipboardState.isExternalChange(read: ClipboardContent): Boolean = content != null && content != read
