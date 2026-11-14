package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide

/**
 * View models inside relocatable content, on real windows: they belong to the
 * tab or the satellite, not to the window composing it, so a tear-off, a move
 * back, a dock or an undock keeps the same instance, and only the content
 * going for good (a closed tab, a declaration leaving) clears it.
 *
 * `RelocatedViewModelStoreTest` covers the same rules on bare scenes; these
 * cases drive the workspaces' own moves between native windows.
 */
internal object RelocatedViewModelHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            tabViewModelFollowsItsTabAcrossWindows(),
            satelliteViewModelFollowsItsPanelAcrossHosts(),
        )

    private fun tabViewModelFollowsItsTabAcrossWindows(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab view models follow a tear-off and a move back, and go with their tab",
            skip = ::workspaceSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val alpha = fixture.tabId("Alpha")
                val beta = fixture.tabId("Beta")

                // Only the selected tab's body composes: show each in turn.
                workspace.select(alpha)
                awaitUntil("Alpha resolved a view model") { fixture.viewModels.value[alpha] != null }
                workspace.select(beta)
                awaitUntil("Beta resolved a view model") { fixture.viewModels.value[beta] != null }
                val alphaViewModel = requireNotNull(fixture.viewModels.value[alpha])
                val betaViewModel = requireNotNull(fixture.viewModels.value[beta])
                check(alphaViewModel !== betaViewModel) { "two tabs share one view model of the same class" }

                // ── tear-off: Beta's body is rebuilt in a new window ──
                val torn =
                    requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor)) {
                        "tear-off refused"
                    }
                awaitMappedStrip(fixture, torn)
                awaitUntil("Beta's body is composed in its own window only") {
                    fixture.composedIn.value[beta]
                        ?.singleOrNull()
                        ?.let { it !== first } == true
                }
                settle()
                check(fixture.viewModels.value[beta] === betaViewModel) {
                    "the torn-off tab got a new view model instead of its own"
                }
                check(fixture.clearedViewModels.isEmpty()) {
                    "a tear-off cleared view models: ${fixture.clearedViewModels.map { it.ownerId }}"
                }

                // ── move back into Alpha's window: the torn window goes ──
                workspace.move(beta, requireNotNull(fixture.groupOf("Alpha")))
                awaitUntil("Beta is back with Alpha, in one window") {
                    workspace.groups.size == 1 && fixture.composedIn.value[beta]?.singleOrNull() === first
                }
                settle()
                check(fixture.viewModels.value[beta] === betaViewModel) {
                    "moving the tab back gave it a new view model"
                }
                check(fixture.clearedViewModels.isEmpty()) {
                    "closing the window the tab left cleared view models: " +
                        fixture.clearedViewModels.map { it.ownerId }
                }

                // ── close: the tab and its view model go, Alpha's stays ──
                workspace.close(beta)
                awaitUntil("Beta's view model is cleared") { betaViewModel in fixture.clearedViewModels }
                settle()
                check(fixture.clearedViewModels.toList() == listOf(betaViewModel)) {
                    "closing Beta cleared ${fixture.clearedViewModels.map { it.ownerId }}"
                }
                // The app stops declaring the tab it closed; a declaration left
                // behind would bring it back as a new tab.
                fixture.titles.remove("Beta")
                settle()
                check(fixture.clearedViewModels.toList() == listOf(betaViewModel)) {
                    "dropping the closed tab's declaration cleared ${fixture.clearedViewModels.map { it.ownerId }}"
                }

                // ── declaration leaves: the tab keeps its place, its view model goes ──
                fixture.titles.remove("Alpha")
                awaitUntil("Alpha's view model is cleared") { alphaViewModel in fixture.clearedViewModels }
                check(fixture.disposedAfterClear.isEmpty()) {
                    "bodies disposed after their view model was cleared: ${fixture.disposedAfterClear.toList()}"
                }
                check(fixture.composedBodies.value == 0) {
                    "a body outlived its declaration: ${fixture.composedIn.value.mapValues { it.value.size }}"
                }
            },
        )
    }

    private fun satelliteViewModelFollowsItsPanelAcrossHosts(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val declared = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "satellite view models survive dock, undock and hide, and go with the declaration",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { if (declared.value) ToolsSatellite() } },
            driver = {
                val floating = awaitFloating(fixture)
                awaitUntil("the floating satellite resolved a view model") { fixture.viewModel.value != null }
                val probe = requireNotNull(fixture.viewModel.value)
                val workspace = fixture.workspace

                // ── dock: rebuilt in the case window's dock ──
                var destroyed = false
                floating.onDestroyed { destroyed = true }
                workspace.dock(SATELLITE_ID, DockSide.Right)
                awaitUntil("floating window destroyed after docking") { destroyed }
                awaitUntil("panel composed in the case window") { fixture.panelHost.value === window }
                settle()
                check(fixture.viewModel.value === probe) { "docking gave the satellite a new view model" }
                check(fixture.clearedViewModels.isEmpty()) { "docking cleared the satellite's view model" }

                // ── undock: rebuilt in a new floating window ──
                workspace.undock(SATELLITE_ID)
                val refloated = awaitFloating(fixture)
                check(refloated !== floating) { "undock reused the destroyed floating window" }
                settle()
                check(fixture.viewModel.value === probe) { "undocking gave the satellite a new view model" }
                check(fixture.clearedViewModels.isEmpty()) { "undocking cleared the satellite's view model" }

                // ── hide and show: a satellite close only hides it ──
                workspace.close(SATELLITE_ID)
                awaitUntil("the hidden satellite is no longer composed") { !fixture.isComposed }
                settle()
                check(fixture.clearedViewModels.isEmpty()) { "hiding the satellite cleared its view model" }
                workspace.open(SATELLITE_ID)
                awaitFloating(fixture)
                settle()
                check(fixture.viewModel.value === probe) { "re-opening the satellite gave it a new view model" }

                // ── declaration leaves: its view model goes once the content is off screen ──
                declared.value = false
                awaitUntil("the satellite's view model is cleared") { probe in fixture.clearedViewModels }
                check(!fixture.isComposed) { "the satellite's content outlived its declaration" }
            },
        )
    }
}
