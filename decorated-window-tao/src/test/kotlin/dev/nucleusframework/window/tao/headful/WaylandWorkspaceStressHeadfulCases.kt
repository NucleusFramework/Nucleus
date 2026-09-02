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
import dev.nucleusframework.window.tao.TabDropTarget
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TransferDrop

/**
 * The native-Wayland transfer drag under abuse: everything that happens
 * between a clean grab and a clean drop when the gesture is a platform
 * drag-and-drop session rather than a pointer the workspace can follow.
 *
 * Grouped by what is being stressed:
 *
 *  - **session identity** — superseded sessions, cancel, a double release, a
 *    record written after the release, a stale session acting late;
 *  - **lifecycle** — the owner window closing mid-session, the dock host
 *    closing, the satellite closed or the whole workspace hidden while a
 *    session is live, a maximize in the middle of one;
 *  - **concurrency** — two satellites of one workspace with sessions in
 *    flight at once, and a satellite session interleaved with a tab session;
 *  - **bursts** — dozens of begin/release pairs with no frame in between,
 *    which is what an abrupt gesture and a synthetic replay both look like
 *    from this side;
 *  - **churn** — repeated dock / undock and tear-off / merge, each of which
 *    creates and destroys a real OS window, checked against the live window
 *    count so a leak cannot hide;
 *  - **edge cases** — a panel with no published bounds, a drop naming a
 *    foreign host, an index past the end of a strip, a minimized host.
 *
 * Runs only on native Wayland; the pointer-driven counterparts of these are
 * [SatelliteWorkspaceStressHeadfulCases] and [TabWorkspaceStressHeadfulCases].
 */
