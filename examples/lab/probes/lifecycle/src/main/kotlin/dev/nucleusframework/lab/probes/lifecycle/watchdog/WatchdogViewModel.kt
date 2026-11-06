package dev.nucleusframework.lab.probes.lifecycle.watchdog

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class WatchdogViewModel(
    private val gateway: WatchdogGateway,
    timeline: Timeline,
) : MviViewModel<WatchdogState, WatchdogIntent, WatchdogEvent, Nothing>(
        WatchdogState(),
        WatchdogReducer,
        timeline,
        WatchdogProbe.ID,
    ) {
    init {
        dispatch(WatchdogEvent.SettingsRead(gateway.settings()))
        launch {
            // The callbacks run on nucleus-tao-watchdog-events by contract, so the thread is
            // carried in the event rather than flagged; the collector only runs once the UI is back.
            gateway.signals().collect { stamped ->
                dispatch(
                    WatchdogEvent.Signalled(stamped.value.signal, stamped.thread, stamped.value.epochMillis),
                    if (stamped.value.signal == WatchdogSignal.Unresponsive) Severity.Warning else Severity.Info,
                )
            }
        }
    }

    override suspend fun handle(intent: WatchdogIntent) {
        val current = state.value
        when (intent) {
            is WatchdogIntent.SetSeconds ->
                reduceSilently(
                    WatchdogEvent.Configured(intent.seconds, current.declaredExpected),
                )
            is WatchdogIntent.SetExpected -> dispatch(WatchdogEvent.Configured(current.seconds, intent.expected))
            WatchdogIntent.Freeze -> freeze(current)
        }
    }

    private suspend fun freeze(current: WatchdogState) {
        val millis = (current.seconds * MILLIS_PER_SECOND).toLong()
        dispatch(WatchdogEvent.FreezeStarted(Freeze(System.currentTimeMillis(), millis, current.declaredExpected)))
        // Let one frame show "freezing" before the loop stops.
        delay(FRAME_GRACE_MS)
        val (_, blockedMs) =
            timedMillis {
                withContext(
                    Dispatchers.Main,
                ) { gateway.block(millis, current.declaredExpected) }
            }
        dispatch(WatchdogEvent.FreezeEnded(blockedMs))
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
        const val FRAME_GRACE_MS = 150L
    }
}
