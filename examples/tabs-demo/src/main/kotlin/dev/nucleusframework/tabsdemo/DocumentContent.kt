package dev.nucleusframework.tabsdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.window.tao.TabScope
import dev.nucleusframework.window.tao.TabWorkspace
import kotlin.math.roundToInt

/**
 * The body of one tab: an editor whose state has to survive being dragged to
 * another window, plus the workspace controls and a live read-out of what the
 * workspace thinks is going on.
 *
 * Composed by `TabWindows` in whichever window holds the tab — the same call
 * site in every window, which is what lets the `rememberSaveable` values below
 * be carried across a move.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabScope.DocumentContent(
    demo: DemoState,
    document: Document,
) {
    val workspace = demo.workspace
    val group = tab.group

    // Saveable: carried to the next window by the workspace.
    var draft by rememberSaveable { mutableStateOf(document.draft) }
    var savedClicks by rememberSaveable { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    // Not saveable, on purpose: the counterexample. A move rebuilds this
    // subtree in the other window's composition, and a plain `remember`
    // starts over there.
    var plainClicks by remember { mutableIntStateOf(0) }

    val window = LocalNucleusWindow.current
    val density = LocalDensity.current.density

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(document.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every document of this demo is declared once as a tab; the workspace decides " +
                "which window shows it. Drag this tab out of the strip and drop it on the " +
                "desktop: it lands in a window of its own. Drag it back onto the other " +
                "window's strip and it is inserted where you drop it. Drag the only tab of a " +
                "window and the window itself follows the pointer, then merges into the strip " +
                "it lands on — Chrome, exactly.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Section("This tab") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { demo.open() }) { Text("New tab") }
                OutlinedButton(onClick = { select() }, enabled = !tab.isSelected) { Text("Select") }
                OutlinedButton(
                    onClick = { moveToOwnWindow(workspace, tab.id, window, density) },
                    enabled = (group?.ids?.size ?: 0) > 1,
                ) {
                    Text("Move to its own window")
                }
                TextButton(onClick = { close() }) { Text("Close this tab") }
            }
            Text(
                "“Move to its own window” is the tear-off a drag performs, called directly: " +
                    "the workspace opens the window, so the app never does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("State that follows the tab") {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("rememberSaveable draft") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { savedClicks++ }) { Text("saveable: $savedClicks") }
                OutlinedButton(onClick = { plainClicks++ }) { Text("plain remember: $plainClicks") }
            }
            Text(
                "Type something, click both counters, scroll down a little, then drag this tab " +
                    "into the other window. The draft, the saveable counter and the scroll " +
                    "position come back; the plain one restarts at 0 — the two windows are two " +
                    "compositions, and only saveable state crosses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Layout") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { demo.saveLayout() }) { Text("Save layout") }
                OutlinedButton(
                    onClick = { demo.restoreLayout() },
                    enabled = demo.savedLayout != null,
                ) {
                    Text("Restore layout")
                }
            }
            Text(
                "A snapshot holds every window, the tabs it had in strip order, which one was " +
                    "selected and where the window sat. Spread the tabs over three windows, " +
                    "save, merge everything back into one, then restore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Live state") {
            StateLine("windows", workspace.groups.size.toString())
            StateLine("tabs, all windows", workspace.tabs.size.toString())
            StateLine("this window's group", group?.id ?: "—")
            StateLine("its tabs", group?.ids?.joinToString(", ") ?: "—")
            StateLine("this window at", window.describeBounds())
            StateLine("dragging", workspace.draggedTab?.title ?: "—")
            StateLine(
                "drop preview",
                workspace.dropPreview?.let { "${it.group.id} @ ${it.index}" } ?: "—",
            )
        }

        // Something to scroll past, so the saved scroll position is visible.
        Section("Notes") {
            for (line in 1..NOTE_LINES) {
                Text("$line. ${document.title} — line $line", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * `TabWorkspace.tearOff` driven from a button: the host window's own frame,
 * nudged down and to the right.
 *
 * The portable window handle reports dp and `tearOff` takes physical screen
 * pixels, hence the density — a drag gets the same rect from the pointer.
 */
private fun moveToOwnWindow(
    workspace: TabWorkspace,
    tabId: String,
    window: NucleusWindow,
    density: Float,
) {
    val bounds = window.boundsOnScreen() ?: return
    val left = (bounds.x + TEAR_OFF_OFFSET_DP) * density
    val top = (bounds.y + TEAR_OFF_OFFSET_DP) * density
    workspace.tearOff(
        tabId = tabId,
        screenRectPx = Rect(left, top, left + bounds.width * density, top + bounds.height * density),
        scaleFactor = density,
    )
}

private fun NucleusWindow.describeBounds(): String =
    boundsOnScreen()?.let { "${it.x.roundToInt()}, ${it.y.roundToInt()} dp" } ?: "—"

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StateLine(
    name: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private const val TEAR_OFF_OFFSET_DP = 48f
private const val NOTE_LINES = 24
