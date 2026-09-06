package dev.nucleusframework.window.tao.event

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Popup NSPanel scroll conversion (#652 / #653): raw AppKit `scrollingDelta*`
 * → AWT `preciseWheelRotation`, i.e. OpenJDK's `-[event deltaX/Y]` with the
 * precise legacy delta being `scrollingDelta × 0.1` — both axes flip and the
 * display scale never enters.
 */
class MacOsWheelDeltaTest {
    @Test
    fun scrollUpMatchesTaoWindowAwtSign() {
        // AppKit scrollingDeltaY > 0 is a scroll up (content moves down).
        // Popup NSPanels report that raw; AWT / Compose get -1.
        val delta = appKitWheelToAwtScrollDelta(dx = 0f, dy = 1f, precise = false)
        assertEquals(0f, delta.x, absoluteTolerance = 0f)
        assertEquals(-1f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun horizontalDeltaFlipsLikeVertical() {
        // #652: AppKit scrollingDeltaX > 0 is content moving right, i.e. a
        // scroll *left*; AWT reports that as -1 — same as TaoWindow now that
        // tao no longer pre-flips X.
        val delta = appKitWheelToAwtScrollDelta(dx = 1f, dy = 0f, precise = false)
        assertEquals(-1f, delta.x, absoluteTolerance = 0f)
        assertEquals(0f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun precisePixelDeltaIgnoresDisplayScale() {
        // #653: 10 AppKit points → AWT preciseWheelRotation -1, on any display.
        val delta = appKitWheelToAwtScrollDelta(dx = 0f, dy = 10f, precise = true)
        assertEquals(0f, delta.x, absoluteTolerance = 0f)
        assertEquals(-1f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun precisePixelHorizontalFlipsAndDividesByTen() {
        val delta = appKitWheelToAwtScrollDelta(dx = 10f, dy = 0f, precise = true)
        assertEquals(-1f, delta.x, absoluteTolerance = 0f)
        assertEquals(0f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun lineDeltaCarriesMacOsScrollAmount() {
        val event = appKitWheelToAwtScrollEvent(dx = 0f, dy = 1f, precise = false)
        assertEquals(0f, event.dxAwt, absoluteTolerance = 0f)
        assertEquals(-1f, event.dyAwt, absoluteTolerance = 0f)
        assertEquals(MACOS_AWT_SCROLL_AMOUNT, event.scrollAmount)
    }

    @Test
    fun preciseDeltaCarriesMacOsScrollAmount() {
        val event = appKitWheelToAwtScrollEvent(dx = 0f, dy = 10f, precise = true)
        assertEquals(0f, event.dxAwt, absoluteTolerance = 0f)
        assertEquals(-1f, event.dyAwt, absoluteTolerance = 0f)
        assertEquals(MACOS_AWT_SCROLL_AMOUNT, event.scrollAmount)
    }
}
