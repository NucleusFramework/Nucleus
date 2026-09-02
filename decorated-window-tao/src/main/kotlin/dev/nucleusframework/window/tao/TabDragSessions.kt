package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.roundToIntRect
import dev.nucleusframework.window.tao.workspace.TransferDrag
import dev.nucleusframework.window.tao.workspace.TransferGhostSource
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
internal fun TabWorkspace.tearOffSizePx(
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
        // whole time; only another window's strip is a target, and the search
        // has to look *past* its own rather than stop at it.
        workspace.dropPreview = workspace.dropTargetAt(pointer, exclude = entry, excludeGroup = entry.group)
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

/**
 * The DnD-carried tab drag (native Wayland, see [TransferDrag]) of [entry] out
 * of [group]'s strip in [window]. Sizes are still readable there, so the
 * torn-off window gets the size a pointer drag would give it; its position is
 * the compositor's.
 */
@Suppress("MagicNumber") // outer frame is [x, y, w, h]
internal fun TabWorkspace.createTabTransferDrag(
    entry: TabEntry,
    group: TabWindowGroup,
    window: TaoWindow,
): TabTransferDrag {
    val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
    val outer = window.outerBoundsPx()
    val windowSizePx =
        outer?.let { tearOffSizePx(window, it, scale) }
            ?: Size(defaultWindowSize.width.value * scale, defaultWindowSize.height.value * scale)
    val slot = group.slotsInWindowPx.getOrNull(group.tabIds.indexOf(entry.id))?.takeIf { !it.isEmpty }
    val ghostSizePx = slot?.size ?: Size(TabMaxWidth.value * scale, TAB_GHOST_HEIGHT_DP * scale)
    // The tab itself is the picture; without a published slot, its title card.
    val ghostSource = slot?.let { TransferGhostSource.Region(it.roundToIntRect()) } ?: TransferGhostSource.None
    return TabTransferDrag(this, entry, ghostSizePx, ghostSource, windowSizePx, scale)
}

/** Ghost height when the dragged tab published no slot yet — roughly a title bar's worth. */
private const val TAB_GHOST_HEIGHT_DP = 32f

/**
 * A tab drag carried by the platform's DnD session. The strip under the
 * release records the insertion in [drop]; [end] then applies it — or, with
 * no record, tears the tab into a window of its own (one of several) and
 * leaves the only tab of a window where it is.
 */
internal class TabTransferDrag(
    private val workspace: TabWorkspace,
    val entry: TabEntry,
    override val ghostSizePx: Size,
    override val ghostSource: TransferGhostSource,
    /** The size a torn-off window gets, physical px. */
    private val windowSizePx: Size,
    private val scaleFactor: Float,
) : TransferDrag {
    override val title: String get() = entry.title

    /** Written by the strip that took the drop, read once the session ends. */
    var drop: TabDropTarget? = null

    override fun end() {
        if (!workspace.isLiveTransfer(this)) return
        val target = drop
        workspace.endTransferDrag(this)
        when {
            target != null -> workspace.move(entry.id, target.group, target.index)
            (entry.group?.tabIds?.size ?: 0) > 1 ->
                workspace.tearOff(entry.id, Rect(Offset.Zero, windowSizePx), scaleFactor)
            else -> Unit
        }
    }

    override fun cancel() {
        workspace.endTransferDrag(this)
    }
}
