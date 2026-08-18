package dev.nucleusframework.systemcolor.mac

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMacSystemColorBridgeTest {
    @Test
    fun `native callbacks deliver accent and contrast updates`() {
        assertTrue(NativeMacSystemColorBridge.isLoaded)
        val accents = mutableListOf<Color?>()
        val contrasts = mutableListOf<Boolean>()
        val accentListener = java.util.function.Consumer<Color?> { accents += it }
        val contrastListener = java.util.function.Consumer<Boolean> { contrasts += it }
        NativeMacSystemColorBridge.registerAccentListener(accentListener)
        NativeMacSystemColorBridge.registerContrastListener(contrastListener)
        try {
            NativeMacSystemColorBridge.onAccentColorChanged(0.1f, 0.2f, 0.3f)
            NativeMacSystemColorBridge.onAccentColorCleared()
            NativeMacSystemColorBridge.onContrastChanged(true)
            NativeMacSystemColorBridge.onContrastChanged(false)
            assertEquals(2, accents.size)
            assertEquals(Color(0.1f, 0.2f, 0.3f), accents[0])
            assertNull(accents[1])
            assertEquals(listOf(true, false), contrasts)
        } finally {
            NativeMacSystemColorBridge.removeAccentListener(accentListener)
            NativeMacSystemColorBridge.removeContrastListener(contrastListener)
        }
        NativeMacSystemColorBridge.onAccentColorCleared()
        NativeMacSystemColorBridge.onContrastChanged(true)
        assertEquals(2, accents.size)
        assertEquals(2, contrasts.size)
    }
}
