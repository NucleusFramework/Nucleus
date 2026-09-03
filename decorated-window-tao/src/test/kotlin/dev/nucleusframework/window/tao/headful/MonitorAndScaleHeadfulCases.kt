package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TaoMonitor
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Monitors and scale, on real windows.
 *
 * Two coordinate spaces run through every one of these APIs: windows are
 * *placed* in logical pixels and *measured* in physical ones, and the ratio
 * between them belongs to a monitor rather than to the application. Every
 * mix-up in that conversion looks correct at 100% and is wrong by a factor of
 * two on a HiDPI desktop — or wrong only on the second display, which is worse,
 * because it looks like a rendering glitch rather than a bug.
 *
 *  1. **enumeration** — what a window says about the monitor it is on has to
 *     agree with what the monitor says about the window, and stay true while
 *     windows open and close;
 *  2. **scale** — the size a window is asked for in dp is the size it gets in
 *     px, and everything the workspaces hit-test with is in the same space;
 *  3. **hops** — a window moved from one display to another, repeatedly and
 *     fast, with its satellites and its strips following it there.
 *
 * The hop cases need a second display, so they report a skip on a
 * single-monitor machine rather than pretending. The scale cases run
 * everywhere, and are the ones that catch a logical/physical mix-up on the
 * HiDPI desktop most developers are actually using.
 */
