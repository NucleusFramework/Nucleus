package dev.nucleusframework.window.tao

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle

/**
 * What the [DockLayout] `splitter` slot composes in: which side and panel the
 * splitter resizes, along which axis, and the modifier that makes an element
 * the grip.
 */
public interface DockSplitterScope {
    /** The side this splitter belongs to. */
    public val side: DockSide

    /**
     * The axis the splitter is dragged along: [Orientation.Horizontal] for a
     * bar between things side by side (a vertical line), [Orientation.Vertical]
     * for a bar between things stacked.
     */
    public val orientation: Orientation

    /**
     * The panel this splitter resizes: the layer just outside it on a layered
     * side, or the panel just before it on a split side. `null` for the
     * splitter between a split side's stack and the content, which drags the
     * side's [SatelliteWorkspace.dockExtent].
     */
    public val panel: SatelliteEntry?

    /**
     * Attaches the resize gesture and the resize cursor. Apply it to the
     * element the user grabs; it may be larger than what is drawn — a 1 dp
     * line can carry a wider invisible grip through `Modifier.requiredWidth`.
     */
    public fun Modifier.dockSplitterHandle(): Modifier
}

/**
 * The stock splitter: a bar of [DockSplitterThickness] in the window style's
 * border colour, the whole of it the grip.
 */
@Composable
public fun DockSplitterScope.DefaultDockSplitter() {
    val color = LocalDecoratedWindowStyle.current.colors.border
    val sizeModifier =
        if (orientation == Orientation.Horizontal) {
            Modifier.fillMaxHeight().width(DockSplitterThickness)
        } else {
            Modifier.fillMaxWidth().height(DockSplitterThickness)
        }
    Box(sizeModifier.background(color).dockSplitterHandle())
}

/** Sign of a pointer delta that grows [side] towards the content. */
internal fun towardsContent(
    side: DockSide,
    deltaPx: Float,
): Float =
    when (side) {
        DockSide.Left, DockSide.Top -> deltaPx
        DockSide.Right, DockSide.Bottom -> -deltaPx
    }

internal class DockSplitterScopeImpl(
    override val side: DockSide,
    override val orientation: Orientation,
    override val panel: SatelliteEntry?,
    private val onDragPx: (deltaPx: Float, density: Density) -> Unit,
) : DockSplitterScope {
    private val horizontal: Boolean get() = orientation == Orientation.Horizontal

    override fun Modifier.dockSplitterHandle(): Modifier =
        pointerHoverIcon(if (horizontal) TaoPointerIcons.ResizeEastWest else TaoPointerIcons.ResizeNorthSouth)
            .pointerInput(this@DockSplitterScopeImpl) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDragPx(if (horizontal) drag.x else drag.y, this)
                }
            }
}

/**
 * Moves [deltaPx] of the stack's length from [after] to [before]: the
 * divider follows the pointer one-to-one, and neither panel drops under
 * [SatelliteWorkspace.MinDockExtent].
 */
internal fun moveWeight(
    state: DockLayoutState,
    side: DockSide,
    before: SatelliteEntry,
    after: SatelliteEntry,
    deltaPx: Float,
    density: Density,
) {
    val lengthPx = state.stackLengthsPx[side]?.takeIf { it > 0 } ?: return
    val total = state.panelsOn(side).sumOf { weightOf(it).toDouble() }.toFloat()
    val pxPerWeight = lengthPx / total
    val minWeight = with(density) { SatelliteWorkspace.MinDockExtent.toPx() } / pxPerWeight
    val beforeWeight = weightOf(before)
    val afterWeight = weightOf(after)
    // Both panels already under the minimum — a stack too short for its
    // panels — leaves nothing to move.
    val low = minWeight - beforeWeight
    val high = afterWeight - minWeight
    if (low > high) return
    val delta = (deltaPx / pxPerWeight).coerceIn(low, high)
    if (delta == 0f || delta.isNaN()) return
    state.workspace.setDockedWeight(before.id, beforeWeight + delta)
    state.workspace.setDockedWeight(after.id, afterWeight - delta)
}

/** Thickness of the [DefaultDockSplitter] bar. */
public val DockSplitterThickness: Dp = 6.dp
