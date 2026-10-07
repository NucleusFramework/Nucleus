package dev.nucleusframework.lab.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.checks.CheckBook
import dev.nucleusframework.lab.core.checks.CheckStatus
import dev.nucleusframework.lab.designsystem.LabLazyColumn
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.SelectableRow
import dev.nucleusframework.lab.designsystem.StatusDot
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.color

/** Progress of a probe's check sheet in the current environment. */
data class CheckProgress(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val decided: Int,
) {
    val summary: String get() = "$passed pass · $failed fail · ${total - decided} open"

    val tone: Tone
        get() =
            when {
                failed > 0 -> Tone.Error
                total > 0 && decided == total -> Tone.Ok
                decided > 0 -> Tone.Warning
                else -> Tone.Muted
            }
}

fun CheckBook.progress(
    fingerprint: String?,
    descriptor: ProbeDescriptor,
): CheckProgress {
    val results = fingerprint?.let { results[it]?.get(descriptor.id.value) }.orEmpty()
    val statuses = descriptor.checks.map { results[it.id]?.status ?: CheckStatus.Untested }
    return CheckProgress(
        total = statuses.size,
        passed = statuses.count { it == CheckStatus.Pass },
        failed = statuses.count { it == CheckStatus.Fail },
        decided = statuses.count { it != CheckStatus.Untested },
    )
}

/** The probe tree, grouped by domain, in IntelliJ's project-view style. */
@Composable
fun Sidebar(
    probes: List<Probe>,
    state: ShellState,
    onSelect: (ProbeId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = probes.groupBy { it.descriptor.domain }
    LabLazyColumn(
        modifier.background(LabTheme.colors.panel),
        contentPadding = PaddingValues(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 8.dp),
    ) {
        grouped.forEach { (domain, inDomain) ->
            item(key = "domain-${domain.name}") {
                Text(
                    domain.title,
                    style = LabTheme.typography.heading,
                    color = LabTheme.colors.textMuted,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(inDomain, key = { it.descriptor.id.value }) { probe ->
                SidebarItem(
                    descriptor = probe.descriptor,
                    selected = probe.descriptor.id == state.selected,
                    progress = state.book.progress(state.environment?.fingerprint, probe.descriptor),
                    onClick = { onSelect(probe.descriptor.id) },
                )
            }
        }
    }
}

@Composable
private fun SidebarItem(
    descriptor: ProbeDescriptor,
    selected: Boolean,
    progress: CheckProgress,
    onClick: () -> Unit,
) {
    val supported = descriptor.supportsCurrentPlatform
    val colors = LabTheme.colors
    SelectableRow(selected = selected, onClick = onClick) {
        StatusDot(if (supported) progress.tone else Tone.Muted)
        Text(
            descriptor.title,
            color = if (supported) colors.text else colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            !supported -> Text("n/a", style = LabTheme.typography.small, color = colors.textMuted)
            progress.total > 0 ->
                Text(
                    "${progress.decided}/${progress.total}",
                    style = LabTheme.typography.small,
                    color = progress.tone.color(),
                )
        }
    }
}
