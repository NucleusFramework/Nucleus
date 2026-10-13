package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The workspaces asked to do several things at the same instant, from several
 * places at once.
 *
 * Every member of a workspace is documented as belonging to the Tao event-loop
 * thread, which is also the Compose dispatcher — so the interesting failures
 * are not data races on fields but *ordering* races between things that each
 * look atomic: a background thread posting work while a gesture runs, two
 * coroutines mutating the same group in one frame, a restore landing between a
 * tear-off and its window being mapped, a close arriving while a drop is being
 * resolved.
 *
 * The invariant behind all of them is the same and is asserted every time: when
 * the dust settles, no tab is in two groups or none, no group is empty, one
 * body composes per window, and nothing is left publishing drag feedback.
 */
internal object WorkspaceRaceHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            workAndPostedFromBackgroundThreadsAllLands(),
            twoCoroutinesMutatingTheSameGroupInOneFrame(),
            aRestoreLandingBetweenATearOffAndItsWindow(),
            everyWindowAskedToCloseAtTheSameInstant(),
            aDropResolvedWhileTheTargetGroupIsBeingEmptied(),
            visibilityTogglesRacingDockChanges(),
            pinChurnWhileTheOwnerCloses(),
            fileDropsArrivingThroughoutAWorkspaceStorm(),
            declarationsAndClosuresInterleavedFromCoroutines(),
            aGestureStartedInOneFrameAndEndedManyLater(),
        )

    /**
     * Work posted from real background threads. The workspace is the event
     * loop's, so an application thread has to hand its change over — and a
     * hundred of them arriving at once must all land, in some order, with none
     * lost and none applied twice.
     */
    private fun workAndPostedFromBackgroundThreadsAllLands(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "workspace race work posted from background threads all lands",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val applied = AtomicInteger()
                val start = CountDownLatch(1)
                val done = CountDownLatch(POSTER_THREADS)

                repeat(POSTER_THREADS) { index ->
                    thread(isDaemon = true, name = "workspace-poster-$index") {
                        start.await()
                        repeat(POSTS_PER_THREAD) { round ->
                            val title = titles[(index + round) % titles.size]
                            // The only correct way in: hand it to the loop.
                            kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                                workspace.select(fixture.tabId(title))
                                applied.incrementAndGet()
                            }
                        }
                        done.countDown()
                    }
                }
                start.countDown()
                awaitUntil("every posted change landed") {
                    done.await(0, TimeUnit.MILLISECONDS) ||
                        applied.get() == POSTER_THREADS * POSTS_PER_THREAD
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(applied.get() == POSTER_THREADS * POSTS_PER_THREAD) {
                    "only ${applied.get()} of ${POSTER_THREADS * POSTS_PER_THREAD} changes landed"
                }
                assertCoherent(fixture, titles.size)
            },
        )
    }

    /**
     * Two coroutines writing the same group inside one frame: one reorders
     * while the other moves a tab out. Both are legitimate, and the group has
     * to end up describing itself either way.
     */
    private fun twoCoroutinesMutatingTheSameGroupInOneFrame(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "workspace race two coroutines mutating one group in the same frame",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val home = requireNotNull(fixture.groupOf("Alpha"))

                coroutineScope {
                    val reorders =
                        launch {
                            repeat(RACE_ROUNDS) { round ->
                                workspace.reorder(fixture.tabId(titles[round % titles.size]), round % titles.size)
                                if (round % YIELD_EVERY == 0) delay(1)
                            }
                        }
                    val moves =
                        launch {
                            repeat(RACE_ROUNDS / 4) { round ->
                                val title = titles[1 + round % (titles.size - 1)]
                                val id = fixture.tabId(title)
                                val from = fixture.groupOf(title)?.window ?: first
                                workspace.tearOff(id, tearOffRectPx(from), from.scaleFactor)
                                delay(1)
                                workspace.move(id, home)
                                delay(1)
                            }
                        }
                    reorders.join()
                    moves.join()
                }
                awaitUntil("everything is back in one window") { workspace.groups.size == 1 }
                awaitUntil("the strip republished its slots in order") {
                    val slots = home.slotsInWindowPx
                    slots.size >= home.ids.size &&
                        slots.take(home.ids.size).zipWithNext().all { (l, r) -> l.left <= r.left }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                assertCoherent(fixture, titles.size)
            },
        )
    }

    /**
     * A saved layout applied in the window between a tear-off and the window it
     * asked for being mapped. The group exists, its window does not yet, and
     * the restore has an opinion about both.
     */
    private fun aRestoreLandingBetweenATearOffAndItsWindow(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "workspace race a restore landing between a tear-off and its window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val snapshot = workspace.snapshot()

                repeat(RESTORE_ROUNDS) { round ->
                    val title = titles[1 + round % (titles.size - 1)]
                    // No await in between: the restore lands while the window
                    // the tear-off asked for is still being created.
                    workspace.tearOff(fixture.tabId(title), tearOffRectPx(first), first.scaleFactor)
                    workspace.restore(snapshot)
                }
                awaitUntil("the layout is back to one window") { workspace.groups.size == 1 }
                awaitUntil("its window is mapped") {
                    (
                        workspace.groups
                            .first()
                            .window
                            ?.outerBoundsPx()
                            ?.get(RECT_W) ?: 0L
                    ) > 0L
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                assertCoherent(fixture, titles.size)
                check(
                    workspace.groups
                        .first()
                        .ids
                        .toSet() == titles.map(fixture::tabId).toSet(),
                ) {
                    "the restore lost a tab: ${workspace.groups.first().ids}"
                }
            },
        )
    }

    /**
     * Every window asked to close in the same instant — the shape of a quit.
     * Each close empties its own group, and the group list is being rewritten
     * by all of them at once.
     */
    private fun everyWindowAskedToCloseAtTheSameInstant(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "workspace race every window asked to close at the same instant",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                for (title in titles.drop(1)) {
                    val from = fixture.groupOf(title)?.window ?: first
                    val group =
                        workspace.tearOff(fixture.tabId(title), tearOffRectPx(from), from.scaleFactor) ?: continue
                    awaitMappedStrip(fixture, group)
                }
                check(workspace.groups.size == titles.size) { "expected one window per tab" }

                // Every window's own close request, in one pass.
                val windows = workspace.groups.mapNotNull { it.window }
                for (w in windows) w.requestUserClose()

                awaitUntil("the workspace emptied") { workspace.groups.isEmpty() && workspace.tabs.isEmpty() }
                awaitUntil("nothing is composing") { fixture.composedBodies.value == 0 }
                awaitUntil("the last window was reported once") { fixture.lastWindowClosedCount.value == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.lastWindowClosedCount.value == 1) {
                    "reported ${fixture.lastWindowClosedCount.value}× for one shutdown"
                }
                check(workspace.draggedTab == null && workspace.dragGhost == null) {
                    "drag feedback outlived the shutdown"
                }
            },
        )
    }

    /**
     * A drop being resolved into a group that the application is emptying in
     * the same frame. The release has to act on the world it finds, not the one
     * it was aimed at, and must not resurrect the group it was heading for.
     */
    private fun aDropResolvedWhileTheTargetGroupIsBeingEmptied(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "workspace race a drop resolved while its target group is emptied",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val gamma = fixture.tabId("Gamma")
                val target =
                    requireNotNull(workspace.tearOff(gamma, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, target)
                settle(SETTLE_AFTER_MAP_MILLIS)

                val beta = fixture.tabId("Beta")
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val onTarget = requireNotNull(fixture.stripRectPx(target)).center
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)
                session.update(onTarget)
                check(workspace.dropPreview?.group === target) { "the target strip did not preview the drop" }

                // The target's only tab is closed in the same frame the drop
                // is released onto it.
                workspace.close(gamma)
                session.end(onTarget)
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.tab(gamma) == null) { "the drop resurrected the closed tab" }
                check(workspace.groups.none { it.ids.isEmpty() }) { "an empty group survived the drop" }
                check(workspace.tab(beta)?.group != null) { "Beta ended up in no group at all" }
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "drag feedback outlived the race"
                }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    /**
     * The workspace-wide visibility switch flipped while satellites are being
     * docked and undocked. Each flip destroys or builds every floating window,
     * and each dock change decides where a satellite lives — in the same frames.
     */
    private fun visibilityTogglesRacingDockChanges(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace race visibility toggles racing dock changes",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                awaitFloating(fixture)
                val workspace = fixture.workspace
                requireNotNull(fixture.counter.value).value = SAVED_CLICKS

                repeat(TOGGLE_ROUNDS) { round ->
                    workspace.visible = false
                    workspace.dock(SATELLITE_ID, if (round % 2 == 0) DockSide.Left else DockSide.Right)
                    workspace.visible = true
                    settle(RACE_SETTLE_MILLIS)
                    workspace.undock(SATELLITE_ID)
                    settle(RACE_SETTLE_MILLIS)
                }
                workspace.visible = true
                awaitUntil("the satellite is composed again") { fixture.isComposed }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(fixture.composedHosts.value == 1) {
                    "${fixture.composedHosts.value} hosts composing after the race"
                }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "the satellite lost its state in the race"
                }
                check(workspace.draggedSatellite == null && workspace.dockPreview == null) {
                    "drag feedback appeared out of a visibility race"
                }
            },
        )
    }

    /**
     * The pinned owner changed repeatedly while the window it points at is
     * closing. A pin that outlives its window would leave every floating
     * satellite anchored to a frame that no longer exists.
     */
    private fun pinChurnWhileTheOwnerCloses(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val dialogVisible = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "workspace race pin churn while the pinned owner closes",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = {
                JoinSatelliteWorkspace(fixture.workspace)
                DockLayout(fixture.workspace, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF3C8D5A)))
                }
            },
            dialogVisible = dialogVisible,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                awaitFloating(fixture)
                val workspace = fixture.workspace
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { workspace.members.size == 2 }

                repeat(PIN_ROUNDS) { round ->
                    workspace.pinTo(if (round % 2 == 0) dialog else window)
                    settle(RACE_SETTLE_MILLIS)
                }
                workspace.pinTo(dialog)
                awaitUntil("the dialog owns the satellites") { workspace.owner === dialog }

                var destroyed = false
                dialog.onDestroyed { destroyed = true }
                dialogVisible.value = false
                awaitUntil("the pinned owner went") { destroyed }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.pinnedOwner == null) { "the pin outlived the window it named" }
                check(workspace.owner === window) { "the owner did not fall back to the survivor" }
                check(workspace.members == listOf(window)) { "the closed window is still a member" }
                awaitFloating(fixture)
            },
        )
    }

    /**
     * Files arriving from outside the application throughout a workspace storm.
     * The two paths share the window and nothing else, so what this pins down
     * is that neither can leave the other in a state it cannot recover from.
     */
    private fun fileDropsArrivingThroughoutAWorkspaceStorm(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles, fileDropTargets = true)
        return TaoWindowTestCase(
            name = "workspace race file drops arriving throughout a workspace storm",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                var delivered = 0

                repeat(DROP_STORM_ROUNDS) { round ->
                    workspace.select(fixture.tabId(titles[round % titles.size]))
                    val selected = requireNotNull(workspace.selectedTab(requireNotNull(fixture.groupOf("Alpha"))))
                    settle(RACE_SETTLE_MILLIS)
                    val host = fixture.windowOf(selected.title) ?: first
                    val point = contentPointPx(host, HALF, DEEP)
                    if (host.fileDragAndDrop(point, listOf("/nucleus/storm/$round.txt"))) delivered++
                    workspace.reorder(fixture.tabId(titles[round % titles.size]), round % titles.size)
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(delivered >= DROP_STORM_ROUNDS / 2) {
                    "only $delivered of $DROP_STORM_ROUNDS drops were taken during the storm"
                }
                val taken = titles.sumOf { fixture.dropLog(it).drops.value }
                check(taken == delivered) { "$delivered drops were accepted but $taken were recorded" }
                check(titles.none { fixture.dropLog(it).failure.value != null }) {
                    "a drop failed to read its payload: " +
                        "${titles.mapNotNull { fixture.dropLog(it).failure.value }}"
                }
                assertCoherent(fixture, titles.size)
            },
        )
    }

    /**
     * Tabs declared and closed from coroutines that interleave. Registration
     * places a tab in the active window and a close can drop that very window,
     * so the two racing is how a tab ends up in a group that is already gone.
     */
    private fun declarationsAndClosuresInterleavedFromCoroutines(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha"))
        return TaoWindowTestCase(
            name = "workspace race declarations and closures interleaved from coroutines",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabSlots(fixture, "Alpha")
                val workspace = fixture.workspace

                coroutineScope {
                    val opening =
                        async {
                            repeat(OPEN_ROUNDS) { round ->
                                fixture.titles += "New$round"
                                delay(RACE_SETTLE_MILLIS)
                            }
                        }
                    val closing =
                        async {
                            repeat(OPEN_ROUNDS) { round ->
                                delay(RACE_SETTLE_MILLIS * 2)
                                val victim = "New${round / 2}"
                                if (workspace.tab(fixture.tabId(victim)) != null) {
                                    workspace.close(fixture.tabId(victim))
                                    fixture.titles -= victim
                                }
                            }
                        }
                    listOf(opening, closing).awaitAll()
                }
                awaitUntil("every declared tab found a group") {
                    workspace.tabs.all { it.group != null }
                }
                awaitUntil("every group has a mapped window") {
                    workspace.groups.all { (it.window?.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.none { it.ids.isEmpty() }) { "an empty group survived" }
                check(workspace.tabs.isNotEmpty()) { "the race closed everything" }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    /**
     * A gesture held across everything else: started, then left running while
     * tabs are closed, declared, reordered and torn off around it, and only
     * then released. The session has to act on the world as it is at the
     * release — or do nothing at all — but never on the one it started in.
     */
    private fun aGestureStartedInOneFrameAndEndedManyLater(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "workspace race a gesture started in one frame and ended many frames later",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val alpha = fixture.tabId("Alpha")
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val grab = requireNotNull(fixture.tabCenterPx("Alpha"))
                val away = requireNotNull(fixture.farFromStripPx(group))

                val session = requireNotNull(workspace.beginDrag(alpha, stripOrigin(first), grab))
                session.update(grab)
                session.update(away)

                // The world moves on around the held gesture.
                withContext(Dispatchers.Main) {
                    workspace.close(fixture.tabId("Delta"))
                    fixture.titles -= "Delta"
                    fixture.titles += "Epsilon"
                }
                awaitUntil("the new tab was declared") { workspace.tab(fixture.tabId("Epsilon")) != null }
                val torn =
                    workspace.tearOff(fixture.tabId("Gamma"), tearOffRectPx(first), first.scaleFactor)
                if (torn != null) awaitMappedStrip(fixture, torn)
                workspace.reorder(fixture.tabId("Beta"), 0)
                settle(SETTLE_AFTER_MAP_MILLIS)

                // Only now is it released, far from every strip.
                session.update(away)
                session.end(away)
                awaitUntil("the held gesture landed") { workspace.draggedTab == null }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.tab(fixture.tabId("Delta")) == null) { "the release resurrected a closed tab" }
                check(workspace.tab(alpha)?.group != null) { "the dragged tab ended up in no group" }
                check(workspace.groups.none { it.ids.isEmpty() }) { "an empty group survived" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) {
                    "drag feedback outlived the gesture"
                }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    /**
     * The invariant every case in this file ends on: the workspace still
     * describes a possible world — every tab in exactly one group, no empty
     * group, one body per window, and no drag feedback left over.
     */
    private fun assertCoherent(
        fixture: TabWorkspaceFixture,
        expectedTabs: Int,
    ) {
        val workspace = fixture.workspace
        check(workspace.tabs.size == expectedTabs) {
            "expected $expectedTabs tabs, got ${workspace.tabs.map { it.id }}"
        }
        val placed = workspace.groups.flatMap { it.ids }
        check(placed.size == placed.toSet().size) { "a tab is in two groups: $placed" }
        check(placed.toSet() == workspace.tabs.map { it.id }.toSet()) {
            "the groups hold $placed but the workspace knows ${workspace.tabs.map { it.id }}"
        }
        check(workspace.groups.none { it.ids.isEmpty() }) { "an empty group survived" }
        check(workspace.groups.all { it.selectedId in it.ids }) {
            "a group selects a tab it does not hold: ${workspace.groups.map { it.id to it.selectedId }}"
        }
        check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
            "drag feedback outlived the race"
        }
    }

    private const val POSTER_THREADS = 4
    private const val POSTS_PER_THREAD = 25
    private const val RACE_ROUNDS = 60
    private const val YIELD_EVERY = 8
    private const val RESTORE_ROUNDS = 5
    private const val TOGGLE_ROUNDS = 4
    private const val PIN_ROUNDS = 6
    private const val OPEN_ROUNDS = 6
    private const val DROP_STORM_ROUNDS = 8
    private const val RACE_SETTLE_MILLIS = 16L
    private const val HALF = 0.5f
    private const val DEEP = 0.8f
    private const val LONG_CASE_TIMEOUT_MILLIS = 120_000L
}
