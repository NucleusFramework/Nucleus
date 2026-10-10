package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TertiaryAction
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.window.tao.DockSide

/**
 * Show / hide, float, and one button per dock side — every side, declared or not, so a
 * refusal (`dock()` on a side the satellite was not declared for) can be exercised too.
 */
@Composable
fun SatelliteControls(
    id: String,
    enabled: Boolean,
    observation: SatelliteObservation?,
    onToggle: () -> Unit,
    onUndock: () -> Unit,
    onDock: (DockSide) -> Unit,
) {
    SubHeading(observation?.title ?: id)
    Actions {
        SecondaryAction(if (observation?.isOpen == true) "Hide" else "Show", enabled = enabled) { onToggle() }
        SecondaryAction("Float", enabled = enabled && observation?.isDocked == true) { onUndock() }
        DockSide.entries.forEach { side ->
            val declared = observation == null || side in observation.dockSides
            TertiaryAction(side.name + if (declared) "" else " ✕", enabled = enabled) { onDock(side) }
        }
    }
}

/** Owner, drag in flight, dock extents and one line per satellite. */
@Composable
fun SatellitesReadout(observation: SatellitesObservation) {
    Readout("owner", observation.owner)
    Readout("pinned", observation.pinned ?: "no (follows focus)")
    Readout("members", observation.members.toString())
    Readout("visible", observation.visible.toString())
    Readout("drag kind", observation.dragKind, tone = if (observation.dragKind != null) Tone.Warning else Tone.Neutral)
    Readout("dragged", observation.dragged)
    Readout("dock preview", observation.dockPreview)
    Readout("side extents", observation.sideExtents.entries.joinToString { "${it.key.name.lowercase()} ${it.value}" })
    observation.satellites.forEach { s ->
        SubHeading(s.title)
        Readout("placement", s.placement, tone = if (s.isOpen) Tone.Neutral else Tone.Muted)
        Readout(
            "declared",
            "sides ${s.dockSides.joinToString { it.name.lowercase() }.ifEmpty { "none" }}" +
                (if (!s.floatable) " · fixed" else "") +
                (if (!s.reorderable) " · pinned rank" else "") +
                " · extent ${s.extentRange}",
        )
        if (!s.isDocked && s.isOpen) Readout("isActive", s.isActive.toString())
    }
}
