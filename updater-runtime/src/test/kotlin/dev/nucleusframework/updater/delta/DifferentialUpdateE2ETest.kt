package dev.nucleusframework.updater.delta

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.internal.delta.UpdateCache
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.flow.toList
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

/**
 * Drives the whole update path — manifest, block maps, ranged requests, checksum, cache — against a
 * loopback release host, using block maps a real electron-builder produced.
 *
 * The point of each test is not only that the resulting file is correct, but *how many bytes crossed
 * the wire to produce it*: the server counts them, so a delta that silently degraded to a full
 * download fails the test instead of passing it.
 */
class DifferentialUpdateE2ETest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: RangeHttpServer
    private lateinit var cacheDir: File
    private val downloaded = mutableListOf<File>()

    @Before
    fun setUp() {
        DeltaFixtures.verify()
        server = RangeHttpServer()
        cacheDir = tmp.newFolder("update-cache")
    }

    @After
    fun tearDown() {
        server.close()
        // downloadUpdate() stages artifacts in a private per-download temp directory; drop it whole.
        downloaded.forEach { file -> file.parentFile?.deleteRecursively() }
    }

    @Test
    fun `the update after a first one fetches only the blocks that changed`() {
        // A user installs 1.0.0 through the updater: nothing is cached yet, so it is a full download.
        publish(version = "1.0.0", fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        val first = download(currentVersion = "0.9.0")

        assertFalse("the first update cannot be differential", first.isDifferential)
        assertEquals(DeltaFixtures.V1_SIZE.toLong(), first.bytesDownloaded)
        assertTrue("the artifact must be cached for the next update", UpdateCache(cacheDir).artifact.isFile)
        assertTrue("its block map too", UpdateCache(cacheDir).blockMap.isFile)

        // 2.0.0 ships: 9000 bytes were inserted near the start, shifting everything after them.
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2())
        server.resetCounters()
        val second = download(currentVersion = "1.0.0")

        assertTrue("the second update must be differential", second.isDifferential)
        assertEquals(DeltaFixtures.EXPECTED_DELTA_BYTES, second.bytesDownloaded)
        assertArtifactIs(DeltaFixtures.v2(), second)
        assertTrue(
            "the host must have sent a fraction of the artifact, not all of it " +
                "(${server.bytesServed.get()} bytes)",
            server.bytesServed.get() < DeltaFixtures.V2_SIZE / 10,
        )
        assertTrue(
            "the changed range must have been fetched with a ranged request",
            server.requests.any { it.contains("bytes=") },
        )
    }

    @Test
    fun `an artifact carrying its own block map updates from the copy on disk`() {
        // The AppImage layout: the block map is appended to the artifact instead of published beside
        // it, so the updater reads the new one with a single ranged request on the artifact's tail.
        val old = DeltaFixtures.withEmbeddedBlockMap(DeltaFixtures.v1(), "v1")
        val new = DeltaFixtures.withEmbeddedBlockMap(DeltaFixtures.v2(), "v2")
        primeCache(fileName = "MyApp-1.0.0.AppImage", artifact = old, blockMapGzip = null)
        publish(
            version = "2.0.0",
            fileName = "MyApp-2.0.0.AppImage",
            artifact = new,
            blockMapGzip = null,
            blockMapSize = DeltaFixtures.embeddedBlockMapSize("v2"),
        )

        val progress = download(currentVersion = "1.0.0", format = "appimage")

        assertTrue(progress.isDifferential)
        assertArtifactIs(new, progress)
        assertTrue(
            "only the changed blocks and the trailer may be fetched (${server.bytesServed.get()} bytes)",
            server.bytesServed.get() < new.size / 10,
        )
    }

    @Test
    fun `a host that ignores range requests falls back to a full download`() {
        server.close()
        server = RangeHttpServer(honorRanges = false)
        primeCache(fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2())

        val progress = download(currentVersion = "1.0.0")

        assertFalse("a host without range support cannot serve a delta", progress.isDifferential)
        assertEquals(DeltaFixtures.V2_SIZE.toLong(), progress.bytesDownloaded)
        assertArtifactIs(DeltaFixtures.v2(), progress)
    }

    @Test
    fun `a release that publishes no block map falls back to a full download`() {
        primeCache(fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2(), blockMapGzip = null)

        val progress = download(currentVersion = "1.0.0")

        assertFalse(progress.isDifferential)
        assertArtifactIs(DeltaFixtures.v2(), progress)
    }

    @Test
    fun `a corrupt cached block map falls back to a full download`() {
        primeCache(fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        UpdateCache(cacheDir).blockMap.writeText("this is not a block map")
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2())

        val progress = download(currentVersion = "1.0.0")

        assertFalse(progress.isDifferential)
        assertArtifactIs(DeltaFixtures.v2(), progress)
    }

    @Test
    fun `a cached artifact of another format is not reused`() {
        primeCache(fileName = "MyApp-1.0.0.msi", artifact = DeltaFixtures.v1())
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2())

        val progress = download(currentVersion = "1.0.0")

        assertFalse("an MSI is no basis for assembling a ZIP", progress.isDifferential)
        assertArtifactIs(DeltaFixtures.v2(), progress)
    }

    @Test
    fun `disabling differential downloads keeps the full download path`() {
        primeCache(fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2())

        val progress = download(currentVersion = "1.0.0", differential = false)

        assertFalse(progress.isDifferential)
        assertEquals(DeltaFixtures.V2_SIZE.toLong(), progress.bytesDownloaded)
        assertTrue(
            "no block map may be requested at all",
            server.requests.none { it.contains(".blockmap") },
        )
    }

    @Test
    fun `an artifact that does not match the manifest checksum is never installed`() {
        primeCache(fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        // A manifest whose checksum belongs to another build: the assembled file cannot match it, and
        // neither can the full download the updater falls back to.
        publish(
            version = "2.0.0",
            fileName = "MyApp-2.0.0.zip",
            artifact = DeltaFixtures.v2(),
            sha512 = DeltaFixtures.V1_SHA512,
        )

        val info = (checkForUpdates(currentVersion = "1.0.0") as UpdateResult.Available).info
        val updater = updater(currentVersion = "1.0.0")

        assertThrows(ChecksumException::class.java) {
            runBlocking { updater.downloadUpdate(info).toList() }
        }
        assertFalse(
            "the rejected artifact must not be left staged",
            File(System.getProperty("java.io.tmpdir"), "MyApp-2.0.0.zip").isFile,
        )
    }

    @Test
    fun `progress is reported over the bytes actually transferred`() {
        primeCache(fileName = "MyApp-1.0.0.zip", artifact = DeltaFixtures.v1())
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = DeltaFixtures.v2())

        val updates = downloadAll(currentVersion = "1.0.0")

        assertTrue("progress must be reported before completion", updates.size > 1)
        assertTrue("every event must be flagged differential", updates.all { it.isDifferential })
        assertEquals(
            "the total must be the delta size, not the artifact size",
            DeltaFixtures.EXPECTED_DELTA_BYTES,
            updates.first().totalBytes,
        )
        assertTrue(
            "progress must never go backwards",
            updates.zipWithNext().all { (previous, next) -> next.bytesDownloaded >= previous.bytesDownloaded },
        )
        assertEquals(PERCENT_MAX, updates.last().percent, 0.0)
    }

    private fun publish(
        version: String,
        fileName: String,
        artifact: ByteArray,
        blockMapGzip: ByteArray? = DeltaFixtures.blockMapGzip(fixtureOf(artifact)),
        blockMapSize: Long? = null,
        sha512: String = DeltaFixtures.sha512Base64(artifact),
    ) {
        server.put("/$fileName", artifact)
        blockMapGzip?.let { server.put("/$fileName.blockmap", it) }
        val yaml =
            buildString {
                appendLine("version: $version")
                appendLine("files:")
                appendLine("  - url: $fileName")
                appendLine("    sha512: $sha512")
                appendLine("    size: ${artifact.size}")
                if (blockMapSize != null) appendLine("    blockMapSize: $blockMapSize")
                appendLine("path: $fileName")
                appendLine("sha512: $sha512")
                appendLine("releaseDate: '2026-01-01T00:00:00.000Z'")
            }
        server.put("/latest.yml", yaml.toByteArray())
    }

    /** Fills the cache as a previous update through the updater would have left it. */
    private fun primeCache(
        fileName: String,
        artifact: ByteArray,
        blockMapGzip: ByteArray? = DeltaFixtures.blockMapGzip("v1"),
    ) {
        val staged = File(tmp.newFolder(), fileName).apply { writeBytes(artifact) }
        UpdateCache(cacheDir).store(staged, fileName, version = "1.0.0", blockMapGzip = blockMapGzip)
    }

    private fun updater(
        currentVersion: String,
        format: String = "zip",
        differential: Boolean = true,
    ) = NucleusUpdater {
        this.currentVersion = currentVersion
        provider = LoopbackProvider(server.baseUrl)
        executableType = format
        cacheDir = this@DifferentialUpdateE2ETest.cacheDir
        differentialDownload = differential
    }

    private fun checkForUpdates(
        currentVersion: String,
        format: String = "zip",
    ): UpdateResult = runBlocking { updater(currentVersion, format).checkForUpdates() }

    private fun downloadAll(
        currentVersion: String,
        format: String = "zip",
        differential: Boolean = true,
    ): List<DownloadProgress> {
        val updater = updater(currentVersion, format, differential)
        return runBlocking {
            val result = updater.checkForUpdates()
            assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
            updater.downloadUpdate((result as UpdateResult.Available).info).toList()
        }.also { events -> events.lastOrNull()?.file?.let(downloaded::add) }
    }

    private fun download(
        currentVersion: String,
        format: String = "zip",
        differential: Boolean = true,
    ): DownloadProgress = downloadAll(currentVersion, format, differential).last()

    private fun assertArtifactIs(
        expected: ByteArray,
        progress: DownloadProgress,
    ) {
        val file = requireNotNull(progress.file) { "the final progress event must carry the artifact" }
        assertEquals("assembled size", expected.size.toLong(), file.length())
        assertTrue("the assembled artifact must be byte-identical", expected.contentEquals(file.readBytes()))
    }

    private class LoopbackProvider(
        private val baseUrl: String,
    ) : UpdateProvider {
        override fun getUpdateMetadataUrl(
            channel: String,
            platform: Platform,
        ): String = "$baseUrl/$channel.yml"

        override fun getDownloadUrl(
            fileName: String,
            version: String,
        ): String = "$baseUrl/$fileName"
    }

    private companion object {
        const val PERCENT_MAX = 100.0

        /** Which fixture an artifact was built from, so its committed block map can be published. */
        fun fixtureOf(artifact: ByteArray): String = if (artifact.size == DeltaFixtures.V1_SIZE) "v1" else "v2"
    }
}
