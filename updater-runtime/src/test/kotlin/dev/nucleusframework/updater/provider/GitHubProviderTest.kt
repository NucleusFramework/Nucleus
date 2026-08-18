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
    private val apiCallCount = AtomicInteger(0)
    private val lastAuthHeader = AtomicReference<String?>(null)
    private var responseBody: String = "[]"
    private var responseStatus: Int = HTTP_OK

    @Before
    fun startServer() {
        apiCallCount.set(0)
        lastAuthHeader.set(null)
        responseBody = "[]"
        responseStatus = HTTP_OK

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/repos/",
            HttpHandler { exchange: HttpExchange ->
                apiCallCount.incrementAndGet()
                lastAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
                val bytes = responseBody.toByteArray()
                exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            },
        )
        server.start()
        httpClient = HttpClient.newHttpClient()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun newProvider(token: String? = null): GitHubProvider =
        GitHubProvider("acme", "tool", token).apply {
            apiBaseUrl = "http://127.0.0.1:${server.address.port}"
        }

    @Test
    fun `stable channel makes no API call`() {
        val provider = newProvider()

        val url = provider.resolveMetadataUrl("latest", Platform.Linux, httpClient)

        assertEquals("https://github.com/acme/tool/releases/latest/download/latest-linux.yml", url)
        assertEquals(0, apiCallCount.get())
    }

    @Test
    fun `beta channel finds matching pre-release`() {
        responseBody =
            jsonArray(
                Release("v1.2.3-alpha.4", prerelease = true),
                Release("v1.2.3-beta.5", prerelease = true),
                Release("v1.2.2", prerelease = false),
            )

        val url = newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)

        assertEquals(
            "https://github.com/acme/tool/releases/download/v1.2.3-beta.5/beta-linux.yml",
            url,
        )
        assertEquals(1, apiCallCount.get())
    }

    @Test
    fun `beta channel picks first match in API order`() {
        responseBody =
            jsonArray(
                Release("v1.3.0-beta.2", prerelease = true),
                Release("v1.3.0-beta.1", prerelease = true),
                Release("v1.2.9-beta.7", prerelease = true),
            )

        val url = newProvider().resolveMetadataUrl("beta", Platform.Windows, httpClient)

        assertEquals(
            "https://github.com/acme/tool/releases/download/v1.3.0-beta.2/beta.yml",
            url,
        )
    }

    @Test
    fun `skips non-prerelease entries with matching tag name`() {
        responseBody =
            jsonArray(
                Release("v1.0.0-beta-leftover", prerelease = false),
                Release("v1.0.0-beta.3", prerelease = true),
            )

        val url = newProvider().resolveMetadataUrl("beta", Platform.MacOS, httpClient)

        assertEquals(
            "https://github.com/acme/tool/releases/download/v1.0.0-beta.3/beta-mac.yml",
            url,
        )
    }

    @Test
    fun `throws when no match in window`() {
        val alphas = (1..NO_MATCH_RELEASE_COUNT).map { Release("v1.0.0-alpha.$it", prerelease = true) }
        responseBody = jsonArray(*alphas.toTypedArray())

        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertNotNull(e.message)
            assertTrue(
                "message should mention the window size",
                e.message!!.contains("within the most recent 100 releases"),
            )
        }
    }

    @Test
    fun `auth header sent when token configured`() {
        responseBody = jsonArray(Release("v1.0.0-beta.1", prerelease = true))

        newProvider(token = "ghp_test").resolveMetadataUrl("beta", Platform.Linux, httpClient)

        assertEquals("Bearer ghp_test", lastAuthHeader.get())
    }

    @Test
    fun `no auth header when token is null`() {
        responseBody = jsonArray(Release("v1.0.0-beta.1", prerelease = true))

        newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)

        assertNull(lastAuthHeader.get())
    }

    @Test
    fun `stable channel is case-insensitive`() {
        val url = newProvider().resolveMetadataUrl("LATEST", Platform.MacOS, httpClient)
        assertEquals("https://github.com/acme/tool/releases/latest/download/LATEST-mac.yml", url)
        assertEquals(0, apiCallCount.get())
    }

    @Test
    fun `getUpdateMetadataUrl and getDownloadUrl use the repo coordinates`() {
        val provider = newProvider()
        assertEquals(
            "https://github.com/acme/tool/releases/latest/download/beta.yml",
            provider.getUpdateMetadataUrl("beta", Platform.Windows),
        )
        assertEquals(
            "https://github.com/acme/tool/releases/download/v2.1.0/App-2.1.0.dmg",
            provider.getDownloadUrl("App-2.1.0.dmg", "2.1.0"),
        )
        assertEquals(emptyMap<String, String>(), provider.authHeaders())
        assertEquals(mapOf("Authorization" to "token ghp_x"), newProvider(token = "ghp_x").authHeaders())
    }

    @Test
    fun `HTTP 500 becomes a NetworkException`() {
        responseStatus = 500
        responseBody = "oops"
        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NetworkException")
        } catch (e: NetworkException) {
            assertTrue(e.message!!.contains("HTTP 500"))
            assertTrue(e.message!!.contains("acme/tool"))
        }
    }

    @Test
    fun `HTTP 403 with exhausted rate limit mentions the token hint`() {
        responseStatus = 403
        server.stop(0)
        server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repos/") { exchange ->
            apiCallCount.incrementAndGet()
            exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
            val bytes = "rate limited".toByteArray()
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val provider =
            GitHubProvider("acme", "tool").apply {
                apiBaseUrl = "http://127.0.0.1:${server.address.port}"
            }
        try {
            provider.resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NetworkException")
        } catch (e: NetworkException) {
            assertTrue(e.message!!.contains("rate limit exceeded"))
        }
    }

    @Test
    fun `tag match is case-insensitive and ignores extra JSON fields`() {
        responseBody =
            """
            [
              {"tag_name":"v1.4.0-Beta.9","prerelease":true,"draft":false,"name":"ignored"}
            ]
            """.trimIndent()
        val url = newProvider().resolveMetadataUrl("beta", Platform.Unknown, httpClient)
        assertEquals(
            "https://github.com/acme/tool/releases/download/v1.4.0-Beta.9/beta.yml",
            url,
        )
    }

    private data class Release(
        val tag: String,
        val prerelease: Boolean,
    )

    private fun jsonArray(vararg releases: Release): String =
        releases.joinToString(prefix = "[", postfix = "]") { r ->
            """{"tag_name":"${r.tag}","prerelease":${r.prerelease}}"""
        }

    private companion object {
        const val HTTP_OK = 200
        const val NO_MATCH_RELEASE_COUNT = 100
    }
}
