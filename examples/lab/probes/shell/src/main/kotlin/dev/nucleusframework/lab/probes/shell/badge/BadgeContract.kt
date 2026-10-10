package dev.nucleusframework.lab.probes.shell.badge

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.launcher.windows.BadgeGlyph

@Immutable
data class BadgeState(
    val backend: BadgeBackend = BadgeBackend.None,
    val availability: Availability = Availability.Unknown,
    val target: String? = null,
    val prepared: CallOutcome? = null,
    val canReadBack: Boolean = false,
    val supportsGlyphs: Boolean = false,
    /** What the Lab last asked for: a count, a glyph name, or `null` once cleared. */
    val requested: String? = null,
    /** What the OS reports (macOS only). */
    val readBack: Int? = null,
    val calls: List<CallRecord> = emptyList(),
)

sealed interface BadgeIntent {
    data class SetCount(
        val count: Int,
    ) : BadgeIntent

    data class SetGlyph(
        val glyph: BadgeGlyph,
    ) : BadgeIntent

    data object Clear : BadgeIntent

    data object ReadBack : BadgeIntent

    /** Counts 1 → [to] at 300 ms per step: catches throttling and lost updates. */
    data class Ramp(
        val to: Int,
    ) : BadgeIntent
}

sealed interface BadgeEvent {
    data class Ready(
        val backend: BadgeBackend,
        val availability: Availability,
        val target: String?,
        val prepared: CallOutcome,
        val canReadBack: Boolean,
        val supportsGlyphs: Boolean,
    ) : BadgeEvent

    data class Called(
        val call: String,
        val requested: String?,
        val outcome: CallOutcome,
    ) : BadgeEvent

    data class ReadBack(
        val count: Int?,
    ) : BadgeEvent
}

object BadgeReducer : Reducer<BadgeState, BadgeEvent> {
    override fun reduce(
        state: BadgeState,
        event: BadgeEvent,
    ): BadgeState =
        when (event) {
            is BadgeEvent.Ready ->
                state.copy(
                    backend = event.backend,
                    availability = event.availability,
                    target = event.target,
                    prepared = event.prepared,
                    canReadBack = event.canReadBack,
                    supportsGlyphs = event.supportsGlyphs,
                )
            is BadgeEvent.Called ->
                state.copy(
                    // A failed call leaves the badge as it was.
                    requested = if (event.outcome.ok) event.requested else state.requested,
                    calls = state.calls.plusCall(event.call, event.outcome),
                )
            is BadgeEvent.ReadBack -> state.copy(readBack = event.count)
        }
}
