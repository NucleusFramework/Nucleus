package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DefaultDockSideOrder
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventCode
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The dock-layout monkeys: random layout mutations on one `DockLayout`, one
 * case per (layout profile, seed).
 *
 * Where [SatelliteWorkspaceMonkeyHeadfulCases] shakes the *workspace* — hosts
 * coming and going, drags across windows — these shake the *layout*: layered
 * and split sides, per-panel extents and weights, splitters dragged with a real
 * mouse, side orders shuffled, sides flipping between layered and split, the
 * direction flipping between LTR and RTL, and snapshots restored on top of
 * whatever the previous steps left. Each profile is a layout an app would
 * actually declare — the reader layout of a right-to-left book app among them —
 * and each is run under several seeds, because the interleavings are the point.
 *
 * What a run asserts, after every action and at checkpoints:
 *
 *  - **geometry**: no two visible panels overlap, none overlaps the content,
 *    and every one is inside the layout — whatever the extents, weights, order
 *    and direction happen to be;
 *  - **identity**: a panel body is built once per *hosting change* (docked to
 *    floating, closed to open, hidden to shown) and never by a change of the
 *    layout alone — a splitter, a reorder, a side change, a restore, a new
 *    side order or direction must move a subtree, not rebuild it. The content
 *    is never rebuilt at all;
 *  - **composition**: no panel composes in two hosts once a step has settled;
 *  - **liveness**: `Dispatchers.Main` keeps answering ([MainLoopWatchdog]),
 *    no action wedges, and native windows do not accumulate;
 *  - **convergence**: the closing phase docks everything back into one
 *    layered configuration and it has to lay out cleanly.
 *
 * Every failure carries the profile, the seed and the last actions;
 * `-Dnucleus.tao.headful.monkeySeed=<seed>` replays the action sequence and
 * `-Dnucleus.tao.headful.monkeyScript=A,B,C` replays a journal verbatim.
 */
internal object DockLayoutMonkeyHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        PROFILES.flatMap { profile ->
            SEEDS.map { seed -> randomLayoutChangesLeaveACleanLayout(profile, seed, MONKEY_ACTIONS) }
        } + randomLayoutChangesLeaveACleanLayout(PROFILES[READER_PROFILE], LONG_RUN_SEED, LONG_RUN_ACTIONS)

    private fun randomLayoutChangesLeaveACleanLayout(
        profile: LayoutProfile,
        seed: Long,
        actions: Int,
    ): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs = profile.specs,
                sideOrder = profile.sideOrder,
                layeredSides = profile.layeredSides,
                direction = profile.direction,
            )
        return TaoWindowTestCase(
            name = "dock layout monkey ${profile.name} seed $seed: $actions random layout changes leave a clean layout",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitUntil("the case window is mapped") { bounds() != null }
                awaitUntil("the layout published its geometry") {
                    fixture.workspace.dockHostGeometry(window)?.layoutScreenRectPx() != null
                }
                awaitUntil("every satellite is declared") {
                    profile.specs.all {
                        fixture.workspace.satellite(it.id) !=
                            null
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val monkey = DockMonkey(this, fixture, profile, monkeySeedOr(seed), actions)
                monkey.run()
                monkey.quiesceAndAssert()
            },
        )
    }

    /** The seed property overrides every case's own seed, so a red one replays. */
    private fun monkeySeedOr(default: Long): Long = System.getProperty(MONKEY_SEED_PROPERTY)?.toLongOrNull() ?: default

    private val SEEDS = longArrayOf(20_260_907L, 42L, 7L)
    private const val READER_PROFILE = 1
    private const val LONG_RUN_SEED = 1_000_003L
}

/** A layout an app would declare, with the satellites that start in it. */
private class LayoutProfile(
    val name: String,
    val sideOrder: List<DockSide>,
    val layeredSides: Set<DockSide>,
    val direction: LayoutDirection,
    val specs: List<DockPanelSpec>,
)

private val FLOATING =
    SatellitePlacement.Floating(positioner = workspaceRightEdgePositioner(), size = workspaceSatelliteSize())

