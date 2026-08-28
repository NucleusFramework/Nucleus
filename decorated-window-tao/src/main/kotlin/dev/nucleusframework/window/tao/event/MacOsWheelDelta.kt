package dev.nucleusframework.window.tao.event

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TaoPointerScrollEvent

/** Same factor [dev.nucleusframework.window.tao.TaoWindow] uses on `SCROLL_PIXEL`. */
internal const val AWT_PIXEL_TO_ROTATION: Float = 10f

/** macOS AWT `MouseWheelEvent.scrollAmount`. */
internal const val MACOS_AWT_SCROLL_AMOUNT: Int = 1

/**
 * Maps raw AppKit `scrollingDelta*` onto AWT `preciseWheelRotation`.
 *
 * Matches [dev.nucleusframework.window.tao.TaoWindow] `SCROLL_LINE` /
 * `SCROLL_PIXEL`: tao already flips X then Kotlin negates both axes, so
 * the net sign from raw AppKit is `Offset(dx, -dy)`. Precise (trackpad)
 * deltas are converted to physical pixels then divided by 10, same as
 * AWT's NSEvent → `preciseWheelRotation` conversion.
 *
 * Popup NSPanel content views skip tao and must go through this before
 * Compose.
 */
internal fun appKitWheelToAwtScrollDelta(
    dx: Float,
    dy: Float,
    precise: Boolean,
    scale: Float,
): Offset {
    val awtSign = Offset(dx, -dy)
    return if (precise) awtSign * (scale / AWT_PIXEL_TO_ROTATION) else awtSign
}

internal fun appKitWheelToAwtScrollEvent(
    dx: Float,
    dy: Float,
    precise: Boolean,
    scale: Float,
): TaoPointerScrollEvent {
    val delta = appKitWheelToAwtScrollDelta(dx, dy, precise, scale)
    return TaoPointerScrollEvent(
        dxAwt = delta.x,
        dyAwt = delta.y,
        // 1, like TaoWindow / Windows: MacOSCocoaConfig does not read a
        // lines-per-notch multiplier out of scrollAmount the way LinuxGtkConfig
        // does. Do not copy LINUX_AWT_SCROLL_AMOUNT_DEFAULT here.
        scrollAmount = MACOS_AWT_SCROLL_AMOUNT,
    )
}
