package dev.nucleusframework.lab.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.IndeterminateHorizontalProgressBar
import org.jetbrains.jewel.ui.component.styling.HorizontalProgressBarColors
import org.jetbrains.jewel.ui.component.styling.HorizontalProgressBarStyle
import org.jetbrains.jewel.ui.theme.horizontalProgressBarStyle

/** `label  [=====     ]  text`: a fraction 0..1 with its value spelled out; warns past 75 %, errs past 90 %. */
@Composable
fun Meter(
    label: String,
    fraction: Float?,
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = LabTheme.typography.small,
            color = LabTheme.colors.textMuted,
            modifier = Modifier.width(LabDimens.labelWidth),
        )
        val barModifier = Modifier.weight(1f).height(4.dp)
        if (fraction == null) {
            IndeterminateHorizontalProgressBar(modifier = barModifier)
        } else {
            val value = fraction.coerceIn(0f, 1f)
            val auto =
                when {
                    value >= 0.9f -> Tone.Error
                    value >= 0.75f -> Tone.Warning
                    else -> null
                }
            val color = (tone ?: auto)?.color()
            HorizontalProgressBar(value, barModifier, style = progressStyle(color))
        }
        Text(
            text,
            style = LabTheme.typography.mono,
            modifier = Modifier.padding(start = LabDimens.gap).width(150.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `name value` counters on one wrapping line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Counters(
    values: List<Pair<String, Any?>>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(LabDimens.block),
        verticalArrangement = Arrangement.spacedBy(LabDimens.lineGap),
    ) {
        values.forEach { (name, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = LabTheme.typography.small, color = LabTheme.colors.textMuted)
                Text(value?.toString() ?: "—", style = LabTheme.typography.mono)
            }
        }
    }
}

/** Monospace table: a header row and data rows; [widths] are the column widths. */
@Composable
fun MonoTable(
    header: List<String>,
    rows: List<List<String>>,
    widths: List<Dp>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LabDimens.lineGap)) {
        TableRow(header, widths, LabTheme.colors.textMuted)
        rows.forEach { TableRow(it, widths, LabTheme.colors.text) }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Dp>,
    color: Color,
) {
    Row {
        cells.forEachIndexed { index, cell ->
            val width = widths.getOrElse(index) { 80.dp }
            Text(
                cell,
                style = LabTheme.typography.mono,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.width(width),
            )
        }
    }
}

/** A minimal live line chart: oldest left, scaled to [max] (or the largest sample). */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    max: Float? = null,
    height: Dp = 36.dp,
    color: Color = LabTheme.colors.accent,
) {
    val grid = LabTheme.colors.border
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawLine(grid, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
        if (values.size < 2) return@Canvas
        val top = (max ?: values.max()).coerceAtLeast(1e-6f)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / top).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** A titled live chart with its current value. */
@Composable
fun LiveChart(
    title: String,
    values: List<Float>,
    current: String,
    modifier: Modifier = Modifier,
    max: Float? = null,
    color: Color = LabTheme.colors.accent,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Readout(title, current)
        if (values.size < 2) EmptyState("collecting samples…") else Sparkline(values, max = max, color = color)
    }
}

/** The theme's progress bar, its fill recoloured when [color] is given (warning / error). */
@Composable
private fun progressStyle(color: Color?): HorizontalProgressBarStyle {
    val base = JewelTheme.horizontalProgressBarStyle
    if (color == null) return base
    val colors = base.colors
    return HorizontalProgressBarStyle(
        colors =
            HorizontalProgressBarColors(
                colors.track,
                color,
                colors.indeterminateBase,
                colors.indeterminateHighlight,
            ),
        metrics = base.metrics,
        indeterminateCycleDuration = base.indeterminateCycleDuration,
    )
}
