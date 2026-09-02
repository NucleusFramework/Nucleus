package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.Tab
import dev.nucleusframework.window.tao.TabDragOrigin
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TabWindows
import dev.nucleusframework.window.tao.TabWorkspace
import dev.nucleusframework.window.tao.TaoWindow

/**
 * Everything one tab case observes; fresh per case, so cases never share
 * windows or state.
 *
 * The tabs are declared at application scope next to [TabWindows], exactly as
 * an app declares them, and each publishes the window it is currently composed
 * in plus its `rememberSaveable` state — which is what lets a case assert that
 * a tab really moved and really kept its state.
 */
internal class TabWorkspaceFixture(
    initialTitles: List<String> = listOf("Alpha", "Beta"),
    private val windowSize: DpSize = DpSize(TAB_WINDOW_W_DP.dp, TAB_WINDOW_H_DP.dp),
) {
    val workspace = TabWorkspace(defaultWindowSize = windowSize)

    /** Ids in declaration order; a case may add to this to open a tab mid-run. */
    val titles = mutableStateListOf(*initialTitles.toTypedArray())

    /** The window each tab's body is composed in, by tab id. */
    val composedIn = mutableStateOf<Map<String, TaoWindow>>(emptyMap())

    /** The `rememberSaveable` counter of each tab's current composition, by tab id. */
    val counters = mutableStateOf<Map<String, MutableState<Int>>>(emptyMap())

    /** The scroll state of each tab's body — a `rememberSaveable` Int under the hood. */
    val scrolls = mutableStateOf<Map<String, Int>>(emptyMap())

    /** How many tab bodies are composing right now; two overlap for a frame while moving. */
    val composedBodies = mutableIntStateOf(0)

    /**
     * How many times each tab's body has been built from scratch. A move to
     * another window necessarily rebuilds it — the two windows are two
     * compositions — but a reorder or a selection change must not.
     */
    val bodyIncarnations = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Set once [TabWindows] reports the last window gone. */
    val lastWindowClosed = mutableStateOf(false)

    fun tabId(title: String): String = "tab-${title.lowercase()}"

    /** The group of the tab titled [title], or `null` while it has none. */
    fun groupOf(title: String): TabWindowGroup? = workspace.tab(tabId(title))?.group

    /** The window showing the tab titled [title], or `null` while it is not composed. */
    fun windowOf(title: String): TaoWindow? = composedIn.value[tabId(title)]

    /** Strip rect of [group] on screen (physical px), or `null` before its first layout. */
    fun stripRectPx(group: TabWindowGroup): Rect? = workspace.stripGeometry(group)?.layoutScreenRectPx()

    /** Screen position (physical px) of the centre of the tab titled [title] in its strip. */
    fun tabCenterPx(title: String): Offset? {
        val group = groupOf(title) ?: return null
        val index = group.ids.indexOf(tabId(title)).takeIf { it >= 0 } ?: return null
        val slot = group.slotsInWindowPx.getOrNull(index) ?: return null
        val client = workspace.stripGeometry(group)?.clientOriginPx() ?: return null
        return client + slot.center
    }

    @Composable
    fun ApplicationScope.Windows() {
        TabWindows(
            workspace = workspace,
            onLastWindowClosed = { lastWindowClosed.value = true },
        )
        for (title in titles) {
            val id = tabId(title)
            Tab(workspace = workspace, id = id, title = title) {
                val clicks = rememberSaveable { mutableStateOf(0) }
                val scroll = rememberScrollState()
                val window = LocalTaoWindow.current
                // A plain `remember`: it comes back at 0 whenever this subtree
                // is rebuilt rather than moved, which is what a body must not
                // do when its tab only changes window.
                val incarnation = remember { Any() }
                SideEffect {
                    counters.value = counters.value + (id to clicks)
                    scrolls.value = scrolls.value + (id to scroll.value)
                    if (window != null) composedIn.value = composedIn.value + (id to window)
                }
                DisposableEffect(incarnation) {
                    composedBodies.value++
                    bodyIncarnations.value = bodyIncarnations.value + (id to (bodyIncarnations.value[id] ?: 0) + 1)
                    onDispose {
                        composedBodies.value--
                        // Only if this window is still the one on record: the
                        // next host may already have published itself.
                        if (composedIn.value[id] === window) composedIn.value = composedIn.value - id
                    }
                }
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF)))
                    Box(Modifier.fillMaxSize().background(Color(0xFF1F4E9C)))
                }
            }
        }
    }
}

