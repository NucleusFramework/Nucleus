package dev.nucleusframework.satellitedemo

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteEntry
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import kotlin.math.roundToInt

/**
 * The control panel inside a document window. Every control here is a call on
 * the shared [SatelliteWorkspace], so its effect shows on whichever document
 * owns or hosts the satellites.
 */
@Composable
fun DocumentContent(
    demo: DemoState,
    documentId: DocumentId,
) {
    val workspace = demo.workspace
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(documentId.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Both documents share one workspace with two satellites: the Inspector and the " +
                "Tools palette. Floating, they belong to the document focused last and follow " +
                "it around. Docked, they become panels inside a document's content. Drag a " +
                "satellite by its header: the edges of the documents light up, drop there to " +
                "dock it; drag a panel's header out over the document to lift it off again, " +
                "state intact.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Section("Satellites") {
            SatelliteControls(workspace, DemoState.INSPECTOR_ID, "Inspector")
            SatelliteControls(workspace, DemoState.TOOLS_ID, "Tools")
            LabelledSwitch(
                label = "Show all satellites",
                checked = workspace.visible,
                onCheckedChange = { workspace.visible = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { demo.saveLayout() }) { Text("Save layout") }
                OutlinedButton(onClick = { demo.restoreLayout() }, enabled = demo.savedLayout != null) {
                    Text("Restore layout")
                }
            }
            Text(
                "The Tools palette keeps its selected tool through every dock and undock: " +
                    "that state is rememberSaveable, and the workspace carries it between hosts. " +
                    "Save the layout, rearrange everything, then restore it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Owner") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = demo.pinnedDocument == null,
                    onClick = { demo.pin(null) },
                    label = { Text("Follow focus") },
                )
                for (id in DocumentId.entries) {
                    FilterChip(
                        selected = demo.pinnedDocument == id,
                        onClick = { demo.pin(id) },
                        enabled = id == DocumentId.A || demo.showDocumentB,
                        label = { Text("Pin to ${id.title}") },
                    )
                }
            }
            LabelledSwitch(
                label = "Open a second document window",
                checked = demo.showDocumentB,
                onCheckedChange = { demo.showDocumentB = it },
            )
            LabelledSwitch(
                label = "Hide floating satellites while their owner is fullscreen or maximized",
                checked = demo.hideWhenParentFills,
                onCheckedChange = { demo.hideWhenParentFills = it },
            )
            Text(
                "Click into the other document: the floating satellites switch owner without " +
                    "moving, then follow it. Close the owner and they move on to the survivor. " +
                    "Pinning keeps them on one document regardless of focus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Inspector positioner") {
            Text("Anchor", style = MaterialTheme.typography.labelLarge)
            PresetChips(
                entries = AnchorPreset.entries,
                label = { it.label },
                selected = demo.anchorPreset,
                onSelect = {
                    demo.anchorPreset = it
                    demo.applyPositioner()
                },
            )
            Spacer(Modifier.height(4.dp))
            Text("Gap: ${demo.gapDp.roundToInt()} dp", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = demo.gapDp,
                onValueChange = { demo.gapDp = it },
                onValueChangeFinished = { demo.applyPositioner() },
                valueRange = 0f..64f,
            )
            Spacer(Modifier.height(4.dp))
            Text("Off-screen adjustment", style = MaterialTheme.typography.labelLarge)
            PresetChips(
                entries = AdjustmentPreset.entries,
                label = { it.label },
                selected = demo.adjustmentPreset,
                onSelect = {
                    demo.adjustmentPreset = it
                    demo.applyPositioner()
                },
            )
            Text(
                "Applies to the Inspector while it floats. Push this window against the right " +
                    "edge of the screen, pick “Right edge”, then compare “None” with “Flip”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Live state") {
            StateLine("owner", demo.ownerDocument?.title ?: "—")
            StateLine("pinned", demo.pinnedDocument?.title ?: "no (follows focus)")
            StateLine("members", workspace.members.size.toString())
            for (entry in workspace.satellites.sortedBy { it.id }) {
                StateLine(entry.id, describe(demo, entry))
            }
            for (side in DockSide.entries) {
                StateLine("extent ${side.name.lowercase()}", "${workspace.dockExtent(side).value.roundToInt()} dp")
            }
        }
    }
}

/** Show / hide, dock / float, and one button per dock side, for one satellite. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SatelliteControls(
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
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.labelLarge)
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

private fun describe(
    demo: DemoState,
    entry: SatelliteEntry,
): String {
    val placement =
        when (val p = entry.placement) {
            is SatellitePlacement.Floating -> {
                val offset = entry.windowState.offsetFromParent
                "floating" + (offset?.let { " @ ${it.x.value.roundToInt()}, ${it.y.value.roundToInt()} dp" } ?: "")
            }
            is SatellitePlacement.Docked -> {
                "docked ${p.side.name.lowercase()} #${p.order} in ${demo.hostDocument(entry)?.title ?: "—"}"
            }
        }
    return if (entry.isOpen) placement else "closed ($placement)"
}

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
private fun <T> PresetChips(
    entries: List<T>,
    label: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (entry in entries) {
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                label = { Text(label(entry)) },
            )
        }
    }
}

@Composable
private fun LabelledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
