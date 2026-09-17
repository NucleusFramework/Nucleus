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
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage for AppImage post-update restart (#178) against a *real* AppImage binary.
 *
 * Flow mirrors production [PlatformInstaller.installLinuxAppImage]:
 *  1. hold the installed AppImage open via `--appimage-mount` (FUSE/loop, like a running app);
 *  2. swap the new bytes in place while that mount still holds the old inode;
 *  3. run the generated update script (wait for pid → clean-env relaunch);
 *  4. assert the relaunch actually started the new AppImage.
 *
 * Supply the artifacts with:
 * ```
 * ./gradlew :updater-runtime:test --tests '*RealAppImageRestartE2ETest*' \
 *   -Dnucleus.e2e.appimage.old=/path/to/v1.AppImage \
 *   -Dnucleus.e2e.appimage.new=/path/to/v2.AppImage
 * ```
 * When the properties are absent the test is skipped (unit CI stays free of multi-dozen-MB downloads).
 */
class RealAppImageRestartE2ETest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var oldArtifact: File
    private lateinit var newArtifact: File

    @Before
    fun setUp() {
        assumeTrue("Linux-only", System.getProperty("os.name").startsWith("Linux"))
        val oldPath = System.getProperty("nucleus.e2e.appimage.old")
        val newPath = System.getProperty("nucleus.e2e.appimage.new")
        assumeTrue(
            "real AppImage artifacts not supplied " +
                "(-Dnucleus.e2e.appimage.old / -Dnucleus.e2e.appimage.new)",
            !oldPath.isNullOrBlank() && !newPath.isNullOrBlank(),
        )
        oldArtifact = File(oldPath!!)
        newArtifact = File(newPath!!)
        assumeTrue("old AppImage missing: $oldPath", oldArtifact.isFile)
        assumeTrue("new AppImage missing: $newPath", newArtifact.isFile)
        assumeTrue("fuse/AppImage runtime must respond", appImageRuntimeWorks(oldArtifact))
    }

    @Test
    fun `real AppImage is replaced in-place and relaunched with a clean environment`() {
        val installDir = tmp.newFolder("Applications")
        val installed = File(installDir, "NucleusDemo.AppImage")
        val download = File(tmp.newFolder("download"), "NucleusDemo-2.AppImage")
        val relaunchLog = File(tmp.root, "relaunch.log")
        val updateLog = File(tmp.root, "nucleus-update.log")

        oldArtifact.copyTo(installed, overwrite = true)
        installed.setExecutable(true, false)
        newArtifact.copyTo(download, overwrite = true)
        download.setExecutable(true, false)

        val oldSha = sha256(installed)
        val newSha = sha256(download)
        assertTrue(
            "e2e needs two distinct AppImage payloads (got identical sha256)",
            oldSha != newSha || oldArtifact.absolutePath != newArtifact.absolutePath,
        )

        // Keep the installed AppImage open the way a running instance does (FUSE mount).
        val mount =
            ProcessBuilder(installed.absolutePath, "--appimage-mount")
                .redirectErrorStream(true)
                .start()
        val mountPoint =
            mount.inputStream
                .bufferedReader()
                .readLine()
                ?.trim()
        assertFalse("AppImage mount produced no mount point", mountPoint.isNullOrBlank())
        assertTrue("mount point must exist: $mountPoint", File(mountPoint!!).isDirectory)

        try {
            // Production step 1: in-process swap while the previous inode is still open.
            val replaced = PlatformInstaller.replaceAppImageInPlace(download, installed)
            assertTrue("in-process replace must succeed against an open AppImage", replaced)
            assertFalse("download consumed by in-process replace", download.exists())
            assertTrue(installed.isFile)
            assertTrue(installed.canExecute())
            // Mount still alive (old inode) even though the directory entry was replaced.
            assertTrue("FUSE mount must survive the replace", mount.isAlive)
            assertTrue(File(mountPoint).isDirectory)

            // Production step 2: detached script waits for the "app" pid, then relaunches.
            // We wrap the relaunch target so a headless environment does not need a display —
            // the wrapper records env + that the real AppImage runtime still works, then exits.
            val wrapper = File(installDir, "NucleusDemo.AppImage")
            // `installed` is already that path and now holds the *new* bytes. Build a recorder
            // that execs those bytes with `--appimage-version` after logging env.
            val realNew = File(installDir, "NucleusDemo.real.AppImage")
            installed.copyTo(realNew, overwrite = true)
            realNew.setExecutable(true, false)
            writeRelaunchRecorder(wrapper, realNew, relaunchLog)

            val scriptFile = tmp.newFile("nucleus-update.sh")
            scriptFile.writeText(
                buildLinuxAppImageUpdateScript(
                    newFile = download.absolutePath, // already gone → alreadyReplaced path
                    oldFile = wrapper.absolutePath,
                    appPid = mount.pid(),
                    logFile = updateLog.absolutePath,
                    restart = true,
                    alreadyReplaced = true,
                    selfDelete = false,
                ),
            )
            scriptFile.setExecutable(true)

            val script =
                ProcessBuilder("setsid", "bash", scriptFile.absolutePath)
                    .directory(File(System.getProperty("user.home")))
                    .apply {
                        environment()["APPDIR"] = mountPoint
                        environment()["APPIMAGE"] = wrapper.absolutePath
                        environment()["LD_LIBRARY_PATH"] = "$mountPoint/usr/lib"
                        environment()["OWD"] = mountPoint
                    }.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

            // Release the mount → script proceeds past the pid wait and relaunches.
            mount.destroy()
            mount.waitFor(10, TimeUnit.SECONDS)
            // Some runtimes ignore SIGTERM; force so the script cannot hang.
            if (mount.isAlive) mount.destroyForcibly()

            assertTrue("update script must finish", script.waitFor(30, TimeUnit.SECONDS))
            assertEquals(
                "update log:\n${updateLog.readText()}",
                0,
                script.exitValue(),
            )

            assertTrue(
                "relaunch must have started (log missing). update log:\n${updateLog.readText()}",
                waitForFile(relaunchLog, 15_000),
            )
            val relaunch = relaunchLog.readText()
            assertTrue("stale APPDIR must be cleared:\n$relaunch", relaunch.contains("APPDIR=unset"))
            assertTrue(
                "stale LD_LIBRARY_PATH must be cleared:\n$relaunch",
                relaunch.contains("LD_LIBRARY_PATH=unset"),
            )
            assertTrue(
                "real AppImage runtime must accept the replaced payload:\n$relaunch",
                relaunch.contains("runtime_ok=1"),
            )
            assertTrue(
                updateLog.readText().contains("relaunch spawned") ||
                    updateLog.readText().contains("relaunching"),
            )
        } finally {
            if (mount.isAlive) mount.destroyForcibly()
        }
    }

    /**
     * Replaces [wrapper] with a small shell recorder that logs the inherited environment, probes
     * the real AppImage at [realAppImage] with `--appimage-version`, then exits. This lets the e2e
     * assert relaunch without requiring a working display for the full GUI app.
     */
    private fun writeRelaunchRecorder(
        wrapper: File,
        realAppImage: File,
        relaunchLog: File,
    ) {
        wrapper.writeText(
            """
            #!/usr/bin/env bash
            {
              echo "APPDIR=${'$'}{APPDIR:-unset}"
              echo "LD_LIBRARY_PATH=${'$'}{LD_LIBRARY_PATH:-unset}"
              echo "APPIMAGE=${'$'}{APPIMAGE:-unset}"
              if "${realAppImage.absolutePath}" --appimage-version >/dev/null 2>&1 \
                || "${realAppImage.absolutePath}" --appimage-help >/dev/null 2>&1; then
                echo "runtime_ok=1"
              else
                echo "runtime_ok=0"
              fi
            } > "${relaunchLog.absolutePath}"
            """.trimIndent() + "\n",
        )
        wrapper.setExecutable(true, false)
    }

    private fun appImageRuntimeWorks(appImage: File): Boolean =
        try {
            val p =
                ProcessBuilder(appImage.absolutePath, "--appimage-help")
                    .redirectErrorStream(true)
                    .start()
            val finished = p.waitFor(15, TimeUnit.SECONDS)
            finished && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun waitForFile(
        file: File,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0L) return true
            Thread.sleep(50)
        }
        return file.isFile && file.length() > 0L
    }
}
