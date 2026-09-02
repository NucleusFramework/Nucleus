package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockTarget
import dev.nucleusframework.window.tao.TabDropTarget
import dev.nucleusframework.window.tao.TransferDrop
import kotlin.math.abs

/**
 * The native-**Wayland** contract of the two cross-window archetypes.
 *
 * A client has neither its windows' screen position nor a way to move them
 * there, so the gestures ride the platform's drag-and-drop session instead:
 * the source hands the session to the compositor, the window under the pointer
 * resolves the drop in its *own* coordinates and records it on the session,
 * and the source acts on that record when the session ends. These cases pin
 * down that contract on real windows:
 *
 *  1. the screen-space API refuses to start, since starting it would mean
 *     moving windows, and the transfer session starts in its place;
 *  2. a recorded dock zone docks the satellite, `rememberSaveable` state
 *     intact, and the floating window is really destroyed;
 *  3. no record lifts a docked panel back out, and a record naming the side it
 *     already occupies leaves it alone;
 *  4. a dock zone is resolved from a window coordinate — the only space an
 *     inbound drag event speaks — on every side;
 *  5. the ownership half is untouched: a floating satellite still hides while
 *     its owner is maximized, and never publishes an owner offset it cannot
 *     know;
 *  6. tabs the same way: no record tears off, a record merges back.
 *
 * The adversarial half — lifecycle, concurrency, bursts, edge cases — lives in
 * [WaylandWorkspaceStressHeadfulCases]. Skipped everywhere that has
 * client-side placement, where [SatelliteWorkspaceHeadfulCases] covers the
 * pointer path with a real mouse.
 */
