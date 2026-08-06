package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.NativeProxy
import dev.nucleusframework.nativeproxy.ProxyProtocol
import dev.nucleusframework.nativeproxy.SystemProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end checks against the live GSettings on this machine.
 * Opt-in via `NUCLEUS_PROXY_E2E=true` so regular CI is not mutated.
 */
class LinuxProxyE2ETest {
    private val isLinux = System.getProperty("os.name", "").lowercase().contains("linux")
    private val e2e = System.getenv("NUCLEUS_PROXY_E2E") == "true"

    @Test
    fun `GSettings mode none is direct even when HTTPS_PROXY is set`() {
        assumeTrue(isLinux && e2e)
        assumeTrue(LinuxProxyBridge.isLoaded)
        assumeTrue(hasProxySchema())

        withGsettingsMode("none") {
            // HTTPS_PROXY is typically set in developer environments; mode=none
            // must still win, matching Chromium.
            val settings = NativeProxy.refresh()
            assertTrue("mode=none must be direct (must not fall through to env)", settings.isDirect)
            assertTrue(NativeProxy.proxiesFor(URI("https://example.com")).isEmpty())
        }
    }

    @Test
    fun `GSettings manual mode is applied`() {
        assumeTrue(isLinux && e2e)
        assumeTrue(LinuxProxyBridge.isLoaded)
        assumeTrue(hasProxySchema())

        withManualProxy("proxy.e2e.test", 3128) {
            val settings = NativeProxy.refresh()
            assertFalse(settings.isDirect)
            val proxies = NativeProxy.proxiesFor(URI("https://example.com"))
            assertEquals(1, proxies.size)
            assertEquals("proxy.e2e.test", proxies[0].host)
            assertEquals(3128, proxies[0].port)
            assertEquals(ProxyProtocol.HTTP, proxies[0].protocol)
            assertTrue(NativeProxy.proxiesFor(URI("http://localhost")).isEmpty())
        }
    }

    @Test
    fun `GSettings change is observed by the watcher`() {
        assumeTrue(isLinux && e2e)
        assumeTrue(LinuxProxyBridge.isLoaded)
        assumeTrue(hasProxySchema())

        val latch = CountDownLatch(1)
        val seen = AtomicReference<SystemProxySettings?>(null)
        val listener: (SystemProxySettings) -> Unit = { settings ->
            seen.set(settings)
            latch.countDown()
        }

        // Start from a known mode so the flip is always a real change.
        runGsettings("set", "org.gnome.system.proxy", "mode", "none")
        NativeProxy.refresh()
        NativeProxy.addChangeListener(listener)
        try {
            runGsettings("set", "org.gnome.system.proxy", "mode", "manual")
            runGsettings("set", "org.gnome.system.proxy.http", "host", "watch.e2e.test")
            runGsettings("set", "org.gnome.system.proxy.http", "port", "9999")
            val ok = latch.await(10, TimeUnit.SECONDS)
            assertTrue("expected a configuration change within 10s", ok)
            assertTrue(seen.get() != null)
        } finally {
            NativeProxy.removeChangeListener(listener)
            runGsettings("set", "org.gnome.system.proxy", "mode", "none")
        }
    }

    private fun hasProxySchema(): Boolean = LinuxProxyBridge.getProxyConfig() != null

    private fun withGsettingsMode(
        mode: String,
        block: () -> Unit,
    ) {
        val previous = runGsettings("get", "org.gnome.system.proxy", "mode").trim().trim('\'')
        runGsettings("set", "org.gnome.system.proxy", "mode", mode)
        try {
            block()
        } finally {
            runGsettings("set", "org.gnome.system.proxy", "mode", previous)
        }
    }

    private fun withManualProxy(
        host: String,
        port: Int,
        block: () -> Unit,
    ) {
        val prevMode = runGsettings("get", "org.gnome.system.proxy", "mode").trim().trim('\'')
        val prevHost = runGsettings("get", "org.gnome.system.proxy.http", "host").trim().trim('\'')
        val prevPort = runGsettings("get", "org.gnome.system.proxy.http", "port").trim()
        val prevHttpsHost = runGsettings("get", "org.gnome.system.proxy.https", "host").trim().trim('\'')
        val prevHttpsPort = runGsettings("get", "org.gnome.system.proxy.https", "port").trim()
        runGsettings("set", "org.gnome.system.proxy", "mode", "manual")
        runGsettings("set", "org.gnome.system.proxy.http", "host", host)
        runGsettings("set", "org.gnome.system.proxy.http", "port", port.toString())
        runGsettings("set", "org.gnome.system.proxy.https", "host", host)
        runGsettings("set", "org.gnome.system.proxy.https", "port", port.toString())
        try {
            block()
        } finally {
            runGsettings("set", "org.gnome.system.proxy", "mode", prevMode)
            runGsettings("set", "org.gnome.system.proxy.http", "host", prevHost)
            runGsettings("set", "org.gnome.system.proxy.http", "port", prevPort)
            runGsettings("set", "org.gnome.system.proxy.https", "host", prevHttpsHost)
            runGsettings("set", "org.gnome.system.proxy.https", "port", prevHttpsPort)
        }
    }

    private fun runGsettings(vararg args: String): String {
        val proc = ProcessBuilder("gsettings", *args).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "gsettings ${args.toList()} failed: $err" }
        return out
    }
}
