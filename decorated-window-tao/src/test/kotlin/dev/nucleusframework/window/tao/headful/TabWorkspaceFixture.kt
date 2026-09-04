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
    /**
     * When `true`, every tab body is also an inbound file-drop target and what
     * it receives is recorded in [dropLog]. Off by default: it adds a
     * drag-and-drop node to the body, which no case that is not about file
     * drops should have to reason about.
     */
    private val fileDropTargets: Boolean = false,
) {
    val workspace = TabWorkspace(defaultWindowSize = windowSize)

    private val dropLogs = HashMap<String, FileDropLog>()

    /** What the body of the tab titled [title] received from inbound file drags. */
    fun dropLog(title: String): FileDropLog = dropLogs.getOrPut(tabId(title)) { FileDropLog() }

    /** Ids in declaration order; a case may add to this to open a tab mid-run. */
    val titles = mutableStateListOf(*initialTitles.toTypedArray())

    /**
     * The windows each tab's body is composed in, by tab id, oldest host first.
     *
     * A list, not a single window: a tab that leaves a multi-tab window is
     * composed in *both* windows until the window it left renders again, and
     * Compose coalesces frames — so the two hosts genuinely overlap for as long
     * as the source window has a frame pending. Recording one window per tab
     * made the arriving host overwrite the departing one, and the departing
     * one's disposal then erased the entry for a body that was still composed.
     */
    val composedIn = mutableStateOf<Map<String, List<TaoWindow>>>(emptyMap())

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

    /**
     * How many times [TabWindows] has reported the last window gone. The
     * callback fires per non-empty → empty transition, so a workspace that is
     * emptied, filled and emptied again reports twice — and never for the
     * empty workspace of the first composition.
     */
    val lastWindowClosedCount = mutableIntStateOf(0)

    fun tabId(title: String): String = "tab-${title.lowercase()}"

    /** The group of the tab titled [title], or `null` while it has none. */
    fun groupOf(title: String): TabWindowGroup? = workspace.tab(tabId(title))?.group

    /** The window showing the tab titled [title], or `null` while it is not composed. */
    fun windowOf(title: String): TaoWindow? = composedIn.value[tabId(title)]?.lastOrNull()

    /** Strip rect of [group] on screen (physical px), or `null` before its first layout. */
    fun stripRectPx(group: TabWindowGroup): Rect? = workspace.stripGeometry(group)?.layoutScreenRectPx()

    /** Slot of the tab titled [title] on screen (physical px), or `null` before its first layout. */
    fun tabRectPx(title: String): Rect? {
        val group = groupOf(title) ?: return null
        val index = group.ids.indexOf(tabId(title)).takeIf { it >= 0 } ?: return null
        val slot = group.slotsInWindowPx.getOrNull(index) ?: return null
        val client = workspace.stripGeometry(group)?.clientOriginPx() ?: return null
        return slot.translate(client)
    }

    /**
     * Slot of the tab titled [title] in its **window's** content space
     * (physical px) — where a pointer event aims, and the one space that is
     * meaningful on every platform, screen placement or not.
     */
    fun tabSlotInWindowPx(title: String): Rect? {
        val group = groupOf(title) ?: return null
        val index = group.ids.indexOf(tabId(title)).takeIf { it >= 0 } ?: return null
        return group.slotsInWindowPx.getOrNull(index)
    }

    /** Centre of [tabSlotInWindowPx]. */
    fun tabPointInWindowPx(title: String): Offset? = tabSlotInWindowPx(title)?.center

    /**
     * What the aim of a robot gesture was derived from, for a case that timed
     * out: the frame the platform reported, the content size the strip was
     * measured in, the client origin those two imply, and the slot itself.
     *
     * `aimed (x, y), pointer at (x, y)` on its own only proves the pointer
     * went where the case asked. Whether *that* was the right place is this.
     */
    fun geometryReport(title: String): String {
        val group = groupOf(title) ?: return "no group for $title"
        val geometry = workspace.stripGeometry(group) ?: return "no strip geometry for $title"
        val outer = group.window?.outerBoundsPx()?.toList()
        return "outer=$outer content=${geometry.containerSizePx} client=${geometry.clientOriginPx()} " +
            "strip=${geometry.layoutBoundsInWindowPx} slot=${tabSlotInWindowPx(title)} " +
            "scale=${group.window?.scaleFactor} focused=${group.window?.isFocused} " +
            "windowsOverAim=${groupsCovering(HeadfulRobot.lastAimPoint)}"
    }

    /**
     * Which groups' windows cover [point] (logical screen points), in
     * workspace order — a press lands in whichever of them the platform has on
     * top, so a case that aimed right and saw nothing has its answer here.
     */
    private fun groupsCovering(point: java.awt.Point?): List<String> {
        if (point == null) return emptyList()
        return workspace.groups
            .filter { group ->
                val window = group.window ?: return@filter false
                val outer = window.outerBoundsPx() ?: return@filter false
                val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
                val x = point.x * scale
                val y = point.y * scale
                x >= outer[0] && x < outer[0] + outer[2] && y >= outer[1] && y < outer[1] + outer[3]
            }.map { it.id }
    }

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
            onLastWindowClosed = {
                lastWindowClosed.value = true
                lastWindowClosedCount.value++
            },
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
                }
                // The host is published for exactly this body's lifetime, not
                // on every recomposition: a body that outlives its selection
                // for a frame never recomposes again, so a SideEffect would
                // never get to republish it.
                DisposableEffect(incarnation) {
                    composedBodies.value++
                    bodyIncarnations.value = bodyIncarnations.value + (id to (bodyIncarnations.value[id] ?: 0) + 1)
                    if (window != null) composedIn.value = composedIn.value.plusHost(id, window)
                    onDispose {
                        composedBodies.value--
                        if (window != null) composedIn.value = composedIn.value.minusHost(id, window)
                    }
                }
                val body =
                    if (fileDropTargets) {
                        Modifier.fillMaxSize().fileDropRecorder(dropLog(title)).verticalScroll(scroll)
                    } else {
                        Modifier.fillMaxSize().verticalScroll(scroll)
                    }
                Column(body) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF)))
                    Box(Modifier.fillMaxSize().background(Color(0xFF1F4E9C)))
                }
            }
        }
    }
}

