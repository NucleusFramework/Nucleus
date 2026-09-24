package dev.nucleusframework.desktop.application.internal.files

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NucleusNativeLibsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun jar(vararg entries: String): File = namedJar("module.jar", *entries)

    private fun namedJar(
        name: String,
        vararg entries: String,
    ): File =
        tmp.newFile(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                for (entry in entries) {
                    zip.putNextEntry(ZipEntry(entry))
                    if (!entry.endsWith("/")) zip.write(entry.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun File.entryNames(): List<String> =
        ZipFile(this).use { zip -> zip.entries().asSequence().map { it.name }.toList() }

    @Test
    fun `platform dirs follow the runtime resource layout`() {
        assertEquals("win32-x64", nucleusNativeDir(OS.Windows, Arch.X64))
        assertEquals("darwin-aarch64", nucleusNativeDir(OS.MacOS, Arch.Arm64))
        assertEquals("linux-aarch64", nucleusNativeDir(OS.Linux, Arch.Arm64))
    }

    @Test
    fun `moves the current platform out and drops the others`() {
        val source =
            jar(
                "dev/nucleusframework/Foo.class",
                "nucleus/native/",
                "nucleus/native/win32-x64/",
                "nucleus/native/win32-x64/nucleus_foo.dll",
                "nucleus/native/win32-x64/WebView2Loader.dll",
                "nucleus/native/win32-aarch64/nucleus_foo.dll",
                "nucleus/native/linux-x64/libnucleus_foo.so",
                "META-INF/MANIFEST.MF",
            )
        val out = tmp.newFolder("out")

        val files = unpackNucleusNativeLibs(source, out.resolve(source.name), out, "win32-x64")

        val rewritten = files.first()
        assertEquals(listOf("dev/nucleusframework/Foo.class", "META-INF/MANIFEST.MF"), rewritten.entryNames())
        assertEquals(
            setOf("nucleus_foo.dll", "WebView2Loader.dll"),
            files.drop(1).map { it.name }.toSet(),
        )
        assertEquals("nucleus/native/win32-x64/nucleus_foo.dll", out.resolve("nucleus_foo.dll").readText())
    }

    @Test
    fun `keeps what the loader cannot resolve from a flat directory`() {
        val source = jar("nucleus/native/win32-x64/nested/data.bin")
        val out = tmp.newFolder("out")

        val files = unpackNucleusNativeLibs(source, out.resolve(source.name), out, "win32-x64")

        assertEquals(listOf("nucleus/native/win32-x64/nested/data.bin"), files.single().entryNames())
    }

    @Test
    fun `detects jars carrying nucleus natives`() {
        assertTrue(jar("nucleus/native/linux-x64/libnucleus_foo.so").containsNucleusNativeLibs())
        assertFalse(namedJar("plain.jar", "dev/nucleusframework/Foo.class").containsNucleusNativeLibs())
    }
}
