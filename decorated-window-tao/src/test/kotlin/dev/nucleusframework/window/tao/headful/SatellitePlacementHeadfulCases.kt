package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteWindowState
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

/**
 * Where a satellite is the first time it is *seen*, on real windows.
 *
 * A window that appears at the platform's default position and only then jumps
 * to its anchor is correct by every state assertion and wrong to every user:
 * the palette flashes in the middle of the screen for a few frames before
 * snapping beside its document. Nothing in the placement API says when the
 * window becomes visible, so this file asserts the one thing the user actually
 * sees — every position the window ever occupies, from its first mapped frame
 * onwards, is its anchored one.
 *
 * The trajectory is sampled rather than checked at the end: the end state is
 * right in the buggy case too.
 *
 * Native Wayland is skipped — the compositor places satellites there and no
 * client can say where they are.
 */
internal object SatellitePlacementHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            aSatelliteInItsParentsContentNeverFlashesElsewhere(),
            aSatelliteOfAnAlreadyMappedParentNeverFlashesElsewhere(),
            aReopenedSatelliteComesBackWhereItWas(),
            aPanelLiftedOutOfItsDockNeverFlashesElsewhere(),
        )

    /**
     * The hard case, and the one an app hits first: the satellite is declared
     * inside its parent's content, so it composes in the same frame the parent
     * window is created — before the parent has a frame to anchor to. Whatever
     * the implementation does about that, the satellite must not be *shown*
     * anywhere but at its anchor.
     */
    private fun aSatelliteInItsParentsContentNeverFlashesElsewhere(): TaoWindowTestCase {
        val state =
            SatelliteWindowState(
                size = workspaceSatelliteSize(),
                positioner = workspaceRightEdgePositioner(),
            )
        return TaoWindowTestCase(
            name = "satellite placement declared in its parent's content, never seen away from its anchor",
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            satelliteState = state,
            satelliteContent = { Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF))) },
            driver = {
                val satellite = requireNotNull(satelliteWindow) { "the satellite never published itself" }
                val trajectory = sampleUntilAnchored(satellite, window)
                assertNoFlash(trajectory, window)
            },
        )
    }

    /**
     * The same satellite whose parent is already on screen — the shape of a
     * palette opened from a menu. There is no excuse for a detour here: the
     * anchor is computable before the window exists.
     */
    private fun aSatelliteOfAnAlreadyMappedParentNeverFlashesElsewhere(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "satellite placement opened over a mapped parent, never seen away from its anchor",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                // Let the first satellite settle, then close and reopen it: the
                // second window is created against a parent that has been on
                // screen for a while.
                awaitFloating(fixture)
                fixture.workspace.close(SATELLITE_ID)
                awaitUntil("the satellite went") { fixture.floatingWindow.value == null }
                settle(SETTLE_AFTER_MAP_MILLIS)

                fixture.workspace.open(SATELLITE_ID)
                awaitUntil("a new satellite window appeared") { fixture.floatingWindow.value != null }
                val satellite = requireNotNull(fixture.floatingWindow.value)
                val trajectory = sampleUntilAnchored(satellite, window)
                assertNoFlash(trajectory, window)
            },
        )
    }

    /**
     * Closed and reopened, the satellite has to come back where the user left
     * it — including when they had dragged it away from its anchor. A reopen
     * that goes through the platform default first is the same flash, and a
     * reopen that lands back at the declared anchor loses their placement.
     */
    private fun aReopenedSatelliteComesBackWhereItWas(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        return TaoWindowTestCase(
            name = "satellite placement a reopened satellite comes back where the user left it",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                val first = awaitFloating(fixture)
                val before = requireNotNull(first.outerBoundsPx())
                // The user drags it somewhere of their own.
                val scale = first.scaleFactor.toDouble()
                first.setOuterPosition(before[0] / scale + MOVE_DELTA_DP, before[1] / scale + MOVE_DELTA_DP)
                awaitUntil("the satellite moved") {
                    val now = first.outerBoundsPx() ?: return@awaitUntil false
                    abs(now[0] - before[0]) > 1L
                }
                awaitUntil("the workspace recorded the new offset") {
                    fixture.workspace
                        .satellite(SATELLITE_ID)
                        ?.windowState
                        ?.offsetFromParent != null
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                val moved = requireNotNull(first.outerBoundsPx())

                fixture.workspace.close(SATELLITE_ID)
                awaitUntil("the satellite went") { fixture.floatingWindow.value == null }
                settle(SETTLE_AFTER_MAP_MILLIS)
                fixture.workspace.open(SATELLITE_ID)
                awaitUntil("it came back") { fixture.floatingWindow.value != null }
                val second = requireNotNull(fixture.floatingWindow.value)
                val trajectory = sampleUntilStable(second)
                settle(SETTLE_AFTER_MAP_MILLIS)

                val now = requireNotNull(second.outerBoundsPx())
                check(abs(now[0] - moved[0]) <= REOPEN_TOLERANCE_PX && abs(now[1] - moved[1]) <= REOPEN_TOLERANCE_PX) {
                    "it came back at (${now[0]}, ${now[1]}), the user left it at (${moved[0]}, ${moved[1]})"
                }
                val strays = trays(trajectory, now)
                check(strays.isEmpty()) {
                    "the reopened satellite lingered at $strays before settling at (${now[0]}, ${now[1]})"
                }
            },
        )
    }

    /**
     * Undocking creates a window that is supposed to appear exactly over the
     * panel it lifts off. Anywhere else — the platform default especially — and
     * the panel visibly teleports out of the window instead of lifting off it.
     */
    private fun aPanelLiftedOutOfItsDockNeverFlashesElsewhere(): TaoWindowTestCase {
        val fixture = SatelliteWorkspaceFixture()
        val docked = mutableStateOf(false)
        return TaoWindowTestCase(
            name = "satellite placement a panel lifted out of its dock never flashes elsewhere",
            timeoutMillis = LONG_CASE_TIMEOUT_MILLIS,
            skip = ::workspaceSkipReason,
            windowState = workspaceParentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Body() },
            applicationContent = { with(fixture) { ToolsSatellite() } },
            driver = {
                awaitFloating(fixture)
                fixture.workspace.dock(SATELLITE_ID, DockSide.Right)
                awaitUntil("the panel is docked") { fixture.panelHost.value === window }
                awaitUntil("the layout published the panel's rect") {
                    fixture.workspace.satellite(SATELLITE_ID)?.dockedBoundsInWindowPx != null
                }
                settle(SETTLE_AFTER_MAP_MILLIS)
                docked.value = true

                fixture.workspace.undock(SATELLITE_ID)
                awaitUntil("a floating window appeared") { fixture.floatingWindow.value != null }
                val lifted = requireNotNull(fixture.floatingWindow.value)
                val trajectory = sampleUntilStable(lifted)
                settle(SETTLE_AFTER_MAP_MILLIS)

                val now = requireNotNull(lifted.outerBoundsPx())
                val strays = trays(trajectory, now)
                check(strays.isEmpty()) {
                    "the lifted panel lingered at $strays before settling at (${now[0]}, ${now[1]})"
                }
            },
        )
    }

    // ── sampling ─────────────────────────────────────────────────────────

    /**
     * Every distinct position [satellite] is seen at, from its first mapped
     * frame until it has been anchored to [parent] and stopped moving.
     *
     * Sampled tightly on the event loop: the flash this file is about lasts a
     * few frames, and a poll slower than that would report the settled state
     * and call it a pass.
     */
    private suspend fun TaoWindowTestScope.sampleUntilAnchored(
        satellite: TaoWindow,
        parent: TaoWindow,
    ): Trajectory =
        sample(satellite) { rect ->
            val parentRect = parent.outerBoundsPx()
            parentRect != null && rect[0] > parentRect[0]
        }

    /** How long [window] is seen at each position, until it stops moving. */
    private suspend fun TaoWindowTestScope.sampleUntilStable(window: TaoWindow): Trajectory = sample(window) { true }

    /**
     * Time spent at each position, in the order they were first seen, until
     * [settled] holds for [STABLE_SAMPLES] samples in a row.
     *
     * Dwell rather than presence: a window the WM maps at its own spot and the
     * client moves within a frame or two is not something anyone sees, while
     * the flash this file is about lasts long enough to read. Only a duration
     * tells them apart.
     */
    private suspend fun TaoWindowTestScope.sample(
        window: TaoWindow,
        settled: (LongArray) -> Boolean,
    ): Trajectory {
        val dwell = LinkedHashMap<Pair<Long, Long>, Long>()
        var stable = 0
        var last: Pair<Long, Long>? = null
        var lastReal: LongArray? = null
        repeat(SAMPLE_ROUNDS) {
            // `hasRealFramePx`, not `> 0`: a frame the platform has not
            // published yet reads as the screen origin, and sampling it makes
            // the window look like it flashed there.
            val rect = window.outerBoundsPx()?.takeIf { window.hasRealFramePx() }
            if (rect != null) {
                lastReal = rect
                val at = rect[0] to rect[1]
                dwell[at] = (dwell[at] ?: 0L) + SAMPLE_INTERVAL_MILLIS
                stable = if (at == last) stable + 1 else 0
                last = at
                if (stable >= STABLE_SAMPLES && settled(rect)) return Trajectory(dwell, rect)
            }
            settle(SAMPLE_INTERVAL_MILLIS)
        }
        return Trajectory(dwell, lastReal)
    }

    /**
     * The satellite was never on screen anywhere but at its anchor: every
     * sampled position matches the settled one, and that one really is the
     * anchored place rather than wherever the platform felt like.
     */
    private fun assertNoFlash(
        trajectory: Trajectory,
        parent: TaoWindow,
    ) {
        check(trajectory.dwell.isNotEmpty()) { "the satellite was never seen with a real frame" }
        val settled = requireNotNull(trajectory.settled) { "the satellite was never seen with a real frame" }
        val parentRect = requireNotNull(parent.outerBoundsPx())
        // The positioner puts it off the parent's right edge; if the settled
        // state is not that, the case is not measuring what it thinks.
        check(settled[0] >= parentRect[0] + parentRect[RECT_W] - EDGE_SLOP_PX) {
            "case premise: the satellite did not end up off the parent's right edge " +
                "(${settled[0]} vs parent right ${parentRect[0] + parentRect[RECT_W]})"
        }
        val strays = trays(trajectory, settled)
        check(strays.isEmpty()) {
            "the satellite was shown away from its anchor for longer than ${FLASH_BUDGET_MILLIS}ms " +
                "($strays) before settling at (${settled[0]}, ${settled[1]}) — a visible jump on screen"
        }
    }

    /**
     * Positions in [trajectory] that are not [settled] and were held long
     * enough for a user to see, with how long each was held.
     */
    private fun trays(
        trajectory: Trajectory,
        settled: LongArray,
    ): Map<Pair<Long, Long>, Long> =
        trajectory.dwell.filter { (at, millis) ->
            millis > FLASH_BUDGET_MILLIS &&
                (abs(at.first - settled[0]) > FLASH_TOLERANCE_PX || abs(at.second - settled[1]) > FLASH_TOLERANCE_PX)
        }

    /**
     * How long a window was seen at each position it occupied, and the last
     * frame it was seen with — the position the case treats as settled, taken
     * from the sampling rather than re-read afterwards so it can never be a
     * frame the platform had already taken away again.
     */
    private class Trajectory(
        val dwell: Map<Pair<Long, Long>, Long>,
        val settled: LongArray?,
    )

    /** How far a sampled position may differ from the settled one and still be the same place. */
    private const val FLASH_TOLERANCE_PX = 4L

    /**
     * How long a window may be somewhere else before it counts as a visible
     * jump. A frame or two is the WM's map-time placement being corrected —
     * nobody sees that. What users report is a palette sitting at the wrong
     * place long enough to read, which is an order of magnitude longer.
     */
    private const val FLASH_BUDGET_MILLIS = 48L

    /** The parent's right edge, minus whatever the frame's shadow margin adds. */
    private const val EDGE_SLOP_PX = 40L

    private const val REOPEN_TOLERANCE_PX = 24L
    private const val SAMPLE_INTERVAL_MILLIS = 8L
    private const val SAMPLE_ROUNDS = 250
    private const val STABLE_SAMPLES = 12
    private const val LONG_CASE_TIMEOUT_MILLIS = 90_000L
}
