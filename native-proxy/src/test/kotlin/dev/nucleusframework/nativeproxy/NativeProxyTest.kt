package dev.nucleusframework.nativeproxy

import dev.nucleusframework.nativeproxy.linux.LinuxProxyBridge
import dev.nucleusframework.nativeproxy.macos.MacOsProxyBridge
import dev.nucleusframework.nativeproxy.windows.WindowsProxyBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy
import java.net.URI

class NativeProxyTest {
    private val os = System.getProperty("os.name", "").lowercase()
    private val isWindows = os.contains("win")
    private val isLinux = os.contains("linux")
    private val isMac = os.contains("mac")

    @Test
    fun `native library loads on Windows`() {
        assumeTrue("Test requires Windows", isWindows)

        assertTrue("Native proxy bridge should be loaded", WindowsProxyBridge.isLoaded)
    }

    @Test
    fun `the Windows configuration is readable`() {
        assumeTrue("Test requires Windows", isWindows)
        assumeTrue("Native library not loaded", WindowsProxyBridge.isLoaded)

        val settings = NativeProxy.settings()
        assertNotNull(settings)
        assertEquals(settings.isDirect, !settings.usesPacScript && settings.rules.isEmpty)
    }

    @Test
    fun `Linux is supported and the configuration is readable`() {
        assumeTrue("Test requires Linux", isLinux)

        assertTrue(NativeProxy.isSupported)
        val settings = NativeProxy.settings()
        assertNotNull(settings)
        assertEquals(settings.isDirect, !settings.usesPacScript && settings.rules.isEmpty)
    }

    @Test
    fun `native library loads on Linux when GIO is present`() {
        assumeTrue("Test requires Linux", isLinux)

        // The library ships with the JAR; load failure is only acceptable when
        // the resource is missing from the test classpath (should not happen).
        assertTrue("Native proxy bridge should be loaded", LinuxProxyBridge.isLoaded)
    }

    @Test
    fun `install returns true on Linux`() {
        assumeTrue("Test requires Linux", isLinux)

        val previous = java.net.ProxySelector.getDefault()
        try {
            assertTrue(NativeProxy.install())
            assertTrue(java.net.ProxySelector.getDefault() is NativeProxySelector)
        } finally {
            NativeProxy.uninstall()
            // Best-effort restore if uninstall did not.
            if (java.net.ProxySelector.getDefault() is NativeProxySelector) {
                java.net.ProxySelector.setDefault(previous)
            }
        }
    }

    @Test
    fun `native library loads on macOS`() {
        assumeTrue("Test requires macOS", isMac)

        assertTrue("Native proxy bridge should be loaded", MacOsProxyBridge.isLoaded)
    }

    @Test
    fun `macOS is supported and the configuration is readable`() {
        assumeTrue("Test requires macOS", isMac)
        assumeTrue("Native library not loaded", MacOsProxyBridge.isLoaded)

        assertTrue(NativeProxy.isSupported)
        val settings = NativeProxy.settings()
        assertNotNull(settings)
        assertEquals(settings.isDirect, !settings.usesPacScript && settings.rules.isEmpty)
    }

    @Test
    fun `install returns true on macOS`() {
        assumeTrue("Test requires macOS", isMac)
        assumeTrue("Native library not loaded", MacOsProxyBridge.isLoaded)

        val previous = java.net.ProxySelector.getDefault()
        try {
            assertTrue(NativeProxy.install())
            assertTrue(java.net.ProxySelector.getDefault() is NativeProxySelector)
        } finally {
            NativeProxy.uninstall()
            if (java.net.ProxySelector.getDefault() is NativeProxySelector) {
                java.net.ProxySelector.setDefault(previous)
            }
        }
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