internal object WaylandWorkspaceHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            screenApiRefusedTransferSessionStarts(),
            recordedZoneDocksAndNoRecordUndocks(),
            everyZoneResolvesFromAWindowCoordinate(),
            tabTransferDragTearsOffAndMergesBack(),
        )

    private fun screenApiRefusedTransferSessionStarts(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "native Wayland: the screen-space drag is refused and the transfer session starts instead",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val workspace = fixture.workspace
                val floating = awaitFloatingOnWayland(fixture)
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                check(window.isNativeWaylandSurface) { "case premise: the owner must be a native Wayland surface" }
                check(entry.windowState.offsetFromParent == null) {
                    "no owner offset can be known on Wayland, yet one was published: " +
                        "${entry.windowState.offsetFromParent}"
                }

                check(workspace.beginDrag(SATELLITE_ID, floatingOrigin(floating), Offset(PROBE_PX, PROBE_PX)) == null) {
                    "the screen-space drag from a floating window must be refused"
                }
                check(workspace.beginDrag(SATELLITE_ID, panelOrigin(window), Offset(PROBE_PX, PROBE_PX)) == null) {
                    "the screen-space drag from a docked panel must be refused"
                }
                check(workspace.publishesNoDragFeedback()) { "a refused drag must publish nothing" }
                check(workspace.dockTargetAt(Offset(PROBE_PX, PROBE_PX)) == null) {
                    "no dock zone can be hit-tested in screen space without window positions"
                }

                val session =
                    requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating))) {
                        "the transfer drag must start where the screen-space one cannot"
                    }
                check(workspace.draggedSatellite === entry) { "a live transfer drag must publish its satellite" }
                check(session.title == entry.title) { "the drag card must read the satellite's title" }
                val floatingWidthPx = requireNotNull(floating.outerBoundsPx())[RECT_W].toFloat()
                check(abs(session.ghostSizePx.width - floatingWidthPx) <= GHOST_TOLERANCE_PX) {
                    "the card must be as wide as the window it came from: " +
                        "${session.ghostSizePx.width} vs $floatingWidthPx"
                }
                check(session.ghostSizePx.height > 0f) { "the card must have a height" }
                session.cancel()
                check(workspace.publishesNoDragFeedback()) { "a cancelled drag must publish nothing" }
                check(!entry.isDocked) { "a cancelled drag must not change the placement" }
            },
        )
    }

    private fun recordedZoneDocksAndNoRecordUndocks(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "native Wayland: a recorded zone docks the satellite and no record lifts it back out",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val workspace = fixture.workspace
                val floating = awaitFloatingOnWayland(fixture)
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                requireNotNull(fixture.counter.value).value = SAVED_CLICKS
                settle()

                // ── a recorded zone docks it, and the window really goes ──
                var destroyed = false
                floating.onDestroyed { destroyed = true }
                workspace.transferDrop(floatingOrigin(floating), DockTarget(window, DockSide.Right))
                awaitUntil("floating window destroyed after docking") { destroyed }
                awaitPanelIn(fixture, window)
                check(workspace.publishesNoDragFeedback()) { "the finished drag must publish nothing" }
                check(workspace.dockedSide() == DockSide.Right) { "not docked right: ${entry.placement}" }
                val panel = requireNotNull(fixture.panelBoundsPx.value)
                val container = requireNotNull(fixture.hostContentSizePx.value)
                check(abs(panel.right - container.width) <= LAYOUT_TOLERANCE_PX) {
                    "panel does not sit on the right edge: panel=$panel container=$container"
                }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "rememberSaveable state lost when docking: ${fixture.counter.value?.value}"
                }

                // ── the side it already occupies: left alone ──
                val ownSide = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, panelOrigin(window)))
                check(ownSide.own == DockTarget(window, DockSide.Right)) {
                    "a docked panel's drag must know the zone it already occupies: ${ownSide.own}"
                }
                check(abs(ownSide.ghostSizePx.width - panel.width) <= GHOST_TOLERANCE_PX) {
                    "the card must be as wide as the panel: ${ownSide.ghostSizePx.width} vs ${panel.width}"
                }
                ownSide.drop = TransferDrop.Stay
                ownSide.end()
                settle()
                check(workspace.dockedSide() == DockSide.Right) { "a Stay record moved the panel: ${entry.placement}" }

                // ── another side: re-docked, still one panel ──
                workspace.transferDrop(panelOrigin(window), DockTarget(window, DockSide.Bottom))
                awaitUntil("re-docked to the bottom") { workspace.dockedSide() == DockSide.Bottom }
                awaitPanelIn(fixture, window)
                check(fixture.composedHosts.value == 1) { "re-docking left two hosts composing" }

                // ── no record at all: lifted out as a window ──
                workspace.transferDrop(panelOrigin(window), target = null)
                awaitUntil("floating window recreated") {
                    val now = fixture.floatingWindow.value
                    now != null && (now.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(!entry.isDocked && entry.dockHost == null) { "entry still reads as docked after the drop" }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "rememberSaveable state lost when undocking: ${fixture.counter.value?.value}"
                }

                // ── no record while already floating: stays put ──
                val stillFloating = requireNotNull(fixture.floatingWindow.value)
                workspace.transferDrop(floatingOrigin(stillFloating), target = null)
                settle()
                check(!entry.isDocked) { "a dropless release of a floating satellite docked it: ${entry.placement}" }
                check(fixture.floatingWindow.value === stillFloating) { "the floating window was needlessly recreated" }
            },
        )
    }

    private fun everyZoneResolvesFromAWindowCoordinate(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "native Wayland: every dock zone resolves from a window coordinate, and maximize still hides",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val workspace = fixture.workspace
                awaitFloatingOnWayland(fixture)
                val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                awaitUntil("the dock layout published its bounds") {
                    workspace.dockHostGeometry(window)?.layoutBoundsInWindowPx?.isEmpty == false
                }

                // ── the four zones, from window coordinates ──
                for (side in DockSide.entries) {
                    check(workspace.zoneProbe(window, side) == side) {
                        "the strip inside the $side edge did not resolve to $side: " +
                            "got ${workspace.zoneProbe(window, side)}"
                    }
                }
                val geometry = requireNotNull(workspace.dockHostGeometry(window))
                val layout = geometry.layoutBoundsInWindowPx
                check(workspace.zoneProbeAt(window, layout.center) == null) {
                    "the middle of the layout is content, not a zone"
                }
                check(workspace.zoneProbeAt(window, Offset(layout.center.x, layout.top - 1f)) == null) {
                    "a point above the layout — the title bar — is no zone"
                }
                check(workspace.zoneProbeAt(window, Offset(layout.right + 1f, layout.center.y)) == null) {
                    "a point outside the layout is no zone at all"
                }
                // The client origin is unknowable, which is what makes the
                // window-space path the only one available here.
                check(geometry.clientOriginPx() == null) { "a Wayland host must not claim a screen origin" }
                check(geometry.layoutScreenRectPx() == null) { "a Wayland host must not claim a screen rect" }

                // ── ownership is untouched by any of this ──
                window.setMaximized(true)
                awaitUntil("satellite hidden while the owner is maximized") { entry.windowState.isHiddenByParent }
                window.setMaximized(false)
                awaitUntil("satellite back once the owner is restored") { !entry.windowState.isHiddenByParent }
                awaitUntil("restored satellite is mapped with a real size") {
                    val rect = fixture.floatingWindow.value?.outerBoundsPx() ?: return@awaitUntil false
                    rect[RECT_W] > 0 && rect[RECT_H] > 0
                }
                check(entry.windowState.offsetFromParent == null) {
                    "a maximize round trip must not invent an owner offset"
                }
            },
        )
    }

    private fun tabTransferDragTearsOffAndMergesBack(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "native Wayland: a tab transfer drag tears a tab off and merges it back",
            skip = ::waylandSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val workspace = fixture.workspace
                val first = awaitTabWindowsOnWayland(fixture, "Alpha", "Beta")
                val beta = fixture.tabId("Beta")
                requireNotNull(fixture.counters.value[beta]).value = TAB_SAVED_CLICKS
                check(workspace.beginDrag(beta, stripOrigin(first), Offset(PROBE_PX, PROBE_PX)) == null) {
                    "the screen-space tab drag must be refused"
                }

                // ── no record: torn into a window of its own ──
                val tearOff =
                    requireNotNull(workspace.beginTransferDrag(beta, first)) { "the transfer drag must start" }
                check(workspace.draggedTab?.id == beta) { "a live transfer drag must publish its tab" }
                check(tearOff.title == "Beta") { "the drag card must read the tab's title" }
                tearOff.end()
                val torn = awaitTornOff(fixture, first, "Beta")
                check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                    "Beta lost its saveable state when torn off"
                }

                // ── a record: merged back, at the index it names ──
                val tornWindow = requireNotNull(torn.window)
                val merge = requireNotNull(workspace.beginTransferDrag(beta, tornWindow))
                val firstGroup = requireNotNull(fixture.groupOf("Alpha"))
                merge.drop = TabDropTarget(firstGroup, 0)
                merge.end()
                awaitUntil("Beta merged back, first in the strip") {
                    workspace.groups.size == 1 && firstGroup.ids.firstOrNull() == beta
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                    "the finished tab drag left feedback behind"
                }
                check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                    "Beta lost its saveable state when merged back"
                }

                // ── close out: the last tab takes the window with it ──
                var lastDestroyed = false
                requireNotNull(firstGroup.window).onDestroyed { lastDestroyed = true }
                workspace.close(beta)
                awaitUntil("one tab left, still one window") { workspace.tabs.size == 1 && workspace.groups.size == 1 }
                workspace.close(fixture.tabId("Alpha"))
                awaitUntil("the last window was destroyed") { lastDestroyed && workspace.groups.isEmpty() }
                awaitUntil("onLastWindowClosed fired") { fixture.lastWindowClosed.value }
            },
        )
    }

    /** Any finite point: neither the refusal nor a zone probe may depend on where it is. */
    private const val PROBE_PX = 100f
}
