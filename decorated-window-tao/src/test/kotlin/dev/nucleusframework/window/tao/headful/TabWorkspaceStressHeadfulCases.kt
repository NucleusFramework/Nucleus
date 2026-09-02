package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

/**
 * The adversarial half of the tab workspace, on real windows: everything that
 * happens between a clean grab and a clean drop.
 *
 *  1. **abrupt movement** — a pointer that teleports across and off the screen
 *     in single samples, then a real mouse flick the OS coalesces into a
 *     couple of enormous deltas;
 *  2. **backing-scale change** — the display flips between its 1x and HiDPI
 *     twin while tabs are open, and a tear-off after it must still land under
 *     the pointer at a window of the right logical size;
 *  3. **minimize** — a minimized window is not a drop target, and comes back
 *     as one when restored;
 *  4. **maximize** — a maximized window's strip is where a drop lands, and a
 *     tab torn out of it gets a window of its own rather than a maximized one;
 *  5. **interrupted gestures** — a drag whose window is resized under it, a
 *     superseded drag, and a drag whose target window closes mid-gesture;
 *  6. **window close mid-drag** — the source window destroyed while its tab is
 *     in flight.
 *
 * Native Wayland is skipped along with the rest of the tab suite.
 */
internal object TabWorkspaceStressHeadfulCases {
    private val isMac: Boolean get() = Platform.Current == Platform.MacOS

    fun all(): List<TaoWindowTestCase> =
        listOf(
            abruptPointerJumpsStillResolve(),
            robotFlickTearsOffTheTab(),
            backingScaleChangeKeepsDropsHonest(),
            minimizedWindowIsNoDropTarget(),
            maximizedWindowTakesAndGivesTabs(),
            interruptedAndSupersededDragsLeaveNoFeedback(),
            sourceWindowClosingMidDragStaysSane(),
        )

    /**
     * A pointer that teleports: no intermediate samples, jumps far off-screen
     * and back, crossing strips without ever hovering the space between them —
     * a synthetic replay does this, and so does a fast flick, since the OS
     * coalesces motion into one enormous delta.
     *
     * Driven through the drag session rather than the Robot: the Robot cannot
     * express "no samples in between" (the OS interpolates), and it is exactly
     * the missing samples this pins down.
     */
    private fun abruptPointerJumpsStillResolve(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "tab drag survives pointer jumps across and off the screen",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)

