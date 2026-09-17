package dev.nucleusframework.autolaunch.windows

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.autolaunch.containsAutostartMarker
import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsAutoLaunchNativeTest {
    @Test
    fun `windows facade uses the win32 backend on an unpackaged host`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(NativeAutoLaunchBridge.isLoaded, "nucleus_autolaunch must load on Windows")

        val state = WindowsAutoLaunch.state()
        assertTrue(
            state != AutoLaunchState.UNSUPPORTED,
            "Win32 registry backend must be supported when the native library is loaded: $state",
        )
        assertTrue(AutoLaunchState.entries.contains(state))

        assertEquals(
            containsAutostartMarker(arrayOf("--nucleus-autostart")),
            WindowsAutoLaunch.wasStartedAtLogin(arrayOf("--nucleus-autostart")),
        )
        assertEquals(
            containsAutostartMarker(emptyArray()),
            WindowsAutoLaunch.wasStartedAtLogin(emptyArray()),
        )

        val diagnostic = AutoLaunch.diagnostic()
        assertTrue(diagnostic.contains("backend: WindowsAutoLaunch"), diagnostic)
        assertTrue(diagnostic.contains("os.name:"), diagnostic)
    }
}
