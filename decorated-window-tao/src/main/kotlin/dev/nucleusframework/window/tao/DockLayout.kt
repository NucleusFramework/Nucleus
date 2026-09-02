package dev.nucleusframework.window.tao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.styling.LocalDecoratedWindowStyle
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.workspace.HostGeometry
import dev.nucleusframework.window.tao.workspace.RelocatedContentHost
import dev.nucleusframework.window.tao.workspace.positionInWindowPx
import dev.nucleusframework.window.tao.workspace.publishHostGeometry
import dev.nucleusframework.window.tao.workspace.rememberHostGeometry

/**
 * Lays [content] out with the satellites docked into this window around it.
 *
 * Panels attach to the four edges of the layout ([DockSide]); the ones on a
 * side share it equally, in [SatellitePlacement.Docked.order], and a splitter
 * between the side and the content drags that side's
 * [SatelliteWorkspace.dockExtent]. With nothing docked — or while the
 * workspace is not [SatelliteWorkspace.visible] — the layout is just
 * [content].
 *
 * Compose it inside a window that joined the workspace, typically as the body
 * of a `WindowScaffold`. The window it is composed in ([host], resolved from
 * [LocalTaoWindow]) is what [SatelliteEntry.dockHost] refers to.
 *
 * The layout is also the drop target for satellite drags
 * ([Modifier.satelliteDragHandle]): a strip of [SatelliteWorkspace.DockZoneWidth]
 * inside each edge lights up while a dragged satellite hovers it, and a panel
 * dragged out of its dock is outlined under the pointer until released.
 *
 * Each panel is the satellite's `header` above its `content`, composed here
 * in the host window's scene under the satellite's own saveable-state
 * registry — see [Satellite].
 */
@Composable
public fun DockLayout(
    workspace: SatelliteWorkspace,
    modifier: Modifier = Modifier,
    host: TaoWindow? = LocalTaoWindow.current,
    content: @Composable () -> Unit,
) {
    val containerSize = LocalWindowInfo.current.containerSize
    // Published so drags can be hit-tested against this layout on screen and
    // undocked windows placed over their panel.
    val geometry = rememberHostGeometry(workspace.dockHosts, host)
    val docked =
        if (host == null || !workspace.visible) {
            emptyList()
        } else {
            workspace.satellites.filter { entry ->
                entry.isOpen && entry.content != null && entry.dockHost === host && entry.isDocked
            }
        }
    Box(
        modifier
            .publishHostGeometry(geometry, containerSize)
            .dockTransferTarget(workspace, host, geometry),
    ) {
        DockScaffold(workspace, docked, containerSize, content)
        if (host != null) DockZoneHints(workspace, host)
    }
}

/**
 * Makes the layout the drop target of a [SatelliteWorkspace.transferDrag]:
 * the drag that rides the platform's DnD session where windows cannot be
 * hit-tested from the source (native Wayland). The events arrive in this
 * window's own coordinates, which is exactly what the source lacks, so the
 * zone under the pointer is resolved here — previewed while hovering, recorded
 * on the session at the drop for the source to act on when the session ends.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.dockTransferTarget(
    workspace: SatelliteWorkspace,
    host: TaoWindow?,
    geometry: HostGeometry?,
): Modifier {
    if (host == null || geometry == null) return this
    val target = remember(workspace, host, geometry) { DockTransferTarget(workspace, host, geometry) }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { workspace.transferDrag != null },
        target = target,
    )
}

private class DockTransferTarget(
    private val workspace: SatelliteWorkspace,
    private val host: TaoWindow,
    private val geometry: HostGeometry,
) : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = preview(event)

    override fun onMoved(event: DragAndDropEvent) = preview(event)

    override fun onExited(event: DragAndDropEvent) = clearPreview()

    override fun onEnded(event: DragAndDropEvent) = clearPreview()

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val drag = workspace.transferDrag ?: return false
        val position = event.positionInWindowPx()
        val zone = zoneAt(position)
        val outcome =
            when {
                zone != null && zone != drag.own -> TransferDrop.Dock(zone)
                // Back onto its own side, or onto the very panel it came from:
                // the gesture was abandoned, not a tear-out.
                zone != null || drag.isOwnPanel(position) -> TransferDrop.Stay
                else -> return false
            }
        drag.drop = outcome
        clearPreview()
        return true
    }

    private fun zoneAt(positionInWindowPx: Offset): DockTarget? {
        val zonePx = SatelliteWorkspace.DockZoneWidth.value * geometry.scaleOrOne()
        return dockSideAt(geometry.layoutBoundsInWindowPx, positionInWindowPx, zonePx)?.let { DockTarget(host, it) }
    }

    private fun preview(event: DragAndDropEvent) {
        val drag = workspace.transferDrag ?: return
        workspace.dockPreview = zoneAt(event.positionInWindowPx())?.takeIf { it != drag.own }
    }

    private fun clearPreview() {
        if (workspace.dockPreview?.host === host) workspace.dockPreview = null
    }

    /** Whether [positionInWindowPx] is on the dragged panel itself, in this host. */
    private fun SatelliteTransferDrag.isOwnPanel(positionInWindowPx: Offset): Boolean =
        (origin as? SatelliteDragOrigin.DockedPanel)?.host === host &&
            entry.dockedBoundsInWindowPx?.contains(positionInWindowPx) == true
}