                val jumps =
                    listOf(
                        Offset(-50_000f, -50_000f),
                        Offset(strip.left + JUMP_INSET_PX, strip.center.y),
                        Offset(200_000f, 200_000f),
                        Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX),
                        Offset(Float.NaN, Float.NaN),
                    )
                for (jump in jumps) {
                    session.update(jump)
                    settle(JUMP_SETTLE_MILLIS)
                    val ghost = requireNotNull(workspace.dragGhost) { "the ghost was lost at $jump" }
                    check(ghost.screenRectPx.width > 0f && ghost.screenRectPx.height > 0f) {
                        "the ghost has no size after jumping to $jump: ${ghost.screenRectPx}"
                    }
                    val bounds = requireNotNull(first.outerBoundsPx()) { "the source window was lost at $jump" }
                    check(bounds[2] > 0 && bounds[3] > 0) { "the source window has no size after $jump" }
                }
                // The garbage sample left the last real one standing.
                val expected = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
                check(requireNotNull(workspace.dragGhost).screenRectPx.contains(expected)) {
                    "the ghost moved to the unusable sample"
                }
                check(workspace.dropPreview == null) { "empty space must preview no insertion" }

                session.end(expected)
                awaitUntil("torn off after the jumps") {
                    workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
                check(workspace.draggedTab == null && workspace.dragGhost == null) {
                    "drag feedback outlived the jumps"
                }
            },
        )
    }

    /** The same gesture with a real mouse, flicked: as few samples as the OS will deliver. */
    private fun robotFlickTearsOffTheTab(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "tab flicked out of the strip with a real mouse tears off",
            skip = {
                workspaceSkipReason() ?: HeadfulRobot.unavailableReason?.let { "no input injection: $it" }
            },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val dropOut = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)

                val flicked =
                    robotPressAndDrag(grab, dropOut, first.scaleFactor, steps = FLICK_STEPS, stepDelayMillis = 0)
                if (flicked == null) {
                    System.err.println("[tab-flick] robot became unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                awaitUntil("the flick started a drag") { workspace.draggedTab?.id == fixture.tabId("Beta") }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }

                awaitUntil("the flicked tab landed in its own window") {
                    workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(fixture.tabId("Beta"))
                }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * A backing-scale change with the window's frame in points untouched — the
     * one transition a single display can produce ([MacDisplayModeTool]), and
     * the one that catches strip geometry cached in physical pixels.
     *
     * Two things must hold afterwards: the strip is hit-tested at the new
     * scale, and a tear-off produces a window of the same *logical* size as
     * the one it came from.
     */
    private fun backingScaleChangeKeepsDropsHonest(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "tab workspace survives a backing-scale change and still drops where the pointer is",
            timeoutMillis = SCALE_TIMEOUT_MILLIS,
            skip = {
                workspaceSkipReason()
                    ?: if (!isMac) {
                        "needs a display whose backing scale can be flipped (macOS)"
                    } else {
                        null
                            ?: MacDisplayModeTool.unavailableReason()
                    }
            },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val baseScale = first.scaleFactor
                val fromMode = if (baseScale >= HIDPI_SCALE) "2x" else "1x"
                val toMode = if (baseScale >= HIDPI_SCALE) "1x" else "2x"
                val expectedScale = if (baseScale >= HIDPI_SCALE) baseScale / 2f else baseScale * 2f
                val stripBefore = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
                val logicalWidthBefore = stripBefore.width / baseScale
                System.err.println("[tab-scale] baseline scale=$baseScale strip=$stripBefore")

                try {
                    System.err.println("[tab-scale] setmode $toMode -> ${MacDisplayModeTool.run(toMode)}")
                    awaitUntil("the tab window reports the new backing scale ($expectedScale)") {
                        abs(first.scaleFactor - expectedScale) < SCALE_TOLERANCE
                    }
                    settle(SETTLE_AFTER_SCALE_MILLIS)
                    awaitUntil("the strip republished its geometry at the new scale") {
                        val strip =
                            fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta")))
                                ?: return@awaitUntil false
                        abs(strip.width / expectedScale - logicalWidthBefore) <= LOGICAL_TOLERANCE_DP
                    }

                    // ── the strip is still hit-tested where it is drawn ──
                    val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
                    val betaCenter = requireNotNull(fixture.tabCenterPx("Beta"))
                    check(strip.contains(betaCenter)) {
                        "the tab's own slot fell outside its strip after the scale change: $betaCenter in $strip"
                    }
                    val target = requireNotNull(workspace.dropTargetAt(betaCenter))
                    check(target.group === fixture.groupOf("Beta")) {
                        "a point on the strip no longer resolves to its window after the scale change"
                    }

                    // ── and a tear-off lands under the pointer, at the right size ──
                    val dropOut = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
                    val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), betaCenter))
                    session.update(betaCenter)
                    session.update(dropOut)
                    session.end(dropOut)
                    awaitUntil("torn off after the scale change") {
                        workspace.groups.size == 2 && fixture.groupOf("Beta")?.ids == listOf(beta)
                    }
                    val torn = requireNotNull(fixture.groupOf("Beta"))
                    awaitUntil("the torn-off window is mapped") {
                        (torn.window?.outerBoundsPx()?.get(2) ?: 0L) > 0L
                    }
                    settle(SETTLE_AFTER_MAP_MILLIS)
                    val tornWindow = requireNotNull(torn.window)
                    val tornBounds = requireNotNull(tornWindow.outerBoundsPx())
                    val sourceBounds = requireNotNull(first.outerBoundsPx())
                    // Same logical size as the window it came from, whatever the
                    // scale of the display it ended up on.
                    val tornLogicalW = tornBounds[2] / tornWindow.scaleFactor
                    val sourceLogicalW = sourceBounds[2] / first.scaleFactor
                    check(abs(tornLogicalW - sourceLogicalW) <= LOGICAL_TOLERANCE_DP) {
                        "torn-off window is ${tornLogicalW}dp wide, source is ${sourceLogicalW}dp " +
                            "(scale ${tornWindow.scaleFactor} vs ${first.scaleFactor})"
                    }
                    check(tornBounds[2] > 0 && tornBounds[3] > 0) { "the torn-off window has no size" }
                } finally {
                    System.err.println("[tab-scale] restoring $fromMode -> ${MacDisplayModeTool.run(fromMode)}")
                    awaitUntil("back at the original scale ($baseScale)") {
                        abs(first.scaleFactor - baseScale) < SCALE_TOLERANCE
                    }
                    settle(SETTLE_AFTER_SCALE_MILLIS)
                }
            },
        )
    }

    /**
     * A minimized window keeps its frame on record but shows nothing, so a
     * drop over where it used to be must not land in it — that would move the
     * tab into a window the user cannot see.
     */
    private fun minimizedWindowIsNoDropTarget(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab workspace never drops into a minimized window",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val gamma = fixture.tabId("Gamma")

                // Gamma into a window of its own, which then gets minimized.
                val torn = requireNotNull(workspace.tearOff(gamma, tearOffRectPx(first), first.scaleFactor))
                val tornWindow = awaitMappedStrip(fixture, torn)
                val stripOnScreen = requireNotNull(fixture.stripRectPx(torn))
                val onTheStrip = stripOnScreen.center
                check(workspace.dropTargetAt(onTheStrip)?.group === torn) {
                    "the torn-off strip is not a drop target to begin with"
                }

                var minimized = false
                tornWindow.onMinimizedChanged { min -> minimized = min }
                tornWindow.setMinimized(true)
                awaitUntil("the torn-off window reports minimized") { minimized && tornWindow.isMinimized }
                settle()

                check(workspace.dropTargetAt(onTheStrip) == null) {
                    "a minimized window is still offering a drop target"
                }
                // And the gesture behaves: dropping Beta there tears it off
                // rather than merging it into the invisible window.
                val beta = fixture.tabId("Beta")
                val betaGrab = requireNotNull(fixture.tabCenterPx("Beta"))
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), betaGrab))
                session.update(betaGrab)
                session.update(onTheStrip)
                check(workspace.dropPreview == null) { "the minimized window previewed a drop" }
                session.end(onTheStrip)
                awaitUntil("Beta got a window of its own instead") {
                    workspace.groups.size == 3 && fixture.groupOf("Beta")?.ids == listOf(beta)
                }
                check(torn.ids == listOf(gamma)) { "the minimized window took the tab anyway: ${torn.ids}" }

                // Restored, it is a target again.
                tornWindow.setMinimized(false)
                tornWindow.focus()
                awaitUntil("the window reports restored") { !minimized && !tornWindow.isMinimized }
                settle(SETTLE_AFTER_MAP_MILLIS)
                awaitUntil("its strip takes drops again") {
                    val strip = fixture.stripRectPx(torn) ?: return@awaitUntil false
                    workspace.dropTargetAt(strip.center)?.group === torn
                }
            },
        )
    }

    /**
     * A maximized window: its strip covers the top of the screen, which is
     * where drops must land, and a tab pulled out of it has to get an ordinary
     * window rather than inherit the maximized frame.
     */
    private fun maximizedWindowTakesAndGivesTabs(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "tab workspace drops into a maximized window and tears back out of it",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val alpha = fixture.tabId("Alpha")

                // Beta out first, so there are two windows to work with.
                val torn = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, torn)

                // ── maximize the first window ──
                val before = requireNotNull(first.outerBoundsPx())
                first.setMaximized(true)
                awaitUntil("the first window grew") {
                    val now = first.outerBoundsPx() ?: return@awaitUntil false
                    now[2] > before[2] && now[3] >= before[3]
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                awaitUntil("its strip republished at the maximized size") {
                    val strip =
                        fixture.stripRectPx(requireNotNull(fixture.groupOf("Alpha")))
                            ?: return@awaitUntil false
                    val now = requireNotNull(first.outerBoundsPx())
                    strip.width > before[2] && strip.left >= now[0] - 1f
                }

                // ── drop Beta into the maximized strip ──
                val maximizedGroup = requireNotNull(fixture.groupOf("Alpha"))
                val maximizedStrip = requireNotNull(fixture.stripRectPx(maximizedGroup))
                val betaGrab = requireNotNull(fixture.tabCenterPx("Beta"))
                val mergeAt = Offset(maximizedStrip.left + MERGE_INSET_PX, maximizedStrip.center.y)
                val tornWindow = requireNotNull(torn.window)
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(tornWindow), betaGrab))
                session.update(betaGrab)
                session.update(mergeAt)
                check(workspace.dropPreview?.group === maximizedGroup) {
                    "the maximized strip did not preview the drop: ${workspace.dropPreview}"
                }
                session.end(mergeAt)
                awaitUntil("both tabs are in the maximized window") {
                    workspace.groups.size == 1 && fixture.groupOf("Beta") === maximizedGroup
                }
                settle()
                check(maximizedGroup.ids.first() == beta) {
                    "dropped at the left of the strip, so it should be first: ${maximizedGroup.ids}"
                }

                // ── and back out: an ordinary window, not a maximized one ──
                val maximizedBounds = requireNotNull(first.outerBoundsPx())
                val stripNow = requireNotNull(fixture.stripRectPx(maximizedGroup))
                val alphaGrab = requireNotNull(fixture.tabCenterPx("Alpha"))
                val dropOut = Offset(stripNow.center.x, stripNow.top + stripNow.height + TAB_DROP_FAR_PX)
                val outSession = requireNotNull(workspace.beginDrag(alpha, stripOrigin(first), alphaGrab))
                outSession.update(alphaGrab)
                outSession.update(dropOut)
                outSession.end(dropOut)
                awaitUntil("Alpha is in a window of its own") {
                    workspace.groups.size == 2 && fixture.groupOf("Alpha")?.ids == listOf(alpha)
                }
                val second = awaitMappedStrip(fixture, requireNotNull(fixture.groupOf("Alpha")))
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(!second.isMaximized) { "the torn-off window came out maximized" }
                val newBounds = requireNotNull(second.outerBoundsPx())
                check(newBounds[2] < maximizedBounds[2]) {
                    "the torn-off window is as wide as the maximized one it came from: " +
                        "${newBounds[2]} vs ${maximizedBounds[2]}"
                }
                first.setMaximized(false)
                awaitUntil("the first window was restored") {
                    val now = first.outerBoundsPx() ?: return@awaitUntil false
                    now[2] < maximizedBounds[2]
                }
            },
        )
    }

    /**
     * Gestures that end badly. A drag whose window is resized under it has its
     * pointer input re-keyed, so neither the release nor the cancel branch of
     * the handle is reached — without the cleanup the preview and the ghost
     * would stay on screen for the rest of the session. A superseded drag must
     * go inert instead of fighting the live one.
     */
    private fun interruptedAndSupersededDragsLeaveNoFeedback(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab drags that are interrupted or superseded leave no preview behind",
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
                val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))

                // ── 1. interrupted by a resize: dropped on the floor ──
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val interrupted = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                interrupted.update(grab)
                interrupted.update(Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX))
                check(workspace.draggedTab?.id == beta) { "the drag must be published while it runs" }
                first.setInnerSize(RESIZED_W_DP, RESIZED_H_DP)
                awaitUntil("the window resized under the drag") {
                    val now = first.outerBoundsPx() ?: return@awaitUntil false
                    abs(now[2] - RESIZED_W_DP * first.scaleFactor) <= RESIZE_TOLERANCE_PX
                }
                // What the cancelled pointer-input coroutine does, and all it does.
                interrupted.cancel()
                settle()
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "an interrupted drag left feedback on screen"
                }
                check(workspace.groups.size == 1) { "an interrupted drag moved a tab" }
                check(fixture.groupOf("Beta")?.ids?.contains(beta) == true) { "Beta left its window" }

                // ── 2. superseded: the first session goes inert ──
                val stripNow = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Beta"))))
                val betaGrab = requireNotNull(fixture.tabCenterPx("Beta"))
                val gammaGrab = requireNotNull(fixture.tabCenterPx("Gamma"))
                val outside = Offset(stripNow.center.x, stripNow.bottom + TAB_DROP_FAR_PX)
                val superseded = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), betaGrab))
                superseded.update(betaGrab)
                superseded.update(outside)
                val live = requireNotNull(workspace.beginDrag(gamma, stripOrigin(first), gammaGrab))
                live.update(gammaGrab)
                check(workspace.draggedTab?.id == gamma) { "the new drag must take over" }

                superseded.update(outside)
                superseded.end(outside)
                check(workspace.groups.size == 1) { "the superseded drag tore a tab off" }
                check(workspace.draggedTab?.id == gamma) { "the superseded drag cleared the live one" }

                live.update(outside)
                live.end(outside)
                awaitUntil("only the surviving drag moved its tab") {
                    workspace.groups.size == 2 && fixture.groupOf("Gamma")?.ids == listOf(gamma)
                }
                check(fixture.groupOf("Beta")?.ids?.contains(beta) == true) { "Beta moved after all" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * The window a tab is being dragged out of, destroyed mid-gesture: the app
     * closed it, or the user did. The release must not resurrect it, move a tab
     * that no longer exists, or leave the ghost behind.
     */
    private fun sourceWindowClosingMidDragStaysSane(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab drag whose window closes mid-gesture leaves the workspace consistent",
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

                // Beta and Gamma into a second window, so closing it destroys a
                // real window with a drag in flight.
                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, second)
                workspace.move(gamma, second)
                awaitUntil("the second window holds both") { second.ids.size == 2 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val secondWindow = requireNotNull(second.window)
                var destroyed = false
                secondWindow.onDestroyed { destroyed = true }

                val grab = requireNotNull(fixture.tabCenterPx("Gamma"))
                val strip = requireNotNull(fixture.stripRectPx(second))
                val away = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
                val session = requireNotNull(workspace.beginDrag(gamma, stripOrigin(secondWindow), grab))
                session.update(grab)
                session.update(away)
                check(workspace.dragGhost != null) { "the tear-out must be previewed" }

                // The app closes the window under the gesture.
                second.ids.toList().forEach(workspace::close)
                awaitUntil("the second window was destroyed mid-drag") { destroyed }
                settle()

                session.end(away)
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(workspace.tab(gamma) == null && workspace.tab(beta) == null) {
                    "closed tabs came back: ${workspace.tabs.map { it.id }}"
                }
                check(workspace.groups.size == 1) { "the release resurrected a window: ${workspace.groups.size}" }
                check(fixture.groupOf("Alpha")?.ids == listOf(fixture.tabId("Alpha"))) {
                    "the surviving window lost its tab: ${fixture.groupOf("Alpha")?.ids}"
                }
                check(workspace.draggedTab == null && workspace.dragGhost == null) {
                    "a drag over a closing window left feedback behind"
                }
                check(fixture.composedBodies.value == 1) {
                    "one body should be composing, got ${fixture.composedBodies.value}"
                }
            },
        )
    }

    /** Waits until [group]'s window is mapped with a laid-out strip, and returns it. */
    private suspend fun TaoWindowTestScope.awaitMappedStrip(
        fixture: TabWorkspaceFixture,
        group: TabWindowGroup,
    ): TaoWindow {
        awaitUntil("the group's window is mapped with a real size") {
            val rect = group.window?.outerBoundsPx() ?: return@awaitUntil false
            rect[2] > 0 && rect[3] > 0
        }
        awaitUntil("its strip published its geometry and slots") {
            fixture.stripRectPx(group) != null && group.slotsInWindowPx.size >= group.ids.size
        }
        settle(SETTLE_AFTER_MAP_MILLIS)
        return requireNotNull(group.window)
    }

    private const val JUMP_INSET_PX = 20f
    private const val MERGE_INSET_PX = 12f
    private const val JUMP_SETTLE_MILLIS = 60L
    private const val SETTLE_AFTER_SCALE_MILLIS = 600L
    private const val SCALE_TIMEOUT_MILLIS = 90_000L
    private const val HIDPI_SCALE = 1.5f
    private const val SCALE_TOLERANCE = 0.05f

    /** A logical size compared across a scale change: dp rounding on both sides. */
    private const val LOGICAL_TOLERANCE_DP = 12f
    private const val RESIZED_W_DP = 620.0
    private const val RESIZED_H_DP = 430.0
    private const val RESIZE_TOLERANCE_PX = 48L

    /** A flick: as few samples as the OS will deliver. */
    private const val FLICK_STEPS = 3
}
