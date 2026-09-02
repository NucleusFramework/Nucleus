package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoMonitor
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.v2.WindowBoundsProvider
import dev.nucleusframework.window.tao.v2.WindowPositionProvider
import dev.nucleusframework.window.tao.v2.WindowScreenProvider
import dev.nucleusframework.window.tao.v2.WindowSizeProvider
import dev.nucleusframework.window.tao.v2.WindowState
import kotlin.math.abs

/**
 * End-to-end coverage for the AWT-free window API v2 clone
 * ([dev.nucleusframework.window.tao.v2]): every request shape that is inert on
 * Compose's own v2 types — because its `WindowGeometryProviderScope` needs a
 * displayable `java.awt.Window` — has to reach a real native window here, and
 * the observed state has to come back from that window.
 */
internal object WindowApiV2HeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            initialBoundsCentreOnScreen(),
            requestSizeAndPosition(),
            scopedBoundsProviderReadsLiveMetrics(),
            requestScreenMovesTheWindow(),
            observedScreenIdTracksTheHostingMonitor(),
        )

    private fun initialBoundsCentreOnScreen(): TaoWindowTestCase {
        val state =
            WindowState(
                initialBoundsProvider =
                    WindowBoundsProvider(
                        sizeProvider = WindowSizeProvider.Fixed(INITIAL_SIZE),
                        positionProvider = WindowPositionProvider.CenteredOnScreen,
                    ),
            )
        return TaoWindowTestCase(
            name = "window v2 clone: initial provider centres a fixed size on the screen",
            nucleusWindowState = state,
        ) {
            awaitMapped()
            // Poll rather than snapshot: a freshly mapped window sits at the
            // platform's placeholder position (32767 on Windows) until the
            // initial geometry effect applies, so a single read right after
            // mapping races the very thing under test.
            var polls = 0
            awaitUntil("initial provider sized the window") {
                val outer = outerDp()
                // Once a second, so a CI timeout leaves the trajectory in the log.
                if (polls++ % DIAG_EVERY_POLLS == 0) {
                    System.err.println(
                        "[v2-e2e] sizing outer=$outer " +
                            "scale=${window.scaleFactor} initialized=${state.isInitialized}",
                    )
                }
                closeEnough(INITIAL_SIZE.width.value, outer.width) &&
                    closeEnough(INITIAL_SIZE.height.value, outer.height)
            }
            // The requested position is a *request*: an X11 window manager
            // applies its own placement policy to a client's initial position
            // (openbox on CI does), which is why the v1 path retries its
            // Aligned centring. Assert the strict centre where the platform
            // honours the request, and containment in the target work area
            // everywhere — that is what the provider genuinely controls.
            val available = hostMonitor().workAreaDp(window.scaleFactor)
            val outer = outerDp()
            System.err.println("[v2-e2e] outer=$outer available=$available scale=${window.scaleFactor}")
            if (!isLinux) {
                awaitUntil("initial provider centred the window on its screen") {
                    val rect = outerDp()
                    closeEnough(available.left.value + (available.width - rect.width) / 2f, rect.left) &&
                        closeEnough(available.top.value + (available.height - rect.height) / 2f, rect.top)
                }
            } else {
                check(outer.left >= available.left.value - TOLERANCE_DP) {
                    "window placed left of the work area: $outer vs $available"
                }
                check(outer.top >= available.top.value - TOLERANCE_DP) {
                    "window placed above the work area: $outer vs $available"
                }
            }
            awaitUntil("the state observed the window being shown") { state.isInitialized }
            // Observed bounds must be the window's own, not the requested ones.
            // Polled, not snapshotted: the native geometry and its publication
            // settle independently, so two separate reads can straddle a frame.
            awaitUntil("observed bounds converge on the native outer rectangle") {
                val outer = outerDp()
                val bounds = state.bounds
                closeEnough(outer.left, bounds.left.value) && closeEnough(outer.width, bounds.width)
            }
        }
    }

    private fun requestSizeAndPosition(): TaoWindowTestCase {
        val state = WindowState()
        return TaoWindowTestCase(
            name = "window v2 clone: requestSize / requestPosition reach the native window",
            nucleusWindowState = state,
        ) {
            awaitMapped()
            settle()

            state.requestSize(RESIZED)
            awaitUntil("outer size follows requestSize(${RESIZED.width.value}x${RESIZED.height.value})") {
                val outer = outerDp()
                closeEnough(RESIZED.width.value, outer.width) && closeEnough(RESIZED.height.value, outer.height)
            }

            val available = hostMonitor().workAreaDp(window.scaleFactor)
            val target = DpOffset(available.left + MOVE_INSET, available.top + MOVE_INSET)
            state.requestPosition(target)
            awaitUntil("outer position follows requestPosition(${target.x.value}, ${target.y.value})") {
                val outer = outerDp()
                closeEnough(target.x.value, outer.left) && closeEnough(target.y.value, outer.top)
            }
            // Moving must not resize.
            val outer = outerDp()
            assertClose(RESIZED.width.value, outer.width, "width after the move")

            // And the state must have observed the result, not just requested it.
            awaitUntil("state.bounds reflects the applied geometry") {
                closeEnough(RESIZED.width.value, state.bounds.right.value - state.bounds.left.value)
            }
        }
    }

    private fun scopedBoundsProviderReadsLiveMetrics(): TaoWindowTestCase {
        val state = WindowState()
        return TaoWindowTestCase(
            name = "window v2 clone: scoped bounds provider reads live window metrics",
            nucleusWindowState = state,
        ) {
            awaitMapped()
            settle()
            // The shape the Compose v2 path logs and drops: the lambda
            // dereferences the geometry scope.
            state.requestBounds {
                // A real measure pass against the live scene, not a snapshot:
                // the DarkGray chrome the suite paints fills the window, so the
                // unconstrained content measures to the current inner size.
                val measured = measureWindowContent()
                check(measured.width.value > 0f && measured.height.value > 0f) {
                    "measureWindowContent returned an empty size: $measured"
                }
                val screen = windowMetrics.screen.availableBounds
                DpRect(
                    left = screen.left + SCOPED_INSET,
                    top = screen.top + SCOPED_INSET,
                    right = screen.left + SCOPED_INSET + SCOPED_SIZE.width,
                    bottom = screen.top + SCOPED_INSET + SCOPED_SIZE.height,
                )
            }
            val available = hostMonitor().workAreaDp(window.scaleFactor)
            awaitUntil("scoped provider applied") {
                val outer = outerDp()
                closeEnough(available.left.value + SCOPED_INSET.value, outer.left) &&
                    closeEnough(SCOPED_SIZE.width.value, outer.width)
            }
        }
    }

    private fun requestScreenMovesTheWindow(): TaoWindowTestCase {
        val state = WindowState()
        return TaoWindowTestCase(
            name = "window v2 clone: requestScreen lands the window on the target monitor",
            nucleusWindowState = state,
        ) {
            awaitMapped()
            settle()
            val monitors = TaoMonitors.all(window)
            // Deterministic target: the last monitor in platform order. On a
            // single-monitor box that is the current one, which still exercises
            // the whole path (evaluate → clamp into the work area → apply).
            val target = monitors.last()
            state.requestScreen(WindowScreenProvider.ById(target.id))
            awaitUntil("window centre lands on '${target.id}'") {
                val centre = outerCentrePx()
                target.containsPx(centre.first, centre.second)
            }
            awaitUntil("state.screenId reports '${target.id}'") { state.screenId == target.id }
            val outer = outerDp()
            val available = target.workAreaDp(window.scaleFactor)
            check(outer.left >= available.left.value - TOLERANCE_DP) {
                "the window was not clamped into the target work area: $outer vs $available"
            }
        }
    }

    private fun observedScreenIdTracksTheHostingMonitor(): TaoWindowTestCase {
        val state = WindowState()
        return TaoWindowTestCase(
            name = "window v2 clone: observed screenId matches the monitor hosting the window",
            nucleusWindowState = state,
        ) {
            awaitMapped()
            settle()
            awaitUntil("state.isInitialized") { state.isInitialized }
            val hosting = hostMonitor()
            check(state.screenId == hosting.id) {
                "state.screenId='${state.screenId}' but the window sits on '${hosting.id}'"
            }
            val centre = outerCentrePx()
            check(hosting.containsPx(centre.first, centre.second)) {
                "TaoMonitors.forWindow returned '${hosting.id}', which does not contain the window centre $centre"
            }
            // The enumeration must agree with itself.
            check(TaoMonitors.byId(hosting.id, window) != null) {
                "the hosting monitor '${hosting.id}' is missing from the enumeration"
            }
        }
    }

    // ── Driver helpers ──────────────────────────────────────────────────────

    private suspend fun TaoWindowTestScope.awaitMapped() =
        awaitUntil("window mapped with non-zero outer bounds") {
            val b = bounds()
            b != null && b[RECT_W] > 0 && b[RECT_H] > 0
        }

    private fun TaoWindowTestScope.hostMonitor(): TaoMonitor = TaoMonitors.forWindow(window)

    private fun TaoWindowTestScope.outerCentrePx(): Pair<Int, Int> {
        val b = checkNotNull(bounds()) { "window is not mapped" }
        return (b[RECT_X] + b[RECT_W] / 2).toInt() to (b[RECT_Y] + b[RECT_H] / 2).toInt()
    }

    /** Outer rectangle in the window's own Dp space — what the v2 API reports. */
    private fun TaoWindowTestScope.outerDp(): OuterDp {
        val b = checkNotNull(bounds()) { "window is not mapped" }
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        return OuterDp(
            left = b[RECT_X] / scale,
            top = b[RECT_Y] / scale,
            width = b[RECT_W] / scale,
            height = b[RECT_H] / scale,
        )
    }

    private class OuterDp(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    ) {
        override fun toString(): String = "OuterDp(${left}x$top ${width}x$height)"
    }

    private val DpRect.width: Float get() = (right - left).value

    private val DpRect.height: Float get() = (bottom - top).value

    private fun closeEnough(
        expected: Float,
        actual: Float,
    ): Boolean = abs(expected - actual) <= TOLERANCE_DP

    private fun assertClose(
        expected: Float,
        actual: Float,
        what: String,
    ) = check(closeEnough(expected, actual)) { "$what: expected ~${expected}dp, the window reported ${actual}dp" }

    private val isLinux: Boolean get() = Platform.Current == Platform.Linux

    private const val DIAG_EVERY_POLLS = 40

    private const val RECT_X = 0
    private const val RECT_Y = 1
    private const val RECT_W = 2
    private const val RECT_H = 3

    /** Native frames round to whole pixels, and a WM may nudge a window. */
    private const val TOLERANCE_DP = 24f

    private val INITIAL_SIZE = DpSize(900.dp, 640.dp)
    private val RESIZED = DpSize(1000.dp, 700.dp)
    private val SCOPED_SIZE = DpSize(820.dp, 560.dp)
    private val MOVE_INSET = 120.dp
    private val SCOPED_INSET = 60.dp
}
