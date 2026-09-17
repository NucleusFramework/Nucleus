package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.buildMacZipUpdateScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Full release loop against artifacts a real build produced: install from the DMG, then update from
 * the ZIP of the next version.
 *
 * Opt-in — the artifacts are supplied by the packaging E2E harness:
 * ```
 * ./gradlew :updater-runtime:test --tests '*MacRealArtifactUpdateTest*' \
 *     -Dnucleus.e2e.dmg=/path/to/v1.dmg -Dnucleus.e2e.zip=/path/to/v2.zip \
 *     -Dnucleus.e2e.expectedVersion=2.0.0
 * ```
 */
class MacRealArtifactUpdateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an app installed from the DMG updates from the ZIP of the next version`() {
        assumeTrue("macOS-only", System.getProperty("os.name").startsWith("Mac"))
        val dmg = System.getProperty("nucleus.e2e.dmg")?.let(::File)
        val zip = System.getProperty("nucleus.e2e.zip")?.let(::File)
        assumeTrue("real artifacts not supplied", dmg != null && zip != null)
        val expectedVersion = System.getProperty("nucleus.e2e.expectedVersion") ?: "2.0.0"

        val installDir = tmp.newFolder("Applications")
        val installed = installFromDmg(dmg!!, installDir)
        println("Installed from DMG: ${installed.name} (version ${versionOf(installed)})")

        // The updater consumes the archive, so hand it a copy.
        val archive = File(tmp.root, zip!!.name).also { zip.copyTo(it) }
        val exitCode = runUpdateScript(archive, installed, installDir)

        assertEquals(0, exitCode)
        val bundles = installDir.listFiles()!!.filter { it.name.endsWith(".app") }
        assertEquals("exactly one bundle must remain", 1, bundles.size)
        assertEquals("the installed bundle must keep its name", installed.name, bundles.single().name)
        assertEquals("the bundle must be the new version", expectedVersion, versionOf(bundles.single()))
        assertTrue("the launcher must still be there", launcherOf(bundles.single()).canExecute())
    }

    private fun installFromDmg(
        dmg: File,
        installDir: File,
    ): File {
        val mountPoint = tmp.newFolder("mnt-${System.nanoTime()}")
        run("hdiutil", "attach", dmg.absolutePath, "-nobrowse", "-readonly", "-mountpoint", mountPoint.absolutePath)
        try {
            val app =
                mountPoint.listFiles()!!.single { it.isDirectory && it.name.endsWith(".app") }
            // What dragging the app onto /Applications does.
            run("ditto", app.absolutePath, File(installDir, app.name).absolutePath)
            return File(installDir, app.name)
        } finally {
            run("hdiutil", "detach", mountPoint.absolutePath)
        }
    }

    private fun runUpdateScript(
        archive: File,
        installedApp: File,
        installDir: File,
    ): Int {
        val logFile = File(tmp.root, "update.log")
        val script = File(tmp.root, "nucleus-update.sh")
        script.writeText(
            buildMacZipUpdateScript(
                zipFile = archive.absolutePath,
                appPath = installedApp.absolutePath,
                installDir = installDir.absolutePath,
                appPid = deadPid(),
                logFile = logFile.absolutePath,
                restart = false,
                selfDelete = false,
            ),
        )
        val process = ProcessBuilder("bash", script.absolutePath).redirectErrorStream(true).start()
        assertTrue("the update script must not hang", process.waitFor(120, TimeUnit.SECONDS))
        println(logFile.takeIf { it.isFile }?.readText().orEmpty())
        return process.exitValue()
    }

    private fun deadPid(): Long {
        val process = ProcessBuilder("true").start()
        val pid = process.pid()
        process.waitFor(10, TimeUnit.SECONDS)
        return pid
    }

    private fun versionOf(app: File): String = plistBuddy(app, "CFBundleShortVersionString")

    private fun launcherOf(app: File): File = File(app, "Contents/MacOS/${plistBuddy(app, "CFBundleExecutable")}")

    private fun plistBuddy(
        app: File,
        key: String,
    ): String {
        val process =
            ProcessBuilder("/usr/libexec/PlistBuddy", "-c", "Print :$key", "${app.absolutePath}/Contents/Info.plist")
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        process.waitFor(30, TimeUnit.SECONDS)
        return output
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(120, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }
}
