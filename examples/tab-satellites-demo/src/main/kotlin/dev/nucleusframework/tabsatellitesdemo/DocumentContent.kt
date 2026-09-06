package dev.nucleusframework.tabsatellitesdemo

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TabScope
import kotlin.math.roundToInt

/**
 * The body of one tab, and the document half of the seam between the two
 * archetypes.
 *
 * The window itself joined its satellite workspace when it opened (`Main.kt`),
 * so what is left here is a [DockLayout] for the docked palettes to live in and
 * the controls that show, hide, dock and float them. Note what is *not* here:
 * nothing that starts or stops a palette. Joining the workspace from the tab
 * body instead would tie the palettes' existence to the selected tab, and every
 * tab change would destroy a native window and create another.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabScope.DocumentContent(
    demo: DemoState,
    document: Document,
) {
    // The workspace of the *window* this tab is composed in — the window joined
    // it once when it opened (see `Main.kt`), so nothing here starts or stops a
    // palette; this only gives the docked ones somewhere to live and the
    // controls something to act on.
    val group = tab.group
    val satellites = group?.let { demo.satellitesOfWindow(it.id) }

    val hostWindow = LocalNucleusWindow.current
    var edits by rememberSaveable { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        DockLayoutOrPlain(satellites) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(document.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Each document says which satellites it wants: Scene.kt asks for both, " +
                        "Shader.glsl for the Inspector only, notes.md for none. The entries " +
                        "themselves belong to this window, so switching between two documents that " +
                        "want the same palette only changes what it draws — and each document " +
                        "brings its own values back. Drag this tab into a window of its own and it " +
                        "arrives with palettes of its own: two windows, two independent sets.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Section("This document's satellites") {
                    if (group != null && satellites != null) {
                        if (document.satellites.isEmpty()) {
                            Text(
                                "This document asks for none, so this window shows none while it " +
                                    "is the selected tab.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        for (kind in document.satellites) {
                            SatelliteRow(satellites, kind.idIn(group.id), kind.label)
                        }
                        val absent = SatelliteKind.entries.filterNot { it in document.satellites }
                        if (absent.isNotEmpty()) {
                            Text(
                                "Not asked for by this document: ${absent.joinToString { it.label }}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "No tab is obliged to have satellites. The entries are declared per window, " +
                            "so switching tabs creates and destroys nothing; which of them are open " +
                            "is per document, so a palette only appears or disappears when the two " +
                            "documents actually disagree about it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Section("This tab") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { demo.open() }) { Text("New tab") }
                        OutlinedButton(onClick = { edits++ }) { Text("edits: $edits") }
                        TextButton(onClick = { close() }) { Text("Close this tab") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { demo.saveLayout() }) { Text("Save tab layout") }
                        OutlinedButton(
                            onClick = { demo.restoreLayout() },
                            enabled = demo.savedLayout != null,
                        ) {
                            Text("Restore")
                        }
                    }
                }

                Section("Live state") {
                    StateLine(
                        "tab windows",
                        demo.tabs.groups.size
                            .toString(),
                    )
                    StateLine("this window's group", group?.id ?: "—")
                    StateLine("tabs in this window", (group?.ids?.size ?: 0).toString())
                    StateLine("this window at", hostWindow.describeBounds())
                    StateLine("workspace members", (satellites?.members?.size ?: 0).toString())
                    StateLine(
                        "owner is this window",
                        (satellites?.owner === hostWindow.unsafe.taoWindow).toString(),
                    )
                    val entries = satellites?.satellites?.sortedBy { it.id }.orEmpty()
                    for (entry in entries) {
                        StateLine(
                            entry.id.removePrefix("${group?.id}-"),
                            buildString {
                                append(if (entry.isOpen) "open" else "hidden")
                                if (entry.isDocked) {
                                    append(", docked ${entry.preferredDockSide.name.lowercase()}")
                                } else {
                                    append(", floating")
                                }
                            },
                        )
                    }
                    for (side in DockSide.entries) {
                        StateLine(
                            "dock extent ${side.name.lowercase()}",
                            "${(satellites?.dockExtent(side)?.value ?: 0f).roundToInt()} dp",
                        )
                    }
                }
            }
        }
    }
}

/**
 * [DockLayout] when this window has a workspace — it has one from its second
 * frame, the first being the one where the window has not yet been recorded by
 * the tab workspace.
 */
@Composable
private fun DockLayoutOrPlain(
    satellites: SatelliteWorkspace?,
    content: @Composable () -> Unit,
) {
    if (satellites == null) {
        content()
    } else {
        DockLayout(satellites, Modifier.fillMaxSize(), content = content)
    }
}

/** Show / hide and dock / float for one satellite of this window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SatelliteRow(
    workspace: SatelliteWorkspace,
    id: String,
    label: String,
) {
    val entry = workspace.satellite(id)
    val docked = entry?.isDocked == true
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelLarge)
        Button(onClick = { workspace.toggle(id) }, enabled = entry != null) {
            Text(if (entry?.isOpen == true) "Hide" else "Show")
        }
        OutlinedButton(
            onClick = {
                if (docked) workspace.undock(id) else workspace.dock(id, entry?.preferredDockSide ?: DockSide.Right)
            },
            enabled = entry != null,
        ) {
            Text(if (docked) "Float" else "Dock")
        }
        for (side in DockSide.entries) {
            TextButton(onClick = { workspace.dock(id, side) }, enabled = entry != null) { Text(side.name) }
        }
    }
}

private fun dev.nucleusframework.application.NucleusWindow.describeBounds(): String =
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
