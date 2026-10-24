package dev.nucleusframework.updater.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.exception.NetworkException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GitHubProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var httpClient: HttpClient
    private lateinit var serverBaseUrl: String
    private val feedCallCount = AtomicInteger(0)
    private val requests = CopyOnWriteArrayList<String>()
    private val authHeaders = CopyOnWriteArrayList<String>()
    private var feedBody: String = ""
    private var feedStatus: Int = HTTP_OK
    private var latestTag: String? = null

    /** `<tag>/<file>` published under `releases/download/`. */
    private val published = mutableSetOf<String>()

    @Before
    fun startServer() {
        feedBody = atomFeed()
        feedStatus = HTTP_OK

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.start()
        serverBaseUrl = "http://127.0.0.1:${server.address.port}"
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        requests += "${exchange.requestMethod} $path"
        exchange.requestHeaders["Authorization"]?.let(authHeaders::addAll)
        when {
            path == "/acme/tool/releases.atom" -> {
                feedCallCount.incrementAndGet()
                respond(exchange, feedStatus, feedBody)
            }
            path == "/api/v3/repos/acme/tool/releases/latest" -> {
                val tag = latestTag
                if (tag == null) {
                    exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                } else {
                    respond(exchange, HTTP_OK, """{"id":1,"tag_name":"$tag","name":"ignored"}""")
                }
            }
            path.startsWith(DOWNLOAD_PATH) && path.removePrefix(DOWNLOAD_PATH) in published ->
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(HTTP_OK, -1)
                } else {
                    respond(exchange, HTTP_OK, manifest(path.removePrefix(DOWNLOAD_PATH).substringBefore('/')))
                }
            else -> exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun newProvider(): GitHubProvider =
        GitHubProvider("acme", "tool", host = "127.0.0.1:${server.address.port}", protocol = "http")

    /**
     * Resolves as [dev.nucleusframework.updater.NucleusUpdater] does: pre-releases allowed on a
     * pre-release [current] version or another channel than `latest`, unless [allowPrerelease] says.
     */
    private fun resolve(
        channel: String,
        current: String = "1.0.0",
        platform: Platform = Platform.Linux,
        allowPrerelease: Boolean = current.contains('-') || !channel.equals("latest", ignoreCase = true),
        provider: GitHubProvider = newProvider(),
    ): String = provider.resolveMetadataUrl(channel, platform, httpClient, current, allowPrerelease)

    private fun download(
        tag: String,
        file: String,
    ): String = "$serverBaseUrl$DOWNLOAD_PATH$tag/$file"

    /** The manifest every file of [tag] serves: one zip of that version. */
    private fun manifest(tag: String): String {
        val version = tag.removePrefix("v")
        return """
            version: $version
            files:
              - url: App-$version.zip
                sha512: AAAA
                size: 10
            releaseDate: '2026-01-01T00:00:00.000Z'
            """.trimIndent()
    }

    /** Checks through [NucleusUpdater], as an app on [current] and [channel] would. */
    private fun check(
        current: String,
        channel: String = "latest",
    ): UpdateResult {
        val provider = newProvider()
        val updater =
            NucleusUpdater {
                currentVersion = current
                this.channel = channel
                this.provider = provider
                executableType = "zip"
            }
        return runBlocking { updater.checkForUpdates() }
    }

    private fun publish(
        tag: String,
        vararg files: String,
    ) {
        files.forEach { published += "$tag/$it" }
    }

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
    fun `stable reads the latest release's tag and no feed`() {
        latestTag = "1.5.0"
        assertEquals(download("1.5.0", "latest-linux.yml"), resolve("latest"))
        assertEquals(0, feedCallCount.get())
        assertEquals(listOf("GET /api/v3/repos/acme/tool/releases/latest"), requests)
    }

    @Test
    fun `stable channel is case-insensitive`() {
        latestTag = "v1.5.0"
        assertEquals(download("v1.5.0", "LATEST-mac.yml"), resolve("LATEST", platform = Platform.MacOS))
        assertEquals(0, feedCallCount.get())
    }

    @Test
    fun `no published release is reported`() {
        val e = assertThrows(NoSuchElementException::class.java) { resolve("latest") }
        assertTrue(e.message!!, e.message!!.contains("No published release for acme/tool"))
    }

    @Test
    fun `files download under the manifest's tag, spaces as dashes`() {
        latestTag = "1.5.0"
        val provider = newProvider()
        val metadataUrl = resolve("latest", provider = provider)
        assertEquals(
            download("1.5.0", "App-Setup-1.5.0.exe"),
            provider.getDownloadUrl("App Setup 1.5.0.exe", "1.5.0", metadataUrl),
        )
        assertEquals(
            download("1.5.0", "App-1.5.0.zip"),
            provider.getDownloadUrl("https://cdn.example.com/x/App-1.5.0.zip", "1.5.0", metadataUrl),
        )
        // Without the manifest's URL, the tag is v<version>.
        assertEquals(download("v1.5.0", "App-1.5.0.zip"), provider.getDownloadUrl("App-1.5.0.zip", "1.5.0"))
    }

    @Test
    fun `beta client takes the newest beta from the feed`() {
        feedBody = atomFeed("v1.2.3-alpha.4", "v1.2.3-beta.5", "v1.2.2")
        publish("v1.2.3-beta.5", "beta-linux.yml")
        assertEquals(download("v1.2.3-beta.5", "beta-linux.yml"), resolve("beta"))
        assertEquals(1, feedCallCount.get())
    }

    @Test
    fun `beta client takes the highest beta, whatever the feed order`() {
        feedBody = atomFeed("v1.2.9-beta.7", "v1.3.0-beta.1", "v1.3.0-beta.2")
        publish("v1.3.0-beta.2", "beta.yml")
        assertEquals(download("v1.3.0-beta.2", "beta.yml"), resolve("beta", platform = Platform.Windows))
    }

    @Test
    fun `a stable hotfix published after a beta does not hide that beta`() {
        // Published v1.1.0-beta.3, v1.1.0-beta.4, then the hotfix v1.0.4: the feed lists it first.
        // electron-updater takes the first eligible entry, v1.0.4, and leaves beta.3 where it is.
        feedBody = atomFeed("v1.0.4", "v1.1.0-beta.4", "v1.1.0-beta.3")
        publish("v1.0.4", "latest-linux.yml")
        publish("v1.1.0-beta.4", "beta-linux.yml")
        assertEquals(download("v1.1.0-beta.4", "beta-linux.yml"), resolve("beta", current = "1.1.0-beta.3"))
        assertEquals(download("v1.1.0-beta.4", "beta-linux.yml"), resolve("latest", current = "1.1.0-beta.3"))
    }

    @Test
    fun `a beta user is offered the next beta after a hotfix, then the release`() {
        feedBody = atomFeed("v1.0.4", "v1.1.0-beta.4", "v1.1.0-beta.3")
        publish("v1.0.4", "latest-linux.yml", "latest-mac.yml", "latest.yml")
        publish("v1.1.0-beta.4", "beta-linux.yml", "beta-mac.yml", "beta.yml")
        for (channel in listOf("latest", "beta")) {
            val result = check("1.1.0-beta.3", channel)
            assertTrue("channel $channel: $result", result is UpdateResult.Available)
            assertEquals("channel $channel", "1.1.0-beta.4", (result as UpdateResult.Available).info.version)
        }

        feedBody = atomFeed("v1.1.0", "v1.0.4", "v1.1.0-beta.4", "v1.1.0-beta.3")
        publish("v1.1.0", "latest-linux.yml", "latest-mac.yml", "latest.yml")
        for (channel in listOf("latest", "beta")) {
            val result = check("1.1.0-beta.4", channel)
            assertTrue("channel $channel: $result", result is UpdateResult.Available)
            assertEquals("channel $channel", "1.1.0", (result as UpdateResult.Available).info.version)
        }
    }

    @Test
    fun `beta client moves on to a newer release through its latest manifest`() {
        feedBody = atomFeed("v1.1.0", "v1.1.0-beta.2")
        publish("v1.1.0", "latest-linux.yml")
        assertEquals(download("v1.1.0", "latest-linux.yml"), resolve("beta", current = "1.1.0-beta.2"))
        assertTrue(requests.contains("HEAD ${DOWNLOAD_PATH}v1.1.0/beta-linux.yml"))
    }

    @Test
    fun `a release that publishes the beta manifest keeps it`() {
        feedBody = atomFeed("v1.1.0", "v1.1.0-beta.2")
        publish("v1.1.0", "latest-linux.yml", "beta-linux.yml")
        assertEquals(download("v1.1.0", "beta-linux.yml"), resolve("beta"))
    }

    @Test
    fun `beta client takes a release past ten releases without a beta`() {
        feedBody = atomFeed(*(10 downTo 1).map { "v1.0.$it" }.toTypedArray())
        publish("v1.0.10", "latest-linux.yml")
        assertEquals(download("v1.0.10", "latest-linux.yml"), resolve("beta", current = "1.0.0-beta.3"))
    }

    @Test
    fun `alpha client takes a newer beta`() {
        feedBody = atomFeed("v2.0.0-beta.1", "v2.0.0-alpha.3")
        publish("v2.0.0-beta.1", "beta-linux.yml")
        assertEquals(download("v2.0.0-beta.1", "beta-linux.yml"), resolve("alpha"))
    }

    @Test
    fun `beta client skips alpha and same-prefix channels`() {
        // Channels "alpha" and "beta-leftover", not "beta".
        feedBody = atomFeed("v1.0.0-alpha.9", "v1.0.0-beta-leftover", "v1.0.0-beta.3")
        publish("v1.0.0-beta.3", "beta-mac.yml")
        assertEquals(download("v1.0.0-beta.3", "beta-mac.yml"), resolve("beta", platform = Platform.MacOS))
    }

    @Test
    fun `another channel takes exactly that channel`() {
        feedBody = atomFeed("v2.0.0", "v2.0.0-beta.1", "v1.9.0-rc.2")
        publish("v1.9.0-rc.2", "rc-linux.yml")
        assertEquals(download("v1.9.0-rc.2", "rc-linux.yml"), resolve("rc"))
    }

    @Test
    fun `channels are matched exactly`() {
        feedBody = atomFeed("v1.4.0-Beta.9", "v1.3.0-beta.1")
        publish("v1.3.0-beta.1", "beta-linux.yml")
        assertEquals(download("v1.3.0-beta.1", "beta-linux.yml"), resolve("beta"))
    }

    @Test
    fun `a pre-release version on latest follows its own channel`() {
        feedBody = atomFeed("v1.1.0-alpha.1", "v1.1.0-beta.2")
        publish("v1.1.0-beta.2", "beta-linux.yml")
        assertEquals(download("v1.1.0-beta.2", "beta-linux.yml"), resolve("latest", current = "1.1.0-beta.1"))
    }

    @Test
    fun `a release with pre-releases allowed takes the highest version`() {
        feedBody = atomFeed("v1.0.1", "v1.1.0-beta.1", "v1.0.2-alpha.1")
        publish("v1.1.0-beta.1", "beta-linux.yml")
        assertEquals(download("v1.1.0-beta.1", "beta-linux.yml"), resolve("latest", allowPrerelease = true))
    }

    @Test
    fun `without the three-argument context a pre-release channel still reads the feed`() {
        feedBody = atomFeed("v1.2.0-beta.1")
        publish("v1.2.0-beta.1", "beta-linux.yml")
        assertEquals(
            download("v1.2.0-beta.1", "beta-linux.yml"),
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient),
        )
    }

    @Test
    fun `links in release notes are not releases`() {
        // GitHub escapes the notes' HTML into <content>; a regex over the raw feed read a junk tag
        // out of this link (`v1.9.0-beta.4&quot;&gt;…`, on channel "beta") before the real entry.
        val notes = "&lt;a href=&quot;https://github.com/acme/tool/releases/tag/v1.9.0-beta.4&quot;&gt;x&lt;/a&gt;"
        feedBody = atomFeed("v1.9.0-beta.3", notes = notes)
        publish("v1.9.0-beta.3", "beta-linux.yml")
        assertEquals(download("v1.9.0-beta.3", "beta-linux.yml"), resolve("beta"))
    }

    @Test
    fun `feed tags come from each entry's own link only`() {
        val notes = "&lt;a href=&quot;https://github.com/acme/tool/releases/tag/v0.1.0&quot;&gt;old&lt;/a&gt;"
        assertEquals(
            listOf("v3.0.0", "v2.0.0", "v1.0.0"),
            GitHubReleases.feedTags(atomFeed("v3.0.0", "v2.0.0", "v1.0.0", notes = notes)),
        )
    }

    @Test
    fun `tags that are not versions are on no channel`() {
        feedBody = atomFeed(".", "..", "nightly", "beta", "v2.0.0-beta.1")
        publish("v2.0.0-beta.1", "beta-linux.yml")
        assertEquals(download("v2.0.0-beta.1", "beta-linux.yml"), resolve("beta"))
    }

    @Test
    fun `a pre-release channel is the first pre-release identifier`() {
        assertEquals("beta", GitHubReleases.prereleaseChannel("v2.3.5-beta.8"))
        assertEquals("beta-leftover", GitHubReleases.prereleaseChannel("1.0.0-beta-leftover"))
        assertEquals("rc", GitHubReleases.prereleaseChannel("1.0.0-rc+build.5"))
        assertNull(GitHubReleases.prereleaseChannel("v1.0.0+build-5"))
        assertNull(GitHubReleases.prereleaseChannel("v1.0.0"))
        assertNull(GitHubReleases.prereleaseChannel("nightly-beta"))
    }

    @Test
    fun `a release tag is a SemVer version, optionally prefixed with v`() {
        for (tag in listOf("v1.2.3", "1.2.3", "v1.2.3-beta.1", "1.0.0-rc.1+build.5")) {
            assertTrue(tag, GitHubReleases.isReleaseTag(tag))
        }
        for (tag in listOf(".", "..", "nightly", "v1.2", "01.2.3", "v1.2.3&quot;&gt;", "v../1.0.0")) {
            assertFalse(tag, GitHubReleases.isReleaseTag(tag))
        }
    }

    @Test
    fun `a feed that is not XML becomes a NetworkException`() {
        feedBody = "<feed><entry>"
        val e =
            assertThrows(
                NetworkException::class.java,
            ) { newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient) }
        assertTrue(e.message!!, e.message!!.contains("acme/tool is not valid Atom"))
    }

    @Test
    fun `a feed cannot pull in external entities`() {
        val secret = tmp.newFile("secret.txt").apply { writeText("v6.6.6-beta.1") }
        feedBody =
            """<?xml version="1.0"?><!DOCTYPE feed [<!ENTITY x SYSTEM "${secret.toURI()}">]>""" +
            """<feed xmlns="http://www.w3.org/2005/Atom"><entry>""" +
            """<link href="https://github.com/acme/tool/releases/tag/&x;"/></entry></feed>"""
        assertThrows(
            NetworkException::class.java,
        ) { newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient) }
    }

    @Test
    fun `throws when no release matches the channel`() {
        feedBody = atomFeed("v1.0.0-alpha.1", "v1.0.0")
        try {
            resolve("rc")
            fail("expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertNotNull(e.message)
            assertTrue("message should name the channel", e.message!!.contains("No release found for channel 'rc'"))
            assertTrue("message should name the feed's limit", e.message!!.contains("10 most recent releases"))
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
        latestTag = "v0.9.0"
        val provider = newProvider()
        resolve("beta", provider = provider)
        resolve("latest", provider = provider)
        assertEquals(emptyList<String>(), authHeaders)
        assertEquals(emptyMap<String, String>(), provider.authHeaders())
    }

    /** A releases feed, newest first; [notes] is each entry's `<content>`, already escaped as GitHub does. */
    private fun atomFeed(
        vararg tags: String,
        notes: String = "",
    ): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<feed xmlns="http://www.w3.org/2005/Atom">""")
            append("""<link rel="self" href="https://github.com/acme/tool/releases.atom"/>""")
            for (tag in tags) {
                append("<entry>")
                append("""<link rel="alternate" type="text/html" """)
                append("""href="https://github.com/acme/tool/releases/tag/$tag"/>""")
                append("""<content type="html">$notes</content>""")
                append("</entry>")
            }
            append("</feed>")
        }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val DOWNLOAD_PATH = "/acme/tool/releases/download/"
    }
}
