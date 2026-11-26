package dev.nucleusframework.nativeproxy.linux

import dev.nucleusframework.nativeproxy.ProxyProtocol
import dev.nucleusframework.nativeproxy.ProxyServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class EnvProxySettingsTest {
    @Test
    fun `empty environment yields null`() {
        assertNull(EnvProxySettings.read { null })
    }

    @Test
    fun `all_proxy sets a single proxy for every scheme`() {
        val env = mapOf("all_proxy" to "http://proxy.corp:8080")
        val settings = EnvProxySettings.read(env::get)!!

        assertEquals(
            listOf(ProxyServer(ProxyProtocol.HTTP, "proxy.corp", 8080)),
            settings.rules.proxiesForUrlScheme("https"),
        )
    }

    @Test
    fun `per-scheme env vars are mapped independently`() {
        val env =
            mapOf(
                "http_proxy" to "http://http.proxy:80",
                "https_proxy" to "http://https.proxy:443",
            )
        val settings = EnvProxySettings.read(env::get)!!

        assertEquals(
            "http.proxy",
            settings.rules
                .proxiesForUrlScheme("http")
                .single()
                .host,
        )
        assertEquals(
            "https.proxy",
            settings.rules
                .proxiesForUrlScheme("https")
                .single()
                .host,
        )
        assertTrue(settings.rules.proxiesForUrlScheme("ftp").isEmpty())
    }

    @Test
    fun `SOCKS_SERVER defaults to socks5 unless SOCKS_VERSION is 4`() {
        val socks5 =
            EnvProxySettings.read(
                mapOf("SOCKS_SERVER" to "socks.corp:1080")::get,
            )!!
        assertEquals(
            ProxyProtocol.SOCKS5,
            socks5.rules.singleProxies
                .single()
                .protocol,
        )

        val socks4 =
            EnvProxySettings.read(
                mapOf("SOCKS_SERVER" to "socks.corp:1080", "SOCKS_VERSION" to "4")::get,
            )!!
        assertEquals(
            ProxyProtocol.SOCKS4,
            socks4.rules.singleProxies
                .single()
                .protocol,
        )
    }

    @Test
    fun `no_proxy alone is an explicit direct configuration`() {
        val settings = EnvProxySettings.read(mapOf("no_proxy" to "*")::get)!!
        assertTrue(settings.isDirect)
    }

    @Test
    fun `no_proxy uses suffix matching and comma separators`() {
        val env =
            mapOf(
                "http_proxy" to "proxy.corp:8080",
                "no_proxy" to "localhost,corp.com,10.0.0.0/8",
            )
        val settings = EnvProxySettings.read(env::get)!!

        assertTrue(settings.bypassRules.matches(URI("http://localhost")))
        assertTrue(settings.bypassRules.matches(URI("http://www.corp.com")))
        assertTrue(settings.bypassRules.matches(URI("http://corp.com")))
        assertTrue(settings.bypassRules.matches(URI("http://10.1.2.3")))
        assertFalse(settings.bypassRules.matches(URI("http://example.com")))
    }

    @Test
    fun `auto_proxy empty enables WPAD and non-empty sets the PAC URL`() {
        assertTrue(EnvProxySettings.read(mapOf("auto_proxy" to "")::get)!!.autoDetect)
        assertEquals(
            "http://wpad/proxy.pac",
            EnvProxySettings.read(mapOf("auto_proxy" to "http://wpad/proxy.pac")::get)!!.pacUrl,
        )
    }

    @Test
    fun `userinfo in a proxy URL is stripped`() {
        val settings =
            EnvProxySettings.read(
                mapOf("http_proxy" to "http://user:pass@proxy.corp:8080/")::get,
            )!!
        assertEquals(
            ProxyServer(ProxyProtocol.HTTP, "proxy.corp", 8080),
            settings.rules.proxiesForHttp.single(),
        )
    }
}
