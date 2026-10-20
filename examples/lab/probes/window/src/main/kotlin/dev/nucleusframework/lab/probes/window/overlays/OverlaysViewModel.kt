package dev.nucleusframework.lab.probes.window.overlays

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.window.shared.WindowFollow
import dev.nucleusframework.lab.probes.window.shared.WindowObserver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class OverlaysViewModel(
    observer: WindowObserver,
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<OverlaysState, OverlaysIntent, OverlaysEvent, Nothing>(
        OverlaysState(),
        OverlaysReducer,
        timeline,
        OverlaysProbe.ID,
    ) {
    /** Window states the overlays are placed by; corner changes go through `position`. */
    val watermarkState =
        WindowState(position = WindowPosition.Aligned(Corner.BottomEnd.alignment), size = DpSize(360.dp, 140.dp))
    val widgetState =
        WindowState(position = WindowPosition.Aligned(Corner.TopEnd.alignment), size = DpSize(280.dp, 300.dp))

    private val sessions = sessions(host)
    private val follow = WindowFollow(viewModelScope, observer)

    init {
        Overlay.entries.forEach { overlay ->
            launch { sessions.isOpen(overlay.sessionName).collect { dispatch(OverlaysEvent.OpenChanged(overlay, it)) } }
        }
    }

    override suspend fun handle(intent: OverlaysIntent) {
        when (intent) {
            is OverlaysIntent.Open ->
                sessions.open(intent.overlay.name, intent.overlay.sessionName) { close ->
                    when (intent.overlay) {
                        Overlay.Watermark -> WatermarkWindow(this@OverlaysViewModel, close)
                        Overlay.Widget -> WidgetWindow(this@OverlaysViewModel, close)
                    }
                }
            is OverlaysIntent.Close -> sessions.close(intent.overlay.sessionName)
            is OverlaysIntent.SetWatermark -> {
                if (intent.config.corner != state.value.watermark.corner) {
                    watermarkState.position = WindowPosition.Aligned(intent.config.corner.alignment)
                }
                dispatch(OverlaysEvent.WatermarkChanged(intent.config))
            }
            is OverlaysIntent.SetWidget -> dispatch(OverlaysEvent.WidgetChanged(intent.config))
            OverlaysIntent.WatermarkPressed -> dispatch(OverlaysEvent.WatermarkPressed)
            OverlaysIntent.WidgetDragStarted -> dispatch(OverlaysEvent.WidgetDragStarted)
            is OverlaysIntent.WidgetMenuPicked -> dispatch(OverlaysEvent.WidgetMenuPicked(intent.label))
            is OverlaysIntent.Attached -> {
                val overlay = intent.overlay
                follow.follow(
                    intent.window,
                    key = overlay,
                    // The settled snapshot carries geometry; log only the state flips.
                    onSignal = { if (!it.value.isGeometry) dispatch(it.map { s -> OverlaysEvent.Signal(overlay, s) }) },
                    onSettled = { dispatch(OverlaysEvent.Snapshot(overlay, it)) },
                )
            }
        }
    }
}

private val Overlay.sessionName: String get() = name.lowercase()
