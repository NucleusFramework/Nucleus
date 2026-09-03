package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

/**
 * Real-window coverage for the tab workspace: one [dev.nucleusframework.window.tao.DecoratedWindow]
 * per group, tabs moving between them, and the windows appearing and
 * disappearing with the tabs.
 *
 *  1. the whole lifecycle — two tabs in one window, one torn off into a second
 *     window with a real mouse, merged back by dropping it on the first strip,
 *     then closed until the last window goes and `onLastWindowClosed` fires;
 *  2. `rememberSaveable` state and scroll position survive every move, while a
 *     reorder inside one window rebuilds nothing;
 *  3. a snapshot restores the windows it described, tabs declared afterwards
 *     included;
 *  4. selection: closing the selected tab picks a neighbour, in real windows.
 *
 * The edge cases — abrupt pointer jumps, a backing-scale change, minimize,
 * maximize, interrupted gestures — live in [TabWorkspaceStressHeadfulCases].
 *
 * Native Wayland is skipped: without client-side window positioning neither
 * the tear-off placement nor the window drag is observable.
 */
internal object TabWorkspaceHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            tearOffMergeAndCloseLifecycle(),
            stateSurvivesMovesAndReordersDoNotRebuild(),
            snapshotRestoresWindows(),
            closingTheSelectedTabPicksANeighbour(),
        )

    /**
     * The gesture an app is judged on: pull a tab out into its own window with
     * a real mouse, push it back into the other window's strip, then close
     * everything and watch the windows go with the tabs.
     */
    private fun tearOffMergeAndCloseLifecycle(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "tab workspace tears a tab into its own window, merges it back and closes out",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                check(workspace.groups.size == 1) { "two tabs must open one window, got ${workspace.groups.size}" }
                // The workspace is empty on the composition that declares the
                // tabs, which must not read as "every window is gone" — an app
                // wiring this to exitApplication would never open at all.
                check(!fixture.lastWindowClosed.value) { "onLastWindowClosed fired before a window ever opened" }
                requireNotNull(fixture.counters.value[fixture.tabId("Beta")]).value = TAB_SAVED_CLICKS
                settle()

                val robot = tearBetaOff(fixture, first)
                mergeBetaBack(fixture, first, robot)
                closeEverything(fixture)
            },
        )
    }

    /** Pulls "Beta" out of the shared strip into a window of its own. Returns whether a real mouse drove it. */
    private suspend fun TaoWindowTestScope.tearBetaOff(
        fixture: TabWorkspaceFixture,
        first: TaoWindow,
    ): Boolean {
        val workspace = fixture.workspace
        val beta = fixture.tabId("Beta")
        val grab = requireNotNull(fixture.tabCenterPx("Beta")) { "Beta published no slot" }
        val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
        val dropOut = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
        val scale = first.scaleFactor
        first.focus()
        awaitUntil("first window is focused") { first.isFocused }
        val robot = robotPressAndDrag(grab, dropOut, scale) != null
        if (robot) {
            // Button still down: the ghost is the whole affordance, and only
            // while it is held is the drop position certain.
            awaitUntil("the press-drag started a drag of Beta") { workspace.draggedTab?.id == beta }
            // Tracks the pointer within a drag step: the robot's last sample may
            // still be in flight, and pinning the exact pixel would race it.
            awaitUntil("the ghost follows the pointer down to the drop") {
                val ghost = workspace.dragGhost ?: return@awaitUntil false
                ghost.tab.id == beta &&
                    (ghost.screenRectPx.center - dropOut).getDistance() <= GHOST_FOLLOW_TOLERANCE_PX
            }
            checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
        } else {
            System.err.println("[tab-drag] robot unavailable, driving the drag session directly")
            val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
            session.update(grab)
            session.update(dropOut)
            val ghost = requireNotNull(workspace.dragGhost) { "dragging a tab out must show a ghost" }
            check(ghost.screenRectPx.contains(dropOut)) { "the ghost must sit under the pointer" }
            session.end(dropOut)
        }
        awaitUntil("a second window holds Beta on its own") {
            workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
        }
        val torn = requireNotNull(fixture.groupOf("Beta"))
        awaitUntil("the torn-off window is mapped and composing Beta") {
            val window = torn.window ?: return@awaitUntil false
            window !== first && window.hasRealFramePx() && fixture.windowOf("Beta") != null
        }
        settle(SETTLE_AFTER_MAP_MILLIS)
        check(fixture.groupOf("Alpha")?.ids == listOf(fixture.tabId("Alpha"))) {
            "Alpha should be alone in the first window: ${fixture.groupOf("Alpha")?.ids}"
        }
        check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
            "Beta lost its saveable state when torn off"
        }
        check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
        // The new window inherits the size of the one it came from.
        val tornBounds = requireNotNull(requireNotNull(torn.window).outerBoundsPx())
        val expectedWidthPx = TAB_WINDOW_W_DP * first.scaleFactor
        check(abs(tornBounds[2] - expectedWidthPx) <= TAB_SIZE_TOLERANCE_PX) {
            "torn-off window is ${tornBounds[2]}px wide, expected \u2248${expectedWidthPx}px"
        }
        return robot
    }

    /** Drops "Beta" back on the first window's strip, which empties and destroys its own window. */
    private suspend fun TaoWindowTestScope.mergeBetaBack(
        fixture: TabWorkspaceFixture,
        first: TaoWindow,
        robot: Boolean,
    ) {
        val workspace = fixture.workspace
        val beta = fixture.tabId("Beta")
        val tornWindow = requireNotNull(requireNotNull(fixture.groupOf("Beta")).window)
        var tornDestroyed = false
        tornWindow.onDestroyed { tornDestroyed = true }
        val alphaGroup = requireNotNull(fixture.groupOf("Alpha"))
        val alphaStrip = requireNotNull(fixture.stripRectPx(alphaGroup))
        val betaGrab = requireNotNull(fixture.tabCenterPx("Beta"))
        // Past the midpoint of the only tab there, so Beta is appended after it.
        val mergeAt = Offset(alphaStrip.left + alphaStrip.width * MERGE_X_FRACTION, alphaStrip.center.y)
        if (robot) {
            tornWindow.focus()
            awaitUntil("torn window is focused") { tornWindow.isFocused }
            checkNotNull(robotPressAndDrag(betaGrab, mergeAt, first.scaleFactor)) {
                "robot became unavailable mid-case"
            }
            awaitUntil("the first strip previews the insertion") { workspace.dropPreview?.group === alphaGroup }
            checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
        } else {
            val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(tornWindow), betaGrab))
            session.update(betaGrab)
            session.update(mergeAt)
            check(workspace.dropPreview?.group === alphaGroup) {
                "hovering the other strip must preview it: ${workspace.dropPreview}"
            }
            session.end(mergeAt)
        }
        awaitUntil("both tabs are back in one window") {
            workspace.groups.size == 1 && fixture.groupOf("Beta") === alphaGroup
        }
        awaitUntil("the emptied window was destroyed") { tornDestroyed }
        settle()
        check(alphaGroup.ids == listOf(fixture.tabId("Alpha"), beta)) {
            "merged in the wrong order: ${alphaGroup.ids}"
        }
        check(alphaGroup.selectedId == beta) { "the arriving tab must be selected" }
        check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
            "Beta lost its saveable state on the way back"
        }
    }

    /** Closes the tabs one by one: the last one has to take the last window with it. */
    private suspend fun TaoWindowTestScope.closeEverything(fixture: TabWorkspaceFixture) {
        val workspace = fixture.workspace
        val group = requireNotNull(fixture.groupOf("Alpha"))
        var lastDestroyed = false
        requireNotNull(group.window).onDestroyed { lastDestroyed = true }

        workspace.close(fixture.tabId("Beta"))
        awaitUntil("one tab left, still one window") { workspace.tabs.size == 1 && workspace.groups.size == 1 }
        check(!lastDestroyed) { "closing one of two tabs must not close the window" }

        workspace.close(fixture.tabId("Alpha"))
        awaitUntil("the last window was destroyed") { lastDestroyed && workspace.groups.isEmpty() }
        awaitUntil("onLastWindowClosed fired") { fixture.lastWindowClosed.value }
        check(fixture.composedBodies.value == 0) { "a tab body outlived every window" }
    }

    /**
     * The tools an app actually keeps in a tab: a scroll position and a
     * `rememberSaveable` counter. Both must cross every window boundary, and a
     * reorder — which changes nothing about where the body lives — must not
     * rebuild it at all.
     */
    private fun stateSurvivesMovesAndReordersDoNotRebuild(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace keeps saveable state across windows and rebuilds nothing on a reorder",
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
                settle()
                val incarnationsBefore =
                    requireNotNull(fixture.bodyIncarnations.value[beta]) {
                        "no body incarnation recorded for Beta: ${fixture.bodyIncarnations.value} " +
                            "composedIn=${fixture.composedIn.value.keys} bodies=${fixture.composedBodies.value}"
                    }

                // ── a reorder inside one window ──
                workspace.reorder(beta, 0)
                awaitUntil("Beta moved to the front of the strip") {
                    requireNotNull(fixture.groupOf("Beta")).ids.first() == beta
                }
                settle()
                check(fixture.bodyIncarnations.value[beta] == incarnationsBefore) {
                    "a reorder rebuilt the tab body: ${fixture.bodyIncarnations.value[beta]} vs $incarnationsBefore"
                }
                check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS)
                check(fixture.windowOf("Beta") === first) { "a reorder must not move the tab to another window" }

                // ── a change of selection: each body keeps its own state ──
                // Compose remembers by position, so the arriving body must not
                // be handed the slots — and the saveable values — of the one
                // that left.
                val gamma = fixture.tabId("Gamma")
                workspace.select(gamma)
                awaitUntil("Gamma is the composed body") { fixture.windowOf("Gamma") != null }
                settle()
                check(requireNotNull(fixture.counters.value[gamma]).value == 0) {
                    "Gamma inherited Beta's saveable state: ${fixture.counters.value[gamma]?.value}"
                }
                check(fixture.bodyIncarnations.value[gamma] != null) { "Gamma's body never ran its effects" }
                workspace.select(beta)
                awaitUntil("Beta is back") { fixture.windowOf("Beta") != null }
                settle()
                check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                    "Beta lost its state across a selection round trip"
                }

                // ── out into its own window and back, twice ──
                repeat(TAB_CHURN_CYCLES) { cycle ->
                    val torn =
                        requireNotNull(
                            workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor),
                        ) { "tear-off $cycle produced no window" }
                    awaitUntil("cycle $cycle: Beta composed in its own window") {
                        val window = torn.window
                        window != null && fixture.windowOf("Beta") === window && window !== first
                    }
                    settle(SETTLE_AFTER_MAP_MILLIS)
                    check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                        "cycle $cycle: saveable state lost on tear-off"
                    }

                    workspace.move(beta, requireNotNull(fixture.groupOf("Alpha")), index = 0)
                    awaitUntil("cycle $cycle: Beta back in the first window") {
                        workspace.groups.size == 1 && fixture.windowOf("Beta") === first
                    }
                    settle()
                    check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                        "cycle $cycle: saveable state lost on the way back"
                    }
                }
                check(workspace.tabs.size == 3) { "the churn lost a tab: ${workspace.tabs.size}" }
                check(fixture.composedBodies.value == 1) {
                    "one body per window should compose, got ${fixture.composedBodies.value}"
                }
            },
        )
    }

    /** A layout snapshot has to bring the windows back, including for tabs declared afterwards. */
    private fun snapshotRestoresWindows(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab workspace snapshot restores the windows and their tabs",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                val torn = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                awaitUntil("two windows") { workspace.groups.size == 2 && torn.window != null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val snapshot = workspace.snapshot()
                check(snapshot.groups.size == 2) { "the snapshot missed a window: ${snapshot.groups}" }

                // Merge everything back, then ask for the two windows again.
                workspace.move(beta, requireNotNull(fixture.groupOf("Alpha")))
                awaitUntil("one window") { workspace.groups.size == 1 }
                settle()

                workspace.restore(snapshot)
                awaitUntil("the snapshot's two windows are back") {
                    workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
                awaitUntil("both tabs are composed again") {
                    fixture.windowOf("Alpha") != null && fixture.windowOf("Beta") != null
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.windowOf("Alpha") !== fixture.windowOf("Beta")) {
                    "the restored tabs ended up in the same window"
                }

                // A snapshot applies once: a tab closed and declared again is a
                // new tab, and opens in the active window like any other.
                workspace.close(beta)
                awaitUntil("Beta's window is gone") { workspace.groups.size == 1 }
                fixture.titles += "Beta"
                awaitUntil("the redeclared tab opened in the surviving window") {
                    workspace.groups.size == 1 && fixture.groupOf("Beta") === fixture.groupOf("Alpha")
                }
                // And asking for the layout again does put it back in its own window.
                workspace.restore(snapshot)
                awaitUntil("the second restore split them again") {
                    workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
            },
        )
    }

    /** Closing the visible tab has to leave a visible tab behind, in a real window. */
    private fun closingTheSelectedTabPicksANeighbour(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace closing the selected tab shows a neighbour instead",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                workspace.select(fixture.tabId("Beta"))
                awaitUntil("Beta is the composed body") {
                    fixture.windowOf("Beta") != null && fixture.windowOf("Alpha") == null
                }

                workspace.close(fixture.tabId("Beta"))
                awaitUntil("Gamma took over as the visible tab") { fixture.windowOf("Gamma") != null }
                check(fixture.composedBodies.value == 1) {
                    "exactly one body composes per window, got ${fixture.composedBodies.value}"
                }

                workspace.close(fixture.tabId("Gamma"))
                awaitUntil("Alpha is all that is left") {
                    fixture.windowOf("Alpha") != null && workspace.tabs.size == 1
                }
                check(workspace.groups.size == 1) { "the window closed too early" }
            },
        )
    }
}
