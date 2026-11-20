package dev.nucleusframework.lab.probes.fixtures.runner

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.core.format.formatTime
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SelectableRow
import dev.nucleusframework.lab.designsystem.StatusDot
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.color

/** Lines of output shown; the launcher itself keeps the last 400. */
private const val TAIL_LINES = 200

internal val RunOutcome.tone: Tone
    get() =
        when (this) {
            RunOutcome.Running -> Tone.Neutral
            RunOutcome.Passed -> Tone.Ok
            is RunOutcome.Stopped -> Tone.Warning
            is RunOutcome.Failed -> Tone.Error
        }

/** Readouts of the focused run: what was launched and how it ended. */
@Composable
internal fun RunDetails(
    run: FixtureRun,
    outcome: RunOutcome,
    now: Long,
) {
    Readout("run", "#${run.runId} ${run.fixtureId} [${run.variant}]")
    Readout("pid", run.pid?.toString())
    Readout("started", formatTime(run.startedAt))
    Readout("duration", formatDurationMillis(run.durationMillis(now)))
    Readout("exit", outcome.label(), tone = outcome.tone)
    Readout("command", run.command.joinToString(" "), tone = Tone.Muted)
}

/** The tail of the run's output, kept scrolled to the end while it grows. */
@Composable
internal fun RunOutput(run: FixtureRun) {
    CodeBlock(run.output.takeLast(TAIL_LINES).joinToString("\n"), followTail = true, empty = "(no output yet)")
}

/** Every run, newest first; a click shows its output. */
@Composable
internal fun RunHistory(
    state: FixturesState,
    onSelect: (Int) -> Unit,
) {
    if (state.runs.isEmpty()) {
        EmptyState("No run yet.")
        return
    }
    Column {
        state.runs.asReversed().forEach { run ->
            val outcome = state.outcomeOf(run)
            SelectableRow(selected = run.runId == state.focusedRun?.runId, onClick = { onSelect(run.runId) }) {
                StatusDot(outcome.tone)
                Text(
                    "#${run.runId} ${formatTime(run.startedAt)}  ${run.fixtureId} [${run.variant}]",
                    style = LabTheme.typography.small,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${outcome.label()} · ${formatDurationMillis(run.durationMillis(state.now))}",
                    style = LabTheme.typography.mono,
                    color = outcome.tone.color(),
                )
            }
        }
    }
}
