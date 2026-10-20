package dev.nucleusframework.lab.probes.input.gestures

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class GesturesViewModel(
    timeline: Timeline,
) : MviViewModel<GesturesState, GesturesIntent, GesturesEvent, Nothing>(
        GesturesState(),
        GesturesReducer,
        timeline,
        GesturesProbe.ID,
    ) {
    // Gesture boundaries and anomalies go to the timeline; the steps in between only to the state.

    fun onScaleStart(
        nowMs: Long,
        touchContacts: Int,
    ) {
        dispatch(
            GesturesEvent.ScaleStarted(nowMs, touchContacts),
            if (touchContacts >
                0
            ) {
                Severity.Warning
            } else {
                Severity.Info
            },
        )
    }

    fun onScaleChange(
        factor: Float,
        touchContacts: Int,
    ) {
        val event =
            GesturesEvent.ScaleChanged(
                factor,
                touchContacts,
                applies =
                    state.value.mode == GestureMode.ScaleEvents,
            )
        when {
            !factor.isFinite() || factor <= 0f -> dispatch(event, Severity.Error)
            touchContacts > 0 -> dispatch(event, Severity.Warning)
            else -> reduceSilently(event)
        }
    }

    fun onScaleEnd(nowMs: Long) = dispatch(GesturesEvent.ScaleEnded(nowMs))

    fun onTransform(
        zoom: Float,
        rotation: Float,
        pan: Offset,
    ) {
        val event = GesturesEvent.Transformed(zoom, rotation, pan)
        if (zoom.isFinite() && rotation.isFinite()) reduceSilently(event) else dispatch(event, Severity.Error)
    }

    fun onTouchContacts(count: Int) {
        if (count == state.value.touchContacts) return
        // Contacts appearing / disappearing are gesture boundaries worth a line.
        if (count == 0 ||
            state.value.touchContacts == 0
        ) {
            dispatch(GesturesEvent.TouchContacts(count))
        } else {
            reduceSilently(GesturesEvent.TouchContacts(count))
        }
    }

    override suspend fun handle(intent: GesturesIntent) {
        when (intent) {
            is GesturesIntent.SetMode -> dispatch(GesturesEvent.ModeChanged(intent.mode))
            GesturesIntent.ResetTransform -> dispatch(GesturesEvent.TransformReset)
            GesturesIntent.ResetCounters -> dispatch(GesturesEvent.CountersReset)
        }
    }
}
