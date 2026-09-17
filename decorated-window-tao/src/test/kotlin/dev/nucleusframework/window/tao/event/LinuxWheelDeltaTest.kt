package dev.nucleusframework.window.tao.event

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxWheelDeltaTest {
    @Test
    fun popupButton5CarriesThreeLinesPerNotch() {
        // X11 Button5 (wheel down) is already Compose-signed (+1). DecoratedWindow
        // Linux reports scrollAmount=3 on SCROLL_LINE; the standalone panel
        // must match or Compose's LinuxGtkConfig under-scrolls 3×.
        val event = linuxWheelToAwtScrollEvent(dx = 0f, dy = 1f)
        assertEquals(0f, event.dxAwt, absoluteTolerance = 0f)
        assertEquals(1f, event.dyAwt, absoluteTolerance = 0f)
        assertEquals(LINUX_AWT_SCROLL_AMOUNT_DEFAULT, event.scrollAmount)
    }
}
