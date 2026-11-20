package dev.nucleusframework.nativeproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRulesTest {
    @Test
    fun `single proxy applies to every scheme`() {
        val rules = ProxyRules.parse("proxy.corp:8080")

        assertEquals(
            listOf(ProxyServer(ProxyProtocol.HTTP, "proxy.corp", 8080)),
            rules.proxiesForUrlScheme("http"),
        )
        assertEquals(rules.proxiesForUrlScheme("http"), rules.proxiesForUrlScheme("https"))
        assertEquals(rules.proxiesForUrlScheme("http"), rules.proxiesForUrlScheme("ftp"))
    }

    @Test
    fun `per-scheme entries are mapped to their scheme`() {
        val rules = ProxyRules.parse("http=http1.corp:80;https=http2.corp:8443;ftp=ftp.corp:21")

        assertEquals(listOf(ProxyServer(ProxyProtocol.HTTP, "http1.corp", 80)), rules.proxiesForUrlScheme("http"))
        assertEquals(listOf(ProxyServer(ProxyProtocol.HTTP, "http2.corp", 8443)), rules.proxiesForUrlScheme("https"))
        assertEquals(listOf(ProxyServer(ProxyProtocol.HTTP, "ftp.corp", 21)), rules.proxiesForUrlScheme("ftp"))
    }

    @Test
    fun `socks entry defaults to socks4 and serves unlisted schemes`() {
        val rules = ProxyRules.parse("http=http.corp:80;socks=socks.corp:1080")

        assertEquals(listOf(ProxyServer(ProxyProtocol.SOCKS4, "socks.corp", 1080)), rules.fallbackProxies)
        assertEquals(rules.fallbackProxies, rules.proxiesForUrlScheme("gopher"))
        assertEquals(rules.fallbackProxies, rules.proxiesForUrlScheme("https"))
    }

    @Test
    fun `explicit scheme prefixes win over the default`() {
        val rules = ProxyRules.parse("https=https://secure.corp:443;socks=socks5://socks.corp:1080")

        assertEquals(listOf(ProxyServer(ProxyProtocol.HTTPS, "secure.corp", 443)), rules.proxiesForUrlScheme("https"))
        assertEquals(listOf(ProxyServer(ProxyProtocol.SOCKS5, "socks.corp", 1080)), rules.fallbackProxies)
    }

    @Test
    fun `a missing port falls back to the protocol default`() {
        val rules = ProxyRules.parse("proxy.corp")

        assertEquals(listOf(ProxyServer(ProxyProtocol.HTTP, "proxy.corp", 80)), rules.singleProxies)
    }

    @Test
    fun `blank and malformed input yields empty rules`() {
        assertTrue(ProxyRules.parse("").isEmpty)
        assertTrue(ProxyRules.parse("  ;; ").isEmpty)
        assertTrue(ProxyRules.parse("direct://proxy.corp:80").isEmpty)
    }

    @Test
    fun `proxy list entries are parsed in order`() {
        val servers = ProxyServer.parseList("first.corp:8080; second.corp:3128 third.corp")

        assertEquals(3, servers.size)
        assertEquals("first.corp", servers[0].host)
        assertEquals(3128, servers[1].port)
        assertEquals(80, servers[2].port)
    }

    @Test
    fun `ipv6 proxies keep their literal host`() {
        val server = ProxyServer.parse("[::1]:8080")

        assertEquals(ProxyServer(ProxyProtocol.HTTP, "::1", 8080), server)
        assertEquals("http://[::1]:8080", server.toString())
    }

    @Test
    fun `out of range ports are rejected`() {
        assertNull(ProxyServer.parse("proxy.corp:0"))
        assertNull(ProxyServer.parse("proxy.corp:70000"))
    }
}
