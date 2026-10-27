package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.NativeProxy
import dev.nucleusframework.nativeproxy.ProxyProtocol
import dev.nucleusframework.nativeproxy.ProxyServer
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
 * Opt-in via `NUCLEUS_PROXY_E2E=true` so regular CI is not mutated, and only on
 * a GNOME-family session — elsewhere the provider ignores GSettings by design.
 * Every test restores the keys it touches, whatever the outcome.
 */
class LinuxProxyE2ETest {
    private val isLinux = System.getProperty("os.name", "").lowercase().contains("linux")
    private val e2e = System.getenv("NUCLEUS_PROXY_E2E") == "true"

    @Test
    fun `GSettings mode none is direct even when HTTPS_PROXY is set`() {
        assumeTrue(isLinux && e2e)
        assumeTrue(LinuxProxyBridge.isLoaded)
        assumeTrue(hasProxySchema())
        assumeTrue(LinuxSystemProxyProvider.desktopFamily() == LinuxSystemProxyProvider.DesktopFamily.GNOME)

        withRestoredGsettings {
            runGsettings("set", PROXY_SCHEMA, "mode", "none")
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
        assumeTrue(LinuxSystemProxyProvider.desktopFamily() == LinuxSystemProxyProvider.DesktopFamily.GNOME)

        withRestoredGsettings {
            setManualProxy("proxy.e2e.test", 3128)
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
    fun `GSettings IPv6 host and unset port are parsed`() {
        assumeTrue(isLinux && e2e)
        assumeTrue(LinuxProxyBridge.isLoaded)
        assumeTrue(hasProxySchema())
        assumeTrue(LinuxSystemProxyProvider.desktopFamily() == LinuxSystemProxyProvider.DesktopFamily.GNOME)

        withRestoredGsettings {
            setManualProxy("fd00::1", 3128)
            runGsettings("set", "$PROXY_SCHEMA.https", "port", "0")
            NativeProxy.refresh()
            assertEquals(
                ProxyServer(ProxyProtocol.HTTP, "fd00::1", 3128),
                NativeProxy.proxiesFor(URI("http://example.com")).single(),
            )
            // Port 0 is GSettings' "unset": the scheme default applies, as in Chromium.
            assertEquals(
                ProxyServer(ProxyProtocol.HTTP, "fd00::1", 80),
                NativeProxy.proxiesFor(URI("https://example.com")).single(),
            )
        }
    }

    @Test
    fun `GSettings change is observed by the watcher`() {
        assumeTrue(isLinux && e2e)
        assumeTrue(LinuxProxyBridge.isLoaded)
        assumeTrue(hasProxySchema())
        assumeTrue(LinuxSystemProxyProvider.desktopFamily() == LinuxSystemProxyProvider.DesktopFamily.GNOME)

        val latch = CountDownLatch(1)
        val seen = AtomicReference<SystemProxySettings?>(null)
        val listener: (SystemProxySettings) -> Unit = { settings ->
            seen.set(settings)
            latch.countDown()
        }

        withRestoredGsettings {
            // Start from a known mode so the flip is always a real change.
            runGsettings("set", PROXY_SCHEMA, "mode", "none")
            NativeProxy.refresh()
            NativeProxy.addChangeListener(listener)
            try {
                setManualProxy("watch.e2e.test", 9999)
                val ok = latch.await(10, TimeUnit.SECONDS)
                assertTrue("expected a configuration change within 10s", ok)
                assertTrue(seen.get() != null)
            } finally {
                NativeProxy.removeChangeListener(listener)
            }
        }
    }

    private fun hasProxySchema(): Boolean = LinuxProxyBridge.getProxyConfig() != null

    /** Snapshots every key these tests write and puts it back after [block]. */
    private fun withRestoredGsettings(block: () -> Unit) {
        val snapshot =
            RESTORED_KEYS.map { (schema, key) ->
                Triple(schema, key, runGsettings("get", schema, key).trim())
            }
        try {
            block()
        } finally {
            snapshot.forEach { (schema, key, value) -> runGsettings("set", schema, key, value) }
        }
    }

    private fun setManualProxy(
        host: String,
        port: Int,
    ) {
        runGsettings("set", PROXY_SCHEMA, "mode", "manual")
        for (scheme in listOf("http", "https")) {
            runGsettings("set", "$PROXY_SCHEMA.$scheme", "host", host)
            runGsettings("set", "$PROXY_SCHEMA.$scheme", "port", port.toString())
        }
    }

    private fun runGsettings(vararg args: String): String {
        val proc = ProcessBuilder("gsettings", *args).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "gsettings ${args.toList()} failed: $err" }
        return out
    }

    private companion object {
        const val PROXY_SCHEMA = "org.gnome.system.proxy"

        val RESTORED_KEYS =
            listOf(PROXY_SCHEMA to "mode") +
                listOf("http", "https").flatMap { scheme ->
                    listOf("$PROXY_SCHEMA.$scheme" to "host", "$PROXY_SCHEMA.$scheme" to "port")
                }
    }
}
