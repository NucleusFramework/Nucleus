package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

@OptIn(ExperimentalLayoutApi::class)
val MaterialSamples: List<SampleEntry> =
    samples(SampleCategory.Material) {
        sample(
            "buttons",
            "Buttons & FABs",
            "Hover darkens, press shows a ripple from the pointer, keyboard focus (Tab) draws a ring; disabled ones ignore the pointer.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button({}) { Text("Filled") }
                FilledTonalButton({}) { Text("Tonal") }
                ElevatedButton({}) { Text("Elevated") }
                OutlinedButton({}) { Text("Outlined") }
                TextButton({}) { Text("Text") }
                Button({}, enabled = false) { Text("Disabled") }
                FloatingActionButton({}) { Icon(Icons.Filled.Add, "Add") }
                ExtendedFloatingActionButton(
                    text = { Text("Compose") },
                    icon = { Icon(Icons.Filled.Edit, null) },
                    onClick = {},
                )
            }
        }
        sample(
            "selection",
            "Selection controls",
            "Checkbox, radio, switch animate their state; the slider and range slider track the pointer without lag and snap with keyboard arrows.",
        ) {
            var checked by remember { mutableStateOf(true) }
            var radio by remember { mutableIntStateOf(0) }
            var switched by remember { mutableStateOf(false) }
            var value by remember { mutableFloatStateOf(0.4f) }
            var range by remember { mutableStateOf(0.2f..0.7f) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { checked = it })
                    repeat(3) { RadioButton(radio == it, { radio = it }) }
                    Switch(switched, { switched = it })
                }
                Slider(value, { value = it }, Modifier.width(320.dp), steps = 9)
                RangeSlider(range, { range = it }, Modifier.width(320.dp))
            }
        }
        sample(
            "chips",
            "Chips",
            "Filter chips toggle with a check mark; the input chip shows its trailing icon; all keep their height when the font scale changes.",
        ) {
            var selected by remember { mutableStateOf(setOf(1)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({}, { Text("Assist") })
                repeat(3) { i ->
                    FilterChip(
                        i in selected,
                        { selected = if (i in selected) selected - i else selected + i },
                        { Text("Filter $i") },
                        leadingIcon = if (i in selected) ({ Icon(Icons.Filled.Check, null) }) else null,
                    )
                }
                InputChip(true, {}, { Text("Input") }, trailingIcon = { Icon(Icons.Filled.Edit, null) })
            }
        }
        sample(
            "fields",
            "Text fields",
            "Labels float on focus; the error field is red with its supporting text; the outlined field's notch fits the label at every font scale.",
        ) {
            var filled by remember { mutableStateOf("") }
            var outlined by remember { mutableStateOf("Outlined") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    filled,
                    { filled = it },
                    label = { Text("Filled") },
                    supportingText = { Text("Supporting text") },
                )
                OutlinedTextField(outlined, { outlined = it }, label = { Text("Outlined label") })
                OutlinedTextField(
                    "bad@",
                    {},
                    label = { Text("E-mail") },
                    isError = true,
                    supportingText = { Text("Not an address") },
                )
            }
        }
        sample(
            "progress",
            "Progress indicators",
            "Indeterminate indicators animate smoothly at the display rate; the determinate pair stops at 60 %.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    CircularProgressIndicator(progress = { 0.6f })
                }
                LinearProgressIndicator(Modifier.width(320.dp))
                LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.width(320.dp))
            }
        }
    }
