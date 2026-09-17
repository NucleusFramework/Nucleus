package dev.nucleusframework.updater.delta

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.internal.delta.UpdateCache
import dev.nucleusframework.updater.provider.UpdateProvider
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
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * The macOS half of the real-artifact verification: two consecutive ZIPs (or DMGs) a real packaging
 * run produced, driven through the whole updater — manifest, block map, ranged requests, checksum,
 * cache — over a loopback host that counts the bytes it serves.
 *
 * Unlike [RealArtifactDeltaTest], which hands the updater a hand-built [dev.nucleusframework.updater.UpdateFile],
 * this test starts from a *published manifest* and runs `checkForUpdates()` first, so the manifest
 * shape is part of what is under test. Both shapes this project publishes are exercised:
 *
 *  - the `latest-mac.yml` electron-builder writes next to the artifacts, which carries no
 *    `blockMapSize`;
 *  - the one `.github/actions/generate-update-yml` writes at release time, which sets `blockMapSize`
 *    to the length of the standalone `<artifact>.blockmap` for every artifact that has one.
 *
 * Opt-in:
 * ```
 * ./gradlew :updater-runtime:test --tests '*RealMacArtifactDeltaTest*' \
 *     -Dnucleus.e2e.mac.old=/tmp/delta-macos/zip/v1.zip \
 *     -Dnucleus.e2e.mac.new=/tmp/delta-macos/zip/v2.zip \
 *     -Dnucleus.e2e.mac.out=/tmp/delta-macos/assembled
 * ```
 * The `<artifact>.blockmap` files are picked up next to the artifacts. `nucleus.e2e.mac.out` is
 * optional: when set, the assembled artifact is copied there so it can be unzipped and code-signature
 * checked outside the JVM.
 */
class RealMacArtifactDeltaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var server: RangeHttpServer? = null
    private val staged = mutableListOf<File>()

    @After
    fun tearDown() {
        server?.close()
        staged.forEach { file ->
            file.delete()
            File(file.parentFile, "${file.name}.asc").delete()
        }
    }

    @Test
    fun `the manifest electron-builder writes locally yields a differential download`() {
        runRealUpdate(withBlockMapSize = false)
    }

    @Test
    fun `the manifest the release workflow publishes yields a differential download`() {
        runRealUpdate(withBlockMapSize = true)
    }

    private fun runRealUpdate(withBlockMapSize: Boolean) {
        val old = property("old")?.let(::File)
        val new = property("new")?.let(::File)
        assumeTrue("real macOS artifacts not supplied", old != null && new != null)
        assertTrue("old artifact not found: $old", old!!.isFile)
        assertTrue("new artifact not found: $new", new!!.isFile)
        val oldBlockMap = File(old.parentFile, "${old.name}.blockmap")
        val newBlockMap = File(new.parentFile, "${new.name}.blockmap")
        assertTrue("no block map next to $old — the release publishes none for this format", oldBlockMap.isFile)
        assertTrue("no block map next to $new — the release publishes none for this format", newBlockMap.isFile)

        val running = RangeHttpServer().also { server = it }
        running.put("/${new.name}", new.readBytes())
        running.put("/${new.name}.blockmap", newBlockMap.readBytes())
        running.put("/$MANIFEST", manifest(new, newBlockMap, withBlockMapSize).toByteArray())

        val cacheDir = tmp.newFolder("cache")
        UpdateCache(cacheDir).store(old, old.name, version = "1.0.0", blockMapGzip = oldBlockMap.readBytes())

        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = MacManifestProvider(running.baseUrl)
                executableType = new.extension.lowercase()
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

        assertTrue(
            "the update must be differential (manifest ${if (withBlockMapSize) "with" else "without"} blockMapSize)",
            last.isDifferential,
        )
        assertEquals("assembled size", new.length(), last.file!!.length())
        assertEquals("assembled digest", sha512(new), sha512(last.file))
        assertTrue(
            "a delta that transfers the whole artifact is no delta",
            last.bytesDownloaded < new.length(),
        )
    }

    /** The manifest a release publishes for [artifact], in either of the two shapes this project emits. */
    private fun manifest(
        artifact: File,
        blockMap: File,
        withBlockMapSize: Boolean,
    ): String =
        buildString {
            appendLine("version: 2.0.0")
            appendLine("files:")
            appendLine("  - url: ${artifact.name}")
            appendLine("    sha512: ${sha512(artifact)}")
            appendLine("    size: ${artifact.length()}")
            // .github/actions/generate-update-yml sets blockMapSize to the standalone file's length.
            if (withBlockMapSize) appendLine("    blockMapSize: ${blockMap.length()}")
            appendLine("path: ${artifact.name}")
            appendLine("sha512: ${sha512(artifact)}")
            appendLine("releaseDate: '2026-01-01T00:00:00.000Z'")
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
        System.getProperty("nucleus.e2e.mac.$name")?.takeIf { it.isNotBlank() }

    private class MacManifestProvider(
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
        const val MANIFEST = "latest-mac.yml"
        const val PERCENT = 100.0
        const val BUFFER = 64 * 1024
    }
}
