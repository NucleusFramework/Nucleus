// #636: the ghost opener below is `@ComposableOpenTarget(-1)` with a
// `@UiComposable` content lambda, like every window opener.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.window.tao

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.ExperimentalNucleusApi
import dev.nucleusframework.window.noWindowDrag
import dev.nucleusframework.window.tao.workspace.DragGhostWindow

/*
 * The parts TabWindows is built from, for an app that opens the windows of a
 * TabWorkspace itself — its own DecoratedWindow per group, with its own state,
 * title, key handling and close policy — and draws its own tab strip.
 */

/**
 * Binds [window] to [group] for as long as the caller is composed: the
 * workspace learns which native window shows the group — what drops, tear-offs
 * and focus tracking resolve against — and a system quit leaves the window's
 * tabs to the workspace instead of closing them (the workspace is the session).
 *
 * Call it inside the window that shows [group], once. [TabWindows] does it for
 * the windows it opens.
 */
@Composable
@ExperimentalNucleusApi
public fun BindTabGroupWindow(
    workspace: TabWorkspace,
    group: TabWindowGroup,
    window: TaoWindow? = LocalTaoWindow.current,
) {
    DisposableEffect(workspace, group, window) {
        if (window == null) return@DisposableEffect onDispose {}
        window.closesOnQuit = false
        workspace.attachWindow(group, window)
        onDispose { if (group.window === window) workspace.detachWindow(group) }
    }
}

/**
 * Applies to [state] the placements the workspace asks for — a [TabWorkspace.restore],
 * a [TabWorkspace.tearOff] of a window's only tab — and nothing else, so a
 * window the user is dragging is never snapped back.
 *
 * [TabWindows] does it for the windows it opens; an app sizing its own
 * windows seeds [state] from [TabWindowGroup.position] and [TabWindowGroup.size].
 */
@Composable
@ExperimentalNucleusApi
public fun TabGroupWindowPlacement(
    group: TabWindowGroup,
    state: WindowState,
) {
    LaunchedEffect(group, state, group.placementRevision) {
        if (group.placementRevision == 0) return@LaunchedEffect
        group.position?.let { state.position = WindowPosition.Absolute(it.x, it.y) }
        state.size = group.size
    }
}

/**
 * The window a tab dragged out of its strip travels in: borderless, following
 * the pointer, the size the tab had in its strip — composed only while
 * [TabWorkspace.dragGhost] is set, and never where the app cannot place
 * windows (native Wayland), where the compositor draws the drag icon.
 *
 * [TabWindows] composes it with its `dragGhost` slot; an app opening its own
 * windows composes it once, next to them.
 */
@Suppress("FunctionNaming")
@Composable
@ComposableOpenTarget(-1)
@ExperimentalNucleusApi
public fun ApplicationScope.TabDragGhostWindow(
    workspace: TabWorkspace,
    compositionLocalContext: CompositionLocalContext? = null,
    content: @Composable @UiComposable TaoDecoratedWindowScope.(TabDragGhost) -> Unit = { TabDragGhostCard(it) },
) {
    val ghost = workspace.dragGhost ?: return
    DragGhostWindow(
        screenRectPx = ghost.screenRectPx,
        scaleFactor = ghost.scaleFactor,
        title = ghost.tab.title,
        compositionLocalContext = compositionLocalContext,
        layoutDirection = ghost.layoutDirection,
    ) {
        content(ghost)
    }
}

/**
 * The tab gestures of one strip, for a strip that draws its own tabs: the
 * carry along the strip, the slide home on release, and the hand-over to the
 * workspace once a tab leaves the strip (a ghost window and a drop elsewhere,
 * or the platform's drag session on native Wayland) — the behaviour of
 * [TabStrip] with the app's own chrome.
 *
 * Put [tabStripGrip] on each tab's slot and [tabStripCarry] on what it draws.
 */
@Stable
@ExperimentalNucleusApi
public class TabStripDrag internal constructor(
    internal val motion: TabStripMotion,
) {
    /** The tab in hand inside this strip, or `null`. */
    public val held: String? get() = motion.held

    /** The tab being carried or sliding home — draw it over its neighbours — or `null`. */
    public val animating: String? get() = motion.animating

    /** How far [tabId] is drawn from its slot along the strip, in px; read it at draw time. */
    public fun offsetPx(tabId: String): Float = motion.drawnOffsetOf(tabId)
}

/**
 * The gestures of this strip's tabs — see [TabStripDrag]. [reorderAnimation]
 * is how a tab travels along the strip; `null` moves it at once.
 */
@Composable
@ExperimentalNucleusApi
public fun TabStripScope.rememberTabStripDrag(
    reorderAnimation: AnimationSpec<Float>? = TabReorderAnimation,
): TabStripDrag {
    val motion = rememberTabStripMotion(reorderAnimation)
    return androidx.compose.runtime.remember(motion) { TabStripDrag(motion) }
}

/**
 * Makes this element the slot of [tab] at [index] and its grip: what a drop
 * resolves against ([tabSlot]) and what starts the drag. Apply it to the
 * element that stays put — the slot — not to the drawing that follows the
 * pointer ([tabStripCarry]): a gesture on a node that moves with the pointer
 * reads no movement at all. Opts the slot out of the title bar's window move.
 */
@ExperimentalNucleusApi
public fun Modifier.tabStripGrip(
    scope: TabStripScope,
    drag: TabStripDrag,
    tab: TabEntry,
    index: Int,
): Modifier =
    tabSlot(scope.group, index)
        .onPlaced { drag.motion.placed(tab.id, it.boundsInWindow()) }
        .noWindowDrag()
        .tabStripGripFor(scope.workspace, tab, drag.motion)

/** Draws [tab] where the strip's motion puts it: carried, pushed aside, sliding home. */
@ExperimentalNucleusApi
public fun Modifier.tabStripCarry(
    drag: TabStripDrag,
    tab: TabEntry,
): Modifier = graphicsLayer { translationX = drag.offsetPx(tab.id) }
