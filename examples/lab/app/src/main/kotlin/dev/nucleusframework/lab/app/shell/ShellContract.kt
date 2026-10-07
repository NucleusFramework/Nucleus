package dev.nucleusframework.lab.app.shell

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.checks.CheckBook
import dev.nucleusframework.lab.core.checks.CheckStatus
import dev.nucleusframework.lab.core.environment.EnvironmentSnapshot
import dev.nucleusframework.lab.core.mvi.Reducer

enum class ThemeMode { System, Light, Dark }

enum class TimelineScope { Probe, All }

@Immutable
data class ShellState(
    val selected: ProbeId = OverviewProbeId,
    val paletteOpen: Boolean = false,
    val timelineOpen: Boolean = true,
    val timelineScope: TimelineScope = TimelineScope.Probe,
    val checksOpen: Boolean = true,
    val theme: ThemeMode = ThemeMode.System,
    val book: CheckBook = CheckBook(),
    val environment: EnvironmentSnapshot? = null,
    /** Bumped per probe to rebuild it from scratch (fresh ViewModel, fresh remembered state). */
    val generations: Map<ProbeId, Int> = emptyMap(),
)

sealed interface ShellIntent {
    data class Select(
        val probe: ProbeId,
    ) : ShellIntent

    data class SetPalette(
        val open: Boolean,
    ) : ShellIntent

    data object ToggleTimeline : ShellIntent

    data object ToggleChecks : ShellIntent

    data class SetTimelineScope(
        val scope: TimelineScope,
    ) : ShellIntent

    data object ClearTimeline : ShellIntent

    data object CycleTheme : ShellIntent

    data class SetCheck(
        val probe: ProbeId,
        val check: String,
        val status: CheckStatus,
    ) : ShellIntent

    data class SetCheckNote(
        val probe: ProbeId,
        val check: String,
        val note: String,
    ) : ShellIntent

    data class ResetChecks(
        val probe: ProbeId,
    ) : ShellIntent

    data class ResetProbe(
        val probe: ProbeId,
    ) : ShellIntent

    /** `null` = every probe. */
    data class CopyReport(
        val probe: ProbeId?,
    ) : ShellIntent

    data class CopyDeepLink(
        val probe: ProbeId,
    ) : ShellIntent
}

sealed interface ShellEvent {
    data class Selected(
        val probe: ProbeId,
    ) : ShellEvent

    data class PaletteChanged(
        val open: Boolean,
    ) : ShellEvent

    data object TimelineToggled : ShellEvent

    data object ChecksToggled : ShellEvent

    data class TimelineScopeChanged(
        val scope: TimelineScope,
    ) : ShellEvent

    data class ThemeChanged(
        val theme: ThemeMode,
    ) : ShellEvent

    data class BookChanged(
        val book: CheckBook,
    ) : ShellEvent {
        override fun toString(): String = "BookChanged"
    }

    data class EnvironmentChanged(
        val environment: EnvironmentSnapshot,
    ) : ShellEvent {
        override fun toString(): String = "EnvironmentChanged(${environment.fingerprint})"
    }

    data class ProbeReset(
        val probe: ProbeId,
    ) : ShellEvent
}

sealed interface ShellEffect {
    data class CopyToClipboard(
        val text: String,
        val what: String,
    ) : ShellEffect {
        override fun toString(): String = "CopyToClipboard($what, ${text.length} chars)"
    }
}

object ShellReducer : Reducer<ShellState, ShellEvent> {
    override fun reduce(
        state: ShellState,
        event: ShellEvent,
    ): ShellState =
        when (event) {
            is ShellEvent.Selected -> state.copy(selected = event.probe, paletteOpen = false)
            is ShellEvent.PaletteChanged -> state.copy(paletteOpen = event.open)
            ShellEvent.TimelineToggled -> state.copy(timelineOpen = !state.timelineOpen)
            ShellEvent.ChecksToggled -> state.copy(checksOpen = !state.checksOpen)
            is ShellEvent.TimelineScopeChanged -> state.copy(timelineScope = event.scope)
            is ShellEvent.ThemeChanged -> state.copy(theme = event.theme)
            is ShellEvent.BookChanged -> state.copy(book = event.book)
            is ShellEvent.EnvironmentChanged -> state.copy(environment = event.environment)
            is ShellEvent.ProbeReset ->
                state.copy(generations = state.generations + (event.probe to (state.generations[event.probe] ?: 0) + 1))
        }
}

val OverviewProbeId = ProbeId("lab.overview")
