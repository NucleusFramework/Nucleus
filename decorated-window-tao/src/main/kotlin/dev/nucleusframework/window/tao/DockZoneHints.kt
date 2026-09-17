package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import dev.nucleusframework.window.tao.workspace.DockDropZone
import kotlin.math.roundToInt

/**
 * The four drop zones of this layout, shown while a satellite is being
 * dragged anywhere in the workspace.
 *
 * Every side is outlined faintly as soon as the drag starts — that is what
 * tells the user the gesture exists — and on the one the satellite has
 * entered the panel's own card ([SatelliteGhostCard], the card that follows
 * the pointer) is drawn on the space the release will fill
 * ([DockLayoutState.dropRectPx]): the side's own band rather than the whole
 * edge, at the width the drop will produce, inside the layers already there
 * on a layered side, and on a side with panels at the rank the pointer picks
 * — the share of the stack the panel gets between the two it lands between.
 * The rank the dragged panel already holds is not a target: a side it is
 * alone on is left out altogether, and with neighbours the strip past the
 * stack is not lit while the panel is the last of them, since a drop there
 * changes nothing.
 */
@Composable
internal fun BoxScope.DockZoneHints(
    workspace: SatelliteWorkspace,
    host: TaoWindow,
    state: DockLayoutState,
) {
    val dragged = workspace.draggedSatellite ?: return
    val density = LocalDensity.current
    val hinted = hintedSides(dragged, host, workspace.satellites)
    val zoneWidthPx = with(density) { SatelliteWorkspace.DockZoneWidth.toPx() }
    // What a drag is hit-tested against is what is drawn: the idle strips and
    // the ranks of each stack, published to the geometry the workspace
    // resolves drops on. Cleared when the drag ends, so a stale set can never
    // answer for a later one. Recomputed on every recomposition rather than
    // remembered: the rects come from the measured bands, which move without
    // any of the keys a remember could name (a side order change, a splitter
    // drag). Four strips and a handful of slots.
    val zones =
        hinted.associateWith { side ->
            val strip = state.landingRectPx(side, zoneWidthPx, joinsStack = false, dragged = dragged)
            DockDropZone(strip, state.dropSlotsPx(side, strip, dragged))
        }
    val origin = state.layoutBoundsInWindowPx.topLeft
    DisposableEffect(zones, origin) {
        val geometry = workspace.dockHostGeometry(host)
        geometry?.zoneBoundsInWindowPx = zones.mapValues { (_, zone) -> zone.translate(origin) }
        onDispose { geometry?.zoneBoundsInWindowPx = emptyMap() }
    }
    val own = workspace.ownTarget(dragged, host)
    // Keeps the closed-hand cursor over the whole layout for the length of the
    // drag: the grip itself is only under the pointer while the satellite
    // floats, and a docked panel's header is left behind at the first move.
    Box(
        Modifier
            .matchParentSize()
            .pointerHoverIcon(TaoPointerIcons.Grabbing, overrideDescendants = true),
    )
    for (side in hinted) {
        SideHint(workspace, state, host, side, zones.getValue(side), dragged, own)
    }
}

/**
 * One side's feedback: the panel's card on the space it will take when the
 * side is the one aimed at, else the faint strip that says it could be.
 */
@Suppress("LongParameterList") // the drag's whole state, read once per side
@Composable
private fun SideHint(
    workspace: SatelliteWorkspace,
    state: DockLayoutState,
    host: TaoWindow,
    side: DockSide,
    zone: DockDropZone,
    dragged: SatelliteEntry,
    own: DockTarget?,
) {
    val preview = workspace.dockPreview
    val active = preview?.host === host && preview.side == side
    // Its own side, with itself last: the strip past the stack is the rank it
    // holds, so lighting it up would promise a move that does not happen.
    if (!active && own?.side == side && own.order == zone.slots.lastIndex) return
    if (!active) {
        PreviewAt(zone.strip) { DragPreviewSurface(Modifier.fillMaxSize(), hint = true) }
        return
    }
    val density = LocalDensity.current
    // The width the drop will actually produce: on a layered side the panel's
    // own, elsewhere the side's — which on a side that has no extent yet is
    // the satellite's own size, not the default.
    val extent =
        if (state.isLayered(side)) {
            workspace.dockSeedExtent(dragged, side)
        } else {
            workspace.plannedDockExtent(dragged, side)
        }
    val order = preview.order?.takeIf { zone.slots.isNotEmpty() }
    val rect = state.dropRectPx(side, dragged, order, with(density) { extent.toPx() })
    PreviewAt(rect) { SatelliteGhostCard(dragged.title, Modifier.fillMaxSize()) }
}

/** [content] laid over [rect], in the layout's own px. */
@Composable
private fun PreviewAt(
    rect: Rect,
    content: @Composable () -> Unit,
) {
    if (rect.isEmpty) return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
    ) {
        content()
    }
}

/**
 * The sides worth hinting while [dragged] is in flight over [host]: every one
 * except the side [dragged] is already docked on **in this window** while
 * there is no other rank for it there — it is alone, or pinned
 * ([SatelliteEntry.isReorderable]) — since dropping it back is a no-op and
 * offering it would promise a move that does not happen. With other panels
 * on that side it is a target again: the panel can be dropped at another
 * rank among them.
 * Dragged from another window, or floating, every side is a real target —
 * among the sides the satellite was declared for ([SatelliteEntry.dockSides]).
 * [satellites] are the workspace's, to tell a lone panel from a stack.
 */
internal fun hintedSides(
    dragged: SatelliteEntry,
    host: TaoWindow,
    satellites: Collection<SatelliteEntry>,
): List<DockSide> {
    val own = (dragged.placement as? SatellitePlacement.Docked)?.side?.takeIf { dragged.dockHost === host }
    val alone =
        own != null &&
            satellites.none {
                it !== dragged &&
                    it.isShown &&
                    it.dockHost === host &&
                    (it.placement as? SatellitePlacement.Docked)?.side == own
            }
    // Its own side is a target only while another rank is on offer there.
    val stuck = alone || !dragged.isReorderable
    return DockSide.entries.filter { it in dragged.dockSides && !(stuck && it == own) }
}

/** The smallest rect containing both. */
internal fun unionOf(
    a: Rect,
    b: Rect,
): Rect = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
