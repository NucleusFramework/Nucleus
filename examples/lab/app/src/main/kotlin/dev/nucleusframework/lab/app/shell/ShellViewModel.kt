package dev.nucleusframework.lab.app.shell

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.app.builtin.BuiltinProbes
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.checks.CheckStatus
import dev.nucleusframework.lab.core.checks.CheckStore
import dev.nucleusframework.lab.core.checks.buildReport
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.environment.EnvironmentProvider
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.core.timeline.TimelineEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.StateFlow

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ShellViewModel(
    probes: Set<Probe>,
    private val checks: CheckStore,
    private val environment: EnvironmentProvider,
    commands: LabCommands,
    private val sessions: SessionHost,
    timeline: Timeline,
) : MviViewModel<ShellState, ShellIntent, ShellEvent, ShellEffect>(ShellState(), ShellReducer, timeline, ShellProbeId) {
    /** Sorted by domain, then title: sidebar and palette order. */
    val probes: List<Probe> =
        BuiltinProbes + probes.sortedWith(compareBy({ it.descriptor.domain.ordinal }, { it.descriptor.title }))

    val timelineEntries: StateFlow<List<TimelineEntry>> = timeline.entries

    init {
        launch { checks.book.collect { reduceSilently(ShellEvent.BookChanged(it)) } }
        launch { environment.snapshot.collect { reduceSilently(ShellEvent.EnvironmentChanged(it)) } }
        launch {
            commands.navigation.collect { id ->
                if (this.probes.any { it.descriptor.id == id }) {
                    dispatch(ShellEvent.Selected(id))
                } else {
                    timeline.record(ShellProbeId, EntryKind.Log, "Unknown probe '$id'")
                }
            }
        }
    }

    override suspend fun handle(intent: ShellIntent) {
        when (intent) {
            is ShellIntent.Select -> dispatch(ShellEvent.Selected(intent.probe))
            is ShellIntent.SetPalette -> dispatch(ShellEvent.PaletteChanged(intent.open))
            ShellIntent.ToggleTimeline -> dispatch(ShellEvent.TimelineToggled)
            ShellIntent.ToggleChecks -> dispatch(ShellEvent.ChecksToggled)
            is ShellIntent.SetTimelineScope -> dispatch(ShellEvent.TimelineScopeChanged(intent.scope))
            ShellIntent.ClearTimeline -> timeline.clear()
            ShellIntent.CycleTheme -> {
                val next = ThemeMode.entries[(state.value.theme.ordinal + 1) % ThemeMode.entries.size]
                dispatch(ShellEvent.ThemeChanged(next))
            }
            is ShellIntent.SetCheck -> checks.set(intent.probe, intent.check, intent.status)
            is ShellIntent.SetCheckNote -> {
                val current = checks.resultsFor(checks.book.value, intent.probe)[intent.check]
                checks.set(intent.probe, intent.check, current?.status ?: CheckStatus.Untested, intent.note)
            }
            is ShellIntent.ResetChecks -> checks.reset(intent.probe)
            is ShellIntent.ResetProbe -> {
                sessions.closeOwnedBy(intent.probe)
                dispatch(ShellEvent.ProbeReset(intent.probe))
            }
            is ShellIntent.CopyReport -> {
                val selected = probes.map { it.descriptor }.filter { intent.probe == null || it.id == intent.probe }
                val report =
                    buildReport(
                        environment = environment.snapshot.value,
                        probes = selected,
                        results = { checks.resultsFor(checks.book.value, it.id) },
                        timeline = timelineEntries.value,
                    )
                emit(
                    ShellEffect.CopyToClipboard(
                        report,
                        if (intent.probe ==
                            null
                        ) {
                            "full report"
                        } else {
                            "report of ${intent.probe}"
                        },
                    ),
                )
            }
            is ShellIntent.CopyDeepLink ->
                emit(ShellEffect.CopyToClipboard(LabCommands.deepLink(intent.probe), "deep link"))
        }
    }

    fun refreshEnvironment() = environment.refresh()
}

val ShellProbeId = ProbeId("lab.shell")
