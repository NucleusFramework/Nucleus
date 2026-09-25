package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateHandoffTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `versioned layout is recognized from java home and launcher`() {
        val root = tmp.newFolder("App")
        val install =
            UpdateHandoff.detectVersionedInstall(
                javaHome = File(root, "versions/1.2.0/runtime").path,
                launcherPath = File(root, "App.exe").path,
                isWindows = true,
            )

        assertNotNull(install)
        assertEquals(root.absoluteFile, install!!.root)
        assertEquals("1.2.0", install.versionDir.name)
        assertEquals(File(root, "versions").absoluteFile, install.versionsDir)
    }

    @Test
    fun `flat jpackage layout is not versioned`() {
        val root = tmp.newFolder("App")

        val install =
            UpdateHandoff.detectVersionedInstall(
                javaHome = File(root, "runtime").path,
                launcherPath = File(root, "App.exe").path,
                isWindows = true,
            )

        assertNull(install)
    }

    @Test
    fun `launcher outside the install root is not versioned`() {
        val root = tmp.newFolder("App")

        val install =
            UpdateHandoff.detectVersionedInstall(
                javaHome = File(root, "versions/1.2.0/runtime").path,
                launcherPath = File(tmp.root, "elsewhere/App.exe").path,
                isWindows = true,
            )

        assertNull(install)
    }

    @Test
    fun `versioned layout is Windows only`() {
        val root = tmp.newFolder("App")

        val install =
            UpdateHandoff.detectVersionedInstall(
                javaHome = File(root, "versions/1.2.0/runtime").path,
                launcherPath = File(root, "App.exe").path,
                isWindows = false,
            )

        assertNull(install)
    }

    @Test
    fun `cleanup deletes retired versions and launchers but keeps the running one`() {
        val root = tmp.newFolder("App")
        val current = File(root, "versions/1.2.0").apply { File(this, "runtime").mkdirs() }
        val retiredVersion = File(root, "versions/1.1.0").apply { File(this, "app").mkdirs() }
        File(retiredVersion, "app/lib.jar").writeText("jar")
        val trash = File(root, "versions/.trash-1.0.0-42").apply { mkdirs() }
        val launcher = File(root, "App.exe").apply { writeText("new") }
        // jpackage ships its launcher read-only; the retired copy keeps the attribute.
        val retiredLauncher = File(root, "App.exe.123.nucleus-old").apply { writeText("old") }
        retiredLauncher.setWritable(false)
        val install = VersionedInstall(root, current, launcher)

        UpdateHandoff.cleanupRetiredVersions(install)

        assertTrue(current.isDirectory)
        assertTrue(launcher.isFile)
        assertFalse(retiredVersion.exists())
        assertFalse(trash.exists())
        assertFalse(retiredLauncher.exists())
        assertEquals(listOf("1.2.0"), File(root, "versions").list()!!.toList())
    }
}
