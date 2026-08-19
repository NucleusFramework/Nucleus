package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTest {
    @Test
    fun `current platform matches the host os family`() {
        val os = System.getProperty("os.name", "")
        when {
            os.contains("mac", ignoreCase = true) || os.contains("darwin", ignoreCase = true) ->
                assertEquals(Platform.MacOS, Platform.Current)
            os.contains("linux", ignoreCase = true) ->
                assertEquals(Platform.Linux, Platform.Current)
            os.contains("win", ignoreCase = true) ->
                assertEquals(Platform.Windows, Platform.Current)
            else -> assertEquals(Platform.Unknown, Platform.Current)
        }
    }

    @Test
    fun `wayland is false on non-linux hosts and follows session env on linux`() {
        if (Platform.Current != Platform.Linux) {
            assertFalse(Platform.isWayland)
            return
        }
        val wayland =
            System.getenv("XDG_SESSION_TYPE") == "wayland" ||
                System.getenv("WAYLAND_DISPLAY") != null
        assertEquals(wayland, Platform.isWayland)
    }

    @Test
    fun `enum contains every supported family`() {
        val names = Platform.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Linux", "Windows", "MacOS", "Unknown")))
    }
}
