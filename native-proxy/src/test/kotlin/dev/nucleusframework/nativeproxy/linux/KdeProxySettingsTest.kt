package dev.nucleusframework.nativeproxy.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KdeProxySettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `manual proxy with space-separated port is parsed`() {
        val home = tmp.newFolder("home")
        val config = File(home, ".config").apply { mkdirs() }
        File(config, "kioslaverc").writeText(
            """
            [Proxy Settings]
            ProxyType=1
            httpProxy=proxy.corp 8080
            httpsProxy=proxy.corp 8443
            NoProxyFor=localhost,127.0.0.1
            """.trimIndent(),
        )

        val settings =
            KdeProxySettings.read(
                mapOf("HOME" to home.absolutePath, "XDG_CONFIG_DIRS" to "")::get,
            )!!

        assertEquals(
            "proxy.corp",
            settings.rules.proxiesForHttp
                .single()
                .host,
        )
        assertEquals(
            8080,
            settings.rules.proxiesForHttp
                .single()
                .port,
        )
        assertEquals(
            8443,
            settings.rules.proxiesForHttps
                .single()
                .port,
        )
        assertTrue(settings.bypassRules.matches(java.net.URI("http://localhost")))
    }

    @Test
    fun `ProxyType 0 is direct`() {
        val home = tmp.newFolder("home-direct")
        val config = File(home, ".config").apply { mkdirs() }
        File(config, "kioslaverc").writeText(
            """
            [Proxy Settings]
            ProxyType=0
            httpProxy=proxy.corp 8080
            """.trimIndent(),
        )

        val settings =
            KdeProxySettings.read(
                mapOf("HOME" to home.absolutePath, "XDG_CONFIG_DIRS" to "")::get,
            )!!
        assertTrue(settings.isDirect)
    }

    @Test
    fun `ProxyType 3 is WPAD and ProxyType 2 is PAC`() {
        val home = tmp.newFolder("home-auto")
        val config = File(home, ".config").apply { mkdirs() }
        val file = File(config, "kioslaverc")
        val env = mapOf("HOME" to home.absolutePath, "XDG_CONFIG_DIRS" to "")

        file.writeText(
            """
            [Proxy Settings]
            ProxyType=3
            """.trimIndent(),
        )
        assertTrue(KdeProxySettings.read(env::get)!!.autoDetect)

        file.writeText(
            """
            [Proxy Settings]
            ProxyType=2
            Proxy Config Script=http://wpad/proxy.pac
            """.trimIndent(),
        )
        assertEquals("http://wpad/proxy.pac", KdeProxySettings.read(env::get)!!.pacUrl)
    }

    @Test
    fun `socks-only manual config becomes a single proxy list`() {
        val home = tmp.newFolder("home-socks")
        val config = File(home, ".config").apply { mkdirs() }
        File(config, "kioslaverc").writeText(
            """
            [Proxy Settings]
            ProxyType=1
            socksProxy=socks.corp 1080
            """.trimIndent(),
        )

        val settings =
            KdeProxySettings.read(
                mapOf("HOME" to home.absolutePath, "XDG_CONFIG_DIRS" to "")::get,
            )!!
        val server = settings.rules.singleProxies.single()
        assertEquals("socks.corp", server.host)
        assertEquals(dev.nucleusframework.nativeproxy.ProxyProtocol.SOCKS5, server.protocol)
    }
}
