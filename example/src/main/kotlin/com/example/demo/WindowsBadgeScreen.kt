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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.nucleusframework.nucleus.launcher.windows.BadgeGlyph
import dev.nucleusframework.nucleus.launcher.windows.JumpListCategory
import dev.nucleusframework.nucleus.launcher.windows.JumpListItem
import dev.nucleusframework.nucleus.launcher.windows.StockIcon
import dev.nucleusframework.nucleus.launcher.windows.TaskbarIconSource
import dev.nucleusframework.nucleus.launcher.windows.ThumbnailToolbarButton
import dev.nucleusframework.nucleus.launcher.windows.WindowsBadgeManager
import dev.nucleusframework.nucleus.launcher.windows.WindowsJumpListManager
import dev.nucleusframework.nucleus.launcher.windows.WindowsOverlayIcon
import dev.nucleusframework.nucleus.launcher.windows.WindowsThumbnailToolbar
import java.awt.Window

private const val EVENT_LOG_MAX = 30

private val DEMO_ICONS =
    listOf(
        "Warning" to StockIcon.WARNING,
        "Error" to StockIcon.ERROR,
        "Info" to StockIcon.INFO,
        "Shield" to StockIcon.SHIELD,
        "Help" to StockIcon.HELP,
    )

@Suppress("FunctionNaming")
@Composable
fun WindowsLauncherScreen(window: Window) {
    val events = remember { mutableStateListOf<String>() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BadgeSection(events)
            HorizontalDivider()
            OverlayIconSection(window, events)
            HorizontalDivider()
            ThumbnailToolbarSection(window, events)
            HorizontalDivider()
            JumpListSection(events)
            HorizontalDivider()
            EventLogSection(events)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun BadgeSection(events: MutableList<String>) {
    var badgeStatus by remember { mutableStateOf("") }
    var badgeInitialized by remember { mutableStateOf(false) }
    var badgeCount by remember { mutableStateOf("3") }
    var selectedGlyph by remember { mutableStateOf(BadgeGlyph.ALERT) }

    DisposableEffect(Unit) {
        if (WindowsBadgeManager.isAvailable) {
            badgeInitialized = WindowsBadgeManager.initialize()
            val err = WindowsBadgeManager.lastError
            badgeStatus = if (badgeInitialized) "Initialized" else "Failed: $err"
            events.add(0, "badge init: ok=$badgeInitialized${if (err != null) " error=$err" else ""}")
        } else {
            badgeStatus = "Native library not available"
            events.add(0, "Badge native library not available")
        }
        onDispose { WindowsBadgeManager.uninitialize() }
    }

    Text("Badge Notifications", style = MaterialTheme.typography.headlineSmall)
    Text("Status: $badgeStatus", style = MaterialTheme.typography.bodyMedium)

    HorizontalDivider()

    Text("Numeric Badge", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            badgeCount,
            { badgeCount = it },
            label = { Text("Count") },
            modifier = Modifier.width(120.dp),
        )
        Button(
            onClick = {
                val count = badgeCount.toIntOrNull() ?: 0
                val ok = WindowsBadgeManager.setCount(count)
                val msg =
                    if (ok) "Badge set: $count" else "Badge FAILED: ${WindowsBadgeManager.lastError}"
                events.add(0, msg)
                if (events.size > EVENT_LOG_MAX) events.removeLast()
            },
            enabled = badgeInitialized,
        ) {
            Text("Set Count")
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 1, 3, 5, 10, 42, 99, 100).forEach { n ->
            OutlinedButton(
                onClick = {
                    val ok = WindowsBadgeManager.setCount(n)
                    events.add(0, if (ok) "Badge set: $n" else "Badge set FAILED: $n")
                    if (events.size > EVENT_LOG_MAX) events.removeLast()
                },
                enabled = badgeInitialized,
            ) {
                Text("$n")
            }
        }
    }

    HorizontalDivider()

    Text("Glyph Badge", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BadgeGlyph.entries.filter { it != BadgeGlyph.NONE }.forEach { glyph ->
            val colors =
                if (glyph == selectedGlyph) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
            Button(onClick = { selectedGlyph = glyph }, colors = colors) {
                Text(glyph.value, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    Button(
        onClick = {
            val ok = WindowsBadgeManager.setGlyph(selectedGlyph)
            val msg =
                if (ok) "Glyph set: ${selectedGlyph.value}" else "Glyph FAILED: ${WindowsBadgeManager.lastError}"
            events.add(0, msg)
            if (events.size > EVENT_LOG_MAX) events.removeLast()
        },
        enabled = badgeInitialized,
    ) {
        Text("Set Glyph (${selectedGlyph.value})")
    }

    OutlinedButton(
        onClick = {
            val ok = WindowsBadgeManager.clear()
            val msg = if (ok) "Badge cleared" else "Clear FAILED: ${WindowsBadgeManager.lastError}"
            events.add(0, msg)
            if (events.size > EVENT_LOG_MAX) events.removeLast()
        },
        enabled = badgeInitialized,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text("Clear Badge")
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun JumpListSection(events: MutableList<String>) {
    Text("Jump List", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Items launch the app with arguments via SingleInstanceManager.",
        style = MaterialTheme.typography.bodySmall,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val ok =
                WindowsJumpListManager.setJumpList(
                    categories =
                        listOf(
                            JumpListCategory(
                                name = "Recent Actions",
                                items =
                                    listOf(
                                        JumpListItem(
                                            title = "Open Dashboard",
                                            arguments = "nucleus://dashboard",
                                            description = "Open the main dashboard",
                                            icon = TaskbarIconSource.FromStock(StockIcon.WORLD),
                                        ),
                                        JumpListItem(
                                            title = "Open Settings",
                                            arguments = "nucleus://settings",
                                            description = "Open application settings",
                                            icon = TaskbarIconSource.FromStock(StockIcon.SETTINGS),
                                        ),
                                        JumpListItem(
                                            title = "View Logs",
                                            arguments = "nucleus://logs",
                                            description = "Open the log viewer",
                                            icon = TaskbarIconSource.FromStock(StockIcon.INFO),
                                        ),
                                    ),
                            ),
                        ),
                    tasks =
                        listOf(
                            JumpListItem(
                                title = "New Window",
                                arguments = "nucleus://new-window",
                                description = "Open a new application window",
                                icon = TaskbarIconSource.FromStock(StockIcon.DESKTOP_PC),
                            ),
                            JumpListItem.SEPARATOR,
                            JumpListItem(
                                title = "Check for Updates",
                                arguments = "nucleus://check-updates",
                                description = "Check for application updates",
                                icon = TaskbarIconSource.FromStock(StockIcon.SHIELD),
                            ),
                        ),
                )
            val msg =
                if (ok) "Jump list set" else "Jump list FAILED: ${WindowsJumpListManager.lastError}"
            events.add(0, msg)
            if (events.size > EVENT_LOG_MAX) events.removeLast()
        }) {
            Text("Set Jump List")
        }

        OutlinedButton(
            onClick = {
                val ok = WindowsJumpListManager.clearJumpList()
                val msg =
                    if (ok) "Jump list cleared" else "Clear FAILED: ${WindowsJumpListManager.lastError}"
                events.add(0, msg)
                if (events.size > EVENT_LOG_MAX) events.removeLast()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Clear Jump List")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming")
@Composable
private fun OverlayIconSection(
    window: Window,
    events: MutableList<String>,
) {
    Text("Overlay Icon", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Small 16x16 status icon on the taskbar button (works without MSIX)",
        style = MaterialTheme.typography.bodySmall,
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DEMO_ICONS.forEach { (label, stockIcon) ->
            Button(onClick = {
                val ok =
                    WindowsOverlayIcon.setIcon(
                        window,
                        TaskbarIconSource.FromStock(stockIcon),
                        description = label,
                    )
                val msg = if (ok) "Overlay: $label" else "Overlay FAILED: ${WindowsOverlayIcon.lastError}"
                events.add(0, msg)
                if (events.size > EVENT_LOG_MAX) events.removeLast()
            }) { Text(label) }
        }
    }

    OutlinedButton(
        onClick = {
            val ok = WindowsOverlayIcon.clearIcon(window)
            val msg = if (ok) "Overlay cleared" else "Clear FAILED: ${WindowsOverlayIcon.lastError}"
            events.add(0, msg)
            if (events.size > EVENT_LOG_MAX) events.removeLast()
        },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Clear Overlay") }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ThumbnailToolbarSection(
    window: Window,
    events: MutableList<String>,
) {
    var toolbarAdded by remember { mutableStateOf(false) }

    Text("Thumbnail Toolbar", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Buttons in the taskbar thumbnail preview (hover over taskbar icon to see)",
        style = MaterialTheme.typography.bodySmall,
    )

    DisposableEffect(Unit) {
        onDispose {
            if (toolbarAdded) WindowsThumbnailToolbar.unregister(window)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val buttons =
                    DEMO_ICONS.mapIndexed { index, (label, stockIcon) ->
                        ThumbnailToolbarButton(
                            id = index,
                            tooltip = label,
                            icon = TaskbarIconSource.FromStock(stockIcon),
                        )
                    }
                val ok =
                    WindowsThumbnailToolbar.setButtons(window, buttons) { buttonId ->
                        val name = DEMO_ICONS.getOrNull(buttonId)?.first ?: "?"
                        events.add(0, "Toolbar click: $name (id=$buttonId)")
                        if (events.size > EVENT_LOG_MAX) events.removeLast()
                    }
                toolbarAdded = ok
                val msg =
                    if (ok) {
                        "Toolbar added — hover taskbar icon"
                    } else {
                        "Toolbar FAILED: ${WindowsThumbnailToolbar.lastError}"
                    }
                events.add(0, msg)
                if (events.size > EVENT_LOG_MAX) events.removeLast()
            },
            enabled = !toolbarAdded,
        ) { Text("Add Toolbar Buttons") }

        OutlinedButton(
            onClick = {
                WindowsThumbnailToolbar.unregister(window)
                toolbarAdded = false
                events.add(0, "Toolbar unregistered")
                if (events.size > EVENT_LOG_MAX) events.removeLast()
            },
            enabled = toolbarAdded,
        ) { Text("Unregister") }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun EventLogSection(events: List<String>) {
    Text("Event Log", style = MaterialTheme.typography.titleMedium)
    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()),
        ) {
            if (events.isEmpty()) {
                Text("No events yet", style = MaterialTheme.typography.bodySmall)
            } else {
                events.forEach { event ->
                    Text(event, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
