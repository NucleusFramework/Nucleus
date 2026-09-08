package dev.nucleusframework.window.tao

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nucleusframework.window.styling.LocalTitleBarStyle
import dev.nucleusframework.window.tao.workspace.positionInWindowPx
import dev.nucleusframework.window.tao.workspace.publishHostGeometry
import dev.nucleusframework.window.tao.workspace.rememberHostGeometry

/** What tab-strip chrome gets to see: the workspace and the group this strip belongs to. */
public interface TabStripScope {
    /** The workspace the strip belongs to. */
    public val workspace: TabWorkspace

    /** The group whose tabs this strip shows. */
    public val group: TabWindowGroup

    /** The tabs to show, in strip order. */
    public val tabs: List<TabEntry> get() = workspace.tabsOf(group)
}

internal class TabStripScopeImpl(
    override val workspace: TabWorkspace,
    override val group: TabWindowGroup,
) : TabStripScope

/**
 * The stock tab strip: one tab per entry of the group, the selected one
 * highlighted, each draggable between windows ([Modifier.tabDragHandle]) and
 * closable.
 *
 * The strip publishes its own geometry to the workspace, which is what lets a
 * tab dragged out of *another* window be dropped into this one — so custom
 * chrome should either build on this composable or publish the same geometry
 * with [Modifier.tabStripGeometry].
 *
 * Colours come from [LocalTitleBarStyle], so the strip matches whatever
 * title-bar theme the app installed.
 *
 * A tab dragged along its own strip stays in the strip's hands: it is drawn
 * under the pointer, its neighbours slide aside as its edge crosses their
 * centres, and on release it slides into the slot it was over before the
 * order changes — the motion of a browser's tab strip. Taken out of the strip
 * it becomes a ghost window, as a tab dragged to another window does, and the
 * strip it is carried over opens a slot of its width where it would land,
 * showing the same card ([dropGhost]), so the tab is seen taking its place
 * before it is let go.
 *
 * @param reorderAnimation how a tab travels along the strip — pushed aside,
 *   or sliding home; `null` moves it at once. Only the drawing is animated:
 *   the strip's published geometry is the settled layout throughout, so a
 *   drop resolved mid-motion still lands where the strip says it will.
 * @param hoverPreview the card shown under the tab the pointer rests on;
 *   `null`, the default, shows none. [TabHoverPreview.Default] is a browser's
 *   behaviour, and [TabHoverPreview] takes the card whole for an app that
 *   wants to draw its own.
 * @param trailing chrome placed right after the last tab — a new-tab button,
 *   typically. It sits inside the strip, so the strip stays a single drop
 *   target and a tab released over it is appended.
 */
@Composable
public fun TabStripScope.TabStrip(
    modifier: Modifier = Modifier,
    reorderAnimation: AnimationSpec<Float>? = TabReorderAnimation,
    hoverPreview: TabHoverPreview? = null,
    trailing: @Composable TabStripScope.() -> Unit = {},
) {
    val entries = tabs
    val motion = rememberTabStripMotion(reorderAnimation)
    // A tab still sliding home after being let go: the tabs themselves show
    // where it lands, and a slot would say it twice.
    val ghost = dropGhost?.takeIf { motion.animating == null }
    // The slot shuts with a slide when the tab moves on — but not when the tab
    // lands in it. The tab then takes the slot's place in the very frame the
    // drag ends, so the card is seen becoming the tab rather than shutting
    // beside it: the slots are keyed on a generation that turns over at the
    // landing, which drops the open one from the composition at once.
    val landing = remember(group) { TabLandingMemo() }
    workspace.draggedTab?.let { dragged -> if (ghost != null) landing.expect(dragged, ghost.index) }
    if (ghost == null) landing.settle(entries)
    val closing = remember(group) { mutableStateListOf<String>() }
    Row(
        modifier = modifier.fillMaxWidth().tabStripGeometry(workspace, group),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        entries.forEachIndexed { index, entry ->
            // The slot a tab coming from *another* window would take.
            key(landing.generation) { TabDropGhostSlot(ghost, index) }
            // Keyed on the tab, not on its place in the strip: Compose
            // otherwise identifies the items by position, so a reorder would
            // hand the arriving tab the state of the one that left — its hover
            // for a start — and no item would have moved for an animation to
            // follow.
            key(entry.id) {
                TabStripItem(
                    scope = this@TabStrip,
                    entry = entry,
                    index = index,
                    motion = motion,
                    closing = closing,
                    slotModifier = Modifier.weight(1f, fill = false).fillMaxHeight(),
                )
            }
        }
        key(landing.generation) { TabDropGhostSlot(ghost, entries.size) }
        trailing()
    }
    // Outside the Row: the card is a popup anchored to the tab's own slot, so
    // it belongs to the strip rather than to any one tab, and nothing about it
    // takes part in the strip's layout.
    hoverPreview?.let { TabHoverPreviewPopup(it) }
}

