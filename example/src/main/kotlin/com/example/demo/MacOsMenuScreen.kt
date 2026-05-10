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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.menu.macos.NativeKeyShortcut
import dev.nucleusframework.nucleus.menu.macos.NativeMenuBar
import dev.nucleusframework.nucleus.menu.macos.NsMenuItemBadge
import dev.nucleusframework.nucleus.menu.macos.NsMenuItemImage
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolArrows
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolDevices
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolGeneral
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolMedia
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolObjectsAndTools
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolPower
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolShapes
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolStatus
import dev.nucleusframework.nucleus.sfsymbols.SFSymbolTextFormatting

private const val EVENT_LOG_MAX = 40

@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun MacOsMenuScreen() {
    val events = remember { mutableStateListOf<String>() }

    fun log(msg: String) {
        events.add(0, msg)
        if (events.size > EVENT_LOG_MAX) events.removeRange(EVENT_LOG_MAX, events.size)
    }

    // Reactive state driving the menu bar — changes here recompose the menu bar instantly
    var menuBarActive by remember { mutableStateOf(false) }
    var showToolbar by remember { mutableStateOf(true) }
    var showStatusBar by remember { mutableStateOf(false) }
    var showNavigator by remember { mutableStateOf(true) }
    var advancedEnabled by remember { mutableStateOf(false) }
    var selectedTheme by remember { mutableStateOf("System") }
    var inboxCount by remember { mutableStateOf(42) }
    var customMenuTitle by remember { mutableStateOf("File") }
    var fileMenuItemCount by remember { mutableStateOf(3) }
    var showBadgesMenu by remember { mutableStateOf(true) }

    // ── Native Menu Bar (reactive — rebuilds when state changes) ──
    if (menuBarActive) {
        NativeMenuBar {
            // ── App menu ──
            Menu("Nucleus Demo") {
                Item(
                    "About Nucleus Demo",
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.INFO_CIRCLE),
                ) { log("About") }
                Separator()
                Item(
                    "Settings...",
                    shortcut = NativeKeyShortcut(","),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.GEARSHAPE),
                ) {
                    log("Settings")
                }
                Separator()
                Menu("Services") { /* macOS auto-populates */ }
                Separator()
                Item("Hide Nucleus Demo", shortcut = NativeKeyShortcut("h")) { log("Hide") }
                Item(
                    "Hide Others",
                    shortcut = NativeKeyShortcut("h", option = true),
                ) { log("Hide Others") }
                Item("Show All") { log("Show All") }
                Separator()
                Item(
                    "Quit Nucleus Demo",
                    shortcut = NativeKeyShortcut("q"),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolPower.POWER),
                ) {
                    log("Quit")
                }
            }

            // ── File (title + item count driven by UI controls) ──
            Menu(customMenuTitle) {
                Item("New", shortcut = NativeKeyShortcut("n"), icon = NsMenuItemImage.SystemSymbol("doc.badge.plus")) {
                    log("$customMenuTitle > New")
                }
                if (fileMenuItemCount >= 2) {
                    Item(
                        "Open...",
                        shortcut = NativeKeyShortcut("o"),
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.FOLDER),
                    ) {
                        log("$customMenuTitle > Open")
                    }
                }
                if (fileMenuItemCount >= 3) {
                    Menu("Open Recent", icon = NsMenuItemImage.SystemSymbol("clock.arrow.circlepath")) {
                        Item(
                            "project.kt",
                            icon = NsMenuItemImage.SystemSymbol("doc.text"),
                        ) { log("$customMenuTitle > Recent > project.kt") }
                        Item(
                            "build.gradle.kts",
                            icon = NsMenuItemImage.SystemSymbol("doc.text"),
                        ) { log("$customMenuTitle > Recent > build.gradle.kts") }
                        Separator()
                        Item(
                            "Clear Menu",
                            icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.XMARK_CIRCLE),
                        ) { log("$customMenuTitle > Clear Recent") }
                    }
                }
                if (fileMenuItemCount >= 4) {
                    Separator()
                    Item(
                        "Save",
                        shortcut = NativeKeyShortcut("s"),
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolShapes.SQUARE_AND_ARROW_DOWN),
                    ) {
                        log("$customMenuTitle > Save")
                    }
                }
                if (fileMenuItemCount >= 5) {
                    Item(
                        "Save As...",
                        shortcut = NativeKeyShortcut("s", shift = true),
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolShapes.SQUARE_AND_ARROW_DOWN_ON_SQUARE),
                    ) { log("$customMenuTitle > Save As") }
                    Separator()
                    Item(
                        "Print...",
                        shortcut = NativeKeyShortcut("p"),
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolDevices.PRINTER),
                    ) {
                        log("$customMenuTitle > Print")
                    }
                }
            }

            // ── Edit ──
            Menu("Edit") {
                Item(
                    "Undo",
                    shortcut = NativeKeyShortcut("z"),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_UTURN_BACKWARD),
                ) {
                    log("Edit > Undo")
                }
                Item(
                    "Redo",
                    shortcut = NativeKeyShortcut("z", shift = true),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_UTURN_FORWARD),
                ) { log("Edit > Redo") }
                Separator()
                Item(
                    "Cut",
                    shortcut = NativeKeyShortcut("x"),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.SCISSORS),
                ) {
                    log("Edit > Cut")
                }
                Item("Copy", shortcut = NativeKeyShortcut("c"), icon = NsMenuItemImage.SystemSymbol("doc.on.doc")) {
                    log("Edit > Copy")
                }
                Item(
                    "Paste",
                    shortcut = NativeKeyShortcut("v"),
                    icon = NsMenuItemImage.SystemSymbol("doc.on.clipboard"),
                ) {
                    log("Edit > Paste")
                }
                // Alternate item — shown when Option is held
                Item(
                    "Paste and Match Style",
                    shortcut = NativeKeyShortcut("v", option = true, shift = true),
                    isAlternate = true,
                ) { log("Edit > Paste & Match Style") }
                Item(
                    "Delete",
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.DELETE_LEFT),
                ) { log("Edit > Delete") }
                Item("Select All", shortcut = NativeKeyShortcut("a")) { log("Edit > Select All") }
                Separator()
                Menu("Find", icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.MAGNIFYINGGLASS)) {
                    Item("Find...", shortcut = NativeKeyShortcut("f")) { log("Edit > Find") }
                    Item("Find and Replace...", shortcut = NativeKeyShortcut("f", option = true)) {
                        log("Edit > Find & Replace")
                    }
                    Item("Find Next", shortcut = NativeKeyShortcut("g")) {
                        log("Edit > Find Next")
                    }
                    Item(
                        "Find Previous",
                        shortcut = NativeKeyShortcut("g", shift = true),
                    ) { log("Edit > Find Previous") }
                }
                Menu("Transform", icon = NsMenuItemImage.SystemSymbol(SFSymbolTextFormatting.TEXTFORMAT)) {
                    Item("Make Uppercase", shortcut = NativeKeyShortcut("u", shift = true)) { log("Edit > Uppercase") }
                    Item("Make Lowercase") { log("Edit > Lowercase") }
                    Item("Capitalize") { log("Edit > Capitalize") }
                }
            }

            // ── View (reactive checkboxes + radio + indentation) ──
            Menu("View") {
                SectionHeader("Panels")
                CheckboxItem(
                    "Show Toolbar",
                    checked = showToolbar,
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.SIDEBAR_LEFT),
                    onCheckedChange = {
                        showToolbar = it
                        log("View > Toolbar = $it")
                    },
                )
                CheckboxItem(
                    "Show Status Bar",
                    checked = showStatusBar,
                    icon = NsMenuItemImage.SystemSymbol("rectangle.bottomhalf.inset.filled"),
                    onCheckedChange = {
                        showStatusBar = it
                        log("View > Status Bar = $it")
                    },
                )
                CheckboxItem(
                    "Show Navigator",
                    checked = showNavigator,
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.SIDEBAR_LEADING),
                    toolTip = "Toggle the navigator panel",
                    onCheckedChange = {
                        showNavigator = it
                        log("View > Navigator = $it")
                    },
                )

                Separator()
                SectionHeader("Theme")
                RadioButtonItem(
                    "System",
                    selected = selectedTheme == "System",
                    onClick = {
                        selectedTheme = "System"
                        log("View > Theme = System")
                    },
                )
                RadioButtonItem(
                    "Light",
                    selected = selectedTheme == "Light",
                    onClick = {
                        selectedTheme = "Light"
                        log("View > Theme = Light")
                    },
                )
                RadioButtonItem(
                    "Dark",
                    selected = selectedTheme == "Dark",
                    onClick = {
                        selectedTheme = "Dark"
                        log("View > Theme = Dark")
                    },
                )

                Separator()
                SectionHeader("Indentation")
                Item(
                    "Project",
                    indentationLevel = 0,
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.FOLDER),
                ) { log("View > Project") }
                Item("Source Files", indentationLevel = 1, icon = NsMenuItemImage.SystemSymbol("doc.text")) {
                    log("View > Source")
                }
                Item("Resources", indentationLevel = 1, icon = NsMenuItemImage.SystemSymbol(SFSymbolMedia.PHOTO)) {
                    log("View > Resources")
                }
                Item(
                    "Tests",
                    indentationLevel = 2,
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.CHECKMARK_CIRCLE),
                ) {
                    log("View > Tests")
                }

                Separator()
                Item(
                    "Enter Full Screen",
                    shortcut = NativeKeyShortcut("f", control = true),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_UP_BACKWARD_AND_ARROW_DOWN_FORWARD),
                ) { log("View > Full Screen") }
            }

            // ── Badges (conditionally shown) ──
            if (showBadgesMenu) {
                Menu("Badges") {
                    Item(
                        "Inbox",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.TRAY_FILL),
                        badge = NsMenuItemBadge.Count(inboxCount),
                    ) { log("Badges > Inbox ($inboxCount)") }
                    Item(
                        "Software Update",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_DOWN_CIRCLE),
                        badge = NsMenuItemBadge.updates(3),
                    ) { log("Badges > Update") }
                    Item(
                        "Security Alerts",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.EXCLAMATIONMARK_TRIANGLE),
                        badge = NsMenuItemBadge.alerts(1),
                    ) { log("Badges > Alerts") }
                    Item(
                        "Downloads",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_DOWN_TO_LINE),
                        badge = NsMenuItemBadge.newItems(7),
                    ) { log("Badges > Downloads") }
                    Item(
                        "Build Status",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.HAMMER),
                        badge = NsMenuItemBadge.Text("PASS"),
                    ) { log("Badges > Build") }
                    Separator()
                    Item("Disabled Entry", enabled = false, icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.NOSIGN))
                    Item(
                        "With Subtitle",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolTextFormatting.TEXT_ALIGNLEFT),
                        subtitle = "This is a subtitle (macOS 14.4+)",
                    ) { log("Badges > Subtitle") }
                    Item(
                        "With Tooltip (hover me)",
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.QUESTIONMARK_CIRCLE),
                        toolTip = "Tooltip on hover!",
                    ) { log("Badges > Tooltip") }
                    Item(
                        "Multi-Modifier",
                        shortcut = NativeKeyShortcut("k", shift = true, option = true),
                        icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.COMMAND),
                    ) { log("Badges > Multi-Modifier") }
                    Item(
                        "Hidden (has shortcut)",
                        shortcut = NativeKeyShortcut("h", shift = true, option = true),
                        isHidden = true,
                    ) { log("Badges > Hidden shortcut triggered") }
                }
            }

            // ── Advanced (conditional submenu driven by state) ──
            Menu("Advanced") {
                CheckboxItem(
                    "Enable Advanced",
                    checked = advancedEnabled,
                    onCheckedChange = {
                        advancedEnabled = it
                        log("Advanced > Enabled = $it")
                    },
                )
                if (advancedEnabled) {
                    Separator()
                    Menu("Settings") {
                        Item("Setting 1") { log("Advanced > Setting 1") }
                        Item("Setting 2") { log("Advanced > Setting 2") }
                        Item("Setting 3") { log("Advanced > Setting 3") }
                    }
                    Item("Reset All", icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_COUNTERCLOCKWISE)) {
                        log("Advanced > Reset All")
                    }
                }
            }

            // ── Window ──
            Menu("Window") {
                Item("Minimize", shortcut = NativeKeyShortcut("m")) { log("Window > Minimize") }
                Item("Zoom") { log("Window > Zoom") }
                Separator()
                Item("Bring All to Front") { log("Window > Bring All to Front") }
            }

            // ── Help ──
            Menu("Help") {
                Item(
                    "Nucleus Demo Help",
                    shortcut = NativeKeyShortcut("?"),
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.QUESTIONMARK_CIRCLE),
                ) { log("Help") }
                Separator()
                Item(
                    "Documentation",
                    icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.BOOK),
                ) { log("Help > Docs") }
                Item(
                    "Release Notes",
                    icon = NsMenuItemImage.SystemSymbol("doc.plaintext"),
                ) { log("Help > Release Notes") }
                Item("Report an Issue...", icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.EXCLAMATIONMARK_BUBBLE)) {
                    log("Help > Report")
                }
            }
        }
    }

    // ── UI ──
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("NSMenu — Compose API", style = MaterialTheme.typography.headlineSmall)

            // Controls
            MenuCard("Menu Bar") {
                Text(
                    "All controls below modify Compose state that the menu bar reads. " +
                        "The native menu bar recomposes instantly.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        menuBarActive = true
                        log("Menu bar installed")
                    }, enabled = !menuBarActive) { Text("Install") }

                    OutlinedButton(onClick = {
                        menuBarActive = false
                        log("Menu bar restored to original")
                    }, enabled = menuBarActive) { Text("Restore Original") }
                }
            }

            if (menuBarActive) {
                MenuCard("Live Recomposition") {
                    // Menu title
                    OutlinedTextField(
                        value = customMenuTitle,
                        onValueChange = { customMenuTitle = it },
                        label = { Text("Second menu title (try typing)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    // Item count slider
                    Text("Items in \"$customMenuTitle\" menu: $fileMenuItemCount")
                    Slider(
                        value = fileMenuItemCount.toFloat(),
                        onValueChange = { fileMenuItemCount = it.toInt() },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                    Spacer(Modifier.height(8.dp))

                    // Inbox badge
                    Text("Inbox badge count: $inboxCount")
                    Slider(
                        value = inboxCount.toFloat(),
                        onValueChange = { inboxCount = it.toInt() },
                        valueRange = 0f..99f,
                    )
                    Spacer(Modifier.height(8.dp))

                    // Toggle entire menus
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showBadgesMenu, onCheckedChange = { showBadgesMenu = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Show Badges menu")
                    }

                    // View checkboxes (also editable from here)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showToolbar, onCheckedChange = { showToolbar = it })
                        Spacer(Modifier.width(4.dp))
                        Text("View > Show Toolbar")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showStatusBar, onCheckedChange = { showStatusBar = it })
                        Spacer(Modifier.width(4.dp))
                        Text("View > Show Status Bar")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = advancedEnabled, onCheckedChange = { advancedEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Advanced > Enable (adds Settings submenu)")
                    }
                }
            }

            // Event Log
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Event Log", style = MaterialTheme.typography.titleMedium)
                        if (events.isNotEmpty()) {
                            OutlinedButton(onClick = { events.clear() }) { Text("Clear") }
                        }
                    }
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

@Composable
private fun MenuCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
