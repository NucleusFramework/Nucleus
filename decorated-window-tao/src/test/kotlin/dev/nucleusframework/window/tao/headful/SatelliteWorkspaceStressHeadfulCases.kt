package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockTarget
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.SatelliteDragOrigin
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.TaoApplication
import kotlin.math.abs

/**
 * The satellite workspace under abuse: everything a user or a synthetic event
 * source can do that a well-behaved gesture never does.
 *
 *  1. a pointer that teleports across and off the screen, and hands over
 *     unusable coordinates;
 *  2. a gesture interrupted rather than finished — the host resized under it,
 *     the session abandoned — which must leave no preview behind;
 *  3. dock / undock churn, which creates and destroys a real window each time;
 *  4. a real mouse flick, where the OS coalesces the path into a few enormous
 *     deltas;
 *  5. overlapping drags, a dock host closing mid-gesture, and the workspace
 *     hidden while a drag is live.
 */
internal object SatelliteWorkspaceStressHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            abruptDragJumpsStillResolve(),
            interruptedDragLeavesNoFeedback(),
            dockChurnLeaksNoWindows(),
            robotFlickDocksTheSatellite(),
            overlappingDragsAndClosuresStaySane(),
        )

    /**
     * A pointer that teleports: no intermediate samples, jumps far off-screen
     * and back, crossing zones without ever hovering the space between them.
     * A synthetic replay does this, and so does a fast flick on a real mouse —
     * the OS coalesces motion, and what arrives is one enormous delta.
     *
     * Driven through the drag session rather than the Robot: the Robot cannot
     * express "no samples in between" (the OS interpolates), and it is exactly
     * the missing samples that this pins down.
     */
    private fun abruptDragJumpsStillResolve(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace drag survives pointer jumps across and off the screen",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val floating = awaitFloating(fixture)
                val workspace = fixture.workspace
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                val layout = requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx())
                val outer = requireNotNull(floating.outerBoundsPx())
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * window.scaleFactor)
                val session =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(floating), grab),
                    )

                // Teleports, in one sample each: far negative, far positive,
                // then straight onto opposite zones with nothing in between.
                val jumps =
                    listOf(
                        Offset(-50_000f, -50_000f),
                        Offset(layout.left + DROP_INSET_PX, layout.center.y),
                        Offset(200_000f, 200_000f),
                        Offset(layout.right - DROP_INSET_PX, layout.center.y),
                        Offset(Float.NaN, Float.NaN),
                    )
                for (jump in jumps) {
                    session.update(jump)
                    settle(JUMP_SETTLE_MILLIS)
                    val bounds = requireNotNull(floating.outerBoundsPx()) { "the satellite window was lost at $jump" }
                    check(bounds[2] > 0 && bounds[3] > 0) { "satellite has no size after jumping to $jump" }
                }
                // The garbage sample left the last real one standing.
                check(workspace.dockPreview == DockTarget(window, DockSide.Right)) {
                    "the right zone must still be previewed, got ${workspace.dockPreview}"
                }

                session.end(Offset(layout.right - DROP_INSET_PX, layout.center.y))
                awaitUntil("docked right after the jumps") {
                    (entry.placement as? SatellitePlacement.Docked)?.side == DockSide.Right
                }
                awaitUntil("panel composed") { fixture.panelHost.value === window }
                check(workspace.draggedSatellite == null && workspace.dragGhost == null) {
                    "drag feedback outlived the jumps"
                }
            },
        )
    }

    /**
     * A gesture interrupted instead of finished. Resizing the host window
     * re-keys the pointer input the drag runs in, so neither the release nor
     * the cancel branch of the handle is reached — without the cleanup the
     * zone hints and the ghost would stay on screen for the rest of the
     * session. Here the interruption is made explicit by dropping the session
     * on the floor after a resize, exactly as the cancelled coroutine does.
     */
    private fun interruptedDragLeavesNoFeedback(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace drag interrupted by a resize leaves no preview behind",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val floating = awaitFloating(fixture)
                val workspace = fixture.workspace
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                val layout = requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx())
                val outer = requireNotNull(floating.outerBoundsPx())
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * window.scaleFactor)
                val session =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(floating), grab),
                    )
                session.update(Offset(layout.right - DROP_INSET_PX, layout.center.y))
                check(workspace.draggedSatellite === entry) { "the drag must be published while it runs" }

                // The window resizes under the gesture, then the gesture is
                // abandoned — the pointer input that owned it is gone.
                window.setInnerSize(RESIZED_W_DP, RESIZED_H_DP)
                awaitUntil("window resized") {
                    val now = bounds() ?: return@awaitUntil false
                    abs(now[2] - (RESIZED_W_DP * window.scaleFactor).toLong()) <= RESIZE_TOLERANCE_PX
                }
                session.cancel()

                check(workspace.draggedSatellite == null) { "the drag is still published after the interruption" }
                check(workspace.dockPreview == null) { "a dock zone is still highlighted" }
                check(workspace.dragGhost == null) { "the ghost is still on screen" }
                check(!entry.isDocked) { "an interrupted drag must not dock anything" }

                // And the workspace still takes a new drag afterwards.
                val next =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(floating), grab),
                    ) { "the workspace refuses a new drag after an interrupted one" }
                val liveLayout = requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx())
                next.update(Offset(liveLayout.right - DROP_INSET_PX, liveLayout.center.y))
                next.end(Offset(liveLayout.right - DROP_INSET_PX, liveLayout.center.y))
                awaitUntil("the new drag docked the satellite") { entry.isDocked }
            },
        )
    }

    /**
     * Docking and undocking as fast as the event loop allows. Each undock
     * creates a real window and each dock destroys one, so a mistake here
     * leaks native windows or strands the satellite between hosts.
     */
    private fun dockChurnLeaksNoWindows(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace dock and undock churn leaks no windows and keeps the state",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                awaitFloating(fixture)
                val workspace = fixture.workspace
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                requireNotNull(fixture.counter.value).value = SAVED_CLICKS
                settle()
                val baselineWindows = TaoApplication.liveWindowCount()

                val sides = DockSide.entries
                repeat(CHURN_CYCLES) { index ->
                    val side = sides[index % sides.size]
                    workspace.dock(SATELLITE_ID, side)
                    awaitUntil("panel docked on $side") {
                        (entry.placement as? SatellitePlacement.Docked)?.side == side &&
                            fixture.panelHost.value === window
                    }
                    workspace.undock(SATELLITE_ID)
                    awaitUntil("floating again after $side") {
                        !entry.isDocked &&
                            (
                                fixture.floatingWindow.value
                                    ?.outerBoundsPx()
                                    ?.get(2) ?: 0L
                            ) > 0L
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val windowsNow = TaoApplication.liveWindowCount()
                check(windowsNow <= baselineWindows) {
                    "churn leaked windows: $baselineWindows before, $windowsNow after"
                }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "state lost during the churn: ${fixture.counter.value?.value}"
                }
                check(workspace.draggedSatellite == null && workspace.dragGhost == null) {
                    "churn left drag feedback behind"
                }
            },
        )
    }

    /**
     * A real mouse flick: press, three moves issued back to back with no delay
     * at all, release. The OS coalesces them, so what the window sees is two
     * or three enormous deltas rather than a path — the same shape as a user
     * throwing a palette at a screen edge.
     */
    private fun robotFlickDocksTheSatellite(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace satellite flicked into a zone with a real mouse docks there",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val floating = awaitFloating(fixture)
                val workspace = fixture.workspace
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                val layout = requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx())
                val outer = requireNotNull(floating.outerBoundsPx())
                val scale = floating.scaleFactor
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * scale)
                val drop = Offset(layout.left + DROP_INSET_PX, layout.center.y)

                val flicked =
                    robotPressAndDrag(grab, drop, scale, steps = FLICK_STEPS, stepDelayMillis = 0L)
                if (flicked == null) {
                    System.err.println("[workspace-flick] robot unavailable — skipping the real-mouse half")
                    return@TaoWindowTestCase
                }
                awaitUntil("left zone previewed after the flick") {
                    workspace.dockPreview == DockTarget(window, DockSide.Left)
                }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                awaitUntil("docked left by the flick") {
                    (entry.placement as? SatellitePlacement.Docked)?.side == DockSide.Left
                }
                awaitUntil("panel composed after the flick") { fixture.panelHost.value === window }
                check(workspace.draggedSatellite == null && workspace.dragGhost == null) {
                    "the flick left drag feedback behind"
                }
            },
        )
    }

    /**
     * Everything happening at once: two drags in flight over the same
     * workspace, the dock host closing under one of them, and the master
     * visibility flag toggled while a gesture is live. Each of these on its
     * own is an interleaving the drag sessions have to survive; together they
     * are the worst frame this API can be handed.
     */
    private fun overlappingDragsAndClosuresStaySane(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val dialogVisible = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "workspace survives overlapping drags, a closing host and a visibility toggle",
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
                val floating = awaitFloating(fixture)
                val workspace = fixture.workspace
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { workspace.members.size == 2 }
                val layout = requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx())
                val outer = requireNotNull(floating.outerBoundsPx())
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * window.scaleFactor)

                // ── 1. two sessions in flight: the second wins, the first is inert ──
                val first =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(floating), grab),
                    )
                first.update(Offset(layout.left + DROP_INSET_PX, layout.center.y))
                val second =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(floating), grab),
                    )
                second.update(Offset(layout.right - DROP_INSET_PX, layout.center.y))
                first.end(Offset(layout.left + DROP_INSET_PX, layout.center.y))
                check(!entry.isDocked) { "the superseded drag docked the satellite" }
                check(workspace.dockPreview == DockTarget(window, DockSide.Right)) {
                    "the superseded drag stole the live preview: ${workspace.dockPreview}"
                }
                second.end(Offset(layout.right - DROP_INSET_PX, layout.center.y))
                awaitUntil("docked right by the surviving drag") {
                    (entry.placement as? SatellitePlacement.Docked)?.side == DockSide.Right
                }

                // ── 2. dock into the dialog, then drag it while the dialog closes ──
                dialog.focus()
                awaitUntil("dialog is the owner") { workspace.owner === dialog }
                workspace.dock(SATELLITE_ID, DockSide.Bottom, host = dialog)
                awaitUntil("panel hosted by the dialog") { fixture.panelHost.value === dialog }
                settle()
                val panelGrab =
                    requireNotNull(workspace.dockHostGeometry(dialog)?.clientOriginPx()) +
                        requireNotNull(entry.dockedBoundsInWindowPx).topLeft +
                        Offset(GRAB_INSET_PX, GRAB_INSET_PX)
                val duringClose =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.DockedPanel(dialog), panelGrab),
                    )
                // Clear of every layout, so the drop can only mean "tear out".
                val farFromEveryLayout = Offset(layout.right + DROP_FAR_PX, layout.top + DROP_INSET_PX)
                duringClose.update(farFromEveryLayout)
                var dialogDestroyed = false
                dialog.onDestroyed { dialogDestroyed = true }
                dialogVisible.value = false
                awaitUntil("dialog destroyed mid-drag") { dialogDestroyed }
                duringClose.end(farFromEveryLayout)
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.draggedSatellite == null && workspace.dragGhost == null) {
                    "a drag over a closing host left feedback behind"
                }
                check(workspace.owner === window) { "the owner did not fall back to the surviving member" }
                check(!entry.isDocked) { "the tear-out from a closing host did not undock: ${entry.placement}" }

                // ── 3. a gesture live while everything is hidden and shown again ──
                val liveFloating = awaitFloating(fixture)
                val hiddenGrab =
                    requireNotNull(liveFloating.outerBoundsPx()).let { rect ->
                        Offset(rect[0] + rect[2] / 2f, rect[1] + HEADER_GRAB_Y_DP * window.scaleFactor)
                    }
                val duringHide =
                    requireNotNull(
                        workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(liveFloating), hiddenGrab),
                    )
                duringHide.update(hiddenGrab + Offset(DRAG_AWAY_PX, 0f))
                workspace.visible = false
                awaitUntil("satellite left composition") { !fixture.isComposed }
                duringHide.end(hiddenGrab + Offset(DRAG_AWAY_PX, 0f))
                workspace.visible = true
                awaitUntil("satellite composed again") { fixture.isComposed }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.draggedSatellite == null && workspace.dragGhost == null) {
                    "a drag across a visibility toggle left feedback behind"
                }
                check(workspace.dockPreview == null) { "a dock zone is still highlighted" }
            },
        )
    }
}
