@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.Satellite
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.Tab
import dev.nucleusframework.window.tao.TabScope
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TabWindows
import dev.nucleusframework.window.tao.TabWorkspace
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.dnd.TaoSceneDnD
import java.awt.datatransfer.DataFlavor
import java.io.File

// ── Inbound file drags ───────────────────────────────────────────────────────
//
// The OS hands an inbound drag to a window through the platform bridge
// callbacks, which resolve the scene's drop target through
// `TaoWindow.inboundDragAndDropNode` and pass it to `TaoSceneDnD`. These
// helpers enter the same funnel from inside the process: everything above the
// JNI boundary — the synthetic AWT transferable, the Compose drag-and-drop
// tree, the app's `dragAndDropTarget` — runs exactly as it does for a real
// drop from the file manager. Coordinates are physical pixels in the window's
// own content space, the space the native callbacks speak.

/** `null` when the window's scene has published no drop target (not attached yet). */
private fun TaoWindow.dropTargetNode() = inboundDragAndDropNode?.invoke()

/** `true` once this window's scene is attached and can answer an inbound drag. */
internal fun TaoWindow.hasSceneDropTarget(): Boolean = dropTargetNode() != null

/** A file drag entering [this] window at a content-space point; `true` when the scene took it. */
internal fun TaoWindow.fileDragEnter(pointInContentPx: Offset): Boolean =
    TaoSceneDnD.onDragEnter(dropTargetNode(), pointInContentPx.x.toInt(), pointInContentPx.y.toInt())

/** A file drag moving over [this] window; `true` while a drop target is eligible. */
internal fun TaoWindow.fileDragOver(pointInContentPx: Offset): Boolean =
    TaoSceneDnD.onDragOver(dropTargetNode(), pointInContentPx.x.toInt(), pointInContentPx.y.toInt())

/** The drag left [this] window without dropping. */
internal fun TaoWindow.fileDragLeave() {
    TaoSceneDnD.onDragLeave(dropTargetNode())
}

/** A file drop on [this] window; `true` when a target accepted it. */
internal fun TaoWindow.fileDrop(
    pointInContentPx: Offset,
    files: List<String>,
): Boolean =
    TaoSceneDnD.onDrop(
        dropTargetNode(),
        pointInContentPx.x.toInt(),
        pointInContentPx.y.toInt(),
        files.toTypedArray(),
    )

/** Enter, move and drop in one go — the shape of a drag the user completes. */
internal fun TaoWindow.fileDragAndDrop(
    pointInContentPx: Offset,
    files: List<String>,
): Boolean {
    fileDragEnter(pointInContentPx)
    fileDragOver(pointInContentPx)
    return fileDrop(pointInContentPx, files)
}

// ── In-process pointer input ─────────────────────────────────────────────────
//
// The native loop turns every mouse event into a `TaoWindow.dispatch` of a
// `TaoEventCode`, which the scene host translates into a Compose pointer event.
// These helpers post the same events, so the pointer pipeline under test is the
// real one — the deadband, the resize-edge band, the gesture detectors, the
// drag handles — with only the OS left out. That matters beyond convenience:
// `java.awt.Robot` cannot inject at all on a Wayland session (the compositor
// refuses the portal session, see [HeadfulRobot]), so this is the only way to
// exercise a click on that platform.
//
// Positions are physical pixels in the window's own content space — the space
// `HostGeometry.layoutBoundsInWindowPx` and `TabWindowGroup.slotsInWindowPx`
// are published in, so no screen placement is needed to aim at a tab.

/** Tao ships cursor positions as 1/1024 px fixed point; [TaoWindow.dispatch] expects that wire form. */
private const val POINTER_FIXED_POINT = 1024f

/** Moves the pointer to [pointInContentPx]. Sub-1-dp moves are swallowed by the deadband, as for a real mouse. */
internal fun TaoWindow.pointerMove(pointInContentPx: Offset) {
    dispatch(
        TaoEventCode.CURSOR_MOVED,
        (pointInContentPx.x * POINTER_FIXED_POINT).toInt(),
        (pointInContentPx.y * POINTER_FIXED_POINT).toInt(),
    )
}

