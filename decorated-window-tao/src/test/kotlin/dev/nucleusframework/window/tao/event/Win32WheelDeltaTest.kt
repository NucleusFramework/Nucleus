package dev.nucleusframework.window.tao.event

import kotlin.test.Test
import kotlin.test.assertEquals

class Win32WheelDeltaTest {
    @Test
    fun wheelTowardsUserMatchesTaoWindowAwtSign() {
        // WM_MOUSEWHEEL towards the user is a negative GET_WHEEL_DELTA_WPARAM.
        // Popup/overlay WndProcs report that as dy = -1 (one notch).
        // TaoWindow negates SCROLL_LINE so AWT/Compose get +1 (towards the user).
        val delta = win32WheelToAwtScrollDelta(dx = 0f, dy = -1f)
        assertEquals(0f, delta.x, absoluteTolerance = 0f)
        assertEquals(1f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun horizontalWheelIsNegatedToAwtSign() {
        val delta = win32WheelToAwtScrollDelta(dx = 1f, dy = 0f)
        assertEquals(-1f, delta.x, absoluteTolerance = 0f)
        assertEquals(0f, delta.y, absoluteTolerance = 0f)
    }

    @Test
    fun popupWndProcNotchCarriesWindowScrollAmount() {
        val event = win32WheelToAwtScrollEvent(dx = 0f, dy = -1f)
        assertEquals(0f, event.dxAwt, absoluteTolerance = 0f)
        assertEquals(1f, event.dyAwt, absoluteTolerance = 0f)
        assertEquals(1, event.scrollAmount)
    }
}
