package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TabWindowGroup
import kotlin.math.abs

/**
 * How the tab workspace behaves under motion, on real windows: what the
 * pointer does between the grab and the drop.
 *
 *  1. **a real mouse** — driven by the AWT Robot, in
 *     [TabWorkspaceMouseHeadfulCases];
 *  2. **teleports** — samples with nothing in between, which is what a fast
 *     drag actually delivers once the OS has coalesced it, and what a synthetic
 *     replay delivers by construction;
 *  3. **the strip edge** — a pointer that crosses in and out of a strip dozens
 *     of times must leave the preview in step with the last sample, not one
 *     behind;
 *  4. **a window that moves under the gesture** — the target resized or moved
 *     mid-drag, so the strip the drop resolves against is not where it was at
 *     the grab;
 *  5. **the single-tab window drag** — the window itself follows the pointer,
 *     its own strip travels under it, and only *another* window's strip may
 *     answer the drop.
 *
 * Native Wayland is skipped along with the rest of the tab suite.
 */
internal object TabWorkspaceMotionHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            teleportsBetweenTwoStripsResolveEveryTime(),
            zigZagAcrossTheStripEdgeKeepsThePreviewInStep(),
            offScreenExcursionsKeepTheGestureSane(),
            singleTabWindowFollowsThePointerAndMerges(),
            targetWindowMovingMidDragMovesTheDropTarget(),
            targetWindowResizingMidDragMovesTheDropTarget(),
            backToBackDragsLeaveOneConsistentState(),
        )

    /**
     * Two strips, and a pointer that jumps between them with nothing in
     * between — no sample on the desktop, none on the frame, none on the way.
     * Each jump has to resolve on its own rather than depend on having been
     * walked into.
     */
    private fun teleportsBetweenTwoStripsResolveEveryTime(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab motion teleports between two strips resolve every time",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val gamma = fixture.tabId("Gamma")
                val beta = fixture.tabId("Beta")

                val second = requireNotNull(workspace.tearOff(gamma, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, second)
                val home = requireNotNull(fixture.groupOf("Alpha"))

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val onSecond = requireNotNull(fixture.stripPointPx(second, STRIP_HEAD_FRACTION))
                val onHome = requireNotNull(fixture.stripPointPx(home, STRIP_MID_FRACTION))
                val nowhere = requireNotNull(fixture.farFromStripPx(home))
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)

                repeat(TELEPORT_ROUNDS) { round ->
                    session.update(onSecond)
                    settle(JUMP_SETTLE_MILLIS)
                    check(workspace.dropPreview?.group === second) {
                        "round $round: the other strip did not answer a teleport: ${workspace.dropPreview}"
                    }
                    session.update(nowhere)
                    settle(JUMP_SETTLE_MILLIS)
                    check(workspace.dropPreview == null) { "round $round: empty space previewed a drop" }
                    session.update(onHome)
                    settle(JUMP_SETTLE_MILLIS)
                    check(workspace.dropPreview?.group === home) {
                        "round $round: its own strip did not answer a teleport: ${workspace.dropPreview}"
                    }
                    val ghost = requireNotNull(workspace.dragGhost) { "round $round: the ghost was lost" }
                    check(ghost.screenRectPx.width > 0f) { "round $round: the ghost has no size" }
                }

                // The last sample is the one that decides.
                session.update(onSecond)
                session.end(onSecond)
                awaitUntil("the tab landed where the last teleport pointed") {
                    fixture.groupOf("Beta") === second && second.ids.contains(beta)
                }
                check(workspace.groups.size == 2) { "the teleports changed the window count" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * The strip edge, crossed dozens of times: a pointer sliding along the
     * boundary between "insert here" and "tear off". Every sample has to move
     * the preview with it — one stale frame and the release lands somewhere the
     * user was not pointing.
     */
    private fun zigZagAcrossTheStripEdgeKeepsThePreviewInStep(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab motion a zig-zag across the strip edge keeps the preview in step",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val home = requireNotNull(fixture.groupOf("Beta"))
                val strip = requireNotNull(fixture.stripRectPx(home))

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val inside = Offset(strip.left + strip.width * STRIP_MID_FRACTION, strip.center.y)
                val outside = Offset(inside.x, strip.bottom + EDGE_EXCURSION_PX)
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)

                repeat(ZIGZAG_ROUNDS) { round ->
                    session.update(outside)
                    check(workspace.dropPreview == null) {
                        "round $round: outside the strip still previewed ${workspace.dropPreview}"
                    }
                    session.update(inside)
                    check(workspace.dropPreview?.group === home) {
                        "round $round: back inside the strip previewed ${workspace.dropPreview}"
                    }
                }
                // No settle in the loop on purpose: the preview is snapshot
                // state written by the session, so it must be right as soon as
                // the sample is taken, not a frame later.
                session.end(inside)
                awaitUntil("the tab stayed in its window") {
                    workspace.groups.size == 1 && fixture.groupOf("Beta") === home
                }
                check(workspace.dragGhost == null) { "the ghost survived the zig-zag" }
            },
        )
    }

    /**
     * Excursions no real screen can hold: coordinates far outside every
     * display, non-finite samples, and the same sample repeated. None of them
     * may reach window geometry, and the gesture has to stay usable
     * afterwards.
     */
    private fun offScreenExcursionsKeepTheGestureSane(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab motion off-screen and non-finite samples never reach the windows",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val home = requireNotNull(fixture.groupOf("Beta"))
                val strip = requireNotNull(fixture.stripRectPx(home))
                val boundsBefore = requireNotNull(first.outerBoundsPx())

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)

                val onTheStrip = Offset(strip.left + strip.width * STRIP_MID_FRACTION, strip.center.y)
                session.update(onTheStrip)
                val ghostAtStrip = requireNotNull(workspace.dragGhost).screenRectPx

                val garbage =
                    listOf(
                        Offset(Float.NaN, onTheStrip.y),
                        Offset(onTheStrip.x, Float.NaN),
                        Offset(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
                        Offset(Float.NaN, Float.NaN),
                    )
                for (sample in garbage) {
                    session.update(sample)
                    val ghost = requireNotNull(workspace.dragGhost) { "the ghost was lost at $sample" }
                    check(ghost.screenRectPx == ghostAtStrip) {
                        "an unusable sample ($sample) moved the ghost to ${ghost.screenRectPx}"
                    }
                    check(workspace.dropPreview?.group === home) { "an unusable sample dropped the preview" }
                }

                // Far outside every display, then the same sample twice.
                val faraway = Offset(-1_000_000f, 1_000_000f)
                session.update(faraway)
                session.update(faraway)
                settle(JUMP_SETTLE_MILLIS)
                val ghostFaraway = requireNotNull(workspace.dragGhost)
                check(ghostFaraway.screenRectPx.width > 0f && ghostFaraway.screenRectPx.height > 0f) {
                    "the ghost lost its size off-screen: ${ghostFaraway.screenRectPx}"
                }
                check(workspace.dropPreview == null) { "a point off every display previewed a drop" }
                val boundsDuring = requireNotNull(first.outerBoundsPx())
                check(boundsDuring[2] == boundsBefore[2] && boundsDuring[3] == boundsBefore[3]) {
                    "the source window was resized by the excursion"
                }

                // And the gesture still works: back on the strip, release.
                session.update(onTheStrip)
                session.end(onTheStrip)
                awaitUntil("the tab is still in its window") {
                    workspace.groups.size == 1 && fixture.groupOf("Beta") === home
                }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * The Chrome gesture: the only tab of a window, dragged. The window itself
     * follows the pointer, so its own strip travels under it the whole time and
     * is also the focused one — the drop has to look *past* it and answer with
     * the strip underneath, or a merge can never resolve.
     */
    private fun singleTabWindowFollowsThePointerAndMerges(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab motion dragging a single-tab window follows the pointer and still merges",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")
                val home = requireNotNull(fixture.groupOf("Alpha"))

                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val secondWindow = awaitMappedStrip(fixture, second)
                secondWindow.focus()
                awaitUntil("the dragged window is the focused one") { secondWindow.isFocused }

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val before = requireNotNull(secondWindow.outerBoundsPx())
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(secondWindow), grab))
                session.update(grab)
                check(workspace.dragGhost == null) {
                    "the only tab of a window must move the window, not raise a ghost"
                }

                // A step away first: the window follows the pointer.
                val step = grab + Offset(WINDOW_DRAG_STEP_PX, WINDOW_DRAG_STEP_PX)
                session.update(step)
                awaitUntil("the window followed the pointer") {
                    val now = secondWindow.outerBoundsPx() ?: return@awaitUntil false
                    abs(now[0] - (before[0] + WINDOW_DRAG_STEP_PX.toLong())) <= WINDOW_FOLLOW_TOLERANCE_PX &&
                        abs(now[1] - (before[1] + WINDOW_DRAG_STEP_PX.toLong())) <= WINDOW_FOLLOW_TOLERANCE_PX
                }

                // Then over the other window's strip: its own strip is under the
                // pointer too, and must not be the one that answers.
                val target = requireNotNull(fixture.stripPointPx(home, STRIP_HEAD_FRACTION))
                session.update(target)
                settle(JUMP_SETTLE_MILLIS)
                val preview = requireNotNull(workspace.dropPreview) { "no merge target while over the other strip" }
                check(preview.group === home) { "the dragged window answered its own drop: ${preview.group.id}" }
                check(preview.index == 0) { "dropped at the head of the strip, previewed index ${preview.index}" }

                session.end(target)
                awaitUntil("the windows merged and Beta composes in the first window") {
                    workspace.groups.size == 1 &&
                        fixture.groupOf("Beta") === home &&
                        fixture.windowOf("Beta") === first
                }
                check(home.ids.first() == beta) { "dropped at the head, landed at ${home.ids}" }
                check(workspace.draggedTab == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * The target window moved while a tab is held over it — a follower window,
     * a workspace switch, the app repositioning things. The drop resolves
     * against where the strip *is*, so the old position must go cold and the
     * new one must answer.
     */
    private fun targetWindowMovingMidDragMovesTheDropTarget(): TaoWindowTestCase =
        movingTargetCase(
            name = "tab motion a target window moved mid-drag takes its drop target with it",
        ) { window ->
            val bounds = requireNotNull(window.outerBoundsPx())
            val scale = window.scaleFactor.toDouble()
            window.setOuterPosition(
                bounds[0] / scale + TARGET_MOVE_DP,
                bounds[1] / scale + TARGET_MOVE_DP,
            )
            awaitUntil("the target window moved") {
                val now = window.outerBoundsPx() ?: return@awaitUntil false
                now[0] != bounds[0] || now[1] != bounds[1]
            }
        }

    /**
     * The same, resized: a strip that got wider or narrower under the pointer
     * has to be hit-tested at its new width.
     */
    private fun targetWindowResizingMidDragMovesTheDropTarget(): TaoWindowTestCase =
        movingTargetCase(
            name = "tab motion a target window resized mid-drag republishes its drop target",
        ) { window ->
            window.setInnerSize(TARGET_RESIZED_W_DP, TARGET_RESIZED_H_DP)
            awaitUntil("the target window resized") {
                val now = window.outerBoundsPx() ?: return@awaitUntil false
                abs(now[2] - TARGET_RESIZED_W_DP * window.scaleFactor) <= RESIZE_TOLERANCE_PX
            }
        }

    /**
     * Shared shape of the two "the target moves under the gesture" cases: a
     * tab held over another window's strip, that window disturbed by
     * [disturb], and then the drop.
     */
    private fun movingTargetCase(
        name: String,
        disturb: suspend TaoWindowTestScope.(window: dev.nucleusframework.window.tao.TaoWindow) -> Unit,
    ): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = name,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val gamma = fixture.tabId("Gamma")
                val beta = fixture.tabId("Beta")

                // Gamma into the window that will be disturbed.
                val target = requireNotNull(workspace.tearOff(gamma, tearOffRectPx(first), first.scaleFactor))
                val targetWindow = awaitMappedStrip(fixture, target)

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val pointBefore = requireNotNull(fixture.stripPointPx(target, STRIP_HEAD_FRACTION))
                val session = requireNotNull(workspace.beginDrag(beta, stripOrigin(first), grab))
                session.update(grab)
                session.update(pointBefore)
                check(workspace.dropPreview?.group === target) { "the target strip did not answer before the move" }

                val stripRectBefore = requireNotNull(fixture.stripRectPx(target))
                val windowBefore = requireNotNull(targetWindow.outerBoundsPx())
                disturb(targetWindow)
                // The published geometry reads the window's frame live, so the
                // strip travels with it: same delta in position, same delta in
                // width. Comparing deltas rather than absolutes is what makes
                // this independent of where the platform controls sit.
                awaitUntil("the strip travelled with its window") {
                    val stripNow = fixture.stripRectPx(target) ?: return@awaitUntil false
                    val windowNow = targetWindow.outerBoundsPx() ?: return@awaitUntil false
                    val movedX = (windowNow[0] - windowBefore[0]).toFloat()
                    val grewW = (windowNow[2] - windowBefore[2]).toFloat()
                    (abs(movedX) > 1f || abs(grewW) > 1f) &&
                        abs((stripNow.left - stripRectBefore.left) - movedX) <= STRIP_FOLLOW_TOLERANCE_PX &&
                        abs((stripNow.width - stripRectBefore.width) - grewW) <= STRIP_FOLLOW_TOLERANCE_PX
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                // The point that used to be on the strip is stale; the one that
                // is on it now answers.
                session.update(pointBefore)
                val stalePreview = workspace.dropPreview
                check(stalePreview?.group !== target || stripStillCovers(fixture, target, pointBefore)) {
                    "the old strip position still answers after the window moved"
                }
                val stripNow = requireNotNull(fixture.stripPointPx(target, STRIP_HEAD_FRACTION))
                session.update(stripNow)
                settle(JUMP_SETTLE_MILLIS)
                check(workspace.dropPreview?.group === target) {
                    "the moved strip does not answer at its new position: ${workspace.dropPreview}"
                }

                session.end(stripNow)
                awaitUntil("the tab merged into the disturbed window and composes there") {
                    fixture.groupOf("Beta") === target &&
                        target.ids.contains(beta) &&
                        fixture.windowOf("Beta") === targetWindow
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.size == 2) { "the window count changed: ${workspace.groups.size}" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * Drag after drag with nothing in between: no settle, no frame to recover
     * in. Whatever the intermediate states are, the workspace has to come out
     * of it with every tab in exactly one window and no feedback on screen.
     */
    private fun backToBackDragsLeaveOneConsistentState(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab motion drags back to back leave one consistent state",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                // Two windows that both keep a tab of their own, so neither can
                // disappear under the churn and both strips stay published.
                val second =
                    requireNotNull(
                        workspace.tearOff(fixture.tabId("Gamma"), tearOffRectPx(first), first.scaleFactor),
                    )
                awaitMappedStrip(fixture, second)
                val home = requireNotNull(fixture.groupOf("Alpha"))

                // Beta thrown from one strip to the other, over and over, with
                // no settling in between: every gesture starts before the
                // previous one has been through a frame.
                var started = 0
                repeat(BACK_TO_BACK_DRAGS) { round ->
                    val group = fixture.groupOf("Beta") ?: return@repeat
                    val window = group.window ?: return@repeat
                    val target = if (group === home) second else home
                    val grab = fixture.stripPointPx(group, STRIP_MID_FRACTION) ?: return@repeat
                    val drop = fixture.stripPointPx(target, STRIP_HEAD_FRACTION) ?: return@repeat
                    val session = workspace.beginDrag(beta, stripOrigin(window), grab) ?: return@repeat
                    started++
                    session.update(grab)
                    session.update(drop)
                    check(workspace.dropPreview?.group === target) {
                        "round $round: the target strip did not answer mid-storm: ${workspace.dropPreview}"
                    }
                    session.end(drop)
                    check(fixture.groupOf("Beta") === target) {
                        "round $round: the tab did not land where it was dropped"
                    }
                }
                check(started >= BACK_TO_BACK_DRAGS) { "only $started of $BACK_TO_BACK_DRAGS gestures ran" }

                awaitUntil("the workspace settled with every tab placed") {
                    workspace.tabs.size == 3 && workspace.tabs.all { it.group != null }
                }
                awaitUntil("both windows are still mapped") {
                    workspace.groups.size == 2 &&
                        workspace.groups.all { it.window?.hasRealFramePx() == true }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.sumOf { it.ids.size } == 3) {
                    "tabs went missing or got duplicated: ${workspace.groups.map { it.ids }}"
                }
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "the churn left drag feedback behind"
                }
                awaitUntil("one body per window composes") {
                    fixture.composedBodies.value == workspace.groups.size
                }
            },
        )
    }

    private fun stripStillCovers(
        fixture: TabWorkspaceFixture,
        group: TabWindowGroup,
        point: Offset,
    ): Boolean = fixture.stripRectPx(group)?.contains(point) == true

    private fun robotSkipReason(): String? = HeadfulRobot.unavailableReason?.let { "no input injection: $it" }

    private const val EDGE_EXCURSION_PX = 60f
    private const val ZIGZAG_ROUNDS = 40
    private const val TELEPORT_ROUNDS = 6
    private const val BACK_TO_BACK_DRAGS = 12
    private const val WINDOW_DRAG_STEP_PX = 40f
    private const val WINDOW_FOLLOW_TOLERANCE_PX = 24L
    private const val TARGET_MOVE_DP = 90.0
    private const val TARGET_RESIZED_W_DP = 640.0
    private const val TARGET_RESIZED_H_DP = 440.0

    /** Both sides come from the same live geometry: rounding only. */
    private const val STRIP_FOLLOW_TOLERANCE_PX = 8f
}
