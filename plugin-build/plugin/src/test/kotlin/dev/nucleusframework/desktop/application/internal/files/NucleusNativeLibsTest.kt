package dev.nucleusframework.desktop.application.internal.files

import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import org.junit.Assert.assertEquals
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

    private fun jarWithManifest(
        manifestEntries: List<String>,
        vararg entries: String,
    ): File =
        tmp.newFile("nucleus-module.jar").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/nucleus/native-libraries/nucleus.foo"))
                zip.write(manifestEntries.joinToString("\n", postfix = "\n").toByteArray())
                zip.closeEntry()
                for (entry in entries) {
                    zip.putNextEntry(ZipEntry(entry))
                    if (!entry.endsWith("/")) zip.write(entry.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `moves the listed current platform libraries out and drops the other listed ones`() {
        val source =
            jarWithManifest(
                listOf(
                    "nucleus/native/win32-x64/nucleus_foo.dll",
                    "nucleus/native/win32-x64/libGLESv2.dll",
                    "nucleus/native/win32-aarch64/nucleus_foo.dll",
                    "nucleus/native/linux-x64/libnucleus_foo.so",
                ),
                "dev/nucleusframework/Foo.class",
                "nucleus/native/win32-x64/",
                "nucleus/native/win32-x64/nucleus_foo.dll",
                "nucleus/native/win32-x64/libGLESv2.dll",
                "nucleus/native/win32-aarch64/nucleus_foo.dll",
                "nucleus/native/linux-x64/libnucleus_foo.so",
                "META-INF/MANIFEST.MF",
            )
        val out = tmp.newFolder("out")

        val files =
            unpackNucleusNativeLibs(
                source,
                out.resolve(source.name),
                out,
                "win32-x64",
                source.nucleusNativeEntries(),
            )

        assertEquals(
            listOf(
                "META-INF/nucleus/native-libraries/nucleus.foo",
                "dev/nucleusframework/Foo.class",
                "nucleus/native/win32-x64/",
                "META-INF/MANIFEST.MF",
            ),
            files.first().entryNames(),
        )
        assertEquals(setOf("nucleus_foo.dll", "libGLESv2.dll"), files.drop(1).map { it.name }.toSet())
        assertEquals("nucleus/native/win32-x64/nucleus_foo.dll", out.resolve("nucleus_foo.dll").readText())
    }

    @Test
    fun `leaves unlisted nucleus native entries untouched`() {
        // An application's own library, read as a resource (e.g. through FFM), must stay in its JAR
        val source =
            jarWithManifest(
                listOf("nucleus/native/darwin-aarch64/libnucleus_foo.dylib"),
                "nucleus/native/darwin-aarch64/libnucleus_foo.dylib",
                "nucleus/native/darwin-aarch64/libapp_bridge.dylib",
                "nucleus/native/darwin-x64/libapp_bridge.dylib",
            )
        val out = tmp.newFolder("out")

        val files =
            unpackNucleusNativeLibs(
                source,
                out.resolve(source.name),
                out,
                "darwin-aarch64",
                source.nucleusNativeEntries(),
            )

        assertEquals(
            listOf(
                "META-INF/nucleus/native-libraries/nucleus.foo",
                "nucleus/native/darwin-aarch64/libapp_bridge.dylib",
                "nucleus/native/darwin-x64/libapp_bridge.dylib",
            ),
            files.first().entryNames(),
        )
        assertEquals(listOf("libnucleus_foo.dylib"), files.drop(1).map { it.name })
    }

    @Test
    fun `moves the libraries another module lists out of a jar without a manifest`() {
        // decorated-window-tao lists ANGLE, which ships in its own artifact
        val nucleusEntries =
            jarWithManifest(listOf("nucleus/native/win32-x64/libGLESv2.dll")).nucleusNativeEntries()
        val angle = namedJar("angle.jar", "nucleus/native/win32-x64/libGLESv2.dll", "nucleus/native/win32-x64/NOTICE")
        val out = tmp.newFolder("out")

        val files = unpackNucleusNativeLibs(angle, out.resolve(angle.name), out, "win32-x64", nucleusEntries)

        assertEquals(listOf("nucleus/native/win32-x64/NOTICE"), files.first().entryNames())
        assertEquals(listOf("libGLESv2.dll"), files.drop(1).map { it.name })
    }

    @Test
    fun `jars without a manifest declare no nucleus libraries`() {
        assertTrue(jar("nucleus/native/linux-x64/libapp.so").nucleusNativeEntries().isEmpty())
        assertEquals(
            setOf("nucleus/native/linux-x64/libnucleus_foo.so"),
            jarWithManifest(listOf("", "# comment", "nucleus/native/linux-x64/libnucleus_foo.so"))
                .nucleusNativeEntries(),
        )
    }
}