/**
 * Which tab the strip's open slot stands for, and where — so the frame that
 * shows the tab landed there can tell a landing from a drag that moved on.
 * Plain fields: bookkeeping read in the composition that writes it, never a
 * reason to recompose.
 */
private class TabLandingMemo {
    private var entry: TabEntry? = null
    private var index = -1

    /** Turned over at every landing; the slots are keyed on it. */
    var generation = 0
        private set

    fun expect(
        entry: TabEntry,
        index: Int,
    ) {
        this.entry = entry
        this.index = index
    }

    /** The slot has closed: if the tab it stood for is now at its place, it landed — snap the slot away. */
    fun settle(entries: List<TabEntry>) {
        val expected = entry ?: return
        if (entries.getOrNull(index) === expected) generation++
        entry = null
        index = -1
    }
}

/**
 * The slot a tab dragged from another window would fill in this strip: the
 * place it lands, the width it brings and its title — drawn with
 * [TabDropGhostCard] where [TabStrip]'s own layout puts it, or by a strip
 * written from scratch at [index] among its tabs (`tabs.size` is after the
 * last one).
 *
 * `null` while nothing is dragged over this strip, and for a tab of this very
 * strip in the strip's own hands: its neighbours moving aside already show
 * where it lands.
 */
public val TabStripScope.dropGhost: TabDropGhost?
    get() {
        val preview = workspace.dropPreview?.takeIf { it.group === group } ?: return null
        val dragged = workspace.draggedTab ?: return null
        if (dragged.group === group && workspace.dragGhost == null) return null
        return TabDropGhost(preview.index.coerceIn(0, tabs.size), workspace.draggedTabWidth(dragged), dragged.title)
    }

/**
 * Where a tab dragged from another window would land in a strip, and what it
 * looks like there — see [TabStripScope.dropGhost].
 *
 * @property index the place among the strip's tabs; `tabs.size` is after the last.
 * @property width the width the tab has in the strip it comes from.
 * @property title the tab's title.
 */
public data class TabDropGhost(
    val index: Int,
    val width: Dp,
    val title: String,
)

/**
 * The card a [TabDropGhost] is drawn as: [TabDropGhost.width] wide, the
 * strip's height, the same card the tab travels under. A strip written from
 * scratch composes it at [TabDropGhost.index] among its tabs.
 */
@Composable
public fun TabDropGhostCard(
    ghost: TabDropGhost,
    modifier: Modifier = Modifier,
) {
    TabGhostCard(ghost.title, modifier.width(ghost.width).fillMaxHeight())
}

/**
 * One of the strip's gaps — before the tab at [index], or after the last —
 * opening to [ghost]'s width while [ghost] lands there and shutting when it
 * moves on, so the tabs slide aside for it as they do for one of their own.
 */
@Composable
private fun TabDropGhostSlot(
    ghost: TabDropGhost?,
    index: Int,
) {
    val shown = ghost?.takeIf { it.index == index }
    // Kept through the exit, which still needs a width and a title to shut.
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    AnimatedVisibility(
        visible = shown != null,
        enter = expandHorizontally(TabEnterAnimation, clip = false),
        exit = shrinkHorizontally(TabExitAnimation, clip = false),
    ) {
        last?.let { TabDropGhostCard(it) }
    }
}

