package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockTarget
import dev.nucleusframework.window.tao.DockTransferTarget
import dev.nucleusframework.window.tao.SatelliteCaptionStripWidth
import dev.nucleusframework.window.tao.SatelliteDragKind
import dev.nucleusframework.window.tao.SatellitePlacement
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
 *  6. tabs the same way: no record tears off, a record merges back;
 *  9. the strip's own gesture: a tab carried along its strip reorders with no
 *     screen coordinate at all, and leaving the strip hands the drag to the
 *     platform's session, which is what lets another window preview the drop;
 *  8. chrome is told the compositor places its window, the title bar reserves
 *     the caption strip for the compositor's move and the app's slot is
 *     composed inside it, and a satellite drag reports itself as carried by
 *     the platform session with no ghost window;
 *  7. a drop over a stack resolves the rank under the pointer from window
 *     coordinates — its own rank being no move — and the record reorders the
 *     layers without rebuilding one.
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
            aTransferDropResolvesARankAndReorders(),
            chromeIsToldTheCompositorPlacesTheWindow(),
            theStripReordersAndDefersItsDrops(),
        )

    /**
     * The gesture a compositor-placed window *can* carry, on real windows.
     *
     * Reordering asks nothing of the screen: the strip is handed the travel in
     * its own coordinates and answers with the place the tab would take. A
     * release clear of the strip cannot be hit-tested — every toplevel reports
     * a fake origin here — so the drop is deferred, and the window the
     * compositor hands the pointer to next is the one that resolves it: into
     * its strip, or into a window of its own. Nothing claims it and the tab is
     * torn off, which is what a release over the desktop has always done.
     */
    private fun theStripReordersAndDefersItsDrops(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "native Wayland: the strip reorders without the screen and lets go when the tab leaves it",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindowsInWindow(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val group = requireNotNull(fixture.groupOf("Alpha"))
                check(!first.canPlaceOnScreen) { "case premise: the window must be compositor-placed" }
                check(workspace.beginDrag(fixture.tabId("Gamma"), stripOrigin(first), Offset.Zero) == null) {
                    "the screen-space drag started on a window the app cannot place"
                }

                // ── a reorder, with nothing but window coordinates ──
                val motion = requireNotNull(workspace.motionOf(group)) { "the strip published no motion" }
                val gamma = fixture.tabId("Gamma")
                val beta = fixture.tabId("Beta")
                val alpha = fixture.tabId("Alpha")
                val slot = requireNotNull(motion.slotOf(gamma))
                val driver = SyntheticPointerDriver(first)
                driver.moveTo(slot.center)
                driver.press()
                driver.moveTo(slot.center + Offset(-SLOP_PX, 0f))
                driver.moveTo(slot.center + Offset(-slot.width * CARRY_FRACTION, 0f))
                awaitUntil("the strip shows the tab landing before its neighbour") {
                    workspace.dropPreview?.let { it.group === group && it.index == 1 } == true
                }
                check(workspace.dragGhost == null) { "a ghost window on a compositor-placed surface" }
                driver.release()
                awaitUntil("the reorder is applied once the tab has slid home") {
                    group.ids == listOf(alpha, gamma, beta)
                }

                // ── leaving the strip hands the gesture to the platform ──
                //
                // The strip lets go the moment the pointer is out of it: from
                // there the drag is the platform's own session, which is what
                // gives every *other* window the pointer in its coordinates —
                // the only way a compositor-placed client can preview a drop
                // it does not own. The session itself is the compositor's to
                // start, so what is asserted here is the strip's half: it
                // stops carrying, and the tab is where it was.
                val gammaSlot = requireNotNull(motion.slotOf(gamma))
                driver.moveTo(gammaSlot.center)
                driver.press()
                driver.moveTo(gammaSlot.center + Offset(0f, SLOP_PX))
                driver.moveTo(gammaSlot.center + Offset(0f, OUT_OF_STRIP_PX))
                awaitUntil("the strip let go of the tab it was carrying") { motion.held == null }
                driver.release()
                // Released with nothing under it, the platform session leaves
                // the tab a window of its own — the tear-out a void release has
                // always been, now reached through the session that also gives
                // another window the drop preview.
                awaitUntil("the tab left the strip it was dragged out of") {
                    !group.ids.contains(gamma) && workspace.tab(gamma) != null
                }
                check(workspace.dragGhost == null) { "a ghost window on a compositor-placed surface" }
                check(group.ids == listOf(alpha, beta)) { "the tabs left behind are not in order: ${group.ids}" }
            },
        )
    }

    /**
     * The other half of the X11 case in `DockLayoutHeadfulCases`: here the
     * compositor places the window, so [SatelliteScope.isCompositorPlaced] is
     * `true` for the floating palette, its title bar reserves
     * [SatelliteCaptionStripWidth] for the compositor's move with the app's
     * `floatingCaption` composed inside it, and a satellite drag is a
     * [SatelliteDragKind.Transfer] that publishes no ghost window.
     *
     * The docked panel reads its host, which is compositor-placed too.
     */
    private fun chromeIsToldTheCompositorPlacesTheWindow(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, extent = 120.dp)),
                        DockPanelSpec(
                            NOTES,
                            SatellitePlacement.Floating(
                                positioner = workspaceRightEdgePositioner(),
                                size = workspaceSatelliteSize(),
                            ),
                        ),
                    ),
            )
        return TaoWindowTestCase(
            name = "native Wayland: chrome is told the compositor places the window, and the caption strip is reserved",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodiesInWindow(fixture, TREE)
                awaitUntil("the palette floats") {
                    fixture.floatingWindows.value[NOTES]?.hasRealFramePx() == true
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val floating = requireNotNull(fixture.floatingWindows.value[NOTES])
                check(!floating.canPlaceOnScreen) { "case premise: the palette must be compositor-placed" }

                awaitUntil("the palette's chrome learned how its window is placed") {
                    fixture.compositorPlacedFloating.value[NOTES] == true
                }
                check(fixture.compositorPlacedDocked.value[TREE] == true) {
                    "the panel was told its host places itself: ${fixture.compositorPlacedDocked.value}"
                }
                awaitUntil("the caption strip is composed") { fixture.captionBounds.value[NOTES] != null }
                val caption = requireNotNull(fixture.captionBounds.value[NOTES])
                val expectedPx = SatelliteCaptionStripWidth.value * floating.scaleFactor
                check(abs(caption.width - expectedPx) <= LAYOUT_TOLERANCE_PX) {
                    "the reserved strip is ${caption.width} px, SatelliteCaptionStripWidth is $expectedPx"
                }
                check(caption.height > 0f) { "the strip has no height, so nothing can be aimed at it" }

                // The drag says how it is carried, and no ghost window follows.
                check(workspace.dragKind == null) { "a drag is reported before one starts" }
                val session = requireNotNull(workspace.beginTransferDrag(NOTES, floatingOrigin(floating)))
                check(workspace.dragKind == SatelliteDragKind.Transfer) {
                    "the platform session carries it, but the kind is ${workspace.dragKind}"
                }
                check(workspace.dragGhost == null) { "a ghost window followed a transfer drag" }
                check(workspace.draggedSatellite?.id == NOTES) { "the dragged satellite is not published" }
                session.cancel()
                check(workspace.dragKind == null && workspace.publishesNoDragFeedback()) { "feedback left behind" }

                // The screen-space API is still refused here, which is why the
                // split exists in the first place.
                check(workspace.beginDrag(NOTES, floatingOrigin(floating), Offset.Zero) == null) {
                    "a screen drag started on a window the app cannot place"
                }
            },
        )
    }

    private fun aTransferDropResolvesARankAndReorders(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, order = 0, extent = 100.dp)),
                        DockPanelSpec(TOC, SatellitePlacement.Docked(DockSide.Right, order = 1, extent = 120.dp)),
                        DockPanelSpec(NOTES, SatellitePlacement.Docked(DockSide.Right, order = 2, extent = 90.dp)),
                    ),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "native Wayland: a transfer drop over a stack resolves the rank under the pointer and reorders",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodiesInWindow(fixture, TREE, TOC, NOTES)
                val geometry = requireNotNull(workspace.dockHostGeometry(window))
                val layout = geometry.layoutBoundsInWindowPx
                val tree = requireNotNull(fixture.panelBounds.value[TREE])
                val notesBefore = requireNotNull(fixture.panelBounds.value[NOTES])

                val session = requireNotNull(workspace.beginTransferDrag(NOTES, panelOrigin(window)))
                awaitUntil("the layout published its drop zones") { geometry.zoneBoundsInWindowPx.isNotEmpty() }
                val target = DockTransferTarget(workspace, window, geometry)
                // Window coordinates, the only ones an inbound event carries.
                val overTreeOuterHalf = Offset(tree.left + tree.width * OUTER_HALF, layout.center.y)
                check(target.zoneAt(overTreeOuterHalf) == DockTarget(window, DockSide.Right, 0)) {
                    "the outer half of the first layer did not resolve to rank 0: ${target.zoneAt(overTreeOuterHalf)}"
                }
                check(target.zoneAt(notesBefore.center) == DockTarget(window, DockSide.Right, 2)) {
                    "the panel's own area did not resolve to its own rank: ${target.zoneAt(notesBefore.center)}"
                }
                check(
                    target.zoneAt(notesBefore.center) == session.own,
                ) { "its own rank is not what the session calls its own" }
                // Clear of the left strip and short of the layers: content.
                val content = Offset(layout.left + CONTENT_PROBE_DP * window.scaleFactor, layout.center.y)
                check(target.zoneAt(content) == null) { "the content is no zone: ${target.zoneAt(content)}" }

                session.drop = TransferDrop.Dock(requireNotNull(target.zoneAt(overTreeOuterHalf)))
                session.end()
                awaitUntil("the notes are the first rank") {
                    (workspace.satellite(NOTES)?.placement as? SatellitePlacement.Docked)?.order == 0
                }
                awaitDockedBodiesInWindow(fixture, TREE, TOC, NOTES)
                val notes = requireNotNull(fixture.panelBounds.value[NOTES])
                val treeAfter = requireNotNull(fixture.panelBounds.value[TREE])
                check(
                    near(notes.right, layout.right, LAYOUT_TOLERANCE_PX * 2) &&
                        treeAfter.right <= notes.left + LAYOUT_TOLERANCE_PX,
                ) {
                    "the notes are not the outermost layer: notes=$notes tree=$treeAfter"
                }
                check(
                    near(notes.width, notesBefore.width),
                ) { "the notes changed width: ${notesBefore.width} -> ${notes.width}" }
                check(
                    fixture.incarnationsOf(TREE) == 1 &&
                        fixture.incarnationsOf(TOC) == 1 &&
                        fixture.incarnationsOf(NOTES) == 1,
                ) {
                    "a reorder rebuilt a panel: ${fixture.incarnations.value}"
                }
                check(workspace.publishesNoDragFeedback()) { "feedback left behind after the session ended" }
            },
        )
    }

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

    private const val TREE = "tree"
    private const val TOC = "toc"
    private const val NOTES = "notes"

    /** Well inside the outer half of a layer: the rank ahead of it. */
    private const val OUTER_HALF = 0.8f

    /** A point past the left strip and well short of the 310 dp of layers on the right, in a 520 dp layout. */
    private const val CONTENT_PROBE_DP = 100f

    /** Past Compose's touch slop, so the gesture is a drag and not a click. */
    private const val SLOP_PX = 24f

    /** Far enough along the strip for the carried tab's edge to cross its neighbour's centre. */
    private const val CARRY_FRACTION = 0.8f

    /** Below the strip: the window's body, where a released tab is out of the strip's hands. */
    private const val OUT_OF_STRIP_PX = 120f
}