/** [window] added as the newest host composing the body of [id]. */
internal fun Map<String, List<TaoWindow>>.plusHost(
    id: String,
    window: TaoWindow,
): Map<String, List<TaoWindow>> = this + (id to ((this[id] ?: emptyList()) + window))

/** [window] dropped as a host of [id], leaving whatever other host is still composing it. */
internal fun Map<String, List<TaoWindow>>.minusHost(
    id: String,
    window: TaoWindow,
): Map<String, List<TaoWindow>> {
    val rest = (this[id] ?: return this).filterNot { it === window }
    return if (rest.isEmpty()) this - id else this + (id to rest)
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
        window.hasRealFramePx()
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

/** Waits until [group]'s window is mapped with a laid-out strip, and returns it. */
internal suspend fun TaoWindowTestScope.awaitMappedStrip(
    fixture: TabWorkspaceFixture,
    group: TabWindowGroup,
): TaoWindow {
    awaitUntil("the group's window is mapped with a real size") {
        group.window?.hasRealFramePx() == true
    }
    awaitUntil("its strip published its geometry and slots") {
        fixture.stripRectPx(group) != null && group.slotsInWindowPx.size >= group.ids.size
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return requireNotNull(group.window)
}

/** Screen point on [group]'s strip, [fraction] of the way along it. */
internal fun TabWorkspaceFixture.stripPointPx(
    group: TabWindowGroup,
    fraction: Float,
): Offset? {
    val strip = stripRectPx(group) ?: return null
    return Offset(strip.left + strip.width * fraction, strip.center.y)
}

/** A point far below [group]'s strip: a drop there can only mean "tear off". */
internal fun TabWorkspaceFixture.farFromStripPx(group: TabWindowGroup): Offset? {
    val strip = stripRectPx(group) ?: return null
    return Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
}

/** Skip reason for a case that needs the AWT Robot, or `null` when input can be injected. */
internal fun robotSkipReason(): String? = HeadfulRobot.unavailableReason?.let { "no input injection: $it" }

/** Inside the first tab of a strip, so a drop there inserts at the head. */
internal const val STRIP_HEAD_FRACTION = 0.02f

/** A little further along a strip, past the first tab's midpoint. */
internal const val STRIP_MID_FRACTION = 0.2f

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

/**
 * Waits until every named tab is declared, its window mapped and its strip has
 * published a slot per tab.
 *
 * The counterpart of [awaitTabWindows] for cases that aim at a tab in **window**
 * coordinates: it asks for nothing that native Wayland cannot answer, so a
 * pointer case built on it runs on every backend.
 */
internal suspend fun TaoWindowTestScope.awaitTabSlots(
    fixture: TabWorkspaceFixture,
    vararg titles: String,
): TaoWindow {
    awaitUntil("case window mapped") { bounds() != null }
    awaitUntil("every tab declared") { titles.all { fixture.workspace.tab(fixture.tabId(it)) != null } }
    awaitUntil("a tab window is mapped with a real size") {
        fixture.workspace.groups
            .firstOrNull()
            ?.window
            ?.hasRealFramePx() == true
    }
    awaitUntil("the selected tab's body is composed") { fixture.composedBodies.value > 0 }
    awaitUntil("the strip published a slot per tab with a real width") {
        val group = fixture.workspace.groups.firstOrNull() ?: return@awaitUntil false
        group.slotsInWindowPx.size >= group.ids.size && group.slotsInWindowPx.all { it.width > 1f }
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return requireNotNull(
        fixture.workspace.groups
            .first()
            .window,
    )
}
