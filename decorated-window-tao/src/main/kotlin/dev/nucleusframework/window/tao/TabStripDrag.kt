package dev.nucleusframework.window.tao

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.nucleusframework.window.tao.workspace.ScreenDrag
import dev.nucleusframework.window.tao.workspace.TransferDragGesture
import dev.nucleusframework.window.tao.workspace.screenDragHandle
import dev.nucleusframework.window.tao.workspace.transferDragHandle

/**
 * Makes this element the grip that drags [tab]: a reorder inside its own
 * strip, and — where the platform allows it — a move to another window or a
 * window of its own.
 *
 * While the tab is in hand the strip draws it under the pointer and its
 * neighbours step aside; see [TabStrip], which applies this already.
 *
 * The gesture a window can carry depends on one thing, whether the app is the
 * one placing its windows ([TaoWindow.canPlaceOnScreen]):
 *
 *  - where it is, the drag is the workspace's ([TabWorkspace.beginDrag]) and
 *    speaks screen pixels: the strip animates the reorder from the pointer
 *    that drag publishes, another window's strip can be dropped on, and a
 *    release clear of every strip tears the tab off under a ghost;
 *  - where it is not — a native Wayland surface — the reorder is a *local*
 *    gesture ([tabStripLocalDragHandle]), driven by the pointer's travel
 *    inside the window and resolved against the strip's own slots, because
 *    that is the only thing a client is told. A release clear of the strip
 *    defers the drop to the window the compositor hands the pointer to next,
 *    which is how a merge into another window still resolves there.
 *
 * No-op outside a Tao window.
 */
public fun Modifier.tabDragHandle(
    workspace: TabWorkspace,
    tab: TabEntry,
): Modifier =
    composed {
        val group = tab.group ?: return@composed Modifier
        val scope = rememberCoroutineScope()
        val motion = remember(workspace, group) { workspace.motionFor(group, scope) }
        tabStripGripFor(workspace, tab, motion)
    }

/** [tabDragHandle] with the strip's own motion in hand — see it for the two paths. */
internal fun Modifier.tabStripGripFor(
    workspace: TabWorkspace,
    tab: TabEntry,
    motion: TabStripMotion,
): Modifier =
    composed {
        val window = LocalTaoWindow.current ?: return@composed Modifier
        if (window.canPlaceOnScreen) {
            screenDragHandle(
                key = tab,
                isDragging = { workspace.draggedTab === tab },
                beginTransfer = { host -> workspace.beginTransferDrag(tab.id, host) },
            ) { host, pointerScreenPx ->
                workspace.beginDrag(tab.id, TabDragOrigin.Strip(host), pointerScreenPx)?.asScreenDrag()
            }
        } else {
            tabStripLocalDragHandle(workspace, tab, motion)
        }
    }

/**
 * The strip's own grip, for a window the app cannot place.
 *
 * Reordering is *local*: driven by the pointer's travel inside the window and
 * resolved against the strip's own slots, so it needs no screen coordinate and
 * no window to move. The moment the pointer leaves the strip the gesture is
 * handed to the platform's drag-and-drop session
 * ([Modifier.transferDragHandle]), and that is the only reason it can be: no
 * other window of the app hears a thing about a pointer another window holds,
 * so until that session exists no strip can show where a drop would land. With
 * it, every window's strip gets the drag in its own coordinates and previews
 * the drop, and the release resolves there.
 */
internal fun Modifier.tabStripLocalDragHandle(
    workspace: TabWorkspace,
    tab: TabEntry,
    motion: TabStripMotion,
): Modifier =
    composed {
        val window = LocalTaoWindow.current ?: return@composed Modifier
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val gesture =
            remember(workspace, tab, motion) {
                TabStripTransferGesture(workspace, motion, tab) { coordinates }
            }
        Modifier
            .pointerHoverIcon(
                if (workspace.draggedTab === tab) TaoPointerIcons.Grabbing else TaoPointerIcons.Grab,
            ).onGloballyPositioned { coordinates = it }
            .transferDragHandle(
                key = tab,
                window = window,
                begin = { workspace.beginTransferDrag(tab.id, window) },
                gesture = gesture,
            )
    }

/**
 * The strip's half of the gesture: it reorders while the pointer is over the
 * strip, and hands over the moment it leaves.
 */
