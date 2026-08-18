package dev.nucleusframework.systemcolor

import androidx.compose.ui.graphics.Color
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systemcolor.mac.MacSystemColorDetector
import dev.nucleusframework.systemcolor.windows.WindowsSystemColorDetector
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemColorTest {
    @Test
    fun `accent color support is a boolean and does not throw`() {
        val supported = isSystemAccentColorSupported()
        if (Platform.Current == Platform.MacOS || Platform.Current == Platform.Windows) {
            assertTrue(supported, "${Platform.Current} should expose an accent color")
        } else if (Platform.Current == Platform.Unknown) {
            assertTrue(!supported)
        } else {
            // Linux support depends on the portal / settings backend being present
            assertEquals(supported, isSystemAccentColorSupported())
        }
    }

    @Test
    fun `mac detector returns a Color or null and a high-contrast boolean`() {
        if (Platform.Current != Platform.MacOS) return

        val color: Color? = MacSystemColorDetector.getAccentColor()
        if (color != null) {
            assertTrue(color.red in 0f..1f)
            assertTrue(color.green in 0f..1f)
            assertTrue(color.blue in 0f..1f)
        }
        val highContrast = MacSystemColorDetector.isHighContrast()
        assertEquals(highContrast, MacSystemColorDetector.isHighContrast())
        assertTrue(MacSystemColorDetector.isAccentColorSupported())
    }

    @Test
    fun `windows detector returns a Color or null and a high-contrast boolean`() {
        if (Platform.Current != Platform.Windows) return

        val color: Color? = WindowsSystemColorDetector.getAccentColor()
        if (color != null) {
            assertTrue(color.red in 0f..1f)
            assertTrue(color.green in 0f..1f)
            assertTrue(color.blue in 0f..1f)
        }
        val highContrast = WindowsSystemColorDetector.isHighContrast()
        assertEquals(highContrast, WindowsSystemColorDetector.isHighContrast())
        assertTrue(WindowsSystemColorDetector.isAccentColorSupported())
    }

    @Test
    fun `accent and contrast listeners can be registered and removed`() {
        if (Platform.Current != Platform.MacOS && Platform.Current != Platform.Windows) return

        var colorSeen: Color? = Color.Transparent
        var contrastSeen: Boolean? = null
        if (Platform.Current == Platform.MacOS) {
            val accentListener = Consumer<Color?> { colorSeen = it }
            val contrastListener = Consumer<Boolean> { contrastSeen = it }
            MacSystemColorDetector.registerAccentListener(accentListener)
            MacSystemColorDetector.removeAccentListener(accentListener)
            MacSystemColorDetector.registerContrastListener(contrastListener)
            MacSystemColorDetector.removeContrastListener(contrastListener)
        } else {
            val accentListener = Consumer<Color> { colorSeen = it }
            val contrastListener = Consumer<Boolean> { contrastSeen = it }
            WindowsSystemColorDetector.registerAccentListener(accentListener)
            WindowsSystemColorDetector.removeAccentListener(accentListener)
            WindowsSystemColorDetector.registerContrastListener(contrastListener)
            WindowsSystemColorDetector.removeContrastListener(contrastListener)
        }
        assertEquals(Color.Transparent, colorSeen)
        assertEquals(null, contrastSeen)
    }
}
