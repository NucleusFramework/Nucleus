package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.buildMacZipUpdateScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage for the macOS ZIP update swap.
 *
 * Everything here is real: real `.app` bundles on disk, real archives produced by `ditto` the way
 * electron-builder produces them, and the real generated script executed by `bash`. The scenarios
 * mirror the bug this exists to prevent — a DMG installs `Nucleus Demo.app` while the ZIP ships
 * `NucleusDemo.app`, so the updater must not delete an app it cannot replace.
 */
class MacZipUpdateScriptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var installDir: File
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        assumeTrue("macOS-only", System.getProperty("os.name").startsWith("Mac"))
        installDir = tmp.newFolder("Applications")
        stagingDir = tmp.newFolder("staging")
    }

    @Test
    fun `zip bundle named differently updates the installed app in place`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        val zip = zipAppBundle(createAppBundle(stagingDir, "NucleusDemo", bundleId = APP_ID, marker = "v2"))

        val result = runUpdate(zip, installed)

        assertEquals(result.log, 0, result.exitCode)
        assertTrue("installed bundle must survive", installed.isDirectory)
        assertEquals("v2", markerOf(installed))
        assertFalse(
            "the archive's name must not appear as a second app",
            File(installDir, "NucleusDemo.app").exists(),
        )
        assertEquals(listOf("Nucleus Demo.app"), installDir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `matching names update normally`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        val zip = zipAppBundle(createAppBundle(stagingDir, "Nucleus Demo", bundleId = APP_ID, marker = "v2"))

        val result = runUpdate(zip, installed)

        assertEquals(result.log, 0, result.exitCode)
        assertEquals("v2", markerOf(installed))
        assertEquals(listOf("Nucleus Demo.app"), installDir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `a changed bundle identifier adopts the new name and removes the old bundle`() {
        val installed = createAppBundle(installDir, "Old Brand", bundleId = "dev.nucleus.old", marker = "v1")
        val zip = zipAppBundle(createAppBundle(stagingDir, "New Brand", bundleId = "dev.nucleus.new", marker = "v2"))

        val result = runUpdate(zip, installed)

        assertEquals(result.log, 0, result.exitCode)
        val renamed = File(installDir, "New Brand.app")
        assertTrue("renamed bundle must exist", renamed.isDirectory)
        assertEquals("v2", markerOf(renamed))
        assertFalse("the previous bundle must be gone", installed.exists())
        assertEquals(listOf("New Brand.app"), installDir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `an archive without an app bundle leaves the installed app untouched`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        val junk = tmp.newFolder("junk")
        File(junk, "readme.txt").writeText("not an app")
        val zip = zipDirectory(junk, File(tmp.root, "junk.zip"))

        val result = runUpdate(zip, installed)

        assertTrue("the update must fail", result.exitCode != 0)
        assertTrue("installed bundle must survive", installed.isDirectory)
        assertEquals("v1", markerOf(installed))
        assertEquals(listOf("Nucleus Demo.app"), installDir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `an incomplete app bundle leaves the installed app untouched`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        // A bundle directory without Contents/Info.plist: what a truncated archive produces.
        val broken = File(stagingDir, "NucleusDemo.app").apply { mkdirs() }
        File(broken, "Contents/MacOS").mkdirs()
        val zip = zipAppBundle(broken)

        val result = runUpdate(zip, installed)

        assertTrue("the update must fail", result.exitCode != 0)
        assertTrue("installed bundle must survive", installed.isDirectory)
        assertEquals("v1", markerOf(installed))
    }

    @Test
    fun `a corrupt archive leaves the installed app untouched`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        val zip = File(tmp.root, "corrupt.zip").apply { writeText("this is not a zip archive") }

        val result = runUpdate(zip, installed)

        assertTrue("the update must fail", result.exitCode != 0)
        assertTrue("installed bundle must survive", installed.isDirectory)
        assertEquals("v1", markerOf(installed))
    }

    @Test
    fun `an unreadable bundle identifier keeps the installed path`() {
        // No CFBundleIdentifier on either side: without an identifier to compare, the safe choice
        // is the installed path, never a rename.
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = null, marker = "v1")
        val zip = zipAppBundle(createAppBundle(stagingDir, "NucleusDemo", bundleId = null, marker = "v2"))

        val result = runUpdate(zip, installed)

        assertEquals(result.log, 0, result.exitCode)
        assertEquals("v2", markerOf(installed))
        assertEquals(listOf("Nucleus Demo.app"), installDir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `the staging directory is cleaned up on success and on failure`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        val zip = zipAppBundle(createAppBundle(stagingDir, "NucleusDemo", bundleId = APP_ID, marker = "v2"))
        runUpdate(zip, installed)
        assertTrue(installDir.listFiles()!!.none { it.name.startsWith(".nucleus-update-") })

        val corrupt = File(tmp.root, "corrupt2.zip").apply { writeText("nope") }
        runUpdate(corrupt, installed)
        assertTrue(installDir.listFiles()!!.none { it.name.startsWith(".nucleus-update-") })
    }

    @Test
    fun `the archive is deleted only after a successful install`() {
        val installed = createAppBundle(installDir, "Nucleus Demo", bundleId = APP_ID, marker = "v1")
        val zip = zipAppBundle(createAppBundle(stagingDir, "NucleusDemo", bundleId = APP_ID, marker = "v2"))
        runUpdate(zip, installed)
        assertFalse("a consumed archive must be removed", zip.exists())

        val corrupt = File(tmp.root, "corrupt3.zip").apply { writeText("nope") }
        runUpdate(corrupt, installed)
        assertTrue("a rejected archive must be kept for diagnostics", corrupt.exists())
    }

    // --- helpers ---

    private data class ScriptResult(
        val exitCode: Int,
        val log: String,
    )

    private fun runUpdate(
        zip: File,
        installedApp: File,
    ): ScriptResult {
        val logFile = File(tmp.root, "update-${System.nanoTime()}.log")
        val script = File(tmp.root, "nucleus-update-${System.nanoTime()}.sh")
        script.writeText(
            buildMacZipUpdateScript(
                zipFile = zip.absolutePath,
                appPath = installedApp.absolutePath,
                installDir = installDir.absolutePath,
                appPid = deadPid(),
                logFile = logFile.absolutePath,
                restart = false,
                selfDelete = false,
            ),
        )
        val process =
            ProcessBuilder("bash", script.absolutePath)
                .redirectErrorStream(true)
                .start()
        assertTrue("the update script must not hang", process.waitFor(60, TimeUnit.SECONDS))
        return ScriptResult(process.exitValue(), logFile.takeIf { it.isFile }?.readText().orEmpty())
    }

    /** PID of a process that has already exited, so the script's wait loop returns immediately. */
    private fun deadPid(): Long {
        val process = ProcessBuilder("true").start()
        val pid = process.pid()
        process.waitFor(10, TimeUnit.SECONDS)
        return pid
    }

    private fun createAppBundle(
        parent: File,
        name: String,
        bundleId: String?,
        marker: String,
    ): File {
        val app = File(parent, "$name.app")
        val contents = File(app, "Contents").apply { mkdirs() }
        File(contents, "MacOS").mkdirs()
        File(contents, "MacOS/launcher").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        File(contents, "marker.txt").writeText(marker)
        // Assembled line by line rather than through trimIndent(): the interpolated identifier is
        // itself multi-line, and trimIndent() runs after interpolation, so its indentation would
        // decide how much is stripped from every other line and quietly mangle the plist.
        val plist =
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine(
                    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
                )
                appendLine("""<plist version="1.0">""")
                appendLine("<dict>")
                if (bundleId != null) {
                    appendLine("\t<key>CFBundleIdentifier</key>")
                    appendLine("\t<string>$bundleId</string>")
                }
                appendLine("\t<key>CFBundleExecutable</key>")
                appendLine("\t<string>launcher</string>")
                appendLine("</dict>")
                appendLine("</plist>")
            }
        File(contents, "Info.plist").writeText(plist)
        return app
    }

    private fun markerOf(app: File): String = File(app, "Contents/marker.txt").readText()

    /** Archives a bundle the way electron-builder's macOS ZIP target does. */
    private fun zipAppBundle(app: File): File {
        val zip = File(tmp.root, "${app.nameWithoutExtension}-${System.nanoTime()}.zip")
        run("ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", app.absolutePath, zip.absolutePath)
        return zip
    }

    private fun zipDirectory(
        dir: File,
        zip: File,
    ): File {
        run("ditto", "-c", "-k", "--sequesterRsrc", dir.absolutePath, zip.absolutePath)
        return zip
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

    private companion object {
        const val APP_ID = "dev.nucleusframework.demo"
    }
}
