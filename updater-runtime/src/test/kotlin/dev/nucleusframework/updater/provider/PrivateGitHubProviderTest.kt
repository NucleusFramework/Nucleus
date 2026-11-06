package dev.nucleusframework.updater.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.delta.DeltaFixtures
import dev.nucleusframework.updater.delta.RangeHttpServer
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.internal.PlatformInfo
import dev.nucleusframework.updater.internal.delta.UpdateCache
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class PrivateGitHubProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storage: RangeHttpServer
    private lateinit var api: FakeGitHubApi
    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    @Before
    fun setUp() {
        storage = RangeHttpServer()
        api = FakeGitHubApi(storage)
    }

    @After
    fun tearDown() {
        api.close()
        storage.close()
    }

    private fun newProvider(token: String = TOKEN): PrivateGitHubProvider =
        PrivateGitHubProvider("acme", "tool", token, host = api.host, protocol = "http")

    /**
     * Resolves as [NucleusUpdater] does: pre-releases allowed on a pre-release [current] version or
     * another channel than `latest`, unless [allowPrerelease] says.
     */
    private fun resolve(
        channel: String,
        current: String = "1.0.0",
        platform: Platform = Platform.Linux,
        allowPrerelease: Boolean = current.contains('-') || !channel.equals("latest", ignoreCase = true),
        provider: PrivateGitHubProvider = newProvider(),
    ): String = provider.resolveMetadataUrl(channel, platform, httpClient, current, allowPrerelease)

    @Test
    fun `stable channel reads releases latest and returns the manifest asset`() {
        api.releases =
            listOf(
                FakeRelease("v2.0.0-beta.1", prerelease = true, assets = mapOf("latest-linux.yml" to "beta")),
                FakeRelease("v1.5.0", assets = mapOf("latest-linux.yml" to "stable")),
            )

        val url = newProvider().resolveMetadataUrl("latest", Platform.Linux, httpClient)

        assertEquals(api.assetUrl("v1.5.0", "latest-linux.yml"), url)
        val request = api.requests.single()
        assertEquals("/api/v3/repos/acme/tool/releases/latest", request.path)
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("application/vnd.github+json", request.accept)
    }

    @Test
    fun `beta channel takes the highest non-draft release it follows`() {
        api.releases =
            listOf(
                FakeRelease("v3.0.0-beta.3", draft = true, assets = mapOf("beta.yml" to "draft")),
                FakeRelease("v3.0.0-beta-leftover", prerelease = true, assets = mapOf("beta.yml" to "other channel")),
                FakeRelease("v3.0.0-alpha.1", prerelease = true, assets = mapOf("beta.yml" to "alpha")),
                FakeRelease("v3.0.0-Beta.2", prerelease = true, assets = mapOf("beta.yml" to "channel Beta")),
                FakeRelease("v2.9.1", assets = mapOf("latest.yml" to "release")),
                FakeRelease("v3.0.0-beta.1", prerelease = true, assets = mapOf("beta.yml" to "this one")),
            )

        val url = resolve("beta", platform = Platform.Windows)

        assertEquals(api.assetUrl("v3.0.0-beta.1", "beta.yml"), url)
        assertEquals("/api/v3/repos/acme/tool/releases?per_page=100", api.requests.single().pathAndQuery)
    }

    @Test
    fun `a stable hotfix published after a beta does not hide that beta`() {
        api.releases =
            listOf(
                FakeRelease("v1.0.4", assets = mapOf("latest-linux.yml" to "hotfix")),
                FakeRelease("v1.1.0-beta.4", prerelease = true, assets = mapOf("beta-linux.yml" to "next beta")),
                FakeRelease("v1.1.0-beta.3", prerelease = true, assets = mapOf("beta-linux.yml" to "installed")),
            )

        assertEquals(api.assetUrl("v1.1.0-beta.4", "beta-linux.yml"), resolve("beta", current = "1.1.0-beta.3"))
        assertEquals(api.assetUrl("v1.1.0-beta.4", "beta-linux.yml"), resolve("latest", current = "1.1.0-beta.3"))
    }

    @Test
    fun `a beta client moves on to a release through its latest manifest`() {
        api.releases =
            listOf(
                FakeRelease("v1.1.0", assets = mapOf("latest-linux.yml" to "release")),
                FakeRelease("v1.1.0-beta.2", prerelease = true, assets = mapOf("beta-linux.yml" to "beta")),
            )

        assertEquals(api.assetUrl("v1.1.0", "latest-linux.yml"), resolve("beta", current = "1.1.0-beta.2"))
    }

    @Test
    fun `a release that publishes the beta manifest keeps it`() {
        api.releases =
            listOf(FakeRelease("v1.1.0", assets = mapOf("latest-linux.yml" to "latest", "beta-linux.yml" to "beta")))

        assertEquals(api.assetUrl("v1.1.0", "beta-linux.yml"), resolve("beta"))
    }

    @Test
    fun `a tag that is not a version is on no channel`() {
        // `release-beta.9` reads as channel "beta" by its suffix, but it is no SemVer version.
        api.releases =
            listOf(
                FakeRelease("release-beta.9", prerelease = true, assets = mapOf("beta.yml" to "not a version")),
                FakeRelease("v3.0.0-beta.2", prerelease = true, assets = mapOf("beta.yml" to "this one")),
            )

        val url = resolve("beta", platform = Platform.Windows)

        assertEquals(api.assetUrl("v3.0.0-beta.2", "beta.yml"), url)
    }

    @Test
    fun `download urls are the asset urls of the resolved release`() {
        api.releases =
            listOf(
                FakeRelease(
                    "v1.5.0",
                    assets = mapOf("latest-mac.yml" to "m", "App-1.5.0.dmg" to "d", "App-1.5.0.zip" to "z"),
                ),
            )
        val provider = newProvider()
        val manifest = resolve("latest", platform = Platform.MacOS, provider = provider)

        assertEquals(
            api.assetUrl("v1.5.0", "App-1.5.0.dmg"),
            provider.getDownloadUrl("App-1.5.0.dmg", "1.5.0", manifest),
        )
        // The asset name is the file's last path segment, spaces as dashes, as electron-builder uploads it.
        assertEquals(
            api.assetUrl("v1.5.0", "App-1.5.0.zip"),
            provider.getDownloadUrl("App 1.5.0.zip", "1.5.0", manifest),
        )
        assertEquals(
            api.assetUrl("v1.5.0", "App-1.5.0.zip"),
            provider.getDownloadUrl("https://cdn.example.com/x/App-1.5.0.zip", "1.5.0", manifest),
        )
        val missing =
            assertThrows(
                NoSuchElementException::class.java,
            ) { provider.getDownloadUrl("App-1.5.0.exe", "1.5.0", manifest) }
        assertTrue(missing.message!!.contains("has no App-1.5.0.exe asset"))
    }

    @Test
    fun `download urls need the manifest they come from`() {
        val provider = newProvider()
        assertThrows(UnsupportedOperationException::class.java) { provider.getDownloadUrl("App-1.5.0.dmg", "1.5.0") }
        assertThrows(IllegalStateException::class.java) {
            provider.getDownloadUrl("App-1.5.0.dmg", "1.5.0", "http://unknown/latest-mac.yml")
        }
    }

    @Test
    fun `two channels resolved on one provider keep their own releases`() {
        api.releases =
            listOf(
                FakeRelease(
                    "v2.0.0-beta.1",
                    prerelease = true,
                    assets =
                        mapOf(
                            "beta-linux.yml" to "b",
                            "App-2.0.0-beta.1.zip" to "z",
                        ),
                ),
                FakeRelease("v1.5.0", assets = mapOf("latest-linux.yml" to "l", "App-1.5.0.zip" to "z")),
            )
        val provider = newProvider()
        val beta = resolve("beta", provider = provider)
        val stable = resolve("latest", provider = provider)

        assertEquals(
            api.assetUrl("v2.0.0-beta.1", "App-2.0.0-beta.1.zip"),
            provider.getDownloadUrl("App-2.0.0-beta.1.zip", "2.0.0-beta.1", beta),
        )
        assertEquals(api.assetUrl("v1.5.0", "App-1.5.0.zip"), provider.getDownloadUrl("App-1.5.0.zip", "1.5.0", stable))
    }

    @Test
    fun `block maps and signatures are the companion assets`() {
        api.releases =
            listOf(
                FakeRelease(
                    "v1.5.0",
                    assets =
                        mapOf(
                            "latest-linux.yml" to "m",
                            "App-1.5.0.deb" to "d",
                            "App-1.5.0.deb.blockmap" to "b",
                            "App-1.5.0.deb.asc" to "s",
                            "App-1.5.0.AppImage" to "a",
                        ),
                ),
            )
        val provider = newProvider()
        val manifest = resolve("latest", provider = provider)
        val deb = provider.getDownloadUrl("App-1.5.0.deb", "1.5.0", manifest)
        val appImage = provider.getDownloadUrl("App-1.5.0.AppImage", "1.5.0", manifest)

        assertEquals(api.assetUrl("v1.5.0", "App-1.5.0.deb.blockmap"), provider.getBlockMapUrl(deb))
        assertEquals(api.assetUrl("v1.5.0", "App-1.5.0.deb.asc"), provider.getSignatureUrl(deb))
        // Not published: a URL that 404s.
        assertEquals("$appImage.blockmap", provider.getBlockMapUrl(appImage))
        assertEquals("$appImage.asc", provider.getSignatureUrl(appImage))
    }

    @Test
    fun `a release without the manifest asset is reported`() {
        api.releases = listOf(FakeRelease("v1.5.0", assets = mapOf("latest.yml" to "windows only")))

        val e =
            assertThrows(NoSuchElementException::class.java) {
                newProvider().resolveMetadataUrl("latest", Platform.Linux, httpClient)
            }
        assertTrue(e.message!!, e.message!!.contains("Release v1.5.0 of acme/tool has no latest-linux.yml asset"))
    }

    @Test
    fun `no release on the channel is reported`() {
        // A custom channel follows only its own tags, never a release.
        api.releases = listOf(FakeRelease("v1.0.0-alpha.1", prerelease = true), FakeRelease("v1.0.0"))

        val e = assertThrows(NoSuchElementException::class.java) { resolve("rc") }
        assertTrue(e.message!!, e.message!!.contains("No release found for channel 'rc'"))
    }

    @Test
    fun `API failures name their cause`() {
        fun failure(
            status: Int,
            rateLimited: Boolean = false,
        ): String {
            api.failWith = status
            api.rateLimited = rateLimited
            return assertThrows(NetworkException::class.java) {
                newProvider().resolveMetadataUrl("latest", Platform.Linux, httpClient)
            }.message!!
        }

        assertTrue(failure(401).contains("the token was rejected (HTTP 401)"))
        assertTrue(failure(404).contains("the token cannot read it"))
        assertTrue(failure(403, rateLimited = true).contains("rate limit exceeded"))
        assertTrue(failure(403).contains("HTTP 403"))
        assertTrue(failure(500).contains("acme/tool"))
    }

    @Test
    fun `the API base follows the host`() {
        assertEquals("https://api.github.com", PrivateGitHubProvider("acme", "tool", TOKEN).apiBaseUrl)
        assertEquals(
            "https://github.example.com:8443/api/v3",
            PrivateGitHubProvider("acme", "tool", TOKEN, host = "github.example.com:8443").apiBaseUrl,
        )
    }

    @Test
    fun `asset requests carry the token and ask for the bytes`() {
        assertEquals(
            mapOf("Authorization" to "Bearer $TOKEN", "Accept" to "application/octet-stream"),
            newProvider().authHeaders(),
        )
    }

    @Test
    fun `token and host are validated`() {
        assertThrows(IllegalArgumentException::class.java) { PrivateGitHubProvider("acme", "tool", " ") }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateGitHubProvider("acme", "tool", TOKEN, host = "https://github.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateGitHubProvider("acme", "tool", TOKEN, host = "github.example.com", protocol = "http")
        }
    }

    @Test
    fun `an update downloads differentially through the API without leaking the token to storage`() {
        DeltaFixtures.verify()
        val cacheDir = tmp.newFolder("update-cache")
        val previous = File(tmp.newFolder(), "MyApp-1.0.0.zip").apply { writeBytes(DeltaFixtures.v1()) }
        UpdateCache(
            cacheDir,
        ).store(previous, previous.name, version = "1.0.0", blockMapGzip = DeltaFixtures.blockMapGzip("v1"))

        val artifact = DeltaFixtures.v2()
        val sha512 = DeltaFixtures.sha512Base64(artifact)
        val manifest =
            """
            version: 2.0.0
            files:
              - url: MyApp-2.0.0.zip
                sha512: $sha512
                size: ${artifact.size}
            path: MyApp-2.0.0.zip
            sha512: $sha512
            releaseDate: '2026-01-01T00:00:00.000Z'
            """.trimIndent()
        api.releases =
            listOf(
                FakeRelease(
                    "v2.0.0",
                    binaryAssets =
                        mapOf(
                            GitHubReleases.metadataFileName("latest", PlatformInfo.currentPlatform()) to
                                manifest.toByteArray(),
                            "MyApp-2.0.0.zip" to artifact,
                            "MyApp-2.0.0.zip.blockmap" to DeltaFixtures.blockMapGzip("v2"),
                            "MyApp-2.0.0.zip.asc" to "signature".toByteArray(),
                        ),
                ),
            )

        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = newProvider()
                executableType = "zip"
                this.cacheDir = cacheDir
            }
        val progress =
            runBlocking {
                val result = updater.checkForUpdates()
                assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
                updater.downloadUpdate((result as UpdateResult.Available).info).toList()
            }.last()
        val file = requireNotNull(progress.file)
        try {
            assertTrue("the update must be differential", progress.isDifferential)
            assertEquals(DeltaFixtures.EXPECTED_DELTA_BYTES, progress.bytesDownloaded)
            assertTrue("the assembled artifact must be byte-identical", artifact.contentEquals(file.readBytes()))
            assertEquals("signature", File(file.parentFile, "${file.name}.asc").readText())

            val assetRequests = api.requests.filter { it.path.contains("/releases/assets/") }
            // Manifest, block map, signature, and the artifact once: its later ranges go straight to storage.
            assertEquals("asset requests: $assetRequests", 4, assetRequests.size)
            assertTrue(assetRequests.all { it.authorization == "Bearer $TOKEN" })
            assertTrue(assetRequests.all { it.accept == "application/octet-stream" })
            assertTrue("ranged requests must reach storage", storage.requests.any { it.contains("bytes=") })
            assertEquals("the token must not reach storage", emptyList<String>(), storage.authorizations)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    private class FakeRelease(
        val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        assets: Map<String, String> = emptyMap(),
        binaryAssets: Map<String, ByteArray> = emptyMap(),
    ) {
        val assets: Map<String, ByteArray> = assets.mapValues { it.value.toByteArray() } + binaryAssets
    }

    private class CapturedRequest(
        val pathAndQuery: String,
        val authorization: String?,
        val accept: String?,
    ) {
        val path: String get() = pathAndQuery.substringBefore('?')

        override fun toString(): String = pathAndQuery
    }

    /** The REST API endpoints the provider reads. Assets redirect to [storage] on another host name, like GitHub's. */
    private class FakeGitHubApi(
        private val storage: RangeHttpServer,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private val nextAssetId = AtomicInteger(1)
        private val assetIds = mutableMapOf<Pair<String, String>, Int>()
        private val assetsById = mutableMapOf<Int, Pair<String, String>>()

        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val host: String get() = "127.0.0.1:${server.address.port}"

        @Volatile
        var failWith: Int? = null

        @Volatile
        var rateLimited: Boolean = false

        @Volatile
        var releases: List<FakeRelease> = emptyList()
            set(value) {
                field = value
                for (release in value) {
                    for ((name, bytes) in release.assets) {
                        val id = nextAssetId.getAndIncrement()
                        assetIds[release.tag to name] = id
                        assetsById[id] = release.tag to name
                        storage.put("/${release.tag}/$name", bytes)
                    }
                }
            }

        init {
            server.createContext("/") { exchange -> exchange.use(::handle) }
            server.start()
        }

        fun assetUrl(
            tag: String,
            name: String,
        ): String = "http://$host$ASSETS_PATH${assetIds.getValue(tag to name)}"

        override fun close() {
            server.stop(0)
        }

        private fun handle(exchange: HttpExchange) {
            val uri = exchange.requestURI
            requests +=
                CapturedRequest(
                    pathAndQuery = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: ""),
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    accept = exchange.requestHeaders.getFirst("Accept"),
                )
            failWith?.let { status ->
                if (rateLimited) exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
                exchange.sendResponseHeaders(status, -1)
                return
            }
            val path = uri.rawPath
            when {
                path == "$RELEASES_PATH/latest" -> {
                    val latest = releases.firstOrNull { !it.draft && !it.prerelease }
                    if (latest ==
                        null
                    ) {
                        exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                    } else {
                        respondJson(exchange, json(latest))
                    }
                }
                path == RELEASES_PATH ->
                    respondJson(exchange, releases.joinToString(prefix = "[", postfix = "]") { json(it) })
                path.startsWith(ASSETS_PATH) -> redirectToStorage(exchange, path.removePrefix(ASSETS_PATH).toInt())
                else -> exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            }
        }

        private fun redirectToStorage(
            exchange: HttpExchange,
            id: Int,
        ) {
            val (tag, name) = assetsById[id] ?: return exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            if (exchange.requestHeaders.getFirst("Accept") != "application/octet-stream") {
                // Like GitHub: JSON unless octet-stream is asked for.
                respondJson(exchange, """{"id":$id,"name":"$name"}""")
                return
            }
            val storagePort = storage.baseUrl.substringAfterLast(':')
            exchange.responseHeaders.add("Location", "http://localhost:$storagePort/$tag/$name")
            exchange.sendResponseHeaders(HTTP_FOUND, -1)
        }

        private fun json(release: FakeRelease): String =
            release.assets.keys.joinToString(
                prefix =
                    """{"tag_name":"${release.tag}","draft":${release.draft},""" +
                        """"prerelease":${release.prerelease},"name":"ignored","assets":[""",
                postfix = "]}",
            ) { name ->
                """{"name":"$name","url":"${assetUrl(release.tag, name)}","browser_download_url":"unused"}"""
            }

        private fun respondJson(
            exchange: HttpExchange,
            body: String,
        ) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
            try {
                block(this)
            } finally {
                close()
            }
        }

        private companion object {
            const val RELEASES_PATH = "/api/v3/repos/acme/tool/releases"
            const val ASSETS_PATH = "$RELEASES_PATH/assets/"
            const val HTTP_OK = 200
            const val HTTP_FOUND = 302
            const val HTTP_NOT_FOUND = 404
        }
    }

    private companion object {
        const val TOKEN = "ghp_test"
    }
}
