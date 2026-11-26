package dev.nucleusframework.lab.probes.workspace.satellites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabPane
import dev.nucleusframework.lab.designsystem.LabSlider
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.ToggleChip
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteScope
import kotlin.math.roundToInt

/** The Inspector reads itself back: the same body whether it floats or is docked. */
@Composable
fun SatelliteScope.InspectorContent(model: SatellitesSessionModel) {
    val entry = satellite
    LabPane {
        Readout("hosted as", if (isDocked) "docked panel" else "floating window")
        Readout("compositor-placed", isCompositorPlaced.toString())
        when (val placement = entry.placement) {
            is SatellitePlacement.Docked -> {
                Readout("side · rank", "${placement.side.name.lowercase()} · #${placement.order}")
                Readout("host", model.document(entry.dockHost)?.title)
                Readout("extent", "${(placement.extent ?: workspace.dockExtent(placement.side)).value.roundToInt()} dp")
            }
            is SatellitePlacement.Floating -> {
                Readout("anchor", model.live.anchor.label)
                Readout("adjustment", model.live.adjustment.label)
                Readout(
                    "offsetFromParent",
                    entry.windowState.offsetFromParent?.let {
                        "${it.x.value.roundToInt()}, ${it.y.value.roundToInt()}"
                    },
                )
                Readout("isActive", entry.windowState.isActive.toString())
            }
        }
        Readout("dock sides", entry.dockSides.joinToString { it.name.lowercase() }.ifEmpty { "none" })
        Actions {
            if (isDocked) {
                if (entry.isFloatable) SecondaryAction("Float") { undock() }
            } else {
                SecondaryAction("Reanchor") { entry.windowState.reanchor() }
                if (entry.dockSides.isNotEmpty()) {
                    val side = entry.preferredDockSide.takeIf { it in entry.dockSides } ?: entry.dockSides.first()
                    SecondaryAction("Dock ${side.name.lowercase()}") { dock(side) }
                }
            }
            SecondaryAction("Close") { close() }
        }
    }
}

private val Tools = listOf("Move", "Brush", "Eraser", "Fill", "Text", "Crop")

/**
 * Tools keeps its selection through every dock and undock: it is `rememberSaveable`. The
 * chips and the slider hold that state.
 */
@Composable
fun SatelliteScope.ToolsContent() {
    var tool by rememberSaveable { mutableStateOf(Tools.first()) }
    var brush by rememberSaveable { mutableFloatStateOf(12f) }
    LabPane {
        Text(if (isDocked) "Docked palette" else "Floating palette", style = LabTheme.typography.heading)
        Column(verticalArrangement = Arrangement.spacedBy(LabDimens.lineGap)) {
            Tools.forEach { name ->
                ToggleChip(name, checked = tool == name, modifier = Modifier.fillMaxWidth()) { tool = name }
            }
        }
        Text("Brush ${brush.roundToInt()} px", style = LabTheme.typography.small)
        LabSlider(brush, Modifier.fillMaxWidth(), valueRange = 1f..64f) { brush = it }
        Hint("Pick a tool and a size, then dock / float: both must survive (saveable).")
    }
}