private class TabStripTransferGesture(
    private val workspace: TabWorkspace,
    private val motion: TabStripMotion,
    private val tab: TabEntry,
    private val coordinates: () -> LayoutCoordinates?,
) : TransferDragGesture {
    private var carry: TabStripCarry? = null
    private var origin = 0f

    override fun onStart(pressPosition: Offset) {
        origin = pressPosition.x
        val group = tab.group ?: return
        workspace.takeInStrip(tab.id)
        carry = TabStripCarry(workspace, motion, tab) { workspace.tabsOf(group).map { it.id } }
        carry?.travel(0f)
    }

    override fun onDrag(position: Offset): Boolean {
        val live = carry ?: return true
        val inWindow = coordinates()?.takeIf { it.isAttached }?.localToWindow(position)
        if (live.leftTheStrip(inWindow)) {
            // The tab is leaving: the strip lets go of it, and the platform
            // session carries it from here — the drag icon under the pointer,
            // every window's strip previewing the drop.
            live.abandon()
            carry = null
            return true
        }
        live.travel(position.x - origin, sampleVelocity = true)
        return false
    }

    override fun onEnd(released: Boolean) {
        val live = carry ?: return
        carry = null
        if (released) live.release() else live.abandon()
    }
}

/**
 * One in-strip reorder in flight: how far the tab has travelled, the motion it
 * drives, and the speed it carries into the slide home.
 */
private class TabStripCarry(
    private val workspace: TabWorkspace,
    private val motion: TabStripMotion,
    private val tab: TabEntry,
    private val order: () -> List<String>,
) {
    private var live = true
    private val velocity = CarryVelocity()

    fun travel(
        slidePx: Float,
        sampleVelocity: Boolean = false,
    ) {
        if (!live) return
        if (sampleVelocity) velocity.sample(slidePx)
        motion.carry(tab.id, order(), slidePx)
        workspace.carryInStrip(tab.id, slidePx)
    }

    /**
     * Whether the pointer has left the strip's own rectangle — the only
     * question this gesture can ask, since it is told nothing about the
     * screen. `false` before the grip has been placed.
     */
    fun leftTheStrip(pointerInWindowPx: Offset?): Boolean {
        val group = tab.group ?: return false
        val strip = workspace.stripGeometry(group)?.layoutBoundsInWindowPx ?: return false
        val pointer = pointerInWindowPx ?: return false
        return !strip.inflate(STRIP_SLACK_PX).contains(pointer)
    }

    /** Let go inside the strip: it slides into the place the strip is showing. */
    fun release() {
        if (!live) return
        live = false
        motion.letHold()
        workspace.dropInStrip(tab.id, velocity.perSecond())
    }

    /** The gesture was abandoned: everything back to its slot. */
    fun abandon() {
        if (!live) return
        live = false
        motion.letGo(order())
        workspace.cancelInStrip()
    }

    private companion object {
        /** A press right on the strip's edge should not read as leaving it. */
        const val STRIP_SLACK_PX = 2f
    }
}

/**
 * How fast the tab is travelling along the strip, from the travels the gesture
 * reports: what the slide home starts with, so a flick carries through.
 * Smoothed, since one change can land a millisecond after the one before it.
 */
private class CarryVelocity {
    private var smoothed = 0f
    private var lastNanos = 0L
    private var lastTravel = Float.NaN

    fun sample(travelPx: Float) {
        val now = System.nanoTime()
        val elapsed = now - lastNanos
        val previous = lastTravel
        lastNanos = now
        lastTravel = travelPx
        if (previous.isNaN() || elapsed !in 1..MAX_GAP_NANOS) {
            smoothed = 0f
            return
        }
        val instant = (travelPx - previous) / (elapsed / NANOS_PER_SECOND)
        smoothed = smoothed * (1f - SMOOTHING) + instant * SMOOTHING
    }

    fun perSecond(): Float = smoothed.coerceIn(-MAX_SPEED, MAX_SPEED)

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** Longer than this between samples and the pointer was at rest, not travelling. */
        const val MAX_GAP_NANOS = 100_000_000L

        /** How much of the newest sample the estimate takes: enough to follow a flick, not a jitter. */
        const val SMOOTHING = 0.4f

        /** A flick harder than this is the pointer teleporting, not a throw. */
        const val MAX_SPEED = 6_000f
    }
}

private fun TabDragSession.asScreenDrag(): ScreenDrag =
    object : ScreenDrag {
        override fun update(pointerScreenPx: Offset) = this@asScreenDrag.update(pointerScreenPx)

        override fun end(pointerScreenPx: Offset) = this@asScreenDrag.end(pointerScreenPx)

        override fun cancel() = this@asScreenDrag.cancel()
    }
