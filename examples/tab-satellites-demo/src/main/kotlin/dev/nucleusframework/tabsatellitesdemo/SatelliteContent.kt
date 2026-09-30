package dev.nucleusframework.tabsatellitesdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteScope
import kotlin.math.roundToInt

/**
 * The inspector of whichever tab its window is showing.
 *
 * The values are read from [DemoState], not remembered here: they belong to the
 * document, so they have to be the same whichever window's inspector draws them
 * and be waiting unchanged when the tab comes back. A `rememberSaveable` in a
 * satellite survives dock / undock, but not being handed a different document.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SatelliteScope.InspectorContent(
    demo: DemoState,
    document: Document,
) {
    val state = demo.stateOf(document.id)

    SatelliteSurface {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Inspector — ${document.title}", style = MaterialTheme.typography.titleSmall)
            Text(
                "This palette is the one of the window it is anchored to, and it draws the tab " +
                    "that window is showing — switch tabs and the content changes with no window " +
                    "being created or destroyed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Strength ${(state.strength * PERCENT).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = state.strength, onValueChange = { state.strength = it })
            OutlinedButton(onClick = { state.edits++ }) { Text("edits: ${state.edits}") }
            Text(
                "Both values belong to this document: switch tabs and back, or move the tab to " +
                    "another window, and they are still here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PlacementControls()
            StateLine("hosted as", if (isDocked) "a docked panel" else "a floating window")
            StateLine("placement", describePlacement())
        }
    }
}

/**
 * The palette of whichever tab its window is showing: a swatch grid whose
 * selection, like the inspector's values, belongs to the document.
 */
@Composable
fun SatelliteScope.PaletteContent(
    demo: DemoState,
    document: Document,
) {
    val state = demo.stateOf(document.id)
    val swatches = remember(document.accent) { swatchesFor(document.accent) }

    SatelliteSurface {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Palette — ${document.title}", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                swatches.forEachIndexed { index, colour ->
                    Box(
                        modifier =
                            Modifier
                                .size(SWATCH_DP.dp)
                                .clip(CircleShape)
                                .background(colour)
                                .border(
                                    width = if (index == state.swatch) SELECTED_BORDER_DP.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                ).clickable { state.swatch = index },
                    )
                }
            }
            Text(
                "Swatch ${state.swatch + 1} of ${swatches.size} — the selection belongs to the document too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlacementControls()
        }
    }
}

/** Float / dock buttons, the same for either satellite. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SatelliteScope.PlacementControls() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDocked) {
            OutlinedButton(onClick = { undock() }) { Text("Float") }
        } else {
            OutlinedButton(onClick = { dock() }) { Text("Dock") }
        }
        for (side in DockSide.entries) {
            TextButton(onClick = { dock(side) }) { Text(side.name) }
        }
    }
}

@Composable
private fun SatelliteScope.describePlacement(): String {
    val entry = satellite
    val owner = workspace.owner
    return buildString {
        append(if (entry.isDocked) "docked ${entry.preferredDockSide.name.lowercase()}" else "floating")
        append(if (owner == null) ", no owner" else ", owned by the window showing the tab")
    }
}

/** Themed body of a satellite, the same whether it floats or is docked. */
@Composable
private fun SatelliteSurface(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) { content() }
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
        Text(name, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun swatchesFor(accent: Color): List<Color> =
    listOf(
        accent,
        accent.copy(alpha = SHADE_STRONG),
        accent.copy(alpha = SHADE_MEDIUM),
        accent.copy(alpha = SHADE_LIGHT),
    )

private const val PERCENT = 100
private const val SWATCH_DP = 26
private const val SELECTED_BORDER_DP = 2
private const val SHADE_STRONG = 0.75f
private const val SHADE_MEDIUM = 0.5f
private const val SHADE_LIGHT = 0.3f
