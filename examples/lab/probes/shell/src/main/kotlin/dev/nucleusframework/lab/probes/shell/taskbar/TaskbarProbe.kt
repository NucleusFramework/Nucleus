package dev.nucleusframework.lab.probes.shell.taskbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class TaskbarProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Taskbar progress & attention",
            domain = Domain.Shell,
            summary =
                "Does the progress bar on the taskbar button / Dock icon track the app, and " +
                    "does attention flash or bounce?",
            modules = listOf("taskbar-progress", "taskbar-progress-tao"),
            checks =
                listOf(
                    Check(
                        "value",
                        "Moving the slider moves the bar on the taskbar button / Dock icon to the same fraction",
                    ),
                    Check("states", "Normal, Paused, Error and Indeterminate each show their own colour / animation"),
                    Check("sweep", "A 5 s sweep fills smoothly and ends in the chosen state without a stale frame"),
                    Check("hide", "Hide removes the bar; the icon looks as it did before the first call"),
                    Check(
                        "informational",
                        "Informational attention (from another app) flashes / bounces briefly, then stops by itself",
                    ),
                    Check(
                        "critical",
                        "Critical attention keeps going until the Lab is focused, then stops (Focus = true below)",
                    ),
                ),
            keywords = listOf("ITaskbarList3", "dock", "progress", "flash", "bounce", "urgent"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<TaskbarViewModel>()
        val state by vm.state.collectAsState()
        val window = LocalNucleusWindow.current
        LaunchedEffect(window) { vm.onIntent(TaskbarIntent.Attach(window)) }

        var value by rememberSaveable { mutableFloatStateOf(0.4f) }
        var endState by rememberSaveable { mutableStateOf(TaskbarProgress.State.NO_PROGRESS) }
        var delaySeconds by rememberSaveable { mutableStateOf(3) }
        val enabled = state.availability.isAvailable && state.attached

        ProbeLayout(
            capabilities = listOf(Capability("Taskbar progress", state.availability, detail = state.surface)),
            controls = {
                SliderRow("Value", value, format = { percent(it.toDouble(), decimals = 0) }) {
                    value = it
                    vm.onIntent(TaskbarIntent.SetValue(it.toDouble()))
                }
                ChoiceRow(
                    "State",
                    TaskbarProgress.State.entries,
                    state.shownState,
                ) { vm.onIntent(TaskbarIntent.SetState(it)) }
                Actions { SecondaryAction("Hide", enabled = enabled) { vm.onIntent(TaskbarIntent.Hide) } }

                SubHeading("Sweep 0 → 100 % in 5 s, then")
                ChoiceRow("End state", TaskbarProgress.State.entries, endState) { endState = it }
                Actions {
                    if (state.sweeping) {
                        SecondaryAction("Stop sweep") { vm.onIntent(TaskbarIntent.StopSweep) }
                    } else {
                        PrimaryAction("Sweep", enabled = enabled) { vm.onIntent(TaskbarIntent.Sweep(5, endState)) }
                    }
                }

                SubHeading("Attention")
                Hint("Switch to another app during the delay: attention on a focused app does nothing.")
                ChoiceRow("Delay", listOf(0, 3, 5, 10), delaySeconds, name = { "$it s" }) { delaySeconds = it }
                Actions {
                    PrimaryAction("Informational", enabled = enabled) {
                        vm.onIntent(
                            TaskbarIntent.RequestAttention(TaskbarProgress.AttentionType.INFORMATIONAL, delaySeconds),
                        )
                    }
                    PrimaryAction("Critical", enabled = enabled) {
                        vm.onIntent(
                            TaskbarIntent.RequestAttention(TaskbarProgress.AttentionType.CRITICAL, delaySeconds),
                        )
                    }
                    SecondaryAction("Stop", enabled = enabled) { vm.onIntent(TaskbarIntent.StopAttention) }
                }
            },
            observed = {
                Readout(
                    "Window",
                    if (state.attached) "attached" else "waiting for the window handle",
                    tone = if (state.attached) Tone.Neutral else Tone.Warning,
                )
                Readout(
                    "Expected bar",
                    "${state.shownState} · ${percent(state.shownValue, decimals = 0)}" +
                        if (state.sweeping) " (sweeping)" else "",
                )
                Readout("Attention", state.attention?.name ?: "none pending")
                Readout("Lab focused", state.windowFocused?.toString() ?: "no change seen yet")
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.taskbar-progress")
    }
}
