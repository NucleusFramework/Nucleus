package dev.nucleusframework.lab.probes.system.fswatcher

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.Tone

/** Each action with how many events it drew and how fast the first one came. */
@Composable
fun ActionList(
    actions: List<ActionRecord>,
    watching: Boolean,
) {
    EventLog(actions.map { it.toLogEntry(watching) }, max = MAX_ACTIONS, empty = "No action yet.")
}

/** `Created  nested/deep/leaf.txt [dir]  #3 +12 ms  (thread)`. */
@Composable
fun EventList(events: List<ObservedEvent>) {
    EventLog(events.map { it.toLogEntry() }, max = MAX_EVENTS, empty = "Nothing received yet.")
}

private fun ActionRecord.toLogEntry(watching: Boolean): LogEntry {
    val outcome =
        when {
            error != null -> "failed: $error"
            events == 0 -> if (watching) "no event yet" else "no event (not watching)"
            else -> "$events event(s), first after $firstLatencyMillis ms"
        }
    return LogEntry(
        text = "#$id ${action.label}  → $outcome",
        epochMillis = epochMillis,
        tone =
            when {
                error != null -> Tone.Error
                events == 0 && watching -> Tone.Warning
                else -> Tone.Neutral
            },
    )
}

private fun ObservedEvent.toLogEntry(): LogEntry {
    val o = observation
    val flags =
        listOfNotNull(
            when (o.isDirectory) {
                true -> "dir"
                false -> "file"
                null -> null
            },
            "needsRescan".takeIf { o.needsRescan },
        ).joinToString(" ")
    val attribution = actionId?.let { "#$it +$latencyMillis ms" } ?: "outside change"
    return LogEntry(
        text = "${o.kind.padEnd(KIND_WIDTH)} ${o.paths}${if (flags.isEmpty()) "" else " [$flags]"}  $attribution",
        epochMillis = o.epochMillis,
        tone =
            when {
                o.kind == "Overflow" || o.needsRescan -> Tone.Warning
                o.kind == "Moved" -> Tone.Ok
                else -> Tone.Neutral
            },
        detail = "($thread)",
    )
}

private const val KIND_WIDTH = 8
private const val MAX_ACTIONS = 15
private const val MAX_EVENTS = 80
