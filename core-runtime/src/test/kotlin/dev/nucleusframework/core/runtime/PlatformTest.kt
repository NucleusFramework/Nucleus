package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTest {
    @Test
    fun `current platform is MacOS on this host`() {
        val os = System.getProperty("os.name", "")
        if (!os.contains("mac", ignoreCase = true) && !os.contains("darwin", ignoreCase = true)) {
            return
        }
        assertEquals(Platform.MacOS, Platform.Current)
    }

    @Test
    fun `wayland is false on non-linux hosts`() {
        if (Platform.Current != Platform.Linux) {
            assertFalse(Platform.isWayland)
        }
    }

    @Test
    fun `enum contains every supported family`() {
        val names = Platform.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("Linux", "Windows", "MacOS", "Unknown")))
    }
}
