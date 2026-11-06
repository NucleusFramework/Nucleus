package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.Satellite
import dev.nucleusframework.window.tao.SatelliteDragOrigin
import dev.nucleusframework.window.tao.SatelliteDragSession
import dev.nucleusframework.window.tao.SatelliteEntry
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteScope
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.collections.randomOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The monkey: [MONKEY_ACTIONS] random actions on one [SatelliteWorkspace], in
 * an order no case would ever write by hand.
 *
 * Every other workspace case drives a gesture the way a user performs it —
 * begin, move, release, assert. That is how the intended behaviour is pinned
 * down, and it is also why those cases only ever visit states someone thought
 * of. This one draws each step from [MonkeyAction] with a seeded
 * [Random], so the interleavings it reaches are the ones nobody wrote down: a
 * window closing under a drag that started in another window, a palette docked
 * into a host that is being resized while the workspace is hidden, a scale
 * change landing between a tear-out and its window.
 *
 * What it asserts is deliberately not "the right thing happened" — for a random
 * sequence there is no such expectation. It asserts that nothing is left
 * **orphaned** and nothing **wedges**:
 *
 *  - the workspace never names a window it does not have — no member is a
 *    destroyed window, no satellite is docked into a non-member, no owner or
 *    pin points outside the membership;
 *  - no drag feedback outlives its drag, and no satellite composes in two
 *    hosts once a step has settled;
 *  - native windows do not accumulate: the count stays under what the
 *    declaration can account for at every step, and comes back down to exactly
 *    the quiesced set at the end;
 *  - the Tao event loop and `Dispatchers.Main` keep answering each other. That
 *    one cannot be asserted from the driver — the driver runs *on* the
 *    dispatcher, so a deadlock stops it too and the case would simply run out
 *    of time with nothing said. [MainLoopWatchdog] measures it from a thread
 *    that is not on the loop and dumps every stack the moment a heartbeat goes
 *    unanswered, which is the whole diagnosis;
 *  - the workspace still *works* afterwards: the closing phase asks for a
 *    plain state (visible, nothing docked, one window) and it has to converge.
 *
 * A native panic cannot be asserted at all — a Rust `panic!` across JNI aborts
 * the process, and no Kotlin frame survives to record it. Reaching the end of
 * the case *is* the assertion, and the seed printed at the start is what makes
 * an abort reproducible.
 *
 * Every failure carries the seed and the last [JOURNAL_DEPTH] actions, and
 * `-Dnucleus.tao.headful.monkeySeed=<seed>` replays the *action sequence*
 * exactly. It does not replay the run: the state each action lands on depends
 * on what the loop and the compositor got done in the milliseconds before it,
 * so a red seed usually needs a few attempts — and the journal, not the seed,
 * is what identifies the sequence to turn into a case of its own.
 */
internal object SatelliteWorkspaceMonkeyHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(randomActionsLeaveNothingBehind())

    private fun randomActionsLeaveNothingBehind(): TaoWindowTestCase {
        val fixture = MonkeyFixture()
        return TaoWindowTestCase(
            name = "workspace monkey $MONKEY_ACTIONS random actions leave no orphan and no deadlock",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            // Same gate as every other satellite case: without client-side
            // screen placement `beginDrag` refuses, and half the actions would
            // be no-ops. The Wayland gestures have their own suite.
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.HostBody() },
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                fixture.awaitReady(this)
                val monkey = Monkey(this, fixture, monkeySeed())
                monkey.run()
                monkey.quiesceAndAssert()
            },
        )
    }
}

/**
 * One atomic thing the monkey can do. Drawn uniformly, so over
 * [MONKEY_ACTIONS] steps each is exercised often enough to interleave with
 * every other one — the point of the case is the pairs, not the actions.
 *
 * Each action is a single call plus a short settle: the monkey deliberately
 * does **not** wait for a steady state in between, because the states worth
 * finding are the ones a gesture is interrupted in.
 */
private enum class MonkeyAction {
    /** Shows a palette that was closed. */
    OpenSatellite,

    /** Hides a palette; its placement and state are kept. */
    CloseSatellite,

