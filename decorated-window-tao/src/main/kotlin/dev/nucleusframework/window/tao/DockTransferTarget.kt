package dev.nucleusframework.window.tao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.workspace.HostGeometry
import dev.nucleusframework.window.tao.workspace.positionInWindowPx

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
internal fun Modifier.dockTransferTarget(
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

internal class DockTransferTarget(
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
        val zone = zoneAt(position)?.let { workspace.targetFor(drag.entry, it) }
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

    /**
     * The zone [positionInWindowPx] is in, resolved against the rectangles the
     * layout draws ([HostGeometry.zoneBoundsInWindowPx]) so a drop lands where
     * the highlight promised — inset behind existing layers included, at the
     * rank of the stack the pointer is over — and against the layout's edges
     * while none are published.
     */
    internal fun zoneAt(positionInWindowPx: Offset): DockTarget? {
        val zonePx = SatelliteWorkspace.DockZoneWidth.value * geometry.scaleOrOne()
        val zones = geometry.zoneBoundsInWindowPx
        if (zones.isEmpty()) {
            return dockSideAt(geometry.layoutBoundsInWindowPx, positionInWindowPx, zonePx)?.let { DockTarget(host, it) }
        }
        // A stack the pointer is over wins over a strip running across its corner.
        val (side, zone) =
            zones.entries.firstOrNull { (_, zone) -> zone.slots.any { it.contains(positionInWindowPx) } }
                ?: zones.entries.firstOrNull { (_, zone) -> zone.strip.contains(positionInWindowPx) }
                ?: return null
        return DockTarget(host, side, zone.slotAt(positionInWindowPx))
    }

    private fun preview(event: DragAndDropEvent) {
        val drag = workspace.transferDrag ?: return
        workspace.dockPreview =
            zoneAt(event.positionInWindowPx())
                ?.let { workspace.targetFor(drag.entry, it) }
                ?.takeIf { it != drag.own }
    }

    private fun clearPreview() {
        if (workspace.dockPreview?.host === host) workspace.dockPreview = null
    }

    /** Whether [positionInWindowPx] is on the dragged panel itself, in this host. */
    private fun SatelliteTransferDrag.isOwnPanel(positionInWindowPx: Offset): Boolean =
        (origin as? SatelliteDragOrigin.DockedPanel)?.host === host &&
            entry.dockedBoundsInWindowPx?.contains(positionInWindowPx) == true
}
