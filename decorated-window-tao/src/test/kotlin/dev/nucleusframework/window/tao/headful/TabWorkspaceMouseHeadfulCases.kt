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
 *  5. **a flick**, delivering as few samples as the OS will give;
 *  6. **a pointer resting on a tab**, which offers that tab's hover card —
 *     and every case where the card has to stay away.
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
            robotRestingOnATabOffersItsCard(),
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

    /**
     * Fractions of a tab's slot the click case aims at: clear of the close
     * button, hard against the edges — but past the resize band.
     *
     * A strip sits flush with the top of its window, and the top 5 logical px
     * of a resizable window belong to `ResizeFrameDecoration`, rightly: a
     * press there is a resize grip in every browser too. On a frame that adds
     * nothing above its content (Tao on X11, Win32) that band covers the first
     * eighth of a 40 dp tab, so "hard against the top edge" has to mean the
     * first pixel of the tab that is the tab's to claim.
     */
    private const val SLOT_NEAR_X = 0.25f
    private const val SLOT_MID_X = 0.5f
    private const val SLOT_EDGE_X = 0.06f
    private const val SLOT_NEAR_Y = 0.2f
    private const val SLOT_MID_Y = 0.5f
    private const val SLOT_FAR_Y = 0.88f

    /**
     * The hover card, under a real pointer: resting on a tab offers *that*
     * tab's card, and the three places it has to stay away from — the tab
     * already on screen, anywhere off the strip, and a tab that has just been
     * clicked.
     *
     * The delay itself is not asserted. A wall-clock threshold on a loaded
     * runner is exactly what makes a case flaky; what matters here is that a
     * real pointer reaches the strip's slots at all, and that the popup opens
     * over a real window — neither of which a headless case can tell.
     */
    private fun robotRestingOnATabOffersItsCard(): TaoWindowTestCase {
        val fixture =
            TabWorkspaceFixture(
                initialTitles = listOf("Alpha", "Beta", "Gamma"),
                hoverPreview = true,
            )
        return TaoWindowTestCase(
            name = "tab mouse resting on a tab offers its hover card",
            skip = { workspaceSkipReason() ?: robotSkipReason() },
            windowState = idleCaseWindowState(),
            size = idleCaseWindowSize(),
            paintDefaultBackground = false,
            applicationContent = { with(fixture) { Windows() } },
            driver = {
                val first = awaitTabWindows(fixture, "Alpha", "Beta", "Gamma")
                val workspace = fixture.workspace
                val alpha = fixture.tabId("Alpha")
                val beta = fixture.tabId("Beta")
                workspace.select(alpha)
                awaitUntil("Alpha is the composed body") { fixture.windowOf("Alpha") === first }
                first.focus()
                awaitUntil("first window is focused") { first.isFocused }

                val onAlpha = requireNotNull(fixture.tabCenterPx("Alpha"))
                val onBeta = requireNotNull(fixture.tabCenterPx("Beta"))
                val strip = requireNotNull(fixture.stripRectPx(requireNotNull(fixture.groupOf("Alpha"))))
                val inTheBody = Offset(strip.center.x, strip.bottom + BELOW_STRIP_PX)

                // Resting on a tab that is not the one being read: its card.
                if (robotMoveTo(onBeta, first.scaleFactor) == null) {
                    System.err.println("[tab-mouse] robot became unavailable, nothing to assert")
                    return@TaoWindowTestCase
                }
                awaitUntil(
                    "the strip offers Beta's card — ${robotAim()}; ${fixture.geometryReport("Beta")}",
                ) { fixture.shownHoverCard.value == beta }

                // The tab already on screen gets none: its body is right there.
                checkNotNull(robotMoveTo(onAlpha, first.scaleFactor)) { "robot became unavailable mid-case" }
                awaitUntil("the card goes away over the selected tab — ${robotAim()}") {
                    fixture.shownHoverCard.value == null
                }
                settle(HOVER_HOLD_MILLIS)
                check(fixture.shownHoverCard.value == null) { "a card was offered for the tab on screen" }

                // Back on Beta, and it comes back.
                checkNotNull(robotMoveTo(onBeta, first.scaleFactor)) { "robot became unavailable mid-case" }
                awaitUntil("Beta's card comes back — ${robotAim()}") { fixture.shownHoverCard.value == beta }

                // Off the strip entirely: nothing is being pointed at.
                checkNotNull(robotMoveTo(inTheBody, first.scaleFactor)) { "robot became unavailable mid-case" }
                awaitUntil("the card goes away below the strip — ${robotAim()}") {
                    fixture.shownHoverCard.value == null
                }

                // A click leaves no card under the pointer, however long it
                // rests there: the tab it selected is now the one on screen.
                checkNotNull(
                    robotPressAndDrag(onBeta, onBeta, first.scaleFactor, steps = 1, stepDelayMillis = 0),
                ) { "robot became unavailable mid-case" }
                checkNotNull(robotRelease()) { "robot became unavailable mid-case" }
                awaitUntil("the click selected Beta — ${robotAim()}") {
                    requireNotNull(fixture.groupOf("Beta")).selectedId == beta
                }
                settle(HOVER_HOLD_MILLIS)
                check(fixture.shownHoverCard.value == null) { "a card sat under the tab that was just clicked" }
                check(workspace.draggedTab == null && workspace.dragGhost == null) { "the click became a drag" }
            },
        )
    }
}

/** How far below a strip a case reaches to leave it: well inside the body. */
private const val BELOW_STRIP_PX = 80f

/** Long enough for a card that should not be there to have shown up. */
private const val HOVER_HOLD_MILLIS = 400L
