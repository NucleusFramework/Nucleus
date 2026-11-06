package dev.nucleusframework.updater

import dev.nucleusframework.core.runtime.ExecutableType
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.core.runtime.VersionedInstall
import dev.nucleusframework.updater.internal.WindowsHotUpdate
import dev.nucleusframework.updater.internal.buildWindowsHotUpdateScript
import dev.nucleusframework.updater.internal.writePowerShellScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsHotUpdateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun install(): VersionedInstall {
        val root = tmp.newFolder("App")
        val current = File(root, "versions/1.0.0").apply { File(this, "runtime").mkdirs() }
        val launcher = File(root, "App.exe").apply { writeText("launcher") }
        return VersionedInstall(root, current, launcher)
    }

    private fun writeCfg(
        install: VersionedInstall,
        version: String,
    ) {
        File(install.root, "app").mkdirs()
        File(install.root, "app/App.cfg").writeText(
            "[Application]\r\napp.runtime=\$ROOTDIR\\versions\\$version\\runtime\r\n" +
                "app.classpath=\$ROOTDIR\\versions\\$version\\app\\app.jar\r\n",
        )
    }

    @Test
    fun `installed version is read back from the rewritten cfg`() {
        val install = install()
        File(install.versionsDir, "1.1.0/runtime").mkdirs()
        writeCfg(install, "1.1.0")

        val installed = WindowsHotUpdate.installedVersionDir(install)

        assertEquals(File(install.versionsDir, "1.1.0"), installed)
    }

    @Test
    fun `cfg still pointing at the running version means nothing was installed`() {
        val install = install()
        writeCfg(install, "1.0.0")

        assertNull(WindowsHotUpdate.installedVersionDir(install))
    }

    @Test
    fun `cfg pointing at a missing runtime means nothing was installed`() {
        val install = install()
        writeCfg(install, "1.1.0")

        assertNull(WindowsHotUpdate.installedVersionDir(install))
    }

    @Test
    fun `launchers are retired and copied back writable, the uninstaller is left alone`() {
        val install = install()
        install.launcher.setWritable(false) // jpackage ships it read-only
        val helper = File(install.root, "Helper.exe").apply { writeText("helper") }
        val uninstaller = File(install.root, "Uninstall App.exe").apply { writeText("uninstaller") }

        val retired = WindowsHotUpdate.retireLaunchers(install.root)

        assertEquals(2, retired.size)
        assertTrue(retired.all { it.isFile && it.name.endsWith(".nucleus-old") })
        assertEquals(setOf("launcher", "helper"), retired.map { it.readText() }.toSet())
        // The launcher paths keep working during the install, and the installer can replace them.
        assertEquals("launcher", install.launcher.readText())
        assertTrue(install.launcher.canWrite())
        assertEquals("helper", helper.readText())
        assertEquals(listOf(uninstaller.name), install.root.list()!!.filter { it.startsWith("Uninstall") })
    }

    @Test
    fun `only Windows NSIS installs of the versioned layout are eligible`() {
        val install = install()
        val exe = File(tmp.root, "app-1.1.0-nsis.exe")

        assertNotNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Windows, ExecutableType.NSIS, install))
        assertNotNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Windows, ExecutableType.EXE, install))
        assertNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Windows, ExecutableType.MSI, install))
        assertNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Windows, ExecutableType.NSIS, null))
        assertNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Linux, ExecutableType.NSIS, install))
        assertNull(
            WindowsHotUpdate.eligibleInstall(File(tmp.root, "app.msi"), Platform.Windows, ExecutableType.NSIS, install),
        )
    }

    @Test
    fun `an install whose versions directory cannot be written is not eligible`() {
        val install = install()
        val exe = File(tmp.root, "app-1.1.0-nsis.exe")
        // A plain file where the versions directory should be: creating the probe fails.
        val readOnly = VersionedInstall(install.root, File(tmp.newFile("versions-file"), "1.0.0"), install.launcher)

        assertNotNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Windows, ExecutableType.NSIS, install))
        assertNull(WindowsHotUpdate.eligibleInstall(exe, Platform.Windows, ExecutableType.NSIS, readOnly))
    }

    @Test
    fun `PowerShell scripts are written with a BOM so non-ASCII paths survive`() {
        val script = File(tmp.root, "update.ps1")

        writePowerShellScript(script, "Start-Process 'C:\\Users\\Hélène\\App.exe'")

        val bytes = script.readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        assertTrue(String(bytes, Charsets.UTF_8).contains("Hélène"))
    }

    @Test
    fun `hot update script runs the installer silently and relaunches only if the app was closed`() {
        val script =
            buildWindowsHotUpdateScript(
                pid = 4242,
                installerPath = "C:\\Temp\\it's\\setup.exe",
                launcher = "C:\\Apps\\App\\App.exe",
                exitedMarker = "C:\\Temp\\work\\app-exited",
            )

        assertTrue(
            script.contains(
                "Start-Process 'C:\\Temp\\it''s\\setup.exe' -ArgumentList '/S', '--updated' -Wait -PassThru",
            ),
        )
        assertTrue(script.contains("-not (Get-Process -Id 4242 -ErrorAction SilentlyContinue)"))
        // A user who quit during the install is not relaunched.
        assertTrue(script.contains("-not (Test-Path -LiteralPath 'C:\\Temp\\work\\app-exited')"))
        assertTrue(script.contains("Remove-Item Env:NUCLEUS_HOT_UPDATE"))
        assertTrue(script.contains("Start-Process 'C:\\Apps\\App\\App.exe'"))
        assertTrue(script.trimEnd().endsWith("exit \$code"))
    }
}
