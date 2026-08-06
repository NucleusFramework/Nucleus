package dev.nucleusframework.nativeproxy.macos

import dev.nucleusframework.nativeproxy.NativeProxy
import dev.nucleusframework.nativeproxy.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

/**
 * Smoke against the live System Preferences on this machine.
 * Expects the proxy that `scutil --proxy` reports on the developer's Mac.
 * Skipped when no HTTP proxy is configured (clean CI / clean machines).
 */
class MacOsProxySmokeTest {
    private val isMac = System.getProperty("os.name", "").lowercase().contains("mac")

    @Test
    fun `matches scutil when an HTTP proxy is configured`() {
        assumeTrue(isMac)
        assumeTrue(MacOsProxyBridge.isLoaded)

        val scutil = ProcessBuilder("scutil", "--proxy").start()
        val text = scutil.inputStream.bufferedReader().readText()
        check(scutil.waitFor() == 0)

        val httpEnabled = text.contains("HTTPEnable : 1")
        assumeTrue("No HTTP proxy configured on this Mac", httpEnabled)

        val host = Regex("HTTPProxy : (\\S+)").find(text)?.groupValues?.get(1)
        val port = Regex("HTTPPort : (\\d+)").find(text)?.groupValues?.get(1)?.toInt()
        check(host != null && port != null)

        val settings = NativeProxy.refresh()
        assertFalse(settings.isDirect)

        val proxies = NativeProxy.proxiesFor(URI("http://example.com"))
        assertEquals(1, proxies.size)
        assertEquals(host, proxies[0].host)
        assertEquals(port, proxies[0].port)
        assertEquals(ProxyProtocol.HTTP, proxies[0].protocol)

        // Implicit loopback bypass
        assertTrue(NativeProxy.proxiesFor(URI("http://127.0.0.1")).isEmpty())
        assertTrue(NativeProxy.proxiesFor(URI("http://localhost")).isEmpty())
    }
}
