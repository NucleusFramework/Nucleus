package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.nucleusframework.window.tao.workspace.TransferDrag
import dev.nucleusframework.window.tao.workspace.TransferGhostSource
import dev.nucleusframework.window.tao.workspace.sanitizedOrNull
import dev.nucleusframework.window.tao.workspace.toWindowCoordinate

/**
 * The session for a drag of [entry] from [origin], with the pointer at
 * [pointerScreenPx]; `null` when the origin's geometry is not available yet.
 */
internal fun SatelliteWorkspace.createDragSession(
    entry: SatelliteEntry,
    origin: SatelliteDragOrigin,
    pointerScreenPx: Offset,
): SatelliteDragSession? =
    when (origin) {
        is SatelliteDragOrigin.FloatingWindow -> {
            val outer = origin.outerBoundsPx() ?: return null
            FloatingDragSession(
                workspace = this,
                entry = entry,
                origin = origin,
                grabOffsetPx = pointerScreenPx - Offset(outer[0].toFloat(), outer[1].toFloat()),
                pointer = pointerScreenPx,
            )
        }
        is SatelliteDragOrigin.DockedPanel -> {
            val geometry = dockHostGeometry(origin.host) ?: return null
            val panel = entry.dockedBoundsInWindowPx ?: return null
            val clientOrigin = geometry.clientOriginPx() ?: return null
            DockedDragSession(
                workspace = this,
                entry = entry,
                host = origin.host,
                panelScreenRectPx = panel.translate(clientOrigin),
                grabOffsetPx = pointerScreenPx - (clientOrigin + panel.topLeft),
                pointer = pointerScreenPx,
                scaleFactor = geometry.scaleOrOne(),
            )
        }
    }

/** The part every satellite drag shares: it acts only while live, and cancelling releases it. */
private abstract class SatelliteDragSessionBase(
    protected val workspace: SatelliteWorkspace,
) : SatelliteDragSession {
    /** `true` while this session is the one the workspace is publishing. */
    protected val isLive: Boolean get() = workspace.isLiveDrag(this)

    final override fun cancel() {
        workspace.releaseDrag(this)
    }
}

private class FloatingDragSession(
    workspace: SatelliteWorkspace,
    private val entry: SatelliteEntry,
    private val origin: SatelliteDragOrigin.FloatingWindow,
    /** Pointer offset from the window's outer top-left at the grab. */
    private val grabOffsetPx: Offset,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
) : SatelliteDragSessionBase(workspace) {
    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val topLeft = pointer - grabOffsetPx
        origin.move(topLeft.x.toWindowCoordinate(), topLeft.y.toWindowCoordinate())
        workspace.dockPreview = workspace.dockTargetAt(pointer)
    }

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        update(pointerScreenPx)
        val target = workspace.dockPreview
        cancel()
        if (target != null) workspace.dock(entry.id, target.side, host = target.host)
    }
}

private class DockedDragSession(
    workspace: SatelliteWorkspace,
    private val entry: SatelliteEntry,
    private val host: TaoWindow,
    /** The panel's rect on screen at the grab; released inside it, the drag is a no-op. */
    private val panelScreenRectPx: Rect,
    /** Pointer offset from the panel's top-left at the grab. */
    private val grabOffsetPx: Offset,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
    /** The host's px-per-dp, carried to the ghost window. */
    private val scaleFactor: Float,
) : SatelliteDragSessionBase(workspace) {
    private val own: DockTarget? = (entry.placement as? SatellitePlacement.Docked)?.let { DockTarget(host, it.side) }

    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        workspace.dockPreview = workspace.dockTargetAt(pointer)?.takeIf { it != own }
        // Follows the pointer for the whole gesture, including over a dock
        // zone: the panel is out of the layout as soon as the drag starts, and
        // seeing it hover is what makes the tear-out read.
        workspace.dragGhost = DragGhost(entry, Rect(pointer - grabOffsetPx, panelScreenRectPx.size), scaleFactor)
    }

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val drop = pointer
        val target = workspace.dockTargetAt(drop)?.takeIf { it != own }
        cancel()
        when {
            target != null -> workspace.dock(entry.id, target.side, host = target.host)
            panelScreenRectPx.contains(drop) -> Unit
            else -> workspace.undock(entry.id, workspace.floatingAtScreen(drop - grabOffsetPx, panelScreenRectPx.size))
        }
    }
}

/** What the [DockLayout] under a transfer drag's release recorded for it. */
internal sealed interface TransferDrop {
    /** Dock the satellite in [target]. */
    data class Dock(
        val target: DockTarget,
    ) : TransferDrop

    /** Leave everything as it is: released on its own panel, or on the side it already occupies. */
    data object Stay : TransferDrop
}

/**
 * A satellite drag carried by the platform's DnD session (native Wayland,
 * see [TransferDrag]). The window under the release resolves the drop and
 * writes it to [drop]; [end] then applies it:
 *
 *  - a dock zone docks the satellite there (or re-docks it);
 *  - no record at all — released over content, another app, the desktop —
 *    lifts a docked panel out as a window the compositor places, and leaves a
 *    floating window where it is.
 */
internal class SatelliteTransferDrag(
    private val workspace: SatelliteWorkspace,
    val entry: SatelliteEntry,
    val origin: SatelliteDragOrigin,
    override val ghostSizePx: Size,
    override val ghostSource: TransferGhostSource,
) : TransferDrag {
    override val title: String get() = entry.title

    /** Written by the target that took the drop, read once the session ends. */
    var drop: TransferDrop? = null

    /** The zone the dragged panel already occupies; dropping back onto it changes nothing. */
    val own: DockTarget? =
        (origin as? SatelliteDragOrigin.DockedPanel)?.let { panel ->
            (entry.placement as? SatellitePlacement.Docked)?.let { DockTarget(panel.host, it.side) }
        }

    override fun end() {
        if (!workspace.isLiveTransfer(this)) return
        val outcome = drop
        workspace.endTransferDrag(this)
        when (outcome) {
            is TransferDrop.Dock -> workspace.dock(entry.id, outcome.target.side, host = outcome.target.host)
            TransferDrop.Stay -> Unit
            null -> if (origin is SatelliteDragOrigin.DockedPanel) workspace.undock(entry.id)
        }
    }

    override fun cancel() {
        workspace.endTransferDrag(this)
    }
}
