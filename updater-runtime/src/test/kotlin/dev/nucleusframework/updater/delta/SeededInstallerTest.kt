package dev.nucleusframework.updater.delta

import dev.nucleusframework.updater.internal.delta.SeededInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SeededInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the cache directory follows electron-builder's naming`() {
        assertEquals("myapp-updater", SeededInstaller.updaterCacheDirName("MyApp"))
        assertEquals("my-app-updater", SeededInstaller.updaterCacheDirName("My App"))
        assertEquals("app-updater", SeededInstaller.updaterCacheDirName("!!!"))
    }

    @Test
    fun `the installer is found next to the running executable's cache directory`() {
        val localAppData = tmp.newFolder("LocalAppData")
        val installer =
            File(localAppData, "my-app-updater/installer.exe").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }

        assertEquals(installer, locate(localAppData, executable = "/Program Files/My App/My App.exe"))
    }

    @Test
    fun `nothing is found off Windows, without the directory, or for an empty installer`() {
        val localAppData = tmp.newFolder("LocalAppData")
        val exe = "/Program Files/MyApp/MyApp.exe"
        assertNull("no installer seeded yet", locate(localAppData, exe))

        File(localAppData, "myapp-updater/installer.exe").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(0))
        }
        assertNull("an empty installer is useless", locate(localAppData, exe))
        assertNull(SeededInstaller.locate(osName = "Linux", localAppData = localAppData.path, executablePath = exe))
        assertNull(locate(localAppData, executable = "/opt/MyApp/bin/MyApp"))
    }

    @Test
    fun `the previous release URL swaps in the installed version`() {
        assertEquals(
            "https://example.com/releases/1.0.0/MyApp-1.0.0-win-x64.exe",
            SeededInstaller.previousReleaseUrl(
                "https://example.com/releases/2.0.0/MyApp-2.0.0-win-x64.exe",
                newVersion = "2.0.0",
                currentVersion = "1.0.0",
            ),
        )
        assertNull(SeededInstaller.previousReleaseUrl("https://example.com/MyApp-win-x64.exe", "2.0.0", "1.0.0"))
        assertNull(SeededInstaller.previousReleaseUrl("https://example.com/MyApp-2.0.0.exe", "2.0.0", "2.0.0"))
    }

    @Test
    fun `the previous release URL keeps the host's tag scheme`() {
        // A GitHub release tagged without the v prefix, as GitHubProvider resolves real tags.
        assertEquals(
            "https://github.com/acme/app/releases/download/1.0.0/App-1.0.0.exe",
            SeededInstaller.previousReleaseUrl(
                "https://github.com/acme/app/releases/download/2.0.0/App-2.0.0.exe",
                newVersion = "2.0.0",
                currentVersion = "1.0.0",
            ),
        )
        // A private repository's API URL names the asset by id: there is no previous URL to derive.
        assertNull(
            SeededInstaller.previousReleaseUrl(
                "https://api.github.com/repos/acme/app/releases/assets/123456",
                newVersion = "2.0.0",
                currentVersion = "1.0.0",
            ),
        )
    }

    private fun locate(
        localAppData: File,
        executable: String,
    ): File? =
        SeededInstaller.locate(osName = "Windows 11", localAppData = localAppData.path, executablePath = executable)
}
