package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability

/** `label ........ value`, the value selectable and monospace. */
@Composable
fun Readout(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    indent: Int = 0,
) {
    Row(modifier.fillMaxWidth().padding(start = LabDimens.indent * indent), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = LabTheme.typography.small,
            color = LabTheme.colors.textMuted,
            modifier = Modifier.width(LabDimens.labelWidth),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(value ?: "—", style = LabTheme.typography.mono, color = tone.color())
        }
    }
}

/** A block of readouts, one per entry. */
@Composable
fun Readouts(
    values: List<Pair<String, String?>>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { (label, value) -> Readout(label, value) }
    }
}

enum class Tone { Neutral, Ok, Warning, Error, Muted }

@Composable
fun Tone.color(): Color {
    val colors = LabTheme.colors
    return when (this) {
        Tone.Neutral -> colors.text
        Tone.Ok -> colors.ok
        Tone.Warning -> colors.warning
        Tone.Error -> colors.error
        Tone.Muted -> colors.textMuted
    }
}

@Composable
fun StatusDot(
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(8.dp).clip(CircleShape).background(tone.color()))
}

val Availability.tone: Tone
    get() =
        when (this) {
            Availability.Available -> Tone.Ok
            is Availability.Unavailable -> Tone.Error
            Availability.Unknown -> Tone.Muted
        }

/** Capability chip; hovering an unavailable one tells why. */
@Composable
fun AvailabilityChip(capability: Capability) {
    val availability = capability.availability
    val tooltip =
        listOfNotNull(
            (availability as? Availability.Unavailable)?.reason,
            capability.detail,
        ).joinToString("\n")
    Tooltip(tooltip) {
        Row(
            Modifier
                .clip(LabShapes.pill)
                .background(LabTheme.colors.panel)
                .border(1.dp, LabTheme.colors.border, LabShapes.pill)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(availability.tone)
            Text(
                capability.name,
                style = LabTheme.typography.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (availability is Availability.Unavailable) {
                Text(
                    availability.reason,
                    style = LabTheme.typography.small,
                    color = LabTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
    }
}

/** Shown in place of a probe's content when nothing in it can run here. */
@Composable
fun UnsupportedHere(reason: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LabDimens.gap),
    ) {
        Icon(LabIcons.Info, null, Modifier.size(24.dp))
        Text("Not available on this system", style = LabTheme.typography.heading)
        Text(reason, color = LabTheme.colors.textMuted)
    }
}
