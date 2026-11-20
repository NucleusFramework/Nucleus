package dev.nucleusframework.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppImageIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var appDir: File
    private lateinit var appImage: File
    private lateinit var dataHome: File
    private lateinit var systemDataDir: File
    private val ranCommands = mutableListOf<List<String>>()

    @Before
    fun setUp() {
        appDir = tmp.newFolder("appdir")
        // A space in the image path, so Exec quoting is exercised by every test.
        appImage = tmp.newFolder("images").resolve("My App.AppImage").apply { writeText("image") }
        dataHome = tmp.newFolder("data-home")
        systemDataDir = tmp.newFolder("system-share")

        File(appDir, "myapp.desktop").writeText(
            """
            [Desktop Entry]
            Name=My App
            Exec=AppRun --no-sandbox %U
            TryExec=AppRun
            Terminal=false
            Type=Application
            Icon=myapp
            StartupWMClass=com-example-MainKt
            X-AppImage-Version=1.2.3
            Comment=Test app

            [Desktop Action New]
            Name=New Window
            Exec=AppRun --new-window %U
            """.trimIndent() + "\n",
        )
        for (size in listOf("16x16", "512x512")) {
            File(appDir, "usr/share/icons/hicolor/$size/apps/myapp.png").apply {
                parentFile.mkdirs()
                writeText("png-$size")
            }
        }
    }

    private fun integration(env: Map<String, String?> = defaultEnv()): AppImageIntegration =
        AppImageIntegration(getenv = { env[it] }, runCommand = { ranCommands.add(it) })

    private fun defaultEnv(): Map<String, String?> =
        mapOf(
            "APPIMAGE" to appImage.path,
            "APPDIR" to appDir.path,
            "XDG_DATA_HOME" to dataHome.path,
            "XDG_DATA_DIRS" to systemDataDir.path,
        )

    private fun installedEntry(): File = File(dataHome, "applications/myapp.desktop")

    @Test
    fun `status is NotAppImage without the AppImage environment`() {
        assertEquals(AppImageIntegrationStatus.NotAppImage, integration(emptyMap()).status())
    }

    @Test
    fun `status is NotAppImage when the image path is gone`() {
        val env = defaultEnv() + ("APPIMAGE" to appImage.path + ".moved")
        assertEquals(AppImageIntegrationStatus.NotAppImage, integration(env).status())
    }

    @Test
    fun `status is NotIntegrated with a clean data home`() {
        assertEquals(AppImageIntegrationStatus.NotIntegrated, integration().status())
    }

    @Test
    fun `integrate writes the entry with a rewritten Exec, the icons, and the marker`() {
        val result = integration().integrate()

        val target = installedEntry()
        assertEquals(AppImageIntegrationResult.Integrated(target), result)
        val text = target.readText()
        val lines = text.lines()
        assertTrue("Exec must launch the image:\n$text", lines.contains("Exec=\"${appImage.path}\" %U"))
        assertTrue("TryExec must be dropped:\n$text", lines.none { it.startsWith("TryExec=") })
        assertTrue("marker must be present:\n$text", lines.contains("X-Nucleus-AppImage=${appImage.path}"))
        // The marker belongs to the main group, before any action group.
        assertTrue(text.indexOf("X-Nucleus-AppImage=") < text.indexOf("[Desktop Action New]"))
        // The action's Exec launches the image too (its AppRun arguments only worked in-mount).
        assertEquals(2, lines.count { it == "Exec=\"${appImage.path}\" %U" })

        assertEquals("png-16x16", File(dataHome, "icons/hicolor/16x16/apps/myapp.png").readText())
        assertEquals("png-512x512", File(dataHome, "icons/hicolor/512x512/apps/myapp.png").readText())
        assertEquals(listOf(listOf("update-desktop-database", target.parentFile.path)), ranCommands)

        assertEquals(AppImageIntegrationStatus.Integrated(target), integration().status())
    }

    @Test
    fun `a moved image reports Stale and a refresh repairs the entry`() {
        integration().integrate()
        val movedImage = File(appImage.parentFile, "Renamed.AppImage").apply { writeText("image") }
        val movedEnv = defaultEnv() + ("APPIMAGE" to movedImage.path)

        assertEquals(AppImageIntegrationStatus.Stale(installedEntry()), integration(movedEnv).status())

        integration(movedEnv).integrate()
        assertEquals(AppImageIntegrationStatus.Integrated(installedEntry()), integration(movedEnv).status())
        assertTrue(installedEntry().readText().contains("Exec=\"${movedImage.path}\" %U"))
    }

    @Test
    fun `a different image version reports Stale`() {
        integration().integrate()
        val source = File(appDir, "myapp.desktop")
        source.writeText(source.readText().replace("X-AppImage-Version=1.2.3", "X-AppImage-Version=1.2.4"))

        assertEquals(AppImageIntegrationStatus.Stale(installedEntry()), integration().status())
    }

    @Test
    fun `a hand-written entry is ExternallyManaged and never overwritten`() {
        installedEntry().apply {
            parentFile.mkdirs()
            writeText("[Desktop Entry]\nName=My App\nExec=/somewhere/else\n")
        }

        assertEquals(AppImageIntegrationStatus.ExternallyManaged(installedEntry()), integration().status())
        val result = integration().integrate()
        assertTrue(result is AppImageIntegrationResult.Failed)
        assertEquals("/somewhere/else", File(installedEntry().path).readText().lines()[2].substringAfter('='))
    }

    @Test
    fun `an integration tool's entry for the same image is ExternallyManaged`() {
        val foreign =
            File(dataHome, "applications/appimagekit_0123abcd-myapp.desktop").apply {
                parentFile.mkdirs()
                writeText("[Desktop Entry]\nName=My App\nExec=\"${appImage.path}\" %U\n")
            }

        assertEquals(AppImageIntegrationStatus.ExternallyManaged(foreign), integration().status())
        assertTrue(integration().integrate() is AppImageIntegrationResult.Failed)
    }

    @Test
    fun `a system package's entry with this app's StartupWMClass is ExternallyManaged`() {
        val systemEntry =
            File(systemDataDir, "applications/myapp.desktop").apply {
                parentFile.mkdirs()
                writeText(
                    "[Desktop Entry]\nName=My App\nExec=/opt/MyApp/myapp %U\nStartupWMClass=com-example-MainKt\n",
                )
            }

        assertEquals(AppImageIntegrationStatus.ExternallyManaged(systemEntry), integration().status())
    }

    @Test
    fun `a system package's entry under another name with this app's StartupWMClass is ExternallyManaged`() {
        // Nucleus' own deb installs `nucleus-lab.desktop` where the AppImage carries `NucleusLab.desktop`.
        val systemEntry =
            File(systemDataDir, "applications/my-app.desktop").apply {
                parentFile.mkdirs()
                writeText(
                    "[Desktop Entry]\nName=My App\nExec=/opt/my-app/my-app %U\nStartupWMClass=com-example-MainKt\n",
                )
            }

        assertEquals(AppImageIntegrationStatus.ExternallyManaged(systemEntry), integration().status())
        assertTrue(integration().integrate() is AppImageIntegrationResult.Failed)
    }

    @Test
    fun `a system entry under another name for an unrelated app does not block integration`() {
        File(systemDataDir, "applications/other.desktop").apply {
            parentFile.mkdirs()
            writeText("[Desktop Entry]\nName=Other\nExec=/usr/bin/other %U\nStartupWMClass=org-other-MainKt\n")
        }

        assertEquals(AppImageIntegrationStatus.NotIntegrated, integration().status())
    }

    @Test
    fun `a system entry the user hid with a Hidden override does not count`() {
        File(systemDataDir, "applications/my-app.desktop").apply {
            parentFile.mkdirs()
            writeText(
                "[Desktop Entry]\nName=My App\nExec=/opt/my-app/my-app %U\nStartupWMClass=com-example-MainKt\n",
            )
        }
        File(dataHome, "applications/my-app.desktop").apply {
            parentFile.mkdirs()
            writeText("[Desktop Entry]\nName=My App\nStartupWMClass=com-example-MainKt\nHidden=true\n")
        }

        assertEquals(AppImageIntegrationStatus.NotIntegrated, integration().status())
    }

    @Test
    fun `an unrelated same-named system entry does not block integration`() {
        File(systemDataDir, "applications/myapp.desktop").apply {
            parentFile.mkdirs()
            writeText("[Desktop Entry]\nName=Some Other App\nExec=/usr/bin/myapp %U\n")
        }

        assertEquals(AppImageIntegrationStatus.NotIntegrated, integration().status())
    }

    @Test
    fun `a matching system entry shadowed by an unrelated one does not count`() {
        // XDG precedence: only the first existing same-named entry is visible to the desktop.
        File(systemDataDir, "applications/myapp.desktop").apply {
            parentFile.mkdirs()
            writeText("[Desktop Entry]\nName=Some Other App\nExec=/usr/bin/myapp %U\n")
        }
        val shadowedDir = tmp.newFolder("system-share-2")
        File(shadowedDir, "applications/myapp.desktop").apply {
            parentFile.mkdirs()
            writeText(
                "[Desktop Entry]\nName=My App\nExec=/opt/MyApp/myapp %U\nStartupWMClass=com-example-MainKt\n",
            )
        }
        val env = defaultEnv() + ("XDG_DATA_DIRS" to "${systemDataDir.path}:${shadowedDir.path}")

        assertEquals(AppImageIntegrationStatus.NotIntegrated, integration(env).status())
    }

    @Test
    fun `an image without a themed icon tree falls back to an absolute Icon path`() {
        File(appDir, "usr/share/icons/hicolor").deleteRecursively()
        File(appDir, "myapp.png").writeText("root-png")

        integration().integrate()

        val installedIcon = File(dataHome, "icons/myapp.png")
        assertEquals("root-png", installedIcon.readText())
        assertTrue(installedEntry().readText().lines().contains("Icon=${installedIcon.path}"))
    }

    @Test
    fun `the image's own entry on XDG_DATA_DIRS is not a system package`() {
        // AppRun prepends $APPDIR/usr/share to XDG_DATA_DIRS; an entry the image carries there has
        // this app's StartupWMClass by construction, and must not block its own integration.
        File(appDir, "usr/share/applications/myapp.desktop").apply {
            parentFile.mkdirs()
            writeText("[Desktop Entry]\nName=My App\nExec=AppRun %U\nStartupWMClass=com-example-MainKt\n")
        }
        val env = defaultEnv() + ("XDG_DATA_DIRS" to "${File(appDir, "usr/share").path}:${systemDataDir.path}")

        assertEquals(AppImageIntegrationStatus.NotIntegrated, integration(env).status())
    }

    @Test
    fun `exec arguments are quoted per the desktop entry specification`() {
        assertEquals("\"/plain/path\"", quoteExecArgument("/plain/path"))
        assertEquals("\"/with space/app\"", quoteExecArgument("/with space/app"))
        // One literal backslash becomes four: once for quoting, doubled by string escaping.
        assertEquals("\"a\\\\\\\\b\"", quoteExecArgument("a\\b"))
        assertEquals("\"a\\\\\$b\"", quoteExecArgument("a\$b"))
        assertEquals("\"a\\\\\"b\"", quoteExecArgument("a\"b"))
    }
}
