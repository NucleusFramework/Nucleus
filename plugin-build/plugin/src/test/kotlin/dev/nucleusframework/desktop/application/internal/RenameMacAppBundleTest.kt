package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * macOS volumes are case- and normalization-insensitive, so a destination that differs from the
 * source only in case or in Unicode form already resolves to the source directory. Clearing the
 * destination to make room would therefore erase the app image jpackage just produced.
 */
class RenameMacAppBundleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `renames a bundle to a different name`() {
        val from = bundle("NucleusDemo.app", marker = "content")
        val to = File(tmp.root, "Nucleus Demo.app")

        assertTrue(renameMacAppBundle(from, to))

        assertTrue(to.isDirectory)
        assertEquals("content", markerOf(to))
        assertEquals(listOf("Nucleus Demo.app"), names())
    }

    @Test
    fun `a case-only difference renames instead of deleting the bundle`() {
        assumeTrue("macOS-only", System.getProperty("os.name").startsWith("Mac"))
        val from = bundle("MyApp.app", marker = "content")
        val to = File(tmp.root, "myapp.app")

        assertTrue(renameMacAppBundle(from, to))

        assertEquals("content", markerOf(to))
        assertEquals(listOf("myapp.app"), names())
    }

    @Test
    fun `a normalization-only difference renames instead of deleting the bundle`() {
        assumeTrue("macOS-only", System.getProperty("os.name").startsWith("Mac"))
        val from = bundle("D\u00E9mo.app", marker = "content")
        val to = File(tmp.root, "De\u0301mo.app")

        assertTrue(renameMacAppBundle(from, to))

        assertEquals("content", markerOf(to))
        assertEquals(listOf("De\u0301mo.app"), names())
    }

    @Test
    fun `an unrelated bundle already at the destination is replaced`() {
        val from = bundle("NucleusDemo.app", marker = "new")
        bundle("Nucleus Demo.app", marker = "stale")
        val to = File(tmp.root, "Nucleus Demo.app")

        assertTrue(renameMacAppBundle(from, to))

        assertEquals("new", markerOf(to))
        assertEquals(listOf("Nucleus Demo.app"), names())
    }

    @Test
    fun `identical names are a no-op`() {
        val from = bundle("NucleusDemo.app", marker = "content")

        assertFalse(renameMacAppBundle(from, File(tmp.root, "NucleusDemo.app")))

        assertEquals("content", markerOf(from))
    }

    @Test
    fun `a missing source is a no-op`() {
        assertFalse(renameMacAppBundle(File(tmp.root, "Absent.app"), File(tmp.root, "Other.app")))
        assertEquals(emptyList<String>(), names())
    }

    private fun bundle(
        name: String,
        marker: String,
    ): File =
        File(tmp.root, name).apply {
            resolve("Contents").mkdirs()
            resolve("Contents/marker.txt").writeText(marker)
        }

    private fun markerOf(app: File): String = File(app, "Contents/marker.txt").readText()

    private fun names(): List<String> = tmp.root.listFiles().orEmpty().map { it.name }.sorted()
}
