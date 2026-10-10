package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.testing.FeedFault
import dev.nucleusframework.updater.testing.UpdateFeedServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The differential path under a misbehaving host ([UpdateFeedServer] faults), with the block maps a
 * real electron-builder produced. Whatever goes wrong with the ranged requests, the update must
 * still end byte-identical — by falling back to a full download — and a healthy host must still
 * yield a real delta.
 */
class DifferentialTortureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var feed: UpdateFeedServer
    private lateinit var cacheDir: File
    private val downloaded = mutableListOf<File>()

    @Before
    fun setUp() {
        DeltaFixtures.verify()
        feed = UpdateFeedServer(directory = tmp.newFolder("feed"))
        cacheDir = tmp.newFolder("cache")
        // A first update through the updater caches 1.0.0 and its block map: the base of the delta.
        publish("1.0.0", DeltaFixtures.v1(), "v1")
        val first = download("0.9.0")
        assertFalse(first.last().isDifferential)
        publish("2.0.0", DeltaFixtures.v2(), "v2")
        feed.clearRequests()
    }

    @After
    fun tearDown() {
        feed.close()
        downloaded.forEach { it.parentFile?.deleteRecursively() }
    }

    @Test
    fun `a healthy host yields a real delta`() {
        val progress = download("1.0.0")
        assertTrue(progress.last().isDifferential)
        assertEquals(DeltaFixtures.EXPECTED_DELTA_BYTES, progress.last().bytesDownloaded)
        assertArtifactIsV2(progress)
        assertTrue("ranged requests were made", feed.requests.any { it.range != null && it.status == 206 })
    }

    @Test
    fun `a host that ignores Range falls back to a full download`() {
        feed.fault(FeedFault.IgnoreRange, path = ARTIFACT)
        val progress = download("1.0.0")
        assertFalse(progress.last().isDifferential)
        assertArtifactIsV2(progress)
    }

    @Test
    fun `a ranged response cut part-way falls back to a full download`() {
        feed.fault(FeedFault.Truncate(afterBytes = 100), path = ARTIFACT, times = 1)
        val progress = download("1.0.0")
        assertFalse(progress.last().isDifferential)
        assertArtifactIsV2(progress)
    }

    @Test
    fun `a corrupted ranged response is caught and falls back to a full download`() {
        // Corrupt the first bytes of whatever the first ranged request covers.
        feed.fault(FeedFault.Corrupt(offset = 200_000), path = ARTIFACT, times = 1)
        val progress = download("1.0.0")
        assertFalse(progress.last().isDifferential)
        assertArtifactIsV2(progress)
    }

    @Test
    fun `a missing block map falls back to a full download`() {
        feed.fault(FeedFault.Status(404), path = "$ARTIFACT.blockmap")
        val progress = download("1.0.0")
        assertFalse(progress.last().isDifferential)
        assertArtifactIsV2(progress)
    }

    @Test
    fun `a failing range request falls back to a full download`() {
        feed.fault(FeedFault.Status(500), path = ARTIFACT, times = 1)
        val progress = download("1.0.0")
        assertFalse(progress.last().isDifferential)
        assertArtifactIsV2(progress)
    }

    @Test
    fun `a slow host still yields a delta with monotonic progress`() {
        feed.fault(FeedFault.Throttle(bytesPerSecond = 40_000), path = ARTIFACT)
        val progress = download("1.0.0")
        assertTrue(progress.last().isDifferential)
        val percents = progress.map { it.percent }
        assertEquals(percents.sorted(), percents)
        assertArtifactIsV2(progress)
    }

    private fun publish(
        version: String,
        bytes: ByteArray,
        blockMapFixture: String,
    ) {
        val staging = tmp.newFolder()
        val artifact = File(staging, "MyApp-$version.zip").apply { writeBytes(bytes) }
        File(staging, "MyApp-$version.zip.blockmap").writeBytes(DeltaFixtures.blockMapGzip(blockMapFixture))
        feed.publish(version, artifact)
    }

    private fun download(currentVersion: String): List<DownloadProgress> {
        val updater =
            NucleusUpdater {
                this.currentVersion = currentVersion
                executableType = "zip"
                provider = GenericProvider(feed.baseUrl)
                cacheDir = this@DifferentialTortureTest.cacheDir
            }
        return runBlocking {
            withTimeout(60.seconds) {
                val result = updater.checkForUpdates()
                assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
                updater.downloadUpdate((result as UpdateResult.Available).info).toList()
            }
        }.also { events -> events.last().file?.let(downloaded::add) }
    }

    private fun assertArtifactIsV2(progress: List<DownloadProgress>) {
        assertArrayEquals(DeltaFixtures.v2(), progress.last().file!!.readBytes())
    }

    private companion object {
        const val ARTIFACT = "MyApp-2.0.0.zip"
    }
}
