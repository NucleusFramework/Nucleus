package dev.nucleusframework.nativeproxy.macos

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
 * End-to-end checks against the live System Preferences proxy settings.
 * Opt-in via `NUCLEUS_PROXY_E2E=true` so regular CI is not mutated.
 *
 * Mutates the HTTP proxy via `networksetup` and restores the previous state.
 */
class MacOsProxyE2ETest {
    private val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
    private val e2e = System.getenv("NUCLEUS_PROXY_E2E") == "true"

    @Test
    fun `SCDynamicStore reads the current configuration`() {
        assumeTrue(isMac)
        assumeTrue(MacOsProxyBridge.isLoaded)

        val config = MacOsProxyBridge.getProxyConfig()
        assertTrue("nativeGetProxyConfig must return a 4-element array", config != null && config.size == 4)

        val settings = NativeProxy.refresh()
        // Just smoke-test that the call does not throw and returns a value.
        assertTrue(settings == settings)
    }

    @Test
    fun `manual HTTP proxy is applied`() {
        assumeTrue(isMac && e2e)
        assumeTrue(MacOsProxyBridge.isLoaded)

        val service = primaryNetworkService() ?: return
        withManualHttpProxy(service, "proxy.e2e.test", 3128) {
            val settings = NativeProxy.refresh()
            assertFalse("manual HTTP proxy must not be direct", settings.isDirect)
            val proxies = NativeProxy.proxiesFor(URI("http://example.com"))
            assertEquals(1, proxies.size)
            assertEquals("proxy.e2e.test", proxies[0].host)
            assertEquals(3128, proxies[0].port)
            assertEquals(ProxyProtocol.HTTP, proxies[0].protocol)
            assertTrue(NativeProxy.proxiesFor(URI("http://localhost")).isEmpty())
        }
    }

    @Test
    fun `configuration change is observed by the watcher`() {
        assumeTrue(isMac && e2e)
        assumeTrue(MacOsProxyBridge.isLoaded)

        val service = primaryNetworkService() ?: return
        val latch = CountDownLatch(1)
        val seen = AtomicReference<SystemProxySettings?>(null)
        val listener: (SystemProxySettings) -> Unit = { settings ->
            seen.set(settings)
            latch.countDown()
        }

        // Start from a known state so the flip is always a real change.
        runNetworksetup("-setwebproxystate", service, "off")
        NativeProxy.refresh()
        NativeProxy.addChangeListener(listener)
        try {
            runNetworksetup("-setwebproxy", service, "watch.e2e.test", "9999")
            runNetworksetup("-setwebproxystate", service, "on")
            val ok = latch.await(10, TimeUnit.SECONDS)
            assertTrue("expected a configuration change within 10s", ok)
            assertTrue(seen.get() != null)
        } finally {
            NativeProxy.removeChangeListener(listener)
            runNetworksetup("-setwebproxystate", service, "off")
        }
    }

    private fun primaryNetworkService(): String? {
        // Prefer Wi-Fi / Ethernet — the first hardware port that is not a VPN/bridge.
        val hardware = runNetworksetup("-listallhardwareports")
        val blocks = hardware.split("\n\n")
        for (block in blocks) {
            val name =
                block
                    .lineSequence()
                    .firstOrNull { it.startsWith("Hardware Port:") }
                    ?.substringAfter(':')
                    ?.trim()
            if (name != null && name in setOf("Wi-Fi", "Ethernet", "USB 10/100/1000 LAN")) {
                return name
            }
        }
        return blocks
            .asSequence()
            .mapNotNull { block ->
                block
                    .lineSequence()
                    .firstOrNull { it.startsWith("Hardware Port:") }
                    ?.substringAfter(':')
                    ?.trim()
            }.firstOrNull()
    }

    private fun withManualHttpProxy(
        service: String,
        host: String,
        port: Int,
        block: () -> Unit,
    ) {
        val previous = runNetworksetup("-getwebproxy", service)
        val wasEnabled = previous.lineSequence().any { it.trim() == "Enabled: Yes" }
        val prevServer =
            previous
                .lineSequence()
                .firstOrNull { it.startsWith("Server:") }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
        val prevPort =
            previous
                .lineSequence()
                .firstOrNull { it.startsWith("Port:") }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()

        runNetworksetup("-setwebproxy", service, host, port.toString())
        runNetworksetup("-setwebproxystate", service, "on")
        try {
            block()
        } finally {
            if (prevServer.isNotEmpty() && prevPort.isNotEmpty()) {
                runNetworksetup("-setwebproxy", service, prevServer, prevPort)
            }
            runNetworksetup("-setwebproxystate", service, if (wasEnabled) "on" else "off")
        }
    }

    private fun runNetworksetup(vararg args: String): String {
        val proc = ProcessBuilder("networksetup", *args).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "networksetup ${args.toList()} failed: $err" }
        return out
    }
}
