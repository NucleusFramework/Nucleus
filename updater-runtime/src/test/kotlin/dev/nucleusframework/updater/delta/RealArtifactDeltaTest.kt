package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateFile
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.internal.delta.BlockMapCodec
import dev.nucleusframework.updater.internal.delta.UpdateCache
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Runs a differential update against two artifacts a real packaging run produced, and reports how
 * much of the second one had to be downloaded. This is how the delta yield of an actual Compose
 * Desktop or GraalVM native application gets measured, rather than assumed.
 *
 * Opt-in — point it at two consecutive releases of the same format:
 * ```
 * ./gradlew :updater-runtime:test --tests '*RealArtifactDeltaTest*' \
 *     -Dnucleus.e2e.delta.old=build/v1/App-1.0.0-nsis.exe \
 *     -Dnucleus.e2e.delta.new=build/v2/App-2.0.0-nsis.exe
 * ```
 * The `<artifact>.blockmap` files electron-builder wrote next to them are picked up automatically;
 * override with `-Dnucleus.e2e.delta.oldBlockmap=…` / `-Dnucleus.e2e.delta.newBlockmap=…`. For
 * AppImages, which carry their block map in their own tail, none is needed.
 */
class RealArtifactDeltaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var server: RangeHttpServer? = null
    private var staged: File? = null

    @After
    fun tearDown() {
        server?.close()
        staged?.delete()
        staged?.let { File(it.parentFile, "${it.name}.asc").delete() }
    }

    @Test
    fun `a real update is assembled from the previous real artifact`() {
        val old = property("old")?.let(::File)
        val new = property("new")?.let(::File)
        assumeTrue("real artifacts not supplied", old != null && new != null)
        assertTrue("old artifact not found: $old", old!!.isFile)
        assertTrue("new artifact not found: $new", new!!.isFile)

        val oldBlockMap = blockMapOf("oldBlockmap", old)
        val newBlockMap = blockMapOf("newBlockmap", new)
        val embedded = oldBlockMap == null && newBlockMap == null
        assumeTrue(
            "supply both block maps, or neither for an artifact that embeds its own",
            embedded || (oldBlockMap != null && newBlockMap != null),
        )

        val running = RangeHttpServer().also { server = it }
        running.put("/${new.name}", new.readBytes())
        newBlockMap?.let { running.put("/${new.name}.blockmap", it.readBytes()) }
        UpdateCache(tmp.newFolder("cache")).store(old, old.name, "previous", oldBlockMap?.readBytes())

        val target =
            UpdateFile(
                url = "${running.baseUrl}/${new.name}",
                sha512 = DeltaFixtures.sha512Base64(new.readBytes()),
                size = new.length(),
                blockMapSize = if (embedded) embeddedBlockMapSize(new) else null,
                fileName = new.name,
            )
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = FakeUpdateProviderForDelta()
                cacheDir = File(tmp.root, "cache")
            }

        val events =
            runBlocking {
                updater
                    .downloadUpdate(
                        UpdateInfo("2.0.0", "2026-01-01T00:00:00.000Z", listOf(target), target),
                    ).toList()
            }
        val last = events.last()
        staged = last.file

        report(old, new, last, running.bytesServed.get())
        assertTrue("the update must be differential", last.isDifferential)
        assertEquals("assembled size", new.length(), last.file!!.length())
        assertTrue(
            "the assembled artifact must be byte-identical to the real one",
            new.readBytes().contentEquals(last.file.readBytes()),
        )
        assertTrue(
            "a delta that transfers the whole artifact is no delta",
            last.bytesDownloaded < new.length(),
        )
    }

    private fun report(
        old: File,
        new: File,
        progress: DownloadProgress,
        bytesServed: Long,
    ) {
        val ratio = progress.bytesDownloaded * PERCENT / new.length().toDouble()
        println(
            String.format(
                Locale.ROOT,
                "%n%s → %s%n  artifact: %,d bytes (previous: %,d)%n" +
                    "  transferred: %,d bytes (%.2f%% of the artifact)%n  served by the host: %,d bytes%n",
                old.name,
                new.name,
                new.length(),
                old.length(),
                progress.bytesDownloaded,
                ratio,
                bytesServed,
            ),
        )
    }

    private fun embeddedBlockMapSize(artifact: File): Long =
        RandomAccessFile(artifact, "r").use { BlockMapCodec.readEmbeddedPayloadSize(it).toLong() }

    /** The block map for [artifact]: the explicit override, else the companion file, else none. */
    private fun blockMapOf(
        override: String,
        artifact: File,
    ): File? =
        property(override)?.let(::File)
            ?: File(artifact.parentFile, "${artifact.name}.blockmap").takeIf { it.isFile }

    private fun property(name: String): String? =
        System.getProperty("nucleus.e2e.delta.$name")?.takeIf { it.isNotBlank() }

    private companion object {
        const val PERCENT = 100.0
    }
}
