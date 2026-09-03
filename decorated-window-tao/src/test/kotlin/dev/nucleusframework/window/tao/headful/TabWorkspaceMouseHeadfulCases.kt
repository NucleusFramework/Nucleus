package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.geometry.Offset

/**
 * The tab workspace under a real mouse, on real windows: every case here is
 * driven by the AWT Robot, so what it exercises is the pointer pipeline the
 * user actually goes through — press, move, release, with the OS coalescing
 * whatever it likes in between.
 *
 *  1. **a reorder** inside one strip, which must not rebuild the tab's body;
 *  2. **a press that never moves**, which has to stay a plain selection so the
 *     close button and click-to-select keep working under a drag handle;
 *  3. **a click anywhere in a tab**, top edge to bottom edge: a tab is one
 *     target, not a patchwork of a grip and a selector;
 *  4. **a hover across two strips and back**, where the preview follows the
 *     pointer from window to window and the drop acts on where it ended;
 *  5. **a flick**, delivering as few samples as the OS will give.
 *
 * Native Wayland is skipped along with the rest of the tab suite; so is a host
 * that cannot inject input.
 */
internal object TabWorkspaceMouseHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            robotReorderInsideTheStrip(),
            robotPressWithoutMovingOnlySelects(),
            robotClicksAnywhereInATabSelectIt(),
            robotHoverCrossesTwoStripsAndComesBack(),
            robotFlickBetweenStripsMerges(),
        )

    /**
     * The most ordinary gesture there is, with a real mouse: pick a tab up and
     * put it down further along its own strip. It must reorder, stay in its
     * window, and — since the body does not change host — not be rebuilt.
     */
    private fun robotReorderInsideTheStrip(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab mouse reorders inside one strip without rebuilding the body",
            skip = { workspaceSkipReason() ?: robotSkipReason() },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val alpha = fixture.tabId("Alpha")
                workspace.select(alpha)
                awaitUntil("Alpha is the composed body") { fixture.windowOf("Alpha") === first }
                val incarnationsBefore = requireNotNull(fixture.bodyIncarnations.value[alpha])

                val grab = requireNotNull(fixture.tabCenterPx("Alpha"))
                val betaCenter = requireNotNull(fixture.tabCenterPx("Beta"))
                val gammaCenter = requireNotNull(fixture.tabCenterPx("Gamma"))
                // Past Beta's midpoint, short of Gamma's: index 1.
                val dropAt = Offset((betaCenter.x + gammaCenter.x) / 2f, grab.y)

                first.focus()
                awaitUntil("first window is focused") { first.isFocused }
                if (robotPressAndDrag(grab, dropAt, first.scaleFactor) == null) {
                    System.err.println("[tab-mouse] robot became unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                awaitUntil(
                    "the drag started — ${robotAim()}; ${fixture.geometryReport("Alpha")}",
                ) { workspace.draggedTab?.id == alpha }
                awaitUntil("its own strip previews the new index") {
                    val preview = workspace.dropPreview
                    preview != null && preview.group === fixture.groupOf("Alpha") && preview.index == 1
                }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }

                awaitUntil("Alpha sits second in the strip") {
                    requireNotNull(fixture.groupOf("Alpha")).ids ==
                        listOf(
                            fixture.tabId("Beta"),
                            alpha,
                            fixture.tabId("Gamma"),
                        )
                }
                settle()
                check(workspace.groups.size == 1) { "a reorder opened a window: ${workspace.groups.size}" }
                check(fixture.windowOf("Alpha") === first) { "a reorder moved the tab to another window" }
                check(fixture.bodyIncarnations.value[alpha] == incarnationsBefore) {
                    "a reorder rebuilt the body: ${fixture.bodyIncarnations.value[alpha]} vs $incarnationsBefore"
                }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /**
     * A press with no movement is a click: the close button and plain
     * click-to-select still have to work with a drag handle over the whole tab,
     * so nothing may be dragged, previewed or ghosted.
     */
    private fun robotPressWithoutMovingOnlySelects(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab mouse press that never moves only selects",
            skip = { workspaceSkipReason() ?: robotSkipReason() },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val alpha = fixture.tabId("Alpha")
                workspace.select(fixture.tabId("Beta"))
                awaitUntil("Beta is the composed body") { fixture.windowOf("Beta") === first }
                val idsBefore = requireNotNull(fixture.groupOf("Alpha")).ids

                first.focus()
                awaitUntil("first window is focused") { first.isFocused }
                val grab = requireNotNull(fixture.tabCenterPx("Alpha"))
                if (robotPressAndDrag(grab, grab, first.scaleFactor, steps = 1, stepDelayMillis = 0) == null) {
                    System.err.println("[tab-mouse] robot became unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                settle()
                check(workspace.draggedTab == null) { "a press without movement started a drag" }
                check(workspace.dragGhost == null) { "a press without movement produced a ghost" }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }

                awaitUntil(
                    "the click selected the tab — ${robotAim()}; ${fixture.geometryReport("Alpha")}",
                ) { fixture.windowOf("Alpha") === first }
                settle()
                check(requireNotNull(fixture.groupOf("Alpha")).ids == idsBefore) {
                    "a click reordered the strip: ${fixture.groupOf("Alpha")?.ids}"
                }
                check(workspace.groups.size == 1) { "a click opened a window" }
            },
        )
    }

    /**
     * A tab is one target, not a patchwork: a click anywhere inside its slot
     * selects it — top edge, bottom edge, left of the label, right of it.
     *
     * The trap this guards against is real and easy to walk into with custom
     * chrome: put the drag grip on the label alone and it claims the press
     * wherever it sits, leaving only the padding around the label to select
     * with. The tab then has two different active areas and a sliver that does
     * one but not the other. The stock strip carries the slot, the grip and the
     * click on one element that fills the tab, and this is what says so.
     */
    private fun robotClicksAnywhereInATabSelectIt(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab mouse click anywhere in a tab selects it",
            skip = { workspaceSkipReason() ?: robotSkipReason() },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val alpha = fixture.tabId("Alpha")
                val beta = fixture.tabId("Beta")

                // Well inside the slot horizontally — the close button owns the
                // trailing end — and hard against the top and bottom of it.
                val spots =
                    listOf(
                        "top edge" to Offset(SLOT_NEAR_X, SLOT_NEAR_Y),
                        "bottom edge" to Offset(SLOT_NEAR_X, SLOT_FAR_Y),
                        "left of the label" to Offset(SLOT_EDGE_X, SLOT_MID_Y),
                        "past the label" to Offset(SLOT_MID_X, SLOT_MID_Y),
                    )
                for ((where, fractions) in spots) {
                    first.focus()
                    awaitUntil("first window is focused") { first.isFocused }
                    workspace.select(beta)
                    awaitUntil("$where: Beta is the composed body") { fixture.windowOf("Beta") === first }
                    val slot = requireNotNull(fixture.tabRectPx("Alpha")) { "$where: Alpha has no slot" }
                    val point =
                        Offset(
                            slot.left + slot.width * fractions.x,
                            slot.top + slot.height * fractions.y,
                        )
                    if (robotPressAndDrag(point, point, first.scaleFactor, steps = 1, stepDelayMillis = 0) == null) {
                        System.err.println("[tab-mouse] robot became unavailable, nothing to assert")
                        return@TaoWindowTestCase
                    }
                    checkNotNull(robotRelease()) { "$where: robot became unavailable mid-case" }
                    awaitUntil(
                        "$where selected Alpha — ${robotAim()}; ${fixture.geometryReport("Alpha")}",
                    ) { fixture.windowOf("Alpha") === first }
                    settle()
                    check(workspace.groups.size == 1) { "$where opened a window" }
                    check(requireNotNull(fixture.groupOf("Alpha")).ids == listOf(alpha, beta)) {
                        "$where reordered the strip: ${fixture.groupOf("Alpha")?.ids}"
                    }
                    check(workspace.draggedTab == null && workspace.dragGhost == null) {
                        "$where left drag feedback behind"
                    }
                }
            },
        )
    }

    /**
     * The hesitant user: a tab held over another window's strip, brought back
     * over its own, and dropped at home. Every strip in the workspace shows
     * where the tab would land while it is held, so the preview has to follow
     * the pointer from one window to the other and back — and the drop has to
     * act on where the pointer *ended*.
     */
    private fun robotHoverCrossesTwoStripsAndComesBack(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta", "Gamma"))
        return TaoWindowTestCase(
            name = "tab mouse crosses two strips and drops back home",
            skip = { workspaceSkipReason() ?: robotSkipReason() },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val gamma = fixture.tabId("Gamma")

                // Gamma into a window of its own, well clear of the first one.
                val second = requireNotNull(workspace.tearOff(gamma, tearOffRectPx(first), first.scaleFactor))
                awaitMappedStrip(fixture, second)
                val home = requireNotNull(fixture.groupOf("Alpha"))
                val beta = fixture.tabId("Beta")

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val awayStrip = requireNotNull(fixture.stripPointPx(second, STRIP_HEAD_FRACTION))
                if (robotPressAndDrag(grab, awayStrip, first.scaleFactor) == null) {
                    System.err.println("[tab-mouse] robot became unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                awaitUntil("the other window's strip previews the drop — ${robotAim()}") {
                    workspace.draggedTab?.id == beta && workspace.dropPreview?.group === second
                }

                // Back over its own strip, past Alpha's midpoint.
                val backHome = requireNotNull(fixture.stripPointPx(home, STRIP_MID_FRACTION))
                checkNotNull(robotDragTo(backHome, first.scaleFactor)) { "robot became unavailable mid-case" }
                awaitUntil("its own strip takes the preview back") {
                    workspace.dropPreview?.group === home
                }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }

                awaitUntil("Beta stayed home") { fixture.groupOf("Beta") === home }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(workspace.groups.size == 2) { "the round trip changed the window count" }
                check(second.ids == listOf(gamma)) { "the hovered window kept a tab it never got: ${second.ids}" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /** The same merge, flicked: as few samples as the OS will deliver. */
    private fun robotFlickBetweenStripsMerges(): TaoWindowTestCase {
        val fixture = TabWorkspaceFixture(initialTitles = listOf("Alpha", "Beta"))
        return TaoWindowTestCase(
            name = "tab mouse flick from one strip to another merges the tab",
            skip = { workspaceSkipReason() ?: robotSkipReason() },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta")
                val workspace = fixture.workspace
                val beta = fixture.tabId("Beta")

                val second = requireNotNull(workspace.tearOff(beta, tearOffRectPx(first), first.scaleFactor))
                val secondWindow = awaitMappedStrip(fixture, second)
                val home = requireNotNull(fixture.groupOf("Alpha"))

                val grab = requireNotNull(fixture.tabCenterPx("Beta"))
                val target = requireNotNull(fixture.stripPointPx(home, STRIP_HEAD_FRACTION))
                val flicked =
                    robotPressAndDrag(
                        grab,
                        target,
                        secondWindow.scaleFactor,
                        steps = FLICK_STEPS,
                        stepDelayMillis = 0,
                    )
                if (flicked == null) {
                    System.err.println("[tab-mouse] robot became unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                awaitUntil("the flick started the window drag — ${robotAim()}") { workspace.draggedTab?.id == beta }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }

                awaitUntil("the flicked tab merged into the first window") {
                    workspace.groups.size == 1 && fixture.groupOf("Beta") === home
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                check(home.ids.size == 2) { "the merged strip holds ${home.ids}" }
                check(fixture.windowOf("Beta") === first) { "the tab is composed in the wrong window" }
                check(workspace.dragGhost == null && workspace.dropPreview == null) { "drag feedback left behind" }
            },
        )
    }

    /** Fractions of a tab's slot the click case aims at: clear of the close button, hard against the edges. */
    private const val SLOT_NEAR_X = 0.25f
    private const val SLOT_MID_X = 0.5f
    private const val SLOT_EDGE_X = 0.06f
    private const val SLOT_NEAR_Y = 0.12f
    private const val SLOT_MID_Y = 0.5f
    private const val SLOT_FAR_Y = 0.88f
}