/**
 * The content with its docked panels around it, one stack per side.
 *
 * Every slot is composed unconditionally — a side with nothing docked emits an
 * empty stack and an empty splitter. Compose identifies children by their
 * position, so a conditional slot would move the content's subtree the first
 * time a panel appears and destroy it: the document's scroll position, and
 * every `remember` under it, would be lost on the first dock.
 */
@Composable
private fun DockScaffold(
    workspace: SatelliteWorkspace,
    docked: List<SatelliteEntry>,
    containerSize: IntSize,
    content: @Composable () -> Unit,
) {
    val bySide =
        docked
            .groupBy { (it.placement as SatellitePlacement.Docked).side }
            .mapValues { (_, entries) ->
                entries.sortedWith(compareBy({ (it.placement as SatellitePlacement.Docked).order }, { it.id }))
            }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize().onSizeChanged { layoutSize = it }) {
        DockSideStack(workspace, DockSide.Top, bySide[DockSide.Top].orEmpty(), containerSize)
        DockSplitter(workspace, DockSide.Top, layoutSize, bySide[DockSide.Top] != null)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            DockSideStack(workspace, DockSide.Left, bySide[DockSide.Left].orEmpty(), containerSize)
            DockSplitter(workspace, DockSide.Left, layoutSize, bySide[DockSide.Left] != null)
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            DockSplitter(workspace, DockSide.Right, layoutSize, bySide[DockSide.Right] != null)
            DockSideStack(workspace, DockSide.Right, bySide[DockSide.Right].orEmpty(), containerSize)
        }
        DockSplitter(workspace, DockSide.Bottom, layoutSize, bySide[DockSide.Bottom] != null)
        DockSideStack(workspace, DockSide.Bottom, bySide[DockSide.Bottom].orEmpty(), containerSize)
    }
}

/**
 * The four drop zones of this layout, shown while a satellite is being
 * dragged anywhere in the workspace.
 *
 * Every side is outlined as soon as the drag starts — that is what tells the
 * user the gesture exists — and the one under the pointer fills in solid, at
 * the width the panel will actually have once dropped.
 */
@Composable
private fun BoxScope.DockZoneHints(
    workspace: SatelliteWorkspace,
    host: TaoWindow,
) {
    val dragged = workspace.draggedSatellite ?: return
    val preview = workspace.dockPreview
    val accent = LocalTitleBarStyle.current.colors.content
    // Keeps the closed-hand cursor over the whole layout for the length of the
    // drag: the grip itself is only under the pointer while the satellite
    // floats, and a docked panel's header is left behind at the first move.
    Box(
        Modifier
            .matchParentSize()
            .pointerHoverIcon(TaoPointerIcons.Grabbing, overrideDescendants = true),
    )
    for (side in DockSide.entries) {
        val active = preview?.host === host && preview.side == side
        // The width the drop will actually produce, which on a side that has
        // no extent yet is the satellite's own size, not the default.
        val extent = if (active) workspace.plannedDockExtent(dragged, side) else SatelliteWorkspace.DockZoneWidth
        val alignment =
            when (side) {
                DockSide.Left -> Alignment.CenterStart
                DockSide.Right -> Alignment.CenterEnd
                DockSide.Top -> Alignment.TopCenter
                DockSide.Bottom -> Alignment.BottomCenter
            }
        val sizeModifier =
            if (side.isVertical) {
                Modifier.fillMaxHeight().width(extent)
            } else {
                Modifier.fillMaxWidth().height(extent)
            }
        Box(
            sizeModifier
                .align(alignment)
                .background(accent.copy(alpha = if (active) ZONE_ACTIVE_ALPHA else ZONE_HINT_ALPHA))
                .dashedOutline(accent.copy(alpha = if (active) 1f else ZONE_OUTLINE_ALPHA), dashed = !active),
        )
    }
}

