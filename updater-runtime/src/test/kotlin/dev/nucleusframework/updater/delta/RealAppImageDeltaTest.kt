package dev.nucleusframework.updater.delta

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateFile
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.internal.delta.DeltaPlan
import dev.nucleusframework.updater.internal.delta.DeltaResolver
import dev.nucleusframework.updater.internal.delta.DifferentialDownloader
import dev.nucleusframework.updater.internal.delta.UpdateCache
import dev.nucleusframework.updater.provider.UpdateProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.http.HttpClient
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * The Linux half of the real-artifact verification: two consecutive AppImages a real packaging run
 * produced, driven through the whole updater over a loopback host that counts the bytes it serves.
 *
 * AppImages carry their block map appended to their own tail, so this exercises the embedded flavour
 * end to end — the one no macOS or Windows artifact can reach. Both manifest shapes are covered,
 * because they do *not* behave the same for an AppImage:
 *
 *  - the `latest-linux.yml` electron-builder writes next to the artifact carries `blockMapSize`, and
 *    it is the length of the *embedded raw-deflate payload*;
 *  - the one `.github/actions/generate-update-yml` writes at release time derives `blockMapSize` from
 *    a standalone `<artifact>.blockmap`, which electron-builder never produces for an AppImage — so
 *    the published manifest carries no `blockMapSize` at all.
 *
 * Opt-in:
 * ```
 * ./gradlew :updater-runtime:test --tests '*RealAppImageDeltaTest*' \
 *     -Dnucleus.e2e.appimage.old=/tmp/delta-linux/v1/App-1.0.0.AppImage \
 *     -Dnucleus.e2e.appimage.new=/tmp/delta-linux/v2/App-1.0.1.AppImage
 * ```
 */
class RealAppImageDeltaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var server: RangeHttpServer? = null
    private val staged = mutableListOf<File>()
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @After
    fun tearDown() {
        server?.close()
        staged.forEach { file ->
            file.delete()
            File(file.parentFile, "${file.name}.asc").delete()
        }
    }

    /**
     * The claim the PR makes for AppImages: the running executable is the previous artifact, so the
     * very first update is already differential with nothing cached. Driven through [DeltaResolver]
     * because `$APPIMAGE` cannot be set from inside the test JVM.
     */
    @Test
    fun `the running AppImage makes the first update differential, with an empty cache`() {
        val (old, new) = artifacts()
        val running = RangeHttpServer().also { server = it }
        running.put("/${new.name}", new.readBytes())

        val target = target(new, running.baseUrl, withBlockMapSize = true)
        val destination = File(tmp.newFolder(), "${new.name}.download")
        val resolver =
            DeltaResolver(
                httpClient,
                authHeaders = emptyMap(),
                cache = UpdateCache(tmp.newFolder("empty-cache")),
                appImagePath = { old.absolutePath },
            )

        val resolved = resolver.resolve(target, "${target.url}.blockmap", destination)
        assertNotNull("the running AppImage must be usable as the delta basis", resolved)

        val planned = DeltaPlan.downloadSize(resolved!!.download.operations)
        val transferred =
            runBlocking { DifferentialDownloader(httpClient).download(resolved.download) { _, _ -> } }

        println(
            String.format(
                Locale.ROOT,
                "%n[\$APPIMAGE, empty cache] %s → %s%n  artifact: %,d bytes (previous: %,d)%n" +
                    "  planned: %,d bytes   transferred: %,d bytes (%.2f%% of the artifact)%n" +
                    "  served by the host: %,d bytes%n",
                old.name,
                new.name,
                new.length(),
                old.length(),
                planned,
                transferred,
                transferred * PERCENT / new.length().toDouble(),
                running.bytesServed.get(),
            ),
        )

        assertEquals("planned and transferred bytes must agree", planned, transferred)
        assertEquals("assembled size", new.length(), destination.length())
        assertEquals("assembled digest", sha512(new), sha512(destination))
        assertTrue("a delta that transfers the whole artifact is no delta", transferred < new.length())
    }

    @Test
    fun `the manifest electron-builder writes yields a differential download`() {
        val last = runRealUpdate(withBlockMapSize = true)
        assertTrue("the update must be differential", last.isDifferential)
        assertTrue(
            "a delta that transfers the whole artifact is no delta",
            last.bytesDownloaded < last.file!!.length(),
        )
    }

    /**
     * A manifest with no `blockMapSize` at all leaves the embedded block map unreachable, so the
     * artifact is downloaded whole. Correct behaviour — a full download is always a valid answer —
     * but it is what a released AppImage used to get, because `generate-update-yml` only emitted
     * `blockMapSize` for artifacts with a standalone `<artifact>.blockmap` and an AppImage has none.
     * `embedded_blockmap_size()` in that action now reads the length off the artifact's own tail;
     * [the manifest the release workflow publishes yields a differential download] covers it.
     */
    @Test
    fun `a manifest without blockMapSize falls back to a full download`() {
        val last = runRealUpdate(withBlockMapSize = false)
        assertTrue(
            "without blockMapSize the embedded block map is never read, so the download is full",
            !last.isDifferential,
        )
        assertEquals("the whole artifact was transferred", last.file!!.length(), last.bytesDownloaded)
    }

    /**
     * Drives the updater against the manifest `.github/actions/generate-update-yml` really produced
     * for this artifact, rather than one the test hand-built — so the shape the release publishes is
     * itself under test. Opt in with `-Dnucleus.e2e.appimage.manifest=<path to latest-linux.yml>`.
     */
    @Test
    fun `the manifest the release workflow publishes yields a differential download`() {
        val published = property("manifest")?.let(::File)
        assumeTrue("no generated manifest supplied", published != null)
        assertTrue("manifest not found: $published", published!!.isFile)

        val last = runRealUpdate(withBlockMapSize = true, manifestOverride = published.readText())
        assertTrue("the published manifest must yield a differential download", last.isDifferential)
        assertTrue(
            "a delta that transfers the whole artifact is no delta",
            last.bytesDownloaded < last.file!!.length(),
        )
    }

    private fun runRealUpdate(
        withBlockMapSize: Boolean,
        manifestOverride: String? = null,
    ): DownloadProgress {
        val (old, new) = artifacts()
        val running = RangeHttpServer().also { server = it }
        running.put("/${new.name}", new.readBytes())
        running.put("/$MANIFEST", (manifestOverride ?: manifest(new, withBlockMapSize)).toByteArray())

        // What the previous update left behind. An AppImage needs no companion block map: the one in
        // its own tail is read straight from the cached file.
        val cacheDir = tmp.newFolder("cache")
        UpdateCache(cacheDir).store(old, old.name, version = "1.0.0", blockMapGzip = null)

        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = LinuxManifestProvider(running.baseUrl)
                executableType = "appimage"
                this.cacheDir = cacheDir
            }
        val events =
            runBlocking {
                val result = updater.checkForUpdates()
                assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
                updater.downloadUpdate((result as UpdateResult.Available).info).toList()
            }
        val last = events.last()
        last.file?.let(staged::add)

        report(old, new, last, running.bytesServed.get(), withBlockMapSize)
        property("out")?.let { dir ->
            last.file?.copyTo(File(File(dir).apply { mkdirs() }, new.name), overwrite = true)
        }

        assertEquals("assembled size", new.length(), last.file!!.length())
        assertEquals("assembled digest", sha512(new), sha512(last.file))
        return last
    }

    /** The real artifact pair; the test is skipped when it was not opted into. */
    private fun artifacts(): Pair<File, File> {
        val old = property("old")?.let(::File)
        val new = property("new")?.let(::File)
        assumeTrue("real AppImage artifacts not supplied", old != null && new != null)
        assertTrue("old artifact not found: $old", old!!.isFile)
        assertTrue("new artifact not found: $new", new!!.isFile)
        return old to new
    }

    /** The manifest a release publishes for [artifact], in either of the two shapes this project emits. */
    private fun manifest(
        artifact: File,
        withBlockMapSize: Boolean,
    ): String =
        buildString {
            appendLine("version: 2.0.0")
            appendLine("files:")
            appendLine("  - url: ${artifact.name}")
            appendLine("    sha512: ${sha512(artifact)}")
            appendLine("    size: ${artifact.length()}")
            // electron-builder sets it to the embedded payload length; generate-update-yml omits it.
            if (withBlockMapSize) appendLine("    blockMapSize: ${embeddedBlockMapSize(artifact)}")
            appendLine("path: ${artifact.name}")
            appendLine("sha512: ${sha512(artifact)}")
            appendLine("releaseDate: '2026-01-01T00:00:00.000Z'")
        }

    private fun target(
        artifact: File,
        baseUrl: String,
        withBlockMapSize: Boolean,
    ) = UpdateFile(
        url = "$baseUrl/${artifact.name}",
        sha512 = sha512(artifact),
        size = artifact.length(),
        blockMapSize = if (withBlockMapSize) embeddedBlockMapSize(artifact) else null,
        fileName = artifact.name,
    )

    /** The big-endian length header terminating the block map electron-builder appended to [artifact]. */
    private fun embeddedBlockMapSize(artifact: File): Long =
        java.io.RandomAccessFile(artifact, "r").use { raf ->
            raf.seek(raf.length() - Int.SIZE_BYTES)
            raf.readInt().toLong()
        }

    private fun report(
        old: File,
        new: File,
        progress: DownloadProgress,
        bytesServed: Long,
        withBlockMapSize: Boolean,
    ) {
        println(
            String.format(
                Locale.ROOT,
                "%n%s → %s (manifest %s blockMapSize)%n  artifact: %,d bytes (previous: %,d)%n" +
                    "  differential: %b%n  transferred: %,d bytes (%.2f%% of the artifact)%n" +
                    "  served by the host: %,d bytes%n",
                old.name,
                new.name,
                if (withBlockMapSize) "with" else "without",
                new.length(),
                old.length(),
                progress.isDifferential,
                progress.bytesDownloaded,
                progress.bytesDownloaded * PERCENT / new.length().toDouble(),
                bytesServed,
            ),
        )
    }

    private fun sha512(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun property(name: String): String? =
        System.getProperty("nucleus.e2e.appimage.$name")?.takeIf { it.isNotBlank() }

    private class LinuxManifestProvider(
        private val baseUrl: String,
    ) : UpdateProvider {
        override fun getUpdateMetadataUrl(
            channel: String,
            platform: Platform,
        ): String = "$baseUrl/$MANIFEST"

        override fun getDownloadUrl(
            fileName: String,
            version: String,
        ): String = "$baseUrl/$fileName"
    }

    private companion object {
        const val MANIFEST = "latest-linux.yml"
        const val PERCENT = 100.0
        const val BUFFER = 64 * 1024
    }
}
