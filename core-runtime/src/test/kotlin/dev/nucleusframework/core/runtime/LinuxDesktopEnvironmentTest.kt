package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxDesktopEnvironmentTest {
    @Test
    fun `current desktop is Unknown on non-linux hosts`() {
        if (Platform.Current == Platform.Linux) {
            assertTrue(LinuxDesktopEnvironment.entries.contains(LinuxDesktopEnvironment.Current))
            return
        }
        assertEquals(LinuxDesktopEnvironment.Unknown, LinuxDesktopEnvironment.Current)
    }

    @Test
    fun `linux ui toolkit current is one of the two families`() {
        assertTrue(
            LinuxUiToolkit.Current == LinuxUiToolkit.Gtk ||
                LinuxUiToolkit.Current == LinuxUiToolkit.Qt,
        )
    }
}
