package dev.nucleusframework.lab.probes.rendering.conformance.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.probes.rendering.conformance.SampleCategory

@OptIn(ExperimentalLayoutApi::class)
val MaterialOverlaySamples: List<SampleEntry> =
    samples(SampleCategory.Material) {
        sample(
            "popups",
            "Menus, dialogs, tooltips",
            "The menu opens under its button and closes on outside click or Esc; the dialog dims the window and traps Tab; the tooltip appears on hover after a delay.",
        ) {
            var menu by remember { mutableStateOf(false) }
            var dialog by remember { mutableStateOf(false) }
            var picked by remember { mutableStateOf("—") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton({ menu = true }) { Text("Menu: $picked") }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("Cut", "Copy", "Paste", "A much longer item label").forEach {
                            DropdownMenuItem({ Text(it) }, {
                                picked = it
                                menu = false
                            })
                        }
                    }
                }
                OutlinedButton({ dialog = true }) { Text("Dialog") }
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text("A plain tooltip") } },
                    state = rememberTooltipState(),
                ) { OutlinedButton({}) { Text("Hover me") } }
            }
            if (dialog) {
                AlertDialog(
                    onDismissRequest = { dialog = false },
                    confirmButton = { TextButton({ dialog = false }) { Text("OK") } },
                    dismissButton = { TextButton({ dialog = false }) { Text("Cancel") } },
                    title = { Text("Alert dialog") },
                    text = { Text("Rendered in a dialog layer above the window content.") },
                )
            }
        }
        sample(
            "date-picker",
            "Date picker dialog",
            "The calendar grid lays out evenly at every font scale; month paging animates; today is outlined; RTL mirrors the arrows.",
        ) {
            var open by remember { mutableStateOf(false) }
            val state = rememberDatePickerState()
            OutlinedButton({
                open = true
            }) {
                Text(
                    "Pick a date: ${state.selectedDateMillis?.let {
                        java.time.Instant
                            .ofEpochMilli(
                                it,
                            ).toString()
                            .take(10)
                    } ?: "—"}",
                )
            }
            if (open) {
                DatePickerDialog(
                    onDismissRequest = { open = false },
                    confirmButton = { TextButton({ open = false }) { Text("OK") } },
                ) {
                    DatePicker(state)
                }
            }
        }
        sample(
            "navigation",
            "Tabs & navigation bar",
            "The tab indicator slides to the selected tab; the navigation bar's pill animates; both mirror in RTL.",
        ) {
            var tab by remember { mutableIntStateOf(0) }
            var item by remember { mutableIntStateOf(0) }
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryTabRow(tab) {
                    listOf("One", "Two", "Three").forEachIndexed {
                        i,
                        t,
                        ->
                        Tab(tab == i, { tab = i }, text = { Text(t) })
                    }
                }
                NavigationBar {
                    listOf(
                        Icons.Filled.Home to "Home",
                        Icons.Filled.Favorite to "Liked",
                        Icons.Filled.Settings to "Settings",
                    ).forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(item == i, { item = i }, { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            }
        }
    }
