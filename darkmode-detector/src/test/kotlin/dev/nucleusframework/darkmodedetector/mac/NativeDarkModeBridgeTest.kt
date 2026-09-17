package dev.nucleusframework.darkmodedetector.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeDarkModeBridgeTest {
    @Test
    fun `theme change callbacks reach registered listeners`() {
        if (!NativeDarkModeBridge.isLoaded) return
        assertTrue(NativeDarkModeBridge.isLoaded)
        val seen = mutableListOf<Boolean>()
        val listener = java.util.function.Consumer<Boolean> { seen += it }
        NativeDarkModeBridge.registerListener(listener)
        try {
            NativeDarkModeBridge.onThemeChanged(true)
            NativeDarkModeBridge.onThemeChanged(false)
            assertEquals(listOf(true, false), seen)
        } finally {
            NativeDarkModeBridge.removeListener(listener)
        }
        NativeDarkModeBridge.onThemeChanged(true)
        assertEquals(listOf(true, false), seen)
    }
}
