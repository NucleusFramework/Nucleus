package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.nucleusframework.window.tao.workspace.sanitizedOrNull
import dev.nucleusframework.window.tao.workspace.toWindowCoordinate

/**
 * The session for a drag of [entry] from [origin], with the pointer at
 * [pointerScreenPx]; `null` while the origin's geometry is not available.
 *
 * Which of the two it is follows the window, exactly as in a browser: the only
 * tab of a window has no "out" to be dragged to, so the window itself follows
 * the pointer; one of several is lifted out under a ghost.
 */
@Suppress("MagicNumber") // outer frame is [x, y, w, h]
internal fun TabWorkspace.createTabDragSession(
    entry: TabEntry,
    origin: TabDragOrigin,
    pointerScreenPx: Offset,
): TabDragSession? {
    val strip =
        when (origin) {
            is TabDragOrigin.Strip -> origin
        }
    val group = groupOf(strip.window) ?: return null
    val outer = strip.outerBoundsPx() ?: return null
    val geometry = stripHosts[strip.window] ?: return null
    return if (group.tabIds.size == 1) {
        TabWindowDragSession(
            workspace = this,
            entry = entry,
            origin = strip,
            grabOffsetPx = pointerScreenPx - Offset(outer[0].toFloat(), outer[1].toFloat()),
            pointer = pointerScreenPx,
        )
    } else {
        val slot = group.slotsInWindowPx.getOrNull(group.tabIds.indexOf(entry.id)) ?: return null
        val client = geometry.clientOriginPx() ?: return null
        val scale = geometry.scaleOrOne()
        TabTearOffDragSession(
            workspace = this,
            entry = entry,
            windowSizePx = tearOffSizePx(strip.window, outer, scale),
            grabOffsetPx = pointerScreenPx - (client + slot.topLeft),
            tabSizePx = slot.size,
            pointer = pointerScreenPx,
            scaleFactor = scale,
        )
    }
}

/**
 * The size a window torn off [window] gets: the source window's own, so the
 * tab keeps the room it had — unless the source fills the screen, where
 * inheriting the frame would hand the user a second screen-sized window
 * instead of one they can put somewhere. Then it is the workspace default,
 * which is what a browser does with a tab pulled out of a maximized window.
 */
@Suppress("MagicNumber") // outer frame is [x, y, w, h]
private fun TabWorkspace.tearOffSizePx(
    window: TaoWindow,
    outer: LongArray,
    scale: Float,
): Size =
    if (window.isMaximized || window.isFullscreen) {
        Size(defaultWindowSize.width.value * scale, defaultWindowSize.height.value * scale)
    } else {
        Size(outer[2].toFloat(), outer[3].toFloat())
    }

/** The part every tab drag shares: it acts only while live, and cancelling releases it. */
private abstract class TabDragSessionBase(
    protected val workspace: TabWorkspace,
) : TabDragSession {
    /** `true` while this session is the one the workspace is publishing. */
    protected val isLive: Boolean get() = workspace.isLiveDrag(this)

    final override fun cancel() {
        workspace.releaseDrag(this)
    }
}

/**
 * The only tab of a window, dragged: the window follows the pointer, and
 * releasing it over another strip merges the tab into it — which drops this
 * window, since it is then empty.
 */
private class TabWindowDragSession(
    workspace: TabWorkspace,
    private val entry: TabEntry,
    private val origin: TabDragOrigin.Strip,
    /** Pointer offset from the window's outer top-left at the grab. */
    private val grabOffsetPx: Offset,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
) : TabDragSessionBase(workspace) {
    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val topLeft = pointer - grabOffsetPx
        origin.move(topLeft.x.toWindowCoordinate(), topLeft.y.toWindowCoordinate())
        // Its own strip moved with the window and is under the pointer the
        // whole time; only another window's strip is a target.
        workspace.dropPreview = workspace.dropTargetAt(pointer, exclude = entry)?.takeIf { it.group !== entry.group }
    }

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        update(pointerScreenPx)
        val target = workspace.dropPreview
        cancel()
        if (target != null) workspace.move(entry.id, target.group, target.index)
    }
}

/**
 * One of several tabs, dragged out: a ghost follows the pointer, and releasing
 * either inserts the tab in the strip under it or tears it into a window of
 * its own placed where the ghost was.
 */
private class TabTearOffDragSession(
    workspace: TabWorkspace,
    private val entry: TabEntry,
    /** The source window's outer size, which the torn-off window inherits. */
    private val windowSizePx: Size,
    /** Pointer offset from the dragged tab's top-left at the grab. */
    private val grabOffsetPx: Offset,
    private val tabSizePx: Size,
    /** Where the pointer was last seen; a rejected sample leaves it alone. */
    private var pointer: Offset,
    /** The source window's px-per-dp, carried to the ghost and the new window. */
    private val scaleFactor: Float,
) : TabDragSessionBase(workspace) {
    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        workspace.dropPreview = workspace.dropTargetAt(pointer, exclude = entry)
        // Follows the pointer for the whole gesture, including over a strip:
        // the tab is out of its strip as soon as the drag starts, and seeing it
        // hover is what makes the tear-out read.
        workspace.dragGhost = TabDragGhost(entry, Rect(pointer - grabOffsetPx, tabSizePx), scaleFactor)
    }

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val drop = pointer
        val target = workspace.dropTargetAt(drop, exclude = entry)
        cancel()
        if (target != null) {
            workspace.move(entry.id, target.group, target.index)
            return
        }
        // A window the size of the one it came from, with the grabbed tab
        // still under the pointer: the strip lands where the ghost was.
        workspace.tearOff(entry.id, Rect(drop - grabOffsetPx, windowSizePx), scaleFactor)
    }
}
