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
        //
        // That own strip is also what stands in for the card here: the window
        // is what the user is moving, so a merge is previewed as soon as its
        // strip reaches another's, before the pointer is over it — the same
        // rule as for a tab carried under a ghost.
        workspace.dropPreview =
            workspace.dropTargetAt(stripScreenRectPx(topLeft), pointer, exclude = entry, excludeGroup = entry.group)
        workspace.dragPointerScreenPx = pointer
    }

    /**
     * Where this window's own strip would be with its frame at [topLeftPx]:
     * the band that stands in for the dragged card. `null` before the strip
     * has published its geometry.
     */
    private fun stripScreenRectPx(topLeftPx: Offset): Rect? {
        val geometry = workspace.stripHosts[origin.window] ?: return null
        val outer = origin.outerBoundsPx() ?: return null
        val clientInset = (geometry.clientOriginPx() ?: return null) - Offset(outer[0].toFloat(), outer[1].toFloat())
        return geometry.layoutBoundsInWindowPx.translate(topLeftPx + clientInset)
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
    private val velocity = HorizontalVelocity()

    override fun update(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        workspace.dragVelocityPxPerSecond = velocity.sample(pointer.x)
        // Resolved from the card as well as from the pointer: a tab whose top
        // edge has come up into a strip is previewed there before the pointer
        // reaches it, so the drop reads while the card is still below the
        // strip rather than over it.
        val card = ghostRectPx()
        val target = workspace.dropTargetAt(card, pointer, exclude = entry)
        workspace.dropPreview = target
        workspace.dragPointerScreenPx = pointer
        // Over its own strip the tab has not left: the strip holds it under the
        // pointer and its neighbours make room, the way a browser's do. Over
        // another window's strip, or clear of every strip, it *is* leaving —
        // and seeing it hover is what makes the move and the tear-out read.
        val inOwnStrip = target != null && target.group === entry.group
        workspace.dragGhost = if (inOwnStrip) null else TabDragGhost(entry, card, scaleFactor)
    }

    /** Where the card is on screen: the grabbed tab, carried at the grab offset. */
    private fun ghostRectPx(): Rect = Rect(pointer - grabOffsetPx, tabSizePx)

    override fun end(pointerScreenPx: Offset) {
        if (!isLive) return
        pointer = pointerScreenPx.sanitizedOrNull() ?: pointer
        val drop = pointer
        val target = workspace.dropTargetAt(ghostRectPx(), drop, exclude = entry)
        val group = entry.group
        // Read before the release clears the drag: the slide home starts with
        // the speed the pointer had, so a flick carries through.
        val speed = workspace.dragVelocityPxPerSecond
        cancel()
        if (target != null) {
            if (target.group === group && group != null) {
                // Inside its own strip: the strip slides the tab into its new
                // place and applies the reorder itself, so nothing jumps.
                workspace.pendingReorder = TabReorderSettle(entry, group, target.index, speed)
            } else {
                workspace.move(entry.id, target.group, target.index)
            }
            return
        }
        // A window the size of the one it came from, with the grabbed tab
        // still under the pointer: the strip lands where the ghost was.
        workspace.tearOff(entry.id, Rect(drop - grabOffsetPx, windowSizePx), scaleFactor)
    }
}

/**
 * How fast the pointer is travelling along one axis, from the samples the
 * session is fed: the strip hands it to the spring that slides a released tab
 * home, so a flick carries through and a slow move does not overshoot.
 *
 * Smoothed over the last samples rather than taken from the last pair: one
 * pointer report can land a millisecond after the one before it and read as
 * thousands of px per second.
 */
private class HorizontalVelocity {
    private var lastX = Float.NaN
    private var lastNanos = 0L
    private var smoothed = 0f

    fun sample(x: Float): Float {
        val now = System.nanoTime()
        val elapsed = now - lastNanos
        if (!lastX.isNaN() && elapsed in 1..MAX_GAP_NANOS) {
            val instant = (x - lastX) / (elapsed / NANOS_PER_SECOND)
            smoothed = smoothed * (1f - SMOOTHING) + instant * SMOOTHING
        } else if (lastX.isNaN() || elapsed > MAX_GAP_NANOS) {
            // A first sample, or a pause long enough that the pointer has
            // stopped: no speed to carry.
            smoothed = 0f
        }
        lastX = x
        lastNanos = now
        return smoothed
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** Longer than this between samples and the pointer was at rest, not travelling. */
        const val MAX_GAP_NANOS = 100_000_000L

        /** How much of the newest sample the estimate takes: enough to follow a flick, not a jitter. */
        const val SMOOTHING = 0.4f
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
