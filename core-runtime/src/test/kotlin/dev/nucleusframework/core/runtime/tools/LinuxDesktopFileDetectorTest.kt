package dev.nucleusframework.core.runtime.tools

import dev.nucleusframework.core.runtime.Platform
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxDesktopFileDetectorTest {
    @Test
    fun `desktop filename is null or a desktop file id`() {
        val name = LinuxDesktopFileDetector.desktopFilename
        assertTrue(name == null || name.endsWith(".desktop"))
        if (Platform.Current == Platform.Linux && name != null) {
            assertTrue(name.length > ".desktop".length)
        }
    }
}
