// #636: the window openers below are `@ComposableOpenTarget(-1)` with
// `@UiComposable` content lambdas — callable from any applier, always composing
// UI — so a non-UI composable called in the caller's scope cannot reclassify
// the window content. ktlint's `annotation` and `function-type-modifier-spacing`
// rules contradict each other on the resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.window.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.BasicTitleBar
import dev.nucleusframework.window.TitleBarLayoutPolicy
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.tao.workspace.DragGhostWindow
import dev.nucleusframework.window.tao.workspace.RelocatedContentHost

/**
 * What a tab's body gets to see: the tab, its workspace, and the actions tab
 * chrome needs.
 */
public interface TabScope {
    /** The workspace the tab belongs to. */
    public val workspace: TabWorkspace

    /** The tab being composed. */
    public val tab: TabEntry

    /** Makes this tab the visible one of its group. */
    public fun select() {
        workspace.select(tab.id)
    }

    /** Removes the tab; its window closes with it when it was the last one. */
    public fun close() {
        workspace.close(tab.id)
    }
}

internal class TabScopeImpl(
    override val workspace: TabWorkspace,
    override val tab: TabEntry,
) : TabScope

/**
 * Declares a tab of [workspace]. Where it is shown is the workspace's
 * business: [TabWindows] composes it in whichever window's group holds it.
 *
 * Declare every tab once, at application scope, next to [TabWindows]:
 *
 * ```kotlin
 * val workspace = rememberTabWorkspace()
 * TabWindows(workspace, onLastWindowClosed = ::exitApplication)
 * for (document in documents) {
 *     Tab(workspace, id = document.id, title = document.name) { Editor(document) }
 * }
 * ```
 *
 * On first declaration the tab joins [group] when given — created if it does
 * not exist yet — else the window that was focused last, else a new one. After
 * that the workspace owns its placement, so an id already known only has its
 * title and body refreshed. `rememberSaveable` state inside [content] survives
 * every move between windows; plain `remember` state does not.
 *
 * @param id stable identity within the workspace.
 * @param title shown on the tab and, for the selected tab, as the window title.
 * @param group the group to open in on first declaration.
 * @param content the tab's body.
 */
@Suppress("FunctionNaming")
@Composable
@ComposableOpenTarget(-1)
public fun ApplicationScope.Tab(
    workspace: TabWorkspace,
    id: String,
    title: String,
    group: String? = null,
    content: @Composable @UiComposable TabScope.() -> Unit,
) {
    val entry = remember(workspace, id) { workspace.register(id, title, group) }
    // Published as snapshot state so the window hosting the tab picks up a new
    // lambda without this composable knowing which window that is.
    SideEffect {
        entry.title = title
        entry.content = content
    }
    DisposableEffect(workspace, entry) {
        onDispose { workspace.unregister(entry) }
    }
}

