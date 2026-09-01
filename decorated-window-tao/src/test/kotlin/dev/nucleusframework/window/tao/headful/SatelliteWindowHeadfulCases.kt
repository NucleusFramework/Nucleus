package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.SatelliteWindowState
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Real-window coverage for `SatelliteWindow` — the Flutter satellite archetype
 * on Tao. Everything here is asserted against live `outerBoundsPx()` rects of
 * two actual OS windows, never against Kotlin-side caches:
 *
 *  1. the anchored initial placement resolved by the [WindowPositioner];
 *  2. the parent-relative follow, including re-capturing the offset after the
 *     satellite has been moved independently;
 *  3. suppression while the parent is maximized, and re-anchoring on restore;
 *  4. [SatelliteWindowState.reanchor] snapping a dragged satellite back;
 *  5. reparenting in the very frame the old owner closes — the satellite stays
 *     where it is, is not taken down with its former owner, and follows the
 *     new one.
 *
 * Native Wayland is skipped: xdg-shell gives clients no way to position their
 * own toplevels, so the anchoring and follow paths are documented no-ops there
 * (the ownership and z-order half still applies, but is not observable through
 * window rects).
 */
internal object SatelliteWindowHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            anchorsAndFollowsParent(),
            hidesWhileParentIsMaximized(),
            reanchorSnapsBackToThePositioner(),
            reparentOutlivesOldOwner(),
        )

    /** Parent geometry every case starts from — well inside a 1024×768 work area. */
    private fun parentWindowState() =
        WindowState(
            position = WindowPosition.Absolute(PARENT_X_DP.dp, PARENT_Y_DP.dp),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
        )

    /**
     * Hangs the satellite off the parent's right edge, vertically centred, with
     * a fixed gap. [WindowConstraintAdjustment.None] keeps the expected rect
     * arithmetic exact — no flip/slide can kick in at this position.
     */
    private fun rightEdgeState() =
        SatelliteWindowState(
            size = DpSize(SATELLITE_W_DP.dp, SATELLITE_H_DP.dp),
            positioner =
                WindowPositioner(
                    parentAnchor = WindowAnchor.Right,
                    childAnchor = WindowAnchor.Left,
                    offset = DpOffset(GAP_DP.dp, 0.dp),
                    constraintAdjustment = WindowConstraintAdjustment.None,
                ),
        )

    private fun anchorsAndFollowsParent(): TaoWindowTestCase {
        val satellite = rightEdgeState()
        return TaoWindowTestCase(
            name = "satellite anchors to the parent's right edge and follows it",
            skip = ::skipReason,
            windowState = parentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            satelliteState = satellite,
            satelliteContent = { Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF))) },
            driver = {
                val satelliteWindow = awaitSatellite(satellite)
                val parentRect = requireNotNull(bounds())
                val satelliteRect = requireNotNull(satelliteBounds())

                // ── 1. anchored placement ──
                val scale = window.scaleFactor
                val expectedLeft = parentRect[0] + parentRect[2] + (GAP_DP * scale).toLong()
                check(abs(satelliteRect[0] - expectedLeft) <= ANCHOR_TOLERANCE_PX) {
                    "satellite left ${satelliteRect[0]} is not anchored to the parent's " +
                        "right edge + gap ($expectedLeft); parent=${parentRect.toList()} " +
                        "satellite=${satelliteRect.toList()} scale=$scale"
                }
                // The initial placement predates the native window, so it uses
                // the *requested* height; the real frame may include a CSD
                // shadow margin. Fold that difference into the tolerance
                // instead of pretending the centring is pixel-exact.
                val requestedHeightPx = (SATELLITE_H_DP * scale).toLong()
                val centringTolerance =
                    ANCHOR_TOLERANCE_PX + abs(satelliteRect[3] - requestedHeightPx) / 2
                val parentCentreY = parentRect[1] + parentRect[3] / 2
                val satelliteCentreY = satelliteRect[1] + satelliteRect[3] / 2
                check(abs(satelliteCentreY - parentCentreY) <= centringTolerance) {
                    "satellite is not vertically centred on its parent: " +
                        "$satelliteCentreY vs $parentCentreY (tolerance $centringTolerance)"
                }

                // ── 2. the satellite follows the parent ──
                val anchoredOffsetX = satelliteRect[0] - parentRect[0]
                val anchoredOffsetY = satelliteRect[1] - parentRect[1]
                moveParentBy(MOVE_DELTA_DP, MOVE_DELTA_DP)
                awaitUntil("parent moved") {
                    val now = bounds() ?: return@awaitUntil false
                    now[0] != parentRect[0] || now[1] != parentRect[1]
                }
                awaitUntil("satellite kept its offset from the parent") {
                    keepsOffset(anchoredOffsetX, anchoredOffsetY)
                }

                // ── 3. an independent move re-captures the offset ──
                val movedParent = requireNotNull(bounds())
                val draggedX = (movedParent[0] + DRAG_DELTA_PX).toInt()
                val draggedY = (movedParent[1] + DRAG_DELTA_PX).toInt()
                satelliteWindow.setOuterPositionPx(draggedX, draggedY)
                awaitUntil("satellite landed at the dragged position") {
                    val now = satelliteBounds() ?: return@awaitUntil false
                    abs(now[0] - draggedX) <= ANCHOR_TOLERANCE_PX &&
                        abs(now[1] - draggedY) <= ANCHOR_TOLERANCE_PX
                }
                settle()
                val userOffset =
                    requireNotNull(satellite.offsetFromParent) {
                        "offsetFromParent must be published once both windows are mapped"
                    }
                val satelliteScale = satelliteWindow.scaleFactor
                check(abs(userOffset.x.value * satelliteScale - DRAG_DELTA_PX) <= OFFSET_TOLERANCE_PX) {
                    "offsetFromParent.x (${userOffset.x}) does not reflect the manual move"
                }

                // ── 4. and *that* offset is what the next parent move keeps ──
                val beforeParent = requireNotNull(bounds())
                val beforeSatellite = requireNotNull(satelliteBounds())
                moveParentBy(-MOVE_DELTA_DP, MOVE_DELTA_DP)
                awaitUntil("parent moved again") {
                    val now = bounds() ?: return@awaitUntil false
                    now[0] != beforeParent[0] || now[1] != beforeParent[1]
                }
                awaitUntil("satellite preserved the user-established offset") {
                    keepsOffset(
                        beforeSatellite[0] - beforeParent[0],
                        beforeSatellite[1] - beforeParent[1],
                    )
                }
            },
        )
    }

    private fun hidesWhileParentIsMaximized(): TaoWindowTestCase {
        val satellite = rightEdgeState()
        return TaoWindowTestCase(
            name = "satellite hides while its parent is maximized and re-anchors on restore",
            skip = ::skipReason,
            windowState = parentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            satelliteState = satellite,
            satelliteContent = { Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF))) },
            driver = {
                awaitSatellite(satellite)
                check(!satellite.isHiddenByParent) { "satellite must start visible" }
                val parentRect = requireNotNull(bounds())
                val satelliteRect = requireNotNull(satelliteBounds())
                val offsetX = satelliteRect[0] - parentRect[0]
                val offsetY = satelliteRect[1] - parentRect[1]

                window.setMaximized(true)
                awaitUntil("satellite suppressed while the parent is maximized") {
                    satellite.isHiddenByParent
                }

                window.setMaximized(false)
                awaitUntil("satellite restored after the parent is unmaximized") {
                    !satellite.isHiddenByParent
                }
                val realigned =
                    awaitOrFalse(RESTORE_TIMEOUT_MILLIS) { keepsOffset(offsetX, offsetY) }
                check(realigned) {
                    "satellite was not re-anchored on the restored parent: " +
                        "parent=${bounds()?.toList()} satellite=${satelliteBounds()?.toList()} " +
                        "expected offset=($offsetX, $offsetY) " +
                        "published=${satellite.offsetFromParent}"
                }
                // Restoring must not have orphaned the window: it is still
                // mapped with a real size.
                val restored = requireNotNull(satelliteBounds())
                check(restored[2] > 0 && restored[3] > 0) {
                    "satellite has no size after restore: ${restored.toList()}"
                }
            },
        )
    }

    private fun reanchorSnapsBackToThePositioner(): TaoWindowTestCase {
        val satellite = rightEdgeState()
        return TaoWindowTestCase(
            name = "satellite reanchor re-applies the positioner after a manual move",
            skip = ::skipReason,
            windowState = parentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            satelliteState = satellite,
            satelliteContent = { Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF))) },
            driver = {
                val satelliteWindow = awaitSatellite(satellite)
                val parentRect = requireNotNull(bounds())
                val anchoredLeft = requireNotNull(satelliteBounds())[0]

                satelliteWindow.setOuterPositionPx(
                    (parentRect[0] + DRAG_DELTA_PX).toInt(),
                    (parentRect[1] + DRAG_DELTA_PX).toInt(),
                )
                awaitUntil("satellite left its anchor") {
                    val now = satelliteBounds() ?: return@awaitUntil false
                    abs(now[0] - anchoredLeft) > ANCHOR_TOLERANCE_PX
                }
                settle()

                satellite.reanchor()
                val scale = window.scaleFactor
                awaitUntil("reanchor put the satellite back on the parent's right edge") {
                    val parentNow = bounds() ?: return@awaitUntil false
                    val satelliteNow = satelliteBounds() ?: return@awaitUntil false
                    val expectedLeft = parentNow[0] + parentNow[2] + (GAP_DP * scale).toLong()
                    abs(satelliteNow[0] - expectedLeft) <= ANCHOR_TOLERANCE_PX
                }
                // reanchor() re-reads the real frame, so the centring is exact
                // this time round.
                val parentNow = requireNotNull(bounds())
                val satelliteNow = requireNotNull(satelliteBounds())
                val parentCentreY = parentNow[1] + parentNow[3] / 2
                val satelliteCentreY = satelliteNow[1] + satelliteNow[3] / 2
                check(abs(satelliteCentreY - parentCentreY) <= ANCHOR_TOLERANCE_PX) {
                    "reanchor did not re-centre the satellite: " +
                        "$satelliteCentreY vs $parentCentreY"
                }
            },
        )
    }

    /**
     * The demo's "close the document the palette is attached to" flow. The
     * satellite starts out owned by the suite's dialog window; the driver then
     * hands it to the case window *and* drops the dialog in the same frame.
     * Win32 and GTK destroy owned windows together with their owner, so this
     * only holds because the satellite severs the owner link before the dialog
     * goes — and the close decision is taken from composition, where the new
     * owner is already known.
     */
    private fun reparentOutlivesOldOwner(): TaoWindowTestCase {
        val satellite = rightEdgeState()
        val owner = mutableStateOf(SatelliteOwner.DialogWindow)
        val dialogVisible = mutableStateOf(true)
        val closeRequests = AtomicInteger()
        return TaoWindowTestCase(
            name = "satellite reparented as its owner closes keeps its place and follows the new owner",
            skip = ::skipReason,
            windowState = parentWindowState(),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            dialogSize = DpSize(DIALOG_W_DP.dp, DIALOG_H_DP.dp),
            dialogContent = { Box(Modifier.fillMaxSize().background(Color(0xFF3C8D5A))) },
            dialogVisible = dialogVisible,
            satelliteState = satellite,
            satelliteOwner = owner,
            satelliteOnCloseRequest = { closeRequests.incrementAndGet() },
            satelliteContent = { Box(Modifier.fillMaxSize().background(Color(0xFF2D6CDF))) },
            driver = {
                awaitSatellite(satellite)
                val dialog = requireNotNull(dialogWindow) { "dialog window was never published" }
                settle()

                // ── 1. owned by, and anchored to, the dialog — not the case window ──
                val dialogRect = requireNotNull(dialog.outerBoundsPx())
                val before = requireNotNull(satelliteBounds())
                val scale = dialog.scaleFactor
                val expectedLeft = dialogRect[0] + dialogRect[2] + (GAP_DP * scale).toLong()
                check(abs(before[0] - expectedLeft) <= ANCHOR_TOLERANCE_PX) {
                    "satellite left ${before[0]} is not anchored to the dialog's right edge + gap " +
                        "($expectedLeft); dialog=${dialogRect.toList()} satellite=${before.toList()}"
                }

                // ── 2. new owner and old owner gone, same frame ──
                var dialogDestroyed = false
                dialog.onDestroyed { dialogDestroyed = true }
                owner.value = SatelliteOwner.CaseWindow
                dialogVisible.value = false
                awaitUntil("former owner destroyed") { dialogDestroyed }
                settle(SETTLE_AFTER_MAP_MILLIS)

                check(closeRequests.get() == 0) {
                    "the former owner's death was reported as the satellite's own close request"
                }
                val after =
                    requireNotNull(satelliteBounds()) { "satellite was destroyed together with its former owner" }
                check(after[2] > 0 && after[3] > 0) { "satellite has no size after reparenting: ${after.toList()}" }
                check(
                    abs(after[0] - before[0]) <= FOLLOW_TOLERANCE_PX &&
                        abs(after[1] - before[1]) <= FOLLOW_TOLERANCE_PX,
                ) {
                    "reparenting moved the satellite: before=${before.toList()} after=${after.toList()}"
                }

                // ── 3. from here on it follows the case window ──
                val parentRect = requireNotNull(bounds())
                val offsetX = after[0] - parentRect[0]
                val offsetY = after[1] - parentRect[1]
                val published =
                    requireNotNull(satellite.offsetFromParent) { "offsetFromParent lost across the reparent" }
                val satelliteScale = requireNotNull(satelliteWindow).scaleFactor
                check(abs(published.x.value * satelliteScale - offsetX) <= OFFSET_TOLERANCE_PX) {
                    "offsetFromParent.x (${published.x}) is not relative to the new owner (expected $offsetX px)"
                }
                moveParentBy(MOVE_DELTA_DP, MOVE_DELTA_DP)
                awaitUntil("new owner moved") {
                    val now = bounds() ?: return@awaitUntil false
                    now[0] != parentRect[0] || now[1] != parentRect[1]
                }
                awaitUntil("satellite follows its new owner") { keepsOffset(offsetX, offsetY) }
            },
        )
    }

    /** Waits until both windows are mapped and the follow offset is captured. */
    private suspend fun TaoWindowTestScope.awaitSatellite(state: SatelliteWindowState) =
        run {
            awaitUntil("parent mapped") { bounds() != null }
            awaitUntil("satellite mapped with a real size") {
                val rect = satelliteBounds() ?: return@awaitUntil false
                rect[2] > 0 && rect[3] > 0
            }
            awaitUntil("satellite captured its parent offset") { state.offsetFromParent != null }
            settle(SETTLE_AFTER_MAP_MILLIS)
            requireNotNull(satelliteWindow) { "satellite window was never published" }
        }

    /** Bounded poll that reports the outcome instead of throwing, so the caller can log state. */
    private suspend fun awaitOrFalse(
        timeoutMillis: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            kotlinx.coroutines.delay(POLL_MILLIS)
        }
        return predicate()
    }

    /** True while the satellite still sits at ([offsetX], [offsetY]) off the parent. */
    private fun TaoWindowTestScope.keepsOffset(
        offsetX: Long,
        offsetY: Long,
    ): Boolean {
        val parentRect = bounds() ?: return false
        val satelliteRect = satelliteBounds() ?: return false
        return abs((satelliteRect[0] - parentRect[0]) - offsetX) <= FOLLOW_TOLERANCE_PX &&
            abs((satelliteRect[1] - parentRect[1]) - offsetY) <= FOLLOW_TOLERANCE_PX
    }

    /** Moves the parent by a logical delta, in the dp space `WindowState` uses. */
    private fun TaoWindowTestScope.moveParentBy(
        dxDp: Double,
        dyDp: Double,
    ) {
        val rect = requireNotNull(bounds())
        val scale = window.scaleFactor.toDouble()
        window.setOuterPosition(rect[0] / scale + dxDp, rect[1] / scale + dyDp)
    }

    /**
     * Native Wayland has no client-side toplevel positioning, so neither the
     * anchored placement nor the follow is observable there. Mirrors the
     * backend detection of the suite's own `setOuterPosition` case.
     */
    private fun skipReason(): String? {
        if (Platform.Current != Platform.Linux) return null
        val backend = System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull()
        val forcedX11 =
            backend == "x11" ||
                System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
        val wayland = System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
        return if (wayland) "no client window positioning on Wayland (xdg-shell)" else null
    }

    private const val PARENT_X_DP = 120
    private const val PARENT_Y_DP = 90
    private const val PARENT_W_DP = 420
    private const val PARENT_H_DP = 300
    private const val SATELLITE_W_DP = 220
    private const val SATELLITE_H_DP = 160
    private const val DIALOG_W_DP = 260
    private const val DIALOG_H_DP = 200
    private const val GAP_DP = 10

    private const val MOVE_DELTA_DP = 70.0
    private const val DRAG_DELTA_PX = 60L

    /** Logical → physical rounding slack on a single edge. */
    private const val ANCHOR_TOLERANCE_PX = 6L

    /** Two rects sampled from two windows mid-flight; one extra rounding step. */
    private const val FOLLOW_TOLERANCE_PX = 8L
    private const val OFFSET_TOLERANCE_PX = 8f
    private const val SETTLE_AFTER_MAP_MILLIS = 400L
    private const val RESTORE_TIMEOUT_MILLIS = 5_000L
    private const val POLL_MILLIS = 25L
}
