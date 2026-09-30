package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.internal.delta.DeltaDownload
import dev.nucleusframework.updater.internal.delta.DeltaPlan
import dev.nucleusframework.updater.internal.delta.DeltaUnavailableException
import dev.nucleusframework.updater.internal.delta.DifferentialDownloader
import dev.nucleusframework.updater.internal.delta.Operation
import dev.nucleusframework.updater.internal.delta.OperationKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.http.HttpClient

/**
 * How a plan is executed against a release host: ranges in parallel, transient failures retried,
 * redirects resolved once, and every unrecoverable failure turned into a failed delta with no
 * partial file left behind.
 *
 * The plan alternates downloads and copies across the fixture artifact, so it has many ranges; the
 * "old" file is the artifact itself, so copies read the same offsets they fill.
 */
class DifferentialDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: RangeHttpServer
    private val artifact = DeltaFixtures.v2()
    private val httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    @Before
    fun setUp() {
        server = RangeHttpServer()
        server.put(PATH, artifact)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `ranges download concurrently and assemble the artifact`() {
        server.rangeLatencyMs = 150
        val request = request()

        val transferred = download(request)

        assertTrue("the artifact must be byte-identical", artifact.contentEquals(request.target.readBytes()))
        assertEquals(DeltaPlan.downloadSize(request.operations), transferred)
        assertTrue("ranges must overlap, max in flight was ${server.maxInFlight.get()}", server.maxInFlight.get() > 1)
    }

    @Test
    fun `progress is reported in order and ends at the planned size`() {
        val request = request()
        val reports = mutableListOf<Pair<Long, Long>>()

        runBlocking { downloader().download(request) { done, total -> reports += done to total } }

        val total = DeltaPlan.downloadSize(request.operations)
        assertTrue("progress must be reported", reports.isNotEmpty())
        assertTrue("progress must never move backwards", reports.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue("every report carries the planned total", reports.all { it.second == total })
        assertEquals(total, reports.last().first)
    }

    @Test
    fun `transient server errors are retried`() {
        // Two failures in a row for the first range: the third and last attempt succeeds.
        server.failNext(PATH, status = 503, times = 1, retryAfter = "0")
        server.failNext(PATH, status = 429, times = 1, retryAfter = "0")
        val request = request()

        download(request)

        assertTrue(artifact.contentEquals(request.target.readBytes()))
    }

    @Test
    fun `a range that keeps failing aborts the delta and leaves no partial file`() {
        server.failNext(PATH, status = 500, times = 100, retryAfter = "0")
        val request = request()

        assertThrows(DeltaUnavailableException::class.java) { download(request) }
        assertFalse("a partly assembled artifact must not be left behind", request.target.exists())
        assertEquals("three attempts, then give up", 3, server.requests.count { it.startsWith("GET $PATH") })
    }

    @Test
    fun `a host that ignores Range aborts the delta without retrying`() {
        server.close()
        server = RangeHttpServer(honorRanges = false)
        server.put(PATH, artifact)
        val request = request()

        assertThrows(DeltaUnavailableException::class.java) { download(request) }
        assertEquals(1, server.requests.size)
        assertFalse(request.target.exists())
    }

    @Test
    fun `a client error other than an expired redirect aborts the delta without retrying`() {
        server.failNext(PATH, status = 404, times = 1)
        val request = request()

        assertThrows(DeltaUnavailableException::class.java) { download(request) }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `ranges go straight to the resolved redirect target without the original host's credentials`() {
        // A different host name for the same loopback server, like a release host redirecting to its CDN.
        val cdn = server.baseUrl.replace("127.0.0.1", "localhost") + CDN_PATH
        server.redirect(PATH, cdn)
        server.put(CDN_PATH, artifact)
        val request = request()

        download(request, authHeaders = mapOf("Authorization" to "token secret"))

        assertTrue(artifact.contentEquals(request.target.readBytes()))
        assertEquals(
            "only the first range may go through the redirect: ${server.requests}",
            1,
            server.requests.count { it.startsWith("GET $PATH") },
        )
        val downloads = request.operations.count { it.kind == OperationKind.DOWNLOAD }
        assertEquals(downloads, server.requests.count { it.startsWith("GET $CDN_PATH") })
        assertTrue(
            "credentials must not be sent to the CDN directly: ${server.authorizedRequests}",
            server.authorizedRequests.count { it == CDN_PATH } <= 1,
        )
    }

    @Test
    fun `an expired redirect target is re-resolved through the original URL`() {
        val cdnHost = server.baseUrl.replace("127.0.0.1", "localhost")
        // The first pre-signed URL stops working after one range; asking again yields a fresh one.
        server.redirect(PATH, cdnHost + CDN_PATH, cdnHost + FRESH_CDN_PATH)
        server.put(CDN_PATH, artifact)
        server.put(FRESH_CDN_PATH, artifact)
        server.failNext(CDN_PATH, status = 403, times = 1000, after = 1)
        val request = request()

        download(request)

        assertTrue(artifact.contentEquals(request.target.readBytes()))
        assertTrue(
            "the original URL must be asked for a fresh target: ${server.requests}",
            server.requests.count { it.startsWith("GET $PATH") } >= 2,
        )
        assertTrue(
            "the remaining ranges must come from the fresh target: ${server.requests}",
            server.requests.any { it.startsWith("GET $FRESH_CDN_PATH") },
        )
    }

    private fun downloader(authHeaders: Map<String, String> = emptyMap()) =
        DifferentialDownloader(httpClient, authHeaders)

    private fun download(
        request: DeltaDownload,
        authHeaders: Map<String, String> = emptyMap(),
    ): Long = runBlocking { downloader(authHeaders).download(request) { _, _ -> } }

    /** Alternating 32 KiB downloads and copies over the whole artifact. */
    private fun request(): DeltaDownload {
        val oldFile = File(tmp.newFolder(), "old.bin").apply { writeBytes(artifact) }
        val operations =
            (0 until artifact.size step CHUNK).mapIndexed { index, start ->
                val kind = if (index % 2 == 0) OperationKind.DOWNLOAD else OperationKind.COPY
                Operation(kind, start.toLong(), minOf(start + CHUNK, artifact.size).toLong())
            }
        return DeltaDownload(
            url = server.baseUrl + PATH,
            oldFile = oldFile,
            target = File(tmp.newFolder(), "new.bin"),
            operations = operations,
            expectedSize = artifact.size.toLong(),
            expectedSha512 = DeltaFixtures.sha512Base64(artifact),
        )
    }

    private companion object {
        const val PATH = "/App.zip"
        const val CDN_PATH = "/cdn/App.zip"
        const val FRESH_CDN_PATH = "/cdn/fresh/App.zip"
        const val CHUNK = 32 * 1024
    }
}
