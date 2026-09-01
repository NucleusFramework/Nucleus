package dev.nucleusframework.satellitedemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Content of the satellite itself — a stand-in for the inspector / palette an
 * app would put here, plus a live readout of the anchoring state the window
 * publishes back through `SatelliteWindowState`.
 */
@Composable
fun InspectorContent(demo: DemoState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Owned by ${demo.attachedTo.title}. Always in front of it, never in the " +
                "taskbar, never modal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()

        Readout("anchor", demo.anchorPreset.label)
        Readout("gap", "${demo.gapDp.roundToInt()} dp")
        Readout("adjustment", demo.adjustmentPreset.label)
        val offset = demo.inspector.offsetFromParent
        Readout(
            "offsetFromParent",
            offset?.let { "${it.x.value.roundToInt()}, ${it.y.value.roundToInt()}" } ?: "—",
        )
        Readout("isActive", demo.inspector.isActive.toString())

        HorizontalDivider()
        Text(
            "Drag this window: the offset above changes, and it is that new offset the " +
                "inspector keeps the next time the document moves. “Reanchor” puts it " +
                "back on the positioner.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { demo.inspector.reanchor() }) { Text("Reanchor") }
            TextButton(onClick = { demo.showInspector = false }) { Text("Close") }
        }
    }
}

@Composable
private fun Readout(
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