private val PROFILES =
    listOf(
        LayoutProfile(
            name = "border",
            sideOrder = DefaultDockSideOrder,
            layeredSides = emptySet(),
            direction = LayoutDirection.Ltr,
            specs =
                listOf(
                    DockPanelSpec("tree", SatellitePlacement.Docked(DockSide.Left)),
                    DockPanelSpec("toc", SatellitePlacement.Docked(DockSide.Left, order = 1)),
                    DockPanelSpec("notes", SatellitePlacement.Docked(DockSide.Bottom)),
                    DockPanelSpec("targum", FLOATING),
                    DockPanelSpec("comments", FLOATING),
                ),
        ),
        // The reader: a right-to-left book app with its navigation layered on the
        // right, the translation on the left and the commentaries under both.
        LayoutProfile(
            name = "reader",
            sideOrder = listOf(DockSide.Right, DockSide.Bottom, DockSide.Left, DockSide.Top),
            layeredSides = setOf(DockSide.Right),
            direction = LayoutDirection.Rtl,
            specs =
                listOf(
                    DockPanelSpec("tree", SatellitePlacement.Docked(DockSide.Right, order = 0, extent = 90.dp)),
                    DockPanelSpec("toc", SatellitePlacement.Docked(DockSide.Right, order = 1, extent = 80.dp)),
                    DockPanelSpec("notes", SatellitePlacement.Docked(DockSide.Right, order = 2, extent = 80.dp)),
                    DockPanelSpec("targum", SatellitePlacement.Docked(DockSide.Left, extent = 90.dp)),
                    DockPanelSpec("comments", SatellitePlacement.Docked(DockSide.Bottom, extent = 80.dp)),
                ),
        ),
        LayoutProfile(
            name = "all layered",
            sideOrder = listOf(DockSide.Left, DockSide.Right, DockSide.Top, DockSide.Bottom),
            layeredSides = DockSide.entries.toSet(),
            direction = LayoutDirection.Ltr,
            specs =
                listOf(
                    DockPanelSpec("tree", SatellitePlacement.Docked(DockSide.Left, extent = 90.dp)),
                    DockPanelSpec("toc", SatellitePlacement.Docked(DockSide.Top, extent = 80.dp)),
                    DockPanelSpec("notes", SatellitePlacement.Docked(DockSide.Right, extent = 90.dp)),
                    DockPanelSpec("targum", SatellitePlacement.Docked(DockSide.Bottom, extent = 80.dp)),
                    DockPanelSpec("comments", FLOATING),
                ),
        ),
        LayoutProfile(
            name = "rows rtl",
            sideOrder = listOf(DockSide.Top, DockSide.Bottom, DockSide.Right, DockSide.Left),
            layeredSides = setOf(DockSide.Top, DockSide.Bottom),
            direction = LayoutDirection.Rtl,
            specs =
                listOf(
                    DockPanelSpec("tree", SatellitePlacement.Docked(DockSide.Top, extent = 80.dp)),
                    DockPanelSpec("toc", SatellitePlacement.Docked(DockSide.Top, order = 1, extent = 80.dp)),
                    DockPanelSpec("notes", SatellitePlacement.Docked(DockSide.Right, weight = 2f)),
                    DockPanelSpec("targum", SatellitePlacement.Docked(DockSide.Right, order = 1)),
                    DockPanelSpec("comments", SatellitePlacement.Docked(DockSide.Bottom, extent = 80.dp)),
                ),
        ),
    )

/** One atomic layout change the monkey can make. Drawn uniformly. */
private enum class DockAction {
    /** Docks a satellite on a random side, at a random or appended order. */
    Dock,

    /** Lifts a docked satellite into a floating window. */
    Undock,

    /** Shows a closed satellite. */
    Open,

    /** Hides a satellite, keeping its placement. */
    Close,

    /** Sets a layered panel's own extent to a random value, tiny to huge. */
    SetExtent,

