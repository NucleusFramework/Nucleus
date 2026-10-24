package dev.nucleusframework.lab.probes.input.scroll

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max

/** One physical gesture: a burst of scroll steps closed by a Pan boundary or [ScrollMeter.idleMs] of silence. */
@Immutable
data class GestureStat(
    val index: Int,
    val events: Int,
    /** Σ of the raw deltas, in wheel units (Pan steps converted at 10 dp per unit). */
    val rawSum: Offset,
    val pxScrolled: Int,
    val durationMs: Long,
    val maxRawAbsY: Float,
    /** Frames rendered per second over the gesture window, input tail included. */
    val fps: Int,
)

/**
 * Measures scroll *impact*: raw deltas fed by the backend vs pixels actually scrolled, per
 * gesture. Pure and single-threaded (UI thread only) so it is unit-testable with fake clocks.
 *
 * Ported from nucleus-demo's ScrollTestScreen, the reference compared against
 * `tools/scroll-test.html` in a browser.
 */
class ScrollMeter(
    val idleMs: Long = 180,
) {
    private var inGesture = false
    private var startValuePx = 0
    private var startTimeMs = 0L
    private var lastTimeMs = 0L
    private var events = 0
    private var rawSum = Offset.Zero
    private var maxRawAbsY = 0f
    private var frames = 0L
    private var startFrames = 0L
    private var counter = 0

    fun onFrame() {
        frames++
    }

    /** A delta in wheel units at [nowMs]; [scrollValuePx] is the scroll position *before* it applies. */
    fun onDelta(
        nowMs: Long,
        delta: Offset,
        scrollValuePx: Int,
    ) {
        if (!inGesture || nowMs - lastTimeMs > idleMs) {
            inGesture = true
            startValuePx = scrollValuePx
            startTimeMs = nowMs
            startFrames = frames
            events = 0
            rawSum = Offset.Zero
            maxRawAbsY = 0f
        }
        events++
        rawSum += delta
        maxRawAbsY = max(maxRawAbsY, abs(delta.y))
        lastTimeMs = nowMs
    }

    /** A Pan boundary (start or end) closes the open gesture right away: two quick swipes never merge. */
    fun onBoundary(
        nowMs: Long,
        scrollValuePx: Int,
    ): GestureStat? = finalize(nowMs, scrollValuePx)

    /** Polled by a ticker: closes the gesture once [idleMs] passed without a delta (wheel input). */
    fun onIdleCheck(
        nowMs: Long,
        scrollValuePx: Int,
    ): GestureStat? = if (inGesture && nowMs - lastTimeMs >= idleMs) finalize(nowMs, scrollValuePx) else null

    fun reset() {
        inGesture = false
        counter = 0
    }

    private fun finalize(
        nowMs: Long,
        scrollValuePx: Int,
    ): GestureStat? {
        if (!inGesture) return null
        inGesture = false
        val windowMs = (nowMs - startTimeMs).coerceAtLeast(1)
        return GestureStat(
            index = ++counter,
            events = events,
            rawSum = rawSum,
            pxScrolled = scrollValuePx - startValuePx,
            durationMs = (lastTimeMs - startTimeMs).coerceAtLeast(0),
            maxRawAbsY = maxRawAbsY,
            fps = ((frames - startFrames) * 1000L / windowMs).toInt(),
        )
    }
}
