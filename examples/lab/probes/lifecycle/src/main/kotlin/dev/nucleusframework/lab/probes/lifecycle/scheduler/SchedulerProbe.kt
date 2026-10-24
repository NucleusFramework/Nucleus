package dev.nucleusframework.lab.probes.lifecycle.scheduler

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
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.format.formatTime
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SchedulerProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Scheduled tasks",
            domain = Domain.Lifecycle,
            summary = "Does a task registered with the OS scheduler start this app later, run, and record its result?",
            modules = listOf("scheduler", "scheduler-testing"),
            checks =
                listOf(
                    Check(
                        "enqueue",
                        "Enqueue a periodic heartbeat: it is listed SCHEDULED and visible in Task Scheduler / " +
                            "launchctl list / systemctl --user list-timers",
                    ),
                    Check(
                        "run-now",
                        "runImmediately: a heartbeat line appears in the run log within a minute, runCount goes to 1",
                    ),
                    Check("input", "The run log shows the note typed here: the input data survived the OS round trip"),
                    Check("like-os", "Invoke like the OS: the child exits 0 and the task's lastResult updates"),
                    Check("flaky", "lab-flaky invoked twice: Retry then Success in lastResult"),
                    Check("cancel", "Cancel removes the task here and from the OS scheduler"),
                    Check("closed", "With the Lab closed, a calendar task 2 min ahead still appends to the run log"),
                ),
            keywords = listOf("cron", "launchd", "systemd timer", "Task Scheduler", "background"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SchedulerViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "OS scheduler",
                        when (state.available) {
                            null -> Availability.Unknown
                            true -> Availability.Available
                            false ->
                                Availability.Unavailable(
                                    "no platform scheduler (sandboxed, or Task Scheduler COM missing)",
                                )
                        },
                    ),
                ),
            controls = {
                ChoiceRow("Task", LabTask.entries, state.task, name = { "${it.id} (${it.behaviour})" }) {
                    vm.onIntent(SchedulerIntent.Select(it))
                }
                ChoiceRow("Trigger", Trigger.entries, state.trigger, name = { it.label }) {
                    vm.onIntent(SchedulerIntent.SetTrigger(it))
                }
                TextFieldRow("Note (input data)", state.note) { vm.onIntent(SchedulerIntent.EditNote(it)) }
                SwitchRow("Requires charging", state.requiresCharging) { vm.onIntent(SchedulerIntent.SetCharging(it)) }
                SwitchRow("Requires network", state.requiresNetwork) { vm.onIntent(SchedulerIntent.SetNetwork(it)) }
                Actions {
                    PrimaryAction("Enqueue") { vm.onIntent(SchedulerIntent.Enqueue) }
                    SecondaryAction("Cancel") { vm.onIntent(SchedulerIntent.Cancel) }
                    SecondaryAction("Cancel all") { vm.onIntent(SchedulerIntent.CancelAll) }
                }
                Actions {
                    SecondaryAction("Invoke like the OS") { vm.onIntent(SchedulerIntent.InvokeLikeOs) }
                    SecondaryAction("Dry run in process") { vm.onIntent(SchedulerIntent.DryRun) }
                }
                SubHeading("The OS will run")
                CodeBlock(state.commandLine.joinToString(" ").take(COMMAND_PREVIEW))
            },
            observed = {
                SubHeading("Registered with the OS")
                if (state.tasks.isEmpty()) EmptyState("None.")
                state.tasks.forEach { info ->
                    Readout(
                        info.taskId.value,
                        "${info.state} · runs=${info.runCount} · last=${info.lastRunMs.clock()} · " +
                            "next=${info.nextRunMs.clock()} · ${info.lastResult ?: "no result"}",
                    )
                }
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, max = CALLS_SHOWN)
                if (state.invocations.isNotEmpty()) {
                    SubHeading("OS-like invocations")
                    EventLog(state.invocations.map { it.toLogEntry() }, max = INVOCATIONS_SHOWN)
                }
                SubHeading("Run log (any process)")
                CodeBlock(state.runLog.asReversed().joinToString("\n"), empty = "No task has run yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.scheduler")
        private const val COMMAND_PREVIEW = 600
        private const val CALLS_SHOWN = 8
        private const val INVOCATIONS_SHOWN = 4
    }
}

private fun FixtureRun.toLogEntry(): LogEntry =
    LogEntry(
        text = "#$runId $variant: " + (exitCode?.let { "exited $it" } ?: "running"),
        epochMillis = startedAt,
        tone = if (exitCode == 0) Tone.Ok else Tone.Warning,
    )

private fun Long?.clock(): String = this?.let(::formatTime) ?: "—"
