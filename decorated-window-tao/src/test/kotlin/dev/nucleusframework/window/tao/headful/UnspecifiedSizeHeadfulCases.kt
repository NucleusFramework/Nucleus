package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.DialogTitleBar
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.math.abs

/**
 * #532 — `Dp.Unspecified` on a window/dialog axis must wrap content, not
 * create a 0-tall (or NaN) native surface that Metal/EGL refuses to draw.
 *
 * #546 — and the position must be resolved against the *measured* size:
 * `Aligned(Center)` centres the window on the work area, a dialog centres on
 * its parent, once the wrap-content size is known.
 */
internal object UnspecifiedSizeHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            windowWrapContentHeight(),
            dialogWrapContentHeight(),
            windowWrapContentCentred(),
            dialogWrapContentCentredOnScreen(),
            dialogWrapContentCentredOnParent(),
        )

    private fun windowWrapContentHeight(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 window wrap-content height maps with non-zero size",
            paintDefaultBackground = false,
            size = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            content = { RedBox() },
        ) {
            awaitUntil("window mapped with wrap-content height") {
                val b = bounds() ?: return@awaitUntil false
                if (b[2] <= 0 || b[3] <= 0) return@awaitUntil false
                b.wrapsContent(window)
            }
            val b = checkNotNull(bounds())
            val heightDp = b[3] / window.scaleFactor
            check(b.wrapsContent(window)) {
                "expected wrap-content height around ${CONTENT_HEIGHT_DP}dp, got ${heightDp}dp"
            }
        }

    private fun dialogWrapContentHeight(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#532 dialog wrap-content height maps with non-zero size",
            paintDefaultBackground = false,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil("dialog mapped with wrap-content height") {
                val b = dialog.outerBoundsPx() ?: return@awaitUntil false
                if (b[2] <= 0 || b[3] <= 0) return@awaitUntil false
                b.wrapsContent(dialog)
            }
        }

    private fun windowWrapContentCentred(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#546 window wrap-content height centres on the work area",
            skip = { if (isNativeWayland) "xdg-shell ignores client positions" else null },
            paintDefaultBackground = false,
            windowState =
                WindowState(
                    size = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
                    position = WindowPosition.Aligned(Alignment.Center),
                ),
            content = { RedBox() },
        ) {
            awaitUntil(
                "window centred at its wrap-content size",
                detail = { "bounds=${bounds()?.toList()} workArea=${workArea().toList()}" },
            ) {
                val b = bounds() ?: return@awaitUntil false
                b.wrapsContent(window) && centresMatch(b, workArea(), CENTRE_TOLERANCE_DP * window.scaleFactor)
            }
        }

    /** A parentless dialog centres on the screen, as AWT's `setLocationRelativeTo(null)`. */
    private fun dialogWrapContentCentredOnScreen(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#546 parentless dialog wrap-content height centres on the work area",
            skip = { if (isNativeWayland) "xdg-shell ignores client positions" else null },
            paintDefaultBackground = false,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil(
                "dialog centred on the work area at its wrap-content size",
                detail = { "dialog=${dialog.outerBoundsPx()?.toList()} workArea=${workArea().toList()}" },
            ) {
                val d = dialog.outerBoundsPx() ?: return@awaitUntil false
                d.wrapsContent(dialog) && centresMatch(d, workArea(), CENTRE_TOLERANCE_DP * dialog.scaleFactor)
            }
        }

    private fun dialogWrapContentCentredOnParent(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#546 dialog wrap-content height centres on the parent",
            skip = { if (isNativeWayland) "xdg-shell ignores client positions" else null },
            paintDefaultBackground = false,
            dialogParentedToWindow = true,
            dialogSize = DpSize(WRAP_WIDTH_DP.dp, Dp.Unspecified),
            dialogContent = {
                DialogTitleBar { }
                RedBox()
            },
        ) {
            val dialog = checkNotNull(dialogWindow) { "dialog window never published" }
            awaitUntil(
                "dialog centred on its parent at its wrap-content size",
                detail = { "dialog=${dialog.outerBoundsPx()?.toList()} parent=${bounds()?.toList()}" },
            ) {
                val d = dialog.outerBoundsPx() ?: return@awaitUntil false
                val p = bounds() ?: return@awaitUntil false
                d.wrapsContent(dialog) && centresMatch(d, p, CENTRE_TOLERANCE_DP * dialog.scaleFactor)
            }
        }

    @Composable
    private fun RedBox() {
        Box(
            modifier =
                Modifier
                    .size(WRAP_WIDTH_DP.dp, CONTENT_HEIGHT_DP.dp)
                    .background(Color.Red),
        )
    }

    /** Whether `[x, y, w, h]` outer bounds are the content height plus at most the platform chrome. */
    private fun LongArray.wrapsContent(window: TaoWindow): Boolean {
        val heightDp = this[3] / window.scaleFactor
        return heightDp in CONTENT_HEIGHT_DP..(CONTENT_HEIGHT_DP + MAX_CHROME_DP)
    }

    private fun TaoWindowTestScope.workArea(): LongArray {
        val wa = TaoMonitors.primary(window).workAreaPx
        return longArrayOf(wa.left.toLong(), wa.top.toLong(), wa.width.toLong(), wa.height.toLong())
    }

    /** Whether the centres of two `[x, y, w, h]` rects are within [tolerancePx] on both axes. */
    private fun centresMatch(
        a: LongArray,
        b: LongArray,
        tolerancePx: Float,
    ): Boolean {
        val dx = (a[0] + a[2] / 2.0) - (b[0] + b[2] / 2.0)
        val dy = (a[1] + a[3] / 2.0) - (b[1] + b[3] / 2.0)
        return abs(dx) <= tolerancePx && abs(dy) <= tolerancePx
    }

    private val isNativeWayland: Boolean
        get() {
            val forcedX11 =
                System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull() == "x11" ||
                    System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
            return System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
        }

    private const val WRAP_WIDTH_DP = 300f
    private const val CONTENT_HEIGHT_DP = 137f

    // Title bar + Linux CSD shadow / macOS traffic-light chrome.
    private const val MAX_CHROME_DP = 220f

    // Outer-vs-inner chrome is not symmetric (title bar, Win32 invisible
    // borders): the un-fixed offsets are hundreds of dp, this is well under.
    private const val CENTRE_TOLERANCE_DP = 40f
}
