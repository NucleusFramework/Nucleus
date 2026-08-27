package dev.nucleusframework.window.tao.event

import androidx.compose.ui.geometry.Offset

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
