package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Capability

/**
 * The anatomy every probe shares: what is available here, what you can do, what the OS
 * reports back. Two columns when there is room, stacked otherwise. Checks and timeline are
 * drawn by the shell around it.
 */
@Composable
fun ProbeLayout(
    capabilities: List<Capability>,
    modifier: Modifier = Modifier,
    controls: @Composable ColumnScope.() -> Unit,
    observed: @Composable ColumnScope.() -> Unit,
    /** Full-width area below both columns, for the specimen under test (samples, native views, video). */
    wide: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ScrollableColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(LabDimens.page),
        verticalArrangement = Arrangement.spacedBy(LabDimens.blockGap),
    ) {
        if (capabilities.isNotEmpty()) CapabilityStrip(capabilities)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth > 820.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.blockGap)) {
                    Section("Controls", Modifier.weight(1f), content = controls)
                    Section("Observed", Modifier.weight(1f), content = observed)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(LabDimens.blockGap)) {
                    Section("Controls", content = controls)
                    Section("Observed", content = observed)
                }
            }
        }
        wide?.let {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
                content = it,
            )
        }
    }
}

/** A titled, outlined block: IntelliJ's group header over a bordered panel. */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .border(1.dp, LabTheme.colors.border, LabShapes.block)
                .padding(LabDimens.block),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = LabTheme.typography.heading,
                color = LabTheme.colors.text,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityStrip(capabilities: List<Capability>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LabDimens.gap),
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        capabilities.forEach { AvailabilityChip(it) }
    }
}

/** A short label naming a group of lines inside a section. For sentences, use [Hint]. */
@Composable
fun SubHeading(text: String) {
    Box(Modifier.padding(top = 4.dp)) {
        Text(text, style = LabTheme.typography.heading, color = LabTheme.colors.textMuted)
    }
}

@Composable
fun HSpacer(width: Int = 8) = Spacer(Modifier.width(width.dp))