/** A dashed (or solid) 1 dp outline, drawn rather than composed so it costs no layout. */
private fun Modifier.dashedOutline(
    color: Color,
    dashed: Boolean,
): Modifier =
    drawBehind {
        val stroke = ZoneOutlineWidth.toPx()
        drawRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style =
                Stroke(
                    width = stroke,
                    pathEffect =
                        if (dashed) {
                            PathEffect.dashPathEffect(floatArrayOf(ZoneDashOn.toPx(), ZoneDashOff.toPx()))
                        } else {
                            null
                        },
                ),
        )
    }

/** The panels docked on one side, sharing the side equally along its length. Empty when none are. */
@Composable
private fun DockSideStack(
    workspace: SatelliteWorkspace,
    side: DockSide,
    entries: List<SatelliteEntry>,
    containerSize: IntSize,
) {
    if (entries.isEmpty()) return
    val extent = workspace.dockExtent(side)
    val divider = LocalDecoratedWindowStyle.current.colors.border
    if (side.isVertical) {
        Column(Modifier.fillMaxHeight().width(extent)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(PanelDividerThickness).background(divider))
                DockPanel(workspace, entry, containerSize, Modifier.fillMaxWidth().weight(1f))
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().height(extent)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxHeight().width(PanelDividerThickness).background(divider))
                DockPanel(workspace, entry, containerSize, Modifier.fillMaxHeight().weight(1f))
            }
        }
    }
}

/** One docked satellite: its header strip over its content. */
@Composable
private fun DockPanel(
    workspace: SatelliteWorkspace,
    entry: SatelliteEntry,
    containerSize: IntSize,
    modifier: Modifier,
) {
    if (entry.content == null) return
    val header = entry.header
    val scope = remember(workspace, entry) { SatelliteScopeImpl(workspace, entry, isDocked = true) }
    val headerBackground = LocalTitleBarStyle.current.colors.background
    // Dimmed while its ghost is being dragged: the panel is on its way out.
    val leaving = workspace.dragGhost?.satellite === entry
    Column(
        modifier
            .alpha(if (leaving) LEAVING_PANEL_ALPHA else 1f)
            .onGloballyPositioned { coordinates ->
                // Read by SatelliteWorkspace.undock to lift the window off the panel.
                entry.dockedBoundsInWindowPx = coordinates.boundsInWindow()
                entry.dockHostContainerSizePx = containerSize
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(DockPanelHeaderHeight).background(headerBackground),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (header != null) header(scope) else scope.DefaultSatelliteHeader()
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            RelocatedContentHost(entry.stateSlot, scope, entry.content)
        }
    }
}

/**
 * Drag handle between a dock side and the content. Dragging towards the
 * content grows the side; the extent is kept between
 * [SatelliteWorkspace.MinDockExtent] and the layout minus [MinContentExtent].
 */
@Composable
private fun DockSplitter(
    workspace: SatelliteWorkspace,
    side: DockSide,
    layoutSize: IntSize,
    enabled: Boolean,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val color = LocalDecoratedWindowStyle.current.colors.border
    val sizeModifier =
        if (side.isVertical) {
            Modifier.fillMaxHeight().width(SplitterThickness)
        } else {
            Modifier.fillMaxWidth().height(SplitterThickness)
        }
    Box(
        sizeModifier
            .background(color)
            .pointerHoverIcon(if (side.isVertical) TaoPointerIcons.ResizeEastWest else TaoPointerIcons.ResizeNorthSouth)
            .pointerInput(workspace, side, layoutSize) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val towardsContent =
                        when (side) {
                            DockSide.Left -> drag.x
                            DockSide.Right -> -drag.x
                            DockSide.Top -> drag.y
                            DockSide.Bottom -> -drag.y
                        }
                    val currentPx = with(density) { workspace.dockExtent(side).toPx() }
                    val along = if (side.isVertical) layoutSize.width else layoutSize.height
                    val maxPx = along - with(density) { MinContentExtent.toPx() }
                    var nextPx = currentPx + towardsContent
                    if (along > 0 && maxPx > 0f) nextPx = nextPx.coerceAtMost(maxPx)
                    workspace.setDockExtent(side, with(density) { nextPx.toDp() })
                }
            }.fillMaxSize(),
    )
}

/** Height of the header strip above a docked panel's content. */
public val DockPanelHeaderHeight: Dp = 30.dp

private val SplitterThickness: Dp = 6.dp
private val PanelDividerThickness: Dp = 1.dp
private val MinContentExtent: Dp = 120.dp
private val PreviewBorderWidth: Dp = 1.dp
private val ZoneOutlineWidth: Dp = 1.5.dp
private val ZoneDashOn: Dp = 5.dp
private val ZoneDashOff: Dp = 4.dp
private const val ZONE_HINT_ALPHA = 0.10f
private const val ZONE_ACTIVE_ALPHA = 0.28f
private const val ZONE_OUTLINE_ALPHA = 0.55f
private const val LEAVING_PANEL_ALPHA = 0.35f
