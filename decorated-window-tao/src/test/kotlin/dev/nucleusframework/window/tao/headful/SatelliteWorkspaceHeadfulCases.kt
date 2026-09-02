package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockLayout
import dev.nucleusframework.window.tao.DockPanelHeaderHeight
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.DockTarget
import dev.nucleusframework.window.tao.JoinSatelliteWorkspace
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.Satellite
import dev.nucleusframework.window.tao.SatelliteDragOrigin
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

/**
 * Real-window coverage for the satellite workspace: `Satellite` hosted by a
 * `SatelliteWindow` while floating and by the owner's `DockLayout` while
 * docked, with the workspace deciding who owns what.
 *
 *  1. dock / undock round trip — the floating window is destroyed, the panel
 *     appears on the requested side of the host's content with the extent
 *     seeded from the window, `rememberSaveable` state survives both moves,
 *     and the undocked window lifts off exactly where the panel was;
 *  2. ownership follows focus between two members, and `pinTo` overrides it;
 *  3. a layout snapshot restores a docked panel, and the open / visible flags
 *     take the content in and out of composition;
 *  4. a satellite docked into a member that closes moves to the next owner;
 *  5. dragging the floating window's header into the owner's right dock zone
 *     docks it, and dragging the panel's header back over the content lifts
 *     it out under the pointer — with a real mouse (AWT Robot) where the host
 *     allows input injection, else by driving the same drag session directly;
 *  6. `rememberSaveable` state survives repeated host changes.
 *
 * The adversarial half — teleporting pointers, interrupted gestures, churn,
 * overlapping drags — lives in [SatelliteWorkspaceStressHeadfulCases].
 *
 * Native Wayland is skipped like the plain satellite cases: without client
 * positioning neither the anchoring nor the lift-off is observable.
 */
