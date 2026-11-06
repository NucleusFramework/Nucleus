package dev.nucleusframework.lab.probes.workspace.satellites

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.probes.workspace.common.Declared
import dev.nucleusframework.lab.probes.workspace.common.SatelliteChange
import dev.nucleusframework.lab.probes.workspace.common.SatellitesObservation
import dev.nucleusframework.lab.probes.workspace.common.SessionReducer
import dev.nucleusframework.lab.probes.workspace.common.SessionState
import dev.nucleusframework.window.tao.DockSide

enum class DocumentId(
    val title: String,
) {
    A("Document A"),
    B("Document B"),
}

/** Which sides the Inspector is declared for (`Satellite(dockSides)`). */
enum class InspectorSides(
    val label: String,
    val sides: Set<DockSide>,
) {
    All("all four", DockSide.entries.toSet()),
    Vertical("left / right", setOf(DockSide.Left, DockSide.Right)),
    FloatingOnly("none (floating only)", emptySet()),
}

/** Declaration-time knobs: `SatelliteWorkspace(followFocus)` and `Satellite(...)` parameters. */
@Immutable
data class SatellitesOptions(
    val followFocus: Boolean = true,
    val inspectorSides: InspectorSides = InspectorSides.All,
    /** `Satellite(floatable = false)`: Tools becomes fixed furniture, docked left. */
    val toolsFloatable: Boolean = true,
    val toolsReorderable: Boolean = true,
    /** `Satellite(resizable)`: whether the floating Inspector can be resized. */
    val inspectorResizable: Boolean = true,
)

/** Knobs applied to the open session as they change. */
@Immutable
data class SatellitesLive(
    val secondDocument: Boolean = true,
    val showAll: Boolean = true,
    val hideWhileOwnerFills: Boolean = false,
    val anchor: AnchorPreset = AnchorPreset.RightEdge,
    val adjustment: AdjustmentPreset = AdjustmentPreset.FlipAndSlide,
    val gapDp: Float = 12f,
)

@Immutable
data class SatellitesData(
    override val options: SatellitesOptions = SatellitesOptions(),
    override val appliedOptions: SatellitesOptions? = null,
    val observation: SatellitesObservation = SatellitesObservation(),
    val savedLayoutJson: String? = null,
    val changes: Int = 0,
    val lastChange: String? = null,
) : Declared<SatellitesOptions>

typealias SatellitesState = SessionState<SatellitesLive, SatellitesData>

sealed interface SatellitesIntent {
    data class SetOptions(
        val options: SatellitesOptions,
    ) : SatellitesIntent

    data class Toggle(
        val id: String,
    ) : SatellitesIntent

    data class Dock(
        val id: String,
        val side: DockSide,
    ) : SatellitesIntent

    data class Undock(
        val id: String,
    ) : SatellitesIntent

    /** `null` lets focus decide again. */
    data class Pin(
        val document: DocumentId?,
    ) : SatellitesIntent

    data object Reanchor : SatellitesIntent

    data object SaveLayout : SatellitesIntent

    data object RestoreLayout : SatellitesIntent
}

sealed interface SatellitesEvent {
    data class OptionsChanged(
        val options: SatellitesOptions,
    ) : SatellitesEvent

    data class Observed(
        val observation: SatellitesObservation,
    ) : SatellitesEvent

    data class Changed(
        val change: SatelliteChange,
    ) : SatellitesEvent

    data class LayoutSaved(
        val json: String,
    ) : SatellitesEvent {
        override fun toString(): String = "LayoutSaved(${json.length} chars)"
    }

    data object LayoutRestored : SatellitesEvent
}

object SatellitesReducer : SessionReducer<SatellitesLive, SatellitesData, SatellitesEvent>() {
    override fun opened(data: SatellitesData) =
        data.copy(appliedOptions = data.options, observation = SatellitesObservation())

    override fun ended(data: SatellitesData) = data.copy(appliedOptions = null, observation = SatellitesObservation())

    override fun reduceProbe(
        state: SatellitesState,
        event: SatellitesEvent,
    ): SatellitesState {
        val data = state.data
        return when (event) {
            is SatellitesEvent.OptionsChanged -> state.copy(data = data.copy(options = event.options))
            is SatellitesEvent.Observed -> state.copy(data = data.copy(observation = event.observation))
            is SatellitesEvent.Changed ->
                state.copy(data = data.copy(changes = data.changes + 1, lastChange = event.change.toString()))
            is SatellitesEvent.LayoutSaved -> state.copy(data = data.copy(savedLayoutJson = event.json), notice = null)
            SatellitesEvent.LayoutRestored -> state.copy(notice = null)
        }
    }
}