    /** Docks a palette on a random side of a random member's dock layout. */
    Dock,

    /** Lifts a docked palette back into a floating window. */
    Undock,

    /** Adds a host window to the workspace (up to [MAX_EXTRA_WINDOWS]). */
    OpenWindow,

    /** Drops a host window from composition — the member leaves as it is destroyed. */
    CloseWindow,

    /** Focuses a member, which moves the owner floating palettes follow. */
    FocusWindow,

    /** Begins a drag of a random open palette from wherever it currently lives. */
    StartDrag,

    /** Feeds the live drag a pointer position: a dock zone, content, far away, or garbage. */
    MoveDrag,

    /** Releases the live drag wherever it last was — docks, re-docks or tears out. */
    EndDrag,

    /** Abandons the live drag the way a cancelled pointer gesture does. */
    CancelDrag,

    /** Maximizes or restores a window; a maximized owner also hides its floating palettes. */
    ToggleMaximize,

    /** Resizes a window to a random inner size, re-laying out whatever it hosts. */
    ResizeRandom,

    /** Injects a scale-factor change, as a display hop does. */
    ChangeDpi,

    /** Flips the workspace-wide visibility sweep, which takes every palette down and back. */
    ToggleVisible,
}

/**
 * The declaration the monkey plays with: one workspace, three palettes and up
 * to [MAX_EXTRA_WINDOWS] host windows beside the case window.
 *
 * The palettes are declared for the whole run and opened / closed through the
 * workspace, exactly as an app's View menu does — a palette withdrawn from
 * composition would take its [SatelliteEntry] with it and there would be
 * nothing left to find orphaned. The host windows are the opposite: they come
 * and go from composition, so closing one is a real native destroy with a real
 * membership change behind it.
 */
private class MonkeyFixture {
    val workspace = SatelliteWorkspace()

    /** Palette ids, declared once for the whole run. */
    val satelliteIds = listOf("tools", "outline", "inspector")

    /** Host windows in composition, by slot. The case window is a member too, and never leaves. */
    private val slots = mutableStateListOf<Int>()
    private var nextSlot = 0

    private val hostWindows = mutableStateOf<Map<Int, TaoWindow>>(emptyMap())
    private val floating = mutableStateOf<Map<String, TaoWindow>>(emptyMap())
    private val panelHost = mutableStateOf<Map<String, TaoWindow>>(emptyMap())

    /**
     * Which hosts are composing each palette, as `role@windowHandle#n`.
     *
     * A count would say "two hosts" and leave the interesting half out: what
     * matters when a palette is composed twice is *which* windows they are —
     * the same one twice is a bookkeeping mistake here, two different ones is
     * a composition the framework failed to dispose.
     */
    private val liveHosts = mutableStateOf<Map<String, List<String>>>(emptyMap())
    private var nextIncarnation = 0

    /** Host windows currently declared — not the same thing while one is being destroyed. */
    val declaredWindows: Int get() = slots.size

    /** The floating window of the palette [id], or `null` while it has none. */
    fun floatingWindow(id: String): TaoWindow? = floating.value[id]

    /**
     * How many hosts are composing the palette [id] right now. Exactly one for
     * an open palette; two only for the frame in which a dock or an undock
     * hands it from one host to the next.
     */
    fun composedHostCount(id: String): Int = liveHosts.value[id]?.size ?: 0

    /** The hosts composing the palette [id], for a failure report. */
    fun composedHostsOf(id: String): List<String> = liveHosts.value[id].orEmpty()

    /** Declares one more host window; `false` when the ceiling is already reached. */
    fun openWindow(): Boolean {
        if (slots.size >= MAX_EXTRA_WINDOWS) return false
        slots += nextSlot++
        return true
    }

    /** Drops a random host window from composition; `false` when there is none. */
    fun closeWindow(random: Random): Boolean {
        if (slots.isEmpty()) return false
        slots.removeAt(random.nextInt(slots.size))
        return true
    }

    /** Drops every host window, leaving the case window as the only member. */
    fun closeEveryWindow() {
        slots.clear()
    }

