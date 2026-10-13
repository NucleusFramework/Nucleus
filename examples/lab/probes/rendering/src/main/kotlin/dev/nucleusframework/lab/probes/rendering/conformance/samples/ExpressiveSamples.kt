package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

@OptIn(ExperimentalLayoutApi::class)
val ExpressiveSamples: List<SampleEntry> =
    samples(SampleCategory.Expressive) {
        sample(
            "indicators",
            "Loading & wavy indicators",
            "The loading indicator morphs between shapes continuously; wavy indicators ripple without tearing; the determinate ones stop at 70 %.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator()
                    ContainedLoadingIndicator()
                    LoadingIndicator(progress = { 0.7f })
                    CircularWavyProgressIndicator()
                    CircularWavyProgressIndicator(progress = { 0.7f })
                }
                LinearWavyProgressIndicator(Modifier.width(320.dp))
                LinearWavyProgressIndicator(progress = { 0.7f }, modifier = Modifier.width(320.dp))
            }
        }
        sample(
            "shapes",
            "Material shapes",
            "Eight polygon shapes, each a clean outline (no jagged facets); clicking the cookie grows it with a springy morph.",
        ) {
            var grown by remember { mutableStateOf(false) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    MaterialShapes.Cookie9Sided,
                    MaterialShapes.Sunny,
                    MaterialShapes.Clover4Leaf,
                    MaterialShapes.Pill,
                    MaterialShapes.Gem,
                    MaterialShapes.SoftBoom,
                    MaterialShapes.Flower,
                    MaterialShapes.Heart,
                ).forEach { shape ->
                    Box(Modifier.size(56.dp).clip(shape.toShape()).background(MaterialTheme.colorScheme.primary))
                }
                Box(
                    Modifier
                        .size(if (grown) 96.dp else 56.dp)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(MaterialTheme.colorScheme.tertiary)
                        .clickable { grown = !grown },
                )
            }
        }
        sample(
            "toggles",
            "Toggle buttons & connected group",
            "Toggles change shape when checked (round ↔ square corners); the connected group selects one at a time with a squish on press.",
        ) {
            var filled by remember { mutableStateOf(true) }
            var tonal by remember { mutableStateOf(false) }
            var outlined by remember { mutableStateOf(false) }
            var selected by remember { mutableIntStateOf(0) }
            val options = listOf("Day", "Week", "Month")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleButton(filled, { filled = it }) {
                        Icon(if (filled) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null)
                        Text(" Filled")
                    }
                    TonalToggleButton(tonal, { tonal = it }) { Text("Tonal") }
                    OutlinedToggleButton(outlined, { outlined = it }) { Text("Outlined") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    options.forEachIndexed { index, label ->
                        ToggleButton(
                            checked = selected == index,
                            onCheckedChange = { selected = index },
                            shapes =
                                when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                        ) { Text(label) }
                    }
                }
            }
        }
        sample(
            "button-group",
            "Overflowing button group",
            "Narrow the window: buttons that no longer fit move into the overflow menu, which opens as a popup next to its indicator.",
        ) {
            ButtonGroup(overflowIndicator = {
                ButtonGroupDefaults.OverflowIndicator(menuState = it)
            }, modifier = Modifier.width(360.dp)) {
                listOf("Cut", "Copy", "Paste", "Share", "Delete", "Archive", "Label").forEach {
                    clickableItem(onClick = {}, label = it)
                }
            }
        }
    }
