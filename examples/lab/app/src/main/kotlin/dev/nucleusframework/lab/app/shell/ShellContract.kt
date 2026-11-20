package dev.nucleusframework.lab.app.shell

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.checks.CheckBook
import dev.nucleusframework.lab.core.checks.CheckStatus
import dev.nucleusframework.lab.core.environment.EnvironmentSnapshot
import dev.nucleusframework.lab.core.mvi.Reducer

enum class ThemeMode { System, Light, Dark }

enum class TimelineScope { Probe, All }

/** The shell's panes: satellites of the main window, docked or floating, each open or closed. */
enum class ShellPane { Probes, Checks, Timeline }

@Immutable
data class ShellState(
    val selected: ProbeId = OverviewProbeId,
    val paletteOpen: Boolean = false,
    /** The panes shown, docked or floating; seeded from the layout saved by the previous run. */
    val openPanes: Set<ShellPane> = ShellPane.entries.toSet(),
    val timelineScope: TimelineScope = TimelineScope.Probe,
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

    data class TogglePane(
        val pane: ShellPane,
    ) : ShellIntent

    /** Also what the workspace reports when a pane is closed from its own window or header. */
    data class SetPaneOpen(
        val pane: ShellPane,
        val open: Boolean,
    ) : ShellIntent

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

    data class PaneChanged(
        val pane: ShellPane,
        val open: Boolean,
    ) : ShellEvent

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
            is ShellEvent.PaneChanged ->
                state.copy(openPanes = if (event.open) state.openPanes + event.pane else state.openPanes - event.pane)
            is ShellEvent.TimelineScopeChanged -> state.copy(timelineScope = event.scope)
            is ShellEvent.ThemeChanged -> state.copy(theme = event.theme)
            is ShellEvent.BookChanged -> state.copy(book = event.book)
            is ShellEvent.EnvironmentChanged -> state.copy(environment = event.environment)
            is ShellEvent.ProbeReset ->
                state.copy(generations = state.generations + (event.probe to (state.generations[event.probe] ?: 0) + 1))
        }
}

val OverviewProbeId = ProbeId("lab.overview")
