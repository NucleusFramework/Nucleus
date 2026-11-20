package dev.nucleusframework.lab.probes.system.appearance

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.format.argbHex
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusDelivery

@Immutable
data class AppearanceState(
    val darkModeDetector: Availability = Availability.Unknown,
    val accentSupported: Availability = Availability.Unknown,
    /** Polled read, `isDark()`. */
    val darkPolled: Boolean? = null,
    /** Last value pushed by the listener. */
    val darkPushed: Boolean? = null,
    val accentArgb: Long? = null,
    val highContrast: Boolean? = null,
    /** Every observed change, oldest first, with the thread it arrived on. */
    val history: List<Delivery> = emptyList(),
)

sealed interface AppearanceIntent {
    data object Poll : AppearanceIntent

    /** The composable readers (`systemAccentColor()`, `isSystemInHighContrast()`) changed. */
    data class ComposableReport(
        val accentArgb: Long?,
        val highContrast: Boolean,
    ) : AppearanceIntent

    data object ClearHistory : AppearanceIntent
}

sealed interface AppearanceEvent {
    data class Capabilities(
        val darkModeDetector: Availability,
        val accent: Availability,
    ) : AppearanceEvent

    data class Polled(
        val dark: Boolean,
    ) : AppearanceEvent

    /** The listener's push; [delivery] keeps the thread it arrived on. */
    data class DarkPushed(
        val dark: Boolean,
        val delivery: Delivery,
    ) : AppearanceEvent {
        // The timeline already records the thread: keep its line short.
        override fun toString(): String = "DarkPushed(dark=$dark)"
    }

    data class AccentChanged(
        val argb: Long?,
        val at: Long,
    ) : AppearanceEvent

    data class ContrastChanged(
        val highContrast: Boolean,
        val at: Long,
    ) : AppearanceEvent

    data object HistoryCleared : AppearanceEvent
}

object AppearanceReducer : Reducer<AppearanceState, AppearanceEvent> {
    override fun reduce(
        state: AppearanceState,
        event: AppearanceEvent,
    ): AppearanceState =
        when (event) {
            is AppearanceEvent.Capabilities ->
                state.copy(darkModeDetector = event.darkModeDetector, accentSupported = event.accent)
            is AppearanceEvent.Polled -> state.copy(darkPolled = event.dark)
            is AppearanceEvent.DarkPushed -> state.copy(darkPushed = event.dark).log(event.delivery)
            is AppearanceEvent.AccentChanged ->
                if (state.accentArgb == event.argb) {
                    state
                } else {
                    state
                        .copy(accentArgb = event.argb)
                        .log(composed(event.at, "accent=${event.argb?.argbHex() ?: "none"}"))
                }
            is AppearanceEvent.ContrastChanged ->
                if (state.highContrast == event.highContrast) {
                    state
                } else {
                    state
                        .copy(highContrast = event.highContrast)
                        .log(composed(event.at, "highContrast=${event.highContrast}"))
                }
            AppearanceEvent.HistoryCleared -> state.copy(history = emptyList())
        }

    private fun AppearanceState.log(change: Delivery) = copy(history = history.plusDelivery(change))

    /** A value read by a composable reader: it arrives with the composition, so no thread is judged. */
    private fun composed(
        at: Long,
        what: String,
    ) = Delivery(at, what, thread = "composition", onUiThread = null)
}