    @Composable
    fun ApplicationScope.Windows() {
        for (slot in slots) {
            key(slot) { MonkeyHostWindow(slot) }
        }
        for (id in satelliteIds) {
            key(id) { MonkeyPalette(id) }
        }
    }

    /** What every member window hosts: the workspace membership and a dock layout to drop into. */
    @Composable
    fun HostBody() {
        JoinSatelliteWorkspace(workspace)
        DockLayout(workspace, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.DarkGray))
        }
    }

    /** A host window the monkey can destroy, offset from the others so they do not fully overlap. */
    @Composable
    private fun ApplicationScope.MonkeyHostWindow(slot: Int) {
        val lane = slot % MAX_EXTRA_WINDOWS
        val state =
            rememberWindowState(
                position =
                    WindowPosition.Absolute(
                        (EXTRA_X_DP + lane * EXTRA_STEP_DP).dp,
                        (EXTRA_Y_DP + lane * EXTRA_STEP_DP).dp,
                    ),
                size = DpSize(EXTRA_W_DP.dp, EXTRA_H_DP.dp),
            )
        DecoratedWindow(
            onCloseRequest = { /* the monkey owns the lifecycle */ },
            state = state,
            title = "tao-headful-monkey host $slot",
        ) {
            HostBody()
            val host = window
            DisposableEffect(host) {
                hostWindows.value = hostWindows.value + (slot to host)
                onDispose {
                    if (hostWindows.value[slot] === host) hostWindows.value = hostWindows.value - slot
                }
            }
        }
    }

    @Composable
    private fun ApplicationScope.MonkeyPalette(id: String) {
        Satellite(
            workspace = workspace,
            id = id,
            title = "Palette $id",
            initialPlacement =
                SatellitePlacement.Floating(
                    positioner = workspaceRightEdgePositioner(),
                    size = workspaceSatelliteSize(),
                ),
        ) {
            PaletteBody(id)
        }
    }

    /**
     * Publishes which window is composing the palette, and how many are.
     * Keyed on both the host role and the window, so a palette moved from one
     * window's dock straight into another's is counted as two hosts for the
     * frame in which it is.
     */
    @Composable
    private fun SatelliteScope.PaletteBody(id: String) {
        val host = LocalTaoWindow.current
        val docked = isDocked
        val label =
            remember(docked, host) {
                "${if (docked) "docked" else "floating"}@${host?.handle?.toString(HEX) ?: "none"}#${nextIncarnation++}"
            }
        SideEffect {
            if (host == null) return@SideEffect
            if (docked) {
                panelHost.value = panelHost.value + (id to host)
            } else {
                floating.value = floating.value + (id to host)
            }
        }
        DisposableEffect(label) {
            liveHosts.value = liveHosts.value + (id to (liveHosts.value[id].orEmpty() + label))
            onDispose {
                liveHosts.value = liveHosts.value + (id to (liveHosts.value[id].orEmpty() - label))
                if (docked) {
                    if (panelHost.value[id] === host) panelHost.value = panelHost.value - id
                } else if (floating.value[id] === host) {
                    floating.value = floating.value - id
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color(PALETTE_ARGB)))
    }

    /** Waits until the case window, its dock layout and all three palettes are up. */
    suspend fun awaitReady(scope: TaoWindowTestScope) {
        with(scope) {
            awaitUntil("the case window is mapped") { bounds() != null }
            awaitUntil("it joined the workspace") { workspace.members.isNotEmpty() }
            awaitUntil("every palette is declared") { satelliteIds.all { workspace.satellite(it) != null } }
            awaitUntil("every palette floats with a real size") {
                satelliteIds.all { id ->
                    val rect = floating.value[id]?.outerBoundsPx()
                    rect != null && rect[RECT_W] > 0L && rect[RECT_H] > 0L
                }
            }
            awaitUntil("the dock layout published its geometry") {
                workspace.dockHostGeometry(window)?.layoutScreenRectPx() != null
            }
            settle(SETTLE_AFTER_MAP_MILLIS)
        }
    }
}

/**
 * The run itself: draws actions, applies them under a short budget, and checks
 * after every one of them that the workspace still describes something that
 * exists.
 */