internal object SatelliteWorkspaceHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            dockAndUndockRoundTrip(),
            ownerFollowsFocusAndPin(),
            snapshotRestoresDockedLayout(),
            dockHostDeathRehostsPanel(),
            headerDragDocksAndLiftsOff(),
            titleBarDragOutsideTheHeaderStripDocks(),
            saveableStateSurvivesRepeatedHostChanges(),
        )

    private fun dockAndUndockRoundTrip(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace satellite docks into the owner and lifts off again with its state",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val floating = awaitFloating(fixture)
                val parentRect = requireNotNull(bounds())
                val floatingRect = requireNotNull(floating.outerBoundsPx())
                val scale = window.scaleFactor
                val expectedLeft = parentRect[0] + parentRect[2] + (GAP_DP * scale).toLong()
                check(abs(floatingRect[0] - expectedLeft) <= ANCHOR_TOLERANCE_PX) {
                    "floating satellite is not anchored to the owner's right edge: " +
                        "left=${floatingRect[0]} expected=$expectedLeft"
                }

                // Marked before the first dock: the document's own state, which
                // no dock or undock may reset.
                val documentState = requireNotNull(fixture.documentState.value) { "the document published no state" }
                documentState.value = DOCUMENT_MARK

                // State the docking must carry over. The registry keeps values in
                // memory, so the very same MutableState instance comes back in
                // the next host — only its value is asserted on.
                requireNotNull(fixture.counter.value).value = SAVED_CLICKS
                settle()

                // ── dock ──
                var destroyed = false
                floating.onDestroyed { destroyed = true }
                fixture.workspace.dock(SATELLITE_ID, DockSide.Right)
                awaitUntil("floating window destroyed after docking") { destroyed }
                awaitUntil("panel composed in the case window") {
                    fixture.panelHost.value === window && fixture.panelBoundsPx.value != null
                }
                settle()
                val entry = requireNotNull(fixture.workspace.satellite(SATELLITE_ID))
                check(entry.isDocked && entry.dockHost === window) { "entry not docked into the case window" }
                // The document itself must not have been rebuilt around the
                // new panel: its `remember` — a scroll position in a real app —
                // is the same instance with the same value.
                check(fixture.documentState.value === documentState) {
                    "docking the first panel recreated the document's subtree"
                }
                check(documentState.value == DOCUMENT_MARK) {
                    "the document lost its state when the panel docked: ${documentState.value}"
                }

                val panel = requireNotNull(fixture.panelBoundsPx.value)
                val container = requireNotNull(fixture.hostContentSizePx.value)
                check(abs(panel.right - container.width) <= LAYOUT_TOLERANCE_PX) {
                    "panel does not sit on the right edge: panel=$panel container=$container"
                }
                val expectedExtentPx = SATELLITE_W_DP * scale
                check(abs(panel.width - expectedExtentPx) <= LAYOUT_TOLERANCE_PX) {
                    "dock extent was not seeded from the floating width: ${panel.width} vs $expectedExtentPx"
                }
                val content = requireNotNull(fixture.contentBoundsPx.value)
                check(content.right <= panel.left && content.right > 0f) {
                    "document content was not narrowed by the docked panel: content=$content panel=$panel"
                }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "rememberSaveable state lost when docking: ${fixture.counter.value?.value}"
                }

                // ── undock: lifts off where the panel was ──
                fixture.workspace.undock(SATELLITE_ID)
                awaitUntil("floating window recreated") {
                    val now = fixture.floatingWindow.value
                    now != null && now !== floating && (now.outerBoundsPx()?.get(2) ?: 0L) > 0L
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val lifted = requireNotNull(requireNotNull(fixture.floatingWindow.value).outerBoundsPx())
                val hostOuter = requireNotNull(bounds())
                val clientX = hostOuter[0] + (hostOuter[2] - container.width) / 2.0
                val clientY = hostOuter[1] + (hostOuter[3] - container.height).toDouble()
                // [panel] is the content area below the docked header; the window
                // lifts off the whole panel, header included, so its frame starts
                // one header height above.
                val expectedX = clientX + panel.left
                val expectedY = clientY + panel.top - DockPanelHeaderHeight.value * scale
                check(
                    abs(lifted[0] - expectedX) <= LIFT_OFF_TOLERANCE_PX &&
                        abs(lifted[1] - expectedY) <= LIFT_OFF_TOLERANCE_PX,
                ) {
                    "undocked window did not lift off the panel: window=${lifted.toList()} " +
                        "expected≈($expectedX, $expectedY) host=${hostOuter.toList()} panel=$panel " +
                        "container=$container placement=${entry.placement}"
                }
                check(requireNotNull(fixture.counter.value).value == SAVED_CLICKS) {
                    "rememberSaveable state lost when undocking: ${fixture.counter.value?.value}"
                }
                check(!entry.isDocked && entry.dockHost == null) { "entry still reads as docked after undock" }
                check(fixture.documentState.value === documentState && documentState.value == DOCUMENT_MARK) {
                    "undocking the last panel recreated the document's subtree"
                }
            },
        )
    }

    private fun ownerFollowsFocusAndPin(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace owner follows focus between members and pinTo overrides it",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = { JoinSatelliteWorkspace(fixture.workspace) },
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                awaitFloating(fixture)
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { fixture.workspace.members.size == 2 }

                // ── focus picks the owner ──
                dialog.focus()
                awaitUntil("dialog became the owner") { fixture.workspace.owner === dialog }
                awaitFollows(fixture, dialog, "dialog")

                // ── pinning overrides focus ──
                fixture.workspace.pinTo(window)
                awaitUntil("case window pinned as owner") { fixture.workspace.owner === window }
                awaitFollows(fixture, window, "pinned case window")

                fixture.workspace.pinTo(null)
                awaitUntil("owner back to the last focused member") { fixture.workspace.owner === dialog }
            },
        )
    }

    private fun snapshotRestoresDockedLayout(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace snapshot restores a docked panel and open/visible flags gate the content",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val floating = awaitFloating(fixture)
                fixture.workspace.dock(SATELLITE_ID, DockSide.Left)
                awaitUntil("panel docked left") {
                    fixture.panelHost.value === window && fixture.panelBoundsPx.value != null
                }
                settle()
                val panelLeft = requireNotNull(fixture.panelBoundsPx.value)
                check(panelLeft.left <= LAYOUT_TOLERANCE_PX) { "panel is not on the left edge: $panelLeft" }
                val snapshot = fixture.workspace.snapshot()

                fixture.workspace.undock(SATELLITE_ID)
                awaitUntil("floating again") {
                    val now = fixture.floatingWindow.value
                    now != null && now !== floating && (now.outerBoundsPx()?.get(2) ?: 0L) > 0L
                }
                val refloated = requireNotNull(fixture.floatingWindow.value)
                var destroyed = false
                refloated.onDestroyed { destroyed = true }

                fixture.workspace.restore(snapshot)
                awaitUntil("restore docked the satellite again") {
                    destroyed && fixture.workspace.satellite(SATELLITE_ID)?.isDocked == true
                }
                awaitUntil("panel back in the case window") { fixture.panelHost.value === window && fixture.isComposed }

                // ── close / open ──
                fixture.workspace.close(SATELLITE_ID)
                awaitUntil("closed satellite leaves composition") { !fixture.isComposed }
                fixture.workspace.open(SATELLITE_ID)
                awaitUntil("opened satellite is composed again") { fixture.isComposed }

                // ── master visibility ──
                fixture.workspace.visible = false
                awaitUntil("hidden workspace leaves composition") { !fixture.isComposed }
                fixture.workspace.visible = true
                awaitUntil("visible workspace composes again") { fixture.isComposed }
                check(fixture.workspace.satellite(SATELLITE_ID)?.isDocked == true) {
                    "visibility toggling must not change the placement"
                }
            },
        )
    }

    private fun dockHostDeathRehostsPanel(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val dialogVisible = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "workspace panel docked into a closing member moves to the next owner",
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
                awaitFloating(fixture)
                val dialog = requireNotNull(dialogWindow)
                awaitUntil("both members joined") { fixture.workspace.members.size == 2 }
                dialog.focus()
                awaitUntil("dialog is the owner") { fixture.workspace.owner === dialog }

                fixture.workspace.dock(SATELLITE_ID, DockSide.Bottom)
                awaitUntil("panel docked into the dialog") { fixture.panelHost.value === dialog }
                settle()

                var dialogDestroyed = false
                dialog.onDestroyed { dialogDestroyed = true }
                dialogVisible.value = false
                awaitUntil("dialog destroyed") { dialogDestroyed }
                awaitUntil("panel rehosted in the case window") {
                    fixture.workspace.satellite(SATELLITE_ID)?.dockHost === window && fixture.panelHost.value === window
                }
                check(fixture.workspace.owner === window) { "owner did not fall back to the surviving member" }
            },
        )
    }

    private fun headerDragDocksAndLiftsOff(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace header drag docks the floating satellite and drags the panel back out",
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
                val layout =
                    requireNotNull(workspace.dockHostGeometry(window)?.layoutScreenRectPx()) {
                        "the case window's DockLayout never published its geometry"
                    }

                // ── 1. floating header → right zone ──
                val outer = requireNotNull(floating.outerBoundsPx())
                val scale = floating.scaleFactor
                // Middle of the title bar: clear of the traffic lights, on the header grip.
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + HEADER_GRAB_Y_DP * scale)
                val dropIn = Offset(layout.right - DROP_INSET_PX, layout.center.y)
                val robot = robotPressAndDrag(grab, dropIn, scale) != null
                if (robot) {
                    // Button still down: the zone under the pointer must be
                    // previewed before the drop — that highlight is the whole
                    // affordance — and only then is the drop position certain.
                    awaitUntil("the right zone is previewed while the drag is held") {
                        workspace.dockPreview == DockTarget(window, DockSide.Right)
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[workspace-drag] robot unavailable, driving the drag session directly")
                    val session =
                        requireNotNull(
                            workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.FloatingWindow(floating), grab),
                        )
                    session.update(Offset(layout.center.x, layout.center.y))
                    check(workspace.dockPreview == null) { "the content area must not preview a dock" }
                    session.update(dropIn)
                    check(workspace.dockPreview == DockTarget(window, DockSide.Right)) {
                        "hovering the right zone must preview it: ${workspace.dockPreview}"
                    }
                    session.end(dropIn)
                }
                awaitUntil("satellite docked by the drag") { entry.isDocked && entry.dockHost === window }
                awaitUntil("panel composed in the case window") {
                    fixture.panelHost.value === window && fixture.panelBoundsPx.value != null
                }
                settle()
                check(workspace.dockPreview == null && workspace.dragGhost == null) { "drag feedback left behind" }
                check((entry.placement as SatellitePlacement.Docked).side == DockSide.Right) {
                    "docked on ${entry.placement}, expected the right zone; layout=$layout drop=$dropIn"
                }

                // ── 2. panel header → content: lifts off under the pointer ──
                val panel = requireNotNull(entry.dockedBoundsInWindowPx)
                val client = requireNotNull(workspace.dockHostGeometry(window)?.clientOriginPx())
                val panelGrab =
                    client + Offset(panel.left + panel.width / 2f, panel.top + HEADER_GRAB_Y_DP * window.scaleFactor)
                val dropOut = Offset(layout.center.x, layout.center.y)
                if (robot) {
                    checkNotNull(robotPressAndDrag(panelGrab, dropOut, scale)) { "robot became unavailable mid-case" }
                    awaitUntil("the torn-out panel is previewed under the pointer") {
                        workspace.dragGhost?.let { it.satellite === entry && it.screenRectPx.contains(dropOut) } == true
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    val session =
                        requireNotNull(
                            workspace.beginDrag(SATELLITE_ID, SatelliteDragOrigin.DockedPanel(window), panelGrab),
                        )
                    session.update(dropOut)
                    val ghost = requireNotNull(workspace.dragGhost) { "dragging a panel out must show a ghost" }
                    check(ghost.satellite === entry) { "the ghost must preview the dragged satellite" }
                    check(ghost.screenRectPx.contains(dropOut)) {
                        "the ghost must sit under the pointer: ${ghost.screenRectPx} vs $dropOut"
                    }
                    session.end(dropOut)
                }
                awaitUntil("satellite undocked by the drag") { !entry.isDocked }
                check(workspace.dragGhost == null) { "the ghost must be gone once the drag ends" }
                awaitUntil("floating window recreated") {
                    val now = fixture.floatingWindow.value
                    now != null && now !== floating && (now.outerBoundsPx()?.get(2) ?: 0L) > 0L
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val lifted = requireNotNull(requireNotNull(fixture.floatingWindow.value).outerBoundsPx())
                // The grab point stays under the pointer: window top-left = drop − grab offset.
                val expectedX = dropOut.x - (panelGrab.x - (client.x + panel.left))
                val expectedY = dropOut.y - (panelGrab.y - (client.y + panel.top))
                check(
                    abs(lifted[0] - expectedX) <= LIFT_OFF_TOLERANCE_PX &&
                        abs(lifted[1] - expectedY) <= LIFT_OFF_TOLERANCE_PX,
                ) {
                    "undocked window did not land under the pointer: window=${lifted.toList()} " +
                        "expected≈($expectedX, $expectedY)"
                }
                check(workspace.dockPreview == null && workspace.dragGhost == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * The bar above the header strip. It is a few dp tall, it is where a user
     * grabs a small palette, and it used to belong to the platform's own
     * interactive move — which is a compositor grab, so a satellite dragged
     * from there could never dock on release. The whole bar is the workspace
     * handle now, and this pins it: a drag started clear of the header strip
     * has to dock exactly like one started on the strip.
     */
    private fun titleBarDragOutsideTheHeaderStripDocks(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "workspace satellite dragged by its title bar above the header strip still docks",
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

                // Deliberately above the strip: the header centres itself in the
                // bar, so these few dp are the ones the platform used to own.
                val grab = Offset(outer[0] + outer[2] / 2f, outer[1] + TITLE_BAR_TOP_GRAB_DP * scale)
                check(grab.y < outer[1] + HEADER_GRAB_Y_DP * scale) {
                    "this case has to grab above the strip that ${'$'}HEADER_GRAB_Y_DP dp hits"
                }
                val dropIn = Offset(layout.right - DROP_INSET_PX, layout.center.y)

                val robot = robotPressAndDrag(grab, dropIn, scale) != null
                if (robot) {
                    awaitUntil("the right zone is previewed while the drag is held") {
                        workspace.dockPreview == DockTarget(window, DockSide.Right)
                    }
                    checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                } else {
                    System.err.println("[title-bar-drag] robot unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                awaitUntil("the satellite docked from a title-bar drag") {
                    entry.isDocked && entry.dockHost === window
                }
                awaitUntil("panel composed in the case window") { fixture.panelHost.value === window }
                check((entry.placement as SatellitePlacement.Docked).side == DockSide.Right) {
                    "docked on ${'$'}{entry.placement}, expected the right zone"
                }
                check(workspace.dockPreview == null && workspace.dragGhost == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * The tools-palette shape: a scrollable column (whose `rememberScrollState`
     * saves an `Int`) plus two `rememberSaveable` states, cycled docked →
     * floating → docked → other side. Every value must come back where it
     * belongs, i.e. the key relocation must never hand one call site another
     * site's value.
     */
    private fun saveableStateSurvivesRepeatedHostChanges(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val workspace = fixture.workspace
        val tool = mutableStateOf<MutableState<String>?>(null)
        val brush = mutableStateOf<MutableState<Float>?>(null)
        val composedIn = mutableStateOf<TaoWindow?>(null)
        return TaoWindowTestCase(
            name = "workspace saveable state keeps every call site's value across repeated host changes",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = {
                Satellite(
                    workspace = workspace,
                    id = SATELLITE_ID,
                    title = "Palette",
                    initialPlacement = SatellitePlacement.Docked(DockSide.Left),
                ) {
                    val selected = rememberSaveable { mutableStateOf("Move") }
                    val size = rememberSaveable { mutableStateOf(12f) }
                    val window = LocalTaoWindow.current
                    SideEffect {
                        tool.value = selected
                        brush.value = size
                        composedIn.value = window
                        if (!isDocked) fixture.floatingWindow.value = window
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF)))
                    }
                }
            },
            driver = {
                awaitUntil("owner window mapped") { bounds() != null }
                awaitUntil("palette docked and composed") { composedIn.value === window && tool.value != null }
                requireNotNull(tool.value).value = "Brush"
                requireNotNull(brush.value).value = 33f
                settle()

                fun assertValues(step: String) {
                    check(tool.value?.value == "Brush") { "$step: tool = ${tool.value?.value}" }
                    check(brush.value?.value == 33f) { "$step: brush = ${brush.value?.value}" }
                }

                workspace.undock(SATELLITE_ID)
                awaitUntil("palette floating") {
                    val w = fixture.floatingWindow.value
                    w != null && composedIn.value === w && (w.outerBoundsPx()?.get(2) ?: 0L) > 0L
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                assertValues("after undock")

                workspace.dock(SATELLITE_ID, DockSide.Left)
                awaitUntil("palette docked left again") {
                    composedIn.value === window && workspace.satellite(SATELLITE_ID)?.isDocked == true
                }
                settle()
                assertValues("after re-dock")

                workspace.dock(SATELLITE_ID, DockSide.Right)
                awaitUntil("palette moved to the right side") {
                    (workspace.satellite(SATELLITE_ID)?.placement as? SatellitePlacement.Docked)?.side == DockSide.Right
                }
                settle()
                assertValues("after changing side")

                workspace.undock(SATELLITE_ID)
                awaitUntil("palette floating again") {
                    val w = fixture.floatingWindow.value
                    w != null && composedIn.value === w && (w.outerBoundsPx()?.get(2) ?: 0L) > 0L
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                assertValues("after second undock")
            },
        )
    }
}