@Suppress("LargeClass") // one method per real-window case, by design
internal object WaylandWorkspaceStressHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            supersededSessionIsInert(),
            cancelledSessionNeverActs(),
            doubleReleaseActsOnce(),
            recordWrittenAfterReleaseIsIgnored(),
            ownerClosingMidSessionStaysSane(),
            dockHostClosingMidSessionRehosts(),
            satelliteClosedMidSessionIsNotResurrected(),
            workspaceHiddenMidSessionStaysSane(),
            maximizeMidSessionStillDocks(),
            twoSatellitesInFlightAtOnce(),
            burstOfSessionsLeavesOneOutcome(),
            dockChurnLeaksNoWindows(),
            foreignHostRecordDocksThere(),
            panelWithoutBoundsStillCarriesACard(),
            minimizedHostTakesNoDrop(),
            tabSupersededAndCancelledSessions(),
            tabSourceWindowClosingMidSessionStaysSane(),
            tabClosedMidSessionIsNotResurrected(),
            tabOnlyTabWithoutRecordStaysPut(),
            tabIndexPastTheStripIsClamped(),
            tabTearOffChurnLeaksNoWindows(),
            satelliteAndTabSessionsInterleaved(),
        )

    // ── Session identity ─────────────────────────────────────────────────

    private fun supersededSessionIsInert(): TaoWindowTestCase =
        satelliteCase("native Wayland: a superseded transfer session is inert and the last one wins") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val first = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            val second = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            check(workspace.transferDrag === second) { "the workspace must publish the newest session" }

            // The first one still holds a record; releasing it must do nothing.
            first.drop = TransferDrop.Dock(DockTarget(window, DockSide.Left))
            first.end()
            settle()
            check(!requireNotNull(workspace.satellite(SATELLITE_ID)).isDocked) {
                "the superseded session docked the satellite"
            }
            check(workspace.transferDrag === second) { "the superseded session stole the live one" }

            second.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
            second.end()
            awaitUntil("docked right by the surviving session") { workspace.dockedSide() == DockSide.Right }
            check(workspace.publishesNoDragFeedback()) { "the finished session left feedback behind" }
        }

    private fun cancelledSessionNeverActs(): TaoWindowTestCase =
        satelliteCase("native Wayland: a cancelled transfer session never acts, even with a record") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
            session.cancel()
            check(workspace.publishesNoDragFeedback()) { "a cancelled session left feedback behind" }
            session.end()
            settle()
            check(!requireNotNull(workspace.satellite(SATELLITE_ID)).isDocked) {
                "a cancelled session acted on its record after the fact"
            }
            // Cancelling twice, and after the end: all no-ops.
            session.cancel()
            session.cancel()
            check(workspace.publishesNoDragFeedback()) { "repeated cancels published something" }
        }

    private fun doubleReleaseActsOnce(): TaoWindowTestCase =
        satelliteCase("native Wayland: releasing a transfer session twice docks it once") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Bottom))
            session.end()
            awaitUntil("docked bottom") { workspace.dockedSide() == DockSide.Bottom }
            awaitPanelIn(fixture, window)
            val hosts = fixture.composedHosts.value

            // A second release, and a third with a different record: both inert.
            session.end()
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Left))
            session.end()
            settle()
            check(workspace.dockedSide() == DockSide.Bottom) { "a repeated release moved the panel" }
            check(fixture.composedHosts.value == hosts) { "a repeated release duplicated the host" }
        }

    private fun recordWrittenAfterReleaseIsIgnored(): TaoWindowTestCase =
        satelliteCase("native Wayland: a record written after the release is ignored") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            session.end()
            settle()
            check(!requireNotNull(workspace.satellite(SATELLITE_ID)).isDocked) { "a dropless release docked it" }
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
            settle()
            check(!requireNotNull(workspace.satellite(SATELLITE_ID)).isDocked) {
                "a late record docked the satellite without a release"
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────────────

    private fun ownerClosingMidSessionStaysSane(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val dialogVisible = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "native Wayland: the owner closing mid-session leaves the workspace consistent",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = { secondMemberBody(fixture) },
            dialogVisible = dialogVisible,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val workspace = fixture.workspace
                awaitFloatingOnWayland(fixture)
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { workspace.members.size == 2 }
                // Pinned rather than focused: keyboard focus is the
                // compositor's to give on Wayland, and a client asking for it
                // is within its rights to be refused — so a case that needs a
                // particular owner names it instead of racing activation.
                workspace.pinTo(dialog)
                awaitUntil("the dialog is the owner") { workspace.owner === dialog }
                val owned = awaitFloatingOnWayland(fixture)

                val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(owned)))
                var dialogDestroyed = false
                dialog.onDestroyed { dialogDestroyed = true }
                dialogVisible.value = false
                awaitUntil("the owner was destroyed mid-session") { dialogDestroyed }
                // The record names the window that is gone: acting on it must
                // not resurrect it, and must not take the satellite with it.
                session.drop = TransferDrop.Dock(DockTarget(dialog, DockSide.Right))
                session.end()
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.publishesNoDragFeedback()) { "a session across a closing owner left feedback" }
                check(workspace.owner === window) { "the owner did not fall back to the surviving member" }
                awaitUntil("the satellite is still hosted somewhere") { fixture.isComposed }
            },
        )
    }

    private fun dockHostClosingMidSessionRehosts(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val dialogVisible = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "native Wayland: a panel whose host closes mid-session moves to the surviving member",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = { secondMemberBody(fixture) },
            dialogVisible = dialogVisible,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val workspace = fixture.workspace
                val floating = awaitFloatingOnWayland(fixture)
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { workspace.members.size == 2 }
                requireNotNull(fixture.counter.value).value = SAVED_CLICKS

                workspace.transferDrop(floatingOrigin(floating), DockTarget(dialog, DockSide.Bottom))
                awaitPanelIn(fixture, dialog)

                val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, panelOrigin(dialog)))
                var dialogDestroyed = false
                dialog.onDestroyed { dialogDestroyed = true }
                dialogVisible.value = false
                awaitUntil("the host was destroyed mid-session") { dialogDestroyed }
                session.end()
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.publishesNoDragFeedback()) { "a session across a closing host left feedback" }
                awaitUntil("the satellite is hosted by the surviving member") { fixture.isComposed }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "the satellite lost its state when its host closed mid-session"
                }
            },
        )
    }

    private fun satelliteClosedMidSessionIsNotResurrected(): TaoWindowTestCase =
        satelliteCase("native Wayland: a satellite closed mid-session is not resurrected by the release") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            workspace.close(SATELLITE_ID)
            awaitUntil("the closed satellite left composition") { !fixture.isComposed }
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
            session.end()
            settle(SETTLE_AFTER_MAP_MILLIS)
            check(!fixture.isComposed) { "the release brought a closed satellite back on screen" }
            check(requireNotNull(workspace.satellite(SATELLITE_ID)).isOpen.not()) { "the release reopened it" }
            // Reopening honours the placement the release recorded.
            workspace.open(SATELLITE_ID)
            awaitUntil("reopened as the docked panel the drop asked for") {
                fixture.panelHost.value === window && workspace.dockedSide() == DockSide.Right
            }
        }

    private fun workspaceHiddenMidSessionStaysSane(): TaoWindowTestCase =
        satelliteCase("native Wayland: a session across a workspace visibility toggle leaves no feedback") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            workspace.visible = false
            awaitUntil("everything left composition") { !fixture.isComposed }
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Left))
            session.end()
            workspace.visible = true
            awaitUntil("composed again") { fixture.isComposed }
            settle(SETTLE_AFTER_MAP_MILLIS)
            check(workspace.publishesNoDragFeedback()) { "a session across a visibility toggle left feedback" }
            check(workspace.dockedSide() == DockSide.Left) { "the recorded dock was lost across the toggle" }
        }

    private fun maximizeMidSessionStillDocks(): TaoWindowTestCase =
        satelliteCase("native Wayland: a maximize mid-session still docks on release") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            window.setMaximized(true)
            awaitUntil("the satellite hid itself under the maximized owner") { entry.windowState.isHiddenByParent }
            session.drop = TransferDrop.Dock(DockTarget(window, DockSide.Top))
            session.end()
            awaitUntil("docked into the maximized owner") { workspace.dockedSide() == DockSide.Top }
            awaitPanelIn(fixture, window)
            window.setMaximized(false)
            settle(SETTLE_AFTER_MAP_MILLIS)
            check(workspace.dockedSide() == DockSide.Top) { "the restore undid the dock" }
            check(fixture.isComposed) { "the panel left composition across the restore" }
        }

    // ── Concurrency ──────────────────────────────────────────────────────

    private fun twoSatellitesInFlightAtOnce(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val second = SecondSatellite()
        return TaoWindowTestCase(
            name = "native Wayland: two satellites of one workspace, sessions in flight at once",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = {
                with(fixture) { ToolsSatellite() }
                with(second) { Declare(fixture.workspace) }
            },
            driver = {
                val workspace = fixture.workspace
                val floating = awaitFloatingOnWayland(fixture)
                awaitUntil("the second satellite was declared") { workspace.satellite(SECOND_SATELLITE_ID) != null }

                // One workspace publishes one drag: the second begin supersedes
                // the first even though it is a different satellite.
                val firstSession = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
                val secondSession =
                    requireNotNull(workspace.beginTransferDrag(SECOND_SATELLITE_ID, floatingOrigin(floating)))
                check(workspace.draggedSatellite?.id == SECOND_SATELLITE_ID) {
                    "the workspace must publish the newest satellite: ${workspace.draggedSatellite?.id}"
                }
                firstSession.drop = TransferDrop.Dock(DockTarget(window, DockSide.Left))
                firstSession.end()
                settle()
                check(!requireNotNull(workspace.satellite(SATELLITE_ID)).isDocked) {
                    "the superseded satellite's session still docked it"
                }

                secondSession.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
                secondSession.end()
                awaitUntil("the second satellite docked right") {
                    requireNotNull(workspace.satellite(SECOND_SATELLITE_ID)).isDocked
                }
                settle(SETTLE_AFTER_MAP_MILLIS)

                // Then the first one, cleanly, into the other side: two panels.
                workspace.transferDrop(
                    floatingOrigin(requireNotNull(fixture.floatingWindow.value)),
                    DockTarget(window, DockSide.Left),
                )
                awaitPanelIn(fixture, window)
                check(workspace.dockedSide() == DockSide.Left) { "the first satellite is not docked left" }
                check(workspace.satellites.count { it.isDocked } == 2) { "both satellites should be docked now" }
                check(workspace.publishesNoDragFeedback()) { "two finished sessions left feedback behind" }
            },
        )
    }

    private fun satelliteAndTabSessionsInterleaved(): TaoWindowTestCase {
        val satellites = SatelliteWorkspaceFixture()
        val tabs = TabWorkspaceFixture()
        return TaoWindowTestCase(
            name = "native Wayland: a satellite session and a tab session interleave without interfering",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { satellites.Body() },
            applicationContent = {
                with(satellites) { ToolsSatellite() }
                with(tabs) { Windows() }
            },
            driver = {
                val floating = awaitFloatingOnWayland(satellites)
                val tabWindow = awaitTabWindowsOnWayland(tabs, "Alpha", "Beta")
                val beta = tabs.tabId("Beta")

                // Both live at once: two workspaces, two independent sessions.
                val satelliteSession =
                    requireNotNull(satellites.workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
                val tabSession = requireNotNull(tabs.workspace.beginTransferDrag(beta, tabWindow))
                check(satellites.workspace.draggedSatellite != null) { "the satellite workspace dropped its drag" }
                check(tabs.workspace.draggedTab?.id == beta) { "the tab workspace dropped its drag" }

                // Released in the opposite order to the one they started in.
                tabSession.end()
                awaitTornOff(tabs, tabWindow, "Beta")
                check(satellites.workspace.draggedSatellite != null) { "the tab release cleared the satellite drag" }

                satelliteSession.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
                satelliteSession.end()
                awaitUntil("the satellite docked right") { satellites.workspace.dockedSide() == DockSide.Right }
                check(satellites.workspace.publishesNoDragFeedback()) { "the satellite workspace kept feedback" }
                check(tabs.workspace.draggedTab == null && tabs.workspace.dragGhost == null) {
                    "the tab workspace kept feedback"
                }
                check(tabs.workspace.groups.size == 2) { "the torn-off tab window went away" }
            },
        )
    }

    // ── Bursts and churn ─────────────────────────────────────────────────

    private fun burstOfSessionsLeavesOneOutcome(): TaoWindowTestCase =
        satelliteCase("native Wayland: a burst of sessions with no frame in between leaves one outcome") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val sides = DockSide.entries

            // No settle anywhere in here: every begin, record and release lands
            // in the same frame, which is what an abrupt gesture looks like
            // from this side of the session.
            repeat(BURST_SESSIONS) { i ->
                val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
                session.drop = TransferDrop.Dock(DockTarget(window, sides[i % sides.size]))
                if (i % 3 == 0) session.cancel() else session.end()
            }
            val last = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(floating)))
            last.drop = TransferDrop.Dock(DockTarget(window, DockSide.Right))
            last.end()
            awaitUntil("the last release of the burst is the one that stuck") {
                workspace.dockedSide() == DockSide.Right
            }
            awaitPanelIn(fixture, window)
            check(workspace.publishesNoDragFeedback()) { "the burst left feedback behind" }
            check(fixture.composedHosts.value == 1) { "the burst left more than one host composing" }
        }

    private fun dockChurnLeaksNoWindows(): TaoWindowTestCase =
        satelliteCase("native Wayland: dock and undock churn leaks no windows and keeps the state") { fixture ->
            val workspace = fixture.workspace
            awaitFloatingOnWayland(fixture)
            requireNotNull(fixture.counter.value).value = SAVED_CLICKS
            settle()
            val baseline = TaoApplication.liveWindowCount()

            repeat(CHURN_CYCLES) { cycle ->
                val floating = requireNotNull(fixture.floatingWindow.value) { "no floating window in cycle $cycle" }
                workspace.transferDrop(
                    floatingOrigin(floating),
                    DockTarget(window, DockSide.entries[cycle % DockSide.entries.size]),
                )
                awaitPanelIn(fixture, window)
                workspace.transferDrop(panelOrigin(window), target = null)
                awaitUntil("floating again in cycle $cycle") {
                    val now = fixture.floatingWindow.value
                    now != null && (now.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L
                }
            }
            settle(SETTLE_AFTER_MAP_MILLIS)
            val now = TaoApplication.liveWindowCount()
            check(now <= baseline) { "$CHURN_CYCLES churn cycles leaked windows: $baseline → $now" }
            check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                "the churn lost the saveable state: ${fixture.counter.value?.value}"
            }
            check(workspace.publishesNoDragFeedback()) { "the churn left feedback behind" }
        }

    // ── Edge cases ───────────────────────────────────────────────────────

    private fun foreignHostRecordDocksThere(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "native Wayland: a record naming another member docks the satellite into that window",
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = { secondMemberBody(fixture) },
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val workspace = fixture.workspace
                val floating = awaitFloatingOnWayland(fixture)
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { workspace.members.size == 2 }
                check(workspace.owner === window) { "the case window should own the satellite to start with" }

                // The owner is one window, the drop names the other: the record
                // decides, since it is the window the pointer was actually over.
                workspace.transferDrop(floatingOrigin(floating), DockTarget(dialog, DockSide.Left))
                awaitUntil("the entry records the foreign host") {
                    val entry = requireNotNull(workspace.satellite(SATELLITE_ID))
                    entry.isDocked && entry.dockHost === dialog
                }
                awaitPanelIn(fixture, dialog)
                check(workspace.zoneProbe(dialog, DockSide.Left) == DockSide.Left) {
                    "the foreign host published no usable layout"
                }

                // And back into the first window, from the foreign panel — a
                // dock-to-dock host change, with no floating window in between.
                workspace.transferDrop(panelOrigin(dialog), DockTarget(window, DockSide.Right))
                awaitUntil("the entry records the case window as its host") {
                    requireNotNull(workspace.satellite(SATELLITE_ID)).dockHost === window
                }
                awaitPanelIn(fixture, window)
                check(workspace.dockedSide() == DockSide.Right) { "the panel did not move to the other window" }
                check(fixture.composedHosts.value == 1) { "the host change left two panels composing" }
            },
        )
    }

    private fun panelWithoutBoundsStillCarriesACard(): TaoWindowTestCase =
        satelliteCase("native Wayland: a panel with no published bounds still carries a sized card") { fixture ->
            val workspace = fixture.workspace
            val floating = awaitFloatingOnWayland(fixture)
            val entry = requireNotNull(workspace.satellite(SATELLITE_ID))

            // Docked through the API, and the session started in the same frame:
            // the panel has not been laid out yet, so its bounds are unknown.
            workspace.dock(SATELLITE_ID, DockSide.Right)
            entry.dockedBoundsInWindowPx = null
            val session = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, panelOrigin(window)))
            check(session.ghostSizePx.width > 0f && session.ghostSizePx.height > 0f) {
                "the card fell back to an empty size: ${session.ghostSizePx}"
            }
            session.cancel()

            // The same for a floating window that is not mapped yet.
            workspace.transferDrop(panelOrigin(window), target = null)
            awaitUntil("floating again") { fixture.floatingWindow.value != null }
            val fresh = requireNotNull(fixture.floatingWindow.value)

            @Suppress("UNUSED_VARIABLE")
            val floatingSession = requireNotNull(workspace.beginTransferDrag(SATELLITE_ID, floatingOrigin(fresh)))
            check(floatingSession.ghostSizePx.width > 0f) { "the card has no width: ${floatingSession.ghostSizePx}" }
            check(floatingSession.ghostSizePx.height > 0f) { "the card has no height: ${floatingSession.ghostSizePx}" }
            floatingSession.cancel()
            check(workspace.publishesNoDragFeedback()) { "the cancelled sessions left feedback behind" }
        }

    private fun minimizedHostTakesNoDrop(): TaoWindowTestCase =
        satelliteCase("native Wayland: a minimized host publishes no layout to drop onto") { fixture ->
            val workspace = fixture.workspace
            awaitFloatingOnWayland(fixture)
            awaitUntil("the dock layout published its bounds") {
                workspace.dockHostGeometry(window)?.layoutBoundsInWindowPx?.isEmpty == false
            }
            val geometry = requireNotNull(workspace.dockHostGeometry(window))
            check(!geometry.minimized()) { "the host should start un-minimized" }

            window.setMinimized(true)
            awaitUntil("the host reports itself minimized") { geometry.minimized() }
            // A minimized window is off screen: the compositor sends it no drag
            // events at all, which is what makes it an impossible target.
            check(workspace.dockTargetAt(Offset.Zero) == null) { "a minimized host was offered as a screen target" }
            window.setMinimized(false)
            awaitUntil("the host is back") { !geometry.minimized() }
            check(workspace.zoneProbe(window, DockSide.Right) == DockSide.Right) {
                "the restored host publishes no usable layout"
            }
        }

    // ── Tabs ─────────────────────────────────────────────────────────────

    private fun tabSupersededAndCancelledSessions(): TaoWindowTestCase =
        tabCase("native Wayland: superseded and cancelled tab sessions never act") { fixture ->
            val workspace = fixture.workspace
            val first = awaitTabWindowsOnWayland(fixture, "Alpha", "Beta")
            val beta = fixture.tabId("Beta")
            val alpha = fixture.tabId("Alpha")
            val group = requireNotNull(fixture.groupOf("Beta"))

            val superseded = requireNotNull(workspace.beginTransferDrag(beta, first))
            val live = requireNotNull(workspace.beginTransferDrag(alpha, first))
            check(workspace.draggedTab?.id == alpha) { "the workspace must publish the newest tab" }
            superseded.drop = TabDropTarget(group, 0)
            superseded.end()
            settle()
            check(workspace.groups.size == 1) { "the superseded session tore a window off" }

            live.cancel()
            live.end()
            settle()
            check(workspace.groups.size == 1) { "the cancelled session tore a window off" }
            check(workspace.draggedTab == null && workspace.dragGhost == null && workspace.dropPreview == null) {
                "the cancelled session left feedback behind"
            }
            check(group.ids == listOf(alpha, beta)) { "the strip order changed: ${group.ids}" }
        }

    private fun tabSourceWindowClosingMidSessionStaysSane(): TaoWindowTestCase =
        tabCase("native Wayland: a tab session whose source window closes mid-flight stays sane") { fixture ->
            val workspace = fixture.workspace
            val first = awaitTabWindowsOnWayland(fixture, "Alpha", "Beta")
            val beta = fixture.tabId("Beta")

            // Tear Beta off, then start a session from its own window and close
            // that window under it.
            requireNotNull(workspace.beginTransferDrag(beta, first)).end()
            val torn = awaitTornOff(fixture, first, "Beta")
            val tornWindow = requireNotNull(torn.window)
            val session = requireNotNull(workspace.beginTransferDrag(beta, tornWindow))
            var destroyed = false
            tornWindow.onDestroyed { destroyed = true }
            workspace.close(beta)
            awaitUntil("the source window went with its last tab") { destroyed && workspace.groups.size == 1 }
            session.drop = TabDropTarget(requireNotNull(fixture.groupOf("Alpha")), 0)
            session.end()
            settle(SETTLE_AFTER_MAP_MILLIS)
            check(workspace.tab(beta) == null || workspace.tab(beta)?.group == null) {
                "the release resurrected a closed tab: ${workspace.tab(beta)?.group}"
            }
            check(workspace.groups.size == 1) { "the release opened a window for a closed tab" }
            check(workspace.draggedTab == null && workspace.dragGhost == null) { "feedback survived the close" }
        }

    private fun tabClosedMidSessionIsNotResurrected(): TaoWindowTestCase =
        tabCase("native Wayland: a tab closed mid-session is not resurrected by the release") { fixture ->
            val workspace = fixture.workspace
            val first = awaitTabWindowsOnWayland(fixture, "Alpha", "Beta")
            val beta = fixture.tabId("Beta")
            val group = requireNotNull(fixture.groupOf("Beta"))
            val session = requireNotNull(workspace.beginTransferDrag(beta, first))
            workspace.close(beta)
            awaitUntil("one tab left") { workspace.tabs.size == 1 }
            session.end()
            settle(SETTLE_AFTER_MAP_MILLIS)
            check(workspace.groups.size == 1) { "the release tore a window off for a closed tab" }
            check(group.ids == listOf(fixture.tabId("Alpha"))) { "the closed tab came back: ${group.ids}" }
        }

    private fun tabOnlyTabWithoutRecordStaysPut(): TaoWindowTestCase =
        tabCase(
            name = "native Wayland: the only tab of a window, released with no record, stays put",
            titles = listOf("Solo"),
        ) { fixture ->
            val workspace = fixture.workspace
            val window = awaitTabWindowsOnWayland(fixture, "Solo")
            val solo = fixture.tabId("Solo")
            val sizeBefore = requireNotNull(window.outerBoundsPx()).toList()

            repeat(SOLO_RELEASES) {
                requireNotNull(workspace.beginTransferDrag(solo, window)).end()
            }
            settle(SETTLE_AFTER_MAP_MILLIS)
            check(workspace.groups.size == 1) { "a dropless release of the only tab opened a window" }
            check(requireNotNull(fixture.groupOf("Solo")).window === window) { "the window was recreated" }
            val sizeAfter = requireNotNull(window.outerBoundsPx()).toList()
            check(sizeAfter[RECT_W] == sizeBefore[RECT_W] && sizeAfter[RECT_H] == sizeBefore[RECT_H]) {
                "the window was resized by a dropless release: $sizeBefore → $sizeAfter"
            }
            check(workspace.draggedTab == null && workspace.dragGhost == null) { "feedback survived the releases" }
        }

    private fun tabIndexPastTheStripIsClamped(): TaoWindowTestCase =
        tabCase(
            name = "native Wayland: a drop index past the end of a strip is clamped",
            titles = listOf("Alpha", "Beta", "Gamma"),
        ) { fixture ->
            val workspace = fixture.workspace
            val first = awaitTabWindowsOnWayland(fixture, "Alpha", "Beta", "Gamma")
            val alpha = fixture.tabId("Alpha")
            val group = requireNotNull(fixture.groupOf("Alpha"))

            val session = requireNotNull(workspace.beginTransferDrag(alpha, first))
            session.drop = TabDropTarget(group, index = ABSURD_INDEX)
            session.end()
            awaitUntil("Alpha moved to the end rather than out of range") { group.ids.lastOrNull() == alpha }
            check(group.ids.size == 3) { "a clamped drop lost a tab: ${group.ids}" }

            // And a negative one, the other way.
            val back = requireNotNull(workspace.beginTransferDrag(alpha, first))
            back.drop = TabDropTarget(group, index = -ABSURD_INDEX)
            back.end()
            awaitUntil("Alpha moved to the front") { group.ids.firstOrNull() == alpha }
            check(group.ids.size == 3) { "a clamped drop lost a tab: ${group.ids}" }
        }

    private fun tabTearOffChurnLeaksNoWindows(): TaoWindowTestCase =
        tabCase("native Wayland: tear-off and merge churn leaks no windows and keeps the state") { fixture ->
            val workspace = fixture.workspace
            val first = awaitTabWindowsOnWayland(fixture, "Alpha", "Beta")
            val beta = fixture.tabId("Beta")
            requireNotNull(fixture.counters.value[beta]).value = TAB_SAVED_CLICKS
            settle()
            val baseline = TaoApplication.liveWindowCount()

            repeat(CHURN_CYCLES) { cycle ->
                val source = requireNotNull(fixture.groupOf("Beta")?.window) { "no source window in cycle $cycle" }
                requireNotNull(workspace.beginTransferDrag(beta, source)).end()
                val torn = awaitTornOff(fixture, first, "Beta")
                val merge = requireNotNull(workspace.beginTransferDrag(beta, requireNotNull(torn.window)))
                merge.drop = TabDropTarget(requireNotNull(fixture.groupOf("Alpha")), 1)
                merge.end()
                awaitUntil("merged back in cycle $cycle") { workspace.groups.size == 1 }
                settle(JUMP_SETTLE_MILLIS)
            }
            settle(SETTLE_AFTER_MAP_MILLIS)
            val now = TaoApplication.liveWindowCount()
            check(now <= baseline) { "$CHURN_CYCLES tear-off cycles leaked windows: $baseline → $now" }
            check(requireNotNull(fixture.counters.value[beta]).value == TAB_SAVED_CLICKS) {
                "the churn lost Beta's saveable state: ${fixture.counters.value[beta]?.value}"
            }
        }

    // ── Case scaffolding ─────────────────────────────────────────────────

    /** A one-window satellite case: the fixture's dock layout plus its satellite. */
    private fun satelliteCase(
        name: String,
        driver: suspend TaoWindowTestScope.(SatelliteWorkspaceFixture) -> Unit,
    ): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = name,
            skip = ::waylandSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = { driver(fixture) },
        )
    }

    /** A tab-workspace case: the workspace's own windows, next to an idle case window. */
    private fun tabCase(
        name: String,
        titles: List<String> = listOf("Alpha", "Beta"),
        driver: suspend TaoWindowTestScope.(TabWorkspaceFixture) -> Unit,
    ): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = titles)
        return TaoWindowTestCase(
            name = name,
            skip = ::waylandSkipReason,
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = { driver(fixture) },
        )
    }

    /** A second workspace member: joins, and hosts a dock layout of its own. */
    @androidx.compose.runtime.Composable
    private fun secondMemberBody(fixture: SatelliteWorkspaceFixture) {
        JoinSatelliteWorkspace(fixture.workspace)
        DockLayout(fixture.workspace, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF3C8D5A)))
        }
    }

    /** Enough sessions in one frame to expose a stale one, few enough to stay quick. */
    private const val BURST_SESSIONS = 24

    /** Releases of the only tab of a window: each one must be a no-op. */
    private const val SOLO_RELEASES = 8

    /** Far past any strip's length, and its negative twin. */
    private const val ABSURD_INDEX = 99
}
