package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.UpdateSimulation.Scenario
import dev.nucleusframework.updater.exception.ChecksumException
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.internal.FeedOverride
import dev.nucleusframework.updater.internal.UpdateMarker
import dev.nucleusframework.updater.internal.UpdaterSettings
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.LocalFileProvider
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The switches that test updates without publishing one: the launch-time feed redirect
 * (`nucleus.updater.feedUrl`), the [UpdateSimulation], and the install that an unpackaged run skips.
 */
class LaunchOverridesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val touchedProperties = mutableSetOf<String>()

    @After
    fun clearProperties() {
        touchedProperties.forEach(System::clearProperty)
    }

    private fun property(
        key: String,
        value: String,
    ) {
        touchedProperties += key
        System.setProperty(key, value)
    }

    // ---- settings -------------------------------------------------------------------------------

    @Test
    fun `settings map to environment variable names`() {
        assertEquals("NUCLEUS_UPDATER_FEED_URL", UpdaterSettings.environmentName(UpdaterSettings.FEED_URL))
        assertEquals("NUCLEUS_UPDATER_SIMULATE", UpdaterSettings.environmentName(UpdaterSettings.SIMULATE))
        assertEquals(
            "NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM",
            UpdaterSettings.environmentName(UpdaterSettings.SIMULATE_JUST_UPDATED_FROM),
        )
    }

    @Test
    fun `a system property wins over the environment and blanks count as unset`() {
        val env = mapOf("NUCLEUS_UPDATER_FEED_URL" to "from-env")
        assertEquals("from-prop", UpdaterSettings.get(UpdaterSettings.FEED_URL, { "from-prop" }, env::get))
        assertEquals("from-env", UpdaterSettings.get(UpdaterSettings.FEED_URL, { "  " }, env::get))
        assertNull(UpdaterSettings.get(UpdaterSettings.FEED_URL, { null }, { "" }))
    }

    // ---- feed redirect --------------------------------------------------------------------------

    @Test
    fun `an unpackaged run honours the redirect, an installed app only when allowed`() {
        assertNotNull(FeedOverride.resolve("http://127.0.0.1:8080", packaged = false, allowed = false))
        assertNull(FeedOverride.resolve("http://127.0.0.1:8080", packaged = true, allowed = false))
        assertNotNull(FeedOverride.resolve("http://127.0.0.1:8080", packaged = true, allowed = true))
        assertNull(FeedOverride.resolve(null, packaged = false, allowed = true))
        assertNull(FeedOverride.resolve("   ", packaged = false, allowed = true))
    }

    @Test
    fun `the redirect accepts https, loopback http, file URLs and paths`() {
        assertTrue(FeedOverride.providerFor("https://staging.example.com/feed") is GenericProvider)
        assertTrue(FeedOverride.providerFor("http://localhost:9000") is GenericProvider)
        assertTrue(FeedOverride.providerFor("http://[::1]:9000") is GenericProvider)
        val dir = tmp.newFolder("feed dir")
        assertEquals(
            dir.absoluteFile,
            (FeedOverride.providerFor(dir.toURI().toString()) as LocalFileProvider).directory,
        )
        assertEquals(dir.absoluteFile, (FeedOverride.providerFor(dir.absolutePath) as LocalFileProvider).directory)
        assertTrue(FeedOverride.providerFor("C:\\builds\\nsis") is LocalFileProvider)
        assertTrue(FeedOverride.providerFor("relative/dir") is LocalFileProvider)
    }

    @Test
    fun `the redirect refuses plain http to a remote host and unknown schemes`() {
        assertNull(FeedOverride.resolve("http://updates.example.com", packaged = false, allowed = true))
        assertNull(FeedOverride.resolve("ftp://127.0.0.1/feed", packaged = false, allowed = true))
        assertNull(FeedOverride.resolve("file://%%%", packaged = false, allowed = true))
    }

    @Test
    fun `a local provider names manifests per OS and stays inside its directory`() {
        val dir = tmp.newFolder("local")
        val provider = LocalFileProvider(dir)
        assertTrue(provider.getUpdateMetadataUrl("latest", Platform.Windows).endsWith("/latest.yml"))
        assertTrue(provider.getUpdateMetadataUrl("beta", Platform.MacOS).endsWith("/beta-mac.yml"))
        assertTrue(provider.getUpdateMetadataUrl("latest", Platform.Linux).endsWith("/latest-linux.yml"))
        assertThrows(IllegalArgumentException::class.java) { provider.getDownloadUrl("../x.exe", "1.0.0") }
        assertThrows(IllegalArgumentException::class.java) { provider.getDownloadUrl("sub/../../x.exe", "1.0.0") }
    }

    @Test
    fun `an unpackaged run redirected to a local feed checks and downloads, then skips the install`() {
        val feed = localFeed("2.0.0")
        property(UpdaterSettings.FEED_URL, feed.absolutePath)
        val updater = updater(executableType = "dev")

        assertEquals(feed.absolutePath, updater.feedOverride)
        assertTrue(updater.isUpdateSupported())
        val info = (runBlocking { updater.checkForUpdates() } as UpdateResult.Available).info
        val file = runBlocking { updater.downloadUpdate(info).toList() }.last().file!!
        assertEquals(ARTIFACT_BYTES.toList(), file.readBytes().toList())

        val markerBefore = UpdateMarker.read()
        // Would exit the test JVM if it did not skip.
        updater.installAndRestart(file)
        updater.installAndQuit(file)
        assertEquals("a skipped install records no update", markerBefore, UpdateMarker.read())
        file.parentFile.deleteRecursively()
    }

    @Test
    fun `an unpackaged run in dev version is still redirected`() {
        property(UpdaterSettings.FEED_URL, localFeed("2.0.0").absolutePath)
        val updater = updater(executableType = "dev", currentVersion = UpdaterConfig.DEV_VERSION)
        assertTrue(runBlocking { updater.checkForUpdates() } is UpdateResult.Available)
    }

    @Test
    fun `an unpackaged run without a redirect does not update`() {
        val updater = updater(executableType = "dev")
        assertFalse(updater.isUpdateSupported())
        assertEquals(UpdateResult.NotAvailable, runBlocking { updater.checkForUpdates() })
    }

    @Test
    fun `an installed app ignores the redirect unless it allows launch overrides`() {
        property(UpdaterSettings.FEED_URL, localFeed("2.0.0").absolutePath)
        val locked = updater(executableType = PACKAGED_TYPE)
        assertNull(locked.feedOverride)
        assertTrue("the configured provider is used", runBlocking { locked.checkForUpdates() } is UpdateResult.Error)

        val open = updater(executableType = PACKAGED_TYPE, allowLaunchOverrides = true)
        assertNotNull(open.feedOverride)
        assertTrue(runBlocking { open.checkForUpdates() } is UpdateResult.Available)
    }

    // ---- simulation -----------------------------------------------------------------------------

    @Test
    fun `simulation settings parse into a simulation`() {
        fun parse(vararg settings: Pair<String, String>) = UpdateSimulation.fromSettings(settings.toMap()::get)

        assertNull(parse())
        assertNull(parse(UpdaterSettings.SIMULATE to "false"))
        assertNull(parse(UpdaterSettings.SIMULATE to "nonsense"))
        assertEquals(Scenario.UPDATE_AVAILABLE, parse(UpdaterSettings.SIMULATE to "true")!!.scenario)
        assertEquals(Scenario.UPDATE_AVAILABLE, parse(UpdaterSettings.SIMULATE to "update")!!.scenario)
        assertEquals(Scenario.UP_TO_DATE, parse(UpdaterSettings.SIMULATE to "up-to-date")!!.scenario)
        assertEquals(Scenario.CHECKSUM_ERROR, parse(UpdaterSettings.SIMULATE to "CHECKSUM-ERROR")!!.scenario)
        parse(UpdaterSettings.SIMULATE to "3.2.1").let {
            assertEquals(Scenario.UPDATE_AVAILABLE, it!!.scenario)
            assertEquals("3.2.1", it.version)
        }
        parse(
            UpdaterSettings.SIMULATE to "download-error",
            UpdaterSettings.SIMULATE_VERSION to "9.0.0",
            UpdaterSettings.SIMULATE_DURATION to "1.5",
            UpdaterSettings.SIMULATE_SIZE to "1000",
            UpdaterSettings.SIMULATE_DIFFERENTIAL to "true",
        ).let {
            assertEquals(Scenario.DOWNLOAD_ERROR, it!!.scenario)
            assertEquals("9.0.0", it.version)
            assertEquals(1500.milliseconds, it.downloadDuration)
            assertEquals(1000L, it.downloadSize)
            assertTrue(it.isDifferential)
        }
        parse(UpdaterSettings.SIMULATE_JUST_UPDATED_FROM to "0.9.0").let {
            assertEquals("justUpdatedFrom alone finds no update", Scenario.UP_TO_DATE, it!!.scenario)
            assertEquals("0.9.0", it.justUpdatedFrom)
        }
    }

    @Test
    fun `a simulated update is offered, downloaded and not installed, even unpackaged`() {
        val updater = simulated(UpdateSimulation(downloadDuration = 600.milliseconds, downloadSize = 10_000))
        assertTrue(updater.isUpdateSupported())
        val result = runBlocking { updater.checkForUpdates() } as UpdateResult.Available
        assertEquals("the next minor version is offered", "1.5.0", result.info.version)
        assertEquals(UpdateLevel.MINOR, result.level)

        val started = TimeSource.Monotonic.markNow()
        val progress = runBlocking { updater.downloadUpdate(result.info).toList() }
        val elapsed = started.elapsedNow()
        assertTrue("the download takes its duration, took $elapsed", elapsed >= 550.milliseconds)
        assertTrue("several progress reports, got ${progress.size}", progress.size >= 5)
        assertEquals(progress.map { it.percent }.sorted(), progress.map { it.percent })
        assertEquals(10_000L, progress.last().bytesDownloaded)
        val file = progress.last().file!!
        assertTrue(file.isFile)
        assertTrue("only the last report carries the file", progress.dropLast(1).none { it.file != null })

        val markerBefore = UpdateMarker.read()
        updater.installAndRestart(file)
        assertEquals(markerBefore, UpdateMarker.read())
    }

    @Test
    fun `simulated failures surface as the real errors`() {
        val offline = simulated(UpdateSimulation(Scenario.CHECK_ERROR, checkDuration = Duration.ZERO))
        assertTrue(runBlocking { offline.checkForUpdates() } is UpdateResult.Error)

        val upToDate = simulated(UpdateSimulation(Scenario.UP_TO_DATE, checkDuration = Duration.ZERO))
        assertEquals(UpdateResult.NotAvailable, runBlocking { upToDate.checkForUpdates() })

        val dropped =
            simulated(
                UpdateSimulation(
                    Scenario.DOWNLOAD_ERROR,
                    checkDuration = Duration.ZERO,
                    downloadDuration = 300.milliseconds,
                ),
            )
        val droppedInfo = (runBlocking { dropped.checkForUpdates() } as UpdateResult.Available).info
        val seen = mutableListOf<DownloadProgress>()
        assertThrows(NetworkException::class.java) {
            runBlocking { dropped.downloadUpdate(droppedInfo).collect(seen::add) }
        }
        assertTrue(
            "it failed part-way",
            seen.isNotEmpty() && seen.none { it.file != null } && seen.last().percent < 100.0,
        )

        val tampered =
            simulated(
                UpdateSimulation(
                    Scenario.CHECKSUM_ERROR,
                    checkDuration = Duration.ZERO,
                    downloadDuration = 100.milliseconds,
                ),
            )
        val tamperedInfo = (runBlocking { tampered.checkForUpdates() } as UpdateResult.Available).info
        assertThrows(ChecksumException::class.java) { runBlocking { tampered.downloadUpdate(tamperedInfo).collect() } }
    }

    @Test
    fun `a simulated download can be cancelled`() {
        val updater =
            simulated(
                UpdateSimulation(checkDuration = Duration.ZERO, downloadDuration = kotlin.time.Duration.parse("10s")),
            )
        val info = (runBlocking { updater.checkForUpdates() } as UpdateResult.Available).info
        val finished = runBlocking { withTimeoutOrNull(300.milliseconds) { updater.downloadUpdate(info).collect() } }
        assertNull(finished)
    }

    @Test
    fun `a differential simulation transfers a fraction of the artifact`() {
        val updater =
            simulated(
                UpdateSimulation(
                    checkDuration = Duration.ZERO,
                    downloadDuration = Duration.ZERO,
                    isDifferential = true,
                ),
            )
        val info = (runBlocking { updater.checkForUpdates() } as UpdateResult.Available).info
        val last = runBlocking { updater.downloadUpdate(info).toList() }.last()
        assertTrue(last.isDifferential)
        assertTrue(last.totalBytes < info.currentFile.size / 5)
    }

    @Test
    fun `a simulated post-update launch is reported once`() {
        val updater = simulated(UpdateSimulation(justUpdatedFrom = "1.3.2"))
        assertTrue(updater.wasJustUpdated())
        assertTrue("peeking does not consume", updater.wasJustUpdated())
        assertEquals(UpdateEvent("1.3.2", "1.4.0", UpdateLevel.MINOR), updater.consumeUpdateEvent())
        assertFalse(updater.wasJustUpdated())
    }

    @Test
    fun `a launch-time simulation needs the opt-in in an installed app`() {
        property(UpdaterSettings.SIMULATE, "2.0.0")
        assertNotNull("unpackaged: honoured", updater(executableType = "dev").simulation)
        assertNull("installed: ignored", updater(executableType = PACKAGED_TYPE).simulation)
        assertEquals("2.0.0", updater(executableType = PACKAGED_TYPE, allowLaunchOverrides = true).simulation?.version)
    }

    @Test
    fun `a simulation set in code wins over the launch settings and the redirect`() {
        property(UpdaterSettings.SIMULATE, "up-to-date")
        property(UpdaterSettings.FEED_URL, localFeed("2.0.0").absolutePath)
        val updater =
            NucleusUpdater {
                currentVersion = "1.4.0"
                executableType = "dev"
                provider = Unreachable
                simulation = UpdateSimulation(version = "7.0.0", checkDuration = Duration.ZERO)
            }
        assertNull("no redirect while simulating", updater.feedOverride)
        assertEquals("7.0.0", (runBlocking { updater.checkForUpdates() } as UpdateResult.Available).info.version)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun simulated(simulation: UpdateSimulation): NucleusUpdater =
        NucleusUpdater {
            currentVersion = "1.4.0"
            executableType = "dev"
            provider = Unreachable
            this.simulation = simulation
        }

    private fun updater(
        executableType: String,
        currentVersion: String = "1.0.0",
        allowLaunchOverrides: Boolean = false,
    ): NucleusUpdater =
        NucleusUpdater {
            this.currentVersion = currentVersion
            this.executableType = executableType
            this.allowLaunchOverrides = allowLaunchOverrides
            provider = Unreachable
            differentialDownload = false
            cacheDir = tmp.root.resolve("cache")
        }

    /** A directory laid out like a packaging output: artifact + manifest for this OS. */
    private fun localFeed(version: String): File {
        val dir = tmp.newFolder("feed-$version-${System.nanoTime()}")
        val name =
            when (Platform.Current) {
                Platform.Windows -> "MyApp-$version-win-x64-nsis.exe"
                Platform.MacOS -> "MyApp-$version-mac-arm64.zip"
                else -> "MyApp-$version-linux-x86_64.AppImage"
            }
        File(dir, name).writeBytes(ARTIFACT_BYTES)
        val sha = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(ARTIFACT_BYTES))
        val manifest = LocalFileProvider(dir).getUpdateMetadataUrl("latest", Platform.Current)
        File(java.net.URI(manifest)).writeText(
            "version: $version\nfiles:\n  - url: $name\n    sha512: $sha\n    size: ${ARTIFACT_BYTES.size}\n" +
                "path: $name\nsha512: $sha\nreleaseDate: '2026-09-25T00:00:00.000Z'\n",
        )
        return dir
    }

    /** The provider the app ships with; unreachable, so reaching it is visible as an error. */
    private object Unreachable : UpdateProvider {
        override fun getUpdateMetadataUrl(
            channel: String,
            platform: Platform,
        ): String = "http://127.0.0.1:1/$channel.yml"

        override fun getDownloadUrl(
            fileName: String,
            version: String,
        ): String = "http://127.0.0.1:1/$fileName"
    }

    private companion object {
        val ARTIFACT_BYTES = ByteArray(200_000) { (it * 31 % 251).toByte() }

        val PACKAGED_TYPE =
            when (Platform.Current) {
                Platform.Windows -> "nsis"
                Platform.MacOS -> "zip"
                else -> "appimage"
            }
    }
}
