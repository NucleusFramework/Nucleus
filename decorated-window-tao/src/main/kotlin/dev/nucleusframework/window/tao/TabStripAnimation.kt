package dev.nucleusframework.window.tao

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import dev.nucleusframework.window.noWindowDrag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How a tab travels along its strip by default — pushed aside by the one in
 * hand, or sliding into its new place on release: a soft spring, the motion of
 * a browser's tab strip.
 */
public val TabReorderAnimation: AnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)

/** How a tab opens: its width grows into the strip. */
internal val TabEnterAnimation: FiniteAnimationSpec<IntSize> =
    tween(durationMillis = TAB_ENTER_MILLIS, easing = FastOutSlowInEasing)

/** How a tab closes: its width shuts, taking the strip with it. */
internal val TabExitAnimation: FiniteAnimationSpec<IntSize> =
    tween(durationMillis = TAB_EXIT_MILLIS, easing = FastOutSlowInEasing)

/** The fade that goes with a tab closing, and with one being picked up. */
internal val TabFadeAnimation: FiniteAnimationSpec<Float> = tween(durationMillis = TAB_FADE_MILLIS)

/**
 * The motion of one strip's tabs while one of them is in hand, after the
 * pattern of a reorderable row: every tab has a draw-time offset, the tab in
 * hand is drawn at the pointer's travel since the grab, and a neighbour
 * slides a whole tab aside the moment the carried tab's leading edge crosses
 * its centre — back again when it uncrosses. On release the carried tab
 * slides into the slot it was over, and only then does the order change, so
 * nothing is ever seen jumping.
 *
 * Offsets are drawn through `graphicsLayer`, so none of this moves a layout:
 * the slots the workspace resolves a drop against stay where the settled
 * layout put them, and the neighbours' shifts read from those same slots.
 */
internal class TabStripMotion(
    private val scope: CoroutineScope,
) {
    private val offsets = HashMap<String, Animatable<Float, AnimationVector1D>>()

    /** Each tab's slot in window px, from its last placement. */
    private val slots = HashMap<String, Rect>()

    /** How far the tab [id] is drawn from its slot right now; `0` for one at rest. */
    fun drawnOffsetOf(id: String): Float = offsets[id]?.value ?: 0f

    /** Where the tab [id]'s slot is, in window px, or `null` before its first placement. */
    fun slotOf(id: String): Rect? = slots[id]

    /** The tab in hand, or the one still sliding home after a release. */
    var animating: String? by mutableStateOf(null)
        private set

    /** The tab under the pointer; `null` once it has been let go. */
    var held: String? by mutableStateOf(null)
        private set

    var spec: AnimationSpec<Float>? = TabReorderAnimation

    fun offsetOf(id: String): Animatable<Float, AnimationVector1D> = offsets.getOrPut(id) { Animatable(0f) }

    fun placed(
        id: String,
        slot: Rect,
    ) {
        slots[id] = slot
    }

    /**
     * The tab [id] has been carried [slidePx] from where it was grabbed. Its
     * own offset snaps there — it is the pointer — and every other tab of
     * [order] is pushed a slot aside or let back, by where the carried tab's
     * edges now are against their centres.
     */
    fun carry(
        id: String,
        order: List<String>,
        slidePx: Float,
    ) {
        held = id
        animating = id
        val own = slots[id] ?: return
        scope.launch { offsetOf(id).snapTo(slidePx) }
        val currentStart = own.left + slidePx
        val currentEnd = own.right + slidePx
        val neighbours = order.filter { it != id }.mapNotNull { other -> slots[other]?.let { other to it.center.x } }
        for ((other, centre) in neighbours) {
            val target =
                when {
                    currentStart < own.left && centre in currentStart..own.left -> own.width
                    currentStart > own.left && centre in own.right..currentEnd -> -own.width
                    else -> 0f
                }
            moveTo(other, target)
        }
    }

    /** The pointer let go, but the carried tab keeps its offset: the settle slides it from there. */
    fun letHold() {
        held = null
    }

    /**
     * The tab in hand has left the strip's hands — a ghost took it out of the
     * window, or the drag was abandoned: everything slides back where it was.
     */
    fun letGo(order: List<String>) {
        held = null
        for (id in order) moveTo(id, 0f)
        animating = null
    }

    /**
     * The tab [id], released, slides into the slot of rank [target] in
     * [order]; suspends until it has arrived. The caller then changes the
     * order and calls [rest], in that sequence, so the frame that shows the
     * new order shows every tab at zero offset exactly where it already was.
     */
    suspend fun settle(
        id: String,
        order: List<String>,
        target: Int,
        velocityPxPerSecond: Float = 0f,
    ) {
        held = null
        animating = id
        val from = order.indexOf(id)
        val own = slots[id]
        val into = order.getOrNull(target)?.let(slots::get)
        val destination =
            if (own == null || into == null || from < 0) {
                0f
            } else if (target > from) {
                into.right - own.right
            } else {
                into.left - own.left
            }
        val animate = spec
        if (animate == null) {
            offsetOf(id).snapTo(destination)
        } else {
            offsetOf(id).animateTo(destination, animate, initialVelocity = velocityPxPerSecond)
        }
    }

    /** Every offset back to zero at once: the order has just changed under the tabs. */
    suspend fun rest() {
        for (animatable in offsets.values) animatable.snapTo(0f)
        animating = null
    }

    private fun moveTo(
        id: String,
        target: Float,
    ) {
        val animatable = offsetOf(id)
        if (animatable.targetValue == target) return
        val animate = spec
        scope.launch {
            if (animate == null) animatable.snapTo(target) else animatable.animateTo(target, animate)
        }
    }
}

