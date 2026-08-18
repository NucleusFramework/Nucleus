package dev.nucleusframework.window

import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.utils.linux.LinuxButtonLayoutObserver
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLayoutDirectionBridgeTest {
    @Test
    fun `native layout direction is a concrete ltr or rtl value`() {
        val direction = nativeSystemLayoutDirection()
        assertTrue(direction == LayoutDirection.Ltr || direction == LayoutDirection.Rtl)
        assertEquals(direction, nativeSystemLayoutDirection())
    }

    @Test
    fun `linux native layout queries are safe to call`() {
        if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return
        val rtl = runCatching { NativeLayoutDirectionBridge.nativeIsRTL() }.getOrNull()
        if (rtl != null) {
            assertEquals(rtl, NativeLayoutDirectionBridge.nativeIsRTL())
        }
        runCatching { NativeLayoutDirectionBridge.nativeGetButtonLayout() }
        LinuxButtonLayoutObserver.registerListener { }
        LinuxButtonLayoutObserver.removeListener { }
    }

    @Test
    fun `button layout listeners are notified and can be removed`() {
        val seen = AtomicReference<String?>(null)
        val listener = java.util.function.Consumer<String> { seen.set(it) }
        NativeLayoutDirectionBridge.registerButtonLayoutListener(listener)
        NativeLayoutDirectionBridge.onButtonLayoutChanged("appmenu:close")
        assertEquals("appmenu:close", seen.get())
        NativeLayoutDirectionBridge.removeButtonLayoutListener(listener)
        NativeLayoutDirectionBridge.onButtonLayoutChanged("close:")
        assertEquals("appmenu:close", seen.get())
    }
}
