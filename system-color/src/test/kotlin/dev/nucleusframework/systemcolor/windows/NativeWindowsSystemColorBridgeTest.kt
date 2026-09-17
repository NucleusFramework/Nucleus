package dev.nucleusframework.systemcolor.windows

import androidx.compose.ui.graphics.Color
import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeWindowsSystemColorBridgeTest {
    private fun windowsNativeReady(): Boolean =
        Platform.Current == Platform.Windows && NativeWindowsSystemColorBridge.isLoaded

    @Test
    fun `native callbacks deliver accent and contrast updates`() {
        if (!windowsNativeReady()) return
        val accents = mutableListOf<Color>()
        val contrasts = mutableListOf<Boolean>()
        val accentListener = java.util.function.Consumer<Color> { accents += it }
        val contrastListener = java.util.function.Consumer<Boolean> { contrasts += it }
        NativeWindowsSystemColorBridge.registerAccentListener(accentListener)
        NativeWindowsSystemColorBridge.registerContrastListener(contrastListener)
        try {
            NativeWindowsSystemColorBridge.onAccentColorChanged(25, 51, 76)
            NativeWindowsSystemColorBridge.onHighContrastChanged(true)
            NativeWindowsSystemColorBridge.onHighContrastChanged(false)
            assertEquals(1, accents.size)
            assertEquals(Color(25 / 255f, 51 / 255f, 76 / 255f), accents[0])
            assertEquals(listOf(true, false), contrasts)
        } finally {
            NativeWindowsSystemColorBridge.removeAccentListener(accentListener)
            NativeWindowsSystemColorBridge.removeContrastListener(contrastListener)
        }
        NativeWindowsSystemColorBridge.onAccentColorChanged(255, 255, 255)
        NativeWindowsSystemColorBridge.onHighContrastChanged(true)
        assertEquals(1, accents.size)
        assertEquals(2, contrasts.size)
    }

    @Test
    fun `accent color query is supported on Windows`() {
        if (!windowsNativeReady()) return
        assertTrue(NativeWindowsSystemColorBridge.nativeIsAccentColorSupported())
        val color = NativeWindowsSystemColorBridge.getAccentColor()
        if (color != null) {
            assertTrue(color.red in 0f..1f)
            assertTrue(color.green in 0f..1f)
            assertTrue(color.blue in 0f..1f)
        }
        val contrast = NativeWindowsSystemColorBridge.nativeIsHighContrast()
        assertEquals(contrast, NativeWindowsSystemColorBridge.nativeIsHighContrast())
    }
}
