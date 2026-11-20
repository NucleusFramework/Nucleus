package dev.nucleusframework.nativeproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ProxyBypassRulesTest {
    @Test
    fun `local token bypasses dot-less hostnames only`() {
        val rules = ProxyBypassRules.parse("<local>")

        assertTrue(rules.matches(URI("http://intranet")))
        assertFalse(rules.matches(URI("http://intranet.corp.com")))
        assertFalse(rules.matches(URI("http://93.184.216.34")))
    }

    @Test
    fun `leading dot matches subdomains but not the domain itself`() {
        val rules = ProxyBypassRules.parse(".corp.com")

        assertTrue(rules.matches(URI("https://build.corp.com")))
        assertTrue(rules.matches(URI("https://a.b.corp.com")))
        assertFalse(rules.matches(URI("https://corp.com")))
    }

    @Test
    fun `an exact hostname does not match its subdomains`() {
        val rules = ProxyBypassRules.parse("corp.com")

        assertTrue(rules.matches(URI("https://corp.com")))
        assertFalse(rules.matches(URI("https://build.corp.com")))
    }

    @Test
    fun `wildcards and star match as glob patterns`() {
        assertTrue(ProxyBypassRules.parse("*.corp.com").matches(URI("http://a.corp.com")))
        assertTrue(ProxyBypassRules.parse("*").matches(URI("http://anything.example")))
    }

    @Test
    fun `a port suffix restricts the rule to that port`() {
        val rules = ProxyBypassRules.parse("build.corp.com:8080")

        assertTrue(rules.matches(URI("http://build.corp.com:8080/path")))
        assertFalse(rules.matches(URI("http://build.corp.com/path")))
    }

    @Test
    fun `a scheme prefix restricts the rule to that scheme`() {
        val rules = ProxyBypassRules.parse("http://build.corp.com")

        assertTrue(rules.matches(URI("http://build.corp.com")))
        assertFalse(rules.matches(URI("https://build.corp.com")))
    }

    @Test
    fun `cidr blocks match ipv4 and ipv6 literals`() {
        assertTrue(ProxyBypassRules.parse("10.0.0.0/8").matches(URI("http://10.4.5.6")))
        assertFalse(ProxyBypassRules.parse("10.0.0.0/8").matches(URI("http://11.4.5.6")))
        assertTrue(ProxyBypassRules.parse("192.168.1.0/24").matches(URI("http://192.168.1.42")))
        assertFalse(ProxyBypassRules.parse("192.168.1.0/24").matches(URI("http://192.168.2.42")))
        assertTrue(ProxyBypassRules.parse("fd00::/8").matches(URI("http://[fd12::1]")))
    }

    @Test
    fun `localhost and link-local addresses bypass implicitly`() {
        val rules = ProxyBypassRules.EMPTY

        assertTrue(rules.matches(URI("http://localhost:3000")))
        assertTrue(rules.matches(URI("http://127.0.0.1")))
        assertTrue(rules.matches(URI("http://[::1]:8080")))
        assertTrue(rules.matches(URI("http://169.254.10.20")))
        assertFalse(rules.matches(URI("http://example.com")))
    }

    @Test
    fun `negate loopback token disables the implicit bypass`() {
        val rules = ProxyBypassRules.parse("<-loopback>")

        assertFalse(rules.matches(URI("http://localhost:3000")))
        assertFalse(rules.matches(URI("http://127.0.0.1")))
    }

    @Test
    fun `entries are separated by semicolons and whitespace`() {
        val rules = ProxyBypassRules.parse(" .corp.com; 10.0.0.0/8 <local>")

        assertTrue(rules.bypassesSimpleHostnames)
        assertTrue(rules.matches(URI("http://a.corp.com")))
        assertTrue(rules.matches(URI("http://10.1.1.1")))
        assertTrue(rules.matches(URI("http://intranet")))
    }
}
