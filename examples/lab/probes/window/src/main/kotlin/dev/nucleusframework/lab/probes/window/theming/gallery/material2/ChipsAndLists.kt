@file:OptIn(ExperimentalMaterialApi::class)

package dev.nucleusframework.lab.probes.window.theming.gallery.material2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Checkbox
import androidx.compose.material.Chip
import androidx.compose.material.ChipDefaults
import androidx.compose.material.Divider
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.FilterChip
import androidx.compose.material.Icon
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ChipsPage() {
    Page {
        Demo("Action chips") {
            var last by remember { mutableStateOf("none") }
            ChipRow {
                Chip(onClick = { last = "Directions" }) { Text("Directions") }
                Chip(
                    onClick = { last = "Tag" },
                    leadingIcon = { Icon(Icons.Filled.LocalOffer, contentDescription = null) },
                ) { Text("Tag") }
                Chip(
                    onClick = { last = "Outlined" },
                    border = ChipDefaults.outlinedBorder,
                    colors = ChipDefaults.outlinedChipColors(),
                ) { Text("Outlined") }
                Chip(onClick = {}, enabled = false) { Text("Disabled") }
            }
            StateLine("Last clicked: $last")
        }
        Demo("Filter chips (multi-select)") {
            val genres = listOf("Jazz", "Classical", "Folk", "Electronic", "Choral", "Ambient")
            val selected = remember { mutableStateListOf("Jazz", "Choral") }
            ChipRow {
                genres.forEach { genre ->
                    FilterChip(
                        selected = genre in selected,
                        onClick = { if (genre in selected) selected -= genre else selected += genre },
                        leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                        selectedIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    ) { Text(genre) }
                }
            }
            StateLine("Selected: ${selected.joinToString().ifEmpty { "nothing" }}")
        }
        Demo("Input chips (removable)") {
            val people = remember { mutableStateListOf("Avraham", "Yitzchak", "Yaakov", "Yosef") }
            ChipRow {
                people.forEach { person ->
                    Chip(
                        onClick = { people -= person },
                        leadingIcon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
                    ) {
                        Text(person)
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colors.onSurface.copy(alpha = ChipDefaults.LeadingIconOpacity),
                        )
                    }
                }
            }
            StateLine("${people.size} left: click one to remove it")
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@Composable
internal fun ListsPage() {
    Page {
        Demo("One, two and three lines") {
            Column {
                ListItem(text = { Text("One line") })
                Divider()
                ListItem(
                    text = { Text("One line with an icon") },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                )
                Divider()
                ListItem(
                    text = { Text("Two lines") },
                    secondaryText = { Text("Secondary text") },
                    icon = { Icon(Icons.Filled.Image, contentDescription = null) },
                )
                Divider()
                ListItem(
                    overlineText = { Text("OVERLINE") },
                    text = { Text("Three lines") },
                    secondaryText = {
                        Text("Secondary text long enough to wrap onto a second line beneath the primary one")
                    },
                    singleLineSecondaryText = false,
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                )
            }
        }
        Demo("Trailing controls") {
            var wifi by remember { mutableStateOf(true) }
            val tasks = remember { mutableStateListOf(true, false, true) }
            Column {
                ListItem(
                    modifier = Modifier.toggleable(wifi, role = Role.Switch) { wifi = it },
                    text = { Text("Wi-Fi") },
                    secondaryText = { Text(if (wifi) "Connected" else "Off") },
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    trailing = { Switch(checked = wifi, onCheckedChange = null) },
                )
                listOf("Write the probe", "Run the checks", "Paste the report").forEachIndexed { index, label ->
                    Divider()
                    ListItem(
                        modifier = Modifier.toggleable(tasks[index], role = Role.Checkbox) { tasks[index] = it },
                        text = { Text(label) },
                        trailing = { Checkbox(checked = tasks[index], onCheckedChange = null) },
                    )
                }
            }
            StateLine("${tasks.count { it }} of ${tasks.size} done")
        }
        Demo("Selectable") {
            var selected by remember { mutableIntStateOf(1) }
            Column {
                repeat(4) { index ->
                    val isSelected = index == selected
                    val tint = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
                    ListItem(
                        modifier =
                            Modifier.toggleable(isSelected, role = Role.RadioButton) { selected = index },
                        text = {
                            Text("Item ${index + 1}", color = tint)
                        },
                        trailing = {
                            if (isSelected) Icon(Icons.Filled.Check, null, tint = tint)
                        },
                    )
                }
            }
        }
    }
}
