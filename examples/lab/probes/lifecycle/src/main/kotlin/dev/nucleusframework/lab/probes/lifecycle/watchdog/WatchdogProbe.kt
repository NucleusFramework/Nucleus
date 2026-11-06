package dev.nucleusframework.lab.probes.lifecycle.watchdog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class WatchdogProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Hang watchdog",
            domain = Domain.Lifecycle,
            summary =
                "When the UI thread stalls, does the watchdog report it — and stay quiet when the stall was " +
                    "declared?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "unresponsive",
                        "Windows: an undeclared 8 s freeze fires Unresponsive about 5 s + grace after it started, " +
                            "on nucleus-tao-watchdog-events",
                    ),
                    Check("responsive", "Responsive follows as soon as the freeze ends"),
                    Check("severe", "The console shows one SEVERE line with a thread dump for the undeclared freeze"),
                    Check("expected", "The same freeze inside expectUnresponsive fires nothing and logs nothing"),
                    Check("short", "A 2 s freeze fires nothing (below Windows' own hang threshold)"),
                    Check(
                        "other-os",
                        "macOS/Linux: the freeze is felt (window stops repainting) but no signal fires — detection " +
                            "is Windows-only",
                    ),
                ),
            keywords = listOf("freeze", "hang", "not responding", "IsHungAppWindow", "expectUnresponsive"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WatchdogViewModel>()
        val state by vm.state.collectAsState()
        val settings = state.settings

        ProbeLayout(
            capabilities =
                listOfNotNull(
                    settings?.let {
                        Capability(
                            "Hang detection",
                            Availability.of(it.detectsOnThisOs) {
                                "Windows only: no non-perturbing hang probe on this OS"
                            },
                        )
                    },
                    settings?.let {
                        Capability(
                            "Watchdog running",
                            Availability.of(it.effectivelyOn) {
                                when {
                                    !it.detectsOnThisOs -> "nothing to watch on this OS"
                                    it.switch == "false" -> "-Dnucleus.tao.watchdog=false"
                                    else -> "debugger attached: pass -Dnucleus.tao.watchdog=true to force it"
                                }
                            },
                        )
                    },
                ),
            controls = {
                SliderRow(
                    "Freeze for",
                    state.seconds,
                    valueRange = 1f..20f,
                    steps = 18,
                    format = { "${it.fmt(0)} s" },
                ) {
                    vm.onIntent(WatchdogIntent.SetSeconds(it))
                }
                SwitchRow(
                    "Declare it (expectUnresponsive)",
                    state.declaredExpected,
                ) { vm.onIntent(WatchdogIntent.SetExpected(it)) }
                Actions {
                    PrimaryAction(
                        if (state.freezing) "Freezing…" else "Freeze the UI thread",
                        enabled = !state.freezing,
                    ) {
                        vm.onIntent(WatchdogIntent.Freeze)
                    }
                }
                Hint("The whole Lab stops responding for the duration: that is the test.")
            },
            observed = {
                settings?.let {
                    Readout("nucleus.tao.watchdog", it.switch ?: "unset (default)")
                    Readout("watchdogGraceMs", it.graceMs ?: "unset (default)")
                    Readout("watchdogDialog", it.dialog ?: "unset (off)")
                    Readout(
                        "Debugger attached",
                        it.debuggerAttached.toString(),
                        tone = if (it.debuggerAttached) Tone.Warning else Tone.Neutral,
                    )
                }
                SubHeading("Freezes")
                EventLog(state.freezes.map { it.toLogEntry() })
                SubHeading("Signals")
                EventLog(state.signals.map { it.toLogEntry() }, empty = "None received.")
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.watchdog")
    }
}

private fun Freeze.toLogEntry(): LogEntry =
    LogEntry(
        text =
            "$requestedMs ms${if (declaredExpected) " (declared)" else ""} → " +
                (actualMs?.let { "blocked $it ms" } ?: "in progress"),
        epochMillis = startedAt,
    )

private fun SignalRecord.toLogEntry(): LogEntry =
    LogEntry(
        text = "$signal${sinceFreezeMs?.let { " · $it ms after freeze start" }.orEmpty()}",
        epochMillis = epochMillis,
        tone = if (signal == WatchdogSignal.Unresponsive) Tone.Warning else Tone.Ok,
        detail = "on $thread",
    )
