package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Verifies the content-addressed cache guarantees that fix issue #304:
 * different library versions must never share an extraction path, and an
 * already-extracted file must never be replaced. Also covers the cache
 * directory resolution order of issue #303.
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

    @Test
    fun `the system property is preferred, the override kept as a fallback`() {
        val fromProperty = Path.of("/tmp/from-property")
        val override = Path.of("/tmp/from-override")

        assertEquals(
            listOf(fromProperty, override),
            NativeLibraryLoader.requestedCacheDirs(fromProperty.toString(), override),
        )
    }

    @Test
    fun `programmatic override applies when the property is absent or blank`() {
        val override = Path.of("/tmp/from-override")

        assertEquals(listOf(override), NativeLibraryLoader.requestedCacheDirs(null, override))
        assertEquals(listOf(override), NativeLibraryLoader.requestedCacheDirs("  ", override))
    }

    @Test
    fun `no configuration means platform default`() {
        assertEquals(emptyList<Path>(), NativeLibraryLoader.requestedCacheDirs(null, null))
        assertEquals(emptyList<Path>(), NativeLibraryLoader.requestedCacheDirs("", null))
    }

    @Test
    fun `a property the platform cannot parse is dropped, not fatal`() {
        val override = Path.of("/tmp/from-override")

        assertEquals(
            listOf(override),
            NativeLibraryLoader.requestedCacheDirs("/tmp/bad" + '\u0000' + "dir", override),
        )
    }

    @Test
    fun `relative property path is resolved against the working directory`() {
        val resolved = NativeLibraryLoader.requestedCacheDirs("native-cache", null)

        assertEquals(listOf(Path.of("native-cache").toAbsolutePath().normalize()), resolved)
        assertTrue(resolved.single().isAbsolute)
    }

    @Test
    fun `default cache dir follows the platform conventions`() {
        // The env values are absolute for the *test* file system: a real
        // `C:\Users\...` is not absolute to the Linux/macOS provider running CI.
        val env = mapOf("LOCALAPPDATA" to "/appdata/local", "XDG_CACHE_HOME" to "/xdg/cache")

        assertEquals(
            Path.of("/appdata/local", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Windows 11", "/home/me", env::get),
        )
        assertEquals(
            Path.of("/home/me", "Library", "Caches", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Mac OS X", "/home/me", env::get),
        )
        assertEquals(
            Path.of("/xdg/cache", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Linux", "/home/me", env::get),
        )
        assertEquals(
            Path.of("/home/me", ".cache", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Linux", "/home/me") { null },
        )
    }

    @Test
    fun `cacheDirectory is settable before the first extraction`() {
        val dir = Files.createTempDirectory("nucleus-cache-dir")
        try {
            // The loader is a process-wide singleton: another test may already
            // have extracted a library and latched the root.
            NativeLibraryLoader.resetCacheDirForTesting()
            NativeLibraryLoader.cacheDirectory = dir
            assertEquals(dir, NativeLibraryLoader.cacheDirectory)
        } finally {
            NativeLibraryLoader.resetCacheDirForTesting()
            dir.toFile().deleteRecursively()
        }
        assertNull(NativeLibraryLoader.cacheDirectory)
    }

    @Test
    fun `blank or relative cache environment variables are ignored`() {
        // A set-but-empty XDG_CACHE_HOME (or a relative one, which the XDG spec
        // says to ignore) must not put the cache under the working directory.
        assertEquals(
            Path.of("/home/me", ".cache", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Linux", "/home/me", mapOf("XDG_CACHE_HOME" to "")::get),
        )
        assertEquals(
            Path.of("/home/me", ".cache", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Linux", "/home/me", mapOf("XDG_CACHE_HOME" to "relative/dir")::get),
        )
        assertEquals(
            Path.of("/home/me", "AppData", "Local", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Windows 11", "/home/me", mapOf("LOCALAPPDATA" to "  ")::get),
        )
        assertEquals(
            Path.of("/home/me", "AppData", "Local", "nucleus", "native"),
            NativeLibraryLoader.defaultCacheDir("Windows 11", "/home/me", mapOf("LOCALAPPDATA" to "rel/dir")::get),
        )
    }

    @Test
    fun `cacheRoot uses the configured directory and latches it`() {
        val dir = Files.createTempDirectory("nucleus-root")
        try {
            NativeLibraryLoader.resetCacheDirForTesting()
            NativeLibraryLoader.cacheDirectory = dir

            assertEquals(dir, NativeLibraryLoader.cacheRoot())
            // No probe file survives the check.
            assertEquals(emptyList<Path>(), Files.list(dir).use { it.toList() })

            // Latched: a later assignment cannot move libraries already loaded.
            NativeLibraryLoader.cacheDirectory = Files.createTempDirectory("nucleus-late")
            assertEquals(dir, NativeLibraryLoader.cacheRoot())
        } finally {
            NativeLibraryLoader.resetCacheDirForTesting()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an unusable configured directory falls back to the next candidate`() {
        val readOnly = Files.createTempDirectory("nucleus-ro")
        val fallback = Files.createTempDirectory("nucleus-fallback")
        try {
            readOnly.toFile().setWritable(false)
            NativeLibraryLoader.resetCacheDirForTesting()
            NativeLibraryLoader.cacheDirectory = fallback
            System.setProperty(NativeLibraryLoader.CACHE_DIR_PROPERTY, readOnly.resolve("sub").toString())

            // The property naming an unwritable directory must not discard the
            // directory the application set itself (the 3-level chain of #303).
            assertEquals(fallback, NativeLibraryLoader.cacheRoot())
        } finally {
            System.clearProperty(NativeLibraryLoader.CACHE_DIR_PROPERTY)
            NativeLibraryLoader.resetCacheDirForTesting()
            readOnly.toFile().setWritable(true)
            readOnly.toFile().deleteRecursively()
            fallback.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cacheRoot falls back to the platform default when nothing is configured`() {
        try {
            NativeLibraryLoader.resetCacheDirForTesting()
            assertEquals(NativeLibraryLoader.defaultCacheDir(), NativeLibraryLoader.cacheRoot())
        } finally {
            NativeLibraryLoader.resetCacheDirForTesting()
        }
    }
}
