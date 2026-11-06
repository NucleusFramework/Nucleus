package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateYmlGeneratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logger = Logging.getLogger(UpdateYmlGeneratorTest::class.java)

    @Test
    fun `a packaging output without a publish provider becomes a complete local feed`() {
        val dir = tmp.newFolder("nsis")
        File(dir, "app-1.1.0-win-x64-nsis.exe").writeBytes(ByteArray(1000) { it.toByte() })
        File(dir, "app-1.1.0-win-x64-nsis.exe.blockmap").writeBytes(ByteArray(10))
        File(dir, "nucleus-installer.nsh").writeText("; build leftover")
        File(dir, "builder-debug.yml").writeText("x: 1")
        File(dir, "package.json").writeText("{}")

        UpdateYmlGenerator.generateIfMissing(dir, "latest.yml", "1.1.0", logger, artifactExtension = "exe")

        val yml = File(dir, "latest.yml").readText()
        assertTrue(yml, yml.startsWith("version: 1.1.0\n"))
        assertTrue(yml, yml.contains("  - url: app-1.1.0-win-x64-nsis.exe\n"))
        assertTrue(yml, yml.contains("    size: 1000\n"))
        assertFalse("build leftovers are no artifact: $yml", yml.contains("nsh"))
        assertEquals("one artifact listed", 1, Regex("- url:").findAll(yml).count())
    }

    @Test
    fun `the installer of a previous version left in the output is not listed`() {
        val dir = tmp.newFolder("bumped")
        File(dir, "app-1.0.0-win-x64-nsis.exe").writeBytes(ByteArray(10))
        File(dir, "app-11.1.0-win-x64-nsis.exe").writeBytes(ByteArray(10))
        File(dir, "app-1.1.0.1-win-x64-nsis.exe").writeBytes(ByteArray(10))
        File(dir, "app-1.1.0-win-x64-nsis.exe").writeBytes(ByteArray(20))

        UpdateYmlGenerator.generateIfMissing(dir, "latest.yml", "1.1.0", logger, artifactExtension = "exe")

        val urls = Regex("- url: (.*)").findAll(File(dir, "latest.yml").readText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("app-1.1.0-win-x64-nsis.exe"), urls)
    }

    @Test
    fun `an artifact name without the version keeps the newest artifact`() {
        val old = tmp.newFile("MyApp.exe.old.exe").apply { setLastModified(1_000_000) }
        val current = tmp.newFile("MyApp.exe").apply { setLastModified(2_000_000) }
        assertEquals(listOf(current), UpdateYmlGenerator.currentArtifacts(listOf(old, current), "2.0.0"))
        assertEquals(emptyList<File>(), UpdateYmlGenerator.currentArtifacts(emptyList(), "2.0.0"))
    }

    @Test
    fun `a new packaging run starts without the previous run's manifests`() {
        val dir = tmp.newFolder("rerun")
        listOf("latest.yml", "beta-mac.yml", "alpha-linux.yml").forEach { File(dir, it).writeText("version: 1.0.0\n") }
        File(dir, "builder-debug.yml").writeText("x: 1")
        File(dir, "app-1.0.0.exe").writeText("x")
        UpdateYmlPublish.deleteManifests(dir)
        assertEquals(setOf("builder-debug.yml", "app-1.0.0.exe"), dir.list()!!.toSet())
    }

    @Test
    fun `a manifest electron-builder wrote is left alone`() {
        val dir = tmp.newFolder("dmg")
        File(dir, "app.dmg").writeBytes(ByteArray(3))
        File(dir, "latest-mac.yml").writeText("version: 9.9.9\n")
        UpdateYmlGenerator.generateIfMissing(dir, "latest-mac.yml", "1.0.0", logger, artifactExtension = "dmg")
        assertEquals("version: 9.9.9\n", File(dir, "latest-mac.yml").readText())
    }

    @Test
    fun `every self-contained updatable format names its artifact`() {
        assertEquals("exe", TargetFormat.Nsis.updateArtifactExtension)
        assertEquals("msi", TargetFormat.Msi.updateArtifactExtension)
        assertEquals("AppImage", TargetFormat.AppImage.updateArtifactExtension)
        assertEquals("deb", TargetFormat.Deb.updateArtifactExtension)
        assertNull("NSIS-Web's packages live on its publish host", TargetFormat.NsisWeb.updateArtifactExtension)
        assertNull(TargetFormat.Flatpak.updateArtifactExtension)
    }
}
