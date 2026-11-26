package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.format.formatTime
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.TimelineEntry

/** Newest last, follows the tail while at the bottom. */
@Composable
fun TimelineView(
    entries: List<TimelineEntry>,
    modifier: Modifier = Modifier,
    showSource: Boolean = true,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        val atBottom =
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
                ?.let { it >= entries.size - 3 } ?: true
        if (atBottom && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    if (entries.isEmpty()) {
        EmptyState("Nothing yet.", modifier.padding(8.dp))
        return
    }
    SelectionContainer(modifier) {
        LabLazyColumn(state = listState) {
            items(entries, key = { it.seq }) { entry -> TimelineRow(entry, showSource) }
        }
    }
}

@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    showSource: Boolean,
) {
    val tone =
        when (entry.severity) {
            Severity.Error -> Tone.Error
            Severity.Warning -> Tone.Warning
            Severity.Info -> if (entry.kind == EntryKind.Intent) Tone.Muted else Tone.Neutral
        }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(formatTime(entry.epochMillis), style = LabTheme.typography.mono, color = Tone.Muted.color())
        Text(entry.kind.glyph, style = LabTheme.typography.mono, color = tone.color(), modifier = Modifier.width(14.dp))
        if (showSource) {
            Text(
                entry.source?.value ?: "lab",
                style = LabTheme.typography.mono,
                color = LabTheme.colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
            )
        }
        Text(entry.message, style = LabTheme.typography.mono, color = tone.color(), modifier = Modifier.weight(1f))
        if (entry.onUiThread == false) {
            Text(entry.thread, style = LabTheme.typography.mono, color = Tone.Warning.color(), maxLines = 1)
        }
    }
}

private val EntryKind.glyph: String
    get() =
        when (this) {
            EntryKind.Intent -> "→"
            EntryKind.Event -> "←"
            EntryKind.Effect -> "✦"
            EntryKind.Process -> "⚙"
            EntryKind.Log -> "·"
        }
