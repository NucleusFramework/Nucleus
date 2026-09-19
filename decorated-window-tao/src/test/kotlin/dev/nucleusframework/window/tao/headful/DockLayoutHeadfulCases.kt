package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DefaultDockSideOrder
import dev.nucleusframework.window.tao.DockPanelHeaderHeight
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockTarget
import dev.nucleusframework.window.tao.SatelliteDragKind
import dev.nucleusframework.window.tao.SatelliteDragOrigin
import dev.nucleusframework.window.tao.SatelliteDragSession
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.hintedSides
import kotlin.math.abs

/**
 * Real-window coverage for the `DockLayout` arrangements: layered sides where
 * every panel is a column of its own width, split sides where panels share a
 * side by weight, the side order that decides who owns the corners, and the
 * right-to-left layout that keeps its sides physical.
 *
 *  1. three panels on a layered right side sit side by side, each at its own
 *     width, with the default header strip sizing itself;
 *  2. a layered panel's splitter, dragged with a real mouse, resizes that
 *     panel alone and the new extent lands in the snapshot;
 *  3. two panels on a split side share it by weight, and the divider between
 *     them moves the weight from one to the other;
 *  4. with the right side first in the order it runs the full height, the
 *     bottom panel stops at it and still runs under the left panel;
 *  5. under a right-to-left direction the left side is the physical left, its
 *     splitter grows it rightwards, and the content and the panels see RTL;
 *  6. no change of the layout — extents, weights, order, side, a restore, a
 *     new side order, a layered toggle, a direction flip — rebuilds a panel
 *     body or the content, and a floating satellite keeps its window through
 *     every restore;
 *  7. a custom 1 dp splitter with a wider grip takes the drag aimed off the line;
 *  8. a floating satellite dropped on a layered side becomes a layer of its
 *     window's width, next to the panel already there;
 *  9. undocking a layer lifts the window off exactly where the layer was;
 * 10. the drop preview follows the palette's own edge, not the pointer;
 * 11. a layer floated and docked again without a rank comes back between the
 *     neighbours it left, on a layered and on a split side alike;
 * 12. a layer dragged by its header over the outer half of the outermost
 *     layer previews the first rank and lands there, nothing rebuilt;
 * 16. chrome is told how its window is placed, no caption strip is reserved
 *     where the app places its own windows, and a drag says how it is carried;
 * 15. a fixed panel is never torn out — no ghost, no window, nothing
 *     rebuilt — nor displaced by a neighbour docking in front of it, while
 *     that neighbour is still torn out by the same gesture;
 * 14. a palette declared for three sides is never offered the fourth: the
 *     top strip is neither hinted nor published, a release there leaves it
 *     floating, and a direct dock on that side is refused;
 * 13. on a split side a panel dropped on its own rank stays, dropped on the
 *     first half of the first panel becomes the first, and the closed one in
 *     the middle keeps its rank.
 *
 * Every drag is a real mouse (AWT Robot) where the host can inject input,
 * else the same change through the workspace — the geometry the layout then
 * shows is asserted either way. Native Wayland is skipped as for every
 * satellite case: no client-side screen placement to aim a pointer with.
 */
