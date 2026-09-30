package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TaoWindow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * The whole archetype under sustained load: several document windows open at
 * once, each hosting its own palettes, each animating, while tabs are switched,
 * torn off and merged as fast as the loop will take it.
 *
 * One event loop drives every window, so load is where the archetype's costs
 * become visible: a window that stops being scheduled, a palette whose follow
 * falls behind the window it belongs to, an anchoring that never catches up
 * because the parent moves again before it lands. None of that shows in a case
 * that drives one window at a time.
 *
 * What is asserted is **fairness and convergence**, never an absolute frame
 * rate: CI runners paint through software GL, and a hard fps threshold there
 * measures the runner. Every window has to keep getting frames while the
 * others do, and every gesture has to converge once the storm stops.
 */
internal object WorkspaceLoadHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            everyWindowKeepsGettingFramesWhileTheOthersAnimate(),
            palettesKeepUpWithABurstOfOwnerMoves(),
            aTabStormAcrossFourAnimatingWindowsConverges(),
            tearOffAndMergeUnderAnimationLoadLoseNoTabs(),
            aPaletteDockedAndUndockedRepeatedlyUnderLoad(),
            everyWindowStillPaintsAfterHalfOfThemClose(),
            aSelectionStormWhilePalettesAnimateKeepsOneBodyPerWindow(),
            anchoringConvergesWhenTheOwnerNeverStopsMoving(),
        )

    /**
     * Four windows, all animating. The loop is shared, so the question is
     * whether it is shared *fairly*: every window has to keep painting while
     * the others do. A window that stops being scheduled looks alive — its
     * state is right, its size is right — and is frozen on screen.
     */
    private fun everyWindowKeepsGettingFramesWhileTheOthersAnimate(): TaoWindowTestCase {
        val titles = (1..WINDOW_CROWD).map { "W$it" }
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load every window keeps getting frames while the others animate",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val groups = fixture.spread(this, first, titles.drop(1))
                check(groups.size + 1 == WINDOW_CROWD) { "expected $WINDOW_CROWD windows" }
                awaitUntil("every window is animating", detail = { fixture.frameReport() }) {
                    fixture.workspace.groups.all { fixture.frames(it.id) > MIN_FRAMES }
                }

                val before = fixture.workspace.groups.associate { it.id to fixture.frames(it.id) }
                settle(FRAME_WINDOW_MILLIS)
                val after = fixture.workspace.groups.associate { it.id to fixture.frames(it.id) }
                val painted = after.mapValues { (id, n) -> n - (before[id] ?: 0L) }
                check(painted.values.all { it >= MIN_FRAMES }) {
                    "a window was starved over ${FRAME_WINDOW_MILLIS}ms: $painted"
                }
                // Fairness, not a rate: the busiest window may get several
                // times the frames of the quietest, but not all of them.
                val most = painted.values.max()
                val least = painted.values.min()
                check(least * STARVATION_RATIO >= most) {
                    "one window got $most frames while another got $least"
                }
            },
        )
    }

    /**
     * The owner window dragged in a burst while its palette follows. Every
     * move is a native command for the satellite, and a follow that queues them
     * instead of converging leaves the palette trailing across the desktop
     * after the drag ends.
     */
    private fun palettesKeepUpWithABurstOfOwnerMoves(): TaoWindowTestCase {
        val titles = listOf("W1", "W2")
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load palettes keep up with a burst of owner moves",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val group = requireNotNull(fixture.workspace.groups.first())
                val palette = fixture.awaitPalette(this, group)
                awaitUntil("the palette captured its offset") {
                    fixture
                        .satellites(group.id)
                        .satellite(fixture.paletteId(group.id))
                        ?.windowState
                        ?.offsetFromParent != null
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val ownerStart = requireNotNull(first.outerBoundsPx())
                val paletteStart = requireNotNull(palette.outerBoundsPx())
                val offsetX = paletteStart[0] - ownerStart[0]
                val offsetY = paletteStart[1] - ownerStart[1]
                val scale = first.scaleFactor.toDouble()

                // A drag's worth of moves, faster than the platform answers.
                repeat(MOVE_BURST) { round ->
                    val delta = (round % MOVE_SPAN) * MOVE_STEP_DP
                    first.setOuterPosition(ownerStart[0] / scale + delta, ownerStart[1] / scale + delta)
                }
                first.setOuterPosition(ownerStart[0] / scale, ownerStart[1] / scale)

                awaitUntil("the palette converged back onto its offset") {
                    val owner = first.outerBoundsPx() ?: return@awaitUntil false
                    val follower = palette.outerBoundsPx() ?: return@awaitUntil false
                    abs((follower[0] - owner[0]) - offsetX) <= FOLLOW_SLOP_PX &&
                        abs((follower[1] - owner[1]) - offsetY) <= FOLLOW_SLOP_PX
                }
                check(requireNotNull(palette.outerBoundsPx())[RECT_W] > 0L) {
                    "the palette lost its size in the burst"
                }
            },
        )
    }

    /**
     * Selections and reorders fired across four animating windows at once.
     * Every window is repainting while its strip is rewritten, which is the
     * frame where a stale slot list turns into a drop landing in the wrong
     * place. Afterwards every strip has to describe itself again.
     */
    private fun aTabStormAcrossFourAnimatingWindowsConverges(): TaoWindowTestCase {
        val titles = (1..TAB_CROWD).map { "W$it" }
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load a tab storm across four animating windows converges",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val leads = titles.filterIndexed { index, _ -> index % TABS_PER_WINDOW == 0 }.drop(1)
                val homes = fixture.spread(this, first, leads)
                for ((index, title) in titles.withIndex()) {
                    val home = homes.getOrNull(index / TABS_PER_WINDOW - 1) ?: continue
                    if (index % TABS_PER_WINDOW != 0) fixture.workspace.move(fixture.archetype.tabId(title), home)
                }
                awaitUntil("the tabs are spread") {
                    fixture.workspace.groups.size == WINDOW_CROWD &&
                        fixture.workspace.groups.sumOf { it.ids.size } == TAB_CROWD
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                repeat(STORM_ROUNDS) { round ->
                    val title = titles[round % titles.size]
                    fixture.workspace.select(fixture.archetype.tabId(title))
                    fixture.workspace.reorder(fixture.archetype.tabId(title), round % TABS_PER_WINDOW)
                }

                awaitUntil(
                    "every strip republished a slot per tab, in order",
                    detail = { fixture.stripReport() },
                ) {
                    fixture.workspace.groups.all { group ->
                        val slots = group.slotsInWindowPx
                        slots.size >= group.ids.size &&
                            slots.take(group.ids.size).zipWithNext().all { (l, r) -> l.left <= r.left }
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.groups.sumOf { it.ids.size } == TAB_CROWD) {
                    "the storm lost a tab: ${fixture.workspace.groups.map { it.ids }}"
                }
                awaitUntil("one body per window composes") {
                    fixture.archetype.composedBodies.value == fixture.workspace.groups.size
                }
                // And the windows are still painting.
                val before = fixture.workspace.groups.associate { it.id to fixture.frames(it.id) }
                settle(FRAME_WINDOW_MILLIS)
                check(
                    fixture.workspace.groups.all {
                        fixture.frames(it.id) - (before[it.id] ?: 0L) >= MIN_FRAMES
                    },
                ) { "a window stopped painting after the storm" }
            },
        )
    }

    /**
     * Tear-offs and merges while every window animates. Windows are created and
     * destroyed under a running frame clock, which is where a scene outlives
     * the window it belonged to.
     */
    private fun tearOffAndMergeUnderAnimationLoadLoseNoTabs(): TaoWindowTestCase {
        val titles = listOf("W1", "W2", "W3")
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load tear-offs and merges under animation load lose no tabs",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val workspace = fixture.workspace
                val home = requireNotNull(fixture.archetype.groupOf("W1"))

                repeat(CHURN_ROUNDS) { round ->
                    val title = titles[1 + round % (titles.size - 1)]
                    val id = fixture.archetype.tabId(title)
                    val from = fixture.archetype.groupOf(title)?.window ?: first
                    val torn = workspace.tearOff(id, tearOffRectPx(from), from.scaleFactor)
                    if (torn != null) {
                        awaitUntil("round $round: $title is in a window of its own") {
                            fixture.archetype.groupOf(title)?.ids == listOf(id)
                        }
                        awaitUntil("round $round: that window is mapped") {
                            (torn.window?.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L
                        }
                    }
                    workspace.move(id, home)
                    awaitUntil("round $round: $title is back home") { fixture.archetype.groupOf(title) === home }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.groups.size == 1) { "the churn left ${workspace.groups.size} windows" }
                check(home.ids.size == titles.size) { "the churn lost a tab: ${home.ids}" }
                awaitUntil("one body composes") { fixture.archetype.composedBodies.value == 1 }
                val before = fixture.frames(home.id)
                settle(FRAME_WINDOW_MILLIS)
                check(fixture.frames(home.id) - before >= MIN_FRAMES) {
                    "the surviving window stopped painting after the churn"
                }
            },
        )
    }

    /**
     * Docking and undocking a palette over and over while its window animates.
     * Each round destroys a native window and builds a panel, or the reverse,
     * under a live frame clock — and the palette's own state has to ride
     * through every one of them.
     */
    private fun aPaletteDockedAndUndockedRepeatedlyUnderLoad(): TaoWindowTestCase {
        val titles = listOf("W1", "W2")
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load a palette docked and undocked repeatedly under animation load",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val group = requireNotNull(fixture.workspace.groups.first())
                val host = requireNotNull(group.window)
                fixture.awaitPalette(this, group)
                val workspace = fixture.satellites(group.id)
                val id = fixture.paletteId(group.id)
                requireNotNull(fixture.paletteCounters[group.id]).value = SAVED_CLICKS

                repeat(DOCK_ROUNDS) { round ->
                    workspace.dock(id, if (round % 2 == 0) DockSide.Right else DockSide.Bottom)
                    awaitUntil("round $round: docked") { fixture.panelHosts[group.id] === host }
                    check(requireNotNull(fixture.paletteCounters[group.id]).value == SAVED_CLICKS) {
                        "round $round: the palette lost its state docking"
                    }
                    workspace.undock(id)
                    fixture.awaitPalette(this, group)
                    check(requireNotNull(fixture.paletteCounters[group.id]).value == SAVED_CLICKS) {
                        "round $round: the palette lost its state undocking"
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.composedPalettes() == 1) {
                    "${fixture.composedPalettes()} palette bodies after the churn"
                }
                val before = fixture.frames(group.id)
                settle(FRAME_WINDOW_MILLIS)
                check(fixture.frames(group.id) - before >= MIN_FRAMES) {
                    "the host window stopped painting after the dock churn"
                }
            },
        )
    }

    /**
     * Half the windows closed while every one of them is animating. The loop
     * keeps running, and the survivors must keep being scheduled — a frame
     * clock left holding a destroyed window's scene stops the whole loop, not
     * just that window.
     */
    private fun everyWindowStillPaintsAfterHalfOfThemClose(): TaoWindowTestCase {
        val titles = (1..WINDOW_CROWD).map { "W$it" }
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load the survivors still paint after half the windows close",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                fixture.spread(this, first, titles.drop(1))
                awaitUntil("every window is animating", detail = { fixture.frameReport() }) {
                    fixture.workspace.groups.all { fixture.frames(it.id) > MIN_FRAMES }
                }

                val doomed = titles.filterIndexed { index, _ -> index % 2 == 1 }
                for (title in doomed) fixture.workspace.close(fixture.archetype.tabId(title))
                awaitUntil("the closed windows went") {
                    fixture.workspace.groups.size == WINDOW_CROWD - doomed.size
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val before = fixture.workspace.groups.associate { it.id to fixture.frames(it.id) }
                settle(FRAME_WINDOW_MILLIS)
                val painted =
                    fixture.workspace.groups.associate { it.id to fixture.frames(it.id) - (before[it.id] ?: 0L) }
                check(painted.values.all { it >= MIN_FRAMES }) {
                    "a survivor stopped painting after the others closed: $painted"
                }
                check(fixture.workspace.groups.all { (it.window?.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L }) {
                    "a survivor lost its frame"
                }
            },
        )
    }

    /**
     * Selection changed hundreds of times across windows whose palettes are all
     * animating. Every change swaps a body in and out under a running clock,
     * which is where a body is left composing in a window that has moved on.
     */
    private fun aSelectionStormWhilePalettesAnimateKeepsOneBodyPerWindow(): TaoWindowTestCase {
        val titles = (1..TAB_CROWD).map { "W$it" }
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load a selection storm while palettes animate keeps one body per window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val group = requireNotNull(fixture.workspace.groups.first())
                fixture.awaitPalette(this, group)
                check(first.outerBoundsPx() != null)

                repeat(STORM_ROUNDS) { round ->
                    fixture.workspace.select(fixture.archetype.tabId(titles[round % titles.size]))
                }
                val last = titles[(STORM_ROUNDS - 1) % titles.size]
                awaitUntil("the storm settled on $last") {
                    group.selectedId == fixture.archetype.tabId(last)
                }
                awaitUntil("one body composes") { fixture.archetype.composedBodies.value == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.composedPalettes() == 1) {
                    "${fixture.composedPalettes()} palette bodies after the storm"
                }
                val before = fixture.frames(group.id)
                settle(FRAME_WINDOW_MILLIS)
                check(fixture.frames(group.id) - before >= MIN_FRAMES) {
                    "the window stopped painting after the selection storm"
                }
            },
        )
    }

    /**
     * The owner moved again before its palette has finished being placed —
     * over and over. The anchoring is a command the platform answers
     * asynchronously, so this is the case where it can chase its own tail and
     * never settle. It has to converge the moment the moves stop.
     */
    private fun anchoringConvergesWhenTheOwnerNeverStopsMoving(): TaoWindowTestCase {
        val titles = listOf("W1")
        val fixture = LoadFixture(titles)
        return TaoWindowTestCase(
            name = "workspace load anchoring converges when the owner never stops moving",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val host = awaitTabSatellites(fixture.archetype, *titles.toTypedArray())
                val group = requireNotNull(fixture.workspace.groups.first())
                val palette = fixture.awaitPalette(this, group)
                val state =
                    requireNotNull(fixture.satellites(group.id).satellite(fixture.paletteId(group.id))).windowState
                awaitUntil("the offset was captured") { state.offsetFromParent != null }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val start = requireNotNull(host.outerBoundsPx())
                val scale = host.scaleFactor.toDouble()
                // Re-anchor requested in the middle of a move burst, repeatedly.
                repeat(ANCHOR_ROUNDS) { round ->
                    host.setOuterPosition(
                        start[0] / scale + (round % MOVE_SPAN) * MOVE_STEP_DP,
                        start[1] / scale,
                    )
                    state.reanchor()
                }
                host.setOuterPosition(start[0] / scale, start[1] / scale)
                state.reanchor()

                awaitUntil("the palette settled off the owner's right edge") {
                    val owner = host.outerBoundsPx() ?: return@awaitUntil false
                    val follower = palette.outerBoundsPx() ?: return@awaitUntil false
                    follower[0] >= owner[0] + owner[RECT_W] - ANCHOR_SLOP_PX
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val owner = requireNotNull(host.outerBoundsPx())
                val follower = requireNotNull(palette.outerBoundsPx())
                check(follower[RECT_W] > 0L && follower[RECT_H] > 0L) { "the palette lost its size" }
                check(follower[0] >= owner[0]) { "the palette ended up left of its owner" }
            },
        )
    }

    // ── the fixture ──────────────────────────────────────────────────────

    /**
     * Tab windows that animate, each with a palette of its own.
     *
     * Built on [TabSatellitesFixture] — the same wiring the composed archetype
     * uses — with a frame-clock driver per window so a case can tell which
     * windows are actually being painted.
     */
    private class LoadFixture(
        titles: List<String>,
    ) {
        val archetype: TabSatellitesFixture =
            TabSatellitesFixture(
                initialTitles = titles,
                windowSize = DpSize(LOAD_WINDOW_W_DP.dp, LOAD_WINDOW_H_DP.dp),
                bodyExtra = { group -> WindowAnimation(group.id) },
            )

        /** The tab workspace the windows come from. */
        val workspace: dev.nucleusframework.window.tao.TabWorkspace get() = archetype.tabs

        private val frameCounts = ConcurrentHashMap<String, AtomicLong>()

        /** Frames the window of [groupId] has painted since it opened. */
        fun frames(groupId: String): Long = frameCounts[groupId]?.get() ?: 0L

        /** Frames per group, so a starved window names itself in a failure. */
        fun frameReport(): String =
            workspace.groups.joinToString { "${it.id}=${frames(it.id)}" } +
                " | counted=" + frameCounts.entries.joinToString { "${it.key}=${it.value.get()}" }

        /** Tabs and published slots per group, for a strip that never converges. */
        fun stripReport(): String =
            workspace.groups.joinToString { group ->
                "${group.id}: tabs=${group.ids.size} slots=${group.slotsInWindowPx.map { it.left.toInt() }}"
            }

        fun satellites(groupId: String) = archetype.palettesOf(groupId)

        fun paletteId(groupId: String) = archetype.paletteId(groupId)

        val panelHosts: Map<String, TaoWindow> get() = archetype.panelHost.value

        val paletteCounters get() = archetype.paletteCounters.value

        fun composedPalettes(): Int = archetype.composedPalettes.value

        @Composable
        fun dev.nucleusframework.window.tao.ApplicationScope.Windows() {
            with(archetype) { Windows() }
        }

        /** Tears each of [titles] into a window of its own and waits for it. */
        suspend fun spread(
            scope: TaoWindowTestScope,
            source: TaoWindow,
            titles: List<String>,
        ): List<TabWindowGroup> {
            val groups = ArrayList<TabWindowGroup>(titles.size)
            for (title in titles) {
                val from = archetype.groupOf(title)?.window ?: source
                val group =
                    workspace.tearOff(archetype.tabId(title), tearOffRectPx(from), from.scaleFactor) ?: continue
                scope.awaitUntil("the window for $title is mapped with a strip") {
                    (group.window?.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L &&
                        group.slotsInWindowPx.size >= group.ids.size
                }
                groups += group
            }
            scope.settle(SETTLE_AFTER_MAP_MILLIS)
            return groups
        }

        /** Waits for the palette of [group] to be floating with a real size. */
        suspend fun awaitPalette(
            scope: TaoWindowTestScope,
            group: TabWindowGroup,
        ): TaoWindow {
            scope.awaitUntil("the palette of ${group.id} is up") {
                val rect = archetype.floatingPalette.value[group.id]?.outerBoundsPx() ?: return@awaitUntil false
                rect[RECT_W] > 0L && rect[RECT_H] > 0L
            }
            scope.settle(SETTLE_AFTER_MAP_MILLIS)
            return requireNotNull(archetype.floatingPalette.value[group.id])
        }

        /**
         * A frame-clock loop for one window, counted per group so a case can
         * see which windows the shared loop is actually painting. The phase is
         * read in `drawBehind`, which is what keeps the clock ticking.
         */
        @Composable
        fun WindowAnimation(groupId: String) {
            val counter = remember(groupId) { frameCounts.getOrPut(groupId) { AtomicLong() } }
            val phase = remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    @Suppress("UNUSED_EXPRESSION")
                    phase.value
                },
            )
            LaunchedEffect(groupId) {
                while (true) {
                    withFrameNanos {
                        counter.incrementAndGet()
                        phase.value = (phase.value + 1f) % PHASE_WRAP
                    }
                }
            }
        }
    }

    private const val WINDOW_CROWD = 4
    private const val TAB_CROWD = 8
    private const val TABS_PER_WINDOW = 2
    private const val LOAD_WINDOW_W_DP = 420
    private const val LOAD_WINDOW_H_DP = 260
    private const val STORM_ROUNDS = 120
    private const val CHURN_ROUNDS = 4
    private const val DOCK_ROUNDS = 4
    private const val MOVE_BURST = 60
    private const val MOVE_SPAN = 6
    private const val MOVE_STEP_DP = 8.0
    private const val ANCHOR_ROUNDS = 40
    private const val FRAME_WINDOW_MILLIS = 500L
    private const val MIN_FRAMES = 3L
    private const val PHASE_WRAP = 1000f

    /** How much more one window may paint than another before it is starvation. */
    private const val STARVATION_RATIO = 12

    private const val FOLLOW_SLOP_PX = 24L
    private const val ANCHOR_SLOP_PX = 48L
    private const val LONG_CASE_TIMEOUT_MILLIS = 150_000L
}
