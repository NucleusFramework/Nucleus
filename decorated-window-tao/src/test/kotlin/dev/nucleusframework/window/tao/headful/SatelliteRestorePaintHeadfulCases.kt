package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import kotlin.math.abs

/**
 * A satellite must come back painted when its parent is restored from the
 * taskbar.
 *
 * Win32 hides the windows a window owns when it is minimized and shows them
 * again on restore (`WM_SHOWWINDOW` with `SW_PARENTOPENING`), without the app
 * ever calling show. DWM does not keep the hidden window's last frame, and the
 * satellite's scene is clean — nothing changed while it was away — so unless
 * that re-show forces a present, the satellite shows as a blank (white) frame
 * until something invalidates it, such as the pointer passing over it.
 *
 * The case reads the satellite back after each restore, with the pointer left
 * alone: through `PrintWindow` (DWM's copy of it), which decides, and from the
 * screen, which is only logged.
 */
internal object SatelliteRestorePaintHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(aSatelliteIsPaintedAfterItsParentIsRestored())

    private fun skipReason(): String? =
        when {
            Platform.Current != Platform.Windows -> "Windows only: owned windows hide and re-show with their owner"
            !NativeTaoWindowsNativeViewBridge.isLoaded -> "nucleus_tao_windows_native_view not loaded"
            else -> null
        }

    private fun aSatelliteIsPaintedAfterItsParentIsRestored(): TaoWindowTestCase {
        val satellite =
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
        return TaoWindowTestCase(
            name = "windows a satellite is painted after its parent is restored",
            skip = ::skipReason,
            windowState =
                WindowState(
                    position = WindowPosition.Absolute(PARENT_X_DP.dp, PARENT_Y_DP.dp),
                    size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
                ),
            size = DpSize(PARENT_W_DP.dp, PARENT_H_DP.dp),
            satelliteState = satellite,
            satelliteContent = { Box(Modifier.fillMaxSize().background(FILL)) },
            driver = {
                awaitUntil("parent mapped") { bounds() != null }
                awaitUntil("satellite mapped with a real size") { satelliteWindow?.hasRealFramePx() == true }
                awaitUntil("satellite captured its parent offset") { satellite.offsetFromParent != null }
                window.focus()
                settle(SETTLE_MILLIS)
                val before = screenCoverage()
                check(before >= MIN_COVERAGE) { "the satellite is not painted before the minimize: $before" }

                for (round in 1..ROUNDS) {
                    window.setMinimized(true)
                    awaitUntil("parent minimized") { window.isMinimized }
                    settle(SETTLE_MILLIS)
                    window.setMinimized(false)
                    awaitUntil("parent restored") { !window.isMinimized }
                    // A taskbar click also activates the window: without it,
                    // whatever else is in front would be what the screen shows.
                    window.focus()
                    settle(SETTLE_MILLIS)
                    val dwmCopy = dwmCopyCoverage()
                    val screen = screenCoverage()
                    println("[satellite-restore] round=$round before=$before dwmCopy=$dwmCopy screen=$screen")
                    // DWM's copy is the verdict: it was as white as the screen
                    // with the bug, and no other window can stand in front of it
                    // (Windows may refuse the focus above to a background process).
                    check(dwmCopy >= MIN_COVERAGE) {
                        "round $round: after the restore the satellite shows its content on only $dwmCopy of " +
                            "DWM's copy (screen: $screen)"
                    }
                }
            },
        )
    }

    /** [coverage] of the screen under the satellite: what the user sees. */
    private fun TaoWindowTestScope.screenCoverage(): Double {
        val rect = satelliteBounds() ?: return 0.0
        return coverage(
            NativeTaoWindowsNativeViewBridge.nativeDiagCaptureScreen(
                rect[0].toInt(),
                rect[1].toInt(),
                rect[2].toInt(),
                rect[3].toInt(),
            ),
        )
    }

    /** [coverage] of DWM's copy of the satellite (`PrintWindow`), whatever is stacked over it. */
    private fun TaoWindowTestScope.dwmCopyCoverage(): Double {
        val hwnd = satelliteWindow?.nativeHandle ?: return 0.0
        return coverage(NativeTaoWindowsNativeViewBridge.nativeDiagPrintClient(hwnd))
    }

    /**
     * Share of a `[width, height, argb…]` capture's pixels, inset from its
     * edges, that show [FILL]; 0 for no capture.
     */
    private fun coverage(shot: IntArray?): Double {
        if (shot == null) return 0.0
        val width = shot[0]
        val height = shot[1]
        var matching = 0
        var total = 0
        for (y in EDGE_INSET_PX until height - EDGE_INSET_PX step SAMPLE_STEP_PX) {
            for (x in EDGE_INSET_PX until width - EDGE_INSET_PX step SAMPLE_STEP_PX) {
                total++
                if (shot[2 + y * width + x].matches(FILL.toArgb())) matching++
            }
        }
        return if (total == 0) 0.0 else matching.toDouble() / total
    }

    private fun Int.matches(expected: Int): Boolean =
        (0..16 step 8).all { shift ->
            abs((this shr shift and 0xFF) - (expected shr shift and 0xFF)) <= CHANNEL_TOLERANCE
        }

    private val FILL = Color(0xFF2D6CDF)

    private const val PARENT_X_DP = 120
    private const val PARENT_Y_DP = 120
    private const val PARENT_W_DP = 420
    private const val PARENT_H_DP = 300
    private const val SATELLITE_W_DP = 200
    private const val SATELLITE_H_DP = 240
    private const val GAP_DP = 12
    private const val ROUNDS = 3
    private const val SETTLE_MILLIS = 700L
    private const val EDGE_INSET_PX = 16
    private const val SAMPLE_STEP_PX = 4
    private const val CHANNEL_TOLERANCE = 6
    private const val MIN_COVERAGE = 0.98
}
