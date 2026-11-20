package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.nucleusframework.lab.core.format.formatTime
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery

/** One line of a probe's own log: what happened, when, how it should read, and its details. */
@Immutable
data class LogEntry(
    val text: String,
    val epochMillis: Long? = null,
    val tone: Tone = Tone.Neutral,
    /** Shown after the text, muted (a thread name, a latency, an id). */
    val detail: String? = null,
    /** Indented sub-lines (an action under a notification, a step under a call). */
    val children: List<LogEntry> = emptyList(),
)

/**
 * The single log presentation of the Lab: newest first, monospace, timestamped, toned,
 * capped to [max] lines on screen (the ViewModel keeps whatever it wants).
 */
@Composable
fun EventLog(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier,
    max: Int = 40,
    empty: String = "Nothing yet.",
    newestFirst: Boolean = true,
) {
    if (entries.isEmpty()) {
        EmptyState(empty, modifier)
        return
    }
    val shown = (if (newestFirst) entries.asReversed() else entries).take(max)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(LabDimens.lineGap)) {
            shown.forEach { LogLine(it, depth = 0) }
            if (entries.size > max) EmptyState("… ${entries.size - max} older")
        }
    }
}

@Composable
private fun LogLine(
    entry: LogEntry,
    depth: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = LabDimens.indent * depth),
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        entry.epochMillis?.let { Text(formatTime(it), style = LabTheme.typography.mono, color = Tone.Muted.color()) }
        Text(
            entry.text,
            style = LabTheme.typography.mono,
            color = entry.tone.color(),
            modifier = Modifier.weight(1f, fill = false),
        )
        entry.detail?.let {
            Text(
                it,
                style = LabTheme.typography.mono,
                color = Tone.Muted.color(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    entry.children.forEach { LogLine(it, depth + 1) }
}

/** `setCount(5)  → ok` / `→ failed: reason`. */
fun CallRecord.toLogEntry(): LogEntry =
    LogEntry(
        text =
            call + (
                returned?.let {
                    "  = $it"
                } ?: ""
            ) + if (outcome.ok) "  → ok" else "  → failed: ${outcome.error}",
        epochMillis = epochMillis,
        tone = if (outcome.ok) Tone.Ok else Tone.Error,
    )

/** What the OS sent, flagged when it arrived off the UI thread. */
fun Delivery.toLogEntry(): LogEntry =
    LogEntry(
        text = what,
        epochMillis = epochMillis,
        tone = if (onUiThread == false) Tone.Warning else Tone.Neutral,
        detail = threadNote,
    )

/** [EventLog] of API calls. */
@JvmName("CallLog")
@Composable
fun EventLog(
    calls: List<CallRecord>,
    modifier: Modifier = Modifier,
    max: Int = 40,
    empty: String = "No call made yet.",
) = EventLog(calls.map { it.toLogEntry() }, modifier, max, empty)

/** [EventLog] of what the OS sent back. */
@JvmName("DeliveryLog")
@Composable
fun EventLog(
    deliveries: List<Delivery>,
    modifier: Modifier = Modifier,
    max: Int = 40,
    empty: String = "Nothing received yet.",
) = EventLog(deliveries.map { it.toLogEntry() }, modifier, max, empty)
