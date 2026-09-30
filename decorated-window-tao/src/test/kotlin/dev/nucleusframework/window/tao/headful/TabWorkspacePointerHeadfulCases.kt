package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow

/**
 * The tab strip under a real pointer, on real windows — clicks fired faster
 * than a human can, gestures that stop just short of being drags, and buttons
 * that must do nothing at all.
 *
 * The events are posted into the window the way the native loop posts them
 * (see the pointer helpers in `WorkspaceChaosSupport`), so everything from the
 * sub-pixel deadband up is the real pipeline: the resize-edge band, Compose's
 * hit-testing, `clickable`, the touch slop and `Modifier.tabDragHandle`. Unlike
 * the `HeadfulRobot` cases next door, this runs on Wayland too, where no
 * process can inject into the compositor's pointer at all.
 *
 *  1. **clicks** — one, then bursts of them, alternating, with sub-pixel drift,
 *     and on the buttons that are not the left one;
 *  2. **the line between a click and a drag** — a press under the touch slop
 *     selects and nothing else; a press past it becomes a gesture;
 *  3. **the close button** — it closes and never drags, however fast it is hit;
 *  4. **clicks against everything else** — during a live drag, on an unfocused
 *     window, and on a tab that goes away under the pointer.
 */
internal object TabWorkspacePointerHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aClickSelectsTheTabUnderIt(),
            aBurstOfClicksOnOneTabSelectsItOnce(),
            clicksAlternatingBetweenTabsAlwaysLandOnTheLast(),
            aClickWithSubPixelDriftStillSelects(),
            aPressUnderTheTouchSlopOnlySelects(),
            aPressPastTheSlopBecomesADragAndBackAgain(),
            aPointerDragOutOfTheStripTearsTheTabOff(),
            aPointerDragOntoAnotherStripMergesTheTab(),
            theCloseButtonClosesAndNeverDrags(),
            closeClicksInSuccessionCloseOneTabEach(),
            aRightClickOnATabNeitherSelectsNorDrags(),
            aMiddleClickOnATabDoesNothing(),
            clicksOnAnUnfocusedWindowSelectInThatWindow(),
            aPressWhoseTabIsClosedUnderItLeavesNoDrag(),
            aClickStormAcrossTwoWindowsKeepsBothStripsConsistent(),
            aPointerLeavingMidDragKeepsTheGestureAlive(),
        )

    // ── 1. clicks ────────────────────────────────────────────────────────

    /** The plainest gesture there is: click a tab, that tab is showing. */
    private fun aClickSelectsTheTabUnderIt(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer a click selects the tab under it",
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                for (title in listOf("Gamma", "Alpha", "Beta")) {
                    val point = requireNotNull(fixture.tabPointInWindowPx(title)) { "$title has no slot" }
                    tabWindow.pointerClick(point)
                    awaitUntil("$title became the selected tab") {
                        fixture.groupOf(title)?.selectedId == fixture.tabId(title)
                    }
                    awaitUntil("and its body composed") { fixture.windowOf(title) === tabWindow }
                }
                check(fixture.workspace.groups.size == 1) { "a click opened a window" }
                check(fixture.composedBodies.value == 1) { "clicks left extra bodies composing" }
            },
        )
    }

    /**
     * A burst of clicks on the tab that is already selected — a double click,
     * a triple, an impatient user. Selection is idempotent, nothing may be
     * dragged, and no window may appear.
     */
    private fun aBurstOfClicksOnOneTabSelectsItOnce(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab pointer a burst of clicks on one tab changes nothing but the selection",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, "Alpha", "Beta")
                val point = requireNotNull(fixture.tabPointInWindowPx("Beta"))
                val incarnationsBefore = fixture.bodyIncarnations.value[fixture.tabId("Beta")] ?: 0

                repeat(CLICK_BURST) { tabWindow.pointerClick(point) }
                awaitUntil("Beta is selected") {
                    fixture.groupOf("Beta")?.selectedId == fixture.tabId("Beta")
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.groups.size == 1) { "the burst opened a window" }
                check(fixture.workspace.draggedTab == null && fixture.workspace.dragGhost == null) {
                    "the burst started a drag"
                }
                check(fixture.composedBodies.value == 1) {
                    "the burst left ${fixture.composedBodies.value} bodies composing"
                }
                val after = fixture.bodyIncarnations.value[fixture.tabId("Beta")] ?: 0
                check(after - incarnationsBefore <= 1) {
                    "$CLICK_BURST clicks rebuilt Beta's body ${after - incarnationsBefore} times"
                }
            },
        )
    }

    /**
     * Clicks alternating between two tabs as fast as they can be posted. Every
     * change swaps which body is composed, so this is where a body left behind
     * shows up — and the last click has to win.
     */
    private fun clicksAlternatingBetweenTabsAlwaysLandOnTheLast(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer clicks alternating between tabs always end on the last one",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val points = titles.associateWith { requireNotNull(fixture.tabPointInWindowPx(it)) }

                repeat(ALTERNATION_STORM) { round ->
                    tabWindow.pointerClick(requireNotNull(points[titles[round % titles.size]]))
                }
                val last = titles[(ALTERNATION_STORM - 1) % titles.size]
                awaitUntil("the storm settled on $last") {
                    fixture.groupOf(last)?.selectedId == fixture.tabId(last)
                }
                awaitUntil("only its body composes") { fixture.composedBodies.value == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.windowOf(last) === tabWindow) { "$last is not the composed body" }
                check(fixture.workspace.groups.size == 1) { "the storm opened a window" }
                check(fixture.workspace.draggedTab == null) { "the storm left a drag behind" }
                // Every tab is still where it was: clicks reorder nothing.
                check(requireNotNull(fixture.groupOf("Alpha")).ids == titles.map(fixture::tabId)) {
                    "the storm reordered the strip: ${fixture.groupOf("Alpha")?.ids}"
                }
            },
        )
    }

    /**
     * The #615 shape, at the workspace level: a click whose cursor drifts a
     * fraction of a pixel between press and release. Without the sub-pixel
     * deadband the drift starts the tab's drag gesture, which consumes the
     * move, and the tab is never selected — "tabs need two clicks".
     */
    private fun aClickWithSubPixelDriftStillSelects(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab pointer a click that drifts a fraction of a pixel still selects",
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, "Alpha", "Beta")
                val point = requireNotNull(fixture.tabPointInWindowPx("Beta"))

                tabWindow.pointerMove(point)
                tabWindow.pointerPress()
                // The drift a real mouse reports between press and release.
                tabWindow.pointerMove(point + Offset(SUB_PIXEL_DRIFT_PX, SUB_PIXEL_DRIFT_PX))
                tabWindow.pointerRelease()

                awaitUntil("the drifting click selected Beta") {
                    fixture.groupOf("Beta")?.selectedId == fixture.tabId("Beta")
                }
                settle()
                check(fixture.workspace.draggedTab == null && fixture.workspace.dragGhost == null) {
                    "sub-pixel drift started a drag"
                }
                check(fixture.workspace.groups.size == 1) { "sub-pixel drift tore the tab off" }
            },
        )
    }

    /**
     * A press that moves a couple of pixels and comes back — a hand that is not
     * quite steady. Under the touch slop it is a click, so it selects and
     * starts no gesture.
     */
    private fun aPressUnderTheTouchSlopOnlySelects(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab pointer a press that wobbles under the touch slop only selects",
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, "Alpha", "Beta")
                val point = requireNotNull(fixture.tabPointInWindowPx("Beta"))
                val scale = tabWindow.scaleFactor

                tabWindow.pointerMove(point)
                tabWindow.pointerPress()
                settle(POINTER_DRAG_STEP_MILLIS)
                for (dx in listOf(1f, -1f, 1f)) {
                    tabWindow.pointerMove(point + Offset(dx * WOBBLE_DP * scale, 0f))
                    settle(POINTER_DRAG_STEP_MILLIS)
                }
                check(fixture.workspace.draggedTab == null) { "a wobble under the slop started a drag" }
                tabWindow.pointerMove(point)
                tabWindow.pointerRelease()

                awaitUntil("the wobbling press selected Beta") {
                    fixture.groupOf("Beta")?.selectedId == fixture.tabId("Beta")
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.groups.size == 1) { "a wobble tore the tab off" }
                check(fixture.workspace.dragGhost == null) { "a wobble left a ghost behind" }
            },
        )
    }

    /**
     * Past the slop it is a gesture: the workspace publishes the drag, and
     * releasing back over the tab's own slot puts it back where it was rather
     * than tearing it out.
     */
    private fun aPressPastTheSlopBecomesADragAndBackAgain(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer a press past the slop drags and releasing home reorders nothing",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val beta = fixture.tabId("Beta")
                val home = requireNotNull(fixture.tabPointInWindowPx("Beta"))
                val slot = requireNotNull(fixture.tabSlotInWindowPx("Beta"))
                val idsBefore = requireNotNull(fixture.groupOf("Beta")).ids

                // A few pixels to the right, well past the slop but inside the
                // tab's own slot, then back home.
                pointerDragFrom(tabWindow, home, home + Offset(slot.width / 3f, 0f))
                awaitUntil("the gesture became a drag") { fixture.workspace.draggedTab?.id == beta }
                tabWindow.pointerMove(home)
                settle(POINTER_DRAG_STEP_MILLIS)
                tabWindow.pointerRelease()

                awaitUntil("the drag ended") { fixture.workspace.draggedTab == null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.groups.size == 1) { "releasing home tore the tab off" }
                check(requireNotNull(fixture.groupOf("Beta")).ids == idsBefore) {
                    "releasing home reordered the strip: ${fixture.groupOf("Beta")?.ids}"
                }
                check(fixture.workspace.dragGhost == null && fixture.workspace.dropPreview == null) {
                    "drag feedback outlived the gesture"
                }
            },
        )
    }

    /**
     * The whole tear-off gesture with nothing but pointer events: press a tab,
     * drag it out of the strip, release. A window appears under the pointer
     * with that tab in it.
     */
    private fun aPointerDragOutOfTheStripTearsTheTabOff(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer a drag out of the strip tears the tab into its own window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val beta = fixture.tabId("Beta")
                val home = requireNotNull(fixture.tabPointInWindowPx("Beta"))
                val outer = requireNotNull(tabWindow.outerBoundsPx())
                // Straight down into the body, far clear of the strip.
                val out = Offset(home.x, outer[RECT_H] * DEEP_IN_BODY)

                pointerDragFrom(tabWindow, home, out)
                awaitUntil("the tab is being dragged") { fixture.workspace.draggedTab?.id == beta }
                check(fixture.workspace.dragGhost != null) { "the tear-out is not previewed" }
                tabWindow.pointerRelease()

                awaitUntil("it landed in a window of its own") {
                    fixture.workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
                val torn = awaitMappedStrip(fixture, requireNotNull(fixture.groupOf("Beta")))
                check(torn !== tabWindow) { "the tab stayed in its old window" }
                check(fixture.workspace.dragGhost == null && fixture.workspace.dropPreview == null) {
                    "drag feedback outlived the tear-off"
                }
                awaitUntil("one body per window composes") { fixture.composedBodies.value == 2 }
            },
        )
    }

    /**
     * And the way back, by pointer: the tab dragged out of its window and
     * released on another window's strip merges into it at the insertion point
     * under the pointer.
     */
    private fun aPointerDragOntoAnotherStripMergesTheTab(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab pointer a drag released on another strip merges the tab into it",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val gamma = fixture.tabId("Gamma")
                // Gamma and Beta into a second window, so the source strip has
                // two tabs and the gesture is a lift-out rather than a window move.
                val second = requireNotNull(workspace.tearOff(gamma, tearOffRectPx(first), first.scaleFactor))
                val secondWindow = awaitMappedStrip(fixture, second)
                workspace.move(fixture.tabId("Beta"), second)
                awaitUntil("the second window holds both") { second.ids.size == 2 }
                awaitMappedStrip(fixture, second)
                settle(SETTLE_AFTER_MAP_MILLIS)

                val home = requireNotNull(fixture.groupOf("Alpha"))
                val grab = requireNotNull(fixture.tabPointInWindowPx("Gamma"))
                val targetOnScreen =
                    requireNotNull(fixture.stripPointPx(home, MERGE_X_FRACTION)) { "no target strip point" }
                // The gesture is driven in the source window's coordinates; the
                // drop lands wherever that is on screen.
                val client = requireNotNull(fixture.workspace.stripGeometry(second)?.clientOriginPx())
                val targetInSource = targetOnScreen - client

                pointerDragFrom(secondWindow, grab, targetInSource)
                awaitUntil("the drop is previewed in the other window") {
                    workspace.dropPreview?.group === home
                }
                secondWindow.pointerRelease()

                awaitUntil("Gamma merged into the first window") {
                    fixture.groupOf("Gamma") === home && home.ids.contains(gamma)
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.size == 2) { "the merge changed the window count" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) {
                    "drag feedback outlived the merge"
                }
            },
        )
    }

    // ── 3. the close button ──────────────────────────────────────────────

    /**
     * The × of a tab: it closes, and because `clickable` consumes the press it
     * must never start the tab's drag — a close that tears the tab into a new
     * window on the way out is the worst possible outcome.
     */
    private fun theCloseButtonClosesAndNeverDrags(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer the close button closes the tab and never drags it",
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val beta = fixture.tabId("Beta")
                val close = requireNotNull(closePointInWindowPx(fixture, tabWindow, "Beta"))

                tabWindow.pointerClick(close)
                awaitUntil("Beta was closed") { fixture.workspace.tab(beta) == null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.groups.size == 1) { "the close opened a window" }
                check(fixture.workspace.draggedTab == null && fixture.workspace.dragGhost == null) {
                    "the close started a drag"
                }
                check(requireNotNull(fixture.groupOf("Alpha")).ids.size == 2) {
                    "the close took more than one tab: ${fixture.groupOf("Alpha")?.ids}"
                }
            },
        )
    }

    /**
     * Closing tab after tab by hitting the × where the *next* tab has just
     * slid — the strip re-lays out between clicks, so each click has to be
     * aimed at the strip as it is now, and each has to close exactly one tab.
     */
    private fun closeClicksInSuccessionCloseOneTabEach(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer close clicks in succession close one tab each",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                for (title in listOf("Delta", "Gamma")) {
                    val before = fixture.workspace.tabs.size
                    val close =
                        requireNotNull(closePointInWindowPx(fixture, tabWindow, title)) {
                            "$title has no close button"
                        }
                    tabWindow.pointerClick(close)
                    awaitUntil("$title closed") { fixture.workspace.tab(fixture.tabId(title)) == null }
                    awaitUntil("the strip re-laid out around it") {
                        val group = fixture.groupOf("Alpha") ?: return@awaitUntil false
                        group.slotsInWindowPx.size >= group.ids.size &&
                            group.slotsInWindowPx.take(group.ids.size).all { it.width > 1f }
                    }
                    settle(SETTLE_AFTER_MAP_MILLIS)
                    check(fixture.workspace.tabs.size == before - 1) {
                        "closing $title took ${before - fixture.workspace.tabs.size} tabs"
                    }
                }
                check(fixture.workspace.groups.size == 1) { "the closes opened a window" }
                check(fixture.composedBodies.value == 1) { "the closes left extra bodies composing" }
            },
        )
    }

    // ── 4. buttons that are not the left one, and everything else ────────

    /** A right click is for a context menu, not for selecting or dragging. */
    private fun aRightClickOnATabNeitherSelectsNorDrags(): TaoWindowTestCase =
        secondaryButtonCase(
            name = "tab pointer a right click on a tab neither selects nor drags",
            button = TaoMouseButton.RIGHT,
        )

    /** Middle click is close-tab in a browser, and nothing at all here. */
    private fun aMiddleClickOnATabDoesNothing(): TaoWindowTestCase =
        secondaryButtonCase(
            name = "tab pointer a middle click on a tab does nothing",
            button = TaoMouseButton.MIDDLE,
        )

    private fun secondaryButtonCase(
        name: String,
        button: Int,
    ): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = name,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, "Alpha", "Beta")
                val selected = requireNotNull(fixture.groupOf("Alpha")).selectedId
                val point = requireNotNull(fixture.tabPointInWindowPx("Beta"))

                repeat(SECONDARY_CLICKS) { tabWindow.pointerClick(point, button) }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(requireNotNull(fixture.groupOf("Alpha")).selectedId == selected) {
                    "a non-left click changed the selection to " +
                        "${fixture.groupOf("Alpha")?.selectedId}"
                }
                check(fixture.workspace.draggedTab == null && fixture.workspace.dragGhost == null) {
                    "a non-left click started a drag"
                }
                check(fixture.workspace.groups.size == 1) { "a non-left click opened a window" }
                check(fixture.workspace.tabs.size == 2) { "a non-left click closed a tab" }
                // And the left button still works right after.
                tabWindow.pointerClick(point)
                awaitUntil("a left click still selects") {
                    fixture.groupOf("Beta")?.selectedId == fixture.tabId("Beta")
                }
            },
        )
    }

    /**
     * A click on a window that is not the focused one. Every window has its own
     * scene and its own strip, so the click belongs to the window it landed on
     * whatever the desktop thinks is focused.
     */
    private fun clicksOnAnUnfocusedWindowSelectInThatWindow(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer a click on an unfocused window selects in that window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val second =
                    requireNotNull(
                        workspace.tearOff(fixture.tabId("Gamma"), tearOffRectPx(first), first.scaleFactor),
                    )
                val secondWindow = awaitMappedStrip(fixture, second)
                workspace.move(fixture.tabId("Beta"), second)
                awaitUntil("the second window holds two tabs") { second.ids.size == 2 }
                awaitMappedStrip(fixture, second)
                first.focus()
                settle(SETTLE_AFTER_MAP_MILLIS)

                // Aimed at the window that is (probably) not focused.
                val point = requireNotNull(fixture.tabPointInWindowPx("Gamma"))
                secondWindow.pointerClick(point)
                awaitUntil("Gamma is selected in its own window") {
                    second.selectedId == fixture.tabId("Gamma")
                }
                check(requireNotNull(fixture.groupOf("Alpha")).selectedId == fixture.tabId("Alpha")) {
                    "the click changed the other window's selection"
                }
                check(workspace.groups.size == 2) { "the click changed the window count" }
            },
        )
    }

    /**
     * The tab under the pointer, closed by the application while the button is
     * still down. The gesture has nothing left to act on and must simply end.
     */
    private fun aPressWhoseTabIsClosedUnderItLeavesNoDrag(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer a press whose tab is closed under it leaves no drag behind",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val beta = fixture.tabId("Beta")
                val point = requireNotNull(fixture.tabPointInWindowPx("Beta"))

                tabWindow.pointerMove(point)
                tabWindow.pointerPress()
                settle(POINTER_DRAG_STEP_MILLIS)
                fixture.workspace.close(beta)
                awaitUntil("the tab is gone") { fixture.workspace.tab(beta) == null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                tabWindow.pointerRelease()
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(fixture.workspace.tab(beta) == null) { "the release brought the tab back" }
                check(fixture.workspace.draggedTab == null && fixture.workspace.dragGhost == null) {
                    "the release left drag feedback behind"
                }
                check(fixture.workspace.groups.size == 1) { "the release opened a window" }
                // And the strip still answers a click.
                val alpha = requireNotNull(fixture.tabPointInWindowPx("Alpha"))
                tabWindow.pointerClick(alpha)
                awaitUntil("clicking still works") {
                    fixture.groupOf("Alpha")?.selectedId == fixture.tabId("Alpha")
                }
            },
        )
    }

    /**
     * Two windows clicked in turn, over and over. Each strip keeps its own
     * selection and its own slots; a shared piece of state anywhere in the
     * chain shows up here as one window answering for the other.
     */
    private fun aClickStormAcrossTwoWindowsKeepsBothStripsConsistent(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer a click storm across two windows keeps both strips consistent",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val workspace = fixture.workspace
                val second =
                    requireNotNull(
                        workspace.tearOff(fixture.tabId("Gamma"), tearOffRectPx(first), first.scaleFactor),
                    )
                awaitMappedStrip(fixture, second)
                workspace.move(fixture.tabId("Delta"), second)
                awaitUntil("two windows of two tabs") {
                    workspace.groups.size == 2 && workspace.groups.all { it.ids.size == 2 }
                }
                val secondWindow = awaitMappedStrip(fixture, second)
                settle(SETTLE_AFTER_MAP_MILLIS)

                repeat(CROSS_WINDOW_CLICKS) { round ->
                    val onFirst = if (round % 2 == 0) "Alpha" else "Beta"
                    val onSecond = if (round % 2 == 0) "Delta" else "Gamma"
                    fixture.tabPointInWindowPx(onFirst)?.let { first.pointerClick(it) }
                    fixture.tabPointInWindowPx(onSecond)?.let { secondWindow.pointerClick(it) }
                }
                val lastFirst = if ((CROSS_WINDOW_CLICKS - 1) % 2 == 0) "Alpha" else "Beta"
                val lastSecond = if ((CROSS_WINDOW_CLICKS - 1) % 2 == 0) "Delta" else "Gamma"
                awaitUntil("each window settled on its own last click") {
                    requireNotNull(fixture.groupOf(lastFirst)).selectedId == fixture.tabId(lastFirst) &&
                        requireNotNull(fixture.groupOf(lastSecond)).selectedId == fixture.tabId(lastSecond)
                }
                awaitUntil("one body per window composes") { fixture.composedBodies.value == 2 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.size == 2) { "the storm changed the window count" }
                check(workspace.groups.all { it.ids.size == 2 }) {
                    "the storm moved a tab: ${workspace.groups.map { it.ids }}"
                }
                check(workspace.draggedTab == null) { "the storm left a drag behind" }
            },
        )
    }

    /**
     * The pointer leaving the window mid-drag — which it does the moment a tab
     * is dragged past the window's edge. The platform grab keeps delivering
     * positions, so a `CURSOR_LEFT` in the middle of a gesture must not end it.
     */
    private fun aPointerLeavingMidDragKeepsTheGestureAlive(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab pointer leaving the window mid-drag does not end the gesture",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSlots(fixture, *titles.toTypedArray())
                val beta = fixture.tabId("Beta")
                val home = requireNotNull(fixture.tabPointInWindowPx("Beta"))
                val outer = requireNotNull(tabWindow.outerBoundsPx())
                val out = Offset(home.x, outer[RECT_H] * DEEP_IN_BODY)

                pointerDragFrom(tabWindow, home, out)
                awaitUntil("the tab is being dragged") { fixture.workspace.draggedTab?.id == beta }

                // Past the bottom edge: the OS reports the pointer as gone.
                tabWindow.pointerExit()
                settle(POINTER_DRAG_STEP_MILLIS)
                check(fixture.workspace.draggedTab?.id == beta) { "leaving the window ended the drag" }
                tabWindow.pointerMove(Offset(home.x, outer[RECT_H] + BEYOND_EDGE_PX))
                settle(POINTER_DRAG_STEP_MILLIS)
                check(fixture.workspace.draggedTab?.id == beta) { "a position outside the window ended the drag" }
                tabWindow.pointerRelease()

                awaitUntil("the release outside tore the tab off") {
                    fixture.workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.workspace.dragGhost == null) { "drag feedback outlived the gesture" }
            },
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * The centre of the × of the tab titled [title], in window content px.
     *
     * The button sits at the trailing edge of the slot, inside the item's
     * horizontal padding — close enough to the edge that the offset is derived
     * from the strip's own metrics rather than hard-coded pixels.
     */
    private fun closePointInWindowPx(
        fixture: TabWorkspaceFixture,
        window: TaoWindow,
        title: String,
    ): Offset? {
        val slot = fixture.tabSlotInWindowPx(title) ?: return null
        val inset = CLOSE_BUTTON_INSET_DP * window.scaleFactor
        if (slot.width <= inset) return null
        return Offset(slot.right - inset, slot.center.y)
    }

    /** Distance from a tab slot's trailing edge to the centre of its close button, in dp. */
    private const val CLOSE_BUTTON_INSET_DP = 15f

    /** Sub-pixel drift: under the 1 dp deadband, over Compose's own mouse slop. */
    private const val SUB_PIXEL_DRIFT_PX = 0.3f

    /** A wobble in dp: over the deadband, under the touch slop. */
    private const val WOBBLE_DP = 2f

    /** How far down the window body a torn-off drop lands. */
    private const val DEEP_IN_BODY = 0.8f

    private const val BEYOND_EDGE_PX = 40f
    private const val CLICK_BURST = 40
    private const val ALTERNATION_STORM = 60
    private const val SECONDARY_CLICKS = 5
    private const val CROSS_WINDOW_CLICKS = 12
    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