    /** Sets a split panel's weight to a random value, including a degenerate one. */
    SetWeight,

    /** Drags a random splitter with the real mouse, a random distance along its axis. */
    DragSplitter,

    /** Records the current layout for a later restore. */
    Snapshot,

    /** Restores a recorded layout — or the current one — on top of what is there. */
    Restore,

    /** Shuffles the side order. */
    ShuffleSides,

    /** Flips one side between layered and split. */
    ToggleLayered,

    /** Flips the layout direction. */
    FlipDirection,

    /** Resizes the window to a random inner size. */
    Resize,

    /** Flips the workspace-wide visibility sweep. */
    ToggleVisible,

    /** Injects a scale-factor change. */
    ChangeDpi,
}

/** How a satellite is hosted at a given instant, the thing whose changes justify a rebuild. */
private enum class Hosting { Docked, Floating, None }

private class DockMonkey(
    private val scope: TaoWindowTestScope,
    private val fixture: DockLayoutFixture,
    private val profile: LayoutProfile,
    seed: Long,
    private val actions: Int,
) {
    private val random = Random(seed)
    private val journal = MonkeyJournal("dock-monkey[${profile.name}]", seed)
    private val script = monkeyScript()
    private val workspace get() = fixture.workspace
    private val ids = profile.specs.map { it.id }
    private val snapshots = ArrayList<SatelliteLayoutSnapshot>()
    private var worstStallMillis = 0L

    /** Hosting changes seen per satellite: the only thing that may rebuild a body. */
    private val hostingChanges = HashMap<String, Int>()
    private var lastHosting: Map<String, Hosting> = emptyMap()

    suspend fun run() {
        System.err.println("[dock-monkey] profile=${profile.name} seed=${journal.seed} actions=$actions")
        lastHosting = currentHosting()
        val watchdog = MainLoopWatchdog("dock-monkey", journal::report).start()
        try {
            while (journal.step < actions) {
                val action = nextAction() ?: break
                journal.record(action)
                monkeyAction({ journal.failure("$action never returned", describe()) }) { apply(action) }
                scope.settle(STEP_SETTLE_MILLIS)
                noteHosting()
                checkStepInvariants()
                if ((journal.step + 1) % CHECKPOINT_EVERY == 0) checkpoint()
                journal.step++
            }
        } finally {
            worstStallMillis = watchdog.stop()
        }
    }

    private fun nextAction(): DockAction? {
        val scripted = script ?: return DockAction.entries[random.nextInt(DockAction.entries.size)]
        val name = scripted.getOrNull(journal.step) ?: return null
        return DockAction.valueOf(name)
    }

    /**
     * Docks everything back into the profile's own layout and requires a clean
     * result: one body per panel, no overlap, no leftover window.
     */
    suspend fun quiesceAndAssert() {
        workspace.visible = true
        scope.window.dispatch(
            TaoEventCode.SCALE_FACTOR_CHANGED,
            (scope.window.scaleFactor * SCALE_MILLI).roundToInt(),
            0,
        )
        scope.window.setInnerSize(PARENT_W_DP.toDouble(), PARENT_H_DP.toDouble())
        fixture.sideOrder.value = profile.sideOrder
        fixture.layeredSides.value = profile.layeredSides
        fixture.direction.value = profile.direction
        for ((index, id) in ids.withIndex()) {
            workspace.open(id)
            workspace.dock(id, DockSide.entries[index % DockSide.entries.size], order = index)
            workspace.setDockedExtent(id, QUIESCE_EXTENT_DP.dp)
            workspace.setDockedWeight(id, 1f)
        }
        scope.settle(SETTLE_AFTER_MAP_MILLIS)

        awaitConverges("every panel is docked with exactly one live body") {
            ids.all { fixture.liveBodiesOf(it) == 1 && fixture.bodyBounds.value[it] != null }
        }
        awaitConverges("the docked layout is clean") { geometryProblem() == null }
        awaitConverges("no floating window is left") { fixture.floatingWindows.value.isEmpty() }
        awaitConverges("the run leaked no window") { TaoApplication.liveWindowCount() <= 1 + TEARDOWN_SLACK }
        check(fixture.contentIncarnations.value == 1) { journal.failure("the content was rebuilt", describe()) }

        System.err.println(
            "[dock-monkey] profile=${profile.name} seed=${journal.seed} survived $actions actions; " +
                "worst main-dispatcher round trip ${worstStallMillis}ms; reached ${journal.reachedSummary()}",
        )
        check(worstStallMillis <= MONKEY_MAX_STALL_MILLIS) {
            journal.failure("the main dispatcher took ${worstStallMillis}ms to answer a heartbeat", describe())
        }
        if (script == null) {
            check(journal.reachedCount("splitterDragged") + journal.reachedCount("splitterSet") > 0) {
                journal.failure("no splitter was ever moved", describe())
            }
            check(journal.reachedCount("restored") > 0) { journal.failure("no snapshot was ever restored", describe()) }
        }
    }

    // ── applying one action ──────────────────────────────────────────────

    private suspend fun apply(action: DockAction) {
        when (action) {
            DockAction.Dock -> {
                val order = if (random.nextBoolean()) null else random.nextInt(MAX_ORDER)
                workspace.dock(randomId(), randomSide(), order = order)
            }
            DockAction.Undock -> workspace.undock(randomId())
            DockAction.Open -> workspace.open(randomId())
            DockAction.Close -> workspace.close(randomId())
            DockAction.SetExtent -> {
                workspace.setDockedExtent(randomId(), (random.nextFloat() * EXTENT_SPAN_DP).dp)
                journal.reach("splitterSet")
            }
            DockAction.SetWeight -> workspace.setDockedWeight(randomId(), random.nextFloat() * WEIGHT_SPAN - 1f)
            DockAction.DragSplitter -> dragSplitter()
            DockAction.Snapshot -> {
                snapshots += workspace.snapshot()
                if (snapshots.size > MAX_SNAPSHOTS) snapshots.removeAt(0)
            }
            DockAction.Restore -> {
                val snapshot = snapshots.randomOrNull(random) ?: workspace.snapshot()
                workspace.restore(snapshot)
                journal.reach("restored")
            }
            DockAction.ShuffleSides,
            DockAction.ToggleLayered,
            DockAction.FlipDirection,
            DockAction.Resize,
            DockAction.ToggleVisible,
            DockAction.ChangeDpi,
            -> applyToTheLayout(action)
        }
    }

    /** The actions that change the layout's shape or its window rather than a satellite. */
    private fun applyToTheLayout(action: DockAction) {
        when (action) {
            DockAction.ShuffleSides -> fixture.sideOrder.value = DockSide.entries.shuffled(random)
            DockAction.ToggleLayered -> {
                val side = randomSide()
                val current = fixture.layeredSides.value
                fixture.layeredSides.value = if (side in current) current - side else current + side
            }
            DockAction.FlipDirection ->
                fixture.direction.value =
                    if (fixture.direction.value == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
            DockAction.Resize ->
                scope.window.setInnerSize(
                    MIN_INNER_W_DP + random.nextDouble(INNER_W_SPAN_DP),
                    MIN_INNER_H_DP + random.nextDouble(INNER_H_SPAN_DP),
                )
            DockAction.ToggleVisible -> workspace.visible = !workspace.visible
            DockAction.ChangeDpi -> {
                val scale = SCALE_HOPS[random.nextInt(SCALE_HOPS.size)]
                scope.window.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (scale * SCALE_MILLI).roundToInt(), 0)
            }
            else -> error("not a layout action: $action")
        }
    }

    /**
     * A real mouse drag on a random splitter: a press on its grip and a move
     * along its axis, a flick or a deliberate drag. Falls back to the
     * workspace call when the host cannot inject input.
     */
    private suspend fun dragSplitter() {
        val (key, grip) =
            fixture.splitterBounds.value.entries
                .randomOrNull(random)
                ?: return journal.reach("noSplitter")
        if (grip.width <= 0f || grip.height <= 0f) return journal.reach("emptySplitter")
        val horizontal = grip.height > grip.width
        val deltaPx = (random.nextFloat() * 2f - 1f) * DRAG_SPAN_PX
        val delta = if (horizontal) Offset(deltaPx, 0f) else Offset(0f, deltaPx)
        val client =
            workspace.dockHostGeometry(scope.window)?.clientOriginPx() ?: return journal.reach("noClientOrigin")
        val from = client + grip.center
        val steps = if (random.nextBoolean()) FLICK_STEPS else ROBOT_DRAG_STEPS
        val pressed =
            robotPressAndDrag(from, from + delta, scope.window.scaleFactor, steps = steps, stepDelayMillis = 0L)
        if (pressed == null) {
            // Same change, no mouse: the panel the splitter would have moved.
            val id = key.removePrefix("panel:")
            if (key.startsWith("panel:")) workspace.setDockedExtent(id, (random.nextFloat() * EXTENT_SPAN_DP).dp)
            journal.reach("splitterSet")
            return
        }
        robotRelease()
        journal.reach("splitterDragged")
    }

    // ── invariants ───────────────────────────────────────────────────────

    private fun currentHosting(): Map<String, Hosting> =
        ids.associateWith { id ->
            val entry = workspace.satellite(id)
            when {
                entry == null || !entry.isOpen || !workspace.visible -> Hosting.None
                entry.isDocked -> Hosting.Docked
                else -> Hosting.Floating
            }
        }

    private fun noteHosting() {
        val now = currentHosting()
        for (id in ids) {
            if (now[id] != lastHosting[id]) hostingChanges[id] = (hostingChanges[id] ?: 0) + 1
        }
        lastHosting = now
    }

    /** Holds at every instant, whatever is in flight. */
    private fun checkStepInvariants() {
        for (id in ids) {
            val live = fixture.liveBodiesOf(id)
            check(live in 0..MAX_LIVE_BODIES) { journal.failure("$id has $live live bodies", describe()) }
            // One build for the first hosting plus one per hosting change; a
            // layout change on its own is never one of them.
            val allowed = 1 + (hostingChanges[id] ?: 0) + REBUILD_SLACK
            check(fixture.incarnationsOf(id) <= allowed) {
                journal.failure(
                    "$id was built ${fixture.incarnationsOf(id)} times for ${hostingChanges[id] ?: 0} hosting changes",
                    describe(),
                )
            }
        }
        check(fixture.contentIncarnations.value == 1) { journal.failure("the content was rebuilt", describe()) }
        val live = TaoApplication.liveWindowCount()
        check(live <= 1 + ids.size + TEARDOWN_SLACK) { journal.failure("$live native windows are alive", describe()) }
    }

    /** Holds once the dust of a step has settled. */
    private suspend fun checkpoint() {
        awaitConverges("every open satellite has exactly one body") {
            ids.all { id ->
                val entry = workspace.satellite(id)
                val expected = if (entry != null && entry.isOpen && workspace.visible) 1 else 0
                fixture.liveBodiesOf(id) == expected
            }
        }
        awaitConverges("the layout is clean: ${geometryProblem()}") { geometryProblem() == null }
    }

    /**
     * What is wrong with the visible geometry, or `null`: a panel outside the
     * layout, two panels overlapping, or one overlapping the content. Panels
     * whose bounds have not been published yet are not judged.
     */
    private fun geometryProblem(): String? {
        val layout = workspace.dockHostGeometry(scope.window)?.layoutBoundsInWindowPx ?: return "no layout geometry"
        val visible =
            ids.filter { id ->
                val entry = workspace.satellite(id)
                entry != null && entry.isOpen && workspace.visible && entry.isDocked
            }
        val rects = visible.mapNotNull { id -> fixture.panelBounds.value[id]?.let { id to it } }
        val outer = layout.inflate(LAYOUT_TOLERANCE_PX)
        for ((id, rect) in rects) {
            if (rect.left < outer.left ||
                rect.top < outer.top ||
                rect.right > outer.right ||
                rect.bottom > outer.bottom
            ) {
                return "$id at $rect is outside the layout $layout"
            }
        }
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                if (overlaps(rects[i].second, rects[j].second)) {
                    return "${rects[i].first} ${rects[i].second} overlaps ${rects[j].first} ${rects[j].second}"
                }
            }
        }
        val content = fixture.contentBounds.value
        if (content != null && content.width > 0f && content.height > 0f) {
            for ((id, rect) in rects) {
                if (overlaps(rect, content)) return "$id $rect overlaps the content $content"
            }
        }
        return null
    }

    private suspend fun awaitConverges(
        description: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + CONVERGE_MILLIS
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) {
                journal.failure("$description did not hold within ${CONVERGE_MILLIS}ms", describe())
            }
            scope.settle(CONVERGE_POLL_MILLIS)
        }
    }

    private fun randomId(): String = ids[random.nextInt(ids.size)]

    private fun randomSide(): DockSide = DockSide.entries[random.nextInt(DockSide.entries.size)]

    private fun describe(): String =
        "profile=${profile.name} sides=${fixture.sideOrder.value} layered=${fixture.layeredSides.value} " +
            "direction=${fixture.direction.value} visible=${workspace.visible} " +
            "live=${TaoApplication.liveWindowCount()} content=${fixture.contentBounds.value} " +
            workspace.satellites.joinToString(prefix = "satellites=[", postfix = "]") { entry ->
                val placement = entry.placement
                val where =
                    if (placement is SatellitePlacement.Docked) {
                        "docked(${placement.side}#${placement.order} " +
                            "extent=${placement.extent} weight=${placement.weight})"
                    } else {
                        "floating"
                    }
                "${entry.id}:${if (entry.isOpen) "open" else "closed"}/$where" +
                    "/bounds=${fixture.panelBounds.value[entry.id]?.let(::short)}" +
                    "/bodies=${fixture.liveBodiesOf(entry.id)}/built=${fixture.incarnationsOf(entry.id)}"
            }

    private fun short(rect: Rect): String =
        "(${rect.left.roundToInt()},${rect.top.roundToInt()} ${rect.width.roundToInt()}x${rect.height.roundToInt()})"
}

