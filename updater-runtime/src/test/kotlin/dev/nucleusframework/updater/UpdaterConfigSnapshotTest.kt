package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.delta.RangeHttpServer
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * Guards the construction-time contract of [NucleusUpdater] (issue #487):
 * a missing provider fails at construction rather than at the first network call,
 * the whole config is snapshotted once so later mutation is inert, and downloads
 * are staged in a private per-download directory instead of a predictable path
 * in the shared temp dir.
 */
class UpdaterConfigSnapshotTest {
    private lateinit var server: RangeHttpServer
    private val staged = mutableListOf<File>()

    @Before
    fun setUp() {
        server = RangeHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
        staged.forEach { it.parentFile?.deleteRecursively() }
    }

    @Test
    fun `a config without a provider is rejected at construction`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                NucleusUpdater { currentVersion = "1.0.0" }
            }
        assertTrue(
            "the error must point at the missing provider, got: ${failure.message}",
            failure.message.orEmpty().contains("provider"),
        )
    }

    @Test
    fun `mutating the config after construction has no effect`() {
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = ARTIFACT)
        val config =
            UpdaterConfig().apply {
                currentVersion = "1.0.0"
                provider = LoopbackProvider(server.baseUrl)
                executableType = "zip"
            }
        val updater = NucleusUpdater(config)

        // Post-construction mutation must be inert: the version must not change and the
        // dead provider must never be consulted for the update check.
        config.currentVersion = "9.9.9"
        config.provider = DeadProvider

        assertEquals("1.0.0", updater.currentVersion)
        val result = runBlocking { updater.checkForUpdates() }
        assertTrue(
            "the update check must still use the snapshotted provider, got $result",
            result is UpdateResult.Available,
        )
    }

    @Test
    fun `downloads are staged in a private directory, not at a predictable temp path`() {
        publish(version = "2.0.0", fileName = "MyApp-2.0.0.zip", artifact = ARTIFACT)
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = LoopbackProvider(server.baseUrl)
                executableType = "zip"
                differentialDownload = false
            }

        val file =
            runBlocking {
                val result = updater.checkForUpdates()
                assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
                updater.downloadUpdate((result as UpdateResult.Available).info).toList()
            }.last().file.also { it?.let(staged::add) }

        requireNotNull(file) { "the final progress event must carry the artifact" }
        assertTrue("the artifact must be intact", ARTIFACT.contentEquals(file.readBytes()))

        val tempDir = File(System.getProperty("java.io.tmpdir"))
        assertNotEquals(
            "the artifact must not land directly in the shared temp dir",
            tempDir.canonicalFile,
            file.parentFile.canonicalFile,
        )
        val perms = runCatching { Files.getPosixFilePermissions(file.parentFile.toPath()) }.getOrNull()
        if (perms != null) {
            assertTrue(
                "the staging directory must be owner-only, got $perms",
                perms.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") },
            )
        }
    }

    private fun publish(
        version: String,
        fileName: String,
        artifact: ByteArray,
    ) {
        server.put("/$fileName", artifact)
        val sha512 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(artifact))
        val yaml =
            buildString {
                appendLine("version: $version")
                appendLine("files:")
                appendLine("  - url: $fileName")
                appendLine("    sha512: $sha512")
                appendLine("    size: ${artifact.size}")
                appendLine("path: $fileName")
                appendLine("sha512: $sha512")
                appendLine("releaseDate: '2026-01-01T00:00:00.000Z'")
            }
        server.put("/latest.yml", yaml.toByteArray())
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

    /** A provider that fails every call, proving it is never consulted. */
    private object DeadProvider : UpdateProvider {
        override fun getUpdateMetadataUrl(
            channel: String,
            platform: Platform,
        ): String = error("the snapshotted provider must be used, not this one")

        override fun getDownloadUrl(
            fileName: String,
            version: String,
        ): String = error("the snapshotted provider must be used, not this one")
    }

    private companion object {
        val ARTIFACT: ByteArray = ByteArray(64 * 1024) { (it % 251).toByte() }
    }
}
