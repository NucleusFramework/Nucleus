package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.checks.CheckResult
import dev.nucleusframework.lab.core.checks.CheckStatus
import dev.nucleusframework.lab.core.timeline.TimelineEntry
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Link
import dev.nucleusframework.lab.designsystem.ScrollableColumn
import dev.nucleusframework.lab.designsystem.Segmented
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.TextField
import dev.nucleusframework.lab.designsystem.TimelineView
import dev.nucleusframework.lab.designsystem.Tone

/** The checks pane's title: its progress in this environment. */
fun checksTitle(
    descriptor: ProbeDescriptor,
    state: ShellState,
): String {
    val progress = state.book.progress(state.environment?.fingerprint, descriptor)
    return if (progress.total == 0) "Checks" else "Checks ${progress.decided}/${progress.total}"
}

/** The checks pane's header actions. */
@Composable
fun ChecksActions(
    descriptor: ProbeDescriptor,
    shell: ShellViewModel,
) {
    if (descriptor.checks.isNotEmpty()) Link("Clear") { shell.onIntent(ShellIntent.ResetChecks(descriptor.id)) }
}

/** The check sheet of the selected probe, stored for the current environment. */
@Composable
fun ChecksPanel(
    descriptor: ProbeDescriptor,
    state: ShellState,
    shell: ShellViewModel,
    modifier: Modifier = Modifier,
) {
    val results =
        state.environment
            ?.fingerprint
            ?.let { state.book.results[it]?.get(descriptor.id.value) }
            .orEmpty()
    ScrollableColumn(
        modifier.fillMaxSize().background(LabTheme.colors.panel),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (descriptor.checks.isEmpty()) EmptyState("${descriptor.title} has no manual checks.")
        descriptor.checks.forEach { check ->
            CheckRow(descriptor.id, check, results[check.id] ?: CheckResult(), shell)
        }
        Text(
            "Stored for ${state.environment?.fingerprint ?: "?"}",
            style = LabTheme.typography.mono,
            color = LabTheme.colors.textMuted,
        )
    }
}

@Composable
private fun CheckRow(
    probe: ProbeId,
    check: Check,
    result: CheckResult,
    shell: ShellViewModel,
) {
    var editingNote by remember(check.id) { mutableStateOf(false) }
    var note by remember(check.id, result.note) { mutableStateOf(result.note) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(check.description)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Clicking the chosen verdict again clears it back to untested.
            Segmented(
                options = Verdicts,
                selected = result.status.takeIf { it != CheckStatus.Untested },
                name = { it.label },
                tone = { it.tone },
            ) { shell.onIntent(ShellIntent.SetCheck(probe, check.id, toggle(result.status, it))) }
            Link(if (result.note.isBlank()) "Note" else "Edit note") { editingNote = !editingNote }
        }
        if (editingNote) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(note, { note = it }, Modifier.weight(1f), placeholder = "What did you see?")
                Link("Save") {
                    shell.onIntent(ShellIntent.SetCheckNote(probe, check.id, note))
                    editingNote = false
                }
            }
        } else if (result.note.isNotBlank()) {
            Hint(result.note)
        }
    }
}

private fun toggle(
    current: CheckStatus,
    target: CheckStatus,
) = if (current == target) CheckStatus.Untested else target

private val Verdicts = listOf(CheckStatus.Pass, CheckStatus.Fail, CheckStatus.Skipped)

private val CheckStatus.label: String
    get() = if (this == CheckStatus.Skipped) "Skip" else name

private val CheckStatus.tone: Tone
    get() =
        when (this) {
            CheckStatus.Pass -> Tone.Ok
            CheckStatus.Fail -> Tone.Error
            else -> Tone.Muted
        }

private fun ShellState.shownTimeline(
    entries: List<TimelineEntry>,
    current: ProbeId,
): List<TimelineEntry> =
    when (timelineScope) {
        TimelineScope.Probe -> entries.filter { it.source == current || it.source == null }
        TimelineScope.All -> entries
    }

/** The timeline pane's title: how many entries are shown, and how many arrived off the UI thread. */
@Composable
fun timelineTitle(
    shell: ShellViewModel,
    state: ShellState,
    current: ProbeId,
): String {
    val entries by shell.timelineEntries.collectAsState()
    val shown = state.shownTimeline(entries, current)
    val offUi = shown.count { it.onUiThread == false }
    return "Timeline · ${shown.size}" + if (offUi > 0) " · $offUi off UI thread" else ""
}

/** The timeline pane's header actions: scope and clear. */
@Composable
fun TimelineActions(
    shell: ShellViewModel,
    state: ShellState,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Segmented(
            options = TimelineScope.entries,
            selected = state.timelineScope,
            name = { if (it == TimelineScope.Probe) "This probe" else "All" },
        ) { shell.onIntent(ShellIntent.SetTimelineScope(it)) }
        Link("Clear", Modifier.padding(start = 4.dp)) { shell.onIntent(ShellIntent.ClearTimeline) }
    }
}

/** Every intent, event and effect of the selected probe (or of all of them). */
@Composable
fun TimelinePanel(
    shell: ShellViewModel,
    state: ShellState,
    current: ProbeId,
    modifier: Modifier = Modifier,
) {
    val entries by shell.timelineEntries.collectAsState()
    TimelineView(
        state.shownTimeline(entries, current),
        modifier.fillMaxSize().background(LabTheme.colors.background).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        showSource = state.timelineScope == TimelineScope.All,
    )
}