private class Monkey(
    private val scope: TaoWindowTestScope,
    private val fixture: MonkeyFixture,
    private val seed: Long,
) {
    private val random = Random(seed)

    /**
     * The last [JOURNAL_DEPTH] actions, newest last — the only thing that makes
     * a random failure readable. Concurrent because [MainLoopWatchdog] prints
     * it from its own thread, precisely when the main thread is not answering.
     */
    private val journal = ConcurrentLinkedDeque<String>()

    private val workspace get() = fixture.workspace

    private var drag: SatelliteDragSession? = null
    private var lastDragPoint = Offset.Zero
    private var step = 0
    private var worstStallMillis = 0L

    /**
     * What the run actually reached, printed when it ends. A monkey that
     * refuses every drag and never opens a window still passes every
     * invariant, so a green run has to say what it did — otherwise the case
     * silently stops testing anything the day a guard starts rejecting early.
     */
    private val reached = mutableMapOf<String, Int>()

    suspend fun run() {
        System.err.println(
            "[monkey] seed=$seed actions=$MONKEY_ACTIONS " +
                "(replay with -D$MONKEY_SEED_PROPERTY=$seed)",
        )
        val watchdog = MainLoopWatchdog("satellite-monkey", ::journalReport).start()
        try {
            while (step < MONKEY_ACTIONS) {
                val action = MonkeyAction.entries[random.nextInt(MonkeyAction.entries.size)]
                record(action)
                perform(action)
                checkStepInvariants()
                if ((step + 1) % CHECKPOINT_EVERY == 0) checkpoint()
                step++
            }
        } finally {
            worstStallMillis = watchdog.stop()
        }
    }

    /**
     * Puts the desktop back to a plain state and requires that it converges
     * there. A workspace that survived the storm but can no longer be brought
     * back to one visible window with three floating palettes is exactly as
     * broken as one that failed mid-run — it just fails later, in the app.
     */
    suspend fun quiesceAndAssert() {
        cancelDrag()
        workspace.visible = true
        for (target in everyWindow()) {
            target.setMaximized(false)
            // Undo whatever fake scale the monkey injected: the scene's density
            // is a listener away from the native value, and the geometry checks
            // below read the real frames.
            target.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (target.scaleFactor * SCALE_MILLI).roundToInt(), 0)
        }
        scope.window.setInnerSize(PARENT_W_DP.toDouble(), PARENT_H_DP.toDouble())
        fixture.closeEveryWindow()
        for (id in fixture.satelliteIds) {
            workspace.undock(id)
            workspace.open(id)
        }
        scope.settle(SETTLE_AFTER_MAP_MILLIS)

        awaitConverges("the workspace is down to the case window") {
            workspace.members == listOf(scope.window)
        }
        awaitConverges("no drag feedback is left behind") {
            workspace.draggedSatellite == null && workspace.dragGhost == null && workspace.dockPreview == null
        }
        awaitConverges("every palette floats again with a real size") {
            fixture.satelliteIds.all { id ->
                val rect = fixture.floatingWindow(id)?.outerBoundsPx()
                rect != null && rect[RECT_W] > 0L && rect[RECT_H] > 0L
            }
        }
        awaitConverges("exactly one host composes each palette") {
            fixture.satelliteIds.all { fixture.composedHostCount(it) == 1 }
        }
        val quiesced = 1 + fixture.satelliteIds.size
        awaitConverges("the run leaked no window (expected $quiesced)") {
            TaoApplication.liveWindowCount() <= quiesced
        }
        for (entry in workspace.satellites) {
            if (entry.dockHost != null) fail("${entry.id} still names a dock host while floating")
        }

        System.err.println(
            "[monkey] seed=$seed survived $MONKEY_ACTIONS actions; " +
                "worst main-dispatcher round trip ${worstStallMillis}ms; " +
                "reached ${reached.toSortedMap()}",
        )
        if (worstStallMillis > MONKEY_MAX_STALL_MILLIS) {
            fail("the main dispatcher took ${worstStallMillis}ms to answer a heartbeat — the loop stalled")
        }
        // A degenerate run passes every invariant above without having tested
        // anything: if a guard starts refusing early, this is what notices.
        val drags = (reached["dragFromWindow"] ?: 0) + (reached["dragFromPanel"] ?: 0)
        if (drags == 0) fail("no drag ever began — the run exercised none of the gestures")
        if ((reached["windowOpened"] ?: 0) == 0) fail("no host window ever opened")
        if ((reached["windowClosed"] ?: 0) == 0) fail("no host window ever closed")
    }

    // ── applying one action ──────────────────────────────────────────────

    /**
     * The short watchdog: an action is a handful of calls and a 25 ms settle,
     * so anything that does not come back inside [ACTION_BUDGET_MILLIS] has
     * wedged — and saying *which* action did is worth far more than the case's
     * own deadline firing minutes later.
     */
    private suspend fun perform(action: MonkeyAction) {
        try {
            withTimeout(ACTION_BUDGET_MILLIS) { apply(action) }
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException(report("$action never returned (budget ${ACTION_BUDGET_MILLIS}ms)"), timeout)
        }
    }

    private suspend fun apply(action: MonkeyAction) {
        when (action) {
            MonkeyAction.OpenSatellite -> workspace.open(randomSatelliteId())
            MonkeyAction.CloseSatellite -> workspace.close(randomSatelliteId())
            MonkeyAction.Dock -> workspace.dock(randomSatelliteId(), randomSide(), host = randomMember())
            MonkeyAction.Undock -> workspace.undock(randomSatelliteId())
            MonkeyAction.OpenWindow -> if (fixture.openWindow()) reach("windowOpened")
            MonkeyAction.CloseWindow -> if (fixture.closeWindow(random)) reach("windowClosed")
            MonkeyAction.FocusWindow -> randomMember()?.focus()
            MonkeyAction.StartDrag -> startDrag()
            MonkeyAction.MoveDrag -> moveDrag()
            MonkeyAction.EndDrag -> endDrag()
            MonkeyAction.CancelDrag -> cancelDrag()
            MonkeyAction.ToggleMaximize -> randomWindow()?.let { it.setMaximized(!it.isMaximized) }
            MonkeyAction.ResizeRandom -> resizeRandom()
            MonkeyAction.ChangeDpi -> changeDpi()
            MonkeyAction.ToggleVisible -> workspace.visible = !workspace.visible
        }
        scope.settle(STEP_SETTLE_MILLIS)
    }

    private fun startDrag() {
        val entry = workspace.satellites.filter { it.isOpen }.randomOrNull(random) ?: return
        val origin = originOf(entry) ?: return reach("dragWithoutAHost")
        val grab = grabPointOf(entry, origin) ?: return reach("dragWithoutGeometry")
        // `null` when the origin has no geometry yet — a legitimate refusal,
        // and the next MoveDrag simply has nothing to feed.
        drag = workspace.beginDrag(entry.id, origin, grab)
        lastDragPoint = grab
        reach(
            when {
                drag == null -> "dragRefused"
                entry.isDocked -> "dragFromPanel"
                else -> "dragFromWindow"
            },
        )
    }

    private fun moveDrag() {
        val session = drag ?: return
        val point = randomDragPoint()
        session.update(point)
        if (point.x.isFinite() && point.y.isFinite()) lastDragPoint = point
    }

    private fun endDrag() {
        val session = drag ?: return
        drag = null
        reach(if (workspace.dockPreview != null) "dropInAZone" else "dropOutsideEveryZone")
        session.end(lastDragPoint)
    }

    private fun cancelDrag() {
        val session = drag ?: return
        drag = null
        reach("dragCancelled")
        session.cancel()
    }

    private fun resizeRandom() {
        val target = randomWindow() ?: return
        target.setInnerSize(
            MIN_INNER_W_DP + random.nextDouble(INNER_W_SPAN_DP),
            MIN_INNER_H_DP + random.nextDouble(INNER_H_SPAN_DP),
        )
    }

    /**
     * The Kotlin seam of a display hop: the loop reports a new scale with no
     * resize of its own. Inert on the GTK host, which re-derives the live scale
     * from the window — the action still costs nothing there and the other two
     * platforms take it.
     */
    private fun changeDpi() {
        val target = randomWindow() ?: return
        val scale = SCALE_HOPS[random.nextInt(SCALE_HOPS.size)]
        target.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (scale * SCALE_MILLI).roundToInt(), 0)
    }

    // ── what the monkey aims at ──────────────────────────────────────────

    private fun randomSatelliteId(): String = fixture.satelliteIds[random.nextInt(fixture.satelliteIds.size)]

    private fun randomSide(): DockSide = DockSide.entries[random.nextInt(DockSide.entries.size)]

    private fun randomMember(): TaoWindow? = workspace.members.randomOrNull(random)

    /** Any window the monkey may abuse: the members plus the floating palettes. */
    private fun everyWindow(): List<TaoWindow> =
        workspace.members + fixture.satelliteIds.mapNotNull { fixture.floatingWindow(it) }

    private fun randomWindow(): TaoWindow? = everyWindow().randomOrNull(random)

    private fun originOf(entry: SatelliteEntry): SatelliteDragOrigin? =
        if (entry.isDocked) {
            entry.dockHost?.let { SatelliteDragOrigin.DockedPanel(it) }
        } else {
            fixture.floatingWindow(entry.id)?.let { SatelliteDragOrigin.FloatingWindow(it) }
        }

    /** Where the gesture would have been grabbed: the header strip of whichever host holds it. */
    private fun grabPointOf(
        entry: SatelliteEntry,
        origin: SatelliteDragOrigin,
    ): Offset? =
        when (origin) {
            is SatelliteDragOrigin.FloatingWindow -> {
                val outer = origin.window.outerBoundsPx()
                outer?.let {
                    Offset(
                        it[0] + it[RECT_W] / 2f,
                        it[1] + HEADER_GRAB_Y_DP * origin.window.scaleFactor,
                    )
                }
            }
            is SatelliteDragOrigin.DockedPanel -> {
                val client = workspace.dockHostGeometry(origin.host)?.clientOriginPx()
                val panel = entry.dockedBoundsInWindowPx
                if (client == null || panel == null) {
                    null
                } else {
                    client + panel.topLeft + Offset(GRAB_INSET_PX, GRAB_INSET_PX)
                }
            }
        }

    /**
     * A pointer position for the live drag. Half of these are somewhere a user
     * could plausibly aim; the rest are what a synthetic event source, a
     * coalesced flick or a display unplug actually hands over — a point on no
     * screen at all, or one that is not a number.
     */
    private fun randomDragPoint(): Offset {
        val host = randomMember()
        val layout = workspace.dockHostGeometry(host)?.layoutScreenRectPx()
        return when (random.nextInt(DRAG_POINT_KINDS)) {
            0 ->
                layout?.let {
                    when (randomSide()) {
                        DockSide.Left -> Offset(it.left + DROP_INSET_PX, it.center.y)
                        DockSide.Right -> Offset(it.right - DROP_INSET_PX, it.center.y)
                        DockSide.Top -> Offset(it.center.x, it.top + DROP_INSET_PX)
                        DockSide.Bottom -> Offset(it.center.x, it.bottom - DROP_INSET_PX)
                    }
                } ?: farPoint()
            1 -> layout?.center ?: farPoint()
            2 -> farPoint()
            3 ->
                Offset(
                    random.nextFloat() * DESKTOP_SPAN_PX - DESKTOP_SPAN_PX / 2f,
                    random.nextFloat() * DESKTOP_SPAN_PX - DESKTOP_SPAN_PX / 2f,
                )
            else -> Offset(Float.NaN, Float.NaN)
        }
    }

    /** Clear of every dock layout, so a drop there can only mean "tear out". */
    private fun farPoint(): Offset {
        val outer = scope.bounds() ?: return Offset(DROP_FAR_PX, DROP_FAR_PX)
        return Offset(outer[0] + outer[RECT_W] + DROP_FAR_PX, outer[1] + DROP_INSET_PX)
    }

    // ── invariants ───────────────────────────────────────────────────────

    /**
     * The checks that hold at every instant, whatever is in flight. All of
     * them are about the workspace describing something that exists: a member
     * list with no duplicate and no stranger in it, a dock host that is a
     * member, drag feedback only while a drag runs, and no more native windows
     * than the declaration can account for.
     */
    private suspend fun checkStepInvariants() {
        val members = workspace.members
        if (members.distinct().size != members.size) fail("a window is a member twice: $members")
        if (scope.window !in members) fail("the case window is no longer a member of its own workspace")
        workspace.owner?.let { if (it !in members) fail("the owner is not a member") }
        workspace.pinnedOwner?.let { if (it !in members) fail("the pinned owner is not a member") }

        for (entry in workspace.satellites) {
            val host = entry.dockHost
            if (host != null && host !in members) fail("${entry.id} is docked into a window that is not a member")
            if (entry.isDocked && host == null) fail("${entry.id} is docked into nothing")
            val hosts = fixture.composedHostCount(entry.id)
            if (hosts < 0) fail("${entry.id} has a negative host count — a disposal ran twice")
            // A dock hand-off overlaps two hosts for a frame, and a palette
            // moved twice in as many frames can chain them — so more than two
            // is not a failure by itself, a hand-off that never finishes is.
            // Only the excess is paid for: the common case costs one read.
            if (hosts > MAX_COMPOSED_HOSTS) {
                awaitConverges("${entry.id} composes in $hosts hosts and does not come back to one") {
                    fixture.composedHostCount(entry.id) <= 1
                }
            }
        }

        if (workspace.draggedSatellite == null) {
            workspace.dockPreview?.let { fail("a dock zone is previewed with no drag in flight: $it") }
            if (workspace.dragGhost != null) fail("a drag ghost outlived its drag")
        }

        val live = TaoApplication.liveWindowCount()
        if (live > windowCeiling()) fail("$live native windows are alive, more than the ceiling ${windowCeiling()}")
    }

    /**
     * What the declaration can account for at once: the case window, the host
     * windows, one floating window per palette and a drag ghost — plus a small
     * slack, because a window that has just been dropped from composition is
     * still counted until the platform confirms its destroy.
     */
    private fun windowCeiling(): Int =
        1 + MAX_EXTRA_WINDOWS + fixture.satelliteIds.size + GHOST_WINDOWS + TEARDOWN_SLACK

    /**
     * The checks that only hold once the dust of a step has settled, run every
     * [CHECKPOINT_EVERY] actions. A member is removed as its window's
     * `JoinSatelliteWorkspace` is disposed, one frame after the destroy, so
     * "every member is a live window" is a *converging* invariant — asserted
     * instantly it would fail on a window the monkey closed a millisecond ago.
     */
    private suspend fun checkpoint() {
        awaitConverges("every member is a live window") {
            workspace.members.all { TaoApplication.lookup(it.handle) === it }
        }
        awaitConverges("no palette composes in two hosts") {
            fixture.satelliteIds.all { fixture.composedHostCount(it) <= 1 }
        }
        awaitConverges("no more windows than the declaration accounts for") {
            TaoApplication.liveWindowCount() <= 1 + fixture.declaredWindows + fixture.satelliteIds.size
        }
    }

    private suspend fun awaitConverges(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + CONVERGE_MILLIS
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                fail("$description did not hold within ${CONVERGE_MILLIS}ms")
            }
            scope.settle(CONVERGE_POLL_MILLIS)
        }
    }

    // ── reporting ────────────────────────────────────────────────────────

    private fun reach(what: String) {
        reached[what] = (reached[what] ?: 0) + 1
    }

    private fun record(action: MonkeyAction) {
        if (journal.size >= JOURNAL_DEPTH) journal.pollFirst()
        journal.addLast("$step $action")
    }

    private fun fail(reason: String): Nothing = error(report(reason))

    private fun report(reason: String): String =
        buildString {
            appendLine("monkey failed at step $step: $reason")
            appendLine("  seed: $seed (replay with -D$MONKEY_SEED_PROPERTY=$seed)")
            appendLine("  workspace: ${describe()}")
            append(journalReport())
        }

    /** Only the journal, the seed and the step: safe to read from another thread. */
    private fun journalReport(): String =
        buildString {
            appendLine("  monkey seed $seed, at step $step, last ${journal.size} actions:")
            for (entry in journal) appendLine("    $entry")
        }

    private fun describe(): String =
        "members=${workspace.members.size} hostWindows=${fixture.declaredWindows} " +
            "owner=${workspace.owner?.handle?.toString(HEX)}" +
            "/maximized=${workspace.owner?.isMaximized}/fullscreen=${workspace.owner?.isFullscreen} " +
            "live=${TaoApplication.liveWindowCount()} visible=${workspace.visible} " +
            "dragging=${workspace.draggedSatellite?.id} preview=${workspace.dockPreview} " +
            workspace.satellites.joinToString(prefix = "satellites=[", postfix = "]") { entry ->
                val placement = entry.placement
                val where =
                    if (placement is SatellitePlacement.Docked) "docked(${placement.side})" else "floating"
                "${entry.id}:${if (entry.isOpen) "open" else "closed"}/$where" +
                    "/dockHost=${entry.dockHost?.handle?.toString(HEX)}" +
                    "/hiddenByOwner=${entry.windowState.isHiddenByParent}" +
                    "/hosts=${fixture.composedHostsOf(entry.id)}"
            }
}

