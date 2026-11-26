package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.delta.RangeHttpServer
import dev.nucleusframework.updater.exception.ParseException
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The artifact's file name comes from the manifest's `url` field, which is remote input, and names
 * the file the download is staged as. Anything but a plain file name could resolve outside the
 * staging directory, so it must be rejected before anything is downloaded or written.
 */
class ManifestFileNameTest {
    private lateinit var server: RangeHttpServer

    @Before
    fun setUp() {
        server = RangeHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a manifest url that climbs out of the staging directory is rejected`() {
        server.put(
            "/latest.yml",
            """
            version: 2.0.0
            files:
              - url: ../victim.zip
                sha512: hash
                size: 10
            releaseDate: '2026-01-01T00:00:00.000Z'
            """.trimIndent().toByteArray(),
        )
        val updater = updater()

        val result = runBlocking { updater.checkForUpdates() }
        assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
        server.requests.clear()

        assertThrows(ParseException::class.java) {
            runBlocking { updater.downloadUpdate((result as UpdateResult.Available).info).toList() }
        }
        assertEquals("nothing may be downloaded", emptyList<String>(), server.requests.toList())
    }

    @Test
    fun `names that are not a single path component are rejected before downloading`() {
        val updater = updater()
        for (hostile in listOf("../victim.zip", "sub/App.zip", "..", ".", "", "a\\b.zip", "..\\victim.zip")) {
            val file =
                UpdateFile(
                    url = "${server.baseUrl}/App.zip",
                    sha512 = "hash",
                    size = 10,
                    fileName = hostile,
                )
            val info = UpdateInfo(version = "2.0.0", releaseDate = "", files = listOf(file), currentFile = file)
            assertThrows("'$hostile' must be rejected", ParseException::class.java) {
                runBlocking { updater.downloadUpdate(info).toList() }
            }
        }
        assertEquals("nothing may be downloaded", emptyList<String>(), server.requests.toList())
    }

    private fun updater() =
        NucleusUpdater {
            currentVersion = "1.0.0"
            provider = LoopbackProvider(server.baseUrl)
            executableType = "zip"
            differentialDownload = false
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
}
