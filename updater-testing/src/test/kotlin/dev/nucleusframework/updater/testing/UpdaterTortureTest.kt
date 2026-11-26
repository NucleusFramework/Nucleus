package dev.nucleusframework.updater.testing

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.exception.UpdateException
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.LocalFileProvider
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the real [NucleusUpdater] against an [UpdateFeedServer] that misbehaves the ways release
 * hosts, proxies and networks do. Every case checks the two invariants an update must keep whatever
 * happens: a download that completes is byte-identical to the published artifact, and a download
 * that fails leaves nothing behind — no staged file an app could install.
 */
class UpdaterTortureTest {
    private lateinit var feed: UpdateFeedServer
    private lateinit var work: File
    private val artifactName = artifactNameForThisOs("2.0.0")
    private lateinit var artifact: File
    private lateinit var stagingBefore: Set<File>

    @Before
    fun setUp() {
        stagingBefore = stagingDirs()
        feed = UpdateFeedServer()
        work = Files.createTempDirectory("nucleus-torture-").toFile()
        artifact = File(work, artifactName).apply { writeBytes(Random(42).nextBytes(ARTIFACT_SIZE)) }
        feed.publish("2.0.0", listOf(artifact), platform = Platform.Current)
    }

    @After
    fun tearDown() {
        feed.close()
        work.deleteRecursively()
    }

    private fun updater(
        provider: UpdateProvider = GenericProvider(feed.baseUrl),
        currentVersion: String = "1.0.0",
    ): NucleusUpdater =
        NucleusUpdater {
            this.currentVersion = currentVersion
            // An installed build: the self-updatable format of each OS, no dev-mode short-circuit.
            executableType = packagedTypeForThisOs()
            this.provider = provider
            differentialDownload = false
            cacheDir = File(work, "cache")
        }

    private fun available(updater: NucleusUpdater): UpdateInfo {
        val result = runBlocking { updater.checkForUpdates() }
        assertTrue("expected an update, got $result", result is UpdateResult.Available)
        return (result as UpdateResult.Available).info
    }

    private fun assertDownloadsIntact(
        updater: NucleusUpdater,
        info: UpdateInfo = available(updater),
    ): List<DownloadProgress> {
        val progress = runBlocking { withTimeout(60.seconds) { updater.downloadUpdate(info).toList() } }
        val file = progress.last().file
        assertNotNull("the last progress report carries the file", file)
        assertArrayEquals("the downloaded artifact is byte-identical", artifact.readBytes(), file!!.readBytes())
        file.parentFile.deleteRecursively()
        return progress
    }

    private fun assertDownloadFails(
        updater: NucleusUpdater,
        info: UpdateInfo,
        expected: Class<out UpdateException>,
    ) {
        val staged = mutableListOf<File>()
        try {
            runBlocking {
                withTimeout(60.seconds) {
                    updater.downloadUpdate(info).onEach { p -> p.file?.let(staged::add) }.collect()
                }
            }
            staged.forEach { it.parentFile.deleteRecursively() }
            fail("the download must fail")
        } catch (e: UpdateException) {
            assertTrue("expected ${expected.simpleName}, got $e", expected.isInstance(e))
        }
        assertTrue("a failed download hands over no file", staged.isEmpty())
        assertNoStagingLeft()
    }

    /** Download staging dirs (`nucleus-update-*` in the temp dir) this test created and left behind. */
    private fun stagingDirs(): Set<File> =
        File(System.getProperty("java.io.tmpdir"))
            .listFiles { f ->
                f.isDirectory &&
                    f.name.startsWith("nucleus-update-") &&
                    !f.name.startsWith("nucleus-update-feed-")
            }.orEmpty()
            .toSet()

    private fun assertNoStagingLeft() {
        // Other test JVMs stage downloads in the same temp dir: only this test's artifact counts.
        val leftovers =
            (stagingDirs() - stagingBefore).filter { dir -> dir.list().orEmpty().any { it.startsWith("TortureApp-") } }
        assertTrue("staging left behind: ${leftovers.map { "$it ${it.list()?.toList()}" }}", leftovers.isEmpty())
    }

    @Test
    fun `a healthy feed updates byte for byte with monotonic progress`() {
        val progress = assertDownloadsIntact(updater())
        val percents = progress.map { it.percent }
        assertEquals(percents.sorted(), percents)
        assertEquals(100.0, percents.last(), 0.0)
    }

