package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DialogTitleBar
import java.util.concurrent.atomic.AtomicReference

/**
 * #532 — `Dp.Unspecified` on a window/dialog axis must wrap content, not
 * create a 0-tall (or NaN) native surface that Metal/EGL refuses to draw.
 */
internal object UnspecifiedSizeHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            windowWrapContentHeight(),
            dialogWrapContentHeight(),
        )

    private fun windowWrapContentHeight(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 window wrap-content height maps with non-zero size",
            paintDefaultBackground = false,
            size = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            content = {
                Box(
                    modifier =
                        Modifier
                            .size(WRAP_WIDTH_DP.dp, CONTENT_HEIGHT_DP.dp)
                            .background(Color.Red),
                )
            },
        ) {
            awaitUntil("window mapped with wrap-content height") {
                val b = bounds() ?: return@awaitUntil false
                if (b[2] <= 0 || b[3] <= 0) return@awaitUntil false
                val heightDp = b[3] / window.scaleFactor
                heightDp in CONTENT_HEIGHT_DP..(CONTENT_HEIGHT_DP + MAX_CHROME_DP)
            }
            val b = checkNotNull(bounds())
            val heightDp = b[3] / window.scaleFactor
            check(heightDp in CONTENT_HEIGHT_DP..(CONTENT_HEIGHT_DP + MAX_CHROME_DP)) {
                "expected wrap-content height around ${CONTENT_HEIGHT_DP}dp, got ${heightDp}dp"
            }
        }

    private fun dialogWrapContentHeight(): TaoWindowTestCase {
        val dialogRef = AtomicReference<dev.nucleusframework.window.tao.TaoWindow?>(null)
        return TaoWindowTestCase(
            name = "#532 dialog wrap-content height maps with non-zero size",
            paintDefaultBackground = false,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                Box(
                    modifier =
                        Modifier
                            .size(WRAP_WIDTH_DP.dp, CONTENT_HEIGHT_DP.dp)
                            .background(Color.Red),
                )
                val w = window
                SideEffect { dialogRef.set(w) }
            },
        ) {
            val dialog =
                dialogWindow
                    ?: dialogRef.get()
                    ?: run {
                        awaitUntil("dialog window published") {
                            dialogRef.get() != null
                        }
                        checkNotNull(dialogRef.get())
                    }
            awaitUntil("dialog mapped with wrap-content height") {
                val b = dialog.outerBoundsPx() ?: return@awaitUntil false
                if (b[2] <= 0 || b[3] <= 0) return@awaitUntil false
                val heightDp = b[3] / dialog.scaleFactor
                heightDp in CONTENT_HEIGHT_DP..(CONTENT_HEIGHT_DP + MAX_CHROME_DP)
            }
        }
    }

    private const val WRAP_WIDTH_DP = 300f
    private const val CONTENT_HEIGHT_DP = 137f

    // Title bar + Linux CSD shadow / macOS traffic-light chrome.
    private const val MAX_CHROME_DP = 220f
}
