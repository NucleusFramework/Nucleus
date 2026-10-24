package dev.nucleusframework.lab.probes.workspace.satellites

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.pinTo
import dev.nucleusframework.lab.probes.workspace.common.SessionModel
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner

/** Anchor pairs worth testing, named the way a tester would describe them. */
enum class AnchorPreset(
    val label: String,
    val parentAnchor: WindowAnchor,
    val childAnchor: WindowAnchor,
) {
    RightEdge("right edge", WindowAnchor.Right, WindowAnchor.Left),
    LeftEdge("left edge", WindowAnchor.Left, WindowAnchor.Right),
    TopRightOutside("top-right, outside", WindowAnchor.TopRight, WindowAnchor.TopLeft),
    BelowCentre("below, centred", WindowAnchor.Bottom, WindowAnchor.Top),
    OverCentre("over the centre", WindowAnchor.Center, WindowAnchor.Center),
}

/** What the positioner does when the satellite would leave the screen. */
enum class AdjustmentPreset(
    val label: String,
    val adjustment: WindowConstraintAdjustment,
) {
    None("none (may overhang)", WindowConstraintAdjustment.None),
    Slide("slide", WindowConstraintAdjustment.Slide),
    Flip("flip", WindowConstraintAdjustment.Flip),
    FlipAndSlide("flip, then slide", WindowConstraintAdjustment.FlipAndSlide),
    All("all (shrink last)", WindowConstraintAdjustment.All),
}

fun positionerFor(live: SatellitesLive): WindowPositioner =
    WindowPositioner(
        parentAnchor = live.anchor.parentAnchor,
        childAnchor = live.anchor.childAnchor,
        // The gap points away from the parent, so its sign follows the anchor.
        offset =
            when (live.anchor) {
                AnchorPreset.RightEdge, AnchorPreset.TopRightOutside -> DpOffset(live.gapDp.dp, 0.dp)
                AnchorPreset.LeftEdge -> DpOffset(-live.gapDp.dp, 0.dp)
                AnchorPreset.BelowCentre -> DpOffset(0.dp, live.gapDp.dp)
                AnchorPreset.OverCentre -> DpOffset.Zero
            },
        constraintAdjustment = live.adjustment.adjustment,
    )

/**
 * One open satellite session: the workspace both documents join, the windows they
 * published (to name and pin the owner), and the live knobs the session composes from.
 */
class SatellitesSessionModel(
    val options: SatellitesOptions,
    live: SatellitesLive,
) : SessionModel<SatellitesLive> {
    val workspace = SatelliteWorkspace(followFocus = options.followFocus)

    override var live: SatellitesLive by mutableStateOf(live)

    private val documents = mutableStateMapOf<DocumentId, NucleusWindow>()

    val inspectorPlacement: SatellitePlacement =
        SatellitePlacement.Floating(positioner = positionerFor(live), size = DpSize(300.dp, 400.dp))

    /** Fixed Tools must start docked: a non-floatable satellite needs a docked placement. */
    val toolsPlacement: SatellitePlacement = SatellitePlacement.Docked(DockSide.Left)

    fun publish(
        id: DocumentId,
        window: NucleusWindow,
    ) {
        documents[id] = window
    }

    fun forget(id: DocumentId) {
        documents.remove(id)
    }

    fun document(window: TaoWindow?): DocumentId? =
        window?.let { w -> documents.entries.firstOrNull { it.value.unsafe.taoWindow === w }?.key }

    /** Pins [id] as owner (`null` = follow focus); `false` when that document is not open. */
    fun pin(id: DocumentId?): Boolean {
        val window = id?.let { documents[it] ?: return false }
        workspace.pinTo(window)
        return true
    }

    /** Placement is a one-shot: a new positioner only takes effect through `reanchor()`. */
    fun applyPositioner() {
        val inspector = workspace.satellite(INSPECTOR_ID) ?: return
        inspector.windowState.positioner = positionerFor(live)
        inspector.windowState.reanchor()
    }

    companion object {
        const val INSPECTOR_ID = "inspector"
        const val TOOLS_ID = "tools"
    }
}
