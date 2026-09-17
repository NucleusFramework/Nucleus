@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.demo.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ExpressiveSelection() {
    ParentSection(title = "Expressive selection") {
        ChildSection(title = "Vertical slider") { VerticalSliderDemo() }
        ChildSection(title = "Shape-morphing chips") { MorphingChipsDemo() }
    }
}

@Composable
private fun VerticalSliderDemo() {
    val vertical = remember { SliderState(value = 0.45f) }
    val horizontal = remember { SliderState(value = 0.35f, steps = 4) }
    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VerticalSlider(
                state = vertical,
                modifier = Modifier.height(180.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Centered track + stop indicators",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    state = horizontal,
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            drawStopIndicator = { SliderDefaults.TrackStopIndicatorSize },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MorphingChipsDemo() {
    val labels = listOf("Design", "Motion", "Shape", "Type")
    var selected by remember { mutableStateOf(setOf("Design", "Shape")) }
    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEach { label ->
                val checked = label in selected
                FilterChip(
                    selected = checked,
                    onClick = {
                        selected = if (checked) selected - label else selected + label
                    },
                    label = { Text(label) },
                    shapes = FilterChipDefaults.shapes(),
                )
            }
        }
    }
}
