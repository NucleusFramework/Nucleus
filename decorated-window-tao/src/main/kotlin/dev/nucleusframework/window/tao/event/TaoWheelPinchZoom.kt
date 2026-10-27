package dev.nucleusframework.window.tao.event

import kotlin.math.pow

/**
 * Maps a Ctrl+wheel / precision-touchpad wheel delta to a multiplicative zoom step.
 * Shared by the Windows and Linux hosts, which both turn Ctrl+wheel into a
 * Compose scale gesture so it zooms (never scrolls) — the AWT backend has no
 * pinch-zoom, so this gives Windows/Linux the same behaviour.
 */
internal object TaoWheelPinchZoom {
    private const val WHEEL_DELTAS_PER_DOUBLING: Float = 12f

    /**
     * Wheel deltas are WHEEL_DELTA-normalized (≈1.0 per notch, fractional for precision
     * touchpads). Treat them as a continuous stream: fractional deltas compose to the same
     * zoom as a single larger delta, and one full wheel delta stays moderate.
     */
    fun stepFromWheelDelta(delta: Float): Float = if (delta == 0f) 1f else 2f.pow(delta / WHEEL_DELTAS_PER_DOUBLING)
}
