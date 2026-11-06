package dev.nucleusframework.window.tao.headful

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockTarget
import dev.nucleusframework.window.tao.Satellite
import dev.nucleusframework.window.tao.SatelliteDragOrigin
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteTransferDrag
import dev.nucleusframework.window.tao.SatelliteWorkspace
import dev.nucleusframework.window.tao.TabWindowGroup
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.TransferDrop
import dev.nucleusframework.window.tao.dockSideAt

// Shared scaffolding for the native-Wayland workspace cases.
//
// The pointer path is not driveable there — the compositor owns the pointer for
// the whole drag-and-drop session, and no test harness on this platform can
// inject into it (see the suite's notes on input injection) — so the cases
// drive the *session* the gesture starts and assert what each end of it does.
// Everything else is a real window: two toplevels, a real dock layout, real
// creation and destruction on every dock.

/** Runs only where [workspaceSkipReason] skips: native Wayland, no forced X11. */
internal fun waylandSkipReason(): String? =
    if (workspaceSkipReason() == null) "requires native Wayland (WAYLAND_DISPLAY, no forced X11)" else null

/**
 * Waits until the workspace's floating satellite window is mapped with a real
 * size, and returns it.
 *
 * The Wayland counterpart of [awaitFloating], which additionally waits for the
 * owner offset — a value that stays `null` here on purpose, since no client
 * can know where its windows are.
 */
