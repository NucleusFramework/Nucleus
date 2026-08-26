package dev.nucleusframework.darkmodedetector.windows

import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeWindowsThemeBridgeTest {
    @Test
    fun `theme query and callbacks reach registered listeners`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(NativeWindowsBridge.isLoaded, "nucleus_windows_theme must load on Windows")
        NativeWindowsBridge.nativeIsDark()

        val seen = mutableListOf<Boolean>()
        val listener = java.util.function.Consumer<Boolean> { seen += it }
        NativeWindowsBridge.registerListener(listener)
        try {
            NativeWindowsBridge.onThemeChanged(true)
            NativeWindowsBridge.onThemeChanged(false)
            assertEquals(listOf(true, false), seen)
        } finally {
            NativeWindowsBridge.removeListener(listener)
        }
        NativeWindowsBridge.onThemeChanged(true)
        assertEquals(listOf(true, false), seen)
    }
}