internal object MonitorAndScaleHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            everyMonitorReportsACoherentFrame(),
            aWindowResolvesToTheMonitorThatContainsIt(),
            enumerationSurvivesAWindowStorm(),
            aRequestedSizeInDpArrivesAsPixelsAtTheMonitorScale(),
            stripSlotsAreInTheSameSpaceAsTheHitTest(),
            theDockZoneIsScaledWithTheDisplay(),
            aTearOffRectInPixelsBecomesAWindowOfTheRightLogicalSize(),
            theDragGhostIsMeasuredInTheSameSpaceAsThePointer(),
            aSatelliteIsKeptInsideTheWorkArea(),
            aWindowPlacedFarOffEveryMonitorStillResolvesOne(),
            aWindowHoppedBetweenMonitorsReportsEachOne(),
            rapidHopsBetweenMonitorsConvergeOnTheLast(),
            aSatelliteFollowsItsOwnerToAnotherMonitor(),
            aStripStillTakesDropsAfterItsWindowChangesMonitor(),
        )

    // ── 1. enumeration ───────────────────────────────────────────────────

    /**
     * The frames the platform reports have to make sense before anything can be
     * anchored against them: a work area inside its monitor, a positive scale,
     * a unique id per monitor and exactly one primary.
     */
    private fun everyMonitorReportsACoherentFrame(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "monitors every monitor reports a coherent frame",
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle()
                val monitors = TaoMonitors.all(window)
                check(monitors.isNotEmpty()) { "no monitor was reported at all" }
                check(monitors.map { it.id }.toSet().size == monitors.size) {
                    "duplicate monitor ids: ${monitors.map { it.id }}"
                }
                check(monitors.count { it.isPrimary } == 1) {
                    "${monitors.count { it.isPrimary }} primary monitors"
                }
                for (monitor in monitors) {
                    check(monitor.boundsPx.width > 0 && monitor.boundsPx.height > 0) {
                        "${monitor.name} has no size: ${monitor.boundsPx}"
                    }
                    check(monitor.scaleFactor > 0f) { "${monitor.name} reports scale ${monitor.scaleFactor}" }
                    val work = monitor.workAreaPx
                    check(work.width in 1..monitor.boundsPx.width && work.height in 1..monitor.boundsPx.height) {
                        "${monitor.name}'s work area $work is not inside its bounds ${monitor.boundsPx}"
                    }
                    check(
                        work.left >= monitor.boundsPx.left && work.top >= monitor.boundsPx.top,
                    ) { "${monitor.name}'s work area starts outside its bounds" }
                    check(TaoMonitors.byId(monitor.id, window)?.id == monitor.id) {
                        "${monitor.name} cannot be looked up by its own id"
                    }
                }
            },
        )

    /**
     * The two directions have to agree: the monitor a window resolves to is
     * one that actually contains it. A window is placed in logical pixels and
     * a monitor is measured in physical ones, so this is the smallest possible
     * test of that conversion.
     */
    private fun aWindowResolvesToTheMonitorThatContainsIt(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "monitors a window resolves to a monitor that contains it",
            skip = ::workspaceSkipReason,
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val rect = requireNotNull(bounds())
                val centre =
                    Offset(rect[0] + rect[RECT_W] / 2f, rect[1] + rect[RECT_H] / 2f)
                val resolved = TaoMonitors.forWindow(window)
                check(resolved.containsPx(centre.x.roundToInt(), centre.y.roundToInt())) {
                    "the window's centre $centre is not on ${resolved.name} ${resolved.boundsPx}"
                }
                check(abs(window.scaleFactor - resolved.scaleFactor) < SCALE_TOLERANCE) {
                    "the window reports scale ${window.scaleFactor}, its monitor ${resolved.scaleFactor}"
                }
            },
        )

    /**
     * Enumeration must not depend on what the application happens to have open:
     * opening and closing windows is not a display change, and a list that
     * shifts under one would move every anchored satellite with it.
     */
    private fun enumerationSurvivesAWindowStorm(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "monitors enumeration is unchanged by a storm of windows opening and closing",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSlots(fixture, *titles.toTypedArray())
                val before = TaoMonitors.all(window).map { it.id to it.boundsPx }

                repeat(STORM_ROUNDS) { round ->
                    val title = titles[1 + round % (titles.size - 1)]
                    val id = fixture.tabId(title)
                    val from = fixture.groupOf(title)?.window ?: first
                    fixture.workspace.tearOff(id, tearOffRectPx(from), from.scaleFactor)
                    fixture.workspace.move(id, requireNotNull(fixture.groupOf("Alpha")))
                }
                awaitUntil("the storm settled") { fixture.workspace.groups.size == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val after = TaoMonitors.all(window).map { it.id to it.boundsPx }
                check(after == before) { "the monitor list changed under a window storm: $before → $after" }
            },
        )
    }

    // ── 2. scale ─────────────────────────────────────────────────────────

    /**
     * The conversion every window API rests on: a size asked for in dp arrives
     * as that many dp worth of physical pixels. On a 200% desktop a factor-of-
     * two mix-up is invisible in the code and unmistakable on screen.
     */
    private fun aRequestedSizeInDpArrivesAsPixelsAtTheMonitorScale(): TaoWindowTestCase {
        val scene = mutableStateOf(IntSize.Zero)
        return TaoWindowTestCase(
            name = "monitors a size requested in dp arrives as pixels at the monitor's scale",
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            paintDefaultBackground = false,
            content = {
                val container = LocalWindowInfo.current.containerSize
                SideEffect { scene.value = container }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("the scene has a size") { scene.value.width > 0 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val scale = window.scaleFactor
                check(scale > 0f) { "the window reports scale $scale" }
                for (wDp in listOf(REQUEST_A_DP, REQUEST_B_DP)) {
                    window.setInnerSize(wDp, REQUEST_H_DP)
                    // The *scene* is the inner size in physical pixels, which
                    // is what `setInnerSize` asks for. The outer frame carries
                    // the chrome and, on a CSD desktop, a shadow margin the WM
                    // owns — measuring it would measure the decoration.
                    awaitUntil("the scene is ${wDp}dp wide at scale $scale") {
                        abs(scene.value.width - (wDp * scale).toInt()) <= SIZE_TOLERANCE_PX
                    }
                }
            },
        )
    }

    /**
     * The strip publishes its slots in physical window pixels and the workspace
     * hit-tests drops in physical screen pixels. If either side used logical
     * ones, every drop on a HiDPI display would resolve half a strip away — so
     * each tab's own centre has to resolve to its own index.
     */
    private fun stripSlotsAreInTheSameSpaceAsTheHitTest(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "monitors strip slots are hit-tested in the space they are published in",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabWindows(fixture, *titles.toTypedArray())
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val scale = tabWindow.scaleFactor
                val strip = requireNotNull(fixture.stripRectPx(group))

                // The strip spans the window it is in, in the same units.
                val outer = requireNotNull(tabWindow.outerBoundsPx())
                check(strip.width <= outer[RECT_W] + STRIP_SLOP_PX) {
                    "the strip (${strip.width}px) is wider than its window (${outer[RECT_W]}px) at scale $scale"
                }
                for ((index, id) in group.ids.withIndex()) {
                    val title = titles.first { fixture.tabId(it) == id }
                    val centre = requireNotNull(fixture.tabCenterPx(title)) { "$title has no slot" }
                    val entry = requireNotNull(fixture.workspace.tab(id))
                    val resolved =
                        requireNotNull(fixture.workspace.dropTargetAt(centre, exclude = entry)) {
                            "$title's own centre resolves to no strip at scale $scale"
                        }
                    check(resolved.group === group && resolved.index == index) {
                        "$title sits at $index but resolves to ${resolved.index} at scale $scale"
                    }
                }
            },
        )
    }

    /**
     * The dock zone is a dp band, so its width in pixels has to follow the
     * display. A fixed pixel band is half as deep as it should be at 200% —
     * and on a mixed-DPI desktop it is right on one display and wrong on the
     * other.
     */
    private fun theDockZoneIsScaledWithTheDisplay(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "monitors the dock zone band is scaled with the display",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                awaitFloating(fixture)
                val workspace = fixture.workspace
                awaitUntil("the layout published its geometry") {
                    workspace.dockHostGeometry(window)?.layoutScreenRectPx() != null
                }
                val layout = requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx())
                val scale = window.scaleFactor
                val bandPx = SatelliteWorkspace.DockZoneWidth.value * scale

                // Just inside the band on the right edge is a zone…
                val inside = Offset(layout.right - bandPx / 2f, layout.center.y)
                check(workspace.dockTargetAt(inside)?.side == DockSide.Right) {
                    "a point ${bandPx / 2f}px inside the right edge is not the right zone at scale $scale"
                }
                // …and well past it is content, not a zone.
                val outside = Offset(layout.right - bandPx * BEYOND_BAND, layout.center.y)
                check(workspace.dockTargetAt(outside) == null) {
                    "a point ${bandPx * BEYOND_BAND}px inside the right edge is still a zone at scale $scale"
                }
            },
        )
    }

    /**
     * `tearOff` takes a rect in physical pixels and the scale it was measured
     * at, because the window it creates is placed in logical ones. Getting that
     * conversion wrong gives a window half or twice the size the user dragged.
     */
    private fun aTearOffRectInPixelsBecomesAWindowOfTheRightLogicalSize(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "monitors a tear-off rect in pixels becomes a window of the right logical size",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val scale = first.scaleFactor
                val source = requireNotNull(first.outerBoundsPx())
                val torn =
                    requireNotNull(
                        fixture.workspace.tearOff(fixture.tabId("Beta"), tearOffRectPx(first), scale),
                    )
                val tornWindow = awaitMappedStrip(fixture, torn)
                settle(SETTLE_AFTER_MAP_MILLIS)
                val rect = requireNotNull(tornWindow.outerBoundsPx())

                val sourceLogicalW = source[RECT_W] / scale
                val tornLogicalW = rect[RECT_W] / tornWindow.scaleFactor
                check(abs(tornLogicalW - sourceLogicalW) <= LOGICAL_TOLERANCE_DP) {
                    "torn-off window is ${tornLogicalW}dp wide, the source is ${sourceLogicalW}dp " +
                        "(scales ${tornWindow.scaleFactor} vs $scale)"
                }
            },
        )
    }

    /**
     * The ghost follows the pointer, and both are physical screen pixels. A
     * ghost sized or placed in logical ones drifts away from the cursor by the
     * scale factor — which on a 200% display means it is nowhere near the
     * pointer by the time it crosses the screen.
     */
    private fun theDragGhostIsMeasuredInTheSameSpaceAsThePointer(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "monitors the drag ghost is measured in the same space as the pointer",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, *titles.toTypedArray())
                val group = requireNotNull(fixture.groupOf("Beta"))
                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val away = requireNotNull(fixture.farFromStripPx(group))
                val session =
                    requireNotNull(fixture.workspace.beginDrag(fixture.tabId("Beta"), stripOrigin(first), grab))
                session.update(grab)
                session.update(away)
                settle()

                val ghost = requireNotNull(fixture.workspace.dragGhost) { "no ghost while dragging out" }
                check(abs(ghost.scaleFactor - first.scaleFactor) < SCALE_TOLERANCE) {
                    "the ghost reports scale ${ghost.scaleFactor}, the window ${first.scaleFactor}"
                }
                check(ghost.screenRectPx.contains(away)) {
                    "the ghost ${ghost.screenRectPx} does not cover the pointer at $away"
                }
                val slot = requireNotNull(fixture.tabRectPx("Beta"))
                check(abs(ghost.screenRectPx.width - slot.width) <= GHOST_SIZE_TOLERANCE_PX) {
                    "the ghost is ${ghost.screenRectPx.width}px wide, the tab ${slot.width}px"
                }
                session.cancel()
            },
        )
    }

    /**
     * A satellite anchored past the edge of the display: the positioner is
     * asked to keep it inside the work area, and the work area is a monitor
     * fact in physical pixels. A conversion slip here parks the palette
     * off-screen, where the user cannot reach it at all.
     */
    private fun aSatelliteIsKeptInsideTheWorkArea(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "monitors a satellite anchored past the edge is slid back into the work area",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val satellite = awaitFloating(fixture)
                val monitor = TaoMonitors.forWindow(window)
                val scale = window.scaleFactor.toDouble()

                // The owner pushed against the right edge of the work area, so
                // the satellite's anchor lands beyond it.
                val edgeX = (monitor.workAreaPx.right - EDGE_MARGIN_PX) / scale
                window.setOuterPosition(edgeX, monitor.workAreaPx.top / scale + EDGE_MARGIN_PX)
                awaitUntil("the owner moved to the edge") {
                    val rect = bounds() ?: return@awaitUntil false
                    rect[0] > monitor.workAreaPx.right - monitor.boundsPx.width / 2
                }
                // Re-anchor with a rule that is allowed to slide it back on.
                val entry = requireNotNull(fixture.workspace.satellite(SATELLITE_ID))
                entry.windowState.positioner =
                    WindowPositioner(
                        parentAnchor = WindowAnchor.Right,
                        childAnchor = WindowAnchor.Left,
                        offset = DpOffset(GAP_DP.dp, 0.dp),
                        constraintAdjustment = WindowConstraintAdjustment.Slide,
                    )
                entry.windowState.reanchor()

                awaitUntil("the satellite is inside the work area") {
                    val rect = satellite.outerBoundsPx() ?: return@awaitUntil false
                    rect[0] + rect[RECT_W] <= monitor.workAreaPx.right + WORK_AREA_SLOP_PX &&
                        rect[0] >= monitor.workAreaPx.left - WORK_AREA_SLOP_PX
                }
            },
        )
    }

    /**
     * A window dropped far outside every display — a restored layout from a
     * monitor that is no longer plugged in. Resolving a monitor for it has to
     * answer *something* usable rather than fail, or every anchor computed
     * from it is null and the palettes never appear.
     */
    private fun aWindowPlacedFarOffEveryMonitorStillResolvesOne(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "monitors a window placed far off every display still resolves one",
            skip = ::workspaceSkipReason,
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val monitors = TaoMonitors.all(window)
                val farRight = monitors.maxOf { it.boundsPx.right } + OFF_SCREEN_PX
                val scale = window.scaleFactor.toDouble()

                window.setOuterPosition(farRight / scale, OFF_SCREEN_PX / scale)
                settle(SETTLE_AFTER_MAP_MILLIS)
                val resolved = TaoMonitors.forWindow(window)
                check(resolved.boundsPx.width > 0) { "resolved a monitor with no bounds for an off-screen window" }
                check(resolved.scaleFactor > 0f) { "resolved a monitor with no scale" }
                check(TaoMonitors.all(window).any { it.id == resolved.id }) {
                    "resolved a monitor that is not in the list"
                }
                check(bounds() != null) { "the window was lost off-screen" }
            },
        )

    // ── 3. hops between displays ─────────────────────────────────────────

    /** A window moved onto each display in turn reports the one it is on. */
    private fun aWindowHoppedBetweenMonitorsReportsEachOne(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "monitors a window hopped between displays reports each one",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::twoMonitorsSkipReason,
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                for (monitor in TaoMonitors.all(window)) {
                    moveOnto(window, monitor)
                    awaitUntil("the window reports ${monitor.name}") {
                        TaoMonitors.forWindow(window).id == monitor.id
                    }
                    settle(SETTLE_AFTER_MAP_MILLIS)
                    check(abs(window.scaleFactor - monitor.scaleFactor) < SCALE_TOLERANCE) {
                        "on ${monitor.name} the window reports scale ${window.scaleFactor}, " +
                            "the monitor ${monitor.scaleFactor}"
                    }
                }
            },
        )

    /**
     * Hops fired faster than the platform answers. Each one may change the
     * backing scale, which rebuilds the surface — so this is where a window
     * ends up reporting one display while drawing at another's scale.
     */
    private fun rapidHopsBetweenMonitorsConvergeOnTheLast(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "monitors rapid hops between displays converge on the last one",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::twoMonitorsSkipReason,
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val monitors = TaoMonitors.all(window)
                repeat(HOP_ROUNDS) { round -> moveOnto(window, monitors[round % monitors.size]) }
                val last = monitors[(HOP_ROUNDS - 1) % monitors.size]
                moveOnto(window, last)

                awaitUntil("the window settled on ${last.name}") {
                    TaoMonitors.forWindow(window).id == last.id
                }
                awaitUntil("and reports that display's scale") {
                    abs(window.scaleFactor - last.scaleFactor) < SCALE_TOLERANCE
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val rect = requireNotNull(bounds())
                check(rect[RECT_W] > 0 && rect[RECT_H] > 0) { "the window lost its size hopping" }
            },
        )

    /** The satellite goes where its owner goes, including onto another display. */
    private fun aSatelliteFollowsItsOwnerToAnotherMonitor(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "monitors a satellite follows its owner onto another display",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::twoMonitorsSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val satellite = awaitFloating(fixture)
                val monitors = TaoMonitors.all(window)
                val target = monitors.first { it.id != TaoMonitors.forWindow(window).id }

                moveOnto(window, target)
                awaitUntil("the owner is on ${target.name}") { TaoMonitors.forWindow(window).id == target.id }
                awaitUntil("the satellite came along") {
                    val rect = satellite.outerBoundsPx() ?: return@awaitUntil false
                    target.containsPx(
                        (rect[0] + rect[RECT_W] / 2).toInt(),
                        (rect[1] + rect[RECT_H] / 2).toInt(),
                    )
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(requireNotNull(satellite.outerBoundsPx())[RECT_W] > 0L) {
                    "the satellite lost its size on the way over"
                }
            },
        )
    }

    /**
     * A strip whose window changed display: the geometry it published was in
     * the old display's pixels, and a drop resolved against it would land in
     * the wrong place — or nowhere.
     */
    private fun aStripStillTakesDropsAfterItsWindowChangesMonitor(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta")
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "monitors a strip still takes drops after its window changes display",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::twoMonitorsSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabWindows(fixture, *titles.toTypedArray())
                val group = requireNotNull(fixture.groupOf("Alpha"))
                val target = TaoMonitors.all(tabWindow).first { it.id != TaoMonitors.forWindow(tabWindow).id }

                moveOnto(tabWindow, target)
                awaitUntil("the tab window is on ${target.name}") {
                    TaoMonitors.forWindow(tabWindow).id == target.id
                }
                awaitUntil("its strip republished on the new display") {
                    val strip = fixture.stripRectPx(group) ?: return@awaitUntil false
                    target.containsPx(strip.center.x.roundToInt(), strip.center.y.roundToInt())
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val strip = requireNotNull(fixture.stripRectPx(group))
                check(fixture.workspace.dropTargetAt(strip.center)?.group === group) {
                    "the strip does not answer a drop after the hop"
                }
                for ((index, id) in group.ids.withIndex()) {
                    val title = titles.first { fixture.tabId(it) == id }
                    val centre = requireNotNull(fixture.tabCenterPx(title))
                    val entry = requireNotNull(fixture.workspace.tab(id))
                    check(fixture.workspace.dropTargetAt(centre, exclude = entry)?.index == index) {
                        "$title resolves to the wrong index after the hop"
                    }
                }
            },
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Puts [window] near the top-left of [monitor]'s work area, in logical pixels. */
    private fun moveOnto(
        window: TaoWindow,
        monitor: TaoMonitor,
    ) {
        val scale = (monitor.scaleFactor.takeIf { it > 0f } ?: 1f).toDouble()
        window.setOuterPosition(
            (monitor.workAreaPx.left + EDGE_MARGIN_PX) / scale,
            (monitor.workAreaPx.top + EDGE_MARGIN_PX) / scale,
        )
    }

    /** Why the hop cases cannot run here, or `null` when a second display exists. */
    private fun twoMonitorsSkipReason(): String? =
        workspaceSkipReason() ?: if (TaoMonitors.all().size < 2) "needs a second display" else null

    private const val CASE_W_DP = 420
    private const val CASE_H_DP = 300
    private const val REQUEST_A_DP = 380.0
    private const val REQUEST_B_DP = 520.0
    private const val REQUEST_H_DP = 300.0
    private const val EDGE_MARGIN_PX = 40
    private const val OFF_SCREEN_PX = 4_000
    private const val HOP_ROUNDS = 12
    private const val STORM_ROUNDS = 6

    /** Where the "outside the band" probe sits, as a multiple of the band's own depth. */
    private const val BEYOND_BAND = 3f

    private const val SCALE_TOLERANCE = 0.01f
    private const val SIZE_TOLERANCE_PX = 12
    private const val STRIP_SLOP_PX = 8f
    private const val GHOST_SIZE_TOLERANCE_PX = 24f
    private const val LOGICAL_TOLERANCE_DP = 12f
    private const val WORK_AREA_SLOP_PX = 48L
    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
