package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.gradle.api.logging.Logging
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun `bundles the runtime next to the app image executable only`() {
        val crt = tmpDir.newFolder("crt")
        MsvcRedistLocator.CPP_RUNTIME_DLLS.forEach { File(crt, it).writeText(it) }
        val destination = tmpDir.newFolder("app-image")
        val appRoot = File(destination, "MyApp").apply { mkdirs() }
        File(appRoot, "MyApp.exe").writeText("")
        val runtimeBin = File(appRoot, "runtime/bin").apply { mkdirs() }

        MsvcRedistLocator.bundleIntoAppImage(
            destination,
            MsvcRedistLocator.CPP_RUNTIME_DLLS,
            Logging.getLogger(MsvcRedistLocatorTest::class.java),
            crt,
        )

        MsvcRedistLocator.CPP_RUNTIME_DLLS.forEach { dll -> assertTrue(dll, File(appRoot, dll).isFile) }
        assertTrue(runtimeBin.list().orEmpty().isEmpty())
    }
}