/** Presses a mouse button at wherever the pointer last moved to. */
internal fun TaoWindow.pointerPress(button: Int = TaoMouseButton.LEFT) {
    dispatch(TaoEventCode.MOUSE_DOWN, button, 0)
}

/** Releases a mouse button. */
internal fun TaoWindow.pointerRelease(button: Int = TaoMouseButton.LEFT) {
    dispatch(TaoEventCode.MOUSE_UP, button, 0)
}

/** The pointer left the window. */
internal fun TaoWindow.pointerExit() {
    dispatch(TaoEventCode.CURSOR_LEFT, 0, 0)
}

/** Move, press, release — one click, with no motion in between. */
internal fun TaoWindow.pointerClick(
    pointInContentPx: Offset,
    button: Int = TaoMouseButton.LEFT,
) {
    pointerMove(pointInContentPx)
    pointerPress(button)
    pointerRelease(button)
}

/**
 * Presses at [from] and drags to [to] in [steps] samples, leaving the button
 * **down** so the caller can assert the in-flight state before
 * [TaoWindow.pointerRelease] ends it.
 *
 * Settles between samples: a gesture detector consumes events from a coroutine
 * on the scene's dispatcher, and a drag whose whole path arrives inside one
 * tick is not the gesture a user makes.
 */
internal suspend fun TaoWindowTestScope.pointerDragFrom(
    window: TaoWindow,
    from: Offset,
    to: Offset,
    steps: Int = POINTER_DRAG_STEPS,
    stepMillis: Long = POINTER_DRAG_STEP_MILLIS,
) {
    window.pointerMove(from)
    settle(stepMillis)
    window.pointerPress()
    settle(stepMillis)
    for (step in 1..steps) {
        window.pointerMove(from + (to - from) * (step / steps.toFloat()))
        settle(stepMillis)
    }
}

/** Enough samples to cross the touch slop and be a drag rather than a twitch. */
internal const val POINTER_DRAG_STEPS = 8

internal const val POINTER_DRAG_STEP_MILLIS = 16L

/**
 * What one drop target saw, published so a case can assert on it.
 *
 * [files] is read back through Compose's own `awtTransferable` accessor — the
 * route an application uses — so a case that finds the paths here has proven
 * the whole chain, not just that a callback fired.
 */
internal class FileDropLog {
    val entered = mutableIntStateOf(0)
    val moved = mutableIntStateOf(0)
    val exited = mutableIntStateOf(0)
    val ended = mutableIntStateOf(0)
    val drops = mutableIntStateOf(0)

    /** Paths of the last drop, in the order the transferable listed them. */
    val files = mutableStateOf<List<String>>(emptyList())

    /** Every path this target ever received, across drops. */
    val allFiles = mutableStateListOf<String>()

    /** What the target threw while reading a drop, if anything. */
    val failure = mutableStateOf<String?>(null)

    fun reset() {
        entered.value = 0
        moved.value = 0
        exited.value = 0
        ended.value = 0
        drops.value = 0
        files.value = emptyList()
        allFiles.clear()
        failure.value = null
    }
}

/**
 * Records every inbound file drag event on this node into [log].
 *
 * [accept] gates `shouldStartDragAndDrop`, so a case can put a target that
 * refuses the drag next to one that takes it — which is how a scene with
 * several targets decides where a drop lands.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.fileDropRecorder(
    log: FileDropLog,
    accept: Boolean = true,
): Modifier {
    val target =
        remember(log) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    log.entered.value++
                }

                override fun onMoved(event: DragAndDropEvent) {
                    log.moved.value++
                }

                override fun onExited(event: DragAndDropEvent) {
                    log.exited.value++
                }

                override fun onEnded(event: DragAndDropEvent) {
                    log.ended.value++
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    log.drops.value++
                    val paths = readPaths(event)
                    log.files.value = paths
                    log.allFiles += paths
                    return true
                }

                private fun readPaths(event: DragAndDropEvent): List<String> =
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (
                            event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)
                                as? List<File>
                        ).orEmpty().map { it.absolutePath }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") t: Throwable,
                    ) {
                        log.failure.value = "${t::class.simpleName}: ${t.message}"
                        emptyList()
                    }
            }
        }
    return dragAndDropTarget(shouldStartDragAndDrop = { accept }, target = target)
}

// ── The two archetypes composed: tabs, each window with its own palettes ─────

/**
 * The `tab-satellites` archetype on real windows: one [TabWorkspace] owning
 * the windows, and one [SatelliteWorkspace] **per tab window** whose palette
 * draws whichever tab that window is showing.
 *
 * The wiring mirrors `examples/tab-satellites-demo` down to where each piece
 * lives — the window joins its workspace from the window wrapper, the
 * [DockLayout] is inside the tab body, and the satellites are declared at
 * application scope per group — because that placement is the whole design:
 * anything else churns a native palette window on every tab change.
 *
 * Everything a case asserts on is published from composition, keyed by group
 * id for the palettes and by tab id for the bodies.
 */
