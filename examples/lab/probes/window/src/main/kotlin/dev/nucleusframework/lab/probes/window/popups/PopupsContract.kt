package dev.nucleusframework.lab.probes.window.popups

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntRect
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.probes.window.shared.WindowSnapshot
import dev.nucleusframework.lab.probes.window.shared.describe
import dev.nucleusframework.lab.probes.window.state.PositionTarget
import dev.nucleusframework.window.tao.TaoWindow

/** Both flags are read when the window is created: changing one reopens it. */
@Immutable
data class PopupConfig(
    val nativePopupLayers: Boolean = true,
    val nativeContextMenu: Boolean = true,
)

/** Where a popup ended up, measured from inside it. */
@Immutable
data class PopupReport(
    val label: String,
    /** Bounds in its host's pixels. */
    val boundsInHostPx: IntRect,
    /** `true` when the popup got a native window of its own (native popup layers). */
    val ownWindow: Boolean,
    /** The popup on screen, when its host window can tell. */
    val screenPx: IntRect?,
    /** Whether [screenPx] lies inside the owner's monitor work area; `null` when unknown. */
    val fitsWorkArea: Boolean?,
) {
    fun describe(): String =
        buildString {
            append(if (ownWindow) "own window" else "drawn in the owner")
            append(" · ")
            append(screenPx?.let { "screen ${it.describe()}" } ?: "in host ${boundsInHostPx.describe()}")
            fitsWorkArea?.let { append(if (it) " · inside the work area" else " · CROSSES the work area edge") }
        }
}

@Immutable
data class PopupsState(
    val sessionOpen: Boolean = false,
    val config: PopupConfig = PopupConfig(),
    val snapshot: WindowSnapshot? = null,
    val popups: Map<String, PopupReport> = emptyMap(),
    val picks: List<String> = emptyList(),
)

sealed interface PopupsIntent {
    data object Open : PopupsIntent

    data object Close : PopupsIntent

    data class SetConfig(
        val config: PopupConfig,
    ) : PopupsIntent

    data class Park(
        val target: PositionTarget,
    ) : PopupsIntent

    data class Measured(
        val report: PopupReport,
    ) : PopupsIntent

    data class Picked(
        val label: String,
    ) : PopupsIntent

    class Attached(
        val window: TaoWindow,
    ) : PopupsIntent {
        override fun toString(): String = "Attached(handle=${window.handle})"
    }
}

sealed interface PopupsEvent {
    data class SessionChanged(
        val open: Boolean,
    ) : PopupsEvent

    data class ConfigChanged(
        val config: PopupConfig,
    ) : PopupsEvent

    data class Snapshot(
        val snapshot: WindowSnapshot,
    ) : PopupsEvent {
        override fun toString(): String = "Snapshot(${snapshot.describe()})"
    }

    data class Measured(
        val report: PopupReport,
    ) : PopupsEvent {
        override fun toString(): String = "Popup '${report.label}': ${report.describe()}"
    }

    data class Picked(
        val label: String,
    ) : PopupsEvent
}

object PopupsReducer : Reducer<PopupsState, PopupsEvent> {
    private const val PICKS = 8

    override fun reduce(
        state: PopupsState,
        event: PopupsEvent,
    ): PopupsState =
        when (event) {
            is PopupsEvent.SessionChanged ->
                if (event.open) state.copy(sessionOpen = true) else state.copy(sessionOpen = false, snapshot = null)
            // New native windows, new placements: old measurements say nothing about them.
            is PopupsEvent.ConfigChanged -> state.copy(config = event.config, popups = emptyMap())
            is PopupsEvent.Snapshot -> state.copy(snapshot = event.snapshot)
            is PopupsEvent.Measured -> state.copy(popups = state.popups + (event.report.label to event.report))
            is PopupsEvent.Picked -> state.copy(picks = state.picks.append(event.label, PICKS))
        }
}

/** Pure: does [rect] lie inside [work]? */
fun IntRect.within(work: IntRect): Boolean =
    left >= work.left && top >= work.top && right <= work.right && bottom <= work.bottom
