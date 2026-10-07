package dev.nucleusframework.lab.probes.window.theming.gallery.material3

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun Selection() {
    ParentSection("Selection") {
        ChildSection(title = "Checkboxes") { CheckboxesDemo() }
        ChildSection(title = "Chips") { ChipsDemo() }
        ChildSection(title = "Date picker") { DatePickerDemo() }
        ChildSection(title = "Radio buttons") { RadioButtonsDemo() }
        ChildSection(title = "Sliders") { SlidersDemo() }
        ChildSection(title = "Switches") { SwitchesDemo() }
        ChildSection(title = "Time picker") { TimePickerDemo() }
    }
}

@Composable
private fun CheckboxesDemo() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            val state1 = remember { mutableStateOf(true) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { state1.value = !state1.value }
                        .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Option 1")
                Checkbox(checked = state1.value, onCheckedChange = null)
            }

            val state2 = remember { mutableStateOf(ToggleableState.Indeterminate) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { state2.value = state2.value.nextState() }
                        .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Option 2")
                TriStateCheckbox(state = state2.value, onClick = null)
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable(enabled = false) {}
                        .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Option 3")
                Checkbox(checked = true, enabled = false, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun ChipsDemo() {
    var filterChipSelected by remember { mutableStateOf(true) }

    @Composable
    fun ChipsRow(enabled: Boolean = true) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            AssistChip(
                enabled = enabled,
                onClick = {},
                label = { Text("Assist") },
                leadingIcon = {
                    Icon(Icons.Outlined.Event, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
                },
            )
            FilterChip(
                enabled = enabled,
                onClick = { filterChipSelected = !filterChipSelected },
                label = { Text("Filter") },
                selected = filterChipSelected,
                leadingIcon =
                    if (filterChipSelected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Done,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
            )
            InputChip(
                onClick = {},
                label = { Text("Input") },
                selected = true,
                enabled = enabled,
                avatar = null,
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = null, Modifier.size(InputChipDefaults.IconSize))
                },
            )
            SuggestionChip(
                enabled = enabled,
                onClick = {},
                label = { Text("Suggestion") },
            )
        }
    }

    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            ChipsRow(enabled = true)
            ChipsRow(enabled = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDemo() {
    var openDialog by remember { mutableStateOf(false) }

    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = { openDialog = true }) { Text("Show date picker") }
        }
    }

    if (openDialog) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { openDialog = false },
            confirmButton = { TextButton(onClick = { openDialog = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { openDialog = false }) { Text("Cancel") } },
            content = { DatePicker(state) },
        )
    }
}

private val radioOptions = listOf("Option 1", "Option 2", "Option 3")

@Composable
private fun RadioButtonsDemo() {
    val (selectedOption, onOptionSelected) = remember { mutableStateOf(radioOptions[0]) }

    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .selectableGroup()
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(16.dp),
        ) {
            radioOptions.forEachIndexed { ix, text ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .selectable(
                            enabled = ix < 2,
                            selected = text == selectedOption,
                            onClick = { onOptionSelected(text) },
                            role = Role.RadioButton,
                        ).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(enabled = ix < 2, selected = text == selectedOption, onClick = null)
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (ix < 2) 1f else 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlidersDemo() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .selectableGroup()
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(32.dp),
        ) {
            var sliderPosition by remember { mutableFloatStateOf(0f) }
            Slider(value = sliderPosition, onValueChange = { sliderPosition = it })
            Spacer(modifier = Modifier.height(32.dp))
            var sliderPosition2 by remember { mutableFloatStateOf(0f) }
            Slider(
                value = sliderPosition2,
                onValueChange = { sliderPosition2 = it },
                steps = 5,
                valueRange = 0f..100f,
            )
            Spacer(modifier = Modifier.height(32.dp))
            var sliderPosition3 by remember { mutableStateOf(0f..100f) }
            RangeSlider(
                value = sliderPosition3,
                steps = 5,
                onValueChange = { sliderPosition3 = it },
                valueRange = 0f..100f,
            )
        }
    }
}

@Composable
private fun SwitchesDemo() {
    OutlinedCard {
        Column(
            modifier =
                Modifier
                    .selectableGroup()
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(32.dp),
        ) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                var checked by remember { mutableStateOf(true) }
                Switch(checked = checked, onCheckedChange = { checked = it })

                var checked2 by remember { mutableStateOf(true) }
                Switch(
                    checked = checked2,
                    onCheckedChange = { checked2 = it },
                    thumbContent =
                        if (checked2) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        } else {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Switch(enabled = false, checked = false, onCheckedChange = null)
                Switch(
                    enabled = false,
                    checked = true,
                    onCheckedChange = null,
                    thumbContent = {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDemo() {
    var openDialog by remember { mutableStateOf(false) }

    OutlinedCard {
        Row(
            modifier =
                Modifier
                    .selectableGroup()
                    .requiredWidthIn(400.dp)
                    .width(600.dp)
                    .padding(32.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = { openDialog = true }) { Text("Show time picker") }
        }
    }

    if (openDialog) {
        val state = rememberTimePickerState()
        Dialog(onDismissRequest = { openDialog = false }) {
            Card(shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp)) {
                    TimePicker(
                        state,
                        layoutType = TimePickerLayoutType.Vertical,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { openDialog = false }) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(32.dp))
                        TextButton(onClick = { openDialog = false }) { Text("OK") }
                    }
                }
            }
        }
    }
}

private fun ToggleableState.nextState(): ToggleableState =
    when (this) {
        ToggleableState.Indeterminate -> ToggleableState.Off
        ToggleableState.On -> ToggleableState.Indeterminate
        ToggleableState.Off -> ToggleableState.On
    }
