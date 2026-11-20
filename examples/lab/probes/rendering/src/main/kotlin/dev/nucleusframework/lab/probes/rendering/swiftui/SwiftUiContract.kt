package dev.nucleusframework.lab.probes.rendering.swiftui

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer

@Immutable
data class SwiftUiState(
    val availability: Availability = Availability.Unknown,
    val counter: Int = 0,
    val hue: Float = DEFAULT_HUE,
    /** `NSHostingView*` currently embedded, `null` when none. */
    val viewAddress: Long? = null,
    val created: Int = 0,
    val released: Int = 0,
    val embedded: Boolean = true,
) {
    /** Handles alive right now: must be 0 or 1, never more. */
    val live: Int get() = created - released

    companion object {
        const val DEFAULT_HUE = 0.55f
    }
}

sealed interface SwiftUiIntent {
    data object Increment : SwiftUiIntent

    data object Decrement : SwiftUiIntent

    data object Reset : SwiftUiIntent

    data class SetHue(
        val hue: Float,
    ) : SwiftUiIntent

    /** Removes the NativeView from composition and back: the release path. */
    data object ToggleEmbedded : SwiftUiIntent
}

sealed interface SwiftUiEvent {
    data class AvailabilityRead(
        val value: Availability,
    ) : SwiftUiEvent

    data class CounterChanged(
        val value: Int,
    ) : SwiftUiEvent

    data class HueChanged(
        val hue: Float,
    ) : SwiftUiEvent

    data object EmbeddedToggled : SwiftUiEvent

    data class ViewCreated(
        val viewAddress: Long,
    ) : SwiftUiEvent

    data object ViewReleased : SwiftUiEvent
}

object SwiftUiReducer : Reducer<SwiftUiState, SwiftUiEvent> {
    override fun reduce(
        state: SwiftUiState,
        event: SwiftUiEvent,
    ): SwiftUiState =
        when (event) {
            is SwiftUiEvent.AvailabilityRead -> state.copy(availability = event.value)
            is SwiftUiEvent.CounterChanged -> state.copy(counter = event.value)
            is SwiftUiEvent.HueChanged -> state.copy(hue = event.hue.coerceIn(0f, 1f))
            SwiftUiEvent.EmbeddedToggled -> state.copy(embedded = !state.embedded)
            is SwiftUiEvent.ViewCreated -> state.copy(viewAddress = event.viewAddress, created = state.created + 1)
            SwiftUiEvent.ViewReleased -> state.copy(viewAddress = null, released = state.released + 1)
        }
}
