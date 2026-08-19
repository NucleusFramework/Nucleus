package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

class PlatformInstallerHelpersTest {
    @Test
    fun `update work dir is a fresh private directory not a predictable shared temp path`() {
        val first = createUpdateWorkDir()
        val second = createUpdateWorkDir()
        try {
            assertTrue("work dir must exist", first.isDirectory)
            // A fixed name in the shared temp dir would collide across runs; each run is unique.
            assertNotEquals("each update run gets its own directory", first.absolutePath, second.absolutePath)
            // On POSIX the directory must be owner-only (rwx------), closing the symlink/pre-create hole.
            val posix = Files.getFileAttributeView(first.toPath(), PosixFileAttributeView::class.java)
            if (posix != null) {
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                    posix.readAttributes().permissions(),
                )
            }
        } finally {
            first.delete()
            second.delete()
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `linux installer process builders pick dpkg rpm or xdg-open`() {
        val pkg = tmp.newFile("app.deb")
        val deb = invokeProcessBuilder("buildLinuxInstaller", pkg, "deb")
        assertEquals(listOf("sudo", "dpkg", "-i", pkg.absolutePath), deb.command())
        val rpm = invokeProcessBuilder("buildLinuxInstaller", pkg, "rpm")
        assertEquals(listOf("sudo", "rpm", "-U", pkg.absolutePath), rpm.command())
        val other = invokeProcessBuilder("buildLinuxInstaller", pkg, "appimage")
        assertEquals(listOf("xdg-open", pkg.absolutePath), other.command())
    }

    @Test
    fun `mac installer opens the downloaded file`() {
        val dmg = tmp.newFile("App.dmg")
        val builder = invokeProcessBuilder("buildMacInstaller", dmg)
        assertEquals(listOf("open", dmg.absolutePath), builder.command())
    }

    @Test
    fun `generic process builder rejects windows and unknown platforms`() {
        val file = tmp.newFile("payload.bin")
        val linux = invokeBuildProcessForInstaller(file, Platform.Linux, "deb")
        assertEquals("sudo", linux.command().first())
        val mac = invokeBuildProcessForInstaller(file, Platform.MacOS, "dmg")
        assertEquals("open", mac.command().first())
        val windows =
            runCatching { invokeBuildProcessForInstaller(file, Platform.Windows, "exe") }
                .exceptionOrNull()
        assertTrue(
            windows?.message?.contains("Windows uses installWindows") == true ||
                windows is IllegalStateException,
        )
        val unknown =
            runCatching { invokeBuildProcessForInstaller(file, Platform.Unknown, "bin") }
                .exceptionOrNull()
        assertTrue(unknown != null)
    }

    @Test
    fun `current app bundle is null outside a packaged mac app`() {
        val method = PlatformInstaller::class.java.getDeclaredMethod("resolveCurrentAppBundle")
        method.isAccessible = true
        assertNull(method.invoke(PlatformInstaller))
    }

    @Test
    fun `current executable path is the running process when available`() {
        val method = PlatformInstaller::class.java.getDeclaredMethod("currentExecutablePath")
        method.isAccessible = true
        val path = method.invoke(PlatformInstaller) as String?
        if (path != null) {
            assertTrue(path.isNotEmpty())
            assertTrue(File(path).isAbsolute)
        }
    }

    @Test
    fun `linux fallback logger covers missing helper and missing signature`() {
        val method =
            PlatformInstaller::class.java.getDeclaredMethod(
                "logLinuxInstallFallback",
                File::class.java,
                File::class.java,
            )
        method.isAccessible = true
        val missingSig = tmp.newFile("pkg.deb")
        method.invoke(PlatformInstaller, null, File(tmp.root, "missing.asc"))
        val helper = tmp.newFile("nucleus-update-helper")
        method.invoke(PlatformInstaller, helper, File(tmp.root, "missing.asc"))
        method.invoke(PlatformInstaller, helper, missingSig)
        assertTrue(helper.isFile)
        assertFalse(File(tmp.root, "missing.asc").isFile)
    }

    @Test
    fun `mac zip script can skip relaunch and self-delete`() {
        val script =
            buildMacZipUpdateScript(
                zipFile = "/tmp/App.zip",
                appPath = "/Applications/App.app",
                installDir = "/Applications",
                appPid = 42L,
                logFile = "/tmp/nucleus-update.log",
                restart = false,
                selfDelete = false,
            )
        assertTrue(script.contains("Relaunch skipped"))
        assertTrue(script.contains("true"))
        assertFalse(script.contains("open \"\$TARGET\" || echo"))
    }

    @Test
    fun `replaceAppImageInPlace returns false for a directory source`() {
        val destination = tmp.newFile("dest.AppImage")
        val directory = tmp.newFolder("not-a-file")
        assertFalse(PlatformInstaller.replaceAppImageInPlace(directory, destination))
        assertEquals("", destination.readText())
    }

    @Test
    fun `helper search stops when a parent is missing`() {
        assertNull(resolveUpdateHelperFromLauncher("/no/such/launcher", maxDepth = 1))
    }

    @Test
    fun `constants match the plugin helper name and walk depth`() {
        assertEquals("nucleus-update-helper", PlatformInstaller.UPDATE_HELPER_NAME)
        assertEquals(3, PlatformInstaller.HELPER_SEARCH_MAX_DEPTH)
    }

    private fun invokeProcessBuilder(
        name: String,
        file: File,
        extension: String? = null,
    ): ProcessBuilder {
        val method =
            if (extension == null) {
                PlatformInstaller::class.java.getDeclaredMethod(name, File::class.java)
            } else {
                PlatformInstaller::class.java.getDeclaredMethod(name, File::class.java, String::class.java)
            }
        method.isAccessible = true
        return if (extension == null) {
            method.invoke(PlatformInstaller, file) as ProcessBuilder
        } else {
            method.invoke(PlatformInstaller, file, extension) as ProcessBuilder
        }
    }

    private fun invokeBuildProcessForInstaller(
        file: File,
        platform: Platform,
        extension: String,
    ): ProcessBuilder {
        val method =
            PlatformInstaller::class.java.getDeclaredMethod(
                "buildProcessForInstaller",
                File::class.java,
                Platform::class.java,
                String::class.java,
            )
        method.isAccessible = true
        return try {
            method.invoke(PlatformInstaller, file, platform, extension) as ProcessBuilder
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException
        }
    }
}