/** Enough to interleave every pair of actions a few times, short enough to run a dozen profiles. */
private const val MONKEY_ACTIONS = 120

/** The reader profile once more, for longer: the layout SeforimApp would declare. */
private const val LONG_RUN_ACTIONS = 400

private const val MONKEY_CASE_TIMEOUT_MILLIS = 300_000L
private const val STEP_SETTLE_MILLIS = 25L
private const val CHECKPOINT_EVERY = 10
private const val CONVERGE_MILLIS = 5_000L
private const val CONVERGE_POLL_MILLIS = 50L

/** Two bodies overlap for the frame in which a dock or an undock hands a panel over. */
private const val MAX_LIVE_BODIES = 2

/**
 * A hosting change is counted after the step settled; a panel that went
 * docked → floating → docked inside one restore shows as no change and two
 * builds. One step of slack absorbs that without hiding a layout rebuild,
 * which happens on every splitter drag and would run away at once.
 */
private const val REBUILD_SLACK = 2

/** Windows dropped from composition are counted until the platform confirms the destroy. */
private const val TEARDOWN_SLACK = 3

private const val MAX_ORDER = 6
private const val MAX_SNAPSHOTS = 6
private const val EXTENT_SPAN_DP = 500f
private const val WEIGHT_SPAN = 6f
private const val DRAG_SPAN_PX = 240f
private const val QUIESCE_EXTENT_DP = 70f

private const val MIN_INNER_W_DP = 300.0
private const val INNER_W_SPAN_DP = 400.0
private const val MIN_INNER_H_DP = 220.0
private const val INNER_H_SPAN_DP = 300.0

private val SCALE_HOPS = floatArrayOf(1f, 1.25f, 1.5f, 2f)
private const val SCALE_MILLI = 1000
