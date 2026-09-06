package dev.nucleusframework.window.tao.event

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoScrollGesturePhase

/** Same factor [dev.nucleusframework.window.tao.TaoWindow] uses on `SCROLL_PIXEL`. */
internal const val AWT_PIXEL_TO_ROTATION: Float = 10f

/** macOS AWT `MouseWheelEvent.scrollAmount`. */
internal const val MACOS_AWT_SCROLL_AMOUNT: Int = 1

/**
 * Maps raw AppKit `scrollingDelta*` onto AWT `preciseWheelRotation` the way
 * OpenJDK's `AWTView.m` + `CPlatformResponder` do: `-[event deltaX/Y]`, where
 * a precise (trackpad) event's legacy delta is `scrollingDelta × 0.1` in
 * points. AppKit's sign is "positive = content moves down / right", AWT's is
 * "positive = scroll down / right" — both axes flip (#652) — and the display
 * scale never enters (#653).
 *
 * Same net result as [dev.nucleusframework.window.tao.TaoWindow] `SCROLL_LINE`
 * / `SCROLL_PIXEL`. Popup NSPanel content views skip tao and must go through
 * this before Compose.
 */
internal fun appKitWheelToAwtScrollDelta(
    dx: Float,
    dy: Float,
    precise: Boolean,
): Offset {
    val awtSign = Offset(-dx, -dy)
    return if (precise) awtSign / AWT_PIXEL_TO_ROTATION else awtSign
}

/** [gesturePhase] is the [TaoScrollGesturePhase] of a trackpad step, `NONE` for a wheel. */
internal fun appKitWheelToAwtScrollEvent(
    dx: Float,
    dy: Float,
    precise: Boolean,
    gesturePhase: Int = TaoScrollGesturePhase.NONE,
): TaoPointerScrollEvent {
    val delta = appKitWheelToAwtScrollDelta(dx, dy, precise)
    return TaoPointerScrollEvent(
        dxAwt = delta.x,
        dyAwt = delta.y,
        // 1, like TaoWindow / Windows: MacOSCocoaConfig does not read a
        // lines-per-notch multiplier out of scrollAmount the way LinuxGtkConfig
        // does. Do not copy LINUX_AWT_SCROLL_AMOUNT_DEFAULT here.
        scrollAmount = MACOS_AWT_SCROLL_AMOUNT,
        // A wheel notch has no phase; only precise events can belong to a gesture.
        gesturePhase = if (precise) gesturePhase else TaoScrollGesturePhase.NONE,
    )
}
