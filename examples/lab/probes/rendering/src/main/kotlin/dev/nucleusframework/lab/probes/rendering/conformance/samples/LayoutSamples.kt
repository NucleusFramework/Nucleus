package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Swatches =
    listOf(Color(0xFF6366F1), Color(0xFFEC4899), Color(0xFF06B6D4), Color(0xFFF59E0B), Color(0xFF10B981))

@Composable
private fun Chip(
    index: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Swatches[index % Swatches.size]).padding(6.dp), contentAlignment = Alignment.Center) {
        Text("${index + 1}", color = Color.White, style = LabTheme.typography.label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
val LayoutSamples: List<SampleEntry> =
    samples(SampleCategory.Layout) {
        sample(
            "arrangement",
            "Row arrangements",
            "Start, End, Center, SpaceBetween, SpaceAround, SpaceEvenly in that order; with RTL on, 1 starts on the right and Start/End swap.",
        ) {
            val arrangements =
                listOf(
                    "Start" to Arrangement.Start,
                    "End" to Arrangement.End,
                    "Center" to Arrangement.Center,
                    "SpaceBetween" to Arrangement.SpaceBetween,
                    "SpaceAround" to Arrangement.SpaceAround,
                    "SpaceEvenly" to Arrangement.SpaceEvenly,
                )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                arrangements.forEach { (name, arrangement) ->
                    Text(name, style = LabTheme.typography.small)
                    Row(
                        Modifier.fillMaxWidth().background(LabTheme.colors.raised),
                        horizontalArrangement = arrangement,
                    ) {
                        repeat(3) { Chip(it, Modifier.size(28.dp)) }
                    }
                }
            }
        }
        sample(
            "alignment",
            "Box alignment grid",
            "Nine numbers sit in the nine alignment slots, 1 top-start to 9 bottom-end (start = right in RTL).",
        ) {
            val alignments =
                listOf(
                    Alignment.TopStart,
                    Alignment.TopCenter,
                    Alignment.TopEnd,
                    Alignment.CenterStart,
                    Alignment.Center,
                    Alignment.CenterEnd,
                    Alignment.BottomStart,
                    Alignment.BottomCenter,
                    Alignment.BottomEnd,
                )
            Box(Modifier.size(220.dp).background(LabTheme.colors.raised)) {
                alignments.forEachIndexed { index, alignment -> Chip(index, Modifier.align(alignment).size(32.dp)) }
            }
        }
        sample(
            "custom",
            "Custom Layout",
            "Eight chips placed on a circle by a hand-written Layout, evenly spaced, none overlapping the centre label.",
        ) {
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Text("centre")
                Layout(content = {
                    repeat(8) { Chip(it, Modifier.size(30.dp)) }
                }, modifier = Modifier.size(220.dp)) { measurables, constraints ->
                    val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val radius = constraints.maxWidth / 2f - 24.dp.toPx()
                        placeables.forEachIndexed { i, p ->
                            val angle = i * 2 * Math.PI / placeables.size
                            val x = constraints.maxWidth / 2f + radius * cos(angle) - p.width / 2f
                            val y = constraints.maxHeight / 2f + radius * sin(angle) - p.height / 2f
                            p.place(x.roundToInt(), y.roundToInt())
                        }
                    }
                }
            }
        }
        sample(
            "flow",
            "FlowRow wrapping",
            "Chips of varied widths wrap onto new lines with 8 dp gaps; resizing the window re-flows them.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(18) { Chip(it, Modifier.width((40 + (it * 23) % 70).dp).height(28.dp)) }
            }
        }
        sample(
            "intrinsic",
            "Intrinsics, weight, aspect ratio",
            "Row 1: three columns as tall as the tallest, the divider full height. Row 2: weights 1:2:1. Box 3 is exactly 16:9.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.height(IntrinsicSize.Min).background(LabTheme.colors.raised)) {
                    Text("short", Modifier.weight(1f).padding(6.dp))
                    Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Gray))
                    Text("a much longer text that wraps onto several lines", Modifier.weight(1f).padding(6.dp))
                }
                Row(Modifier.fillMaxWidth()) {
                    Chip(0, Modifier.weight(1f).height(28.dp))
                    Chip(1, Modifier.weight(2f).height(28.dp))
                    Chip(2, Modifier.weight(1f).height(28.dp))
                }
                Box(
                    Modifier.width(240.dp).aspectRatio(16f / 9f).background(Swatches[4]),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("16:9", color = Color.White)
                }
            }
        }
        sample(
            "offset",
            "Relative vs absolute offset",
            "With RTL off both chips shift right. With RTL on, the relative one shifts left and the absolute one still shifts right.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Chip(0, Modifier.offset(x = 40.dp).size(28.dp))
                    Text("  offset (relative)")
                }
                Row {
                    Chip(1, Modifier.absoluteOffset(x = 40.dp).size(28.dp))
                    Text("  absoluteOffset")
                }
            }
        }
    }
