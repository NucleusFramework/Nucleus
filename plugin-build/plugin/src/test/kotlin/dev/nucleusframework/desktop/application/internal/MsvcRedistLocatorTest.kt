package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.gradle.api.logging.Logging
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MsvcRedistLocatorTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun crtDir(
        root: File,
        year: String,
        edition: String,
        version: String,
        arch: String = "x64",
        crt: String = "Microsoft.VC143.CRT",
    ): File = File(root, "$year/$edition/VC/Redist/MSVC/$version/$arch/$crt").apply { mkdirs() }

    @Test
    fun `picks the newest redist version across years and editions`() {
        val root = tmpDir.newFolder("Microsoft Visual Studio")
        crtDir(root, "2019", "BuildTools", "14.29.30133", crt = "Microsoft.VC142.CRT")
        val newest = crtDir(root, "2022", "Community", "14.42.34433")
        crtDir(root, "2022", "Enterprise", "14.38.33135")

        assertEquals(newest, MsvcRedistLocator.locateCrtDir(listOf(root), "x64"))
    }

    @Test
    fun `compares versions numerically, not lexically`() {
        val root = tmpDir.newFolder("Microsoft Visual Studio")
        crtDir(root, "2022", "Community", "14.9.0")
        val newest = crtDir(root, "2022", "Community", "14.10.0")

        assertEquals(newest, MsvcRedistLocator.locateCrtDir(listOf(root), "x64"))
    }

    @Test
    fun `skips non-version folders and other architectures`() {
        val root = tmpDir.newFolder("Microsoft Visual Studio")
        File(root, "2022/Community/VC/Redist/MSVC/v143/x64/Microsoft.VC143.CRT").mkdirs()
        crtDir(root, "2022", "Community", "14.42.34433", arch = "arm64")

        assertNull(MsvcRedistLocator.locateCrtDir(listOf(root), "x64"))
    }

    @Test
    fun `returns null without Visual Studio`() {
        assertNull(MsvcRedistLocator.locateCrtDir(listOf(File(tmpDir.root, "missing")), "x64"))
    }

    @Test
    fun `reads the PE file version`() {
        val dll = fakeDll(tmpDir.newFolder("bin"), "msvcp140.dll", listOf(14, 42, 34438, 0))

        assertEquals(listOf(14, 42, 34438, 0), MsvcRedistLocator.peFileVersion(dll))
        assertNull(MsvcRedistLocator.peFileVersion(tmpDir.newFile("plain.txt")))
    }

    @Test
    fun `replaces the JDK's runtime in place and adds the rest next to the executable`() {
        val (crt, appRoot, runtimeBin) = appImage(redist = listOf(14, 44, 0, 0), jdk = listOf(14, 42, 0, 0))

        bundle(crt, appRoot.parentFile)

        val jdkShipped = listOf("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll")
        jdkShipped.forEach { dll ->
            assertEquals(dll, File(crt, dll).readText(), File(runtimeBin, dll).readText())
            assertFalse(dll, File(appRoot, dll).exists())
        }
        (MsvcRedistLocator.CPP_RUNTIME_DLLS - jdkShipped.toSet()).forEach { dll ->
            assertTrue(dll, File(appRoot, dll).isFile)
            assertFalse(dll, File(runtimeBin, dll).exists())
        }
    }

    @Test
    fun `keeps a newer JDK runtime`() {
        val (crt, appRoot, runtimeBin) = appImage(redist = listOf(14, 42, 0, 0), jdk = listOf(14, 44, 0, 0))
        val jdkRuntime = File(runtimeBin, "msvcp140.dll").readText()

        bundle(crt, appRoot.parentFile)

        assertEquals(jdkRuntime, File(runtimeBin, "msvcp140.dll").readText())
        assertTrue(File(appRoot, "msvcp140_1.dll").isFile)
    }

    private fun bundle(
        crt: File,
        destination: File,
    ) = MsvcRedistLocator.bundleIntoAppImage(
        destination,
        MsvcRedistLocator.CPP_RUNTIME_DLLS,
        Logging.getLogger(MsvcRedistLocatorTest::class.java),
        crt,
    )

    /** A redistributable with every DLL and a jpackage image whose JDK ships msvcp140 and vcruntime140*. */
    private fun appImage(
        redist: List<Int>,
        jdk: List<Int>,
    ): Triple<File, File, File> {
        val crt = tmpDir.newFolder("crt")
        MsvcRedistLocator.CPP_RUNTIME_DLLS.forEach { fakeDll(crt, it, redist) }
        val appRoot = File(tmpDir.newFolder("app-image"), "MyApp").apply { mkdirs() }
        File(appRoot, "MyApp.exe").writeText("")
        val runtimeBin = File(appRoot, "runtime/bin").apply { mkdirs() }
        listOf("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll").forEach { fakeDll(runtimeBin, it, jdk) }
        return Triple(crt, appRoot, runtimeBin)
    }

    /** A file holding a `VS_FIXEDFILEINFO` with [version], after a name-and-version tag. */
    private fun fakeDll(
        dir: File,
        name: String,
        version: List<Int>,
    ): File {
        val info =
            ByteBuffer
                .allocate(FIXED_FILE_INFO_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xFEEF04BD.toInt())
                .putInt(0x00010000)
                .putInt((version[0] shl 16) or version[1])
                .putInt((version[2] shl 16) or version[3])
                .array()
        return File(dir, name).apply { writeBytes("$name@$version|".toByteArray() + info) }
    }

    private companion object {
        const val FIXED_FILE_INFO_SIZE = 16
    }
}
