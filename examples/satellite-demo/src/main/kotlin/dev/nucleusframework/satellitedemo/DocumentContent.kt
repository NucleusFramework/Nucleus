package dev.nucleusframework.satellitedemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The control panel inside a document window. Every switch here drives the one
 * shared inspector satellite, so the effect of a change is visible on whichever
 * document currently owns it.
 */
@Composable
fun DocumentContent(
    demo: DemoState,
    documentId: DocumentId,
) {
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
            "A satellite is an auxiliary window that belongs to this one: anchored to it, " +
                "moving with it, above it without being modal, and gone when it closes. " +
                "Drag this window around — the inspector comes along. Drag the inspector " +
                "somewhere else and *that* offset is the one it keeps.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Section("Inspector") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { demo.showInspector = !demo.showInspector }) {
                    Text(if (demo.showInspector) "Hide inspector" else "Show inspector")
                }
                OutlinedButton(
                    onClick = { demo.applyPositioner() },
                    enabled = demo.showInspector,
                ) {
                    Text("Reanchor")
                }
            }
            LabelledSwitch(
                label = "Hide while this window is fullscreen or maximized",
                checked = demo.hideWhenParentFills,
                onCheckedChange = { demo.hideWhenParentFills = it },
            )
            Text(
                "Maximize this window with the switch on: the inspector steps aside " +
                    "instead of floating over the content, and comes back re-anchored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Attached to") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (id in DocumentId.entries) {
                    FilterChip(
                        selected = demo.attachedTo == id,
                        onClick = { demo.attachedTo = id },
                        enabled = id == DocumentId.A || demo.showDocumentB,
                        label = { Text(id.title) },
                    )
                }
            }
            LabelledSwitch(
                label = "Open a second document window",
                checked = demo.showDocumentB,
                onCheckedChange = { open ->
                    demo.showDocumentB = open
                    if (!open) demo.attachedTo = DocumentId.A
                },
            )
            Text(
                "Reparenting keeps the inspector exactly where it is on screen; only its " +
                    "owner changes — so it now follows, and closes with, the other document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Positioner") {
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
                "Push this window against the right edge of the screen, pick “Right edge”, " +
                    "then compare “None” with “Flip”: the inspector mirrors to the other " +
                    "side rather than hanging off the display.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Live state") {
            val offset = demo.inspector.offsetFromParent
            StateLine(
                "offsetFromParent",
                offset?.let { "${it.x.value.roundToInt()}, ${it.y.value.roundToInt()} dp" } ?: "—",
            )
            StateLine("isHiddenByParent", demo.inspector.isHiddenByParent.toString())
            StateLine("isActive", demo.inspector.isActive.toString())
            StateLine("owner", demo.attachedTo.title)
        }
    }
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