/**
 * Composes one [DecoratedWindow] per group of [workspace] — the windows the
 * user has pulled tabs into — with a [TabStrip] in each title bar and the
 * group's selected tab as its content.
 *
 * A group appears when a tab is torn off and disappears when its last tab
 * leaves, so windows follow the tabs without the app opening or closing any.
 * [onLastWindowClosed] fires when the final group goes, which is where an app
 * calls `exitApplication`.
 *
 * `rememberSaveable` state inside a tab survives the move from one window to
 * the next: the workspace carries it across, and the body is composed from one
 * shared call site here so the two compositions agree on its keys.
 *
 * @param strip the chrome of one window's tab strip; [TabStrip] by default.
 *   Composed inside the window's title bar.
 * @param compositionLocalContext parent locals bridged into every window's own
 *   scene, as for [DecoratedWindow].
 * @param windowContentWrapper composed around each window's chrome and
 *   content, inside that window's scene — the hook framework layers use to
 *   provide their per-window locals. Must invoke the lambda it is given.
 * @param onLastWindowClosed called every time the workspace goes from holding
 *   groups to holding none — never for the empty workspace this composable
 *   first sees, since the tabs are declared after it.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
public fun ApplicationScope.TabWindows(
    workspace: TabWorkspace,
    compositionLocalContext: CompositionLocalContext? = null,
    strip: @Composable TabStripScope.() -> Unit = { TabStrip() },
    windowContentWrapper: @Composable TaoDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit = { it() },
    onLastWindowClosed: () -> Unit = {},
) {
    val ghost = workspace.dragGhost
    if (ghost != null) {
        DragGhostWindow(
            screenRectPx = ghost.screenRectPx,
            scaleFactor = ghost.scaleFactor,
            title = ghost.tab.title,
            compositionLocalContext = compositionLocalContext,
        ) {
            TabGhostCard(ghost.tab.title)
        }
    }

    val currentOnLastClosed = rememberUpdatedState(onLastWindowClosed)

    // The groups to compose, mirrored out of the workspace by an effect rather
    // than read straight from it.
    //
    // The tabs are declared next to this call, so the first group is created by
    // a write that lands *during* the composition that has already read the
    // list here — and Compose drops an invalidation aimed at a scope it has
    // just composed, taking it for an imminent one. Read directly, the very
    // first window would then never be composed at all: an application whose
    // only windows come from the workspace would never open one. Written from
    // an effect, outside composition, every change lands.
    var groups by remember(workspace) { mutableStateOf(workspace.groups.toList()) }
    LaunchedEffect(workspace) {
        snapshotFlow { workspace.groups.toList() }.collect { groups = it }
    }

    // Only a real close fires the callback: the workspace is empty on the
    // first composition too, and firing then would close an application that
    // has not opened a window yet.
    val empty = groups.isEmpty()
    val everOpened = remember { mutableStateOf(false) }
    LaunchedEffect(empty) {
        if (!empty) {
            everOpened.value = true
        } else if (everOpened.value) {
            currentOnLastClosed.value()
        }
    }

    for (group in groups) {
        key(group.id) {
            TabWindow(workspace, group, compositionLocalContext, strip, windowContentWrapper)
        }
    }
}

/** One group's window: its strip in the title bar, its selected tab as content. */
@Suppress("FunctionNaming")
@Composable
private fun ApplicationScope.TabWindow(
    workspace: TabWorkspace,
    group: TabWindowGroup,
    compositionLocalContext: CompositionLocalContext?,
    strip: @Composable TabStripScope.() -> Unit,
    windowContentWrapper: @Composable TaoDecoratedWindowScope.(content: @Composable () -> Unit) -> Unit,
) {
    val state =
        rememberWindowState(
            position = group.position?.toWindowPosition() ?: WindowPosition.PlatformDefault,
            size = group.size,
        )
    // A restore moves a window that is already open; a user drag does not go
    // through the group, so nothing here fights the pointer.
    LaunchedEffect(group.placementRevision) {
        if (group.placementRevision == 0) return@LaunchedEffect
        group.position?.let { state.position = WindowPosition.Absolute(it.x, it.y) }
        state.size = group.size
    }
    val selected = workspace.selectedTab(group)
    DecoratedWindow(
        // Closing a window closes the tabs it holds — the group goes with its
        // last tab, so this composable leaves on its own.
        onCloseRequest = { group.ids.toList().forEach(workspace::close) },
        state = state,
        title = selected?.title.orEmpty(),
        compositionLocalContext = compositionLocalContext,
    ) {
        val windowScope: TaoDecoratedWindowScope = this
        val window = windowScope.window
        DisposableEffect(workspace, group, window) {
            workspace.attachWindow(group, window)
            onDispose { workspace.detachWindow(group) }
        }
        val stripScope = remember(workspace, group) { TabStripScopeImpl(workspace, group) }
        windowContentWrapper {
            with(windowScope) {
                WindowScaffold(
                    titleBar = {
                        // FillCenter hands its single centre child exactly the
                        // width left between the platform controls, which is
                        // where a tab strip belongs: a strip, not a title.
                        BasicTitleBar(layoutPolicy = TitleBarLayoutPolicy.FillCenter) {
                            Box(Modifier.fillMaxWidth()) { strip(stripScope) }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        TabBody(workspace, selected)
                    }
                }
            }
        }
    }
}

/**
 * The selected tab's body, composed from this one call site in every window.
 *
 * That is what makes `rememberSaveable` state survive a move: the relocation
 * matches keys between two hosts whose path to the content is identical, and
 * routing every window through here is how the paths stay identical. Wrapping
 * the call per window — or per group — would break it.
 *
 * Keyed on the tab, and it has to be. Compose identifies what it remembers by
 * position, so without the key a change of selection would hand the arriving
 * body the slots of the one that left: its `remember` values, its effects, and
 * its `rememberSaveable` registry entries. The key is above the relocation
 * anchor, not below it, so the path from the anchor down to the content is
 * still identical in every window.
 */
@Suppress("FunctionNaming")
@Composable
private fun TabBody(
    workspace: TabWorkspace,
    tab: TabEntry?,
) {
    if (tab == null) return
    key(tab.id) {
        val scope = remember(workspace, tab) { TabScopeImpl(workspace, tab) }
        RelocatedContentHost(tab.stateSlot, scope, tab.content)
    }
}

private fun DpOffset.toWindowPosition(): WindowPosition = WindowPosition.Absolute(x, y)
