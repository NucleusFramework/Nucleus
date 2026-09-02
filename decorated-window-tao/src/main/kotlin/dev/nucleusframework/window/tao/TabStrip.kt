package dev.nucleusframework.window.tao

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
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
import dev.nucleusframework.window.tao.workspace.ScreenDrag
import dev.nucleusframework.window.tao.workspace.positionInWindowPx
import dev.nucleusframework.window.tao.workspace.publishHostGeometry
import dev.nucleusframework.window.tao.workspace.rememberHostGeometry
import dev.nucleusframework.window.tao.workspace.screenDragHandle

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
 * @param trailing chrome placed right after the last tab — a new-tab button,
 *   typically. It sits inside the strip, so the strip stays a single drop
 *   target and a tab released over it is appended.
 */
@Composable
public fun TabStripScope.TabStrip(
    modifier: Modifier = Modifier,
    trailing: @Composable TabStripScope.() -> Unit = {},
) {
    val entries = tabs
    val dragged = workspace.draggedTab
    val preview = workspace.dropPreview?.takeIf { it.group === group }
    Row(
        modifier = modifier.fillMaxWidth().tabStripGeometry(workspace, group),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        entries.forEachIndexed { index, entry ->
            // The gap the dragged tab would take, so the strip shows where the
            // drop lands rather than only that it will land somewhere.
            if (preview?.index == index) DropIndicator()
            TabItem(
                scope = this@TabStrip,
                tab = entry,
                selected = entry.id == group.selectedId,
                // Dimmed while its ghost is being dragged: it is on its way out.
                leaving = dragged === entry && workspace.dragGhost != null,
                // An equal share of whatever the chrome leaves, capped at
                // [TabMaxWidth] — so tabs shrink together as more open, the way
                // a browser's do. Without the weight the strip would serve the
                // first tabs their full width and leave the last ones zero-wide:
                // present in the model, unclickable on screen.
                modifier = Modifier.tabSlot(group, index).weight(1f, fill = false),
            )
        }
        if (preview != null && preview.index >= entries.size) DropIndicator()
        trailing()
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
 * [TabStrip] applies it already; a strip written from scratch must apply it to
 * every tab, in strip order.
 */
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

/**
 * Makes this element the grip that drags [tab] between windows.
 *
 * Dragging the only tab of a window moves that window along with the pointer;
 * one of several is lifted out under a ghost. In both cases every strip in the
 * workspace shows where the tab would be inserted
 * ([TabWorkspace.dropPreview]), and releasing:
 *
 *  - over a strip inserts the tab there, reordering it when that is its own
 *    strip;
 *  - anywhere else tears it into a window of its own under the pointer — or,
 *    for the only tab of a window, just leaves that window where it was
 *    dropped.
 *
 * A press without movement does nothing, so the close button and a plain
 * click-to-select still work. The press is claimed, which keeps the title bar
 * from starting the native window move instead — the window is moved by the
 * workspace so the drop can be decided from the pointer position, at the cost
 * of the OS's own snapping while a tab is dragged.
 *
 * On native **Wayland** the gesture rides the platform's drag-and-drop
 * session instead, since the workspace can neither move a window nor hit-test
 * a strip from the source: a card with the tab's title follows the pointer,
 * the strip under it previews the insertion, and releasing there inserts the
 * tab; releasing anywhere else tears one of several tabs into a window the
 * compositor places, and leaves the only tab of a window where it is (that
 * window moves by its title bar's compositor drag).
 *
 * No-op outside a Tao window. Drives [TabWorkspace.beginDrag].
 */
public fun Modifier.tabDragHandle(
    workspace: TabWorkspace,
    tab: TabEntry,
): Modifier =
    screenDragHandle(
        key = tab,
        isDragging = { workspace.draggedTab === tab },
        beginTransfer = { window -> workspace.beginTransferDrag(tab.id, window) },
    ) { window, pointerScreenPx ->
        workspace.beginDrag(tab.id, TabDragOrigin.Strip(window), pointerScreenPx)?.asScreenDrag()
    }

private fun TabDragSession.asScreenDrag(): ScreenDrag =
    object : ScreenDrag {
        override fun update(pointerScreenPx: Offset) = this@asScreenDrag.update(pointerScreenPx)

        override fun end(pointerScreenPx: Offset) = this@asScreenDrag.end(pointerScreenPx)

        override fun cancel() = this@asScreenDrag.cancel()
    }

/** One tab: its title, a close button, and the whole thing a drag handle. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabItem(
    scope: TabStripScope,
    tab: TabEntry,
    selected: Boolean,
    leaving: Boolean,
    modifier: Modifier,
) {
    val colors = LocalTitleBarStyle.current.colors
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = TabCornerRadius, topEnd = TabCornerRadius)
    val background =
        when {
            selected -> colors.content.copy(alpha = TAB_SELECTED_ALPHA)
            hovered -> colors.content.copy(alpha = TAB_HOVER_ALPHA)
            else -> Color.Transparent
        }
    Row(
        modifier =
            modifier
                .widthIn(max = TabMaxWidth)
                .fillMaxHeight()
                .alpha(if (leaving) TAB_LEAVING_ALPHA else 1f)
                .background(background, shape)
                .tabDragHandle(scope.workspace, tab)
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
        TabCloseButton(colors.content) { scope.workspace.close(tab.id) }
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

/** The gap a dropped tab would fill: where in the strip the drag would land. */
@Composable
private fun DropIndicator() {
    val accent = LocalTitleBarStyle.current.colors.content
    Box(
        Modifier
            .width(DropIndicatorWidth)
            .fillMaxHeight()
            .padding(vertical = DropIndicatorInset)
            .background(accent.copy(alpha = DROP_INDICATOR_ALPHA), RoundedCornerShape(DropIndicatorWidth / 2)),
    )
}

/**
 * The translucent card a tab dragged out of its strip is previewed as, filling
 * the ghost window.
 */
@Composable
internal fun TabGhostCard(title: String) {
    val accent = LocalTitleBarStyle.current.colors.content
    val shape = RoundedCornerShape(TabCornerRadius)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(accent.copy(alpha = GHOST_FILL_ALPHA), shape)
                .border(GhostBorderWidth, accent.copy(alpha = GHOST_BORDER_ALPHA), shape),
        contentAlignment = Alignment.CenterStart,
    ) {
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
private val DropIndicatorWidth: Dp = 3.dp
private val DropIndicatorInset: Dp = 4.dp
private val GhostBorderWidth: Dp = 1.dp
private const val TAB_SELECTED_ALPHA = 0.16f
private const val TAB_HOVER_ALPHA = 0.08f
private const val TAB_LEAVING_ALPHA = 0.35f
private const val DROP_INDICATOR_ALPHA = 0.8f
private const val GHOST_FILL_ALPHA = 0.22f
private const val GHOST_BORDER_ALPHA = 0.55f
private const val TAB_TITLE_SP = 12
private const val TAB_CLOSE_SP = 14