/** Enough actions to interleave every pair of them, few enough to stay inside a CI budget. */
private const val MONKEY_ACTIONS = 200

/** The whole run plus its quiesce; a starved CI runner needs the headroom. */
private const val MONKEY_CASE_TIMEOUT_MILLIS = 240_000L

/**
 * The short watchdog around a single action. An action is a handful of calls
 * and a settle, so this is orders of magnitude of slack — anything that
 * exceeds it is stuck, not slow.
 */
private const val ACTION_BUDGET_MILLIS = 5_000L

/** Long enough for the loop to deliver a frame, short enough to stay a storm. */
private const val STEP_SETTLE_MILLIS = 25L

private const val CHECKPOINT_EVERY = 25
private const val CONVERGE_MILLIS = 5_000L
private const val CONVERGE_POLL_MILLIS = 50L
private const val JOURNAL_DEPTH = 40

/** Host windows beside the case window. Two is enough for every hand-off to have somewhere to go. */
private const val MAX_EXTRA_WINDOWS = 2

/** A drag publishes at most one ghost window. */
private const val GHOST_WINDOWS = 1

/** Windows dropped from composition are counted until the platform confirms the destroy. */
private const val TEARDOWN_SLACK = 3

/**
 * Two hosts overlap for the frame in which a dock or an undock hands a palette
 * over. Beyond that the hand-off is asked to finish rather than failed outright
 * — a palette moved twice in as many frames can legitimately chain two of them.
 */
private const val MAX_COMPOSED_HOSTS = 2

/** Scale factors a display hop can report. */
private val SCALE_HOPS = floatArrayOf(1f, 1.25f, 1.5f, 2f)

/** [TaoEventCode.SCALE_FACTOR_CHANGED] ships the scale as milli-units. */
private const val SCALE_MILLI = 1000

private const val EXTRA_W_DP = 420
private const val EXTRA_H_DP = 300
private const val EXTRA_X_DP = 660
private const val EXTRA_Y_DP = 130
private const val EXTRA_STEP_DP = 48

private const val MIN_INNER_W_DP = 260.0
private const val INNER_W_SPAN_DP = 420.0
private const val MIN_INNER_H_DP = 200.0
private const val INNER_H_SPAN_DP = 300.0

/** Kinds of pointer position [Monkey.randomDragPoint] draws from. */
private const val DRAG_POINT_KINDS = 5

/** Wider than any desktop this runs on, so a quarter of the samples land on no screen. */
private const val DESKTOP_SPAN_PX = 8_000f

private const val PALETTE_ARGB = 0xFF7A5CD6

/** Window handles read better in hex — that is how every other log prints them. */
private const val HEX = 16
