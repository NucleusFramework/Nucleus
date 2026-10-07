package dev.nucleusframework.updater.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.exception.NetworkException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GitHubProviderTest {
    private lateinit var server: HttpServer
    private lateinit var httpClient: HttpClient
    private lateinit var serverBaseUrl: String
    private val feedCallCount = AtomicInteger(0)
    private val lastAuthHeader = AtomicReference<String?>(null)
    private var feedBody: String = ""
    private var feedStatus: Int = HTTP_OK

    @Before
    fun startServer() {
        feedCallCount.set(0)
        lastAuthHeader.set(null)
        feedBody = atomFeed()
        feedStatus = HTTP_OK

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/acme/tool/releases.atom",
            HttpHandler { exchange: HttpExchange ->
                feedCallCount.incrementAndGet()
                lastAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
                val bytes = feedBody.toByteArray()
                exchange.sendResponseHeaders(feedStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            },
        )
        server.start()
        serverBaseUrl = "http://127.0.0.1:${server.address.port}"
        httpClient = HttpClient.newHttpClient()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun newProvider(): GitHubProvider =
        GitHubProvider("acme", "tool", host = "127.0.0.1:${server.address.port}", protocol = "http")

    @Test
    fun `default base url is github`() {
        val provider = GitHubProvider("acme", "tool")
        assertEquals(
            "https://github.com/acme/tool/releases/latest/download/latest.yml",
            provider.getUpdateMetadataUrl("latest", Platform.Windows),
        )
        assertEquals(
            "https://github.com/acme/tool/releases/download/v2.1.0/App-2.1.0.dmg",
            provider.getDownloadUrl("App-2.1.0.dmg", "2.1.0"),
        )
    }

    @Test
    fun `custom host is used for metadata and downloads`() {
        val provider = GitHubProvider("acme", "tool", host = "github.example.com:8443")
        assertEquals("github.example.com:8443", provider.host)
        assertEquals(
            "https://github.example.com:8443/acme/tool/releases/latest/download/latest-linux.yml",
            provider.getUpdateMetadataUrl("latest", Platform.Linux),
        )
        assertEquals(
            "https://github.example.com:8443/acme/tool/releases/latest/download/latest-mac.yml",
            provider.resolveMetadataUrl("latest", Platform.MacOS, httpClient),
        )
        assertEquals(
            "https://github.example.com:8443/acme/tool/releases/download/v2.1.0/App-2.1.0.exe",
            provider.getDownloadUrl("App-2.1.0.exe", "2.1.0"),
        )
    }

    @Test
    fun `host must be a bare host name`() {
        for (host in listOf(
            "",
            " ",
            "https://github.example.com",
            "github.example.com/",
            "github.example.com/ghe",
            "a b",
        )) {
            try {
                GitHubProvider("acme", "tool", host = host)
                fail("expected IllegalArgumentException for host '$host'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("bare host name"))
            }
        }
    }

    @Test
    fun `protocol is https or http and http only for a loopback host`() {
        assertEquals("https", GitHubProvider("acme", "tool").protocol)
        assertEquals(
            "http://localhost:8080/acme/tool/releases/download/v1.0.0/App.zip",
            GitHubProvider(
                "acme",
                "tool",
                host = "localhost:8080",
                protocol = "HTTP",
            ).getDownloadUrl("App.zip", "1.0.0"),
        )
        val remote =
            assertThrows(IllegalArgumentException::class.java) {
                GitHubProvider("acme", "tool", host = "github.example.com", protocol = "http")
            }
        assertTrue(remote.message!!.contains("http only for a loopback host"))
        val unknown =
            assertThrows(IllegalArgumentException::class.java) { GitHubProvider("acme", "tool", protocol = "ftp") }
        assertTrue(unknown.message!!.contains("protocol must be"))
    }

    @Test
    fun `stable channel makes no feed call`() {
        val url = newProvider().resolveMetadataUrl("latest", Platform.Linux, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/latest/download/latest-linux.yml", url)
        assertEquals(0, feedCallCount.get())
    }

    @Test
    fun `stable channel is case-insensitive`() {
        val url = newProvider().resolveMetadataUrl("LATEST", Platform.MacOS, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/latest/download/LATEST-mac.yml", url)
        assertEquals(0, feedCallCount.get())
    }

    @Test
    fun `beta channel finds the newest matching pre-release from the feed`() {
        feedBody = atomFeed("v1.2.3-alpha.4", "v1.2.3-beta.5", "v1.2.2")
        val url = newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.2.3-beta.5/beta-linux.yml", url)
        assertEquals(1, feedCallCount.get())
    }

    @Test
    fun `beta channel picks the first match in feed order`() {
        feedBody = atomFeed("v1.3.0-beta.2", "v1.3.0-beta.1", "v1.2.9-beta.7")
        val url = newProvider().resolveMetadataUrl("beta", Platform.Windows, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.3.0-beta.2/beta.yml", url)
    }

    @Test
    fun `beta channel skips alpha and same-prefix channels`() {
        // Channels "alpha" and "beta-leftover", not "beta".
        feedBody = atomFeed("v1.0.0-alpha.9", "v1.0.0-beta-leftover", "v1.0.0-beta.3")
        val url = newProvider().resolveMetadataUrl("beta", Platform.MacOS, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.0.0-beta.3/beta-mac.yml", url)
    }

    @Test
    fun `channel match is case-insensitive`() {
        feedBody = atomFeed("v1.4.0-Beta.9")
        val url = newProvider().resolveMetadataUrl("beta", Platform.Unknown, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.4.0-Beta.9/beta.yml", url)
    }

    @Test
    fun `throws when no release matches the channel`() {
        feedBody = atomFeed("v1.0.0-alpha.1", "v1.0.0")
        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertNotNull(e.message)
            assertTrue("message should name the channel", e.message!!.contains("No release found for channel 'beta'"))
        }
    }

    @Test
    fun `throws when the feed has no releases`() {
        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertTrue(e.message!!.contains("No published versions for acme/tool"))
        }
    }

    @Test
    fun `feed HTTP error becomes a NetworkException`() {
        feedStatus = 503
        feedBody = "unavailable"
        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NetworkException")
        } catch (e: NetworkException) {
            assertTrue(e.message!!.contains("HTTP 503"))
            assertTrue(e.message!!.contains("acme/tool"))
        }
    }

    @Test
    fun `requests carry no credentials`() {
        feedBody = atomFeed("v1.0.0-beta.1")
        val provider = newProvider()
        provider.resolveMetadataUrl("beta", Platform.Linux, httpClient)
        assertNull(lastAuthHeader.get())
        assertEquals(emptyMap<String, String>(), provider.authHeaders())
    }

    private fun atomFeed(vararg tags: String): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<feed xmlns="http://www.w3.org/2005/Atom">""")
            append("""<link rel="self" href="https://github.com/acme/tool/releases.atom"/>""")
            for (tag in tags) {
                append("<entry>")
                append("""<link rel="alternate" type="text/html" """)
                append("""href="https://github.com/acme/tool/releases/tag/$tag"/>""")
                append("</entry>")
            }
            append("</feed>")
        }

    private companion object {
        const val HTTP_OK = 200
    }
}
