@file:OptIn(ExperimentalMaterialApi::class)

package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.Checkbox
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.RangeSlider
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun SelectionPage() {
    Page {
        Demo("Checkboxes, with a tri-state parent") {
            val children = remember { mutableStateListOf(true, false, false) }
            val parent =
                when {
                    children.all { it } -> ToggleableState.On
                    children.none { it } -> ToggleableState.Off
                    else -> ToggleableState.Indeterminate
                }
            Row(
                Modifier.triStateToggleable(parent, role = Role.Checkbox) {
                    val target = parent != ToggleableState.On
                    children.indices.forEach { children[it] = target }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriStateCheckbox(state = parent, onClick = null)
                Text("All toppings")
            }
            listOf("Olives", "Mushrooms", "Peppers").forEachIndexed { index, label ->
                Row(
                    Modifier.padding(start = 32.dp).toggleable(children[index], role = Role.Checkbox) {
                        children[index] = it
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = children[index], onCheckedChange = null)
                    Text(label)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = null, enabled = false)
                Text("Disabled, checked")
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = false, onCheckedChange = null, enabled = false)
                Text("Disabled")
            }
        }
        Demo("Radio buttons") {
            val options = listOf("Light roast", "Medium roast", "Dark roast")
            var selected by remember { mutableStateOf(options[1]) }
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(option == selected, role = Role.RadioButton) { selected = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(option, Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = false, onClick = null, enabled = false)
                    Text("Unavailable", Modifier.padding(start = 8.dp))
                }
            }
            StateLine("Selected: $selected")
        }
        Demo("Switches") {
            var wifi by remember { mutableStateOf(true) }
            var bluetooth by remember { mutableStateOf(false) }
            SwitchRow("Wi-Fi", wifi) { wifi = it }
            SwitchRow("Bluetooth", bluetooth) { bluetooth = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = true,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colors.primary),
                )
                Text("Primary-coloured thumb")
                Spacer(Modifier.width(16.dp))
                Switch(checked = true, onCheckedChange = null, enabled = false)
                Text("Disabled")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun SlidersPage() {
    Page {
        Demo("Continuous") {
            var value by remember { mutableFloatStateOf(0.4f) }
            Slider(value = value, onValueChange = { value = it })
            StateLine("value = ${"%.2f".format(value)}")
        }
        Demo("Discrete: 0 to 10 in steps of 1") {
            var value by remember { mutableFloatStateOf(3f) }
            Slider(value = value, onValueChange = { value = it }, valueRange = 0f..10f, steps = 9)
            StateLine("value = ${value.roundToInt()}")
        }
        Demo("Secondary colours") {
            var value by remember { mutableFloatStateOf(0.7f) }
            Slider(
                value = value,
                onValueChange = { value = it },
                colors =
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colors.secondary,
                        activeTrackColor = MaterialTheme.colors.secondary,
                    ),
            )
        }
        Demo("Range") {
            var range by remember { mutableStateOf(20f..80f) }
            RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..100f)
            StateLine("${range.start.roundToInt()} to ${range.endInclusive.roundToInt()}")
        }
        Demo("Disabled") {
            Slider(value = 0.5f, onValueChange = {}, enabled = false)
        }
    }
}
