package dev.nucleusframework.desktop.application.internal.electronbuilder

import dev.nucleusframework.desktop.application.dsl.JvmApplicationDistributions
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.dsl.WindowsWidgetSize
import dev.nucleusframework.desktop.application.internal.WindowsWidgetsPackaging
import dev.nucleusframework.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * `windows { widgets { } }` must change nothing for an app that declares no widget, and must
 * produce every piece the widgets board needs (and fails silently without) for one that does.
 */
class ElectronBuilderAppXWidgetsConfigTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder.builder().build().objects.newInstance(JvmApplicationDistributions::class.java)

    private fun renderAppX(
        distributions: JvmApplicationDistributions,
        widgets: AppXWidgetFiles? = null,
    ): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateWindowsConfig(
            yaml = yaml,
            distributions = distributions,
            targetFormat = TargetFormat.AppX,
            targetArch = Arch.X64,
            windowsIconOverride = null,
            executableName = "Demo",
            nsisInclude = null,
            appxWidgets = widgets,
        )
        return yaml.toString()
    }

    private fun png(
        name: String,
        width: Int,
        height: Int,
    ): File = temp.newFile(name).also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it) }

    @Test
    fun `no widget leaves the appx config untouched`() {
        val yaml = renderAppX(distributions())

        assertFalse(yaml, yaml.contains("customManifestPath"))
        assertFalse(yaml, yaml.contains("customExtensionsPath"))
    }

    @Test
    fun `widgets stage images, template and extensions`() {
        val distributions = distributions()
        distributions.windows.appx.identityName = "Acme.Demo"
        distributions.windows.widgets.widget("Counter") {
            it.displayName = "Counter & co"
            it.sizes = listOf(WindowsWidgetSize.Small, WindowsWidgetSize.Medium)
            it.icon.set(png("icon.png", 64, 64))
            it.preview.set(png("preview.png", 300, 304))
            it.previewDark.set(png("dark.png", 300, 304))
        }
        val appImage = temp.newFolder("app")
        val staged =
            WindowsWidgetsPackaging.stage(
                widgets = distributions.windows.widgets,
                identity = WindowsWidgetsPackaging.identity(distributions),
                executableName = "Demo",
                appImageDir = appImage,
                workDir = temp.newFolder("work"),
                userExtensions = null,
                warn = { error("unexpected warning: $it") },
            )

        assertTrue(appImage.resolve("widgets/Counter/icon.png").isFile)
        assertTrue(appImage.resolve("widgets/Counter/preview.png").isFile)
        assertTrue(appImage.resolve("widgets/Counter/preview-dark.png").isFile)
        assertTrue(staged.manifestTemplate.readText().contains("Microsoft.WindowsAppRuntime.1.8"))

        val clsid = WindowsWidgetsPackaging.clsid("Acme.Demo").toString().uppercase()
        val xml = staged.extensions.readText()
        for (expected in listOf(
            "<com:ExeServer Executable=\"app\\Demo.exe\" Arguments=\"-RegisterProcessAsComServer\"",
            "<com:Class Id=\"$clsid\"",
            "<CreateInstance ClassId=\"$clsid\" />",
            "PublicFolder=\"assets\"",
            "<Definition Id=\"Counter\" DisplayName=\"Counter &amp; co\"",
            "<Size Name=\"small\" />",
            "<Size Name=\"medium\" />",
            "<Screenshot Path=\"app\\widgets\\Counter\\preview.png\"",
            "<DarkMode><Screenshots><Screenshot Path=\"app\\widgets\\Counter\\preview-dark.png\"",
        )) {
            assertTrue("missing $expected in\n$xml", xml.contains(expected))
        }
        assertFalse(xml, xml.contains("<Size Name=\"large\" />"))

        val yaml = renderAppX(distributions, AppXWidgetFiles(staged.manifestTemplate, staged.extensions))
        assertTrue(yaml, yaml.contains("customManifestPath"))
        assertTrue(yaml, yaml.contains("customExtensionsPath"))
    }

    @Test
    fun `the clsid is stable per identity and distinct across apps`() {
        assertEquals(WindowsWidgetsPackaging.clsid("Acme.Demo"), WindowsWidgetsPackaging.clsid("Acme.Demo"))
        assertNotEquals(WindowsWidgetsPackaging.clsid("Acme.Demo"), WindowsWidgetsPackaging.clsid("Acme.Other"))
    }
}
