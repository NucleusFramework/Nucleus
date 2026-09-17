package dev.nucleusframework.application

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.launcher.windows.WindowsJumpListManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlatformBootstrapTest {
    @Before
    fun resetDoubles() {
        AutoLaunch.reset()
        WindowsJumpListManager.reset()
    }

    @After
    fun cleanupDoubles() {
        AutoLaunch.reset()
        WindowsJumpListManager.reset()
    }

    @Test
    fun `primePlatformIntegrations does not throw when optional modules are present`() {
        val args = arrayOf("--autostart", "myapp://open")
        primePlatformIntegrations(args)
        assertEquals(1, AutoLaunch.calls)
        assertArrayEquals(args, AutoLaunch.lastArgs)
        if (Platform.Current == Platform.Windows) {
            assertEquals(1, WindowsJumpListManager.calls)
            assertEquals(null, WindowsJumpListManager.lastAumid)
        } else {
            assertEquals(0, WindowsJumpListManager.calls)
        }
    }

    @Test
    fun `primePlatformIntegrations is safe with empty args`() {
        primePlatformIntegrations(emptyArray())
        assertEquals(1, AutoLaunch.calls)
        assertArrayEquals(emptyArray<String>(), AutoLaunch.lastArgs)
    }

    @Test
    fun `windows AUMID priming is a no-throw when launcher-windows is present`() {
        invokePrimeWindowsAumid()
        assertEquals(1, WindowsJumpListManager.calls)
        assertEquals(null, WindowsJumpListManager.lastAumid)
    }

    @Test
    fun `repeated priming is idempotent for the reflective targets`() {
        val args = arrayOf("again")
        primePlatformIntegrations(args)
        primePlatformIntegrations(args)
        assertEquals(2, AutoLaunch.calls)
        WindowsJumpListManager.reset()
        invokePrimeWindowsAumid()
        assertEquals(1, WindowsJumpListManager.calls)
        assertTrue(platformBootstrapClass().name.endsWith("PlatformBootstrapKt"))
    }

    private fun invokePrimeWindowsAumid() {
        val method = platformBootstrapClass().getDeclaredMethod("primeWindowsAumid")
        method.isAccessible = true
        method.invoke(null)
    }

    private fun platformBootstrapClass(): Class<*> =
        Class.forName("dev.nucleusframework.application.PlatformBootstrapKt")
}
