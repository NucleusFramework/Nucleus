package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.PlatformInstaller
import dev.nucleusframework.updater.internal.buildLinuxAppImageUpdateScript
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
 * Regression coverage for AppImage post-update restart (#178).
 *
 * The previous script used `set -e` and relaunched with the full parent environment: after the
 * FUSE mount went away, stale `APPDIR` / `LD_LIBRARY_PATH` values prevented the new process from
 * starting, so the update applied but the app never came back.
 */
class LinuxAppImageUpdateScriptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var installDir: File
    private lateinit var logFile: File

    @Before
    fun setUp() {
        assumeTrue("Linux-only", System.getProperty("os.name").startsWith("Linux"))
        installDir = tmp.newFolder("apps")
        logFile = tmp.newFile("nucleus-update.log")
    }

    @Test
    fun `script unsets AppImage env and relaunches after replace`() {
        val oldApp = writeMarkerApp(installDir, "App.AppImage", marker = "v1")
        val newApp = writeMarkerApp(tmp.newFolder("download"), "App-2.AppImage", marker = "v2")
        val startedFlag = File(tmp.root, "started.flag")

        // A short-lived "running" process the script will wait on.
        val holder = ProcessBuilder("sleep", "30").start()
        try {
            val script =
                writeScript(
                    newFile = newApp,
                    oldFile = oldApp,
                    appPid = holder.pid(),
                    restart = true,
                    alreadyReplaced = false,
                )

            val runner =
                ProcessBuilder("bash", script.absolutePath)
                    .apply {
                        // Simulate the environment a still-mounted AppImage leaves on the
                        // process: after unmount these paths are dead and must not leak into
                        // the relaunch (the #178 failure mode).
                        environment()["APPDIR"] = "/tmp/.mount_stale_app"
                        environment()["LD_LIBRARY_PATH"] = "/tmp/.mount_stale_app/usr/lib"
                        environment()["APPIMAGE"] = oldApp.absolutePath
                    }.start()
            // Let the script enter its wait loop, then release it.
            Thread.sleep(200)
            holder.destroy()
            holder.waitFor(5, TimeUnit.SECONDS)

            assertTrue(
                "update script must finish",
                runner.waitFor(15, TimeUnit.SECONDS),
            )
            assertEquals(readLog(), 0, runner.exitValue())

            assertTrue("app must have been relaunched", waitForFile(startedFlag, 10_000))
            assertTrue("version flag must be written by relaunch", waitForFile(File(tmp.root, "version.flag"), 5_000))
            assertEquals("v2", markerOf(oldApp))
            assertFalse("download must be consumed", newApp.exists())
            assertTrue(
                "relaunch must clear APPDIR (stale FUSE path)",
                startedFlag.readText().contains("APPDIR=unset"),
            )
            assertTrue(
                "relaunch must clear LD_LIBRARY_PATH",
                startedFlag.readText().contains("LD_LIBRARY_PATH=unset"),
            )
            assertTrue(
                readLog().contains("relaunch spawned") || readLog().contains("relaunching"),
            )
        } finally {
            holder.destroyForcibly()
            // Kill anything the script may have started from our temp tree.
            installDir.listFiles()?.forEach { /* marker apps exit on their own */ }
        }
    }

    @Test
    fun `restart=false installs without relaunch`() {
        val oldApp = writeMarkerApp(installDir, "App.AppImage", marker = "v1", recordStart = false)
        val newApp = writeMarkerApp(tmp.newFolder("download"), "App-2.AppImage", marker = "v2", recordStart = false)
        val startedFlag = File(tmp.root, "started.flag")

        val holder = ProcessBuilder("sleep", "30").start()
        try {
            val script =
                writeScript(
                    newFile = newApp,
                    oldFile = oldApp,
                    appPid = holder.pid(),
                    restart = false,
                    alreadyReplaced = false,
                )
            val runner = ProcessBuilder("bash", script.absolutePath).start()
            Thread.sleep(200)
            holder.destroy()
            holder.waitFor(5, TimeUnit.SECONDS)

            assertTrue(runner.waitFor(15, TimeUnit.SECONDS))
            assertEquals(readLog(), 0, runner.exitValue())
            // No relaunch, so the version flag is never written — the installed bytes are the proof.
            assertTrue("installed AppImage must be the v2 payload", oldApp.readText().contains("v2"))
            assertFalse("must not relaunch when restart=false", startedFlag.exists())
            assertTrue(readLog().contains("relaunch skipped"))
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test
    fun `in-process replace leaves the destination ready before the script runs`() {
        val destination = File(installDir, "App.AppImage").apply { writeText("old") }
        val incoming =
            File(tmp.newFolder("download"), "App-new.AppImage").apply {
                writeText("new-bytes")
                setExecutable(true)
            }

        assertTrue(PlatformInstaller.replaceAppImageInPlace(incoming, destination))
        assertEquals("new-bytes", destination.readText())
        assertTrue(destination.canExecute())
        assertFalse(incoming.exists())
    }

    @Test
    fun `script resumes from an in-process replace when the download is already gone`() {
        val oldApp = writeMarkerApp(installDir, "App.AppImage", marker = "v2")
        // Simulate PlatformInstaller having already swapped the file.
        val missingDownload = File(tmp.newFolder("download"), "gone.AppImage")
        val startedFlag = File(tmp.root, "started.flag")

        val holder = ProcessBuilder("sleep", "30").start()
        try {
            val script =
                writeScript(
                    newFile = missingDownload,
                    oldFile = oldApp,
                    appPid = holder.pid(),
                    restart = true,
                    alreadyReplaced = true,
                )
            val runner = ProcessBuilder("bash", script.absolutePath).start()
            Thread.sleep(200)
            holder.destroy()
            holder.waitFor(5, TimeUnit.SECONDS)

            assertTrue(runner.waitFor(15, TimeUnit.SECONDS))
            assertEquals(readLog(), 0, runner.exitValue())
            assertTrue("app must have been relaunched", waitForFile(startedFlag, 10_000))
            assertTrue(readLog().contains("in-process install") || readLog().contains("relaunch"))
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test
    fun `generated script quotes paths with spaces`() {
        val script =
            buildLinuxAppImageUpdateScript(
                newFile = "/tmp/My App-2.0.0.AppImage",
                oldFile = "/home/user/My Applications/My App.AppImage",
                appPid = 42L,
                logFile = "/tmp/nucleus update.log",
                restart = true,
                alreadyReplaced = false,
                selfDelete = false,
            )
        assertTrue(script.contains("NEW_FILE='/tmp/My App-2.0.0.AppImage'"))
        assertTrue(script.contains("OLD_FILE='/home/user/My Applications/My App.AppImage'"))
        assertTrue(script.contains("unset APPDIR APPIMAGE OWD ARGV0"))
        assertTrue(script.contains("unset LD_LIBRARY_PATH LD_PRELOAD"))
        // Active errexit would abort before relaunch on a flaky mv; only the explanatory
        // comment may mention `set -e`.
        assertFalse(
            "set -e would abort before relaunch on a flaky mv",
            script.lines().any { it.trimStart().startsWith("set -e") },
        )
    }

    private fun writeScript(
        newFile: File,
        oldFile: File,
        appPid: Long,
        restart: Boolean,
        alreadyReplaced: Boolean,
    ): File {
        val scriptFile = tmp.newFile("nucleus-update.sh")
        scriptFile.writeText(
            buildLinuxAppImageUpdateScript(
                newFile = newFile.absolutePath,
                oldFile = oldFile.absolutePath,
                appPid = appPid,
                logFile = logFile.absolutePath,
                restart = restart,
                alreadyReplaced = alreadyReplaced,
                selfDelete = false,
            ),
        )
        scriptFile.setExecutable(true)
        return scriptFile
    }

    /**
     * Tiny stand-in for an AppImage: records that it was launched (and with which env) then exits.
     *
     * Marker / start-flag paths are absolute under [tmp] so they still resolve after the update
     * script `mv`s the download onto the installed path (the shebang script body moves with the
     * file, but must not hard-code the pre-mv location).
     */
    private fun writeMarkerApp(
        dir: File,
        name: String,
        marker: String,
        recordStart: Boolean = true,
    ): File {
        val file = File(dir, name)
        val versionFlag = File(tmp.root, "version.flag")
        val startedFlag = File(tmp.root, "started.flag")
        val body =
            if (recordStart) {
                """
                #!/usr/bin/env bash
                echo "$marker" > "${versionFlag.absolutePath}"
                {
                  echo "APPDIR=${'$'}{APPDIR:-unset}"
                  echo "LD_LIBRARY_PATH=${'$'}{LD_LIBRARY_PATH:-unset}"
                } > "${startedFlag.absolutePath}"
                """.trimIndent()
            } else {
                """
                #!/usr/bin/env bash
                echo "$marker" > "${versionFlag.absolutePath}"
                """.trimIndent()
            }
        file.writeText(body + "\n")
        file.setExecutable(true)
        return file
    }

    private fun markerOf(
        @Suppress("UNUSED_PARAMETER") app: File,
    ): String = File(tmp.root, "version.flag").readText().trim()

    private fun readLog(): String = if (logFile.isFile) logFile.readText() else ""

    private fun waitForFile(
        file: File,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) return true
            Thread.sleep(50)
        }
        return file.isFile && file.length() > 0
    }
}
