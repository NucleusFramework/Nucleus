package dev.nucleusframework.desktop.application.internal

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `appLinks(…)` hosts become the AppX `windows.appUriHandler` extension. */
class AppXAppLinksTest {
    @Test
    fun `hosts are normalized and deduplicated`() {
        assertEquals(
            listOf("example.com", "*.example.com"),
            AppXAppLinks.hosts(listOf(" Example.com", "*.example.com", "example.COM")),
        )
    }

    @Test
    fun `anything but a bare host name is rejected`() {
        val invalid =
            listOf("https://example.com", "example.com/path", "example.com:443", "*example.com", "a.*.com", "")
        for (bad in invalid) {
            assertThrows(bad, GradleException::class.java) { AppXAppLinks.hosts(listOf(bad)) }
        }
    }

    @Test
    fun `exact hosts use uap3, wildcards uap5, both namespaces declared inline`() {
        val xml = AppXAppLinks.extensionsXml(listOf("example.com", "*.example.com"))

        assertTrue(xml, xml.contains("xmlns:uap3=\"http://schemas.microsoft.com/appx/manifest/uap/windows10/3\""))
        assertTrue(xml, xml.contains("xmlns:uap5=\"http://schemas.microsoft.com/appx/manifest/uap/windows10/5\""))
        assertTrue(xml, xml.contains("Category=\"windows.appUriHandler\""))
        assertTrue(xml, xml.contains("<uap3:Host Name=\"example.com\" />"))
        assertTrue(xml, xml.contains("<uap5:Host Name=\"*.example.com\" />"))
        assertFalse(xml, xml.contains("<uap3:Host Name=\"*.example.com\" />"))
    }
}
