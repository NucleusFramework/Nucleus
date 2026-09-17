package dev.nucleusframework.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsUpdateScriptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `msi command uses msiexec passive and exe uses silent nsis flags`() {
        val msi = tmp.newFile("App-2.0.0.msi")
        val exe = tmp.newFile("App-2.0.0.exe")
        val msiCmd = windowsInstallerCommand(msi, "msi")
        assertTrue("msiexec: $msiCmd", msiCmd.contains("msiexec"))
        assertTrue("/passive: $msiCmd", msiCmd.contains("/passive"))
        assertTrue("msi path: $msiCmd", msiCmd.contains(msi.absolutePath))
        val exeCmd = windowsInstallerCommand(exe, "exe")
        assertTrue("/S: $exeCmd", exeCmd.contains("/S"))
        assertTrue("--updated: $exeCmd", exeCmd.contains("--updated"))
        assertTrue("exe path: $exeCmd", exeCmd.contains(exe.absolutePath))
    }

    @Test
    fun `relaunch is omitted when restart is off or launcher is missing`() {
        assertTrue(windowsRelaunchCommand(restart = false, launcher = "C:\\\\App\\\\App.exe").isEmpty())
        assertTrue(windowsRelaunchCommand(restart = true, launcher = null).isEmpty())
        val relaunch = windowsRelaunchCommand(restart = true, launcher = "C:\\\\App\\\\App.exe")
        assertTrue("Start-Process: $relaunch", relaunch.contains("Start-Process"))
        assertTrue("launcher: $relaunch", relaunch.contains("C:\\\\App\\\\App.exe"))
    }

    @Test
    fun `generated script waits for the pid then runs the installer and deletes itself`() {
        val artifact = File("C:\\\\Temp\\\\App-2.0.0.exe")
        val scriptFile = File("C:\\\\Temp\\\\nucleus-update.ps1")
        val script =
            buildWindowsUpdateScript(
                pid = 4242L,
                installerCommand = windowsInstallerCommand(artifact, "exe"),
                relaunchCommand = windowsRelaunchCommand(true, "C:\\\\App\\\\App.exe"),
                artifactPath = artifact.absolutePath,
                scriptPath = scriptFile.absolutePath,
            )
        assertTrue("pid wait: $script", script.contains("Get-Process -Id 4242"))
        assertTrue("/S: $script", script.contains("/S"))
        assertTrue("--updated: $script", script.contains("--updated"))
        assertTrue("relaunch: $script", script.contains("Start-Process 'C:\\\\App\\\\App.exe'"))
        assertTrue("artifact cleanup: $script", script.contains("Remove-Item '${artifact.absolutePath}'"))
        assertTrue("script cleanup: $script", script.contains("Remove-Item '${scriptFile.absolutePath}'"))
        assertFalse("unexpected msiexec: $script", script.contains("msiexec"))
    }

    @Test
    fun `a single quote in the manifest-derived name cannot break out of the powershell string`() {
        // The artifact name comes from the remote manifest's `url`; a hostile `'` must be neutralised.
        val artifact = File("C:\\\\Temp\\\\ev'il; Start-Process calc.exe #.exe")
        val script =
            buildWindowsUpdateScript(
                pid = 1L,
                installerCommand = windowsInstallerCommand(artifact, "exe"),
                relaunchCommand = "",
                artifactPath = artifact.absolutePath,
                scriptPath = "C:\\\\Temp\\\\nucleus-update.ps1",
            )
        // The lone quote is doubled (escaped); the raw break-out sequence never appears verbatim.
        assertTrue("escaped: $script", script.contains("ev''il; Start-Process calc.exe #.exe"))
        assertFalse("raw quote break-out: $script", script.contains("ev'il;"))
        assertEquals("ev''il", psSingleQuote("ev'il"))
    }
}