internal const val TAB_WINDOW_W_DP = 560
internal const val TAB_WINDOW_H_DP = 380
internal const val TAB_SAVED_CLICKS = 5

/** Vertical grab point inside a tab strip, in dp from the strip's top. */
internal const val TAB_GRAB_Y_DP = 10f

/** Far enough from every window that a drop there can only mean "tear off". */
internal const val TAB_DROP_FAR_PX = 340f

/**
 * The case window a tab case does not use: the harness always composes one and
 * hands it to the driver, so it is parked out of the way of the tab windows and
 * kept small. The tab windows are the ones the assertions are about.
 */
internal fun idleCaseWindowState() =
    WindowState(
        position = WindowPosition.Absolute(IDLE_CASE_X_DP.dp, IDLE_CASE_Y_DP.dp),
        size = idleCaseWindowSize(),
    )

internal fun idleCaseWindowSize() = DpSize(IDLE_CASE_W_DP.dp, IDLE_CASE_H_DP.dp)

/** A strip origin for [window], the call site a real drag handle uses. */
internal fun stripOrigin(window: TaoWindow) = TabDragOrigin.Strip(window)

/**
 * A rect for tearing a tab off [window] without a pointer: the same size,
 * offset down and to the right so the new window is visibly its own.
 */
internal fun tearOffRectPx(window: TaoWindow): Rect {
    val outer = requireNotNull(window.outerBoundsPx()) { "the source window is not mapped" }
    val offset = TEAR_OFF_OFFSET_DP * window.scaleFactor
    return Rect(
        outer[0] + offset,
        outer[1] + offset,
        outer[0] + offset + outer[2],
        outer[1] + offset + outer[3],
    )
}

/**
 * Waits until every named tab has been declared and the window showing the
 * selected one is mapped, and returns that window.
 */
internal suspend fun TaoWindowTestScope.awaitTabWindows(
    fixture: TabWorkspaceFixture,
    vararg titles: String,
): TaoWindow {
    awaitUntil("case window mapped") { bounds() != null }
    awaitUntil("every tab declared") { titles.all { fixture.workspace.tab(fixture.tabId(it)) != null } }
    awaitUntil("a tab window is mapped with a real size") {
        val window =
            fixture.workspace.groups
                .firstOrNull()
                ?.window ?: return@awaitUntil false
        val rect = window.outerBoundsPx() ?: return@awaitUntil false
        rect[2] > 0 && rect[3] > 0
    }
    awaitUntil("the selected tab's body is composed") { fixture.composedBodies.value > 0 }
    awaitUntil("the strip published its slots") {
        val group = fixture.workspace.groups.firstOrNull() ?: return@awaitUntil false
        fixture.stripRectPx(group) != null && group.slotsInWindowPx.size >= group.ids.size
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return requireNotNull(
        fixture.workspace.groups
            .first()
            .window,
    )
}

private const val IDLE_CASE_X_DP = 40
private const val IDLE_CASE_Y_DP = 620
private const val IDLE_CASE_W_DP = 220
private const val IDLE_CASE_H_DP = 120
private const val TEAR_OFF_OFFSET_DP = 60f

/** Rounding across a dp round trip, plus whatever the WM adds to a frame. */
internal const val TAB_SIZE_TOLERANCE_PX = 40L

/** Where along a strip a merge drops: past the midpoint of a single tab, so it appends. */
internal const val MERGE_X_FRACTION = 0.35f

/** Enough out-and-back rounds to expose a state leak, few enough to stay quick. */
internal const val TAB_CHURN_CYCLES = 2

/**
 * How far the ghost may trail the pointer, in physical px: one step of a
 * robot drag, since the last synthetic move may still be in flight when the
 * assertion runs.
 */
internal const val GHOST_FOLLOW_TOLERANCE_PX = 60f
