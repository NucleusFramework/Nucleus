package dev.nucleusframework.satellitedemo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.window.tao.SatelliteWindowState
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner

/** Which document window a satellite is currently attached to. */
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
 * windows and the shared inspector read the same source of truth.
 *
 * [inspector] is deliberately built here rather than with
 * `rememberSatelliteWindowState`: the position the user drags the inspector to
 * has to survive closing and reopening it, and a state remembered inside the
 * `if (showInspector)` branch would not.
 */
class DemoState {
    /** On from the start: the satellite is what the demo is about. */
    var showInspector by mutableStateOf(true)
    var showDocumentB by mutableStateOf(false)

    /** The document the inspector belongs to — change it to reparent live. */
    var attachedTo by mutableStateOf(DocumentId.A)

    var anchorPreset by mutableStateOf(AnchorPreset.RightEdge)
    var adjustmentPreset by mutableStateOf(AdjustmentPreset.FlipAndSlide)
    var gapDp by mutableStateOf(INITIAL_GAP_DP)
    var hideWhenParentFills by mutableStateOf(true)

    val inspector: SatelliteWindowState =
        SatelliteWindowState(
            size = DpSize(INSPECTOR_WIDTH_DP.dp, INSPECTOR_HEIGHT_DP.dp),
            positioner = positionerFor(AnchorPreset.RightEdge, AdjustmentPreset.FlipAndSlide, INITIAL_GAP_DP),
        )

    /** Document windows publish themselves here so the satellite can be parented. */
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

    val parentWindow: NucleusWindow?
        get() = documents[attachedTo]

    /**
     * Pushes the current picker values into the satellite and re-applies them.
     *
     * Placement is a one-shot by design — the satellite keeps the offset the
     * user gave it — so changing the rule only takes effect on
     * [SatelliteWindowState.reanchor].
     */
    fun applyPositioner() {
        inspector.positioner = positionerFor(anchorPreset, adjustmentPreset, gapDp)
        inspector.reanchor()
    }

    private companion object {
        const val INITIAL_GAP_DP = 12f
        const val INSPECTOR_WIDTH_DP = 300
        const val INSPECTOR_HEIGHT_DP = 380

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
        fun gapOffsetFor(
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
