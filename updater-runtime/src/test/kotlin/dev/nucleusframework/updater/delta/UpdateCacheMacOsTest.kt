package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.internal.delta.UpdateCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * The cache claims to cost no extra disk space by hard-linking the artifact it keeps. On macOS that
 * means APFS, where a link is only possible within the same volume — so this checks the claim on the
 * filesystem the tests actually run on rather than assuming it.
 */
class UpdateCacheMacOsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the cached artifact is a hard link to the staged one, not a second copy`() {
        val staged = File(tmp.newFolder("staged"), "MyApp-1.0.0.zip").apply { writeBytes(DeltaFixtures.v1()) }
        val cache = UpdateCache(tmp.newFolder("cache"))

        cache.store(staged, staged.name, version = "1.0.0", blockMapGzip = DeltaFixtures.blockMapGzip("v1"))

        assertTrue("the artifact must be cached", cache.artifact.isFile)
        assertEquals("same length", staged.length(), cache.artifact.length())
        val stagedKey = Files.readAttributes(staged.toPath(), BasicFileAttributes::class.java).fileKey()
        val cachedKey = Files.readAttributes(cache.artifact.toPath(), BasicFileAttributes::class.java).fileKey()
        assertEquals("the cache must hard-link the artifact, not copy it", stagedKey, cachedKey)
    }

    @Test
    fun `the artifact survives in the cache after the installer consumes the staged file`() {
        val staged = File(tmp.newFolder("staged"), "MyApp-1.0.0.zip").apply { writeBytes(DeltaFixtures.v1()) }
        val cache = UpdateCache(tmp.newFolder("cache"))
        cache.store(staged, staged.name, version = "1.0.0", blockMapGzip = DeltaFixtures.blockMapGzip("v1"))

        // Every platform installer deletes the file it consumed; the cached link must outlive it.
        assertTrue("staged file must be removable", staged.delete())

        assertTrue(cache.artifact.isFile)
        assertTrue(DeltaFixtures.v1().contentEquals(cache.artifact.readBytes()))
        assertEquals("MyApp-1.0.0.zip", cache.read()?.fileName)
    }
}
