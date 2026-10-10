package dev.nucleusframework.lab.probes.lifecycle.scheduler

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.fixture.FixtureLauncher
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SchedulerViewModel(
    private val gateway: SchedulerGateway,
    private val fixtures: FixtureLauncher,
    private val fixture: ScheduledTaskFixture,
    timeline: Timeline,
) : MviViewModel<SchedulerState, SchedulerIntent, SchedulerEvent, Nothing>(
        SchedulerState(),
        SchedulerReducer,
        timeline,
        SchedulerProbe.ID,
    ) {
    init {
        configure { it }
        // Runs happen in other processes: only a change is worth a timeline entry.
        poll(
            POLL_MS,
            read = { SchedulerEvent.Read(gateway.isAvailable, gateway.tasks(), gateway.runLog()) },
            toEvent = { it },
        )
        launch {
            fixtures.runs.collect { runs ->
                reduceSilently(SchedulerEvent.Invocations(runs.filter { it.fixtureId == fixture.id }))
            }
        }
    }

    override suspend fun handle(intent: SchedulerIntent) {
        val current = state.value
        when (intent) {
            is SchedulerIntent.Select -> configure { it.copy(task = intent.task) }
            is SchedulerIntent.SetTrigger -> configure { it.copy(trigger = intent.trigger) }
            is SchedulerIntent.EditNote -> configure(quiet = true) { it.copy(note = intent.note) }
            is SchedulerIntent.SetCharging -> configure { it.copy(requiresCharging = intent.required) }
            is SchedulerIntent.SetNetwork -> configure { it.copy(requiresNetwork = intent.required) }
            SchedulerIntent.Enqueue ->
                act("enqueue ${current.task.id} (${current.trigger.label})") {
                    gateway.enqueue(
                        EnqueueRequest(
                            current.task,
                            current.trigger,
                            current.note,
                            current.requiresCharging,
                            current.requiresNetwork,
                        ),
                    )
                }
            SchedulerIntent.Cancel -> act("cancel ${current.task.id}") { gateway.cancel(current.task.id) }
            SchedulerIntent.CancelAll -> act("cancel all") { gateway.cancelAll().let { true } }
            SchedulerIntent.DryRun -> {
                val result = runCatching { gateway.dryRun(current.task, current.note) }
                record("dry run ${current.task.id}", CallOutcome.of(result), result.getOrNull()?.toString())
            }
            SchedulerIntent.InvokeLikeOs -> {
                val variant = fixture.variants.first { it.name == current.task.id.value }
                fixtures.launch(fixture, variant)
                record("invoke like the OS: ${current.task.id}", CallOutcome.Ok)
            }
        }
    }

    private fun configure(
        quiet: Boolean = false,
        change: (SchedulerState) -> SchedulerState,
    ) {
        val next = change(state.value).let { it.copy(commandLine = gateway.commandLine(it.task.id)) }
        if (quiet) reduceSilently(SchedulerEvent.Configured(next)) else dispatch(SchedulerEvent.Configured(next))
    }

    private suspend fun act(
        description: String,
        call: () -> Boolean,
    ) {
        val outcome =
            runCatching { io(call) }.fold(
                onSuccess = { ok -> CallOutcome.of(ok) { null } },
                onFailure = { CallOutcome(false, it.summary) },
            )
        record(description, outcome)
    }

    private fun record(
        call: String,
        outcome: CallOutcome,
        returned: String? = null,
    ) {
        dispatch(
            SchedulerEvent.Called(CallRecord(System.currentTimeMillis(), call, outcome, returned)),
            if (outcome.ok) Severity.Info else Severity.Error,
        )
    }

    private companion object {
        const val POLL_MS = 2_000L
    }
}
