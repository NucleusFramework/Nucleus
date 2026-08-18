package dev.nucleusframework.systemcolor.linux

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLinuxSystemColorBridgeTest {
    @Test
    fun `native callbacks deliver accent and contrast updates`() {
        if (!NativeLinuxSystemColorBridge.isLoaded) return
        val accents = mutableListOf<Color>()
        val contrasts = mutableListOf<Boolean>()
        val accentListener = java.util.function.Consumer<Color> { accents += it }
        val contrastListener = java.util.function.Consumer<Boolean> { contrasts += it }
        NativeLinuxSystemColorBridge.registerAccentListener(accentListener)
        NativeLinuxSystemColorBridge.registerContrastListener(contrastListener)
        try {
            NativeLinuxSystemColorBridge.onAccentColorChanged(0.1f, 0.2f, 0.3f)
            NativeLinuxSystemColorBridge.onHighContrastChanged(true)
            NativeLinuxSystemColorBridge.onHighContrastChanged(false)
            assertEquals(1, accents.size)
            assertEquals(Color(0.1f, 0.2f, 0.3f), accents[0])
            assertEquals(listOf(true, false), contrasts)
            LinuxSystemColorDetector.isAccentColorSupported()
            LinuxSystemColorDetector.getAccentColor()
            LinuxSystemColorDetector.isHighContrast()
        } finally {
            NativeLinuxSystemColorBridge.removeAccentListener(accentListener)
            NativeLinuxSystemColorBridge.removeContrastListener(contrastListener)
        }
        NativeLinuxSystemColorBridge.onAccentColorChanged(1f, 1f, 1f)
        NativeLinuxSystemColorBridge.onHighContrastChanged(true)
        assertEquals(1, accents.size)
        assertEquals(2, contrasts.size)
    }

    @Test
    fun `accent color query is safe when the portal is absent`() {
        if (!NativeLinuxSystemColorBridge.isLoaded) return
        val supported = LinuxSystemColorDetector.isAccentColorSupported()
        val color = LinuxSystemColorDetector.getAccentColor()
        if (supported && color != null) {
            assertTrue(color.red in 0f..1f)
            assertTrue(color.green in 0f..1f)
            assertTrue(color.blue in 0f..1f)
        }
        val contrast = LinuxSystemColorDetector.isHighContrast()
        assertEquals(contrast, LinuxSystemColorDetector.isHighContrast())
    }
}
