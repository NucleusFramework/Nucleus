package dev.nucleusframework.updater.delta

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the delta path against a manifest shaped like the ones this project actually publishes,
 * rather than a hand-written one.
 *
 * `UpdateYmlGenerator` and `.github/actions/generate-update-yml` both emit `blockMapSize` for every
 * artifact that has a companion `<artifact>.blockmap` — the length of that standalone file — and so
 * does electron-builder for NSIS, macOS ZIP and DMG. A released `latest-mac.yml` therefore always
 * carries `blockMapSize` next to a standalone block map, which is the exact combination the other
 * delta tests never produce.
 */
class PublishedManifestDeltaTest {
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
        downloaded.forEach { file ->
            file.delete()
            File(file.parentFile, "${file.name}.asc").delete()
        }
    }

    @Test
    fun `a macOS ZIP published with the manifest the plugin generates updates differentially`() {
        assertDifferential(format = "zip", fileName = "NucleusDemo-2.0.0-macos-arm64.zip")
    }

    @Test
    fun `a macOS DMG published with the manifest the plugin generates updates differentially`() {
        assertDifferential(format = "dmg", fileName = "NucleusDemo-2.0.0-macos-arm64.dmg")
    }

    private fun assertDifferential(
        format: String,
        fileName: String,
    ) {
        primeCache(fileName = fileName.replace("2.0.0", "1.0.0"), artifact = DeltaFixtures.v1())
        publishAsThePluginWould(fileName = fileName, artifact = DeltaFixtures.v2())

        val progress = download(currentVersion = "1.0.0", format = format)

        assertTrue(
            "a release that publishes a standalone block map must update differentially, " +
                "not fall back to the full ${DeltaFixtures.V2_SIZE} bytes",
            progress.isDifferential,
        )
        assertEquals(DeltaFixtures.EXPECTED_DELTA_BYTES, progress.bytesDownloaded)
        val file = requireNotNull(progress.file)
        assertTrue("the assembled artifact must be byte-identical", DeltaFixtures.v2().contentEquals(file.readBytes()))
    }

    /**
     * Publishes an artifact exactly as a Nucleus release does: the artifact, its standalone
     * `<artifact>.blockmap`, and a manifest whose `blockMapSize` is that file's length.
     */
    private fun publishAsThePluginWould(
        fileName: String,
        artifact: ByteArray,
    ) {
        val blockMapGzip = DeltaFixtures.blockMapGzip("v2")
        server.put("/$fileName", artifact)
        server.put("/$fileName.blockmap", blockMapGzip)
        server.put(
            "/latest-mac.yml",
            buildString {
                appendLine("version: 2.0.0")
                appendLine("files:")
                appendLine("  - url: $fileName")
                appendLine("    sha512: ${DeltaFixtures.sha512Base64(artifact)}")
                appendLine("    size: ${artifact.size}")
                appendLine("    blockMapSize: ${blockMapGzip.size}")
                appendLine("path: $fileName")
                appendLine("sha512: ${DeltaFixtures.sha512Base64(artifact)}")
                appendLine("releaseDate: '2026-01-01T00:00:00.000Z'")
            }.toByteArray(),
        )
    }

    private fun primeCache(
        fileName: String,
        artifact: ByteArray,
    ) {
        val staged = File(tmp.newFolder(), fileName).apply { writeBytes(artifact) }
        dev.nucleusframework.updater.internal.delta
            .UpdateCache(cacheDir)
            .store(staged, fileName, version = "1.0.0", blockMapGzip = DeltaFixtures.blockMapGzip("v1"))
    }

    private fun download(
        currentVersion: String,
        format: String,
    ): DownloadProgress {
        val updater =
            NucleusUpdater {
                this.currentVersion = currentVersion
                provider = MacLoopbackProvider(server.baseUrl)
                executableType = format
                cacheDir = this@PublishedManifestDeltaTest.cacheDir
            }
        return runBlocking {
            val result = updater.checkForUpdates()
            assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
            updater.downloadUpdate((result as UpdateResult.Available).info).toList()
        }.also { events -> events.lastOrNull()?.file?.let(downloaded::add) }.last()
    }

    private class MacLoopbackProvider(
        private val baseUrl: String,
    ) : UpdateProvider {
        override fun getUpdateMetadataUrl(
            channel: String,
            platform: Platform,
        ): String = "$baseUrl/latest-mac.yml"

        override fun getDownloadUrl(
            fileName: String,
            version: String,
        ): String = "$baseUrl/$fileName"
    }
}
