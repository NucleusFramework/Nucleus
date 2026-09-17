package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.unit.IntSize
import java.util.concurrent.ConcurrentHashMap

/**
 * Size of the last frame each window's host presented, keyed by
 * `TaoWindow.handle` — the seam the headful suite asserts the #576 contract
 * through: a resize event must not end its run-loop turn before a frame at
 * the new size has been presented, or the compositor (Core Animation on
 * macOS, DWM on Windows) shows the previous frame stretched to the new
 * bounds and the whole content trembles.
 *
 * The macOS (Metal) and Windows (ANGLE) hosts record; the Linux host leaves
 * its entries `null`.
 * Same shape as [dev.nucleusframework.window.tao.popup.TaoPopupDiagnostics]:
 * plain writes on the frame path, not snapshot state.
 */
internal object TaoPresentDiagnostics {
    private val last = ConcurrentHashMap<Long, IntSize>()

    fun record(
        windowHandle: Long,
        sizePx: IntSize,
    ) {
        last[windowHandle] = sizePx
    }

    /** Physical size of the last frame presented for [windowHandle], `null` before the first. */
    fun lastPresentedPx(windowHandle: Long): IntSize? = last[windowHandle]
}