@Suppress("LargeClass") // one method per real-window case, by design
internal object DockLayoutHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            layeredPanelsSitSideBySideWithTheirOwnWidths(),
            aLayeredSplitterResizesItsPanelAlone(),
            splitPanelsShareBySideWeightAndTheDividerMovesIt(),
            theOuterSideOwnsTheCorners(),
            rtlKeepsPhysicalSidesAndHandsTheDirectionBack(),
            layoutChangesNeverRebuildAPanelOrTheContent(),
            aOneDpSplitterWithAWiderGripTakesTheDrag(),
            aDropOnALayeredSideAddsALayerOfTheWindowsWidth(),
            undockingALayerLiftsTheWindowOffThePanel(),
            thePaletteEdgeDecidesTheZoneNotThePointer(),
            aPanelDockedAgainReturnsToTheRankItLeft(),
            aLayerDraggedOverTheOutermostOneBecomesTheFirst(),
            aSplitPanelDroppedOnItsStackTakesTheRankUnderThePointer(),
            aPaletteIsNeverOfferedASideItWasNotDeclaredFor(),
            aFixedPanelIsNeverTornOut(),
            chromeIsToldHowTheWindowIsPlaced(),
        )

    // ── 16. what chrome is told about the two gestures ───────────────────

    /**
     * What chrome is told about the two gestures, where the app places its own
     * windows: [SatelliteScope.isCompositorPlaced] is `false` for the panel and
     * for the floating palette alike, the `floatingCaption` slot is not
     * composed at all — the whole bar drags the satellite — and a pointer drag
     * reports itself as [SatelliteDragKind.Window] with a ghost to match.
     *
     * The other half of the contract, on a compositor-placed window, is
     * `WaylandWorkspaceHeadfulCases`.
     */
    private fun chromeIsToldHowTheWindowIsPlaced(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, extent = TREE_W_DP.dp)),
                        DockPanelSpec(
                            INSPECTOR,
                            SatellitePlacement.Floating(
                                positioner = workspaceRightEdgePositioner(),
                                size = workspaceSatelliteSize(),
                            ),
                        ),
                    ),
            )
        return TaoWindowTestCase(
            name = "dock layout chrome is told the window places itself, and no caption strip is reserved",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodies(fixture, TREE)
                awaitUntil("the inspector floats") {
                    fixture.floatingWindows.value[INSPECTOR]?.hasRealFramePx() == true
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val floating = requireNotNull(fixture.floatingWindows.value[INSPECTOR])

                check(window.canPlaceOnScreen) { "the case window should place itself on this leg" }
                check(floating.canPlaceOnScreen) { "the satellite window should place itself on this leg" }
                check(fixture.compositorPlacedDocked.value[TREE] == false) {
                    "the panel was told the compositor places it: ${fixture.compositorPlacedDocked.value}"
                }
                check(fixture.compositorPlacedFloating.value[INSPECTOR] == false) {
                    "the palette was told the compositor places it: ${fixture.compositorPlacedFloating.value}"
                }
                check(fixture.captionBounds.value.isEmpty()) {
                    "a caption strip is reserved where nothing needs one: ${fixture.captionBounds.value}"
                }

                // The drag reports how it is carried, and the ghost matches.
                check(workspace.dragKind == null) { "a drag is reported before one starts" }
                val outer = requireNotNull(floating.outerBoundsPx())
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * floating.scaleFactor)
                val palette =
                    requireNotNull(workspace.beginDrag(INSPECTOR, SatelliteDragOrigin.FloatingWindow(floating), grab))
                check(workspace.dragKind == SatelliteDragKind.Window) {
                    "the palette's own window carries the drag, but the kind is ${workspace.dragKind}"
                }
                palette.cancel()
                check(workspace.dragKind == null) { "the kind outlived the drag" }

                val treeBounds = panel(fixture, TREE)
                val panelGrab =
                    toScreen(
                        fixture,
                        Offset(
                            treeBounds.center.x,
                            treeBounds.top + DockPanelHeaderHeight.value * window.scaleFactor / 2f,
                        ),
                    )
                val panelDrag = beginDockedDrag(workspace, TREE, panelGrab)
                panelDrag.update(panelGrab + Offset(0f, PANEL_DRAG_STEP_PX))
                check(workspace.dragKind == SatelliteDragKind.Window) { "the torn-out panel's ghost is a window" }
                check(workspace.dragGhost?.satellite?.id == TREE) { "no ghost for a window-carried drag" }
                panelDrag.cancel()
                check(workspace.dragGhost == null && workspace.dragKind == null) { "feedback left behind" }
            },
        )
    }

    // ── 15. a fixed panel ────────────────────────────────────────────────

    /**
     * A fixed panel ([floatable] `false`): dragged into the middle of the
     * content and released, it is still the panel it was — no ghost followed
     * the pointer, no window appeared, its subtree was never rebuilt — while
     * the panel next to it, an ordinary one, is torn out by the same gesture.
     */
    private fun aFixedPanelIsNeverTornOut(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(
                            TREE,
                            SatellitePlacement.Docked(DockSide.Right, order = 0, extent = TREE_W_DP.dp),
                            dockSides = setOf(DockSide.Right),
                            floatable = false,
                            reorderable = false,
                        ),
                        DockPanelSpec(TOC, SatellitePlacement.Docked(DockSide.Right, order = 1, extent = TOC_W_DP.dp)),
                    ),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout a fixed panel is never torn out nor displaced, its neighbour still is",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodies(fixture, TREE, TOC)
                val scale = window.scaleFactor
                val layout = awaitDockLayout(workspace, window)
                val client = requireNotNull(workspace.dockHostGeometry(window)?.clientOriginPx())
                val treeBefore = panel(fixture, TREE)
                val tree = requireNotNull(workspace.satellite(TREE))
                check(!tree.isFloatable) { "the fixture did not declare the tree fixed" }

                // Into the middle of the content — a tear-out for any other panel.
                val grab =
                    toScreen(
                        fixture,
                        Offset(treeBefore.center.x, treeBefore.top + DockPanelHeaderHeight.value * scale / 2f),
                    )
                // Deep in the content: clear of the left strip and well clear
                // of the right side's ranks, which reach in behind its layers.
                val middle = Offset(layout.left + CONTENT_AIM_DP * scale, layout.center.y)
                // No wait for zones here: a pinned panel fixed to one side is
                // offered none, which is the first thing to check.
                val session =
                    requireNotNull(workspace.beginDrag(TREE, SatelliteDragOrigin.DockedPanel(window), grab))
                settle()
                check(workspace.dockHostGeometry(window)?.zoneBoundsInWindowPx.isNullOrEmpty()) {
                    "a zone is offered to a panel that can go nowhere: " +
                        "${workspace.dockHostGeometry(window)?.zoneBoundsInWindowPx}"
                }
                session.update(middle)
                check(workspace.dragGhost == null) { "a fixed panel published a tear-out ghost" }
                check(workspace.dockPreview == null) { "the content previewed a zone: ${workspace.dockPreview}" }
                session.end(middle)
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(
                    tree.placement is SatellitePlacement.Docked,
                ) { "the fixed panel left the dock: ${tree.placement}" }
                check(fixture.floatingWindows.value[TREE] == null) { "the fixed panel opened a window of its own" }
                check(fixture.incarnationsOf(TREE) == 1) { "the refused tear-out rebuilt the panel" }
                check(near(panel(fixture, TREE).width, treeBefore.width)) { "the fixed panel changed width" }

                // A direct undock is refused as well.
                workspace.undock(TREE)
                settle()
                check(tree.placement is SatellitePlacement.Docked) { "undock() tore out a fixed panel" }

                // Its rank is pinned: nothing offers it another one, and the
                // panel next to it cannot be dropped in front of it.
                check(hintedSides(tree, window, workspace.satellites).isEmpty()) {
                    "a pinned panel with one side is offered somewhere to go: " +
                        "${hintedSides(tree, window, workspace.satellites)}"
                }
                workspace.dock(TOC, DockSide.Right, order = 0)
                settle()
                check((workspace.satellite(TOC)?.placement as SatellitePlacement.Docked).order == 1) {
                    "the neighbour took the pinned panel's rank: ${workspace.satellite(TOC)?.placement}"
                }
                check((tree.placement as SatellitePlacement.Docked).order == 0) {
                    "the pinned panel lost its rank: ${tree.placement}"
                }
                awaitDockedBodies(fixture, TREE, TOC)
                check(near(panel(fixture, TREE).right, layoutInWindowRight(layout, client), LAYOUT_TOLERANCE_PX * 2)) {
                    "the pinned panel is not still the outermost layer: ${panel(fixture, TREE)}"
                }

                // The ordinary neighbour is torn out by the very same gesture.
                val tocBefore = panel(fixture, TOC)
                // Grabbed near its left edge, so its ghost hangs to the right
                // of the pointer and stays clear of the left strip: the
                // release is a tear-out, not a dock on the left.
                val tocGrab =
                    toScreen(
                        fixture,
                        Offset(
                            tocBefore.left + GRAB_EDGE_INSET_DP * scale,
                            tocBefore.top + DockPanelHeaderHeight.value * scale / 2f,
                        ),
                    )
                val tocSession = beginDockedDrag(workspace, TOC, tocGrab)
                tocSession.update(middle)
                check(workspace.dragGhost?.satellite?.id == TOC) { "no ghost for the ordinary panel" }
                check(workspace.dockPreview == null) {
                    "the ordinary panel is over a zone, so the release would not tear it out: ${workspace.dockPreview}"
                }
                tocSession.end(middle)
                awaitUntil("the toc floats") { fixture.floatingWindows.value[TOC]?.hasRealFramePx() == true }
                check(near(panel(fixture, TREE).right, layoutInWindowRight(layout, client), LAYOUT_TOLERANCE_PX * 2)) {
                    "the fixed panel is not still at the edge: ${panel(fixture, TREE)}"
                }
            },
        )
    }

    // ── 14. dockSides ────────────────────────────────────────────────────

    /**
     * A palette declared for three sides only: the top is neither hinted nor
     * published as a zone, a direct `dock(Top)` is refused, a release with the
     * palette's top edge in the top strip leaves it floating — and the left
     * side, which it *was* declared for, still takes it.
     *
     * Nothing else is docked, so the only thing that could light up is an
     * edge of the layout itself.
     */
    private fun aPaletteIsNeverOfferedASideItWasNotDeclaredFor(): TaoWindowTestCase {
        val notTop = setOf(DockSide.Left, DockSide.Right, DockSide.Bottom)
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(
                            INSPECTOR,
                            SatellitePlacement.Floating(
                                positioner = workspaceRightEdgePositioner(),
                                size = workspaceSatelliteSize(),
                            ),
                            dockSides = notTop,
                        ),
                    ),
            )
        return TaoWindowTestCase(
            name = "dock layout a palette is never offered a side it was not declared for",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitUntil("owner window mapped") { bounds() != null }
                awaitUntil("the inspector floats") {
                    fixture.floatingWindows.value[INSPECTOR]?.hasRealFramePx() == true
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val workspace = fixture.workspace
                val inspector = requireNotNull(workspace.satellite(INSPECTOR))
                val floating = requireNotNull(fixture.floatingWindows.value[INSPECTOR])
                val layout = awaitDockLayout(workspace, window)
                val zonePx = SatelliteWorkspace.DockZoneWidth.value * window.scaleFactor
                check(
                    hintedSides(inspector, window, workspace.satellites) ==
                        listOf(DockSide.Left, DockSide.Right, DockSide.Bottom),
                ) { "the top is offered: ${hintedSides(inspector, window, workspace.satellites)}" }

                // A direct dock on the top is refused outright.
                workspace.dock(INSPECTOR, DockSide.Top)
                settle()
                check(inspector.placement is SatellitePlacement.Floating) {
                    "dock(Top) was not refused: ${inspector.placement}"
                }

                // Read live: the first release moves the window, so the second
                // grab has to be taken where the palette is by then.
                fun grabNow(): Pair<Offset, Offset> {
                    val frame = requireNotNull(floating.outerBoundsPx())
                    val inset = Offset(frame[2] / 2f, HEADER_GRAB_Y_DP * floating.scaleFactor)
                    return Offset(frame[0].toFloat(), frame[1].toFloat()) + inset to inset
                }
                val outer = requireNotNull(floating.outerBoundsPx())
                val paletteSize = Size(outer[2].toFloat(), outer[3].toFloat())
                val (grab, grabInset) = grabNow()

                // Top edge inside the top strip, the palette clear of the
                // three sides it *may* dock on, so the top is the only edge it
                // has reached and a preview could only come from there.
                val paletteTopLeft = Offset(layout.center.x - paletteSize.width / 2f, layout.top + EDGE_INSET_PX)
                val atTop = paletteTopLeft + grabInset
                val session =
                    requireNotNull(workspace.beginDrag(INSPECTOR, SatelliteDragOrigin.FloatingWindow(floating), grab))
                awaitUntil("the layout published its drop zones") {
                    workspace.dockHostGeometry(window)?.zoneBoundsInWindowPx?.isNotEmpty() == true
                }
                val zones = requireNotNull(workspace.dockHostGeometry(window)?.zoneBoundsInWindowPx)
                check(!zones.containsKey(DockSide.Top)) { "the top zone is published: $zones" }
                check(
                    paletteTopLeft.x - layout.left > zonePx &&
                        layout.right - (paletteTopLeft.x + paletteSize.width) > zonePx &&
                        layout.bottom - (paletteTopLeft.y + paletteSize.height) > zonePx,
                ) { "the palette also reaches a side it may dock on: layout=$layout palette=$paletteSize" }
                session.update(atTop)
                check(workspace.dockPreview == null) {
                    "a zone is previewed for a palette aimed at the top: ${workspace.dockPreview} — " +
                        "layout=$layout paletteTopLeft=$paletteTopLeft pointer=$atTop zones=$zones"
                }
                session.end(atTop)
                settle()
                check(inspector.placement is SatellitePlacement.Floating) {
                    "released on the top strip, the palette docked: ${inspector.placement}"
                }
                check(fixture.floatingWindows.value[INSPECTOR] != null) { "the floating window is gone" }

                // The left side, which it was declared for, still works.
                val (grabAgain, insetAgain) = grabNow()
                val atLeft =
                    Offset(layout.left + EDGE_INSET_PX, layout.center.y - paletteSize.height / 2f) + insetAgain
                val second =
                    requireNotNull(
                        workspace.beginDrag(INSPECTOR, SatelliteDragOrigin.FloatingWindow(floating), grabAgain),
                    )
                // A new session starts with the zones of the last one cleared.
                awaitUntil("the layout published its drop zones again") {
                    workspace.dockHostGeometry(window)?.zoneBoundsInWindowPx?.isNotEmpty() == true
                }
                second.update(atLeft)
                check(workspace.dockPreview == DockTarget(window, DockSide.Left)) {
                    "the left zone is not previewed: ${workspace.dockPreview} — " +
                        "layout=$layout pointer=$atLeft grab=$grabAgain " +
                        "frame=${floating.outerBoundsPx()?.toList()}"
                }
                second.end(atLeft)
                awaitDockedBodies(fixture, INSPECTOR)
                check(near(panel(fixture, INSPECTOR).left, 0f, LAYOUT_TOLERANCE_PX * 2)) {
                    "not docked on the left: ${panel(fixture, INSPECTOR)}"
                }
            },
        )
    }

    // ── 12. reorder a layered side by dragging ───────────────────────────

    /**
     * The innermost of three layers is dragged by its header — a real mouse
     * where the host injects one, else the drag session it drives — until the
     * pointer is over the outer half of the outermost layer. The first rank
     * is previewed; released, the layer is the outermost column, at its own
     * width, and no panel was rebuilt on the way.
     */
    private fun aLayerDraggedOverTheOutermostOneBecomesTheFirst(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs = layeredRightSpecs(),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout a layer dragged over the outermost one becomes the first, nothing rebuilt",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodies(fixture, TREE, TOC, NOTES)
                val scale = window.scaleFactor
                val layout = awaitDockLayout(workspace, window)
                // The panels are in window px, the layout rect in screen px.
                val client = requireNotNull(workspace.dockHostGeometry(window)?.clientOriginPx())
                val layoutInWindow = layout.translate(-client)
                val tree = panel(fixture, TREE)
                val notesBefore = panel(fixture, NOTES)
                // The header strip is the grip; a docked panel of another
                // rank is offered its own side.
                check(
                    hintedSides(
                        requireNotNull(workspace.satellite(NOTES)),
                        window,
                        workspace.satellites,
                    ).contains(DockSide.Right),
                ) {
                    "a layer with neighbours is not offered its own side"
                }
                val grab =
                    toScreen(
                        fixture,
                        Offset(
                            notesBefore.center.x,
                            notesBefore.top + DockPanelHeaderHeight.value * scale / 2f,
                        ),
                    )
                // The outer half of the outermost layer: rank 0.
                val target = toScreen(fixture, Offset(tree.left + tree.width * OUTER_HALF, layoutInWindow.center.y))
                val expected = DockTarget(window, DockSide.Right, 0)

                if (robotPressAndDrag(grab, target, scale) != null) {
                    awaitUntil("the first rank previews under the pointer — ${robotAim()}") {
                        workspace.dockPreview ==
                            expected
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[dock-layout] robot unavailable, driving the drag session directly")
                    val session = beginDockedDrag(workspace, NOTES, grab)
                    session.update(target)
                    check(
                        workspace.dockPreview == expected,
                    ) { "expected $expected, previewed ${workspace.dockPreview}" }
                    session.end(target)
                }
                awaitUntil("the notes are the first rank") {
                    (workspace.satellite(NOTES)?.placement as? SatellitePlacement.Docked)?.order == 0
                }
                awaitDockedBodies(fixture, TREE, TOC, NOTES)
                val notes = panel(fixture, NOTES)
                val treeAfter = panel(fixture, TREE)
                val toc = panel(fixture, TOC)
                check(
                    near(notes.right, layoutInWindow.right, LAYOUT_TOLERANCE_PX * 2),
                ) { "the notes are not at the edge: $notes vs $layoutInWindow" }
                check(
                    near(treeAfter.right, notes.left, SPLITTER_TOLERANCE_PX) &&
                        near(toc.right, treeAfter.left, SPLITTER_TOLERANCE_PX),
                ) {
                    "the columns are not notes, tree, toc from the edge: notes=$notes tree=$treeAfter toc=$toc"
                }
                check(
                    near(notes.width, notesBefore.width),
                ) { "the notes changed width: ${notesBefore.width} -> ${notes.width}" }
                check(
                    fixture.incarnationsOf(TREE) == 1 &&
                        fixture.incarnationsOf(TOC) == 1 &&
                        fixture.incarnationsOf(NOTES) == 1,
                ) { "a reorder rebuilt a panel: ${fixture.incarnations.value}" }
            },
        )
    }

    // ── 13. reorder a split side, and stay on its own rank ──────────────

    private fun aSplitPanelDroppedOnItsStackTakesTheRankUnderThePointer(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TARGUM, SatellitePlacement.Docked(DockSide.Bottom, order = 0)),
                        DockPanelSpec(COMMENTS, SatellitePlacement.Docked(DockSide.Bottom, order = 1)),
                        DockPanelSpec(INSPECTOR, SatellitePlacement.Docked(DockSide.Bottom, order = 2)),
                    ),
            )
        return TaoWindowTestCase(
            name = "dock layout a split panel dropped on its stack takes the rank under the pointer or stays put",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodies(fixture, TARGUM, COMMENTS, INSPECTOR)
                val scale = window.scaleFactor
                val inspectorBefore = panel(fixture, INSPECTOR)
                val targum = panel(fixture, TARGUM)
                val grab =
                    toScreen(
                        fixture,
                        Offset(
                            inspectorBefore.center.x,
                            inspectorBefore.top + DockPanelHeaderHeight.value * scale / 2f,
                        ),
                    )

                // Nudged within its own panel: its own rank is no target, and the release changes nothing.
                var session = beginDockedDrag(workspace, INSPECTOR, grab)
                val nudge = grab + Offset(OWN_NUDGE_PX, OWN_NUDGE_PX)
                session.update(nudge)
                check(workspace.dockPreview == null) { "its own rank is previewed: ${workspace.dockPreview}" }
                session.end(nudge)
                settle()
                check((workspace.satellite(INSPECTOR)?.placement as SatellitePlacement.Docked).order == 2) {
                    "a release on its own rank moved the panel: ${workspace.satellite(INSPECTOR)?.placement}"
                }
                check(
                    fixture.floatingWindows.value[INSPECTOR] == null,
                ) { "a release on its own rank undocked the panel" }

                // The left half of the first panel: the first rank.
                val target = toScreen(fixture, Offset(targum.left + targum.width * (1f - OUTER_HALF), targum.center.y))
                session = beginDockedDrag(workspace, INSPECTOR, grab)
                session.update(target)
                check(workspace.dockPreview == DockTarget(window, DockSide.Bottom, 0)) {
                    "the first rank is not previewed: ${workspace.dockPreview}"
                }
                session.end(target)
                awaitUntil("the inspector is the first rank") {
                    (workspace.satellite(INSPECTOR)?.placement as? SatellitePlacement.Docked)?.order == 0
                }
                awaitDockedBodies(fixture, TARGUM, COMMENTS, INSPECTOR)
                val inspector = panel(fixture, INSPECTOR)
                val targumAfter = panel(fixture, TARGUM)
                val comments = panel(fixture, COMMENTS)
                check(
                    inspector.right <= targumAfter.left + LAYOUT_TOLERANCE_PX &&
                        targumAfter.right <= comments.left + LAYOUT_TOLERANCE_PX,
                ) {
                    "the row is not inspector, targum, comments: " +
                        "inspector=$inspector targum=$targumAfter comments=$comments"
                }
                check(
                    fixture.incarnationsOf(TARGUM) == 1 && fixture.incarnationsOf(COMMENTS) == 1,
                ) { "a reorder rebuilt a neighbour" }

                // With the middle one closed, a drop on the shown neighbour's far half goes behind the closed one too.
                workspace.close(TARGUM)
                awaitDockedBodies(fixture, INSPECTOR, COMMENTS)
                val commentsShown = panel(fixture, COMMENTS)
                val farHalf =
                    toScreen(
                        fixture,
                        Offset(commentsShown.left + commentsShown.width * OUTER_HALF, commentsShown.center.y),
                    )
                session = beginDockedDrag(workspace, INSPECTOR, grab)
                session.update(farHalf)
                check(workspace.dockPreview == DockTarget(window, DockSide.Bottom, 1)) {
                    "the rank after the comments is not previewed: ${workspace.dockPreview}"
                }
                session.end(farHalf)
                awaitUntil("the inspector is last") {
                    (workspace.satellite(INSPECTOR)?.placement as? SatellitePlacement.Docked)?.order == 2
                }
                check((workspace.satellite(TARGUM)?.placement as SatellitePlacement.Docked).order == 0) {
                    "the closed targum lost its rank: ${workspace.satellite(TARGUM)?.placement}"
                }
                workspace.open(TARGUM)
                awaitDockedBodies(fixture, TARGUM, COMMENTS, INSPECTOR)
                val reopened = panel(fixture, TARGUM)
                check(reopened.right <= panel(fixture, COMMENTS).left + LAYOUT_TOLERANCE_PX) {
                    "the reopened targum is not first: $reopened vs ${panel(fixture, COMMENTS)}"
                }
            },
        )
    }

    // ── 11. a re-dock returns to the rank ────────────────────────────────

    /**
     * The middle layer of three is floated, then docked again through the
     * path a header button takes — a side and no rank. It comes back between
     * the two it left, at its own width, and neither neighbour is rebuilt.
     * The same on the bottom side, split: the panel that left the middle
     * of the row is back in the middle of the row.
     */
    private fun aPanelDockedAgainReturnsToTheRankItLeft(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    layeredRightSpecs() +
                        listOf(
                            DockPanelSpec(TARGUM, SatellitePlacement.Docked(DockSide.Bottom, order = 0)),
                            DockPanelSpec(COMMENTS, SatellitePlacement.Docked(DockSide.Bottom, order = 1)),
                            DockPanelSpec(INSPECTOR, SatellitePlacement.Docked(DockSide.Bottom, order = 2)),
                        ),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout a panel docked again without a rank returns between the neighbours it left",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                val workspace = fixture.workspace
                awaitDockedBodies(fixture, TREE, TOC, NOTES, TARGUM, COMMENTS, INSPECTOR)
                val tocBefore = panel(fixture, TOC)
                val commentsBefore = panel(fixture, COMMENTS)

                // Layered right side: the toc is the middle column.
                workspace.undock(TOC)
                awaitUntil("the toc floats") { fixture.floatingWindows.value[TOC]?.hasRealFramePx() == true }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(panel(fixture, NOTES).right > tocBefore.left + LAYOUT_TOLERANCE_PX) {
                    "the inner layer did not slide out while the toc floated: ${panel(fixture, NOTES)}"
                }
                workspace.dock(TOC, DockSide.Right)
                awaitDockedBodies(fixture, TREE, TOC, NOTES)
                val tree = panel(fixture, TREE)
                val toc = panel(fixture, TOC)
                val notes = panel(fixture, NOTES)
                // Between its neighbours, a splitter's width from each.
                check(
                    near(toc.right, tree.left, SPLITTER_TOLERANCE_PX) &&
                        near(notes.right, toc.left, SPLITTER_TOLERANCE_PX),
                ) {
                    "the toc is not back between the tree and the notes: tree=$tree toc=$toc notes=$notes"
                }
                check(
                    near(toc.width, tocBefore.width),
                ) { "the toc came back at ${toc.width} px, was ${tocBefore.width}" }
                check((workspace.satellite(TOC)?.placement as SatellitePlacement.Docked).order == 1) {
                    "the toc's rank is not 1: ${workspace.satellite(TOC)?.placement}"
                }
                check(fixture.incarnationsOf(TREE) == 1 && fixture.incarnationsOf(NOTES) == 1) {
                    "a neighbour was rebuilt by the toc leaving and returning"
                }

                // Split bottom side: the comments are the middle of the row.
                workspace.undock(COMMENTS)
                awaitUntil(
                    "the comments float",
                ) { fixture.floatingWindows.value[COMMENTS]?.hasRealFramePx() == true }
                settle(SETTLE_AFTER_MAP_MILLIS)
                workspace.dock(COMMENTS, DockSide.Bottom)
                awaitDockedBodies(fixture, TARGUM, COMMENTS, INSPECTOR)
                val targum = panel(fixture, TARGUM)
                val comments = panel(fixture, COMMENTS)
                val inspector = panel(fixture, INSPECTOR)
                check(
                    targum.right <= comments.left + LAYOUT_TOLERANCE_PX &&
                        comments.right <= inspector.left + LAYOUT_TOLERANCE_PX,
                ) {
                    "the comments are not back in the middle of the row: " +
                        "targum=$targum comments=$comments inspector=$inspector"
                }
                check(near(comments.width, commentsBefore.width, SPLITTER_TOLERANCE_PX)) {
                    "the comments came back at ${comments.width} px, were ${commentsBefore.width}"
                }
            },
        )
    }

    // ── 10. the preview follows the palette, not the pointer ─────────────

    /**
     * The zone lights up when the *palette* reaches it, with the pointer still
     * in the middle of the palette and nowhere near the layout's edge — and
     * the side the panel already occupies is never offered.
     *
     * Driven with a real mouse where the host allows it: the palette follows
     * the pointer, so grabbing its centre keeps the pointer far from every
     * edge for the whole gesture while the palette's own edge enters the zone.
     */
    private fun thePaletteEdgeDecidesTheZoneNotThePointer(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Bottom, extent = BOTTOM_H_DP.dp)),
                        DockPanelSpec(
                            INSPECTOR,
                            SatellitePlacement.Floating(
                                positioner = workspaceRightEdgePositioner(),
                                size = workspaceSatelliteSize(),
                            ),
                        ),
                    ),
            )
        return TaoWindowTestCase(
            name = "dock layout the palette's own edge decides the zone, not the pointer",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE)
                awaitUntil(
                    "the inspector floats",
                ) { fixture.floatingWindows.value[INSPECTOR]?.hasRealFramePx() == true }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val workspace = fixture.workspace
                val floating = requireNotNull(fixture.floatingWindows.value[INSPECTOR])
                val layout = awaitDockLayout(workspace, window)
                val scale = window.scaleFactor
                val outer = requireNotNull(floating.outerBoundsPx())
                val paletteWidth = outer[2].toFloat()

                // The panel already on the bottom is not offered that side.
                val tree = requireNotNull(workspace.satellite(TREE))
                check(!hintedSides(tree, window, workspace.satellites).contains(DockSide.Bottom)) {
                    "the bottom panel is offered the side it is already on: ${hintedSides(
                        tree,
                        window,
                        workspace.satellites,
                    )}"
                }
                check(
                    hintedSides(requireNotNull(workspace.satellite(INSPECTOR)), window, workspace.satellites).size ==
                        DockSide.entries.size,
                ) {
                    "a floating palette must be offered every side"
                }

                // Aim so the palette's left edge lands just inside the left
                // zone while the pointer stays at its centre — well past the
                // zone, over the content — and the palette itself stays clear
                // of the top and bottom zones, so the left one is the only
                // edge in reach and the assertion is unambiguous.
                val paletteHeight = outer[3].toFloat()
                val grab = Offset(outer[0] + paletteWidth / 2f, outer[1] + HEADER_GRAB_Y_DP * floating.scaleFactor)
                val grabInset = grab - Offset(outer[0].toFloat(), outer[1].toFloat())
                val target = Offset(layout.left + EDGE_INSET_PX, layout.center.y - paletteHeight / 2f) + grabInset
                val zonePx = SatelliteWorkspace.DockZoneWidth.value * scale
                check(target.x - layout.left > zonePx) {
                    "the pointer would land inside the left zone itself: this case would prove nothing"
                }
                val paletteTop = target.y - grabInset.y
                check(paletteTop - layout.top > zonePx && layout.bottom - (paletteTop + paletteHeight) > zonePx) {
                    "the palette also reaches the top or bottom zone (layout=$layout palette height=$paletteHeight): " +
                        "the case would be ambiguous"
                }

                val robot = robotPressAndDrag(grab, target, scale) != null
                if (robot) {
                    awaitUntil("the left zone previews while the pointer is over the content — ${robotAim()}") {
                        workspace.dockPreview == DockTarget(window, DockSide.Left)
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[dock-layout] robot unavailable, driving the drag session directly")
                    val session =
                        requireNotNull(
                            workspace.beginDrag(INSPECTOR, SatelliteDragOrigin.FloatingWindow(floating), grab),
                        )
                    session.update(target)
                    check(workspace.dockPreview == DockTarget(window, DockSide.Left)) {
                        "the palette's edge reached the left zone but ${workspace.dockPreview} is previewed"
                    }
                    session.end(target)
                }
                awaitUntil("the palette docked on the left") {
                    (workspace.satellite(INSPECTOR)?.placement as? SatellitePlacement.Docked)?.side == DockSide.Left
                }
                awaitDockedBodies(fixture, TREE, INSPECTOR)
                check(near(panel(fixture, INSPECTOR).left, 0f, LAYOUT_TOLERANCE_PX * 2)) {
                    "the new panel is not at the left edge: ${panel(fixture, INSPECTOR)}"
                }
            },
        )
    }

    // ── 1. layered geometry ──────────────────────────────────────────────

    private fun layeredPanelsSitSideBySideWithTheirOwnWidths(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs = layeredRightSpecs(),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout three layered panels on the right are three columns of their own width",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE, TOC, NOTES)
                val scale = window.scaleFactor
                val layout = requireNotNull(fixture.workspace.dockHostGeometry(window)).layoutBoundsInWindowPx
                val tree = panel(fixture, TREE)
                val toc = panel(fixture, TOC)
                val notes = panel(fixture, NOTES)
                val content = requireNotNull(fixture.contentBounds.value)

                // Order 0 is at the edge; each layer runs the full height.
                check(near(tree.right, layout.right)) { "the first layer is not at the right edge: $tree in $layout" }
                check(toc.right <= tree.left + LAYOUT_TOLERANCE_PX && notes.right <= toc.left + LAYOUT_TOLERANCE_PX) {
                    "layers are not side by side from the edge inwards: tree=$tree toc=$toc notes=$notes"
                }
                check(
                    content.right <= notes.left + LAYOUT_TOLERANCE_PX,
                ) { "the content runs under a layer: $content vs $notes" }
                for ((id, rect) in listOf(TREE to tree, TOC to toc, NOTES to notes)) {
                    check(near(rect.top, layout.top) && near(rect.bottom, layout.bottom)) {
                        "$id does not run the full height: $rect in $layout"
                    }
                }
                // Each at its own width.
                check(near(tree.width, TREE_W_DP * scale)) { "tree width ${tree.width} != ${TREE_W_DP * scale}" }
                check(near(toc.width, TOC_W_DP * scale)) { "toc width ${toc.width} != ${TOC_W_DP * scale}" }
                check(near(notes.width, NOTES_W_DP * scale)) { "notes width ${notes.width} != ${NOTES_W_DP * scale}" }
                // Nothing overlaps anything.
                val all = listOf(tree, toc, notes, content)
                for (i in all.indices) {
                    for (j in i + 1 until all.size) {
                        check(!overlaps(all[i], all[j])) { "panels overlap: ${all[i]} and ${all[j]}" }
                    }
                }
                // The default header strip sizes itself in the dock.
                val body = requireNotNull(fixture.bodyBounds.value[TREE])
                check(near(body.top - tree.top, DockPanelHeaderHeight.value * scale)) {
                    "the header strip is ${body.top - tree.top} px, expected ${DockPanelHeaderHeight.value * scale}"
                }
            },
        )
    }

    // ── 2. layered splitter ──────────────────────────────────────────────

    private fun aLayeredSplitterResizesItsPanelAlone(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs = layeredRightSpecs(),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout a layered panel's splitter resizes that panel alone",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE, TOC, NOTES)
                val scale = window.scaleFactor
                val treeBefore = panel(fixture, TREE)
                val tocBefore = panel(fixture, TOC)
                val notesBefore = panel(fixture, NOTES)
                val contentBefore = requireNotNull(fixture.contentBounds.value)
                val grip = requireNotNull(fixture.splitterOf(TOC)) { "no splitter published for $TOC" }
                check(
                    grip.left <= tocBefore.left + LAYOUT_TOLERANCE_PX &&
                        grip.right >= notesBefore.right - LAYOUT_TOLERANCE_PX,
                ) {
                    "the toc splitter is not between toc and notes: grip=$grip toc=$tocBefore notes=$notesBefore"
                }

                // On the right side, towards the content is leftwards.
                val deltaPx = -(SPLITTER_DRAG_DP * scale)
                val from = toScreen(fixture, grip.center)
                val robot = robotPressAndDrag(from, from + Offset(deltaPx, 0f), scale) != null
                if (robot) {
                    awaitUntil("the toc layer grew under the drag — ${robotAim()}") {
                        panelOrNull(fixture, TOC)?.let { it.width > tocBefore.width + abs(deltaPx) / 2 } == true
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[dock-layout] robot unavailable, resizing through the workspace")
                    fixture.workspace.setDockedExtent(TOC, (TOC_W_DP + SPLITTER_DRAG_DP).dp)
                }
                awaitUntil("the layout settled at the new width") {
                    panelOrNull(
                        fixture,
                        TOC,
                    )?.let { near(it.width, tocBefore.width + abs(deltaPx), SPLITTER_TOLERANCE_PX) } ==
                        true
                }
                settle()

                val toc = panel(fixture, TOC)
                check(near(panel(fixture, TREE).width, treeBefore.width)) { "the tree layer changed width" }
                check(near(panel(fixture, NOTES).width, notesBefore.width)) { "the notes layer changed width" }
                check(near(toc.right, tocBefore.right)) { "the toc layer moved instead of growing towards the content" }
                val content = requireNotNull(fixture.contentBounds.value)
                check(near(content.width, contentBefore.width - (toc.width - tocBefore.width), SPLITTER_TOLERANCE_PX)) {
                    "the content did not give up what the layer took: $contentBefore -> $content"
                }
                // The extent is the panel's own, and it is in the snapshot.
                val saved = requireNotNull(fixture.workspace.snapshot().satellites[TOC]).placement
                val docked = saved as SatellitePlacement.Docked
                val extent = requireNotNull(docked.extent)
                check(abs(extent.value * scale - toc.width) <= SPLITTER_TOLERANCE_PX) {
                    "the snapshot carries $extent, the layer is ${toc.width / scale} dp wide"
                }
                check(
                    (
                        fixture.workspace
                            .snapshot()
                            .satellites[TREE]
                            ?.placement as SatellitePlacement.Docked
                    ).extent ==
                        TREE_W_DP.dp,
                ) {
                    "the tree's extent changed in the snapshot"
                }
            },
        )
    }

    // ── 3. split weights ─────────────────────────────────────────────────

    private fun splitPanelsShareBySideWeightAndTheDividerMovesIt(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Left, order = 0, weight = 1f)),
                        DockPanelSpec(TOC, SatellitePlacement.Docked(DockSide.Left, order = 1, weight = 3f)),
                    ),
            )
        return TaoWindowTestCase(
            name = "dock layout split panels share the side by weight and the divider moves it",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE, TOC)
                val scale = window.scaleFactor
                val tree = panel(fixture, TREE)
                val toc = panel(fixture, TOC)
                check(
                    near(tree.left, toc.left) && near(tree.width, toc.width),
                ) { "split panels do not share the side's width" }
                check(tree.bottom <= toc.top + LAYOUT_TOLERANCE_PX) { "order 0 is not above order 1: $tree / $toc" }
                // 1 : 3, minus the divider between them.
                check(abs(toc.height - 3f * tree.height) <= SPLITTER_TOLERANCE_PX * 3) {
                    "heights are not 1:3 — tree ${tree.height}, toc ${toc.height}"
                }
                val extentPx = fixture.workspace.dockExtent(DockSide.Left).value * scale
                check(near(tree.width, extentPx)) { "the stack is ${tree.width} px wide, extent says $extentPx" }

                val divider = requireNotNull(fixture.splitterOf(TREE)) { "no divider between the two panels" }
                check(
                    divider.top >= tree.bottom - LAYOUT_TOLERANCE_PX && divider.bottom <= toc.top + LAYOUT_TOLERANCE_PX,
                ) {
                    "the divider is not between the panels: $divider between $tree and $toc"
                }
                val deltaPx = SPLITTER_DRAG_DP * scale
                val from = toScreen(fixture, divider.center)
                val robot = robotPressAndDrag(from, from + Offset(0f, deltaPx), scale) != null
                if (robot) {
                    awaitUntil("the tree panel grew under the drag — ${robotAim()}") {
                        panelOrNull(fixture, TREE)?.let { it.height > tree.height + deltaPx / 2 } == true
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[dock-layout] robot unavailable, moving weight through the workspace")
                    val total = tree.height + toc.height
                    val moved = deltaPx / total * 4f
                    fixture.workspace.setDockedWeight(TREE, 1f + moved)
                    fixture.workspace.setDockedWeight(TOC, 3f - moved)
                }
                awaitUntil("the divider settled where it was dropped") {
                    panelOrNull(fixture, TREE)?.let { near(it.height, tree.height + deltaPx, SPLITTER_TOLERANCE_PX) } ==
                        true
                }
                settle()
                val treeAfter = panel(fixture, TREE)
                val tocAfter = panel(fixture, TOC)
                check(near(tocAfter.height, toc.height - deltaPx, SPLITTER_TOLERANCE_PX)) {
                    "the toc panel did not shrink by what the tree took: ${toc.height} -> ${tocAfter.height}"
                }
                check(near(treeAfter.width, tree.width)) { "the side's width changed under a weight drag" }
                val weights =
                    fixture.workspace.satellites.associate {
                        it.id to (it.placement as SatellitePlacement.Docked).weight
                    }
                check(weights.getValue(TREE) > 1f && weights.getValue(TOC) < 3f) { "weights did not move: $weights" }
                check(abs(weights.getValue(TREE) + weights.getValue(TOC) - 4f) < WEIGHT_SUM_TOLERANCE) {
                    "the divider changed the total weight: $weights"
                }
                // The side's own splitter still drags the shared width.
                val sideGrip = requireNotNull(fixture.sideSplitterOf(DockSide.Left))
                check(
                    near(sideGrip.left, tree.right, LAYOUT_TOLERANCE_PX + 1f),
                ) { "the side splitter is not at the stack's edge" }
            },
        )
    }

    // ── 4. side order ────────────────────────────────────────────────────

    private fun theOuterSideOwnsTheCorners(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, extent = TREE_W_DP.dp)),
                        DockPanelSpec(TARGUM, SatellitePlacement.Docked(DockSide.Left, extent = TREE_W_DP.dp)),
                        DockPanelSpec(COMMENTS, SatellitePlacement.Docked(DockSide.Bottom, extent = BOTTOM_H_DP.dp)),
                    ),
                sideOrder = listOf(DockSide.Right, DockSide.Bottom, DockSide.Left, DockSide.Top),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout the first side in the order runs the full length and owns the corners",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE, TARGUM, COMMENTS)
                val layout = requireNotNull(fixture.workspace.dockHostGeometry(window)).layoutBoundsInWindowPx
                val tree = panel(fixture, TREE)
                val targum = panel(fixture, TARGUM)
                val comments = panel(fixture, COMMENTS)
                val content = requireNotNull(fixture.contentBounds.value)

                check(near(tree.top, layout.top) && near(tree.bottom, layout.bottom)) {
                    "the right side does not run the full height: $tree"
                }
                check(comments.right <= tree.left + LAYOUT_TOLERANCE_PX) {
                    "the bottom panel runs under the right side: $comments vs $tree"
                }
                check(near(comments.left, layout.left)) {
                    "the bottom panel does not reach the left edge under the left panel: $comments"
                }
                check(targum.bottom <= comments.top + LAYOUT_TOLERANCE_PX) {
                    "the left panel runs beside the bottom one: $targum vs $comments"
                }
                check(near(targum.left, layout.left)) { "the left panel is not at the left edge: $targum" }
                check(
                    content.left >= targum.right - LAYOUT_TOLERANCE_PX &&
                        content.bottom <= comments.top + LAYOUT_TOLERANCE_PX,
                ) {
                    "the content is not boxed in by left and bottom: $content"
                }
                check(near(comments.bottom, layout.bottom)) { "the bottom panel is not at the bottom edge" }

                // Now the classic order: bottom runs the full width under everything.
                fixture.sideOrder.value = DefaultDockSideOrder
                awaitUntil("the bottom panel took the full width — ${fixture.panelBounds.value}") {
                    panelOrNull(
                        fixture,
                        COMMENTS,
                    )?.let { near(it.right, layout.right) && near(it.left, layout.left) } ==
                        true
                }
                settle()
                val treeAfter = panel(fixture, TREE)
                check(treeAfter.bottom <= panel(fixture, COMMENTS).top + LAYOUT_TOLERANCE_PX) {
                    "the right side still runs beside the bottom"
                }
                check(
                    fixture.incarnationsOf(TREE) == 1 &&
                        fixture.incarnationsOf(COMMENTS) == 1 &&
                        fixture.incarnationsOf(TARGUM) == 1,
                ) {
                    "a side-order change rebuilt a panel: ${fixture.incarnations.value}"
                }
                check(fixture.contentIncarnations.value == 1) { "a side-order change rebuilt the content" }
            },
        )
    }

    // ── 5. right-to-left ─────────────────────────────────────────────────

    private fun rtlKeepsPhysicalSidesAndHandsTheDirectionBack(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TARGUM, SatellitePlacement.Docked(DockSide.Left, extent = TREE_W_DP.dp)),
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, extent = TREE_W_DP.dp)),
                    ),
                layeredSides = setOf(DockSide.Left, DockSide.Right),
                direction = LayoutDirection.Rtl,
            )
        return TaoWindowTestCase(
            name = "dock layout under RTL the left side is the physical left and its splitter grows it rightwards",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TARGUM, TREE)
                val scale = window.scaleFactor
                val layout = requireNotNull(fixture.workspace.dockHostGeometry(window)).layoutBoundsInWindowPx
                val left = panel(fixture, TARGUM)
                val right = panel(fixture, TREE)
                check(
                    near(left.left, layout.left),
                ) { "DockSide.Left is not at the physical left under RTL: $left in $layout" }
                check(
                    near(right.right, layout.right),
                ) { "DockSide.Right is not at the physical right under RTL: $right in $layout" }
                check(fixture.contentDirection.value == LayoutDirection.Rtl) { "the content lost its RTL direction" }
                check(
                    fixture.bodyDirections.value[TARGUM] == LayoutDirection.Rtl,
                ) { "the panel body lost its RTL direction" }

                // Dragging the left panel's splitter to the right grows it.
                val grip = requireNotNull(fixture.splitterOf(TARGUM))
                check(grip.left >= left.right - LAYOUT_TOLERANCE_PX) {
                    "the left panel's splitter is not on its content side: $grip vs $left"
                }
                val deltaPx = SPLITTER_DRAG_DP * scale
                val from = toScreen(fixture, grip.center)
                val robot = robotPressAndDrag(from, from + Offset(deltaPx, 0f), scale) != null
                if (robot) {
                    awaitUntil("the left panel grew rightwards — ${robotAim()}") {
                        panelOrNull(fixture, TARGUM)?.let { it.width > left.width + deltaPx / 2 } == true
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[dock-layout] robot unavailable, resizing through the workspace")
                    fixture.workspace.setDockedExtent(TARGUM, (TREE_W_DP + SPLITTER_DRAG_DP).dp)
                }
                awaitUntil("the left panel settled at its new width") {
                    panelOrNull(fixture, TARGUM)?.let { near(it.width, left.width + deltaPx, SPLITTER_TOLERANCE_PX) } ==
                        true
                }
                check(near(panel(fixture, TARGUM).left, layout.left)) { "the left panel left the edge while growing" }
                check(near(panel(fixture, TREE).width, right.width)) { "the right panel changed under a left drag" }

                // Flipping the direction changes nothing about where the sides are.
                fixture.direction.value = LayoutDirection.Ltr
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(
                    near(panel(fixture, TARGUM).left, layout.left) && near(panel(fixture, TREE).right, layout.right),
                ) {
                    "a direction flip moved the sides"
                }
                check(
                    fixture.contentDirection.value == LayoutDirection.Ltr,
                ) { "the content did not follow the direction flip" }
                check(fixture.incarnationsOf(TARGUM) == 1 && fixture.contentIncarnations.value == 1) {
                    "a direction flip rebuilt the panel or the content"
                }
            },
        )
    }

    // ── 6. nothing is rebuilt ────────────────────────────────────────────

    private fun layoutChangesNeverRebuildAPanelOrTheContent(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    layeredRightSpecs() +
                        DockPanelSpec(COMMENTS, SatellitePlacement.Docked(DockSide.Bottom, extent = BOTTOM_H_DP.dp)) +
                        DockPanelSpec(
                            INSPECTOR,
                            SatellitePlacement.Floating(
                                positioner = workspaceRightEdgePositioner(),
                                size = workspaceSatelliteSize(),
                            ),
                        ),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout no layout change rebuilds a panel or the content and restores keep the floating window",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE, TOC, NOTES, COMMENTS)
                awaitUntil(
                    "the inspector floats",
                ) { fixture.floatingWindows.value[INSPECTOR]?.hasRealFramePx() == true }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val workspace = fixture.workspace
                val floating = requireNotNull(fixture.floatingWindows.value[INSPECTOR])
                val docked = listOf(TREE, TOC, NOTES, COMMENTS)
                val initial = workspace.snapshot()

                suspend fun step(what: String) {
                    settle(SETTLE_AFTER_MAP_MILLIS)
                    for (id in docked) {
                        check(
                            fixture.incarnationsOf(id) == 1,
                        ) { "$what rebuilt $id: built ${fixture.incarnationsOf(id)} times" }
                        check(
                            fixture.liveBodiesOf(id) == 1,
                        ) { "$what left $id composed ${fixture.liveBodiesOf(id)} times" }
                    }
                    check(fixture.contentIncarnations.value == 1) { "$what rebuilt the content" }
                    check(
                        fixture.floatingWindows.value[INSPECTOR] === floating,
                    ) { "$what recreated the inspector's window" }
                    check(fixture.incarnationsOf(INSPECTOR) == 1) { "$what rebuilt the inspector's body" }
                }

                workspace.setDockedExtent(TOC, (TOC_W_DP + SPLITTER_DRAG_DP).dp)
                step("a layered extent change")
                workspace.setDockExtent(DockSide.Bottom, (BOTTOM_H_DP + SPLITTER_DRAG_DP).dp)
                step("a side extent change")
                workspace.dock(TREE, DockSide.Right, order = 5)
                step("a reorder on the same side")
                awaitUntil("tree moved to the inner end") {
                    panelOrNull(fixture, TREE)?.let {
                        it.left <
                            panel(fixture, TOC).left
                    } ==
                        true
                }
                workspace.dock(NOTES, DockSide.Left)
                awaitUntil("notes moved to the left side") {
                    panelOrNull(fixture, NOTES)?.let {
                        near(
                            it.left,
                            0f,
                            LAYOUT_TOLERANCE_PX * 2,
                        )
                    } ==
                        true
                }
                step("a move to another side")
                workspace.dock(NOTES, DockSide.Bottom)
                awaitUntil("notes shares the bottom") {
                    panelOrNull(fixture, NOTES)?.let {
                        it.top >
                            panel(fixture, TOC).top
                    } ==
                        true
                }
                step("a move to a split side")
                workspace.setDockedWeight(NOTES, 2f)
                step("a weight change")
                fixture.layeredSides.value = setOf(DockSide.Right, DockSide.Bottom)
                step("a side turning layered")
                fixture.layeredSides.value = setOf(DockSide.Right)
                step("a side turning split again")
                fixture.sideOrder.value = listOf(DockSide.Right, DockSide.Bottom, DockSide.Left, DockSide.Top)
                step("a new side order")
                fixture.direction.value = LayoutDirection.Rtl
                step("a direction flip")
                repeat(RESTORE_ROUNDS) {
                    workspace.restore(initial)
                    step("a restore of the initial layout")
                    workspace.restore(workspace.snapshot())
                    step("a restore of the current layout")
                }
                awaitUntil("the initial layout is back") {
                    panelOrNull(fixture, NOTES)?.let { near(it.width, NOTES_W_DP * window.scaleFactor) } == true
                }
                // A resize of the window re-lays everything out and rebuilds nothing.
                window.setInnerSize(RESIZED_W_DP, RESIZED_H_DP)
                awaitUntil("the window resized") { (bounds()?.get(2) ?: 0L) > PARENT_W_DP * window.scaleFactor + 1 }
                step("a window resize")
            },
        )
    }

    // ── 7. custom splitter ───────────────────────────────────────────────

    private fun aOneDpSplitterWithAWiderGripTakesTheDrag(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs = listOf(DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, extent = TREE_W_DP.dp))),
                layeredSides = setOf(DockSide.Right),
                gripOverflow = true,
            )
        return TaoWindowTestCase(
            name = "dock layout a 1 dp splitter with a wider grip takes a drag aimed off the line",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE)
                val scale = window.scaleFactor
                val before = panel(fixture, TREE)
                val grip = requireNotNull(fixture.splitterOf(TREE))
                check(near(grip.width, GRIP_OVERFLOW_DP * scale, LAYOUT_TOLERANCE_PX)) {
                    "the grip is ${grip.width} px wide, expected ${GRIP_OVERFLOW_DP * scale}: " +
                        "requiredWidth did not overflow"
                }
                // The layout itself only gave the splitter one dp.
                check(
                    near(before.left - requireNotNull(fixture.contentBounds.value).right, scale, LAYOUT_TOLERANCE_PX),
                ) {
                    "the layout reserved more than 1 dp for the splitter"
                }
                // Aim two dp off the line, inside the grip but outside the 1 dp of layout.
                val aim = Offset(grip.center.x - 2f * scale, grip.center.y)
                val deltaPx = -(SPLITTER_DRAG_DP * scale)
                val from = toScreen(fixture, aim)
                if (robotPressAndDrag(from, from + Offset(deltaPx, 0f), scale) == null) {
                    System.err.println("[dock-layout] robot unavailable, the overflowing grip cannot be exercised")
                    return@TaoWindowTestCase
                }
                awaitUntil("the panel grew under a drag aimed beside the line — ${robotAim()}") {
                    panelOrNull(fixture, TREE)?.let { it.width > before.width + abs(deltaPx) / 2 } == true
                }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                awaitUntil("the panel settled") {
                    panelOrNull(
                        fixture,
                        TREE,
                    )?.let { near(it.width, before.width + abs(deltaPx), SPLITTER_TOLERANCE_PX) } ==
                        true
                }
            },
        )
    }

    // ── 8. drop on a layered side ────────────────────────────────────────

    private fun aDropOnALayeredSideAddsALayerOfTheWindowsWidth(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs =
                    listOf(
                        DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, extent = TREE_W_DP.dp)),
                        DockPanelSpec(
                            INSPECTOR,
                            SatellitePlacement.Floating(
                                positioner = workspaceRightEdgePositioner(),
                                size = workspaceSatelliteSize(),
                            ),
                        ),
                    ),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout a floating satellite dropped on a layered side becomes a layer of its window's width",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE)
                awaitUntil(
                    "the inspector floats",
                ) { fixture.floatingWindows.value[INSPECTOR]?.hasRealFramePx() == true }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val workspace = fixture.workspace
                val floating = requireNotNull(fixture.floatingWindows.value[INSPECTOR])
                val scale = window.scaleFactor
                val layout = awaitDockLayout(workspace, window)
                val treeBefore = panel(fixture, TREE)
                val outer = requireNotNull(floating.outerBoundsPx())
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * floating.scaleFactor)
                val dropIn = Offset(layout.right - DROP_INSET_PX, layout.center.y)

                val session =
                    requireNotNull(workspace.beginDrag(INSPECTOR, SatelliteDragOrigin.FloatingWindow(floating), grab))
                session.update(dropIn)
                check(workspace.dockPreview == DockTarget(window, DockSide.Right)) {
                    "the right zone is not previewed: ${workspace.dockPreview}"
                }
                session.end(dropIn)
                awaitDockedBodies(fixture, TREE, INSPECTOR)

                val inspector = panel(fixture, INSPECTOR)
                val tree = panel(fixture, TREE)
                val placement = workspace.satellite(INSPECTOR)?.placement as SatellitePlacement.Docked
                check(
                    placement.side == DockSide.Right && placement.order > 0,
                ) { "not appended on the right: $placement" }
                check(
                    placement.extent == workspaceSatelliteSize().width,
                ) { "the layer's extent is not the window's width: $placement" }
                check(near(inspector.width, SATELLITE_W_DP * scale)) {
                    "the layer is ${inspector.width} px, the window was ${SATELLITE_W_DP * scale}"
                }
                check(inspector.right <= tree.left + LAYOUT_TOLERANCE_PX) {
                    "the new layer is not inside the existing one: $inspector vs $tree"
                }
                check(near(tree.width, treeBefore.width) && near(tree.right, treeBefore.right)) {
                    "the existing layer moved or resized: $treeBefore -> $tree"
                }
            },
        )
    }

    // ── 9. lift-off from a layer ─────────────────────────────────────────

    private fun undockingALayerLiftsTheWindowOffThePanel(): TaoWindowTestCase {
        val fixture =
            DockLayoutFixture(
                specs = layeredRightSpecs(),
                layeredSides = setOf(DockSide.Right),
            )
        return TaoWindowTestCase(
            name = "dock layout undocking a middle layer lifts its window off where the layer was",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { Satellites() } },
            driver = {
                awaitDockedBodies(fixture, TREE, TOC, NOTES)
                val client = requireNotNull(fixture.workspace.dockHostGeometry(window)?.clientOriginPx())
                val tocBefore = panel(fixture, TOC)
                val expected = tocBefore.translate(client)
                val treeBefore = panel(fixture, TREE)
                val notesBefore = panel(fixture, NOTES)

                fixture.workspace.undock(TOC)
                awaitUntil(
                    "the toc floats with a frame",
                ) { fixture.floatingWindows.value[TOC]?.hasRealFramePx() == true }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val outer = requireNotNull(requireNotNull(fixture.floatingWindows.value[TOC]).outerBoundsPx())
                check(
                    abs(outer[0] - expected.left) <= LIFT_OFF_TOLERANCE_PX &&
                        abs(outer[1] - expected.top) <= LIFT_OFF_TOLERANCE_PX,
                ) {
                    "the window lifted off at (${outer[0]}, ${outer[1]}), " +
                        "the layer was at (${expected.left}, ${expected.top})"
                }
                check(abs(outer[2] - expected.width) <= LIFT_OFF_TOLERANCE_PX) {
                    "the window is ${outer[2]} px wide, the layer was ${expected.width}"
                }
                // The neighbours close the gap: the tree stays at the edge, the notes slide out to meet it.
                val tree = panel(fixture, TREE)
                val notes = panel(fixture, NOTES)
                check(
                    near(tree.right, treeBefore.right) && near(tree.width, treeBefore.width),
                ) { "the outer layer moved" }
                check(near(notes.width, notesBefore.width) && notes.right > notesBefore.right + tocBefore.width / 2) {
                    "the inner layer did not slide out to fill the gap: $notesBefore -> $notes"
                }
                check(
                    fixture.incarnationsOf(TREE) == 1 && fixture.incarnationsOf(NOTES) == 1,
                ) { "undocking one layer rebuilt another" }
            },
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Starts a drag of the docked panel [id] and waits for the layout to
     * publish the zones the drop is resolved against. A pointer gesture gives
     * the hints a frame to compose before the slop is passed; a session driven
     * by hand has to wait for it, or the first sample is resolved against the
     * bare edges.
     */
    private suspend fun TaoWindowTestScope.beginDockedDrag(
        workspace: SatelliteWorkspace,
        id: String,
        grab: Offset,
    ): SatelliteDragSession {
        val session = requireNotNull(workspace.beginDrag(id, SatelliteDragOrigin.DockedPanel(window), grab))
        awaitUntil("the layout published its drop zones") {
            workspace.dockHostGeometry(window)?.zoneBoundsInWindowPx?.isNotEmpty() == true
        }
        return session
    }

    private fun layeredRightSpecs(): List<DockPanelSpec> =
        listOf(
            DockPanelSpec(TREE, SatellitePlacement.Docked(DockSide.Right, order = 0, extent = TREE_W_DP.dp)),
            DockPanelSpec(TOC, SatellitePlacement.Docked(DockSide.Right, order = 1, extent = TOC_W_DP.dp)),
            DockPanelSpec(NOTES, SatellitePlacement.Docked(DockSide.Right, order = 2, extent = NOTES_W_DP.dp)),
        )

    private fun panel(
        fixture: DockLayoutFixture,
        id: String,
    ): Rect =
        requireNotNull(fixture.panelBounds.value[id]) { "no panel bounds for $id: ${fixture.panelBounds.value.keys}" }

    /** The layout's right edge in window px: the panels are measured there, the layout rect on screen. */
    private fun layoutInWindowRight(
        layoutScreenPx: Rect,
        clientOriginPx: Offset,
    ): Float = layoutScreenPx.right - clientOriginPx.x

    private fun panelOrNull(
        fixture: DockLayoutFixture,
        id: String,
    ): Rect? = fixture.panelBounds.value[id]

    private const val TREE = "tree"
    private const val TOC = "toc"
    private const val NOTES = "notes"
    private const val TARGUM = "targum"
    private const val COMMENTS = "comments"
    private const val INSPECTOR = "inspector"

    private const val TREE_W_DP = 100f
    private const val TOC_W_DP = 120f
    private const val NOTES_W_DP = 90f
    private const val BOTTOM_H_DP = 90f
    private const val SPLITTER_DRAG_DP = 40f
    private const val SPLITTER_TOLERANCE_PX = 6f
    private const val WEIGHT_SUM_TOLERANCE = 0.01f
    private const val RESTORE_ROUNDS = 3

    /** How far inside the layout's edge the dragged palette's own edge is aimed. */
    private const val EDGE_INSET_PX = 8f

    /** Where in a neighbour a drop aims to land ahead of it: well inside its outer half. */
    private const val OUTER_HALF = 0.8f

    /** A drag that stays on the panel it started from. */
    private const val OWN_NUDGE_PX = 6f

    /** Into the content, in dp from the layout's left edge: past the strip, short of the right ranks. */
    private const val CONTENT_AIM_DP = 120f

    /** Enough to pass the touch slop and publish a ghost. */
    private const val PANEL_DRAG_STEP_PX = 24f

    /** How far inside a panel's leading edge a grab is taken. */
    private const val GRAB_EDGE_INSET_DP = 8f
}