internal class TabSatellitesFixture(
    initialTitles: List<String> = listOf("Alpha", "Beta"),
    windowSize: DpSize = DpSize(TAB_WINDOW_W_DP.dp, TAB_WINDOW_H_DP.dp),
    /**
     * Extra content composed inside every tab body, given the group of the
     * window it is composed in — a per-window animation, typically, so a case
     * can tell which windows the shared loop is actually painting.
     */
    private val bodyExtra: (@Composable (TabWindowGroup) -> Unit)? = null,
) {
    val tabs = TabWorkspace(defaultWindowSize = windowSize)

    /** Ids in declaration order; a case may add to this to open a tab mid-run. */
    val titles = mutableStateListOf(*initialTitles.toTypedArray())

    // Plain map, not snapshot state: it is read from composition and must not
    // invalidate anything when a window's workspace is created on demand.
    private val workspaces = HashMap<String, SatelliteWorkspace>()

    /** The satellite workspace of the tab window [groupId], created on first use. */
    fun palettesOf(groupId: String): SatelliteWorkspace = workspaces.getOrPut(groupId) { SatelliteWorkspace() }

    /** Whether [groupId] still has a workspace — a window's workspace is forgotten with the window. */
    fun hasPalettes(groupId: String): Boolean = groupId in workspaces

    /** How many satellite workspaces are alive; one per live tab window, no more. */
    val liveWorkspaces: Int get() = workspaces.size

    fun tabId(title: String): String = "tab-${title.lowercase()}"

    fun paletteId(groupId: String): String = "$groupId-palette"

    /** The group of the tab titled [title], or `null` while it has none. */
    fun groupOf(title: String): TabWindowGroup? = tabs.tab(tabId(title))?.group

    /** The window showing the tab titled [title], or `null` while it is not composed. */
    fun windowOf(title: String): TaoWindow? = composedIn.value[tabId(title)]

    /** The window each tab's body is composed in, by tab id. */
    val composedIn = mutableStateOf<Map<String, TaoWindow>>(emptyMap())

    /** The `rememberSaveable` counter of each tab's current composition, by tab id. */
    val counters = mutableStateOf<Map<String, MutableState<Int>>>(emptyMap())

    /** How many tab bodies are composing right now. */
    val composedBodies = mutableIntStateOf(0)

    /** Times [TabWindows] reported the last window gone. */
    val lastWindowClosedCount = mutableIntStateOf(0)

    /** The host window of each group's docked palette, by group id. */
    val panelHost = mutableStateOf<Map<String, TaoWindow>>(emptyMap())

    /** The floating window of each group's palette, by group id. */
    val floatingPalette = mutableStateOf<Map<String, TaoWindow>>(emptyMap())

    /** The tab title each group's palette is currently drawing, by group id. */
    val paletteShows = mutableStateOf<Map<String, String?>>(emptyMap())

    /** The `rememberSaveable` counter of each group's palette body, by group id. */
    val paletteCounters = mutableStateOf<Map<String, MutableState<Int>>>(emptyMap())

    /**
     * How many times each group's palette body was built from scratch. A dock
     * or an undock rebuilds it once — two hosts, two compositions — but a tab
     * change inside the window must not.
     */
    val paletteIncarnations = mutableStateOf<Map<String, Int>>(emptyMap())

    /** How many palette bodies are composing right now. */
    val composedPalettes = mutableIntStateOf(0)

    @Composable
    fun ApplicationScope.Windows() {
        TabWindows(
            workspace = tabs,
            windowContentWrapper = { content ->
                // The window joins its own workspace once, for as long as it
                // lives: tying membership to the tab body would destroy and
                // recreate a native palette on every tab change.
                val group = tabs.groupOf(window)
                if (group != null) JoinSatelliteWorkspace(palettesOf(group.id))
                content()
            },
            onLastWindowClosed = { lastWindowClosedCount.value++ },
        )
        for (title in titles) {
            key(title) {
                Tab(workspace = tabs, id = tabId(title), title = title) { TabBody(title) }
            }
        }
        for (group in rememberLiveGroups(tabs)) {
            key(group.id) { WindowPalette(group) }
        }
    }

    /** One tab's body: the dock layout its window's panels live in, plus a saveable value. */
    @Composable
    private fun TabScope.TabBody(title: String) {
        val id = tabId(title)
        val clicks = rememberSaveable { mutableStateOf(0) }
        val window = LocalTaoWindow.current
        val palettes = tab.group?.let { palettesOf(it.id) }

        SideEffect {
            counters.value = counters.value + (id to clicks)
            if (window != null) composedIn.value = composedIn.value + (id to window)
        }
        DisposableEffect(Unit) {
            composedBodies.value++
            onDispose {
                composedBodies.value--
                if (composedIn.value[id] === window) composedIn.value = composedIn.value - id
            }
        }
        val group = tab.group
        if (palettes == null || group == null) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF)))
        } else {
            DockLayout(palettes, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF))) {
                    bodyExtra?.invoke(group)
                }
            }
        }
    }

    /** The palette of one tab window, drawing whichever tab that window shows. */
    @Composable
    private fun ApplicationScope.WindowPalette(group: TabWindowGroup) {
        val workspace = palettesOf(group.id)
        DisposableEffect(group.id) {
            onDispose {
                workspaces.remove(group.id)
                panelHost.value = panelHost.value - group.id
                floatingPalette.value = floatingPalette.value - group.id
                paletteShows.value = paletteShows.value - group.id
            }
        }
        val shown = tabs.selectedTab(group)?.title
        Satellite(
            workspace = workspace,
            id = paletteId(group.id),
            title = "Palette ${shown ?: "—"}",
            initialPlacement =
                SatellitePlacement.Floating(
                    positioner = workspaceRightEdgePositioner(),
                    size = workspaceSatelliteSize(),
                ),
        ) {
            val clicks = rememberSaveable { mutableStateOf(0) }
            val window = LocalTaoWindow.current
            val docked = isDocked
            // A plain `remember`: back at a fresh identity whenever this
            // subtree is rebuilt rather than moved.
            val incarnation = remember { Any() }
            SideEffect {
                paletteCounters.value = paletteCounters.value + (group.id to clicks)
                paletteShows.value = paletteShows.value + (group.id to shown)
                if (docked) {
                    if (window != null) panelHost.value = panelHost.value + (group.id to window)
                } else if (window != null) {
                    floatingPalette.value = floatingPalette.value + (group.id to window)
                }
            }
            DisposableEffect(incarnation) {
                composedPalettes.value++
                paletteIncarnations.value =
                    paletteIncarnations.value + (group.id to (paletteIncarnations.value[group.id] ?: 0) + 1)
                onDispose {
                    composedPalettes.value--
                    if (docked) {
                        if (panelHost.value[group.id] === window) panelHost.value = panelHost.value - group.id
                    } else if (floatingPalette.value[group.id] === window) {
                        floatingPalette.value = floatingPalette.value - group.id
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF7A5CD6)))
        }
    }
}

