package dev.nucleusframework.window.tao.headful

import dev.nucleusframework.window.tao.DockSide
import kotlin.math.abs

/**
 * The two archetypes composed, on real windows: Chrome-like tabs where **each
 * tab window** owns a satellite workspace whose palette draws the tab that
 * window is showing — the shape of `examples/tab-satellites-demo`.
 *
 * Neither workspace knows about the other, which is exactly why they can go
 * wrong together:
 *
 *  1. **who owns what** — a palette belongs to a window, not to a tab, so a tab
 *     change must not create or destroy one, and a tab torn into a window of
 *     its own must arrive with palettes of its own;
 *  2. **windows going away** — the window a palette belongs to is created and
 *     destroyed by the *tab* workspace, so its satellite workspace has to go
 *     with it, and no other window's palettes may notice;
 *  3. **docking under tabs** — the dock layout lives inside the tab body, so
 *     docking, switching tab and moving the drawn tab elsewhere all re-host the
 *     same panel while its state has to stay put;
 *  4. **gestures at once** — a tab drag and a palette drag in flight over the
 *     same desktop, and a strip and a dock zone competing for a point;
 *  5. **storms and shutdown** — hundreds of tab changes, both layouts saved and
 *     restored together, and everything closed at once.
 */
internal object TabSatellitesHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            eachTabWindowOwnsOnePalette(),
            aTabChangeOnlyChangesWhatThePaletteDraws(),
            aTornOffTabArrivesWithPalettesOfItsOwn(),
            mergingWindowsBackTakesTheSecondWindowsPaletteWithIt(),
            aPaletteFollowsItsOwnWindowAndNotTheOther(),
            dockingAPaletteIntoItsOwnWindowKeepsItsState(),
            aDockedPaletteSurvivesATabChangeInItsWindow(),
            aDockedPaletteStaysWhenTheTabItDrewLeaves(),
            undockingLiftsThePaletteBackOffThePanel(),
            aWindowClosingTakesItsDockedPaletteAndNoOther(),
        )

    // ── 1. who owns what ─────────────────────────────────────────────────

    /**
     * The bootstrap of the composed archetype: the window the tabs opened
     * joined a workspace of its own and its palette is floating over it. One
     * window, one workspace, one palette — anything else and the two
     * archetypes are not actually wired together.
     */
    private fun eachTabWindowOwnsOnePalette(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites the first tab window owns one palette drawing its selected tab",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSatellites(fixture, "Alpha", "Beta")
                val group = requireNotNull(fixture.tabs.groups.firstOrNull())
                val palette = awaitFloatingPalette(fixture, group)

                check(fixture.liveWorkspaces == 1) { "${fixture.liveWorkspaces} workspaces for one window" }
                check(fixture.palettesOf(group.id).members == listOf(tabWindow)) {
                    "the workspace's members are not just its own window: " +
                        "${fixture.palettesOf(group.id).members.size} of them"
                }
                check(fixture.palettesOf(group.id).owner === tabWindow) { "the palette has the wrong owner" }
                check(palette !== tabWindow) { "the palette is not a window of its own" }
                awaitUntil("the palette draws the selected tab") {
                    fixture.paletteShows.value[group.id] == fixture.tabs.selectedTab(group)?.title
                }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies for one window"
                }
            },
        )
    }

    /**
     * The design decision the archetype rests on: a palette belongs to the
     * *window*. Switching tabs may change what it draws and nothing else — no
     * native window destroyed and recreated (the user sees that as a flash),
     * and no body rebuilt, which would lose everything in it.
     */
    private fun aTabChangeOnlyChangesWhatThePaletteDraws(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab satellites a tab change redraws the palette without recreating it",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                awaitTabSatellites(fixture, "Alpha", "Beta", "Gamma")
                val group = requireNotNull(fixture.tabs.groups.firstOrNull())
                val palette = awaitFloatingPalette(fixture, group)
                val incarnationsBefore = fixture.paletteIncarnations.value[group.id]
                requireNotNull(fixture.paletteCounters.value[group.id]).value = SAVED_CLICKS

                for (title in listOf("Beta", "Gamma", "Alpha")) {
                    fixture.tabs.select(fixture.tabId(title))
                    awaitUntil("the palette redrew for $title") {
                        fixture.paletteShows.value[group.id] == title
                    }
                    check(fixture.floatingPalette.value[group.id] === palette) {
                        "the palette window was recreated when the tab changed to $title"
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(fixture.paletteIncarnations.value[group.id] == incarnationsBefore) {
                    "the palette body was rebuilt by a tab change: " +
                        "${fixture.paletteIncarnations.value[group.id]} vs $incarnationsBefore"
                }
                check(requireNotNull(fixture.paletteCounters.value[group.id]).value == SAVED_CLICKS) {
                    "the palette lost its state on a tab change"
                }
                check(fixture.liveWorkspaces == 1) { "a tab change created a workspace" }
            },
        )
    }

    /**
     * A tab pulled into a window of its own arrives with a palette of its own:
     * two windows, two workspaces, two palettes, each drawing its own window's
     * selected tab. One shared palette would be the wrong archetype entirely.
     */
    private fun aTornOffTabArrivesWithPalettesOfItsOwn(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites a tab torn into its own window arrives with a palette of its own",
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

                check(fixture.liveWorkspaces == 2) { "${fixture.liveWorkspaces} workspaces for two windows" }
                check(fixture.palettesOf(torn.id).owner === tornWindow) {
                    "the new window's palette is owned by another window"
                }
                check(fixture.palettesOf(home.id).members == listOf(first)) {
                    "the first window's workspace picked up another window: " +
                        "${fixture.palettesOf(home.id).members.size} members"
                }
                awaitUntil("each palette draws its own window's tab") {
                    fixture.paletteShows.value[home.id] == "Alpha" &&
                        fixture.paletteShows.value[torn.id] == "Beta"
                }
                check(fixture.composedPalettes.value == 2) {
                    "${fixture.composedPalettes.value} palette bodies for two windows"
                }
                val palettes =
                    listOfNotNull(fixture.floatingPalette.value[home.id], fixture.floatingPalette.value[torn.id])
                check(palettes.size == 2 && palettes[0] !== palettes[1]) { "the two windows share one palette" }
            },
        )
    }

    /**
     * And back: a window emptied of tabs takes its palette, its workspace and
     * its native palette window with it. A workspace left behind is a leak
     * that keeps a window alive on a dead member.
     */
    private fun mergingWindowsBackTakesTheSecondWindowsPaletteWithIt(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites merging two windows back takes the second one's palette with it",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta")
                val home = requireNotNull(fixture.tabs.groups.first())
                val torn = tearOffTabWindow(fixture, "Beta", first)
                val tornPalette = awaitFloatingPalette(fixture, torn)
                var paletteDestroyed = false
                tornPalette.onDestroyed { paletteDestroyed = true }

                fixture.tabs.move(fixture.tabId("Beta"), home)
                awaitUntil("one window is left") { fixture.tabs.groups.size == 1 }
                awaitUntil("the second window's palette was destroyed") { paletteDestroyed }
                awaitUntil("its workspace was forgotten") { !fixture.hasPalettes(torn.id) }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(fixture.liveWorkspaces == 1) { "${fixture.liveWorkspaces} workspaces for one window" }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies after the merge"
                }
                check(fixture.floatingPalette.value[home.id] != null) { "the surviving palette went too" }
                awaitUntil("the survivor draws the tab that arrived") {
                    fixture.paletteShows.value[home.id] == "Beta"
                }
                check(home.ids.size == 2) { "the merge lost a tab: ${home.ids}" }
            },
        )
    }

    /**
     * Each palette is anchored to its own window: moving one window moves its
     * palette and leaves the other one exactly where it was.
     */
    private fun aPaletteFollowsItsOwnWindowAndNotTheOther(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites a palette follows its own window and ignores the other",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabSatellites(fixture, "Alpha", "Beta")
                val home = requireNotNull(fixture.tabs.groups.first())
                val homePalette = awaitFloatingPalette(fixture, home)
                val torn = tearOffTabWindow(fixture, "Beta", first)
                val tornWindow = requireNotNull(torn.window)
                val tornPalette = awaitFloatingPalette(fixture, torn)
                awaitUntil("both palettes captured their owner offset") {
                    listOf(home, torn).all { group ->
                        fixture
                            .palettesOf(group.id)
                            .satellite(fixture.paletteId(group.id))
                            ?.windowState
                            ?.offsetFromParent != null
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                val ownerBefore = requireNotNull(tornWindow.outerBoundsPx())
                val followerBefore = requireNotNull(tornPalette.outerBoundsPx())
                val strangerBefore = requireNotNull(homePalette.outerBoundsPx())
                val offsetX = followerBefore[0] - ownerBefore[0]
                val offsetY = followerBefore[1] - ownerBefore[1]

                val scale = tornWindow.scaleFactor.toDouble()
                tornWindow.setOuterPosition(
                    ownerBefore[0] / scale + MOVE_DELTA_DP,
                    ownerBefore[1] / scale + MOVE_DELTA_DP,
                )
                awaitUntil("its palette followed") {
                    val owner = tornWindow.outerBoundsPx() ?: return@awaitUntil false
                    val follower = tornPalette.outerBoundsPx() ?: return@awaitUntil false
                    owner[0] != ownerBefore[0] &&
                        abs((follower[0] - owner[0]) - offsetX) <= FOLLOW_TOLERANCE_PX &&
                        abs((follower[1] - owner[1]) - offsetY) <= FOLLOW_TOLERANCE_PX
                }
                settle()
                val strangerNow = requireNotNull(homePalette.outerBoundsPx())
                check(abs(strangerNow[0] - strangerBefore[0]) <= FOLLOW_TOLERANCE_PX) {
                    "the other window's palette moved with a window it does not belong to"
                }
            },
        )
    }

    // ── 2. docking under tabs ────────────────────────────────────────────

    /**
     * Docking inside the composed archetype: the panel lands in the dock
     * layout of the tab body, its floating window goes, and its saveable state
     * comes across — the whole point of the relocation machinery.
     */
    private fun dockingAPaletteIntoItsOwnWindowKeepsItsState(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites docking a palette into its tab window keeps its state",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSatellites(fixture, "Alpha", "Beta")
                val group = requireNotNull(fixture.tabs.groups.first())
                val palette = awaitFloatingPalette(fixture, group)
                requireNotNull(fixture.paletteCounters.value[group.id]).value = SAVED_CLICKS
                var floatingDestroyed = false
                palette.onDestroyed { floatingDestroyed = true }

                fixture.palettesOf(group.id).dock(fixture.paletteId(group.id), DockSide.Right)
                awaitUntil("the panel is hosted by the tab window") {
                    fixture.panelHost.value[group.id] === tabWindow
                }
                awaitUntil("the floating window went") { floatingDestroyed }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(requireNotNull(fixture.paletteCounters.value[group.id]).value == SAVED_CLICKS) {
                    "the palette lost its state on the way into the dock"
                }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies after docking one"
                }
                check(fixture.composedBodies.value == 1) { "the dock disturbed the tab body count" }
            },
        )
    }

    /**
     * The dock layout lives inside the tab body, so a tab change destroys the
     * layout the panel is in and builds another. The panel has to be re-hosted
     * into it with its state — and the window it belongs to must not change.
     */
    private fun aDockedPaletteSurvivesATabChangeInItsWindow(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab satellites a docked palette survives a tab change in its window",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSatellites(fixture, "Alpha", "Beta", "Gamma")
                val group = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, group)
                val workspace = fixture.palettesOf(group.id)
                workspace.dock(fixture.paletteId(group.id), DockSide.Bottom)
                awaitUntil("the panel is docked") { fixture.panelHost.value[group.id] === tabWindow }
                settle(SETTLE_AFTER_MAP_MILLIS)
                requireNotNull(fixture.paletteCounters.value[group.id]).value = SAVED_CLICKS

                for (title in listOf("Beta", "Gamma", "Alpha", "Beta")) {
                    fixture.tabs.select(fixture.tabId(title))
                    awaitUntil("the palette redrew for $title") {
                        fixture.paletteShows.value[group.id] == title
                    }
                    awaitUntil("and is still docked in the same window") {
                        fixture.panelHost.value[group.id] === tabWindow
                    }
                    check(requireNotNull(fixture.paletteCounters.value[group.id]).value == SAVED_CLICKS) {
                        "the docked palette lost its state switching to $title"
                    }
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.satellite(fixture.paletteId(group.id))?.isDocked == true) {
                    "the palette undocked itself across the tab changes"
                }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies after the tab changes"
                }
                check(fixture.floatingPalette.value[group.id] == null) { "a floating palette reappeared" }
            },
        )
    }

    /**
     * The panel belongs to the window, not to the tab it happens to be
     * drawing. Moving that tab into another window leaves the panel where it
     * is, drawing whatever the window shows now.
     */
    private fun aDockedPaletteStaysWhenTheTabItDrewLeaves(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites a docked palette stays put when the tab it drew moves away",
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
                fixture.palettesOf(home.id).dock(fixture.paletteId(home.id), DockSide.Left)
                awaitUntil("the panel is docked in the first window") {
                    fixture.panelHost.value[home.id] === first
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                requireNotNull(fixture.paletteCounters.value[home.id]).value = SAVED_CLICKS

                // The tab it is drawing goes to a window of its own.
                fixture.tabs.select(fixture.tabId("Beta"))
                awaitUntil("the panel draws Beta") { fixture.paletteShows.value[home.id] == "Beta" }
                val torn = tearOffTabWindow(fixture, "Beta", first)
                awaitUntil("the panel is still in the first window") {
                    fixture.panelHost.value[home.id] === first
                }
                awaitUntil("and now draws what that window shows") {
                    fixture.paletteShows.value[home.id] == "Alpha"
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(requireNotNull(fixture.paletteCounters.value[home.id]).value == SAVED_CLICKS) {
                    "the panel lost its state when the tab it drew left"
                }
                check(fixture.palettesOf(torn.id).satellite(fixture.paletteId(torn.id))?.isDocked == false) {
                    "the new window's own palette arrived docked"
                }
            },
        )
    }

    /** Undocking gives the palette a window back, over the panel it just was. */
    private fun undockingLiftsThePaletteBackOffThePanel(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites undocking lifts the palette back off its panel",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val tabWindow = awaitTabSatellites(fixture, "Alpha", "Beta")
                val group = requireNotNull(fixture.tabs.groups.first())
                awaitFloatingPalette(fixture, group)
                val workspace = fixture.palettesOf(group.id)
                val id = fixture.paletteId(group.id)

                workspace.dock(id, DockSide.Right)
                awaitUntil("docked") { fixture.panelHost.value[group.id] === tabWindow }
                settle(SETTLE_AFTER_MAP_MILLIS)
                requireNotNull(fixture.paletteCounters.value[group.id]).value = SAVED_CLICKS
                val panel = requireNotNull(workspace.satellite(id)?.dockedBoundsInWindowPx)

                workspace.undock(id)
                val lifted = awaitFloatingPalette(fixture, group)
                check(requireNotNull(fixture.paletteCounters.value[group.id]).value == SAVED_CLICKS) {
                    "the palette lost its state on the way out of the dock"
                }
                // The panel host clears when the docked body is disposed, which
                // is a frame behind the floating window being mapped.
                awaitUntil("the panel is no longer hosted") { fixture.panelHost.value[group.id] == null }
                check(workspace.satellite(id)?.dockHost == null) { "the entry still names a dock host" }
                val outer = requireNotNull(lifted.outerBoundsPx())
                val scale = lifted.scaleFactor
                check(abs(outer[RECT_W] - panel.width * scale / tabWindow.scaleFactor) <= LIFT_OFF_TOLERANCE_PX * 2) {
                    "the lifted window is ${outer[RECT_W]}px wide, the panel was ${panel.width}px"
                }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies after undocking"
                }
            },
        )
    }

    /**
     * A window with a docked palette, closed by the user. Its workspace, its
     * panel and its tabs go; the other window's palette must not so much as
     * blink.
     */
    private fun aWindowClosingTakesItsDockedPaletteAndNoOther(): TaoWindowTestCase {
        val fixture = TabSatellitesFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab satellites a window closing takes its docked palette and no other",
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
                fixture.palettesOf(torn.id).dock(fixture.paletteId(torn.id), DockSide.Top)
                awaitUntil("the second window's palette is docked") {
                    fixture.panelHost.value[torn.id] === tornWindow
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val survivorIncarnations = fixture.paletteIncarnations.value[home.id]
                var destroyed = false
                tornWindow.onDestroyed { destroyed = true }

                tornWindow.requestUserClose()
                awaitUntil("the window went with its tab") { destroyed && fixture.tabs.groups.size == 1 }
                awaitUntil("its workspace was forgotten") { !fixture.hasPalettes(torn.id) }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(fixture.panelHost.value[torn.id] == null) { "the closed window's panel outlived it" }
                check(fixture.liveWorkspaces == 1) { "${fixture.liveWorkspaces} workspaces after the close" }
                check(fixture.paletteIncarnations.value[home.id] == survivorIncarnations) {
                    "the surviving window's palette was rebuilt by another window closing"
                }
                check(fixture.floatingPalette.value[home.id] != null) { "the survivor's palette went too" }
                check(fixture.composedPalettes.value == 1) {
                    "${fixture.composedPalettes.value} palette bodies after the close"
                }
            },
        )
    }

    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
