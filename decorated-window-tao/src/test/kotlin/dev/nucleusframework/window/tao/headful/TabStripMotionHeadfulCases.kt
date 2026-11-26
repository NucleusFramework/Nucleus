package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/**
 * Real-window coverage for the tab strip's *motion*: what a tab does on its
 * way to a new place, and what the strip's published geometry does while it
 * happens.
 *
 *  1. a reorder animates the drawing only — the slots a drop resolves against
 *     are the settled layout from the first frame;
 *  2. a tab dragged along its own strip stays in the strip's hands: no ghost
 *     window, and the release reorders it;
 *  3. a right-to-left strip runs from the right and carries a tab the same way;
 *  4. the close button plays the tab out before the workspace drops it, and a
 *     new tab arrives to be opened rather than already open;
 *  5. the numbers behind the motion: the carried tab is drawn at the pointer's
 *     travel, a crossed neighbour stands exactly one tab aside, the rest are at
 *     rest, and the release slides home before the order changes.
 *
 * Native Wayland is skipped: the drag there rides the platform's
 * drag-and-drop session, which tells the source nothing about the pointer.
 */
internal object TabStripMotionHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aReorderAnimatesTheDrawingNotTheGeometry(),
            aTabDraggedInItsOwnStripStaysInIt(),
            aRightToLeftStripRunsFromTheRight(),
            theCloseButtonPlaysTheTabOut(),
            theCarriedTabAndItsNeighboursMoveByTheNumbers(),
        )

    /**
     * A reorder moves the tabs at once as far as the workspace is concerned —
     * only the drawing travels ([dev.nucleusframework.window.tao.TabReorderAnimation]).
     *
     * Sampled one frame after the reorder, well inside the animation: the slot
     * rects have already swapped, and a drop resolved from a pointer over the
     * first slot answers with the first index. Were the geometry animated, the
     * strip would promise for a fifth of a second a drop it does not do.
     */
    private fun aReorderAnimatesTheDrawingNotTheGeometry(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace animates a reorder without moving the geometry a drop resolves against",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val firstSlot = requireNotNull(fixture.tabSlotInWindowPx("Alpha"))
                val gamma = fixture.tabId("Gamma")

                workspace.reorder(gamma, 0)
                awaitUntil("Gamma is the first tab of the strip") { group.ids.first() == gamma }
                // One frame, deep inside the 180 ms the drawing takes.
                settle(ONE_FRAME_MILLIS)
                val gammaSlot = requireNotNull(fixture.tabSlotInWindowPx("Gamma"))
                check(abs(gammaSlot.left - firstSlot.left) <= LAYOUT_TOLERANCE_PX) {
                    "the slot a drop resolves against is still travelling: $gammaSlot vs $firstSlot"
                }
                check(abs(gammaSlot.width - firstSlot.width) <= LAYOUT_TOLERANCE_PX) {
                    "the first slot changed width on a reorder: $gammaSlot vs $firstSlot"
                }

                // What the workspace answers a pointer, mid-animation: the
                // left edge of the first slot is the first index.
                val client = requireNotNull(workspace.stripGeometry(group)?.clientOriginPx())
                val atStart = client + Offset(firstSlot.left + EDGE_PROBE_PX, firstSlot.center.y)
                val target = requireNotNull(workspace.dropTargetAt(atStart)) { "no drop target over the first slot" }
                check(target.group === group && target.index == 0) {
                    "a drop over the first slot resolved to ${target.index}, not the first place"
                }

                // And it settles where it was put.
                settle(REORDER_SETTLE_MILLIS)
                check(group.ids == listOf(gamma, fixture.tabId("Alpha"), fixture.tabId("Beta"))) {
                    "the strip order drifted after the animation: ${group.ids}"
                }
                check(
                    abs(
                        requireNotNull(fixture.tabSlotInWindowPx("Gamma")).left - firstSlot.left,
                    ) <= LAYOUT_TOLERANCE_PX,
                ) {
                    "the settled slot moved"
                }
            },
        )
    }

    /**
     * The browser gesture: a tab dragged along its own strip never leaves it.
     * No ghost window is published while the pointer is over the strip — the
     * strip draws the tab under the pointer and its neighbours make room — and
     * the release is a reorder. Leave the strip and the ghost appears, which is
     * what says the tab is being taken out; come back and it is put away again.
     */
    private fun aTabDraggedInItsOwnStripStaysInIt(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace a tab dragged along its own strip is held by the strip, not by a ghost",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val gamma = fixture.tabId("Gamma")
                val onGamma = requireNotNull(fixture.tabCenterPx("Gamma"))
                // The leading edge of the first tab, where the insertion index
                // is the first place — its centre would already be "after it".
                val client = requireNotNull(workspace.stripGeometry(group)?.clientOriginPx())
                val alphaSlot = requireNotNull(fixture.tabSlotInWindowPx("Alpha"))
                val onAlpha = client + Offset(alphaSlot.left + EDGE_PROBE_PX, alphaSlot.center.y)
                val strip = requireNotNull(fixture.stripRectPx(group))

                val session = requireNotNull(workspace.beginDrag(gamma, stripOrigin(first), onGamma))
                session.update(onGamma)
                // Along the strip, over the first tab: in hand, still home.
                session.update(onAlpha)
                settle()
                check(workspace.dragGhost == null) { "a ghost window for a tab still in its strip" }
                check(workspace.draggedTab?.id == gamma) { "the drag lost its tab" }
                check(workspace.dropPreview?.group === group && workspace.dropPreview?.index == 0) {
                    "the strip does not show the tab landing first: ${workspace.dropPreview}"
                }
                check(workspace.dragPointerScreenPx == onAlpha) {
                    "the strip was not told where the pointer is: ${workspace.dragPointerScreenPx}"
                }

                // Out of the strip: now it really is leaving, so the ghost takes it.
                val below = Offset(onAlpha.x, strip.bottom + OUT_OF_STRIP_PX)
                session.update(below)
                settle()
                check(workspace.dragGhost?.tab?.id == gamma) { "no ghost once the tab left the strip" }

                // Back on the strip: the strip takes it in hand again.
                session.update(onAlpha)
                settle()
                check(workspace.dragGhost == null) { "the ghost outlived the tab's return to the strip" }

                session.end(onAlpha)
                awaitUntil("the tab was reordered rather than torn out") {
                    workspace.groups.size == 1 && group.ids.first() == gamma
                }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
                check(workspace.dragPointerScreenPx == null) { "the pointer outlived the drag" }
            },
        )
    }

    /**
     * A strip composed right to left — a Hebrew or Arabic app: the first tab is
     * the *rightmost*, and a tab carried along it resolves the same insertion
     * indices, since the strip's own geometry is what a drop is measured
     * against whichever way the tabs run.
     */
    private fun aRightToLeftStripRunsFromTheRight(): TaoWindowTestCase {
        val fixture =
            TabWorkspaceFixture(
                initialTitles = listOf("Alpha", "Beta", "Gamma"),
                layoutDirection = LayoutDirection.Rtl,
            )
        return TaoWindowTestCase(
            name = "tab workspace a right-to-left strip runs from the right and carries a tab the same way",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val alpha = requireNotNull(fixture.tabSlotInWindowPx("Alpha"))
                val beta = requireNotNull(fixture.tabSlotInWindowPx("Beta"))
                val gammaSlot = requireNotNull(fixture.tabSlotInWindowPx("Gamma"))

                // The first tab is the rightmost, the last the leftmost.
                check(alpha.left > beta.left && beta.left > gammaSlot.left) {
                    "the strip does not run from the right: alpha=$alpha beta=$beta gamma=$gammaSlot"
                }

                // Carried from the last place to the first: the pointer aims at
                // the trailing edge of the first tab, which in this direction is
                // its right edge.
                val client = requireNotNull(workspace.stripGeometry(group)?.clientOriginPx())
                val gamma = fixture.tabId("Gamma")
                val onGamma = requireNotNull(fixture.tabCenterPx("Gamma"))
                val atFirst = client + Offset(alpha.right - EDGE_PROBE_PX, alpha.center.y)
                val session = requireNotNull(workspace.beginDrag(gamma, stripOrigin(first), onGamma))
                session.update(onGamma)
                session.update(atFirst)
                settle()
                check(workspace.dragGhost == null) { "a ghost for a tab still in its own strip" }
                check(workspace.dropPreview?.group === group && workspace.dropPreview?.index == 0) {
                    "the right edge of the first tab is not the first place: ${workspace.dropPreview}"
                }
                session.end(atFirst)
                awaitUntil("the tab took the first place") { group.ids.first() == gamma }
                settle(REORDER_SETTLE_MILLIS)
                // And it is the rightmost tab now, geometry included.
                val settled = requireNotNull(fixture.tabSlotInWindowPx("Gamma"))
                check(abs(settled.right - alpha.right) <= LAYOUT_TOLERANCE_PX) {
                    "the reordered tab is not where the first slot is: $settled vs $alpha"
                }
            },
        )
    }

    /**
     * The strip's close button shuts the tab's width before the workspace hears
     * about it, which is what makes a close a motion rather than a jump: right
     * after the click the tab is still there, and it is gone once the animation
     * has had its time.
     *
     * The other half of the same contract: a tab the strip has not shown yet is
     * marked as arriving, so it opens by width instead of appearing at its full
     * one — see `TabEntry.isEntering`.
     */
    private fun theCloseButtonPlaysTheTabOut(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace the close button plays the tab out before the workspace drops it",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val slot = requireNotNull(fixture.tabSlotInWindowPx("Beta"))
                val driver = SyntheticPointerDriver(first)

                // The close button of the stock tab sits at its trailing edge.
                val closeButton = Offset(slot.right - CLOSE_BUTTON_INSET_PX, slot.center.y)
                driver.click(closeButton)
                settle(ONE_FRAME_MILLIS)
                check(workspace.tab(beta) != null) {
                    "the workspace dropped the tab before the strip could play it out"
                }
                awaitUntil("the tab is gone once its width has shut") { workspace.tab(beta) == null }
                check(requireNotNull(fixture.groupOf("Alpha")).ids.size == 2) {
                    "the strip did not settle on two tabs: ${fixture.groupOf("Alpha")?.ids}"
                }

                // A tab declared now has not been shown yet: it is marked as arriving.
                fixture.titles += "Delta"
                awaitUntil("Delta is declared") { workspace.tab(fixture.tabId("Delta")) != null }
                awaitUntil("and the strip has taken it in hand") {
                    workspace.tab(fixture.tabId("Delta"))?.isEntering == false
                }
                settle()
                check(requireNotNull(fixture.groupOf("Alpha")).ids.size == 3) { "Delta did not join the strip" }
            },
        )
    }

    /**
     * What the strip's motion actually is, asserted rather than looked at: the
     * tab in hand is drawn at exactly the pointer's travel since the grab, a
     * neighbour whose centre that tab's leading edge has crossed comes to rest
     * exactly one tab-width aside, a neighbour it has not reached stays at
     * zero, and the release slides the carried tab into the crossed
     * neighbour's slot *before* the order changes — every offset back to zero
     * once it has.
     */
    private fun theCarriedTabAndItsNeighboursMoveByTheNumbers(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace the carried tab and its neighbours move by the numbers",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val motion = requireNotNull(workspace.motionOf(group)) { "the strip published no motion" }
                val gamma = fixture.tabId("Gamma")
                val beta = fixture.tabId("Beta")
                val alpha = fixture.tabId("Alpha")
                val gammaSlot = requireNotNull(motion.slotOf(gamma)) { "no slot for the tab to be carried" }
                val betaSlot = requireNotNull(motion.slotOf(beta))
                val width = gammaSlot.width
                check(width > MIN_TAB_WIDTH_PX) { "a tab of $width px is too narrow to carry meaningfully" }

                // Grabbed in the middle of the last tab, then carried far
                // enough left that its leading edge passes the middle tab's
                // centre — the library's rule, and ours.
                // The workspace's own drag: where the app places its windows,
                // that is what the strip animates from. The local gesture of a
                // compositor-placed window is covered on the Wayland leg.
                val grab = requireNotNull(fixture.tabCenterPx("Gamma"))
                val session = requireNotNull(workspace.beginDrag(gamma, stripOrigin(first), grab))
                session.update(grab)
                val travel = -(width * CARRY_SLOTS)
                val carriedTo = grab + Offset(travel, 0f)
                session.update(carriedTo)

                awaitUntil("the middle tab has stepped aside by one tab: ${motion.drawnOffsetOf(beta)}") {
                    abs(motion.drawnOffsetOf(beta) - width) <= MOTION_TOLERANCE_PX
                }
                check(abs(motion.drawnOffsetOf(gamma) - travel) <= MOTION_TOLERANCE_PX) {
                    "the carried tab is drawn at ${motion.drawnOffsetOf(gamma)} px, the pointer travelled $travel"
                }
                check(abs(motion.drawnOffsetOf(alpha)) <= MOTION_TOLERANCE_PX) {
                    "a tab the carried one never reached moved: ${motion.drawnOffsetOf(alpha)}"
                }
                check(motion.slotOf(gamma) == gammaSlot && motion.slotOf(beta) == betaSlot) {
                    "the motion moved the layout: the slots a drop resolves against must not budge"
                }

                // Released: it slides into the middle tab's slot, and only then
                // is the order changed — with every offset back to zero.
                session.end(carriedTo)
                awaitUntil("the reorder is applied once the slide is over") {
                    group.ids == listOf(alpha, gamma, beta)
                }
                check(abs(motion.drawnOffsetOf(gamma)) <= MOTION_TOLERANCE_PX) {
                    "the tab kept an offset after the order changed: ${motion.drawnOffsetOf(gamma)}"
                }
                check(abs(motion.drawnOffsetOf(beta)) <= MOTION_TOLERANCE_PX) {
                    "a neighbour kept an offset after the order changed: ${motion.drawnOffsetOf(beta)}"
                }
                check(workspace.pendingReorder == null) { "the settle was never cleared" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /** One frame at 60 Hz: long enough for the reorder to be laid out, far from the animation's end. */
    private const val ONE_FRAME_MILLIS = 24L

    /** Comfortably past the slide home. */
    private const val REORDER_SETTLE_MILLIS = 400L

    /** Just inside a slot's leading edge: the index before that tab. */
    private const val EDGE_PROBE_PX = 4f

    /** Below the strip: the window's body, where a dragged tab is out of the strip's hands. */
    private const val OUT_OF_STRIP_PX = 60f

    /** Inside a tab's trailing edge, where the stock strip puts its close button. */
    private const val CLOSE_BUTTON_INSET_PX = 12f

    /** Far enough for the carried tab's leading edge to pass one neighbour's centre. */
    private const val CARRY_SLOTS = 0.8f

    /** A spring settles within a pixel; anything larger is a wrong number, not a rounding. */
    private const val MOTION_TOLERANCE_PX = 2f

    /** Below this a tab is too narrow for the case to mean anything. */
    private const val MIN_TAB_WIDTH_PX = 40f
}