    @Test
    fun `the running version or a newer one is not offered`() {
        runBlocking {
            assertEquals(UpdateResult.NotAvailable, updater(currentVersion = "2.0.0").checkForUpdates())
            assertEquals(UpdateResult.NotAvailable, updater(currentVersion = "3.1.0").checkForUpdates())
        }
    }

    @Test
    fun `a server error on the manifest is an error result, not an exception`() {
        feed.fault(FeedFault.Status(503), path = "*.yml")
        val result = runBlocking { updater().checkForUpdates() }
        assertTrue("got $result", result is UpdateResult.Error)
    }

    @Test
    fun `a missing manifest is an error result`() {
        File(feed.directory, UpdateFeedServer.manifestName("latest")).delete()
        val result = runBlocking { updater().checkForUpdates() }
        assertTrue("got $result", result is UpdateResult.Error)
    }

    @Test
    fun `a garbage manifest is an error result`() {
        File(feed.directory, UpdateFeedServer.manifestName("latest")).writeBytes(Random(7).nextBytes(4096))
        val result = runBlocking { updater().checkForUpdates() }
        assertTrue("got $result", result is UpdateResult.Error || result is UpdateResult.NotAvailable)
    }

    @Test
    fun `an artifact that went missing after the check fails cleanly`() {
        val updater = updater()
        val info = available(updater)
        feed.fault(FeedFault.Status(404), path = artifactName)
        assertDownloadFails(updater, info, NetworkException::class.java)
    }

    @Test
    fun `a connection cut part-way fails cleanly`() {
        val updater = updater()
        val info = available(updater)
        feed.fault(FeedFault.Truncate(afterBytes = ARTIFACT_SIZE / 3L), path = artifactName)
        assertDownloadFails(updater, info, UpdateException::class.java)
    }

    @Test
    fun `a corrupted byte is caught by the SHA-512 check`() {
        val updater = updater()
        val info = available(updater)
        feed.fault(FeedFault.Corrupt(offset = ARTIFACT_SIZE / 2L), path = artifactName)
        assertDownloadFails(updater, info, ChecksumException::class.java)
    }

    @Test
    fun `an artifact replaced between check and download fails the checksum`() {
        val updater = updater()
        val info = available(updater)
        File(feed.directory, artifactName).writeBytes(Random(99).nextBytes(ARTIFACT_SIZE))
        assertDownloadFails(updater, info, ChecksumException::class.java)
    }

    @Test
    fun `a transient failure does not poison the next attempt`() {
        val updater = updater()
        val info = available(updater)
        feed.fault(FeedFault.Truncate(afterBytes = 1000), path = artifactName, times = 1)
        assertDownloadFails(updater, info, UpdateException::class.java)
        assertDownloadsIntact(updater, info)
    }

    @Test
    fun `a throttled link reports many progress steps and still completes`() {
        feed.fault(FeedFault.Throttle(bytesPerSecond = ARTIFACT_SIZE * 2L), path = artifactName)
        val progress = assertDownloadsIntact(updater())
        assertTrue("a slow link reports progress along the way, got ${progress.size}", progress.size > 5)
    }

    @Test
    fun `cancelling a slow download leaves nothing staged`() {
        val updater = updater()
        val info = available(updater)
        feed.fault(FeedFault.Throttle(bytesPerSecond = 64 * 1024L), path = artifactName)
        val seen = mutableListOf<DownloadProgress>()
        val finished =
            runBlocking {
                withTimeoutOrNull(700.milliseconds) { updater.downloadUpdate(info).collect { seen += it } }
            }
        assertEquals("the download was cancelled mid-way", null, finished)
        assertTrue("it had started", seen.isNotEmpty())
        assertTrue("no file handed over", seen.none { it.file != null })
        // The staging directory is removed as the cancellation unwinds.
        Thread.sleep(300)
        assertNoStagingLeft()
    }

    @Test
    fun `a slow host is waited for`() {
        feed.fault(FeedFault.Delay(1.seconds))
        assertDownloadsIntact(updater())
    }

