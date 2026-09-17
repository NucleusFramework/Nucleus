package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.delta.RangeHttpServer
import dev.nucleusframework.updater.exception.NoMatchingFileException
import dev.nucleusframework.updater.exception.ParseException
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CheckForUpdatesLogicTest {
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
    fun `dev version short-circuits to not available`() {
        publish(version = "9.9.9", fileName = "App-9.9.9.zip")
        val updater =
            NucleusUpdater {
                currentVersion = UpdaterConfig.DEV_VERSION
                provider = LoopbackProvider(server.baseUrl)
                executableType = "zip"
            }
        val result = runBlocking { updater.checkForUpdates() }
        assertEquals(UpdateResult.NotAvailable, result)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `unsupported executable type short-circuits to not available`() {
        publish(version = "2.0.0", fileName = "App-2.0.0.zip")
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = LoopbackProvider(server.baseUrl)
                executableType = "pkg"
            }
        assertFalse(updater.isUpdateSupported())
        assertEquals(UpdateResult.NotAvailable, runBlocking { updater.checkForUpdates() })
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `zip executable type is treated as self-updatable`() {
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = FakeUpdateProvider()
                executableType = "zip"
            }
        assertTrue(updater.isUpdateSupported())
    }

    @Test
    fun `same version is not available`() {
        publish(version = "1.2.3", fileName = "App-1.2.3.zip")
        val result = check("1.2.3")
        assertEquals(UpdateResult.NotAvailable, result)
    }

    @Test
    fun `older remote without allowDowngrade is not available`() {
        publish(version = "1.0.0", fileName = "App-1.0.0.zip")
        assertEquals(UpdateResult.NotAvailable, check("2.0.0"))
    }

    @Test
    fun `older remote with allowDowngrade is available as a major update`() {
        publish(version = "1.0.0", fileName = "App-1.0.0.zip")
        val result = check("2.0.0") { allowDowngrade = true }
        assertTrue(result is UpdateResult.Available)
        val available = result as UpdateResult.Available
        assertEquals("1.0.0", available.info.version)
        assertEquals(UpdateLevel.MAJOR, available.level)
        assertEquals("App-1.0.0.zip", available.info.currentFile.fileName)
        assertTrue(
            available.info.currentFile.url
                .endsWith("/App-1.0.0.zip"),
        )
    }

    @Test
    fun `pre-release remote is skipped unless allowPrerelease is set`() {
        publish(version = "1.1.0-beta.1", fileName = "App-1.1.0-beta.1.zip")
        assertEquals(UpdateResult.NotAvailable, check("1.0.0"))

        val allowed = check("1.0.0") { allowPrerelease = true }
        assertTrue(allowed is UpdateResult.Available)
        assertEquals(UpdateLevel.MINOR, (allowed as UpdateResult.Available).level)
    }

    @Test
    fun `current pre-release version implies allowPrerelease`() {
        publish(version = "1.0.0-beta.2", fileName = "App-1.0.0-beta.2.zip")
        val result = check("1.0.0-beta.1")
        assertTrue(result is UpdateResult.Available)
        assertEquals(UpdateLevel.PRE_RELEASE, (result as UpdateResult.Available).level)
    }

    @Test
    fun `no matching file becomes an error`() {
        publish(version = "2.0.0", fileName = "App-2.0.0.deb")
        val result = check("1.0.0")
        assertTrue(result is UpdateResult.Error)
        assertTrue((result as UpdateResult.Error).exception is NoMatchingFileException)
        assertTrue(result.exception.message!!.contains("zip") || result.exception.message!!.contains("auto"))
    }

    @Test
    fun `HTTP error from the metadata host becomes an error`() {
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = LoopbackProvider(server.baseUrl)
                executableType = "zip"
            }
        val result = runBlocking { updater.checkForUpdates() }
        assertTrue(result is UpdateResult.Error)
        assertTrue((result as UpdateResult.Error).exception.message!!.contains("HTTP"))
    }

    @Test
    fun `invalid YAML becomes an error`() {
        server.put("/latest.yml", "files:\n  - url: x\n".toByteArray())
        val result = check("1.0.0")
        assertTrue(result is UpdateResult.Error)
        assertTrue((result as UpdateResult.Error).exception is ParseException)
    }

    @Test
    fun `newer patch is available with the matching file`() {
        publish(version = "1.0.1", fileName = "App-1.0.1.zip")
        val result = check("1.0.0")
        assertTrue(result is UpdateResult.Available)
        val available = result as UpdateResult.Available
        assertEquals(UpdateLevel.PATCH, available.level)
        assertEquals("1.0.1", available.info.version)
        assertEquals(1, available.info.files.size)
        assertEquals("2026-01-01T00:00:00.000Z", available.info.releaseDate)
    }

    private fun check(
        current: String,
        configure: UpdaterConfig.() -> Unit = {},
    ): UpdateResult {
        val updater =
            NucleusUpdater {
                currentVersion = current
                provider = LoopbackProvider(server.baseUrl)
                executableType = "zip"
                configure()
            }
        return runBlocking { updater.checkForUpdates() }
    }

    private fun publish(
        version: String,
        fileName: String,
    ) {
        val yaml =
            """
            version: $version
            files:
              - url: $fileName
                sha512: hash
                size: 10
            releaseDate: '2026-01-01T00:00:00.000Z'
            """.trimIndent()
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
}
