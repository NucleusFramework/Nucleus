package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.UpdateFile
import dev.nucleusframework.updater.internal.delta.DeltaPlan
import dev.nucleusframework.updater.internal.delta.DeltaResolver
import dev.nucleusframework.updater.internal.delta.DifferentialDownloader
import dev.nucleusframework.updater.internal.delta.UpdateCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.http.HttpClient

/**
 * The AppImage case, which the end-to-end test cannot reach: the previous artifact is the *running*
 * executable, located through `$APPIMAGE`, so an AppImage user gets a differential update from the
 * first one on — with nothing cached.
 */
class DeltaResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: RangeHttpServer
    private lateinit var cache: UpdateCache
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @Before
    fun setUp() {
        server = RangeHttpServer()
        cache = UpdateCache(tmp.newFolder("empty-cache"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the running AppImage is the basis for the update, with an empty cache`() {
        val running = appImage(DeltaFixtures.v1(), "v1", "MyApp-1.0.0.AppImage")
        val newBytes = DeltaFixtures.withEmbeddedBlockMap(DeltaFixtures.v2(), "v2")
        server.put("/MyApp-2.0.0.AppImage", newBytes)
        val target = appImageTarget(newBytes)

        val resolved = resolver { running.absolutePath }.resolve(target, blockMapUrl(target), destination())

        assertNotNull("the running AppImage must be usable as the delta basis", resolved)
        assertEquals(
            DeltaFixtures.EXPECTED_DELTA_BYTES,
            DeltaPlan.downloadSize(resolved!!.download.operations),
        )
        assertNull("an embedded block map needs no companion file in the cache", resolved.blockMapGzip)

        val transferred =
            runBlocking { DifferentialDownloader(httpClient).download(resolved.download) { _, _ -> } }

        assertEquals(DeltaFixtures.EXPECTED_DELTA_BYTES, transferred)
        assertTrue(
            "the assembled AppImage must be byte-identical, trailer included",
            newBytes.contentEquals(resolved.download.target.readBytes()),
        )
    }

    @Test
    fun `no delta is attempted when the running AppImage cannot be located`() {
        val newBytes = DeltaFixtures.withEmbeddedBlockMap(DeltaFixtures.v2(), "v2")
        server.put("/MyApp-2.0.0.AppImage", newBytes)
        val target = appImageTarget(newBytes)

        val resolved = resolver { null }.resolve(target, blockMapUrl(target), destination())

        assertNull(resolved)
        assertTrue("nothing may be requested before a basis is found", server.requests.isEmpty())
    }

    @Test
    fun `no delta is attempted when the path in APPIMAGE does not exist`() {
        val newBytes = DeltaFixtures.withEmbeddedBlockMap(DeltaFixtures.v2(), "v2")
        server.put("/MyApp-2.0.0.AppImage", newBytes)
        val target = appImageTarget(newBytes)

        val resolved =
            resolver { File(tmp.root, "deleted.AppImage").absolutePath }
                .resolve(target, blockMapUrl(target), destination())

        assertNull(resolved)
    }

    private fun resolver(appImagePath: () -> String?) =
        DeltaResolver(httpClient, authHeaders = emptyMap(), cache = cache, appImagePath = appImagePath)

    private fun appImage(
        artifact: ByteArray,
        version: String,
        name: String,
    ): File = File(tmp.newFolder(), name).apply { writeBytes(DeltaFixtures.withEmbeddedBlockMap(artifact, version)) }

    private fun appImageTarget(bytes: ByteArray) =
        UpdateFile(
            url = "${server.baseUrl}/MyApp-2.0.0.AppImage",
            sha512 = DeltaFixtures.sha512Base64(bytes),
            size = bytes.size.toLong(),
            blockMapSize = DeltaFixtures.embeddedBlockMapSize("v2"),
            fileName = "MyApp-2.0.0.AppImage",
        )

    private fun blockMapUrl(target: UpdateFile) = "${target.url}.blockmap"

    private fun destination() = File(tmp.newFolder(), "MyApp-2.0.0.AppImage.download")
}
