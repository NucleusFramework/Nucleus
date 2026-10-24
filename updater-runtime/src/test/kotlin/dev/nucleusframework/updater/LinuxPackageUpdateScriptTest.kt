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

        val installLine = script.lines().single { it.startsWith("pkexec ") }
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

    private fun script(
        packageFile: String = "/tmp/App-2.0.0.deb",
        extension: String = "deb",
        launcher: String = "/opt/App/bin/App",
        helper: String? = null,
        restart: Boolean = true,
    ) = buildLinuxPackageUpdateScript(
        packageFile = packageFile,
        extension = extension,
        launcher = launcher,
        helper = helper,
        appPid = 4321,
        restart = restart,
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
