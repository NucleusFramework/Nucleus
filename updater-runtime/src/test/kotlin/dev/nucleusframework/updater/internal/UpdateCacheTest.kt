package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.updater.internal.delta.CachedArtifact
import dev.nucleusframework.updater.internal.delta.UpdateCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `store then read returns the metadata and bytes`() {
        val cache = UpdateCache(tmp.newFolder("cache"))
        val source = tmp.newFile("App-2.0.0.zip").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val blockMap = byteArrayOf(1, 2, 3, 4)

        cache.store(source, "App-2.0.0.zip", "2.0.0", blockMap)

        val meta = cache.read()
        assertEquals(CachedArtifact("App-2.0.0.zip", "2.0.0"), meta)
        assertEquals("zip", meta!!.extension)
        assertArrayEquals(byteArrayOf(9, 8, 7), cache.artifact.readBytes())
        assertArrayEquals(blockMap, cache.blockMap.readBytes())
    }

    @Test
    fun `store without a block map leaves the block map file missing`() {
        val cache = UpdateCache(tmp.newFolder("cache"))
        val source = tmp.newFile("App.AppImage").apply { writeText("image") }
        cache.store(source, "App.AppImage", "1.1.0", null)

        assertEquals(CachedArtifact("App.AppImage", "1.1.0"), cache.read())
        assertEquals("appimage", cache.read()!!.extension)
        assertFalse(cache.blockMap.exists())
    }

    @Test
    fun `read returns null when the cache is empty or the artifact is empty`() {
        val dir = tmp.newFolder("empty")
        val cache = UpdateCache(dir)
        assertNull(cache.read())

        cache.artifact.writeText("")
        FileMeta.write(dir, "fileName=App.zip", "version=1.0.0")
        assertNull(cache.read())
    }

    @Test
    fun `read returns null when fileName is missing`() {
        val dir = tmp.newFolder("nofilename")
        val cache = UpdateCache(dir)
        cache.artifact.writeText("bytes")
        FileMeta.write(dir, "version=1.0.0")
        assertNull(cache.read())
    }

    @Test
    fun `clear deletes artifact block map and metadata`() {
        val cache = UpdateCache(tmp.newFolder("cache"))
        val source = tmp.newFile("App.zip").apply { writeText("payload") }
        cache.store(source, "App.zip", "3.0.0", byteArrayOf(5))
        cache.clear()
        assertFalse(cache.artifact.exists())
        assertFalse(cache.blockMap.exists())
        assertNull(cache.read())
    }

    @Test
    fun `default cache path is namespaced by the app id`() {
        val cache = UpdateCache.default()
        assertTrue(cache.artifact.path.contains("nucleus"))
        assertTrue(cache.artifact.path.contains("updates"))
        assertTrue(cache.artifact.path.contains(NucleusApp.appId))
        assertEquals("current-artifact", cache.artifact.name)
    }

    @Test
    fun `CachedArtifact extension is the lowercase suffix`() {
        assertEquals("dmg", CachedArtifact("App.DMG", "1").extension)
        assertEquals("", CachedArtifact("no-suffix", "1").extension)
    }

    private object FileMeta {
        fun write(
            dir: java.io.File,
            vararg lines: String,
        ) {
            java.io.File(dir, "current.meta").writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}