    @Test
    fun `parallel checks and downloads each get an intact private copy`() {
        feed.fault(FeedFault.Throttle(bytesPerSecond = ARTIFACT_SIZE * 4L), path = artifactName)
        val updaters = List(PARALLEL) { updater() }
        val files =
            runBlocking {
                updaters
                    .map { u ->
                        async(kotlinx.coroutines.Dispatchers.IO) {
                            val info = (u.checkForUpdates() as UpdateResult.Available).info
                            u.downloadUpdate(info).last().file!!
                        }
                    }.awaitAll()
            }
        assertEquals("every download is staged privately", PARALLEL, files.map { it.absolutePath }.toSet().size)
        files.forEach { assertArrayEquals(artifact.readBytes(), it.readBytes()) }
        files.forEach { it.parentFile.deleteRecursively() }
    }

    @Test
    fun `a newer release published while running is picked up by the next check`() {
        val updater = updater(currentVersion = "2.0.0")
        runBlocking { assertEquals(UpdateResult.NotAvailable, updater.checkForUpdates()) }
        val next = File(work, artifactNameForThisOs("2.1.0")).apply { writeBytes(Random(3).nextBytes(1024)) }
        feed.publish("2.1.0", next)
        val result = runBlocking { updater.checkForUpdates() }
        assertEquals("2.1.0", (result as UpdateResult.Available).info.version)
    }

    @Test
    fun `a local directory feed updates through the same path`() {
        val dir = File(work, "feed dir with spaces ünïcødé").apply { mkdirs() }
        feed.directory.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        assertDownloadsIntact(updater(provider = LocalFileProvider(dir)))
    }

    @Test
    fun `a local feed with a missing artifact fails cleanly`() {
        val dir = File(work, "local").apply { mkdirs() }
        feed.directory.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        val updater = updater(provider = LocalFileProvider(dir))
        val info = available(updater)
        File(dir, artifactName).delete()
        assertDownloadFails(updater, info, NetworkException::class.java)
    }

    @Test
    fun `a local manifest pointing outside its directory is refused`() {
        val dir = File(work, "evil").apply { mkdirs() }
        File(dir, UpdateFeedServer.manifestName("latest")).writeText(
            "version: 9.0.0\nfiles:\n  - url: ../../outside.exe\n    sha512: AAAA\n    size: 1\n",
        )
        val result = runBlocking { updater(provider = LocalFileProvider(dir)).checkForUpdates() }
        assertTrue("got $result", result is UpdateResult.Error)
    }

    @Test
    fun `the server honours single byte ranges`() {
        val client =
            java.net.http.HttpClient
                .newHttpClient()
        val response =
            client.send(
                java.net.http.HttpRequest
                    .newBuilder(java.net.URI("${feed.baseUrl}/$artifactName"))
                    .header("Range", "bytes=10-19")
                    .build(),
                java.net.http.HttpResponse.BodyHandlers
                    .ofByteArray(),
            )
        assertEquals(206, response.statusCode())
        assertArrayEquals(artifact.readBytes().copyOfRange(10, 20), response.body())
        feed.fault(FeedFault.IgnoreRange)
        val ignored =
            client.send(
                java.net.http.HttpRequest
                    .newBuilder(java.net.URI("${feed.baseUrl}/$artifactName"))
                    .header("Range", "bytes=10-19")
                    .build(),
                java.net.http.HttpResponse.BodyHandlers
                    .ofByteArray(),
            )
        assertEquals(200, ignored.statusCode())
        assertEquals(ARTIFACT_SIZE, ignored.body().size)
    }

    @Test
    fun `the server refuses to serve outside its directory`() {
        File(work, "secret.txt").writeText("secret")
        val client =
            java.net.http.HttpClient
                .newHttpClient()
        val response =
            client.send(
                java.net.http.HttpRequest
                    .newBuilder(java.net.URI("${feed.baseUrl}/..%2Fsecret.txt"))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers
                    .ofString(),
            )
        assertEquals(404, response.statusCode())
        assertFalse(response.body().contains("secret"))
    }

    private companion object {
        const val ARTIFACT_SIZE = 3 * 1024 * 1024 + 17
        const val PARALLEL = 6

        fun artifactNameForThisOs(version: String): String =
            when (Platform.Current) {
                Platform.Windows -> "TortureApp-$version-win-x64-nsis.exe"
                Platform.MacOS -> "TortureApp-$version-mac-arm64.zip"
                else -> "TortureApp-$version-linux-x86_64.AppImage"
            }

        fun packagedTypeForThisOs(): String =
            when (Platform.Current) {
                Platform.Windows -> "nsis"
                Platform.MacOS -> "zip"
                else -> "appimage"
            }
    }
}