/**
 * Publishes this element as [group]'s tab strip: the drop target a tab dragged
 * from any window of [workspace] can be released on.
 *
 * [TabStrip] applies it already; use it directly when writing a strip from
 * scratch, on the element that spans the whole strip, and mark each tab's own
 * slot with [Modifier.tabSlot] so the insertion index can be worked out.
 */
public fun Modifier.tabStripGeometry(
    workspace: TabWorkspace,
    group: TabWindowGroup,
): Modifier =
    composed {
        val containerSize = LocalWindowInfo.current.containerSize
        val geometry = rememberHostGeometry(workspace.stripHosts, group.window)
        Modifier
            .publishHostGeometry(geometry, containerSize)
            .tabTransferTarget(workspace, group)
    }

/**
 * Makes the strip the drop target of a [TabWorkspace.transferDrag]: the drag
 * that rides the platform's DnD session where strips cannot be hit-tested
 * from the source (native Wayland). The insertion index is resolved here, in
 * this window's coordinates — previewed while hovering, recorded on the
 * session at the drop for the source to act on when the session ends.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.tabTransferTarget(
    workspace: TabWorkspace,
    group: TabWindowGroup,
): Modifier {
    val target = remember(workspace, group) { TabTransferTarget(workspace, group) }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { workspace.transferDrag != null },
        target = target,
    )
}

private class TabTransferTarget(
    private val workspace: TabWorkspace,
    private val group: TabWindowGroup,
) : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = preview(event)

    override fun onMoved(event: DragAndDropEvent) = preview(event)

    override fun onExited(event: DragAndDropEvent) = clearPreview()

    override fun onEnded(event: DragAndDropEvent) = clearPreview()

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val drag = workspace.transferDrag ?: return false
        drag.drop = insertion(drag, event) ?: return false
        clearPreview()
        return true
    }

    /**
     * Where the dragged tab would land in this strip; `null` for the only tab
     * of this very window, which has no "in" here — its own strip moves with
     * it on the other platforms and is no target there either.
     */
    private fun insertion(
        drag: TabTransferDrag,
        event: DragAndDropEvent,
    ): TabDropTarget? {
        if (drag.entry.group === group && group.tabIds.size == 1) return null
        return TabDropTarget(group, workspace.insertionIndex(group, event.positionInWindowPx().x, exclude = drag.entry))
    }

    private fun preview(event: DragAndDropEvent) {
        val drag = workspace.transferDrag ?: return
        workspace.dropPreview = insertion(drag, event)
    }

    private fun clearPreview() {
        if (workspace.dropPreview?.group === group) workspace.dropPreview = null
    }
}

