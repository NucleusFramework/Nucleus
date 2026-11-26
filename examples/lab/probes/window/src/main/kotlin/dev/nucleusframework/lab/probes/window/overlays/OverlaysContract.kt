package dev.nucleusframework.lab.probes.window.overlays

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.probes.window.shared.WindowSignal
import dev.nucleusframework.lab.probes.window.shared.WindowSnapshot
import dev.nucleusframework.window.tao.TaoWindow

enum class Corner(
    val alignment: Alignment,
) {
    TopStart(Alignment.TopStart),
    TopEnd(Alignment.TopEnd),
    BottomStart(Alignment.BottomStart),
    BottomEnd(Alignment.BottomEnd),
}

enum class Overlay { Watermark, Widget }

/** [forceX11] is creation-time: changing it reopens the overlay. */
@Immutable
data class WatermarkConfig(
    val corner: Corner = Corner.BottomEnd,
    val clickThrough: Boolean = true,
    val alwaysOnTop: Boolean = true,
    val allWorkspaces: Boolean = true,
    val forceX11: Boolean = true,
)

@Immutable
data class WidgetConfig(
    val alwaysOnBottom: Boolean = true,
    val locked: Boolean = false,
    val allWorkspaces: Boolean = true,
    val forceX11: Boolean = true,
)

@Immutable
data class OverlaysState(
    val open: Set<Overlay> = emptySet(),
    val watermark: WatermarkConfig = WatermarkConfig(),
    val widget: WidgetConfig = WidgetConfig(),
    val snapshots: Map<Overlay, WindowSnapshot> = emptyMap(),
    /** Presses that reached the watermark: must stay 0 while it is click-through. */
    val watermarkPresses: Int = 0,
    val watermarkPressesWhileClickThrough: Int = 0,
    val widgetDrags: Int = 0,
    val widgetMenuPicks: List<String> = emptyList(),
)

sealed interface OverlaysIntent {
    data class Open(
        val overlay: Overlay,
    ) : OverlaysIntent

    data class Close(
        val overlay: Overlay,
    ) : OverlaysIntent

    data class SetWatermark(
        val config: WatermarkConfig,
    ) : OverlaysIntent

    data class SetWidget(
        val config: WidgetConfig,
    ) : OverlaysIntent

    data object WatermarkPressed : OverlaysIntent

    data object WidgetDragStarted : OverlaysIntent

    data class WidgetMenuPicked(
        val label: String,
    ) : OverlaysIntent

    class Attached(
        val overlay: Overlay,
        val window: TaoWindow,
    ) : OverlaysIntent {
        override fun toString(): String = "Attached($overlay, handle=${window.handle})"
    }
}

sealed interface OverlaysEvent {
    data class OpenChanged(
        val overlay: Overlay,
        val open: Boolean,
    ) : OverlaysEvent

    data class WatermarkChanged(
        val config: WatermarkConfig,
    ) : OverlaysEvent

    data class WidgetChanged(
        val config: WidgetConfig,
    ) : OverlaysEvent

    data class Snapshot(
        val overlay: Overlay,
        val snapshot: WindowSnapshot,
    ) : OverlaysEvent {
        override fun toString(): String = "Snapshot($overlay: ${snapshot.describe()} · ${snapshot.surface})"
    }

    data object WatermarkPressed : OverlaysEvent

    data object WidgetDragStarted : OverlaysEvent

    data class WidgetMenuPicked(
        val label: String,
    ) : OverlaysEvent

    /** A native focus / minimize / destroy notification: recorded with its thread, no state of its own. */
    data class Signal(
        val overlay: Overlay,
        val signal: WindowSignal,
    ) : OverlaysEvent
}

object OverlaysReducer : Reducer<OverlaysState, OverlaysEvent> {
    private const val MENU_PICKS = 6

    override fun reduce(
        state: OverlaysState,
        event: OverlaysEvent,
    ): OverlaysState =
        when (event) {
            is OverlaysEvent.OpenChanged ->
                if (event.open) {
                    state.copy(open = state.open + event.overlay)
                } else {
                    state.copy(open = state.open - event.overlay, snapshots = state.snapshots - event.overlay)
                }
            is OverlaysEvent.WatermarkChanged -> state.copy(watermark = event.config)
            is OverlaysEvent.WidgetChanged -> state.copy(widget = event.config)
            is OverlaysEvent.Snapshot -> state.copy(snapshots = state.snapshots + (event.overlay to event.snapshot))
            OverlaysEvent.WatermarkPressed ->
                state.copy(
                    watermarkPresses = state.watermarkPresses + 1,
                    watermarkPressesWhileClickThrough =
                        state.watermarkPressesWhileClickThrough + if (state.watermark.clickThrough) 1 else 0,
                )
            OverlaysEvent.WidgetDragStarted -> state.copy(widgetDrags = state.widgetDrags + 1)
            is OverlaysEvent.WidgetMenuPicked ->
                state.copy(
                    widgetMenuPicks = state.widgetMenuPicks.append(event.label, MENU_PICKS),
                )
            is OverlaysEvent.Signal -> state
        }
}
