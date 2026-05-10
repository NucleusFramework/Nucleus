package com.example.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.nucleus.launcher.linux.DbusmenuItem
import dev.nucleusframework.nucleus.launcher.linux.LauncherProperties
import dev.nucleusframework.nucleus.launcher.linux.LinuxLauncherEntry
import dev.nucleusframework.nucleus.launcher.linux.LinuxQuicklist

private const val EVENT_LOG_MAX = 20

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun LauncherScreen() {
    val events = remember { mutableStateListOf<String>() }
    var desktopFileId by remember { mutableStateOf("NucleusDemo.desktop") }
    var count by remember { mutableStateOf(0L) }
    var countVisible by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressVisible by remember { mutableStateOf(false) }
    var urgent by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }

    fun appUri(): String = LinuxLauncherEntry.appUri(desktopFileId)

    fun log(msg: String) {
        events.add(0, msg)
        if (events.size > EVENT_LOG_MAX) events.removeRange(EVENT_LOG_MAX, events.size)
    }

    // Quicklist server
    val quicklist =
        remember {
            LinuxQuicklist("/com/example/NucleusDemo/Menu")
        }
    var quicklistActive by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(true) }

    // Register Query handler on mount, unregister on dispose
    DisposableEffect(Unit) {
        if (LinuxLauncherEntry.isAvailable) {
            LinuxLauncherEntry.registerQueryHandler(LinuxLauncherEntry.appUri(desktopFileId))
        }
        onDispose {
            quicklist.dispose()
            LinuxLauncherEntry.unregister()
        }
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
            Text("Unity Launcher API", style = MaterialTheme.typography.headlineSmall)

            if (!LinuxLauncherEntry.isAvailable) {
                Text(
                    "Native library not available.",
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            // -- Desktop File ID --
            LauncherSectionCard("Application") {
                OutlinedTextField(
                    value = desktopFileId,
                    onValueChange = { desktopFileId = it },
                    label = { Text("Desktop File ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "URI: ${appUri()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // -- Quick Actions --
            LauncherSectionCard("Quick Actions") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        count = 42
                        countVisible = true
                        val ok = LinuxLauncherEntry.setCount(appUri(), 42)
                        log("Set count=42 visible=true -> $ok")
                    }) { Text("Badge 42") }

                    Button(onClick = {
                        countVisible = false
                        val ok = LinuxLauncherEntry.clearCount(appUri())
                        log("Clear count -> $ok")
                    }) { Text("Clear Badge") }

                    Button(onClick = {
                        progressVisible = true
                        progress = 0.65f
                        val ok = LinuxLauncherEntry.setProgress(appUri(), 0.65)
                        log("Set progress=65% visible=true -> $ok")
                    }) { Text("Progress 65%") }

                    Button(onClick = {
                        progressVisible = false
                        progress = 0f
                        val ok = LinuxLauncherEntry.clearProgress(appUri())
                        log("Clear progress -> $ok")
                    }) { Text("Clear Progress") }

                    Button(
                        onClick = {
                            urgent = true
                            val ok = LinuxLauncherEntry.setUrgent(appUri(), true)
                            log("Set urgent=true -> $ok")
                        },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                    ) { Text("Urgent") }

                    OutlinedButton(onClick = {
                        urgent = false
                        val ok = LinuxLauncherEntry.setUrgent(appUri(), false)
                        log("Set urgent=false -> $ok")
                    }) { Text("Clear Urgent") }

                    Button(onClick = {
                        updating = true
                        val ok = LinuxLauncherEntry.setUpdating(appUri(), true)
                        log("Set updating=true -> $ok")
                    }) { Text("Updating") }

                    OutlinedButton(onClick = {
                        updating = false
                        val ok = LinuxLauncherEntry.setUpdating(appUri(), false)
                        log("Set updating=false -> $ok")
                    }) { Text("Clear Updating") }

                    OutlinedButton(onClick = {
                        count = 0
                        countVisible = false
                        progress = 0f
                        progressVisible = false
                        urgent = false
                        updating = false
                        val ok =
                            LinuxLauncherEntry.update(
                                appUri(),
                                LauncherProperties(
                                    count = 0L,
                                    countVisible = false,
                                    progress = 0.0,
                                    progressVisible = false,
                                    urgent = false,
                                    updating = false,
                                ),
                            )
                        log("Reset all -> $ok")
                    }) { Text("Reset All") }
                }
            }

            // -- Custom Update --
            LauncherSectionCard("Custom Update") {
                // Count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Count:", modifier = Modifier.width(80.dp))
                    OutlinedTextField(
                        value = count.toString(),
                        onValueChange = { count = it.toLongOrNull() ?: 0L },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                    Switch(
                        checked = countVisible,
                        onCheckedChange = { countVisible = it },
                    )
                    Text("Visible")
                }

                Spacer(Modifier.height(8.dp))

                // Progress
                Text(
                    "Progress: ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = progress,
                        onValueChange = { progress = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = progressVisible,
                        onCheckedChange = { progressVisible = it },
                    )
                    Text("Visible")
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                // Flags
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = urgent, onCheckedChange = { urgent = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Urgent")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = updating, onCheckedChange = { updating = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Updating")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(onClick = {
                    val ok =
                        LinuxLauncherEntry.update(
                            appUri(),
                            LauncherProperties(
                                count = count,
                                countVisible = countVisible,
                                progress = progress.toDouble(),
                                progressVisible = progressVisible,
                                urgent = urgent,
                                updating = updating,
                            ),
                        )
                    log(
                        "Update: count=$count countVisible=$countVisible " +
                            "progress=${(progress * 100).toInt()}% " +
                            "progressVisible=$progressVisible " +
                            "urgent=$urgent updating=$updating -> $ok",
                    )
                }) { Text("Send Update") }
            }

            // -- Quicklist (Dbusmenu) --
            LauncherSectionCard("Quicklist (com.canonical.dbusmenu)") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        log("Setting quicklist...")

                        fun buildMenu() =
                            listOf(
                                DbusmenuItem(id = 1, label = "New Window", icon = FreedesktopIcon.Action.WINDOW_NEW),
                                DbusmenuItem(id = 2, label = "Open File", icon = FreedesktopIcon.Action.DOCUMENT_OPEN),
                                DbusmenuItem.separator(id = 3),
                                DbusmenuItem(
                                    id = 4,
                                    label = "Recent",
                                    icon = FreedesktopIcon.Action.DOCUMENT_OPEN_RECENT,
                                    children =
                                        listOf(
                                            DbusmenuItem(id = 41, label = "project.kt"),
                                            DbusmenuItem(id = 42, label = "build.gradle.kts"),
                                            DbusmenuItem(id = 43, label = "README.md"),
                                        ),
                                ),
                                DbusmenuItem.separator(id = 5),
                                DbusmenuItem(
                                    id = 6,
                                    label = "Dark Mode",
                                    toggleType = DbusmenuItem.ToggleType.CHECKBOX,
                                    toggleState = if (darkMode) 1 else 0,
                                ),
                                DbusmenuItem.separator(id = 7),
                                DbusmenuItem(
                                    id = 8,
                                    label = "Quit",
                                    icon = FreedesktopIcon.Action.APPLICATION_EXIT,
                                    disposition = DbusmenuItem.Disposition.ALERT,
                                ),
                            )

                        quicklist.listener =
                            LinuxQuicklist.Listener { id ->
                                when (id) {
                                    6 -> {
                                        darkMode = !darkMode
                                        log("Dark Mode toggled: $darkMode")
                                        quicklist.setMenu(buildMenu())
                                    }
                                    else -> log("Quicklist item clicked: id=$id")
                                }
                            }

                        val ok = quicklist.setMenu(buildMenu())
                        if (ok) {
                            LinuxLauncherEntry.update(
                                appUri(),
                                LauncherProperties(quicklist = quicklist.objectPath),
                            )
                            quicklistActive = true
                            log("Quicklist active at ${quicklist.objectPath}")
                        } else {
                            log("Quicklist registration failed")
                        }
                    }) { Text("Set Quicklist") }

                    OutlinedButton(
                        onClick = {
                            quicklist.setMenu(emptyList())
                            quicklistActive = false
                            log("Quicklist cleared")
                        },
                        enabled = quicklistActive,
                    ) { Text("Clear Quicklist") }
                }
                if (quicklistActive) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Active at: ${quicklist.objectPath}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Right-click the app icon in the dock to see the menu. " +
                        "Click events depend on DE support (Plank, KDE yes / GNOME no).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -- Event Log --
            LauncherSectionCard("Event Log") {
                if (events.isEmpty()) {
                    Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    events.forEachIndexed { index, event ->
                        Text(
                            event,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (index < events.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
