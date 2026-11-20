package dev.nucleusframework.lab.probes.lifecycle.watchdog

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append

@Immutable
data class Freeze(
    val startedAt: Long,
    val requestedMs: Long,
    val declaredExpected: Boolean,
    val actualMs: Long? = null,
)

@Immutable
data class SignalRecord(
    val epochMillis: Long,
    val signal: WatchdogSignal,
    val thread: String,
    /** Milliseconds since the freeze in flight (or the last one) started. */
    val sinceFreezeMs: Long?,
)

@Immutable
data class WatchdogState(
    val settings: WatchdogSettings? = null,
    val seconds: Float = 8f,
    val declaredExpected: Boolean = false,
    val freezes: List<Freeze> = emptyList(),
    val signals: List<SignalRecord> = emptyList(),
) {
    val freezing: Boolean get() = freezes.lastOrNull()?.actualMs == null && freezes.isNotEmpty()
}

sealed interface WatchdogIntent {
    data class SetSeconds(
        val seconds: Float,
    ) : WatchdogIntent

    data class SetExpected(
        val expected: Boolean,
    ) : WatchdogIntent

    data object Freeze : WatchdogIntent
}

sealed interface WatchdogEvent {
    data class SettingsRead(
        val settings: WatchdogSettings,
    ) : WatchdogEvent

    data class Configured(
        val seconds: Float,
        val declaredExpected: Boolean,
    ) : WatchdogEvent

    data class FreezeStarted(
        val freeze: Freeze,
    ) : WatchdogEvent

    data class FreezeEnded(
        val actualMs: Long,
    ) : WatchdogEvent

    data class Signalled(
        val signal: WatchdogSignal,
        val thread: String,
        val at: Long,
    ) : WatchdogEvent
}

object WatchdogReducer : Reducer<WatchdogState, WatchdogEvent> {
    override fun reduce(
        state: WatchdogState,
        event: WatchdogEvent,
    ): WatchdogState =
        when (event) {
            is WatchdogEvent.SettingsRead -> state.copy(settings = event.settings)
            is WatchdogEvent.Configured ->
                state.copy(
                    seconds = event.seconds,
                    declaredExpected = event.declaredExpected,
                )
            is WatchdogEvent.FreezeStarted -> state.copy(freezes = state.freezes.append(event.freeze, HISTORY))
            is WatchdogEvent.FreezeEnded ->
                state.copy(
                    freezes =
                        state.freezes.dropLast(1) +
                            listOfNotNull(state.freezes.lastOrNull()?.copy(actualMs = event.actualMs)),
                )
            is WatchdogEvent.Signalled -> {
                val since = state.freezes.lastOrNull()?.let { event.at - it.startedAt }
                state.copy(
                    signals = state.signals.append(SignalRecord(event.at, event.signal, event.thread, since), HISTORY),
                )
            }
        }

    private const val HISTORY = 20
}
