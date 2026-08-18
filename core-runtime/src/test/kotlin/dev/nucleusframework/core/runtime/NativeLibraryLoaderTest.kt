package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Verifies the content-addressed cache guarantees that fix issue #304:
 * different library versions must never share an extraction path, and an
 * already-extracted file must never be replaced.
 */
class NativeLibraryLoaderTest {
    @Test
    fun `different content yields different path-safe fingerprints`() {
        val dir = Files.createTempDirectory("nucleus-fp")
        val a = dir.resolve("a.bin").apply { writeText("version one") }
        val b = dir.resolve("b.bin").apply { writeText("version two, longer") }

        val fpA = NativeLibraryLoader.resolveFingerprint(a.toUri().toURL())
        val fpB = NativeLibraryLoader.resolveFingerprint(b.toUri().toURL())

        assertNotEquals(fpA, fpB)
        // Must be usable as a directory name on all platforms (':' is illegal on Windows)
        assertTrue(fpA.matches(Regex("[0-9-]+")))
    }

    @Test
    fun `extractIfAbsent never replaces an existing file`() {
        val dir = Files.createTempDirectory("nucleus-extract")
        val source = dir.resolve("source.bin").apply { writeText("new bytes") }
        val target = dir.resolve("lib.so").apply { writeText("already extracted") }

        val loadPath = NativeLibraryLoader.extractIfAbsent(source.toUri().toURL(), target)

        assertEquals(target, loadPath)
        assertEquals("already extracted", target.readText())
    }

    @Test
    fun `load returns false for a library that is not on this platform`() {
        assertEquals(
            false,
            NativeLibraryLoader.load("nucleus_does_not_exist_kover", NativeLibraryLoaderTest::class.java),
        )
    }

    @Test
    fun `extractIfAbsent extracts when target is missing`() {
        val dir = Files.createTempDirectory("nucleus-extract")
        val source = dir.resolve("source.bin").apply { writeText("library bytes") }
        val target = dir.resolve("lib.so")

        val loadPath = NativeLibraryLoader.extractIfAbsent(source.toUri().toURL(), target)

        assertEquals("library bytes", loadPath.readText())
    }
}
