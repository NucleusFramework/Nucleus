package dev.nucleusframework.darkmodedetector.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLinuxThemeBridgeTest {
    @Test
    fun `theme change callbacks reach registered listeners`() {
        if (!NativeLinuxBridge.isLoaded) return
        val seen = mutableListOf<Boolean>()
        val listener = java.util.function.Consumer<Boolean> { seen += it }
        NativeLinuxBridge.registerListener(listener)
        try {
            NativeLinuxBridge.onThemeChanged(true)
            NativeLinuxBridge.onThemeChanged(false)
            assertEquals(listOf(true, false), seen)
            val nativeDark = NativeLinuxBridge.nativeIsDark()
            assertEquals(nativeDark, NativeLinuxBridge.nativeIsDark())
        } finally {
            NativeLinuxBridge.removeListener(listener)
        }
        NativeLinuxBridge.onThemeChanged(true)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `portal detector reports a stable boolean when native is loaded`() {
        if (!NativeLinuxBridge.isLoaded) return
        val dark = LinuxPortalThemeDetector.isDark()
        assertTrue(dark == LinuxPortalThemeDetector.isDark())
    }
}
