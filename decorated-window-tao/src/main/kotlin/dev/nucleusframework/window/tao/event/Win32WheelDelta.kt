package dev.nucleusframework.window.tao.event

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TaoPointerScrollEvent

/**
 * Maps a raw Win32 wheel delta (`GET_WHEEL_DELTA_WPARAM / WHEEL_DELTA`) onto
 * AWT `preciseWheelRotation` / [dev.nucleusframework.window.tao.TaoPointerScrollEvent]
 * sign.
 *
 * Win32 positive is away from the user; AWT positive is towards the user.
 * [dev.nucleusframework.window.tao.TaoWindow] applies the same negate on
 * `SCROLL_LINE` / `SCROLL_PIXEL`. Popup and overlay WndProcs emit the raw
 * Win32 units and must go through this before Compose.
 */
internal fun win32WheelToAwtScrollDelta(
    dx: Float,
    dy: Float,
): Offset = Offset(-dx, -dy)

/**
 * Raw Win32 units → AWT-shaped event. `scrollAmount = 1` matches
 * [dev.nucleusframework.window.tao.TaoWindow] on Windows; the three-lines-per-notch
 * policy lives in [TaoWindowsScrollConfig].
 */
internal fun win32WheelToAwtScrollEvent(
    dx: Float,
    dy: Float,
): TaoPointerScrollEvent {
    val delta = win32WheelToAwtScrollDelta(dx, dy)
    return TaoPointerScrollEvent(
        dxAwt = delta.x,
        dyAwt = delta.y,
        scrollAmount = 1,
    )
}
