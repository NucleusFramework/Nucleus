package dev.nucleusframework.lab.probes.shell.taskbar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class TaskbarViewModel(
    private val gateway: TaskbarGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<TaskbarState, TaskbarIntent, TaskbarEvent, Nothing>(
        TaskbarState(),
        TaskbarReducer,
        timeline,
        TaskbarProbe.ID,
    ) {
    private var window: NucleusWindow? = null
    private var sweep: Job? = null

    init {
        dispatch(TaskbarEvent.Ready(gateway.availability(), gateway.surface()))
        // nucleus-lab://probe/shell.taskbar-progress?value=0.4
        onParams(commands) { params ->
            params.double("value")?.let { onIntent(TaskbarIntent.SetValue(it.coerceIn(0.0, 1.0))) }
        }
    }

    override suspend fun handle(intent: TaskbarIntent) {
        if (intent is TaskbarIntent.Attach) {
            attach(intent.window)
            return
        }
        val window = window ?: return
        when (intent) {
            is TaskbarIntent.Attach -> Unit
            is TaskbarIntent.SetState -> applyState(window, intent.state)
            is TaskbarIntent.SetValue -> {
                if (state.value.shownState == TaskbarProgress.State.NO_PROGRESS) {
                    applyState(window, TaskbarProgress.State.NORMAL)
                }
                applyValue(window, intent.value)
            }
            TaskbarIntent.Hide -> {
                sweep?.cancel()
                val outcome = gateway.hide(window)
                dispatch(TaskbarEvent.StateApplied(TaskbarProgress.State.NO_PROGRESS, outcome), severity(outcome.ok))
            }
            is TaskbarIntent.Sweep -> startSweep(window, intent)
            TaskbarIntent.StopSweep -> sweep?.cancel()
            is TaskbarIntent.RequestAttention -> {
                // The delay lets the tester switch to another app first: attention on a focused app is a no-op.
                delay(intent.delaySeconds * 1_000L)
                val outcome = gateway.requestAttention(window, intent.type)
                dispatch(TaskbarEvent.AttentionApplied(intent.type, outcome), severity(outcome.ok))
            }
            TaskbarIntent.StopAttention -> {
                val outcome = gateway.stopAttention(window)
                dispatch(TaskbarEvent.AttentionApplied(null, outcome), severity(outcome.ok))
            }
        }
    }

    private fun attach(window: NucleusWindow) {
        if (this.window === window) return
        this.window = window
        dispatch(TaskbarEvent.Attached)
        launch { window.focusFlow.drop(1).collect { dispatch(TaskbarEvent.FocusChanged(it)) } }
    }

    private fun startSweep(
        window: NucleusWindow,
        intent: TaskbarIntent.Sweep,
    ) {
        sweep?.cancel()
        sweep =
            viewModelScope.launch {
                dispatch(TaskbarEvent.SweepChanged(true))
                try {
                    applyState(window, TaskbarProgress.State.NORMAL)
                    val steps = intent.seconds * STEPS_PER_SECOND
                    for (i in 0..steps) {
                        applyValue(window, i.toDouble() / steps)
                        delay(1_000L / STEPS_PER_SECOND)
                    }
                    applyState(window, intent.endState)
                } finally {
                    dispatch(TaskbarEvent.SweepChanged(false))
                }
            }
    }

    private fun applyState(
        window: NucleusWindow,
        target: TaskbarProgress.State,
    ) {
        val outcome = gateway.setState(window, target)
        dispatch(TaskbarEvent.StateApplied(target, outcome), severity(outcome.ok))
    }

    private fun applyValue(
        window: NucleusWindow,
        value: Double,
    ) {
        val outcome = gateway.setProgress(window, value)
        // Sweep steps are not worth a timeline line each; failures always are.
        if (state.value.sweeping && outcome.ok) {
            reduceSilently(TaskbarEvent.ValueApplied(value, outcome))
        } else {
            dispatch(TaskbarEvent.ValueApplied(value, outcome), severity(outcome.ok))
        }
    }

    private fun severity(ok: Boolean) = if (ok) Severity.Info else Severity.Error

    private companion object {
        const val STEPS_PER_SECOND = 20
    }
}
