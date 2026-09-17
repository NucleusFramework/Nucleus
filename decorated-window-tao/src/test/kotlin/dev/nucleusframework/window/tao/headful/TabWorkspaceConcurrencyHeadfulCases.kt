package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TabWindowGroup

/**
 * The tab workspace under load, on real windows: many tabs, many windows, and
 * operations that overlap instead of taking turns.
 *
 *  1. **scale** — a dozen tabs spread over four windows and merged back, with
 *     one body composing per window the whole way;
 *  2. **interleaving** — tabs declared while others are being torn off, so
 *     registration and window creation land in the same frames;
 *  3. **churn** — out and back, over and over, with the saveable state of the
 *     travelling tab checked every round;
 *  4. **overlapping gestures** — several drag sessions alive at once, ended out
 *     of order, and tabs (or every window) closed while they are in flight;
 *  5. **storms and stacks** — the rest of the load story lives in
 *     [TabWorkspaceStormHeadfulCases]: hundreds of selections, reorders and
 *     pointer samples, and windows stacked on the same spot.
 *
 * Native Wayland is skipped along with the rest of the tab suite.
 */
internal object TabWorkspaceConcurrencyHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            manyTabsSpreadOverFourWindowsAndMergedBack(),
            declarationsInterleavedWithTearOffs(),
            churnKeepsStateAndOneBodyPerWindow(),
            severalLiveSessionsOnlyTheLastActs(),
            closingTheDraggedTabMidGestureIsSurvivable(),
            closingEveryTabWhileSessionsAreLive(),
        )

    /**
     * The shape of a real session after an hour's work: a dozen tabs spread
     * over four windows, then merged back into one. Windows must appear and
     * disappear with the tabs and exactly one body must compose per window at
     * every step — a body left behind in a window that lost its tab is a leak
     * the user pays for in memory and in effects that keep running.
     */
    private fun manyTabsSpreadOverFourWindowsAndMergedBack(): TaoWindowTestCase {
        val titles = (1..TAB_CROWD).map { "T$it" }
        val fixture =
            TabWorkspaceFixture(
                initialTitles = titles,
                windowSize = DpSize(CROWD_WINDOW_W_DP.dp, CROWD_WINDOW_H_DP.dp),
            )
        return TaoWindowTestCase(
            name = "tab concurrency a dozen tabs spread over four windows and merge back",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                check(workspace.tabs.size == TAB_CROWD) { "declared ${workspace.tabs.size} of $TAB_CROWD tabs" }

                // Four windows of three tabs each: the first tab of each triple
                // is torn off, the other two follow it.
                val homes = ArrayList<TabWindowGroup>()
                homes += requireNotNull(fixture.groupOf(titles.first()))
                for (start in TABS_PER_WINDOW until TAB_CROWD step TABS_PER_WINDOW) {
                    val lead = fixture.tabId(titles[start])
                    val group =
                        requireNotNull(workspace.tearOff(lead, tearOffRectPx(first), first.scaleFactor)) {
                            "tearing ${titles[start]} off produced no window"
                        }
                    awaitMappedStrip(fixture, group)
                    for (offset in 1 until TABS_PER_WINDOW) {
                        workspace.move(fixture.tabId(titles[start + offset]), group)
                    }
                    awaitUntil("window ${homes.size + 1} holds $TABS_PER_WINDOW tabs") {
                        group.ids.size == TABS_PER_WINDOW
                    }
                    homes += group
                }
                check(workspace.groups.size == WINDOW_CROWD) {
                    "expected $WINDOW_CROWD windows, got ${workspace.groups.size}"
                }
                for (group in homes) awaitMappedStrip(fixture, group)
                awaitUntil("one body per window composes") { fixture.composedBodies.value == WINDOW_CROWD }
                check(workspace.groups.sumOf { it.ids.size } == TAB_CROWD) {
                    "tabs went missing across the spread: ${workspace.groups.map { it.ids.size }}"
                }

                // And everything back into the first window, in order.
                val home = homes.first()
                for (title in titles.drop(TABS_PER_WINDOW)) {
                    workspace.move(fixture.tabId(title), home)
                }
                awaitUntil("one window holds every tab") {
                    workspace.groups.size == 1 && home.ids.size == TAB_CROWD
                }
                awaitUntil("only that window's body composes") { fixture.composedBodies.value == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(home.ids.toSet() == titles.map(fixture::tabId).toSet()) {
                    "the merge lost or duplicated a tab: ${home.ids}"
                }
                check(requireNotNull(first.outerBoundsPx())[2] > 0) { "the surviving window was destroyed" }
            },
        )
    }

    /**
     * Registration and window creation landing in the same frames: the app
     * opens tabs while the user is pulling others out. Both paths write the
     * same group list, and a new tab must join the window that is active *now*
     * rather than one being dismantled.
     */
    private fun declarationsInterleavedWithTearOffs(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab concurrency declarations interleaved with tear-offs lose no tabs",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace

                repeat(INTERLEAVE_ROUNDS) { round ->
                    // A new tab is declared in the same frame as a tear-off of
                    // the tab declared last round.
                    val fresh = "New$round"
                    fixture.titles += fresh
                    val previous = if (round == 0) "Beta" else "New${round - 1}"
                    val id = fixture.tabId(previous)
                    val from = fixture.groupOf(previous)?.window ?: first
                    workspace.tearOff(id, tearOffRectPx(from), from.scaleFactor)
                    awaitUntil("round $round: the fresh tab was registered") {
                        workspace.tab(fixture.tabId(fresh)) != null
                    }
                }

                val expected = 2 + INTERLEAVE_ROUNDS
                awaitUntil("every declared tab is in a group") {
                    workspace.tabs.size == expected && workspace.tabs.all { it.group != null }
                }
                awaitUntil("every group has a mapped window") {
                    workspace.groups.all { it.window?.hasRealFramePx() == true }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.sumOf { it.ids.size } == expected) {
                    "tabs went missing: ${workspace.groups.map { it.ids }}"
                }
                check(workspace.groups.none { it.ids.isEmpty() }) { "an empty group survived" }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    /**
     * Out and back, over and over. Every round the travelling tab's saveable
     * state has to come along, and the body count has to match the window
     * count — the two things a leak shows up in.
     */
    private fun churnKeepsStateAndOneBodyPerWindow(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab concurrency churning a tab out and back keeps its state and one body per window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                workspace.select(beta)
                awaitUntil("Beta is composed") { fixture.windowOf("Beta") != null }
                requireNotNull(fixture.counters.value[beta]).value = TAB_SAVED_CLICKS

                repeat(CHURN_ROUNDS) { round ->
                    val torn =
                        requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor)) {
                            "round $round: tear-off produced no window"
                        }
                    awaitUntil("round $round: Beta composed in its own window") {
                        val window = torn.window
                        window != null && fixture.windowOf("Beta") === window && window !== first
                    }
                    awaitUntil("round $round: two bodies compose") { fixture.composedBodies.value == 2 }
                    check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                        "round $round: state lost on the way out"
                    }

                    workspace.move(beta, requireNotNull(fixture.groupOf("Alpha")), index = round % 2)
                    awaitUntil("round $round: Beta is back") {
                        workspace.groups.size == 1 && fixture.windowOf("Beta") === first
                    }
                    awaitUntil("round $round: one body composes") { fixture.composedBodies.value == 1 }
                    check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                        "round $round: state lost on the way back"
                    }
                }
                check(workspace.tabs.size == 3) { "the churn lost a tab: ${workspace.tabs.map { it.id }}" }
                check(requireNotNull(fixture.groupOf("Beta")).ids.size == 3) {
                    "the strip ended up with ${fixture.groupOf("Beta")?.ids}"
                }
            },
        )
    }

    /**
     * Several sessions alive at once — a synthetic replay, a stuck gesture, a
     * second pointer — and ended out of order. Exactly one may act: the one
     * the workspace is publishing. Everything else has to be inert, including
     * when it is ended *after* the live one.
     */
    private fun severalLiveSessionsOnlyTheLastActs(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab concurrency several live drag sessions leave only the last one acting",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val away = requireNotNull(fixture.farFromStripPx(group))

                val sessions =
                    titles.mapNotNull { title ->
                        val grab = fixture.tabCenterPx(title) ?: return@mapNotNull null
                        val session =
                            workspace.beginDrag(fixture.tabId(title), stripOrigin(first), grab)
                                ?: return@mapNotNull null
                        session.update(grab)
                        title to session
                    }
                check(sessions.size >= 2) { "not enough sessions to supersede: ${sessions.size}" }
                val (liveTitle, live) = sessions.last()
                check(workspace.draggedTab?.id == fixture.tabId(liveTitle)) {
                    "the last session must be the published one, got ${workspace.draggedTab?.id}"
                }

                // Every superseded session, driven and ended: all inert.
                for ((title, session) in sessions.dropLast(1)) {
                    session.update(away)
                    session.end(away)
                    check(workspace.groups.size == 1) { "superseded session ($title) moved a tab" }
                    check(workspace.draggedTab?.id == fixture.tabId(liveTitle)) {
                        "superseded session ($title) took the live one down"
                    }
                }

                live.update(away)
                live.end(away)
                awaitUntil("only the live session tore its tab off") {
                    workspace.groups.size == 2 &&
                        fixture.groupOf(liveTitle)?.ids == listOf(fixture.tabId(liveTitle))
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.tabs.size == titles.size) { "a tab was lost: ${workspace.tabs.map { it.id }}" }
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "drag feedback outlived the sessions"
                }
                // Ending a superseded session again changes nothing.
                for ((_, session) in sessions.dropLast(1)) session.end(away)
                settle()
                check(workspace.groups.size == 2) { "a second end resurrected a gesture" }
            },
        )
    }

    /**
     * The tab under the pointer, closed mid-gesture — by a shortcut, by the
     * app, by another window. The release must not move a tab that no longer
     * exists, nor bring it back.
     */
    private fun closingTheDraggedTabMidGestureIsSurvivable(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab concurrency closing the dragged tab mid-gesture is survivable",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val group = requireNotNull(fixture.groupOf("Beta"))
                val strip = requireNotNull(fixture.stripRectPx(group))
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val away = requireNotNull(fixture.farFromStripPx(group))

                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)
                session.update(away)
                check(workspace.dragGhost != null) { "the tear-out must be previewed" }

                workspace.close(beta)
                awaitUntil("the dragged tab is gone") { workspace.tab(beta) == null }
                // Samples keep arriving after the tab went — the pointer does
                // not know anything happened.
                session.update(Offset(strip.center.x, strip.center.y))
                session.update(away)
                session.end(away)
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.tab(beta) == null) { "the closed tab came back" }
                check(workspace.groups.size == 1) { "the release opened a window: ${workspace.groups.size}" }
                check(workspace.tabs.size == 2) { "tabs went missing: ${workspace.tabs.map { it.id }}" }
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "drag feedback outlived the closed tab"
                }
                awaitUntil("one body composes") { fixture.composedBodies.value == 1 }
                // And the workspace still works.
                val alpha = fixture.tabId("Alpha")
                val nextGrab = requireNotNull(fixture.tabCenterPx("Alpha"))
                val next = requireNotNull(workspace.beginDrag(alpha, stripOrigin(first), nextGrab))
                next.update(nextGrab)
                next.update(away)
                next.end(away)
                awaitUntil("a later drag still works") { workspace.groups.size == 2 }
            },
        )
    }

    /**
     * Everything closed while three gestures are in flight — the shape of an
     * app quitting under the user's hands. The workspace has to end up empty,
     * with no window, no body and no feedback, and report the last window
     * exactly once.
     */
    private fun closingEveryTabWhileSessionsAreLive(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab concurrency closing every tab while gestures are live empties cleanly",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val away = requireNotNull(fixture.farFromStripPx(group))

                val sessions =
                    titles.mapNotNull { title ->
                        val grab = fixture.tabCenterPx(title) ?: return@mapNotNull null
                        val session =
                            workspace.beginDrag(fixture.tabId(title), stripOrigin(first), grab)
                                ?: return@mapNotNull null
                        session.update(grab)
                        session.update(away)
                        session
                    }

                workspace.tabs.map { it.id }.forEach(workspace::close)
                awaitUntil("the workspace emptied") { workspace.groups.isEmpty() && workspace.tabs.isEmpty() }
                for (session in sessions) {
                    session.update(away)
                    session.end(away)
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.groups.isEmpty()) { "a release resurrected a window: ${workspace.groups.size}" }
                check(workspace.tabs.isEmpty()) { "a release resurrected a tab: ${workspace.tabs.map { it.id }}" }
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "drag feedback outlived the workspace"
                }
                awaitUntil("no body is composing") { fixture.composedBodies.value == 0 }
                awaitUntil("the last window was reported once") { fixture.lastWindowClosedCount.value == 1 }
                settle()
                check(fixture.lastWindowClosedCount.value == 1) {
                    "reported ${fixture.lastWindowClosedCount.value}× for one emptying"
                }
            },
        )
    }

    private const val TAB_CROWD = 12
    private const val TABS_PER_WINDOW = 3
    private const val WINDOW_CROWD = TAB_CROWD / TABS_PER_WINDOW
    private const val CROWD_WINDOW_W_DP = 1100
    private const val CROWD_WINDOW_H_DP = 360
    private const val INTERLEAVE_ROUNDS = 5
    private const val CHURN_ROUNDS = 6
    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
