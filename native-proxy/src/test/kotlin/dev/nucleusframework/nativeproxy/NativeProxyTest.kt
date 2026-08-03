package dev.nucleusframework.nativeproxy

import dev.nucleusframework.nativeproxy.windows.WindowsProxyBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy
import java.net.URI

class NativeProxyTest {
    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    @Test
    fun `unsupported platforms report a direct configuration`() {
        assumeTrue("Test requires a non-Windows host", !isWindows)

        assertFalse(NativeProxy.isSupported)
        assertEquals(SystemProxySettings.DIRECT, NativeProxy.settings())
        assertTrue(NativeProxy.proxiesFor(URI("https://example.com")).isEmpty())
        assertFalse(NativeProxy.install())
    }

    @Test
    fun `native library loads on Windows`() {
        assumeTrue("Test requires Windows", isWindows)

        assertTrue("Native proxy bridge should be loaded", WindowsProxyBridge.isLoaded)
    }

    @Test
    fun `the Windows configuration is readable`() {
        assumeTrue("Test requires Windows", isWindows)
        assumeTrue("Native library not loaded", WindowsProxyBridge.isLoaded)

        // Any outcome is valid — the CI machine may or may not have a proxy —
        // but reading must never throw and must be internally consistent.
        val settings = NativeProxy.settings()
        assertNotNull(settings)
        assertEquals(settings.isDirect, !settings.usesPacScript && settings.rules.isEmpty)
    }

    @Test
    fun `loopback is never proxied`() {
        assertTrue(NativeProxy.proxiesFor(URI("http://127.0.0.1:8080")).isEmpty())
    }

    @Test
    fun `java proxies always contain at least a direct entry`() {
        val proxies = NativeProxy.javaProxiesFor(URI("http://localhost"))

        assertEquals(listOf(Proxy.NO_PROXY), proxies)
    }
}
