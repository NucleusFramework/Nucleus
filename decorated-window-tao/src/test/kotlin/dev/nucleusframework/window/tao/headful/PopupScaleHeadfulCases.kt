package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.nucleusframework.core.runtime.Platform
import kotlinx.coroutines.delay

/**
 * Headful regression cases for issue #502 — "Tao/Wayland: 1x1 popup surface
 * trips a protocol error and crash".
 *
 * With `nativePopupLayers = true` a Compose `Popup` becomes a real Tao popup
 * window whose EGL child is a `wl_subsurface` announcing
 * `wl_surface.set_buffer_scale(scale)`. Wayland requires every attached buffer
 * to be an integer multiple of that scale, and Mutter enforces it by dropping
 * the connection:
 *
 * ```
 * wl_display#1.error(wl_surface#66, 2,
 *   "Buffer size (1x1) must be an integer multiple of the buffer_scale (2).")
 * Gdk-Message: Error 71 (Protocol error) dispatching to Wayland display.
 * ```
 *
 * Both cases assert the same thing — the process is still alive after the popup
 * was shown — because the defect kills it outright. They are only meaningful on
 * a Wayland session with `scale >= 2`: at 100% every size is trivially aligned,
 * on X11 there is no `buffer_scale`, and weston does not police the rule at all
 * (it silently upscales), so a green run there proves nothing. Verified against
 * GNOME 50 Wayland at 200%, where both cases fail before the fix.
 */
internal object PopupScaleHeadfulCases {
    private val isLinux: Boolean get() = Platform.Current == Platform.Linux

    fun all(): List<TaoWindowTestCase> =
        listOf(
            oddSizedPopup(),
            zeroSizedPopup(),
        )

    /**
     * A popup whose content measures an ODD number of physical pixels
     * (50.5 dp × 30.5 dp at density 2 = 101 × 61 px) — the everyday shape of
     * the bug, since text measurement and half-dp padding produce odd sizes
     * routinely.
     */
    private fun oddSizedPopup(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#502 popup sized to an odd pixel count survives a scaled output",
            skip = { if (!isLinux) "Linux only" else null },
            nativePopupLayers = true,
            content = {
                var show by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(POPUP_DELAY_MILLIS)
                    show = true
                }
                if (show) {
                    Popup(alignment = Alignment.Center) {
                        Box(Modifier.size(ODD_W_DP.dp, ODD_H_DP.dp).background(Color.Red))
                    }
                }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle(SETTLE_MILLIS)
            check(bounds() != null) { "window must survive an odd-sized popup" }
        }

    /**
     * The literal shape reported in #502: a popup that measures 0 × 0, whose
     * native frame the layer coerces to the smallest legal surface. Before the
     * fix that was 1 × 1 — committed with `buffer_scale = 2`.
     */
    private fun zeroSizedPopup(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#502 popup that measures zero survives a scaled output",
            skip = { if (!isLinux) "Linux only" else null },
            nativePopupLayers = true,
            content = {
                var show by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(POPUP_DELAY_MILLIS)
                    show = true
                }
                if (show) {
                    Popup(alignment = Alignment.Center) {
                        Box(Modifier.size(0.dp))
                    }
                }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle(SETTLE_MILLIS)
            check(bounds() != null) { "window must survive a zero-sized popup" }
        }

    private const val ODD_W_DP = 50.5f
    private const val ODD_H_DP = 30.5f
    private const val POPUP_DELAY_MILLIS = 500L
    private const val SETTLE_MILLIS = 2_500L
}
