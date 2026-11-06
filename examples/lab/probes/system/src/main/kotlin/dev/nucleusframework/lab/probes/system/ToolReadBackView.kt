package dev.nucleusframework.lab.probes.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Tone

/** The command, then its kept lines, or why the tool could not answer. */
@Composable
fun ToolReadBackView(
    label: String,
    readBack: ToolReadBack?,
    empty: String = "Nothing listed.",
) {
    if (readBack == null) {
        Readout(label, "not read yet", tone = Tone.Muted)
        return
    }
    Readout(label, readBack.command, tone = Tone.Muted)
    val lines = readBack.lines
    when {
        lines == null -> Readout("unavailable", readBack.note, tone = Tone.Warning)
        lines.isEmpty() -> EmptyState(empty)
        else -> CodeBlock(lines.joinToString("\n"), maxHeight = 200.dp)
    }
    if (lines != null && readBack.note != null) Hint(readBack.note)
}
