package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.launcher.macos.DockMenuItem
import dev.nucleusframework.nucleus.launcher.macos.MacOsDockMenu

private const val EVENT_LOG_MAX = 20

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun MacOsLauncherScreen() {
    val events = remember { mutableStateListOf<String>() }
    var dockMenuActive by remember { mutableStateOf(false) }

    fun log(msg: String) {
        events.add(0, msg)
        if (events.size > EVENT_LOG_MAX) events.removeRange(EVENT_LOG_MAX, events.size)
    }

    DisposableEffect(Unit) {
        onDispose { MacOsDockMenu.clearDockMenu() }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("macOS Dock Menu", style = MaterialTheme.typography.headlineSmall)

            if (!MacOsDockMenu.isAvailable) {
                Text("Native library not available.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Dock Menu", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = {
                            MacOsDockMenu.listener = { itemId -> log("Item clicked: id=$itemId") }
                            MacOsDockMenu.setDockMenu(
                                listOf(
                                    DockMenuItem(id = 1, title = "New Window"),
                                    DockMenuItem(id = 2, title = "Open File"),
                                    DockMenuItem.separator(id = 3),
                                    DockMenuItem(
                                        id = 4,
                                        title = "Recent Files",
                                        children =
                                            listOf(
                                                DockMenuItem(id = 41, title = "project.kt"),
                                                DockMenuItem(id = 42, title = "build.gradle.kts"),
                                                DockMenuItem(id = 43, title = "README.md"),
                                            ),
                                    ),
                                    DockMenuItem.separator(id = 5),
                                    DockMenuItem(id = 6, title = "Preferences"),
                                    DockMenuItem.separator(id = 7),
                                    DockMenuItem(id = 8, title = "Disabled Item", enabled = false),
                                ),
                            )
                            dockMenuActive = true
                            log("Dock menu set")
                        }) { Text("Set Dock Menu") }

                        OutlinedButton(
                            onClick = {
                                MacOsDockMenu.clearDockMenu()
                                MacOsDockMenu.listener = null
                                dockMenuActive = false
                                log("Dock menu cleared")
                            },
                            enabled = dockMenuActive,
                        ) { Text("Clear") }
                    }

                    if (dockMenuActive) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Active — right-click the dock icon to see the menu.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            // Event Log
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Event Log", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (events.isEmpty()) {
                        Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        events.forEachIndexed { index, event ->
                            Text(event, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            if (index < events.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
