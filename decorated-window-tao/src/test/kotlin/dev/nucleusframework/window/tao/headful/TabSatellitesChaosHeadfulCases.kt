package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteDragOrigin
import dev.nucleusframework.window.tao.SatellitePlacement
import kotlin.math.abs

/**
 * The composed archetype under pressure: gestures from both workspaces in
 * flight at once, storms of tab changes, both layouts persisted together, and
 * everything closed at the same time.
 *
 * The wiring itself — which window owns which palette, and what a dock does to
 * it — is pinned by [TabSatellitesHeadfulCases]. What is left here is what
 * happens when the two archetypes are asked to act *simultaneously*: a tab drag
 * and a palette drag over the same desktop, a strip and a dock zone competing
 * for a point, a window emptied while its palette is docked into it.
 */
internal object TabSatellitesChaosHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aTabDragAndAPaletteDragInFlightAtOnce(),
            aTabMergesIntoAWindowWhosePaletteIsDocked(),
            aStripPointIsNeverADockZone(),
            tearingOffATabOutOfAWindowWithADockedPalette(),
            aStormOfTabChangesLeavesOnePaletteBodyPerWindow(),
            bothLayoutsSaveAndRestoreTogether(),
            closingEveryTabTakesEveryPaletteWithIt(),
            aPaletteDeclaredForAWindowThatNeverOpensIsNoLeak(),
        )

    /**
     * A tab drag in one window and a palette drag in another, both in flight.
     * They belong to different workspaces and must not clear each other's
     * feedback or act on each other's release.
     */
    private fun aTabDragAndAPaletteDragInFlightAtOnce(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab satellites a tab drag and a palette drag in flight at once",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta", "Gamma")
                val home = requireNotNull(fixture.tabs.groups.first())
                val palette = awaitFloatingPalette(fixture, home)
                val palettes = fixture.palettesOf(home.id)
                val layout = requireNotNull(palettes.dockHostGeometry(first)?.layoutScreenRectPx())

                // The palette, grabbed by its header and held over a dock zone.
                val outer = requireNotNull(palette.outerBoundsPx())
                val paletteGrab =
                    Offset(outer[0] + outer[RECT_W] / 2f, outer[1] + HEADER_GRAB_Y_DP * first.scaleFactor)
                val paletteDrag =
                    requireNotNull(
                        palettes.beginDrag(
                            fixture.paletteId(home.id),
                            SatelliteDragOrigin.FloatingWindow(palette),
                            paletteGrab,
                        ),
                    ) { "the palette drag must start" }
                val zone = Offset(layout.right - DROP_INSET_PX, layout.center.y)
                paletteDrag.update(zone)
                check(palettes.dockPreview?.side == DockSide.Right) {
                    "the right zone is not previewed: ${palettes.dockPreview}"
                }

                // And a tab, at the same time, out of the same window.
                val gamma = fixture.tabId("Gamma")
                val strip = requireNotNull(fixture.tabs.stripGeometry(home)?.layoutScreenRectPx())
                val tabGrab = requireNotNull(tabCenterOnScreenPx(fixture, "Gamma"))
                val away = Offset(strip.center.x, strip.bottom + TAB_DROP_FAR_PX)
                val tabDrag =
                    requireNotNull(fixture.tabs.beginDrag(gamma, stripOrigin(first), tabGrab)) {
                        "the tab drag must start"
                    }
                tabDrag.update(tabGrab)
                tabDrag.update(away)

                check(fixture.tabs.draggedTab?.id == gamma) { "the tab drag was lost" }
                check(palettes.draggedSatellite?.id == fixture.paletteId(home.id)) {
                    "the tab drag cleared the palette drag"
                }
                check(palettes.dockPreview?.side == DockSide.Right) {
                    "the tab drag cleared the dock preview: ${palettes.dockPreview}"
                }

                // Released out of order: each acts on its own workspace only.
                tabDrag.end(away)
                awaitUntil("the tab landed in a window of its own") {
                    fixture.tabs.groups.size == 2 && fixture.groupOf("Gamma")?.ids == listOf(gamma)
                }
                check(palettes.draggedSatellite != null) { "the tab release ended the palette drag" }
                paletteDrag.end(zone)
                awaitUntil("the palette docked right") {
                    (
                        palettes.satellite(fixture.paletteId(home.id))?.placement
                            as? SatellitePlacement.Docked
                    )?.side == DockSide.Right
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.tabs.draggedTab == null && fixture.tabs.dragGhost == null) {
                    "tab drag feedback outlived the gestures"
                }
                check(palettes.draggedSatellite == null && palettes.dragGhost == null) {
                    "palette drag feedback outlived the gestures"
                }
            },
        )
    }

    /**
     * A docked panel takes width out of the tab body, not out of the strip.
     * Merging a tab into that window has to keep working, and the panel must
     * not move.
     */
    private fun aTabMergesIntoAWindowWhosePaletteIsDocked(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites a tab merges into a window whose palette is docked",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta")
                val home = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, home)
                val torn = tearOffTabWindow(fixture, "Beta", first)
                val tornWindow = requireNotNull(torn.window)
                awaitFloatingPalette(fixture, torn)

                fixture.palettesOf(home.id).dock(fixture.paletteId(home.id), DockSide.Right)
                awaitUntil("the first window's palette is docked") {
                    fixture.panelHost.value[home.id] === first
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val panelBefore =
                    requireNotNull(
                        fixture.palettesOf(home.id).satellite(fixture.paletteId(home.id))?.dockedBoundsInWindowPx,
                    )

                // Beta back into the docked window, dropped on its strip.
                val strip = requireNotNull(fixture.tabs.stripGeometry(home)?.layoutScreenRectPx())
                val target = Offset(strip.left + strip.width * MERGE_X_FRACTION, strip.center.y)
                val grab = requireNotNull(tabCenterOnScreenPx(fixture, "Beta"))
                val session =
                    requireNotNull(fixture.tabs.beginDrag(fixture.tabId("Beta"), stripOrigin(tornWindow), grab))
                session.update(grab)
                session.update(target)
                check(fixture.tabs.dropPreview?.group === home) {
                    "the docked window's strip did not preview the drop: ${fixture.tabs.dropPreview}"
                }
                session.end(target)
                awaitUntil("both tabs are back in the docked window") {
                    fixture.tabs.groups.size == 1 && home.ids.size == 2
                }
                awaitUntil("the panel is still docked in it") { fixture.panelHost.value[home.id] === first }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val panelAfter =
                    requireNotNull(
                        fixture.palettesOf(home.id).satellite(fixture.paletteId(home.id))?.dockedBoundsInWindowPx,
                    )
                check(abs(panelAfter.width - panelBefore.width) <= LAYOUT_TOLERANCE_PX) {
                    "the merge resized the panel: ${panelAfter.width} vs ${panelBefore.width}"
                }
                check(!fixture.hasPalettes(torn.id)) { "the emptied window's workspace was left behind" }
            },
        )
    }

    /**
     * The strip is in the title bar and the dock zones are inside the content,
     * so no point can be both. If they ever overlapped, dragging a tab across
     * the top of a window would dock a palette instead.
     */
    private fun aStripPointIsNeverADockZone(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites a point on the strip is never a dock zone",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta")
                val group = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, group)
                val palettes = fixture.palettesOf(group.id)
                awaitUntil("the dock layout published its geometry") {
                    palettes.dockHostGeometry(first)?.layoutScreenRectPx() != null
                }
                val strip = requireNotNull(fixture.tabs.stripGeometry(group)?.layoutScreenRectPx())
                val layout = requireNotNull(palettes.dockHostGeometry(first)?.layoutScreenRectPx())

                check(!strip.overlaps(layout)) { "the strip overlaps the dock layout: $strip vs $layout" }
                for (fraction in listOf(STRIP_HEAD_FRACTION, MERGE_X_FRACTION, 0.9f)) {
                    val point = Offset(strip.left + strip.width * fraction, strip.center.y)
                    check(palettes.dockTargetAt(point) == null) {
                        "a point on the strip resolves to a dock zone: $point"
                    }
                    check(fixture.tabs.dropTargetAt(point)?.group === group) {
                        "a point on the strip does not resolve to the strip: $point"
                    }
                }
                val zone = Offset(layout.right - DROP_INSET_PX, layout.center.y)
                check(palettes.dockTargetAt(zone)?.side == DockSide.Right) { "the right zone does not resolve" }
                check(fixture.tabs.dropTargetAt(zone) == null) { "a dock zone resolves as a strip drop" }
            },
        )
    }

    /**
     * Tearing a tab out of a window whose palette is docked. The new window
     * gets a floating palette of its own, and the docked one stays where it
     * is — two windows in two different palette states at once.
     */
    private fun tearingOffATabOutOfAWindowWithADockedPalette(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites tearing a tab out of a window whose palette is docked",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta")
                val home = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, home)
                fixture.palettesOf(home.id).dock(fixture.paletteId(home.id), DockSide.Bottom)
                awaitUntil("the palette is docked") { fixture.panelHost.value[home.id] === first }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val torn = tearOffTabWindow(fixture, "Beta", first)
                val tornPalette = awaitFloatingPalette(fixture, torn)
                awaitUntil("the first window's panel stayed docked") {
                    fixture.panelHost.value[home.id] === first
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(tornPalette !== fixture.floatingPalette.value[home.id]) {
                    "the two windows share a palette window"
                }
                check(fixture.palettesOf(torn.id).satellite(fixture.paletteId(torn.id))?.isDocked == false) {
                    "the new window's palette inherited the docked placement"
                }
                check(fixture.composedPalettes.value == 2) {
                    "${fixture.composedPalettes.value} palette bodies for two windows"
                }
                awaitUntil("each palette draws its own window's tab") {
                    fixture.paletteShows.value[home.id] == "Alpha" &&
                        fixture.paletteShows.value[torn.id] == "Beta"
                }
            },
        )
    }

    // ── 4. storms and shutdown ───────────────────────────────────────────

    /**
     * Hundreds of tab changes with no frame in between. The palette redraws
     * as fast as the selection moves, and at the end exactly one body per
     * window may be composing — the count is where a leak shows up.
     */
    private fun aStormOfTabChangesLeavesOnePaletteBodyPerWindow(): TaoWindowTestCase {
        val titles = listOf("Alpha", "Beta", "Gamma", "Delta")
        val fixture = TabSatellitesFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = "tab satellites a storm of tab changes leaves one palette body per window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabSatellites(fixture, *titles.toTypedArray())
                val group = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, group)
                val palette = requireNotNull(fixture.floatingPalette.value[group.id])
                val incarnationsBefore = fixture.paletteIncarnations.value[group.id]
                requireNotNull(fixture.paletteCounters.value[group.id]).value = SAVED_CLICKS

                repeat(SELECTION_STORM) { round ->
                    fixture.tabs.select(fixture.tabId(titles[round % titles.size]))
                }
                val last = titles[(SELECTION_STORM - 1) % titles.size]
                awaitUntil("the storm settled on $last") { fixture.paletteShows.value[group.id] == last }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(fixture.composedPalettes.value == 1) {
                    "the storm left ${fixture.composedPalettes.value} palette bodies"
                }
                check(fixture.composedBodies.value == 1) {
                    "the storm left ${fixture.composedBodies.value} tab bodies"
                }
                check(fixture.floatingPalette.value[group.id] === palette) {
                    "the storm recreated the palette window"
                }
                check(fixture.paletteIncarnations.value[group.id] == incarnationsBefore) {
                    "the storm rebuilt the palette body"
                }
                check(requireNotNull(fixture.paletteCounters.value[group.id]).value == SAVED_CLICKS) {
                    "the storm lost the palette's state"
                }
                check(fixture.liveWorkspaces == 1) { "the storm created ${fixture.liveWorkspaces} workspaces" }
            },
        )
    }

    /**
     * Both layouts persisted together, which is what an application actually
     * saves: which window holds which tabs, and where each window's palettes
     * were. Restoring has to bring the windows back *and* put their palettes
     * back in the state they were in.
     */
    private fun bothLayoutsSaveAndRestoreTogether(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites both layouts save and restore together",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta")
                val home = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, home)
                val torn = tearOffTabWindow(fixture, "Beta", first)
                awaitFloatingPalette(fixture, torn)
                fixture.palettesOf(home.id).dock(fixture.paletteId(home.id), DockSide.Left)
                awaitUntil("the first window's palette is docked") {
                    fixture.panelHost.value[home.id] === first
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val tabLayout = fixture.tabs.snapshot()
                val paletteLayouts = fixture.tabs.groups.associate { it.id to fixture.palettesOf(it.id).snapshot() }
                check(tabLayout.groups.size == 2) { "the tab snapshot missed a window" }
                check(paletteLayouts.size == 2) { "a window's palette layout was not captured" }

                // Everything back into one window, palettes floating again.
                fixture.palettesOf(home.id).undock(fixture.paletteId(home.id))
                awaitFloatingPalette(fixture, home)
                fixture.tabs.move(fixture.tabId("Beta"), home)
                awaitUntil("one window is left") { fixture.tabs.groups.size == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)

                // And the saved layout applied again.
                fixture.tabs.restore(tabLayout)
                awaitUntil("the two windows are back") {
                    fixture.tabs.groups.size == 2 &&
                        fixture.tabs.groups.all { (it.window?.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L }
                }
                for ((groupId, layout) in paletteLayouts) {
                    if (fixture.hasPalettes(groupId)) fixture.palettesOf(groupId).restore(layout)
                }
                awaitUntil("the first window's palette is docked again") {
                    fixture.panelHost.value[home.id] === first
                }
                awaitUntil("the other window's palette floats again") {
                    fixture.tabs.groups
                        .filter { it !== home }
                        .all { fixture.floatingPalette.value[it.id] != null }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.composedPalettes.value == 2) {
                    "${fixture.composedPalettes.value} palette bodies after the restore"
                }
                check(fixture.tabs.groups.sumOf { it.ids.size } == 2) {
                    "the restore lost a tab: ${fixture.tabs.groups.map { it.ids }}"
                }
            },
        )
    }

    /**
     * The application quitting: every tab closed at once, with palettes both
     * docked and floating. Nothing may be left composing, no workspace may
     * survive its window, and the last window has to be reported once.
     */
    private fun closingEveryTabTakesEveryPaletteWithIt(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab satellites closing every tab takes every palette with it",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta", "Gamma")
                val home = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, home)
                val torn = tearOffTabWindow(fixture, "Beta", first)
                awaitFloatingPalette(fixture, torn)
                fixture.palettesOf(torn.id).dock(fixture.paletteId(torn.id), DockSide.Right)
                awaitUntil("one palette docked, one floating") {
                    fixture.panelHost.value[torn.id] === torn.window &&
                        fixture.floatingPalette.value[home.id] != null
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                fixture.tabs.tabs
                    .map { it.id }
                    .forEach(fixture.tabs::close)
                awaitUntil("the tab workspace emptied") {
                    fixture.tabs.groups.isEmpty() && fixture.tabs.tabs.isEmpty()
                }
                awaitUntil("no body of either kind is composing") {
                    fixture.composedBodies.value == 0 && fixture.composedPalettes.value == 0
                }
                awaitUntil("every satellite workspace was forgotten") { fixture.liveWorkspaces == 0 }
                awaitUntil("the last window was reported once") { fixture.lastWindowClosedCount.value == 1 }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.lastWindowClosedCount.value == 1) {
                    "reported ${fixture.lastWindowClosedCount.value}× for one shutdown"
                }
                check(fixture.panelHost.value.isEmpty() && fixture.floatingPalette.value.isEmpty()) {
                    "a palette outlived every window"
                }
            },
        )
    }

    /**
     * A window emptied and refilled in the same breath — the shape of a
     * restore, and of a user closing the last tab and opening another. The
     * palettes of the window that went must not come back attached to the new
     * one, and the new window has to get palettes of its own.
     */
    private fun aPaletteDeclaredForAWindowThatNeverOpensIsNoLeak(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha"))
        return TaoWindowTestCase(
            name = "tab satellites a window emptied and refilled gets palettes of its own",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabSatellites(fixture, "Alpha")
                val first = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, first)
                requireNotNull(fixture.paletteCounters.value[first.id]).value = SAVED_CLICKS

                fixture.tabs.close(fixture.tabId("Alpha"))
                fixture.titles -= "Alpha"
                awaitUntil("everything went") {
                    fixture.tabs.groups.isEmpty() && fixture.composedPalettes.value == 0
                }
                awaitUntil("the workspace was forgotten") { fixture.liveWorkspaces == 0 }
                settle(SETTLE_AFTER_MAP_MILLIS)

                fixture.titles += "Delta"
                awaitUntil("a window opened for the new tab") { fixture.tabs.groups.size == 1 }
                val second = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, second)
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(fixture.liveWorkspaces == 1) { "${fixture.liveWorkspaces} workspaces for one window" }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies for one window"
                }
                awaitUntil("the new palette draws the new tab") {
                    fixture.paletteShows.value[second.id] == "Delta"
                }
                if (second.id != first.id) {
                    check(requireNotNull(fixture.paletteCounters.value[second.id]).value == 0) {
                        "the new window's palette came back with the old one's state"
                    }
                }
            },
        )
    }

    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
    private const val SELECTION_STORM = 200
}
