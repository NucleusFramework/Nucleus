package dev.nucleusframework.window.tao.event

import kotlin.test.Test
import kotlin.test.assertEquals

class MacOsWheelDeltaTest {
    @Test
    fun scrollUpMatchesTaoWindowAwtSign() {
        // AppKit scrollingDeltaY > 0 is a scroll up. Popup NSPanels report
        // that raw. TaoWindow SCROLL_LINE negates, so AWT/Compose get -1.
        val delta =
            appKitWheelToAwtScrollDelta(dx = 0f, dy = 1f, precise = false, scale = 2f)
        assertEquals(0f, delta.x, absoluteTolerance = 0f)
        assertEquals(-1f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun horizontalDeltaKeepsAppKitX() {
        // tao flips X then TaoWindow negates, net identity vs raw AppKit X.
        val delta =
            appKitWheelToAwtScrollDelta(dx = 1f, dy = 0f, precise = false, scale = 2f)
        assertEquals(1f, delta.x, absoluteTolerance = 0f)
        assertEquals(0f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun precisePixelDeltaMatchesTaoWindowScale() {
        // 10 AppKit points at 2x → physical 20 → AWT preciseWheelRotation -2
        // after TaoWindow SCROLL_PIXEL's negate and /10.
        val delta =
            appKitWheelToAwtScrollDelta(dx = 0f, dy = 10f, precise = true, scale = 2f)
        assertEquals(0f, delta.x, absoluteTolerance = 0f)
        assertEquals(-2f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun precisePixelHorizontalMatchesTaoWindowScale() {
        val delta =
            appKitWheelToAwtScrollDelta(dx = 10f, dy = 0f, precise = true, scale = 2f)
        assertEquals(2f, delta.x, absoluteTolerance = 0f)
        assertEquals(0f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun lineDeltaCarriesMacOsScrollAmount() {
        val event = appKitWheelToAwtScrollEvent(dx = 0f, dy = 1f, precise = false, scale = 2f)
        assertEquals(0f, event.dxAwt, absoluteTolerance = 0f)
        assertEquals(-1f, event.dyAwt, absoluteTolerance = 0f)
        assertEquals(MACOS_AWT_SCROLL_AMOUNT, event.scrollAmount)
    }

    @Test
    fun preciseDeltaCarriesMacOsScrollAmount() {
        val event = appKitWheelToAwtScrollEvent(dx = 0f, dy = 10f, precise = true, scale = 2f)
        assertEquals(0f, event.dxAwt, absoluteTolerance = 0f)
        assertEquals(-2f, event.dyAwt, absoluteTolerance = 0f)
        assertEquals(MACOS_AWT_SCROLL_AMOUNT, event.scrollAmount)
    }
}