internal suspend fun TaoWindowTestScope.awaitFloatingOnWayland(fixture: SatelliteWorkspaceFixture): TaoWindow {
    awaitUntil("owner window mapped") { bounds() != null }
    awaitUntil("floating satellite mapped with a real size") {
        val rect = fixture.floatingWindow.value?.outerBoundsPx() ?: return@awaitUntil false
        rect[RECT_W] > 0 && rect[RECT_H] > 0
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    val floating = requireNotNull(fixture.floatingWindow.value)
    check(floating.isNativeWaylandSurface) { "case premise: the satellite must be a native Wayland surface" }
    return floating
}

/** Waits until the satellite is composed as a panel in [host]. */
internal suspend fun TaoWindowTestScope.awaitPanelIn(
    fixture: SatelliteWorkspaceFixture,
    host: TaoWindow,
) {
    awaitUntil("panel composed in the expected host") {
        fixture.panelHost.value === host && fixture.panelBoundsPx.value != null
    }
    settle()
}

/**
 * Starts a transfer drag of the workspace's satellite from [origin] and
 * releases it on [target] — the whole gesture as the two ends of the session
 * see it, with no pointer in between.
 */
internal fun SatelliteWorkspace.transferDrop(
    origin: SatelliteDragOrigin,
    target: DockTarget?,
): SatelliteTransferDrag {
    val session = requireNotNull(beginTransferDrag(SATELLITE_ID, origin)) { "the transfer drag must start" }
    session.drop = target?.let { TransferDrop.Dock(it) }
    session.end()
    return session
}

/** The floating window's own drag origin. */
internal fun floatingOrigin(window: TaoWindow) = SatelliteDragOrigin.FloatingWindow(window)

/** A docked panel's drag origin in [host]. */
internal fun panelOrigin(host: TaoWindow) = SatelliteDragOrigin.DockedPanel(host)

/**
 * The dock zone [side] of [host]'s layout resolved the way the layout itself
 * does it — from a point in *window* coordinates, the only space an inbound
 * drag event speaks. `null` when the host published no layout yet.
 */
internal fun SatelliteWorkspace.zoneProbe(
    host: TaoWindow,
    side: DockSide,
): DockSide? {
    val geometry = dockHostGeometry(host) ?: return null
    val layout = geometry.layoutBoundsInWindowPx
    val inset = 1f
    val point =
        when (side) {
            DockSide.Left -> Offset(layout.left + inset, layout.center.y)
            DockSide.Right -> Offset(layout.right - inset, layout.center.y)
            DockSide.Top -> Offset(layout.center.x, layout.top + inset)
            DockSide.Bottom -> Offset(layout.center.x, layout.bottom - inset)
        }
    return dockSideAt(layout, point, SatelliteWorkspace.DockZoneWidth.value * geometry.scaleOrOne())
}

/** `true` while the workspace publishes no drag feedback of any kind. */
internal fun SatelliteWorkspace.publishesNoDragFeedback(): Boolean =
    draggedSatellite == null && dockPreview == null && dragGhost == null && transferDrag == null

/** The side the satellite is docked on, or `null` while it floats. */
internal fun SatelliteWorkspace.dockedSide(): DockSide? =
    (satellite(SATELLITE_ID)?.placement as? SatellitePlacement.Docked)?.side

/**
 * A second satellite of [fixture]'s workspace, so a case can put two sessions
 * in flight over one workspace. Publishes its host and its saveable counter
 * the same way the fixture's own satellite does.
 */
internal class SecondSatellite {
    val counter = mutableStateOf<MutableState<Int>?>(null)
    val isDocked = mutableStateOf(false)

    @Composable
    fun ApplicationScope.Declare(workspace: SatelliteWorkspace) {
        Satellite(
            workspace = workspace,
            id = SECOND_SATELLITE_ID,
            title = "Palette",
            initialPlacement =
                SatellitePlacement.Floating(
                    positioner = workspaceRightEdgePositioner(),
                    size = workspaceSatelliteSize(),
                ),
        ) {
            val clicks = rememberSaveable { mutableStateOf(0) }
            val hosted = isDocked
            SideEffect {
                counter.value = clicks
                this@SecondSatellite.isDocked.value = hosted
            }
        }
    }
}

internal const val SECOND_SATELLITE_ID = "palette"

/** Index of the width / height components of an `outerBoundsPx()` rect. */
internal const val RECT_W = 2
internal const val RECT_H = 3

/** [zoneProbe] at an explicit point rather than at a side's own strip. */
internal fun SatelliteWorkspace.zoneProbeAt(
    host: TaoWindow,
    pointInWindowPx: Offset,
): DockSide? {
    val geometry = dockHostGeometry(host) ?: return null
    val zonePx = SatelliteWorkspace.DockZoneWidth.value * geometry.scaleOrOne()
    return dockSideAt(geometry.layoutBoundsInWindowPx, pointInWindowPx, zonePx)
}

/** The Wayland counterpart of [awaitTabWindows]: no strip screen rect to wait for. */
internal suspend fun TaoWindowTestScope.awaitTabWindowsOnWayland(
    fixture: TabWorkspaceFixture,
    vararg titles: String,
): TaoWindow {
    awaitUntil("case window mapped") { bounds() != null }
    awaitUntil("every tab declared") { titles.all { fixture.workspace.tab(fixture.tabId(it)) != null } }
    awaitUntil("a tab window is mapped with a real size") {
        val tabWindow =
            fixture.workspace.groups
                .firstOrNull()
                ?.window ?: return@awaitUntil false
        val rect = tabWindow.outerBoundsPx() ?: return@awaitUntil false
        rect[RECT_W] > 0 && rect[RECT_H] > 0
    }
    awaitUntil("the selected tab's body is composed") { fixture.composedBodies.value > 0 }
    awaitUntil("the strip published its slots") {
        val group = fixture.workspace.groups.firstOrNull() ?: return@awaitUntil false
        group.slotsInWindowPx.size >= group.ids.size
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    val window =
        requireNotNull(
            fixture.workspace.groups
                .first()
                .window,
        )
    check(window.isNativeWaylandSurface) { "case premise: the tab window must be a native Wayland surface" }
    return window
}

/** Waits until [title] holds a window of its own, distinct from [from], and returns its group. */
internal suspend fun TaoWindowTestScope.awaitTornOff(
    fixture: TabWorkspaceFixture,
    from: TaoWindow,
    title: String,
): TabWindowGroup {
    val id = fixture.tabId(title)
    awaitUntil("a second window holds $title on its own") {
        fixture.workspace.groups.size >= 2 && fixture.groupOf(title)?.ids == listOf(id)
    }
    val torn = requireNotNull(fixture.groupOf(title))
    awaitUntil("the torn-off window is mapped and composing $title") {
        val tornWindow = torn.window ?: return@awaitUntil false
        tornWindow !== from &&
            (tornWindow.outerBoundsPx()?.get(RECT_W) ?: 0L) > 0L &&
            fixture.windowOf(title) != null
    }
    settle(SETTLE_AFTER_MAP_MILLIS)
    return torn
}

/** The card is sized off a live frame; one rounding step on each side. */
internal const val GHOST_TOLERANCE_PX = 4f
