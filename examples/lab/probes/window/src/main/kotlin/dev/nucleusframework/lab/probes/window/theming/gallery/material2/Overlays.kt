@file:OptIn(ExperimentalMaterialApi::class)

package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedButton
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val cities = listOf("Jerusalem", "Bnei Brak", "Paris", "London", "New York", "Antwerp", "Montreal", "Lakewood")

@Composable
internal fun MenusPage() {
    Page {
        Dialogs()
        Demo("Dropdown menu") {
            var expanded by remember { mutableStateOf(false) }
            var last by remember { mutableStateOf("none") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    val pick: (String) -> Unit = {
                        last = it
                        expanded = false
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        MenuEntry("Cut", Icons.Filled.ContentCut, pick)
                        MenuEntry("Copy", Icons.Filled.ContentCopy, pick)
                        MenuEntry("Paste", Icons.Filled.ContentPaste, pick)
                        Divider()
                        DropdownMenuItem(onClick = {}, enabled = false) { Text("Disabled") }
                    }
                }
                StateLine("Last picked: $last")
            }
        }
        Demo("Exposed dropdown menus") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReadOnlyDropdown(Modifier.weight(1f))
                FilteringDropdown(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MenuEntry(
    label: String,
    icon: ImageVector,
    onPick: (String) -> Unit,
) {
    DropdownMenuItem(onClick = { onPick(label) }) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}

@Composable
private fun Dialogs() {
    Demo("Alert dialogs") {
        var simple by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf(false) }
        var choice by remember { mutableStateOf(false) }
        var outcome by remember { mutableStateOf("none") }
        var ringtone by remember { mutableStateOf("Classic") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { simple = true }) { Text("Simple") }
            OutlinedButton(onClick = { confirm = true }) { Text("Confirmation") }
            OutlinedButton(onClick = { choice = true }) { Text("Single choice") }
        }
        StateLine("Last outcome: $outcome · ringtone: $ringtone")
        if (simple) {
            AlertDialog(
                onDismissRequest = {
                    simple = false
                    outcome = "dismissed"
                },
                title = { Text("Use location service?") },
                text = { Text("Let the app determine location. Anonymous data is sent even when no app is running.") },
                confirmButton = {
                    TextButton(onClick = {
                        simple = false
                        outcome = "agreed"
                    }) { Text("AGREE") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        simple = false
                        outcome = "disagreed"
                    }) { Text("DISAGREE") }
                },
            )
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                text = { Text("Discard draft?") },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        outcome = "discarded"
                    }) { Text("DISCARD") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL") } },
            )
        }
        if (choice) {
            var pending by remember { mutableStateOf(ringtone) }
            AlertDialog(
                onDismissRequest = { choice = false },
                title = { Text("Phone ringtone") },
                text = {
                    Column {
                        listOf("Classic", "Chimes", "Harp", "Silent").forEach { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(option == pending, role = Role.RadioButton) { pending = option },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = option == pending, onClick = null)
                                Spacer(Modifier.width(16.dp))
                                Text(option)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        ringtone = pending
                        choice = false
                        outcome = "ringtone set"
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { choice = false }) { Text("CANCEL") } },
            )
        }
    }
}

@Composable
private fun ReadOnlyDropdown(modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf(cities.first()) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        TextField(
            value = city,
            onValueChange = {},
            readOnly = true,
            label = { Text("City") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            cities.forEach { option ->
                DropdownMenuItem(onClick = {
                    city = option
                    expanded = false
                }) { Text(option) }
            }
        }
    }
}

@Composable
private fun FilteringDropdown(modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val matches = cities.filter { it.contains(query, ignoreCase = true) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        TextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text("Filter cities") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (matches.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                matches.forEach { option ->
                    DropdownMenuItem(onClick = {
                        query = option
                        expanded = false
                    }) { Text(option) }
                }
            }
        }
    }
}
