package dev.nucleusframework.satellitedemo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.pinTo
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteEntry
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner

/** The document windows of the demo. */
enum class DocumentId(
    val title: String,
) {
    A("Document A"),
    B("Document B"),
}

/** Anchor pairs worth demonstrating, named the way a user would describe them. */
enum class AnchorPreset(
    val label: String,
    val parentAnchor: WindowAnchor,
    val childAnchor: WindowAnchor,
) {
    RightEdge("Right edge", WindowAnchor.Right, WindowAnchor.Left),
    LeftEdge("Left edge", WindowAnchor.Left, WindowAnchor.Right),
    TopRightOutside("Top-right, outside", WindowAnchor.TopRight, WindowAnchor.TopLeft),
    BelowCentre("Below, centred", WindowAnchor.Bottom, WindowAnchor.Top),
    OverCentre("Over the centre", WindowAnchor.Center, WindowAnchor.Center),
}

/** The [WindowConstraintAdjustment] presets, for the screen-edge story. */
enum class AdjustmentPreset(
    val label: String,
    val adjustment: WindowConstraintAdjustment,
) {
    None("None — may overhang", WindowConstraintAdjustment.None),
    Slide("Slide", WindowConstraintAdjustment.Slide),
    Flip("Flip", WindowConstraintAdjustment.Flip),
    FlipAndSlide("Flip, then slide", WindowConstraintAdjustment.FlipAndSlide),
    All("All (shrink as a last resort)", WindowConstraintAdjustment.All),
}

/**
 * Everything the demo drives, hoisted to the application so both document
 * windows and the satellites read the same source of truth.
 *
 * The [workspace] is the heart of it: both documents join it, the Inspector
 * and the Tools palette are declared against it, and everything the UI does —
 * dock, undock, pin, hide, save and restore the layout — is a workspace call.
 */
class DemoState {
    val workspace = SatelliteWorkspace()

    var showDocumentB by mutableStateOf(false)

    var anchorPreset by mutableStateOf(AnchorPreset.RightEdge)
    var adjustmentPreset by mutableStateOf(AdjustmentPreset.FlipAndSlide)
    var gapDp by mutableStateOf(INITIAL_GAP_DP)
    var hideWhenParentFills by mutableStateOf(true)

    /** The layout captured by "Save layout", ready for "Restore layout". */
    var savedLayout: SatelliteLayoutSnapshot? by mutableStateOf(null)
        private set

    /** Document windows publish themselves here so the owner can be named and pinned. */
    private val documents = mutableStateMapOf<DocumentId, NucleusWindow>()

    fun publish(
        id: DocumentId,
        window: NucleusWindow,
    ) {
        documents[id] = window
    }

    fun forget(id: DocumentId) {
        documents.remove(id)
    }

    /** The document currently owning the floating satellites. */
    val ownerDocument: DocumentId?
        get() = documents.entries.firstOrNull { it.value.unsafe.taoWindow === workspace.owner }?.key

    /** The document pinned as owner, or `null` while the owner follows focus. */
    val pinnedDocument: DocumentId?
        get() = documents.entries.firstOrNull { it.value.unsafe.taoWindow === workspace.pinnedOwner }?.key

    /** Pins [id] as owner; `null` lets focus decide again. */
    fun pin(id: DocumentId?) {
        workspace.pinTo(id?.let { documents[it] })
    }

    /** Which document a docked satellite lives in, if it is docked. */
    fun hostDocument(entry: SatelliteEntry): DocumentId? =
        documents.entries.firstOrNull { it.value.unsafe.taoWindow === entry.dockHost }?.key

    val inspector: SatelliteEntry? get() = workspace.satellite(INSPECTOR_ID)

    /**
     * Pushes the picker values into the floating inspector and re-applies them.
     * Placement is a one-shot by design — the satellite keeps the offset the
     * user gave it — so a new rule only takes effect through `reanchor()`.
     */
    fun applyPositioner() {
        val entry = inspector ?: return
        entry.windowState.positioner = positionerFor(anchorPreset, adjustmentPreset, gapDp)
        entry.windowState.reanchor()
    }

    fun saveLayout() {
        savedLayout = workspace.snapshot()
    }

    fun restoreLayout() {
        savedLayout?.let(workspace::restore)
    }

    companion object {
        const val INSPECTOR_ID = "inspector"
        const val TOOLS_ID = "tools"
        const val INITIAL_GAP_DP = 12f
        private const val INSPECTOR_WIDTH_DP = 300
        private const val INSPECTOR_HEIGHT_DP = 400

        /** The inspector starts floating off the owner's right edge. */
        val InspectorPlacement: SatellitePlacement =
            SatellitePlacement.Floating(
                positioner = positionerFor(AnchorPreset.RightEdge, AdjustmentPreset.FlipAndSlide, INITIAL_GAP_DP),
                size = DpSize(INSPECTOR_WIDTH_DP.dp, INSPECTOR_HEIGHT_DP.dp),
            )

        /** The tools palette starts docked on the left of the owner. */
        val ToolsPlacement: SatellitePlacement = SatellitePlacement.Docked(DockSide.Left)

        fun positionerFor(
            anchor: AnchorPreset,
            adjustment: AdjustmentPreset,
            gapDp: Float,
        ): WindowPositioner =
            WindowPositioner(
                parentAnchor = anchor.parentAnchor,
                childAnchor = anchor.childAnchor,
                offset = gapOffsetFor(anchor, gapDp),
                constraintAdjustment = adjustment.adjustment,
            )

        /** The gap has to point *away* from the parent, so its sign follows the anchor. */
        private fun gapOffsetFor(
            anchor: AnchorPreset,
            gapDp: Float,
        ): DpOffset =
            when (anchor) {
                AnchorPreset.RightEdge -> DpOffset(gapDp.dp, 0.dp)
                AnchorPreset.LeftEdge -> DpOffset(-gapDp.dp, 0.dp)
                AnchorPreset.TopRightOutside -> DpOffset(gapDp.dp, 0.dp)
                AnchorPreset.BelowCentre -> DpOffset(0.dp, gapDp.dp)
                AnchorPreset.OverCentre -> DpOffset.Zero
            }
    }
}