@Composable
internal fun TabStripScope.rememberTabStripMotion(spec: AnimationSpec<Float>?): TabStripMotion {
    val scope = rememberCoroutineScope()
    val motion = remember(group) { workspace.motionFor(group, scope) }
    motion.spec = spec
    val workspace = workspace
    // Where the app places its own windows the drag is the workspace's, and
    // the pointer it publishes is what the strip animates from; the local
    // gesture of a compositor-placed window drives the motion itself.
    LaunchedEffect(motion, workspace, group) {
        snapshotFlow {
            val tab = workspace.draggedTab
            val pointer = workspace.dragPointerScreenPx
            val grab = workspace.dragGrabScreenPx
            val inHand =
                tab != null &&
                    tab.group === group &&
                    pointer != null &&
                    grab != null &&
                    workspace.dragGhost == null &&
                    workspace.dropPreview?.group === group
            if (inHand) Triple(tab!!.id, pointer!!.x - grab!!.x, workspace.tabsOf(group).map { it.id }) else null
        }.collect { sample ->
            if (sample != null) {
                motion.carry(sample.first, sample.third, sample.second)
            } else if (motion.held != null && workspace.pendingReorder == null) {
                motion.letGo(workspace.tabsOf(group).map { it.id })
            }
        }
    }
    // The release inside this strip: slide home, then reorder.
    val settle = workspace.pendingReorder?.takeIf { it.group === group }
    LaunchedEffect(settle) {
        if (settle == null) return@LaunchedEffect
        val order = workspace.tabsOf(group).map { it.id }
        motion.settle(settle.tab.id, order, settle.index, settle.velocityPxPerSecond)
        workspace.reorder(settle.tab.id, settle.index)
        motion.rest()
        if (workspace.pendingReorder === settle) workspace.pendingReorder = null
    }
    return motion
}

/**
 * One tab of the strip: its slot, which is the geometry a drop resolves
 * against, and inside it the tab as it is drawn — carried, pushed aside,
 * sliding home, opening or closing.
 *
 * @param slotModifier the share of the strip the caller gives this tab.
 */
@Suppress("LongParameterList")
@Composable
internal fun TabStripItem(
    scope: TabStripScope,
    entry: TabEntry,
    index: Int,
    motion: TabStripMotion,
    closing: SnapshotStateList<String>,
    slotModifier: Modifier,
) {
    val workspace = scope.workspace
    val group = scope.group
    val held = motion.held == entry.id
    val coroutineScope = rememberCoroutineScope()

    // A tab the strip has not shown yet opens; one the close button took
    // shuts, and only then leaves the workspace.
    var visible by remember { mutableStateOf<Boolean>(!entry.isEntering) }
    LaunchedEffect(entry) {
        entry.isEntering = false
        visible = true
    }
    if (entry.id in closing) visible = false

    AnimatedVisibility(
        visible = visible,
        // In hand or sliding home, it is drawn over its neighbours: a Row draws
        // its children in order, so a tab carried past the ones after it would
        // otherwise slide underneath them.
        modifier = slotModifier.zIndex(if (motion.animating == entry.id) 1f else 0f),
        // A tab opens and closes by width, so the strip never jumps. Unclipped:
        // a tab in hand is drawn outside its own slot, and a clip would cut it
        // at the slot's edges.
        enter = expandHorizontally(TabEnterAnimation, clip = false),
        exit = shrinkHorizontally(TabExitAnimation, clip = false) + fadeOut(TabFadeAnimation),
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = TabMaxWidth)
                    .fillMaxHeight()
                    // The slot is this box, and it is never animated: what the
                    // workspace resolves a drop against is the settled layout,
                    // whatever the drawing is doing.
                    .tabSlot(group, index)
                    .onPlaced { motion.placed(entry.id, it.boundsInWindow()) }
                    // The grip is the slot, not the card: the card is drawn
                    // translated under the pointer, and a gesture on a node
                    // that follows the pointer reads no movement at all —
                    // in its own coordinates the pointer never moves.
                    // Never the window's move: a tab is dragged by the pointer,
                    // and on a compositor-placed surface the title bar's move is
                    // a grab that swallows the whole gesture. The grip claims the
                    // press on Main, but the bar arms on Final for *any*
                    // unclaimed press — a press this gesture is not ready for
                    // (the one that lands while the previous is winding down)
                    // would take the window with it.
                    .noWindowDrag()
                    .tabStripGripFor(workspace, entry, motion),
        ) {
            val offset = motion.offsetOf(entry.id)
            TabItem(
                scope = scope,
                tab = entry,
                selected = entry.id == group.selectedId,
                // On its way out of this window, which dims it right down;
                // held inside the strip, which draws it as a card in hand.
                leaving = entry === workspace.draggedTab && workspace.dragGhost != null,
                held = held,
                hoverSuppressed = motion.held != null,
                // Drawn where the motion puts it — at draw time, so a layer
                // translation moves no layout and recomposes nothing.
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = offset.value },
            ) {
                if (entry.id !in closing) {
                    closing += entry.id
                    coroutineScope.launch {
                        delay(TAB_EXIT_MILLIS.toLong())
                        closing -= entry.id
                        workspace.close(entry.id)
                    }
                }
            }
        }
    }
}

private const val TAB_ENTER_MILLIS = 200
private const val TAB_EXIT_MILLIS = 200
private const val TAB_FADE_MILLIS = 150