/**
 * Marks this element as the slot of the tab at [index] in [group], which is
 * what turns a pointer position into an insertion index.
 *
 * It is also what publishes the tab under the pointer
 * ([TabStripScope.hoveredTab]) and the rect a hover card is anchored to, so a
 * strip that marks its slots gets [TabHoverPreviewPopup] for nothing.
 *
 * [TabStrip] applies it already; a strip written from scratch must apply it to
 * every tab, in strip order.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.tabSlot(
    group: TabWindowGroup,
    index: Int,
): Modifier =
    onGloballyPositioned { coordinates ->
        val slots = group.slotsInWindowPx.toMutableList()
        while (slots.size <= index) slots += Rect.Zero
        slots[index] = coordinates.boundsInWindow()
        // Trailing slots of tabs that have left: the list is rebuilt from the
        // ones still placed, so a stale rect cannot shift an insertion index.
        group.slotsInWindowPx = slots.take(group.ids.size.coerceAtLeast(index + 1))
    }
        // The id is resolved at event time, not captured: the slot at an index
        // is whichever tab the strip has put there.
        .onPointerEvent(PointerEventType.Enter) { group.noteHoverEnter(group.ids.getOrNull(index)) }
        .onPointerEvent(PointerEventType.Exit) { group.noteHoverExit(group.ids.getOrNull(index)) }
        .onPointerEvent(PointerEventType.Press) { group.noteHoverPress(group.ids.getOrNull(index)) }

/** One tab: its title, a close button, and the whole thing a drag handle. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Suppress("LongParameterList")
internal fun TabItem(
    scope: TabStripScope,
    tab: TabEntry,
    selected: Boolean,
    leaving: Boolean,
    held: Boolean,
    /** `true` while a tab of this strip is in hand: the others stop reacting to the pointer. */
    hoverSuppressed: Boolean,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val colors = LocalTitleBarStyle.current.colors
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = TabCornerRadius, topEnd = TabCornerRadius)
    // A tab in hand is faded, and it fades rather than switches, so picking one
    // up and putting it down again is one motion. Held inside its own strip it
    // stays nearly solid — it is a card being carried, not a tab on its way out.
    val targetAlpha =
        when {
            held -> TAB_HELD_ALPHA
            leaving -> TAB_LEAVING_ALPHA
            else -> 1f
        }
    val leavingAlpha by animateFloatAsState(targetAlpha, TabFadeAnimation)
    val background =
        when {
            // Carried, it needs a body of its own: a tab whose background is
            // the strip's would travel as a bare title and read as nothing.
            held -> colors.content.copy(alpha = TAB_HELD_BACKGROUND_ALPHA)
            selected -> colors.content.copy(alpha = TAB_SELECTED_ALPHA)
            // A tab under the pointer while another is being carried over it is
            // not being pointed at, it is being passed: highlighting it would
            // light up every tab the carried one crosses.
            hovered && !hoverSuppressed -> colors.content.copy(alpha = TAB_HOVER_ALPHA)
            else -> Color.Transparent
        }
    Row(
        modifier =
            modifier
                .widthIn(max = TabMaxWidth)
                .fillMaxHeight()
                .alpha(leavingAlpha)
                .background(background, shape)
                .clickable { scope.workspace.select(tab.id) }
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = TabHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = tab.title,
            modifier = Modifier.weight(1f),
            style =
                TextStyle(
                    color = colors.content,
                    fontSize = TAB_TITLE_SP.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TabCloseButton(colors.content, onClose)
    }
}

@Composable
private fun TabCloseButton(
    color: Color,
    onClick: () -> Unit,
) {
    // `clickable` consumes the press, which is what opts this out of both the
    // tab drag and the title bar's native window move.
    Box(
        modifier = Modifier.clickable(onClick = onClick).padding(TabCloseInset),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = "×", style = TextStyle(color = color, fontSize = TAB_CLOSE_SP.sp))
    }
}

/**
 * The card a tab is previewed as while it is dragged — following the pointer
 * out of its strip, and drawn on the slot it would take in another: its title
 * on the shared [DragPreviewSurface].
 */
@Composable
internal fun TabGhostCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    val accent = LocalTitleBarStyle.current.colors.content
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        DragPreviewSurface(Modifier.matchParentSize())
        BasicText(
            text = title,
            modifier = Modifier.padding(horizontal = TabHorizontalPadding),
            style = TextStyle(color = accent, fontSize = TAB_TITLE_SP.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val TabMaxWidth: Dp = 220.dp
private val TabHorizontalPadding: Dp = 8.dp
private val TabCornerRadius: Dp = 8.dp
private val TabCloseInset: Dp = 3.dp
private const val TAB_SELECTED_ALPHA = 0.16f
private const val TAB_HOVER_ALPHA = 0.08f
private const val TAB_LEAVING_ALPHA = 0.35f

/** A tab held under the pointer in its own strip: almost solid, and clearly in hand. */
private const val TAB_HELD_ALPHA = 0.7f

/** The body a carried tab is given, so it travels as a card rather than as a title. */
private const val TAB_HELD_BACKGROUND_ALPHA = 0.16f
private const val TAB_TITLE_SP = 12
private const val TAB_CLOSE_SP = 14

private const val TAB_REORDER_MILLIS = 180
private const val TAB_ENTER_MILLIS = 200
private const val TAB_EXIT_MILLIS = 200
private const val TAB_FADE_MILLIS = 150
