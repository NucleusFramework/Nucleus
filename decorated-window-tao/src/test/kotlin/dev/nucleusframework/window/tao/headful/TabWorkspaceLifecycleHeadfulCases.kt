package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.TabWindowGroup
import kotlin.math.abs

/**
 * The lifecycle half of the tab workspace, on real windows: every point where a
 * window, a tab or a body comes into existence or leaves it.
 *
 *  1. **bootstrap** — the tabs are declared *after* `TabWindows`, so the first
 *     window exists only because a write that lands mid-composition is picked
 *     up; nothing else in the archetype works if this does not;
 *  2. **the last window** — `onLastWindowClosed` fires per non-empty → empty
 *     transition, never for the empty workspace of the first composition, and
 *     again after the app re-opens a tab;
 *  3. **who closes what** — a window closed by the user takes its own tabs and
 *     no others; the last tab out of a window takes the window with it;
 *  4. **declaration** — a tab dropped from composition keeps its place with no
 *     body, comes back when re-declared, and a tab closed and declared again is
 *     a *new* tab with fresh state;
 *  5. **restore** — a snapshot brings the windows back after every one of them
 *     has been destroyed, and applying one under a live drag is survivable.
 *
 * Native Wayland is skipped along with the rest of the tab suite.
 */
internal object TabWorkspaceLifecycleHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            firstWindowOpensForTabsDeclaredAfterTabWindows(),
            lastWindowClosedFiresPerTransition(),
            userClosingAWindowClosesOnlyItsOwnTabs(),
            theLastTabOutOfAWindowTakesTheWindow(),
            aTabDroppedFromCompositionKeepsItsPlace(),
            aTabClosedAndDeclaredAgainIsANewTab(),
            everyWindowGoingAtOnceLeavesNothingComposed(),
            snapshotRestoresAfterEveryWindowWasDestroyed(),
            restoreUnderALiveDragStaysConsistent(),
            selectionSurvivesTheGroupItPointsAtBeingDropped(),
        )

    /**
     * The bootstrap, and the regression that hid behind the test harness: an
     * app declares its tabs next to `TabWindows`, hence *after* it, so the
     * first group is created by a write that lands during the composition
     * which has already read the group list. If that write is not picked up,
     * an application whose only windows come from the workspace never opens
     * one — and `onLastWindowClosed` must not read the startup emptiness as
     * "every window is gone" either.
     */
    private fun firstWindowOpensForTabsDeclaredAfterTabWindows(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab lifecycle opens the first window for tabs declared after TabWindows",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace

                check(workspace.groups.size == 1) { "three tabs must share one window: ${workspace.groups.size}" }
                check(requireNotNull(first.outerBoundsPx())[2] > 0) { "the first window has no size" }
                check(fixture.lastWindowClosedCount.value == 0) {
                    "onLastWindowClosed fired ${fixture.lastWindowClosedCount.value}× before a window ever opened"
                }
                val group = requireNotNull(fixture.groupOf("Alpha"))
                check(group.ids.size == 3) { "the strip is missing tabs: ${group.ids}" }
                check(group.selectedId != null) { "no tab is selected in a window that holds three" }
                awaitUntil("exactly one body composes") { fixture.composedBodies.value == 1 }
                check(fixture.stripRectPx(group) != null) { "the strip never published its geometry" }
            },
        )
    }

    /**
     * `onLastWindowClosed` is the app's exit hook, so it has to fire exactly
     * once per emptying — not at startup, not twice for one close — and it has
     * to fire *again* if the app carries on and opens another tab.
     */
    private fun lastWindowClosedFiresPerTransition(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab lifecycle reports the last window gone once per emptying",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                check(fixture.lastWindowClosedCount.value == 0) { "fired at startup" }

                // Two windows, so emptying goes through an intermediate state
                // that must not count as "the last one".
                val torn =
                    requireNotNull(
                        workspace.tearOff(fixture.tabId("Beta"), tearOffRectPx(first), first.scaleFactor),
                    )
                awaitMappedStrip(fixture, torn)
                workspace.close(fixture.tabId("Beta"))
                awaitUntil("one window left") { workspace.groups.size == 1 }
                settle()
                check(fixture.lastWindowClosedCount.value == 0) {
                    "closing one of two windows counted as the last one"
                }

                workspace.close(fixture.tabId("Alpha"))
                awaitUntil("the workspace is empty and reported it") {
                    workspace.groups.isEmpty() && fixture.lastWindowClosedCount.value == 1
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.lastWindowClosedCount.value == 1) {
                    "fired ${fixture.lastWindowClosedCount.value}× for one emptying"
                }
                check(fixture.composedBodies.value == 0) { "a body outlived every window" }

                // The app did not exit: a new tab opens a window again, and
                // emptying it reports a second time.
                fixture.titles += "Delta"
                awaitUntil("a new window opened for the new tab") {
                    workspace.groups.size == 1 && fixture.groupOf("Delta")?.ids == listOf(fixture.tabId("Delta"))
                }
                awaitMappedStrip(fixture, requireNotNull(fixture.groupOf("Delta")))
                check(fixture.lastWindowClosedCount.value == 1) { "re-opening fired the callback" }

                workspace.close(fixture.tabId("Delta"))
                awaitUntil("emptied again and reported again") {
                    workspace.groups.isEmpty() && fixture.lastWindowClosedCount.value == 2
                }
            },
        )
    }

    /**
     * The user hitting the close button of one window: the native request goes
     * through the window's `onCloseRequest`, which closes the tabs that window
     * holds. Tabs in another window must not notice.
     */
    private fun userClosingAWindowClosesOnlyItsOwnTabs(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab lifecycle closing a window closes its own tabs and no others",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val gamma = fixture.tabId("Gamma")

                // Beta and Gamma into a window of their own.
                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, second)
                workspace.move(gamma, second)
                awaitUntil("the second window holds two tabs") { second.ids.size == 2 }
                val secondWindow = awaitMappedStrip(fixture, second)
                var destroyed = false
                secondWindow.onDestroyed { destroyed = true }

                // The user-close path: what the native X and Alt+F4 fire, and
                // what a title-bar close button must fire — `requestClose`
                // would destroy the window behind the composition's back.
                secondWindow.requestUserClose()
                awaitUntil("the second window went with its tabs") {
                    destroyed && workspace.groups.size == 1
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.tab(beta) == null && workspace.tab(gamma) == null) {
                    "the closed window's tabs survived: ${workspace.tabs.map { it.id }}"
                }
                check(fixture.groupOf("Alpha")?.ids == listOf(fixture.tabId("Alpha"))) {
                    "the surviving window lost its tab: ${fixture.groupOf("Alpha")?.ids}"
                }
                check(requireNotNull(first.outerBoundsPx())[2] > 0) { "the surviving window was destroyed too" }
                awaitUntil("one body composes") { fixture.composedBodies.value == 1 }
                check(fixture.lastWindowClosedCount.value == 0) { "one window closing reported the last one" }
            },
        )
    }

    /**
     * Windows follow the tabs in both directions: the tab that leaves a window
     * empty destroys it, and the *only* tab of a window is moved rather than
     * torn into a second one.
     */
    private fun theLastTabOutOfAWindowTakesTheWindow(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab lifecycle the last tab out of a window takes the window with it",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val secondWindow = awaitMappedStrip(fixture, second)
                var destroyed = false
                secondWindow.onDestroyed { destroyed = true }

                // Tearing off the only tab of a window is a move of that
                // window, not a second window for the same tab.
                val movedTo = tearOffRectPx(secondWindow)
                val again = workspace.tearOff(beta, movedTo, secondWindow.scaleFactor)
                check(again === second) { "the only tab of a window was duplicated into another one" }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(!destroyed) { "the window was destroyed by a move" }
                check(workspace.groups.size == 2) { "an extra window appeared: ${workspace.groups.size}" }
                awaitUntil("the moved window is where the move asked") {
                    val now = secondWindow.outerBoundsPx() ?: return@awaitUntil false
                    abs(now[0] - movedTo.left.toLong()) <= TAB_SIZE_TOLERANCE_PX
                }

                // Back into the first window: the second one goes.
                workspace.move(beta, requireNotNull(fixture.groupOf("Alpha")))
                awaitUntil("the emptied window was destroyed") { destroyed && workspace.groups.size == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.windowOf("Beta") === first) { "Beta is not composed in the surviving window" }
                check(fixture.composedBodies.value == 1) {
                    "bodies left over: ${fixture.composedBodies.value}"
                }
            },
        )
    }

    /**
     * A tab the app takes out of composition — a document closed in the model
     * but not in the workspace — keeps its place in the strip with no body, and
     * gets it back when the app declares it again. What it must never do is
     * take its window down or move.
     */
    private fun aTabDroppedFromCompositionKeepsItsPlace(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab lifecycle a tab dropped from composition keeps its place and comes back",
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
                awaitUntil("Beta is the composed body") { fixture.windowOf("Beta") != null }
                requireNotNull(fixture.counters.value[beta]).value = TAB_SAVED_CLICKS
                val idsBefore = requireNotNull(fixture.groupOf("Beta")).ids

                // The app stops declaring it while it is the selected tab.
                fixture.titles -= "Beta"
                awaitUntil("Beta's body left") { fixture.composedBodies.value == 0 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.tab(beta) != null) { "an undeclared tab was forgotten entirely" }
                check(requireNotNull(fixture.groupOf("Beta")).ids == idsBefore) {
                    "the strip lost or moved the undeclared tab: ${fixture.groupOf("Beta")?.ids}"
                }
                check(workspace.groups.size == 1) { "the window went with the undeclared tab" }
                check(requireNotNull(first.outerBoundsPx())[2] > 0) { "the window was destroyed" }

                // And the window is still usable: selecting a declared tab
                // brings a body back.
                workspace.select(fixture.tabId("Gamma"))
                awaitUntil("Gamma took over") { fixture.windowOf("Gamma") === first }

                // Declared again, it composes again — in the same place.
                fixture.titles += "Beta"
                awaitUntil("Beta's body is back") { workspace.tab(beta)?.content != null }
                workspace.select(beta)
                awaitUntil("Beta composes again") { fixture.windowOf("Beta") === first }
                settle()
                check(requireNotNull(fixture.groupOf("Beta")).ids.contains(beta)) { "Beta lost its strip place" }
            },
        )
    }

    /**
     * A *closed* tab is gone, state included — unlike one that merely left
     * composition. Declaring the same id afterwards is a new tab: fresh
     * saveable state, and placed like any other new tab.
     */
    private fun aTabClosedAndDeclaredAgainIsANewTab(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab lifecycle a closed tab declared again is a new tab with fresh state",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                workspace.select(beta)
                awaitUntil("Beta is composed") { fixture.windowOf("Beta") != null }
                requireNotNull(fixture.counters.value[beta]).value = TAB_SAVED_CLICKS

                // Torn into its own window first, so the redeclaration also has
                // to pick a *window*, not just a strip slot.
                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, second)
                first.focus()
                awaitUntil("the first window is focused again") { first.isFocused }

                workspace.close(beta)
                fixture.titles -= "Beta"
                awaitUntil("Beta and its window are gone") {
                    workspace.tab(beta) == null && workspace.groups.size == 1
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                fixture.titles += "Beta"
                awaitUntil("the new Beta opened in the focused window") {
                    fixture.groupOf("Beta") === fixture.groupOf("Alpha")
                }
                awaitUntil("its body composed") { fixture.counters.value[beta] != null }
                settle()
                check(requireNotNull(fixture.counters.value[beta]).value == 0) {
                    "a closed tab's saveable state came back: ${fixture.counters.value[beta]?.value}"
                }
                check(workspace.groups.size == 1) { "the redeclared tab opened a window of its own" }
            },
        )
    }

    /**
     * Everything down at once, the way an app quits: several windows, each with
     * a composed body, all emptied in one pass. Nothing may outlive it — no
     * window, no body, no drag feedback — and the report must come exactly
     * once.
     */
    private fun everyWindowGoingAtOnceLeavesNothingComposed(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma", "Delta"))
        return TaoWindowTestCase(
            name = "tab lifecycle every window going at once leaves nothing composed",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma", "Delta")
                val workspace = fixture.workspace
                val spread = spreadOverWindows(fixture, first, listOf("Beta", "Gamma", "Delta"))
                check(workspace.groups.size == 4) { "expected four windows, got ${workspace.groups.size}" }
                awaitUntil("every window composes its body") { fixture.composedBodies.value == 4 }

                val destroyed = BooleanArray(spread.size)
                spread.forEachIndexed { index, group ->
                    requireNotNull(group.window).onDestroyed { destroyed[index] = true }
                }

                workspace.tabs.map { it.id }.forEach(workspace::close)
                awaitUntil("every window reported destroyed") { destroyed.all { it } }
                awaitUntil("the workspace is empty and reported once") {
                    workspace.groups.isEmpty() && fixture.lastWindowClosedCount.value == 1
                }
                awaitUntil("no body is composing") { fixture.composedBodies.value == 0 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.lastWindowClosedCount.value == 1) {
                    "reported ${fixture.lastWindowClosedCount.value}× for one shutdown"
                }
                check(workspace.tabs.isEmpty()) { "tabs survived: ${workspace.tabs.map { it.id }}" }
                check(workspace.draggedTab == null && workspace.dragGhost == null) { "drag feedback outlived the app" }
            },
        )
    }

    /**
     * The persistence story an app really needs: save the layout, lose every
     * window (a restart, or the user closing them all), declare the tabs again,
     * and get the windows back where they were.
     */
    private fun snapshotRestoresAfterEveryWindowWasDestroyed(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab lifecycle a snapshot restores the windows after all of them were destroyed",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                spreadOverWindows(fixture, first, listOf("Beta", "Gamma"))
                check(workspace.groups.size == 3) { "expected three windows" }

                val snapshot = workspace.snapshot()
                check(snapshot.groups.size == 3) { "the snapshot missed a window: ${snapshot.groups.size}" }
                val savedOf = snapshot.groups.associateBy { it.id }

                // Everything down, including the declarations.
                workspace.tabs.map { it.id }.forEach(workspace::close)
                fixture.titles.clear()
                awaitUntil("nothing is left") {
                    workspace.groups.isEmpty() && workspace.tabs.isEmpty() && fixture.composedBodies.value == 0
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                // The app asks for the layout back before declaring anything,
                // which is the order a real restart has.
                workspace.restore(snapshot)
                fixture.titles += listOf("Alpha", "Beta", "Gamma")
                awaitUntil("the three windows are back with one tab each") {
                    workspace.groups.size == 3 && workspace.groups.all { it.ids.size == 1 }
                }
                awaitUntil("every restored window is mapped") {
                    workspace.groups.all { (it.window?.outerBoundsPx()?.get(2) ?: 0L) > 0L }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.map { it.id }.toSet() == savedOf.keys) {
                    "restored under different group ids: ${workspace.groups.map { it.id }} vs ${savedOf.keys}"
                }
                for (group in workspace.groups) {
                    val saved = requireNotNull(savedOf[group.id])
                    check(group.ids == saved.tabIds) { "group ${group.id} holds ${group.ids}, saved ${saved.tabIds}" }
                    val window = requireNotNull(group.window)
                    val bounds = requireNotNull(window.outerBoundsPx())
                    val savedPosition = requireNotNull(saved.position)
                    val scale = window.scaleFactor
                    check(abs(bounds[0] - (savedPosition.x.value * scale).toLong()) <= RESTORE_TOLERANCE_PX) {
                        "group ${group.id} came back at ${bounds[0]}px, saved ${savedPosition.x}"
                    }
                }
                awaitUntil("three bodies compose again") { fixture.composedBodies.value == 3 }
            },
        )
    }

    /**
     * A restore arriving mid-gesture. The app is free to apply a saved layout
     * whenever it likes, including while the user is holding a tab — the
     * release must then act on the world as it is, not as it was at the grab.
     */
    private fun restoreUnderALiveDragStaysConsistent(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab lifecycle a layout restored under a live drag leaves no debris",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                spreadOverWindows(fixture, first, listOf("Gamma"))
                val snapshot = workspace.snapshot()

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val away = requireNotNull(fixture.farFromStripPx(requireNotNull(fixture.groupOf("Beta"))))
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)
                session.update(away)
                check(workspace.dragGhost != null) { "the tear-out must be previewed" }

                // The layout comes back under the pointer.
                workspace.restore(snapshot)
                settle()
                session.end(away)
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "a restore under a drag left feedback behind"
                }
                check(workspace.tabs.size == 3) { "a tab was lost: ${workspace.tabs.map { it.id }}" }
                check(workspace.groups.all { it.ids.isNotEmpty() }) { "an empty group survived" }
                for (group in workspace.groups) {
                    awaitUntil("group ${group.id} is mapped") {
                        (group.window?.outerBoundsPx()?.get(2) ?: 0L) > 0L
                    }
                }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    /**
     * A group can be dropped while it is the one the workspace considers
     * active — the window whose tab a new declaration would join. The next tab
     * must find a home anyway rather than land in a group that no longer
     * exists.
     */
    private fun selectionSurvivesTheGroupItPointsAtBeingDropped(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab lifecycle a new tab finds a window after the active one was dropped",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val secondWindow = awaitMappedStrip(fixture, second)
                secondWindow.focus()
                awaitUntil("the torn-off window is the focused one") { secondWindow.isFocused }
                awaitUntil("and the workspace agrees it is active") { workspace.activeGroup === second }

                // The active window goes; a new tab must not follow it into
                // nothing.
                workspace.close(beta)
                awaitUntil("the active group was dropped") { workspace.groups.size == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.activeGroup === fixture.groupOf("Alpha")) {
                    "the workspace still points at a dropped group"
                }

                fixture.titles += "Delta"
                awaitUntil("the new tab joined the surviving window") {
                    fixture.groupOf("Delta") === fixture.groupOf("Alpha") && workspace.groups.size == 1
                }
                awaitUntil("its body composes") { fixture.windowOf("Delta") === first }
            },
        )
    }

    /**
     * Tears each of [titles] into a window of its own, waits for every one of
     * them to map, and returns the groups in that order.
     */
    private suspend fun TaoWindowTestScope.spreadOverWindows(
        fixture: TabWorkspaceFixture,
        source: dev.nucleusframework.window.tao.TaoWindow,
        titles: List<String>,
    ): List<TabWindowGroup> {
        val groups = ArrayList<TabWindowGroup>(titles.size)
        for (title in titles) {
            val id = fixture.tabId(title)
            val from = requireNotNull(fixture.groupOf(title)?.window) { "$title has no window to leave" }
            val group =
                requireNotNull(fixture.workspace.tearOff(id, tearOffRectPx(from), source.scaleFactor)) {
                    "tearing $title off produced no window"
                }
            awaitMappedStrip(fixture, group)
            groups += group
        }
        return groups
    }

    /** Position after a snapshot round trip: dp rounding on both sides, plus whatever the WM adds. */
    private const val RESTORE_TOLERANCE_PX = 60L
}
