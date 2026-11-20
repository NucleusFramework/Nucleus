package dev.nucleusframework.updater

import dev.nucleusframework.updater.internal.buildLinuxPackageUpdateScript
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
 * The deb/rpm update script interpolates the downloaded package path, whose file name comes from
 * the update manifest, plus the launcher and helper paths. Each must reach bash as one literal:
 * a `$(…)`, backtick, quote or apostrophe in it must never be interpreted.
 */
class LinuxPackageUpdateScriptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var bash: File
    private lateinit var canary: File

    @Before
    fun setUp() {
        val found = File("/bin/bash").takeIf { it.canExecute() }
        assumeTrue("bash is unavailable on this host", found != null)
        bash = found!!
        canary = File(tmp.root, "pwned")
    }

    @Test
    fun `scripts parse as bash for every install path`() {
        for (restart in listOf(true, false)) {
            assertParses(script(extension = "deb", restart = restart))
            assertParses(script(extension = "rpm", restart = restart))
            assertParses(script(extension = "deb", helper = "/opt/App/nucleus-update-helper", restart = restart))
        }
    }

    @Test
    fun `package and launcher paths round-trip as literals`() {
        val hostilePackage = "/tmp/App'\$(touch ${canary.path})\"`touch ${canary.path}`.deb"
        val hostileLauncher = "/home/o'brien/\$(touch ${canary.path})/bin/App"
        val script = script(packageFile = hostilePackage, launcher = hostileLauncher)

        assertParses(script)
        assertEquals(hostilePackage, evaluate(script, "PKG_FILE"))
        assertEquals(hostileLauncher, evaluate(script, "APP_LAUNCHER"))
        assertFalse("an injected command must not run", canary.exists())
    }

    @Test
    fun `helper path is passed to pkexec as one literal`() {
        val hostileHelper = "/opt/O'Brien \$(touch ${canary.path})/nucleus-update-helper"
        val script = script(helper = hostileHelper)

        // Without the redirect to the install log, so the arguments reach stdout.
        val installLine = script.lines().single { it.startsWith("pkexec ") }.substringBeforeLast(" > ")
        // Swap pkexec for printf to see exactly which arguments it would receive.
        val args =
            run(
                "PKG_FILE=/tmp/App.deb\n" + installLine.replaceFirst("pkexec", "printf '%s\\n'"),
            ).lines()
        assertEquals(listOf(hostileHelper, "/tmp/App.deb"), args)
        assertFalse("an injected command must not run", canary.exists())
    }

    @Test
    fun `without a helper the package manager prompts through pkexec`() {
        assertTrue(script(extension = "deb").contains("pkexec dpkg -i \"\$PKG_FILE\""))
        assertTrue(script(extension = "rpm").contains("pkexec rpm -U \"\$PKG_FILE\""))
    }

    @Test
    fun `versions and state paths round-trip as literals`() {
        // The target version comes from the update manifest, which is remote input.
        val hostileVersion = "2.0.0'\$(touch ${canary.path})\"`touch ${canary.path}`"
        val hostileLog = "/home/o'brien/\$(touch ${canary.path})/nucleus-install.log"
        val script = script(version = hostileVersion, logFile = hostileLog)

        assertParses(script)
        assertEquals(hostileVersion, evaluate(script, "NEW_VERSION"))
        assertEquals(hostileLog, evaluate(script, "LOG_FILE"))
        assertFalse("an injected command must not run", canary.exists())
    }

    @Test
    fun `a failed install is recorded and the app still relaunches`() {
        // The regression: a `sudo apt upgrade` waiting at its prompt held the dpkg lock, so dpkg
        // exited 2 at once. The script discarded its output and relaunched the old version, which
        // looked like an update that silently did nothing.
        val run =
            runPackageUpdate(
                pkexec = "echo 'dpkg: error: dpkg frontend lock was locked by another process' >&2; exit 2",
            )

        assertTrue("the app must be relaunched", run.relaunched)
        assertEquals("version=2.0.0\nrunningVersion=1.9.0\nexitCode=2\n", run.failureFile.readText())
        assertTrue(run.logFile.readText().contains("frontend lock was locked by another process"))
        assertFalse("the package must be removed", run.packageFile.exists())
    }

    @Test
    fun `a failure through the update helper is recorded too`() {
        val run = runPackageUpdate(pkexec = "echo 'refusing non-upgrade' >&2; exit 5", useHelper = true)

        assertTrue("the app must be relaunched", run.relaunched)
        assertEquals("version=2.0.0\nrunningVersion=1.9.0\nexitCode=5\n", run.failureFile.readText())
        assertTrue(run.logFile.readText().contains("refusing non-upgrade"))
    }

    @Test
    fun `a successful install leaves no failure record`() {
        val run = runPackageUpdate(pkexec = "echo 'Unpacking app (2.0.0)'; exit 0", staleFailure = true)

        assertTrue("the app must be relaunched", run.relaunched)
        assertFalse("a record from an earlier attempt must be cleared", run.failureFile.exists())
        assertTrue(run.logFile.readText().contains("Unpacking app"))
    }

    @Test
    fun `the package is installed even when the log cannot be written`() {
        // The log's directory cannot be created because a regular file is in the way.
        val blocker = tmp.newFile("blocker")
        val run = runPackageUpdate(pkexec = "exit 0", logFile = File(blocker, "nucleus-install.log"))

        assertTrue("the installer must have been invoked", run.invoked)
        assertTrue("the app must be relaunched", run.relaunched)
    }

    private class PackageRun(
        val relaunched: Boolean,
        val invoked: Boolean,
        val packageFile: File,
        val logFile: File,
        val failureFile: File,
    )

    /**
     * Runs the deb script for real against an app process that has already exited, with a
     * stand-in `pkexec` on PATH whose body is [pkexec] and a launcher that records its relaunch.
     */
    private fun runPackageUpdate(
        pkexec: String,
        useHelper: Boolean = false,
        staleFailure: Boolean = false,
        logFile: File = File(tmp.root, "state/nucleus-install.log"),
    ): PackageRun {
        val bin = tmp.newFolder("bin")
        val invokedMarker = File(tmp.root, "invoked")
        File(bin, "pkexec").apply {
            writeText("#!/bin/sh\ntouch '${invokedMarker.path}'\n$pkexec\n")
            setExecutable(true)
        }
        val relaunchMarker = File(tmp.root, "relaunched")
        val launcher =
            File(tmp.root, "App").apply {
                writeText("#!/bin/sh\ntouch '${relaunchMarker.path}'\n")
                setExecutable(true)
            }
        val packageFile = File(tmp.newFolder("staging"), "App-2.0.0.deb").apply { writeText("deb") }
        val failureFile = File(tmp.root, "state/nucleus-install-failure")
        if (staleFailure) {
            failureFile.parentFile.mkdirs()
            failureFile.writeText("version=1.9.5\nrunningVersion=1.9.0\nexitCode=2\n")
        }
        val deadPid = ProcessBuilder("true").start().also { it.waitFor() }.pid()
        val script =
            tmp.newFile("nucleus-update.sh").apply {
                writeText(
                    script(
                        packageFile = packageFile.path,
                        launcher = launcher.path,
                        helper = "/opt/App/nucleus-update-helper".takeIf { useHelper },
                        appPid = deadPid,
                        logFile = logFile.path,
                        failureFile = failureFile.path,
                    ),
                )
            }

        val process =
            ProcessBuilder(bash.path, script.path)
                .apply { environment()["PATH"] = "${bin.path}:${environment()["PATH"]}" }
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the script did not finish", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("the script failed:\n$output", 0, process.exitValue())

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!relaunchMarker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        return PackageRun(relaunchMarker.exists(), invokedMarker.exists(), packageFile, logFile, failureFile)
    }

    @Suppress("LongParameterList")
    private fun script(
        packageFile: String = "/tmp/App-2.0.0.deb",
        extension: String = "deb",
        launcher: String = "/opt/App/bin/App",
        helper: String? = null,
        restart: Boolean = true,
        appPid: Long = 4321,
        version: String = "2.0.0",
        logFile: String = "/home/user/.local/share/app/nucleus-install.log",
        failureFile: String = "/home/user/.local/share/app/nucleus-install-failure",
    ) = buildLinuxPackageUpdateScript(
        packageFile = packageFile,
        extension = extension,
        launcher = launcher,
        helper = helper,
        appPid = appPid,
        restart = restart,
        version = version,
        runningVersion = "1.9.0",
        logFile = logFile,
        failureFile = failureFile,
    )

    /** Runs `bash -n`, which parses the script without executing any of it. */
    private fun assertParses(script: String) {
        val file = tmp.newFile().apply { writeText(script) }
        val process = ProcessBuilder(bash.path, "-n", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("bash rejected the script:\n$output\n---\n$script", 0, process.waitFor())
    }

    /** The value bash assigns to [variable] when it evaluates only the script's assignment line. */
    private fun evaluate(
        script: String,
        variable: String,
    ): String {
        val assignment = script.lines().single { it.startsWith("$variable=") }
        return run("$assignment\nprintf '%s' \"\$$variable\"")
    }

    private fun run(snippet: String): String {
        val process = ProcessBuilder(bash.path, "-c", snippet).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("bash failed:\n$output", 0, process.waitFor())
        return output.removeSuffix("\n")
    }
}
