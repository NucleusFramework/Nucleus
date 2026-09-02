package dev.nucleusframework.satellitedemo

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteScope
import kotlin.math.roundToInt

/**
 * Content of the Inspector satellite — a stand-in for the inspector an app
 * would put here, plus a live readout of what the workspace knows about it.
 * Composed unchanged whether the inspector floats or is docked; [scope] tells
 * it which, and gives it the dock / undock / close actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectorContent(
    demo: DemoState,
    scope: SatelliteScope,
) {
    val entry = scope.satellite
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (scope.isDocked) {
                "Docked into ${demo.hostDocument(entry)?.title ?: "a document"}. Part of that window " +
                    "now — resize the splitter, or lift it off."
            } else {
                "Owned by ${demo.ownerDocument?.title ?: "—"}. Always in front of it, never in the " +
                    "taskbar, never modal."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()

        Readout("placement", if (scope.isDocked) "docked" else "floating")
        when (val placement = entry.placement) {
            is SatellitePlacement.Docked -> {
                Readout("side", placement.side.name.lowercase())
                Readout("order", placement.order.toString())
                Readout("extent", "${scope.workspace.dockExtent(placement.side).value.roundToInt()} dp")
            }
            is SatellitePlacement.Floating -> {
                Readout("anchor", demo.anchorPreset.label)
                Readout("gap", "${demo.gapDp.roundToInt()} dp")
                Readout("adjustment", demo.adjustmentPreset.label)
                val offset = entry.windowState.offsetFromParent
                Readout(
                    "offsetFromParent",
                    offset?.let { "${it.x.value.roundToInt()}, ${it.y.value.roundToInt()}" } ?: "—",
                )
                Readout("isActive", entry.windowState.isActive.toString())
            }
        }

        HorizontalDivider()
        Text(
            if (scope.isDocked) {
                "Drag the “Inspector” header out over the document to lift this back into a " +
                    "window of its own; drop it on another edge to move it there. “Float” lifts " +
                    "it off right over the panel."
            } else {
                "Drag the “Inspector” header: the edges of the documents light up as you " +
                    "approach them, and dropping there docks it. Elsewhere, the new offset is " +
                    "what the inspector keeps the next time the document moves. “Reanchor” puts " +
                    "it back on the positioner; “Dock” docks it on its last side."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scope.isDocked) {
                OutlinedButton(onClick = { scope.undock() }) { Text("Float") }
            } else {
                OutlinedButton(onClick = { entry.windowState.reanchor() }) { Text("Reanchor") }
                OutlinedButton(onClick = { scope.dock() }) { Text("Dock") }
            }
            TextButton(onClick = { scope.close() }) { Text("Close") }
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