/**
 * The tab workspace's groups, mirrored out through an effect.
 *
 * The tabs are declared above the call site, so the write that creates the
 * first group lands during a composition that has already read the list —
 * and Compose drops an invalidation aimed at a scope it has just composed.
 * Read directly, the first window's palettes would never be declared.
 */
@Composable
internal fun rememberLiveGroups(workspace: TabWorkspace): List<TabWindowGroup> {
    var groups by remember(workspace) { mutableStateOf(workspace.groups.toList()) }
    LaunchedEffect(workspace) {
        snapshotFlow { workspace.groups.toList() }.collect { groups = it }
    }
    return groups
}

/** Waits until every named tab is declared, its window mapped and its palettes alive. */
internal suspend fun TaoWindowTestScope.awaitTabSatellites(
    fixture: TabSatellitesFixture,
    vararg titles: String,
): TaoWindow {
    awaitUntil("case window mapped") { bounds() != null }
    awaitUntil("every tab declared") { titles.all { fixture.tabs.tab(fixture.tabId(it)) != null } }
    awaitUntil("a tab window is mapped with a real size") {
        val rect =
            fixture.tabs.groups
                .firstOrNull()
                ?.window
                ?.outerBoundsPx() ?: return@awaitUntil false
        rect[RECT_W] > 0 && rect[RECT_H] > 0
    }
    awaitUntil("the selected tab's body is composed") { fixture.composedBodies.value > 0 }
    val group = requireNotNull(fixture.tabs.groups.firstOrNull())
    awaitUntil("the window joined its own satellite workspace") {
        fixture.palettesOf(group.id).members.isNotEmpty()
    }
    awaitUntil("its palette is declared") {
        fixture.palettesOf(group.id).satellite(fixture.paletteId(group.id)) != null
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return requireNotNull(group.window)
}

/** Waits until the palette of [group] is composed as a floating window, and returns it. */
internal suspend fun TaoWindowTestScope.awaitFloatingPalette(
    fixture: TabSatellitesFixture,
    group: TabWindowGroup,
): TaoWindow {
    awaitUntil("the palette of ${group.id} floats with a real size") {
        val rect = fixture.floatingPalette.value[group.id]?.outerBoundsPx() ?: return@awaitUntil false
        rect[RECT_W] > 0 && rect[RECT_H] > 0
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return requireNotNull(fixture.floatingPalette.value[group.id])
}

/** A point in [window]'s content space, [fx]/[fy] of the way across it. */
internal fun contentPointPx(
    window: TaoWindow,
    fx: Float,
    fy: Float,
): Offset {
    val outer = requireNotNull(window.outerBoundsPx()) { "the window is not mapped" }
    return Offset(outer[RECT_W] * fx, outer[RECT_H] * fy)
}

/** Temp files a drop can name, deleted when the JVM exits. */
internal fun dropFiles(
    count: Int,
    prefix: String = "nucleus-drop",
): List<String> =
    (1..count).map { index ->
        File
            .createTempFile("$prefix-$index-", ".txt")
            .apply {
                deleteOnExit()
                writeText("drop $index")
            }.absolutePath
    }

/** Window size for the tab-satellites cases: wide enough for a strip of several tabs. */
internal const val CHAOS_WINDOW_W_DP = 720

internal const val CHAOS_WINDOW_H_DP = 460

/**
 * Tears the tab titled [title] out of [from] into a window of its own, and
 * waits until that window is mapped with a laid-out strip and a satellite
 * workspace of its own.
 */
internal suspend fun TaoWindowTestScope.tearOffTabWindow(
    fixture: TabSatellitesFixture,
    title: String,
    from: TaoWindow,
): TabWindowGroup {
    val group =
        requireNotNull(
            fixture.tabs.tearOff(fixture.tabId(title), tearOffRectPx(from), from.scaleFactor),
        ) { "tearing $title off produced no window" }
    awaitUntil("the torn-off window is mapped with a strip") {
        val window = group.window ?: return@awaitUntil false
        (window.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L &&
            fixture.tabs.stripGeometry(group)?.layoutScreenRectPx() != null &&
            group.slotsInWindowPx.size >= group.ids.size
    }
    awaitUntil("it joined a satellite workspace of its own") {
        fixture.hasPalettes(group.id) && fixture.palettesOf(group.id).owner === group.window
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return group
}

/** Screen centre (physical px) of the tab titled [title] in its strip. */
internal fun tabCenterOnScreenPx(
    fixture: TabSatellitesFixture,
    title: String,
): Offset? {
    val group = fixture.groupOf(title) ?: return null
    val index = group.ids.indexOf(fixture.tabId(title)).takeIf { it >= 0 } ?: return null
    val slot = group.slotsInWindowPx.getOrNull(index) ?: return null
    val client = fixture.tabs.stripGeometry(group)?.clientOriginPx() ?: return null
    return client + slot.center
}
