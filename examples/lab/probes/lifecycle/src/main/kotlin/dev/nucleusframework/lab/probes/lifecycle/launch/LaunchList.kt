package dev.nucleusframework.lab.probes.lifecycle.launch

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone

/** Launched instances with their fate and the tail of what they printed. */
@Composable
fun LaunchList(launches: List<LaunchRecord>) {
    if (launches.isEmpty()) return
    SubHeading("Launched instances")
    EventLog(launches.map { it.toLogEntry() }, max = SHOWN)
}

private fun LaunchRecord.toLogEntry(): LogEntry =
    LogEntry(
        text = "#$id $purpose: $summary",
        epochMillis = epochMillis,
        tone =
            when {
                failure != null -> Tone.Error
                running -> Tone.Warning
                exitCode == 0 -> Tone.Ok
                else -> Tone.Error
            },
        children = output.takeLast(OUTPUT_TAIL).map { LogEntry(it, tone = Tone.Muted) },
    )

private const val SHOWN = 4
private const val OUTPUT_TAIL = 3
